@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val SUBJ_DETAIL_PT_BR_LOCALE: Locale = Locale("pt", "BR")
private val SUBJ_DETAIL_SHORT_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", SUBJ_DETAIL_PT_BR_LOCALE)
private val SUBJ_DETAIL_DIACRITICS_REGEX = Regex("\\p{M}+")
private val SUBJ_DETAIL_WS_REGEX = Regex("\\s+")

private data class SubjectDetailDerivedState(
    val initialized: Boolean = false,
    val studiedMinutesByTaskId: Map<Long, Long> = emptyMap(),
    val subjectTasks: List<StudyTask> = emptyList(),
    val subjectItems: List<SubjectItem> = emptyList(),
    val subjectQuestions: List<QuestionLog> = emptyList(),
    val subjectBooks: List<Book> = emptyList(),
    val subjectMistakes: List<Mistake> = emptyList(),
    val subjectGoals: List<Goal> = emptyList(),
    val subjectFlashcards: List<Flashcard> = emptyList(),
    val dueSubjectCards: List<Flashcard> = emptyList(),
    val overview: SubjectEcosystemOverview? = null,
    val syllabusTopics: List<VerticalSyllabusTopic> = emptyList(),
    val periodSessions: List<StudySession> = emptyList(),
    val periodQuestions: List<QuestionLog> = emptyList(),
    val totalQuestions: Int = 0,
    val correctQuestions: Int = 0,
    val accuracy: Int = 0
)

