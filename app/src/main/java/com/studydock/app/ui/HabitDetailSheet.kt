@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.Habit
import com.studydock.app.data.HabitArea
import com.studydock.app.data.HabitCompletion
import com.studydock.app.data.HabitNote
import com.studydock.app.data.HabitPatternsEngine
import com.studydock.app.data.HabitTask
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Helpers compartilhados internos para o estúdio de hábitos
// ---------------------------------------------------------------------------

private val ptBrHabitLocale = Locale("pt", "BR")

internal fun Double.habitStudioFormatNumber(): String =
    if (this % 1.0 == 0.0) toInt().toString() else String.format(ptBrHabitLocale, "%.1f", this)

internal fun habitStudioLocalDateFromKey(key: Long): LocalDate {
    val safeYear = (key / 10000L).toInt().coerceIn(1970, 2100)
    val safeMonth = ((key % 10000L) / 100L).toInt().coerceIn(1, 12)
    val maxDay = YearMonth.of(safeYear, safeMonth).lengthOfMonth()
    val safeDay = (key % 100L).toInt().coerceIn(1, maxDay)
    return LocalDate.of(safeYear, safeMonth, safeDay)
}

internal fun habitStudioKeyFromLocalDate(date: LocalDate): Long =
    date.year * 10000L + date.monthValue * 100L + date.dayOfMonth

internal fun habitStudioWeekdayIndexFromKey(key: Long): Int =
    habitStudioLocalDateFromKey(key).dayOfWeek.value - 1

