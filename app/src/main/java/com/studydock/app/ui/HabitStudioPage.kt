@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.FocusTimeZone
import com.studydock.app.data.Habit
import com.studydock.app.data.HabitArea
import com.studydock.app.data.HabitCompletion
import com.studydock.app.data.HabitIconCatalog
import com.studydock.app.data.HabitNote
import com.studydock.app.data.HabitPatternsEngine
import com.studydock.app.data.HabitPatternsSnapshot
import com.studydock.app.data.HabitTask
import com.studydock.app.habits.HabitReminderScheduler
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

private val studioPtLocale = Locale("pt", "BR")

private data class StudioPendingHabitReminder(
    val id: Long,
    val title: String,
    val minutes: Int,
    val daysMask: Int
)

/**
 * Experiência completa de Hábitos estilo Habitify:
 * - Aba **Meu dia** com ícones/emojis coloridos, streaks, ações rápidas (`DONE`, `+1`, `SKIPPED`, `FAILED`) e filtros por área.
 * - Aba **Progresso** com switcher `Bons Hábitos | Hábitos Ruins`, filtros de janela (`7/14/28/90d`), hábito e área,
 *   cards **Zonas de Pico de Foco** e **Hábitos Perdidos** (Donut `Pulados` vs `Falhados`), calendário e ritmo semanal.
 * - Abre [HabitDetailSheet] completo (`Progresso | Notas | Sobre`) e [HabitIconColorPicker].
 */
