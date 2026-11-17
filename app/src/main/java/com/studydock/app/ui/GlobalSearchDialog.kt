package com.studydock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DIARY_BR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale("pt", "BR"))

private data class PaletteNoteMatch(
    val note: Note,
    val tags: List<String>,
    val folder: String?,
    val snippet: String
)

private data class GlobalSearchResults(
    val matchedCommands: List<QuickCommandItem> = emptyList(),
    val matchedNotes: List<PaletteNoteMatch> = emptyList(),
    val matchedCards: List<Flashcard> = emptyList(),
    val matchedTasks: List<StudyTask> = emptyList(),
    val matchedSubjects: List<Subject> = emptyList(),
    val matchedBooks: List<Book> = emptyList(),
    val highlightsByBookId: Map<Long, List<BookHighlight>> = emptyMap(),
    val matchedPlaylists: List<MusicPlaylist> = emptyList(),
    val matchedCustomMixes: List<CustomSoundMix> = emptyList(),
    val matchedGoals: List<Goal> = emptyList(),
    val matchedJournalEntries: List<JournalEntry> = emptyList(),
    val matchedMistakes: List<Mistake> = emptyList(),
    val exactNoteMatch: Note? = null
)

enum class PaletteScope(val label: String) {
    ALL("Tudo"),
    COMMANDS("⚡ Comandos"),
    NOTES("🧠 Notas"),
    CARDS("🃏 Cartões"),
    TASKS("✅ Tarefas"),
    SUBJECTS_BOOKS("📚 Matérias & Livros"),
    AUDIO("🎵 Sons & Playlists"),
    GOALS("🎯 Metas"),
    DIARY("📔 Diário"),
    MISTAKES("🩺 Erros")
}

data class QuickCommandItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val badge: String,
    val onRun: () -> Unit
)

