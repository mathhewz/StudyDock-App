@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// ---------- datas & cores ----------

fun taskDateLabel(time: Long): String {
    val diff = taskDayDiff(time)
    return when {
        diff == 0 -> "Hoje"
        diff == 1 -> "Amanhã"
        diff == -1 -> "Ontem"
        diff in 2..6 -> StudyDateFormats.formatEpoch(time, StudyDateFormats.WEEKDAY_FULL_PT).replaceFirstChar { it.uppercase() }
        else -> StudyDateFormats.formatEpoch(time, StudyDateFormats.DATE_D_MMM_PT)
    }
}

fun taskDateLabel(time: Long, hasDueDate: Boolean): String =
    if (!hasDueDate) "Inbox" else taskDateLabel(time)

internal fun priorityColor(priority: Int, fallback: Color): Color {
    val isDarkFallback = relativeLuminance(fallback) > 0.25
    return when (priority) {
        1 -> if (isDarkFallback) Color(0xFFFF8A80) else Color(0xFFC62828)
        2 -> if (isDarkFallback) Color(0xFFFBBF24) else Color(0xFFB45309)
        3 -> if (isDarkFallback) Color(0xFF60A5FA) else Color(0xFF1D4ED8)
        else -> fallback
    }
}

@Composable
internal fun themePriorityColor(
    priority: Int,
    fallback: Color = MaterialTheme.colorScheme.onSurfaceVariant
): Color {
    val semantic = LocalStudySemanticColors.current
    return when (priority) {
        1 -> MaterialTheme.colorScheme.error
        2 -> semantic.warning
        3 -> semantic.info
        else -> fallback
    }
}

/**
 * Adjusts an arbitrary foreground/accent color (e.g. subject or tag tint) until its WCAG
 * contrast ratio against [surface] reaches at least [minContrast].
 */
internal fun accessibleAccentOnSurface(
    rawColor: Color,
    surface: Color,
    minContrast: Double = 4.5
): Color {
    if (contrastRatio(rawColor, surface) >= minContrast) return rawColor
    val surfaceIsDark = relativeLuminance(surface) < 0.25
    var (h, s, v) = colorToHsv(rawColor)
    if (s < 0.06f) s = 0f
    var candidate = rawColor
    var step = 0
    while (contrastRatio(candidate, surface) < minContrast && step < 40) {
        if (surfaceIsDark) {
            if (v < 0.96f) v = (v + 0.05f).coerceAtMost(0.96f)
            else s = (s - 0.06f).coerceAtLeast(0f)
        } else {
            if (v > 0.14f) v = (v - 0.05f).coerceAtLeast(0.14f)
            else break
        }
        candidate = hsvToColor(h, s, v)
        step++
    }
    return candidate
}

/**
 * Picks high-contrast dark or white ink over [background] using WCAG contrast math.
 */
internal fun accessibleOnColor(background: Color): Color {
    val darkInk = Color(0xFF141318)
    return if (contrastRatio(background, Color.White) >= contrastRatio(background, darkInk)) {
        Color.White
    } else {
        darkInk
    }
}

// ---------- adicionar rápido (linguagem natural -> delega para TaskQuickParser) ----------

data class QuickTask(
    val title: String,
    val dueAt: Long?,
    val priority: Int?,
    val subjectId: Long?,
    val recurrence: String?,
    val hasDueDate: Boolean = true,
    val afterCompletion: Boolean = false,
    val estimatedMinutes: Int = 0,
    val tags: List<String> = emptyList(),
    val bookId: Long? = null
)

/**
 * Faz o parse rápido de texto em linguagem natural delegando para [TaskQuickParser].
 * Mantida pública para compatibilidade com chamadores existentes.
 */
fun parseQuickTask(input: String, subjects: List<Subject>): QuickTask {
    val parsed = TaskQuickParser.parse(input, subjects)
    return QuickTask(
        title = parsed.title,
        dueAt = parsed.dueAt,
        priority = parsed.priority,
        subjectId = parsed.subjectId,
        recurrence = parsed.recurrence,
        hasDueDate = parsed.hasDueDate,
        afterCompletion = parsed.afterCompletion,
        estimatedMinutes = parsed.estimatedMinutes,
        tags = parsed.tags,
        bookId = parsed.bookId
    )
}