@Composable
fun HabitsStudioPage(
    habits: List<Habit>,
    areas: List<HabitArea>,
    dayKey: Long,
    completions: List<HabitCompletion>,
    history: List<HabitCompletion>,
    allLogs: List<HabitCompletion>,
    onAdd: (String, String, String, String, Int, Double, String, String, Int?, (Long) -> Unit) -> Unit,
    onAddArea: (String) -> Unit,
    onLog: (Long, Long, String, Double, String?) -> Unit,
    onDelete: (Habit) -> Unit,
    onDay: (Long) -> Unit,
    onShiftDay: (Int) -> Unit,
    onUpdate: (Habit) -> Unit = {},
    habitNotes: List<HabitNote> = emptyList(),
    habitTasks: List<HabitTask> = emptyList(),
    onAddWithStyle: ((
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
        startedAt: Long,
        onCreated: (Long) -> Unit
    ) -> Unit)? = null,
    onAddHabitNote: (habitId: Long, content: String, dayKey: Long, mood: String) -> Unit = { _, _, _, _ -> },
    onUpdateHabitNote: (HabitNote) -> Unit = {},
    onDeleteHabitNote: (HabitNote) -> Unit = {},
    onAddHabitTask: (habitId: Long, title: String) -> Unit = { _, _ -> },
    onToggleHabitTask: (taskId: Long, completed: Boolean) -> Unit = { _, _ -> },
    onDeleteHabitTask: (HabitTask) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestHabits by rememberUpdatedState(habits)
    var progressMode by remember { mutableStateOf(false) }
    var areaFilter by remember { mutableStateOf("Todos") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    var detailHabitId by remember { mutableStateOf<Long?>(null) }
    var areaDialog by remember { mutableStateOf(false) }
    var pendingReminder by remember { mutableStateOf<StudioPendingHabitReminder?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingReminder?.let {
            if (granted) HabitReminderScheduler.schedule(context, it.id, it.title, it.minutes, it.daysMask)
        }
        pendingReminder = null
    }

    val scheduleWithPermission: (Long, String, Int?, Int) -> Unit = { id, label, reminderMinutes, mask ->
        if (reminderMinutes != null) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                pendingReminder = StudioPendingHabitReminder(id, label, reminderMinutes, mask)
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                HabitReminderScheduler.schedule(context, id, label, reminderMinutes, mask)
            }
        } else {
            HabitReminderScheduler.cancel(context, id)
        }
    }

    val date = remember(dayKey) { habitStudioLocalDateFromKey(dayKey) }
    val dayCompletions = remember(completions) { completions.associateBy { it.habitId } }
    val scheduled = remember(habits, dayKey) { habits.filter { habitStudioIsScheduledOn(it, date) } }
    val visible = remember(scheduled, areaFilter) {
        if (areaFilter == "Todos") scheduled else scheduled.filter { it.area == areaFilter }
    }
    val doneCount = remember(visible, dayCompletions) {
        visible.count { dayCompletions[it.id]?.status in listOf("DONE", "PARTIAL") }
    }
    val dateLabel = remember(dayKey) {
        StudyDateFormats.formatLocalDate(date, StudyDateFormats.FULL_WEEKDAY_DAY_MONTH_PT).replaceFirstChar { it.uppercase() }
    }
    val detailHabit = remember(detailHabitId, habits) { detailHabitId?.let { id -> habits.firstOrNull { it.id == id } } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StudyPageHeader(
                title = "Hábitos",
                subtitle = if (dayKey == habitStudioCurrentDayKey()) "Seu ritmo, um dia de cada vez" else dateLabel,
                showGlobalSearch = true,
                actions = {
                    IconButton(onClick = { onDay(habitStudioCurrentDayKey()) }) {
                        Icon(Icons.Rounded.Today, "Ir para hoje", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, "Novo hábito", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )

            TabRow(
                selectedTabIndex = if (progressMode) 1 else 0,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = !progressMode,
                    onClick = { progressMode = false },
                    text = { Text("Meu dia", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.Rounded.CheckCircle, null) }
                )
                Tab(
                    selected = progressMode,
                    onClick = { progressMode = true },
                    text = { Text("Progresso", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.Rounded.Insights, null) }
                )
            }

            if (progressMode) {
                HabitStudioProgressTab(
                    habits = habits,
                    areas = areas,
                    logs = if (allLogs.isNotEmpty()) allLogs else history,
                    dayKey = dayKey,
                    onSelectDay = { selectedDay ->
                        onDay(selectedDay)
                        progressMode = false
                    },
                    onOpenHabitDetail = { habit -> detailHabitId = habit.id }
                )
            } else {
                // Navegador de data
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onShiftDay(-1) }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Dia anterior")
                    }
                    Text(
                        text = dateLabel,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d -> onDay(y * 10000L + (m + 1) * 100L + d) },
                                    date.year,
                                    date.monthValue - 1,
                                    date.dayOfMonth
                                ).show()
                            },
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    IconButton(onClick = { onShiftDay(1) }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Próximo dia")
                    }
                }

                // Chips de áreas
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (listOf("Todos") + areas.map { it.name }).forEach { area ->
                        FilterChip(
                            selected = areaFilter == area,
                            onClick = { areaFilter = area },
                            label = { Text(area, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = if (areaFilter == area) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                    AssistChip(
                        onClick = { areaDialog = true },
                        label = { Text("Nova área", maxLines = 1, softWrap = false) },
                        leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) }
                    )
                }

                // Card resumo do dia
                StudyCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColorOverride = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Hoje", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = "$doneCount de ${visible.size} ${if (visible.size == 1) "hábito" else "hábitos"}",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            if (visible.isNotEmpty()) {
                                val pct = doneCount * 100 / visible.size
                                Text(
                                    text = "$pct%",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        if (visible.isNotEmpty()) {
                            LinearProgressIndicator(
                                progress = { (doneCount.toFloat() / visible.size).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)
                            )
                        }
                    }
                }

                // Lista de hábitos do dia
                val sourceLogs = if (allLogs.isNotEmpty()) allLogs else history
                val logsByHabitId = remember(sourceLogs) { sourceLogs.groupBy { it.habitId } }
                val tasksByHabitId = remember(habitTasks) { habitTasks.groupBy { it.habitId } }
                val habitsById = remember(habits) { habits.associateBy { it.id } }
                val weekStartKey = remember(dayKey) {
                    val ld = habitStudioLocalDateFromKey(dayKey)
                    habitStudioKeyFromLocalDate(ld.minusDays((ld.dayOfWeek.value - 1).toLong()))
                }
                val monthStartKey = remember(dayKey) {
                    val ld = habitStudioLocalDateFromKey(dayKey)
                    habitStudioKeyFromLocalDate(ld.withDayOfMonth(1))
                }
                val groups = remember(areas, scheduled, areaFilter) {
                    if (areaFilter == "Todos") {
                        areas.map { it.name }.filter { name -> scheduled.any { it.area == name } } +
                            scheduled.map { it.area }.distinct().filter { name -> areas.none { it.name == name } }
                    } else {
                        listOf(areaFilter)
                    }
                }
                val visibleByArea = remember(visible) { visible.groupBy { it.area } }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 84.dp)
                ) {
                    if (habits.isEmpty()) {
                        item(key = "habits_empty", contentType = "empty") {
                            StudyCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Rounded.Spa, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                    Text("Comece com um hábito pequeno", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                                    Text(
                                        text = "Escolha um ícone, uma cor e acompanhe seu foco e evolução diária.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Button(onClick = { showCreateDialog = true }, modifier = Modifier.padding(top = 14.dp)) {
                                        Icon(Icons.Rounded.Add, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Criar hábito", maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    } else {
                        groups.forEach { area ->
                            val groupHabits = visibleByArea[area].orEmpty()
                            if (groupHabits.isNotEmpty()) {
                                item(key = "area_header_$area", contentType = "areaHeader") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 16.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = area,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                                        )
                                        TextButton(onClick = { showCreateDialog = true }) {
                                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Adicionar", maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                                items(
                                    items = groupHabits,
                                    key = { "habit_${it.id}" },
                                    contentType = { "habitCard" }
                                ) { habit ->
                                    val habitLogs = logsByHabitId[habit.id].orEmpty()
                                    val streak = remember(habit, habitLogs, dayKey) {
                                        habitStudioCurrentStreak(habit, habitLogs, dayKey)
                                    }
                                    val periodAccumulated = remember(habit, habitLogs, dayKey, weekStartKey, monthStartKey) {
                                        when (habit.targetPeriod) {
                                            "semana" -> habitLogs.filter { it.dayKey in weekStartKey..dayKey && it.status in listOf("DONE", "PARTIAL") }.sumOf { it.value }
                                            "mês" -> habitLogs.filter { it.dayKey in monthStartKey..dayKey && it.status in listOf("DONE", "PARTIAL") }.sumOf { it.value }
                                            else -> dayCompletions[habit.id]?.value ?: 0.0
                                        }
                                    }
                                    val stackedAfterTitle = remember(habit.stackAfterHabitId, habitsById) {
                                        habit.stackAfterHabitId?.let { habitsById[it]?.title }
                                    }
                                    val tasksForHabit = tasksByHabitId[habit.id].orEmpty()
                                    HabitStudioDayCard(
                                        habit = habit,
                                        completion = dayCompletions[habit.id],
                                        streak = streak,
                                        periodAccumulated = periodAccumulated,
                                        stackedAfterTitle = stackedAfterTitle,
                                        openTasksCount = tasksForHabit.count { !it.completed },
                                        onOpen = { detailHabitId = habit.id },
                                        onQuickToggle = {
                                            val currentStatus = dayCompletions[habit.id]?.status
                                            val nextStatus = if (currentStatus == "DONE") "NONE" else "DONE"
                                            onLog(habit.id, dayKey, nextStatus, habit.targetValue, null)
                                        },
                                        onIncrement = {
                                            val currentVal = dayCompletions[habit.id]?.value ?: 0.0
                                            val nextVal = currentVal + 1.0
                                            val nextStatus = when {
                                                habit.kind == "LIMIT" -> if (nextVal <= habit.targetValue) "DONE" else "FAILED"
                                                nextVal >= habit.targetValue -> "DONE"
                                                else -> "PARTIAL"
                                            }
                                            onLog(habit.id, dayKey, nextStatus, nextVal, null)
                                        },
                                        onLogStatus = { status, value ->
                                            onLog(habit.id, dayKey, status, value, null)
                                        },
                                        onEdit = { editingHabit = habit },
                                        onDelete = {
                                            HabitReminderScheduler.cancel(context, habit.id)
                                            onDelete(habit)
                                        }
                                    )
                                }
                            }
                        }

                        if (visible.isEmpty()) {
                            item(key = "habits_area_empty", contentType = "empty") {
                                Text(
                                    text = "Nada programado para esta área neste dia.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 22.dp)
                                )
                            }
                        }

                        item(key = "add_habit_footer", contentType = "footer") {
                            OutlinedButton(
                                onClick = { showCreateDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                Icon(Icons.Rounded.Add, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Adicionar hábito")
                            }
                        }
                    }
                }
            }
        }

        // FAB azul estilo referência para criação rápida em qualquer aba
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Criar hábito")
        }
    }

    detailHabit?.let { opened ->
        val sourceLogs = if (allLogs.isNotEmpty()) allLogs else history
        HabitDetailSheet(
            habit = opened,
            dayKey = dayKey,
            completions = sourceLogs,
            notes = habitNotes,
            tasks = habitTasks,
            areas = areas,
            onDismiss = { detailHabitId = null },
            onLog = { targetDay, status, value, note ->
                onLog(opened.id, targetDay, status, value, note)
            },
            onUpdateHabit = { updated ->
                onUpdate(updated)
                val label = if (isHabitEmojiMode(updated.iconKey, updated.emoji)) {
                    "${updated.emoji} ${updated.title}"
                } else {
                    updated.title
                }
                scheduleWithPermission(updated.id, label, updated.reminderMinutes, updated.repeatDaysMask)
            },
            onDeleteHabit = { h ->
                HabitReminderScheduler.cancel(context, h.id)
                onDelete(h)
                detailHabitId = null
            },
            onAddNote = onAddHabitNote,
            onUpdateNote = onUpdateHabitNote,
            onDeleteNote = onDeleteHabitNote,
            onAddTask = onAddHabitTask,
            onToggleTask = onToggleHabitTask,
            onDeleteTask = onDeleteHabitTask,
            onRequestEditHabit = { h ->
                editingHabit = h
            }
        )
    }

    if (showCreateDialog) {
        HabitStudioFormDialog(
            initialHabit = null,
            areas = areas.map { it.name },
            onAddArea = onAddArea,
            onDismiss = { showCreateDialog = false },
            allHabits = habits,
            onSave = { title, emoji, iconKey, color, description, area, kind, mask, target, unit, period, reminder, startedAt, timeOfDay, miniTarget, eliteTarget, stackAfterId ->
                val reminderLabel = if (isHabitEmojiMode(iconKey, emoji)) "$emoji $title" else title
                if (onAddWithStyle != null) {
                    onAddWithStyle(title, emoji, iconKey, color, description, area, kind, mask, target, unit, period, reminder, startedAt) { id ->
                        scheduleWithPermission(id, reminderLabel, reminder, mask)
                        if (id > 0L && (timeOfDay != "ANYTIME" || miniTarget > 0.0 || eliteTarget > 0.0 || stackAfterId != null)) {
                            scope.launch {
                                for (attempt in 0 until 20) {
                                    val inserted = latestHabits.firstOrNull { it.id == id }
                                    if (inserted != null) {
                                        onUpdate(
                                            inserted.copy(
                                                timeOfDay = timeOfDay,
                                                stackAfterHabitId = stackAfterId,
                                                miniTarget = miniTarget,
                                                eliteTarget = eliteTarget
                                            )
                                        )
                                        break
                                    }
                                    delay(50L)
                                }
                            }
                        }
                    }
                } else {
                    onAdd(title, emoji, area, kind, mask, target, unit, period, reminder) { id ->
                        scheduleWithPermission(id, reminderLabel, reminder, mask)
                    }
                }
                showCreateDialog = false
            }
        )
    }

    editingHabit?.let { current ->
        HabitStudioFormDialog(
            initialHabit = current,
            areas = areas.map { it.name },
            onAddArea = onAddArea,
            onDismiss = { editingHabit = null },
            allHabits = habits,
            onSave = { title, emoji, iconKey, color, description, area, kind, mask, target, unit, period, reminder, startedAt, timeOfDay, miniTarget, eliteTarget, stackAfterId ->
                val updated = current.copy(
                    title = title,
                    emoji = emoji,
                    iconKey = iconKey,
                    color = color,
                    description = description,
                    area = area,
                    kind = kind,
                    repeatDaysMask = mask,
                    targetValue = target,
                    targetUnit = unit,
                    targetPeriod = period,
                    reminderMinutes = reminder,
                    startedAt = startedAt,
                    timeOfDay = timeOfDay,
                    stackAfterHabitId = stackAfterId,
                    miniTarget = miniTarget,
                    eliteTarget = eliteTarget,
                    freezeTokens = current.freezeTokens,
                    vacationUntilDay = current.vacationUntilDay
                )
                onUpdate(updated)
                val reminderLabel = if (isHabitEmojiMode(iconKey, emoji)) "$emoji $title" else title
                scheduleWithPermission(updated.id, reminderLabel, reminder, mask)
                editingHabit = null
            }
        )
    }

    if (areaDialog) {
        StudioSimpleAreaDialog(
            onDismiss = { areaDialog = false },
            onSave = { name ->
                onAddArea(name)
                areaFilter = name
                areaDialog = false
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Card do Hábito em "Meu dia"
// ---------------------------------------------------------------------------

@Composable
private fun HabitStudioDayCard(
    habit: Habit,
    completion: HabitCompletion?,
    streak: Int,
    periodAccumulated: Double = 0.0,
    stackedAfterTitle: String? = null,
    openTasksCount: Int,
    onOpen: () -> Unit,
    onQuickToggle: () -> Unit,
    onIncrement: () -> Unit,
    onLogStatus: (String, Double) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val status = completion?.status ?: "NONE"
    val isBad = habit.kind == "BAD"
    val isLimit = habit.kind == "LIMIT"
    val habitAccent = habitColorOrDefault(habit.color)
    val semantic = LocalStudySemanticColors.current

    StudyCard(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        containerColorOverride = if (status == "DONE") MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.68f) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HabitVisualBadge(
                habit = habit,
                size = 42.dp,
                cornerRadius = 12.dp,
                solidFill = status == "DONE"
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 11.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = habit.title,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (streak > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = semantic.streak.copy(alpha = 0.14f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(Icons.Rounded.LocalFireDepartment, null, tint = semantic.streak, modifier = Modifier.size(12.dp))
                                Text(
                                    text = "${streak}d",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = semantic.streak,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                val metaText = if (isBad) {
                    if (streak > 0) "🛡️ Sem recaída há $streak ${if (streak == 1) "dia" else "dias"}"
                    else "Objetivo · passar o dia sem praticar"
                } else if (isLimit) {
                    "Limite · até ${habit.targetValue.habitStudioFormatNumber()} ${habit.targetUnit}"
                } else if (habit.targetPeriod != "dia") {
                    "Acumulado no(a) ${habit.targetPeriod}: ${periodAccumulated.habitStudioFormatNumber()} / ${habit.targetValue.habitStudioFormatNumber()} ${habit.targetUnit}"
                } else {
                    "Meta · ${habit.targetValue.habitStudioFormatNumber()} ${habit.targetUnit} por ${habit.targetPeriod}"
                }

                val progressText = when {
                    isLimit && status != "SKIPPED" && status != "NONE" ->
                        "Limite · ${completion?.value?.habitStudioFormatNumber() ?: "0"} / ${habit.targetValue.habitStudioFormatNumber()} ${habit.targetUnit}"
                    status == "DONE" ->
                        if (isBad) "Dia sem recaída"
                        else if ((completion?.value ?: 0.0) > habit.targetValue)
                            "${completion?.value?.habitStudioFormatNumber()} ${habit.targetUnit} · meta superada"
                        else "Concluído · ${completion?.value?.habitStudioFormatNumber() ?: habit.targetValue.habitStudioFormatNumber()} ${habit.targetUnit}"
                    status == "PARTIAL" ->
                        "${completion?.value?.habitStudioFormatNumber()} / ${habit.targetValue.habitStudioFormatNumber()} ${habit.targetUnit} · faltam ${(habit.targetValue - (completion?.value ?: 0.0)).coerceAtLeast(0.0).habitStudioFormatNumber()}"
                    status == "SKIPPED" -> "Dia pulado"
                    status == "FAILED" -> if (isBad) "Recaída registrada" else "Falhado neste dia"
                    habit.description.isNotBlank() -> habit.description
                    else -> metaText
                }

                Text(
                    text = progressText,
                    color = when (status) {
                        "DONE" -> semantic.success
                        "FAILED" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )

                if (!stackedAfterTitle.isNullOrBlank()) {
                    Text(
                        text = "🔗 Empilhado após: $stackedAfterTitle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (openTasksCount > 0) {
                    Text(
                        text = "$openTasksCount ${if (openTasksCount == 1) "tarefa de apoio" else "tarefas de apoio"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = habitAccent,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if ((habit.kind == "GOOD" || isLimit) && status in listOf("DONE", "PARTIAL", "FAILED")) {
                    val progressRatio = ((completion?.value ?: 0.0) / habit.targetValue.coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progressRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = when {
                            status == "FAILED" -> MaterialTheme.colorScheme.error
                            status == "DONE" -> semantic.success
                            else -> habitAccent
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            }

            // Stepper rápido +1 para hábitos quantitativos
            if (habit.kind != "BAD" && habit.targetValue > 1.0) {
                Surface(
                    onClick = onIncrement,
                    shape = RoundedCornerShape(10.dp),
                    color = habitAccent.copy(alpha = 0.14f),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = "+1",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = habitAccent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            // Botão rápido Check / Shield
            IconButton(
                onClick = if (isLimit) onOpen else onQuickToggle,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = when (status) {
                        "DONE" -> semantic.successContainer
                        "FAILED" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = when (status) {
                        "DONE" -> semantic.onSuccessContainer
                        "FAILED" -> MaterialTheme.colorScheme.onErrorContainer
                        else -> habitAccent
                    }
                ),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = when {
                        status == "DONE" -> Icons.Rounded.Check
                        status == "FAILED" -> Icons.Rounded.Close
                        isBad -> Icons.Rounded.Shield
                        else -> Icons.Rounded.Add
                    },
                    contentDescription = if (isBad) "Marcar dia sem recaída" else "Concluir",
                    modifier = Modifier.size(19.dp)
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.MoreVert, "Opções", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Abrir detalhes (Notas & Sobre)") },
                        leadingIcon = { Icon(Icons.Rounded.Insights, null, tint = habitAccent) },
                        onClick = { menuExpanded = false; onOpen() }
                    )
                    if (habit.kind != "BAD") {
                        DropdownMenuItem(
                            text = { Text("Registrar +1 ${habit.targetUnit}") },
                            leadingIcon = { Icon(Icons.Rounded.Add, null) },
                            onClick = { menuExpanded = false; onIncrement() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Pular este dia") },
                        leadingIcon = { Icon(Icons.Rounded.SkipNext, null) },
                        onClick = { menuExpanded = false; onLogStatus("SKIPPED", 0.0) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isBad) "Registrar recaída" else "Marcar como falhado") },
                        leadingIcon = { Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onLogStatus("FAILED", 0.0) }
                    )
                    if (status != "NONE") {
                        DropdownMenuItem(
                            text = { Text("Limpar registro") },
                            leadingIcon = { Icon(Icons.Rounded.Clear, null) },
                            onClick = { menuExpanded = false; onLogStatus("NONE", 0.0) }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Editar hábito") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menuExpanded = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir hábito", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Aba Progresso (fiel à imagem 1: Bons Hábitos | Hábitos Ruins, filtros, Padrões)
// ---------------------------------------------------------------------------

@Composable
private fun HabitStudioProgressTab(
    habits: List<Habit>,
    areas: List<HabitArea>,
    logs: List<HabitCompletion>,
    dayKey: Long,
    onSelectDay: (Long) -> Unit,
    onOpenHabitDetail: (Habit) -> Unit
) {
    var kindFilter by remember { mutableStateOf("GOOD") }
    var rangeDays by remember { mutableIntStateOf(28) }
    var selectedHabitId by remember { mutableStateOf<Long?>(null) }
    var selectedArea by remember { mutableStateOf<String?>(null) }
    var patternsExpanded by remember { mutableStateOf(true) }

    var rangeMenu by remember { mutableStateOf(false) }
    var habitMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }

    val habitsOfKind = remember(habits, kindFilter, selectedArea) {
        habits.filter { h ->
            val kindOk = when (kindFilter) {
                "ALL" -> true
                else -> h.kind.equals(kindFilter, ignoreCase = true)
            }
            kindOk && (selectedArea == null || h.area == selectedArea)
        }
    }

    // Se mudou o filtro e o hábito selecionado não faz parte dele, limpa o filtro de hábito.
    val effectiveHabitId = selectedHabitId?.takeIf { id -> habitsOfKind.any { it.id == id } }

    val patterns = remember(habits, logs, dayKey, rangeDays, kindFilter, effectiveHabitId, selectedArea) {
        HabitPatternsEngine.computePatterns(
            habits = habits,
            completions = logs,
            todayDayKey = dayKey,
            rangeDays = rangeDays,
            kindFilter = kindFilter,
            habitIdFilter = effectiveHabitId,
            areaFilter = selectedArea
        )
    }

    val filteredHabits = remember(habitsOfKind, effectiveHabitId) {
        if (effectiveHabitId == null) habitsOfKind else habitsOfKind.filter { it.id == effectiveHabitId }
    }
    val filteredHabitIds = remember(filteredHabits) { filteredHabits.map { it.id }.toSet() }
    val days = remember(dayKey, rangeDays) {
        val start = habitStudioShiftDayKey(dayKey, -(rangeDays - 1))
        (0 until rangeDays).map { habitStudioShiftDayKey(start, it) }
    }
    val startDayKey = days.firstOrNull() ?: dayKey
    val filteredLogs = remember(logs, filteredHabitIds, startDayKey, dayKey) {
        logs.filter { it.habitId in filteredHabitIds && it.dayKey in startDayKey..dayKey }
    }

    val hasLimitHabits = remember(habits) { habits.any { it.kind == "LIMIT" } }
    val selectedHabitTitle = remember(effectiveHabitId, habits) {
        habits.firstOrNull { it.id == effectiveHabitId }?.title ?: "Todos os Hábitos"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = 84.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Switcher editorial grande: Bons Hábitos | Hábitos Ruins (imagem 1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KindTabTitle(
                text = "Bons Hábitos",
                selected = kindFilter == "GOOD",
                onClick = { kindFilter = "GOOD" }
            )
            KindTabTitle(
                text = "Hábitos Ruins",
                selected = kindFilter == "BAD",
                onClick = { kindFilter = "BAD" }
            )
            if (hasLimitHabits) {
                KindTabTitle(
                    text = "Limites",
                    selected = kindFilter == "LIMIT",
                    onClick = { kindFilter = "LIMIT" }
                )
            }
        }

        // Barra de filtros estilo pílula escura/azul (Últimos 28 dias v | Todos os Hábitos v | [Tune])
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                ProgressFilterPill(
                    label = "Últimos $rangeDays dias",
                    showChevron = true,
                    onClick = { rangeMenu = true }
                )
                DropdownMenu(expanded = rangeMenu, onDismissRequest = { rangeMenu = false }) {
                    listOf(7, 14, 28, 90).forEach { opt ->
                        DropdownMenuItem(
                            text = { Text("Últimos $opt dias") },
                            trailingIcon = if (rangeDays == opt) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                            } else null,
                            onClick = {
                                rangeDays = opt
                                rangeMenu = false
                            }
                        )
                    }
                }
            }

            Box {
                ProgressFilterPill(
                    label = selectedHabitTitle,
                    showChevron = true,
                    onClick = { habitMenu = true }
                )
                DropdownMenu(expanded = habitMenu, onDismissRequest = { habitMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Todos os Hábitos") },
                        trailingIcon = if (effectiveHabitId == null) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            selectedHabitId = null
                            habitMenu = false
                        }
                    )
                    habitsOfKind.forEach { habit ->
                        DropdownMenuItem(
                            text = { Text(habit.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { HabitVisualBadge(habit = habit, size = 24.dp, cornerRadius = 6.dp) },
                            trailingIcon = if (effectiveHabitId == habit.id) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                            } else null,
                            onClick = {
                                selectedHabitId = habit.id
                                habitMenu = false
                            }
                        )
                    }
                }
            }

            Box {
                val filterActive = selectedArea != null || kindFilter == "ALL"
                Surface(
                    onClick = { filterMenu = true },
                    shape = RoundedCornerShape(10.dp),
                    color = if (filterActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Filtrar por área ou tipo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Todas as áreas") },
                        trailingIcon = if (selectedArea == null) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            selectedArea = null
                            filterMenu = false
                        }
                    )
                    areas.forEach { area ->
                        DropdownMenuItem(
                            text = { Text(area.name) },
                            trailingIcon = if (selectedArea == area.name) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                            } else null,
                            onClick = {
                                selectedArea = area.name
                                filterMenu = false
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Incluir todos os tipos") },
                        trailingIcon = if (kindFilter == "ALL") {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            kindFilter = if (kindFilter == "ALL") "GOOD" else "ALL"
                            filterMenu = false
                        }
                    )
                }
            }
        }

        if (selectedArea != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { selectedArea = null },
                    label = { Text("Área: $selectedArea") },
                    trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(14.dp)) }
                )
            }
        }

        // Cabeçalho retrátil "v Padrões"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { patternsExpanded = !patternsExpanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (patternsExpanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Padrões",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        AnimatedVisibility(visible = patternsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PeakFocusZonesCard(patterns = patterns)
                MissedHabitsCard(patterns = patterns)
            }
        }

        // Calendário de consistência do período
        val semantic = LocalStudySemanticColors.current
        val cardPadding = StudyTokens.cardPadding()
        val records = remember(filteredLogs) { filteredLogs.associateBy { it.habitId to it.dayKey } }
        val logsByDay = remember(filteredLogs) { filteredLogs.groupBy { it.dayKey } }
        val logsByHabit = remember(filteredLogs) { filteredLogs.groupBy { it.habitId } }
        val dayDates = remember(days) { days.associateWith { habitStudioLocalDateFromKey(it) } }
        val (planned, achieved) = remember(filteredHabits, days, dayDates, records) {
            var p = 0
            var a = 0.0
            filteredHabits.forEach { habit ->
                val startDayKey = habitStudioDayKeyFromMillis(habit.startedAt.takeIf { it > 0L } ?: habit.createdAt)
                days.forEach { key ->
                    val ld = dayDates[key] ?: habitStudioLocalDateFromKey(key)
                    if (key >= startDayKey && habitStudioIsScheduledOn(habit, ld)) {
                        p++
                        val rec = records[habit.id to key]
                        a += when (rec?.status) {
                            "DONE" -> 1.0
                            "PARTIAL" -> (rec.value / habit.targetValue.coerceAtLeast(0.1)).coerceIn(0.0, 1.0)
                            else -> 0.0
                        }
                    }
                }
            }
            p to a
        }
        val score = if (planned == 0) 0 else (achieved * 100.0 / planned).roundToInt().coerceIn(0, 100)

        StudyCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(cardPadding)) {
                Text("Últimos $rangeDays dias", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "Toque em um dia para abrir o registro",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp, bottom = 12.dp)
                )
                val gridDays = days.takeLast(28)
                val firstGridDow = gridDays.firstOrNull()?.let { habitStudioWeekdayIndexFromKey(it) } ?: 0
                val paddedGridDays = buildList<Long?> {
                    repeat(firstGridDow) { add(null) }
                    addAll(gridDays)
                    while (size % 7 != 0) add(null)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("S", "T", "Q", "Q", "S", "S", "D").forEach { d ->
                        Text(
                            text = d,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                paddedGridDays.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        week.forEach { key ->
                            if (key == null) {
                                Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
                            } else {
                                val dayComps = logsByDay[key].orEmpty()
                                val (cellBg, cellFg) = when {
                                    dayComps.any { it.status == "DONE" } -> semantic.success to semantic.onSuccess
                                    dayComps.any { it.status == "PARTIAL" } -> semantic.warning to semantic.onWarning
                                    dayComps.any { it.status == "FAILED" } -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
                                    dayComps.any { it.status == "SKIPPED" } -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 32.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(cellBg)
                                        .clickable { onSelectDay(key) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (key % 100).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cellFg,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProgressMetricColumn("Média", "$score%", Modifier.weight(1f))
                    ProgressMetricColumn("Registros", filteredLogs.size.toString(), Modifier.weight(1f))
                    ProgressMetricColumn("Dias ativos", patterns.trackedDaysCount.toString(), Modifier.weight(1f))
                }
            }
        }

        // Ritmo semanal
        Text("Ritmo semanal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        StudyCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(cardPadding)) {
                val names = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
                val counts = remember(filteredLogs) {
                    val arr = IntArray(7)
                    filteredLogs.forEach { c ->
                        if (c.status == "DONE" || c.status == "PARTIAL") {
                            arr[habitStudioWeekdayIndexFromKey(c.dayKey).coerceIn(0, 6)]++
                        }
                    }
                    arr
                }
                val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
                Row(
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    names.forEachIndexed { i, name ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height((14 + 70f * counts[i] / max).dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (counts[i] == 0) 0.16f else 0.85f))
                            )
                            Text(
                                text = name,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                    }
                }
            }
        }

        // Lista de hábitos filtrados
        Text("Hábitos no filtro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (filteredHabits.isEmpty()) {
            Text(
                text = "Nenhum hábito encontrado para os filtros selecionados.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val habitMetricsById = remember(filteredHabits, logsByHabit, days, dayDates) {
                filteredHabits.associate { habit ->
                    val habitRecords = logsByHabit[habit.id].orEmpty()
                    val count = habitRecords.count { it.status == "DONE" || (it.status == "PARTIAL" && it.value >= habit.targetValue) }
                    val startDayKey = habitStudioDayKeyFromMillis(habit.startedAt.takeIf { it > 0L } ?: habit.createdAt)
                    val eligibleDays = days.count { key ->
                        val ld = dayDates[key] ?: habitStudioLocalDateFromKey(key)
                        key >= startDayKey && habitStudioIsScheduledOn(habit, ld)
                    }.coerceAtLeast(1)
                    val habitScore = habitRecords.sumOf {
                        when (it.status) {
                            "DONE" -> 1.0
                            "PARTIAL" -> (it.value / habit.targetValue.coerceAtLeast(0.1)).coerceIn(0.0, 1.0)
                            else -> 0.0
                        }
                    }
                    habit.id to Triple(count, eligibleDays, habitScore)
                }
            }
            filteredHabits.forEach { habit ->
                val (count, eligibleDays, habitScore) = habitMetricsById[habit.id] ?: Triple(0, 1, 0.0)
                val expScore = remember(habit, logsByHabit[habit.id], dayKey) {
                    habitStudioExponentialScore(habit, logsByHabit[habit.id].orEmpty(), dayKey)
                }
                val habitAccent = habitColorOrDefault(habit.color)
                StudyCard(onClick = { onOpenHabitDetail(habit) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        HabitVisualBadge(habit = habit, size = 38.dp, cornerRadius = 10.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                            Text(habit.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "$count registros · ${habit.area} · Score $expScore%",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${(habitScore * 100 / eligibleDays).toInt().coerceAtMost(100)}%",
                            color = habitAccent,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KindTabTitle(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun ProgressFilterPill(
    label: String,
    showChevron: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (showChevron) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Card **Zonas de Pico de Foco** fiel à imagem 1:
 * Eixo esquerdo `7h / 12h / 17h / 7h`, barras proporcionais Manhã/Tarde/Noite e banner de desbloqueio.
 */
@Composable
private fun PeakFocusZonesCard(patterns: HabitPatternsSnapshot) {
    val maxCount = (patterns.peakZones.maxOfOrNull { it.completionCount } ?: 0).coerceAtLeast(1)
    val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val trackBg = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
    val defaultBarFill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val bestBarFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)

    StudyCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                Text(
                    text = "Zonas de Pico de Foco",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(198.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Coluna do eixo de horários: 7h, 12h, 17h, 7h alinhados nas divisões
                    Column(
                        modifier = Modifier
                            .width(34.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        listOf("7h", "12h", "17h", "7h").forEach { hourLabel ->
                            Text(
                                text = hourLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    // Área do gráfico com 3 faixas (Manhã, Tarde, Noite)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(trackBg)
                            .border(0.8.dp, trackBorder, RoundedCornerShape(4.dp))
                    ) {
                        patterns.peakZones.forEachIndexed { index, stat ->
                            val isBest = patterns.bestZone == stat.zone
                            val barFraction = when {
                                stat.completionCount == 0 -> 0f
                                stat.successRatePercent != null -> (stat.successRatePercent / 100f).coerceIn(0.18f, 1f)
                                else -> (stat.completionCount.toFloat() / maxCount).coerceIn(0.22f, 1f)
                            }
                            val zoneIcon = when (stat.zone) {
                                FocusTimeZone.MORNING -> Icons.Rounded.LocalCafe
                                FocusTimeZone.AFTERNOON -> Icons.Rounded.WbSunny
                                FocusTimeZone.EVENING -> Icons.Rounded.Bedtime
                            }
                            val rateLabel = stat.successRatePercent?.let { "$it%" } ?: "N/D"
                            val habitCountLabel = "${stat.completionCount} ${if (stat.completionCount == 1) "hábito" else "hábitos"}"

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                if (barFraction > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(barFraction)
                                            .background(if (isBest) bestBarFill else defaultBarFill)
                                    )
                                    if (isBest) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .width(3.dp)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = zoneIcon,
                                        contentDescription = stat.zone.label,
                                        tint = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = stat.zone.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isBest) FontWeight.Bold else FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "$rateLabel • $habitCountLabel",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isBest) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = "Pico",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            if (index < patterns.peakZones.lastIndex) {
                                HorizontalDivider(color = trackBorder)
                            }
                        }
                    }
                }
            }

            PatternsFooterBanner(
                text = if (!patterns.missed.unlocked) {
                    val rem = patterns.missed.remainingDaysToUnlock
                    "Coletando seus padrões... Registre mais $rem ${if (rem == 1) "dia" else "dias"} para desbloquear suas Zonas de Pico de Foco."
                } else {
                    patterns.bestZone?.let {
                        "Seu pico de foco foi à ${it.label.lowercase(studioPtLocale)} (${it.rangeLabel}). Agende hábitos-chave nesse período."
                    } ?: "Conclua hábitos no período para identificar sua zona de pico."
                }
            )
        }
    }
}

/**
 * Card **Hábitos Perdidos** fiel à imagem 1:
 * Legenda `Pulados` vs `Falhados` à esquerda e gráfico Donut em `Canvas` à direita.
 */
@Composable
private fun MissedHabitsCard(patterns: HabitPatternsSnapshot) {
    val skippedColor = Color(0xFF60A5FA)
    val failedColor = Color(0xFFEF4444)
    val mutedTrack = MaterialTheme.colorScheme.surfaceContainerHighest
    val missed = patterns.missed
    val total = missed.totalMissed

    StudyCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                Text(
                    text = "Hábitos Perdidos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MissedLegendRow(
                            label = "Pulados",
                            count = missed.skippedCount,
                            pillColor = if (missed.skippedCount > 0) skippedColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                        MissedLegendRow(
                            label = "Falhados",
                            count = missed.failedCount,
                            pillColor = if (missed.failedCount > 0) failedColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        )
                    }

                    Box(
                        modifier = Modifier.size(108.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                            val strokePx = 18.dp.toPx()
                            val arcSize = Size(size.width - strokePx, size.height - strokePx)
                            val topLeft = Offset(strokePx / 2f, strokePx / 2f)

                            drawArc(
                                color = mutedTrack,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokePx, cap = StrokeCap.Butt)
                            )

                            if (total > 0) {
                                val gap = if (missed.skippedCount > 0 && missed.failedCount > 0) 4f else 0f
                                val skippedSweep = (missed.skippedCount.toFloat() / total) * 360f
                                val failedSweep = (missed.failedCount.toFloat() / total) * 360f

                                if (missed.skippedCount > 0) {
                                    drawArc(
                                        color = skippedColor,
                                        startAngle = -90f,
                                        sweepAngle = (skippedSweep - gap).coerceAtLeast(2f),
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = strokePx, cap = StrokeCap.Butt)
                                    )
                                }
                                if (missed.failedCount > 0) {
                                    drawArc(
                                        color = failedColor,
                                        startAngle = -90f + skippedSweep,
                                        sweepAngle = (failedSweep - gap).coerceAtLeast(2f),
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = strokePx, cap = StrokeCap.Butt)
                                    )
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = total.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (total > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (total == 1) "perdido" else "perdidos",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            PatternsFooterBanner(
                text = if (!missed.unlocked) {
                    val rem = missed.remainingDaysToUnlock
                    "Coletando seus padrões... Registre mais $rem ${if (rem == 1) "dia" else "dias"} para desbloquear Hábitos Perdidos."
                } else {
                    "${missed.skippedCount} pulados e ${missed.failedCount} falhados em ${missed.evaluatedScheduledCount} registros avaliados."
                }
            )
        }
    }
}

@Composable
private fun MissedLegendRow(label: String, count: Int, pillColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(pillColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PatternsFooterBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            modifier = Modifier.size(26.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProgressMetricColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
// Formulário unificado de Criação / Edição de Hábito (com Ícone+Cor, Descrição, Bom/Ruim)
// ---------------------------------------------------------------------------

@Composable
private fun HabitStudioFormDialog(
    initialHabit: Habit?,
    areas: List<String>,
    onAddArea: (String) -> Unit,
    onDismiss: () -> Unit,
    allHabits: List<Habit> = emptyList(),
    onSave: (
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
        startedAt: Long,
        timeOfDay: String,
        miniTarget: Double,
        eliteTarget: Double,
        stackAfterHabitId: Long?
    ) -> Unit
) {
    val context = LocalContext.current
    val areaOptions = remember(areas) { (areas.ifEmpty { listOf("Manhã", "Tarde", "Noite", "Dia") }).distinct() }

    var title by remember(initialHabit?.id) { mutableStateOf(initialHabit?.title.orEmpty()) }
    var description by remember(initialHabit?.id) { mutableStateOf(initialHabit?.description.orEmpty()) }
    var iconKey by remember(initialHabit?.id) { mutableStateOf(initialHabit?.iconKey ?: HabitIconCatalog.DEFAULT_KEY) }
    var emoji by remember(initialHabit?.id) { mutableStateOf(initialHabit?.emoji ?: "✨") }
    var color by remember(initialHabit?.id) { mutableLongStateOf(initialHabit?.color ?: HabitIconCatalog.colors.first()) }
    var area by remember(initialHabit?.id) { mutableStateOf(initialHabit?.area ?: areaOptions.firstOrNull() ?: "Dia") }
    var kind by remember(initialHabit?.id) { mutableStateOf(initialHabit?.kind ?: "GOOD") }
    var mask by remember(initialHabit?.id) { mutableIntStateOf(initialHabit?.repeatDaysMask ?: 127) }
    var target by remember(initialHabit?.id) { mutableStateOf((initialHabit?.targetValue ?: 1.0).habitStudioFormatNumber()) }
    var unit by remember(initialHabit?.id) { mutableStateOf(initialHabit?.targetUnit ?: "vezes") }
    var period by remember(initialHabit?.id) { mutableStateOf(initialHabit?.targetPeriod ?: "dia") }
    var reminder by remember(initialHabit?.id) { mutableStateOf(initialHabit?.reminderMinutes) }
    var timeOfDay by remember(initialHabit?.id) { mutableStateOf(initialHabit?.timeOfDay ?: "ANYTIME") }
    var stackAfterHabitId by remember(initialHabit?.id) { mutableStateOf(initialHabit?.stackAfterHabitId) }
    var stackMenuExpanded by remember { mutableStateOf(false) }
    var miniTargetText by remember(initialHabit?.id) { mutableStateOf(if ((initialHabit?.miniTarget ?: 0.0) > 0.0) initialHabit!!.miniTarget.habitStudioFormatNumber() else "") }
    var eliteTargetText by remember(initialHabit?.id) { mutableStateOf(if ((initialHabit?.eliteTarget ?: 0.0) > 0.0) initialHabit!!.eliteTarget.habitStudioFormatNumber() else "") }
    var startedAt by remember(initialHabit?.id) {
        mutableLongStateOf(initialHabit?.startedAt?.takeIf { it > 0L } ?: initialHabit?.createdAt ?: System.currentTimeMillis())
    }

    var showIconPicker by remember { mutableStateOf(false) }
    var areaExpanded by remember { mutableStateOf(false) }
    var periodExpanded by remember { mutableStateOf(false) }
    var showNewAreaDialog by remember { mutableStateOf(false) }

    val weekdays = listOf("S", "T", "Q", "Q", "S", "S", "D")
    val startedAtLabel = remember(startedAt) {
        StudyDateFormats.formatEpoch(startedAt, StudyDateFormats.DATE_D_DE_MMM_DE_YYYY_PT)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialHabit == null) "Novo hábito" else "Editar hábito",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Tipo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = kind == "GOOD",
                        onClick = { kind = "GOOD" },
                        shape = SegmentedButtonDefaults.itemShape(0, 3)
                    ) {
                        Text("Praticar", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    SegmentedButton(
                        selected = kind == "LIMIT",
                        onClick = { kind = "LIMIT" },
                        shape = SegmentedButtonDefaults.itemShape(1, 3)
                    ) {
                        Text("Reduzir", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    SegmentedButton(
                        selected = kind == "BAD",
                        onClick = { kind = "BAD" },
                        shape = SegmentedButtonDefaults.itemShape(2, 3)
                    ) {
                        Text("Parar", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }

                // Preview Ícone + Cor + Nome do hábito
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { showIconPicker = true }
                        ) {
                            HabitVisualPreviewBadge(
                                iconKey = iconKey,
                                emoji = emoji,
                                color = color,
                                size = 54.dp,
                                cornerRadius = 14.dp,
                                solidFill = true
                            )
                        }
                        Text(
                            text = "Ícone/Cor",
                            style = MaterialTheme.typography.labelSmall,
                            color = habitColorOrDefault(color),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { showIconPicker = true }
                                .padding(top = 2.dp)
                        )
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Nome do hábito") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descrição / propósito (opcional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = areaExpanded,
                        onExpandedChange = { areaExpanded = !areaExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = area,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Período / Área") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(areaExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = areaExpanded, onDismissRequest = { areaExpanded = false }) {
                            areaOptions.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        area = name
                                        areaExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showNewAreaDialog = true }) {
                        Icon(Icons.Rounded.Add, "Nova área", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Text("Repetir", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    weekdays.forEachIndexed { i, letter ->
                        val checked = (mask and (1 shl i)) != 0
                        FilterChip(
                            selected = checked,
                            onClick = { mask = if (checked) mask and (1 shl i).inv() else mask or (1 shl i) },
                            label = { Text(letter, maxLines = 1, softWrap = false) }
                        )
                    }
                }
                Text("Seg, Ter, Qua, Qui, Sex, Sáb, Dom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (kind != "BAD") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = target,
                            onValueChange = { target = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            label = { Text(if (kind == "LIMIT") "Limite diário" else "Meta", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(0.45f)
                        )
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text("Unidade") },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f)
                        )
                    }
                }
                if (kind == "GOOD") {
                    ExposedDropdownMenuBox(expanded = periodExpanded, onExpandedChange = { periodExpanded = !periodExpanded }) {
                        OutlinedTextField(
                            value = "por $period",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Período da meta") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(periodExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = periodExpanded, onDismissRequest = { periodExpanded = false }) {
                            listOf("dia", "semana", "mês").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("por $option") },
                                    onClick = {
                                        period = option
                                        periodExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Text("Turno do dia", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("ANYTIME" to "Qualquer", "MORNING" to "Manhã", "AFTERNOON" to "Tarde", "EVENING" to "Noite").forEach { (k, lbl) ->
                        FilterChip(selected = timeOfDay == k, onClick = { timeOfDay = k }, label = { Text(lbl) })
                    }
                }
                if (kind == "GOOD") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = miniTargetText,
                            onValueChange = { miniTargetText = it },
                            label = { Text("Bronze (Mini)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = eliteTargetText,
                            onValueChange = { eliteTargetText = it },
                            label = { Text("Ouro (Elite)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                val stackCandidates = remember(allHabits, initialHabit?.id) {
                    allHabits.filter { it.id != (initialHabit?.id ?: -1L) }
                }
                if (stackCandidates.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = stackMenuExpanded,
                        onExpandedChange = { stackMenuExpanded = !stackMenuExpanded }
                    ) {
                        val selectedParent = stackCandidates.firstOrNull { it.id == stackAfterHabitId }
                        OutlinedTextField(
                            value = selectedParent?.let { "${it.emoji} ${it.title}" } ?: "Nenhum (independente)",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Habit Stacking (Empilhar após...)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(stackMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = stackMenuExpanded,
                            onDismissRequest = { stackMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Nenhum (independente)") },
                                onClick = { stackAfterHabitId = null; stackMenuExpanded = false }
                            )
                            stackCandidates.forEach { cand ->
                                DropdownMenuItem(
                                    text = { Text("${cand.emoji} ${cand.title}") },
                                    onClick = { stackAfterHabitId = cand.id; stackMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
                // Data de início
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val zone = java.time.ZoneId.systemDefault()
                            val d0 = java.time.Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    startedAt = java.time.LocalDate.of(y, m + 1, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                                },
                                d0.year,
                                d0.monthValue - 1,
                                d0.dayOfMonth
                            ).show()
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.CalendarMonth, null, tint = habitColorOrDefault(color))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Iniciado em", fontWeight = FontWeight.Medium)
                        Text(startedAtLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Lembrete
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val initial = reminder ?: 8 * 60
                            TimePickerDialog(context, { _, h, m -> reminder = h * 60 + m }, initial / 60, initial % 60, true).show()
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = habitColorOrDefault(color))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Lembrete", fontWeight = FontWeight.Medium)
                        Text(
                            text = reminder?.let {
                                String.format(studioPtLocale, "Todos os dias às %02d:%02d", it / 60, it % 60)
                            } ?: "Toque para escolher um horário",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (reminder != null) {
                        IconButton(onClick = { reminder = null }) {
                            Icon(Icons.Rounded.Close, "Remover lembrete")
                        }
                    } else {
                        Icon(Icons.Rounded.Add, null, tint = habitColorOrDefault(color))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = (target.replace(',', '.').toDoubleOrNull() ?: 1.0).coerceAtLeast(0.1)
                    val miniVal = (miniTargetText.replace(',', '.').toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                    val eliteVal = (eliteTargetText.replace(',', '.').toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                    if (title.isNotBlank() && mask != 0) {
                        onSave(
                            title.trim(),
                            emoji.ifBlank { "✨" },
                            iconKey.ifBlank { HabitIconCatalog.DEFAULT_KEY },
                            color,
                            description.trim(),
                            area,
                            kind,
                            mask,
                            amount,
                            unit.ifBlank { "vezes" },
                            if (kind == "GOOD") period else "dia",
                            reminder,
                            startedAt,
                            timeOfDay,
                            if (kind == "GOOD") miniVal else 0.0,
                            if (kind == "GOOD") eliteVal else 0.0,
                            stackAfterHabitId
                        )
                    }
                },
                enabled = title.isNotBlank() && mask != 0
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )

    if (showIconPicker) {
        HabitIconColorPicker(
            selectedIconKey = iconKey,
            selectedEmoji = emoji,
            selectedColor = color,
            onConfirm = { pickedIconKey, pickedEmoji, pickedColor ->
                iconKey = pickedIconKey
                emoji = pickedEmoji
                color = pickedColor
                showIconPicker = false
            },
            onDismiss = { showIconPicker = false }
        )
    }

    if (showNewAreaDialog) {
        StudioSimpleAreaDialog(
            onDismiss = { showNewAreaDialog = false },
            onSave = { name ->
                onAddArea(name)
                area = name
                showNewAreaDialog = false
            }
        )
    }
}

@Composable
private fun StudioSimpleAreaDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova área") },
        text = {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Ex.: Depois da aula") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (value.isNotBlank()) onSave(value.trim()) },
                enabled = value.isNotBlank()
            ) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
