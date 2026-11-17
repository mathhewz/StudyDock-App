package com.studydock.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studydock.app.data.Book
import com.studydock.app.data.BookHighlight
import com.studydock.app.data.BookHighlights
import com.studydock.app.data.CalendarEvent
import com.studydock.app.data.CanvasBoard
import com.studydock.app.data.CardReview
import com.studydock.app.data.DiaryAnalytics
import com.studydock.app.data.DeckStudyIndex
import com.studydock.app.data.Flashcard
import com.studydock.app.data.Goal
import com.studydock.app.data.GoalProgressEngine
import com.studydock.app.data.Habit
import com.studydock.app.data.HabitCompletion
import com.studydock.app.data.JournalEntry
import com.studydock.app.data.Mistake
import com.studydock.app.data.Note
import com.studydock.app.data.QuestionLog
import com.studydock.app.data.ReadingLog
import com.studydock.app.data.StudyDatabase
import com.studydock.app.data.StudySession
import com.studydock.app.data.StudyTask
import com.studydock.app.data.StudyTutorMode
import com.studydock.app.data.Subject
import com.studydock.app.timer.TimerState
import com.studydock.app.ui.AcademicPlannerPage
import com.studydock.app.ui.ActivityTimerHub
import com.studydock.app.ui.AssistantPage
import com.studydock.app.ui.BookDetailPage
import com.studydock.app.ui.BooksPage
import com.studydock.app.ui.CalendarAgendaPage
import com.studydock.app.ui.DashboardHojePage
import com.studydock.app.ui.DiaryPage
import com.studydock.app.ui.FitnessPage
import com.studydock.app.ui.FlashcardsPage
import com.studydock.app.ui.GlobalSearchAndCommandPaletteDialog
import com.studydock.app.ui.GoalsDashboardPage
import com.studydock.app.ui.HabitsStudioPage
import com.studydock.app.ui.MathPracticePage
import com.studydock.app.ui.MiniAudioPlayer
import com.studydock.app.ui.MoreHub
import com.studydock.app.ui.NoteEditorPlus
import com.studydock.app.ui.NotesListPage
import com.studydock.app.ui.OrganizationHub
import com.studydock.app.ui.PlayerPage
import com.studydock.app.ui.QuickCommandItem
import com.studydock.app.ui.ReadEraReaderPage
import com.studydock.app.ui.ReviewPage
import com.studydock.app.ui.ReviewWidget
import com.studydock.app.ui.SlimGlobalSearchPill
import com.studydock.app.ui.StudyAnalyticsPage
import com.studydock.app.ui.StudyCard
import com.studydock.app.ui.StudyDockTheme
import com.studydock.app.ui.StudyNavSlots
import com.studydock.app.ui.StudyThemeConfig
import com.studydock.app.ui.StudyThemeStore
import com.studydock.app.ui.StudyTimerPage
import com.studydock.app.ui.SubjectDetailPage
import com.studydock.app.ui.SubjectsDashboardPage
import com.studydock.app.ui.ThemeStudioSheet
import com.studydock.app.ui.TodoPage
import com.studydock.app.ui.quickTaskFrom
import com.studydock.app.ui.rememberDailyGoalResolution
import com.studydock.app.ui.rememberObsidianVaultZipActions
import com.studydock.app.ui.rememberStudyThemeConfig
import com.studydock.app.ui.studyHorizontalScreenPadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MAIN_PT_BR_LOCALE: Locale = Locale("pt", "BR")
private val MAIN_DAY_MONTH_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d/M", MAIN_PT_BR_LOCALE)

class MainActivity : ComponentActivity() {
    private var openTimerPage by mutableStateOf(false)
    private var openHabitsPage by mutableStateOf(false)
    private var openPlayerPage by mutableStateOf(false)
    private var openNewNotePage by mutableStateOf(false)
    private var openCardsPage by mutableStateOf(false)
    private var deepLinkNonce by mutableIntStateOf(0)
    private var paletteShortcutTrigger by mutableIntStateOf(0)
    private var pendingSharedText by mutableStateOf<String?>(null)
    private var pendingSharedTitle by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavigationExtras(intent)
    }

    private fun consumeNavigationExtras(incoming: Intent?) {
        if (incoming == null) return
        var handled = false
        when {
            incoming.getBooleanExtra("open_timer_page", false) -> {
                openTimerPage = true
                openHabitsPage = false
                openPlayerPage = false
                openNewNotePage = false
                openCardsPage = false
                incoming.removeExtra("open_timer_page")
                handled = true
            }
            incoming.getBooleanExtra("open_habits_page", false) -> {
                openHabitsPage = true
                openTimerPage = false
                openPlayerPage = false
                openNewNotePage = false
                openCardsPage = false
                incoming.removeExtra("open_habits_page")
                handled = true
            }
            incoming.getBooleanExtra("open_player_page", false) -> {
                openPlayerPage = true
                openTimerPage = false
                openHabitsPage = false
                openNewNotePage = false
                openCardsPage = false
                incoming.removeExtra("open_player_page")
                handled = true
            }
            incoming.getBooleanExtra("open_new_note", false) -> {
                openNewNotePage = true
                openTimerPage = false
                openHabitsPage = false
                openPlayerPage = false
                openCardsPage = false
                incoming.removeExtra("open_new_note")
                handled = true
            }
            incoming.action == ReviewWidget.OPEN_PROGRESS -> {
                openCardsPage = true
                openTimerPage = false
                openHabitsPage = false
                openPlayerPage = false
                openNewNotePage = false
                incoming.action = null
                handled = true
            }
            incoming.getBooleanExtra("open_cards_page", false) -> {
                openCardsPage = true
                openTimerPage = false
                openHabitsPage = false
                openPlayerPage = false
                openNewNotePage = false
                incoming.removeExtra("open_cards_page")
                handled = true
            }
        }
        if (incoming.action == Intent.ACTION_SEND && incoming.type?.startsWith("text/") == true) {
            val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val sharedSubject = incoming.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            if (sharedText.isNotEmpty()) {
                pendingSharedText = sharedText
                pendingSharedTitle = sharedSubject
                incoming.action = null
            }
        }
        if (handled) deepLinkNonce++
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            com.studydock.app.timer.StudySessionManager.getInstanceOrNull()?.recordAppExitIfRunning()
        }
    }

    // androidx.core.app.ComponentActivity marks dispatchKeyEvent @RestrictTo; needed so Ctrl/Meta+K runs before text focus consumes it.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode == android.view.KeyEvent.KEYCODE_K &&
            (event.isCtrlPressed || event.isMetaPressed) &&
            !event.isAltPressed &&
            !event.isShiftPressed &&
            event.repeatCount == 0
        ) {
            paletteShortcutTrigger++
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        consumeNavigationExtras(intent)
        StudyThemeStore.preloadAsync(applicationContext)
        val dao = StudyDatabase.get(this).studyDao()
        setContent {
            val vm: StudyViewModel = viewModel(factory = StudyViewModel.factory(dao, applicationContext))
            val themeConfig = rememberStudyThemeConfig()
            // Observe only the running flag at root so 500ms timer ticks never recompose the app chrome.
            val isTimerRunning by vm.isTimerRunning.collectAsStateWithLifecycle()
            val effectiveSurface = themeConfig.effectiveBaseSurface(isTimerRunning = isTimerRunning)
            val isDark = effectiveSurface.isDark

            var showThemeStudio by rememberSaveable { mutableStateOf(false) }
            var paletteRequest by remember { mutableIntStateOf(0) }
            var paletteInitialQuery by remember { mutableStateOf("") }

            StudyDockTheme(
                config = themeConfig,
                isTimerRunning = isTimerRunning,
                onOpenGlobalSearch = { initial ->
                    paletteInitialQuery = initial
                    paletteRequest++
                },
                onOpenThemeStudio = { showThemeStudio = true },
                animateColors = showThemeStudio
            ) {
                val sharedBody = pendingSharedText
                if (sharedBody != null) {
                    var captureTitle by remember(sharedBody, pendingSharedTitle) {
                        mutableStateOf(
                            pendingSharedTitle?.takeIf { it.isNotBlank() }
                                ?: sharedBody.lineSequence().firstOrNull()?.take(60)?.trim().orEmpty()
                        )
                    }
                    var captureContent by remember(sharedBody) { mutableStateOf(sharedBody) }
                    AlertDialog(
                        onDismissRequest = {
                            pendingSharedText = null
                            pendingSharedTitle = null
                        },
                        title = { Text("Captura rápida", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = captureTitle,
                                    onValueChange = { captureTitle = it },
                                    label = { Text("Título / Frente") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = captureContent,
                                    onValueChange = { captureContent = it },
                                    label = { Text("Conteúdo / Verso") },
                                    minLines = 3,
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = {
                                        val t = captureTitle.trim().ifBlank { "Captura rápida" }
                                        val b = captureContent.trim()
                                        pendingSharedText = null
                                        pendingSharedTitle = null
                                        vm.appendQuickCaptureNote(t, b)
                                        Toast.makeText(applicationContext, "Salvo como Nota!", Toast.LENGTH_SHORT).show()
                                    }
                                ) { Text("Nota") }
                                TextButton(
                                    onClick = {
                                        val t = captureTitle.trim().ifBlank { captureContent.trim().take(60) }
                                        vm.saveTask(
                                            StudyTask(
                                                title = t,
                                                details = captureContent.trim(),
                                                dueAt = System.currentTimeMillis(),
                                                hasDueDate = true,
                                                priority = 2
                                            )
                                        )
                                        Toast.makeText(applicationContext, "Salvo como Tarefa!", Toast.LENGTH_SHORT).show()
                                        pendingSharedText = null
                                        pendingSharedTitle = null
                                    }
                                ) { Text("Tarefa") }
                                Button(
                                    onClick = {
                                        val f = captureTitle.trim().ifBlank { "Captura rápida" }
                                        val b = captureContent.trim().ifBlank { f }
                                        pendingSharedText = null
                                        pendingSharedTitle = null
                                        vm.createQuickCaptureFlashcard(f, b)
                                        Toast.makeText(applicationContext, "Salvo como Flashcard!", Toast.LENGTH_SHORT).show()
                                    }
                                ) { Text("Card") }
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    pendingSharedText = null
                                    pendingSharedTitle = null
                                }
                            ) { Text("Cancelar") }
                        }
                    )
                }
                LaunchedEffect(isDark) {
                    val lightIcons = !isDark
                    enableEdgeToEdge(
                        statusBarStyle = if (lightIcons) {
                            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        },
                        navigationBarStyle = if (lightIcons) {
                            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        }
                    )
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightIcons
                        isAppearanceLightNavigationBars = lightIcons
                    }
                }
                StudyApp(
                    vm = vm,
                    darkMode = themeConfig.baseSurface.isDark,
                    themeConfig = themeConfig,
                    openProgress = intent.action == ReviewWidget.OPEN_PROGRESS,
                    openTimer = openTimerPage,
                    openHabits = openHabitsPage,
                    openPlayer = openPlayerPage,
                    openNewNote = openNewNotePage,
                    openCards = openCardsPage,
                    deepLinkNonce = deepLinkNonce,
                    onDeepLinksConsumed = {
                        openTimerPage = false
                        openHabitsPage = false
                        openPlayerPage = false
                        openNewNotePage = false
                        openCardsPage = false
                    },
                    paletteShortcutTrigger = paletteShortcutTrigger,
                    externalPaletteTrigger = paletteRequest,
                    externalPaletteInitialQuery = paletteInitialQuery,
                    onOpenThemeStudio = { showThemeStudio = true },
                    onDarkMode = { enabled ->
                        StudyThemeStore.setDarkMode(applicationContext, enabled)
                    }
                )
                if (showThemeStudio) {
                    ThemeStudioSheet(
                        onDismiss = { showThemeStudio = false },
                        isTimerRunning = isTimerRunning,
                        config = themeConfig
                    )
                }
            }
        }
    }
}

private enum class Page(val label: String) {
    Hoje("Hoje"),
    Timer("Timer"),
    Agenda("Agenda"),
    Historico("Histórico"),
    Organizar("Organizar"),
    Mais("Mais"),
    Materias("Matérias"),
    DetalheMateria("Detalhe"),
    Tarefas("Tarefas"),
    Habitos("Hábitos"),
    Metas("Metas"),
    Notas("Notas"),
    EditarNota("Editar"),
    Livros("Livros"),
    DetalheLivro("Livro"),
    Leitor("Leitor"),
    Matematica("Arena de Exatas"),
    Fitness("Treino e alimentação"),
    Diario("Diário"),
    Cartoes("Flashcards"),
    Revisao("Revisão"),
    Player("Sons"),
    Assistente("Assistente")
}

