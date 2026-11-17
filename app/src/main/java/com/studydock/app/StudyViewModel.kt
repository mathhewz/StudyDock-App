package com.studydock.app

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.studydock.app.audio.FocusAudioService
import com.studydock.app.data.*
import com.studydock.app.timer.PomodoroPhase
import com.studydock.app.timer.StudySessionManager
import com.studydock.app.timer.StudyTimerMode
import com.studydock.app.timer.TimerState
import com.studydock.app.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val DASHBOARD_WINDOW_DAYS = 365L
private const val DIARY_DASHBOARD_WINDOW_DAYS = 30L
private val DEFAULT_HABIT_AREAS = listOf("Manhã", "Tarde", "Noite", "Dia")
private const val ANKI_ROUND_PREFS = "studydock_anki_round"
private const val KEY_ROUND_STARTED_AT = "round_started_at"
private val CLOZE_MARKER_REGEX = Regex("""\{\{c\d+::""")
private val INVALID_FOLDER_CHARS_REGEX = Regex("""[<>:"|?*]""")
private val INVALID_PATH_CHARS_REGEX = Regex("""[/\\<>:"|?*]""")
private val NON_ALNUM_RUN_REGEX = Regex("""[^a-z0-9]+""")
private val FLOW_SHARING_POLICY = SharingStarted.WhileSubscribed(60_000L)

@OptIn(ExperimentalCoroutinesApi::class)
class StudyViewModel(initialDao: StudyDao, context: Context) : ViewModel() {
    private val appContext: Context = context.applicationContext
    @Volatile private var currentDao: StudyDao = initialDao
    private val importMutex = Mutex()

    val studySessionManager = StudySessionManager.get(appContext, initialDao)

    private val daoLock = Any()
    private val dao: StudyDao
        get() = synchronized(daoLock) {
            val active = if (StudyDatabase.databaseEpoch.value > 0L) {
                runCatching { StudyDatabase.get(appContext).studyDao() }.getOrDefault(currentDao)
            } else {
                currentDao
            }
            if (active !== currentDao) {
                currentDao = active
                studySessionManager.updateDao(active)
            }
            active
        }

    private val activeDaoFlow: Flow<StudyDao> = StudyDatabase.databaseEpoch
        .map {
            val next = if (it > 0L) {
                runCatching { StudyDatabase.get(appContext).studyDao() }.getOrDefault(currentDao)
            } else {
                currentDao
            }
            currentDao = next
            studySessionManager.updateDao(next)
            next
        }
        .distinctUntilChanged()

