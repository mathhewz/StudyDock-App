package com.studydock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studydock.app.StudyViewModel
import com.studydock.app.data.CalendarEvent
import com.studydock.app.data.Note
import com.studydock.app.data.StudySession
import com.studydock.app.data.StudyTask
import com.studydock.app.data.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CAL_PT_BR_LOCALE: Locale = Locale("pt", "BR")
private val CAL_MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", CAL_PT_BR_LOCALE)
private val CAL_EVENT_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", CAL_PT_BR_LOCALE)
private val CAL_SESSION_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", CAL_PT_BR_LOCALE)
private val CAL_FULL_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", CAL_PT_BR_LOCALE)

private data class CalendarDayDetailsState(
    val reviewLines: List<String> = emptyList(),
    val timerLines: List<String> = emptyList(),
    val readingLines: List<String> = emptyList(),
    val plannedTasks: List<StudyTask> = emptyList(),
    val completedLines: List<String> = emptyList(),
    val dayNotes: List<Note> = emptyList(),
    val diaryLines: List<String> = emptyList(),
    val workoutLines: List<String> = emptyList(),
    val mealLines: List<String> = emptyList(),
    val habitLines: List<String> = emptyList()
)

@Composable
internal fun CalendarAgendaPage(
    vm: StudyViewModel,
    onStartStudy: (subjectId: Long?, topic: String, noteId: Long?, taskId: Long?) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNote: (Long) -> Unit
) {
    var agendaSubTab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
            SegmentedButton(
                selected = agendaSubTab == 0,
                onClick = { agendaSubTab = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.AutoMirrored.Filled.Assignment, null, Modifier.size(16.dp)) }
            ) { Text("Planejador", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            SegmentedButton(
                selected = agendaSubTab == 1,
                onClick = { agendaSubTab = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) }
            ) { Text("Calendário", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        }

        if (agendaSubTab == 0) {
            PlannerSubTab(vm = vm, onStartStudy = onStartStudy)
        } else {
            CalendarSubTab(vm = vm, onOpenHistory = onOpenHistory, onOpenNote = onOpenNote)
        }
    }
}

@Composable
private fun PlannerSubTab(
    vm: StudyViewModel,
    onStartStudy: (subjectId: Long?, topic: String, noteId: Long?, taskId: Long?) -> Unit
) {
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val subjectItems by vm.subjectItems.collectAsStateWithLifecycle()

    AcademicPlannerPage(
        subjects = subjects,
        sessions = sessions,
        tasks = tasks,
        events = events,
        subjectItems = subjectItems,
        onStartStudy = { subjectId, topic -> onStartStudy(subjectId, topic, null, null) },
        onUpdateSubject = vm::updateSubject,
        onSaveTask = vm::saveTask,
        onToggleTask = vm::toggleTask,
        onDeleteTask = vm::deleteTask,
        onAddEvent = vm::addEvent,
        onDeleteEvent = vm::deleteEvent,
        onToggleSubjectItem = { item -> vm.updateSubjectItem(item.copy(completed = !item.completed)) },
        onStartTask = { task -> onStartStudy(task.subjectId, task.title, task.noteId, task.id) },
        onRescheduleTasks = vm::rescheduleTasks,
        onDistributeOverdue = vm::distributeOverdueTasks
    )
}

@Composable
private fun CalendarSubTab(
    vm: StudyViewModel,
    onOpenHistory: () -> Unit,
    onOpenNote: (Long) -> Unit
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()

    CalendarPage(
        sessions = sessions,
        subjects = subjects,
        events = events,
        addEvent = vm::addEvent,
        onHistory = onOpenHistory,
        deleteEvent = vm::deleteEvent
    ) { day ->
        CalendarDayDetails(day = day, vm = vm, onOpenNote = onOpenNote)
    }
}

