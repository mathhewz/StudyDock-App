@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.studydock.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

private val dailyNoteTitleRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")

private val isoDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val shortDatePtFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale("pt", "BR"))
private val draftDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM · HH:mm")


private data class SlashCommand(
    val command: String,
    val label: String,
    val description: String,
    val snippet: String,
    val cursorBackOffset: Int = 0,
    val isTemplatePicker: Boolean = false
)

private val editorSlashCommands = listOf(
    SlashCommand("/callout", "Callout dobrável", "Bloco > [!info]+", "> [!info]+ Título\n> ", 0),
    SlashCommand("/tabela", "Tabela Markdown", "Grade com cabeçalho", "| Coluna 1 | Coluna 2 |\n| --- | --- |\n| Item 1 | Item 2 |\n", 0),
    SlashCommand("/matematica", "Bloco LaTeX", "Fórmula $$...$$", "$$\n\n$$\n", 4),
    SlashCommand("/codigo", "Bloco de código", "Trecho ```kotlin", "```kotlin\n\n```\n", 5),
    SlashCommand("/query", "Query Dataview", "Consulta ao vivo", "```query\nFROM #tag\nSORT updated DESC\nLIMIT 10\n```\n", 0),
    SlashCommand("/tarefa", "Lista de tarefa", "Checkbox - [ ]", "- [ ] ", 0),
    SlashCommand("/template", "Inserir modelo", "Modelos do Vault e padrão", "", 0, isTemplatePicker = true),
    SlashCommand("/hr", "Linha divisória", "Separador ---", "\n---\n", 0),
    SlashCommand("/h2", "Subtítulo H2", "Cabeçalho ##", "## ", 0)
)

private sealed interface AutocompleteSuggestion {
    data class WikiNote(val note: Note, val viaAlias: String?, val folder: String? = null) : AutocompleteSuggestion
    data class WikiHeading(val targetTitle: String?, val heading: MarkdownHeading) : AutocompleteSuggestion
    data class TagOption(val tag: String) : AutocompleteSuggestion
    data class SlashOption(val cmd: SlashCommand) : AutocompleteSuggestion
}

private data class AutocompleteState(
    val replaceStart: Int,
    val replaceEnd: Int,
    val header: String,
    val items: List<AutocompleteSuggestion>
)

private data class VaultNoteMetaCache(
    val tags: List<String>,
    val aliasesById: Map<Long, List<String>>,
    val foldersById: Map<Long, String?>
)

private data class EditorContextData(
    val index: NoteLinkIndex,
    val backlinks: List<BacklinkOccurrence>,
    val unlinkedMentions: List<UnlinkedMention>
)

private fun countWordsFast(markdown: String): Int {
    val bodyOnly = NoteFrontmatter.body(markdown)
    var count = 0
    var inWord = false
    for (ch in bodyOnly) {
        if (ch.isWhitespace()) {
            inWord = false
        } else if (!inWord) {
            inWord = true
            count++
        }
    }
    return count
}