private fun backTarget(p: Page): Page = when (p) {
    Page.Historico -> Page.Agenda
    Page.Materias, Page.Tarefas, Page.Habitos, Page.Metas, Page.Notas, Page.Livros, Page.Fitness, Page.Diario -> Page.Organizar
    Page.DetalheMateria -> Page.Materias
    Page.DetalheLivro, Page.Leitor -> Page.Livros
    Page.EditarNota -> Page.Notas
    Page.Cartoes, Page.Player, Page.Assistente, Page.Matematica -> Page.Mais
    Page.Revisao -> Page.Cartoes
    else -> Page.Hoje
}

@Composable
private fun StudyApp(
    vm: StudyViewModel,
    darkMode: Boolean,
    themeConfig: StudyThemeConfig,
    openProgress: Boolean,
    openTimer: Boolean = false,
    openHabits: Boolean = false,
    openPlayer: Boolean = false,
    openNewNote: Boolean = false,
    openCards: Boolean = false,
    deepLinkNonce: Int = 0,
    onDeepLinksConsumed: () -> Unit = {},
    paletteShortcutTrigger: Int = 0,
    externalPaletteTrigger: Int = 0,
    externalPaletteInitialQuery: String = "",
    onOpenThemeStudio: () -> Unit = {},
    onDarkMode: (Boolean) -> Unit
) {
    var page by rememberSaveable {
        mutableStateOf(
            when {
                openTimer -> Page.Timer
                openHabits -> Page.Habitos
                openPlayer -> Page.Player
                openNewNote -> Page.EditarNota
                openCards || openProgress -> Page.Cartoes
                else -> Page.Hoje
            }
        )
    }
    LaunchedEffect(deepLinkNonce, openTimer, openHabits, openPlayer, openNewNote, openCards) {
        var consumed = false
        when {
            openTimer -> { vm.noteEditor.flush(); page = Page.Timer; consumed = true }
            openHabits -> { vm.noteEditor.flush(); page = Page.Habitos; consumed = true }
            openPlayer -> { vm.noteEditor.flush(); page = Page.Player; consumed = true }
            openNewNote -> {
                vm.openNote(null)
                page = Page.EditarNota
                consumed = true
            }
            openCards -> { vm.noteEditor.flush(); page = Page.Cartoes; consumed = true }
        }
        if (consumed) onDeepLinksConsumed()
    }

    var detailSubjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var bookSubjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deck by rememberSaveable { mutableLongStateOf(-1L) }
    var subjectFilter by rememberSaveable { mutableLongStateOf(-1L) }
    var showCardStats by rememberSaveable { mutableStateOf(openProgress) }
    var cardsOrigin by rememberSaveable { mutableStateOf(Page.Mais) }
    var noteOrigin by rememberSaveable { mutableStateOf(Page.Notas) }
    var pendingCanvasBoardId by rememberSaveable { mutableStateOf<Long?>(null) }
    var readingBook by rememberSaveable { mutableStateOf<Long?>(null) }
    var readingOrigin by rememberSaveable { mutableStateOf(Page.Livros) }
    var readerBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    var readerOrigin by rememberSaveable { mutableStateOf(Page.Livros) }
    var mathSubjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var mathOrigin by rememberSaveable { mutableStateOf(Page.Mais) }
    var playerOrigin by rememberSaveable { mutableStateOf(Page.Mais) }
    var trainingExercise by rememberSaveable { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.studySessionManager.refreshNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isSessionActive by vm.isSessionActive.collectAsStateWithLifecycle()
    val isNoteEditorBusy by vm.isNoteEditorBusy.collectAsStateWithLifecycle()

    var noteNavigation by remember { mutableLongStateOf(0) }
    fun leaveNote(target: Page) {
        val editorState = vm.noteEditor.state.value
        if (page == Page.EditarNota && (editorState?.loading == true || editorState?.leaving == true)) return
        val request = ++noteNavigation
        if (page == Page.EditarNota) vm.noteEditor.flush { if (request == noteNavigation) page = target }
        else page = target
    }

    fun safeNonCardOrigin(candidate: Page): Page = candidate.takeUnless {
        it == Page.Cartoes || it == Page.Revisao || it == Page.EditarNota
    } ?: Page.Hoje

    fun openNoteFrom(origin: Page, id: Long?) {
        if (origin != Page.EditarNota) noteOrigin = origin
        vm.openNote(id)
        page = Page.EditarNota
    }

    fun createTemplatedNoteFrom(origin: Page, title: String, markdown: String) {
        if (origin != Page.EditarNota) noteOrigin = origin
        if (page == Page.EditarNota) {
            vm.noteEditor.flush {
                vm.createNoteWithTemplate(title, markdown)
                page = Page.EditarNota
            }
        } else {
            vm.createNoteWithTemplate(title, markdown)
            page = Page.EditarNota
        }
    }

    fun openCardsFrom(origin: Page, subjectId: Long = -1L, showStats: Boolean = false) {
        cardsOrigin = safeNonCardOrigin(origin).let { safe ->
            if (origin == Page.Cartoes || origin == Page.Revisao || origin == Page.EditarNota) {
                safeNonCardOrigin(cardsOrigin)
            } else {
                safe
            }
        }
        showCardStats = showStats
        subjectFilter = subjectId
        deck = -1L
        leaveNote(Page.Cartoes)
    }

    fun openReaderForBook(selectedBook: Book) {
        if (page != Page.Leitor) readerOrigin = page
        readerBookId = selectedBook.id
        leaveNote(Page.Leitor)
    }

    fun openMathArena(subjectId: Long? = null) {
        if (page != Page.Matematica) mathOrigin = page
        mathSubjectId = subjectId
        leaveNote(Page.Matematica)
    }

    val sectionState = rememberSaveableStateHolder()
    val appCtx = LocalContext.current
    val scope = rememberCoroutineScope()

    /** Single entry point to start studying from Planner/Tasks/Notes. Never hijacks an active session. */
    fun startStudyFrom(subjectId: Long?, topic: String, noteId: Long? = null, taskId: Long? = null) {
        val snap = vm.studySessionManager.snapshot.value
        val busy = snap.state == TimerState.RUNNING || snap.state == TimerState.PAUSED || snap.elapsedFocusSeconds > 0L
        if (busy) {
            Toast.makeText(appCtx, "Já há uma sessão em andamento. Finalize-a antes de iniciar outra.", Toast.LENGTH_LONG).show()
            page = Page.Timer
            return
        }
        val cachedSubjects = vm.subjects.value
        val cachedTarget = subjectId ?: snap.subjectId
            ?: cachedSubjects.firstOrNull { !it.archived }?.id
            ?: cachedSubjects.firstOrNull()?.id
        if (cachedTarget != null) {
            vm.studySessionManager.setSubject(cachedTarget)
            vm.studySessionManager.setTopic(topic)
            vm.studySessionManager.setLinkedNoteId(noteId)
            vm.studySessionManager.setLinkedTaskId(taskId)
            vm.studySessionManager.startOrResume()
            page = Page.Timer
            return
        }
        scope.launch {
            val target = vm.resolveTargetSubjectId(subjectId ?: snap.subjectId)
            if (target == null) {
                Toast.makeText(appCtx, "Cadastre uma matéria para iniciar o cronômetro.", Toast.LENGTH_LONG).show()
                page = Page.Materias
                return@launch
            }
            vm.studySessionManager.setSubject(target)
            vm.studySessionManager.setTopic(topic)
            vm.studySessionManager.setLinkedNoteId(noteId)
            vm.studySessionManager.setLinkedTaskId(taskId)
            vm.studySessionManager.startOrResume()
            page = Page.Timer
        }
    }

    fun startSubjectDirect(subjectId: Long) {
        val snap = vm.studySessionManager.snapshot.value
        val busyOther = (snap.state == TimerState.RUNNING || snap.state == TimerState.PAUSED || snap.elapsedFocusSeconds > 0L) &&
            snap.subjectId != subjectId
        if (busyOther) {
            Toast.makeText(appCtx, "Já há uma sessão em andamento. Finalize-a antes de iniciar outra.", Toast.LENGTH_LONG).show()
            page = Page.Timer
            return
        }
        val subj = vm.subjects.value.firstOrNull { it.id == subjectId }
        if (snap.elapsedFocusSeconds == 0L) vm.studySessionManager.setLinkedTaskId(null)
        vm.studySessionManager.setSubject(subjectId)
        val step = subj?.nextStep?.trim().orEmpty()
        if (step.isNotEmpty() && snap.elapsedFocusSeconds == 0L) {
            vm.studySessionManager.setTopic(step)
        }
        vm.studySessionManager.startOrResume()
        page = Page.Timer
    }

    LaunchedEffect(page) {
        if (page != Page.Timer && page != Page.Leitor) {
            readingBook = null
            readingOrigin = Page.Livros
            trainingExercise = null
        }
        if (page != Page.DetalheMateria) {
            detailInitialTab = 0
        }
    }

    var roundBusy by remember { mutableStateOf(false) }
    var roundError by remember { mutableStateOf<String?>(null) }
    val hasOpenScheduledRound by vm.hasOpenScheduledRound.collectAsStateWithLifecycle()
    var pendingCramReplacement by remember { mutableStateOf<List<Flashcard>?>(null) }
    fun changeRound(target: Page, operation: suspend () -> Unit) {
        if (roundBusy) return
        roundBusy = true
        scope.launch {
            try {
                operation()
                page = target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                roundError = e.localizedMessage ?: "Não foi possível atualizar a rodada."
            } finally {
                roundBusy = false
            }
        }
    }
    fun startCramProtected(batch: List<Flashcard>) {
        if (batch.none { !it.suspended }) {
            Toast.makeText(appCtx, "Nenhum cartão ativo disponível para revisar.", Toast.LENGTH_SHORT).show()
            return
        }
        if (hasOpenScheduledRound) {
            pendingCramReplacement = batch
        } else {
            changeRound(Page.Revisao) { vm.startRound(batch, cram = true) }
        }
    }
    pendingCramReplacement?.let { batch ->
        val dialogRound by vm.reviewRound.collectAsStateWithLifecycle()
        val openRemaining = remember(dialogRound) { dialogRound.count { !it.entry.done } }
        val pendingSummary = if (openRemaining > 0) {
            "$openRemaining ${if (openRemaining == 1) "cartão pendente" else "cartões pendentes"}"
        } else {
            "uma revisão pendente"
        }
        AlertDialog(
            onDismissRequest = { pendingCramReplacement = null },
            title = { Text("Rodada agendada em aberto") },
            text = {
                Column {
                    Text(
                        "Você tem $pendingSummary em uma rodada programada. Deseja retomá-la ou substituí-la pelo estudo livre (Cram)?"
                    )
                    TextButton(
                        onClick = {
                            pendingCramReplacement = null
                            leaveNote(Page.Revisao)
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Retomar rodada em aberto")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCramReplacement = null
                        changeRound(Page.Revisao) { vm.startRound(batch, cram = true) }
                    }
                ) {
                    Text("Iniciar Cram (${batch.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCramReplacement = null }) { Text("Cancelar") }
            }
        )
    }
    roundError?.let { message ->
        AlertDialog(
            onDismissRequest = { roundError = null },
            text = { Text(message) },
            confirmButton = { TextButton({ roundError = null }) { Text("Fechar") } }
        )
    }

    val navSlotIds = remember(themeConfig.bottomNavSlots) { StudyNavSlots.sanitize(themeConfig.bottomNavSlots) }
    val navItems = remember(navSlotIds) { navSlotIds.map { StudyNavSlots.byId(it) to slotIdToPage(it) } }
    val activeSlotPages = remember(navItems) { navItems.map { it.second }.toSet() }
    val selectedNavPage = resolvedActiveNavPage(page, activeSlotPages)
    val navSelectEnabled = page != Page.EditarNota || !isNoteEditorBusy
    val onSelectSlot: (Page) -> Unit = { target ->
        if (target == Page.Livros) bookSubjectId = null
        if (target == Page.Cartoes && page != Page.Cartoes && page != Page.Revisao) {
            cardsOrigin = if (page == Page.EditarNota) safeNonCardOrigin(noteOrigin) else safeNonCardOrigin(page)
        }
        leaveNote(target)
    }

    val isImmersiveReader = page == Page.Leitor
    val isObsidianWorkspace = page == Page.Notas || page == Page.EditarNota
    val hideAppChrome = isImmersiveReader || isObsidianWorkspace

    BackHandler(enabled = page != Page.Hoje) {
        leaveNote(
            when (page) {
                Page.EditarNota -> noteOrigin
                Page.Notas -> if (Page.Notas in activeSlotPages) Page.Hoje else backTarget(page)
                Page.Livros -> if (bookSubjectId != null) Page.DetalheMateria else backTarget(page)
                Page.DetalheLivro -> {
                    detailBookId = null
                    Page.Livros
                }
                Page.Leitor -> readerOrigin
                Page.Matematica -> mathOrigin
                Page.Player -> playerOrigin
                Page.Cartoes -> cardsOrigin.takeUnless {
                    it == Page.Cartoes || it == Page.Revisao || it == Page.EditarNota
                } ?: backTarget(Page.Cartoes)
                Page.Timer -> when {
                    readingBook != null -> readingOrigin
                    trainingExercise != null -> Page.Fitness
                    else -> backTarget(page)
                }
                else -> backTarget(page)
            }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!hideAppChrome) {
                    Column(Modifier.fillMaxWidth()) {
                        if (page != Page.Player) {
                            MiniAudioPlayer(
                                onOpenPlayer = {
                                    playerOrigin = Page.Mais
                                    leaveNote(Page.Player)
                                },
                                applyNavigationBarPadding = useRail
                            )
                        }
                        if (!useRail) {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                navItems.forEach { (slot, targetPage) ->
                                    NavigationBarItem(
                                        selected = selectedNavPage == targetPage,
                                        onClick = { onSelectSlot(targetPage) },
                                        enabled = navSelectEnabled,
                                        icon = { Icon(slot.icon, null) },
                                        label = {
                                            Text(
                                                slot.label,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                if (useRail && !hideAppChrome) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(top = 12.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                            Text("StudyDock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        navItems.forEach { (slot, targetPage) ->
                            NavigationRailItem(
                                selected = selectedNavPage == targetPage,
                                onClick = { onSelectSlot(targetPage) },
                                enabled = navSelectEnabled,
                                icon = { Icon(slot.icon, null) },
                                label = {
                                    Text(
                                        slot.label,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val horizontalPadding = if (hideAppChrome) 0.dp else studyHorizontalScreenPadding(maxWidth)
                    val responsiveWidth = if (useRail && !hideAppChrome) Modifier.widthIn(max = 1080.dp) else Modifier
                    Column(
                        responsiveWidth
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(horizontal = horizontalPadding)
                    ) {
                        var showGlobalPalette by rememberSaveable { mutableStateOf(false) }
                        var activePaletteQuery by rememberSaveable { mutableStateOf("") }
                        LaunchedEffect(paletteShortcutTrigger) {
                            if (paletteShortcutTrigger > 0) {
                                activePaletteQuery = ""
                                showGlobalPalette = true
                            }
                        }
                        LaunchedEffect(externalPaletteTrigger) {
                            if (externalPaletteTrigger > 0) {
                                activePaletteQuery = externalPaletteInitialQuery
                                showGlobalPalette = true
                            }
                        }
                        val showHubSearchPill = page == Page.Hoje || page == Page.Organizar || page == Page.Mais
                        if (showHubSearchPill) {
                            HubSearchPillSlot(
                                vm = vm,
                                showKeyboardHint = useRail,
                                onOpenPalette = {
                                    activePaletteQuery = ""
                                    showGlobalPalette = true
                                },
                                onQuickCards = { if (page != Page.Cartoes) openCardsFrom(page) }
                            )
                        }
                        if (showGlobalPalette) {
                            GlobalSearchPaletteHost(
                                vm = vm,
                                darkMode = darkMode,
                                currentPage = page,
                                initialQuery = activePaletteQuery,
                                onDismiss = {
                                    showGlobalPalette = false
                                    activePaletteQuery = ""
                                },
                                onNavigate = { target ->
                                    if (target == Page.Player) playerOrigin = Page.Mais
                                    leaveNote(target)
                                },
                                onOpenTimer = { page = Page.Timer },
                                onOpenNote = { noteId -> openNoteFrom(page, noteId) },
                                onCreateNoteWithTitle = { newTitle ->
                                    createTemplatedNoteFrom(page, newTitle, "# $newTitle\n\n")
                                },
                                onOpenFlashcardsForSubject = { subjId -> openCardsFrom(page, subjId) },
                                onOpenAnkiStats = {
                                    openCardsFrom(page, showStats = true)
                                },
                                onReviewCardsImmediate = ::startCramProtected,
                                onFocusTask = { task ->
                                    startStudyFrom(task.subjectId, task.title, task.noteId, task.id)
                                },
                                onOpenSubject = { subjId ->
                                    detailSubjectId = subjId
                                    detailInitialTab = 0
                                    leaveNote(Page.DetalheMateria)
                                },
                                onStartStudySubject = { subjId ->
                                    val snap = vm.studySessionManager.snapshot.value
                                    val busyOther = (snap.state == TimerState.RUNNING || snap.state == TimerState.PAUSED || snap.elapsedFocusSeconds > 0L) &&
                                        snap.subjectId != subjId
                                    if (busyOther) {
                                        Toast.makeText(appCtx, "Já há uma sessão em andamento. Finalize-a antes de iniciar outra.", Toast.LENGTH_LONG).show()
                                    } else {
                                        if (snap.elapsedFocusSeconds == 0L) vm.studySessionManager.setLinkedTaskId(null)
                                        vm.studySessionManager.setSubject(subjId)
                                        val step = vm.subjects.value.firstOrNull { it.id == subjId }?.nextStep?.trim().orEmpty()
                                        if (step.isNotEmpty() && snap.elapsedFocusSeconds == 0L) {
                                            vm.studySessionManager.setTopic(step)
                                        }
                                        vm.studySessionManager.startOrResume()
                                    }
                                    leaveNote(Page.Timer)
                                },
                                onOpenBookSubject = { subjId ->
                                    bookSubjectId = subjId
                                    leaveNote(Page.Livros)
                                },
                                onReadBook = { bookId ->
                                    readingOrigin = Page.Livros
                                    readingBook = bookId
                                    leaveNote(Page.Timer)
                                },
                                onOpenSubjectMistakes = { subjId ->
                                    detailSubjectId = subjId
                                    detailInitialTab = 2
                                    leaveNote(Page.DetalheMateria)
                                },
                                onOpenBookDetail = { bookId ->
                                    detailBookId = bookId
                                    leaveNote(Page.DetalheLivro)
                                },
                                onOpenMathArena = { openMathArena(null) },
                                onOpenReader = ::openReaderForBook,
                                onOpenThemeStudio = onOpenThemeStudio,
                                onDarkMode = onDarkMode
                            )
                        }
                        if (page != Page.Timer && !hideAppChrome && isSessionActive) {
                            ActiveStudyBannerSlot(
                                vm = vm,
                                onOpen = { page = Page.Timer }
                            )
                        }
                        if (page == Page.EditarNota) {
                            NoteEditorRoute(
                                vm = vm,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                onBack = { leaveNote(noteOrigin) },
                                onDeleted = { page = noteOrigin },
                                onReviewCards = ::startCramProtected,
                                onStartStudySession = { noteId, noteTitle, subjId ->
                                    startStudyFrom(subjId, noteTitle, noteId)
                                },
                                onStartTask = { task, activeNoteId ->
                                    startStudyFrom(task.subjectId, task.title, task.noteId ?: activeNoteId, task.id)
                                }
                            )
                        } else {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                when (page) {
                                    Page.Hoje -> sectionState.SaveableStateProvider("hoje") {
                                        DashboardHojePage(
                                            vm = vm,
                                            timerActive = isSessionActive,
                                            themeConfig = themeConfig,
                                            onOpenThemeStudio = onOpenThemeStudio,
                                            onTimer = { page = Page.Timer },
                                            onSubjects = { page = Page.Materias },
                                            onStartSubject = ::startSubjectDirect,
                                            onTasks = { page = Page.Tarefas },
                                            onNewNote = { openNoteFrom(Page.Hoje, null) },
                                            onHabits = { page = Page.Habitos },
                                            onGoals = { page = Page.Metas },
                                            onCards = { openCardsFrom(Page.Hoje) },
                                            onHistory = { page = Page.Historico },
                                            onOpenPlanner = { page = Page.Agenda },
                                            onDiary = { page = Page.Diario }
                                        )
                                    }
                                    Page.Timer -> sectionState.SaveableStateProvider("timer") {
                                        TimerRoute(
                                            vm = vm,
                                            readingBook = readingBook,
                                            trainingExercise = trainingExercise,
                                            hasOpenScheduledRound = hasOpenScheduledRound,
                                            onOpenSubjects = { page = Page.Materias },
                                            onOpenSounds = {
                                                playerOrigin = Page.Mais
                                                page = Page.Player
                                            },
                                            onReviewFlashcards = { batch ->
                                                changeRound(Page.Revisao) { vm.startRound(batch) }
                                            },
                                            onCramFlashcards = { batch ->
                                                changeRound(Page.Revisao) { vm.startRound(batch, cram = true) }
                                            },
                                            onResumeOpenRound = { page = Page.Revisao },
                                            onOpenFlashcards = {
                                                val subjectId = vm.studySessionManager.snapshot.value.subjectId
                                                openCardsFrom(Page.Timer, subjectId ?: -1L)
                                            },
                                            onOpenNote = { noteId -> openNoteFrom(Page.Timer, noteId) },
                                            onOpenReader = ::openReaderForBook
                                        )
                                    }
                                    Page.Agenda -> sectionState.SaveableStateProvider("agenda") {
                                        CalendarAgendaPage(
                                            vm = vm,
                                            onStartStudy = ::startStudyFrom,
                                            onOpenHistory = { page = Page.Historico },
                                            onOpenNote = { noteId -> openNoteFrom(Page.Agenda, noteId) }
                                        )
                                    }
                                    Page.Historico -> sectionState.SaveableStateProvider("history") {
                                        HistoryRoute(
                                            vm = vm,
                                            onOpenCalendar = { page = Page.Agenda }
                                        )
                                    }
                                    Page.Organizar -> sectionState.SaveableStateProvider("organize") {
                                        OrganizationHub(
                                            onHabits = { page = Page.Habitos },
                                            onGoals = { page = Page.Metas },
                                            onNotes = { page = Page.Notas },
                                            onSubjects = { page = Page.Materias },
                                            onTasks = { page = Page.Tarefas },
                                            onBooks = {
                                                bookSubjectId = null
                                                page = Page.Livros
                                            },
                                            onFitness = { page = Page.Fitness },
                                            onDiary = { page = Page.Diario }
                                        )
                                    }
                                    Page.Fitness -> sectionState.SaveableStateProvider("fitness") {
                                        FitnessRoute(
                                            vm = vm,
                                            onTimer = { exercise ->
                                                trainingExercise = exercise
                                                page = Page.Timer
                                            }
                                        )
                                    }
                                    Page.Diario -> sectionState.SaveableStateProvider("diary") {
                                        DiaryRoute(
                                            vm = vm,
                                            onOpenNote = { noteId -> openNoteFrom(Page.Diario, noteId) },
                                            onOpenCreateNote = { title ->
                                                createTemplatedNoteFrom(Page.Diario, title, "# $title\n\n")
                                            },
                                            onOpenAssistant = { page = Page.Assistente }
                                        )
                                    }
                                    Page.Tarefas -> sectionState.SaveableStateProvider("tasks") {
                                        TodoRoute(
                                            vm = vm,
                                            onOpenNote = { openNoteFrom(Page.Tarefas, it) },
                                            onOpenTimer = { page = Page.Timer },
                                            onStudyTask = { task -> startStudyFrom(task.subjectId, task.title, task.noteId, task.id) },
                                            onOpenMistakes = { subjId ->
                                                detailSubjectId = subjId
                                                detailInitialTab = 2
                                                page = Page.DetalheMateria
                                            },
                                            onReviewCards = ::startCramProtected,
                                            onOpenCards = { subjId -> openCardsFrom(Page.Tarefas, subjId) },
                                            onOpenAssistant = { page = Page.Assistente }
                                        )
                                    }
                                    Page.Habitos -> sectionState.SaveableStateProvider("habits") {
                                        HabitsRoute(vm = vm)
                                    }
                                    Page.Metas -> sectionState.SaveableStateProvider("goals") {
                                        GoalsRoute(vm = vm)
                                    }
                                    Page.Notas -> sectionState.SaveableStateProvider("notes") {
                                        NotesListRoute(
                                            vm = vm,
                                            onCreate = { openNoteFrom(Page.Notas, null) },
                                            onOpen = { openNoteFrom(Page.Notas, it) },
                                            onRecoverDraft = { key ->
                                                noteOrigin = Page.Notas
                                                vm.noteEditor.recover(key)
                                                page = Page.EditarNota
                                            },
                                            onCreateWithTemplate = { title, markdown ->
                                                createTemplatedNoteFrom(Page.Notas, title, markdown)
                                            },
                                            onExitWorkspace = { page = Page.Hoje },
                                            requestedCanvasBoardId = pendingCanvasBoardId,
                                            onRequestedCanvasBoardConsumed = { pendingCanvasBoardId = null }
                                        )
                                    }
                                    Page.EditarNota -> Unit
                                    Page.Materias -> sectionState.SaveableStateProvider("subjects") {
                                        SubjectsRoute(
                                            vm = vm,
                                            isSessionActive = isSessionActive,
                                            onStartSubject = ::startSubjectDirect,
                                            onOpenDetail = { id ->
                                                detailSubjectId = id
                                                detailInitialTab = 0
                                                page = Page.DetalheMateria
                                            }
                                        )
                                    }
                                    Page.DetalheMateria -> SubjectDetailRoute(
                                        vm = vm,
                                        detailSubjectId = detailSubjectId,
                                        detailInitialTab = detailInitialTab,
                                        onBack = {
                                            detailInitialTab = 0
                                            page = Page.Materias
                                        },
                                        onStartSubject = ::startSubjectDirect,
                                        onStartStudy = ::startStudyFrom,
                                        onOpenCards = { subjId -> openCardsFrom(Page.DetalheMateria, subjId) },
                                        onOpenBooks = { subjId ->
                                            bookSubjectId = subjId
                                            page = Page.Livros
                                        },
                                        onOpenNote = { noteId -> openNoteFrom(Page.DetalheMateria, noteId) },
                                        onReviewSubjectCards = ::startCramProtected,
                                        onOpenGoals = { page = Page.Metas },
                                        onOpenAssistant = { page = Page.Assistente },
                                        onOpenMathArena = { subjId -> openMathArena(subjId) },
                                        onOpenSubject = { subjId ->
                                            detailSubjectId = subjId
                                            detailInitialTab = 0
                                        },
                                        onMissingSubject = {
                                            if (page == Page.DetalheMateria) {
                                                detailInitialTab = 0
                                                page = Page.Materias
                                            }
                                        }
                                    )
                                    Page.Livros -> sectionState.SaveableStateProvider("books") {
                                        BooksRoute(
                                            vm = vm,
                                            bookSubjectId = bookSubjectId,
                                            onRead = { id ->
                                                readingOrigin = Page.Livros
                                                readingBook = id
                                                page = Page.Timer
                                            },
                                            onOpenNote = { openNoteFrom(Page.Livros, it) },
                                            onOpenBookDetail = { id ->
                                                detailBookId = id
                                                page = Page.DetalheLivro
                                            },
                                            onOpenReader = ::openReaderForBook
                                        )
                                    }
                                    Page.DetalheLivro -> BookDetailRoute(
                                        vm = vm,
                                        detailBookId = detailBookId,
                                        onBack = {
                                            detailBookId = null
                                            page = Page.Livros
                                        },
                                        onReadWithTimer = { bookId ->
                                            readingOrigin = Page.DetalheLivro
                                            readingBook = bookId
                                            page = Page.Timer
                                        },
                                        onDeleted = {
                                            detailBookId = null
                                            page = Page.Livros
                                        },
                                        onOpenNote = { noteId -> openNoteFrom(Page.DetalheLivro, noteId) },
                                        onOpenAssistant = { page = Page.Assistente },
                                        onOpenReader = ::openReaderForBook,
                                        onMissingBook = {
                                            if (page == Page.DetalheLivro) {
                                                detailBookId = null
                                                page = Page.Livros
                                            }
                                        }
                                    )
                                    Page.Leitor -> ReadEraRoute(
                                        vm = vm,
                                        readerBookId = readerBookId,
                                        onBack = { leaveNote(readerOrigin) },
                                        onOpenNote = { noteId -> openNoteFrom(Page.Leitor, noteId) },
                                        onOpenSounds = {
                                            playerOrigin = Page.Leitor
                                            page = Page.Player
                                        }
                                    )
                                    Page.Matematica -> MathPracticeRoute(
                                        vm = vm,
                                        mathSubjectId = mathSubjectId,
                                        onBack = { leaveNote(mathOrigin) }
                                    )
                                    Page.Mais -> sectionState.SaveableStateProvider("more") {
                                        MoreHub(
                                            onCards = { openCardsFrom(Page.Mais) },
                                            onPlayer = {
                                                playerOrigin = Page.Mais
                                                page = Page.Player
                                            },
                                            onAssistant = { page = Page.Assistente },
                                            darkMode = darkMode,
                                            onDarkMode = onDarkMode,
                                            onMathPractice = { openMathArena(null) }
                                        )
                                    }
                                    Page.Cartoes -> sectionState.SaveableStateProvider("cards") {
                                        FlashcardsRoute(
                                            vm = vm,
                                            showCardStats = showCardStats,
                                            onShowCardStatsChange = { showCardStats = it },
                                            subjectFilter = subjectFilter,
                                            onSubjectFilterChange = { subjectFilter = it },
                                            deck = deck,
                                            onDeckChange = { deck = it },
                                            onResumeRound = { page = Page.Revisao },
                                            onFinishRound = { changeRound(Page.Cartoes, vm::finishRound) },
                                            onStartRound = { batch, cram ->
                                                if (cram) startCramProtected(batch)
                                                else changeRound(Page.Revisao) { vm.startRound(batch, cram = false) }
                                            }
                                        )
                                    }
                                    Page.Revisao -> ReviewRoute(
                                        vm = vm,
                                        onPause = { page = Page.Cartoes },
                                        onFinish = { changeRound(Page.Cartoes, vm::finishRound) }
                                    )
                                    Page.Player -> PlayerPage(onBack = { page = playerOrigin })
                                    Page.Assistente -> sectionState.SaveableStateProvider("assistant") {
                                        AssistantRoute(
                                            vm = vm,
                                            onOpenNote = { noteId -> openNoteFrom(Page.Assistente, noteId) },
                                            onOpenCanvas = { boardId ->
                                                pendingCanvasBoardId = boardId
                                                page = Page.Notas
                                            }
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
}

@Composable
private fun HubSearchPillSlot(
    vm: StudyViewModel,
    showKeyboardHint: Boolean,
    onOpenPalette: () -> Unit,
    onQuickCards: () -> Unit
) {
    val dueCount by vm.dueFlashcardsCount.collectAsStateWithLifecycle()
    SlimGlobalSearchPill(
        dueCardsCount = dueCount,
        showKeyboardHint = showKeyboardHint,
        onOpenPalette = onOpenPalette,
        onQuickCards = onQuickCards,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun ActiveStudyBannerSlot(
    vm: StudyViewModel,
    onOpen: () -> Unit
) {
    val studySnapshot by vm.studySessionManager.snapshot.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val activeSubject = subjects.firstOrNull { it.id == studySnapshot.subjectId }
    ActiveStudyBanner(
        subjectName = activeSubject?.name ?: "Estudo",
        elapsedSeconds = studySnapshot.elapsedFocusSeconds,
        running = studySnapshot.state == TimerState.RUNNING,
        onOpen = onOpen,
        onToggle = { vm.studySessionManager.toggle() }
    )
}

@Composable
private fun GlobalSearchPaletteHost(
    vm: StudyViewModel,
    darkMode: Boolean,
    currentPage: Page,
    initialQuery: String,
    onDismiss: () -> Unit,
    onNavigate: (Page) -> Unit,
    onOpenTimer: () -> Unit,
    onOpenNote: (Long?) -> Unit,
    onCreateNoteWithTitle: (String) -> Unit,
    onOpenFlashcardsForSubject: (Long) -> Unit,
    onOpenAnkiStats: () -> Unit,
    onReviewCardsImmediate: (List<Flashcard>) -> Unit,
    onFocusTask: (StudyTask) -> Unit,
    onOpenSubject: (Long) -> Unit,
    onStartStudySubject: (Long) -> Unit,
    onOpenBookSubject: (Long?) -> Unit,
    onReadBook: (Long) -> Unit,
    onOpenSubjectMistakes: (Long) -> Unit,
    onOpenBookDetail: (Long) -> Unit,
    onOpenMathArena: () -> Unit,
    onOpenReader: (Book) -> Unit,
    onOpenThemeStudio: () -> Unit,
    onDarkMode: (Boolean) -> Unit
) {
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()
    val activeNotesCount by vm.activeNotesCount.collectAsStateWithLifecycle()
    val flashcards by vm.flashcards.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val decks by vm.cardDecks.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val journalEntries by vm.journalEntries.collectAsStateWithLifecycle()
    val mistakes by vm.mistakes.collectAsStateWithLifecycle()
    val dueCount by vm.dueFlashcardsCount.collectAsStateWithLifecycle()
    val timerPaletteState by remember(vm) {
        vm.studySessionManager.snapshot
            .map { Triple(it.state == TimerState.RUNNING, it.state == TimerState.PAUSED, it.elapsedFocusSeconds > 0L) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = Triple(false, false, false))

    val isTimerRunningNow = timerPaletteState.first
    val isSessionActive = isTimerRunningNow || timerPaletteState.second || timerPaletteState.third
    val activeGoalsCount = remember(goals) { goals.count { !it.completed } }
    val preferredReaderBook = remember(books) {
        books.filter { it.status == "READING" }
            .maxWithOrNull(compareBy<Book> { it.documentUri.isNotBlank() }.thenBy { it.lastReadAt }.thenBy { it.updatedAt })
            ?: books.maxWithOrNull(compareBy<Book> { it.documentUri.isNotBlank() }.thenBy { it.lastReadAt }.thenBy { it.updatedAt })
    }

    val quickCommands = remember(
        isSessionActive,
        isTimerRunningNow,
        dueCount,
        darkMode,
        currentPage,
        activeNotesCount,
        books.size,
        activeGoalsCount,
        preferredReaderBook
    ) {
        listOf(
            QuickCommandItem(
                id = "toggle_timer",
                title = if (isTimerRunningNow) "Pausar Cronômetro Pomodoro" else "Iniciar / Abrir Cronômetro de Estudo",
                subtitle = "Controla a sessão de foco atual ou abre a tela do Timer",
                icon = Icons.Default.Timer,
                badge = "Timer",
                onRun = {
                    if (isSessionActive) vm.studySessionManager.toggle() else onOpenTimer()
                }
            ),
            QuickCommandItem(
                id = "new_note",
                title = "Nova Nota no Segundo Cérebro",
                subtitle = "Cria uma nota Markdown com suporte a [[wikilinks]] e LaTeX",
                icon = Icons.AutoMirrored.Filled.NoteAdd,
                badge = "Obsidian",
                onRun = { onOpenNote(null) }
            ),
            QuickCommandItem(
                id = "daily_note",
                title = "Abrir Nota Diária de Hoje",
                subtitle = "Abre ou cria o diário estruturado do dia no Segundo Cérebro",
                icon = Icons.Default.Today,
                badge = "Hoje",
                onRun = { vm.openDailyNote { id -> onOpenNote(id) } }
            ),
            QuickCommandItem(
                id = "open_second_brain",
                title = "Abrir Segundo Cérebro & Grafo 60fps",
                subtitle = "Visualize suas notas, pastas e o grafo interativo estilo Obsidian",
                icon = Icons.Default.Hub,
                badge = "$activeNotesCount notas",
                onRun = { onNavigate(Page.Notas) }
            ),
            QuickCommandItem(
                id = "review_anki",
                title = "Revisar Flashcards (Anki & Cram Mode)",
                subtitle = "Abre seus baralhos, Estudo Personalizado e Oclusão de Imagem",
                icon = Icons.Default.Style,
                badge = "$dueCount pendentes",
                onRun = { onOpenFlashcardsForSubject(-1L) }
            ),
            QuickCommandItem(
                id = "anki_stats",
                title = "Abrir Estatísticas Completas do Anki",
                subtitle = "Heatmap 12 semanas, Retenção Real, Sanguessugas, Intervalos e Hora do Dia",
                icon = Icons.Default.Insights,
                badge = "Anki Stats",
                onRun = onOpenAnkiStats
            ),
            QuickCommandItem(
                id = "open_tutor_ai",
                title = "Abrir Tutor IA (Modo Aluno Feynman & Modo Professor)",
                subtitle = "Explique a matéria, audite seus erros no Anki ou peça Cartões/Notas/Tarefas",
                icon = Icons.Default.AutoAwesome,
                badge = "Tutor IA",
                onRun = { onNavigate(Page.Assistente) }
            ),
            QuickCommandItem(
                id = "open_goals",
                title = "Abrir Metas & Marcos",
                subtitle = "Acompanhe metas automáticas de horas, questões, livros, cartões e tarefas",
                icon = Icons.Default.Flag,
                badge = "$activeGoalsCount ativas",
                onRun = { onNavigate(Page.Metas) }
            ),
            QuickCommandItem(
                id = "open_diary",
                title = "Abrir Diário & Humor",
                subtitle = "Check-in de humor, energia, foco e resumo automático do dia",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                badge = "Diário",
                onRun = { onNavigate(Page.Diario) }
            ),
            QuickCommandItem(
                id = "open_books",
                title = "Abrir Livros & Leitura",
                subtitle = "Estante, ritmo pág/h, destaques, metas e notas",
                icon = Icons.Default.AutoStories,
                badge = "${books.size} livros",
                onRun = { onOpenBookSubject(null) }
            ),
            QuickCommandItem(
                id = "open_player",
                title = "Abrir Sons, Binaural & Músicas",
                subtitle = "Mixes ambientes, binaural Alpha/Theta, playlists e sleep timer",
                icon = Icons.Default.Headphones,
                badge = "Áudio",
                onRun = { onNavigate(Page.Player) }
            ),
            QuickCommandItem(
                id = "open_math_arena",
                title = "Arena de Exatas & Cálculo",
                subtitle = "Álgebra, geometria, trigonometria, probabilidade, funções e sprint",
                icon = Icons.Default.Calculate,
                badge = "Exatas",
                onRun = onOpenMathArena
            ),
            QuickCommandItem(
                id = "open_readera",
                title = "Modo Leitura (ReadEra)",
                subtitle = preferredReaderBook?.let { "Continuar ${it.title} em tela cheia" }
                    ?: "Abrir estante e escolher livro para ler",
                icon = Icons.AutoMirrored.Filled.ChromeReaderMode,
                badge = if (preferredReaderBook != null) "Tela cheia" else "Livros",
                onRun = { preferredReaderBook?.let(onOpenReader) ?: onOpenBookSubject(null) }
            ),
            QuickCommandItem(
                id = "theme_studio",
                title = "Personalizar Tema & Aparência (> tema)",
                subtitle = "Superfícies, cores, estilo dos cards, navegação e dashboard modular",
                icon = Icons.Default.Palette,
                badge = "> tema",
                onRun = onOpenThemeStudio
            ),
            QuickCommandItem(
                id = "toggle_theme",
                title = if (darkMode) "Alternar para Tema Claro" else "Alternar para Tema Escuro",
                subtitle = "Alterna rapidamente entre superfície clara e escura",
                icon = if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                badge = "Tema",
                onRun = { onDarkMode(!darkMode) }
            )
        )
    }

    GlobalSearchAndCommandPaletteDialog(
        notes = notes,
        flashcards = flashcards,
        tasks = tasks,
        subjects = subjects,
        books = books,
        decks = decks,
        commands = quickCommands,
        goals = goals,
        journalEntries = journalEntries,
        mistakes = mistakes,
        onDismiss = onDismiss,
        onOpenNote = onOpenNote,
        onCreateNoteWithTitle = onCreateNoteWithTitle,
        onOpenFlashcardsForSubject = onOpenFlashcardsForSubject,
        onReviewCardsImmediate = onReviewCardsImmediate,
        onToggleTask = vm::toggleTask,
        onFocusTask = onFocusTask,
        onOpenSubject = onOpenSubject,
        onStartStudySubject = onStartStudySubject,
        onOpenBook = onOpenBookSubject,
        onReadBook = onReadBook,
        onOpenGoal = { onNavigate(Page.Metas) },
        onOpenJournalDay = { onNavigate(Page.Diario) },
        onOpenSubjectMistakes = onOpenSubjectMistakes,
        onOpenBookDetail = onOpenBookDetail,
        onOpenPlayer = { onNavigate(Page.Player) },
        initialQuery = initialQuery,
        onOpenMathArena = onOpenMathArena,
        onOpenReadEra = { preferredReaderBook?.let(onOpenReader) ?: onOpenBookSubject(null) },
        onOpenBookReader = onOpenReader
    )
}

@Composable
private fun NoteEditorRoute(
    vm: StudyViewModel,
    modifier: Modifier,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onReviewCards: (List<Flashcard>) -> Unit,
    onStartStudySession: (Long?, String, Long?) -> Unit,
    onStartTask: (StudyTask, Long?) -> Unit
) {
    LaunchedEffect(Unit) {
        if (vm.noteEditor.state.value == null) vm.noteEditor.resume()
    }
    val noteEditor by vm.noteEditor.state.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val trashedNotes by vm.trashedNotes.collectAsStateWithLifecycle()
    val noteLinks by vm.noteLinks.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val decks by vm.cardDecks.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val bookNoteLinks by vm.bookNoteLinks.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val noteSnapshots by vm.noteSnapshots.collectAsStateWithLifecycle()
    val activeNoteId by vm.activeNoteId.collectAsStateWithLifecycle()
    val flashcards by remember(vm, activeNoteId) {
        activeNoteId?.let { vm.flashcardsForNote(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeNote = remember(notes, activeNoteId) { notes.firstOrNull { it.id == activeNoteId } }
    val suggestedSubject = remember(books, bookNoteLinks, activeNoteId) {
        books.filter { book -> bookNoteLinks.any { it.noteId == activeNoteId && it.bookId == book.id } }
            .mapNotNull { it.subjectId }
            .distinct()
            .singleOrNull()
    }

    Box(modifier) {
        NoteEditorPlus(
            state = noteEditor,
            controller = vm.noteEditor,
            note = activeNote,
            notes = notes,
            trash = trashedNotes,
            links = noteLinks,
            tasks = tasks,
            decks = decks,
            subjects = subjects,
            suggestedSubject = suggestedSubject,
            onCard = vm::cardFromNote,
            onBack = onBack,
            onDeleted = onDeleted,
            onTogglePin = { activeNote?.let(vm::toggleNotePin) },
            onToggleTask = vm::toggleTask,
            onAddTask = { text -> activeNoteId?.let { vm.saveTask(quickTaskFrom(text, subjects, it)) } },
            flashcards = flashcards,
            sessions = sessions,
            onReviewCards = onReviewCards,
            onStartStudySession = { noteId, noteTitle, subjId -> onStartStudySession(noteId, noteTitle, subjId) },
            onLinkMention = vm::linkMention,
            onStartTask = { task -> onStartTask(task, activeNoteId) },
            onSaveTask = vm::saveTask,
            snapshots = noteSnapshots,
            onRestoreSnapshot = vm::restoreNoteSnapshot,
            onNavigateNote = { targetId -> vm.openNote(targetId) },
            onCreateAndOpenNote = vm::openOrCreateNoteByTitle,
            canNavigateBack = noteEditor?.canGoBack ?: vm.noteEditor.canGoBack,
            canNavigateForward = noteEditor?.canGoForward ?: vm.noteEditor.canGoForward,
            onNavigateBack = vm.noteEditor::goBack,
            onNavigateForward = vm.noteEditor::goForward
        )
    }
}

@Composable
private fun TimerRoute(
    vm: StudyViewModel,
    readingBook: Long?,
    trainingExercise: String?,
    hasOpenScheduledRound: Boolean = false,
    onOpenSubjects: () -> Unit,
    onOpenSounds: () -> Unit,
    onReviewFlashcards: (List<Flashcard>) -> Unit,
    onCramFlashcards: ((List<Flashcard>) -> Unit)? = null,
    onResumeOpenRound: (() -> Unit)? = null,
    onOpenFlashcards: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenReader: (Book) -> Unit
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()
    val flashcards by vm.flashcards.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()

    ActivityTimerHub(
        flow = vm.activityTimers,
        books = books,
        workouts = workouts,
        start = vm::startActivityTimer,
        setRunning = vm::setActivityRunning,
        recordSet = vm::recordActivitySet,
        finish = vm::finishActivity,
        readingBook = readingBook,
        trainingExercise = trainingExercise,
        onOpenSounds = onOpenSounds,
        onOpenReader = onOpenReader,
        onQuickHighlight = { bookId, text, pageNum, chapter, noteText ->
            vm.addBookHighlight(
                bookId,
                BookHighlight(
                    text = text,
                    page = pageNum,
                    chapter = chapter,
                    note = noteText
                )
            )
        }
    ) {
        StudyTimerPage(
            sessionManager = vm.studySessionManager,
            subjects = subjects,
            sessions = sessions,
            notes = notes,
            flashcards = flashcards,
            tasks = tasks,
            onOpenSubjects = onOpenSubjects,
            onOpenSounds = onOpenSounds,
            onReviewFlashcards = onReviewFlashcards,
            onOpenFlashcards = onOpenFlashcards,
            hasOpenScheduledRound = hasOpenScheduledRound,
            onCramFlashcards = onCramFlashcards,
            onResumeOpenRound = onResumeOpenRound,
            onCompleteTask = { task -> vm.toggleTask(task) },
            onOpenNote = onOpenNote,
            onToggleSubtask = vm::toggleTaskSubtask,
            onSaveTask = vm::saveTask
        )
    }
}

@Composable
private fun HistoryRoute(
    vm: StudyViewModel,
    onOpenCalendar: () -> Unit
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    StudyAnalyticsPage(sessions, subjects, onOpenCalendar = onOpenCalendar)
}

@Composable
private fun FitnessRoute(
    vm: StudyViewModel,
    onTimer: (String) -> Unit
) {
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()
    FitnessPage(
        workouts = workouts,
        meals = meals,
        onAddWorkout = vm::addWorkout,
        onDeleteWorkout = vm::deleteWorkout,
        onAddMeal = vm::addMeal,
        onDeleteMeal = vm::deleteMeal,
        onTimer = onTimer
    )
}

@Composable
private fun DiaryRoute(
    vm: StudyViewModel,
    onOpenNote: (Long) -> Unit,
    onOpenCreateNote: (String) -> Unit,
    onOpenAssistant: () -> Unit
) {
    val appCtx = LocalContext.current
    val journalEntries by vm.journalEntries.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val habits by vm.habits.collectAsStateWithLifecycle()
    val allHabitLogs by vm.allHabitLogs.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val flashcards by vm.flashcards.collectAsStateWithLifecycle()
    val cardReviews by vm.recentCardReviews.collectAsStateWithLifecycle()
    val questionLogs by vm.questionLogs.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val readingLogs by vm.readingLogs.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()

    DiaryPage(
        entries = journalEntries,
        sessions = sessions,
        habits = habits,
        completions = allHabitLogs,
        onSave = vm::saveJournalEntry,
        tasks = tasks,
        flashcards = flashcards,
        cardReviews = cardReviews,
        questionLogs = questionLogs,
        books = books,
        readingLogs = readingLogs,
        workouts = workouts,
        meals = meals,
        subjects = subjects,
        notes = notes,
        onDeleteEntry = { entry -> vm.deleteJournalEntry(entry.dayKey) },
        onOpenWikilink = { targetTitle ->
            val clean = targetTitle.trim()
            val match = notes.firstOrNull { it.title.equals(clean, ignoreCase = true) }
            if (match != null) {
                onOpenNote(match.id)
            } else if (clean.isNotEmpty()) {
                onOpenCreateNote(clean)
            }
        },
        onPromoteToNote = { entry ->
            vm.promoteDiaryToNote(entry) { noteId -> onOpenNote(noteId) }
        },
        onCreateTaskFromDiary = { title, subjectId ->
            val clean = title.trim()
            if (clean.isNotEmpty()) {
                vm.saveTask(
                    StudyTask(
                        subjectId = subjectId,
                        title = clean,
                        dueAt = System.currentTimeMillis(),
                        hasDueDate = true,
                        priority = 2
                    )
                )
                Toast.makeText(appCtx, "Tarefa '$clean' adicionada!", Toast.LENGTH_SHORT).show()
            }
        },
        onAskTutorFromDiary = { entry ->
            val date = DiaryAnalytics.localDateFromDayKey(entry.dayKey)
            val dateLabel = "%02d/%02d/%04d".format(date.dayOfMonth, date.monthValue, date.year)
            val moodLabel = DiaryAnalytics.moodPresets.toMap()[entry.mood] ?: entry.mood
            val draftPrompt = buildString {
                append("Com base no meu check-in do diário em $dateLabel ")
                append("(Humor: ${entry.mood} $moodLabel, Energia: ${entry.energy}/5, Foco: ${entry.focus}/5")
                if (entry.tags.isNotBlank()) append(", Tags: ${entry.tags}")
                append("), ")
                if (entry.body.isNotBlank()) {
                    append("reflita sobre o que escrevi e sugira ajustes práticos de estudo e descanso:\n\n\"${entry.body.trim().take(600)}\"")
                } else {
                    append("sugira como adaptar minha rotina de estudo de hoje a esse nível de energia e foco.")
                }
            }
            vm.setTutorMode(StudyTutorMode.PROFESSOR_CRITIC)
            vm.setTutorFocusTopic(entry.title.ifBlank { "Diário de $dateLabel" })
            vm.setTutorDraft(draftPrompt)
            onOpenAssistant()
        }
    )
}

@Composable
private fun TodoRoute(
    vm: StudyViewModel,
    onOpenNote: (Long) -> Unit,
    onOpenTimer: () -> Unit,
    onStudyTask: (StudyTask) -> Unit,
    onOpenMistakes: (Long) -> Unit,
    onReviewCards: (List<Flashcard>) -> Unit,
    onOpenCards: (Long) -> Unit,
    onOpenAssistant: () -> Unit
) {
    val appCtx = LocalContext.current
    val todoScope = rememberCoroutineScope()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val bookNoteLinks by vm.bookNoteLinks.collectAsStateWithLifecycle()
    val dailyGoalMinutes = rememberDailyGoalResolution(subjects).goalMinutes

    TodoPage(
        tasks = tasks,
        subjects = subjects,
        notes = notes,
        onSave = vm::saveTask,
        onToggle = vm::toggleTask,
        onDelete = vm::deleteTask,
        onOpenNote = onOpenNote,
        onStudy = { id ->
            if (vm.studySessionManager.snapshot.value.elapsedFocusSeconds == 0L) {
                vm.studySessionManager.setLinkedTaskId(null)
            }
            vm.studySessionManager.setSubject(id)
            vm.studySessionManager.startOrResume()
            onOpenTimer()
        },
        sessions = sessions,
        onStudyTask = onStudyTask,
        books = books,
        dailyGoalMinutes = dailyGoalMinutes,
        onUpdateSubtasks = { task, subtasksJson -> vm.updateTaskSubtasks(task.id, subtasksJson) },
        onRescheduleAll = vm::rescheduleTasks,
        onDistributeOverdue = vm::distributeOverdueTasks,
        onLogQuestionsFromTask = { subjectId, topic, total, correct, openMistakes ->
            val targetSubjId = subjectId ?: subjects.firstOrNull()?.id
            if (targetSubjId != null) {
                vm.addQuestionLog(targetSubjId, topic, total, correct)
                if (openMistakes) onOpenMistakes(targetSubjId)
            } else {
                Toast.makeText(appCtx, "Associe uma matéria para registrar questões.", Toast.LENGTH_SHORT).show()
            }
        },
        onAdvanceBookFromTask = vm::advanceBookPage,
        onOpenFlashcardsForNote = { noteId ->
            todoScope.launch {
                val noteCards = vm.activeFlashcardsForNote(noteId)
                if (noteCards.isNotEmpty()) {
                    onReviewCards(noteCards)
                } else {
                    val linkedSubjId = books
                        .filter { book -> bookNoteLinks.any { it.noteId == noteId && it.bookId == book.id } }
                        .mapNotNull { it.subjectId }
                        .firstOrNull()
                        ?: tasks.firstOrNull { it.noteId == noteId && it.subjectId != null }?.subjectId
                    onOpenCards(linkedSubjId ?: -1L)
                }
            }
        },
        onOpenTutorForTask = { task ->
            val subjName = subjects.firstOrNull { it.id == task.subjectId }?.name
            val draftPrompt = buildString {
                append("Decomponha a tarefa \"${task.title}\"")
                if (!subjName.isNullOrBlank()) append(" (matéria: $subjName)")
                append(" em subtarefas práticas e sequenciais para eu estudar hoje.")
                if (task.details.isNotBlank()) append(" Contexto: ${task.details}")
            }
            vm.setTutorMode(StudyTutorMode.AGENT_CREATOR)
            vm.setTutorFocusTopic(task.title)
            vm.setTutorDraft(draftPrompt)
            onOpenAssistant()
        }
    )
}

@Composable
private fun HabitsRoute(vm: StudyViewModel) {
    val habits by vm.habits.collectAsStateWithLifecycle()
    val habitAreas by vm.habitAreas.collectAsStateWithLifecycle()
    val habitDay by vm.habitDay.collectAsStateWithLifecycle()
    val habitDayCompletions by vm.habitDayCompletions.collectAsStateWithLifecycle()
    val habitHistory by vm.habitHistory.collectAsStateWithLifecycle()
    val allHabitLogs by vm.allHabitLogs.collectAsStateWithLifecycle()
    val allHabitNotes by vm.allHabitNotes.collectAsStateWithLifecycle()
    val allHabitTasks by vm.allHabitTasks.collectAsStateWithLifecycle()

    HabitsStudioPage(
        habits = habits,
        areas = habitAreas,
        dayKey = habitDay,
        completions = habitDayCompletions,
        history = habitHistory,
        allLogs = allHabitLogs,
        habitNotes = allHabitNotes,
        habitTasks = allHabitTasks,
        onAdd = vm::addHabit,
        onAddWithStyle = vm::addHabitWithStyle,
        onAddArea = vm::addHabitArea,
        onLog = vm::logHabit,
        onDelete = vm::deleteHabit,
        onDay = vm::selectHabitDay,
        onShiftDay = vm::shiftHabitDay,
        onUpdate = vm::updateHabit,
        onAddHabitNote = { habitId, content, dayKey, mood ->
            vm.addHabitNote(habitId, content, dayKey, mood)
        },
        onUpdateHabitNote = vm::updateHabitNote,
        onDeleteHabitNote = vm::deleteHabitNote,
        onAddHabitTask = vm::addHabitTask,
        onToggleHabitTask = vm::toggleHabitTask,
        onDeleteHabitTask = vm::deleteHabitTask
    )
}

@Composable
private fun GoalsRoute(vm: StudyViewModel) {
    val goals by vm.goals.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val questionLogs by vm.questionLogs.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val readingLogs by vm.readingLogs.collectAsStateWithLifecycle()
    val flashcards by vm.flashcards.collectAsStateWithLifecycle()
    val cardReviews by vm.cardReviews.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val allHabitLogs by vm.allHabitLogs.collectAsStateWithLifecycle()

    GoalsDashboardPage(
        goals = goals,
        subjects = subjects,
        sessions = sessions,
        questionLogs = questionLogs,
        books = books,
        readingLogs = readingLogs,
        flashcards = flashcards,
        cardReviews = cardReviews,
        tasks = tasks,
        habitCompletions = allHabitLogs,
        onSaveGoal = vm::saveGoal,
        onUpdateGoal = vm::updateGoal,
        onCompleteGoal = vm::completeGoal,
        onTogglePinned = vm::toggleGoalPinned,
        onDeleteGoal = vm::deleteGoal
    )
}

@Composable
private fun NotesListRoute(
    vm: StudyViewModel,
    onCreate: () -> Unit,
    onOpen: (Long) -> Unit,
    onRecoverDraft: (String) -> Unit,
    onCreateWithTemplate: (String, String) -> Unit,
    onExitWorkspace: () -> Unit,
    requestedCanvasBoardId: Long? = null,
    onRequestedCanvasBoardConsumed: () -> Unit = {}
) {
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val noteDrafts by vm.noteDrafts.collectAsStateWithLifecycle()
    val trashedNotes by vm.trashedNotes.collectAsStateWithLifecycle()
    val noteLinks by vm.noteLinks.collectAsStateWithLifecycle()
    val canvasBoards by vm.canvasBoards.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val vaultActions = rememberObsidianVaultZipActions { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    NotesListPage(
        notes = notes,
        tasks = tasks,
        drafts = noteDrafts,
        trash = trashedNotes,
        links = noteLinks,
        onCreate = onCreate,
        onOpen = onOpen,
        onDaily = { vm.openDailyNote { id -> onOpen(id) } },
        onTogglePin = vm::toggleNotePin,
        onDraft = onRecoverDraft,
        onDiscardDraft = vm::discardNoteDraft,
        onRestore = vm::restoreNote,
        onCreateWithTemplate = { template ->
            onCreateWithTemplate(template.defaultTitle, template.initialMarkdown)
        },
        onPurgeNote = vm::purgeNoteForever,
        onEmptyTrash = vm::emptyNoteTrash,
        onExportVaultZip = vaultActions.exportVault,
        onImportVaultZip = vaultActions.importVault,
        canvasBoards = canvasBoards,
        onSaveCanvasBoard = vm::saveCanvasBoard,
        onDeleteCanvasBoard = vm::deleteCanvasBoard,
        onCreateOrOpenTitle = vm::openOrCreateNoteByTitle,
        onExitWorkspace = onExitWorkspace,
        requestedCanvasBoardId = requestedCanvasBoardId,
        onRequestedCanvasBoardConsumed = onRequestedCanvasBoardConsumed
    )
}

@Composable
private fun SubjectsRoute(
    vm: StudyViewModel,
    isSessionActive: Boolean,
    onStartSubject: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val flashcards by vm.flashcards.collectAsStateWithLifecycle()
    val mistakes by vm.mistakes.collectAsStateWithLifecycle()
    val questionLogs by vm.questionLogs.collectAsStateWithLifecycle()
    val subjectStartRule by remember(vm) {
        vm.studySessionManager.snapshot
            .map { Triple(it.state != TimerState.RUNNING, it.elapsedFocusSeconds == 0L, it.subjectId) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = Triple(true, true, null))
    val dailyGoalMinutes = rememberDailyGoalResolution(subjects).goalMinutes

    SubjectsDashboardPage(
        subjects = subjects,
        sessions = sessions,
        tasks = tasks,
        flashcards = flashcards,
        mistakes = mistakes,
        questionLogs = questionLogs,
        dailyGoalMinutes = dailyGoalMinutes,
        onSaveSubject = vm::saveSubject,
        onDeleteSubject = vm::deleteSubject,
        onDeleteSubjectWithChildren = vm::deleteSubjectWithChildren,
        onStart = onStartSubject,
        onOpen = onOpenDetail,
        canStart = { id ->
            subjectStartRule.first && (subjectStartRule.second || subjectStartRule.third == id)
        },
        timerLocked = isSessionActive
    )
}

@Composable
private fun SubjectDetailRoute(
    vm: StudyViewModel,
    detailSubjectId: Long?,
    detailInitialTab: Int,
    onBack: () -> Unit,
    onStartSubject: (Long) -> Unit,
    onStartStudy: (Long?, String, Long?, Long?) -> Unit,
    onOpenCards: (Long) -> Unit,
    onOpenBooks: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
    onReviewSubjectCards: (List<Flashcard>) -> Unit,
    onOpenGoals: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenMathArena: (Long) -> Unit,
    onOpenSubject: ((Long) -> Unit)? = null,
    onMissingSubject: () -> Unit
) {
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val subject = subjects.firstOrNull { it.id == detailSubjectId }
    if (subject == null) {
        LaunchedEffect(detailSubjectId, subjects) {
            delay(200)
            if (vm.subjects.value.none { it.id == detailSubjectId }) {
                onMissingSubject()
            }
        }
        return
    }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val subjectItems by vm.subjectItems.collectAsStateWithLifecycle()
    val questionLogs by vm.questionLogs.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val mistakes by vm.mistakes.collectAsStateWithLifecycle()
    val flashcards by vm.flashcards.collectAsStateWithLifecycle()
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()
    val bookNoteLinks by vm.bookNoteLinks.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val cardReviews by vm.recentCardReviews.collectAsStateWithLifecycle()
    val readingLogs by vm.readingLogs.collectAsStateWithLifecycle()
    val allHabitLogs by vm.allHabitLogs.collectAsStateWithLifecycle()
    val dailyGoalMinutes = rememberDailyGoalResolution(subjects).goalMinutes

    SubjectDetailPage(
        subject = subject,
        sessions = sessions,
        tasks = tasks,
        items = subjectItems,
        questions = questionLogs,
        books = books,
        onBack = onBack,
        onStart = { onStartSubject(subject.id) },
        onAddTask = { title, dueAt -> vm.addTask(subject.id, title, dueAt) },
        onCompleteTask = vm::completeTask,
        onDeleteTask = vm::deleteTask,
        onSaveItem = { item -> if (item.id == 0L) vm.addSubjectItem(item) else vm.updateSubjectItem(item) },
        onDeleteItem = vm::deleteSubjectItem,
        mistakes = mistakes,
        onMistake = vm::addMistake,
        onAddQuestions = { topic, total, correct -> vm.addQuestionLog(subject.id, topic, total, correct) },
        cardCount = flashcards.count { it.subjectId == subject.id },
        onOpenCards = { onOpenCards(subject.id) },
        onOpenBooks = { onOpenBooks(subject.id) },
        onStartTask = { task -> onStartStudy(task.subjectId ?: subject.id, task.title, task.noteId, task.id) },
        onSaveTask = vm::saveTask,
        onToggleSubtask = vm::toggleTaskSubtask,
        initialTab = detailInitialTab,
        notes = notes,
        bookNoteLinks = bookNoteLinks,
        flashcards = flashcards,
        goals = goals,
        cardReviews = cardReviews,
        readingLogs = readingLogs,
        habitCompletions = allHabitLogs,
        allSubjects = subjects,
        dailyGoalMinutes = dailyGoalMinutes,
        onUpdateSubject = vm::updateSubject,
        onStartTopic = { topic -> onStartStudy(subject.id, topic, null, null) },
        onOpenNote = onOpenNote,
        onCreateNoteForSubject = { subj ->
            vm.createNoteForSubject(subj) { noteId -> onOpenNote(noteId) }
        },
        onReviewSubjectCards = onReviewSubjectCards,
        onOpenGoals = onOpenGoals,
        onOpenMathArena = onOpenMathArena,
        onOpenSubject = onOpenSubject,
        onSaveSubject = vm::saveSubject,
        onUpdateMistake = vm::updateMistake,
        onDeleteMistake = vm::deleteMistake,
        onReviewMistake = vm::reviewMistake,
        onMistakeToCard = vm::flashcardFromMistake,
        onMistakeToTask = vm::taskFromMistake,
        onMistakeToTutor = { subj, mistake ->
            val draftPrompt = buildString {
                append("Estou com dificuldade no erro abaixo em ${subj.name} (tópico: ${mistake.topic}). ")
                append("Explique o raciocínio correto, aponte a armadilha e sugira uma revisão ou flashcard:\n\n")
                if (mistake.question.isNotBlank()) appendLine("- Questão/Erro: ${mistake.question}")
                if (mistake.reason.isNotBlank()) appendLine("- Por que errei: ${mistake.reason}")
                if (mistake.solution.isNotBlank()) appendLine("- Solução anotada: ${mistake.solution}")
            }.trim()
            vm.setTutorMode(StudyTutorMode.PROFESSOR_CRITIC)
            vm.setTutorFocusTopic("${subj.name}: ${mistake.topic}")
            vm.setTutorDraft(draftPrompt)
            onOpenAssistant()
        }
    )
}

@Composable
private fun BooksRoute(
    vm: StudyViewModel,
    bookSubjectId: Long?,
    onRead: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenBookDetail: (Long) -> Unit,
    onOpenReader: (Book) -> Unit
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()
    val bookNoteLinks by vm.bookNoteLinks.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val readingLogs by vm.readingLogs.collectAsStateWithLifecycle()
    val activityTimers by vm.activityTimers.collectAsStateWithLifecycle(emptyList())

    BooksPage(
        books = books,
        notes = notes,
        links = bookNoteLinks,
        subjects = subjects,
        initialSubjectId = bookSubjectId,
        onSave = { book, noteIds, onSaved -> vm.saveBook(book, noteIds, onSaved) },
        onRead = onRead,
        onDelete = vm::deleteBook,
        onOpenNote = onOpenNote,
        onCreateNote = { id, title -> vm.createBookNote(id, title) { noteId -> onOpenNote(noteId) } },
        readingLogs = readingLogs,
        activityTimers = activityTimers,
        onCreateFlashcardFromQuote = { book, question, quote -> vm.cardFromBookQuote(book, question, quote) },
        onCreateNoteFromQuote = { book, quote, summary ->
            vm.noteFromBookQuote(book, quote, summary) { noteId -> onOpenNote(noteId) }
        },
        onOpenBookDetail = onOpenBookDetail,
        onAdvancePages = { book, newPage -> vm.advanceBookPage(book.id, newPage) },
        onChangeStatus = { book, newStatus -> vm.setBookStatus(book.id, newStatus) },
        onOpenReader = onOpenReader
    )
}

@Composable
private fun BookDetailRoute(
    vm: StudyViewModel,
    detailBookId: Long?,
    onBack: () -> Unit,
    onReadWithTimer: (Long) -> Unit,
    onDeleted: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenReader: (Book) -> Unit,
    onMissingBook: () -> Unit
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == detailBookId }
    if (book == null) {
        LaunchedEffect(detailBookId, books) {
            delay(200)
            if (vm.books.value.none { it.id == detailBookId }) {
                onMissingBook()
            }
        }
        return
    }
    val bookNoteLinks by vm.bookNoteLinks.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()
    val readingLogs by vm.readingLogs.collectAsStateWithLifecycle()
    val activityTimers by vm.activityTimers.collectAsStateWithLifecycle(emptyList())
    val linkedNoteIds = remember(bookNoteLinks, book.id) {
        bookNoteLinks.asSequence().filter { it.bookId == book.id }.map { it.noteId }.toSet()
    }

    BookDetailPage(
        book = book,
        subjects = subjects,
        notes = notes,
        linkedNoteIds = linkedNoteIds,
        readingLogs = readingLogs,
        activityTimers = activityTimers,
        onBack = onBack,
        onReadWithTimer = { selected -> onReadWithTimer(selected.id) },
        onSaveBook = { updated, noteIds -> vm.saveBook(updated, noteIds) {} },
        onDeleteBook = { target ->
            vm.deleteBook(target)
            onDeleted()
        },
        onAdvancePage = { target, newPage -> vm.advanceBookPage(target.id, newPage) },
        onUpdateNoteLinks = vm::updateBookNoteLinks,
        onUpdateHighlights = { target, nextList -> vm.updateBookHighlights(target.id, nextList) },
        onOpenNote = onOpenNote,
        onCreateLinkedNote = { target ->
            vm.createBookNote(target.id, target.title) { noteId -> onOpenNote(noteId) }
        },
        onCreateFlashcardFromHighlight = { target, question, quote -> vm.cardFromBookQuote(target, question, quote) },
        onCreateNoteFromHighlight = { target, quote, summary ->
            vm.noteFromBookQuote(target, quote, summary) { noteId -> onOpenNote(noteId) }
        },
        onCreateReadingGoal = vm::createReadingGoalForBook,
        onCreateReadingTask = vm::createReadingTaskForBook,
        onOpenTutorForBook = { target, highlights ->
            val subjName = subjects.firstOrNull { it.id == target.subjectId }?.name
            val draftPrompt = buildString {
                append("Estou lendo o livro \"${target.title}\"")
                if (target.author.isNotBlank()) append(" de ${target.author}")
                if (!subjName.isNullOrBlank()) append(" (matéria: $subjName)")
                append(" (página ${target.currentPage}${target.totalPages?.takeIf { it > 0 }?.let { "/$it" } ?: ""}")
                if (target.currentChapter.isNotBlank()) append(", capítulo: ${target.currentChapter}")
                appendLine(").")
                if (highlights.isNotEmpty()) {
                    appendLine("\nDestaques salvos:")
                    highlights.takeLast(8).forEach { hl ->
                        append("- \"${hl.text.trim().take(240)}\"")
                        hl.page?.let { append(" (p. $it)") }
                        if (hl.note.isNotBlank()) append(" — Nota: ${hl.note.trim().take(120)}")
                        appendLine()
                    }
                }
                append("\nAjude-me a revisar os conceitos principais deste livro, fazer perguntas no estilo Feynman ou sugerir flashcards/notas.")
            }.trim()
            vm.setTutorMode(StudyTutorMode.STUDENT_FEYNMAN)
            vm.setTutorFocusTopic(target.title)
            vm.setTutorDraft(draftPrompt)
            onOpenAssistant()
        },
        onOpenReader = onOpenReader,
        onChangeStatus = { target, newStatus -> vm.setBookStatus(target.id, newStatus) },
        onAttachDocument = { target, uri, name, hint -> vm.attachBookDocument(target.id, uri, name, hint) }
    )
}

@Composable
private fun ReadEraRoute(
    vm: StudyViewModel,
    readerBookId: Long?,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenSounds: () -> Unit
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == readerBookId }
    if (book == null) {
        LaunchedEffect(readerBookId, books) {
            delay(200)
            if (vm.books.value.none { it.id == readerBookId }) {
                onBack()
            }
        }
        return
    }
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()
    val bookNoteLinks by vm.bookNoteLinks.collectAsStateWithLifecycle()
    val linkedNoteIds = remember(bookNoteLinks, book.id) {
        bookNoteLinks.asSequence().filter { it.bookId == book.id }.map { it.noteId }.toSet()
    }
    val linkedNotes = remember(notes, linkedNoteIds) {
        notes.filter { it.id in linkedNoteIds && it.trashedAt == null }
    }

    ReadEraReaderPage(
        book = book,
        linkedNotes = linkedNotes,
        onBack = onBack,
        onAttachDocument = { target, uri, name, hint ->
            vm.attachBookDocument(target.id, uri, name, hint)
        },
        onRecordProgress = { target, page, hint, mins ->
            vm.recordBookReaderProgress(target.id, page, hint, mins)
        },
        onAddHighlight = { target, quote, pageNum ->
            vm.addBookHighlight(
                target.id,
                BookHighlight(
                    text = quote,
                    page = pageNum,
                    chapter = target.currentChapter
                )
            )
        },
        onSaveHighlightFull = { target, hl ->
            vm.addBookHighlight(target.id, hl)
        },
        onChangeStatus = { target, newStatus ->
            vm.setBookStatus(target.id, newStatus)
        },
        onUpdateBook = { updated ->
            vm.saveBook(updated, linkedNoteIds) {}
        },
        onOpenNote = onOpenNote,
        onCreateLinkedNote = { target ->
            vm.createBookNote(target.id, target.title) { noteId -> onOpenNote(noteId) }
        },
        onOpenSounds = onOpenSounds
    )
}

@Composable
private fun MathPracticeRoute(
    vm: StudyViewModel,
    mathSubjectId: Long?,
    onBack: () -> Unit
) {
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    MathPracticePage(
        subjects = subjects,
        initialSubjectId = mathSubjectId,
        onSavePracticeResult = vm::logMathPracticeSession,
        onCreateFlashcardsFromMistakes = vm::createFlashcardsFromMathMistakes,
        onBack = onBack
    )
}

@Composable
private fun FlashcardsRoute(
    vm: StudyViewModel,
    showCardStats: Boolean,
    onShowCardStatsChange: (Boolean) -> Unit,
    subjectFilter: Long,
    onSubjectFilterChange: (Long) -> Unit,
    deck: Long,
    onDeckChange: (Long) -> Unit,
    onResumeRound: () -> Unit,
    onFinishRound: () -> Unit,
    onStartRound: (List<Flashcard>, Boolean) -> Unit
) {
    val flashcards by vm.flashcards.collectAsStateWithLifecycle()
    val cardReviews by vm.cardReviews.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val decks by vm.cardDecks.collectAsStateWithLifecycle()
    val templates by vm.cardTemplates.collectAsStateWithLifecycle()
    val round by vm.reviewRound.collectAsStateWithLifecycle(initialValue = null)
    val canUndoReview by vm.canUndoReview.collectAsStateWithLifecycle()

    val deckStudyIndex by vm.deckStudyIndex.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val emptyFallbackIndex = remember(zone) {
        DeckStudyIndex(emptyList(), emptyList(), emptyList(), LocalDate.now(zone), zone)
    }
    val effectiveIndex = deckStudyIndex ?: emptyFallbackIndex
    val indexUpdating = deckStudyIndex == null
    val queueNow by vm.queueClockMillis.collectAsStateWithLifecycle()

    FlashcardsPage(
        cards = flashcards,
        reviews = cardReviews,
        subjects = subjects,
        showStats = showCardStats,
        selectStats = onShowCardStatsChange,
        decks = decks,
        index = effectiveIndex,
        indexUpdating = indexUpdating,
        now = queueNow,
        saveDeck = vm::saveDeck,
        dissolveDeck = vm::dissolveDeck,
        deleteCard = vm::deleteCard,
        setSuspended = vm::setSuspended,
        importDeck = vm::importDeck,
        exportDeck = vm::exportDeck,
        subjectFilter = subjectFilter,
        selectSubject = onSubjectFilterChange,
        deck = deck,
        selectDeck = onDeckChange,
        templates = templates,
        loadAuthor = vm::authorNote,
        loadImage = vm::answerImage,
        saveAuthor = vm::saveCardNote,
        round = round,
        onResume = onResumeRound,
        onFinish = onFinishRound,
        onReview = { batch -> onStartRound(batch, false) },
        onCramReview = { batch -> onStartRound(batch, true) },
        undoLastReview = vm::undoLastReview,
        canUndoReview = canUndoReview,
        batchMoveDeck = vm::moveCardsToDeck,
        batchSetSuspended = vm::suspendCards,
        batchAddTag = vm::addTagToCards,
        batchSetFlag = vm::setCardFlags,
        batchReschedule = vm::rescheduleCards,
        batchResetCards = vm::resetCards,
        batchDeleteCards = vm::deleteCards,
        createFilteredDeck = vm::createFilteredDeck,
        importApkg = vm::importApkgUri,
        importCsv = vm::importCsvDeck,
        exportCsv = vm::exportDeckCsv,
        onBuryCard = vm::buryCard,
        onSetCardFlag = vm::setCardFlag
    )
}

@Composable
private fun ReviewRoute(
    vm: StudyViewModel,
    onPause: () -> Unit,
    onFinish: () -> Unit
) {
    val round by vm.reviewRound.collectAsStateWithLifecycle(initialValue = null)
    val cardReviews by vm.recentCardReviews.collectAsStateWithLifecycle()
    val decks by vm.cardDecks.collectAsStateWithLifecycle()
    val canUndo by vm.canUndoReview.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun launchCardAction(errorLabel: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.localizedMessage ?: errorLabel, Toast.LENGTH_SHORT).show()
            }
        }
    }

    ReviewPage(
        round = round,
        reviews = cardReviews,
        loadImage = vm::answerImage,
        onGrade = { row, grade, ms, allowEarly, cb -> vm.reviewFlashcard(row, grade, ms, allowEarly, cb) },
        onTime = vm::saveAnswerTime,
        onPause = onPause,
        onFinish = onFinish,
        decks = decks,
        canUndo = canUndo,
        onUndo = vm::undoLastReview,
        onBuryCard = { card ->
            launchCardAction("Não foi possível enterrar o cartão.") { vm.buryCard(card.id) }
        },
        onSuspendCard = { card ->
            launchCardAction("Não foi possível suspender o cartão.") { vm.setSuspended(card.id, true) }
        },
        onSetFlag = { card, flag ->
            launchCardAction("Não foi possível atualizar a bandeira.") { vm.setCardFlag(card.id, flag) }
        },
        onDeleteCard = { card ->
            launchCardAction("Não foi possível excluir o cartão.") { vm.deleteCard(card.id) }
        },
        onSaveQuickEdit = vm::saveQuickCardEdit,
        loadAuthor = vm::authorNote,
        loadNote = vm::loadNoteById,
        loadCardReviews = vm::reviewsForCard
    )
}

@Composable
private fun AssistantRoute(
    vm: StudyViewModel,
    onOpenNote: (Long) -> Unit,
    onOpenCanvas: (Long) -> Unit
) {
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val habits by vm.habits.collectAsStateWithLifecycle()
    val completed by vm.todayCompletions.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val flashcards by vm.flashcards.collectAsStateWithLifecycle()
    val dueCount by vm.dueFlashcardsCount.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val notes by vm.noteHeaders.collectAsStateWithLifecycle()
    val canvasBoards by vm.canvasBoards.collectAsStateWithLifecycle()
    val questionLogs by vm.questionLogs.collectAsStateWithLifecycle()
    val readingLogs by vm.readingLogs.collectAsStateWithLifecycle()
    val cardReviews by vm.recentCardReviews.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val allHabitLogs by vm.allHabitLogs.collectAsStateWithLifecycle()
    val journalEntries by vm.journalEntries.collectAsStateWithLifecycle()
    val mistakes by vm.mistakes.collectAsStateWithLifecycle()
    val tutorState by vm.tutorState.collectAsStateWithLifecycle()

    val assistantStudyContext = remember(
        subjects, sessions, habits, completed, goals, events, flashcards, dueCount, books,
        notes, canvasBoards, questionLogs, readingLogs, cardReviews, tasks, allHabitLogs, mistakes
    ) {
        buildTutorStudyContext(
            subjects = subjects,
            sessions = sessions,
            habits = habits,
            completedTodayCount = completed.size,
            goals = goals,
            events = events,
            flashcards = flashcards,
            dueCount = dueCount,
            books = books,
            notes = notes,
            canvasBoards = canvasBoards,
            questionLogs = questionLogs,
            readingLogs = readingLogs,
            cardReviews = cardReviews,
            tasks = tasks,
            habitCompletions = allHabitLogs,
            mistakes = mistakes
        )
    }

    AssistantPage(
        studyContext = assistantStudyContext,
        subjects = subjects,
        notes = notes,
        canvasBoards = canvasBoards,
        flashcards = flashcards,
        mistakes = mistakes,
        questionLogs = questionLogs,
        tutorState = tutorState,
        onTutorModeChange = vm::setTutorMode,
        onFeynmanLevelChange = vm::setFeynmanLevel,
        onFocusTopicChange = vm::setTutorFocusTopic,
        onDraftChange = vm::setTutorDraft,
        onSendPrompt = vm::sendTutorPrompt,
        onClearConversation = vm::clearTutorConversation,
        onApplyAction = { actionKey, obj, openAfter ->
            vm.applyTutorAction(
                actionKey = actionKey,
                obj = obj,
                openNoteAfter = openAfter,
                onOpenNote = onOpenNote,
                onOpenCanvas = onOpenCanvas
            )
        },
        onOpenSavedNote = onOpenNote,
        onOpenSavedCanvas = onOpenCanvas
    )
}

private fun slotIdToPage(slotId: String): Page = when (slotId) {
    StudyNavSlots.SLOT_HOJE -> Page.Hoje
    StudyNavSlots.SLOT_TIMER -> Page.Timer
    StudyNavSlots.SLOT_AGENDA -> Page.Agenda
    StudyNavSlots.SLOT_ORGANIZAR -> Page.Organizar
    StudyNavSlots.SLOT_TAREFAS -> Page.Tarefas
    StudyNavSlots.SLOT_NOTAS -> Page.Notas
    StudyNavSlots.SLOT_CARTOES -> Page.Cartoes
    StudyNavSlots.SLOT_LIVROS -> Page.Livros
    StudyNavSlots.SLOT_DIARIO -> Page.Diario
    StudyNavSlots.SLOT_MATERIAS -> Page.Materias
    StudyNavSlots.SLOT_METAS -> Page.Metas
    StudyNavSlots.SLOT_ASSISTENTE -> Page.Assistente
    StudyNavSlots.SLOT_MAIS -> Page.Mais
    else -> Page.Mais
}

private fun resolvedActiveNavPage(currentPage: Page, activeSlotPages: Set<Page>): Page {
    if (currentPage in activeSlotPages) return currentPage
    return when (currentPage) {
        Page.Historico -> if (Page.Agenda in activeSlotPages) Page.Agenda else Page.Mais
        Page.EditarNota -> when {
            Page.Notas in activeSlotPages -> Page.Notas
            Page.Organizar in activeSlotPages -> Page.Organizar
            else -> Page.Mais
        }
        Page.DetalheMateria -> when {
            Page.Materias in activeSlotPages -> Page.Materias
            Page.Organizar in activeSlotPages -> Page.Organizar
            else -> Page.Mais
        }
        Page.DetalheLivro, Page.Leitor -> when {
            Page.Livros in activeSlotPages -> Page.Livros
            Page.Organizar in activeSlotPages -> Page.Organizar
            else -> Page.Mais
        }
        Page.Revisao -> if (Page.Cartoes in activeSlotPages) Page.Cartoes else Page.Mais
        Page.Materias, Page.Tarefas, Page.Habitos, Page.Metas, Page.Notas, Page.Livros, Page.Fitness, Page.Diario, Page.Matematica ->
            if (Page.Organizar in activeSlotPages) Page.Organizar else Page.Mais
        else -> Page.Mais
    }
}

@Composable
private fun ActiveStudyBanner(subjectName: String, elapsedSeconds: Long, running: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    StudyCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        containerColorOverride = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f)) {
                Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(10.dp).size(20.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(
                    if (running) "Estudo em andamento" else "Sessão pausada",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$subjectName · ${formatElapsedHms(elapsedSeconds)}",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .82f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (running) "Pausar estudo" else "Continuar estudo",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun formatElapsedHms(seconds: Long): String =
    String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)

private fun buildTutorStudyContext(
    subjects: List<Subject>,
    sessions: List<StudySession>,
    habits: List<Habit>,
    completedTodayCount: Int,
    goals: List<Goal>,
    events: List<CalendarEvent>,
    flashcards: List<Flashcard>,
    dueCount: Int,
    books: List<Book>,
    notes: List<Note>,
    canvasBoards: List<CanvasBoard>,
    questionLogs: List<QuestionLog>,
    readingLogs: List<ReadingLog>,
    cardReviews: List<CardReview>,
    tasks: List<StudyTask>,
    habitCompletions: List<HabitCompletion>,
    mistakes: List<Mistake> = emptyList()
): String = buildString {
    val activeSubjects = subjects.filter { !it.archived }
    if (activeSubjects.isEmpty()) {
        appendLine("Matérias ativas: nenhuma cadastrada.")
    } else {
        appendLine("Matérias ativas:")
        activeSubjects.forEach { subj ->
            val meta = buildList {
                if (subj.weight > 1) add("peso ${subj.weight}")
                if (subj.targetMinutesPerDay > 0) add("meta ${subj.targetMinutesPerDay} min/dia")
                subj.nextStep?.trim()?.takeIf { it.isNotEmpty() }?.let { add("próximo passo: \"$it\"") }
            }
            if (meta.isEmpty()) appendLine("- ${subj.name}")
            else appendLine("- ${subj.name} (${meta.joinToString("; ")})")
        }
    }
    appendLine("Sessões registradas: ${sessions.size}; tempo total: ${sessions.sumOf { it.durationSeconds } / 3600}h ${sessions.sumOf { it.durationSeconds } % 3600 / 60}min.")
    appendLine("Hábitos: ${habits.size}; concluídos hoje: $completedTodayCount.")
    val openGoals = goals.filter { !it.completed }.sortedWith(compareByDescending<Goal> { it.pinned }.thenBy { it.targetAt })
    appendLine("Metas abertas (${openGoals.size}) e concluídas (${goals.count { it.completed }}):")
    openGoals.take(10).forEach { goal ->
        val snap = GoalProgressEngine.compute(
            goal = goal,
            sessions = sessions,
            questionLogs = questionLogs,
            books = books,
            readingLogs = readingLogs,
            flashcards = flashcards,
            cardReviews = cardReviews,
            tasks = tasks,
            habitCompletions = habitCompletions
        )
        val pct = (snap.progressFraction * 100f).toInt().coerceIn(0, 100)
        val pace = snap.paceStatusLabel?.let { " · $it" }.orEmpty()
        val pinnedTag = if (goal.pinned) " [Fixada]" else ""
        appendLine("- ${goal.title}$pinnedTag [${snap.metricType.label}]: ${snap.displayCurrent} / ${snap.displayTarget} ($pct%$pace)")
    }
    if (mistakes.isNotEmpty()) {
        val subjectMap = subjects.associateBy { it.id }
        val byTopic = mistakes.groupBy { m ->
            val sName = subjectMap[m.subjectId]?.name ?: "Geral"
            "$sName · ${m.topic.ifBlank { "Geral" }}"
        }.entries.sortedByDescending { it.value.size }.take(6)
        appendLine("Padrões de erro recorrentes no Caderno de Erros (${mistakes.size} total): ${byTopic.joinToString { "${it.key} (${it.value.size}x)" }}.")
    }
    val zone = ZoneId.systemDefault()
    appendLine(
        "Compromissos: ${events.take(10).joinToString {
            val dateText = Instant.ofEpochMilli(it.at).atZone(zone).format(MAIN_DAY_MONTH_FORMATTER)
            "${it.title} ($dateText)"
        }.ifBlank { "nenhum" }}."
    )
    appendLine("Cartões: ${flashcards.size}; em revisão agora: $dueCount.")
    val activeBooks = books.filter { it.status == "READING" }
    appendLine("Livros: ${books.size}; em andamento: ${activeBooks.size}; concluídos: ${books.count { it.status == "DONE" }}.")
    activeBooks.take(5).forEach { b ->
        val totalStr = b.totalPages?.takeIf { it > 0 }?.let { "/$it" } ?: ""
        val hlCount = BookHighlights.count(b)
        append("- Lendo: ${b.title} (p. ${b.currentPage}$totalStr")
        if (b.currentChapter.isNotBlank()) append(", cap. ${b.currentChapter}")
        if (hlCount > 0) append(", $hlCount destaques")
        appendLine(")")
    }
    notes.take(8).forEach { appendLine("Nota ‘${it.title}’: ${it.markdown.take(700)}") }
    if (canvasBoards.isNotEmpty()) {
        appendLine("Canvas visuais salvos (${canvasBoards.size}): ${canvasBoards.take(8).joinToString { it.title }}.")
    }
}