@Composable
private fun CalendarDayDetails(
    day: Long,
    vm: StudyViewModel,
    onOpenNote: (Long) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val cardReviews by vm.cardReviews.collectAsStateWithLifecycle()
    val activityTimers by vm.activityTimers.collectAsStateWithLifecycle(emptyList())
    val readingLogs by vm.readingLogs.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val journalEntries by vm.journalEntries.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()
    val allHabitLogs by vm.allHabitLogs.collectAsStateWithLifecycle()
    val habits by vm.habits.collectAsStateWithLifecycle()

    var details by remember { mutableStateOf(CalendarDayDetailsState()) }
    LaunchedEffect(
        day, zone, cardReviews, activityTimers, readingLogs, tasks,
        notes, journalEntries, workouts, meals, allHabitLogs, habits
    ) {
        details = withContext(Dispatchers.Default) {
            val dailyReviews = cardReviews.filter { calendarDateKey(it.reviewedAt, zone) == day }
            val reviewLines = if (dailyReviews.isNotEmpty()) {
                listOf("${dailyReviews.size} revisões · ${dailyReviews.distinctBy { it.cardId }.size} cartões")
            } else {
                emptyList()
            }
            val timerLines = activityTimers
                .filter { it.endedAt != null && calendarDateKey(it.startedAt, zone) == day }
                .map { "${it.mode} · ${it.title} · ${formatCalendarHms(it.elapsedMs / 1000)}" }
            val readingLines = readingLogs
                .filter { calendarDateKey(it.loggedAt, zone) == day }
                .groupBy { it.title }
                .map { (title, logs) -> "$title · ${logs.sumOf { it.pages }} páginas" }
            val plannedTasks = tasks.filter {
                it.hasDueDate &&
                    calendarDateKey(it.dueAt, zone) == day &&
                    (!it.completed || (it.completedAt?.let { t -> calendarDateKey(t, zone) } ?: day) > day)
            }
            val completedLines = tasks.filter {
                it.completed &&
                    (it.completedAt?.let { t -> calendarDateKey(t, zone) }
                        ?: if (it.hasDueDate) calendarDateKey(it.dueAt, zone) else null) == day
            }.map { it.title + if (it.completedAt == null) " · data prevista" else "" }
            val dayNotes = notes.filter { calendarDateKey(it.updatedAt, zone) == day }
            val diaryLines = journalEntries.filter { it.dayKey == day }.map { it.mood + " " + it.title + "\n" + it.body }
            val workoutLines = workouts.filter { calendarDateKey(it.startedAt, zone) == day }.map { it.title + "\n" + it.exercises }
            val mealLines = meals.filter { calendarDateKey(it.loggedAt, zone) == day }.map { it.mealType + " · " + it.foods }
            val habitsById = habits.associateBy { it.id }
            val habitLines = allHabitLogs.filter { it.dayKey == day }.map { log ->
                val habit = habitsById[log.habitId]
                val status = when (log.status) {
                    "DONE" -> "Concluído"
                    "SKIPPED" -> "Pulado"
                    "FAILED" -> if (habit?.kind == "LIMIT") "Acima do limite" else "Ocorrência"
                    else -> "Parcial"
                }
                "${habit?.title.orEmpty()} · $status"
            }
            CalendarDayDetailsState(
                reviewLines = reviewLines,
                timerLines = timerLines,
                readingLines = readingLines,
                plannedTasks = plannedTasks,
                completedLines = completedLines,
                dayNotes = dayNotes,
                diaryLines = diaryLines,
                workoutLines = workoutLines,
                mealLines = mealLines,
                habitLines = habitLines
            )
        }
    }

    DaySection("Flashcards", details.reviewLines)
    DaySection("Tempo de leitura e treino", details.timerLines)
    DaySection("Leitura", details.readingLines)
    if (details.plannedTasks.isNotEmpty()) {
        Text("A fazer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        details.plannedTasks.forEach { task ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, Modifier.weight(1f))
                if (!task.completed) TextButton({ vm.toggleTask(task) }) { Text("Concluir hoje") }
                else Text("Concluída depois", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    DaySection("Concluídas", details.completedLines)
    if (details.dayNotes.isNotEmpty()) {
        Text("Notas atualizadas", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        details.dayNotes.forEach { note ->
            Column(Modifier.fillMaxWidth().clickable { onOpenNote(note.id) }.padding(vertical = 6.dp)) {
                Text(note.title, fontWeight = FontWeight.SemiBold)
                Text(note.markdown, maxLines = 3, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    DaySection("Diário", details.diaryLines)
    DaySection("Exercícios", details.workoutLines)
    DaySection("Alimentação", details.mealLines)
    DaySection("Hábitos", details.habitLines)
}

@Composable
private fun CalendarPage(
    sessions: List<StudySession>,
    subjects: List<Subject>,
    events: List<CalendarEvent>,
    addEvent: (String, Long) -> Unit,
    onHistory: () -> Unit,
    deleteEvent: (CalendarEvent) -> Unit = {},
    dayContent: @Composable (Long) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val initialMonth = remember { LocalDate.now(zone).withDayOfMonth(1) }
    var eventToDelete by remember { mutableStateOf<CalendarEvent?>(null) }
    var title by remember { mutableStateOf("") }
    var monthYear by rememberSaveable { mutableIntStateOf(initialMonth.year) }
    var monthValue by rememberSaveable { mutableIntStateOf(initialMonth.monthValue) }
    var selectedDay by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var showDay by rememberSaveable { mutableStateOf(false) }

    val currentMonth = remember(monthYear, monthValue) { LocalDate.of(monthYear, monthValue, 1) }
    val selectedKey = remember(selectedDay, zone) { calendarDateKey(selectedDay, zone) }
    val sessionsByDay = remember(sessions, zone) { sessions.groupBy { calendarDateKey(it.startedAt, zone) } }
    val sessionSecondsByDay = remember(sessionsByDay) {
        sessionsByDay.mapValues { (_, list) -> list.sumOf { it.durationSeconds } }
    }
    val eventDayKeys = remember(events, zone) {
        events.mapTo(HashSet(events.size)) { calendarDateKey(it.at, zone) }
    }
    val days = remember(currentMonth) {
        val offset = currentMonth.dayOfWeek.value - 1
        val firstCell = currentMonth.minusDays(offset.toLong())
        List(42) { idx -> firstCell.plusDays(idx.toLong()) }
    }
    val subjectsById = remember(subjects) { subjects.associateBy { it.id } }
    val monthHeaderLabel = remember(currentMonth) {
        currentMonth.format(CAL_MONTH_FORMATTER)
            .replaceFirstChar { it.uppercase() }
    }
    val selectedDayLabel = remember(selectedDay, zone) { calendarDayLabel(selectedDay, zone) }
    val selectedEvents = remember(events, selectedKey, zone) {
        events.filter { calendarDateKey(it.at, zone) == selectedKey }
    }
    val selectedSessions = remember(sessionsByDay, selectedKey) {
        sessionsByDay[selectedKey].orEmpty().sortedBy { it.startedAt }
    }
    val semanticColors = LocalStudySemanticColors.current
    val baseSurface = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContrastOnSurface = remember(primaryColor, baseSurface) {
        contrastRatio(primaryColor, baseSurface) >= 3.5
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        item(key = "cal_header", contentType = "header") {
            StudyPageHeader(
                title = "Calendário",
                showGlobalSearch = true,
                actions = {
                    TextButton(onClick = onHistory) {
                        Icon(Icons.Default.BarChart, null, modifier = Modifier.size(18.dp))
                        Text(" Histórico", maxLines = 1, softWrap = false)
                    }
                }
            )
        }
        item(key = "cal_month_grid", contentType = "month_grid") {
            StudyCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val prev = currentMonth.minusMonths(1)
                                monthYear = prev.year
                                monthValue = prev.monthValue
                            },
                            modifier = Modifier.size(48.dp)
                        ) { Icon(Icons.Default.ChevronLeft, "Mês anterior") }
                        Text(
                            monthHeaderLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = {
                                val next = currentMonth.plusMonths(1)
                                monthYear = next.year
                                monthValue = next.monthValue
                            },
                            modifier = Modifier.size(48.dp)
                        ) { Icon(Icons.Default.ChevronRight, "Próximo mês") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom").forEach {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        }
                    }
                    days.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            week.forEach { date ->
                                val key = date.year * 10000L + date.monthValue * 100L + date.dayOfMonth
                                val secondsForDay = sessionSecondsByDay[key] ?: 0L
                                val inMonth = date.monthValue == monthValue && date.year == monthYear
                                val isSelected = key == selectedKey
                                val intensity = if (secondsForDay <= 0) 0f else (secondsForDay / 7200f).coerceIn(.18f, .76f)
                                val cellBg = when {
                                    isSelected -> primaryColor
                                    secondsForDay > 0 -> primaryColor.copy(alpha = intensity).compositeOver(baseSurface)
                                    else -> baseSurface
                                }
                                val numberColor = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    secondsForDay > 0 -> if (contrastRatio(MaterialTheme.colorScheme.onPrimary, cellBg) >= contrastRatio(MaterialTheme.colorScheme.onSurface, cellBg)) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                    inMonth -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.outline
                                }
                                val durationColor = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    secondsForDay == 0L -> if (primaryContrastOnSurface) primaryColor else numberColor
                                    contrastRatio(primaryColor, cellBg) >= 3.5 -> primaryColor
                                    else -> numberColor
                                }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else if (secondsForDay > 0) MaterialTheme.colorScheme.primary.copy(alpha = intensity) else Color.Transparent)
                                    .clickable {
                                        selectedDay = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                                        showDay = true
                                    }
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    color = numberColor,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                if (secondsForDay > 0) {
                                    Text(
                                        formatCalendarDuration(secondsForDay),
                                        color = durationColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else if (key in eventDayKeys) {
                                    Box(
                                        Modifier
                                            .padding(top = 2.dp)
                                            .size(5.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(if (isSelected) MaterialTheme.colorScheme.onPrimary else semanticColors.warning)
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
                    Text(" Tempo de estudo", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp))
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(semanticColors.warning))
                    Text(" Compromisso", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        }
        item(key = "cal_day_header", contentType = "day_header") {
            Text(
                "$selectedDayLabel · ${formatCalendarDuration(sessionSecondsByDay[selectedKey] ?: 0L)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
            )
        }
        items(selectedEvents, key = { "ev_${it.id}" }, contentType = { "event" }) { event ->
            val eventTime = remember(event.at, zone) {
                Instant.ofEpochMilli(event.at).atZone(zone).toLocalTime().format(CAL_EVENT_TIME_FORMATTER)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Event, null, tint = semanticColors.warning)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(event.title, fontWeight = FontWeight.Medium)
                    Text(
                        eventTime,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = { eventToDelete = event }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Delete, "Excluir compromisso", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(selectedSessions, key = { "sess_${it.id}" }, contentType = { "session" }) { session ->
            val subjName = session.subjectId?.let { subjectsById[it]?.name } ?: "Estudo"
            SessionRow(session, subjects, subjectNameOverride = subjName)
        }
        if (selectedEvents.isEmpty() && selectedSessions.isEmpty()) {
            item(key = "cal_empty_day", contentType = "empty_day") {
                Text(
                    "Sem sessões ou compromissos neste dia.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
        item(key = "cal_add_event", contentType = "add_event") {
            Column(Modifier.fillMaxWidth()) {
                Text("Adicionar compromisso", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(title, { title = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.weight(1f))
                    FilledIconButton(
                        onClick = {
                            if (title.isNotBlank()) {
                                val dayDate = Instant.ofEpochMilli(selectedDay).atZone(zone).toLocalDate()
                                val at = dayDate.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                                addEvent(title, at)
                                title = ""
                            }
                        },
                        enabled = title.isNotBlank(),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Adicionar compromisso")
                    }
                }
            }
        }
    }
    if (showDay) {
        AlertDialog(
            onDismissRequest = { showDay = false },
            title = { Text(selectedDayLabel) },
            text = {
                key(selectedKey) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()) {
                        Text("Tempo de estudo · ${formatCalendarDuration(sessionSecondsByDay[selectedKey] ?: 0L)}")
                        selectedSessions.forEach { session ->
                            val subjName = session.subjectId?.let { subjectsById[it]?.name } ?: "Estudo"
                            SessionRow(session, subjects, subjectNameOverride = subjName)
                        }
                        DaySection("Compromissos", selectedEvents.map { it.title })
                        dayContent(selectedKey)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDay = false }) { Text("Fechar") } }
        )
    }
    eventToDelete?.let { event ->
        AlertDialog(
            onDismissRequest = { eventToDelete = null },
            title = { Text("Excluir compromisso?") },
            text = { Text(event.title) },
            confirmButton = {
                TextButton(onClick = { deleteEvent(event); eventToDelete = null }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { eventToDelete = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun DaySection(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
    lines.forEach { Text(it, modifier = Modifier.padding(vertical = 3.dp)) }
}

@Composable
internal fun SessionRow(
    session: StudySession,
    subjects: List<Subject>,
    subjectNameOverride: String? = null
) {
    val name = subjectNameOverride ?: (subjects.firstOrNull { it.id == session.subjectId }?.name ?: "Estudo")
    val mins = (session.durationSeconds + 59) / 60
    val timeText = remember(session.startedAt) {
        Instant.ofEpochMilli(session.startedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(CAL_SESSION_TIME_FORMATTER)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text(timeText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Text("$mins min", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatCalendarHms(s: Long): String =
    String.format(Locale.getDefault(), "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)

private fun formatCalendarDuration(s: Long): String {
    val minutes = (s + 59) / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "$minutes min"
}

private fun calendarDateKey(time: Long, zone: ZoneId): Long {
    val ld = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    return ld.year * 10000L + ld.monthValue * 100L + ld.dayOfMonth
}

private fun calendarDayLabel(time: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(time)
        .atZone(zone)
        .toLocalDate()
        .format(CAL_FULL_DAY_FORMATTER)
        .replaceFirstChar { it.uppercase() }