@Composable
fun NoteEditorPlus(
    state: NoteEditorState?, controller: NoteEditorController, note: Note?, notes: List<Note>, trash: List<Note>,
    links: List<NoteLink>, tasks: List<StudyTask>, decks: List<CardDeck>, subjects: List<Subject>, suggestedSubject: Long?,
    onCard: (Long, String, String, Long?, Long?) -> Unit, onBack: () -> Unit, onDeleted: () -> Unit,
    onTogglePin: () -> Unit, onToggleTask: (StudyTask) -> Unit, onAddTask: (String) -> Unit,
    flashcards: List<Flashcard> = emptyList(),
    sessions: List<StudySession> = emptyList(),
    onReviewCards: ((List<Flashcard>) -> Unit)? = null,
    onStartStudySession: ((Long, String, Long?) -> Unit)? = null,
    onLinkMention: (suspend (Long, Int, Int, Long, String) -> Boolean)? = null,
    onStartTask: ((StudyTask) -> Unit)? = null,
    onSaveTask: ((StudyTask) -> Unit)? = null,
    snapshots: List<NoteSnapshot> = emptyList(),
    onRestoreSnapshot: (suspend (Long) -> Unit)? = null,
    onNavigateNote: ((Long) -> Unit)? = null,
    onCreateAndOpenNote: (suspend (String) -> Long)? = null,
    canNavigateBack: Boolean = false,
    canNavigateForward: Boolean = false,
    onNavigateBack: (() -> Unit)? = null,
    onNavigateForward: (() -> Unit)? = null
) {
    if (state == null || state.loading) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Abrindo nota…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (state.loadFailed) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp)
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.error ?: "Não foi possível abrir a nota.", color = MaterialTheme.colorScheme.error)
                Button(
                    controller::retry,
                    shape = studyControlShape(),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Tentar novamente") }
                TextButton(onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Voltar") }
            }
        }
        return
    }

    var viewMode by rememberSaveable { mutableStateOf(NoteViewMode.EDIT) }
    var focusMode by rememberSaveable { mutableStateOf(false) }

    key(state.key) {
        val context = LocalContext.current
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current

        DisposableEffect(owner, state.key) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) controller.flush()
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer); controller.flush() }
        }

        BackHandler {
            if (!state.leaving) {
                if (focusMode) focusMode = false else onBack()
            }
        }

        val title = state.title
        val body = state.body
        val noteId = state.noteId
        val currentNote = notes.firstOrNull { it.id == noteId } ?: note?.takeIf { it.id == noteId }

        var references by remember(state.key) {
            mutableStateOf(NoteReferences.parse(body.text).distinctBy { it.raw })
        }
        var outline by remember(state.key) {
            mutableStateOf(extractOutline(body.text))
        }
        var properties by remember(state.key) {
            mutableStateOf(NoteFrontmatter.properties(body.text))
        }
        LaunchedEffect(body.text) {
            val snapshotText = body.text
            if (snapshotText.length > 2_000) delay(75)
            val (parsedRefs, parsedOutline, parsedProps) = withContext(Dispatchers.Default) {
                Triple(
                    NoteReferences.parse(snapshotText).distinctBy { it.raw },
                    extractOutline(snapshotText),
                    NoteFrontmatter.properties(snapshotText)
                )
            }
            references = parsedRefs
            outline = parsedOutline
            properties = parsedProps
        }
        val noteSnapshots = remember(snapshots, noteId) {
            if (noteId == null) emptyList()
            else snapshots.filter { it.noteId == noteId }.sortedByDescending { it.savedAt }
        }

        // Offload link index, backlinks, and unlinked mention discovery off the main thread.
        var contextData by remember { mutableStateOf<EditorContextData?>(null) }
        LaunchedEffect(notes, trash, links, noteId, currentNote) {
            contextData = withContext(Dispatchers.Default) {
                val builtIndex = NoteLinkIndex(notes + trash, links)
                val back = if (noteId == null) emptyList() else NoteConnections.backlinksWithContext(noteId, notes, links)
                val mentions = if (currentNote == null) emptyList() else NoteConnections.unlinkedMentions(currentNote, notes, links)
                EditorContextData(builtIndex, back, mentions)
            }
        }
        val index = contextData?.index ?: remember { NoteLinkIndex(emptyList(), emptyList()) }
        val backlinksWithContext = contextData?.backlinks.orEmpty()
        val unlinkedMentions = contextData?.unlinkedMentions.orEmpty()

        val noteFlashcards = remember(flashcards, noteId) {
            if (noteId == null) emptyList()
            else flashcards.filter { it.sourceNoteId == noteId }
        }
        val noteTasks = remember(tasks, noteId) {
            if (noteId == null) emptyList()
            else tasks.filter { it.noteId == noteId }
                .sortedWith(compareBy<StudyTask>({ it.completed }, { !it.hasDueDate }, { it.priority }, { it.dueAt }))
        }
        val studiedMinutesByTaskId = remember(sessions) {
            sessions.filter { !it.isActive && it.taskId != null }
                .groupBy { it.taskId!! }
                .mapValues { (_, list) -> list.sumOf { it.durationSeconds } / 60L }
        }

        // Precompute Vault aliases, folders, and tags off the main thread for autocomplete & quick switcher.
        var vaultMeta by remember { mutableStateOf(VaultNoteMetaCache(emptyList(), emptyMap(), emptyMap())) }
        LaunchedEffect(notes) {
            vaultMeta = withContext(Dispatchers.Default) {
                val aliasesMap = HashMap<Long, List<String>>()
                val foldersMap = HashMap<Long, String?>()
                val tagCounts = HashMap<String, Int>()
                for (n in notes) {
                    if (n.trashedAt != null) continue
                    val (p, noteBody) = fastPropertiesAndBody(n.markdown)
                    if (p.aliases.isNotEmpty()) aliasesMap[n.id] = p.aliases
                    if (!p.folder.isNullOrBlank()) foldersMap[n.id] = p.folder
                    for (t in extractNoteTagsFromParts(p.tags, noteBody)) {
                        tagCounts[t] = (tagCounts[t] ?: 0) + 1
                    }
                }
                val sortedTags = tagCounts.entries.sortedByDescending { it.value }.map { it.key }
                VaultNoteMetaCache(sortedTags, aliasesMap, foldersMap)
            }
        }

        // Offload word count and inline autocomplete off the main thread.
        var words by remember { mutableIntStateOf(0) }
        LaunchedEffect(body.text) {
            val snapshotText = body.text
            words = withContext(Dispatchers.Default) { countWordsFast(snapshotText) }
        }

        var autocomplete by remember { mutableStateOf<AutocompleteState?>(null) }
        LaunchedEffect(body.text, body.selection, vaultMeta, notes, viewMode, currentNote) {
            if (viewMode == NoteViewMode.READ || !body.selection.collapsed) {
                autocomplete = null
                return@LaunchedEffect
            }
            val cursor = body.selection.start
            val text = body.text
            if (cursor <= 0 || cursor > text.length) {
                autocomplete = null
                return@LaunchedEffect
            }
            delay(50)
            autocomplete = withContext(Dispatchers.Default) {
                computeAutocomplete(
                    text = text,
                    cursor = cursor,
                    notes = notes,
                    currentNote = currentNote,
                    allTags = vaultMeta.tags,
                    aliasesById = vaultMeta.aliasesById,
                    foldersById = vaultMeta.foldersById
                )
            }
        }

        var activeJumpHeading by rememberSaveable(state.key) { mutableStateOf<String?>(null) }
        var jumpTriggerCount by rememberSaveable(state.key) { mutableIntStateOf(0) }

        LaunchedEffect(state.key, noteId) {
            if (noteId != null) {
                val pending = NoteJumpTargets.consume(noteId)
                if (!pending.isNullOrBlank()) {
                    activeJumpHeading = pending
                    jumpTriggerCount++
                    val matched = outline.firstOrNull {
                        it.title.equals(pending, true) || it.anchor.equals(pending, true)
                    }
                    if (matched != null && viewMode == NoteViewMode.EDIT) {
                        val pos = matched.charOffset.coerceIn(0, body.text.length)
                        controller.body(TextFieldValue(body.text, TextRange(pos)))
                    }
                }
            }
        }

        var showPropertiesSheet by remember { mutableStateOf(false) }
        var showActionsSheet by remember { mutableStateOf(false) }
        var showContextSheet by remember { mutableStateOf(false) }
        var contextTab by rememberSaveable { mutableStateOf(ContextHubTab.BACKLINKS) }
        var showQuickSwitcher by remember { mutableStateOf(false) }
        var showQuickInsertSheet by remember { mutableStateOf(false) }

        var showSearch by rememberSaveable { mutableStateOf(false) }
        var showReplace by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }
        var replaceQuery by rememberSaveable { mutableStateOf("") }
        var currentMatchIdx by rememberSaveable { mutableIntStateOf(0) }
        var showLocalGraph by rememberSaveable { mutableStateOf(false) }
        var showTemplatePicker by rememberSaveable { mutableStateOf(false) }
        var showOutlineSheet by remember { mutableStateOf(false) }
        var showSnapshotsSheet by remember { mutableStateOf(false) }
        var newTask by rememberSaveable { mutableStateOf("") }
        var confirmDelete by remember { mutableStateOf(false) }
        var confirmReload by remember { mutableStateOf(false) }
        var showLinkPicker by remember { mutableStateOf(false) }
        var opening by remember { mutableStateOf<WikiReference?>(null) }
        var excerpt by rememberSaveable { mutableStateOf<String?>(null) }

        val titleFocus = remember { FocusRequester() }
        val bodyFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val imeOpen = WindowInsets.ime.getBottom(density) > 0

        fun updateBody(value: TextFieldValue) {
            val expanded = if (value.selection.collapsed) {
                LatexSnippetExpander.tryExpandPostfix(value.text, value.selection.start)
            } else null
            val nextVal = if (expanded != null) {
                value.copy(text = expanded.first, selection = TextRange(expanded.second))
            } else value
            controller.body(nextVal)
            if (viewMode != NoteViewMode.READ) {
                runCatching { bodyFocus.requestFocus() }
                keyboard?.show()
            }
        }

        fun wrapSelection(prefix: String, suffix: String = prefix) {
            val start = body.selection.min
            val end = body.selection.max
            updateBody(
                TextFieldValue(
                    body.text.replaceRange(start, end, prefix + body.text.substring(start, end) + suffix),
                    TextRange(start + prefix.length, end + prefix.length)
                )
            )
        }

        fun prefixLine(prefix: String) {
            val start = body.text.lastIndexOf('\n', body.selection.min - 1) + 1
            updateBody(
                TextFieldValue(
                    body.text.substring(0, start) + prefix + body.text.substring(start),
                    TextRange(body.selection.start + prefix.length, body.selection.end + prefix.length)
                )
            )
        }

        fun insertText(text: String, cursorBackOffset: Int = 0) {
            val nextText = body.text.replaceRange(body.selection.min, body.selection.max, text)
            val cursor = (body.selection.min + text.length - cursorBackOffset).coerceIn(0, nextText.length)
            updateBody(TextFieldValue(nextText, TextRange(cursor)))
        }

        fun openNoteWithOptionalJump(target: Note, ref: WikiReference? = null) {
            val headingJump = ref?.heading ?: ref?.blockId?.let { "^$it" }
            if (ref != null) controller.bind(ref.raw, target.id)
            controller.flush {
                if (!headingJump.isNullOrBlank()) NoteJumpTargets.set(target.id, headingJump)
                if (onNavigateNote != null) {
                    onNavigateNote(target.id)
                } else {
                    controller.open(target.id)
                }
            }
        }

        fun openReference(raw: String) {
            val ref = references.firstOrNull { it.raw == raw }
                ?: NoteReferences.parse("[[$raw]]").firstOrNull()
                ?: return
            if (ref.noteTarget.isEmpty() && (ref.heading != null || ref.blockId != null)) {
                val targetHeading = ref.heading ?: ref.blockId
                activeJumpHeading = targetHeading
                jumpTriggerCount++
                val matched = outline.firstOrNull {
                    it.title.equals(targetHeading, true) || it.anchor.equals(targetHeading, true)
                }
                if (matched != null && viewMode == NoteViewMode.EDIT) {
                    controller.body(TextFieldValue(body.text, TextRange(matched.charOffset.coerceIn(0, body.text.length))))
                }
                return
            }
            val candidates = index.candidates(noteId, ref, state.choices)
            val target = candidates.singleOrNull()
            if (target != null && target.trashedAt == null) {
                openNoteWithOptionalJump(target, ref)
            } else {
                opening = ref
            }
        }

        fun chooseLink(target: Note) {
            val occupied = index.bound(noteId).toMutableMap().apply { putAll(state.choices) }
            val duplicates = notes.filter { it.title.equals(target.title, true) }.sortedBy { it.id }
            val label = if (duplicates.size > 1) "${target.title} (${duplicates.indexOfFirst { it.id == target.id } + 1})" else null
            val raw = NoteReferences.reference(target.title, target.id, occupied, label)
            insertText("[[$raw]]")
            controller.bind(raw, target.id)
            showLinkPicker = false
        }

        val effCanBack = canNavigateBack || state.canGoBack || controller.canGoBack
        val effCanForward = canNavigateForward || state.canGoForward || controller.canGoForward
        val effCanUndo = state.canUndo || controller.canUndo
        val effCanRedo = state.canRedo || controller.canRedo

        val noteTaskIds = remember(tasks, noteId) {
            if (noteId == null) emptySet()
            else tasks.asSequence().filter { it.noteId == noteId }.map { it.id }.toSet()
        }
        val noteStudiedMinutes = remember(sessions, title, noteTaskIds) {
            val cleanTitle = title.trim()
            sessions.filter {
                !it.isActive && (
                    (it.taskId != null && it.taskId in noteTaskIds) ||
                        (cleanTitle.isNotBlank() && it.topic.equals(cleanTitle, ignoreCase = true))
                    )
            }.sumOf { it.durationSeconds } / 60L
        }
        val inferredSubjectId = remember(suggestedSubject, subjects, title, body.text) {
            suggestedSubject ?: subjects.firstOrNull { s ->
                title.contains(s.name, ignoreCase = true) || body.text.contains("#${s.name}", ignoreCase = true)
            }?.id
        }

        val toggleChecklistLine: (Int) -> Unit = { lineIndex ->
            val cleanLen = NoteFrontmatter.body(body.text).length
            val prefixLen = (body.text.length - cleanLen).coerceAtLeast(0)
            val lineOffset = if (prefixLen > 0) body.text.take(prefixLen).count { it == '\n' } else 0
            val absIndex = lineOffset + lineIndex
            val targetLine = NoteChecklists.extract(body.text).firstOrNull { it.lineIndex == absIndex }
            val updated = NoteChecklists.toggleLine(body.text, absIndex)
            if (updated != body.text) {
                controller.body(
                    TextFieldValue(
                        updated,
                        TextRange(
                            body.selection.start.coerceIn(0, updated.length),
                            body.selection.end.coerceIn(0, updated.length)
                        )
                    )
                )
                if (targetLine != null) {
                    val newChecked = !targetLine.checked
                    val lineNorm = normalizeTaskToken(targetLine.text)
                    val parsedNorm = normalizeTaskToken(TaskQuickParser.parse(targetLine.text, subjects).title)
                    noteTasks.firstOrNull { task ->
                        val taskNorm = normalizeTaskToken(task.title.trim())
                        taskNorm == lineNorm || taskNorm == parsedNorm
                    }?.let { matchedTask ->
                        if (matchedTask.completed != newChecked) onToggleTask(matchedTask)
                    }
                }
            }
        }

        val openNoteByIdInRich: (Long) -> Unit = { id ->
            notes.firstOrNull { it.id == id }?.let { target ->
                openNoteWithOptionalJump(target, null)
            }
        }

        val totalContextCount = backlinksWithContext.size +
            unlinkedMentions.size +
            references.size +
            noteTasks.size +
            noteFlashcards.size

        val applyPropertiesChange: (NoteProperties) -> Unit = { updatedProps ->
            val (updatedText, nextStart, nextEnd) = NoteFrontmatter.applyPreservingCursor(
                body.text, updatedProps, body.selection.start, body.selection.end
            )
            controller.body(TextFieldValue(updatedText, TextRange(nextStart, nextEnd)))
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {
                // Minimal 56dp Obsidian Mobile Top Chrome
                EditorMinimalTopBar(
                    isDirty = state.isDirty,
                    hasError = state.error != null,
                    leaving = state.leaving,
                    viewMode = viewMode,
                    focusMode = focusMode,
                    onBack = onBack,
                    onToggleReadEdit = {
                        viewMode = if (viewMode == NoteViewMode.READ) NoteViewMode.EDIT else NoteViewMode.READ
                    },
                    onExitSplit = { viewMode = NoteViewMode.EDIT },
                    onExitFocusMode = { focusMode = false },
                    onOpenActions = { showActionsSheet = true }
                )

                // Error / Conflict Banner (only displayed when an issue occurs)
                if (state.error != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        val errorInk = MaterialTheme.colorScheme.onErrorContainer
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(state.error, color = errorInk, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.conflict) {
                                    TextButton(
                                        onClick = controller::saveCopy,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = errorInk)
                                    ) { Text("Salvar cópia") }
                                    TextButton(
                                        onClick = { confirmReload = true },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = errorInk)
                                    ) { Text("Recarregar") }
                                } else {
                                    TextButton(
                                        onClick = { controller.flush() },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = errorInk)
                                    ) { Text("Tentar salvar") }
                                }
                            }
                        }
                    }
                }

                // In-Note Find & Replace Bar (on demand)
                AnimatedVisibility(visible = showSearch) {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        FindReplaceBar(
                            searchQuery = searchQuery,
                            onSearchQueryChange = {
                                searchQuery = it
                                currentMatchIdx = 0
                            },
                            replaceQuery = replaceQuery,
                            onReplaceQueryChange = { replaceQuery = it },
                            showReplace = showReplace,
                            onToggleReplace = { showReplace = !showReplace },
                            body = body,
                            currentMatchIdx = currentMatchIdx,
                            onSelectMatch = { idx, match ->
                                currentMatchIdx = idx
                                updateBody(TextFieldValue(body.text, TextRange(match.range.first, match.range.last + 1)))
                            },
                            onReplaceOne = { match ->
                                val updated = body.text.replaceRange(match.range.first, match.range.last + 1, replaceQuery)
                                val cursor = (match.range.first + replaceQuery.length).coerceIn(0, updated.length)
                                updateBody(TextFieldValue(updated, TextRange(cursor)))
                            },
                            onReplaceAll = { matches ->
                                if (matches.isNotEmpty()) {
                                    var updated = body.text
                                    for (m in matches.asReversed()) {
                                        updated = updated.replaceRange(m.range.first, m.range.last + 1, replaceQuery)
                                    }
                                    updateBody(TextFieldValue(updated, TextRange(body.selection.start.coerceIn(0, updated.length))))
                                }
                            },
                            onClose = {
                                showSearch = false
                                searchQuery = ""
                            }
                        )
                    }
                }

                // Main Borderless Canvas
                if (viewMode == NoteViewMode.SPLIT) {
                    EditorBorderlessHeader(
                        title = title,
                        leaving = state.leaving,
                        properties = properties,
                        focusMode = focusMode,
                        titleFocus = titleFocus,
                        onTitleChange = controller::title,
                        onNextToBody = { runCatching { bodyFocus.requestFocus() } },
                        onOpenProperties = { showPropertiesSheet = true },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val sideBySide = maxWidth >= 600.dp
                        if (sideBySide) {
                            Row(Modifier.fillMaxSize()) {
                                EditorWritingPane(
                                    body = body,
                                    leaving = state.leaving,
                                    focus = bodyFocus,
                                    onBodyChange = controller::body,
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                                VerticalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                PreviewPane(
                                    bodyText = body.text,
                                    notes = notes,
                                    links = links,
                                    tasks = tasks,
                                    currentNoteId = noteId,
                                    activeJumpHeading = activeJumpHeading,
                                    jumpTriggerCount = jumpTriggerCount,
                                    onToggleLine = toggleChecklistLine,
                                    onToggleTask = onToggleTask,
                                    onOpenLinked = ::openReference,
                                    onOpenNoteById = openNoteByIdInRich,
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                EditorWritingPane(
                                    body = body,
                                    leaving = state.leaving,
                                    focus = bodyFocus,
                                    onBodyChange = controller::body,
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                PreviewPane(
                                    bodyText = body.text,
                                    notes = notes,
                                    links = links,
                                    tasks = tasks,
                                    currentNoteId = noteId,
                                    activeJumpHeading = activeJumpHeading,
                                    jumpTriggerCount = jumpTriggerCount,
                                    onToggleLine = toggleChecklistLine,
                                    onToggleTask = onToggleTask,
                                    onOpenLinked = ::openReference,
                                    onOpenNoteById = openNoteByIdInRich,
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                    ) {
                        EditorBorderlessHeader(
                            title = title,
                            leaving = state.leaving,
                            properties = properties,
                            focusMode = focusMode,
                            titleFocus = titleFocus,
                            onTitleChange = controller::title,
                            onNextToBody = { runCatching { bodyFocus.requestFocus() } },
                            onOpenProperties = { showPropertiesSheet = true }
                        )

                        Spacer(Modifier.height(10.dp))

                        if (viewMode == NoteViewMode.EDIT) {
                            BorderlessBodyEditor(
                                body = body,
                                leaving = state.leaving,
                                focus = bodyFocus,
                                onBodyChange = controller::body
                            )
                        } else {
                            if (body.text.isBlank()) {
                                Text(
                                    "Nota vazia. Toque em ✏️ no canto superior para escrever.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            } else {
                                RichMarkdown(
                                    markdown = body.text,
                                    onToggleLine = toggleChecklistLine,
                                    onOpenLinked = ::openReference,
                                    notes = notes,
                                    links = links,
                                    tasks = tasks,
                                    transclusionStack = noteId?.let { setOf(it) } ?: emptySet(),
                                    highlightedHeading = activeJumpHeading,
                                    jumpVersion = jumpTriggerCount,
                                    onOpenNoteById = openNoteByIdInRich,
                                    onToggleTask = onToggleTask
                                )
                            }
                        }

                        if (!focusMode) {
                            Spacer(Modifier.height(24.dp))
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                            NoteBottomContextSummaryLink(
                                backlinksCount = backlinksWithContext.size,
                                mentionsCount = unlinkedMentions.size,
                                linksCount = references.size,
                                tasksCount = noteTasks.size,
                                cardsCount = noteFlashcards.size,
                                words = words,
                                onClick = {
                                    contextTab = when {
                                        backlinksWithContext.isNotEmpty() -> ContextHubTab.BACKLINKS
                                        unlinkedMentions.isNotEmpty() -> ContextHubTab.MENTIONS
                                        noteTasks.isNotEmpty() -> ContextHubTab.TASKS
                                        noteFlashcards.isNotEmpty() -> ContextHubTab.FLASHCARDS
                                        else -> ContextHubTab.LINKS
                                    }
                                    showContextSheet = true
                                }
                            )
                        }

                        Spacer(Modifier.height(if (imeOpen) 88.dp else 112.dp))
                    }
                }
            }

            // Bottom Floating Overlay:
            // Keyboard open -> horizontally scrollable formatting pill + live autocomplete directly above IME
            // Keyboard closed -> centered floating bottom pill bar (< > 🔍 + [N] ☰)
            if (imeOpen && viewMode != NoteViewMode.READ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (autocomplete != null && autocomplete!!.items.isNotEmpty()) {
                        InlineAutocompletePopup(
                            state = autocomplete!!,
                            onSelect = { suggestion ->
                                applyAutocompleteSelection(
                                    autocomplete = autocomplete!!,
                                    suggestion = suggestion,
                                    body = body,
                                    onApply = ::updateBody,
                                    onBind = controller::bind,
                                    onOpenTemplatePicker = { showTemplatePicker = true }
                                )
                            }
                        )
                    }
                    EditorKeyboardFormattingPill(
                        leaving = state.leaving,
                        canUndo = effCanUndo,
                        canRedo = effCanRedo,
                        onUndo = controller::undo,
                        onRedo = controller::redo,
                        onWrapSelection = ::wrapSelection,
                        onPrefixLine = ::prefixLine,
                        onInsertText = ::insertText,
                        onOpenLinkPicker = { showLinkPicker = true }
                    )
                }
            } else if (!imeOpen) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (viewMode != NoteViewMode.READ && autocomplete != null && autocomplete!!.items.isNotEmpty()) {
                        InlineAutocompletePopup(
                            state = autocomplete!!,
                            onSelect = { suggestion ->
                                applyAutocompleteSelection(
                                    autocomplete = autocomplete!!,
                                    suggestion = suggestion,
                                    body = body,
                                    onApply = ::updateBody,
                                    onBind = controller::bind,
                                    onOpenTemplatePicker = { showTemplatePicker = true }
                                )
                            }
                        )
                    }
                    EditorFloatingNavPill(
                        canGoBack = effCanBack && !state.leaving,
                        canGoForward = effCanForward && !state.leaving,
                        contextCount = totalContextCount,
                        onBackClick = { onNavigateBack?.invoke() ?: controller.goBack() },
                        onForwardClick = { onNavigateForward?.invoke() ?: controller.goForward() },
                        onSearchClick = { showQuickSwitcher = true },
                        onPlusClick = { showQuickInsertSheet = true },
                        onContextClick = { showContextSheet = true },
                        onMenuClick = { showActionsSheet = true }
                    )
                }
            }
        }

        // Clean Obsidian Actions Bottom Sheet (replaces crowded 15-icon toolbar)
        if (showActionsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showActionsSheet = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            SheetActionRow(
                                icon = Icons.Default.Search,
                                label = "Quick Switcher",
                                trailing = "Ir para nota",
                                onClick = {
                                    showActionsSheet = false
                                    showQuickSwitcher = true
                                }
                            )
                            if (currentNote != null) {
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                SheetActionRow(
                                    icon = Icons.Default.Hub,
                                    label = "Grafo Local",
                                    onClick = {
                                        showActionsSheet = false
                                        showLocalGraph = true
                                    }
                                )
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                                label = "Sumário (Outline)",
                                trailing = "$words palavras · ~${maxOf(1, words / 200)}m",
                                onClick = {
                                    showActionsSheet = false
                                    showOutlineSheet = true
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.Default.FindInPage,
                                label = "Localizar e substituir",
                                trailing = if (showSearch) "Aberto" else null,
                                onClick = {
                                    showActionsSheet = false
                                    showSearch = !showSearch
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.Outlined.Tune,
                                label = "Propriedades YAML",
                                trailing = if (properties.isEmpty) null else "Configuradas",
                                onClick = {
                                    showActionsSheet = false
                                    showPropertiesSheet = true
                                }
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            SheetActionRow(
                                icon = Icons.Default.ViewWeek,
                                label = "Split View (Editor + Preview)",
                                trailing = if (viewMode == NoteViewMode.SPLIT) "Ativo" else null,
                                onClick = {
                                    showActionsSheet = false
                                    viewMode = if (viewMode == NoteViewMode.SPLIT) NoteViewMode.EDIT else NoteViewMode.SPLIT
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.Default.Visibility,
                                label = "Modo Foco (Zen)",
                                trailing = if (focusMode) "Ativo" else null,
                                onClick = {
                                    showActionsSheet = false
                                    focusMode = !focusMode
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.Default.PostAdd,
                                label = "Inserir Template",
                                onClick = {
                                    showActionsSheet = false
                                    showTemplatePicker = true
                                }
                            )
                            if (noteId != null) {
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                SheetActionRow(
                                    icon = Icons.Default.History,
                                    label = "Histórico de versões",
                                    trailing = if (noteSnapshots.isEmpty()) null else "${noteSnapshots.size}",
                                    onClick = {
                                        showActionsSheet = false
                                        showSnapshotsSheet = true
                                    }
                                )
                            }
                            if (noteId != null && onStartStudySession != null) {
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                SheetActionRow(
                                    icon = Icons.Default.Timer,
                                    label = "Focar com Timer",
                                    trailing = if (noteStudiedMinutes > 0) "${noteStudiedMinutes}m" else null,
                                    onClick = {
                                        showActionsSheet = false
                                        if (title.isNotBlank()) {
                                            controller.flush {
                                                onStartStudySession(noteId, title.trim(), inferredSubjectId)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            if (noteId != null) {
                                SheetActionRow(
                                    icon = Icons.Default.Style,
                                    label = "Criar Flashcard Anki",
                                    onClick = {
                                        showActionsSheet = false
                                        val selectedText = if (!body.selection.collapsed) {
                                            body.text.substring(body.selection.min, body.selection.max).trim()
                                        } else ""
                                        excerpt = selectedText.ifBlank {
                                            NoteFrontmatter.body(body.text).trim().take(400).ifBlank { title }
                                        }
                                    }
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                SheetActionRow(
                                    icon = Icons.Default.PushPin,
                                    label = if (currentNote?.pinned == true) "Desafixar nota" else "Fixar nota",
                                    onClick = {
                                        showActionsSheet = false
                                        onTogglePin()
                                    }
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            }
                            SheetActionRow(
                                icon = Icons.Default.ContentCopy,
                                label = "Salvar uma cópia",
                                onClick = {
                                    showActionsSheet = false
                                    controller.saveCopy()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.Default.Share,
                                label = "Compartilhar / Exportar .md",
                                onClick = {
                                    showActionsSheet = false
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, title)
                                        putExtra(Intent.EXTRA_TEXT, body.text)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Compartilhar nota"))
                                }
                            )
                            if (noteId != null) {
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                SheetActionRow(
                                    icon = Icons.Default.DeleteOutline,
                                    label = "Mover para lixeira",
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        showActionsSheet = false
                                        confirmDelete = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // YAML Properties & Tags ModalBottomSheet (on demand, never auto-expanded above editor)
        if (showPropertiesSheet) {
            ModalBottomSheet(
                onDismissRequest = { showPropertiesSheet = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Propriedades da Nota",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    PropertiesEditor(
                        properties = properties,
                        onChange = applyPropertiesChange
                    )
                }
            }
        }

        // Quick Insert (+) Bottom Sheet
        if (showQuickInsertSheet) {
            ModalBottomSheet(
                onDismissRequest = { showQuickInsertSheet = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Inserir ou Criar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            SheetActionRow(
                                icon = Icons.Default.Link,
                                label = "Inserir link [[nota]]",
                                onClick = {
                                    showQuickInsertSheet = false
                                    showLinkPicker = true
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.Default.PostAdd,
                                label = "Inserir template",
                                onClick = {
                                    showQuickInsertSheet = false
                                    showTemplatePicker = true
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.Default.CheckBox,
                                label = "Adicionar tarefa na nota",
                                onClick = {
                                    showQuickInsertSheet = false
                                    contextTab = ContextHubTab.TASKS
                                    showContextSheet = true
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.Default.EditNote,
                                label = "Nova nota em branco",
                                onClick = {
                                    showQuickInsertSheet = false
                                    controller.flush { controller.open(null) }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Tabbed Context Hub Bottom Sheet (Backlinks, Unlinked Mentions, Outgoing Links, Tasks, Flashcards)
        if (showContextSheet) {
            ModalBottomSheet(
                onDismissRequest = { showContextSheet = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 560.dp)
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    Text(
                        "Contexto da Nota",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )

                    ScrollableTabRow(
                        selectedTabIndex = contextTab.ordinal,
                        edgePadding = 16.dp,
                        containerColor = Color.Transparent
                    ) {
                        ContextHubTab.entries.forEach { tab ->
                            val badge = when (tab) {
                                ContextHubTab.BACKLINKS -> backlinksWithContext.size
                                ContextHubTab.MENTIONS -> unlinkedMentions.size
                                ContextHubTab.LINKS -> references.size
                                ContextHubTab.TASKS -> noteTasks.size
                                ContextHubTab.FLASHCARDS -> noteFlashcards.size
                            }
                            Tab(
                                selected = contextTab == tab,
                                onClick = { contextTab = tab },
                                text = {
                                    Text(
                                        if (badge > 0) "${tab.label} ($badge)" else tab.label,
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (contextTab) {
                            ContextHubTab.BACKLINKS -> {
                                if (backlinksWithContext.isEmpty()) {
                                    Text(
                                        "Nenhuma outra nota aponta para esta ainda.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    backlinksWithContext.forEach { occurrence ->
                                        Surface(
                                            onClick = {
                                                showContextSheet = false
                                                openNoteWithOptionalJump(occurrence.source, null)
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(Icons.Default.Link, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                                    Text(
                                                        occurrence.source.title,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Text(
                                                    occurrence.snippet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            ContextHubTab.MENTIONS -> {
                                if (unlinkedMentions.isEmpty()) {
                                    Text(
                                        "Nenhuma menção não vinculada encontrada no Vault.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    var linkingSourceId by remember { mutableStateOf<Long?>(null) }
                                    val resolvedTargetId = noteId ?: currentNote?.id ?: 0L
                                    val resolvedTargetTitle = title.trim().ifBlank { currentNote?.title.orEmpty() }
                                    unlinkedMentions.take(15).forEach { mention ->
                                        val isSourceBusy = linkingSourceId == mention.source.id
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        mention.source.title,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        mention.snippet,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                FilledTonalButton(
                                                    enabled = !isSourceBusy && resolvedTargetId > 0L && resolvedTargetTitle.isNotBlank(),
                                                    onClick = {
                                                        if (onLinkMention != null && linkingSourceId == null) {
                                                            linkingSourceId = mention.source.id
                                                            scope.launch {
                                                                try {
                                                                    val linked = onLinkMention(
                                                                        mention.source.id,
                                                                        mention.start,
                                                                        mention.end,
                                                                        resolvedTargetId,
                                                                        resolvedTargetTitle
                                                                    )
                                                                    if (linked) {
                                                                        Toast.makeText(context, "Menção vinculada com sucesso!", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                } finally {
                                                                    linkingSourceId = null
                                                                }
                                                            }
                                                        }
                                                    },
                                                    shape = studyControlShape(),
                                                    modifier = Modifier.heightIn(min = 48.dp)
                                                ) {
                                                    Text(if (isSourceBusy) "..." else "Vincular", maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            ContextHubTab.LINKS -> {
                                if (references.isEmpty()) {
                                    Text(
                                        "Nenhum [[link]] nesta nota. Digite [[ para conectar notas.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    references.forEach { ref ->
                                        val target = index.target(noteId, ref, state.choices)
                                        val label = ref.label ?: target?.title ?: ref.target
                                        Surface(
                                            onClick = {
                                                showContextSheet = false
                                                openReference(ref.raw)
                                            },
                                            enabled = !state.leaving,
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(Icons.Default.ArrowOutward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                                Text(
                                                    label + if (target?.trashedAt != null) " · na lixeira" else if (target == null) " · criar nota" else "",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            ContextHubTab.TASKS -> {
                                if (noteId == null) {
                                    Text(
                                        "Digite um título para salvar a nota e gerenciar tarefas vinculadas.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    NoteTasksSection(
                                        noteId = noteId,
                                        body = body,
                                        noteTasks = noteTasks,
                                        subjects = subjects,
                                        inferredSubjectId = inferredSubjectId,
                                        studiedMinutesByTaskId = studiedMinutesByTaskId,
                                        newTask = newTask,
                                        onNewTaskChange = { newTask = it },
                                        onToggleTask = onToggleTask,
                                        onUpdateBody = controller::body,
                                        onSaveTask = onSaveTask,
                                        onAddTask = onAddTask,
                                        onStartTask = onStartTask,
                                        onFlush = controller::flush
                                    )
                                }
                            }

                            ContextHubTab.FLASHCARDS -> {
                                if (noteFlashcards.isEmpty()) {
                                    Text(
                                        "Nenhum flashcard vinculado a esta nota ainda.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    NoteFlashcardsHub(noteFlashcards = noteFlashcards, onReviewCards = onReviewCards)
                                }
                                if (noteId != null) {
                                    FilledTonalButton(
                                        onClick = {
                                            showContextSheet = false
                                            excerpt = NoteFrontmatter.body(body.text).trim().take(400).ifBlank { title }
                                        },
                                        shape = studyControlShape(),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    ) {
                                        Icon(Icons.Default.Style, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Criar flashcard desta nota")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Quick Switcher Bottom Sheet (⌘K / 🔍)
        if (showQuickSwitcher) {
            QuickSwitcherSheet(
                notes = notes,
                aliasesById = vaultMeta.aliasesById,
                foldersById = vaultMeta.foldersById,
                onDismiss = { showQuickSwitcher = false },
                onFindInCurrentNote = {
                    showQuickSwitcher = false
                    showSearch = true
                },
                onSelectNote = { target ->
                    showQuickSwitcher = false
                    openNoteWithOptionalJump(target, null)
                },
                onCreateNewTitle = onCreateAndOpenNote?.let { creator ->
                    { newTitle ->
                        showQuickSwitcher = false
                        controller.flush {
                            scope.launch {
                                val createdId = creator(newTitle)
                                if (createdId > 0L) {
                                    if (onNavigateNote != null) onNavigateNote(createdId)
                                    else controller.open(createdId)
                                }
                            }
                        }
                    }
                }
            )
        }

        // Outline Bottom Sheet / Dialog
        if (showOutlineSheet) {
            OutlineSheetDialog(
                outline = outline,
                onDismiss = { showOutlineSheet = false },
                onSelectHeading = { h ->
                    showOutlineSheet = false
                    activeJumpHeading = h.title
                    jumpTriggerCount++
                    if (viewMode != NoteViewMode.READ) {
                        val pos = h.charOffset.coerceIn(0, body.text.length)
                        updateBody(TextFieldValue(body.text, TextRange(pos)))
                    }
                }
            )
        }

        // Version History (Snapshots) Sheet
        if (showSnapshotsSheet) {
            SnapshotsDialog(
                snapshots = noteSnapshots,
                onDismiss = { showSnapshotsSheet = false },
                onRestore = { snap ->
                    showSnapshotsSheet = false
                    if (onRestoreSnapshot != null) {
                        scope.launch {
                            onRestoreSnapshot(snap.id)
                            Toast.makeText(context, "Versão restaurada!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        controller.title(snap.title)
                        controller.body(TextFieldValue(snap.markdown, TextRange(snap.markdown.length)))
                        controller.flush()
                        Toast.makeText(context, "Versão aplicada ao editor!", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // Local Graph Dialog
        if (showLocalGraph && currentNote != null) {
            LocalGraphDialog(
                note = currentNote,
                allNotes = notes,
                links = links,
                onOpen = { targetId ->
                    showLocalGraph = false
                    notes.firstOrNull { it.id == targetId }?.let { openNoteWithOptionalJump(it, null) }
                        ?: controller.flush { controller.open(targetId) }
                },
                onDismiss = { showLocalGraph = false },
                onCreateMissingNote = onCreateAndOpenNote?.let { creator ->
                    { missingTitle ->
                        showLocalGraph = false
                        controller.flush {
                            scope.launch {
                                val createdId = creator(missingTitle)
                                if (createdId > 0L) {
                                    if (onNavigateNote != null) onNavigateNote(createdId)
                                    else controller.open(createdId)
                                }
                            }
                        }
                    }
                },
                onCreateNote = {
                    showLocalGraph = false
                    controller.flush { controller.open(null) }
                }
            )
        }

        if (showTemplatePicker) {
            TemplatePickerDialog(
                notes = notes,
                titleHint = title,
                onDismiss = { showTemplatePicker = false },
                onSelect = { template ->
                    showTemplatePicker = false
                    val resolvedTitle = title.ifBlank { expandTemplate(template.defaultTitle, template.defaultTitle) }
                    val expanded = expandTemplate(template.initialMarkdown, resolvedTitle)
                    if (body.text.isBlank()) {
                        if (title.isBlank()) controller.title(resolvedTitle)
                        controller.body(TextFieldValue(expanded, TextRange(expanded.length)))
                    } else {
                        insertText("\n\n" + expanded)
                    }
                }
            )
        }

        if (showLinkPicker) {
            LinkPickerDialog(notes, noteId, { showLinkPicker = false }, ::chooseLink) { newTitle ->
                insertText("[[${NoteReferences.escape(newTitle)}]]")
                showLinkPicker = false
            }
        }

        opening?.let { ref ->
            val candidates = index.candidates(noteId, ref, state.choices)
            AlertDialog(
                onDismissRequest = { opening = null },
                modifier = Modifier.imePadding(),
                title = { Text(if (candidates.isEmpty()) "Criar nota vinculada?" else "Escolher nota") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (candidates.isEmpty()) {
                            Text("Criar uma nota chamada “${ref.noteTarget}” e abrir seu conteúdo.")
                        } else {
                            if (candidates.size > 1) Text("Há mais de uma nota com esse título. Escolha o destino deste link.")
                            candidates.forEachIndexed { number, target ->
                                TextButton(
                                    onClick = {
                                        opening = null
                                        if (target.trashedAt == null) openNoteWithOptionalJump(target, ref)
                                        else controller.restoreTarget(target, ref.raw)
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(target.title + if (candidates.size > 1) " (${number + 1})" else "")
                                        Text(
                                            if (target.trashedAt == null) target.markdown.take(100).ifBlank { "Nota vazia" }
                                            else "Restaurar da lixeira e abrir",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (candidates.isEmpty()) {
                        TextButton({
                            opening = null
                            val headingJump = ref.heading ?: ref.blockId?.let { "^$it" }
                            if (onCreateAndOpenNote != null) {
                                controller.flush {
                                    scope.launch {
                                        val createdId = onCreateAndOpenNote(ref.noteTarget)
                                        if (createdId > 0L) {
                                            controller.bind(ref.raw, createdId)
                                            controller.flush {
                                                if (!headingJump.isNullOrBlank()) NoteJumpTargets.set(createdId, headingJump)
                                                if (onNavigateNote != null) onNavigateNote(createdId)
                                                else controller.open(createdId)
                                            }
                                        }
                                    }
                                }
                            } else {
                                controller.createLinked(ref.noteTarget, ref.raw)
                            }
                        }) { Text("Criar e abrir") }
                    }
                },
                dismissButton = { TextButton({ opening = null }) { Text("Cancelar") } }
            )
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                modifier = Modifier.imePadding(),
                title = { Text("Mover nota para a lixeira?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Você poderá restaurá-la pela lista de notas. Os vínculos com outras notas, tarefas, livros e cartões ficam preservados.")
                    }
                },
                confirmButton = {
                    TextButton(
                        { confirmDelete = false; controller.trash(onDeleted) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Mover para a lixeira") }
                },
                dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } }
            )
        }

        if (confirmReload) {
            AlertDialog(
                onDismissRequest = { confirmReload = false },
                modifier = Modifier.imePadding(),
                title = { Text("Recarregar a nota salva?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("A edição aberta será substituída pela versão salva. Você também pode salvar sua edição como cópia antes de continuar.")
                    }
                },
                confirmButton = { TextButton({ confirmReload = false; controller.reload() }) { Text("Recarregar") } },
                dismissButton = { TextButton({ confirmReload = false }) { Text("Cancelar") } }
            )
        }

        excerpt?.let { text ->
            var question by rememberSaveable { mutableStateOf("") }
            var deckId by remember { mutableStateOf<Long?>(null) }
            var subjectId by remember { mutableStateOf(suggestedSubject) }
            AlertDialog(
                onDismissRequest = { excerpt = null },
                modifier = Modifier.imePadding(),
                title = { Text("Criar flashcard") },
                text = {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            question,
                            { question = it },
                            label = { Text("Pergunta") },
                            shape = studyControlShape(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (decks.isNotEmpty()) CardDestination("Baralho", "Sem baralho", decks.map { it.id to it.name }, deckId) { deckId = it }
                        if (subjects.isNotEmpty()) CardDestination("Matéria", "Geral", subjects.map { it.id to it.name }, subjectId) { subjectId = it }
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Button(
                        { noteId?.let { onCard(it, question, text, deckId, subjectId) }; excerpt = null },
                        enabled = question.isNotBlank(),
                        shape = studyControlShape()
                    ) { Text("Criar cartão") }
                },
                dismissButton = { TextButton({ excerpt = null }) { Text("Cancelar") } }
            )
        }
    }
}

@Composable
private fun EditorMinimalTopBar(
    isDirty: Boolean,
    hasError: Boolean,
    leaving: Boolean,
    viewMode: NoteViewMode,
    focusMode: Boolean,
    onBack: () -> Unit,
    onToggleReadEdit: () -> Unit,
    onExitSplit: () -> Unit,
    onExitFocusMode: () -> Unit,
    onOpenActions: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalIconButton(
            onClick = onBack,
            enabled = !leaving,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
        }

        if (isDirty || hasError) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            )
        }

        if (focusMode) {
            AssistChip(
                onClick = onExitFocusMode,
                label = { Text("Foco ×", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.heightIn(min = 36.dp)
            )
        }

        if (viewMode == NoteViewMode.SPLIT) {
            AssistChip(
                onClick = onExitSplit,
                label = { Text("Split ×", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.heightIn(min = 36.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleReadEdit,
                    enabled = !leaving,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (viewMode == NoteViewMode.READ) Icons.Default.Edit else Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = if (viewMode == NoteViewMode.READ) "Alternar para edição" else "Alternar para leitura"
                    )
                }
                Box(
                    Modifier
                        .width(0.6.dp)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
                IconButton(
                    onClick = onOpenActions,
                    enabled = !leaving,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Mais ações da nota")
                }
            }
        }
    }
}

@Composable
private fun EditorBorderlessHeader(
    title: String,
    leaving: Boolean,
    properties: NoteProperties,
    focusMode: Boolean,
    titleFocus: FocusRequester,
    onTitleChange: (String) -> Unit,
    onNextToBody: () -> Unit,
    onOpenProperties: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth()) {
            if (title.isEmpty()) {
                Text(
                    "Sem título",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                )
            }
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                enabled = !leaving,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { onNextToBody() }),
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus)
            )
        }

        if (!focusMode) {
            val count = (if (!properties.folder.isNullOrBlank()) 1 else 0) +
                (if (properties.aliases.isNotEmpty()) 1 else 0) +
                (if (properties.tags.isNotEmpty()) 1 else 0) +
                properties.custom.size
            val summary = buildString {
                append(if (count == 0) "Propriedades · +" else "Propriedades ($count)")
                properties.folder?.takeIf { it.isNotBlank() }?.let { append(" · 📁 $it") }
                if (properties.tags.isNotEmpty()) {
                    append(" · ")
                    append(properties.tags.take(2).joinToString(" ") { "#$it" })
                }
            }
            Row(
                modifier = Modifier
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenProperties)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Outlined.Tune,
                    null,
                    Modifier.size(15.dp),
                    tint = if (properties.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (properties.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BorderlessBodyEditor(
    body: TextFieldValue,
    leaving: Boolean,
    focus: FocusRequester,
    onBodyChange: (TextFieldValue) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 340.dp)
            .padding(vertical = 4.dp)
    ) {
        if (body.text.isEmpty()) {
            Text(
                "Comece a escrever… (use [[ para links, # para tags, / para comandos)",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }
        BasicTextField(
            value = body,
            onValueChange = onBodyChange,
            enabled = !leaving,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onBackground
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 340.dp)
                .focusRequester(focus)
        )
    }
}

@Composable
private fun NoteBottomContextSummaryLink(
    backlinksCount: Int,
    mentionsCount: Int,
    linksCount: Int,
    tasksCount: Int,
    cardsCount: Int,
    words: Int,
    onClick: () -> Unit
) {
    val summaryText = buildList {
        if (backlinksCount > 0) add("$backlinksCount backlinks")
        if (mentionsCount > 0) add("$mentionsCount menções")
        if (linksCount > 0) add("$linksCount links")
        if (tasksCount > 0) add("$tasksCount tarefas")
        if (cardsCount > 0) add("$cardsCount cards")
    }.joinToString(" · ").ifEmpty { "$words palavras · Abrir backlinks, tarefas e cards" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.Hub,
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            summaryText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.ChevronRight,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EditorFloatingNavPill(
    canGoBack: Boolean,
    canGoForward: Boolean,
    contextCount: Int,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onSearchClick: () -> Unit,
    onPlusClick: () -> Unit,
    onContextClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = onBackClick,
                enabled = canGoBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Nota anterior")
            }
            IconButton(
                onClick = onForwardClick,
                enabled = canGoForward,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Próxima nota")
            }
            IconButton(onClick = onSearchClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Search, contentDescription = "Quick Switcher")
            }
            IconButton(onClick = onPlusClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Inserir ou criar")
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onContextClick),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .border(
                            width = 1.5.dp,
                            color = if (contextCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contextCount.coerceAtMost(99).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (contextCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Menu, contentDescription = "Ações da nota")
            }
        }
    }
}

@Composable
private fun EditorKeyboardFormattingPill(
    leaving: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onWrapSelection: (String, String) -> Unit,
    onPrefixLine: (String) -> Unit,
    onInsertText: (String, Int) -> Unit,
    onOpenLinkPicker: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onUndo, enabled = canUndo && !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer")
            }
            IconButton(onClick = onRedo, enabled = canRedo && !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Redo, "Refazer")
            }
            IconButton({ onPrefixLine("- [ ] ") }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.CheckBox, "Checklist")
            }
            IconButton(onOpenLinkPicker, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Link, "Linkar nota [[ ]]")
            }
            IconButton({ onInsertText("#", 0) }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Tag, "Tag")
            }
            IconButton({ onInsertText("> [!info]+ Título\n> ", 0) }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Info, "Callout")
            }
            IconButton({ onInsertText("![[", 0) }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Description, "Transcluir ![[ ]]")
            }
            IconButton({ onPrefixLine("## ") }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Title, "Título H2")
            }
            IconButton({ onWrapSelection("**", "**") }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.FormatBold, "Negrito")
            }
            IconButton({ onWrapSelection("*", "*") }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.FormatItalic, "Itálico")
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !leaving) { onWrapSelection("~~", "~~") },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "S",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.LineThrough,
                    color = if (leaving) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton({ onPrefixLine("> ") }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.FormatQuote, "Citação")
            }
            IconButton({ onWrapSelection("$", "$") }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Calculate, "Fórmula LaTeX $")
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !leaving) { onInsertText("$$\n\n$$\n", 4) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "∑",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (leaving) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !leaving) { onInsertText("/", 0) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "/",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton({ onWrapSelection("`", "`") }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Code, "Código")
            }
            IconButton({ onInsertText("| Coluna 1 | Coluna 2 |\n| --- | --- |\n| Item 1 | Item 2 |\n", 0) }, enabled = !leaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.TableChart, "Tabela")
            }
        }
    }
}

@Composable
private fun QuickSwitcherSheet(
    notes: List<Note>,
    aliasesById: Map<Long, List<String>>,
    foldersById: Map<Long, String?>,
    onDismiss: () -> Unit,
    onFindInCurrentNote: () -> Unit,
    onSelectNote: (Note) -> Unit,
    onCreateNewTitle: ((String) -> Unit)?
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Note>>(emptyList()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(query, notes, aliasesById, foldersById) {
        if (query.isNotBlank()) delay(70)
        val q = query.trim()
        results = withContext(Dispatchers.Default) {
            val active = notes.filter { it.trashedAt == null }
            if (q.isEmpty()) {
                active.sortedByDescending { it.updatedAt }.take(20)
            } else {
                active.mapNotNull { n ->
                    val aliasMatch = aliasesById[n.id].orEmpty().any { it.contains(q, true) }
                    val folderMatch = foldersById[n.id]?.contains(q, true) == true
                    val titleMatch = n.title.contains(q, true)
                    if (titleMatch || aliasMatch || folderMatch) {
                        val score = when {
                            n.title.equals(q, true) -> 0
                            n.title.startsWith(q, true) -> 1
                            titleMatch -> 2
                            aliasMatch -> 3
                            else -> 4
                        }
                        score to n
                    } else null
                }
                    .sortedWith(compareBy<Pair<Int, Note>> { it.first }.thenByDescending { it.second.updatedAt })
                    .map { it.second }
                    .take(25)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 300.dp, max = 560.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Quick Switcher",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = onFindInCurrentNote,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.FindInPage, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Localizar nesta nota")
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Abrir outra nota por título, pasta ou apelido…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, "Limpar")
                        }
                    }
                },
                shape = studyControlShape(),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                if (onCreateNewTitle != null && query.isNotBlank() && results.none { it.title.equals(query.trim(), true) }) {
                    item(key = "create_missing") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onCreateNewTitle(query.trim()) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "Criar nova nota “${query.trim()}”",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
                items(results, key = { it.id }) { target ->
                    val folder = foldersById[target.id]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectNote(target) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Description,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                target.title.ifBlank { "Nota sem título" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!folder.isNullOrBlank()) {
                                Text(
                                    folder,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
        }
    }
}

@Composable
private fun EditorWritingPane(
    body: TextFieldValue,
    leaving: Boolean,
    focus: FocusRequester,
    onBodyChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        BorderlessBodyEditor(
            body = body,
            leaving = leaving,
            focus = focus,
            onBodyChange = onBodyChange
        )
    }
}

@Composable
private fun PreviewPane(
    bodyText: String,
    notes: List<Note>,
    links: List<NoteLink>,
    tasks: List<StudyTask>,
    currentNoteId: Long?,
    activeJumpHeading: String?,
    jumpTriggerCount: Int,
    onToggleLine: (Int) -> Unit,
    onToggleTask: (StudyTask) -> Unit,
    onOpenLinked: (String) -> Unit,
    onOpenNoteById: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var debouncedBody by remember { mutableStateOf(bodyText) }
    LaunchedEffect(bodyText) {
        if (bodyText.length > 500) delay(160)
        debouncedBody = bodyText
    }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (debouncedBody.isBlank()) {
            Text("Nota vazia.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            RichMarkdown(
                markdown = debouncedBody,
                onToggleLine = onToggleLine,
                onOpenLinked = onOpenLinked,
                notes = notes,
                links = links,
                tasks = tasks,
                transclusionStack = currentNoteId?.let { setOf(it) } ?: emptySet(),
                highlightedHeading = activeJumpHeading,
                jumpVersion = jumpTriggerCount,
                onOpenNoteById = onOpenNoteById,
                onToggleTask = onToggleTask
            )
        }
    }
}

@Composable
private fun FindReplaceBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    replaceQuery: String,
    onReplaceQueryChange: (String) -> Unit,
    showReplace: Boolean,
    onToggleReplace: () -> Unit,
    body: TextFieldValue,
    currentMatchIdx: Int,
    onSelectMatch: (Int, MatchResult) -> Unit,
    onReplaceOne: (MatchResult) -> Unit,
    onReplaceAll: (List<MatchResult>) -> Unit,
    onClose: () -> Unit
) {
    val compiledSearchRegex = remember(searchQuery) {
        if (searchQuery.isBlank()) null
        else Regex.escape(searchQuery).toRegex(RegexOption.IGNORE_CASE)
    }
    var matches by remember { mutableStateOf<List<MatchResult>>(emptyList()) }
    LaunchedEffect(compiledSearchRegex, body.text) {
        if (compiledSearchRegex == null) {
            matches = emptyList()
        } else {
            val textSnapshot = body.text
            if (textSnapshot.length > 2_000) delay(60)
            matches = withContext(Dispatchers.Default) {
                compiledSearchRegex.findAll(textSnapshot).toList()
            }
        }
    }
    val safeIdx = if (matches.isEmpty()) 0 else currentMatchIdx.coerceIn(0, matches.lastIndex)

    Surface(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = studyControlShape(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedTextField(
                    searchQuery,
                    onSearchQueryChange,
                    placeholder = { Text("Localizar…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    shape = studyControlShape(),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        if (matches.isNotEmpty()) {
                            val prev = if (safeIdx - 1 < 0) matches.lastIndex else safeIdx - 1
                            onSelectMatch(prev, matches[prev])
                        }
                    },
                    enabled = matches.isNotEmpty(),
                    modifier = Modifier.size(34.dp)
                ) { Icon(Icons.Default.KeyboardArrowUp, "Anterior") }
                IconButton(
                    onClick = {
                        if (matches.isNotEmpty()) {
                            val next = (safeIdx + 1) % matches.size
                            onSelectMatch(next, matches[next])
                        }
                    },
                    enabled = matches.isNotEmpty(),
                    modifier = Modifier.size(34.dp)
                ) { Icon(Icons.Default.KeyboardArrowDown, "Próximo") }
                IconButton(onToggleReplace, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.SwapHoriz, "Substituir", tint = if (showReplace) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClose, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Close, "Fechar busca") }
            }

            if (showReplace) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        replaceQuery,
                        onReplaceQueryChange,
                        placeholder = { Text("Substituir por…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        shape = studyControlShape(),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { matches.getOrNull(safeIdx)?.let(onReplaceOne) },
                        enabled = matches.isNotEmpty()
                    ) { Text("Subst.") }
                    TextButton(
                        onClick = { onReplaceAll(matches) },
                        enabled = matches.isNotEmpty()
                    ) { Text("Tudo") }
                }
            }

            if (searchQuery.isNotBlank()) {
                val label = if (matches.isEmpty()) "0 encontrados" else "${safeIdx + 1}/${matches.size} encontrados"
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

private fun computeAutocomplete(
    text: String,
    cursor: Int,
    notes: List<Note>,
    currentNote: Note?,
    allTags: List<String>,
    aliasesById: Map<Long, List<String>> = emptyMap(),
    foldersById: Map<Long, String?> = emptyMap()
): AutocompleteState? {
    val safeCursor = cursor.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', safeCursor - 1) + 1
    val prefix = text.substring(lineStart, safeCursor)

    // 1. Wikilink [[... or [[Nota#...
    val lastOpen = prefix.lastIndexOf("[[")
    val lastClose = prefix.lastIndexOf("]]")
    if (lastOpen >= 0 && lastOpen > lastClose) {
        val rawQuery = prefix.substring(lastOpen + 2)
        val replaceStart = lineStart + lastOpen
        if (rawQuery.contains('#')) {
            val notePart = rawQuery.substringBefore('#').trim()
            val headingPart = rawQuery.substringAfter('#').trim()
            val targetNote = if (notePart.isEmpty()) {
                currentNote
            } else {
                notes.firstOrNull { n ->
                    n.trashedAt == null && (
                        n.title.equals(notePart, true) ||
                            (aliasesById[n.id] ?: NoteFrontmatter.properties(n.markdown).aliases).any { it.equals(notePart, true) }
                        )
                }
            }
            val headings = targetNote?.let { extractOutline(it.markdown) }.orEmpty()
                .filter { headingPart.isEmpty() || it.title.contains(headingPart, true) }
                .take(8)
            if (headings.isEmpty()) return null
            return AutocompleteState(
                replaceStart = replaceStart,
                replaceEnd = safeCursor,
                header = if (targetNote != null) "Cabeçalhos em ${targetNote.title}" else "Cabeçalhos",
                items = headings.map { AutocompleteSuggestion.WikiHeading(targetNote?.title?.takeIf { notePart.isNotEmpty() }, it) }
            )
        } else {
            val q = rawQuery.trim()
            val matches = notes.asSequence()
                .filter { it.trashedAt == null }
                .mapNotNull { n ->
                    val aliases = aliasesById[n.id] ?: NoteFrontmatter.properties(n.markdown).aliases
                    val alias = aliases.firstOrNull {
                        q.isNotEmpty() && it.contains(q, true)
                    }
                    val titleMatches = q.isEmpty() || n.title.contains(q, true)
                    if (titleMatches || alias != null) {
                        val score = when {
                            n.title.equals(q, true) -> 0
                            n.title.startsWith(q, true) -> 1
                            titleMatches -> 2
                            else -> 3
                        }
                        val folder = if (foldersById.containsKey(n.id)) {
                            foldersById[n.id]
                        } else {
                            NoteFrontmatter.properties(n.markdown).folder
                        }
                        score to AutocompleteSuggestion.WikiNote(n, if (titleMatches) null else alias, folder)
                    } else null
                }
                .sortedBy { it.first }
                .map { it.second }
                .take(8)
                .toList()
            if (matches.isEmpty()) return null
            return AutocompleteState(
                replaceStart = replaceStart,
                replaceEnd = safeCursor,
                header = "Vincular nota (digite # para seção)",
                items = matches
            )
        }
    }

    // 2. #tag autocomplete
    val tagMatch = tagAutocompleteRegex.find(prefix)
    if (tagMatch != null) {
        val q = tagMatch.groupValues[1].lowercase(Locale.ROOT)
        val hashIndexInPrefix = prefix.lastIndexOf('#')
        val matchedTags = allTags.filter { q.isEmpty() || it.contains(q, true) }.take(8)
        if (matchedTags.isNotEmpty()) {
            return AutocompleteState(
                replaceStart = lineStart + hashIndexInPrefix,
                replaceEnd = safeCursor,
                header = "Tags do Vault",
                items = matchedTags.map { AutocompleteSuggestion.TagOption(it) }
            )
        }
    }

    // 3. Slash commands /...
    val slashMatch = slashAutocompleteRegex.find(prefix)
    if (slashMatch != null) {
        val typed = slashMatch.groupValues[1].lowercase(Locale.ROOT)
        val slashIdx = prefix.lastIndexOf('/')
        val cmds = editorSlashCommands.filter {
            typed == "/" || it.command.startsWith(typed) || it.label.contains(typed.drop(1), true)
        }
        if (cmds.isNotEmpty()) {
            return AutocompleteState(
                replaceStart = lineStart + slashIdx,
                replaceEnd = safeCursor,
                header = "Comandos /",
                items = cmds.map { AutocompleteSuggestion.SlashOption(it) }
            )
        }
    }

    return null
}

private fun applyAutocompleteSelection(
    autocomplete: AutocompleteState,
    suggestion: AutocompleteSuggestion,
    body: TextFieldValue,
    onApply: (TextFieldValue) -> Unit,
    onBind: (String, Long) -> Unit,
    onOpenTemplatePicker: () -> Unit
) {
    val start = autocomplete.replaceStart.coerceIn(0, body.text.length)
    val cursorEnd = body.selection.start.coerceIn(start, body.text.length)
    val baseEnd = maxOf(autocomplete.replaceEnd, cursorEnd).coerceIn(start, body.text.length)
    when (suggestion) {
        is AutocompleteSuggestion.WikiNote -> {
            val end = if (body.text.startsWith("]]", baseEnd)) baseEnd + 2 else baseEnd
            val escapedTitle = NoteReferences.escape(suggestion.note.title)
            val raw = if (suggestion.viaAlias != null) "$escapedTitle|${NoteReferences.escape(suggestion.viaAlias)}" else escapedTitle
            val inserted = "[[$raw]]"
            val updated = body.text.replaceRange(start, end, inserted)
            onBind(raw, suggestion.note.id)
            onApply(TextFieldValue(updated, TextRange(start + inserted.length)))
        }
        is AutocompleteSuggestion.WikiHeading -> {
            val end = if (body.text.startsWith("]]", baseEnd)) baseEnd + 2 else baseEnd
            val prefix = suggestion.targetTitle?.let { NoteReferences.escape(it) }.orEmpty()
            val inserted = "[[$prefix#${suggestion.heading.title}]]"
            val updated = body.text.replaceRange(start, end, inserted)
            onApply(TextFieldValue(updated, TextRange(start + inserted.length)))
        }
        is AutocompleteSuggestion.TagOption -> {
            val inserted = "#${suggestion.tag} "
            val updated = body.text.replaceRange(start, baseEnd, inserted)
            onApply(TextFieldValue(updated, TextRange(start + inserted.length)))
        }
        is AutocompleteSuggestion.SlashOption -> {
            val cmd = suggestion.cmd
            val updated = body.text.replaceRange(start, baseEnd, cmd.snippet)
            val nextCursor = (start + cmd.snippet.length - cmd.cursorBackOffset).coerceIn(0, updated.length)
            onApply(TextFieldValue(updated, TextRange(nextCursor)))
            if (cmd.isTemplatePicker) onOpenTemplatePicker()
        }
    }
}

@Composable
private fun InlineAutocompletePopup(
    state: AutocompleteState,
    onSelect: (AutocompleteSuggestion) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        shape = studyCardShape(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                state.header,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                state.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(item) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (item) {
                            is AutocompleteSuggestion.WikiNote -> {
                                Icon(Icons.Default.Description, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(item.note.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val folder = item.folder
                                    val sub = listOfNotNull(item.viaAlias?.let { "apelido: $it" }, folder?.let { "📁 $it" }).joinToString(" · ")
                                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                            is AutocompleteSuggestion.WikiHeading -> {
                                Text("H${item.heading.level}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(item.heading.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            is AutocompleteSuggestion.TagOption -> {
                                Icon(Icons.Default.Tag, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.secondary)
                                Text("#${item.tag}", style = MaterialTheme.typography.bodySmall)
                            }
                            is AutocompleteSuggestion.SlashOption -> {
                                Text(item.cmd.command, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(item.cmd.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(item.cmd.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineSheetDialog(
    outline: List<MarkdownHeading>,
    onDismiss: () -> Unit,
    onSelectHeading: (MarkdownHeading) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sumário / Outline (${outline.size})") },
        text = {
            if (outline.isEmpty()) {
                Text("Nenhum cabeçalho (#, ##, ###) encontrado nesta nota.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    outline.forEach { heading ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectHeading(heading) }
                                .padding(start = ((heading.level - 1) * 14).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    "H${heading.level}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                heading.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (heading.level <= 2) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun SnapshotsDialog(
    snapshots: List<NoteSnapshot>,
    onDismiss: () -> Unit,
    onRestore: (NoteSnapshot) -> Unit
) {
    var selectedSnapshot by remember { mutableStateOf(snapshots.firstOrNull()) }
    var confirmRestore by remember { mutableStateOf<NoteSnapshot?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(14.dp),
            shape = studyCardShape(),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Histórico de Versões", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${snapshots.size} snapshots salvos automaticamente", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onDismiss) { Icon(Icons.Default.Close, "Fechar") }
                }

                if (snapshots.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Ainda não há versões anteriores salvas para esta nota.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LazyColumn(
                            Modifier.weight(0.45f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(snapshots, key = { it.id }) { snap ->
                                val isSel = selectedSnapshot?.id == snap.id
                                StudyCard(
                                    onClick = { selectedSnapshot = snap },
                                    containerColorOverride = if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                formatEpochMillis(snap.savedAt, snapshotFullFormatter),
                                                fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                "${snap.title} · rev ${snap.revision} · ${snap.markdown.length} chars",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        TextButton(onClick = { confirmRestore = snap }) { Text("Restaurar") }
                                    }
                                }
                            }
                        }

                        selectedSnapshot?.let { previewSnap ->
                            Surface(
                                Modifier.weight(0.55f).fillMaxWidth(),
                                shape = studyControlShape(),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(previewSnap.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    HorizontalDivider()
                                    Text(
                                        previewSnap.markdown.ifBlank { "(nota vazia)" },
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmRestore?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restaurar esta versão?") },
            text = { Text("O conteúdo atual da nota será substituído pelo snapshot de ${formatEpochMillis(target.savedAt, snapshotShortFormatter)}.") },
            confirmButton = {
                Button(onClick = { onRestore(target); confirmRestore = null }, shape = studyControlShape()) {
                    Text("Restaurar")
                }
            },
            dismissButton = { TextButton({ confirmRestore = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun NoteFlashcardsHub(
    noteFlashcards: List<Flashcard>,
    onReviewCards: ((List<Flashcard>) -> Unit)?
) {
    val secInk = MaterialTheme.colorScheme.onSecondaryContainer
    StudyCard(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        containerColorOverride = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.School, null, tint = secInk)
                    Text(
                        "Flashcards desta nota · ${noteFlashcards.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = secInk
                    )
                }
                onReviewCards?.let { startReview ->
                    FilledTonalButton(
                        onClick = { startReview(noteFlashcards) },
                        modifier = Modifier.align(Alignment.CenterVertically),
                        shape = studyControlShape(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("Revisar agora", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            noteFlashcards.forEach { card ->
                Surface(Modifier.fillMaxWidth(), shape = studyControlShape(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(card.front, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when {
                            card.suspended -> "Suspenso"
                            card.reviewState == "NEW" -> "Novo"
                            card.intervalDays < 21 -> "Jovem (${card.intervalDays}d)"
                            else -> "Maduro (${card.intervalDays}d)"
                        }
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteTasksSection(
    noteId: Long,
    body: TextFieldValue,
    noteTasks: List<StudyTask>,
    subjects: List<Subject>,
    inferredSubjectId: Long?,
    studiedMinutesByTaskId: Map<Long, Long>,
    newTask: String,
    onNewTaskChange: (String) -> Unit,
    onToggleTask: (StudyTask) -> Unit,
    onUpdateBody: (TextFieldValue) -> Unit,
    onSaveTask: ((StudyTask) -> Unit)?,
    onAddTask: (String) -> Unit,
    onStartTask: ((StudyTask) -> Unit)?,
    onFlush: (() -> Unit) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val semantic = LocalStudySemanticColors.current
    val scheme = MaterialTheme.colorScheme
    val uncoveredChecklists = remember(body.text, noteTasks, subjects) {
        NoteChecklists.uncoveredLines(body.text, noteTasks, subjects).filter { !it.checked }
    }
    val addQuickTaskToNote: (String) -> Unit = { rawText ->
        val trimmed = rawText.trim()
        if (trimmed.isNotBlank()) {
            val created = quickTaskFrom(trimmed, subjects, noteId).let { task ->
                if (task.subjectId == null && inferredSubjectId != null) task.copy(subjectId = inferredSubjectId)
                else task
            }
            if (onSaveTask != null) onSaveTask(created) else onAddTask(trimmed)
        }
    }

    StudyCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tarefas desta nota", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val openCount = noteTasks.count { !it.completed }
                if (noteTasks.isNotEmpty()) {
                    Text("$openCount pendentes · ${noteTasks.size} total", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
            noteTasks.forEach { task ->
                val rawPriority = when (task.priority) {
                    1 -> scheme.error
                    2 -> semantic.warning
                    3 -> semantic.info
                    else -> scheme.onSurfaceVariant
                }
                val pColor = if (contrastRatio(rawPriority, scheme.surfaceContainerLow) >= 4.5) rawPriority
                else lerp(rawPriority, scheme.onSurface, 0.4f)
                val dateLabel = if (!task.hasDueDate) "Inbox" else taskDateLabel(task.dueAt)
                val isOverdue = !task.completed && task.hasDueDate &&
                    Instant.ofEpochMilli(task.dueAt).atZone(zone).toLocalDate().isBefore(today)
                val studiedMin = studiedMinutesByTaskId[task.id] ?: 0L
                val (subDone, subTotal) = TaskSubtasks.summary(task.subtasksJson)
                val taskTags = TaskTagPresets.parseTags(task.tags)

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = task.completed,
                        onCheckedChange = {
                            val synced = NoteChecklists.syncTaskCompletion(
                                markdown = body.text,
                                taskTitle = task.title,
                                completed = !task.completed,
                                subjects = subjects
                            )
                            if (synced != body.text) {
                                onUpdateBody(
                                    TextFieldValue(
                                        synced,
                                        TextRange(
                                            body.selection.start.coerceIn(0, synced.length),
                                            body.selection.end.coerceIn(0, synced.length)
                                        )
                                    )
                                )
                            }
                            onToggleTask(task)
                        },
                        colors = CheckboxDefaults.colors(uncheckedColor = pColor)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            task.title,
                            fontWeight = FontWeight.Medium,
                            textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                            color = if (task.completed) scheme.onSurfaceVariant else scheme.onSurface
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            if (task.priority < 4) {
                                Text("P${task.priority}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = pColor)
                            }
                            Text(
                                dateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isOverdue -> scheme.error
                                    !task.hasDueDate -> scheme.primary
                                    else -> scheme.onSurfaceVariant
                                }
                            )
                            if (task.estimatedMinutes > 0) {
                                Text("~${task.estimatedMinutes}m", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                            if (studiedMin > 0L) {
                                Text("⏱ ${studiedMin}m", style = MaterialTheme.typography.labelSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                            if (subTotal > 0) {
                                Text("$subDone/$subTotal", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                            taskTags.take(3).forEach { tag ->
                                Text(tag, style = MaterialTheme.typography.labelSmall, color = scheme.secondary)
                            }
                        }
                    }
                    if (!task.completed && onStartTask != null) {
                        FilledTonalIconButton(
                            onClick = { onFlush { onStartTask(task) } },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, "Estudar tarefa", Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (uncoveredChecklists.isNotEmpty()) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                Text("Checklists da nota sem tarefa (${uncoveredChecklists.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = scheme.primary)
                uncoveredChecklists.forEach { line ->
                    Surface(modifier = Modifier.fillMaxWidth(), shape = studyControlShape(), color = scheme.surfaceContainerLow) {
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            val stackBtn = maxWidth < 310.dp * LocalDensity.current.fontScale
                            if (stackBtn) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.CheckBoxOutlineBlank, null, modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                                        Text(line.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    FilledTonalButton(onClick = { addQuickTaskToNote(line.text) }, shape = studyControlShape(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                        Text("Converter em Tarefa", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            } else {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CheckBoxOutlineBlank, null, modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                                    Text(line.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    FilledTonalButton(onClick = { addQuickTaskToNote(line.text) }, shape = studyControlShape(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                        Text("Converter em Tarefa", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                newTask, onNewTaskChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Nova tarefa (ex: Revisar p1 ~25m @revisao)") },
                shape = studyControlShape(),
                trailingIcon = {
                    IconButton({ addQuickTaskToNote(newTask); onNewTaskChange("") }, enabled = newTask.isNotBlank()) {
                        Icon(Icons.Default.Add, "Adicionar tarefa")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (newTask.isNotBlank()) { addQuickTaskToNote(newTask); onNewTaskChange("") }
                })
            )
        }
    }
}

// ---------- Properties Editor Pro Component ----------

@Composable
private fun PropertiesEditor(
    properties: NoteProperties,
    onChange: (NoteProperties) -> Unit
) {
    var newAlias by remember { mutableStateOf("") }
    var newTag by remember { mutableStateOf("") }
    var folderField by remember {
        mutableStateOf(TextFieldValue(properties.folder.orEmpty(), TextRange(properties.folder.orEmpty().length)))
    }
    LaunchedEffect(properties.folder) {
        val incoming = properties.folder.orEmpty()
        if (incoming != folderField.text.trim()) {
            folderField = TextFieldValue(incoming, TextRange(incoming.length))
        }
    }

    var showAddCustom by remember { mutableStateOf(false) }
    var customKey by remember { mutableStateOf("") }
    var customType by remember { mutableStateOf(NotePropertyType.TEXT) }
    var customValue by remember { mutableStateOf("") }
    var customBool by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text("Propriedades & Metadados", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        // Folder (preserves trailing spaces while typing)
        OutlinedTextField(
            value = folderField,
            onValueChange = { incoming ->
                folderField = incoming
                val trimmed = incoming.text.trim().ifBlank { null }
                if (trimmed != properties.folder) {
                    onChange(properties.copy(folder = trimmed))
                }
            },
            label = { Text("Pasta / Área (use / para subpastas)") },
            placeholder = { Text("ex.: Direito/Constitucional") },
            singleLine = true,
            shape = studyControlShape(),
            modifier = Modifier.fillMaxWidth()
        )

        // Aliases
        Text("Apelidos / Aliases", style = MaterialTheme.typography.labelMedium)
        if (properties.aliases.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                properties.aliases.forEach { alias ->
                    InputChip(
                        selected = true,
                        onClick = { onChange(properties.copy(aliases = properties.aliases - alias)) },
                        label = { Text(alias, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Default.Close, "Remover", Modifier.size(14.dp)) }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                newAlias, { newAlias = it },
                label = { Text("Adicionar apelido") },
                singleLine = true,
                shape = studyControlShape(),
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    if (newAlias.isNotBlank()) {
                        onChange(properties.copy(aliases = (properties.aliases + newAlias.trim()).distinct()))
                        newAlias = ""
                    }
                },
                enabled = newAlias.isNotBlank()
            ) { Icon(Icons.Default.Add, "Adicionar") }
        }

        // Tags
        Text("Tags da nota", style = MaterialTheme.typography.labelMedium)
        if (properties.tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                properties.tags.forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { onChange(properties.copy(tags = properties.tags - tag)) },
                        label = { Text("#$tag", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Default.Close, "Remover", Modifier.size(14.dp)) }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                newTag, { newTag = it },
                label = { Text("Adicionar tag (ex: mat/alg)") },
                singleLine = true,
                shape = studyControlShape(),
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val clean = newTag.removePrefix("#").trim()
                    if (clean.isNotBlank()) {
                        onChange(properties.copy(tags = (properties.tags + clean).distinct()))
                        newTag = ""
                    }
                },
                enabled = newTag.isNotBlank()
            ) { Icon(Icons.Default.Add, "Adicionar") }
        }

        // Custom Properties
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Propriedades customizadas", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { showAddCustom = !showAddCustom }) {
                Icon(if (showAddCustom) Icons.Default.Close else Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (showAddCustom) "Fechar" else "Adicionar propriedade")
            }
        }

        properties.custom.forEach { (key, rawVal) ->
            val inferred = NoteFrontmatter.inferPropertyType(rawVal)
            val typeLabel = when (inferred) {
                NotePropertyType.TEXT -> "Texto"
                NotePropertyType.NUMBER -> "Número"
                NotePropertyType.DATE -> "Data"
                NotePropertyType.BOOL -> "Bool"
                NotePropertyType.LIST -> "Lista"
            }
            var editField by remember(key) {
                mutableStateOf(TextFieldValue(rawVal, TextRange(rawVal.length)))
            }
            LaunchedEffect(rawVal) {
                if (rawVal != editField.text.trim()) {
                    editField = TextFieldValue(rawVal, TextRange(rawVal.length))
                }
            }
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.widthIn(min = 72.dp, max = 120.dp)) {
                        Text(key, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(typeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (inferred == NotePropertyType.BOOL) {
                        val boolVal = rawVal.equals("true", ignoreCase = true)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = boolVal,
                            onCheckedChange = { checked ->
                                onChange(properties.copy(custom = properties.custom + (key to checked.toString())))
                            }
                        )
                    } else {
                        OutlinedTextField(
                            value = editField,
                            onValueChange = { incoming ->
                                editField = incoming
                                val cleaned = incoming.text.trim()
                                if (cleaned.isNotEmpty() && cleaned != rawVal) {
                                    onChange(properties.copy(custom = properties.custom + (key to cleaned)))
                                }
                            },
                            singleLine = true,
                            shape = studyControlShape(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconButton(
                        onClick = { onChange(properties.copy(custom = properties.custom - key)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, "Remover propriedade", Modifier.size(16.dp))
                    }
                }
            }
        }

        AnimatedVisibility(showAddCustom) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = studyControlShape(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        customKey,
                        { customKey = it },
                        label = { Text("Nome da propriedade (ex: status, fonte, nota)") },
                        singleLine = true,
                        shape = studyControlShape(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            NotePropertyType.TEXT to "Texto",
                            NotePropertyType.NUMBER to "Número",
                            NotePropertyType.DATE to "Data",
                            NotePropertyType.BOOL to "Booleano",
                            NotePropertyType.LIST to "Lista"
                        ).forEach { (type, label) ->
                            FilterChip(
                                selected = customType == type,
                                onClick = { customType = type },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    if (customType == NotePropertyType.BOOL) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Valor:", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = customBool, onCheckedChange = { customBool = it })
                            Text(if (customBool) "true" else "false", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        OutlinedTextField(
                            customValue,
                            { customValue = it },
                            label = {
                                Text(
                                    when (customType) {
                                        NotePropertyType.DATE -> "Data (ex: 2026-11-14)"
                                        NotePropertyType.LIST -> "Itens separados por vírgula"
                                        NotePropertyType.NUMBER -> "Valor numérico"
                                        else -> "Valor"
                                    }
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (customType == NotePropertyType.NUMBER) KeyboardType.Decimal else KeyboardType.Text
                            ),
                            singleLine = true,
                            shape = studyControlShape(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                val cleanKey = customKey.trim().replace(":", "")
                                val serialized = when (customType) {
                                    NotePropertyType.BOOL -> customBool.toString()
                                    NotePropertyType.LIST -> {
                                        val items = customValue.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                        NoteFrontmatter.formatListProperty(items)
                                    }
                                    else -> customValue.trim()
                                }
                                if (cleanKey.isNotEmpty() && serialized.isNotEmpty()) {
                                    onChange(properties.copy(custom = properties.custom + (cleanKey to serialized)))
                                    customKey = ""
                                    customValue = ""
                                    showAddCustom = false
                                }
                            },
                            enabled = customKey.isNotBlank() && (customType == NotePropertyType.BOOL || customValue.isNotBlank()),
                            shape = studyControlShape()
                        ) { Text("Adicionar") }
                    }
                }
            }
        }
    }
}

// ---------- Template Picker Dialog (2.0) ----------

@Composable
fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (NoteTemplate) -> Unit,
    notes: List<Note> = emptyList(),
    titleHint: String = ""
) {
    val userTemplates = remember(notes) { userTemplatesFromNotes(notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("Escolher modelo de nota") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (userTemplates.isNotEmpty()) {
                    Text(
                        "Modelos do Vault (#template)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    userTemplates.forEach { template ->
                        StudyCard(onClick = { onSelect(template) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(template.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                                Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                }
                Text(
                    "Modelos padrão",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                StandardNoteTemplates.all.forEach { template ->
                    StudyCard(onClick = { onSelect(template) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(template.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun LinkPickerDialog(notes: List<Note>, currentId: Long?, onDismiss: () -> Unit, onPick: (Note) -> Unit, onNew: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = notes.filter { it.id != currentId && it.title.contains(query, true) }.take(30)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("Linkar nota") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    label = { Text("Buscar ou digitar um título") },
                    shape = studyControlShape(),
                    modifier = Modifier.fillMaxWidth()
                )
                Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    if (query.isNotBlank() && notes.none { it.title.equals(query.trim(), true) }) {
                        TextButton({ onNew(query.trim()) }) { Text("Link para “${query.trim()}”") }
                    }
                    matches.forEach { match ->
                        val duplicates = notes.filter { it.title.equals(match.title, true) }.sortedBy { it.id }
                        val suffix = if (duplicates.size > 1) " (${duplicates.indexOfFirst { it.id == match.id } + 1})" else ""
                        TextButton({ onPick(match) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(match.title + suffix, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    match.markdown.take(80).ifBlank { "Nota vazia" },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Fechar") } }
    )
}

// ---------- prévia em markdown enriquecida (Obsidian Pro) ----------

@Composable
private fun CardDestination(label: String, empty: String, options: List<Pair<Long, String>>, selected: Long?, choose: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton({ expanded = true }) { Text("$label · ${options.find { it.first == selected }?.second ?: empty}") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(empty) }, onClick = { choose(null); expanded = false })
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { choose(id); expanded = false })
            }
        }
    }
}