internal fun habitStudioNoonMillisFromKey(key: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    habitStudioLocalDateFromKey(key).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

internal fun habitStudioCurrentDayKey(zone: ZoneId = ZoneId.systemDefault()): Long =
    habitStudioKeyFromLocalDate(LocalDate.now(zone))

internal fun habitStudioDayKeyFromMillis(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    habitStudioKeyFromLocalDate(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

internal fun habitStudioShiftDayKey(key: Long, amount: Int): Long =
    habitStudioKeyFromLocalDate(habitStudioLocalDateFromKey(key).plusDays(amount.toLong()))

internal fun habitStudioIsScheduledOn(habit: Habit, date: LocalDate): Boolean =
    (habit.repeatDaysMask and (1 shl (date.dayOfWeek.value - 1))) != 0

internal fun habitStudioIsScheduledOnKey(habit: Habit, dayKey: Long): Boolean =
    habitStudioIsScheduledOn(habit, habitStudioLocalDateFromKey(dayKey))

internal fun habitStudioIsScheduledOn(habit: Habit, date: Calendar): Boolean =
    (habit.repeatDaysMask and (1 shl ((date.get(Calendar.DAY_OF_WEEK) + 5) % 7))) != 0

internal fun habitStudioExponentialScore(habit: Habit, logs: List<HabitCompletion>, throughDay: Long): Int {
    val byDay = logs.associateBy { it.dayKey }
    val startDayKey = habitStudioDayKeyFromMillis(habit.startedAt.takeIf { it > 0L } ?: habit.createdAt)
    val startDate = habitStudioLocalDateFromKey(startDayKey)
    val endDate = habitStudioLocalDateFromKey(throughDay)
    if (endDate.isBefore(startDate)) return 0

    // Loop-style exponential smoothing over up to 90 days
    val windowStart = endDate.minusDays(89)
    var cursor = if (startDate.isAfter(windowStart)) startDate else windowStart
    val decay = 0.5.pow(1.0 / 13.0)
    var score = 0.0
    while (!cursor.isAfter(endDate)) {
        val key = habitStudioKeyFromLocalDate(cursor)
        val log = byDay[key]
        val isVacation = habit.vacationUntilDay > 0L && key <= habit.vacationUntilDay
        if (log?.status == "SKIPPED" || isVacation) {
            cursor = cursor.plusDays(1)
            continue
        }
        if (habitStudioIsScheduledOn(habit, cursor)) {
            val doneVal = when (log?.status) {
                "DONE" -> 1.0
                "PARTIAL" -> {
                    val target = habit.targetValue.coerceAtLeast(0.1)
                    if (habit.miniTarget > 0.0 && log.value >= habit.miniTarget && log.value < target) 0.75
                    else (log.value / target).coerceIn(0.0, 1.0)
                }
                else -> 0.0
            }
            score = score * decay + (1.0 - decay) * doneVal
        }
        cursor = cursor.plusDays(1)
    }
    return (score * 100.0).roundToInt().coerceIn(0, 100)
}

internal fun habitStudioCurrentStreak(habit: Habit, logs: List<HabitCompletion>, throughDay: Long): Int {
    val byDay = logs.associateBy { it.dayKey }
    var date = habitStudioLocalDateFromKey(throughDay)
    val startDayKey = habitStudioDayKeyFromMillis(habit.startedAt.takeIf { it > 0L } ?: habit.createdAt)
    val minGoalToday = if (habit.miniTarget > 0.0) habit.miniTarget else habit.targetValue
    val isDoneToday = byDay[throughDay]?.let { it.status == "DONE" || (it.status == "PARTIAL" && it.value >= minGoalToday) } == true
    if (throughDay == habitStudioCurrentDayKey() && !isDoneToday) {
        date = date.minusDays(1)
    }
    var streak = 0
    repeat(3660) {
        val dayKey = habitStudioKeyFromLocalDate(date)
        if (dayKey < startDayKey) return streak
        if (habitStudioIsScheduledOn(habit, date)) {
            val log = byDay[dayKey]
            val isVacation = habit.vacationUntilDay > 0L && dayKey <= habit.vacationUntilDay
            if (log?.status == "SKIPPED" || isVacation) {
                date = date.minusDays(1)
                return@repeat
            }
            val minGoal = if (habit.miniTarget > 0.0) habit.miniTarget else habit.targetValue
            if (log?.status != "DONE" && !(log?.status == "PARTIAL" && log.value >= minGoal)) {
                return streak
            }
            streak++
        }
        date = date.minusDays(1)
    }
    return streak
}

internal fun habitStudioBestStreak(habit: Habit, logs: List<HabitCompletion>): Int {
    if (logs.isEmpty()) return 0
    val skippedSet = logs.filter { it.status == "SKIPPED" }.map { it.dayKey }.toHashSet()
    val ordered = logs.filter { it.status == "DONE" || (it.status == "PARTIAL" && it.value >= habit.targetValue) }
        .map { it.dayKey }
        .distinct()
        .sorted()
    if (ordered.isEmpty()) return 0
    val doneSet = ordered.toHashSet()
    var cursorDate = habitStudioLocalDateFromKey(ordered.first())
    val lastDate = habitStudioLocalDateFromKey(maxOf(ordered.last(), habitStudioCurrentDayKey()))
    var current = 0
    var best = 0
    var guard = 0
    while (!cursorDate.isAfter(lastDate) && guard < 3660) {
        if (habitStudioIsScheduledOn(habit, cursorDate)) {
            val key = habitStudioKeyFromLocalDate(cursorDate)
            if (key in doneSet) {
                current++
                if (current > best) best = current
            } else if (key !in skippedSet) {
                current = 0
            }
        }
        cursorDate = cursorDate.plusDays(1)
        guard++
    }
    return best
}

internal fun habitStudioElapsedSince(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    val days = totalMinutes / (24 * 60)
    val hours = totalMinutes % (24 * 60) / 60
    val minutes = totalMinutes % 60
    return buildList {
        if (days > 0) add("$days ${if (days == 1L) "dia" else "dias"}")
        if (hours > 0 || days > 0) add("$hours h")
        add("$minutes min")
    }.joinToString(" ")
}

internal fun habitStudioInclusiveDaysSince(timestampMillis: Long, referenceMillis: Long = System.currentTimeMillis()): Long {
    val zone = ZoneId.systemDefault()
    val startDate = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
    val endDate = Instant.ofEpochMilli(referenceMillis).atZone(zone).toLocalDate()
    val diff = ChronoUnit.DAYS.between(startDate, endDate)
    return if (diff < 0L) 0L else diff + 1L
}

internal fun habitStudioFormatWeekdaysMask(mask: Int): String {
    if (mask == 127) return "Todos os dias"
    if (mask == 31) return "Dias úteis (Seg–Sex)"
    if (mask == 96) return "Fins de semana"
    val names = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
    val active = names.filterIndexed { i, _ -> (mask and (1 shl i)) != 0 }
    return if (active.isEmpty()) "Personalizado" else active.joinToString(", ")
}

// ---------------------------------------------------------------------------
// Sheet completo de Detalhes do Hábito: Progresso | Notas | Sobre
// ---------------------------------------------------------------------------

private enum class HabitDetailTab(val label: String) {
    PROGRESS("Progresso"),
    NOTES("Notas"),
    ABOUT("Sobre")
}

@Composable
fun HabitDetailSheet(
    habit: Habit,
    dayKey: Long,
    completions: List<HabitCompletion>,
    notes: List<HabitNote>,
    tasks: List<HabitTask>,
    @Suppress("UNUSED_PARAMETER") areas: List<HabitArea> = emptyList(),
    onDismiss: () -> Unit,
    onLog: (dayKey: Long, status: String, value: Double, note: String?) -> Unit,
    onUpdateHabit: (Habit) -> Unit,
    onDeleteHabit: (Habit) -> Unit,
    onAddNote: (habitId: Long, content: String, dayKey: Long, mood: String) -> Unit,
    onUpdateNote: (HabitNote) -> Unit,
    onDeleteNote: (HabitNote) -> Unit,
    onAddTask: (habitId: Long, title: String) -> Unit,
    onToggleTask: (taskId: Long, completed: Boolean) -> Unit,
    onDeleteTask: (HabitTask) -> Unit,
    onRequestEditHabit: (Habit) -> Unit = {}
) {
    val context = LocalContext.current
    val habitAccent = habitColorOrDefault(habit.color)
    var activeTab by remember(habit.id) { mutableStateOf(HabitDetailTab.PROGRESS) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showInsightDialog by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<HabitNote?>(null) }

    val habitCompletions = remember(completions, habit.id) {
        completions.filter { it.habitId == habit.id }
    }
    val habitNotes = remember(notes, habit.id) {
        notes.filter { it.habitId == habit.id }.sortedWith(compareByDescending<HabitNote> { it.dayKey }.thenByDescending { it.createdAt })
    }
    val habitTasks = remember(tasks, habit.id) {
        tasks.filter { it.habitId == habit.id }.sortedWith(compareBy<HabitTask> { it.completed }.thenBy { it.createdAt })
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            floatingActionButton = {
                if (activeTab == HabitDetailTab.NOTES) {
                    FloatingActionButton(
                        onClick = {
                            editingNote = null
                            showNoteDialog = true
                        },
                        shape = CircleShape,
                        containerColor = habitAccent,
                        contentColor = habitContrastContentColor(habitAccent)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Nova nota")
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Cabeçalho estilo Habitify: ← [Badge + Título] ✦ ⋮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                    HabitVisualBadge(
                        habit = habit,
                        size = 30.dp,
                        cornerRadius = 8.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = habit.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showInsightDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = "Resumo inteligente",
                            tint = habitAccent
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Mais opções"
                            )
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Editar hábito") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = {
                                    menuExpanded = false
                                    onRequestEditHabit(habit)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Ícone e cor") },
                                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = habitAccent) },
                                onClick = {
                                    menuExpanded = false
                                    showIconPicker = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Pular dia selecionado") },
                                leadingIcon = { Icon(Icons.Rounded.SkipNext, null) },
                                onClick = {
                                    menuExpanded = false
                                    onLog(dayKey, "SKIPPED", 0.0, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (habit.kind == "BAD") "Registrar recaída" else "Marcar como falhado") },
                                leadingIcon = { Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    onLog(dayKey, "FAILED", 0.0, null)
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Excluir hábito", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteConfirm = true
                                }
                            )
                        }
                    }
                }

                // Abas: Progresso | Notas | Sobre (com indicador pill curto estilo imagem 3 & 4)
                val selectedIndex = HabitDetailTab.entries.indexOf(activeTab)
                TabRow(
                    selectedTabIndex = selectedIndex,
                    containerColor = Color.Transparent,
                    contentColor = habitAccent,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) },
                    indicator = { tabPositions ->
                        val tabPos = tabPositions[selectedIndex]
                        Box(
                            modifier = Modifier
                                .tabIndicatorOffset(tabPos)
                                .padding(horizontal = 32.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(habitAccent)
                        )
                    }
                ) {
                    HabitDetailTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        Tab(
                            selected = selected,
                            onClick = { activeTab = tab },
                            text = {
                                Text(
                                    text = tab.label,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) habitAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                when (activeTab) {
                    HabitDetailTab.PROGRESS -> HabitDetailProgressTab(
                        habit = habit,
                        habitAccent = habitAccent,
                        dayKey = dayKey,
                        completions = habitCompletions,
                        onLog = onLog
                    )
                    HabitDetailTab.NOTES -> HabitDetailNotesTab(
                        habitAccent = habitAccent,
                        notes = habitNotes,
                        onEditNote = { note ->
                            editingNote = note
                            showNoteDialog = true
                        },
                        onDeleteNote = onDeleteNote
                    )
                    HabitDetailTab.ABOUT -> HabitDetailAboutTab(
                        habit = habit,
                        habitAccent = habitAccent,
                        tasks = habitTasks,
                        onUpdateDescription = { desc -> onUpdateHabit(habit.copy(description = desc)) },
                        onPickStartDate = {
                            val zone = ZoneId.systemDefault()
                            val currentStart = habit.startedAt.takeIf { it > 0L } ?: habit.createdAt
                            val d0 = Instant.ofEpochMilli(currentStart).atZone(zone).toLocalDate()
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val chosen = LocalDate.of(y, m + 1, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                                    onUpdateHabit(habit.copy(startedAt = chosen))
                                },
                                d0.year,
                                d0.monthValue - 1,
                                d0.dayOfMonth
                            ).show()
                        },
                        onAddTask = { title -> onAddTask(habit.id, title) },
                        onToggleTask = onToggleTask,
                        onDeleteTask = onDeleteTask
                    )
                }
            }
        }
    }

    if (showIconPicker) {
        HabitIconColorPicker(
            selectedIconKey = habit.iconKey,
            selectedEmoji = habit.emoji,
            selectedColor = habit.color,
            onConfirm = { newIconKey, newEmoji, newColor ->
                onUpdateHabit(habit.copy(iconKey = newIconKey, emoji = newEmoji, color = newColor))
                showIconPicker = false
            },
            onDismiss = { showIconPicker = false }
        )
    }

    if (showNoteDialog) {
        HabitNoteEditorDialog(
            initialNote = editingNote,
            defaultDayKey = dayKey,
            habitAccent = habitAccent,
            onDismiss = {
                showNoteDialog = false
                editingNote = null
            },
            onSave = { content, noteDayKey, mood ->
                val existing = editingNote
                if (existing != null) {
                    onUpdateNote(existing.copy(content = content, dayKey = noteDayKey, mood = mood))
                } else {
                    onAddNote(habit.id, content, noteDayKey, mood)
                }
                showNoteDialog = false
                editingNote = null
            }
        )
    }

    if (showInsightDialog) {
        val currentStreak = habitStudioCurrentStreak(habit, habitCompletions, dayKey)
        val bestStreak = habitStudioBestStreak(habit, habitCompletions)
        val snapshot = HabitPatternsEngine.computePatterns(
            habits = listOf(habit),
            completions = habitCompletions,
            todayDayKey = dayKey,
            rangeDays = 28,
            kindFilter = "ALL"
        )
        AlertDialog(
            onDismissRequest = { showInsightDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = habitAccent)
                    Text("Ritmo do hábito", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sequência atual: $currentStreak ${if (currentStreak == 1) "dia" else "dias"} · Recorde: $bestStreak")
                    Text(
                        snapshot.bestZone?.let { "Seu melhor horário nos últimos 28 dias foi ${it.label} (${it.rangeLabel})." }
                            ?: "Continue registrando por pelo menos 3 dias para mapear seu horário de pico."
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = habit.freezeTokens > 0,
                            onClick = {
                                onLog(dayKey, "SKIPPED", 0.0, "Streak Freeze")
                                onUpdateHabit(habit.copy(freezeTokens = (habit.freezeTokens - 1).coerceAtLeast(0)))
                                showInsightDialog = false
                            }
                        ) {
                            Text("❄️ Escudo (${habit.freezeTokens})")
                        }
                        OutlinedButton(
                            onClick = {
                                val vacKey = habitStudioKeyFromLocalDate(habitStudioLocalDateFromKey(dayKey).plusDays(7))
                                onUpdateHabit(habit.copy(vacationUntilDay = vacKey))
                                showInsightDialog = false
                            }
                        ) {
                            Text("🏖️ Férias +7d")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onLog(dayKey, "DONE", habit.targetValue, null)
                        showInsightDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = habitAccent, contentColor = habitContrastContentColor(habitAccent))
                ) {
                    Text(if (habit.kind == "BAD") "Marcar dia limpo" else "Concluir hoje")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInsightDialog = false }) { Text("Fechar") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Excluir hábito?") },
            text = { Text("“${habit.title}” e todo o histórico, notas e tarefas vinculadas serão removidos.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteHabit(habit)
                        onDismiss()
                    }
                ) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Aba 1: Progresso do Hábito
// ---------------------------------------------------------------------------

@Composable
private fun HabitDetailProgressTab(
    habit: Habit,
    habitAccent: Color,
    dayKey: Long,
    completions: List<HabitCompletion>,
    onLog: (dayKey: Long, status: String, value: Double, note: String?) -> Unit
) {
    val semantic = LocalStudySemanticColors.current
    val dayMap = remember(completions) { completions.associateBy { it.dayKey } }
    val selectedCompletion = dayMap[dayKey]
    val selectedDate = remember(dayKey) { habitStudioLocalDateFromKey(dayKey) }
    val dateLabel = remember(dayKey) {
        StudyDateFormats.formatLocalDate(selectedDate, StudyDateFormats.FULL_WEEKDAY_DAY_MONTH_PT).replaceFirstChar { it.uppercase() }
    }

    var monthOffset by remember(dayKey) { mutableIntStateOf(0) }
    val monthDate = remember(dayKey, monthOffset) {
        selectedDate.withDayOfMonth(1).plusMonths(monthOffset.toLong())
    }

    val currentStreak = remember(habit, completions, dayKey) { habitStudioCurrentStreak(habit, completions, dayKey) }
    val bestStreak = remember(habit, completions) { habitStudioBestStreak(habit, completions) }
    val totalDone = remember(completions) { completions.count { it.status == "DONE" || it.status == "PARTIAL" } }
    val pattern = remember(habit, completions, dayKey) {
        HabitPatternsEngine.computePatterns(
            habits = listOf(habit),
            completions = completions,
            todayDayKey = dayKey,
            rangeDays = 28,
            kindFilter = "ALL"
        )
    }
    val adherencePercent = remember(habit, dayMap, dayKey) {
        var scheduled = 0
        var score = 0.0
        val startKey = habitStudioDayKeyFromMillis(habit.startedAt.takeIf { it > 0L } ?: habit.createdAt)
        val baseDate = habitStudioLocalDateFromKey(dayKey)
        for (offset in -27..0) {
            val d = baseDate.plusDays(offset.toLong())
            val k = habitStudioKeyFromLocalDate(d)
            if (k < startKey) continue
            if (habitStudioIsScheduledOn(habit, d)) {
                val c = dayMap[k]
                if (c?.status != "SKIPPED") {
                    scheduled++
                    score += when (c?.status) {
                        "DONE" -> 1.0
                        "PARTIAL" -> (c.value / habit.targetValue.coerceAtLeast(0.1)).coerceIn(0.0, 1.0)
                        else -> 0.0
                    }
                }
            }
        }
        if (scheduled == 0) 0 else ((score * 100.0) / scheduled).roundToInt().coerceIn(0, 100)
    }

    var amountText by remember(habit.id, dayKey, selectedCompletion?.value) {
        mutableStateOf((selectedCompletion?.value ?: 0.0).habitStudioFormatNumber())
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(habit.kind) {
        while (habit.kind == "BAD") {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }

    val lastRelapseAt = remember(completions) {
        completions.filter { it.status == "FAILED" }.maxOfOrNull { log ->
            if (log.loggedAt > 0L) log.loggedAt else habitStudioNoonMillisFromKey(log.dayKey)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card de registro rápido do dia selecionado
        StudyCard(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    val statusBadge = when (selectedCompletion?.status) {
                        "DONE" -> "Concluído" to semantic.success
                        "PARTIAL" -> "Parcial" to semantic.warning
                        "SKIPPED" -> "Pulado" to MaterialTheme.colorScheme.onSurfaceVariant
                        "FAILED" -> if (habit.kind == "BAD") "Recaída" to MaterialTheme.colorScheme.error else "Falhado" to MaterialTheme.colorScheme.error
                        else -> "Pendente" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusBadge.second.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = statusBadge.first,
                            color = statusBadge.second,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (habit.kind == "BAD") {
                    val since = (lastRelapseAt ?: (habit.startedAt.takeIf { it > 0L } ?: habit.createdAt)).coerceAtMost(now)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = habitAccent.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Rounded.Timer, null, tint = habitAccent, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(4.dp))
                            Text("Sem praticar há", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = habitStudioElapsedSince(now - since),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = habitAccent
                            )
                            Text(
                                text = if (lastRelapseAt == null) "desde o início do hábito" else "desde a última recaída",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onLog(dayKey, "DONE", 1.0, null) },
                            colors = ButtonDefaults.buttonColors(containerColor = habitAccent, contentColor = habitContrastContentColor(habitAccent)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sem recaída", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = { onLog(dayKey, "FAILED", 0.0, null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text("Recaída", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onLog(dayKey, "SKIPPED", 0.0, null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.SkipNext, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pular dia", maxLines = 1, softWrap = false)
                        }
                        if (selectedCompletion != null) {
                            TextButton(
                                onClick = { onLog(dayKey, "NONE", 0.0, null) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Clear, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Limpar registro", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                } else {
                    val entered = amountText.replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    val ratio = (entered / habit.targetValue.coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (habit.kind == "LIMIT" && entered > habit.targetValue) MaterialTheme.colorScheme.error else habitAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val next = (entered - 1.0).coerceAtLeast(0.0)
                                amountText = next.habitStudioFormatNumber()
                                val nextStatus = when {
                                    next <= 0.0 -> "NONE"
                                    habit.kind == "LIMIT" -> if (next <= habit.targetValue) "DONE" else "FAILED"
                                    next >= habit.targetValue -> "DONE"
                                    else -> "PARTIAL"
                                }
                                onLog(dayKey, nextStatus, next, null)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Remove, "Diminuir", Modifier.size(18.dp))
                        }
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            label = { Text("${habit.targetUnit} / ${habit.targetValue.habitStudioFormatNumber()}") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                val next = entered + 1.0
                                amountText = next.habitStudioFormatNumber()
                                val nextStatus = when {
                                    habit.kind == "LIMIT" -> if (next <= habit.targetValue) "DONE" else "FAILED"
                                    next >= habit.targetValue -> "DONE"
                                    else -> "PARTIAL"
                                }
                                onLog(dayKey, nextStatus, next, null)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Add, "Aumentar", Modifier.size(18.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (habit.kind == "LIMIT") {
                                    onLog(dayKey, if (entered <= habit.targetValue) "DONE" else "FAILED", entered, null)
                                } else {
                                    val target = entered.takeIf { it > 0.0 } ?: habit.targetValue
                                    onLog(dayKey, if (target >= habit.targetValue) "DONE" else "PARTIAL", target, null)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = habitAccent, contentColor = habitContrastContentColor(habitAccent)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (habit.kind == "LIMIT") "Salvar total" else "Concluir", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = { onLog(dayKey, "SKIPPED", 0.0, null) }
                        ) {
                            Icon(Icons.Rounded.SkipNext, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pular", maxLines = 1, softWrap = false)
                        }
                        if (selectedCompletion != null) {
                            IconButton(onClick = { onLog(dayKey, "NONE", 0.0, null) }) {
                                Icon(Icons.Rounded.Clear, "Limpar registro")
                            }
                        }
                    }
                }
            }
        }

        // Métricas resumidas
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailMetricTile("Sequência", "$currentStreak d", habitAccent, Modifier.weight(1f))
            DetailMetricTile("Recorde", "$bestStreak d", habitAccent, Modifier.weight(1f))
            DetailMetricTile("Adesão 28d", "$adherencePercent%", semantic.success, Modifier.weight(1f))
            DetailMetricTile("Total", totalDone.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }

        // Calendário mensal do hábito
        StudyCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val monthTitle = remember(monthDate) {
                    StudyDateFormats.formatLocalDate(monthDate, StudyDateFormats.MONTH_YEAR_SHORT_PT).replaceFirstChar { it.uppercase() }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { monthOffset-- }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Mês anterior")
                    }
                    Text(
                        text = monthTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { monthOffset++ }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Próximo mês")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("S", "T", "Q", "Q", "S", "S", "D").forEach { d ->
                        Text(
                            text = d,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                val daysInMonth = monthDate.lengthOfMonth()
                val firstDowOffset = monthDate.dayOfWeek.value - 1
                val cells = buildList<Long?> {
                    repeat(firstDowOffset) { add(null) }
                    for (day in 1..daysInMonth) {
                        add(habitStudioKeyFromLocalDate(monthDate.withDayOfMonth(day)))
                    }
                    while (size % 7 != 0) add(null)
                }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        week.forEach { cellKey ->
                            if (cellKey == null) {
                                Spacer(Modifier.weight(1f).height(34.dp))
                            } else {
                                val comp = dayMap[cellKey]
                                val isSelected = cellKey == dayKey
                                val (bg, fg) = when (comp?.status) {
                                    "DONE" -> habitAccent to habitContrastContentColor(habitAccent)
                                    "PARTIAL" -> semantic.warning to semantic.onWarning
                                    "FAILED" -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
                                    "SKIPPED" -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f) to MaterialTheme.colorScheme.onSurface
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bg)
                                        .then(
                                            if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onBackground, RoundedCornerShape(8.dp))
                                            else Modifier
                                        )
                                        .clickable {
                                            val nextStatus = when (comp?.status) {
                                                null, "NONE" -> "DONE"
                                                "DONE" -> "SKIPPED"
                                                "SKIPPED" -> "FAILED"
                                                else -> "NONE"
                                            }
                                            val nextValue = if (nextStatus == "DONE") habit.targetValue else 0.0
                                            onLog(cellKey, nextStatus, nextValue, null)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (cellKey % 100).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (comp != null || isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = fg,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = "Toque em um dia para alternar: Concluído → Pulado → Falhado → Limpar.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Horários de conclusão do hábito
        StudyCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Zonas de horário (28 dias)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val maxCount = (pattern.peakZones.maxOfOrNull { it.completionCount } ?: 0).coerceAtLeast(1)
                pattern.peakZones.forEach { stat ->
                    val fraction = if (stat.completionCount == 0) 0f else (stat.completionCount.toFloat() / maxCount).coerceIn(0.12f, 1f)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stat.zone.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        ) {
                            if (fraction > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (pattern.bestZone == stat.zone) habitAccent else habitAccent.copy(alpha = 0.55f))
                                )
                            }
                        }
                        Text(
                            text = "${stat.completionCount}x",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(34.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailMetricTile(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    StudyCard(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Aba 2: Notas (fiel à imagem 3: navegador < Nov, 2026 v >, cards com rodapé data)
// ---------------------------------------------------------------------------

@Composable
private fun HabitDetailNotesTab(
    habitAccent: Color,
    notes: List<HabitNote>,
    onEditNote: (HabitNote) -> Unit,
    onDeleteNote: (HabitNote) -> Unit
) {
    var filterByMonth by remember { mutableStateOf(true) }
    var monthDate by remember {
        mutableStateOf(LocalDate.now().withDayOfMonth(1))
    }
    var monthDropdown by remember { mutableStateOf(false) }

    val activeYear = monthDate.year
    val activeMonth = monthDate.monthValue
    val activeMonthPrefix = activeYear * 100L + activeMonth

    val monthTitle = remember(monthDate, filterByMonth) {
        if (!filterByMonth) "Todos os meses"
        else StudyDateFormats.formatLocalDate(monthDate, StudyDateFormats.MONTH_YEAR_SHORT_PT).replace(".", "").replaceFirstChar { it.uppercase() }
    }

    val visibleNotes = remember(notes, filterByMonth, activeMonthPrefix) {
        if (!filterByMonth) notes
        else notes.filter { (it.dayKey / 100L) == activeMonthPrefix }
    }

    val noteMonths = remember(notes) {
        notes.map { it.dayKey / 100L }.distinct().sortedDescending()
    }

    Column(Modifier.fillMaxSize()) {
        // Navegador < Nov, 2026 v >
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    filterByMonth = true
                    monthDate = monthDate.minusMonths(1)
                }
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Mês anterior")
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { monthDropdown = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = monthTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Selecionar mês",
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(expanded = monthDropdown, onDismissRequest = { monthDropdown = false }) {
                    DropdownMenuItem(
                        text = { Text("Todos os meses (${notes.size})") },
                        onClick = {
                            filterByMonth = false
                            monthDropdown = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Mês atual") },
                        onClick = {
                            filterByMonth = true
                            monthDate = LocalDate.now().withDayOfMonth(1)
                            monthDropdown = false
                        }
                    )
                    noteMonths.forEach { ym ->
                        val y = (ym / 100L).toInt().coerceIn(1970, 2100)
                        val m = (ym % 100L).toInt().coerceIn(1, 12)
                        val candidateDate = LocalDate.of(y, m, 1)
                        val label = StudyDateFormats.formatLocalDate(candidateDate, StudyDateFormats.MONTH_YEAR_SHORT_PT).replace(".", "").replaceFirstChar { it.uppercase() }
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                filterByMonth = true
                                monthDate = candidateDate
                                monthDropdown = false
                            }
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    filterByMonth = true
                    monthDate = monthDate.plusMonths(1)
                }
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Próximo mês")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (visibleNotes.isEmpty()) {
                item(key = "notes_empty", contentType = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, bottom = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (filterByMonth) "Nenhuma nota em $monthTitle" else "Nenhuma nota registrada ainda",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Toque no botão + para registrar pensamentos, gatilhos ou conquistas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(
                    items = visibleNotes,
                    key = { it.id },
                    contentType = { "habitNote" }
                ) { note ->
                    HabitNoteCard(
                        note = note,
                        habitAccent = habitAccent,
                        onClick = { onEditNote(note) },
                        onDelete = { onDeleteNote(note) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HabitNoteCard(
    note: HabitNote,
    habitAccent: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateLabel = remember(note.dayKey) {
        val dateLd = habitStudioLocalDateFromKey(note.dayKey)
        val dow = StudyDateFormats.formatLocalDate(dateLd, StudyDateFormats.WEEKDAY_EEE_PT).replace(".", "").replaceFirstChar { it.uppercase() }
        val mon = StudyDateFormats.formatLocalDate(dateLd, StudyDateFormats.MONTH_MMM_PT).replace(".", "").replaceFirstChar { it.uppercase() }
        val day = StudyDateFormats.formatLocalDate(dateLd, StudyDateFormats.DAY_D_PT)
        "$dow, $mon $day"
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (note.mood.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = habitAccent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = note.mood,
                            style = MaterialTheme.typography.labelSmall,
                            color = habitAccent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            // Rodapé inferior escuro com ações discretas à esquerda e data à direita (imagem 3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Editar nota",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Excluir nota",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun HabitNoteEditorDialog(
    initialNote: HabitNote?,
    defaultDayKey: Long,
    habitAccent: Color,
    onDismiss: () -> Unit,
    onSave: (content: String, dayKey: Long, mood: String) -> Unit
) {
    val context = LocalContext.current
    var content by remember(initialNote?.id) { mutableStateOf(initialNote?.content.orEmpty()) }
    var selectedDayKey by remember(initialNote?.id) { mutableLongStateOf(initialNote?.dayKey ?: defaultDayKey) }
    var mood by remember(initialNote?.id) { mutableStateOf(initialNote?.mood.orEmpty()) }
    val moods = listOf("Focado", "Motivado", "Fácil", "Difícil", "Cansado")
    val dateLabel = remember(selectedDayKey) {
        StudyDateFormats.formatLocalDate(habitStudioLocalDateFromKey(selectedDayKey), StudyDateFormats.WEEKDAY_D_DE_MMM_YYYY_PT)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialNote == null) "Nova nota" else "Editar nota", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val d0 = habitStudioLocalDateFromKey(selectedDayKey)
                        DatePickerDialog(
                            context,
                            { _, y, m, d -> selectedDayKey = y * 10000L + (m + 1) * 100L + d },
                            d0.year,
                            d0.monthValue - 1,
                            d0.dayOfMonth
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(dateLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Como foi hoje?") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Estado / Humor (opcional)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    moods.forEach { option ->
                        FilterChip(
                            selected = mood == option,
                            onClick = { mood = if (mood == option) "" else option },
                            label = { Text(option, maxLines = 1, softWrap = false) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (content.isNotBlank()) onSave(content.trim(), selectedDayKey, mood) },
                enabled = content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = habitAccent, contentColor = habitContrastContentColor(habitAccent))
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

// ---------------------------------------------------------------------------
// Aba 3: Sobre (fiel à imagem 4: Descrição c/ lápis, Tarefas + Nova Tarefa, Info CRIADO EM / INICIADO EM)
// ---------------------------------------------------------------------------

@Composable
private fun HabitDetailAboutTab(
    habit: Habit,
    habitAccent: Color,
    tasks: List<HabitTask>,
    onUpdateDescription: (String) -> Unit,
    onPickStartDate: () -> Unit,
    onAddTask: (String) -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    onDeleteTask: (HabitTask) -> Unit
) {
    var editingDescription by remember { mutableStateOf(false) }
    var draftDescription by remember(habit.id, habit.description) { mutableStateOf(habit.description) }
    var addingTask by remember { mutableStateOf(false) }
    var draftTaskTitle by remember { mutableStateOf("") }

    val createdDate = remember(habit.createdAt) {
        val text = StudyDateFormats.formatEpoch(habit.createdAt, StudyDateFormats.DATE_D_DE_MMM_DE_YYYY_PT)
        val days = habitStudioInclusiveDaysSince(habit.createdAt)
        "$text ($days ${if (days == 1L) "dia" else "dias"})"
    }
    val startedMillis = habit.startedAt.takeIf { it > 0L } ?: habit.createdAt
    val startedDate = remember(startedMillis) {
        val text = StudyDateFormats.formatEpoch(startedMillis, StudyDateFormats.DATE_D_DE_MMM_DE_YYYY_PT)
        val days = habitStudioInclusiveDaysSince(startedMillis)
        "$text ($days ${if (days == 1L) "dia" else "dias"})"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp)
    ) {
        // Seção Descrição
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editingDescription = true }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Descrição",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { editingDescription = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Editar descrição",
                        tint = habitAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (habit.description.isNotBlank()) {
                Text(
                    text = habit.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    text = "Toque no lápis para adicionar um motivo, meta pessoal ou observação.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // Seção Tarefas
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Tarefas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleTask(task.id, !task.completed) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (task.completed) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (task.completed) "Concluída" else "Pendente",
                        tint = if (task.completed) habitAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onDeleteTask(task) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remover tarefa",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { addingTask = true }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Nova Tarefa",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = addingTask) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draftTaskTitle,
                        onValueChange = { draftTaskTitle = it },
                        label = { Text("Ex.: Separar garrafa d'água") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (draftTaskTitle.isNotBlank()) {
                                onAddTask(draftTaskTitle.trim())
                                draftTaskTitle = ""
                                addingTask = false
                            }
                        },
                        enabled = draftTaskTitle.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = habitAccent, contentColor = habitContrastContentColor(habitAccent))
                    ) {
                        Text("Criar")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            Text(
                text = "Tarefas ajudam você a lembrar ações únicas para se preparar melhor para este hábito.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // Seção Info (CRIADO EM / INICIADO EM + detalhes extras)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Info",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            AboutInfoBlock(label = "CRIADO EM", value = createdDate)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPickStartDate)
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "INICIADO EM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Alterar",
                        style = MaterialTheme.typography.labelSmall,
                        color = habitAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = startedDate,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            AboutInfoBlock(
                label = "TIPO & ÁREA",
                value = "${when (habit.kind) { "BAD" -> "Parar (Mau hábito)"; "LIMIT" -> "Reduzir / Limite"; else -> "Praticar (Bom hábito)" }} · ${habit.area}"
            )
            AboutInfoBlock(
                label = "FREQUÊNCIA",
                value = habitStudioFormatWeekdaysMask(habit.repeatDaysMask)
            )
            if (habit.kind != "BAD") {
                AboutInfoBlock(
                    label = if (habit.kind == "LIMIT") "LIMITE" else "META",
                    value = "${habit.targetValue.habitStudioFormatNumber()} ${habit.targetUnit} por ${habit.targetPeriod}"
                )
            }
            AboutInfoBlock(
                label = "LEMBRETE",
                value = habit.reminderMinutes?.let {
                    String.format(ptBrHabitLocale, "%02d:%02d", it / 60, it % 60)
                } ?: "Desativado"
            )
        }
    }

    if (editingDescription) {
        AlertDialog(
            onDismissRequest = { editingDescription = false },
            title = { Text("Descrição do hábito", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = draftDescription,
                    onValueChange = { draftDescription = it },
                    label = { Text("Por que esse hábito importa?") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateDescription(draftDescription.trim())
                        editingDescription = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = habitAccent, contentColor = habitContrastContentColor(habitAccent))
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingDescription = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun AboutInfoBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