internal data class LinkedNoteMatch(
    val note: Note,
    val reason: String,
    val preview: String
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SubjectDetailPage(
    subject: Subject,
    sessions: List<StudySession>,
    tasks: List<StudyTask>,
    items: List<SubjectItem>,
    questions: List<QuestionLog>,
    books: List<Book>,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onAddTask: (String, Long) -> Unit,
    onCompleteTask: (Long, Boolean) -> Unit,
    onDeleteTask: (StudyTask) -> Unit,
    onSaveItem: (SubjectItem) -> Unit,
    onDeleteItem: (SubjectItem) -> Unit,
    mistakes: List<Mistake>,
    onMistake: (Mistake) -> Unit,
    onAddQuestions: (String, Int, Int) -> Unit,
    cardCount: Int,
    onOpenCards: () -> Unit,
    onOpenBooks: () -> Unit,
    onStartTask: ((StudyTask) -> Unit)? = null,
    onSaveTask: ((StudyTask) -> Unit)? = null,
    onToggleSubtask: ((StudyTask, String) -> Unit)? = null,
    initialTab: Int = 0,
    notes: List<Note> = emptyList(),
    bookNoteLinks: List<BookNoteLink> = emptyList(),
    flashcards: List<Flashcard> = emptyList(),
    goals: List<Goal> = emptyList(),
    cardReviews: List<CardReview> = emptyList(),
    readingLogs: List<ReadingLog> = emptyList(),
    habitCompletions: List<HabitCompletion> = emptyList(),
    allSubjects: List<Subject> = emptyList(),
    dailyGoalMinutes: Int = 180,
    onUpdateSubject: ((Subject) -> Unit)? = null,
    onStartTopic: ((String) -> Unit)? = null,
    onOpenNote: ((Long) -> Unit)? = null,
    onCreateNoteForSubject: ((Subject) -> Unit)? = null,
    onReviewSubjectCards: ((List<Flashcard>) -> Unit)? = null,
    onOpenGoals: (() -> Unit)? = null,
    onUpdateMistake: ((Mistake) -> Unit)? = null,
    onDeleteMistake: ((Mistake) -> Unit)? = null,
    onReviewMistake: ((Mistake, Boolean) -> Unit)? = null,
    onMistakeToCard: ((Subject, Mistake) -> Unit)? = null,
    onMistakeToTask: ((Subject, Mistake) -> Unit)? = null,
    onMistakeToTutor: ((Subject, Mistake) -> Unit)? = null,
    onOpenMathArena: ((Long) -> Unit)? = null,
    onOpenSubject: ((Long) -> Unit)? = null,
    onSaveSubject: ((Subject) -> Unit)? = onUpdateSubject
) {
    val context = LocalContext.current
    val tabLabels = remember { listOf("Resumo", "Planejar", "Questões", "Livros", "Conhecimento") }
    var tab by remember(subject.id, initialTab) { mutableIntStateOf(initialTab.coerceIn(0, tabLabels.lastIndex)) }
    var period by remember { mutableIntStateOf(30) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }

    var showTaskDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<StudyTask?>(null) }
    var showItemDialog by remember { mutableStateOf(false) }
    var itemDialogDefaultType by remember { mutableStateOf("GOAL") }
    var editingItem by remember { mutableStateOf<SubjectItem?>(null) }
    var showQuestionDialog by remember { mutableStateOf(false) }
    var prefilledQuestionTopic by remember { mutableStateOf("") }
    var showSubjectEditor by remember { mutableStateOf(false) }
    var showSubSubjectEditor by remember { mutableStateOf(false) }

    val parentSubject = remember(subject.parentId, allSubjects) {
        subject.parentId?.let { pid -> allSubjects.firstOrNull { it.id == pid && it.id != subject.id } }
    }
    val childSubjects = remember(subject.id, allSubjects) {
        if (subject.parentId == null) SubjectHierarchy.childrenOf(subject.id, allSubjects)
        else emptyList()
    }

    val todayMidnight = remember {
        LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
    var studiedMinutesByTaskId by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(sessions) {
        studiedMinutesByTaskId = withContext(Dispatchers.Default) {
            sessions.filter { !it.isActive && it.taskId != null }
                .groupBy { it.taskId!! }
                .mapValues { (_, list) -> list.sumOf { it.durationSeconds } / 60L }
        }
    }

    fun generateStudyCycleFor(title: String) {
        val baseTitle = title.trim().ifBlank { subject.name }
        val cycleTasks = listOf(
            StudyTask(
                subjectId = subject.id,
                title = "Teoria · $baseTitle",
                dueAt = noonTimestamp(offsetDays = 0),
                hasDueDate = true,
                priority = 2,
                estimatedMinutes = 45,
                tags = TaskTagPresets.TEORIA
            ),
            StudyTask(
                subjectId = subject.id,
                title = "Questões · $baseTitle",
                dueAt = noonTimestamp(offsetDays = 1),
                hasDueDate = true,
                priority = 2,
                estimatedMinutes = 30,
                tags = TaskTagPresets.QUESTOES
            ),
            StudyTask(
                subjectId = subject.id,
                title = "Revisão · $baseTitle",
                dueAt = noonTimestamp(offsetDays = 3),
                hasDueDate = true,
                priority = 3,
                estimatedMinutes = 20,
                tags = TaskTagPresets.REVISAO
            )
        )
        cycleTasks.forEach { task ->
            if (onSaveTask != null) onSaveTask(task) else onAddTask(task.title, task.dueAt)
        }
        Toast.makeText(context, "Ciclo gerado: Teoria + Questões + Revisão", Toast.LENGTH_SHORT).show()
    }

    var linkedNotes by remember(subject.id) { mutableStateOf<List<LinkedNoteMatch>>(emptyList()) }
    LaunchedEffect(notes, subject, books, bookNoteLinks, tasks, flashcards) {
        linkedNotes = withContext(Dispatchers.Default) {
            val scopedBooks = books.filter { it.subjectId == subject.id }
            val scopedTasks = tasks.filter { it.subjectId == subject.id }
            val scopedCards = flashcards.filter { it.subjectId == subject.id && !it.suspended }
            findLinkedNotesForSubject(
                subject = subject,
                notes = notes,
                subjectBooks = scopedBooks,
                bookNoteLinks = bookNoteLinks,
                subjectTasks = scopedTasks,
                subjectFlashcards = scopedCards
            )
        }
    }

    var derived by remember(subject.id) { mutableStateOf(SubjectDetailDerivedState()) }
    LaunchedEffect(
        allSubjects,
        subject,
        sessions,
        tasks,
        items,
        questions,
        books,
        mistakes,
        goals,
        flashcards,
        dailyGoalMinutes,
        period,
        now
    ) {
        derived = withContext(Dispatchers.Default) {
            val sTasks = tasks.filter { it.subjectId == subject.id }
            val sItems = items.filter { it.subjectId == subject.id }
            val sQuestions = questions.filter { it.subjectId == subject.id }
            val sBooks = books.filter { it.subjectId == subject.id }
            val sMistakes = mistakes.filter { it.subjectId == subject.id }
            val sGoals = goals.filter { it.subjectId == subject.id }
                .sortedWith(compareBy<Goal> { it.completed }.thenBy { it.targetAt })
            val sFlashcards = flashcards.filter { it.subjectId == subject.id && !it.suspended }
            val dueCards = sFlashcards.filter { it.dueAt <= now }
            val subjectList = if (allSubjects.any { it.id == subject.id }) allSubjects else listOf(subject)
            val computedOverview = SubjectEcosystemEngine.buildOverview(
                subjects = subjectList,
                sessions = sessions,
                tasks = tasks,
                flashcards = flashcards,
                mistakes = mistakes,
                questionLogs = questions,
                dailyGoalMinutes = dailyGoalMinutes,
                now = now
            ).firstOrNull { it.subject.id == subject.id }
            val computedTopics = SyllabusEngine.buildTopics(
                subjectId = subject.id,
                items = sItems,
                questionLogs = sQuestions,
                flashcards = flashcards,
                mistakes = sMistakes,
                now = now
            )
            val since = now - period * 86_400_000L
            val pSessions = sessions.filter { it.subjectId == subject.id && !it.isActive && it.startedAt >= since }
            val pQuestions = sQuestions.filter { it.loggedAt >= since }
            val totQ = pQuestions.sumOf { it.questionCount }
            val corrQ = pQuestions.sumOf { it.correctCount }
            val acc = if (totQ == 0) 0 else corrQ * 100 / totQ
            SubjectDetailDerivedState(
                initialized = true,
                subjectTasks = sTasks,
                subjectItems = sItems,
                subjectQuestions = sQuestions,
                subjectBooks = sBooks,
                subjectMistakes = sMistakes,
                subjectGoals = sGoals,
                subjectFlashcards = sFlashcards,
                dueSubjectCards = dueCards,
                overview = computedOverview,
                syllabusTopics = computedTopics,
                periodSessions = pSessions,
                periodQuestions = pQuestions,
                totalQuestions = totQ,
                correctQuestions = corrQ,
                accuracy = acc
            )
        }
    }

    val subjectTasks = derived.subjectTasks
    val subjectItems = derived.subjectItems
    val subjectQuestions = derived.subjectQuestions
    val subjectBooks = derived.subjectBooks
    val subjectMistakes = derived.subjectMistakes
    val subjectGoals = derived.subjectGoals
    val subjectFlashcards = derived.subjectFlashcards
    val dueSubjectCards = derived.dueSubjectCards
    val effectiveCardCount = if (flashcards.isNotEmpty() && derived.initialized) subjectFlashcards.size else cardCount
    val overview = derived.overview
    val syllabusTopics = derived.syllabusTopics
    val periodSessions = derived.periodSessions
    val periodQuestions = derived.periodQuestions
    val totalQuestions = derived.totalQuestions
    val correctQuestions = derived.correctQuestions
    val accuracy = derived.accuracy
    val rawSubjectColor = Color(subject.color.toInt())
    val subjectColor = accessibleAccentOnSurface(rawSubjectColor, MaterialTheme.colorScheme.surface)

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Header enriquecido
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StudyPageHeader(
                title = subject.name,
                onBack = onBack,
                showGlobalSearch = true,
                actions = {
                    if (onOpenMathArena != null) {
                        IconButton(
                            onClick = { onOpenMathArena(subject.id) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Calculate, "Abrir Arena de Matemática")
                        }
                    }
                    if (onUpdateSubject != null) {
                        IconButton(
                            onClick = { showSubjectEditor = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Tune, "Editar configurações da matéria")
                        }
                    }
                    FilledTonalIconButton(
                        onClick = onStart,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, "Começar a estudar")
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(5.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(rawSubjectColor)
                )
                FlowRow(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (parentSubject != null) {
                        AssistChip(
                            onClick = { onOpenSubject?.invoke(parentSubject.id) },
                            label = {
                                Text(
                                    "Submatéria de ${parentSubject.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    val todaySec = overview?.todayFocusSeconds ?: 0L
                    val targetMin = if (subject.targetMinutesPerDay > 0) {
                        subject.targetMinutesPerDay
                    } else {
                        overview?.recommendedMinutesPerDay ?: 0
                    }
                    val metaLabel = when {
                        subject.targetMinutesPerDay > 0 ->
                            "Meta ${formatMinutesBadge(todaySec)}/${subject.targetMinutesPerDay}m"
                        targetMin > 0 ->
                            "Ciclo ${formatMinutesBadge(todaySec)}/${targetMin}m"
                        else ->
                            "${formatMinutesBadge(todaySec)} hoje"
                    }
                    SubjectBadgePill(
                        text = metaLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (subject.archived) {
                        SubjectBadgePill(
                            text = "Arquivada",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val sharePct = overview?.cycleSharePercent?.roundToInt()
                        val weightLabel = if (allSubjects.size > 1 && sharePct != null) {
                            "Peso ${subject.weight} · $sharePct%"
                        } else {
                            "Peso ${subject.weight}"
                        }
                        SubjectBadgePill(
                            text = weightLabel,
                            containerColor = rawSubjectColor.copy(alpha = 0.12f),
                            contentColor = subjectColor
                        )
                    }

                    val examDateMs = subject.examDate
                    val examInfo = examCountdownInfo(examDateMs, overview?.daysUntilExam)
                    if (examInfo != null && examDateMs != null) {
                        val dateStr = remember(examDateMs) {
                            Instant.ofEpochMilli(examDateMs)
                                .atZone(ZoneId.systemDefault())
                                .format(SUBJ_DETAIL_SHORT_DATE_FORMATTER)
                        }
                        SubjectBadgePill(
                            text = "${examInfo.first} ($dateStr)",
                            containerColor = if (examInfo.second) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.tertiaryContainer
                            },
                            contentColor = if (examInfo.second) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                    }
                }
            }

            InlineNextStepField(
                subjectId = subject.id,
                nextStep = subject.nextStep,
                accentColor = subjectColor,
                editable = onUpdateSubject != null,
                placeholder = if (onUpdateSubject != null) {
                    "Definir próximo passo da matéria..."
                } else {
                    "Sem próximo passo definido"
                },
                onSave = { nextText ->
                    onUpdateSubject?.invoke(subject.copy(nextStep = nextText))
                    Toast.makeText(
                        context,
                        if (nextText == null) "Próximo passo limpo" else "Próximo passo salvo",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }

        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 0.dp,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabLabels.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            label,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
        ) {
            when (tab) {
                0 -> SubjectSummaryTab(
                    subject = subject,
                    overview = overview,
                    subjectColor = subjectColor,
                    period = period,
                    onPeriodChange = { period = it },
                    periodSessions = periodSessions,
                    totalQuestions = totalQuestions,
                    correctQuestions = correctQuestions,
                    accuracy = accuracy,
                    subjectFlashcards = subjectFlashcards,
                    dueSubjectCards = dueSubjectCards,
                    effectiveCardCount = effectiveCardCount,
                    subjectTasks = subjectTasks,
                    subjectMistakes = subjectMistakes,
                    syllabusTopics = syllabusTopics,
                    linkedNotes = linkedNotes,
                    subjectGoals = subjectGoals,
                    now = now,
                    onOpenCards = onOpenCards,
                    onReviewSubjectCards = onReviewSubjectCards,
                    onSelectTab = { tab = it },
                    childSubjects = childSubjects,
                    onOpenChildSubject = onOpenSubject,
                    onAddSubSubject = if (subject.parentId == null && onSaveSubject != null) {
                        { showSubSubjectEditor = true }
                    } else null
                )

                1 -> SubjectPlanTab(
                    subject = subject,
                    syllabusTopics = syllabusTopics,
                    subjectGoals = subjectGoals,
                    subjectItems = subjectItems,
                    subjectTasks = subjectTasks,
                    sessions = sessions,
                    questions = questions,
                    books = books,
                    readingLogs = readingLogs,
                    flashcards = flashcards,
                    cardReviews = cardReviews,
                    tasks = tasks,
                    habitCompletions = habitCompletions,
                    todayMidnight = todayMidnight,
                    studiedMinutesByTaskId = studiedMinutesByTaskId,
                    now = now,
                    onStart = onStart,
                    onStartTopic = onStartTopic,
                    onSaveItem = onSaveItem,
                    onDeleteItem = onDeleteItem,
                    onCompleteTask = onCompleteTask,
                    onDeleteTask = onDeleteTask,
                    onStartTask = onStartTask,
                    onSaveTask = onSaveTask,
                    onToggleSubtask = onToggleSubtask,
                    onOpenGoals = onOpenGoals,
                    onGenerateCycle = ::generateStudyCycleFor,
                    onOpenNewTopicDialog = {
                        editingItem = null
                        itemDialogDefaultType = "TOPIC"
                        showItemDialog = true
                    },
                    onOpenEditItemDialog = { item ->
                        editingItem = item
                        itemDialogDefaultType = item.type
                        showItemDialog = true
                    },
                    onOpenNewGoalItemDialog = {
                        editingItem = null
                        itemDialogDefaultType = "GOAL"
                        showItemDialog = true
                    },
                    onOpenQuestionDialogForTopic = { topicTitle ->
                        prefilledQuestionTopic = topicTitle
                        showQuestionDialog = true
                    },
                    onOpenNewTaskDialog = {
                        editingTask = null
                        showTaskDialog = true
                    },
                    onOpenEditTaskDialog = { task ->
                        editingTask = task
                        showTaskDialog = true
                    }
                )

                2 -> {
                    if (onOpenMathArena != null) {
                        StudyCard(
                            onClick = { onOpenMathArena(subject.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            containerColorOverride = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                ) {
                                    Icon(
                                        Icons.Default.Calculate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(9.dp).size(20.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Text(
                                        "Arena de Exatas",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "Treinar equações visuais com cronômetro e salvar acertos/erros",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    SubjectQuestionsTab(
                        subject = subject,
                        period = period,
                        onPeriodChange = { period = it },
                        periodQuestions = periodQuestions,
                        totalQuestions = totalQuestions,
                        correctQuestions = correctQuestions,
                        accuracy = accuracy,
                        subjectMistakes = subjectMistakes,
                        topicSuggestions = syllabusTopics.map { it.title },
                        now = now,
                        onOpenQuestionDialog = {
                            prefilledQuestionTopic = ""
                            showQuestionDialog = true
                        },
                        onMistake = onMistake,
                        onUpdateMistake = onUpdateMistake,
                        onDeleteMistake = onDeleteMistake,
                        onReviewMistake = onReviewMistake,
                        onMistakeToCard = onMistakeToCard,
                        onMistakeToTask = onMistakeToTask,
                        onMistakeToTutor = onMistakeToTutor,
                        onSaveTask = onSaveTask,
                        onAddTask = onAddTask
                    )
                }

                3 -> SubjectBooksTab(
                    subjectBooks = subjectBooks,
                    onOpenBooks = onOpenBooks
                )

                else -> SubjectKnowledgeTab(
                    subject = subject,
                    linkedNotes = linkedNotes,
                    subjectFlashcards = subjectFlashcards,
                    dueSubjectCards = dueSubjectCards,
                    fallbackCardCount = effectiveCardCount,
                    syllabusTopics = syllabusTopics,
                    subjectGoals = subjectGoals,
                    sessions = sessions,
                    questions = questions,
                    books = books,
                    readingLogs = readingLogs,
                    allFlashcards = flashcards,
                    cardReviews = cardReviews,
                    tasks = tasks,
                    habitCompletions = habitCompletions,
                    now = now,
                    onOpenNote = onOpenNote,
                    onCreateNoteForSubject = onCreateNoteForSubject,
                    onOpenCards = onOpenCards,
                    onReviewSubjectCards = onReviewSubjectCards,
                    onOpenGoals = onOpenGoals,
                    onGoToPlanTab = { tab = 1 },
                    onGoToQuestionsTab = { tab = 2 }
                )
            }
        }
    }

    if (showTaskDialog) {
        SubjectTaskDialog(
            subjectId = subject.id,
            subjectName = subject.name,
            editingTask = editingTask,
            supportsFullEdit = onSaveTask != null,
            onDismiss = { showTaskDialog = false; editingTask = null },
            onSave = { savedTask ->
                if (onSaveTask != null) {
                    onSaveTask(savedTask)
                } else {
                    onAddTask(savedTask.title, savedTask.dueAt)
                }
                showTaskDialog = false
                editingTask = null
            }
        )
    }

    if (showItemDialog) {
        SubjectItemDialog(
            subjectId = subject.id,
            item = editingItem,
            defaultType = itemDialogDefaultType,
            onDismiss = { showItemDialog = false; editingItem = null },
            onSave = {
                onSaveItem(it)
                showItemDialog = false
                editingItem = null
            }
        )
    }

    if (showQuestionDialog) {
        SubjectQuestionDialog(
            initialTopic = prefilledQuestionTopic,
            topicSuggestions = syllabusTopics.map { it.title },
            onDismiss = {
                showQuestionDialog = false
                prefilledQuestionTopic = ""
            },
            onSave = { topic, total, correct ->
                onAddQuestions(topic, total, correct)
                showQuestionDialog = false
                prefilledQuestionTopic = ""
            }
        )
    }

    if (showSubjectEditor && onUpdateSubject != null) {
        SubjectEditorDialog(
            initial = subject,
            defaultColor = subject.color,
            allSubjects = allSubjects,
            onDismiss = { showSubjectEditor = false },
            onSave = { updated ->
                onUpdateSubject(updated)
                showSubjectEditor = false
            }
        )
    }

    if (showSubSubjectEditor && onSaveSubject != null) {
        SubjectEditorDialog(
            initial = null,
            defaultColor = subject.color,
            allSubjects = allSubjects,
            initialParentSubject = subject,
            onDismiss = { showSubSubjectEditor = false },
            onSave = { created ->
                onSaveSubject(created)
                showSubSubjectEditor = false
            }
        )
    }
}

private fun normalizeMatchToken(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(SUBJ_DETAIL_DIACRITICS_REGEX, "")
        .lowercase(Locale.ROOT)
        .trim()

private fun findLinkedNotesForSubject(
    subject: Subject,
    notes: List<Note>,
    subjectBooks: List<Book>,
    bookNoteLinks: List<BookNoteLink>,
    subjectTasks: List<StudyTask>,
    subjectFlashcards: List<Flashcard>
): List<LinkedNoteMatch> {
    val activeNotes = notes.filter { it.trashedAt == null }
    if (activeNotes.isEmpty()) return emptyList()

    val bookIds = subjectBooks.map { it.id }.toSet()
    val bookLinkedNoteIds = bookNoteLinks.filter { it.bookId in bookIds }.map { it.noteId }.toSet()
    val taskLinkedNoteIds = subjectTasks.mapNotNull { it.noteId }.toSet()
    val cardLinkedNoteIds = subjectFlashcards.mapNotNull { it.sourceNoteId }.toSet()

    val normName = normalizeMatchToken(subject.name)
    val compactSlug = normName.replace(SUBJ_DETAIL_WS_REGEX, "")
    val hyphenSlug = normName.replace(SUBJ_DETAIL_WS_REGEX, "-")
    val wordRegex = if (normName.isNotEmpty()) {
        Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(normName)}(?![\\p{L}\\p{N}_])")
    } else null

    fun matchesText(text: String): Boolean {
        if (normName.isEmpty()) return false
        val normText = normalizeMatchToken(text)
        if (normText.isEmpty()) return false
        if (wordRegex != null && wordRegex.containsMatchIn(normText)) return true
        if (compactSlug.isNotEmpty() && ("#$compactSlug" in normText || "#$hyphenSlug" in normText)) return true
        if (normName.length >= 4 && normName in normText) return true
        return false
    }

    return activeNotes.mapNotNull { note ->
        val (props, body) = NoteFrontmatter.parse(note.markdown)
        val reason = when {
            note.id in bookLinkedNoteIds -> "Livro vinculado"
            note.id in taskLinkedNoteIds -> "Tarefa vinculada"
            note.id in cardLinkedNoteIds -> "Flashcard vinculado"
            props.folder?.let { matchesText(it) } == true -> "Pasta: ${props.folder}"
            props.tags.any { tag ->
                val nt = normalizeMatchToken(tag)
                nt == normName || nt == compactSlug || nt == hyphenSlug
            } -> "Tag #${compactSlug.ifEmpty { normName }}"
            props.aliases.any { matchesText(it) } -> "Alias"
            matchesText(note.title) -> "Título"
            matchesText(body) -> "Menção no texto"
            else -> null
        } ?: return@mapNotNull null

        val preview = body
            .lineSequence()
            .map { it.trim().trimStart('#', '-', '*', '>', ' ') }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        LinkedNoteMatch(note = note, reason = reason, preview = preview)
    }.sortedWith(compareByDescending<LinkedNoteMatch> { it.note.pinned }.thenByDescending { it.note.updatedAt })
}