    val isTimerRunning: StateFlow<Boolean> = studySessionManager.snapshot
        .map { it.state == TimerState.RUNNING }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            studySessionManager.snapshot.value.state == TimerState.RUNNING
        )

    val isSessionActive: StateFlow<Boolean> = studySessionManager.snapshot
        .map { it.state == TimerState.RUNNING || it.state == TimerState.PAUSED || it.elapsedFocusSeconds > 0L }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            studySessionManager.snapshot.value.let {
                it.state == TimerState.RUNNING || it.state == TimerState.PAUSED || it.elapsedFocusSeconds > 0L
            }
        )

    // Tickers ativos apenas enquanto houver coletores inscritos (WhileSubscribed).
    private val currentDayFlow: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(30_000L)
        }
    }.distinctUntilChanged()

    private val currentMinuteMillis: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000L)
        }
    }

    private val sixtySecondClockFlow: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    val subjects = activeDaoFlow.flatMapLatest { it.subjects() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val sessions = activeDaoFlow.flatMapLatest { it.sessions() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val events = activeDaoFlow.flatMapLatest { it.events() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val tasks = activeDaoFlow.flatMapLatest { it.tasks() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val subjectItems = activeDaoFlow.flatMapLatest { it.subjectItems() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val mistakes = activeDaoFlow.flatMapLatest { it.mistakes() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    /**
     * Fluxos leves com janela temporal recente (365 dias) para a aba Hoje/Dashboard:
     * evita carregar histórico multi-anual no cold start preservando sequências de até 1 ano,
     * gráficos de 30 dias, mapa mensal e metas fixadas recentes. Telas de histórico completo
     * (`StudyAnalyticsPage`, `GoalsDashboardPage`) usam os fluxos integrais (`sessions`, `cardReviews`, etc.).
     */
    val recentSessions: StateFlow<List<StudySession>> = combine(activeDaoFlow, currentDayFlow) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.recentSessions(startOfDayMillis(day.minusDays(DASHBOARD_WINDOW_DAYS))) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    val recentCardReviews: StateFlow<List<CardReview>> = combine(activeDaoFlow, currentDayFlow) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.recentCardReviews(startOfDayMillis(day.minusDays(DASHBOARD_WINDOW_DAYS))) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    val recentQuestionLogs: StateFlow<List<QuestionLog>> = combine(activeDaoFlow, currentDayFlow) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.recentQuestionLogs(startOfDayMillis(day.minusDays(DASHBOARD_WINDOW_DAYS))) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    val recentReadingLogs: StateFlow<List<ReadingLog>> = combine(activeDaoFlow, currentDayFlow) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.recentReadingLogs(startOfDayMillis(day.minusDays(DASHBOARD_WINDOW_DAYS))) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    val recentHabitCompletions: StateFlow<List<HabitCompletion>> = combine(activeDaoFlow, currentDayFlow) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.recentHabitCompletions(dayKeyOf(day.minusDays(DASHBOARD_WINDOW_DAYS))) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    val recentJournalEntries: StateFlow<List<JournalEntry>> = combine(activeDaoFlow, currentDayFlow) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.recentJournalEntries(dayKeyOf(day.minusDays(DIARY_DASHBOARD_WINDOW_DAYS))) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    val todayUpcomingEvents: StateFlow<List<CalendarEvent>> = combine(activeDaoFlow, currentDayFlow) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.upcomingEvents(startOfDayMillis(day)) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    // Contadores leves SQL para chrome (pílula de busca) e grid de estatísticas sem deserializar listas inteiras.
    val dueFlashcardsCount: StateFlow<Int> = combine(activeDaoFlow, currentDayFlow) { d, _ -> d }
        .flatMapLatest { d -> d.dueFlashcardsCount(System.currentTimeMillis()) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, 0)

    val sessionsCount: StateFlow<Int> = activeDaoFlow.flatMapLatest { it.sessionsCount() }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, 0)

    val activeGoalsCount: StateFlow<Int> = activeDaoFlow.flatMapLatest { it.activeGoalsCount() }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, 0)

    val activeNotesCount: StateFlow<Int> = activeDaoFlow.flatMapLatest { it.activeNotesCount() }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, 0)

    val booksCount: StateFlow<Int> = activeDaoFlow.flatMapLatest { it.booksCount() }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, 0)

    fun addMistake(mistake: Mistake) { viewModelScope.launch { dao.addMistake(mistake) } }
    fun updateMistake(mistake: Mistake) { viewModelScope.launch { dao.updateMistake(mistake) } }
    fun deleteMistake(mistake: Mistake) { viewModelScope.launch { dao.deleteMistake(mistake) } }

    fun reviewMistake(mistake: Mistake, rememberedWell: Boolean) {
        viewModelScope.launch {
            dao.updateMistake(MistakeSpacedReview.scheduleNextReview(mistake, rememberedWell))
        }
    }

    private suspend fun insertCardRespectingCloze(
        front: String,
        back: String,
        deckId: Long?,
        subjectId: Long?,
        sourceNoteId: Long? = null,
        sourceExcerpt: String? = null,
        tags: String = ""
    ) {
        val cleanFront = front.trim()
        val cleanBack = back.trim()
        val hasClozeInFront = CLOZE_MARKER_REGEX.containsMatchIn(cleanFront)
        val hasCloze = hasClozeInFront || CLOZE_MARKER_REGEX.containsMatchIn(cleanBack)
        if (hasCloze) {
            val prompt = when {
                hasClozeInFront -> cleanFront
                cleanFront.isNotEmpty() -> "$cleanFront\n\n$cleanBack"
                else -> cleanBack
            }
            val extraAnswer = if (hasClozeInFront) cleanBack else ""
            val cleanTags = normalizeCardTags(tags)
            val authorNote = CardAuthorNote(
                format = "CLOZE",
                prompt = prompt,
                answer = extraAnswer,
                tags = cleanTags
            )
            if (authorNote.variants().isNotEmpty()) {
                dao.saveCardNoteWithSource(
                    note = authorNote,
                    deckId = deckId,
                    subjectId = subjectId,
                    sourceNoteId = sourceNoteId,
                    sourceExcerpt = sourceExcerpt
                )
                return
            }
        }
        dao.addFlashcard(
            Flashcard(
                deckId = deckId,
                subjectId = subjectId,
                front = cleanFront,
                back = cleanBack,
                sourceNoteId = sourceNoteId,
                sourceExcerpt = sourceExcerpt,
                tags = normalizeCardTags(tags)
            )
        )
    }

    fun flashcardFromMistake(subject: Subject, mistake: Mistake) {
        viewModelScope.launch {
            val deckName = "Erros · ${subject.name.take(32).trim()}"
            val deckId = runCatching { dao.getOrCreateRootDeck(deckName) }.getOrNull()
            insertCardRespectingCloze(
                front = MistakeSpacedReview.formatFlashcardFront(subject.name, mistake),
                back = MistakeSpacedReview.formatFlashcardBack(mistake),
                deckId = deckId,
                subjectId = subject.id
            )
            android.widget.Toast.makeText(
                appContext,
                "Flashcard de erro criado em '$deckName'!",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun taskFromMistake(subject: Subject, mistake: Mistake) {
        viewModelScope.launch {
            val topicTitle = mistake.topic.trim().ifBlank { mistake.question.trim().take(40) }.ifBlank { "Erro" }
            val details = buildString {
                if (mistake.question.isNotBlank()) appendLine("Questão: ${mistake.question.trim()}")
                if (mistake.reason.isNotBlank()) appendLine("Causa: ${mistake.reason.trim()}")
                if (mistake.solution.isNotBlank()) appendLine("Solução: ${mistake.solution.trim()}")
            }.trim()
            val now = System.currentTimeMillis()
            val dueAt = if (mistake.nextReviewAt > now) mistake.nextReviewAt else now + 86_400_000L
            dao.addTask(
                StudyTask(
                    subjectId = subject.id,
                    title = "Revisar erro (${subject.name}): $topicTitle".take(120),
                    dueAt = dueAt,
                    priority = 2,
                    details = details
                )
            )
            android.widget.Toast.makeText(
                appContext,
                "Tarefa de revisão criada para ${subject.name}!",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun setNextStep(id: Long, text: String) { viewModelScope.launch { dao.setNextStep(id, text.trim()) } }

    fun cardFromNote(noteId: Long, question: String, excerpt: String, deckId: Long?, subjectId: Long?) {
        viewModelScope.launch {
            insertCardRespectingCloze(
                front = question,
                back = excerpt,
                deckId = deckId,
                subjectId = subjectId,
                sourceNoteId = noteId,
                sourceExcerpt = excerpt
            )
        }
    }

    val questionLogs = activeDaoFlow.flatMapLatest { it.questionLogs() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val workouts = activeDaoFlow.flatMapLatest { it.workoutLogs() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val meals = activeDaoFlow.flatMapLatest { it.mealLogs() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val journalEntries = activeDaoFlow.flatMapLatest { it.journalEntries() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val habits = activeDaoFlow.flatMapLatest { it.habits() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val habitAreas = activeDaoFlow.flatMapLatest { it.habitAreas() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val allHabitLogs = activeDaoFlow.flatMapLatest { it.allHabitCompletions() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    private val _habitDay = MutableStateFlow(todayKey())
    val habitDay = _habitDay.asStateFlow()
    val habitDayCompletions = combine(activeDaoFlow, habitDay) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.completionsForDay(day) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val habitHistory = combine(activeDaoFlow, habitDay) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.habitCompletionsBetween(shiftDayKey(day, -27), day) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val allHabitNotes: StateFlow<List<HabitNote>> = activeDaoFlow.flatMapLatest { it.allHabitNotes() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val allHabitTasks: StateFlow<List<HabitTask>> = activeDaoFlow.flatMapLatest { it.allHabitTasks() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val todayCompletions = combine(activeDaoFlow, currentDayFlow) { d, day -> d to day }
        .flatMapLatest { (d, day) -> d.completionsForDay(dayKeyOf(day)) }
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    val goals = activeDaoFlow.flatMapLatest { it.goals() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val workoutPlans = activeDaoFlow.flatMapLatest { it.workoutPlans() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    fun saveWorkoutPlanEntity(name: String, movesJson: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertWorkoutPlan(com.studydock.app.data.WorkoutPlanEntity(name = name.trim(), movesJson = movesJson))
        }
    }
    val notes = activeDaoFlow.flatMapLatest { it.noteHeadersFlow() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val noteHeaders = activeDaoFlow.flatMapLatest { it.noteHeadersFlow() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val activityTimers: StateFlow<List<ActivityTimer>> = activeDaoFlow.flatMapLatest { it.activityTimers() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    fun startActivityTimer(mode: String, title: String, bookId: Long?) {
        viewModelScope.launch {
            val wasInactive = dao.activeTimerCount(mode) == 0
            dao.startActivity(mode, title, bookId)
            if (wasInactive && mode == "Leitura") {
                startAudioForContext("READING")
            }
        }
    }

    fun setActivityRunning(id: Long, running: Boolean) {
        viewModelScope.launch {
            val timer = dao.openTimer(id)
            dao.setActivityRunning(id, running)
            if (timer?.mode == "Leitura") {
                if (running) startAudioForContext("READING") else pauseAudioForContext()
            }
        }
    }

    fun recordActivitySet(id: Long, reps: Int, rest: Int) { viewModelScope.launch { dao.recordSet(id, reps, rest) } }

    fun finishActivity(id: Long, page: Int?) {
        viewModelScope.launch {
            val timer = dao.openTimer(id)
            dao.finishActivity(id, page)
            if (timer?.mode == "Leitura") {
                pauseAudioForContext()
            }
        }
    }

    val readingLogs = activeDaoFlow.flatMapLatest { it.readingLogs() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val books = activeDaoFlow.flatMapLatest { it.books() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val bookNoteLinks = activeDaoFlow.flatMapLatest { it.bookNoteLinks() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val flashcards = activeDaoFlow.flatMapLatest { it.flashcards() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val cardDecks = activeDaoFlow.flatMapLatest { it.cardDecks() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    suspend fun saveDeck(deck: CardDeck) = withContext(Dispatchers.IO) { dao.saveDeckSettings(deck) }
    suspend fun deleteCard(id: Long) { deleteCards(listOf(id)) }
    suspend fun dissolveDeck(id: Long) = withContext(Dispatchers.IO) { dao.dissolveDeck(id) }
    suspend fun setSuspended(id: Long, suspended: Boolean) { suspendCards(listOf(id), suspended) }
    suspend fun loadNoteById(id: Long): Note? = withContext(Dispatchers.IO) { dao.findNote(id) }
    suspend fun saveNoteQuick(note: Note): Long = withContext(Dispatchers.IO) { dao.saveNote(note) }
    suspend fun reviewsForCard(cardId: Long): List<CardReview> = withContext(Dispatchers.IO) { dao.reviewsForCard(cardId) }
    suspend fun activeFlashcardsForNote(noteId: Long): List<Flashcard> = withContext(Dispatchers.IO) { dao.activeFlashcardsForNote(noteId) }
    fun flashcardsForSubject(subjectId: Long): Flow<List<Flashcard>> = activeDaoFlow.flatMapLatest { it.flashcardsForSubject(subjectId) }
    fun flashcardsForNote(noteId: Long): Flow<List<Flashcard>> = activeDaoFlow.flatMapLatest { it.flashcardsForNote(noteId) }
    suspend fun importDeck(file: DeckFile) = withContext(Dispatchers.IO) { dao.importDeckFile(file) }
    suspend fun exportDeck(id: Long, name: String) = withContext(Dispatchers.IO) { dao.exportDeckFile(id, name) }
    suspend fun answerImage(id: Long) = withContext(Dispatchers.IO) { dao.answerImage(id) }
    suspend fun authorNote(id: Long) = withContext(Dispatchers.IO) { dao.authorNote(id) }
    val cardTemplates = activeDaoFlow.flatMapLatest { it.cardTemplates() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    suspend fun saveCardNote(id: Long?, expected: CardAuthorNote?, note: CardAuthorNote, deck: Long?, subject: Long?, image: CardImage?) =
        withContext(Dispatchers.IO) { dao.saveCardNote(id, expected, note, deck, subject, image) }
    val cardReviews = activeDaoFlow.flatMapLatest { it.cardReviews() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    private val _activeNoteId = MutableStateFlow<Long?>(null)
    val activeNoteId = _activeNoteId.asStateFlow()
    val noteEditor = NoteEditorController(initialDao, appContext, viewModelScope) { _activeNoteId.value = it }
    val isNoteEditorBusy: StateFlow<Boolean> = noteEditor.state
        .map { it?.loading == true || it?.leaving == true }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            noteEditor.state.value.let { it?.loading == true || it?.leaving == true }
        )
    val noteLinks = activeDaoFlow.flatMapLatest { it.noteLinks() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val noteDrafts = activeDaoFlow.flatMapLatest { it.noteDrafts() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val trashedNotes = activeDaoFlow.flatMapLatest { it.trashedNotes() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val noteSnapshots: StateFlow<List<NoteSnapshot>> =
        activeDaoFlow.flatMapLatest { it.noteSnapshots() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())
    val canvasBoards: StateFlow<List<CanvasBoard>> =
        activeDaoFlow.flatMapLatest { it.canvasBoards() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    suspend fun linkMention(sourceId: Long, start: Int, end: Int, targetId: Long, targetTitle: String): Boolean =
        withContext(Dispatchers.IO) { dao.linkMention(sourceId, start, end, targetId, targetTitle) }

    suspend fun restoreNoteSnapshot(snapshotId: Long) {
        val updated = withContext(Dispatchers.IO) { dao.restoreNoteFromSnapshot(snapshotId) } ?: return
        noteEditor.rebaseIfOpen(updated.id, updated.revision, updated.title, updated.markdown)
    }

    fun purgeNoteForever(noteId: Long) {
        viewModelScope.launch { dao.purgeTrashedNote(noteId) }
    }

    fun emptyNoteTrash() {
        viewModelScope.launch { dao.emptyTrashedNotes() }
    }

    suspend fun saveCanvasBoard(board: CanvasBoard): Long = dao.upsertCanvasBoard(board)

    suspend fun deleteCanvasBoard(id: Long) = dao.deleteCanvasBoard(id)

    fun restoreNote(id: Long) {
        viewModelScope.launch {
            try {
                dao.restoreNote(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StudyLog.w("StudyViewModel", "Falha ao restaurar nota $id", e)
                android.widget.Toast.makeText(appContext, "Não foi possível restaurar a nota.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun discardNoteDraft(draft: NoteDraft) {
        viewModelScope.launch {
            try {
                if (dao.discardNoteDraft(draft.key, draft.revision) == 0) {
                    android.widget.Toast.makeText(appContext, "O rascunho mudou. Abra-o antes de descartar.", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StudyLog.w("StudyViewModel", "Falha ao descartar rascunho", e)
                android.widget.Toast.makeText(appContext, "Não foi possível descartar o rascunho.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    init {
        // Seed e limpeza leve fora da main thread para não competir com o primeiro frame no cold start.
        viewModelScope.launch(Dispatchers.IO) {
            DEFAULT_HABIT_AREAS.forEachIndexed { index, area ->
                dao.addHabitArea(HabitArea(area, index))
            }
            runCatching {
                appContext.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("apkg_") }
                    ?.forEach { it.deleteRecursively() }
                cleanupStaleDatabaseImportFiles(
                    filesDir = appContext.filesDir,
                    databasesDir = appContext.getDatabasePath("studydock.db").parentFile
                )
                dao.cleanOrphanedReferencesAndMedia()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                StudyLog.w("StudyViewModel", "Falha na limpeza leve de inicialização", err)
            }
        }
        viewModelScope.launch {
            activeDaoFlow.collect { latestDao ->
                noteEditor.updateDao(latestDao)
                studySessionManager.updateDao(latestDao)
            }
        }
        viewModelScope.launch {
            studySessionManager.snapshot
                .map { snap ->
                    snap.state == TimerState.RUNNING &&
                        (snap.mode != StudyTimerMode.POMODORO || snap.pomodoroPhase == PomodoroPhase.FOCUS)
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { isFocusRunning ->
                    if (isFocusRunning) startAudioForContext("STUDY")
                    else pauseAudioForContext()
                }
        }
    }

    fun startAudioForContext(contextType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snap = FocusAudioStore.readSnapshot(appContext)
                val isReading = contextType.equals("READING", ignoreCase = true) ||
                    contextType.equals("Leitura", ignoreCase = true)
                val enabled = if (isReading) snap.autoPlayOnReading else snap.autoPlayOnStudy
                if (!enabled || snap.ambientPlaying || snap.musicPlaying) return@launch
                val intent = Intent(appContext, FocusAudioService::class.java)
                    .setAction(FocusAudioService.ACTION_PLAY_PRESET_TARGET)
                    .putExtra(FocusAudioService.EXTRA_CONTEXT_TYPE, if (isReading) "READING" else "STUDY")
                ContextCompat.startForegroundService(appContext, intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StudyLog.w("StudyViewModel", "Falha ao iniciar áudio de contexto ($contextType)", e)
            }
        }
    }

    fun pauseAudioForContext() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snap = FocusAudioStore.readSnapshot(appContext)
                if (!snap.autoPauseOnBreak) return@launch
                if (!snap.ambientPlaying && !snap.musicPlaying) return@launch
                val intent = Intent(appContext, FocusAudioService::class.java)
                    .setAction(FocusAudioService.ACTION_PAUSE_ALL)
                appContext.startService(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StudyLog.w("StudyViewModel", "Falha ao pausar áudio de contexto", e)
            }
        }
    }

    fun addSubject(name: String, color: Long) {
        viewModelScope.launch {
            val id = dao.addSubject(Subject(name = name.trim(), color = color))
            studySessionManager.setSubject(id)
        }
    }

    fun saveSubject(subject: Subject) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingSubjects = dao.subjects().first()
            val hasChildren = subject.id != 0L && existingSubjects.any { it.parentId == subject.id }
            val safeParentId = if (hasChildren) {
                null
            } else {
                val candidateParent = subject.parentId?.takeIf { it > 0L && it != subject.id }
                val parentObj = candidateParent?.let { pid -> existingSubjects.firstOrNull { it.id == pid } }
                if (parentObj != null && parentObj.parentId == null) candidateParent else null
            }
            val clean = subject.copy(
                name = subject.name.trim(),
                nextStep = subject.nextStep?.trim()?.ifBlank { null },
                weight = subject.weight.coerceIn(1, 5),
                targetMinutesPerDay = subject.targetMinutesPerDay.coerceIn(0, 1440),
                parentId = safeParentId
            )
            if (clean.id == 0L) {
                val id = dao.addSubject(clean)
                if (studySessionManager.snapshot.value.subjectId == null && !clean.archived) {
                    studySessionManager.setSubject(id)
                }
            } else {
                dao.updateSubject(clean)
            }
        }
    }

    fun updateSubject(subject: Subject) { saveSubject(subject) }

    suspend fun resolveTargetSubjectId(preferredSubjectId: Long?): Long? {
        if (preferredSubjectId != null) return preferredSubjectId
        val list = subjects.value.ifEmpty { dao.subjects().first() }
        return list.firstOrNull { !it.archived }?.id ?: list.firstOrNull()?.id
    }

    fun createNoteForSubject(subject: Subject, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val noteId = withContext(Dispatchers.IO) {
                val parentName = subject.parentId?.let { pid ->
                    subjects.value.firstOrNull { it.id == pid }?.name
                }?.replace(INVALID_FOLDER_CHARS_REGEX, "-")?.trim()?.takeIf { it.isNotBlank() }
                val cleanName = subject.name.replace(INVALID_FOLDER_CHARS_REGEX, "-").trim().ifBlank { "Matéria" }
                val folderPath = if (parentName != null) "$parentName/$cleanName" else cleanName
                val tagSlug = cleanName.lowercase(Locale.ROOT).replace(NON_ALNUM_RUN_REGEX, "_").trim('_').ifBlank { "materia" }
                val title = if (parentName != null) "$parentName › ${subject.name.trim()} — Resumo" else "${subject.name.trim()} — Resumo"
                val markdown = buildString {
                    appendLine("---")
                    appendLine("folder: $folderPath")
                    appendLine("tags: [materia, $tagSlug]")
                    appendLine("---")
                    appendLine()
                    appendLine(if (parentName != null) "# $parentName › ${subject.name.trim()}" else "# ${subject.name.trim()}")
                    appendLine()
                    if (!subject.nextStep.isNullOrBlank()) {
                        appendLine("> **Próximo passo:** ${subject.nextStep.trim()}")
                        appendLine()
                    }
                    appendLine("## Tópicos principais")
                    appendLine("- ")
                    appendLine()
                    appendLine("## Pontos de atenção & Erros")
                    appendLine("- ")
                    appendLine()
                    appendLine("#materia #$tagSlug")
                }
                dao.saveNote(Note(title = title, markdown = markdown, updatedAt = System.currentTimeMillis()))
            }
            onCreated(noteId)
        }
    }

    fun deleteSubject(subject: Subject) {
        deleteSubjectWithChildren(subject, deleteSubSubjects = false)
    }

    fun deleteSubjectWithChildren(subject: Subject, deleteSubSubjects: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val allSubjects = dao.subjects().first()
            val childIds = if (deleteSubSubjects) {
                allSubjects.filter { it.parentId == subject.id }.map { it.id }.toSet()
            } else {
                emptySet()
            }
            val activeSubjectId = studySessionManager.snapshot.value.subjectId
            if (activeSubjectId == subject.id || (activeSubjectId != null && activeSubjectId in childIds)) {
                if (studySessionManager.snapshot.value.elapsedFocusSeconds > 0L) {
                    studySessionManager.saveAndFinish()
                } else {
                    studySessionManager.resetOrDiscard()
                }
                studySessionManager.setSubject(null)
            }
            if (deleteSubSubjects) {
                dao.deleteChildSubjects(subject.id)
            } else {
                dao.unlinkChildSubjects(subject.id)
            }
            dao.deleteSubject(subject)
        }
    }

    fun addEvent(title: String, at: Long) { viewModelScope.launch { dao.addEvent(CalendarEvent(title = title.trim(), at = at)) } }
    fun deleteEvent(event: CalendarEvent) { viewModelScope.launch { dao.deleteEvent(event) } }
    fun addTask(subjectId: Long?, title: String, dueAt: Long) { viewModelScope.launch { dao.addTask(StudyTask(subjectId = subjectId, title = title.trim(), dueAt = dueAt)) } }

    fun completeTask(id: Long, complete: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.changeTaskCompletion(id, complete)
            val task = tasks.value.firstOrNull { it.id == id } ?: dao.taskById(id)
            val noteId = task?.noteId
            if (task != null && noteId != null) {
                val activeSubjects = subjects.value.ifEmpty { dao.subjects().first() }
                val editorState = noteEditor.state.value
                if (editorState != null && editorState.noteId == noteId && !editorState.loading) {
                    val next = NoteChecklists.syncTaskCompletion(
                        markdown = editorState.body.text,
                        taskTitle = task.title,
                        completed = complete,
                        subjects = activeSubjects
                    )
                    if (next != editorState.body.text) {
                        val sel = editorState.body.selection
                        noteEditor.body(
                            editorState.body.copy(
                                text = next,
                                selection = TextRange(
                                    sel.start.coerceIn(0, next.length),
                                    sel.end.coerceIn(0, next.length)
                                )
                            )
                        )
                    }
                } else {
                    val note = dao.findNote(noteId)
                    if (note != null) {
                        val synced = NoteChecklists.syncTaskCompletion(
                            markdown = note.markdown,
                            taskTitle = task.title,
                            completed = complete,
                            subjects = activeSubjects
                        )
                        if (synced != note.markdown) {
                            val updated = dao.updateNoteContentAndRebase(note.id, note.title, synced, System.currentTimeMillis())
                            if (updated != null) {
                                noteEditor.rebaseIfOpen(updated.id, updated.revision, updated.title, updated.markdown)
                            }
                        }
                    }
                }
            }
        }
    }

    fun deleteTask(task: StudyTask) { viewModelScope.launch { dao.deleteTask(task) } }
    fun saveTask(task: StudyTask) { viewModelScope.launch { if (task.id == 0L) dao.addTask(task) else dao.updateTask(task) } }
    fun toggleTask(task: StudyTask) = completeTask(task.id, !task.completed)

    fun rescheduleTasks(ids: List<Long>, dueAt: Long, hasDueDate: Boolean = true) {
        if (ids.isEmpty()) return
        viewModelScope.launch { dao.rescheduleTasks(ids, dueAt, hasDueDate) }
    }

    fun distributeOverdueTasks(newDueById: Map<Long, Long>) {
        if (newDueById.isEmpty()) return
        viewModelScope.launch { dao.applyRescheduleMap(newDueById) }
    }

    fun updateTaskSubtasks(taskId: Long, subtasksJson: String) {
        viewModelScope.launch { dao.updateTaskSubtasks(taskId, subtasksJson) }
    }

    fun toggleTaskSubtask(task: StudyTask, subtaskId: String) {
        updateTaskSubtasks(task.id, TaskSubtasks.toggle(task.subtasksJson, subtaskId))
    }

    fun advanceBookPage(bookId: Long, newPage: Int) {
        viewModelScope.launch {
            if (dao.advanceBookPage(bookId, newPage)) {
                android.widget.Toast.makeText(appContext, "Leitura atualizada para p. $newPage", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun addBookHighlight(bookId: Long, highlight: BookHighlight) {
        if (highlight.text.isBlank()) return
        viewModelScope.launch {
            val book = dao.bookById(bookId) ?: return@launch
            val updatedList = BookHighlights.add(book, highlight)
            val (json, legacy) = BookHighlights.encodeForBook(updatedList)
            dao.updateBookHighlights(bookId, json, legacy)
            android.widget.Toast.makeText(appContext, "Destaque salvo em '${book.title}'!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun updateBookHighlights(bookId: Long, highlights: List<BookHighlight>) {
        if (bookId <= 0L) return
        viewModelScope.launch {
            val (json, legacy) = BookHighlights.encodeForBook(highlights)
            dao.updateBookHighlights(bookId, json, legacy)
        }
    }

    fun updateBookNoteLinks(bookId: Long, noteIds: Set<Long>) {
        if (bookId <= 0L) return
        viewModelScope.launch { dao.replaceBookNoteLinks(bookId, noteIds.toList()) }
    }

    fun createReadingGoalForBook(book: Book) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val targetPages = (book.totalPages?.takeIf { it > 0 } ?: (book.currentPage + 100).coerceAtLeast(100)).toDouble()
            val targetAt = book.targetFinishAt?.takeIf { it > now } ?: (now + 14L * 86_400_000L)
            dao.addGoal(
                Goal(
                    title = "Ler ${book.title.take(50).trim()}",
                    targetAt = targetAt,
                    direction = "COUNTDOWN",
                    unit = "DAYS",
                    roundUp = true,
                    color = 0xFF6156D9L,
                    style = "BAR",
                    metricType = GoalMetricType.PAGES.id,
                    currentValue = book.currentPage.coerceAtLeast(0).toDouble(),
                    targetValue = targetPages,
                    metricUnit = GoalMetricType.PAGES.defaultUnit,
                    subjectId = book.subjectId
                )
            )
            android.widget.Toast.makeText(appContext, "Meta de leitura criada para '${book.title}'!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun createReadingTaskForBook(book: Book) {
        viewModelScope.launch {
            val pageLabel = if (book.totalPages != null && book.totalPages > 0) {
                "p. ${book.currentPage}/${book.totalPages}"
            } else {
                "p. ${book.currentPage}"
            }
            val details = buildString {
                if (book.author.isNotBlank()) appendLine("Autor: ${book.author}")
                if (book.currentChapter.isNotBlank()) appendLine("Capítulo atual: ${book.currentChapter}")
                append("Progresso atual: $pageLabel")
            }.trim()
            dao.addTask(
                StudyTask(
                    subjectId = book.subjectId,
                    title = "Continuar leitura: ${book.title.take(55).trim()} ($pageLabel)",
                    dueAt = System.currentTimeMillis(),
                    priority = 2,
                    details = details,
                    hasDueDate = true,
                    tags = "leitura",
                    bookId = book.id.takeIf { it > 0L }
                )
            )
            android.widget.Toast.makeText(appContext, "Tarefa de leitura criada!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun addSubjectItem(item: SubjectItem) { viewModelScope.launch { dao.addSubjectItem(item) } }
    fun updateSubjectItem(item: SubjectItem) { viewModelScope.launch { dao.updateSubjectItem(item) } }
    fun deleteSubjectItem(item: SubjectItem) { viewModelScope.launch { dao.deleteSubjectItem(item) } }

    fun addQuestionLog(subjectId: Long, topic: String, total: Int, correct: Int) {
        if (total <= 0) return
        viewModelScope.launch {
            dao.addQuestionLog(
                QuestionLog(
                    subjectId = subjectId,
                    topic = topic.trim().ifBlank { "Geral" },
                    questionCount = total,
                    correctCount = correct.coerceIn(0, total)
                )
            )
        }
    }

    fun addWorkout(title: String, exercises: String, durationMinutes: Int, notes: String) {
        viewModelScope.launch {
            dao.addWorkoutLog(WorkoutLog(title = title, exercises = exercises, durationMinutes = durationMinutes.coerceAtLeast(0), notes = notes))
        }
    }

    fun deleteWorkout(workout: WorkoutLog) { viewModelScope.launch { dao.deleteWorkoutLog(workout) } }

    fun addMeal(type: String, foods: String, calories: Int, protein: Double, carbs: Double, fat: Double) {
        viewModelScope.launch {
            dao.addMealLog(
                MealLog(
                    mealType = type,
                    foods = foods,
                    calories = calories.coerceAtLeast(0),
                    proteinGrams = protein.coerceAtLeast(0.0),
                    carbsGrams = carbs.coerceAtLeast(0.0),
                    fatGrams = fat.coerceAtLeast(0.0)
                )
            )
        }
    }

    fun deleteMeal(meal: MealLog) { viewModelScope.launch { dao.deleteMealLog(meal) } }
    fun saveJournalEntry(entry: JournalEntry) { viewModelScope.launch { dao.saveJournalEntry(entry.copy(updatedAt = System.currentTimeMillis())) } }
    fun deleteJournalEntry(dayKey: Long) { viewModelScope.launch { dao.deleteJournalEntry(dayKey) } }
    fun selectHabitDay(dayKey: Long) { _habitDay.value = dayKey }
    fun shiftHabitDay(amount: Int) { _habitDay.value = shiftDayKey(_habitDay.value, amount) }

    fun addHabit(title: String, emoji: String, area: String, kind: String, daysMask: Int, target: Double, unit: String, period: String, reminderMinutes: Int?, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val cleanArea = area.trim().ifBlank { "Dia" }
            val currentAreas = habitAreas.value.ifEmpty { dao.habitAreas().first() }
            if (currentAreas.none { it.name.equals(cleanArea, true) }) dao.addHabitArea(HabitArea(cleanArea, 100))
            val id = dao.addHabit(
                Habit(
                    title = title.trim(),
                    emoji = emoji,
                    area = cleanArea,
                    kind = kind,
                    repeatDaysMask = daysMask,
                    targetValue = target,
                    targetUnit = unit,
                    targetPeriod = period,
                    reminderMinutes = reminderMinutes
                )
            )
            onCreated(id)
        }
    }

    fun addHabitArea(name: String) { viewModelScope.launch { dao.addHabitArea(HabitArea(name.trim(), 100)) } }

    fun logHabit(id: Long, dayKey: Long, status: String, value: Double, note: String? = null) {
        viewModelScope.launch {
            if (status == "NONE") dao.removeHabitCompletion(id, dayKey)
            else {
                val loggedAt = if (dayKey == todayKey()) {
                    System.currentTimeMillis()
                } else {
                    val zone = ZoneId.systemDefault()
                    val y = (dayKey / 10000L).toInt().coerceIn(1970, 2100)
                    val m = ((dayKey % 10000L) / 100L).toInt().coerceIn(1, 12)
                    val maxD = java.time.YearMonth.of(y, m).lengthOfMonth()
                    val d = (dayKey % 100L).toInt().coerceIn(1, maxD)
                    LocalDate.of(y, m, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                }
                dao.saveHabitCompletion(HabitCompletion(id, dayKey, status, value, note, loggedAt))
            }
        }
    }

    fun deleteHabit(habit: Habit) { viewModelScope.launch { dao.deleteHabit(habit) } }
    fun updateHabit(habit: Habit) { viewModelScope.launch { dao.updateHabit(habit) } }

    /** Versão estendida de [addHabit] com ícone, cor, descrição e data de início. [addHabit] permanece inalterada (usada via `vm::addHabit`). */
    fun addHabitWithStyle(
        title: String,
        emoji: String,
        iconKey: String,
        color: Long,
        description: String,
        area: String,
        kind: String,
        daysMask: Int,
        target: Double,
        unit: String,
        period: String,
        reminderMinutes: Int?,
        startedAt: Long = System.currentTimeMillis(),
        onCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val cleanArea = area.trim().ifBlank { "Dia" }
            val currentAreas = habitAreas.value.ifEmpty { dao.habitAreas().first() }
            if (currentAreas.none { it.name.equals(cleanArea, true) }) dao.addHabitArea(HabitArea(cleanArea, 100))
            val now = System.currentTimeMillis()
            val id = dao.addHabit(
                Habit(
                    title = title.trim(),
                    emoji = emoji,
                    createdAt = now,
                    area = cleanArea,
                    kind = kind,
                    repeatDaysMask = daysMask,
                    targetValue = target,
                    targetUnit = unit,
                    targetPeriod = period,
                    reminderMinutes = reminderMinutes,
                    iconKey = iconKey.trim().ifBlank { HabitIconCatalog.DEFAULT_KEY },
                    color = color,
                    description = description.trim(),
                    startedAt = if (startedAt > 0L) startedAt else now
                )
            )
            onCreated(id)
        }
    }

    fun addHabitNote(habitId: Long, content: String, dayKey: Long = todayKey(), mood: String = "") {
        val text = content.trim()
        if (habitId <= 0L || text.isEmpty()) return
        viewModelScope.launch { dao.addHabitNote(HabitNote(habitId = habitId, content = text, dayKey = dayKey, mood = mood)) }
    }

    fun updateHabitNote(note: HabitNote) {
        if (note.content.isBlank()) return
        viewModelScope.launch { dao.updateHabitNote(note.copy(content = note.content.trim())) }
    }

    fun deleteHabitNote(note: HabitNote) { viewModelScope.launch { dao.deleteHabitNote(note) } }

    fun addHabitTask(habitId: Long, title: String) {
        val text = title.trim()
        if (habitId <= 0L || text.isEmpty()) return
        viewModelScope.launch { dao.addHabitTask(HabitTask(habitId = habitId, title = text)) }
    }

    fun toggleHabitTask(taskId: Long, completed: Boolean) { viewModelScope.launch { dao.setHabitTaskCompleted(taskId, completed) } }

    fun deleteHabitTask(task: HabitTask) { viewModelScope.launch { dao.deleteHabitTask(task) } }

    fun addGoal(title: String, targetAt: Long, direction: String, unit: String, roundUp: Boolean, color: Long, style: String) {
        viewModelScope.launch { dao.addGoal(Goal(title = title.trim(), targetAt = targetAt, direction = direction, unit = unit, roundUp = roundUp, color = color, style = style)) }
    }

    fun saveGoal(goal: Goal) { viewModelScope.launch { if (goal.id == 0L) dao.addGoal(goal) else dao.updateGoal(goal) } }
    fun updateGoal(goal: Goal) { viewModelScope.launch { dao.updateGoal(goal) } }
    fun toggleGoalPinned(goal: Goal) { viewModelScope.launch { dao.updateGoal(goal.copy(pinned = !goal.pinned)) } }
    fun deleteGoal(goal: Goal) { viewModelScope.launch { dao.deleteGoal(goal) } }
    fun completeGoal(id: Long, done: Boolean) { viewModelScope.launch { dao.setGoalCompleted(id, done) } }
    fun openNote(id: Long?) { _activeNoteId.value = id; noteEditor.open(id) }

    fun createNoteWithTemplate(title: String, markdown: String) {
        _activeNoteId.value = null
        val expanded = expandTemplate(markdown, title)
        noteEditor.openWithContent(title, expanded)
    }

    suspend fun openOrCreateNoteByTitle(title: String): Long = withContext(Dispatchers.IO) {
        val raw = title.substringBefore('#').trim().ifBlank { "Sem título" }
        val directMatch = dao.activeNotesByTitle(raw).firstOrNull()
        if (directMatch != null) return@withContext directMatch.id
        val headers = dao.allNoteLinkHeaders().filter { it.trashedAt == null }
        val existing = headers.firstOrNull { it.title.equals(raw, ignoreCase = true) }
            ?: headers.firstOrNull { h ->
                mayHaveFrontmatter(h.headMarkdown) &&
                    NoteFrontmatter.properties(h.headMarkdown).aliases.any { it.equals(raw, ignoreCase = true) }
            }
        if (existing != null) return@withContext existing.id
        dao.saveNote(Note(title = raw, markdown = "# $raw\n\n", updatedAt = System.currentTimeMillis()))
    }

    fun saveBook(book: Book, noteIds: Set<Long>, onSaved: (Long) -> Unit) {
        viewModelScope.launch {
            val id = dao.saveBookProgress(book, noteIds.toList())
            onSaved(id)
        }
    }

    fun deleteBook(book: Book) { viewModelScope.launch { dao.deleteBook(book) } }

    fun attachBookDocument(bookId: Long, uri: String, displayName: String, totalPagesHint: Int? = null) {
        if (bookId <= 0L || uri.isBlank()) return
        viewModelScope.launch {
            dao.attachBookDocument(bookId, uri, displayName.trim(), totalPagesHint?.takeIf { it > 0 }, System.currentTimeMillis())
        }
    }

    fun setBookStatus(bookId: Long, status: String) {
        if (bookId <= 0L || status.isBlank()) return
        viewModelScope.launch { dao.setBookStatus(bookId, status.trim(), System.currentTimeMillis()) }
    }

    /** Progresso vindo do leitor de documentos: avança a página (registrando ReadingLog), ajusta total de páginas e marca a última leitura. */
    fun recordBookReaderProgress(bookId: Long, page: Int, totalPagesHint: Int? = null, durationMinutes: Int = 0) {
        if (bookId <= 0L) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            var book = dao.bookById(bookId) ?: return@launch
            val hint = totalPagesHint?.takeIf { it > 0 }
            val knownTotal = book.totalPages ?: 0
            if (hint != null && (knownTotal <= 0 || knownTotal < page)) {
                val newTotal = maxOf(hint, page)
                dao.attachBookDocument(bookId, book.documentUri.orEmpty(), book.documentName.orEmpty(), newTotal, now)
                book = book.copy(totalPages = newTotal, updatedAt = now)
            }
            val safeDuration = durationMinutes.coerceAtLeast(0)
            val advanced = page > book.currentPage && dao.advanceBookPage(bookId, page, now, safeDuration)
            if (!advanced && safeDuration > 0) {
                dao.logReading(
                    ReadingLog(
                        title = book.title,
                        pages = 0,
                        loggedAt = now,
                        bookId = book.id,
                        subjectId = book.subjectId,
                        durationMinutes = safeDuration
                    )
                )
            }
            dao.touchBookReadAt(bookId, now)
        }
    }

    fun appendQuickCaptureNote(title: String, body: String) {
        val cleanTitle = title.trim().ifBlank { "Captura rápida" }
        val cleanBody = body.trim()
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                val noteId = openOrCreateNoteByTitle(cleanTitle)
                val existing = dao.findNote(noteId) ?: return@withContext null
                val merged = if (cleanBody.isEmpty()) existing.markdown else existing.markdown.trimEnd() + "\n\n" + cleanBody
                val saved = dao.updateNoteContentAndRebase(existing.id, existing.title, merged, System.currentTimeMillis())
                syncNoteWikilinks(existing.id, merged)
                saved
            }
            if (updated != null) {
                noteEditor.rebaseIfOpen(updated.id, updated.revision, updated.title, updated.markdown)
            }
        }
    }

    fun createQuickCaptureFlashcard(front: String, back: String) {
        val cleanFront = front.trim().ifBlank { "Captura rápida" }
        val cleanBack = back.trim().ifBlank { cleanFront }
        viewModelScope.launch(Dispatchers.IO) {
            val deckId = runCatching { dao.getOrCreateRootDeck("Captura rápida") }.getOrNull()
            insertCardRespectingCloze(
                front = cleanFront,
                back = cleanBack,
                deckId = deckId,
                subjectId = null,
                tags = "captura-rapida"
            )
        }
    }

    /**
     * Registra uma sessão de prática de matemática: [QuestionLog], [StudySession] (se durar >= 10 s)
     * e um [Mistake] por item de [mistakes] (question, reason, solution) quando [subjectId] != null.
     */
    fun createFlashcardsFromMathMistakes(
        subjectId: Long?,
        cards: List<Pair<String, String>>
    ) {
        if (cards.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val subjectName = subjectId?.let { id ->
                subjects.value.firstOrNull { it.id == id }?.name
            }?.trim()?.takeIf { it.isNotEmpty() }
            val deckName = subjectName?.let { "$it · Arena" } ?: "Arena de Matemática"
            val existingDeckId = runCatching { dao.getOrCreateRootDeck(deckName) }.getOrNull()
            StudyDatabase.get(appContext).withTransaction {
                cards.forEach { (front, back) ->
                    val cleanFront = front.trim()
                    val cleanBack = back.trim()
                    if (cleanFront.isNotEmpty() && cleanBack.isNotEmpty()) {
                        insertCardRespectingCloze(
                            front = cleanFront,
                            back = cleanBack,
                            deckId = existingDeckId,
                            subjectId = subjectId,
                            tags = "arena-exatas"
                        )
                    }
                }
            }
        }
    }

    fun logMathPracticeSession(
        subjectId: Long?,
        topic: String,
        questionCount: Int,
        correctCount: Int,
        durationSeconds: Long,
        mistakes: List<Triple<String, String, String>>
    ) {
        val cleanTopic = topic.trim().ifBlank { "Matemática" }
        viewModelScope.launch(Dispatchers.IO) {
            StudyDatabase.get(appContext).withTransaction {
            val now = System.currentTimeMillis()
            if (questionCount > 0) {
                dao.addQuestionLog(
                    QuestionLog(
                        subjectId = subjectId,
                        topic = cleanTopic,
                        questionCount = questionCount,
                        correctCount = correctCount.coerceIn(0, questionCount),
                        loggedAt = now
                    )
                )
            }
            if (durationSeconds >= 10L) {
                dao.addSession(
                    StudySession(
                        subjectId = subjectId,
                        startedAt = now - durationSeconds * 1000L,
                        endedAt = now,
                        durationSeconds = durationSeconds,
                        isActive = false,
                        topic = "Prática: $cleanTopic ($correctCount/$questionCount)",
                        mode = "PRACTICE"
                    )
                )
            }
            if (subjectId != null) {
                mistakes.forEach { (question, reason, solution) ->
                    if (question.isBlank()) return@forEach
                    dao.addMistake(
                        Mistake(
                            subjectId = subjectId,
                            topic = cleanTopic,
                            question = question.trim(),
                            reason = reason.trim(),
                            solution = solution.trim(),
                            createdAt = now,
                            nextReviewAt = now
                        )
                    )
                }
            }
            }
        }
    }

    fun createBookNote(bookId: Long, bookTitle: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val noteId = dao.saveNote(Note(title = "$bookTitle — leitura", markdown = "# $bookTitle\n\n## Resumo\n\n## Frases importantes\n", updatedAt = System.currentTimeMillis()))
            dao.addBookNoteLink(BookNoteLink(bookId, noteId))
            onCreated(noteId)
        }
    }

    fun cardFromBookQuote(book: Book, question: String, quote: String) {
        viewModelScope.launch {
            val deckName = "Leitura · ${book.title.take(32).trim()}"
            val deckId = runCatching { dao.getOrCreateRootDeck(deckName) }.getOrNull()
            insertCardRespectingCloze(
                front = question,
                back = quote,
                deckId = deckId,
                subjectId = book.subjectId,
                sourceExcerpt = "${book.title} (${book.author.ifBlank { "Leitura" }})"
            )
            android.widget.Toast.makeText(appContext, "Flashcard criado no baralho '$deckName'!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun noteFromBookQuote(book: Book, quote: String, summary: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val noteId = withContext(Dispatchers.IO) {
                val snippet = quote.take(34).replace('\n', ' ').trim()
                val title = "${book.title} — \"$snippet\""
                val folderClean = book.title.replace(INVALID_PATH_CHARS_REGEX, "-").trim()
                val markdown = buildString {
                    appendLine("---")
                    appendLine("folder: Livros/$folderClean")
                    appendLine("tags: [livro, leitura, destaque]")
                    appendLine("---")
                    appendLine()
                    appendLine("# $title")
                    appendLine()
                    appendLine("> $quote")
                    appendLine()
                    if (book.author.isNotBlank()) appendLine("- **Autor:** ${book.author}")
                    appendLine("- **Página:** ${book.currentPage}${book.totalPages?.let { "/$it" } ?: ""}")
                    if (summary.isNotBlank()) {
                        appendLine()
                        appendLine("## Notas & Contexto")
                        appendLine(summary)
                    }
                    appendLine()
                    appendLine("#livro #leitura")
                }
                val createdId = dao.saveNote(Note(title = title, markdown = markdown, updatedAt = System.currentTimeMillis()))
                syncNoteWikilinks(createdId, markdown)
                if (book.id != 0L) dao.addBookNoteLink(BookNoteLink(book.id, createdId))
                createdId
            }
            onCreated(noteId)
        }
    }

    fun toggleNotePin(note: Note) { viewModelScope.launch { dao.setNotePinned(note.id, !note.pinned) } }

    fun openDailyNote(onReady: (Long) -> Unit) {
        viewModelScope.launch {
            val today = LocalDate.now()
            val title = today.toString()
            val prevMarkdown = withContext(Dispatchers.IO) {
                val prevDate = today.minusDays(1)
                val prevId = dao.noteIdByTitle(prevDate.toString())
                    ?: dao.noteIdByTitle(PeriodicNotesEngine.dailyNoteTitle(prevDate))
                prevId?.let { dao.findNote(it)?.markdown }
            }
            val (_, rolloverBody) = PeriodicNotesEngine.buildDailyNoteWithRollover(today, prevMarkdown)
            val id = dao.getOrCreateDailyNote(title, "$rolloverBody\n#diario")
            onReady(id)
        }
    }

    fun promoteDiaryToNote(entry: JournalEntry, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            val (noteId, noteTitle, updated) = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val date = DiaryAnalytics.localDateFromDayKey(entry.dayKey)
                val dateIso = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
                val existing = entry.linkedNoteId?.let { id -> dao.findNote(id)?.takeIf { it.trashedAt == null } }
                    ?: dao.activeNotesByTitle(dateIso).firstOrNull()
                val tags = (listOf("diario") + DiaryAnalytics.parseTags(entry.tags)).distinct()
                val moodLabel = DiaryAnalytics.moodPresets.toMap()[entry.mood] ?: entry.mood
                val currentSessions = dao.sessions().first()
                val currentTasks = dao.tasks().first()
                val currentReviews = dao.cardReviews().first()
                val currentQuestions = dao.questionLogs().first()
                val currentHabitLogs = dao.allHabitCompletions().first()
                val currentReading = dao.readingLogs().first()
                val currentWorkouts = dao.workoutLogs().first()
                val currentMeals = dao.mealLogs().first()
                val currentSubjects = dao.subjects().first()
                val summary = DiaryAnalytics.summarizeDay(
                    dayKey = entry.dayKey,
                    sessions = currentSessions,
                    tasks = currentTasks,
                    cardReviews = currentReviews,
                    questionLogs = currentQuestions,
                    habitCompletions = currentHabitLogs,
                    readingLogs = currentReading,
                    workouts = currentWorkouts,
                    meals = currentMeals,
                    subjects = currentSubjects
                )
                val summaryMd = DiaryAnalytics.formatSummaryMarkdown(summary)
                val checkInSection = buildString {
                    appendLine("## Check-in do Diário")
                    if (entry.title.isNotBlank()) appendLine("### ${entry.title.trim()}")
                    appendLine("- **Humor:** ${entry.mood.ifBlank { "🙂" }} ($moodLabel)")
                    appendLine("- **Energia:** ${entry.energy.coerceIn(1, 5)}/5  •  **Foco:** ${entry.focus.coerceIn(1, 5)}/5")
                    if (entry.promptUsed.isNotBlank()) appendLine("- **Reflexão:** ${entry.promptUsed.trim()}")
                    appendLine()
                    appendLine(entry.body.trim().ifBlank { "_Sem texto registrado neste check-in._" })
                    if (summaryMd.isNotBlank()) {
                        appendLine()
                        appendLine(summaryMd)
                    }
                    appendLine()
                    appendLine(tags.joinToString(" ") { "#$it" })
                }.trimEnd()
                val title = existing?.title?.takeIf { it.isNotBlank() } ?: dateIso
                val fullDiaryMarkdown = buildString {
                    appendLine("---")
                    appendLine("folder: Diário")
                    appendLine("tags: [${tags.joinToString(", ")}]")
                    appendLine("---")
                    appendLine()
                    appendLine("# $dateIso")
                    appendLine()
                    append(checkInSection)
                }
                val finalMarkdown: String
                val savedId: Long
                var updatedNote: Note? = null
                if (existing == null) {
                    finalMarkdown = fullDiaryMarkdown
                    savedId = dao.saveNote(Note(title = title, markdown = finalMarkdown, updatedAt = now))
                    syncNoteWikilinks(savedId, finalMarkdown)
                } else {
                    finalMarkdown = when {
                        existing.markdown.trimStart().startsWith("---\nfolder: Diário") -> fullDiaryMarkdown
                        existing.markdown.contains("## Check-in do Diário") ->
                            existing.markdown.substringBefore("## Check-in do Diário").trimEnd() + "\n\n" + checkInSection
                        else -> existing.markdown.trimEnd() + "\n\n" + checkInSection
                    }
                    updatedNote = dao.updateNoteContentAndRebase(existing.id, title, finalMarkdown, now)
                    savedId = updatedNote?.id ?: existing.id
                    syncNoteWikilinks(savedId, finalMarkdown)
                }
                dao.saveJournalEntry(entry.copy(linkedNoteId = savedId, updatedAt = now))
                Triple(savedId, title, updatedNote)
            }
            if (updated != null) {
                noteEditor.rebaseIfOpen(updated.id, updated.revision, updated.title, updated.markdown)
            }
            android.widget.Toast.makeText(
                appContext,
                "Diário vinculado à nota '$noteTitle'!",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            onReady(noteId)
        }
    }

    private suspend fun findMatchingSubjectId(subjectName: String?): Long? {
        val target = subjectName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val list = subjects.value.ifEmpty { dao.subjects().first() }
        return list.firstOrNull { !it.archived && it.name.equals(target, ignoreCase = true) }?.id
            ?: list.firstOrNull {
                !it.archived && (it.name.contains(target, ignoreCase = true) || target.contains(it.name, ignoreCase = true))
            }?.id
            ?: list.firstOrNull { it.name.equals(target, ignoreCase = true) }?.id
    }

    private suspend fun syncNoteWikilinks(noteId: Long, markdown: String) = withContext(Dispatchers.IO) {
        val headers = dao.allNoteLinkHeaders().filter { it.trashedAt == null }
        val known = dao.linksFromNote(noteId).associate { it.reference to it.targetId }
        val linksMap = NoteReferences.boundReferencesFromHeaders(markdown, headers, known)
            .filterValues { it != noteId }
        dao.replaceNoteLinks(noteId, linksMap)
    }

    fun createFromAiFlashcard(obj: GeneratedStudyObject.FlashcardObj) {
        viewModelScope.launch {
            val deckId = runCatching {
                withContext(Dispatchers.IO) { dao.getOrCreateRootDeck(obj.deckName.take(40).ifBlank { "Tutor IA" }) }
            }.getOrNull()
            val subjectId = findMatchingSubjectId(obj.subjectName)
            insertCardRespectingCloze(
                front = obj.front,
                back = obj.back,
                deckId = deckId,
                subjectId = subjectId
            )
            android.widget.Toast.makeText(appContext, "Flashcard salvo no baralho '${obj.deckName}'!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun insertAiNote(obj: GeneratedStudyObject.NoteObj): Long {
        val noteId = withContext(Dispatchers.IO) {
            val cleanFolder = obj.folder.replace(INVALID_FOLDER_CHARS_REGEX, "-").trim().ifBlank { "Tutor IA" }
            val tagsCsv = obj.tags.joinToString(", ")
            val fullMarkdown = if (obj.markdown.trimStart().startsWith("---")) {
                obj.markdown
            } else {
                buildString {
                    appendLine("---")
                    appendLine("folder: $cleanFolder")
                    if (tagsCsv.isNotBlank()) appendLine("tags: [$tagsCsv]")
                    appendLine("---")
                    appendLine()
                    appendLine(obj.markdown)
                    if (obj.tags.isNotEmpty() && !obj.markdown.contains("#")) {
                        appendLine()
                        appendLine(obj.tags.joinToString(" ") { "#$it" })
                    }
                }
            }
            val savedId = dao.saveNote(Note(title = obj.title.trim(), markdown = fullMarkdown, updatedAt = System.currentTimeMillis()))
            syncNoteWikilinks(savedId, fullMarkdown)
            savedId
        }
        android.widget.Toast.makeText(appContext, "Nota '${obj.title}' salva no Segundo Cérebro!", android.widget.Toast.LENGTH_SHORT).show()
        return noteId
    }

    private suspend fun insertAiCanvas(obj: GeneratedStudyObject.CanvasObj): Long {
        val boardId = withContext(Dispatchers.IO) {
            val activeHeaders = dao.allNoteLinkHeaders().filter { it.trashedAt == null }
            val noteIdsByTitle = activeHeaders.associate { it.title.trim().lowercase(Locale.ROOT) to it.id }
            val jsonData = AssistantProxyClient.buildCanvasBoardJson(obj, noteIdsByTitle)
            dao.upsertCanvasBoard(
                CanvasBoard(
                    title = obj.title.trim().ifBlank { "Canvas do Tutor IA" },
                    jsonData = jsonData
                )
            )
        }
        android.widget.Toast.makeText(appContext, "Canvas '${obj.title}' salvo!", android.widget.Toast.LENGTH_SHORT).show()
        return boardId
    }

    fun createFromAiNote(obj: GeneratedStudyObject.NoteObj, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            onCreated(insertAiNote(obj))
        }
    }

    fun createFromAiTask(obj: GeneratedStudyObject.TaskObj) {
        viewModelScope.launch {
            val subjectId = findMatchingSubjectId(obj.subjectName)
            val dueAt = System.currentTimeMillis() + obj.dueInDays.coerceAtLeast(0) * 86_400_000L
            dao.addTask(
                StudyTask(
                    subjectId = subjectId,
                    title = obj.title.trim(),
                    dueAt = dueAt,
                    priority = obj.priority.coerceIn(1, 4),
                    details = obj.details.trim()
                )
            )
            android.widget.Toast.makeText(appContext, "Tarefa '${obj.title}' adicionada!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun createFromAiGoal(obj: GeneratedStudyObject.GoalObj) {
        viewModelScope.launch {
            val targetAt = System.currentTimeMillis() + obj.targetInDays.coerceAtLeast(1) * 86_400_000L
            val parsedMetric = GoalMetricType.fromId(obj.metricType)
            val targetValue = obj.targetValue.coerceAtLeast(0.0)
            val resolvedMetric = if (parsedMetric != GoalMetricType.COUNTDOWN && targetValue <= 0.0) {
                GoalMetricType.COUNTDOWN
            } else {
                parsedMetric
            }
            val metricUnit = obj.metricUnit.trim().ifBlank { resolvedMetric.defaultUnit }
            val matchedSubjectId = findMatchingSubjectId(obj.subjectName)
            dao.addGoal(
                Goal(
                    title = obj.title.trim(),
                    targetAt = targetAt,
                    direction = "COUNTDOWN",
                    unit = "DAYS",
                    roundUp = true,
                    color = 0xFF6156D9L,
                    style = if (resolvedMetric == GoalMetricType.COUNTDOWN) "RING" else "BAR",
                    metricType = resolvedMetric.id,
                    targetValue = targetValue,
                    metricUnit = metricUnit,
                    subjectId = matchedSubjectId
                )
            )
            android.widget.Toast.makeText(appContext, "Meta '${obj.title}' criada!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val _tutorState = MutableStateFlow(TutorUiState())
    val tutorState = _tutorState.asStateFlow()
    fun setTutorMode(mode: StudyTutorMode) { _tutorState.update { it.copy(tutorMode = mode) } }
    fun setFeynmanLevel(level: Int) { _tutorState.update { it.copy(feynmanLevel = level.coerceIn(1, 5)) } }
    fun setTutorFocusTopic(topic: String) { _tutorState.update { it.copy(focusTopic = topic) } }
    fun setTutorDraft(draft: String) { _tutorState.update { it.copy(draft = draft) } }

    fun clearTutorConversation() {
        _tutorState.update {
            if (it.busy) it
            else it.copy(
                messages = emptyList(),
                appliedActions = emptyMap(),
                savedNoteIds = emptyMap(),
                savedCanvasIds = emptyMap(),
                feynmanLevel = 1
            )
        }
    }

    fun sendTutorPrompt(
        provider: AiProviderMode,
        apiKeyOrToken: String,
        serviceUrl: String,
        modelName: String,
        userPrompt: String,
        studyContext: String
    ) {
        val cleanPrompt = userPrompt.trim()
        val snapshot = _tutorState.value
        if (cleanPrompt.isEmpty() || snapshot.busy) return
        val historySnapshot = snapshot.messages.map {
            ChatTurn(if (it.fromUser) "user" else "assistant", it.text)
        }
        _tutorState.update {
            it.copy(
                draft = "",
                busy = true,
                messages = it.messages + AssistantMessage(text = cleanPrompt, fromUser = true)
            )
        }
        viewModelScope.launch {
            val rawReply = try {
                val enrichedQuestion = if (snapshot.tutorMode == com.studydock.app.data.StudyTutorMode.STUDENT_FEYNMAN) {
                    val detectedJargon = com.studydock.app.data.FeynmanSocraticEngine.detectJargonWords(cleanPrompt)
                    if (detectedJargon.isNotEmpty()) "$cleanPrompt\n[Radar de Jargão: ${detectedJargon.joinToString(", ")}]" else cleanPrompt
                } else cleanPrompt
                AssistantProxyClient.askTutor(
                    provider = provider,
                    apiKeyOrToken = apiKeyOrToken,
                    serviceUrl = serviceUrl,
                    modelName = modelName,
                    mode = snapshot.tutorMode,
                    feynmanLevel = snapshot.feynmanLevel,
                    focusTopic = snapshot.focusTopic,
                    history = historySnapshot,
                    question = enrichedQuestion,
                    studyContext = studyContext
                )
            } catch (e: CancellationException) {
                _tutorState.update { it.copy(busy = false) }
                throw e
            } catch (e: Exception) {
                StudyLog.w("StudyViewModel", "Falha na chamada ao Tutor IA (${provider.label})", e)
                "Não consegui falar com a IA (${provider.label}): ${e.message ?: "verifique sua chave ou conexão"}"
            }
            val parsed = withContext(Dispatchers.Default) {
                runCatching { AssistantProxyClient.parseReplyActions(rawReply) }
                    .getOrElse { ParsedAssistantReply(cleanText = rawReply, actions = emptyList(), suggestedFeynmanLevel = null) }
            }
            _tutorState.update { current ->
                current.copy(
                    busy = false,
                    feynmanLevel = parsed.suggestedFeynmanLevel?.coerceIn(1, 5) ?: current.feynmanLevel,
                    messages = current.messages + AssistantMessage(
                        text = parsed.cleanText,
                        fromUser = false,
                        actions = parsed.actions,
                        modeBadge = snapshot.tutorMode.title
                    )
                )
            }
        }
    }

    fun applyTutorAction(
        actionKey: String,
        obj: GeneratedStudyObject,
        openNoteAfter: Boolean,
        onOpenNote: (Long) -> Unit,
        onOpenCanvas: (Long) -> Unit = {}
    ) {
        var shouldApply = false
        _tutorState.update { current ->
            if (current.appliedActions[actionKey] == true) {
                current
            } else {
                shouldApply = true
                current.copy(appliedActions = current.appliedActions + (actionKey to true))
            }
        }
        if (!shouldApply) {
            if (openNoteAfter) {
                when (obj) {
                    is GeneratedStudyObject.NoteObj -> _tutorState.value.savedNoteIds[actionKey]?.let(onOpenNote)
                    is GeneratedStudyObject.CanvasObj -> _tutorState.value.savedCanvasIds[actionKey]?.let(onOpenCanvas)
                    else -> Unit
                }
            }
            return
        }
        when (obj) {
            is GeneratedStudyObject.FlashcardObj -> createFromAiFlashcard(obj)
            is GeneratedStudyObject.NoteObj -> viewModelScope.launch {
                try {
                    val noteId = insertAiNote(obj)
                    _tutorState.update { current ->
                        current.copy(savedNoteIds = current.savedNoteIds + (actionKey to noteId))
                    }
                    if (openNoteAfter) onOpenNote(noteId)
                } catch (e: CancellationException) {
                    _tutorState.update { it.copy(appliedActions = it.appliedActions - actionKey) }
                    throw e
                } catch (e: Exception) {
                    StudyLog.w("StudyViewModel", "Falha ao salvar nota gerada pelo Tutor IA", e)
                    _tutorState.update { it.copy(appliedActions = it.appliedActions - actionKey) }
                    android.widget.Toast.makeText(appContext, "Não foi possível salvar a nota.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            is GeneratedStudyObject.CanvasObj -> viewModelScope.launch {
                try {
                    val boardId = insertAiCanvas(obj)
                    _tutorState.update { current ->
                        current.copy(savedCanvasIds = current.savedCanvasIds + (actionKey to boardId))
                    }
                    if (openNoteAfter) onOpenCanvas(boardId)
                } catch (e: CancellationException) {
                    _tutorState.update { it.copy(appliedActions = it.appliedActions - actionKey) }
                    throw e
                } catch (e: Exception) {
                    StudyLog.w("StudyViewModel", "Falha ao salvar canvas gerado pelo Tutor IA", e)
                    _tutorState.update { it.copy(appliedActions = it.appliedActions - actionKey) }
                    android.widget.Toast.makeText(appContext, "Não foi possível salvar o Canvas.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            is GeneratedStudyObject.TaskObj -> createFromAiTask(obj)
            is GeneratedStudyObject.GoalObj -> createFromAiGoal(obj)
        }
    }

    private data class ReviewUndoRecord(
        val priorCard: Flashcard,
        val priorEntry: RoundEntry,
        val reviewReviewedAt: Long,
        val buriedSiblingsBefore: List<Pair<Long, Long>>,
        val removedSiblingEntries: List<RoundEntry>
    )

    private val ankiRoundPrefs by lazy {
        appContext.getSharedPreferences(ANKI_ROUND_PREFS, Context.MODE_PRIVATE)
    }

    private var currentRoundStartedAt: Long
        get() = ankiRoundPrefs.getLong(KEY_ROUND_STARTED_AT, 0L)
        set(value) {
            ankiRoundPrefs.edit().putLong(KEY_ROUND_STARTED_AT, value).apply()
        }

    private val reviewMutex = Mutex()
    private val undoReviewStack = ArrayDeque<ReviewUndoRecord>()
    private val _canUndoReview = MutableStateFlow(false)
    val canUndoReview: StateFlow<Boolean> = _canUndoReview.asStateFlow()

    val reviewRound: StateFlow<List<RoundCard>> =
        activeDaoFlow.flatMapLatest { it.reviewRound() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, emptyList())

    val hasOpenScheduledRound: StateFlow<Boolean> =
        activeDaoFlow.flatMapLatest { it.observeHasOpenScheduledRound() }.stateIn(viewModelScope, FLOW_SHARING_POLICY, false)

    private data class DeckStudyInputs(
        val decks: List<CardDeck>,
        val cards: List<Flashcard>,
        val reviews: List<CardReview>,
        val today: LocalDate
    )

    val queueClockMillis: StateFlow<Long> = sixtySecondClockFlow
        .stateIn(viewModelScope, FLOW_SHARING_POLICY, System.currentTimeMillis())

    val deckStudyIndex: StateFlow<DeckStudyIndex?> = combine(
        cardDecks,
        flashcards,
        cardReviews,
        currentDayFlow
    ) { decks, cards, reviews, today ->
        DeckStudyInputs(decks, cards, reviews, today)
    }.mapLatest { snapshot ->
        withContext(Dispatchers.Default) {
            DeckStudyIndex(snapshot.decks, snapshot.cards, snapshot.reviews, snapshot.today, ZoneId.systemDefault())
        }
    }.stateIn(viewModelScope, FLOW_SHARING_POLICY, null)

    suspend fun startRound(cards: List<Flashcard>, cram: Boolean = false) = withContext(Dispatchers.IO) {
        if (cram) {
            val activeIds = cards.asSequence().filterNot { it.suspended }.map { it.id }.distinct().toList()
            check(activeIds.isNotEmpty()) { "Nenhum cartão ativo disponível para revisar." }
            reviewMutex.withLock {
                if (dao.hasOpenRound()) finishRoundLocked()
                undoReviewStack.clear()
                _canUndoReview.value = false
                dao.startCramRound(activeIds)
                currentRoundStartedAt = System.currentTimeMillis()
            }
        } else {
            reviewMutex.withLock {
                if (!dao.hasOpenRound()) {
                    undoReviewStack.clear()
                    _canUndoReview.value = false
                    dao.startRound(cards.map { it.id })
                    currentRoundStartedAt = System.currentTimeMillis()
                } else if (currentRoundStartedAt == 0L) {
                    currentRoundStartedAt = System.currentTimeMillis()
                }
            }
        }
    }

    suspend fun finishRound() = withContext(Dispatchers.IO) {
        reviewMutex.withLock {
            finishRoundLocked()
        }
    }

    private suspend fun finishRoundLocked() {
        val startedAt = currentRoundStartedAt
        try {
            val currentRound = dao.reviewRound().firstOrNull().orEmpty()
            val doneCards = currentRound.filter { it.entry.done }
            if (doneCards.isNotEmpty() && studySessionManager.snapshot.value.state == TimerState.IDLE) {
                var totalMs = 0L
                if (startedAt > 0L) {
                    val reviewsSinceStart = dao.recentCardReviews(startedAt).first()
                    val byCard = reviewsSinceStart
                        .filter { it.reviewedAt >= startedAt }
                        .groupBy { it.cardId }
                    for (rc in doneCards) {
                        val realMs = byCard[rc.card.id].orEmpty()
                            .sumOf { (it.durationMs ?: 0L).coerceAtLeast(0L) }
                        totalMs += if (realMs > 0L) realMs else rc.entry.elapsedMs.coerceAtLeast(0L)
                    }
                } else {
                    // Process restarted before start timestamp was persisted: never aggregate historical reviews.
                    totalMs = doneCards.sumOf { it.entry.elapsedMs.coerceAtLeast(0L) }
                }
                val durationSecs = totalMs / 1000L
                val currentSubjects = subjects.value.ifEmpty { dao.subjects().first() }
                val dominantSubjectId = doneCards.mapNotNull { it.card.subjectId }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }?.key
                    ?: currentSubjects.firstOrNull()?.id
                if (dominantSubjectId != null && durationSecs > 0L) {
                    val now = System.currentTimeMillis()
                    dao.addSession(
                        StudySession(
                            subjectId = dominantSubjectId,
                            startedAt = (now - durationSecs * 1000L).coerceAtLeast(startedAt),
                            endedAt = now,
                            durationSeconds = durationSecs,
                            isActive = false,
                            topic = "Revisão Anki (${doneCards.size} cartões)",
                            mode = "ANKI"
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StudyLog.w("StudyViewModel", "Falha ao registrar sessão da rodada Anki", e)
        } finally {
            undoReviewStack.clear()
            _canUndoReview.value = false
            currentRoundStartedAt = 0L
            dao.clearReviewRound()
        }
    }

    fun saveAnswerTime(row: RoundCard, elapsed: Long) {
        viewModelScope.launch(Dispatchers.IO) { dao.addRoundTime(row.card.id, row.entry.token, row.card.dueAt, elapsed) }
    }

    fun reviewFlashcard(row: RoundCard, grade: Int, elapsed: Long, allowEarly: Boolean = false, result: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val graded = withContext(Dispatchers.IO) {
                    reviewMutex.withLock {
                        if (currentRoundStartedAt == 0L) {
                            currentRoundStartedAt = System.currentTimeMillis()
                        }
                        val outcomeResult = dao.gradeCardPro(row.card, row.entry.token, grade, System.currentTimeMillis(), elapsed, allowEarly)
                        if (outcomeResult != null) {
                            undoReviewStack.addLast(
                                ReviewUndoRecord(
                                    priorCard = outcomeResult.priorCard,
                                    priorEntry = outcomeResult.priorEntry,
                                    reviewReviewedAt = outcomeResult.outcome.review.reviewedAt,
                                    buriedSiblingsBefore = outcomeResult.buriedSiblingsBefore,
                                    removedSiblingEntries = outcomeResult.removedSiblingEntries
                                )
                            )
                            while (undoReviewStack.size > 30) undoReviewStack.removeFirst()
                            _canUndoReview.value = undoReviewStack.isNotEmpty()
                        }
                        outcomeResult
                    }
                }
                if (graded == null) {
                    result("Este cartão mudou. Confira a rodada atualizada.")
                    return@launch
                }
                if (graded.outcome.isLeechNow) {
                    val msg = if (graded.outcome.card.suspended) {
                        "Sanguessuga detectada: cartão suspenso automaticamente."
                    } else {
                        "Cartão marcado como sanguessuga."
                    }
                    android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                result(null)
                withContext(Dispatchers.IO) { ReviewWidget.refresh(appContext) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StudyLog.w("StudyViewModel", "Erro ao registrar revisão de flashcard", e)
                result("Falha ao registrar a resposta. Tente novamente.")
            }
        }
    }

    suspend fun undoLastReview(): Boolean = withContext(Dispatchers.IO) {
        val undone = reviewMutex.withLock {
            val record = undoReviewStack.removeLastOrNull()
            _canUndoReview.value = undoReviewStack.isNotEmpty()
            if (record == null) {
                false
            } else {
                dao.revertLastReview(
                    priorCard = record.priorCard,
                    priorEntry = record.priorEntry,
                    reviewReviewedAt = record.reviewReviewedAt,
                    buriedSiblingsBefore = record.buriedSiblingsBefore,
                    removedSiblingEntries = record.removedSiblingEntries
                )
                true
            }
        }
        if (undone) {
            ReviewWidget.refresh(appContext)
        }
        undone
    }

    suspend fun buryCard(cardId: Long) = withContext(Dispatchers.IO) {
        reviewMutex.withLock {
            val card = dao.findCard(cardId) ?: return@withLock
            val deck = card.deckId?.let { dao.deckById(it) }
            val untilDay = ankiLogicalDay(System.currentTimeMillis(), deck?.dayStartHour ?: 4).toEpochDay()
            dao.buryCardAndDiscardRound(cardId, untilDay)
            undoReviewStack.removeAll { it.priorCard.id == cardId }
            _canUndoReview.value = undoReviewStack.isNotEmpty()
        }
        ReviewWidget.refresh(appContext)
    }

    suspend fun setCardFlag(cardId: Long, flag: Int) = withContext(Dispatchers.IO) {
        dao.setCardFlag(cardId, flag.coerceIn(0, 7))
    }

    suspend fun createFilteredDeck(name: String, query: String, limit: Int): Long {
        val trimmedName = name.trim()
        val trimmedQuery = query.trim()
        require(trimmedName.isNotEmpty()) { "Informe o nome do baralho filtrado." }
        require(trimmedQuery.isNotEmpty()) { "Informe a consulta do filtro." }
        val safeLimit = limit.coerceAtLeast(1)
        return withContext(Dispatchers.IO) {
            val allCards = dao.listFlashcardsForQueryValidation()
            val allDecks = dao.listDecks()
            val matched = withContext(Dispatchers.Default) {
                CardQueryEngine.filter(
                    cards = allCards,
                    query = trimmedQuery,
                    decks = allDecks,
                    now = System.currentTimeMillis(),
                    zone = ZoneId.systemDefault()
                )
            }
            require(matched.isNotEmpty()) { "Nenhum cartão corresponde ao filtro informado." }
            dao.saveDeckSettings(
                CardDeck(
                    name = trimmedName,
                    isFiltered = true,
                    filterQuery = trimmedQuery,
                    newLimit = safeLimit,
                    reviewLimit = safeLimit
                )
            )
        }
    }

    suspend fun saveQuickCardEdit(card: Flashcard, front: String, back: String, tags: String) = withContext(Dispatchers.IO) {
        reviewMutex.withLock {
            dao.saveQuickCardEdit(card, front, back, tags)
            val cleanTags = normalizeCardTags(tags)
            for (i in undoReviewStack.indices) {
                val rec = undoReviewStack[i]
                if (rec.priorCard.id == card.id) {
                    undoReviewStack[i] = rec.copy(
                        priorCard = rec.priorCard.copy(
                            front = front.trim(),
                            back = back.trim(),
                            tags = cleanTags
                        )
                    )
                }
            }
        }
    }

    suspend fun moveCardsToDeck(ids: List<Long>, deckId: Long?) = withContext(Dispatchers.IO) {
        dao.moveCardsToDeck(ids, deckId)
    }

    suspend fun suspendCards(ids: List<Long>, suspended: Boolean) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        reviewMutex.withLock {
            dao.setCardsSuspended(ids, suspended)
            if (suspended) {
                val idSet = ids.toHashSet()
                undoReviewStack.removeAll { it.priorCard.id in idSet }
                _canUndoReview.value = undoReviewStack.isNotEmpty()
            }
        }
        ReviewWidget.refresh(appContext)
    }

    suspend fun addTagToCards(ids: List<Long>, tag: String) = withContext(Dispatchers.IO) {
        dao.updateCardsTag(ids, tag, add = true)
    }

    suspend fun removeTagFromCards(ids: List<Long>, tag: String) = withContext(Dispatchers.IO) {
        dao.updateCardsTag(ids, tag, add = false)
    }

    suspend fun setCardFlags(ids: List<Long>, flag: Int) = withContext(Dispatchers.IO) {
        dao.setCardsFlag(ids, flag)
    }

    suspend fun rescheduleCards(ids: List<Long>, daysFromNow: Int) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val days = daysFromNow.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val cards = dao.findCardsByIds(ids)
        val decksById = dao.listDecks().associateBy { it.id }
        val groupedByHour = cards.groupBy { card ->
            (card.deckId?.let { decksById[it]?.dayStartHour } ?: 4).coerceIn(0, 23)
        }
        reviewMutex.withLock {
            if (groupedByHour.isEmpty()) {
                val dueAt = if (days == 0) now else nextDayDueMillis(now, days, 4, zone)
                dao.rescheduleCardsDue(ids, dueAt, days)
            } else {
                for ((hour, group) in groupedByHour) {
                    val dueAt = if (days == 0) now else nextDayDueMillis(now, days, hour, zone)
                    dao.rescheduleCardsDue(group.map { it.id }, dueAt, days)
                }
            }
            val idSet = ids.toHashSet()
            undoReviewStack.removeAll { it.priorCard.id in idSet }
            _canUndoReview.value = undoReviewStack.isNotEmpty()
        }
    }

    suspend fun resetCards(ids: List<Long>) = withContext(Dispatchers.IO) {
        reviewMutex.withLock {
            dao.resetCardsProgress(ids)
            val idSet = ids.toHashSet()
            undoReviewStack.removeAll { it.priorCard.id in idSet }
            _canUndoReview.value = undoReviewStack.isNotEmpty()
        }
    }

    suspend fun deleteCards(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        reviewMutex.withLock {
            dao.deleteCardsByIds(ids)
            val idSet = ids.toHashSet()
            undoReviewStack.removeAll { it.priorCard.id in idSet }
            _canUndoReview.value = undoReviewStack.isNotEmpty()
        }
        ReviewWidget.refresh(appContext)
    }

    suspend fun importCsvDeck(name: String, content: String): Long {
        val file = withContext(Dispatchers.Default) {
            CsvDeckCodec.importCsv(StringReader(content), name)
        }
        return withContext(Dispatchers.IO) { dao.importDeckFile(file) }
    }

    suspend fun exportDeckCsv(deckId: Long, name: String): String = withContext(Dispatchers.IO) {
        val cards = dao.cardsInDeckScope(deckId)
        CsvDeckCodec.exportCsv(cards)
    }

    suspend fun importApkgUri(uri: Uri): Pair<Long, List<String>> = importMutex.withLock {
        withContext(Dispatchers.IO) {
        val workDir = File(appContext.cacheDir, "apkg_${System.nanoTime()}").apply { mkdirs() }
        try {
            val result = appContext.contentResolver.openInputStream(uri)?.use { input ->
                ApkgPackageCodec.readApkg(input, workDir, keepMediaFilesOnDisk = true) { sqliteFile ->
                    readApkgSqliteTables(sqliteFile)
                }
            } ?: throw IllegalArgumentException("Não foi possível abrir o arquivo .apkg.")

            val warnings = result.warnings.toMutableList()
            val preparedKeyByHash = mutableMapOf<String, String?>()
            val preparedKeyByPath = mutableMapOf<String, String?>()
            val webpFilesByKey = mutableMapOf<String, File>()

            fun cachePreparedBytes(rawBytes: ByteArray): String? {
                if (rawBytes.isEmpty()) return null
                val rawHash = CardPictures.key(rawBytes)
                if (preparedKeyByHash.containsKey(rawHash)) return preparedKeyByHash[rawHash]
                val img = prepareImportedCardImage(rawBytes)
                if (img == null) {
                    preparedKeyByHash[rawHash] = null
                    return null
                }
                if (!webpFilesByKey.containsKey(img.key)) {
                    val outFile = File(workDir, "webp_${img.key}.bin")
                    outFile.writeBytes(img.bytes)
                    webpFilesByKey[img.key] = outFile
                }
                preparedKeyByHash[rawHash] = img.key
                preparedKeyByHash[img.key] = img.key
                return img.key
            }

            val preparedDecks = ArrayList<Triple<String, Set<String>, List<Pair<CardAuthorNote?, Flashcard>>>>(result.decks.size)
            for (deck in result.decks) {
                val deckImageKeys = linkedSetOf<String>()
                val pairs = ArrayList<Pair<CardAuthorNote?, Flashcard>>(deck.cards.size)
                for (imported in deck.cards) {
                    val cleanTags = normalizeCardTags(imported.tags)
                    var imageKey: String? = null
                    val fileRef = imported.imageFile
                    if (fileRef != null) {
                        val pathKey = fileRef.absolutePath
                        if (preparedKeyByPath.containsKey(pathKey)) {
                            val cachedKey = preparedKeyByPath[pathKey]
                            if (cachedKey != null) {
                                imageKey = cachedKey
                                deckImageKeys.add(cachedKey)
                            } else {
                                warnings.add("Imagem ignorada (${imported.imageName ?: "mídia"}): formato ou tamanho não suportado.")
                            }
                        } else {
                            val rawBytes = runCatching { fileRef.readBytes() }.getOrNull()
                            val resolvedKey = if (rawBytes != null) cachePreparedBytes(rawBytes) else null
                            preparedKeyByPath[pathKey] = resolvedKey
                            if (resolvedKey != null) {
                                imageKey = resolvedKey
                                deckImageKeys.add(resolvedKey)
                            } else {
                                warnings.add("Imagem ignorada (${imported.imageName ?: "mídia"}): formato ou tamanho não suportado.")
                            }
                        }
                    } else {
                        val rawBytes = imported.imageBytes
                        if (rawBytes != null) {
                            val resolvedKey = cachePreparedBytes(rawBytes)
                            if (resolvedKey != null) {
                                imageKey = resolvedKey
                                deckImageKeys.add(resolvedKey)
                            } else {
                                warnings.add("Imagem ignorada (${imported.imageName ?: "mídia"}): formato ou tamanho não suportado.")
                            }
                        }
                    }

                    val hasOcclusion = imported.occlusion.isNotBlank()
                    val needsAuthor = imageKey != null || hasOcclusion || imported.typeAnswer
                    val author = if (needsAuthor) {
                        CardAuthorNote(
                            format = if (hasOcclusion) "occlusion" else "BASIC",
                            prompt = imported.front,
                            answer = imported.back,
                            imageKey = imageKey,
                            occlusion = imported.occlusion.takeIf { it.isNotBlank() },
                            tags = cleanTags,
                            typeAnswer = imported.typeAnswer
                        )
                    } else {
                        null
                    }
                    val card = Flashcard(
                        front = imported.front,
                        back = imported.back,
                        dueAt = imported.dueAt,
                        intervalDays = imported.intervalDays.coerceAtLeast(0),
                        easeFactor = imported.easeFactor.coerceAtLeast(1.3),
                        repetitions = imported.repetitions.coerceAtLeast(0),
                        learningState = imported.learningState,
                        lapses = imported.lapses.coerceAtLeast(0),
                        tags = cleanTags,
                        flag = imported.flag.coerceIn(0, 7),
                        noteTypeKey = imported.noteTypeKey,
                        variantKey = when {
                            hasOcclusion -> "c1"
                            needsAuthor -> "front"
                            else -> null
                        }
                    )
                    pairs.add(author to card)
                }
                preparedDecks.add(Triple(deck.name, deckImageKeys, pairs))
            }
            var firstDeckId = 0L
            StudyDatabase.get(appContext).withTransaction {
                for ((deckName, deckImageKeys, pairs) in preparedDecks) {
                    val deckImages = deckImageKeys.mapNotNull { key ->
                        webpFilesByKey[key]?.let { file -> CardImage(key, file.readBytes()) }
                    }
                    val createdDeckId = dao.importApkgPreparedDeck(
                        deckName = deckName,
                        images = deckImages,
                        cards = pairs
                    )
                    if (firstDeckId == 0L) firstDeckId = createdDeckId
                }
            }
            firstDeckId to warnings.distinct()
        } finally {
            workDir.deleteRecursively()
        }
        }
    }

    private fun prepareImportedCardImage(rawBytes: ByteArray): CardImage? {
        if (rawBytes.isEmpty()) return null
        val quick = runCatching {
            val candidate = CardImage(CardPictures.key(rawBytes), rawBytes)
            CardPictures.validate(candidate)
            candidate
        }.getOrNull()
        if (quick != null) return quick

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sampleSize = 1
        while (width / sampleSize > CardPictures.MAX_EDGE * 2 || height / sampleSize > CardPictures.MAX_EDGE * 2) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            rawBytes,
            0,
            rawBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null

        var scaled = decoded
        try {
            val maxDim = maxOf(decoded.width, decoded.height)
            if (maxDim > CardPictures.MAX_EDGE) {
                val scale = CardPictures.MAX_EDGE.toFloat() / maxDim.toFloat()
                val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            }
            val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            for (quality in listOf(84, 68, 52, 36)) {
                val out = ByteArrayOutputStream()
                scaled.compress(format, quality, out)
                val encoded = out.toByteArray()
                if (encoded.size <= CardPictures.MAX_BYTES) {
                    val img = CardImage(CardPictures.key(encoded), encoded)
                    val valid = runCatching { CardPictures.validate(img); img }.getOrNull()
                    if (valid != null) return valid
                }
            }
            return null
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun readApkgSqliteTables(sqliteFile: File): ApkgRawTables {
        return SQLiteDatabase.openDatabase(sqliteFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            var decksJson = "{}"
            var modelsJson = "{}"
            var crtSeconds = 0L
            var hasCrtColumn = false
            db.rawQuery("PRAGMA table_info(col)", null).use { pragma ->
                val nameCol = pragma.getColumnIndex("name")
                while (pragma.moveToNext()) {
                    if (nameCol >= 0 && pragma.getString(nameCol).equals("crt", ignoreCase = true)) {
                        hasCrtColumn = true
                        break
                    }
                }
            }
            val colSql = if (hasCrtColumn) "SELECT decks, models, crt FROM col LIMIT 1" else "SELECT decks, models FROM col LIMIT 1"
            db.rawQuery(colSql, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    decksJson = cursor.getString(0).orEmpty().ifBlank { "{}" }
                    modelsJson = cursor.getString(1).orEmpty().ifBlank { "{}" }
                    if (hasCrtColumn && !cursor.isNull(2)) {
                        crtSeconds = cursor.getLong(2)
                    }
                }
            }
            if (decksJson.length <= 2) {
                runCatching {
                    val decksObj = org.json.JSONObject()
                    db.rawQuery("SELECT id, name FROM decks", null).use { c ->
                        while (c.moveToNext()) {
                            val did = c.getLong(0)
                            val dname = c.getString(1).orEmpty().replace('', ':').replace(":", "::")
                            decksObj.put(did.toString(), org.json.JSONObject().put("id", did).put("name", dname))
                        }
                    }
                    if (decksObj.length() > 0) decksJson = decksObj.toString()
                }
            }
            if (modelsJson.length <= 2) {
                runCatching {
                    val modelsObj = org.json.JSONObject()
                    val fieldsByNtid = HashMap<Long, MutableList<Pair<Int, String>>>()
                    db.rawQuery("SELECT ntid, ord, name FROM fields ORDER BY ntid, ord", null).use { c ->
                        while (c.moveToNext()) {
                            fieldsByNtid.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getInt(1) to c.getString(2).orEmpty())
                        }
                    }
                    val tmplsByNtid = HashMap<Long, MutableList<org.json.JSONObject>>()
                    db.rawQuery("SELECT ntid, ord, name, config FROM templates ORDER BY ntid, ord", null).use { c ->
                        while (c.moveToNext()) {
                            val ntid = c.getLong(0)
                            val ord = c.getInt(1)
                            val tname = c.getString(2).orEmpty()
                            val cfg = if (!c.isNull(3)) c.getBlob(3) else null
                            var qfmt = "{{Front}}"
                            var afmt = "{{FrontSide}}<hr id=answer>{{Back}}"
                            if (cfg != null && cfg.isNotEmpty()) {
                                val parsed = ApkgPackageCodec.parseAnki21bMediaProtobuf(cfg)
                                if (!parsed["0"].isNullOrBlank()) qfmt = parsed["0"]!!
                            }
                            tmplsByNtid.getOrPut(ntid) { ArrayList() }.add(
                                org.json.JSONObject().put("name", tname).put("ord", ord).put("qfmt", qfmt).put("afmt", afmt)
                            )
                        }
                    }
                    db.rawQuery("SELECT id, name FROM notetypes", null).use { c ->
                        while (c.moveToNext()) {
                            val mid = c.getLong(0)
                            val mname = c.getString(1).orEmpty()
                            val fldsArr = org.json.JSONArray()
                            fieldsByNtid[mid].orEmpty().forEach { (_, fname) ->
                                fldsArr.put(org.json.JSONObject().put("name", fname))
                            }
                            val tmplsArr = org.json.JSONArray()
                            tmplsByNtid[mid].orEmpty().forEach { tmplsArr.put(it) }
                            val isCloze = mname.contains("cloze", ignoreCase = true) || mname.contains("omiss", ignoreCase = true)
                            modelsObj.put(
                                mid.toString(),
                                org.json.JSONObject()
                                    .put("id", mid)
                                    .put("name", mname)
                                    .put("type", if (isCloze) 1 else 0)
                                    .put("flds", fldsArr)
                                    .put("tmpls", tmplsArr)
                            )
                        }
                    }
                    if (modelsObj.length() > 0) modelsJson = modelsObj.toString()
                }
            }
            var hasGuidColumn = false
            db.rawQuery("PRAGMA table_info(notes)", null).use { pragma ->
                val nameCol = pragma.getColumnIndex("name")
                while (pragma.moveToNext()) {
                    if (nameCol >= 0 && pragma.getString(nameCol).equals("guid", ignoreCase = true)) {
                        hasGuidColumn = true
                        break
                    }
                }
            }
            val notes = mutableListOf<ApkgNoteRow>()
            val notesSql = if (hasGuidColumn) "SELECT id, mid, flds, tags, guid FROM notes" else "SELECT id, mid, flds, tags FROM notes"
            db.rawQuery(notesSql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    notes.add(
                        ApkgNoteRow(
                            id = cursor.getLong(0),
                            mid = cursor.getLong(1),
                            flds = cursor.getString(2).orEmpty(),
                            tags = cursor.getString(3).orEmpty(),
                            guid = if (hasGuidColumn && !cursor.isNull(4)) cursor.getString(4).orEmpty() else ""
                        )
                    )
                }
            }
            var hasFlagsColumn = false
            db.rawQuery("PRAGMA table_info(cards)", null).use { pragma ->
                val nameCol = pragma.getColumnIndex("name")
                while (pragma.moveToNext()) {
                    if (nameCol >= 0 && pragma.getString(nameCol).equals("flags", ignoreCase = true)) {
                        hasFlagsColumn = true
                        break
                    }
                }
            }
            val cards = mutableListOf<ApkgCardRow>()
            val cardSql = if (hasFlagsColumn) {
                "SELECT id, nid, did, ord, type, queue, due, ivl, factor, reps, lapses, flags FROM cards"
            } else {
                "SELECT id, nid, did, ord, type, queue, due, ivl, factor, reps, lapses FROM cards"
            }
            db.rawQuery(cardSql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    cards.add(
                        ApkgCardRow(
                            id = cursor.getLong(0),
                            nid = cursor.getLong(1),
                            did = cursor.getLong(2),
                            ord = cursor.getInt(3),
                            type = cursor.getInt(4),
                            queue = cursor.getInt(5),
                            due = cursor.getLong(6),
                            ivl = cursor.getInt(7),
                            factor = cursor.getInt(8),
                            reps = cursor.getInt(9),
                            lapses = cursor.getInt(10),
                            flags = if (hasFlagsColumn) cursor.getInt(11) else 0
                        )
                    )
                }
            }
            ApkgRawTables(decksJson = decksJson, modelsJson = modelsJson, notes = notes, cards = cards, crtSeconds = crtSeconds)
        }
    }

    companion object {
        fun factory(dao: StudyDao, context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T = StudyViewModel(dao, context.applicationContext) as T
        }
    }
}

private fun startOfDayMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun dayKeyOf(date: LocalDate): Long =
    date.year * 10000L + date.monthValue * 100L + date.dayOfMonth

private fun todayKey(): Long = dayKeyOf(LocalDate.now())

private fun shiftDayKey(dayKey: Long, amount: Int): Long {
    val year = (dayKey / 10000L).toInt().coerceIn(1970, 9999)
    val month = ((dayKey % 10000L) / 100L).toInt().coerceIn(1, 12)
    val maxDay = java.time.YearMonth.of(year, month).lengthOfMonth()
    val day = (dayKey % 100L).toInt().coerceIn(1, maxDay)
    return dayKeyOf(LocalDate.of(year, month, day).plusDays(amount.toLong()))
}
