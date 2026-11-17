package com.studydock.app.ui

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.studydock.app.data.*
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The editor's identity survives the first automatic publication of a new note. */
data class NoteEditorState(
    val key: String, val noteId: Long?, val title: String, val body: TextFieldValue,
    val choices: Map<String, Long>, val sequence: Long = 0,
    val status: String = "Salvo", val error: String? = null, val conflict: Boolean = false,
    val loading: Boolean = false, val leaving: Boolean = false, val loadFailed: Boolean = false,
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    val canGoBack: Boolean = false, val canGoForward: Boolean = false,
    val contentSequence: Long = sequence
) {
    /** True while edits exist only locally or in an unpublished draft. */
    val isDirty: Boolean
        get() = status == "Salvando rascunho…" ||
            status == "Rascunho salvo" ||
            status == "Salvando…" ||
            status == "Rascunho recuperado"
}

class NoteEditorController(
    @Volatile private var dao: StudyDao, context: Context, private val scope: CoroutineScope,
    private val selected: (Long?) -> Unit
) {
    fun updateDao(newDao: StudyDao) {
        this.dao = newDao
    }
    private val appContext = context.applicationContext
    // Lazy so StudyViewModel startup never touches SharedPreferences XML on the main thread.
    private val preferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.getSharedPreferences("studydock_note_editor", Context.MODE_PRIVATE)
    }
    private val lock = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow<NoteEditorState?>(null)
    val state = mutableState.asStateFlow()

    @Volatile private var session: Session? = null
    private var lastLoad: Pair<Long?, String?> = null to null
    private var openRequest = 0L
    private var pendingLoadRequest = -1L
    private val pendingLoadEdits = mutableListOf<(NoteEditorState) -> NoteEditorState>()
    private var autoSave: Job? = null
    private var selectionSaveJob: Job? = null

    // Navigation history across visited notes
    private val backStack = ArrayDeque<Long>()
    private val forwardStack = ArrayDeque<Long>()
    private var currentHistoryNoteId: Long? = null

    val canUndo: Boolean get() = mutableState.value?.canUndo == true
    val canRedo: Boolean get() = mutableState.value?.canRedo == true
    val canGoBack: Boolean get() = synchronized(stateLock) { backStack.isNotEmpty() }
    val canGoForward: Boolean get() = synchronized(stateLock) { forwardStack.isNotEmpty() }

    private data class RevisionEntry(val title: String, val body: TextFieldValue)

    private class Session(initial: NoteEditorState, @Volatile var draft: NoteDraft?, @Volatile var base: Note?) {
        val latest = MutableStateFlow(initial)
        var persisted = 0L
        var contentSequence = if (initial.sequence > 0L) initial.sequence else 0L
        var publishedContentSequence = if (draft?.pending == true || initial.sequence > 0L) 0L else initial.sequence
        @Volatile var closed = false
        val undoStack = ArrayDeque<RevisionEntry>()
        val redoStack = ArrayDeque<RevisionEntry>()
        var lastEditAt = 0L
        var pendingBoundary = false
    }

    fun open(noteId: Long?) = load(noteId, null, recordHistory = true)
    fun recover(draftKey: String) = load(null, draftKey, recordHistory = true)
    fun resume() {
        val snapshotRequest: Long
        synchronized(stateLock) {
            if (mutableState.value != null) return
            snapshotRequest = openRequest
            mutableState.value = NoteEditorState(
                "resuming-$snapshotRequest", null, "", TextFieldValue(), emptyMap(),
                loading = true, canGoBack = backStack.isNotEmpty(), canGoForward = forwardStack.isNotEmpty()
            )
        }
        scope.launch {
            val (noteId, draftKey) = withContext(Dispatchers.IO) {
                val key = preferences.getString("draft_key", null)
                val id = preferences.getLong("note_id", 0).takeIf { it > 0 }
                id to key
            }
            val stillValid = synchronized(stateLock) { openRequest == snapshotRequest }
            if (stillValid) {
                load(noteId, draftKey, fallback = true, recordHistory = false)
            }
        }
    }
    fun retry() = load(lastLoad.first, lastLoad.second, recordHistory = false)

    /**
     * Atomically initializes a new note with pre-populated content (used by templates and daily notes)
     * without waiting for an asynchronous empty-draft load that would otherwise discard immediate edits.
     */
    fun openWithContent(initialTitle: String, initialMarkdown: String) {
        val request: Long
        val previous: Session?
        val created: Session
        val hasInitialContent = initialTitle.isNotBlank() || initialMarkdown.isNotBlank()

        synchronized(stateLock) {
            request = ++openRequest
            pendingLoadRequest = -1L
            pendingLoadEdits.clear()
            autoSave?.cancel()
            selectionSaveJob?.cancel()
            previous = session

            val key = UUID.randomUUID().toString()
            val body = TextFieldValue(initialMarkdown, TextRange(initialMarkdown.length))
            if (currentHistoryNoteId != null) {
                pushBackHistoryLocked(currentHistoryNoteId!!)
                forwardStack.clear()
                currentHistoryNoteId = null
            }
            val initialState = NoteEditorState(
                key = key,
                noteId = null,
                title = initialTitle,
                body = body,
                choices = emptyMap(),
                sequence = if (hasInitialContent) 1L else 0L,
                status = if (hasInitialContent) "Salvando rascunho…" else "Nova nota",
                loading = false,
                canUndo = false,
                canRedo = false,
                canGoBack = backStack.isNotEmpty(),
                canGoForward = forwardStack.isNotEmpty()
            )
            created = Session(initialState, null, null).apply {
                contentSequence = if (hasInitialContent) 1L else 0L
                publishedContentSequence = 0L
            }
            session = created
            mutableState.value = initialState
        }

        lastLoad = null to created.latest.value.key
        selected(null)

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            durable {
                preferences.edit().putString("draft_key", created.latest.value.key).putLong("note_id", 0L).apply()
                lock.withLock {
                    if (previous != null && !previous.closed) {
                        persist(previous, publish = true)
                        previous.closed = true
                    }
                    if (request == openRequest && session === created && !created.closed) {
                        persist(created, publish = false)
                    }
                }
            }
        }
        if (hasInitialContent) {
            scheduleAutoPublish(created)
        }
    }

    private fun load(
        noteId: Long?,
        draftKey: String?,
        fallback: Boolean = false,
        ignoreDraft: Boolean = false,
        recordHistory: Boolean = true
    ) {
        lastLoad = noteId to draftKey
        val request: Long
        val previous: Session?
        synchronized(stateLock) {
            request = ++openRequest
            pendingLoadRequest = request
            pendingLoadEdits.clear()
            previous = session
            autoSave?.cancel()
            selectionSaveJob?.cancel()
            val cur = mutableState.value
            mutableState.value = cur?.copy(loading = true) ?: NoteEditorState(
                "loading-$request", noteId, "", TextFieldValue(), emptyMap(), loading = true,
                canGoBack = backStack.isNotEmpty(), canGoForward = forwardStack.isNotEmpty()
            )
        }

        scope.launch {
            try {
                val loaded = durable {
                    lock.withLock {
                        if (previous != null && !previous.closed) {
                            val ok = persist(previous, publish = true)
                            if (!ok && (previous.persisted != previous.latest.value.sequence || !previous.latest.value.conflict)) {
                                error("Guarde uma cópia do rascunho antes de abrir outra nota.")
                            }
                        }
                        var draft = if (ignoreDraft) null else draftKey?.let { dao.noteDraft(it) } ?: noteId?.let { dao.draftForNote(it) }
                        if (fallback && draft == null && noteId == null) draft = dao.latestNoteDraft()
                        val sourceId = draft?.noteId ?: noteId
                        val source = sourceId?.let { dao.findNote(it) }
                        if (sourceId != null && source == null) {
                            throw NoteConflict("A nota não está disponível. Seu rascunho pode ser aberto na lista de rascunhos.")
                        }
                        if (draft != null && !draft.pending && draft.baseRevision != source?.revision) draft = null
                        val choices = draft?.let { NoteReferences.bindings(it.bindings) }
                            ?: source?.let { dao.linksFromNote(it.id).associate { row -> row.reference to row.targetId } }.orEmpty()
                        val text = draft?.markdown ?: source?.markdown.orEmpty()
                        val key = draft?.key ?: UUID.randomUUID().toString()
                        val conflict = source?.trashedAt != null || draft?.let { it.pending && it.baseRevision != source?.revision } == true
                        val editor = NoteEditorState(
                            key = key,
                            noteId = source?.id,
                            title = draft?.title ?: source?.title.orEmpty(),
                            body = TextFieldValue(
                                text,
                                TextRange(
                                    (draft?.selectionStart ?: text.length).coerceIn(0, text.length),
                                    (draft?.selectionEnd ?: text.length).coerceIn(0, text.length)
                                )
                            ),
                            choices = choices,
                            status = if (draft?.pending == true) "Rascunho recuperado" else if (source == null) "Nova nota" else "Salvo",
                            conflict = conflict,
                            error = when {
                                source?.trashedAt != null -> "Esta nota está na lixeira. Restaure-a na lista de notas ou salve uma cópia."
                                conflict -> "A nota salva mudou. Seu rascunho foi preservado. Escolha como continuar."
                                else -> null
                            }
                        )
                        Session(editor, draft, source)
                    }
                }

                var hasQueuedEdits = false
                synchronized(stateLock) {
                    if (request != openRequest) return@launch
                    previous?.closed = true
                    val targetNoteId = loaded.base?.id
                    if (recordHistory && targetNoteId != currentHistoryNoteId) {
                        currentHistoryNoteId?.let { pushBackHistoryLocked(it) }
                        forwardStack.clear()
                        currentHistoryNoteId = targetNoteId
                    } else if (targetNoteId != null) {
                        currentHistoryNoteId = targetNoteId
                    }

                    var finalState = loaded.latest.value.copy(
                        canGoBack = backStack.isNotEmpty(),
                        canGoForward = forwardStack.isNotEmpty()
                    )

                    if (pendingLoadRequest == request && pendingLoadEdits.isNotEmpty()) {
                        val wasEmptyNew = finalState.noteId == null && finalState.title.isBlank() && finalState.body.text.isBlank()
                        for (op in pendingLoadEdits) {
                            val next = op(finalState)
                            if (next.title != finalState.title || next.body.text != finalState.body.text || next.choices != finalState.choices) {
                                if (!wasEmptyNew) {
                                    loaded.undoStack.addLast(RevisionEntry(finalState.title, finalState.body))
                                }
                                loaded.contentSequence++
                                finalState = next.copy(
                                    sequence = finalState.sequence + 1,
                                    contentSequence = loaded.contentSequence,
                                    status = "Salvando rascunho…",
                                    canUndo = loaded.undoStack.isNotEmpty(),
                                    canRedo = false
                                )
                                hasQueuedEdits = true
                            } else if (next.body.selection != finalState.body.selection) {
                                finalState = next.copy(sequence = finalState.sequence + 1)
                            }
                        }
                        pendingLoadEdits.clear()
                    }
                    pendingLoadRequest = -1L
                    loaded.latest.value = finalState
                    session = loaded
                    mutableState.value = finalState
                }

                selected(loaded.base?.id)
                preferences.edit().putString("draft_key", loaded.latest.value.key).putLong("note_id", loaded.base?.id ?: 0).apply()

                if (hasQueuedEdits) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        durable { lock.withLock { persist(loaded, publish = false) } }
                    }
                    scheduleAutoPublish(loaded)
                } else if (loaded.draft?.pending == true && !loaded.latest.value.conflict) {
                    scheduleAutoPublish(loaded)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(stateLock) {
                    if (request != openRequest) return@launch
                    pendingLoadRequest = -1L
                    pendingLoadEdits.clear()
                    val previousState = previous?.latest?.value
                    selected(previousState?.noteId)
                    mutableState.value = previousState?.copy(
                        loading = false,
                        error = e.message ?: "Não foi possível abrir a nota.",
                        canGoBack = backStack.isNotEmpty(),
                        canGoForward = forwardStack.isNotEmpty()
                    ) ?: NoteEditorState(
                        UUID.randomUUID().toString(), noteId, "", TextFieldValue(), emptyMap(),
                        status = "Nota indisponível", error = e.message ?: "Não foi possível abrir a nota.",
                        loadFailed = true,
                        canGoBack = backStack.isNotEmpty(),
                        canGoForward = forwardStack.isNotEmpty()
                    )
                }
            }
        }
    }

    fun title(value: String) = edit { it.copy(title = value) }

    fun body(value: TextFieldValue) {
        var preExpandedLiteral: TextFieldValue? = null
        edit { old ->
            val expanded = if (value.selection.collapsed && value.text.length > old.body.text.length) {
                LatexSnippetExpander.tryExpandPostfix(value.text, value.selection.start)
            } else null
            val nextBody = if (expanded != null) {
                preExpandedLiteral = value
                value.copy(text = expanded.first, selection = TextRange(expanded.second))
            } else value
            old.copy(body = nextBody)
        }
        preExpandedLiteral?.let { literal ->
            synchronized(stateLock) {
                val s = session ?: return@synchronized
                s.undoStack.addLast(RevisionEntry(s.latest.value.title, literal))
                if (s.undoStack.size > 120) s.undoStack.removeFirst()
            }
        }
    }

    fun bind(reference: String, targetId: Long) = edit { it.copy(choices = it.choices + (reference to targetId)) }

    private fun edit(change: (NoteEditorState) -> NoteEditorState) {
        var targetSession: Session? = null
        var contentChanged = false
        var selectionOnlyChanged = false

        synchronized(stateLock) {
            val stateVal = mutableState.value
            if (stateVal?.loading == true && pendingLoadRequest == openRequest) {
                pendingLoadEdits.add(change)
                return
            }
            val current = session ?: return
            if (current.closed || current.latest.value.leaving || stateVal?.loading == true) return

            val old = current.latest.value
            val changed = change(old)
            val titleChanged = changed.title != old.title
            val textChanged = changed.body.text != old.body.text
            val choicesChanged = changed.choices != old.choices
            val selectionChanged = changed.body.selection != old.body.selection

            if (!titleChanged && !textChanged && !choicesChanged && !selectionChanged) return

            if (titleChanged || textChanged || choicesChanged) {
                contentChanged = true
                if (titleChanged || textChanged) {
                    val now = System.currentTimeMillis()
                    val deltaLen = changed.body.text.length - old.body.text.length
                    val insertedBoundary = if (deltaLen == 1) hasInsertedBoundary(old.body.text, changed.body.text) else null
                    val shouldPush = current.undoStack.isEmpty() ||
                        titleChanged ||
                        abs(deltaLen) > 1 ||
                        now - current.lastEditAt >= 450L ||
                        current.pendingBoundary ||
                        insertedBoundary == '\n'

                    if (shouldPush) {
                        current.undoStack.addLast(RevisionEntry(old.title, old.body))
                        if (current.undoStack.size > 120) current.undoStack.removeFirst()
                    }
                    current.pendingBoundary = insertedBoundary == ' ' || insertedBoundary == '\t'
                    current.lastEditAt = now
                    current.redoStack.clear()
                }
                current.contentSequence++
                val next = changed.copy(
                    sequence = old.sequence + 1,
                    contentSequence = current.contentSequence,
                    status = "Salvando rascunho…",
                    error = if (old.conflict) old.error else null,
                    canUndo = current.undoStack.isNotEmpty(),
                    canRedo = current.redoStack.isNotEmpty()
                )
                current.latest.value = next
                mutableState.value = next
                targetSession = current
            } else {
                // Selection-only update: advance sequence and preserve status, persist selection quietly
                selectionOnlyChanged = true
                val next = changed.copy(sequence = old.sequence + 1)
                current.latest.value = next
                mutableState.value = next
                targetSession = current
            }
        }

        val current = targetSession ?: return
        if (contentChanged) {
            selectionSaveJob?.cancel()
            // Enter the durable write before this event returns. It can finish during Activity teardown.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                durable { lock.withLock { persist(current, publish = false) } }
            }
            scheduleAutoPublish(current)
        } else if (selectionOnlyChanged && (current.draft != null || current.base != null)) {
            selectionSaveJob?.cancel()
            selectionSaveJob = scope.launch {
                delay(180)
                durable { lock.withLock { persist(current, publish = false) } }
            }
        }
    }

    fun undo() {
        val current: Session
        synchronized(stateLock) {
            val s = session ?: return
            if (s.closed || s.latest.value.leaving || mutableState.value?.loading == true) return
            if (s.undoStack.isEmpty()) return
            val old = s.latest.value
            val prev = s.undoStack.removeLast()
            s.redoStack.addLast(RevisionEntry(old.title, old.body))
            if (s.redoStack.size > 120) s.redoStack.removeFirst()
            s.pendingBoundary = true
            s.contentSequence++
            val next = old.copy(
                title = prev.title,
                body = prev.body,
                sequence = old.sequence + 1,
                contentSequence = s.contentSequence,
                status = "Salvando rascunho…",
                error = if (old.conflict) old.error else null,
                canUndo = s.undoStack.isNotEmpty(),
                canRedo = s.redoStack.isNotEmpty()
            )
            s.latest.value = next
            mutableState.value = next
            current = s
        }
        selectionSaveJob?.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            durable { lock.withLock { persist(current, publish = false) } }
        }
        scheduleAutoPublish(current)
    }

    fun redo() {
        val current: Session
        synchronized(stateLock) {
            val s = session ?: return
            if (s.closed || s.latest.value.leaving || mutableState.value?.loading == true) return
            if (s.redoStack.isEmpty()) return
            val old = s.latest.value
            val restored = s.redoStack.removeLast()
            s.undoStack.addLast(RevisionEntry(old.title, old.body))
            if (s.undoStack.size > 120) s.undoStack.removeFirst()
            s.pendingBoundary = true
            s.contentSequence++
            val next = old.copy(
                title = restored.title,
                body = restored.body,
                sequence = old.sequence + 1,
                contentSequence = s.contentSequence,
                status = "Salvando rascunho…",
                error = if (old.conflict) old.error else null,
                canUndo = s.undoStack.isNotEmpty(),
                canRedo = s.redoStack.isNotEmpty()
            )
            s.latest.value = next
            mutableState.value = next
            current = s
        }
        selectionSaveJob?.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            durable { lock.withLock { persist(current, publish = false) } }
        }
        scheduleAutoPublish(current)
    }

    fun goBack() {
        val hasBack = synchronized(stateLock) { backStack.isNotEmpty() }
        if (!hasBack) return
        flush {
            val targetId = synchronized(stateLock) {
                if (backStack.isEmpty()) null else {
                    val id = backStack.removeLast()
                    currentHistoryNoteId?.let {
                        if (forwardStack.lastOrNull() != it) forwardStack.addLast(it)
                    }
                    currentHistoryNoteId = id
                    id
                }
            } ?: return@flush
            load(targetId, null, recordHistory = false)
        }
    }

    fun goForward() {
        val hasForward = synchronized(stateLock) { forwardStack.isNotEmpty() }
        if (!hasForward) return
        flush {
            val targetId = synchronized(stateLock) {
                if (forwardStack.isEmpty()) null else {
                    val id = forwardStack.removeLast()
                    currentHistoryNoteId?.let { pushBackHistoryLocked(it) }
                    currentHistoryNoteId = id
                    id
                }
            } ?: return@flush
            load(targetId, null, recordHistory = false)
        }
    }

    /**
     * Rebases an open note in-place when background flows (tasks, diary promotion, vault import)
     * modify the stored Note row, avoiding spurious conflicts or stale overwrites.
     */
    fun rebaseIfOpen(noteId: Long, newRevision: Long, newTitle: String, newMarkdown: String) {
        val current = session ?: return
        if (current.closed) return
        val matchesOpen = synchronized(stateLock) {
            current.base?.id == noteId || current.latest.value.noteId == noteId
        }
        if (!matchesOpen) return

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            durable {
                lock.withLock {
                    if (session !== current || current.closed) return@withLock
                    val latestBefore = synchronized(stateLock) { current.latest.value }
                    val stored = dao.findNote(noteId)
                    val effectiveRevision = maxOf(newRevision, stored?.revision ?: newRevision)
                    val refreshedLinks = dao.linksFromNote(noteId).associate { it.reference to it.targetId }
                    val dbDraft = dao.noteDraft(latestBefore.key)
                    val previousBase = current.base

                    current.base = (stored ?: previousBase)?.copy(
                        id = noteId,
                        title = newTitle,
                        markdown = newMarkdown,
                        revision = effectiveRevision,
                        updatedAt = System.currentTimeMillis()
                    )
                    if (dbDraft != null) {
                        current.draft = dbDraft
                    } else if (current.draft != null) {
                        current.draft = current.draft?.copy(baseRevision = effectiveRevision)
                    }

                    synchronized(stateLock) {
                        val latest = current.latest.value
                        val hasPendingLocalContent = latest.contentSequence > current.publishedContentSequence &&
                            (latest.title != (previousBase?.title ?: newTitle) ||
                                latest.body.text != (previousBase?.markdown ?: newMarkdown))

                        if (!hasPendingLocalContent && latest.sequence == latestBefore.sequence) {
                            val wasAtEnd = latest.body.selection.end >= latest.body.text.length
                            val selStart = if (wasAtEnd) newMarkdown.length else latest.body.selection.start.coerceIn(0, newMarkdown.length)
                            val selEnd = if (wasAtEnd) newMarkdown.length else latest.body.selection.end.coerceIn(0, newMarkdown.length)
                            val next = latest.copy(
                                noteId = noteId,
                                title = newTitle,
                                body = TextFieldValue(newMarkdown, TextRange(selStart, selEnd)),
                                choices = refreshedLinks,
                                sequence = latest.sequence + 1,
                                contentSequence = current.contentSequence,
                                status = "Salvo",
                                conflict = false,
                                error = null
                            )
                            current.persisted = maxOf(current.persisted, next.sequence)
                            current.publishedContentSequence = current.contentSequence
                            current.latest.value = next
                            if (session === current) {
                                mutableState.value = next.copy(loading = mutableState.value?.loading == true)
                            }
                        } else if (latest.conflict) {
                            val next = latest.copy(conflict = false, error = null)
                            current.latest.value = next
                            if (session === current) {
                                mutableState.value = next.copy(loading = mutableState.value?.loading == true)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pushBackHistoryLocked(id: Long) {
        if (backStack.lastOrNull() != id) {
            backStack.addLast(id)
            if (backStack.size > 50) backStack.removeFirst()
        }
    }

    private fun hasInsertedBoundary(oldText: String, newText: String): Char? {
        if (newText.length != oldText.length + 1) return null
        var i = 0
        while (i < oldText.length && oldText[i] == newText[i]) i++
        val inserted = newText[i]
        return if (inserted == ' ' || inserted == '\n' || inserted == '\t') inserted else null
    }

    private fun scheduleAutoPublish(target: Session) {
        autoSave?.cancel()
        autoSave = scope.launch {
            delay(900)
            durable { lock.withLock { persist(target, publish = true) } }
        }
    }

    private suspend fun <T> durable(block: suspend () -> T): T = withContext(NonCancellable + Dispatchers.IO) { block() }

    private suspend fun persist(current: Session, publish: Boolean): Boolean {
        val plan = synchronized(stateLock) {
            if (current.closed) null
            else {
                val snap = current.latest.value
                val dirty = snap.sequence > current.persisted
                val hasUnpublishedContent = snap.contentSequence > current.publishedContentSequence ||
                    current.draft?.pending == true
                Triple(
                    snap,
                    dirty,
                    publish && !snap.conflict && snap.title.isNotBlank() && hasUnpublishedContent
                )
            }
        } ?: return true
        val (snapshot, shouldStoreDraft, shouldPublish) = plan
        if (snapshot.sequence == 0L && current.draft == null) return true

        try {
            if (shouldStoreDraft) {
                val emptyNew = snapshot.noteId == null && snapshot.title.isBlank() && snapshot.body.text.isBlank()
                if (!emptyNew) {
                    val isPending = synchronized(stateLock) {
                        current.draft?.pending == true || snapshot.contentSequence > current.publishedContentSequence
                    }
                    if (isPending || current.draft != null || current.base != null) {
                        val old = current.draft
                        val draft = NoteDraft(
                            key = snapshot.key,
                            noteId = current.base?.id,
                            baseRevision = current.base?.revision,
                            title = snapshot.title,
                            markdown = snapshot.body.text,
                            bindings = NoteReferences.encode(snapshot.choices),
                            selectionStart = snapshot.body.selection.start,
                            selectionEnd = snapshot.body.selection.end,
                            revision = old?.revision ?: 0,
                            pending = isPending
                        )
                        current.draft = dao.storeNoteDraft(draft, old?.revision)
                        synchronized(stateLock) {
                            current.persisted = maxOf(current.persisted, snapshot.sequence)
                        }
                    }
                } else {
                    return true
                }
            }

            if (shouldPublish && current.draft?.pending == true) {
                val result = dao.publishDraft(snapshot.key)
                current.draft = result.draft
                current.base = result.note

                var emitSelectionId: Long? = null
                synchronized(stateLock) {
                    current.publishedContentSequence = maxOf(current.publishedContentSequence, snapshot.contentSequence)
                    if (currentHistoryNoteId == null) {
                        currentHistoryNoteId = result.note.id
                    }
                    val latest = current.latest.value
                    val next = if (latest.sequence == snapshot.sequence) {
                        latest.copy(
                            noteId = result.note.id,
                            body = if (latest.body.text == result.note.markdown) {
                                latest.body
                            } else {
                                TextFieldValue(
                                    result.note.markdown,
                                    TextRange(
                                        if (latest.body.selection.end == latest.body.text.length) result.note.markdown.length
                                        else latest.body.selection.end.coerceAtMost(result.note.markdown.length)
                                    )
                                )
                            },
                            choices = NoteReferences.bindings(result.draft.bindings),
                            status = "Salvo",
                            error = null,
                            conflict = false,
                            canGoBack = backStack.isNotEmpty(),
                            canGoForward = forwardStack.isNotEmpty()
                        )
                    } else if (latest.contentSequence == snapshot.contentSequence) {
                        // Only cursor moved while publish was running: preserve latest selection & mark saved
                        latest.copy(
                            noteId = result.note.id,
                            status = "Salvo",
                            error = null,
                            conflict = false,
                            canGoBack = backStack.isNotEmpty(),
                            canGoForward = forwardStack.isNotEmpty()
                        )
                    } else {
                        // Newer content edits arrived while publishing: never overwrite newer text/status!
                        latest.copy(noteId = result.note.id)
                    }
                    current.latest.value = next
                    if (session === current) {
                        mutableState.value = next.copy(loading = mutableState.value?.loading == true)
                        emitSelectionId = result.note.id
                    }
                }
                if (emitSelectionId != null) {
                    selected(emitSelectionId)
                    preferences.edit().putString("draft_key", snapshot.key).putLong("note_id", emitSelectionId!!).apply()
                }
            } else {
                synchronized(stateLock) {
                    val latest = current.latest.value
                    if (latest.sequence == snapshot.sequence) {
                        val nextStatus = if (current.draft?.pending == false || latest.contentSequence <= current.publishedContentSequence) {
                            "Salvo"
                        } else {
                            "Rascunho salvo"
                        }
                        val next = latest.copy(status = nextStatus)
                        current.latest.value = next
                        if (session === current) {
                            mutableState.value = next.copy(loading = mutableState.value?.loading == true)
                        }
                    }
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val conflict = e is NoteConflict || e is DraftConflict
            update(current) {
                it.copy(
                    status = "Requer atenção",
                    conflict = it.conflict || conflict,
                    error = e.message ?: "Não foi possível salvar. Mantenha esta nota aberta e tente novamente.",
                    leaving = false
                )
            }
            return false
        }
    }

    private fun update(current: Session, change: (NoteEditorState) -> NoteEditorState) {
        synchronized(stateLock) {
            val next = change(current.latest.value)
            current.latest.value = next
            if (session === current) {
                mutableState.value = next.copy(loading = mutableState.value?.loading == true)
            }
        }
    }

    fun flush(after: (() -> Unit)? = null) {
        val current = session
        if (current == null) {
            after?.invoke()
            return
        }
        autoSave?.cancel()
        selectionSaveJob?.cancel()
        if (after != null) update(current) { it.copy(leaving = true) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val saved = durable { lock.withLock { persist(current, publish = true) } }
            if (session !== current) return@launch
            update(current) { it.copy(leaving = false) }
            val readyToLeave = synchronized(stateLock) {
                saved || (current.persisted == current.latest.value.sequence && current.latest.value.conflict)
            }
            if (readyToLeave) after?.invoke()
        }
    }

    fun reload() {
        val current = session ?: return
        autoSave?.cancel()
        selectionSaveJob?.cancel()
        scope.launch {
            val id = current.base?.id
            durable {
                lock.withLock {
                    current.closed = true
                    current.draft?.let { dao.discardNoteDraft(it.key, it.revision) }
                }
            }
            synchronized(stateLock) { session = null }
            load(id, null, ignoreDraft = true, recordHistory = false)
        }
    }

    fun saveCopy() {
        val original = session ?: return
        autoSave?.cancel()
        selectionSaveJob?.cancel()
        val copy: Session
        val nextState: NoteEditorState
        synchronized(stateLock) {
            val value = original.latest.value
            original.closed = true
            nextState = value.copy(
                key = UUID.randomUUID().toString(),
                noteId = null,
                title = value.title.ifBlank { original.base?.title ?: "Nota" } + " — cópia",
                sequence = 1,
                contentSequence = 1L,
                conflict = false,
                error = null,
                leaving = false,
                status = "Salvando…",
                canUndo = false,
                canRedo = false,
                canGoBack = backStack.isNotEmpty(),
                canGoForward = forwardStack.isNotEmpty()
            )
            copy = Session(nextState, null, null).apply {
                contentSequence = 1L
                publishedContentSequence = 0L
            }
            session = copy
            mutableState.value = nextState
        }
        selected(null)
        scope.launch(Dispatchers.IO) {
            preferences.edit().putString("draft_key", nextState.key).putLong("note_id", 0).apply()
        }
        flush()
    }

    fun trash(after: () -> Unit) {
        val current = session ?: return
        autoSave?.cancel()
        selectionSaveJob?.cancel()
        update(current) { it.copy(leaving = true) }
        scope.launch {
            val saved = durable {
                lock.withLock {
                    if (!persist(current, publish = true)) return@withLock false
                    val note = current.base ?: return@withLock false
                    try {
                        dao.trashNote(note.id, note.revision)
                        current.closed = true
                        synchronized(stateLock) {
                            backStack.removeAll { it == note.id }
                            forwardStack.removeAll { it == note.id }
                            if (currentHistoryNoteId == note.id) currentHistoryNoteId = null
                        }
                        true
                    } catch (e: Exception) {
                        update(current) { it.copy(error = e.message ?: "Não foi possível mover a nota para a lixeira.", leaving = false) }
                        false
                    }
                }
            }
            if (saved && session === current) {
                selected(null)
                after()
            }
        }
    }

    fun openTarget(target: Note, reference: String) {
        bind(reference, target.id)
        flush { open(target.id) }
    }

    fun restoreTarget(target: Note, reference: String) {
        val current = session ?: return
        scope.launch {
            try {
                durable { lock.withLock { dao.restoreNote(target.id) } }
                if (session === current) openTarget(target.copy(trashedAt = null), reference)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(current) { it.copy(error = "Não foi possível restaurar a nota vinculada.") }
            }
        }
    }

    fun createLinked(title: String, reference: String) {
        val current = session ?: return
        scope.launch {
            try {
                val target = durable {
                    lock.withLock {
                        val matches = dao.activeNotesByTitle(title)
                        require(matches.size <= 1) { "Há mais de uma nota com esse título. Escolha o destino na lista." }
                        matches.singleOrNull() ?: Note(id = dao.insertNoteRow(Note(title = title, markdown = "")), title = title, markdown = "")
                    }
                }
                if (session === current) openTarget(target, reference)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(current) { it.copy(error = e.message ?: "Não foi possível criar a nota vinculada.") }
            }
        }
    }
}

/** Expansor instantâneo de snippets LaTeX (estilo Gilles Castel) para edição rápida no mobile. */
object LatexSnippetExpander {
    fun tryExpandPostfix(text: String, cursor: Int): Pair<String, Int>? {
        if (cursor <= 0 || cursor > text.length) return null
        val before = text.substring(0, cursor)
        val after = text.substring(cursor)
        val rules = listOf(
            "mk" to ("$$" to -1),
            "dm" to ("$$\n\n$$" to -3),
            "sq" to ("\\sqrt{}" to -1),
            ">=" to ("\\geq " to 0),
            "<=" to ("\\leq " to 0),
            "inf" to ("\\infty" to 0),
            "->" to ("\\to " to 0)
        )
        for ((trigger, replPair) in rules) {
            if (before.endsWith(trigger)) {
                val prefixOk = before.length == trigger.length || !before[before.length - trigger.length - 1].isLetterOrDigit()
                if (prefixOk) {
                    val (replacement, cursorOffset) = replPair
                    val newBefore = before.dropLast(trigger.length) + replacement
                    val newCursor = (newBefore.length + cursorOffset).coerceIn(0, newBefore.length)
                    return (newBefore + after) to newCursor
                }
            }
        }
        // Postfix fraction: `a/` or `x//` -> `\frac{a}{}`
        val fracMatch = Regex("([A-Za-z0-9_]+)\\/\\/$").find(before)
        if (fracMatch != null) {
            val num = fracMatch.groupValues[1]
            val repl = "\\frac{$num}{}"
            val newBefore = before.dropLast(fracMatch.value.length) + repl
            return (newBefore + after) to (newBefore.length - 1)
        }
        return null
    }
}
