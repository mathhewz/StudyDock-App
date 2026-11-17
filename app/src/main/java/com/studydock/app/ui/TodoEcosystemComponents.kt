@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studydock.app.data.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------- Enums & Helpers Compartilhados ----------

internal enum class TodoViewMode(val label: String) {
    LIST("Lista"),
    KANBAN("Kanban"),
    MATRIX("Matriz")
}

internal enum class TaskSortGroup(val label: String) {
    DATE("Data"),
    PRIORITY("Prioridade"),
    SUBJECT("Matéria")
}

internal enum class KanbanGroupMode(val label: String) {
    STATUS("Por Prazo"),
    SUBJECT("Por Matéria")
}

internal fun taskDayDiff(time: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
    val today = LocalDate.now(zone)
    val date = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(today, date).toInt()
}

internal fun formatMinutesCompact(minutes: Int): String {
    if (minutes <= 0) return "0m"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

internal fun studiedMinutesForTask(task: StudyTask, sessions: List<StudySession>): Long {
    val cleanTitle = task.title.trim()
    return sessions.filter { session ->
        !session.isActive && (
            (task.id != 0L && session.taskId == task.id) ||
                (session.taskId == null && cleanTitle.isNotBlank() && session.topic.trim().equals(cleanTitle, ignoreCase = true))
            )
    }.sumOf { it.durationSeconds } / 60L
}

internal fun buildStudiedSecondsMaps(sessions: List<StudySession>): Pair<Map<Long, Long>, Map<String, Long>> {
    if (sessions.isEmpty()) return emptyMap<Long, Long>() to emptyMap()
    val byTaskId = HashMap<Long, Long>()
    val byTopicLower = HashMap<String, Long>()
    for (session in sessions) {
        if (session.isActive || session.durationSeconds <= 0L) continue
        val tid = session.taskId
        if (tid != null) {
            byTaskId[tid] = (byTaskId[tid] ?: 0L) + session.durationSeconds
        } else if (session.topic.isNotBlank()) {
            val key = session.topic.trim().lowercase(Locale.ROOT)
            byTopicLower[key] = (byTopicLower[key] ?: 0L) + session.durationSeconds
        }
    }
    return byTaskId to byTopicLower
}

internal fun lookupStudiedMinutes(
    task: StudyTask,
    byTaskId: Map<Long, Long>,
    byTopicLower: Map<String, Long>
): Long {
    val taskSec = if (task.id != 0L) byTaskId[task.id] ?: 0L else 0L
    val cleanTitle = task.title.trim()
    val topicSec = if (cleanTitle.isNotEmpty()) byTopicLower[cleanTitle.lowercase(Locale.ROOT)] ?: 0L else 0L
    return (taskSec + topicSec) / 60L
}

internal fun nextWeekendNoon(
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val offset = when (today.dayOfWeek) {
        DayOfWeek.SATURDAY -> 1
        DayOfWeek.SUNDAY -> 6
        else -> DayOfWeek.SATURDAY.value - today.dayOfWeek.value
    }
    return noonTimestamp(now, offset, zone)
}

internal fun prioritySectionTitle(priority: Int): String = when (priority) {
    1 -> "P1 · Urgente + Importante"
    2 -> "P2 · Importante"
    3 -> "P3 · Média"
    else -> "P4 · Backlog / Normal"
}

internal fun priorityQuadrantTitle(priority: Int): String = when (priority) {
    1 -> "P1 Urgente + Importante"
    2 -> "P2 Importante / Agendar"
    3 -> "P3 Rápidas / Delegáveis"
    else -> "P4 Backlog"
}

internal fun tagAccentColor(tag: String, fallback: Color): Color = when (TaskTagPresets.normalizeTag(tag)) {
    TaskTagPresets.QUESTOES -> Color(0xFF246FE0)
    TaskTagPresets.LEITURA -> Color(0xFF0E9F6E)
    TaskTagPresets.TEORIA -> Color(0xFF7E57C2)
    TaskTagPresets.REVISAO -> Color(0xFFD97706)
    TaskTagPresets.SIMULADO -> Color(0xFFD1453B)
    TaskTagPresets.RESUMO -> Color(0xFF00838F)
    else -> fallback
}

@Composable
internal fun themeTagAccentColor(
    tag: String,
    fallback: Color = MaterialTheme.colorScheme.secondary,
    surface: Color = MaterialTheme.colorScheme.surface
): Color {
    val semantic = LocalStudySemanticColors.current
    val raw = when (TaskTagPresets.normalizeTag(tag)) {
        TaskTagPresets.QUESTOES -> semantic.info
        TaskTagPresets.LEITURA -> semantic.success
        TaskTagPresets.TEORIA -> MaterialTheme.colorScheme.tertiary
        TaskTagPresets.REVISAO -> semantic.warning
        TaskTagPresets.SIMULADO -> MaterialTheme.colorScheme.error
        TaskTagPresets.RESUMO -> MaterialTheme.colorScheme.secondary
        else -> fallback
    }
    return accessibleAccentOnSurface(raw, surface)
}

// ---------- Medidor de Carga Diária & Reagendamento ----------

@Composable
internal fun DailyWorkloadCard(
    todayDoneCount: Int,
    todayTotalCount: Int,
    todayRemainingMinutes: Int,
    todayCompletedMinutes: Int,
    todayStudiedMinutes: Long,
    dailyGoalMinutes: Int,
    onDistributeOverdue: (() -> Unit)? = null
) {
    val plannedTotalMinutes = todayRemainingMinutes + todayCompletedMinutes
    val effectiveGoal = (if (dailyGoalMinutes > 0) dailyGoalMinutes else 180).coerceAtLeast(1)
    val overloaded = plannedTotalMinutes > effectiveGoal
    val completionProgress = if (todayTotalCount > 0) todayDoneCount.toFloat() / todayTotalCount else 0f
    val completionPercent = if (todayTotalCount > 0) todayDoneCount * 100 / todayTotalCount else 0
    val workloadProgress = (plannedTotalMinutes.toFloat() / effectiveGoal).coerceIn(0f, 1f)

    val containerColor = if (overloaded) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (overloaded) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
    val barColor = if (overloaded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = studyCardShape(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (todayTotalCount > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Hoje: $todayDoneCount de $todayTotalCount concluídas",
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "$completionPercent%",
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    LinearProgressIndicator(
                        progress = { completionProgress },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                        color = barColor,
                        trackColor = trackColor
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (overloaded) Icons.Default.WarningAmber else Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor
                    )
                    val workloadSummary = buildString {
                        append("Carga estimada: ${formatMinutesCompact(plannedTotalMinutes)} / ${formatMinutesCompact(effectiveGoal)}")
                        if (todayRemainingMinutes > 0 && todayCompletedMinutes > 0) {
                            append(" (${formatMinutesCompact(todayRemainingMinutes)} restantes)")
                        }
                        if (todayStudiedMinutes > 0L) {
                            append(" · Estudado: ${formatMinutesCompact(todayStudiedMinutes.toInt())}")
                        }
                    }
                    Text(
                        workloadSummary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = contentColor,
                        modifier = Modifier.weight(1f)
                    )
                }
                LinearProgressIndicator(
                    progress = { workloadProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                    color = barColor,
                    trackColor = trackColor
                )
                if (overloaded) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Acima da meta diária em ${formatMinutesCompact(plannedTotalMinutes - effectiveGoal)}. Considere reagendar.",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            modifier = Modifier.weight(1f)
                        )
                        if (onDistributeOverdue != null) {
                            TextButton(
                                onClick = onDistributeOverdue,
                                colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) {
                                Text("Equilibrar", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (plannedTotalMinutes == 0 && todayTotalCount > 0) {
                    Text(
                        "Dica: use ~30m ao criar tarefas para medir sua carga diária.",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun OverdueRescheduleBar(
    overdueCount: Int,
    onMoveToday: () -> Unit,
    onMoveTomorrow: () -> Unit,
    onMoveWeekend: () -> Unit,
    onDistributeBalanced: () -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.errorContainer
    val contentColor = MaterialTheme.colorScheme.onErrorContainer
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = studyCardShape(),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Reagendar atrasadas ($overdueCount)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RescheduleQuickChip("Para Hoje", Icons.Default.Today, onMoveToday)
                RescheduleQuickChip("Amanhã", Icons.Default.ChevronRight, onMoveTomorrow)
                RescheduleQuickChip("Fim de semana", Icons.Default.Event, onMoveWeekend)
                RescheduleQuickChip("Distribuir equilibrado", Icons.Default.AutoAwesome, onDistributeBalanced)
            }
        }
    }
}

@Composable
private fun RescheduleQuickChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp)) }
    )
}

// ---------- Preview NLP do Quick Add ----------

@Composable
internal fun QuickTaskPreviewStrip(
    parsed: TaskQuickParseResult,
    subjects: List<Subject>,
    defaultSubjectId: Long?,
    defaultHasDueDate: Boolean
) {
    val subjectById = remember(subjects) { subjects.associateBy { it.id } }
    val effectiveHasDate = parsed.dueAt != null || (parsed.hasDueDate && defaultHasDueDate)
    val effectiveSubjectId = parsed.subjectId ?: defaultSubjectId

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = {},
            label = {
                Text(
                    if (!effectiveHasDate) "Inbox (Sem data)"
                    else taskDateLabel(parsed.dueAt ?: noonTimestamp(offsetDays = 0))
                )
            },
            leadingIcon = {
                Icon(
                    if (!effectiveHasDate) Icons.Default.Inbox else Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
        parsed.priority?.let { p ->
            AssistChip(
                onClick = {},
                label = { Text("P$p") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Flag,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = themePriorityColor(p)
                    )
                }
            )
        }
        effectiveSubjectId?.let { id ->
            subjectById[id]?.let { subj ->
                AssistChip(
                    onClick = {},
                    label = { Text(subj.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(subj.color.toInt()))
                        )
                    }
                )
            }
        }
        if (parsed.estimatedMinutes > 0) {
            AssistChip(
                onClick = {},
                label = { Text("~${formatMinutesCompact(parsed.estimatedMinutes)}") },
                leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
        parsed.recurrence?.let { rec ->
            AssistChip(
                onClick = {},
                label = { Text(taskRecurrenceLabel(rec, parsed.afterCompletion)) },
                leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
        if (parsed.recurrence == null && parsed.afterCompletion) {
            AssistChip(
                onClick = {},
                label = { Text("Após concluir") },
                leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
        parsed.tags.forEach { tag ->
            val tint = themeTagAccentColor(tag)
            AssistChip(
                onClick = {},
                label = { Text(tag, color = tint) },
                leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null, modifier = Modifier.size(15.dp), tint = tint) }
            )
        }
    }
}

// ---------- Menu Compartilhado de Ações Rápidas ----------

@Composable
internal fun TaskQuickActionsMenu(
    expanded: Boolean,
    task: StudyTask,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onQuickSchedule: (Long, Boolean) -> Unit,
    onQuickPriority: (Int) -> Unit,
    onOpenTutorForTask: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Editar detalhes") },
            leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
            onClick = { onDismiss(); onEdit() }
        )
        DropdownMenuItem(
            text = { Text("Mover para Hoje") },
            leadingIcon = { Icon(Icons.Default.Today, null, Modifier.size(18.dp)) },
            onClick = { onDismiss(); onQuickSchedule(noonTimestamp(offsetDays = 0), true) }
        )
        DropdownMenuItem(
            text = { Text("Adiar para Amanhã") },
            leadingIcon = { Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp)) },
            onClick = { onDismiss(); onQuickSchedule(noonTimestamp(offsetDays = 1), true) }
        )
        DropdownMenuItem(
            text = { Text("Próxima semana (+7d)") },
            leadingIcon = { Icon(Icons.Default.Upcoming, null, Modifier.size(18.dp)) },
            onClick = { onDismiss(); onQuickSchedule(noonTimestamp(offsetDays = 7), true) }
        )
        DropdownMenuItem(
            text = { Text("Mover para Inbox (Sem data)") },
            leadingIcon = { Icon(Icons.Default.Inbox, null, Modifier.size(18.dp)) },
            onClick = { onDismiss(); onQuickSchedule(task.dueAt, false) }
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            (1..4).forEach { p ->
                val pColor = themePriorityColor(p)
                val selected = task.priority == p
                val bg = if (selected) pColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
                val fg = if (selected) accessibleAccentOnSurface(pColor, MaterialTheme.colorScheme.surfaceContainer) else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(
                    onClick = { onDismiss(); onQuickPriority(p) },
                    shape = studyControlShape(),
                    color = bg
                ) {
                    Box(
                        modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "P$p",
                            color = fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
        if (onOpenTutorForTask != null) {
            DropdownMenuItem(
                text = { Text("Quebrar com Tutor IA") },
                leadingIcon = { Icon(Icons.Default.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                onClick = { onDismiss(); onOpenTutorForTask() }
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() }
        )
    }
}

@Composable
internal fun PrioritySelectorBadge(
    priority: Int,
    onSelectPriority: (Int) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pColor = themePriorityColor(priority)
    Box {
        Surface(
            onClick = { menuOpen = true },
            shape = studyControlShape(),
            color = pColor.copy(alpha = 0.14f)
        ) {
            Row(
                modifier = Modifier.defaultMinSize(minHeight = 36.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Flag, contentDescription = "Alterar prioridade", tint = pColor, modifier = Modifier.size(14.dp))
                Text("P$priority", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = pColor)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            (1..4).forEach { p ->
                val color = themePriorityColor(p)
                DropdownMenuItem(
                    text = { Text(priorityQuadrantTitle(p), fontWeight = if (p == priority) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = { Icon(Icons.Default.Flag, null, tint = color, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        if (p != priority) onSelectPriority(p)
                    }
                )
            }
        }
    }
}

// ---------- Rich TaskRow com Subtarefas Inline ----------

@Composable
internal fun TaskRow(
    task: StudyTask,
    subject: Subject?,
    note: Note?,
    book: Book? = null,
    studiedMinutes: Long = 0L,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onStudy: (() -> Unit)?,
    onOpenNote: (() -> Unit)?,
    onToggleSubtask: (String) -> Unit,
    onQuickSchedule: (Long, Boolean) -> Unit,
    onQuickPriority: (Int) -> Unit,
    onDelete: () -> Unit,
    onOpenTutorForTask: (() -> Unit)? = null
) {
    val flagColor = themePriorityColor(task.priority)
    val late = !task.completed && task.hasDueDate && taskDayDiff(task.dueAt) < 0
    val subtasks = remember(task.subtasksJson) { TaskSubtasks.parse(task.subtasksJson) }
    val (subDone, subTotal) = remember(subtasks) { TaskSubtasks.summary(subtasks) }
    val tags = remember(task.tags) { TaskTagPresets.parseTags(task.tags) }
    var expandedSubtasks by remember(task.id) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val cardBg = MaterialTheme.colorScheme.surface

    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (late) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.14f)
            else cardBg
        ),
        border = if (late) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)) else null,
        shape = studyCardShape()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = task.completed,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(uncheckedColor = flagColor, checkedColor = flagColor)
                )
                Column(
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = task.title,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                        color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    if (task.details.isNotBlank()) {
                        Text(
                            text = task.details.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!task.hasDueDate) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Default.Inbox, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Inbox", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Text(
                                taskDateLabel(task.dueAt),
                                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (late) FontWeight.SemiBold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (task.recurrence != "NONE") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(Icons.Default.Repeat, "Repete", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    taskRecurrenceLabel(task.recurrence, task.afterCompletion),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        subject?.let {
                            val subColor = accessibleAccentOnSurface(Color(it.color.toInt()), cardBg)
                            Text(
                                "● ${it.name}",
                                color = subColor,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (task.estimatedMinutes > 0) {
                            Text(
                                "~${formatMinutesCompact(task.estimatedMinutes)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (studiedMinutes > 0L) {
                            Text(
                                "⏱ ${formatMinutesCompact(studiedMinutes.toInt())}",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (subTotal > 0) {
                            val subBg = if (subDone == subTotal) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                            val subFg = if (subDone == subTotal) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Surface(
                                onClick = { expandedSubtasks = !expandedSubtasks },
                                shape = RoundedCornerShape(8.dp),
                                color = subBg,
                                contentColor = subFg
                            ) {
                                Row(
                                    modifier = Modifier.defaultMinSize(minHeight = 32.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.Checklist, null, Modifier.size(13.dp), tint = subFg)
                                    Text("$subDone/$subTotal", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = subFg)
                                    Icon(
                                        if (expandedSubtasks) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expandir subtarefas",
                                        modifier = Modifier.size(14.dp),
                                        tint = subFg
                                    )
                                }
                            }
                        }
                    }

                    if (tags.isNotEmpty() || note != null || book != null) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            tags.forEach { tag ->
                                val tint = themeTagAccentColor(tag, surface = cardBg)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = tint.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        tag,
                                        color = tint,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (note != null) {
                                Surface(
                                    onClick = { onOpenNote?.invoke() },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.defaultMinSize(minHeight = 32.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Description, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Text(
                                            note.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (book != null) {
                                Surface(
                                    onClick = onEdit,
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.defaultMinSize(minHeight = 32.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                        val pageHint = "${book.title} (p.${book.currentPage}${book.totalPages?.let { "/$it" } ?: ""})"
                                        Text(
                                            pageHint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (late) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                onClick = { onQuickSchedule(noonTimestamp(offsetDays = 0), true) },
                                shape = studyControlShape(),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Box(
                                    modifier = Modifier.defaultMinSize(minHeight = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Mover p/ Hoje",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Surface(
                                onClick = { onQuickSchedule(noonTimestamp(offsetDays = 1), true) },
                                shape = studyControlShape(),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Box(
                                    modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "+1d",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onStudy != null && !task.completed) {
                        IconButton(onClick = onStudy) {
                            Icon(Icons.Default.PlayCircle, "Estudar agora", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "Mais ações", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TaskQuickActionsMenu(
                            expanded = menuExpanded,
                            task = task,
                            onDismiss = { menuExpanded = false },
                            onEdit = onEdit,
                            onQuickSchedule = onQuickSchedule,
                            onQuickPriority = onQuickPriority,
                            onOpenTutorForTask = onOpenTutorForTask,
                            onDelete = onDelete
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expandedSubtasks && subtasks.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 44.dp, end = 12.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    subtasks.forEach { sub ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onToggleSubtask(sub.id) }
                                .padding(vertical = 3.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (sub.done) Icons.Default.CheckBox else Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (sub.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                            Text(
                                text = sub.title,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = if (sub.done) TextDecoration.LineThrough else null,
                                color = if (sub.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Visualização Kanban ----------

private data class KanbanColumnData(
    val id: String,
    val title: String,
    val accent: Color,
    val tasks: List<StudyTask>,
    val showMoveOverdueToToday: Boolean = false
)

@Composable
internal fun TodoKanbanView(
    scopedTasks: List<StudyTask>,
    subjects: List<Subject>,
    sessions: List<StudySession>,
    groupMode: KanbanGroupMode,
    onGroupModeChange: (KanbanGroupMode) -> Unit,
    onToggle: (StudyTask) -> Unit,
    onEdit: (StudyTask) -> Unit,
    onQuickMove: (StudyTask, Long, Boolean) -> Unit,
    onMoveAllOverdueToday: (List<StudyTask>) -> Unit,
    resolveStudyAction: (StudyTask) -> (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val subjectById = remember(subjects) { subjects.associateBy { it.id } }
    val (studiedByTaskId, studiedByTopicLower) = remember(sessions) { buildStudiedSecondsMaps(sessions) }
    val priorityOrder = remember { compareBy<StudyTask>({ it.priority }, { !it.hasDueDate }, { it.dueAt }, { it.createdAt }) }

    val openTasks = remember(scopedTasks) { scopedTasks.filter { !it.completed } }
    val semantic = LocalStudySemanticColors.current
    val errorColor = MaterialTheme.colorScheme.error
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val neutralColor = MaterialTheme.colorScheme.onSurfaceVariant
    val colSurface = MaterialTheme.colorScheme.surfaceContainerLow

    val columns = remember(scopedTasks, subjects, groupMode, errorColor, primaryColor, secondaryColor, neutralColor, semantic.success, colSurface) {
        if (groupMode == KanbanGroupMode.STATUS) {
            val inbox = openTasks.filter { !it.hasDueDate }.sortedWith(priorityOrder)
            val overdue = openTasks.filter { it.hasDueDate && taskDayDiff(it.dueAt) < 0 }.sortedWith(priorityOrder)
            val today = openTasks.filter { it.hasDueDate && taskDayDiff(it.dueAt) == 0 }.sortedWith(priorityOrder)
            val upcoming = openTasks.filter { it.hasDueDate && taskDayDiff(it.dueAt) > 0 }
                .sortedWith(compareBy<StudyTask>({ it.dueAt }, { it.priority }, { it.createdAt }))
            val done = scopedTasks.filter { it.completed }
                .sortedByDescending { it.completedAt ?: it.dueAt }

            listOf(
                KanbanColumnData("inbox", "Inbox / Sem Data", neutralColor, inbox),
                KanbanColumnData("overdue", "Atrasadas", errorColor, overdue, showMoveOverdueToToday = overdue.isNotEmpty()),
                KanbanColumnData("today", "Hoje", primaryColor, today),
                KanbanColumnData("upcoming", "Próximas", secondaryColor, upcoming),
                KanbanColumnData("done", "Concluídas", semantic.success, done)
            )
        } else {
            val subjectCols = subjects.map { subj ->
                KanbanColumnData(
                    id = "subj-${subj.id}",
                    title = subj.name,
                    accent = accessibleAccentOnSurface(Color(subj.color.toInt()), colSurface),
                    tasks = openTasks.filter { it.subjectId == subj.id }.sortedWith(priorityOrder)
                )
            }
            val generalCol = KanbanColumnData(
                id = "subj-none",
                title = "Geral",
                accent = neutralColor,
                tasks = openTasks.filter { it.subjectId == null }.sortedWith(priorityOrder)
            )
            subjectCols + generalCol
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Agrupar:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KanbanGroupMode.entries.forEach { mode ->
                FilterChip(
                    selected = groupMode == mode,
                    onClick = { onGroupModeChange(mode) },
                    label = { Text(mode.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val columnWidth = minOf(288.dp, (maxWidth - 20.dp).coerceAtLeast(220.dp))
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 84.dp)
            ) {
                items(columns, key = { it.id }) { column ->
                    KanbanColumnCard(
                        column = column,
                        columnWidth = columnWidth,
                        subjectById = subjectById,
                        studiedByTaskId = studiedByTaskId,
                        studiedByTopicLower = studiedByTopicLower,
                        onToggle = onToggle,
                        onEdit = onEdit,
                        onQuickMove = onQuickMove,
                        onMoveOverdueToday = { onMoveAllOverdueToday(column.tasks) },
                        resolveStudyAction = resolveStudyAction
                    )
                }
            }
        }
    }
}

@Composable
private fun KanbanColumnCard(
    column: KanbanColumnData,
    columnWidth: Dp,
    subjectById: Map<Long, Subject>,
    studiedByTaskId: Map<Long, Long>,
    studiedByTopicLower: Map<String, Long>,
    onToggle: (StudyTask) -> Unit,
    onEdit: (StudyTask) -> Unit,
    onQuickMove: (StudyTask, Long, Boolean) -> Unit,
    onMoveOverdueToday: () -> Unit,
    resolveStudyAction: (StudyTask) -> (() -> Unit)?
) {
    val estimatedSum = remember(column.tasks) { column.tasks.sumOf { it.estimatedMinutes } }
    val wipOverloaded = column.id == "today" && column.tasks.size > 5
    Surface(
        modifier = Modifier.width(columnWidth).fillMaxHeight(),
        shape = studyCardShape(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, column.accent.copy(alpha = 0.28f))
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(column.accent))
                Text(
                    column.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (estimatedSum > 0 || wipOverloaded) {
                    val badgeText = when {
                        wipOverloaded && estimatedSum > 0 -> "⚠️ WIP · ~${formatMinutesCompact(estimatedSum)}"
                        wipOverloaded -> "⚠️ WIP"
                        else -> "~${formatMinutesCompact(estimatedSum)}"
                    }
                    Text(
                        badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (wipOverloaded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = column.accent,
                    contentColor = accessibleOnColor(column.accent)
                ) {
                    Text(
                        "${column.tasks.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accessibleOnColor(column.accent),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            if (column.showMoveOverdueToToday) {
                TextButton(
                    onClick = onMoveOverdueToday,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                ) {
                    Icon(Icons.Default.Today, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mover todas p/ Hoje", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }

            if (column.tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nenhuma tarefa",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(column.tasks, key = { it.id }) { task ->
                        KanbanTaskCard(
                            task = task,
                            subject = task.subjectId?.let { subjectById[it] },
                            studiedMinutes = lookupStudiedMinutes(task, studiedByTaskId, studiedByTopicLower),
                            onToggle = { onToggle(task) },
                            onEdit = { onEdit(task) },
                            onQuickMove = { due, hasDate -> onQuickMove(task, due, hasDate) },
                            onStudy = resolveStudyAction(task)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KanbanTaskCard(
    task: StudyTask,
    subject: Subject?,
    studiedMinutes: Long,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onQuickMove: (Long, Boolean) -> Unit,
    onStudy: (() -> Unit)?
) {
    val pColor = themePriorityColor(task.priority)
    val late = !task.completed && task.hasDueDate && taskDayDiff(task.dueAt) < 0
    val isToday = task.hasDueDate && taskDayDiff(task.dueAt) == 0
    val isTomorrow = task.hasDueDate && taskDayDiff(task.dueAt) == 1
    val (subDone, subTotal) = remember(task.subtasksJson) { TaskSubtasks.summary(task.subtasksJson) }
    val tags = remember(task.tags) { TaskTagPresets.parseTags(task.tags) }
    val cardBg = MaterialTheme.colorScheme.surface

    Card(
        onClick = onEdit,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(pColor)
                )
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                    color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = if (!task.hasDueDate) "Inbox" else taskDateLabel(task.dueAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (late) FontWeight.SemiBold else FontWeight.Normal
                )
                subject?.let {
                    Text(
                        "● ${it.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accessibleAccentOnSurface(Color(it.color.toInt()), cardBg),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (task.estimatedMinutes > 0) {
                    Text("~${formatMinutesCompact(task.estimatedMinutes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (studiedMinutes > 0L) {
                    Text("⏱ ${formatMinutesCompact(studiedMinutes.toInt())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (subTotal > 0) {
                    Text("✓ $subDone/$subTotal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    tags.take(3).forEach { tag ->
                        val tint = themeTagAccentColor(tag, surface = cardBg)
                        Surface(shape = RoundedCornerShape(6.dp), color = tint.copy(alpha = 0.12f)) {
                            Text(tag, color = tint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!task.completed) {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!isToday) {
                            KanbanMiniChip("Hoje") { onQuickMove(noonTimestamp(offsetDays = 0), true) }
                        }
                        if (!isTomorrow) {
                            KanbanMiniChip("Amanhã") { onQuickMove(noonTimestamp(offsetDays = 1), true) }
                        }
                        if (task.hasDueDate) {
                            KanbanMiniChip("Sem data") { onQuickMove(task.dueAt, false) }
                        }
                    }
                    if (onStudy != null) {
                        IconButton(onClick = onStudy, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.PlayCircle, "Iniciar Timer", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.CheckCircle, "Concluir", tint = pColor, modifier = Modifier.size(20.dp))
                    }
                } else {
                    KanbanMiniChip("Reabrir") { onToggle() }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun KanbanMiniChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = studyControlShape(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Box(
            modifier = Modifier.defaultMinSize(minHeight = 36.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Matriz de Eisenhower ----------

@Composable
internal fun TodoEisenhowerMatrixView(
    openTasks: List<StudyTask>,
    subjects: List<Subject>,
    sessions: List<StudySession>,
    onToggle: (StudyTask) -> Unit,
    onEdit: (StudyTask) -> Unit,
    onChangePriority: (StudyTask, Int) -> Unit,
    resolveStudyAction: (StudyTask) -> (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val subjectById = remember(subjects) { subjects.associateBy { it.id } }
    val (studiedByTaskId, studiedByTopicLower) = remember(sessions) { buildStudiedSecondsMaps(sessions) }
    val order = remember { compareBy<StudyTask>({ !it.hasDueDate }, { it.dueAt }, { it.createdAt }) }
    val quadrants = remember(openTasks) {
        (1..4).map { p ->
            p to openTasks.filter {
                if (p == 4) it.priority !in 1..3 else it.priority == p
            }.sortedWith(order)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val twoColumns = maxWidth >= 620.dp
        if (twoColumns) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 84.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(0 to 1, 2 to 3).forEach { (leftIdx, rightIdx) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(quadrants[leftIdx], quadrants[rightIdx]).forEach { (priority, list) ->
                            EisenhowerQuadrantCard(
                                priority = priority,
                                tasks = list,
                                subjectById = subjectById,
                                studiedByTaskId = studiedByTaskId,
                                studiedByTopicLower = studiedByTopicLower,
                                onToggle = onToggle,
                                onEdit = onEdit,
                                onChangePriority = onChangePriority,
                                resolveStudyAction = resolveStudyAction,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 6.dp, bottom = 84.dp)
            ) {
                items(quadrants, key = { it.first }) { (priority, list) ->
                    EisenhowerQuadrantCard(
                        priority = priority,
                        tasks = list,
                        subjectById = subjectById,
                        studiedByTaskId = studiedByTaskId,
                        studiedByTopicLower = studiedByTopicLower,
                        onToggle = onToggle,
                        onEdit = onEdit,
                        onChangePriority = onChangePriority,
                        resolveStudyAction = resolveStudyAction,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun EisenhowerQuadrantCard(
    priority: Int,
    tasks: List<StudyTask>,
    subjectById: Map<Long, Subject>,
    studiedByTaskId: Map<Long, Long>,
    studiedByTopicLower: Map<String, Long>,
    onToggle: (StudyTask) -> Unit,
    onEdit: (StudyTask) -> Unit,
    onChangePriority: (StudyTask, Int) -> Unit,
    resolveStudyAction: (StudyTask) -> (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val accent = themePriorityColor(priority)
    val totalMinutes = remember(tasks) { tasks.sumOf { it.estimatedMinutes } }
    val cardBg = MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier,
        shape = studyCardShape(),
        color = cardBg,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                Text(
                    priorityQuadrantTitle(priority),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (totalMinutes > 0) {
                    Text(
                        "~${formatMinutesCompact(totalMinutes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = 0.14f)) {
                    Text(
                        "${tasks.size}",
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            if (tasks.isEmpty()) {
                Text(
                    "Nenhuma tarefa neste quadrante.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                tasks.forEach { task ->
                    val subject = task.subjectId?.let { subjectById[it] }
                    val late = task.hasDueDate && taskDayDiff(task.dueAt) < 0
                    val studied = lookupStudiedMinutes(task, studiedByTaskId, studiedByTopicLower)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                            .clickable { onEdit(task) }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = task.completed,
                            onCheckedChange = { onToggle(task) },
                            colors = CheckboxDefaults.colors(uncheckedColor = accent, checkedColor = accent)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    if (!task.hasDueDate) "Inbox" else taskDateLabel(task.dueAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                subject?.let {
                                    Text(
                                        "● ${it.name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accessibleAccentOnSurface(Color(it.color.toInt()), cardBg),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (task.estimatedMinutes > 0) {
                                    Text("~${formatMinutesCompact(task.estimatedMinutes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (studied > 0L) {
                                    Text("⏱ ${formatMinutesCompact(studied.toInt())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        PrioritySelectorBadge(priority = task.priority) { newP ->
                            onChangePriority(task, newP)
                        }
                        val studyAction = resolveStudyAction(task)
                        if (studyAction != null) {
                            IconButton(onClick = studyAction) {
                                Icon(Icons.Default.PlayCircle, "Estudar agora", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Editor Completo de Tarefa ----------

@Composable
internal fun TaskEditDialog(
    task: StudyTask?,
    subjects: List<Subject>,
    notes: List<Note>,
    books: List<Book> = emptyList(),
    defaultSubject: Long?,
    defaultHasDueDate: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (StudyTask) -> Unit,
    onDelete: (() -> Unit)?,
    onOpenTutorForTask: ((StudyTask) -> Unit)? = null,
    onPromoteSubtask: ((StudyTask) -> Unit)? = null
) {
    val context = LocalContext.current
    val activeNotes = remember(notes) { notes.filter { it.trashedAt == null } }

    var title by remember(task?.id) { mutableStateOf(task?.title.orEmpty()) }
    var details by remember(task?.id) { mutableStateOf(task?.details.orEmpty()) }
    var priority by remember(task?.id) { mutableIntStateOf(task?.priority ?: 4) }
    var hasDueDate by remember(task?.id) { mutableStateOf(task?.hasDueDate ?: defaultHasDueDate) }
    var dueAt by remember(task?.id) { mutableLongStateOf(task?.dueAt ?: noonTimestamp(offsetDays = 0)) }
    var estimatedMinutes by remember(task?.id) { mutableIntStateOf(task?.estimatedMinutes ?: 0) }
    var customMinutesMode by remember(task?.id) {
        mutableStateOf((task?.estimatedMinutes ?: 0) !in listOf(0, 15, 30, 45, 60, 90))
    }
    var customMinutesText by remember(task?.id) {
        mutableStateOf(if ((task?.estimatedMinutes ?: 0) > 0) task?.estimatedMinutes.toString() else "")
    }
    var tagsRaw by remember(task?.id) { mutableStateOf(task?.tags.orEmpty()) }
    var customTagInput by remember(task?.id) { mutableStateOf("") }

    val initialRec = task?.recurrence ?: "NONE"
    val initialIsEveryN = initialRec.startsWith("EVERY_N_DAYS:")
    var recurrenceKind by remember(task?.id) {
        mutableStateOf(if (initialIsEveryN) "EVERY_N_DAYS" else initialRec)
    }
    var everyNDaysText by remember(task?.id) {
        mutableStateOf(
            if (initialIsEveryN) initialRec.substringAfter("EVERY_N_DAYS:").trim().ifBlank { "3" }
            else "3"
        )
    }
    var afterCompletion by remember(task?.id) { mutableStateOf(task?.afterCompletion ?: false) }

    var subjectId by remember(task?.id) { mutableStateOf(task?.subjectId ?: defaultSubject) }
    var noteId by remember(task?.id) { mutableStateOf(task?.noteId) }
    var bookId by remember(task?.id) { mutableStateOf(task?.bookId) }
    var subtasks by remember(task?.id) { mutableStateOf(TaskSubtasks.parse(task?.subtasksJson)) }
    var newSubtaskTitle by remember(task?.id) { mutableStateOf("") }

    var subjectMenu by remember { mutableStateOf(false) }
    var noteMenu by remember { mutableStateOf(false) }
    var bookMenu by remember { mutableStateOf(false) }

    val parsedTags = remember(tagsRaw) { TaskTagPresets.parseTags(tagsRaw) }

    fun addSubtaskFromInput() {
        val clean = newSubtaskTitle.trim()
        if (clean.isNotEmpty()) {
            subtasks = subtasks + TaskSubtask(title = clean)
            newSubtaskTitle = ""
        }
    }

    fun addCustomTagFromInput() {
        val normalized = TaskTagPresets.normalizeTag(customTagInput)
        if (normalized.isNotEmpty()) {
            tagsRaw = TaskTagPresets.formatTags(parsedTags + normalized)
            customTagInput = ""
        }
    }

    fun buildTaskSnapshot(): StudyTask {
        val pendingSubtask = newSubtaskTitle.trim()
        val finalSubtasks = if (pendingSubtask.isNotEmpty()) {
            subtasks + TaskSubtask(title = pendingSubtask)
        } else {
            subtasks
        }
        val pendingTag = TaskTagPresets.normalizeTag(customTagInput)
        val finalTags = if (pendingTag.isNotEmpty()) parsedTags + pendingTag else parsedTags

        val recurrenceValue = when (recurrenceKind) {
            "EVERY_N_DAYS" -> {
                val n = everyNDaysText.toIntOrNull()?.coerceIn(1, 365) ?: 3
                if (n == 1) "DAILY" else "EVERY_N_DAYS:$n"
            }
            else -> recurrenceKind
        }
        val base = task ?: StudyTask(title = "", dueAt = dueAt)
        return base.copy(
            title = title.trim(),
            details = details.trim(),
            priority = priority,
            dueAt = dueAt,
            hasDueDate = hasDueDate,
            estimatedMinutes = estimatedMinutes.coerceAtLeast(0),
            subtasksJson = TaskSubtasks.encode(finalSubtasks),
            tags = TaskTagPresets.formatTags(finalTags),
            subjectId = subjectId,
            noteId = noteId,
            bookId = bookId,
            recurrence = recurrenceValue,
            afterCompletion = if (recurrenceValue == "NONE") false else afterCompletion
        )
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Nova tarefa" else "Editar tarefa") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Tarefa") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Detalhes (opcional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Prioridade", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (1..4).forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text("P$p") },
                            leadingIcon = {
                                Icon(Icons.Default.Flag, null, Modifier.size(16.dp), tint = themePriorityColor(p))
                            }
                        )
                    }
                }

                Text("Data / Inbox", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = !hasDueDate,
                        onClick = { hasDueDate = false },
                        label = { Text("Inbox (Sem data)") },
                        leadingIcon = { Icon(Icons.Default.Inbox, null, Modifier.size(16.dp)) }
                    )
                    listOf("Hoje" to 0, "Amanhã" to 1, "+1 sem" to 7).forEach { (label, offset) ->
                        FilterChip(
                            selected = hasDueDate && taskDayDiff(dueAt) == offset,
                            onClick = {
                                hasDueDate = true
                                dueAt = noonTimestamp(offsetDays = offset)
                            },
                            label = { Text(label) }
                        )
                    }
                    AssistChip(
                        onClick = {
                            val zone = java.time.ZoneId.systemDefault()
                            val d0 = java.time.Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    hasDueDate = true
                                    dueAt = java.time.LocalDate.of(y, m + 1, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                                },
                                d0.year,
                                d0.monthValue - 1,
                                d0.dayOfMonth
                            ).show()
                        },
                        label = {
                            Text(
                                if (hasDueDate) StudyDateFormats.formatEpoch(dueAt, StudyDateFormats.DATE_D_MMM_YYYY_PT)
                                else "Escolher data"
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) }
                    )
                }

                Text("Tempo estimado", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(0 to "Sem", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m", 90 to "90m").forEach { (mins, label) ->
                        FilterChip(
                            selected = !customMinutesMode && estimatedMinutes == mins,
                            onClick = {
                                customMinutesMode = false
                                estimatedMinutes = mins
                            },
                            label = { Text(label) }
                        )
                    }
                    FilterChip(
                        selected = customMinutesMode,
                        onClick = {
                            customMinutesMode = true
                            customMinutesText = if (estimatedMinutes > 0) estimatedMinutes.toString() else ""
                        },
                        label = { Text("Outro") }
                    )
                }
                if (customMinutesMode) {
                    OutlinedTextField(
                        value = customMinutesText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(4)
                            customMinutesText = digits
                            estimatedMinutes = (digits.toIntOrNull() ?: 0).coerceIn(0, 24 * 60)
                        },
                        label = { Text("Minutos personalizados") },
                        suffix = { Text("min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("Tags", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val allTagOptions = remember(parsedTags) { (TaskTagPresets.all + parsedTags).distinct() }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    allTagOptions.forEach { tag ->
                        val selected = tag in parsedTags
                        FilterChip(
                            selected = selected,
                            onClick = { tagsRaw = TaskTagPresets.toggleTag(tagsRaw, tag) },
                            label = { Text(tag) }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = customTagInput,
                        onValueChange = { customTagInput = it },
                        label = { Text("Nova tag (@...)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addCustomTagFromInput() }),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = ::addCustomTagFromInput, enabled = customTagInput.isNotBlank()) {
                        Icon(Icons.Default.AddCircle, "Adicionar tag", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Text("Repetir", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "NONE" to "Não",
                        "DAILY" to "Todo dia",
                        "WEEKDAYS" to "Seg–Sex",
                        "WEEKLY" to "Toda semana",
                        "MONTHLY" to "Todo mês",
                        "EVERY_N_DAYS" to "A cada N dias"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = recurrenceKind == key,
                            onClick = { recurrenceKind = key },
                            label = { Text(label) }
                        )
                    }
                }
                if (recurrenceKind == "EVERY_N_DAYS") {
                    OutlinedTextField(
                        value = everyNDaysText,
                        onValueChange = { everyNDaysText = it.filter { ch -> ch.isDigit() }.take(3) },
                        label = { Text("Intervalo em dias") },
                        suffix = { Text("dias") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (recurrenceKind != "NONE") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Após concluir", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "Conta o próximo prazo a partir da data em que concluir",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = afterCompletion, onCheckedChange = { afterCompletion = it })
                    }
                }

                val subjectById = remember(subjects) { subjects.associateBy { it.id } }
                val orderedSubjects = remember(subjects) { SubjectHierarchy.orderedHierarchically(subjects) }
                ExposedDropdownMenuBox(expanded = subjectMenu, onExpandedChange = { subjectMenu = !subjectMenu }) {
                    OutlinedTextField(
                        value = subjectById[subjectId]?.let { SubjectHierarchy.displayName(it, subjectById) } ?: "Geral",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Matéria") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subjectMenu) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = subjectMenu, onDismissRequest = { subjectMenu = false }) {
                        DropdownMenuItem(text = { Text("Geral") }, onClick = { subjectId = null; subjectMenu = false })
                        orderedSubjects.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(SubjectHierarchy.displayName(s, subjectById)) },
                                onClick = { subjectId = s.id; subjectMenu = false }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(expanded = noteMenu, onExpandedChange = { noteMenu = !noteMenu }) {
                    OutlinedTextField(
                        value = activeNotes.firstOrNull { it.id == noteId }?.title ?: "Nenhuma",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Nota ligada") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(noteMenu) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = noteMenu, onDismissRequest = { noteMenu = false }) {
                        DropdownMenuItem(text = { Text("Nenhuma") }, onClick = { noteId = null; noteMenu = false })
                        activeNotes.forEach { n ->
                            DropdownMenuItem(text = { Text(n.title) }, onClick = { noteId = n.id; noteMenu = false })
                        }
                    }
                }

                ExposedDropdownMenuBox(expanded = bookMenu, onExpandedChange = { bookMenu = !bookMenu }) {
                    val selectedBook = books.firstOrNull { it.id == bookId }
                    OutlinedTextField(
                        value = selectedBook?.let { "${it.title} (p.${it.currentPage})" } ?: "Nenhum",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Livro ligado") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(bookMenu) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                        DropdownMenuItem(text = { Text("Nenhum") }, onClick = { bookId = null; bookMenu = false })
                        books.forEach { b ->
                            DropdownMenuItem(
                                text = { Text("${b.title} (p.${b.currentPage}${b.totalPages?.let { "/$it" } ?: ""})") },
                                onClick = { bookId = b.id; bookMenu = false }
                            )
                        }
                    }
                }

                // Editor completo de subtarefas
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Subtarefas (${subtasks.count { it.done }}/${subtasks.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = {
                        val template = TaskSubtasks.fullStudyCycleTemplate()
                        val existingTitles = subtasks.map { it.title.trim().lowercase(Locale.ROOT) }.toSet()
                        val toAdd = template.filter { it.title.trim().lowercase(Locale.ROOT) !in existingTitles }
                        if (toAdd.isNotEmpty()) subtasks = subtasks + toAdd
                    },
                    label = { Text("Ciclo Completo: Teoria + Resumo + Questões + Revisão", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) }
                )

                subtasks.forEach { sub ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = sub.done,
                            onCheckedChange = { checked ->
                                subtasks = subtasks.map { if (it.id == sub.id) it.copy(done = checked) else it }
                            }
                        )
                        Text(
                            sub.title,
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = if (sub.done) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f)
                        )
                        if (onPromoteSubtask != null) {
                            IconButton(
                                onClick = {
                                    onPromoteSubtask(
                                        StudyTask(
                                            title = sub.title.trim(),
                                            priority = priority,
                                            dueAt = dueAt,
                                            hasDueDate = hasDueDate,
                                            subjectId = subjectId,
                                            noteId = noteId,
                                            bookId = bookId
                                        )
                                    )
                                    subtasks = subtasks.filterNot { it.id == sub.id }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Promover a tarefa independente", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(
                            onClick = { subtasks = subtasks.filterNot { it.id == sub.id } },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Close, "Remover etapa", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newSubtaskTitle,
                        onValueChange = { newSubtaskTitle = it },
                        label = { Text("Nova etapa") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addSubtaskFromInput() }),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = ::addSubtaskFromInput, enabled = newSubtaskTitle.isNotBlank()) {
                        Icon(Icons.Default.AddCircle, "Adicionar etapa", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                if (onOpenTutorForTask != null) {
                    OutlinedButton(
                        onClick = {
                            val snapshot = buildTaskSnapshot()
                            onDismiss()
                            onOpenTutorForTask(snapshot)
                        },
                        enabled = title.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Psychology, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Quebrar com Tutor IA")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(buildTaskSnapshot()) },
                enabled = title.isNotBlank()
            ) { Text("Salvar") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

// ---------- Gatilhos Pós-Conclusão ----------

@Composable
internal fun QuestionLogPromptDialog(
    task: StudyTask,
    subjects: List<Subject>,
    linkedNote: Note?,
    onDismiss: () -> Unit,
    onSubmit: (subjectId: Long?, topic: String, total: Int, correct: Int, openMistakes: Boolean) -> Unit,
    onOpenFlashcardsForNote: ((Long) -> Unit)? = null
) {
    var topic by remember(task.id) { mutableStateOf(task.title) }
    var selectedSubjectId by remember(task.id) { mutableStateOf(task.subjectId) }
    var totalText by remember(task.id) { mutableStateOf("10") }
    var correctText by remember(task.id) { mutableStateOf("") }
    var subjectMenu by remember { mutableStateOf(false) }

    val total = totalText.toIntOrNull() ?: 0
    val correct = (correctText.toIntOrNull() ?: 0).coerceAtLeast(0)
    val valid = total > 0 && correct in 0..total

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Registrar acertos no histórico?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Você concluiu uma tarefa de questões. Deseja salvar o desempenho?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Assunto / Lista") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (subjects.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = subjectMenu, onExpandedChange = { subjectMenu = !subjectMenu }) {
                        OutlinedTextField(
                            value = subjects.firstOrNull { it.id == selectedSubjectId }?.name ?: "Geral",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Matéria") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subjectMenu) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = subjectMenu, onDismissRequest = { subjectMenu = false }) {
                            DropdownMenuItem(text = { Text("Geral") }, onClick = { selectedSubjectId = null; subjectMenu = false })
                            subjects.forEach { s ->
                                DropdownMenuItem(text = { Text(s.name) }, onClick = { selectedSubjectId = s.id; subjectMenu = false })
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = totalText,
                        onValueChange = { totalText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Total") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = correctText,
                        onValueChange = { correctText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Acertos") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (total > 0 && correct > total) {
                    Text(
                        "Acertos não pode ser maior que o total.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (linkedNote != null && onOpenFlashcardsForNote != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenFlashcardsForNote(linkedNote.id)
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Style, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Revisar flashcards da nota \"${linkedNote.title}\"")
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        onSubmit(selectedSubjectId, topic.trim().ifBlank { task.title }, total, correct, true)
                    },
                    enabled = valid
                ) { Text("Salvar + Caderno de erros") }
                TextButton(
                    onClick = {
                        onSubmit(selectedSubjectId, topic.trim().ifBlank { task.title }, total, correct, false)
                    },
                    enabled = valid
                ) { Text("Apenas salvar acertos") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Agora não") }
        }
    )
}

@Composable
internal fun BookPagePromptDialog(
    task: StudyTask,
    books: List<Book>,
    onDismiss: () -> Unit,
    onAdvanceBook: (bookId: Long, newPage: Int) -> Unit
) {
    val initialBook = remember(task.id, books) {
        books.firstOrNull { it.id == task.bookId }
            ?: books.firstOrNull { task.subjectId != null && it.subjectId == task.subjectId && it.status == "READING" }
            ?: books.firstOrNull { it.status == "READING" }
            ?: books.firstOrNull()
    }
    if (initialBook == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var selectedBookId by remember(task.id) { mutableLongStateOf(initialBook.id) }
    val selectedBook = books.firstOrNull { it.id == selectedBookId } ?: initialBook
    var pageText by remember(selectedBookId) {
        mutableStateOf((selectedBook.currentPage + 1).coerceAtMost(selectedBook.totalPages ?: Int.MAX_VALUE).toString())
    }
    var bookMenu by remember { mutableStateOf(false) }

    val parsedPage = pageText.toIntOrNull()
    val valid = parsedPage != null &&
        parsedPage > selectedBook.currentPage &&
        (selectedBook.totalPages == null || parsedPage <= selectedBook.totalPages)

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Parou em qual página?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (task.bookId == null && books.size > 1) {
                    ExposedDropdownMenuBox(expanded = bookMenu, onExpandedChange = { bookMenu = !bookMenu }) {
                        OutlinedTextField(
                            value = selectedBook.title,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Livro") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(bookMenu) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                            books.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text("${b.title} (p.${b.currentPage})") },
                                    onClick = { selectedBookId = b.id; bookMenu = false }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "${selectedBook.title} · Atual: pág. ${selectedBook.currentPage}${selectedBook.totalPages?.let { " de $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(5, 10, 20, 30).forEach { delta ->
                        val target = (selectedBook.currentPage + delta).coerceAtMost(selectedBook.totalPages ?: Int.MAX_VALUE)
                        if (target > selectedBook.currentPage) {
                            FilterChip(
                                selected = parsedPage == target,
                                onClick = { pageText = target.toString() },
                                label = { Text("+$delta (p.$target)") }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Nova página") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { parsedPage?.let { onAdvanceBook(selectedBook.id, it) } },
                enabled = valid
            ) { Text("Atualizar progresso") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Agora não") }
        }
    )
}

@Composable
internal fun TodoFloatingBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (primaryActionLabel != null && onPrimaryAction != null) {
                TextButton(
                    onClick = onPrimaryAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        primaryActionLabel,
                        color = MaterialTheme.colorScheme.inversePrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                TextButton(
                    onClick = onSecondaryAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        secondaryActionLabel,
                        color = MaterialTheme.colorScheme.inversePrimary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, "Fechar", modifier = Modifier.size(18.dp))
            }
        }
    }
}