@Composable
fun UniversalSearchHeaderBar(
    dueCardsCount: Int,
    notesCount: Int,
    onOpenPalette: () -> Unit,
    onQuickDailyNote: () -> Unit,
    onQuickCards: () -> Unit
) {
    SlimGlobalSearchPill(
        dueCardsCount = dueCardsCount,
        showKeyboardHint = true,
        onOpenPalette = onOpenPalette,
        onQuickCards = onQuickCards
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlobalSearchAndCommandPaletteDialog(
    notes: List<Note>,
    flashcards: List<Flashcard>,
    tasks: List<StudyTask>,
    subjects: List<Subject>,
    books: List<Book>,
    decks: List<CardDeck>,
    commands: List<QuickCommandItem>,
    goals: List<Goal> = emptyList(),
    journalEntries: List<JournalEntry> = emptyList(),
    mistakes: List<Mistake> = emptyList(),
    onDismiss: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onCreateNoteWithTitle: (String) -> Unit,
    onOpenFlashcardsForSubject: (Long) -> Unit,
    onReviewCardsImmediate: (List<Flashcard>) -> Unit,
    onToggleTask: (StudyTask) -> Unit,
    onFocusTask: (StudyTask) -> Unit,
    onOpenSubject: (Long) -> Unit,
    onStartStudySubject: (Long) -> Unit,
    onOpenBook: (Long?) -> Unit,
    onReadBook: (Long) -> Unit,
    onOpenGoal: (Goal) -> Unit = {},
    onOpenJournalDay: (Long) -> Unit = {},
    onOpenSubjectMistakes: (Long) -> Unit = {},
    onOpenBookDetail: ((Long) -> Unit)? = null,
    onOpenPlayer: () -> Unit = {},
    initialQuery: String = "",
    onOpenMathArena: () -> Unit = {},
    onOpenReadEra: () -> Unit = {},
    onOpenBookReader: ((Book) -> Unit)? = null
) {
    val context = LocalContext.current
    val themeOpener = LocalThemeStudioOpener.current
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var debouncedQuery by remember(initialQuery) { mutableStateOf(initialQuery) }
    var scopeFilter by remember { mutableStateOf(PaletteScope.ALL) }
    val focusRequester = remember { FocusRequester() }
    val subjectsById = remember(subjects) { subjects.associateBy { it.id } }
    val decksById = remember(decks) { decks.associateBy { it.id } }
    var audioPlaylists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var customSoundMixes by remember { mutableStateOf<List<CustomSoundMix>>(emptyList()) }

    LaunchedEffect(context) {
        val loadedAudio = withContext(Dispatchers.IO) {
            FocusAudioStore.readPlaylists(context) to FocusAudioStore.readCustomMixes(context)
        }
        audioPlaylists = loadedAudio.first
        customSoundMixes = loadedAudio.second
    }

    LaunchedEffect(query) {
        if (query == debouncedQuery) return@LaunchedEffect
        delay(120L)
        debouncedQuery = query
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val preferredReaderBook = remember(books) {
        books.filter { it.status == "READING" }
            .maxByOrNull { maxOf(it.lastReadAt, it.updatedAt) }
            ?: books.maxByOrNull { maxOf(it.lastReadAt, it.updatedAt) }
    }

    val effectiveCommands = remember(commands, preferredReaderBook, onOpenMathArena, onOpenReadEra, onOpenBookReader, onOpenBook) {
        val existingIds = commands.map { it.id }.toSet()
        val builtIn = buildList {
            if ("open_math_arena" !in existingIds) {
                add(
                    QuickCommandItem(
                        id = "open_math_arena",
                        title = "Arena de Matemática",
                        subtitle = "Equações visuais, frações, raízes e treino cronometrado",
                        icon = Icons.Default.Calculate,
                        badge = "Matemática",
                        onRun = onOpenMathArena
                    )
                )
            }
            if ("open_readera" !in existingIds) {
                val target = preferredReaderBook
                add(
                    QuickCommandItem(
                        id = "open_readera",
                        title = "Modo Leitura (ReadEra)",
                        subtitle = target?.let { "Abrir \"${it.title}\" no leitor imersivo ReadEra" }
                            ?: "Abrir estante para ler PDF, TXT, MD ou fichamento no modo ReadEra",
                        icon = Icons.AutoMirrored.Filled.ChromeReaderMode,
                        badge = "ReadEra",
                        onRun = {
                            when {
                                target != null && onOpenBookReader != null -> onOpenBookReader(target)
                                target != null -> onOpenReadEra()
                                else -> onOpenBook(null)
                            }
                        }
                    )
                )
            }
        }
        commands + builtIn
    }

    val cleanQuery = query.trim()
    val isCommandPrefix = cleanQuery.startsWith(">")
    val searchTerm = if (isCommandPrefix) cleanQuery.removePrefix(">").trim() else cleanQuery

    val useObsidianQuery = remember(searchTerm) { usesObsidianSearch(searchTerm) }
    val useCardQuery = remember(searchTerm) { usesAnkiCardQuery(searchTerm) }

    val debouncedClean = debouncedQuery.trim()
    val debouncedCommandPrefix = debouncedClean.startsWith(">")
    val debouncedTerm = if (debouncedCommandPrefix) debouncedClean.removePrefix(">").trim() else debouncedClean

    var searchResults by remember { mutableStateOf(GlobalSearchResults()) }

    LaunchedEffect(
        debouncedTerm,
        debouncedCommandPrefix,
        scopeFilter,
        effectiveCommands,
        notes,
        flashcards,
        decks,
        tasks,
        subjects,
        subjectsById,
        books,
        audioPlaylists,
        customSoundMixes,
        goals,
        journalEntries,
        mistakes
    ) {
        searchResults = withContext(Dispatchers.Default) {
            val term = debouncedTerm
            val cmdPrefix = debouncedCommandPrefix
            val obsidianQuery = usesObsidianSearch(term)
            val cardQuery = usesAnkiCardQuery(term)

            val matchedCommands = if (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.COMMANDS && !cmdPrefix) {
                emptyList()
            } else {
                effectiveCommands.filter {
                    term.isEmpty() ||
                        it.title.contains(term, ignoreCase = true) ||
                        it.subtitle.contains(term, ignoreCase = true) ||
                        it.badge.contains(term, ignoreCase = true)
                }.take(if (scopeFilter == PaletteScope.COMMANDS || cmdPrefix) 20 else 5)
            }

            val rawMatchedNotes = if (cmdPrefix || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.NOTES)) {
                emptyList()
            } else if (term.isEmpty()) {
                notes.take(if (scopeFilter == PaletteScope.NOTES) 35 else 8)
            } else if (obsidianQuery) {
                ObsidianSearchEngine.search(notes, term)
                    .take(if (scopeFilter == PaletteScope.NOTES) 35 else 8)
            } else {
                notes.filter { note ->
                    note.title.contains(term, ignoreCase = true) ||
                        note.markdown.contains(term, ignoreCase = true)
                }.take(if (scopeFilter == PaletteScope.NOTES) 35 else 8)
            }
            val matchedNotes = rawMatchedNotes.map { note ->
                val (props, body) = fastPropertiesAndBody(note.markdown)
                PaletteNoteMatch(
                    note = note,
                    tags = extractNoteTagsFromParts(props.tags, body),
                    folder = props.folder,
                    snippet = extractSearchSnippet(note.markdown, term)
                )
            }

            val matchedCards = if (cmdPrefix || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.CARDS)) {
                emptyList()
            } else if (term.isEmpty()) {
                flashcards.take(if (scopeFilter == PaletteScope.CARDS) 35 else 6)
            } else if (cardQuery) {
                CardQueryEngine.filter(flashcards, term, decks)
                    .take(if (scopeFilter == PaletteScope.CARDS) 35 else 6)
            } else {
                flashcards.filter { card ->
                    card.front.contains(term, ignoreCase = true) ||
                        card.back.contains(term, ignoreCase = true)
                }.take(if (scopeFilter == PaletteScope.CARDS) 35 else 6)
            }

            val matchedTasks = if (cmdPrefix || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.TASKS)) {
                emptyList()
            } else {
                tasks.filter { task ->
                    term.isEmpty() ||
                        task.title.contains(term, ignoreCase = true) ||
                        task.details.contains(term, ignoreCase = true)
                }.sortedWith(compareBy<StudyTask> { it.completed }.thenBy { it.dueAt })
                    .take(if (scopeFilter == PaletteScope.TASKS) 30 else 6)
            }

            val matchedSubjects = if (cmdPrefix || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.SUBJECTS_BOOKS)) {
                emptyList()
            } else {
                subjects.filter {
                    !it.archived && (
                        term.isEmpty() ||
                            it.name.contains(term, ignoreCase = true) ||
                            it.nextStep.orEmpty().contains(term, ignoreCase = true)
                        )
                }.take(8)
            }

            val inBookScope = !cmdPrefix && (scopeFilter == PaletteScope.ALL || scopeFilter == PaletteScope.SUBJECTS_BOOKS)
            val highlightsByBookId = if (!inBookScope || books.isEmpty()) {
                emptyMap()
            } else {
                books.associate { it.id to BookHighlights.parse(it) }
            }

            val matchedBooks = if (!inBookScope) {
                emptyList()
            } else {
                books.filter { book ->
                    val highlights = highlightsByBookId[book.id].orEmpty()
                    term.isEmpty() ||
                        book.title.contains(term, ignoreCase = true) ||
                        book.author.contains(term, ignoreCase = true) ||
                        book.categoryTag.contains(term, ignoreCase = true) ||
                        book.currentChapter.contains(term, ignoreCase = true) ||
                        book.quotes.contains(term, ignoreCase = true) ||
                        highlights.any { hl ->
                            hl.text.contains(term, ignoreCase = true) ||
                                hl.note.contains(term, ignoreCase = true) ||
                                hl.chapter.contains(term, ignoreCase = true)
                        }
                }.take(if (scopeFilter == PaletteScope.SUBJECTS_BOOKS) 25 else 8)
            }

            val matchedPlaylists = if (cmdPrefix || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.AUDIO)) {
                emptyList()
            } else {
                audioPlaylists.filter { playlist ->
                    term.isEmpty() ||
                        playlist.name.contains(term, ignoreCase = true) ||
                        playlist.tracks.any { track ->
                            track.title.contains(term, ignoreCase = true) ||
                                track.artist.contains(term, ignoreCase = true)
                        }
                }.take(if (scopeFilter == PaletteScope.AUDIO) 20 else 4)
            }

            val matchedCustomMixes = if (cmdPrefix || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.AUDIO)) {
                emptyList()
            } else {
                customSoundMixes.filter { mix ->
                    term.isEmpty() ||
                        mix.name.contains(term, ignoreCase = true) ||
                        mix.volumes.keys.any { it.contains(term, ignoreCase = true) }
                }.take(if (scopeFilter == PaletteScope.AUDIO) 20 else 4)
            }

            val matchedGoals = if (cmdPrefix || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.GOALS)) {
                emptyList()
            } else {
                goals.filter { goal ->
                    val metricLabel = GoalMetricType.fromId(goal.metricType).label
                    val subjName = goal.subjectId?.let { subjectsById[it]?.name }.orEmpty()
                    term.isEmpty() ||
                        goal.title.contains(term, ignoreCase = true) ||
                        metricLabel.contains(term, ignoreCase = true) ||
                        goal.metricUnit.contains(term, ignoreCase = true) ||
                        subjName.contains(term, ignoreCase = true)
                }.sortedWith(compareBy<Goal> { it.completed }.thenByDescending { it.pinned }.thenBy { it.targetAt })
                    .take(if (scopeFilter == PaletteScope.GOALS) 30 else 6)
            }

            val matchedJournalEntries = if (cmdPrefix || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.DIARY)) {
                emptyList()
            } else {
                journalEntries.filter { entry ->
                    val dateBr = DiaryAnalytics.localDateFromDayKey(entry.dayKey).format(DIARY_BR_FORMATTER)
                    term.isEmpty() ||
                        entry.title.contains(term, ignoreCase = true) ||
                        entry.body.contains(term, ignoreCase = true) ||
                        entry.tags.contains(term, ignoreCase = true) ||
                        entry.mood.contains(term, ignoreCase = true) ||
                        dateBr.contains(term, ignoreCase = true)
                }.sortedByDescending { it.dayKey }
                    .take(if (scopeFilter == PaletteScope.DIARY) 30 else 6)
            }

            val matchedMistakes = if (cmdPrefix || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.MISTAKES)) {
                emptyList()
            } else {
                mistakes.filter { mistake ->
                    val subjName = subjectsById[mistake.subjectId]?.name.orEmpty()
                    term.isEmpty() ||
                        mistake.topic.contains(term, ignoreCase = true) ||
                        mistake.question.contains(term, ignoreCase = true) ||
                        mistake.reason.contains(term, ignoreCase = true) ||
                        mistake.solution.contains(term, ignoreCase = true) ||
                        subjName.contains(term, ignoreCase = true)
                }.sortedWith(compareBy<Mistake> { it.resolved }.thenByDescending { it.createdAt })
                    .take(if (scopeFilter == PaletteScope.MISTAKES) 30 else 6)
            }

            val exactNote = if (cmdPrefix || term.isEmpty() || (scopeFilter != PaletteScope.ALL && scopeFilter != PaletteScope.NOTES)) {
                null
            } else {
                notes.firstOrNull { it.title.equals(term, ignoreCase = true) }
            }

            GlobalSearchResults(
                matchedCommands = matchedCommands,
                matchedNotes = matchedNotes,
                matchedCards = matchedCards,
                matchedTasks = matchedTasks,
                matchedSubjects = matchedSubjects,
                matchedBooks = matchedBooks,
                highlightsByBookId = highlightsByBookId,
                matchedPlaylists = matchedPlaylists,
                matchedCustomMixes = matchedCustomMixes,
                matchedGoals = matchedGoals,
                matchedJournalEntries = matchedJournalEntries,
                matchedMistakes = matchedMistakes,
                exactNoteMatch = exactNote
            )
        }
    }

    val matchedCommands = searchResults.matchedCommands
    val matchedNotes = searchResults.matchedNotes
    val matchedCards = searchResults.matchedCards
    val matchedTasks = searchResults.matchedTasks
    val matchedSubjects = searchResults.matchedSubjects
    val matchedBooks = searchResults.matchedBooks
    val highlightsByBookId = searchResults.highlightsByBookId
    val matchedPlaylists = searchResults.matchedPlaylists
    val matchedCustomMixes = searchResults.matchedCustomMixes
    val matchedGoals = searchResults.matchedGoals
    val matchedJournalEntries = searchResults.matchedJournalEntries
    val matchedMistakes = searchResults.matchedMistakes
    val exactNoteMatch = searchResults.exactNoteMatch

    val canCreateNote = searchTerm.length >= 2 &&
        !isCommandPrefix &&
        !useObsidianQuery &&
        !useCardQuery &&
        scopeFilter != PaletteScope.COMMANDS &&
        exactNoteMatch == null
    val hasAnyMatch = matchedCommands.isNotEmpty() ||
        matchedNotes.isNotEmpty() ||
        matchedCards.isNotEmpty() ||
        matchedTasks.isNotEmpty() ||
        matchedSubjects.isNotEmpty() ||
        matchedBooks.isNotEmpty() ||
        matchedPlaylists.isNotEmpty() ||
        matchedCustomMixes.isNotEmpty() ||
        matchedGoals.isNotEmpty() ||
        matchedJournalEntries.isNotEmpty() ||
        matchedMistakes.isNotEmpty()
    val enterCreatesNote = canCreateNote &&
        (!hasAnyMatch || (scopeFilter == PaletteScope.NOTES && matchedNotes.isEmpty()))

    fun runPrimaryEnterAction(): Boolean {
        if (isCommandPrefix || scopeFilter == PaletteScope.COMMANDS) {
            val cmd = matchedCommands.firstOrNull()?.takeIf { searchTerm.isNotEmpty() } ?: return false
            onDismiss()
            cmd.onRun()
            return true
        }
        if (searchTerm.isEmpty()) return false
        exactNoteMatch?.let { note ->
            onDismiss()
            onOpenNote(note.id)
            return true
        }
        if (enterCreatesNote) {
            onDismiss()
            onCreateNoteWithTitle(searchTerm)
            return true
        }
        matchedCommands.firstOrNull()?.let { cmd ->
            onDismiss()
            cmd.onRun()
            return true
        }
        matchedNotes.firstOrNull()?.note?.let { note ->
            onDismiss()
            onOpenNote(note.id)
            return true
        }
        matchedCards.firstOrNull()?.let { card ->
            onDismiss()
            onOpenFlashcardsForSubject(card.subjectId ?: -1L)
            return true
        }
        matchedTasks.firstOrNull { !it.completed }?.let { task ->
            onDismiss()
            onFocusTask(task)
            return true
        }
        matchedTasks.firstOrNull()?.let { task ->
            onToggleTask(task)
            return true
        }
        matchedSubjects.firstOrNull()?.let { subj ->
            onDismiss()
            onOpenSubject(subj.id)
            return true
        }
        matchedBooks.firstOrNull()?.let { book ->
            onDismiss()
            if (onOpenBookDetail != null) onOpenBookDetail(book.id) else onOpenBook(book.subjectId)
            return true
        }
        if (matchedPlaylists.isNotEmpty() || matchedCustomMixes.isNotEmpty()) {
            onDismiss()
            onOpenPlayer()
            return true
        }
        matchedGoals.firstOrNull()?.let { goal ->
            onDismiss()
            onOpenGoal(goal)
            return true
        }
        matchedJournalEntries.firstOrNull()?.let { entry ->
            onDismiss()
            onOpenJournalDay(entry.dayKey)
            return true
        }
        matchedMistakes.firstOrNull()?.let { mistake ->
            onDismiss()
            onOpenSubjectMistakes(mistake.subjectId)
            return true
        }
        return false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val backdropInteraction = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            val isTabletSpotlight = maxWidth >= 680.dp
            val dialogModifier = if (isTabletSpotlight) {
                Modifier
                    .padding(top = (maxHeight * 0.06f).coerceIn(8.dp, 40.dp))
                    .widthIn(max = 680.dp)
                    .fillMaxWidth(0.86f)
                    .heightIn(min = 240.dp, max = (maxHeight * 0.80f).coerceAtLeast(280.dp))
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = (maxHeight * 0.94f).coerceAtLeast(240.dp))
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = backdropInteraction,
                        indication = null,
                        onClick = onDismiss
                    )
            )
            Surface(
                modifier = dialogModifier
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Escape || ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.K))
                        ) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
                shape = studyCardShape(isHero = true),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    // Search Header Input
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                "Buscar em tudo ou digite '>' para comandos...",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Clear, "Limpar busca")
                                    }
                                }
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, "Fechar")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (isCommandPrefix || scopeFilter == PaletteScope.COMMANDS) ImeAction.Go else ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { runPrimaryEnterAction() },
                            onGo = { runPrimaryEnterAction() },
                            onDone = { runPrimaryEnterAction() },
                            onSend = { runPrimaryEnterAction() }
                        ),
                        shape = studyControlShape(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                                    !event.isShiftPressed
                                ) {
                                    runPrimaryEnterAction()
                                    true
                                } else {
                                    false
                                }
                            }
                    )

                    // Scope Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PaletteScope.entries.forEach { item ->
                            FilterChip(
                                selected = scopeFilter == item,
                                onClick = { scopeFilter = item },
                                label = {
                                    Text(
                                        item.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }

                    // Quick action to create a note if query doesn't match an exact note title
                    if (canCreateNote) {
                        Surface(
                            onClick = {
                                onDismiss()
                                onCreateNoteWithTitle(searchTerm)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.NoteAdd,
                                    null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Criar nova nota Obsidian: \"$searchTerm\"",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                                ) {
                                    Text(
                                        if (enterCreatesNote) "Enter" else "Criar",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (query.isBlank() && scopeFilter == PaletteScope.ALL) {
                            item(key = "quick_actions_empty", contentType = "quick_actions") {
                                val themeCmd = effectiveCommands.firstOrNull { it.id == "theme_studio" }
                                val newNoteCmd = effectiveCommands.firstOrNull { it.id == "new_note" }
                                val timerCmd = effectiveCommands.firstOrNull { it.id == "toggle_timer" }
                                val dailyCmd = effectiveCommands.firstOrNull { it.id == "daily_note" }
                                val cardsCmd = effectiveCommands.firstOrNull { it.id == "review_anki" }
                                val mathCmd = effectiveCommands.firstOrNull { it.id == "open_math_arena" }
                                val readEraCmd = effectiveCommands.firstOrNull { it.id == "open_readera" }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    PaletteSectionHeader("🚀 Ações Rápidas")
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AssistChip(
                                            onClick = {
                                                onDismiss()
                                                if (themeCmd != null) themeCmd.onRun() else themeOpener()
                                            },
                                            leadingIcon = { Icon(Icons.Default.Palette, null, Modifier.size(16.dp)) },
                                            label = { Text("Tema (> tema)", maxLines = 1, softWrap = false) }
                                        )
                                        AssistChip(
                                            onClick = {
                                                onDismiss()
                                                if (newNoteCmd != null) newNoteCmd.onRun() else onCreateNoteWithTitle("Nova nota")
                                            },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null, Modifier.size(16.dp)) },
                                            label = { Text("Nova nota", maxLines = 1, softWrap = false) }
                                        )
                                        if (timerCmd != null) {
                                            AssistChip(
                                                onClick = {
                                                    onDismiss()
                                                    timerCmd.onRun()
                                                },
                                                leadingIcon = { Icon(Icons.Default.Timer, null, Modifier.size(16.dp)) },
                                                label = { Text("Abrir Timer", maxLines = 1, softWrap = false) }
                                            )
                                        }
                                        if (dailyCmd != null) {
                                            AssistChip(
                                                onClick = {
                                                    onDismiss()
                                                    dailyCmd.onRun()
                                                },
                                                leadingIcon = { Icon(Icons.Default.Today, null, Modifier.size(16.dp)) },
                                                label = { Text("Nota de hoje", maxLines = 1, softWrap = false) }
                                            )
                                        }
                                        if (cardsCmd != null) {
                                            AssistChip(
                                                onClick = {
                                                    onDismiss()
                                                    cardsCmd.onRun()
                                                },
                                                leadingIcon = { Icon(Icons.Default.Style, null, Modifier.size(16.dp)) },
                                                label = { Text("Revisar Anki", maxLines = 1, softWrap = false) }
                                            )
                                        }
                                        if (mathCmd != null) {
                                            AssistChip(
                                                onClick = {
                                                    onDismiss()
                                                    mathCmd.onRun()
                                                },
                                                leadingIcon = { Icon(Icons.Default.Calculate, null, Modifier.size(16.dp)) },
                                                label = { Text("Arena de Matemática", maxLines = 1, softWrap = false) }
                                            )
                                        }
                                        if (readEraCmd != null) {
                                            AssistChip(
                                                onClick = {
                                                    onDismiss()
                                                    readEraCmd.onRun()
                                                },
                                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null, Modifier.size(16.dp)) },
                                                label = { Text("Modo Leitura", maxLines = 1, softWrap = false) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 1. Commands
                        if (matchedCommands.isNotEmpty()) {
                            item(key = "hdr_commands", contentType = "header") {
                                PaletteSectionHeader("⚡ Comandos Rápidos (${matchedCommands.size})")
                            }
                            items(matchedCommands, key = { "cmd_${it.id}" }, contentType = { "command" }) { cmd ->
                                Card(
                                    onClick = {
                                        onDismiss()
                                        cmd.onRun()
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        ) {
                                            Icon(
                                                cmd.icon,
                                                null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(8.dp).size(18.dp)
                                            )
                                        }
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 10.dp)
                                        ) {
                                            Text(
                                                cmd.title,
                                                fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(cmd.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface
                                        ) {
                                            Text(
                                                cmd.badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                    // 2. Notes (Second Brain)
                    if (matchedNotes.isNotEmpty()) {
                        item(key = "hdr_notes", contentType = "header") {
                            PaletteSectionHeader("🧠 Segundo Cérebro · Notas (${matchedNotes.size})")
                        }
                        items(matchedNotes, key = { "note_${it.note.id}" }, contentType = { "note" }) { match ->
                            val note = match.note
                            val tags = match.tags
                            val folder = match.folder
                            Card(
                                onClick = {
                                    onDismiss()
                                    onOpenNote(note.id)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (note.pinned) Icons.Default.PushPin else Icons.Default.Description,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            note.title.ifBlank { "Sem título" },
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!folder.isNullOrBlank()) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "📁 $folder",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 128.dp)
                                            )
                                        }
                                    }
                                    val snippet = match.snippet
                                    if (snippet.isNotBlank()) {
                                        Text(
                                            snippet,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 3.dp)
                                        )
                                    }
                                    if (tags.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            tags.take(4).forEach { tag ->
                                                Text(
                                                    "#$tag",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Flashcards (Anki)
                    if (matchedCards.isNotEmpty()) {
                        item(key = "hdr_cards", contentType = "header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.weight(1f)) {
                                    PaletteSectionHeader("🃏 Flashcards Anki (${matchedCards.size})")
                                }
                                TextButton(
                                    onClick = {
                                        onDismiss()
                                        onReviewCardsImmediate(matchedCards)
                                    }
                                ) {
                                    Icon(Icons.Default.Bolt, null, modifier = Modifier.size(16.dp))
                                    Text(
                                        " Revisar (${matchedCards.size})",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                        items(matchedCards, key = { "card_${it.id}" }, contentType = { "card" }) { card ->
                            val subjectName = card.subjectId?.let { subjectsById[it]?.name }
                            val deckName = card.deckId?.let { decksById[it]?.name }
                            Card(
                                onClick = {
                                    onDismiss()
                                    onOpenFlashcardsForSubject(card.subjectId ?: -1L)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            card.front,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            card.back,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val meta = listOfNotNull(deckName, subjectName, "Ivl: ${card.intervalDays}d").joinToString(" · ")
                                        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                    }
                                    FilledTonalIconButton(
                                        onClick = {
                                            onDismiss()
                                            onReviewCardsImmediate(listOf(card))
                                        }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, "Revisar cartão agora")
                                    }
                                }
                            }
                        }
                    }

                    // 4. Tasks
                    if (matchedTasks.isNotEmpty()) {
                        item(key = "hdr_tasks", contentType = "header") {
                            PaletteSectionHeader("✅ Tarefas (${matchedTasks.size})")
                        }
                        items(matchedTasks, key = { "task_${it.id}" }, contentType = { "task" }) { task ->
                            val subjectName = task.subjectId?.let { subjectsById[it]?.name }
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = task.completed,
                                        onCheckedChange = { onToggleTask(task) }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            task.title,
                                            fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (subjectName != null) {
                                            Text(subjectName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    if (!task.completed) {
                                        FilledTonalIconButton(
                                            onClick = {
                                                onDismiss()
                                                onFocusTask(task)
                                            }
                                        ) {
                                            Icon(Icons.Default.Timer, "Estudar tarefa no Timer")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. Subjects & Books
                    if (matchedSubjects.isNotEmpty() || matchedBooks.isNotEmpty()) {
                        item(key = "hdr_subj_books", contentType = "header") {
                            PaletteSectionHeader("📚 Matérias & Livros (${matchedSubjects.size + matchedBooks.size})")
                        }
                        items(matchedSubjects, key = { "subj_${it.id}" }, contentType = { "subject" }) { subj ->
                            Card(
                                onClick = {
                                    onDismiss()
                                    onOpenSubject(subj.id)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(Color(subj.color.toInt()))
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp)
                                    ) {
                                        Text(subj.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        Text("Meta: ${subj.targetMinutesPerDay} min/dia", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    FilledTonalIconButton(
                                        onClick = {
                                            onDismiss()
                                            onStartStudySubject(subj.id)
                                        }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, "Iniciar estudo da matéria")
                                    }
                                }
                            }
                        }
                        items(matchedBooks, key = { "book_${it.id}" }, contentType = { "book" }) { book ->
                            val highlights = highlightsByBookId[book.id].orEmpty()
                            val matchedHighlight = remember(highlights, debouncedTerm) {
                                if (debouncedTerm.isBlank()) null
                                else highlights.firstOrNull { hl ->
                                    hl.text.contains(debouncedTerm, ignoreCase = true) ||
                                        hl.note.contains(debouncedTerm, ignoreCase = true) ||
                                        hl.chapter.contains(debouncedTerm, ignoreCase = true)
                                }
                            }
                            Card(
                                onClick = {
                                    onDismiss()
                                    if (onOpenBookDetail != null) onOpenBookDetail(book.id) else onOpenBook(book.subjectId)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.AutoStories, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp)
                                    ) {
                                        Text(book.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val pagesLabel = if (book.totalPages != null) "Pág. ${book.currentPage}/${book.totalPages}" else "Pág. ${book.currentPage}"
                                        val meta = buildList {
                                            add(book.author.ifBlank { "Autor" })
                                            add(pagesLabel)
                                            if (book.currentChapter.isNotBlank()) add("Cap. ${book.currentChapter}")
                                            if (book.categoryTag.isNotBlank()) add(book.categoryTag)
                                            if (highlights.isNotEmpty()) add("${highlights.size} dest.")
                                        }.joinToString(" · ")
                                        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (matchedHighlight != null) {
                                            Text(
                                                "“${matchedHighlight.text.replace('\n', ' ').take(90)}”",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (onOpenBookReader != null) {
                                        FilledTonalIconButton(
                                            onClick = {
                                                onDismiss()
                                                onOpenBookReader(book)
                                            }
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, "Modo Leitura (ReadEra)")
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    FilledTonalIconButton(
                                        onClick = {
                                            onDismiss()
                                            onReadBook(book.id)
                                        }
                                    ) {
                                        Icon(Icons.Default.Timer, "Cronometrar leitura")
                                    }
                                }
                            }
                        }
                    }

                    // 5b. Audio Playlists & Custom Mixes
                    if (matchedPlaylists.isNotEmpty() || matchedCustomMixes.isNotEmpty()) {
                        item(key = "hdr_audio", contentType = "header") {
                            PaletteSectionHeader("🎵 Playlists & Mixes de Foco (${matchedPlaylists.size + matchedCustomMixes.size})")
                        }
                        items(matchedPlaylists, key = { "audio_pl_${it.id}" }, contentType = { "playlist" }) { playlist ->
                            val matchedTrack = remember(playlist, debouncedTerm) {
                                if (debouncedTerm.isBlank()) null
                                else playlist.tracks.firstOrNull {
                                    it.title.contains(debouncedTerm, ignoreCase = true) ||
                                        it.artist.contains(debouncedTerm, ignoreCase = true)
                                }
                            }
                            Card(
                                onClick = {
                                    FocusAudioStore.setActivePlaylist(context, playlist.id)
                                    onDismiss()
                                    onOpenPlayer()
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp)
                                    ) {
                                        Text(playlist.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val subtitle = if (matchedTrack != null) {
                                            "Faixa: ${matchedTrack.title}" + (matchedTrack.artist.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                                        } else {
                                            "${playlist.tracks.size} faixa(s)"
                                        }
                                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(matchedCustomMixes, key = { "audio_mix_${it.id}" }, contentType = { "custom_mix" }) { mix ->
                            Card(
                                onClick = {
                                    onDismiss()
                                    onOpenPlayer()
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp)
                                    ) {
                                        Text(mix.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "Mix salvo · ${mix.volumes.size} camada(s)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // 6. Goals
                    if (matchedGoals.isNotEmpty()) {
                        item(key = "hdr_goals", contentType = "header") {
                            PaletteSectionHeader("🎯 Metas (${matchedGoals.size})")
                        }
                        items(matchedGoals, key = { "goal_${it.id}" }, contentType = { "goal" }) { goal ->
                            val metric = GoalMetricType.fromId(goal.metricType)
                            val subjName = goal.subjectId?.let { subjectsById[it]?.name }
                            val statusText = buildString {
                                append(metric.label)
                                if (goal.targetValue > 0.0) {
                                    val unitLabel = goal.metricUnit.ifBlank { metric.defaultUnit }
                                    append(" · Alvo ${goal.targetValue.toInt().takeIf { it.toDouble() == goal.targetValue } ?: goal.targetValue}")
                                    if (unitLabel.isNotBlank()) append(" $unitLabel")
                                }
                                if (!subjName.isNullOrBlank()) append(" · $subjName")
                                if (goal.completed) append(" · Concluída")
                            }
                            Card(
                                onClick = {
                                    onDismiss()
                                    onOpenGoal(goal)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val rawGoalColor = Color(goal.color.toInt())
                                    val goalCardSurface = MaterialTheme.colorScheme.surfaceVariant
                                    val safeGoalColor = if (contrastRatio(rawGoalColor, goalCardSurface) >= 3.0) {
                                        rawGoalColor
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                    Icon(
                                        if (goal.completed) Icons.Default.CheckCircle else Icons.Default.Flag,
                                        null,
                                        tint = safeGoalColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp)
                                    ) {
                                        Text(
                                            goal.title,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            statusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (goal.pinned) {
                                        Icon(
                                            Icons.Default.PushPin,
                                            "Fixada",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 7. Diary Entries
                    if (matchedJournalEntries.isNotEmpty()) {
                        item(key = "hdr_diary", contentType = "header") {
                            PaletteSectionHeader("📔 Diário (${matchedJournalEntries.size})")
                        }
                        items(matchedJournalEntries, key = { "diary_${it.dayKey}" }, contentType = { "diary" }) { entry ->
                            val dateBr = remember(entry.dayKey) {
                                DiaryAnalytics.localDateFromDayKey(entry.dayKey).format(DIARY_BR_FORMATTER)
                            }
                            val displayTitle = entry.title.ifBlank { "Diário de $dateBr" }
                            val snippet = entry.body.trim().replace("\n", " ").ifBlank { entry.tags.ifBlank { "Energia ${entry.energy}/5 · Foco ${entry.focus}/5" } }
                            Card(
                                onClick = {
                                    onDismiss()
                                    onOpenJournalDay(entry.dayKey)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(entry.mood.ifBlank { "🙂" }, style = MaterialTheme.typography.titleMedium)
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp)
                                    ) {
                                        Text(
                                            "$displayTitle · $dateBr",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            snippet,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // 8. Mistakes Notebook
                    if (matchedMistakes.isNotEmpty()) {
                        item(key = "hdr_mistakes", contentType = "header") {
                            PaletteSectionHeader("🩺 Caderno de Erros (${matchedMistakes.size})")
                        }
                        items(matchedMistakes, key = { "mistake_${it.id}" }, contentType = { "mistake" }) { mistake ->
                            val subjName = subjectsById[mistake.subjectId]?.name ?: "Matéria"
                            val detail = mistake.question.ifBlank { mistake.reason }.replace("\n", " ")
                            Card(
                                onClick = {
                                    onDismiss()
                                    onOpenSubjectMistakes(mistake.subjectId)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (mistake.resolved) Icons.Default.CheckCircle else Icons.Default.BugReport,
                                        null,
                                        tint = if (mistake.resolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp)
                                    ) {
                                        Text(
                                            "${mistake.topic} · $subjName",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (detail.isNotBlank()) {
                                            Text(
                                                detail,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Text(
                                        if (mistake.resolved) "Resolvido" else "Revisar",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (mistake.resolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    if (!hasAnyMatch) {
                        item(key = "empty_search", contentType = "empty") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.SearchOff, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Nenhum resultado encontrado para \"$query\"",
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tente outro termo ou toque acima para criar uma nota com esse título.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PaletteSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

private val OBSIDIAN_OPERATOR_REGEX = Regex(
    """(?:^|\s)(?:-?(?:tag|path|file):\S+|"[^"]+"|/[^/\n]+/[a-zA-Z]*)""",
    RegexOption.IGNORE_CASE
)

private val ANKI_CARD_QUERY_REGEX = Regex(
    """(?:^|\s)(?:-?(?:is|flag|deck|tag|prop):\S+|"[^"]+")""",
    RegexOption.IGNORE_CASE
)

private fun usesObsidianSearch(term: String): Boolean =
    term.isNotBlank() && OBSIDIAN_OPERATOR_REGEX.containsMatchIn(term)

private fun usesAnkiCardQuery(term: String): Boolean =
    term.isNotBlank() && ANKI_CARD_QUERY_REGEX.containsMatchIn(term)

private val SNIPPET_SLASH_REGEX = Regex("""(?:^|\s)/([^/\n]+)/([a-zA-Z]*)""")
private val SNIPPET_QUOTED_REGEX = Regex(""""([^"]+)"""")
private val SNIPPET_OPERATOR_STRIP_REGEX = Regex("""-?(?:tag|path|file|is|flag|deck|prop):\S+""", RegexOption.IGNORE_CASE)
private val SNIPPET_SLASH_TOKEN_STRIP_REGEX = Regex("""/[^/\n]+/[a-zA-Z]*""")
private val SNIPPET_WHITESPACE_REGEX = Regex("""\s+""")
private val SNIPPET_TAG_FILTER_REGEX = Regex("""tag:#?([^\s]+)""", RegexOption.IGNORE_CASE)
private val FRONTMATTER_STRIP_REGEX = Regex("""^---[\s\S]*?---\s*""")
private val MARKDOWN_CHAR_STRIP_REGEX = Regex("""[#>*_`~]""")

private fun findSnippetMatch(clean: String, query: String): IntRange? {
    val regexMatch = SNIPPET_SLASH_REGEX.find(query)
    if (regexMatch != null) {
        val pattern = regexMatch.groupValues[1]
        val flags = regexMatch.groupValues[2].lowercase()
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
        }
        val safePattern = pattern.length <= 160 && !Regex("""\([^)]*[+*][^)]*\)[+*]""").containsMatchIn(pattern)
        val compiled = if (safePattern) runCatching { Regex(pattern, options) }.getOrNull() else null
        compiled?.find(clean.take(8192))?.range?.let { return it }
    }
    val quotedMatch = SNIPPET_QUOTED_REGEX.find(query)
    if (quotedMatch != null) {
        val phrase = quotedMatch.groupValues[1]
        val idx = clean.indexOf(phrase, ignoreCase = true)
        if (idx >= 0) return idx until (idx + phrase.length)
    }
    val plainTerms = query
        .replace(SNIPPET_OPERATOR_STRIP_REGEX, " ")
        .replace(SNIPPET_SLASH_TOKEN_STRIP_REGEX, " ")
        .replace("\"", " ")
        .trim()
    if (plainTerms.isNotEmpty()) {
        val idx = clean.indexOf(plainTerms, ignoreCase = true)
        if (idx >= 0) return idx until (idx + plainTerms.length)
        plainTerms.split(SNIPPET_WHITESPACE_REGEX)
            .filter { it.length >= 2 && !it.equals("OR", ignoreCase = true) }
            .forEach { token ->
                val tIdx = clean.indexOf(token, ignoreCase = true)
                if (tIdx >= 0) return tIdx until (tIdx + token.length)
            }
    }
    val tagMatch = SNIPPET_TAG_FILTER_REGEX.find(query)
    if (tagMatch != null) {
        val tagToken = "#${tagMatch.groupValues[1]}"
        val idx = clean.indexOf(tagToken, ignoreCase = true)
        if (idx >= 0) return idx until (idx + tagToken.length)
    }
    return null
}

private fun extractSearchSnippet(markdown: String, query: String): String {
    val clean = markdown
        .replace(FRONTMATTER_STRIP_REGEX, "")
        .replace(MARKDOWN_CHAR_STRIP_REGEX, "")
        .trim()
    if (clean.isEmpty()) return ""
    if (query.isBlank()) return clean.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: ""
    val range = findSnippetMatch(clean, query)
        ?: return clean.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: ""
    val start = (range.first - 35).coerceAtLeast(0)
    val end = (range.last + 66).coerceAtMost(clean.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < clean.length) "…" else ""
    return prefix + clean.substring(start, end).replace('\n', ' ').trim() + suffix
}