fun QuickTask.toStudyTask(defaultSubject: Long? = null, noteId: Long? = null): StudyTask =
    TaskQuickParseResult(
        title = title,
        dueAt = dueAt,
        hasDueDate = hasDueDate,
        priority = priority,
        subjectId = subjectId,
        recurrence = recurrence,
        afterCompletion = afterCompletion,
        estimatedMinutes = estimatedMinutes,
        tags = tags,
        bookId = bookId
    ).toStudyTask(defaultSubject = defaultSubject, noteId = noteId)

fun quickTaskFrom(text: String, subjects: List<Subject>, noteId: Long?): StudyTask =
    TaskQuickParser.parse(text, subjects).toStudyTask(noteId = noteId)

// ---------- banner auxiliar ----------

private data class TodoBannerState(
    val message: String,
    val primaryActionLabel: String? = null,
    val onPrimaryAction: (() -> Unit)? = null,
    val secondaryActionLabel: String? = null,
    val onSecondaryAction: (() -> Unit)? = null
)

// ---------- tela de tarefas ----------

@Composable
fun TodoPage(
    tasks: List<StudyTask>,
    subjects: List<Subject>,
    notes: List<Note>,
    onSave: (StudyTask) -> Unit,
    onToggle: (StudyTask) -> Unit,
    onDelete: (StudyTask) -> Unit,
    onOpenNote: (Long) -> Unit,
    onStudy: (Long) -> Unit,
    sessions: List<StudySession> = emptyList(),
    onStudyTask: ((StudyTask) -> Unit)? = null,
    books: List<Book> = emptyList(),
    dailyGoalMinutes: Int = 180,
    onUpdateSubtasks: ((StudyTask, String) -> Unit)? = null,
    onRescheduleAll: ((List<Long>, Long, Boolean) -> Unit)? = null,
    onDistributeOverdue: ((Map<Long, Long>) -> Unit)? = null,
    onLogQuestionsFromTask: ((subjectId: Long?, topic: String, total: Int, correct: Int, openMistakes: Boolean) -> Unit)? = null,
    onAdvanceBookFromTask: ((bookId: Long, newPage: Int) -> Unit)? = null,
    onOpenFlashcardsForNote: ((Long) -> Unit)? = null,
    onOpenTutorForTask: ((StudyTask) -> Unit)? = null
) {
    var viewMode by rememberSaveable { mutableStateOf(TodoViewMode.LIST) }
    var filter by rememberSaveable { mutableStateOf("HOJE") }
    var sortGroup by rememberSaveable { mutableStateOf(TaskSortGroup.DATE) }
    var kanbanGroupMode by rememberSaveable { mutableStateOf(KanbanGroupMode.STATUS) }
    var subjectFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var quick by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<StudyTask?>(null) }
    var creating by remember { mutableStateOf(false) }

    var questionPromptTask by remember { mutableStateOf<StudyTask?>(null) }
    var bookPromptTask by remember { mutableStateOf<StudyTask?>(null) }
    var bannerState by remember { mutableStateOf<TodoBannerState?>(null) }

    LaunchedEffect(bannerState) {
        if (bannerState != null) {
            kotlinx.coroutines.delay(7_000L)
            bannerState = null
        }
    }

    val activeNotes = remember(notes) { notes.filter { it.trashedAt == null } }
    val noteById = remember(activeNotes) { activeNotes.associateBy { it.id } }
    val subjectById = remember(subjects) { subjects.associateBy { it.id } }
    val bookById = remember(books) { books.associateBy { it.id } }
    val (studiedByTaskId, studiedByTopicLower) = remember(sessions) { buildStudiedSecondsMaps(sessions) }

    var batteryFilter by rememberSaveable { mutableStateOf<com.studydock.app.data.MentalBattery?>(null) }
    val todoKarma = remember(tasks) { com.studydock.app.data.TodoistProEngine.computeTodoKarma(tasks) }
    val subjectNamesById = remember(subjects) { subjects.associate { it.id to it.name } }
    val booleanFilterExpr = if (quick.trimStart().startsWith("?")) quick.trimStart().drop(1).trim() else ""
    val scoped = remember(tasks, subjectFilter, tagFilter, batteryFilter, booleanFilterExpr, subjectNamesById) {
        tasks.filter { task ->
            val matchesSubject = subjectFilter == null || task.subjectId == subjectFilter
            val matchesTag = tagFilter == null || TaskTagPresets.hasTag(task, tagFilter!!)
            val matchesBattery = batteryFilter == null || com.studydock.app.data.TodoistProEngine.inferMentalBattery(task) == batteryFilter
            val matchesBool = booleanFilterExpr.isEmpty() || com.studydock.app.data.TodoistProEngine.matchesBooleanFilter(task, booleanFilterExpr, subjectNamesById)
            matchesSubject && matchesTag && matchesBattery && matchesBool
        }
    }
    val todayLocal = LocalDate.now()
    val openTasks = remember(scoped, todayLocal) { scoped.filter { !it.completed } }
    val overdue = remember(openTasks, todayLocal) { openTasks.filter { it.hasDueDate && taskDayDiff(it.dueAt) < 0 } }
    val todayList = remember(openTasks, todayLocal) { openTasks.filter { it.hasDueDate && taskDayDiff(it.dueAt) == 0 } }
    val upcoming = remember(openTasks, todayLocal) { openTasks.filter { it.hasDueDate && taskDayDiff(it.dueAt) > 0 } }
    val inboxList = remember(openTasks) { openTasks.filter { !it.hasDueDate } }
    val doneList = remember(scoped) { scoped.filter { it.completed } }
    val todayDoneList = remember(scoped, todayLocal) { scoped.filter { it.completed && it.hasDueDate && taskDayDiff(it.dueAt) == 0 } }
    val todayDone = todayDoneList.size
    val todayTotal = todayList.size + todayDone
    val todayRemainingMinutes = remember(todayList) { todayList.sumOf { it.estimatedMinutes } }
    val todayCompletedMinutes = remember(todayDoneList) { todayDoneList.sumOf { it.estimatedMinutes } }
    val todayStudiedMinutes = remember(sessions, subjectFilter) {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        sessions.filter { s ->
            !s.isActive && s.startedAt >= todayStart && (subjectFilter == null || s.subjectId == subjectFilter)
        }.sumOf { it.durationSeconds } / 60L
    }

    val quickFilterTags = remember(tasks) {
        val baseTags = listOf(
            TaskTagPresets.QUESTOES,
            TaskTagPresets.LEITURA,
            TaskTagPresets.TEORIA,
            TaskTagPresets.REVISAO,
            TaskTagPresets.SIMULADO,
            TaskTagPresets.RESUMO
        )
        val extras = tasks.flatMap { TaskTagPresets.parseTags(it.tags) }
            .filter { it !in baseTags }
            .distinct()
            .sorted()
        baseTags + extras
    }

    val tagCounts = remember(tasks, subjectFilter) {
        val subjectScopedOpen = tasks.filter { !it.completed && (subjectFilter == null || it.subjectId == subjectFilter) }
        val counts = mutableMapOf<String, Int>()
        subjectScopedOpen.forEach { t ->
            TaskTagPresets.parseTags(t.tags).forEach { tag ->
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        counts
    }

    val defaultHasDueDateForQuickAdd = viewMode == TodoViewMode.LIST && filter != "INBOX"
    val parsed = remember(quick, subjects) {
        if (quick.isBlank()) null else TaskQuickParser.parse(quick, subjects)
    }

    val submitQuick: () -> Unit = {
        val result = TaskQuickParser.parse(quick, subjects)
        if (result.title.isNotBlank()) {
            onSave(
                result.toStudyTask(
                    defaultSubject = subjectFilter,
                    defaultHasDueDate = defaultHasDueDateForQuickAdd
                )
            )
            quick = ""
        }
    }

    val handleToggleSubtask: (StudyTask, String) -> Unit = { task, subtaskId ->
        val updatedJson = TaskSubtasks.toggle(task.subtasksJson, subtaskId)
        if (onUpdateSubtasks != null) {
            onUpdateSubtasks(task, updatedJson)
        } else {
            onSave(task.copy(subtasksJson = updatedJson))
        }
    }

    val handleQuickSchedule: (StudyTask, Long, Boolean) -> Unit = { task, newDueAt, hasDueDate ->
        onSave(task.copy(dueAt = newDueAt, hasDueDate = hasDueDate))
    }

    val handleRescheduleBatch: (List<StudyTask>, Long, Boolean, String) -> Unit = { targetTasks, newDue, hasDue, feedback ->
        val ids = targetTasks.map { it.id }.filter { it != 0L }
        if (ids.isNotEmpty()) {
            if (onRescheduleAll != null) {
                onRescheduleAll(ids, newDue, hasDue)
            } else {
                targetTasks.forEach { onSave(it.copy(dueAt = newDue, hasDueDate = hasDue)) }
            }
            bannerState = TodoBannerState(message = feedback)
        }
    }

    val handleDistributeOverdue: () -> Unit = {
        val map = computeBalancedReschedule(
            overdue = overdue,
            allOpen = tasks.filter { !it.completed },
            dailyTargetMinutes = if (dailyGoalMinutes > 0) dailyGoalMinutes else 180
        )
        if (map.isNotEmpty()) {
            if (onDistributeOverdue != null) {
                onDistributeOverdue(map)
            } else {
                val byId = overdue.associateBy { it.id }
                map.forEach { (id, newDue) ->
                    byId[id]?.let { onSave(it.copy(dueAt = newDue, hasDueDate = true)) }
                }
            }
            bannerState = TodoBannerState(message = "${map.size} tarefa(s) redistribuída(s) nos próximos dias.")
        }
    }

    val handleToggleTask: (StudyTask) -> Unit = { task ->
        val completingNow = !task.completed
        onToggle(task)
        if (completingNow) {
            val hasNote = task.noteId != null && noteById.containsKey(task.noteId)
            val canLogQuestions = isQuestionTask(task) && onLogQuestionsFromTask != null
            val canAdvanceBook = (
                (task.bookId != null && bookById.containsKey(task.bookId)) ||
                    (isReadingTask(task) && books.isNotEmpty())
                ) && onAdvanceBookFromTask != null

            when {
                canLogQuestions -> questionPromptTask = task
                canAdvanceBook -> bookPromptTask = task
                hasNote -> {
                    val noteId = task.noteId!!
                    val noteTitle = noteById[noteId]?.title ?: "Nota"
                    bannerState = TodoBannerState(
                        message = "Concluída! Nota: $noteTitle",
                        primaryActionLabel = if (onOpenFlashcardsForNote != null) "Flashcards" else "Abrir nota",
                        onPrimaryAction = {
                            bannerState = null
                            if (onOpenFlashcardsForNote != null) onOpenFlashcardsForNote(noteId)
                            else onOpenNote(noteId)
                        },
                        secondaryActionLabel = if (onOpenFlashcardsForNote != null) "Abrir nota" else null,
                        onSecondaryAction = if (onOpenFlashcardsForNote != null) {
                            {
                                bannerState = null
                                onOpenNote(noteId)
                            }
                        } else null
                    )
                }
            }
        }
    }

    val startTaskStudy: ((StudyTask) -> (() -> Unit)?) = { task ->
        when {
            onStudyTask != null -> ({ onStudyTask(task) })
            task.subjectId != null -> {
                val id = task.subjectId
                ({ onStudy(id) })
            }
            else -> null
        }
    }

    val sections = remember(filter, sortGroup, overdue, todayList, upcoming, inboxList, doneList, subjectById) {
        buildListSections(
            filter = filter,
            sortGroup = sortGroup,
            overdue = overdue,
            todayList = todayList,
            upcoming = upcoming,
            inboxList = inboxList,
            doneList = doneList,
            subjectById = subjectById
        )
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            val headerSubtitle = buildString {
                append("${openTasks.size} abertas · ${overdue.size} atrasadas")
                if (inboxList.isNotEmpty()) append(" · ${inboxList.size} inbox")
            }
            StudyPageHeader(
                title = "Tarefas",
                subtitle = headerSubtitle,
                showGlobalSearch = true,
                actions = {
                    FilledTonalIconButton(
                        onClick = { creating = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Add, "Nova tarefa detalhada")
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        "⚡ Karma $todoKarma",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                if (todayList.isNotEmpty() || overdue.isNotEmpty()) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            val replanned = com.studydock.app.data.TodoistProEngine.recalculateDaySchedule(todayList + overdue, emptyList())
                            replanned.forEach { onSave(it) }
                            bannerState = TodoBannerState("Rota do dia recalculada (${replanned.size} tarefas ajustadas).")
                        },
                        label = { Text("🔄 Recalcular Dia") }
                    )
                }
                FilterChip(
                    selected = batteryFilter == null,
                    onClick = { batteryFilter = null },
                    label = { Text("Todas") }
                )
                com.studydock.app.data.MentalBattery.entries.forEach { b ->
                    FilterChip(
                        selected = batteryFilter == b,
                        onClick = { batteryFilter = if (batteryFilter == b) null else b },
                        label = { Text("${b.emoji} ${b.label}") }
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                TodoViewMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = viewMode == mode,
                        onClick = { viewMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index, TodoViewMode.entries.size),
                        icon = {
                            Icon(
                                imageVector = when (mode) {
                                    TodoViewMode.LIST -> Icons.AutoMirrored.Filled.FormatListBulleted
                                    TodoViewMode.KANBAN -> Icons.Default.ViewWeek
                                    TodoViewMode.MATRIX -> Icons.Default.GridView
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text(
                            mode.label,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            OutlinedTextField(
                value = quick,
                onValueChange = { quick = it },
                placeholder = {
                    Text(
                        "Ex.: Resolver lista amanhã p1 ~45m @questoes #Matéria",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                trailingIcon = {
                    if (quick.isNotBlank()) {
                        IconButton(onClick = submitQuick) { Icon(Icons.AutoMirrored.Filled.Send, "Adicionar tarefa") }
                    }
                },
                singleLine = true,
                shape = studyControlShape(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitQuick() }),
                modifier = Modifier.fillMaxWidth()
            )

            parsed?.let { p ->
                QuickTaskPreviewStrip(
                    parsed = p,
                    subjects = subjects,
                    defaultSubjectId = subjectFilter,
                    defaultHasDueDate = defaultHasDueDateForQuickAdd
                )
            }

            if (viewMode == TodoViewMode.LIST) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "HOJE" to "Hoje (${overdue.size + todayList.size})",
                        "PROX" to "Próximas (${upcoming.size})",
                        "INBOX" to "Inbox (${inboxList.size})",
                        "TODAS" to "Todas (${openTasks.size})",
                        "FEITAS" to "Concluídas (${doneList.size})"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = filter == key,
                            onClick = { filter = key },
                            label = { Text(label) }
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 88.dp)
                ) {
                    item(key = "filter-controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.SwapVert,
                                    contentDescription = "Ordenar",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TaskSortGroup.entries.forEach { mode ->
                                    FilterChip(
                                        selected = sortGroup == mode,
                                        onClick = { sortGroup = mode },
                                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                                if (subjectFilter != null || tagFilter != null) {
                                    AssistChip(
                                        onClick = { subjectFilter = null; tagFilter = null },
                                        label = { Text("Limpar filtros", style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = { Icon(Icons.Default.Clear, null, Modifier.size(14.dp)) }
                                    )
                                }
                            }

                            TagAndSubjectFilters(
                                subjects = subjects,
                                subjectFilter = subjectFilter,
                                onSubjectFilter = { subjectFilter = it },
                                quickFilterTags = quickFilterTags,
                                tagFilter = tagFilter,
                                tagCounts = tagCounts,
                                onTagFilter = { tagFilter = it }
                            )
                        }
                    }

                    if ((filter == "HOJE" || filter == "TODAS") && todayTotal > 0) {
                        item(key = "daily-workload") {
                            Spacer(Modifier.height(6.dp))
                            DailyWorkloadCard(
                                todayDoneCount = todayDone,
                                todayTotalCount = todayTotal,
                                todayRemainingMinutes = todayRemainingMinutes,
                                todayCompletedMinutes = todayCompletedMinutes,
                                todayStudiedMinutes = todayStudiedMinutes,
                                dailyGoalMinutes = dailyGoalMinutes,
                                onDistributeOverdue = if (overdue.isNotEmpty()) handleDistributeOverdue else null
                            )
                        }
                    }

                    if ((filter == "HOJE" || filter == "TODAS") && overdue.isNotEmpty()) {
                        item(key = "overdue-reschedule") {
                            Spacer(Modifier.height(2.dp))
                            OverdueRescheduleBar(
                                overdueCount = overdue.size,
                                onMoveToday = {
                                    handleRescheduleBatch(
                                        overdue,
                                        noonTimestamp(offsetDays = 0),
                                        true,
                                        "${overdue.size} atrasada(s) movida(s) para Hoje."
                                    )
                                },
                                onMoveTomorrow = {
                                    handleRescheduleBatch(
                                        overdue,
                                        noonTimestamp(offsetDays = 1),
                                        true,
                                        "${overdue.size} atrasada(s) movida(s) para Amanhã."
                                    )
                                },
                                onMoveWeekend = {
                                    handleRescheduleBatch(
                                        overdue,
                                        nextWeekendNoon(),
                                        true,
                                        "${overdue.size} atrasada(s) movida(s) para o fim de semana."
                                    )
                                },
                                onDistributeBalanced = handleDistributeOverdue
                            )
                        }
                    }

                    val hasAny = sections.any { it.second.isNotEmpty() }
                    if (!hasAny) {
                        item(key = "empty-list") {
                            Text(
                                text = when (filter) {
                                    "HOJE" -> "Nada pendente para hoje. Use o campo acima para adicionar."
                                    "PROX" -> "Nenhuma tarefa futura."
                                    "INBOX" -> "Inbox vazio. Digite \"sem data\" no campo acima para capturar ideias sem prazo."
                                    "TODAS" -> "Você ainda não tem tarefas abertas."
                                    else -> "Nenhuma tarefa concluída ainda."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 20.dp)
                            )
                        }
                    } else {
                        sections.forEachIndexed { sectionIndex, (label, list) ->
                            if (list.isNotEmpty()) {
                                item(key = "header-$filter-$sortGroup-$sectionIndex-$label") {
                                    val sectionMinutes = list.sumOf { it.estimatedMinutes }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "$label · ${list.size}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (label == "Atrasadas") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (sectionMinutes > 0) {
                                            Text(
                                                "~${formatMinutesCompact(sectionMinutes)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                items(list, key = { it.id }) { task ->
                                    val studiedMinutes = remember(studiedByTaskId, studiedByTopicLower, task.id, task.title) {
                                        lookupStudiedMinutes(task, studiedByTaskId, studiedByTopicLower)
                                    }
                                    TaskRow(
                                        task = task,
                                        subject = task.subjectId?.let { subjectById[it] },
                                        note = task.noteId?.let { noteById[it] },
                                        book = task.bookId?.let { bookById[it] },
                                        studiedMinutes = studiedMinutes,
                                        onToggle = { handleToggleTask(task) },
                                        onEdit = { editing = task },
                                        onStudy = startTaskStudy(task),
                                        onOpenNote = task.noteId?.let { id ->
                                            if (noteById.containsKey(id)) ({ onOpenNote(id) }) else null
                                        },
                                        onToggleSubtask = { subId -> handleToggleSubtask(task, subId) },
                                        onQuickSchedule = { due, hasDue -> handleQuickSchedule(task, due, hasDue) },
                                        onQuickPriority = { newP -> onSave(task.copy(priority = newP)) },
                                        onDelete = { onDelete(task) },
                                        onOpenTutorForTask = onOpenTutorForTask?.let { fn -> { fn(task) } }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(6.dp))
                TagAndSubjectFilters(
                    subjects = subjects,
                    subjectFilter = subjectFilter,
                    onSubjectFilter = { subjectFilter = it },
                    quickFilterTags = quickFilterTags,
                    tagFilter = tagFilter,
                    tagCounts = tagCounts,
                    onTagFilter = { tagFilter = it }
                )

                if (viewMode == TodoViewMode.KANBAN) {
                    TodoKanbanView(
                        scopedTasks = scoped,
                        subjects = subjects,
                        sessions = sessions,
                        groupMode = kanbanGroupMode,
                        onGroupModeChange = { kanbanGroupMode = it },
                        onToggle = handleToggleTask,
                        onEdit = { editing = it },
                        onQuickMove = handleQuickSchedule,
                        onMoveAllOverdueToday = { overdueColumnTasks ->
                            handleRescheduleBatch(
                                overdueColumnTasks,
                                noonTimestamp(offsetDays = 0),
                                true,
                                "${overdueColumnTasks.size} tarefa(s) movida(s) para Hoje."
                            )
                        },
                        resolveStudyAction = startTaskStudy,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    TodoEisenhowerMatrixView(
                        openTasks = openTasks,
                        subjects = subjects,
                        sessions = sessions,
                        onToggle = handleToggleTask,
                        onEdit = { editing = it },
                        onChangePriority = { task, newP -> onSave(task.copy(priority = newP)) },
                        resolveStudyAction = startTaskStudy,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = bannerState != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            bannerState?.let { banner ->
                TodoFloatingBanner(
                    message = banner.message,
                    primaryActionLabel = banner.primaryActionLabel,
                    onPrimaryAction = banner.onPrimaryAction,
                    secondaryActionLabel = banner.secondaryActionLabel,
                    onSecondaryAction = banner.onSecondaryAction,
                    onDismiss = { bannerState = null }
                )
            }
        }
    }

    editing?.let { task ->
        TaskEditDialog(
            task = task,
            subjects = subjects,
            notes = activeNotes,
            books = books,
            defaultSubject = null,
            defaultHasDueDate = task.hasDueDate,
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(task); editing = null },
            onOpenTutorForTask = onOpenTutorForTask,
            onPromoteSubtask = onSave
        )
    }

    if (creating) {
        TaskEditDialog(
            task = null,
            subjects = subjects,
            notes = activeNotes,
            books = books,
            defaultSubject = subjectFilter,
            defaultHasDueDate = defaultHasDueDateForQuickAdd,
            onDismiss = { creating = false },
            onSave = { onSave(it); creating = false },
            onDelete = null,
            onOpenTutorForTask = onOpenTutorForTask,
            onPromoteSubtask = onSave
        )
    }

    questionPromptTask?.let { task ->
        QuestionLogPromptDialog(
            task = task,
            subjects = subjects,
            linkedNote = task.noteId?.let { noteById[it] },
            onDismiss = { questionPromptTask = null },
            onSubmit = { subjectId, topic, total, correct, openMistakes ->
                questionPromptTask = null
                onLogQuestionsFromTask?.invoke(subjectId, topic, total, correct, openMistakes)
                bannerState = TodoBannerState(message = "Questões registradas ($correct/$total)!")
            },
            onOpenFlashcardsForNote = onOpenFlashcardsForNote
        )
    }

    bookPromptTask?.let { task ->
        BookPagePromptDialog(
            task = task,
            books = books,
            onDismiss = { bookPromptTask = null },
            onAdvanceBook = { bookId, newPage ->
                bookPromptTask = null
                onAdvanceBookFromTask?.invoke(bookId, newPage)
                bannerState = TodoBannerState(message = "Progresso de leitura atualizado para pág. $newPage!")
            }
        )
    }
}

@Composable
private fun TagAndSubjectFilters(
    subjects: List<Subject>,
    subjectFilter: Long?,
    onSubjectFilter: (Long?) -> Unit,
    quickFilterTags: List<String>,
    tagFilter: String?,
    tagCounts: Map<String, Int>,
    onTagFilter: (String?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        quickFilterTags.forEach { tag ->
            val count = tagCounts[tag] ?: 0
            val label = if (count > 0) "$tag ($count)" else tag
            FilterChip(
                selected = tagFilter == tag,
                onClick = { onTagFilter(if (tagFilter == tag) null else tag) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    val visibleSubjects = remember(subjects, subjectFilter) {
        subjects.filter { !it.archived || it.id == subjectFilter }
    }
    if (visibleSubjects.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = subjectFilter == null,
                onClick = { onSubjectFilter(null) },
                label = { Text("Todas as matérias") }
            )
            visibleSubjects.forEach { subject ->
                FilterChip(
                    selected = subjectFilter == subject.id,
                    onClick = { onSubjectFilter(if (subjectFilter == subject.id) null else subject.id) },
                    label = {
                        Text(
                            subject.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(subject.color.toInt())))
                    }
                )
            }
        }
    }
}

private fun buildListSections(
    filter: String,
    sortGroup: TaskSortGroup,
    overdue: List<StudyTask>,
    todayList: List<StudyTask>,
    upcoming: List<StudyTask>,
    inboxList: List<StudyTask>,
    doneList: List<StudyTask>,
    subjectById: Map<Long, Subject>
): List<Pair<String, List<StudyTask>>> {
    val dateOrder = compareBy<StudyTask>({ it.priority }, { it.dueAt }, { it.createdAt })
    val inboxOrder = compareBy<StudyTask>({ it.priority }, { it.createdAt })

    if (sortGroup == TaskSortGroup.DATE) {
        return when (filter) {
            "HOJE" -> listOf(
                "Atrasadas" to overdue.sortedWith(dateOrder),
                "Hoje" to todayList.sortedWith(dateOrder)
            )
            "PROX" -> upcoming
                .groupBy { taskDayDiff(it.dueAt) }
                .toSortedMap()
                .map { (_, list) -> taskDateLabel(list.first().dueAt) to list.sortedWith(dateOrder) }
            "INBOX" -> listOf("Inbox · Sem data" to inboxList.sortedWith(inboxOrder))
            "TODAS" -> listOf(
                "Atrasadas" to overdue.sortedWith(dateOrder),
                "Hoje" to todayList.sortedWith(dateOrder)
            ) + upcoming
                .groupBy { taskDayDiff(it.dueAt) }
                .toSortedMap()
                .map { (_, list) -> taskDateLabel(list.first().dueAt) to list.sortedWith(dateOrder) } +
                listOf("Inbox · Sem data" to inboxList.sortedWith(inboxOrder))
            else -> listOf("Concluídas" to doneList.sortedByDescending { it.completedAt ?: it.dueAt })
        }
    }

    val pool = when (filter) {
        "HOJE" -> overdue + todayList
        "PROX" -> upcoming
        "INBOX" -> inboxList
        "TODAS" -> overdue + todayList + upcoming + inboxList
        else -> doneList
    }
    if (pool.isEmpty()) return emptyList()

    return when (sortGroup) {
        TaskSortGroup.PRIORITY -> {
            val withinOrder = compareBy<StudyTask>({ !it.hasDueDate }, { it.dueAt }, { it.createdAt })
            (1..4).mapNotNull { p ->
                val items = pool.filter {
                    if (p == 4) it.priority !in 1..3 else it.priority == p
                }.sortedWith(withinOrder)
                if (items.isEmpty()) null else prioritySectionTitle(p) to items
            }
        }
        TaskSortGroup.SUBJECT -> {
            val withinOrder = compareBy<StudyTask>({ it.priority }, { !it.hasDueDate }, { it.dueAt }, { it.createdAt })
            pool.groupBy { it.subjectId }
                .entries
                .sortedWith(compareBy<Map.Entry<Long?, List<StudyTask>>>(
                    { it.key == null },
                    { subjectById[it.key]?.name?.lowercase(Locale.ROOT) ?: "~" }
                ))
                .map { (subjId, items) ->
                    val name = subjId?.let { subjectById[it]?.name } ?: "Geral"
                    name to items.sortedWith(withinOrder)
                }
        }
        TaskSortGroup.DATE -> emptyList()
    }
}
