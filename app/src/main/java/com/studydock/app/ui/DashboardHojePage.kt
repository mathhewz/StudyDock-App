package com.studydock.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studydock.app.StudyViewModel
import com.studydock.app.data.Book
import com.studydock.app.data.CalendarEvent
import com.studydock.app.data.CardReview
import com.studydock.app.data.DiaryAnalytics
import com.studydock.app.data.Flashcard
import com.studydock.app.data.Goal
import com.studydock.app.data.GoalMetricType
import com.studydock.app.data.GoalProgressEngine
import com.studydock.app.data.GoalProgressSnapshot
import com.studydock.app.data.Habit
import com.studydock.app.data.HabitCompletion
import com.studydock.app.data.JournalEntry
import com.studydock.app.data.QuestionLog
import com.studydock.app.data.ReadingLog
import com.studydock.app.data.StudySession
import com.studydock.app.data.StudyTask
import com.studydock.app.data.UnifiedBrainEngine
import com.studydock.app.data.WorkoutLog
import com.studydock.app.data.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DASH_PT_BR_LOCALE: Locale = Locale("pt", "BR")
private val DASH_FULL_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", DASH_PT_BR_LOCALE)

/**
 * Tela principal (`Hoje`) com coleta condicional por widget visível e janela temporal recente.
 *
 * Estratégia de Cold Start e histórico:
 * - Coleta apenas os fluxos necessários para os widgets ativos em [StudyThemeConfig] e para os
 *   tipos de métrica presentes nas metas fixadas, evitando disparar dezenas de queries Room no boot.
 * - Usa janela de 365 dias (`recentSessions`, `recentCardReviews`, `recentHabitCompletions`,
 *   `recentQuestionLogs`, `recentReadingLogs`) para manter a aba inicial rápida sem ler múltiplos
 *   anos de histórico na inicialização; o histórico completo continua disponível sob demanda em
 *   `StudyAnalyticsPage` (Histórico), `GoalsDashboardPage` (Metas) e `ReviewPage` (Revisão).
 * - O card de contagem total de sessões usa `vm.sessionsCount` (`COUNT(*)`) para exibir o total
 *   acumulado exato mesmo com a janela de 365 dias nos gráficos e streaks da tela inicial.
 */
