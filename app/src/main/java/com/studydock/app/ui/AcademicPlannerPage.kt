@file:OptIn(ExperimentalLayoutApi::class)

package com.studydock.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studydock.app.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.*

data class UpcomingAcademicEvent(
    val id: Long,
    val title: String,
    val dateEpoch: Long,
    val subjectId: Long?,
    val isExam: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AcademicPlannerPage(
    subjects: List<Subject>,
    sessions: List<StudySession>,
    tasks: List<StudyTask>,
    events: List<CalendarEvent>,
    subjectItems: List<SubjectItem>,
    onStartStudy: (Long, String) -> Unit, // subjectId, topic
    onUpdateSubject: (Subject) -> Unit,
    onSaveTask: (StudyTask) -> Unit,
    onToggleTask: (StudyTask) -> Unit,
    onDeleteTask: (StudyTask) -> Unit,
    onAddEvent: (String, Long) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit,
    onToggleSubjectItem: (SubjectItem) -> Unit,
    modifier: Modifier = Modifier,
    onStartTask: ((StudyTask) -> Unit)? = null,
    onRescheduleTasks: ((List<Long>, Long, Boolean) -> Unit)? = null,
    onDistributeOverdue: ((Map<Long, Long>) -> Unit)? = null
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val todayMidnight = remember(today) { today.atStartOfDay(zone).toInstant().toEpochMilli() }
    val tomorrowMidnight = remember(today) { today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }

    // Start of current week (Monday)
    val startOfWeek = remember(today) {
        today.minusDays((today.dayOfWeek.value - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    val todaySessions = remember(sessions, todayMidnight) {
        sessions.filter { it.startedAt >= todayMidnight && !it.isActive }
    }
    val weekSessions = remember(sessions, startOfWeek) {
        sessions.filter { it.startedAt >= startOfWeek && !it.isActive }
    }

    var showNewTaskDialog by remember { mutableStateOf(false) }
    var showNewEventDialog by remember { mutableStateOf(false) }
    var subjectToEditGoal by remember { mutableStateOf<Subject?>(null) }

    val activeSubjects = remember(subjects) { subjects.filter { !it.archived } }

    // Calculate Planned vs Realized
    val totalPlannedDailyMinutes = remember(activeSubjects) {
        val total = activeSubjects.sumOf { it.targetMinutesPerDay.coerceAtLeast(0) }
        if (total > 0) total else 180 // default 3h if no specific targets configured
    }
    val todayRealizedMinutes = remember(todaySessions) {
        todaySessions.sumOf { it.durationSeconds } / 60
    }
    val dailyProgress = (todayRealizedMinutes.toFloat() / totalPlannedDailyMinutes.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    val totalPlannedWeeklyMinutes = totalPlannedDailyMinutes * 7
    val weekRealizedMinutes = remember(weekSessions) {
        weekSessions.sumOf { it.durationSeconds } / 60
    }
    val weeklyProgress = (weekRealizedMinutes.toFloat() / totalPlannedWeeklyMinutes.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    // Upcoming Exams and Deadlines (Sorted)
    val upcomingItems = remember(events, subjectItems, todayMidnight, zone) {
        val list = mutableListOf<UpcomingAcademicEvent>()

        events.filter { it.at >= todayMidnight }.forEach { ev ->
            list.add(UpcomingAcademicEvent(ev.id, ev.title, ev.at, null, isExam = ev.title.contains("prova", ignoreCase = true) || ev.title.contains("exame", ignoreCase = true)))
        }

        subjectItems.filter { !it.completed && it.targetAt != null && it.targetAt >= todayMidnight }.forEach { item ->
            val isExam = item.type.equals("PROVA", ignoreCase = true) || item.type.equals("EXAME", ignoreCase = true)
            list.add(UpcomingAcademicEvent(item.id, item.title, item.targetAt!!, item.subjectId, isExam = isExam))
        }

        list.sortedBy { it.dateEpoch }
    }

    // Overdue, Today, Future and Inbox Tasks (Inbox tasks without a date are never overdue)
    val overdueTasks = remember(tasks, todayMidnight) {
        tasks.filter { !it.completed && it.hasDueDate && it.dueAt < todayMidnight }
            .sortedWith(compareBy<StudyTask> { it.priority }.thenBy { it.dueAt })
    }
    val todayTasks = remember(tasks, todayMidnight, tomorrowMidnight) {
        tasks.filter { it.hasDueDate && it.dueAt in todayMidnight until tomorrowMidnight }
            .sortedWith(compareBy<StudyTask> { it.completed }.thenBy { it.priority }.thenBy { it.dueAt })
    }
    val futureTasks = remember(tasks, tomorrowMidnight) {
        tasks.filter { !it.completed && it.hasDueDate && it.dueAt >= tomorrowMidnight }
            .sortedWith(compareBy<StudyTask> { it.dueAt }.thenBy { it.priority })
    }
    val inboxTasks = remember(tasks) {
        tasks.filter { !it.completed && !it.hasDueDate }
            .sortedWith(compareBy<StudyTask> { it.priority }.thenByDescending { it.createdAt })
    }
    val todayTasksEstimatedMinutes = remember(todayTasks) {
        todayTasks.sumOf { it.estimatedMinutes.coerceAtLeast(0) }
    }
    val todayTasksPendingEstimatedMinutes = remember(todayTasks) {
        todayTasks.filter { !it.completed }.sumOf { it.estimatedMinutes.coerceAtLeast(0) }
    }
    val studiedMinutesByTaskId = remember(sessions) {
        sessions.filter { !it.isActive && it.taskId != null }
            .groupBy { it.taskId!! }
            .mapValues { (_, list) -> list.sumOf { it.durationSeconds } / 60L }
    }
    val legacyStudiedMinutesByTopic = remember(sessions) {
        sessions.filter { !it.isActive && it.taskId == null && it.topic.isNotBlank() }
            .groupBy { it.topic.trim().lowercase() }
            .mapValues { (_, list) -> list.sumOf { it.durationSeconds } / 60L }
    }
    fun studiedMinutesFor(task: StudyTask): Long {
        val byId = studiedMinutesByTaskId[task.id]
        return if (byId != null && byId > 0L) byId else (legacyStudiedMinutesByTopic[task.title.trim().lowercase()] ?: 0L)
    }

    val semantic = LocalStudySemanticColors.current
    val cardPadding = StudyTokens.cardPadding()
    val todayMinutesBySubject = remember(todaySessions) {
        todaySessions.groupBy { it.subjectId }.mapValues { (_, list) ->
            list.sumOf { it.durationSeconds } / 60
        }
    }
    val upcomingFutureTasks = remember(futureTasks) { futureTasks.take(10) }
    val upcomingInboxTasks = remember(inboxTasks) { inboxTasks.take(8) }
    val pendingItems = remember(subjectItems) { subjectItems.filter { !it.completed } }
    val subjectItemGroups = remember(pendingItems) { pendingItems.groupBy { it.subjectId }.entries.toList() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
    ) {
        item(key = "planner_header_and_scorecard", contentType = "scorecard") {
            Column(Modifier.fillMaxWidth()) {
                StudyPageHeader(
            title = "Planejador Acadêmico",
            subtitle = "Metas, cronograma e acompanhamento de estudos",
            showGlobalSearch = true,
            actions = {
                IconButton(onClick = { showNewTaskDialog = true }) {
                    Icon(Icons.Default.AddCircle, "Nova Tarefa", tint = MaterialTheme.colorScheme.primary)
                }
            }
        )

        // GOAL TRACKER SCORECARD: Planned vs Realized (Daily & Weekly)
        StudyCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            isHero = true
        ) {
            Column(Modifier.padding(cardPadding)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            "PLANEJADO VS. REALIZADO",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            "Hoje: ${todayRealizedMinutes / 60}h ${todayRealizedMinutes % 60}m / ${totalPlannedDailyMinutes / 60}h ${if (totalPlannedDailyMinutes % 60 > 0) "${totalPlannedDailyMinutes % 60}m" else ""}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        "${(dailyProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = if (dailyProgress >= 1f) semantic.success else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { dailyProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (dailyProgress >= 1f) semantic.success else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                if (todayTasksEstimatedMinutes > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tarefas de hoje: ~${formatPlannerMinutes(todayTasksEstimatedMinutes)} estimados · ${formatPlannerMinutes(todayTasksPendingEstimatedMinutes)} pendentes",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))

                // Weekly summary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Semana: ${weekRealizedMinutes / 60}h estudadas (${totalPlannedWeeklyMinutes / 60}h planejadas)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Text(
                        "${(weeklyProgress * 100).toInt()}% da semana",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (weeklyProgress >= 1f) semantic.success else MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // UPCOMING EXAMS & COMMITMENTS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Próximas Avaliações e Prazos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            TextButton(onClick = { showNewEventDialog = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Novo prazo", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }

        if (upcomingItems.isEmpty()) {
            StudyCard(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(Modifier.padding(cardPadding), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Nenhuma prova ou entrega agendada para os próximos dias.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                upcomingItems.take(6).forEach { item ->
                    val itemDate = Instant.ofEpochMilli(item.dateEpoch).atZone(zone).toLocalDate()
                    val daysUntil = ChronoUnit.DAYS.between(today, itemDate)
                    val daysText = when (daysUntil) {
                        0L -> "Hoje!"
                        1L -> "Amanhã"
                        else -> "Em $daysUntil dias"
                    }
                    val subject = subjects.firstOrNull { it.id == item.subjectId }
                    val backingEvent = if (item.subjectId == null) events.firstOrNull { it.id == item.id } else null
                    val cardTextColor = if (item.isExam) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                    val cardSubColor = if (item.isExam) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

                    StudyCard(
                        modifier = Modifier.width(200.dp),
                        containerColorOverride = if (item.isExam) MaterialTheme.colorScheme.errorContainer else null
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (item.isExam) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        daysText,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.isExam) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                if (subject != null) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(subject.color.toInt()))
                                    )
                                } else if (backingEvent != null) {
                                    IconButton(
                                        onClick = { onDeleteEvent(backingEvent) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remover prazo",
                                            tint = cardSubColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Text(
                                item.title,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cardTextColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(Modifier.height(4.dp))
                            Text(
                                subject?.name ?: StudyDateFormats.formatEpoch(item.dateEpoch, StudyDateFormats.DATE_DD_MM_DEFAULT),
                                style = MaterialTheme.typography.labelSmall,
                                color = cardSubColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // PLANNED VS REALIZED BY SUBJECT (Daily Goals)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Disciplinas e Metas de Estudo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        StudyCard(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(cardPadding)) {
                if (activeSubjects.isEmpty()) {
                    Text(
                        "Cadastre suas matérias ativas para definir metas de estudo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    activeSubjects.forEachIndexed { idx, subject ->
                        val todaySubjMinutes = todayMinutesBySubject[subject.id] ?: 0L
                        val targetMin = subject.targetMinutesPerDay.takeIf { it > 0 } ?: 45
                        val subjProgress = (todaySubjMinutes.toFloat() / targetMin.toFloat()).coerceIn(0f, 1f)
                        val nextStepText = subject.nextStep?.trim().orEmpty()

                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(subject.color.toInt()))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            subject.name,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (subject.weight > 1) {
                                            Text(
                                                "Peso ${subject.weight}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "${todaySubjMinutes}m / ${targetMin}m",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (subjProgress >= 1f) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { subjProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(subject.color.toInt()),
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (nextStepText.isNotEmpty()) "Próximo: $nextStepText" else "Ajuste a meta ou inicie o estudo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                                )
                                IconButton(onClick = { subjectToEditGoal = subject }) {
                                    Icon(Icons.Default.Tune, "Ajustar meta, peso e próximo passo", Modifier.size(18.dp))
                                }
                                FilledTonalIconButton(onClick = { onStartStudy(subject.id, subject.nextStep.orEmpty()) }) {
                                    Icon(Icons.Default.PlayArrow, "Estudar agora", Modifier.size(20.dp))
                                }
                            }
                        }
                        if (idx < activeSubjects.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
            }
        }

        // OVERDUE TASKS (With Quick Reschedule & Balanced Distribution)
        if (overdueTasks.isNotEmpty()) {
            item(key = "overdue_header", contentType = "sectionHeader") {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Tarefas Pendentes Atrasadas (${overdueTasks.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (onDistributeOverdue != null) {
                            TextButton(
                                onClick = {
                                    val plan = computeBalancedReschedule(
                                        overdue = overdueTasks,
                                        allOpen = tasks.filter { !it.completed },
                                        dailyTargetMinutes = totalPlannedDailyMinutes,
                                        zone = zone
                                    )
                                    if (plan.isNotEmpty()) onDistributeOverdue(plan)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Distribuir", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(
                            onClick = {
                                val todayNoon = noonTimestamp(offsetDays = 0, zone = zone)
                                if (onRescheduleTasks != null) {
                                    onRescheduleTasks(overdueTasks.map { it.id }, todayNoon, true)
                                } else {
                                    overdueTasks.forEach { task ->
                                        onSaveTask(task.copy(dueAt = todayNoon, hasDueDate = true))
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Mover todas para hoje", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            items(
                items = overdueTasks,
                key = { "overdue_${it.id}" },
                contentType = { "plannerTask" }
            ) { task ->
                val subject = subjects.firstOrNull { it.id == task.subjectId }
                val studiedMin = studiedMinutesFor(task)
                val dueStr = StudyDateFormats.formatEpoch(task.dueAt, StudyDateFormats.DATE_DD_MM_DEFAULT)
                Box(Modifier.padding(bottom = 6.dp)) {
                    PlannerTaskCard(
                        task = task,
                        subtitle = "${subject?.name ?: "Geral"} · Venceu em $dueStr",
                        studiedMin = studiedMin,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        subtitleColor = MaterialTheme.colorScheme.error,
                        onToggle = { onToggleTask(task) },
                        onStart = if (onStartTask != null || task.subjectId != null) {
                            { if (onStartTask != null) onStartTask(task) else onStartStudy(task.subjectId!!, task.title) }
                        } else null,
                        extraTrailing = {
                            FilledTonalButton(
                                onClick = { onSaveTask(task.copy(dueAt = noonTimestamp(offsetDays = 0, zone = zone), hasDueDate = true)) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Hoje", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { onSaveTask(task.copy(dueAt = noonTimestamp(offsetDays = 1, zone = zone), hasDueDate = true)) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("+1d", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    )
                }
            }

            item(key = "overdue_bottom_spacer", contentType = "spacer") {
                Spacer(Modifier.height(10.dp))
            }
        }

        // TODAY'S TASKS & CONTENTS TO STUDY
        item(key = "today_header", contentType = "sectionHeader") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        if (todayTasksEstimatedMinutes > 0) {
                            "Cronograma de Hoje (${todayTasks.size} · ~${formatPlannerMinutes(todayTasksEstimatedMinutes)})"
                        } else {
                            "Cronograma de Hoje (${todayTasks.size})"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = { showNewTaskDialog = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nova tarefa", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (todayTasks.isEmpty()) {
            item(key = "today_empty", contentType = "empty") {
                StudyCard(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(Modifier.padding(cardPadding), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Todas as tarefas de hoje foram concluídas ou nenhuma foi planejada.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = todayTasks,
                key = { "today_${it.id}" },
                contentType = { "plannerTask" }
            ) { task ->
                val subject = subjects.firstOrNull { it.id == task.subjectId }
                val studiedMin = studiedMinutesFor(task)
                Box(Modifier.padding(bottom = 6.dp)) {
                    PlannerTaskCard(
                        task = task,
                        subtitle = subject?.name ?: "Sem matéria vinculada",
                        studiedMin = studiedMin,
                        containerColor = MaterialTheme.colorScheme.surface,
                        subtitleColor = if (studiedMin > 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        onToggle = { onToggleTask(task) },
                        onStart = if (!task.completed && (onStartTask != null || task.subjectId != null)) {
                            { if (onStartTask != null) onStartTask(task) else onStartStudy(task.subjectId!!, task.title) }
                        } else null,
                        onDelete = { onDeleteTask(task) }
                    )
                }
            }
        }

        // FUTURE TASKS (Próximos dias)
        if (upcomingFutureTasks.isNotEmpty()) {
            item(key = "future_header", contentType = "sectionHeader") {
                Text(
                    "Próximos Dias (${futureTasks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(
                items = upcomingFutureTasks,
                key = { "future_${it.id}" },
                contentType = { "plannerTask" }
            ) { task ->
                val subject = subjects.firstOrNull { it.id == task.subjectId }
                val studiedMin = studiedMinutesFor(task)
                val dueDate = StudyDateFormats.formatEpoch(task.dueAt, StudyDateFormats.DATE_DD_MM_DEFAULT)
                Box(Modifier.padding(bottom = 6.dp)) {
                    PlannerTaskCard(
                        task = task,
                        subtitle = "${subject?.name ?: "Geral"} · $dueDate",
                        studiedMin = studiedMin,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onToggle = { onToggleTask(task) },
                        onStart = if (onStartTask != null || task.subjectId != null) {
                            { if (onStartTask != null) onStartTask(task) else onStartStudy(task.subjectId!!, task.title) }
                        } else null
                    )
                }
            }
        }

        // INBOX TASKS (Sem prazo definido)
        if (upcomingInboxTasks.isNotEmpty()) {
            item(key = "inbox_header", contentType = "sectionHeader") {
                Text(
                    "Inbox · Sem prazo (${inboxTasks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(
                items = upcomingInboxTasks,
                key = { "inbox_${it.id}" },
                contentType = { "plannerTask" }
            ) { task ->
                val subject = subjects.firstOrNull { it.id == task.subjectId }
                val studiedMin = studiedMinutesFor(task)
                Box(Modifier.padding(bottom = 6.dp)) {
                    PlannerTaskCard(
                        task = task,
                        subtitle = "${subject?.name ?: "Geral"} · Inbox",
                        studiedMin = studiedMin,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onToggle = { onToggleTask(task) },
                        onStart = if (onStartTask != null || task.subjectId != null) {
                            { if (onStartTask != null) onStartTask(task) else onStartStudy(task.subjectId!!, task.title) }
                        } else null,
                        extraTrailing = {
                            FilledTonalButton(
                                onClick = { onSaveTask(task.copy(dueAt = noonTimestamp(offsetDays = 0, zone = zone), hasDueDate = true)) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Hoje", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    )
                }
            }
        }

        // SUBJECT ITEMS (Conteúdos a estudar por matéria)
        if (subjectItemGroups.isNotEmpty()) {
            item(key = "subject_items_header", contentType = "sectionHeader") {
                Text(
                    "Conteúdos a Estudar",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                )
            }

            items(
                items = subjectItemGroups,
                key = { "subj_items_${it.key ?: -1L}" },
                contentType = { "subjectItemsCard" }
            ) { (subjectId, items) ->
                val subject = subjects.firstOrNull { it.id == subjectId }
                StudyCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(cardPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (subject != null) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(subject.color.toInt()))
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                subject?.name ?: "Sem matéria",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleSubjectItem(item) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.completed,
                                    onCheckedChange = { onToggleSubjectItem(item) }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (item.targetAt != null) {
                                        Text(
                                            "Até ${StudyDateFormats.formatEpoch(item.targetAt, StudyDateFormats.DATE_DD_MM_DEFAULT)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

    // DIALOG: EDIT SUBJECT PLANNER SETTINGS (GOAL, WEIGHT & NEXT STEP)
    if (subjectToEditGoal != null) {
        val subject = subjectToEditGoal!!
        var goalDraft by remember(subject.id) { mutableStateOf(subject.targetMinutesPerDay.toString()) }
        var weightDraft by remember(subject.id) { mutableIntStateOf(subject.weight.coerceIn(1, 5)) }
        var nextStepDraft by remember(subject.id) { mutableStateOf(subject.nextStep.orEmpty()) }

        AlertDialog(
            onDismissRequest = { subjectToEditGoal = null },
            title = { Text("Planejar ${subject.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Defina meta diária, peso/prioridade e próximo passo desta matéria:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = goalDraft,
                        onValueChange = { goalDraft = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("Meta diária (minutos)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(0, 30, 45, 60, 90, 120).forEach { mins ->
                            FilterChip(
                                selected = goalDraft == mins.toString(),
                                onClick = { goalDraft = mins.toString() },
                                label = { Text(if (mins == 0) "Sem meta" else "${mins}m", maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Peso / prioridade", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        (1..5).forEach { w ->
                            FilterChip(
                                selected = weightDraft == w,
                                onClick = { weightDraft = w },
                                label = { Text("${w}x", maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nextStepDraft,
                        onValueChange = { nextStepDraft = it },
                        label = { Text("Próximo passo") },
                        placeholder = { Text("Ex: Resolver lista 3 • Cap. 4") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val minutes = goalDraft.toIntOrNull()?.coerceIn(0, 1440) ?: 0
                    onUpdateSubject(
                        subject.copy(
                            targetMinutesPerDay = minutes,
                            weight = weightDraft,
                            nextStep = nextStepDraft.trim().ifBlank { null }
                        )
                    )
                    subjectToEditGoal = null
                }) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { subjectToEditGoal = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // DIALOG: ADD NEW TASK
    if (showNewTaskDialog) {
        var taskTitle by remember { mutableStateOf("") }
        var selectedSubjId by remember { mutableStateOf(subjects.firstOrNull()?.id) }
        var selectedPriority by remember { mutableIntStateOf(4) }
        var selectedEstimatedMinutes by remember { mutableIntStateOf(0) }
        var isInboxTask by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showNewTaskDialog = false },
            title = { Text("Nova Atividade Acadêmica") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = taskTitle,
                        onValueChange = { taskTitle = it },
                        label = { Text("Título da tarefa") },
                        placeholder = { Text("Ex: Resolver lista 3 de Cálculo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(selected = !isInboxTask, onClick = { isInboxTask = false }, label = { Text("Hoje", maxLines = 1, softWrap = false) })
                        FilterChip(selected = isInboxTask, onClick = { isInboxTask = true }, label = { Text("Inbox (Sem prazo)", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 2, 3, 4).forEach { p ->
                            val chipLabelColor = when (p) {
                                1 -> MaterialTheme.colorScheme.error
                                2 -> semantic.warning
                                3 -> semantic.info
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            FilterChip(
                                selected = selectedPriority == p,
                                onClick = { selectedPriority = p },
                                label = { Text("P$p", color = chipLabelColor, maxLines = 1, softWrap = false) }
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "Sem est.", 15 to "15m", 25 to "25m", 45 to "45m", 60 to "60m").forEach { (mins, label) ->
                            FilterChip(
                                selected = selectedEstimatedMinutes == mins,
                                onClick = { selectedEstimatedMinutes = mins },
                                label = { Text(label, maxLines = 1, softWrap = false) }
                            )
                        }
                    }

                    if (subjects.isNotEmpty()) {
                        Text("Matéria:", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            subjects.forEach { s ->
                                FilterChip(
                                    selected = selectedSubjId == s.id,
                                    onClick = { selectedSubjId = s.id },
                                    label = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (taskTitle.isNotBlank()) {
                            onSaveTask(
                                StudyTask(
                                    subjectId = selectedSubjId,
                                    title = taskTitle.trim(),
                                    dueAt = noonTimestamp(offsetDays = 0, zone = zone),
                                    hasDueDate = !isInboxTask,
                                    priority = selectedPriority,
                                    estimatedMinutes = selectedEstimatedMinutes
                                )
                            )
                            showNewTaskDialog = false
                        }
                    },
                    enabled = taskTitle.isNotBlank()
                ) {
                    Text("Adicionar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewTaskDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // DIALOG: ADD NEW ACADEMIC EVENT / EXAM
    if (showNewEventDialog) {
        var eventTitle by remember { mutableStateOf("") }
        var daysAhead by remember { mutableStateOf("7") }

        AlertDialog(
            onDismissRequest = { showNewEventDialog = false },
            title = { Text("Novo Prazo / Avaliação") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = eventTitle,
                        onValueChange = { eventTitle = it },
                        label = { Text("Nome da prova ou entrega") },
                        placeholder = { Text("Ex: Prova P1 de Física") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = daysAhead,
                        onValueChange = { daysAhead = it.filter { c -> c.isDigit() } },
                        label = { Text("Em quantos dias?") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (eventTitle.isNotBlank()) {
                            val days = (daysAhead.toLongOrNull() ?: 1L).coerceIn(0L, 3650L)
                            val eventEpoch = today.plusDays(days).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                            onAddEvent(eventTitle.trim(), eventEpoch)
                            showNewEventDialog = false
                        }
                    },
                    enabled = eventTitle.isNotBlank()
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewEventDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

private fun formatPlannerMinutes(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    return if (safe >= 60) {
        val h = safe / 60
        val m = safe % 60
        if (m > 0) "${h}h ${m}m" else "${h}h"
    } else {
        "${safe}m"
    }
}

@Composable
private fun PlannerTaskCard(
    task: StudyTask,
    subtitle: String,
    studiedMin: Long,
    containerColor: Color,
    subtitleColor: Color,
    onToggle: () -> Unit,
    onStart: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    extraTrailing: (@Composable FlowRowScope.() -> Unit)? = null
) {
    val semantic = LocalStudySemanticColors.current
    val (priColor, priContainer, onPriContainer) = when (task.priority) {
        1 -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        2 -> Triple(semantic.warning, semantic.warningContainer, semantic.onWarningContainer)
        3 -> Triple(semantic.info, semantic.infoContainer, semantic.onInfoContainer)
        else -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val tags = remember(task.tags) { TaskTagPresets.parseTags(task.tags) }
    val (doneSub, totalSub) = remember(task.subtasksJson) { TaskSubtasks.summary(task.subtasksJson) }

    StudyCard(
        modifier = Modifier.fillMaxWidth(),
        containerColorOverride = containerColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .width(4.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (task.completed) MaterialTheme.colorScheme.outlineVariant else priColor)
            )
            Checkbox(
                checked = task.completed,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = priColor, uncheckedColor = priColor)
            )
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                Text(
                    task.title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None
                )
                Text(
                    buildString {
                        append(subtitle)
                        if (studiedMin > 0L) append(" · ⏱ ${studiedMin}m")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor
                )
                if (task.priority in 1..3 || task.estimatedMinutes > 0 || totalSub > 0 || tags.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (task.priority in 1..3) {
                            Surface(color = priContainer, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "P${task.priority}",
                                    color = onPriContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (task.estimatedMinutes > 0) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "~${task.estimatedMinutes}m",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (totalSub > 0) {
                            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "$doneSub/$totalSub",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        tags.take(3).forEach { tag ->
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    tag,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                if (extraTrailing != null || onStart != null) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        extraTrailing?.invoke(this)
                        if (onStart != null) {
                            FilledTonalButton(
                                onClick = onStart,
                                modifier = Modifier.align(Alignment.CenterVertically),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Estudar", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Close, "Excluir", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