@Composable
internal fun DashboardHojePage(
    vm: StudyViewModel,
    timerActive: Boolean,
    themeConfig: StudyThemeConfig,
    onOpenThemeStudio: () -> Unit,
    onTimer: () -> Unit,
    onSubjects: () -> Unit,
    onStartSubject: (Long) -> Unit,
    onTasks: () -> Unit,
    onNewNote: () -> Unit,
    onHabits: () -> Unit,
    onGoals: () -> Unit,
    onCards: () -> Unit,
    onHistory: () -> Unit,
    onOpenPlanner: () -> Unit,
    onDiary: () -> Unit
) {
    val showHero = themeConfig.isWidgetVisible(StudyDashboardWidgets.HERO_STUDY)
    val showDailyGoal = themeConfig.isWidgetVisible(StudyDashboardWidgets.DAILY_GOAL)
    val showPinnedGoals = themeConfig.isWidgetVisible(StudyDashboardWidgets.PINNED_GOALS)
    val showDiary = themeConfig.isWidgetVisible(StudyDashboardWidgets.DIARY_CHECKIN)
    val showAgenda = themeConfig.isWidgetVisible(StudyDashboardWidgets.TODAY_AGENDA)
    val showHabits = themeConfig.isWidgetVisible(StudyDashboardWidgets.HABITS_TODAY)
    val showStats = themeConfig.isWidgetVisible(StudyDashboardWidgets.STATS_GRID)
    val showCharts = themeConfig.isWidgetVisible(StudyDashboardWidgets.CHARTS_HEATMAP)
    val showRecentSessions = themeConfig.isWidgetVisible(StudyDashboardWidgets.RECENT_SESSIONS)

    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val recentSessions = if (showHero || showDailyGoal || showPinnedGoals || showCharts || showRecentSessions) {
        vm.recentSessions.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val totalSessionsCount = if (showStats) {
        vm.sessionsCount.collectAsStateWithLifecycle().value
    } else {
        recentSessions.size
    }

    val goals = if (showPinnedGoals) {
        vm.goals.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val pinnedMetricTypes = remember(goals, showPinnedGoals) {
        if (!showPinnedGoals) emptySet()
        else goals.asSequence()
            .filter { it.pinned && !it.completed }
            .take(4)
            .map { GoalMetricType.fromId(it.metricType) }
            .toSet()
    }
    val activeGoalsCount = when {
        showPinnedGoals -> goals.count { !it.completed }
        showStats -> vm.activeGoalsCount.collectAsStateWithLifecycle().value
        else -> 0
    }

    val habits = if (showHabits || showStats) {
        vm.habits.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val completedHabitIds = if (showHabits || showStats) {
        val completed by vm.todayCompletions.collectAsStateWithLifecycle()
        remember(completed) { completed.mapTo(HashSet(completed.size)) { it.habitId } }
    } else {
        emptySet()
    }

    val dueCards = if (showStats) {
        vm.dueFlashcardsCount.collectAsStateWithLifecycle().value
    } else {
        0
    }

    val datedTasks = if (showAgenda || GoalMetricType.TASKS in pinnedMetricTypes) {
        val allTasks by vm.tasks.collectAsStateWithLifecycle()
        if (showAgenda && GoalMetricType.TASKS !in pinnedMetricTypes) {
            remember(allTasks) { allTasks.filter { it.hasDueDate } }
        } else {
            allTasks
        }
    } else {
        emptyList()
    }
    val upcomingEvents = if (showAgenda) {
        vm.todayUpcomingEvents.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val recentJournalEntries = if (showDiary) {
        vm.recentJournalEntries.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val recentWorkouts = if (showDiary || showHero) {
        vm.workouts.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }

    val questionLogs = if (
        GoalMetricType.QUESTIONS in pinnedMetricTypes ||
        GoalMetricType.ACCURACY in pinnedMetricTypes
    ) {
        vm.recentQuestionLogs.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val books = if (GoalMetricType.PAGES in pinnedMetricTypes) {
        vm.books.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val readingLogs = if (GoalMetricType.PAGES in pinnedMetricTypes) {
        vm.recentReadingLogs.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val flashcards = if (GoalMetricType.FLASHCARDS in pinnedMetricTypes) {
        vm.flashcards.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val cardReviews = if (GoalMetricType.FLASHCARDS in pinnedMetricTypes) {
        vm.recentCardReviews.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val habitCompletions = if (GoalMetricType.HABITS in pinnedMetricTypes) {
        vm.recentHabitCompletions.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }

    val dailyGoalMinutes = if (showCharts) {
        rememberDailyGoalResolution(subjects).goalMinutes
    } else {
        180
    }

    Dashboard(
        subjects = subjects,
        sessions = recentSessions,
        totalSessionsCount = totalSessionsCount,
        habits = habits,
        completed = completedHabitIds,
        goals = goals,
        activeGoalsCount = activeGoalsCount,
        dueCards = dueCards,
        onTimer = onTimer,
        onSubjects = onSubjects,
        onStartSubject = onStartSubject,
        onTasks = onTasks,
        onNewNote = onNewNote,
        onHabits = onHabits,
        onGoals = onGoals,
        onCards = onCards,
        onHistory = onHistory,
        tasks = datedTasks,
        events = upcomingEvents,
        onToggleTask = vm::toggleTask,
        timerActive = timerActive,
        questionLogs = questionLogs,
        books = books,
        readingLogs = readingLogs,
        flashcards = flashcards,
        cardReviews = cardReviews,
        habitCompletions = habitCompletions,
        journalEntries = recentJournalEntries,
        workouts = recentWorkouts,
        dailyGoalMinutes = dailyGoalMinutes,
        onOpenPlanner = onOpenPlanner,
        onDiary = onDiary,
        onQuickSaveDiary = vm::saveJournalEntry,
        themeConfig = themeConfig,
        onOpenThemeStudio = onOpenThemeStudio
    )
}

@Composable
private fun Dashboard(
    subjects: List<Subject>,
    sessions: List<StudySession>,
    totalSessionsCount: Int,
    habits: List<Habit>,
    completed: Set<Long>,
    goals: List<Goal>,
    activeGoalsCount: Int,
    dueCards: Int,
    onTimer: () -> Unit,
    onSubjects: () -> Unit,
    onStartSubject: (Long) -> Unit,
    onTasks: () -> Unit,
    onNewNote: () -> Unit,
    onHabits: () -> Unit,
    onGoals: () -> Unit,
    onCards: () -> Unit,
    onHistory: () -> Unit,
    tasks: List<StudyTask> = emptyList(),
    events: List<CalendarEvent> = emptyList(),
    onToggleTask: (StudyTask) -> Unit = {},
    timerActive: Boolean = false,
    questionLogs: List<QuestionLog> = emptyList(),
    books: List<Book> = emptyList(),
    readingLogs: List<ReadingLog> = emptyList(),
    flashcards: List<Flashcard> = emptyList(),
    cardReviews: List<CardReview> = emptyList(),
    habitCompletions: List<HabitCompletion> = emptyList(),
    journalEntries: List<JournalEntry> = emptyList(),
    workouts: List<WorkoutLog> = emptyList(),
    dailyGoalMinutes: Int = 180,
    onOpenPlanner: () -> Unit = {},
    onDiary: () -> Unit = {},
    onQuickSaveDiary: (JournalEntry) -> Unit = {},
    themeConfig: StudyThemeConfig = LocalStudyThemeConfig.current,
    onOpenThemeStudio: () -> Unit = LocalThemeStudioOpener.current
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val todayDate = remember(zone) { LocalDate.now(zone) }
    val sessionAggregates = remember(sessions, todayDate, zone) {
        computeSessionAggregates(sessions, zone, todayDate)
    }
    val total = sessionAggregates.todaySeconds
    val hours = total / 3600
    val mins = total % 3600 / 60
    val subjectsById = remember(subjects) { subjects.associateBy { it.id } }
    val activeSubjects = remember(subjects) { subjects.filter { !it.archived } }
    val featuredSubject = remember(sessions, activeSubjects, subjectsById) {
        val latestSubject = sessions
            .asSequence()
            .filter { it.subjectId != null && it.durationSeconds > 0 }
            .maxByOrNull { it.startedAt }
            ?.subjectId
            ?.let { id -> subjectsById[id] }
        latestSubject?.takeIf { !it.archived }
            ?: activeSubjects.firstOrNull { !it.nextStep.isNullOrBlank() }
            ?: activeSubjects.firstOrNull()
    }
    var pinnedGoalSnapshots by remember { mutableStateOf<List<GoalProgressSnapshot>>(emptyList()) }
    LaunchedEffect(
        goals, sessions, questionLogs, books, readingLogs, flashcards, cardReviews, tasks, habitCompletions
    ) {
        pinnedGoalSnapshots = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            goals.asSequence()
                .filter { it.pinned && !it.completed }
                .take(4)
                .map { goal ->
                    GoalProgressEngine.compute(
                        goal = goal,
                        sessions = sessions,
                        questionLogs = questionLogs,
                        books = books,
                        readingLogs = readingLogs,
                        flashcards = flashcards,
                        cardReviews = cardReviews,
                        tasks = tasks,
                        habitCompletions = habitCompletions,
                        now = now
                    )
                }
                .toList()
        }
        if (pinnedGoalSnapshots.isNotEmpty()) {
            GoalProgressWidget.cacheComputedSnapshots(context, pinnedGoalSnapshots)
        }
    }
    val datedTasks = remember(tasks) { tasks.filter { it.hasDueDate } }
    val greeting = remember(zone) {
        when (LocalTime.now(zone).hour) {
            in 5..11 -> "Bom dia"
            in 12..17 -> "Boa tarde"
            else -> "Boa noite"
        }
    }
    val todayLabel = remember(todayDate) {
        todayDate.format(DASH_FULL_DAY_FORMATTER)
            .replaceFirstChar { it.uppercase() }
    }
    val widgetOrder = remember(themeConfig.dashboardWidgetOrder) {
        StudyDashboardWidgets.sanitizeOrder(themeConfig.dashboardWidgetOrder)
    }
    val visibleWidgets = remember(widgetOrder, themeConfig) {
        buildList {
            for (w in widgetOrder) {
                if (!themeConfig.isWidgetVisible(w)) continue
                if (w == StudyDashboardWidgets.CHARTS_HEATMAP) {
                    add("charts_bar_30")
                    add("charts_month_heatmap")
                    add("charts_week_insights")
                } else {
                    add(w)
                }
            }
        }
    }
    val fontScale = LocalDensity.current.fontScale
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "dash_header", contentType = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        greeting,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        todayLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                IconButton(
                    onClick = onOpenThemeStudio,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = "Personalizar tema e dashboard",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            val todayDiaryKey = remember(todayDate) { DiaryAnalytics.dayKeyFromEpoch(System.currentTimeMillis()) }
            val maintenanceMode = remember(journalEntries, workouts, todayDiaryKey) {
                UnifiedBrainEngine.shouldEnableCognitiveMaintenanceMode(journalEntries, workouts, todayDiaryKey)
            }
            if (maintenanceMode) {
                Surface(
                    onClick = onTasks,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Text(
                        "🧠 Modo de Manutenção Cognitiva ativo: priorize revisões de alta retenção e tarefas de bateria fraca hoje. (Toque para abrir tarefas)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        items(
            items = visibleWidgets,
            key = { it },
            contentType = { it }
        ) { widgetId ->
            when (widgetId) {
                StudyDashboardWidgets.HERO_STUDY -> {
                    StudyCard(
                        modifier = Modifier.fillMaxWidth(),
                        emphasized = true,
                        isHero = true,
                        containerColorOverride = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding() + 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "TEMPO DE ESTUDO",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "$hours h ${mins} min",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Text(
                                        "${sessionAggregates.todayCount} sessões registradas",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.80f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Surface(
                                    shape = studyControlShape(),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                                ) {
                                    Icon(
                                        Icons.Default.School,
                                        null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(14.dp).size(28.dp)
                                    )
                                }
                            }
                            val featuredNextStep = featuredSubject?.nextStep?.trim().orEmpty()
                            if (featuredSubject != null && featuredNextStep.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Flag, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "Próximo passo · ${featuredSubject.name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                featuredNextStep,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = { if (timerActive || featuredSubject == null) onTimer() else onStartSubject(featuredSubject.id) },
                                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Text(
                                    when {
                                        timerActive -> "  Voltar para o timer"
                                        featuredSubject != null -> "  Estudar ${featuredSubject.name}"
                                        else -> "  Começar a estudar"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (featuredSubject != null && !timerActive) {
                                TextButton(
                                    onClick = onTimer,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                                    Text("  Escolher outra matéria", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
                StudyDashboardWidgets.DAILY_GOAL -> {
                    DailyGoalCard(
                        sessions = sessions,
                        subjects = subjects,
                        aggregates = sessionAggregates,
                        onOpenPlanner = onOpenPlanner,
                        onOpenSubjects = onSubjects
                    )
                }
                StudyDashboardWidgets.PINNED_GOALS -> {
                    if (pinnedGoalSnapshots.isNotEmpty()) {
                        PinnedGoalsDashboardSection(snapshots = pinnedGoalSnapshots, onOpenGoals = onGoals)
                    }
                }
                StudyDashboardWidgets.DIARY_CHECKIN -> {
                    TodayDiaryQuickCard(
                        entries = journalEntries,
                        onQuickSaveMood = onQuickSaveDiary,
                        onOpenDiary = onDiary
                    )
                }
                StudyDashboardWidgets.TODAY_AGENDA -> {
                    TodayAgendaCard(datedTasks, events, subjects, onToggleTask)
                }
                StudyDashboardWidgets.QUICK_ACTIONS -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Acesso rápido", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DashboardQuickAction("Tarefa", Icons.Default.Checklist, Modifier.weight(1f), onTasks)
                            DashboardQuickAction("Nota", Icons.Default.EditNote, Modifier.weight(1f), onNewNote)
                            DashboardQuickAction("Revisar", Icons.Default.Style, Modifier.weight(1f), onCards)
                        }
                    }
                }
                StudyDashboardWidgets.HABITS_TODAY -> {
                    HabitsTodayCard(habits, completed, onHabits)
                }
                StudyDashboardWidgets.STATS_GRID -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        val twoColumnsOnly = screenWidthDp < 380.dp || fontScale >= 1.25f
                        if (twoColumnsOnly) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatCard("Matérias", "${activeSubjects.size}", Modifier.weight(1f), onSubjects)
                                    StatCard("Sessões", "$totalSessionsCount", Modifier.weight(1f), onHistory)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatCard("Hábitos", "${completed.size}/${habits.size}", Modifier.weight(1f), onHabits)
                                    StatCard("Revisões", "$dueCards", Modifier.weight(1f), onCards)
                                }
                                StatCard("Metas ativas", "$activeGoalsCount", Modifier.fillMaxWidth(), onGoals)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    StatCard("Matérias", "${activeSubjects.size}", Modifier.weight(1f), onSubjects)
                                    StatCard("Sessões", "$totalSessionsCount", Modifier.weight(1f), onHistory)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatCard("Hábitos", "${completed.size}/${habits.size}", Modifier.weight(1f), onHabits)
                                    StatCard("Revisões", "$dueCards", Modifier.weight(1f), onCards)
                                    StatCard("Metas", "$activeGoalsCount", Modifier.weight(1f), onGoals)
                                }
                            }
                        }
                    }
                }
                "charts_bar_30", StudyDashboardWidgets.CHARTS_HEATMAP -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Tempo de estudo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                        Text("Últimos 30 dias", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        StudyBarChart(sessionAggregates.bar30Values, sessionAggregates.bar30Peak, 30)
                    }
                }
                "charts_month_heatmap" -> {
                    StudyMonthHeatmap(
                        monthTitle = sessionAggregates.monthTitle,
                        totals = sessionAggregates.monthTotals,
                        peak = sessionAggregates.monthPeak,
                        weeks = sessionAggregates.monthWeeks
                    )
                }
                "charts_week_insights" -> {
                    WeekInsightsCard(
                        sessions = sessions,
                        subjects = subjects,
                        aggregates = sessionAggregates,
                        dailyGoalMinutesOverride = dailyGoalMinutes
                    )
                }
                StudyDashboardWidgets.RECENT_SESSIONS -> {
                    val topSessions = remember(sessions) { sessions.take(4) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Últimas sessões", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = onHistory) { Text("Ver histórico", maxLines = 1, softWrap = false) }
                        }
                        if (topSessions.isEmpty()) Text("Suas sessões salvas vão aparecer aqui.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        topSessions.forEach { s ->
                            val subjName = s.subjectId?.let { subjectsById[it]?.name } ?: "Estudo"
                            SessionRow(s, subjects, subjectNameOverride = subjName)
                        }
                    }
                }
            }
        }
    }
}

private fun safeGoalColor(raw: Color, surface: Color, fallback: Color): Color {
    if (contrastRatio(raw, surface) >= 4.5) return raw
    val hsv = colorToHsv(raw)
    val isLightSurface = relativeLuminance(surface) > 0.35
    val candidate = hsvToColor(hsv.h, hsv.s, if (isLightSurface) 0.35f else 0.90f)
    return if (contrastRatio(candidate, surface) >= 4.5) candidate else fallback
}

@Composable
private fun PinnedGoalsDashboardSection(
    snapshots: List<GoalProgressSnapshot>,
    onOpenGoals: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.PushPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Metas fixadas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onOpenGoals) { Text("Ver todas", maxLines = 1, softWrap = false) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val cardSurface = MaterialTheme.colorScheme.surface
        snapshots.forEach { snap ->
            val accent = safeGoalColor(Color(snap.goal.color.toInt()), cardSurface, MaterialTheme.colorScheme.primary)
            val pct = (snap.progressFraction * 100f).toInt().coerceIn(0, 100)
            StudyCard(
                onClick = onOpenGoals,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding())) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(accent))
                        Text(
                            snap.goal.title,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            snap.metricType.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${snap.displayCurrent} / ${snap.displayTarget}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "$pct%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = { snap.progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    val metaInfo = listOfNotNull(
                        snap.paceStatusLabel?.takeIf { it.isNotBlank() },
                        snap.requiredDailyPaceLabel?.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (metaInfo.isNotEmpty()) {
                        Text(
                            metaInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayDiaryQuickCard(
    entries: List<JournalEntry>,
    onQuickSaveMood: (JournalEntry) -> Unit,
    onOpenDiary: () -> Unit
) {
    val todayKey = remember { DiaryAnalytics.dayKeyFromEpoch(System.currentTimeMillis()) }
    val todayEntry = remember(entries, todayKey) { entries.firstOrNull { it.dayKey == todayKey } }
    StudyCard(
        onClick = onOpenDiary,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Check-in do Diário", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    val subtitle = if (todayEntry != null) {
                        "${todayEntry.mood.ifBlank { "🙂" }} Energia ${todayEntry.energy}/5 · Foco ${todayEntry.focus}/5"
                    } else {
                        "Registre seu humor em 1 toque ou escreva sobre o dia"
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onOpenDiary) {
                    Text(if (todayEntry != null) "Editar" else "Abrir", maxLines = 1, softWrap = false)
                    Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp))
                }
            }
            if (!todayEntry?.title.isNullOrBlank() || !todayEntry?.body.isNullOrBlank()) {
                val preview = listOfNotNull(
                    todayEntry?.title?.trim()?.takeIf { it.isNotEmpty() },
                    todayEntry?.body?.trim()?.replace("\n", " ")?.takeIf { it.isNotEmpty() }
                ).joinToString(" — ")
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiaryAnalytics.moodPresets.forEach { (emoji, label) ->
                    val selected = todayEntry?.mood == emoji
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val updated = (todayEntry ?: JournalEntry(dayKey = todayKey)).copy(mood = emoji)
                            onQuickSaveMood(updated)
                        },
                        label = {
                            Text(
                                "$emoji $label",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    StudyCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding())) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun DashboardQuickAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    StudyCard(onClick = onClick, modifier = modifier.heightIn(min = 76.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 76.dp)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun StudyBarChart(values: List<Long>, peak: Long, days: Int) {
    val axisColor = MaterialTheme.colorScheme.outline
    val barColor = MaterialTheme.colorScheme.primary
    StudyCard(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                val step = size.width / values.size.coerceAtLeast(1)
                val barWidth = (step * .58f).coerceAtLeast(2f)
                drawLine(axisColor, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
                values.forEachIndexed { index, value ->
                    val barHeight = if (value == 0L) 0f else (size.height * .88f * value.toFloat() / peak.toFloat()).coerceAtLeast(3f)
                    if (barHeight > 0f) {
                        drawRoundRect(
                            barColor,
                            Offset(index * step + (step - barWidth) / 2f, size.height - barHeight),
                            Size(barWidth, barHeight),
                            CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${days} dias atrás", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text("Hoje", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StudyMonthHeatmap(
    monthTitle: String,
    totals: Map<Int, Long>,
    peak: Long,
    weeks: List<List<Int>>
) {
    val monthStats = remember(monthTitle, totals, peak, weeks) {
        MonthHeatmapData(monthTitle = monthTitle, totals = totals, peak = peak, weeks = weeks)
    }
    val baseSurface = MaterialTheme.colorScheme.surface
    StudyCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding())) {
            Text("Atividade em ${monthStats.monthTitle}", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("S", "T", "Q", "Q", "S", "S", "D").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
            monthStats.weeks.forEach { week ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { day ->
                        if (day == 0) {
                            Spacer(Modifier.weight(1f).heightIn(min = 27.dp))
                        } else {
                            val amount = monthStats.totals[day] ?: 0L
                            val cellColor = if (amount <= 0) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = (.18f + .68f * amount.toFloat() / monthStats.peak.toFloat()).coerceIn(.18f, .86f))
                            }
                            val renderedCell = if (amount <= 0) cellColor else cellColor.compositeOver(baseSurface)
                            val dayTextColor = if (amount <= 0) {
                                MaterialTheme.colorScheme.onSurface
                            } else if (contrastRatio(MaterialTheme.colorScheme.onPrimary, renderedCell) >= contrastRatio(MaterialTheme.colorScheme.onSurface, renderedCell)) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                            Box(
                                Modifier.weight(1f).heightIn(min = 27.dp).clip(RoundedCornerShape(6.dp)).background(cellColor).padding(vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(day.toString(), color = dayTextColor, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    repeat((7 - week.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f).heightIn(min = 27.dp)) }
                }
            }
        }
    }
}

private data class MonthHeatmapData(
    val monthTitle: String,
    val totals: Map<Int, Long>,
    val peak: Long,
    val weeks: List<List<Int>>
)

private fun localDateKey(date: LocalDate): Long =
    date.year * 10000L + date.monthValue * 100L + date.dayOfMonth

private fun epochDateKey(time: Long, zone: ZoneId): Long =
    localDateKey(Instant.ofEpochMilli(time).atZone(zone).toLocalDate())
