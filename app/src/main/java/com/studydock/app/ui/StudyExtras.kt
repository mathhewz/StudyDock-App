@file:OptIn(ExperimentalLayoutApi::class)

package com.studydock.app.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.studydock.app.MainActivity
import com.studydock.app.R
import com.studydock.app.data.*
import com.studydock.app.habits.HabitReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

private const val SETTINGS_FILE = "studydock_settings"
private const val PREF_DAILY_GOAL_MIN = "daily_goal_min"
private const val PREF_DAILY_GOAL_MANUAL = "daily_goal_manual_override"

data class DailyGoalResolution(
    val goalMinutes: Int,
    val subjectsMinutes: Int,
    val manualMinutes: Int,
    val isSubjectsMode: Boolean,
    val forceManual: Boolean
)

fun resolveAppDailyGoal(context: Context, subjects: List<Subject>): DailyGoalResolution {
    val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    val manualMinutes = prefs.getInt(PREF_DAILY_GOAL_MIN, 180).coerceIn(10, 1440)
    val forceManual = prefs.getBoolean(PREF_DAILY_GOAL_MANUAL, false)
    val subjectsMinutes = subjects
        .asSequence()
        .filter { !it.archived }
        .sumOf { it.targetMinutesPerDay.coerceAtLeast(0) }
    val resolved = StudyAnalyticsEngine.resolveDailyGoalMinutes(subjects, manualMinutes, forceManual)
    return DailyGoalResolution(
        goalMinutes = resolved,
        subjectsMinutes = subjectsMinutes,
        manualMinutes = manualMinutes,
        isSubjectsMode = !forceManual && subjectsMinutes > 0,
        forceManual = forceManual
    )
}

@Composable
fun rememberDailyGoalResolution(subjects: List<Subject>): DailyGoalResolution {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_DAILY_GOAL_MIN || key == PREF_DAILY_GOAL_MANUAL || key == null) {
                revision++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(subjects, revision) { resolveAppDailyGoal(context, subjects) }
}

// ---------- helpers & agregados de sessão (A1 / A2) ----------

data class SessionAggregates(
    val todaySeconds: Long = 0L,
    val todayCount: Int = 0,
    val thisWeekSeconds: Long = 0L,
    val lastWeekSeconds: Long = 0L,
    val streak: Int = 0,
    val bestDayName: String? = null,
    val bestPeriodName: String? = null,
    val bar30Values: List<Long> = emptyList(),
    val bar30Peak: Long = 1L,
    val monthTitle: String = "",
    val monthTotals: Map<Int, Long> = emptyMap(),
    val monthPeak: Long = 1L,
    val monthWeeks: List<List<Int>> = emptyList()
)

fun computeSessionAggregates(
    sessions: List<StudySession>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone)
): SessionAggregates {
    val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dowOffset = (today.dayOfWeek.value - 1).toLong()
    val thisWeekStart = today.minusDays(dowOffset).atStartOfDay(zone).toInstant().toEpochMilli()
    val lastWeekStart = today.minusDays(dowOffset + 7L).atStartOfDay(zone).toInstant().toEpochMilli()

    var todaySec = 0L
    var todayCnt = 0
    var thisWeekSec = 0L
    var lastWeekSec = 0L

    val activeDays = HashSet<LocalDate>(64)
    val byWeekday = LongArray(7)
    val byPeriod = LongArray(4) // 0=manhã, 1=tarde, 2=noite, 3=madrugada

    val dayStarts30 = LongArray(31) { idx ->
        today.minusDays((29 - idx).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val bar30Counts = LongArray(30)

    val monthFirst = today.withDayOfMonth(1)
    val daysInMonth = monthFirst.lengthOfMonth()
    val startOffset = monthFirst.dayOfWeek.value - 1
    val monthTotals = HashMap<Int, Long>(daysInMonth)

    for (s in sessions) {
        val dur = s.durationSeconds
        if (s.startedAt in todayStart until todayEnd) {
            todaySec += dur
            todayCnt++
        }
        if (s.startedAt >= thisWeekStart) {
            thisWeekSec += dur
        } else if (s.startedAt >= lastWeekStart) {
            lastWeekSec += dur
        }
        val zdt = Instant.ofEpochMilli(s.startedAt).atZone(zone)
        val ld = zdt.toLocalDate()
        if (dur > 0L) {
            activeDays.add(ld)
        }
        val dowIdx = (ld.dayOfWeek.value - 1).coerceIn(0, 6)
        byWeekday[dowIdx] += dur
        val pIdx = when (zdt.hour) {
            in 5..11 -> 0
            in 12..17 -> 1
            in 18..23 -> 2
            else -> 3
        }
        byPeriod[pIdx] += dur

        if (s.startedAt >= dayStarts30[0] && s.startedAt < dayStarts30[30]) {
            var bucket = ((s.startedAt - dayStarts30[0]) / 86_400_000L).toInt().coerceIn(0, 29)
            while (bucket > 0 && s.startedAt < dayStarts30[bucket]) bucket--
            while (bucket < 29 && s.startedAt >= dayStarts30[bucket + 1]) bucket++
            bar30Counts[bucket] += dur
        }
        if (ld.year == monthFirst.year && ld.monthValue == monthFirst.monthValue) {
            monthTotals[ld.dayOfMonth] = (monthTotals[ld.dayOfMonth] ?: 0L) + dur
        }
    }

    var cursor = if (today in activeDays) today else today.minusDays(1)
    var streak = 0
    while (cursor in activeDays) {
        streak++
        cursor = cursor.minusDays(1)
    }

    val weekdayNames = listOf("segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado", "domingo")
    val periodNames = listOf("manhã", "tarde", "noite", "madrugada")
    val bestDowIdx = byWeekday.indices.maxByOrNull { byWeekday[it] }?.takeIf { byWeekday[it] > 0L }
    val bestPerIdx = byPeriod.indices.maxByOrNull { byPeriod[it] }?.takeIf { byPeriod[it] > 0L }

    val bar30 = bar30Counts.toList()
    val bar30Max = (bar30.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val monthPeak = (monthTotals.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
    val monthCells = (List(startOffset) { 0 } + (1..daysInMonth).toList()).chunked(7)
    val monthTitle = monthFirst.format( java.time.format.DateTimeFormatter.ofPattern("MMMM", Locale("pt", "BR")) )
        .replaceFirstChar { it.uppercase() }

    return SessionAggregates(
        todaySeconds = todaySec,
        todayCount = todayCnt,
        thisWeekSeconds = thisWeekSec,
        lastWeekSeconds = lastWeekSec,
        streak = streak,
        bestDayName = bestDowIdx?.let { weekdayNames[it] },
        bestPeriodName = bestPerIdx?.let { periodNames[it] },
        bar30Values = bar30,
        bar30Peak = bar30Max,
        monthTitle = monthTitle,
        monthTotals = monthTotals,
        monthPeak = monthPeak,
        monthWeeks = monthCells
    )
}

private fun xDayKey(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val ld = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    return ld.year * 10000L + ld.monthValue * 100L + ld.dayOfMonth
}

private fun xDuration(seconds: Long): String {
    val minutes = (seconds + 59) / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "$minutes min"
}

private fun xMidnightMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

private fun Context.xActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

// ---------- meta diária + sequência ----------

@Composable
fun DailyGoalCard(
    sessions: List<StudySession>,
    subjects: List<Subject> = emptyList(),
    aggregates: SessionAggregates? = null,
    onOpenPlanner: (() -> Unit)? = null,
    onOpenSubjects: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE) }
    val goalInfo = rememberDailyGoalResolution(subjects)
    val goalMinutes = goalInfo.goalMinutes
    var editing by remember { mutableStateOf(false) }
    val agg = aggregates ?: remember(sessions) { computeSessionAggregates(sessions) }
    val todaySeconds = agg.todaySeconds
    val goalSeconds = goalMinutes.coerceAtLeast(1) * 60L
    val progress = (todaySeconds.toFloat() / goalSeconds).coerceIn(0f, 1f)
    val percent = (todaySeconds * 100 / goalSeconds).toInt()
    val remainingSeconds = (goalSeconds - todaySeconds).coerceAtLeast(0L)
    val streak = agg.streak
    val weekSeconds = agg.thisWeekSeconds
    val weeklyGoalSeconds = goalSeconds * 7L
    val weeklyProgress = (weekSeconds.toFloat() / weeklyGoalSeconds.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val weeklyPercent = (weekSeconds * 100 / weeklyGoalSeconds.coerceAtLeast(1L)).toInt()

    val modeDescription = when {
        goalInfo.isSubjectsMode -> "Soma das matérias (${xDuration(goalInfo.subjectsMinutes * 60L)}/dia)"
        goalInfo.subjectsMinutes > 0 -> "Meta manual fixa • matérias somam ${xDuration(goalInfo.subjectsMinutes * 60L)}"
        else -> "Meta manual • defina metas por matéria para somar automaticamente"
    }

    val semantic = LocalStudySemanticColors.current
    val cardPadding = StudyTokens.cardPadding()
    StudyCard(Modifier.fillMaxWidth().padding(top = 14.dp), isHero = remainingSeconds == 0L) {
        Column(Modifier.fillMaxWidth().padding(cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (goalInfo.isSubjectsMode) "Meta diária • Matérias" else "Meta diária • Manual",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        modeDescription,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        xDuration(todaySeconds),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (remainingSeconds == 0L) semantic.success else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Text(
                        if (remainingSeconds == 0L) "Meta de ${xDuration(goalSeconds)} concluída!" else "Faltam ${xDuration(remainingSeconds)} para ${xDuration(goalSeconds)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        trackColor = Color.Transparent,
                        strokeWidth = 6.dp
                    )
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxSize(),
                        color = if (remainingSeconds == 0L) semantic.success else MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                        strokeWidth = 6.dp
                    )
                    Text("$percent%", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Meta semanal: ${xDuration(weekSeconds)} / ${xDuration(weeklyGoalSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Text(
                    "$weeklyPercent%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (weeklyPercent >= 100) semantic.success else MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { weeklyProgress },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = if (weeklyPercent >= 100) semantic.success else MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Whatshot, null, tint = semantic.streak)
                Text(
                    "$streak ${if (streak == 1) "dia" else "dias"} estudando em sequência",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(start = 7.dp)
                )
                TextButton(onClick = { editing = true }) {
                    Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ajustar", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (editing) {
        var useManualOverride by remember(goalInfo) { mutableStateOf(goalInfo.forceManual || goalInfo.subjectsMinutes == 0) }
        var text by remember(goalInfo) { mutableStateOf(goalInfo.manualMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Configurar meta diária") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        if (goalInfo.subjectsMinutes > 0) {
                            "Suas matérias ativas somam ${xDuration(goalInfo.subjectsMinutes * 60L)}/dia. Escolha se deseja seguir a soma das matérias ou fixar uma meta manual."
                        } else {
                            "Nenhuma matéria possui meta diária configurada ainda. Você pode definir uma meta manual ou configurar metas por matéria."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !useManualOverride && goalInfo.subjectsMinutes > 0,
                            enabled = goalInfo.subjectsMinutes > 0,
                            onClick = { useManualOverride = false },
                            label = {
                                Text(
                                    if (goalInfo.subjectsMinutes > 0) "Soma das matérias (${goalInfo.subjectsMinutes} min)"
                                    else "Soma das matérias (nenhuma configurada)",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        FilterChip(
                            selected = useManualOverride || goalInfo.subjectsMinutes == 0,
                            onClick = { useManualOverride = true },
                            label = { Text("Meta manual fixa", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                    if (useManualOverride || goalInfo.subjectsMinutes == 0) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { value -> text = value.filter { it.isDigit() }.take(4) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("Minutos por dia") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(60, 120, 180, 240).forEach { mins ->
                                FilterChip(
                                    selected = text == mins.toString(),
                                    onClick = { text = mins.toString() },
                                    label = { Text("${mins / 60}h", maxLines = 1, softWrap = false) }
                                )
                            }
                        }
                    }
                    if (onOpenPlanner != null || onOpenSubjects != null) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onOpenPlanner != null) {
                                OutlinedButton(onClick = { editing = false; onOpenPlanner() }) {
                                    Text("Planejador", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (onOpenSubjects != null) {
                                OutlinedButton(onClick = { editing = false; onOpenSubjects() }) {
                                    Text("Matérias", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = text.toIntOrNull()?.coerceIn(10, 1440) ?: goalInfo.manualMinutes
                    val forceManual = useManualOverride && goalInfo.subjectsMinutes > 0
                    prefs.edit()
                        .putInt(PREF_DAILY_GOAL_MIN, value)
                        .putBoolean(PREF_DAILY_GOAL_MANUAL, forceManual)
                        .apply()
                    editing = false
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancelar") } }
        )
    }
}

// ---------- agenda de hoje (tarefas atrasadas/do dia + compromissos) ----------

@Composable
fun TodayAgendaCard(tasks: List<StudyTask>, events: List<CalendarEvent>, subjects: List<Subject>, onToggleTask: (StudyTask) -> Unit) {
    val dayStart = remember { xMidnightMillis() }
    val dayEnd = dayStart + 86_400_000L
    val dueTasks = remember(tasks, dayEnd) {
        tasks.filter { !it.completed && it.dueAt < dayEnd }
            .sortedWith(compareBy<StudyTask>({ it.priority }, { it.dueAt }))
            .take(5)
    }
    val todayEvents = remember(events, dayStart, dayEnd) {
        events.filter { it.at in dayStart until dayEnd }.sortedBy { it.at }
    }
    val (todayTotal, todayDone) = remember(tasks, dayStart, dayEnd) {
        val total = tasks.count { it.dueAt in dayStart until dayEnd }
        val done = tasks.count { it.completed && it.dueAt in dayStart until dayEnd }
        total to done
    }
    val subjectsById = remember(subjects) { subjects.associateBy { it.id } }
    if (dueTasks.isEmpty() && todayEvents.isEmpty() && todayTotal == 0) return
    val semantic = LocalStudySemanticColors.current
    StudyCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(Modifier.padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Para hoje", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (todayTotal > 0) {
                    Text(
                        "$todayDone/$todayTotal · ${todayDone * 100 / todayTotal}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
            if (dueTasks.isEmpty() && todayTotal > 0) {
                Text("Tudo concluído por hoje.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
            dueTasks.forEach { task ->
                val late = task.dueAt < dayStart
                val subjectName = task.subjectId?.let { subjectsById[it]?.name }
                val label = listOfNotNull(subjectName, if (late) "atrasada" else null).joinToString(" · ")
                val taskCheckboxColor = when (task.priority) {
                    1 -> MaterialTheme.colorScheme.error
                    2 -> semantic.warning
                    3 -> semantic.info
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = false,
                        onCheckedChange = { onToggleTask(task) },
                        colors = CheckboxDefaults.colors(uncheckedColor = taskCheckboxColor)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (label.isNotEmpty()) {
                            Text(
                                label,
                                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            todayEvents.forEach { event ->
                Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Event, null, tint = MaterialTheme.colorScheme.primary)
                    Text("${StudyDateFormats.formatEpoch(event.at, StudyDateFormats.TIME_HH_MM_PT)} · ${event.title}", modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

// ---------- resumo da semana ----------

@Composable
fun WeekInsightsCard(
    sessions: List<StudySession>,
    subjects: List<Subject> = emptyList(),
    aggregates: SessionAggregates? = null,
    dailyGoalMinutesOverride: Int? = null
) {
    if (sessions.isEmpty()) return
    val agg = aggregates ?: remember(sessions) { computeSessionAggregates(sessions) }
    val thisWeek = agg.thisWeekSeconds
    val lastWeek = agg.lastWeekSeconds
    val bestDay = agg.bestDayName
    val bestPeriod = agg.bestPeriodName
    val comparison = if (lastWeek > 0) {
        val delta = ((thisWeek - lastWeek) * 100 / lastWeek).toInt()
        (if (delta >= 0) "+$delta%" else "$delta%") + " em relação à semana passada (${xDuration(lastWeek)})"
    } else "Sem registros na semana passada para comparar."
    val resolvedGoal = rememberDailyGoalResolution(subjects)
    val dailyTarget = (dailyGoalMinutesOverride ?: resolvedGoal.goalMinutes).coerceAtLeast(1)
    val weeklyGoalMinutes = dailyTarget * 7
    val weeklyPercent = (thisWeek / 60f / weeklyGoalMinutes.coerceAtLeast(1) * 100).toInt()
    StudyCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding())) {
            Text("Resumo da semana", fontWeight = FontWeight.SemiBold)
            Text(xDuration(thisWeek), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            Text(comparison, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("$weeklyPercent% da meta semanal (${xDuration(weeklyGoalMinutes * 60L)})", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (bestDay != null && bestPeriod != null) {
                Text("Seu dia mais forte: $bestDay. Período preferido: $bestPeriod.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

// ---------- tela ligada durante o timer + avisos ----------

@Composable
fun KeepScreenOnEffect(active: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember(context) { context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE) }
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean("keep_screen_on", true)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "keep_screen_on" || key == null) {
                enabled = prefs.getBoolean("keep_screen_on", true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(active && enabled) {
        view.keepScreenOn = active && enabled
        onDispose { view.keepScreenOn = false }
    }
}

object StudyNotificationPrefs {
    private const val KEY_TIMER_END_NOTIFICATION = "notif_timer_end_enabled"
    private const val KEY_MATH_ARENA_SFX = "math_arena_sfx_enabled"

    fun isTimerEndNotificationEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_TIMER_END_NOTIFICATION, true)

    fun setTimerEndNotificationEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TIMER_END_NOTIFICATION, enabled).apply()
    }

    fun isMathArenaSfxEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_MATH_ARENA_SFX, true)

    fun setMathArenaSfxEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MATH_ARENA_SFX, enabled).apply()
    }
}

object TimerAlerts {
    private const val CHANNEL_ID = "timer_alerts"
    private const val NOTIFICATION_ID = 7001

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure { openAppNotificationSettings(context) }
        } else {
            openAppNotificationSettings(context)
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Avisos do timer",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifica quando ciclos Pomodoro, pausas ou temporizadores regressivos terminam"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun legacyVibrator(app: Context): Vibrator? =
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun vibrateOnce(app: Context, waveform: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            legacyVibrator(app)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(waveform, -1))
    }

    /** Mostra uma notificação de conclusão do timer (e vibração opcional). */
    fun alert(
        context: Context,
        title: String,
        text: String,
        vibrate: Boolean = false,
        silent: Boolean = false
    ): Boolean {
        val app = context.applicationContext
        return try {
            if (vibrate) {
                vibrateOnce(app, longArrayOf(0, 320, 140, 320))
            }
            if (!hasNotificationPermission(app)) return false
            val manager = app.getSystemService(NotificationManager::class.java) ?: return false
            ensureChannel(manager)
            val openIntent = Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_timer_page", true)
            }
            val open = PendingIntent.getActivity(
                app,
                NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
            if (silent) {
                builder.setSilent(true)
            }
            manager.notify(NOTIFICATION_ID, builder.build())
            true
        } catch (e: Exception) {
            // Sem permissão de notificação ou sem vibrador: o aviso é opcional.
            StudyLog.w("TimerAlerts", "Falha opcional ao emitir alerta/vibração do timer", e)
            false
        }
    }

    fun sendTestNotification(context: Context): Boolean = alert(
        context = context,
        title = "Notificações do StudyDock ativas",
        text = "Você receberá avisos do timer, sessões Pomodoro e lembretes de hábitos aqui.",
        vibrate = true,
        silent = false
    )
}

@Composable
fun NotificationSettingsCard() {
    val context = LocalContext.current
    var hasNotifPermission by remember { mutableStateOf(TimerAlerts.hasNotificationPermission(context)) }
    var canExactAlarm by remember { mutableStateOf(HabitReminderScheduler.canUseExactAlarms(context)) }
    var timerNotifEnabled by remember { mutableStateOf(StudyNotificationPrefs.isTimerEndNotificationEnabled(context)) }
    var mathSfxEnabled by remember { mutableStateOf(StudyNotificationPrefs.isMathArenaSfxEnabled(context)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotifPermission = granted || TimerAlerts.hasNotificationPermission(context)
        if (hasNotifPermission) {
            HabitReminderScheduler.ensureChannel(context)
            statusMessage = "Permissão de notificações ativada."
        } else {
            statusMessage = "Permissão negada no sistema. Use \"Abrir sistema\" para liberar."
        }
    }

    // Re-sync status when card enters or resumes.
    LaunchedEffect(Unit) {
        hasNotifPermission = TimerAlerts.hasNotificationPermission(context)
        canExactAlarm = HabitReminderScheduler.canUseExactAlarms(context)
    }

    StudyCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(StudyTokens.cardPadding()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (hasNotifPermission) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                    null,
                    tint = if (hasNotifPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Notificações & Lembretes", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (hasNotifPermission) {
                            "Permissão ativa · timer contínuo, Pomodoro e hábitos"
                        } else {
                            "Notificações bloqueadas no Android · ative para receber alertas"
                        },
                        color = if (hasNotifPermission) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    ) {
                        Icon(Icons.Default.NotificationsActive, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Permitir notificações", maxLines = 1)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canExactAlarm) {
                    FilledTonalButton(
                        onClick = {
                            TimerAlerts.openExactAlarmSettings(context)
                            statusMessage = "Após liberar alarmes exatos, os lembretes de hábitos disparam no minuto exato."
                        }
                    ) {
                        Icon(Icons.Default.Alarm, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Liberar alarmes exatos", maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = {
                        hasNotifPermission = TimerAlerts.hasNotificationPermission(context)
                        canExactAlarm = HabitReminderScheduler.canUseExactAlarms(context)
                        if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val sent = TimerAlerts.sendTestNotification(context)
                            HabitReminderScheduler.ensureChannel(context)
                            statusMessage = if (sent) {
                                "Notificação de teste enviada para a barra do sistema."
                            } else {
                                "Não foi possível enviar: verifique as permissões do app no Android."
                            }
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Testar aviso", maxLines = 1)
                }
                TextButton(
                    onClick = { TimerAlerts.openAppNotificationSettings(context) }
                ) {
                    Text("Canais do Android", maxLines = 1)
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Avisar término do Pomodoro / Timer", fontWeight = FontWeight.Medium)
                    Text(
                        "Exibe notificação de alta prioridade ao concluir ciclos de foco e pausas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = timerNotifEnabled,
                    onCheckedChange = { enabled ->
                        timerNotifEnabled = enabled
                        StudyNotificationPrefs.setTimerEndNotificationEnabled(context, enabled)
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Sons de resposta na Arena de Exatas", fontWeight = FontWeight.Medium)
                    Text(
                        "Feedback sonoro sutil ao acertar ou errar equações na Arena.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = mathSfxEnabled,
                    onCheckedChange = { enabled ->
                        mathSfxEnabled = enabled
                        StudyNotificationPrefs.setMathArenaSfxEnabled(context, enabled)
                    }
                )
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ---------- ajustes + backup ----------

internal data class ObsidianVaultZipActions(
    val busy: Boolean,
    val exportVault: () -> Unit,
    val importVault: () -> Unit
)

@Composable
internal fun rememberObsidianVaultZipActions(
    enabled: Boolean = true,
    onResult: (String) -> Unit
): ObsidianVaultZipActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val exportVaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    onResult(withContext(Dispatchers.IO) { xExportObsidianVaultZip(context, uri) })
                } finally {
                    busy = false
                }
            }
        }
    }
    val importVaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    onResult(withContext(Dispatchers.IO) { xImportObsidianVaultZip(context, uri) })
                } finally {
                    busy = false
                }
            }
        }
    }
    return remember(busy, enabled) {
        ObsidianVaultZipActions(
            busy = busy,
            exportVault = {
                if (enabled && !busy) {
                    exportVaultLauncher.launch(
                        "studydock-obsidian-vault-${StudyDateFormats.formatNow(StudyDateFormats.ISO_LOCAL_DATE)}.zip"
                    )
                }
            },
            importVault = {
                if (enabled && !busy) {
                    importVaultLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "text/markdown", "text/plain", "*/*")
                    )
                }
            }
        )
    }
}

@Composable
fun BackupAndSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE) }
    var keepOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", true)) }
    var focusDnd by remember { mutableStateOf(com.studydock.app.timer.FocusDndController.isEnabled(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try { message = withContext(Dispatchers.IO) { xExportDatabase(context, uri) } }
                finally { busy = false }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }
    var autoBackupState by remember { mutableStateOf(com.studydock.app.data.AutoBackupScheduler.readState(context)) }
    val autoFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            com.studydock.app.data.AutoBackupScheduler.setTreeUri(context, treeUri)
            autoBackupState = com.studydock.app.data.AutoBackupScheduler.readState(context)
            message = "Pasta configurada para backup diário automático."
        }
    }
    val vaultActions = rememberObsidianVaultZipActions(enabled = !busy) { message = it }
    val anyBusy = busy || vaultActions.busy
    val themeConfig = LocalStudyThemeConfig.current
    val openThemeStudio = LocalThemeStudioOpener.current
    StudyCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Aparência e densidade", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${themeConfig.baseSurface.label} · ${themeConfig.cardStyle.label} · ${themeConfig.density.label}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = openThemeStudio) {
                    Text("Personalizar", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Manter a tela ligada no timer", fontWeight = FontWeight.SemiBold)
                    Text("Evita que a tela apague enquanto você estuda.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = keepOn, onCheckedChange = { keepOn = it; prefs.edit().putBoolean("keep_screen_on", it).apply() })
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DoNotDisturbOn, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Modo foco (Não Perturbe no timer)", fontWeight = FontWeight.SemiBold)
                    Text("Silencia notificações automaticamente enquanto o timer estiver rodando.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = focusDnd,
                    onCheckedChange = { checked ->
                        if (checked && !com.studydock.app.timer.FocusDndController.hasPolicyAccess(context)) {
                            com.studydock.app.timer.FocusDndController.setEnabled(context, true)
                            focusDnd = true
                            com.studydock.app.timer.FocusDndController.openPolicyAccessSettings(context)
                            message = "Conceda acesso ao Não Perturbe nas configurações do Android para ativar automaticamente."
                        } else {
                            focusDnd = checked
                            com.studydock.app.timer.FocusDndController.setEnabled(context, checked)
                        }
                    }
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Text("Backup completo dos dados (.db)", fontWeight = FontWeight.SemiBold)
            Text("Inclui sessões, notas, livros, tarefas, hábitos, cartões, grupos do grafo Obsidian, planos de treino e metas de alimentação.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = !anyBusy,
                    onClick = { exportLauncher.launch("studydock-backup-${StudyDateFormats.formatNow(StudyDateFormats.ISO_LOCAL_DATE)}.db") }
                ) {
                    Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Salvar .db", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    enabled = !anyBusy,
                    onClick = { importLauncher.launch(arrayOf("*/*")) }
                ) {
                    Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restaurar .db", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Backup automático diário (pasta SAF)", fontWeight = FontWeight.SemiBold)
                    val autoDesc = when {
                        autoBackupState.treeUri == null -> "Escolha uma pasta local (ou sincronizada por Drive/Syncthing) para manter os 5 últimos backups."
                        autoBackupState.lastBackupAt > 0L -> "Último: ${StudyDateFormats.formatEpoch(autoBackupState.lastBackupAt, StudyDateFormats.DATE_TIME_DOT_DEFAULT)} · ${autoBackupState.lastBackupStatus.take(60)}"
                        else -> "Pasta configurada. Agendado a cada 24h."
                    }
                    Text(autoDesc, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = autoBackupState.enabled && autoBackupState.treeUri != null,
                    enabled = autoBackupState.treeUri != null && !anyBusy,
                    onCheckedChange = { checked ->
                        com.studydock.app.data.AutoBackupScheduler.setEnabled(context, checked)
                        autoBackupState = com.studydock.app.data.AutoBackupScheduler.readState(context)
                    }
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !anyBusy,
                    onClick = { autoFolderLauncher.launch(null) }
                ) {
                    Text(if (autoBackupState.treeUri == null) "Escolher pasta" else "Trocar pasta", maxLines = 1)
                }
                if (autoBackupState.treeUri != null) {
                    OutlinedButton(
                        enabled = !anyBusy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    message = withContext(Dispatchers.IO) { com.studydock.app.data.AutoBackupScheduler.runBackupNow(context) }
                                    autoBackupState = com.studydock.app.data.AutoBackupScheduler.readState(context)
                                } finally { busy = false }
                            }
                        }
                    ) {
                        Text("Fazer backup agora", maxLines = 1)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Hub, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cofre Obsidian (.zip de notas Markdown)", fontWeight = FontWeight.SemiBold)
            }
            Text("Exporte todas as notas do Segundo Cérebro como arquivos .md reais organizados em pastas (compatível com Obsidian no PC/celular), ou importe um arquivo .zip/.md com suas notas e [[wikilinks]].", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    enabled = !anyBusy,
                    onClick = vaultActions.exportVault
                ) {
                    Icon(Icons.Default.FolderZip, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Exportar Cofre .zip", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    enabled = !anyBusy,
                    onClick = vaultActions.importVault
                ) {
                    Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Importar .zip / .md", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.Medium) }
        }
    }
    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Restaurar backup?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Isso substitui todos os dados atuais pelos do arquivo. Uma cópia dos dados de agora fica guardada no aparelho. O app vai fechar; abra-o de novo em seguida.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    busy = true
                    scope.launch {
                        val error = try {
                            withContext(Dispatchers.IO) { xImportDatabase(context, uri) }
                        } finally {
                            busy = false
                        }
                        if (error == null) {
                            Toast.makeText(context, "Backup restaurado. Abra o StudyDock de novo.", Toast.LENGTH_LONG).show()
                            context.xActivity()?.finishAffinity()
                        } else message = error
                    }
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancelar") } }
        )
    }
}

private const val FITNESS_PREFS = "studydock_fitness"
private const val GRAPH_PREFS = "studydock_obsidian_graph"
private const val GRAPH_GROUPS_KEY = "group_colors_json"
private const val GRAPH_RULES_KEY = "custom_rules_json"
private const val LEGACY_GRAPH_GROUPS_KEY = "color_groups_json"
private const val MAX_OBSIDIAN_ENTRY_BYTES = 2 * 1024 * 1024
private const val MAX_OBSIDIAN_TOTAL_BYTES = 32L * 1024 * 1024
private const val MAX_OBSIDIAN_ENTRIES = 5_000
private val fitnessTargets = listOf("target_calories", "target_protein", "target_carbs", "target_fat")

private val BACKUP_EXTRA_PREF_FILES = listOf(
    "studydock_settings",
    "studydock_reader",
    "studydock_review_prefs",
    "studydock_note_editor",
    "studydock_audio",
    "studydock_card_templates",
    "bookshelf"
)

internal fun encodeExtraPrefsBundle(context: Context): org.json.JSONObject {
    val root = org.json.JSONObject()
    for (fileName in BACKUP_EXTRA_PREF_FILES) {
        val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        val entries = prefs.all
        if (entries.isEmpty()) continue
        val fileObj = org.json.JSONObject()
        for ((key, value) in entries) {
            when (value) {
                is Boolean -> fileObj.put(key, org.json.JSONObject().put("t", "B").put("v", value))
                is Int -> fileObj.put(key, org.json.JSONObject().put("t", "I").put("v", value))
                is Long -> fileObj.put(key, org.json.JSONObject().put("t", "L").put("v", value))
                is Float -> fileObj.put(key, org.json.JSONObject().put("t", "F").put("v", value.toDouble()))
                is String -> fileObj.put(key, org.json.JSONObject().put("t", "S").put("v", value))
                is Set<*> -> {
                    val arr = org.json.JSONArray()
                    value.filterIsInstance<String>().forEach { arr.put(it) }
                    fileObj.put(key, org.json.JSONObject().put("t", "SS").put("v", arr))
                }
            }
        }
        if (fileObj.length() > 0) root.put(fileName, fileObj)
    }
    return root
}

internal fun restoreExtraPrefsBundle(context: Context, root: org.json.JSONObject?) {
    if (root == null) return
    for (fileName in BACKUP_EXTRA_PREF_FILES) {
        val fileObj = root.optJSONObject(fileName) ?: continue
        val edit = context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit()
        edit.clear()
        val keys = fileObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = fileObj.optJSONObject(key) ?: continue
            when (item.optString("t")) {
                "B" -> edit.putBoolean(key, item.optBoolean("v"))
                "I" -> edit.putInt(key, item.optInt("v"))
                "L" -> edit.putLong(key, item.optLong("v"))
                "F" -> edit.putFloat(key, item.optDouble("v").toFloat())
                "S" -> edit.putString(key, item.optString("v"))
                "SS" -> {
                    val arr = item.optJSONArray("v")
                    if (arr != null) {
                        val set = LinkedHashSet<String>(arr.length())
                        for (i in 0 until arr.length()) {
                            set.add(arr.optString(i))
                        }
                        edit.putStringSet(key, set)
                    }
                }
            }
        }
        edit.apply()
    }
}

private fun tryCheckpoint(db: androidx.sqlite.db.SupportSQLiteDatabase): Boolean =
    db.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
        it.moveToFirst() && it.getInt(0) == 0
    }

private fun checkpoint(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    check(tryCheckpoint(db)) { "O banco está ocupado. Tente novamente." }
}

internal fun exportDatabaseToUri(context: Context, target: Uri): String = xExportDatabase(context, target)

private fun xExportDatabase(context: Context, target: Uri): String {
    var temp: File? = null
    return try {
        val exportTemp = File.createTempFile("studydock-export-", ".db", context.cacheDir)
        temp = exportTemp
        val liveDbFile = context.getDatabasePath("studydock.db")
        val walFile = File("${liveDbFile.path}-wal")
        val db = StudyDatabase.get(context).openHelper.writableDatabase
        var lockedClean = false
        for (attempt in 0 until 5) {
            if (tryCheckpoint(db)) {
                db.beginTransaction()
                if (!walFile.exists() || walFile.length() == 0L) {
                    lockedClean = true
                    break
                }
                db.endTransaction()
            }
            if (attempt < 4) Thread.sleep(30L * (attempt + 1))
        }
        check(lockedClean) { "O banco está ocupado. Tente novamente." }
        try {
            liveDbFile.copyTo(exportTemp, overwrite = true)
        } finally { db.endTransaction() }
        val prefs = context.getSharedPreferences(FITNESS_PREFS, Context.MODE_PRIVATE)
        val graphPrefs = context.getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE)
        val settings = org.json.JSONObject()
        prefs.getString("training_plans", null)?.let { settings.put("training_plans", it) }
        val groupColorsJson = graphPrefs.getString(GRAPH_GROUPS_KEY, null)
            ?: graphPrefs.getString(LEGACY_GRAPH_GROUPS_KEY, null)
        groupColorsJson?.let { settings.put("obsidian_group_colors", it) }
        graphPrefs.getString(GRAPH_RULES_KEY, null)?.let { settings.put("obsidian_custom_rules", it) }
        fitnessTargets.filter { prefs.contains(it) }.forEach { settings.put(it, prefs.getInt(it, 0)) }
        val extraPrefs = encodeExtraPrefsBundle(context)
        if (extraPrefs.length() > 0) settings.put("extra_prefs", extraPrefs)
        android.database.sqlite.SQLiteDatabase.openDatabase(exportTemp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { snapshot ->
            snapshot.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst() && it.getString(0).equals("delete", true)) }
            snapshot.execSQL("CREATE TABLE IF NOT EXISTS backup_fitness (settings TEXT NOT NULL)")
            snapshot.execSQL("DELETE FROM backup_fitness")
            snapshot.execSQL("INSERT INTO backup_fitness VALUES (?)", arrayOf(settings.toString()))
        }
        val output = context.contentResolver.openOutputStream(target, "wt") ?: error("Não foi possível abrir o destino.")
        output.use { stream -> exportTemp.inputStream().use { it.copyTo(stream) } }
        "Backup completo (.db) salvo com sucesso."
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        StudyLog.e("StudyExtras", "Falha ao exportar backup SQLite (.db)", e)
        "Falha ao salvar o backup: ${e.message ?: "erro desconhecido"}"
    } finally {
        temp?.let { android.database.sqlite.SQLiteDatabase.deleteDatabase(it) }
    }
}

internal fun normalizeSafeVaultZipEntryPath(rawName: String): String {
    val normalized = rawName.replace('\\', '/')
    if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) {
        throw IllegalArgumentException("Entrada inválida detectada no arquivo .zip.")
    }
    return normalized.trimStart('/')
}

internal fun cleanupStaleDatabaseImportFiles(
    filesDir: File?,
    databasesDir: File?,
    maxBeforeImportAgeMs: Long = 24L * 60 * 60 * 1000L,
    nowMillis: Long = System.currentTimeMillis()
) {
    if (filesDir != null && filesDir.isDirectory) {
        val beforeImport = File(filesDir, "before-import.db")
        if (beforeImport.exists() && nowMillis - beforeImport.lastModified() >= maxBeforeImportAgeMs) {
            beforeImport.delete()
        }
        filesDir.listFiles { _, name ->
            name == "before-import.db-wal" || name == "before-import.db-shm"
        }?.forEach { it.delete() }
    }
    if (databasesDir != null && databasesDir.isDirectory) {
        databasesDir.listFiles { _, name ->
            name.startsWith("studydock-import-") && name.contains(".db")
        }?.forEach { file ->
            if (nowMillis - file.lastModified() >= 60L * 60L * 1000L) {
                file.delete()
            }
        }
    }
}

private suspend fun xImportDatabase(context: Context, source: Uri): String? {
    val dbFile = context.getDatabasePath("studydock.db")
    val beforeImportFile = File(context.filesDir, "before-import.db")
    var temp: File? = null
    var replacedLiveDb = false
    return try {
        dbFile.parentFile?.mkdirs()
        cleanupStaleDatabaseImportFiles(context.filesDir, dbFile.parentFile)
        val importTemp = File.createTempFile("studydock-import-", ".db", dbFile.parentFile)
        temp = importTemp
        val input = context.contentResolver.openInputStream(source) ?: error("Não foi possível ler o arquivo.")
        input.use { stream -> importTemp.outputStream().use { stream.copyTo(it) } }
        val live = StudyDatabase.get(context)
        val currentVersion = live.openHelper.readableDatabase.version
        var fitness: org.json.JSONObject? = null
        android.database.sqlite.SQLiteDatabase.openDatabase(importTemp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { copy ->
            require(copy.version in 1..currentVersion) { "Versão de backup incompatível." }
            copy.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") { "O arquivo está danificado." } }
            copy.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='backup_fitness'", null).use { found ->
                if (found.moveToFirst()) copy.rawQuery("SELECT settings FROM backup_fitness", null).use {
                    check(it.moveToFirst())
                    fitness = org.json.JSONObject(it.getString(0))
                }
            }
            fitness?.let { config ->
                if (config.has("training_plans")) validateTrainingPlans(config.getString("training_plans"))
                fitnessTargets.filter(config::has).forEach { require(config.getInt(it) >= 0) }
            }
            copy.execSQL("DROP TABLE IF EXISTS backup_fitness")
            // Room verifies the imported tables and indexes when its identity table is absent.
            copy.execSQL("DROP TABLE IF EXISTS room_master_table")
        }
        val staged = StudyDatabase.openNamed(context, importTemp.name)
        try {
            val db = staged.openHelper.writableDatabase
            db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "O backup contém vínculos inválidos." } }
            db.query("SELECT COUNT(*) FROM note_snapshots").use { check(it.moveToFirst()) }
            db.query("SELECT COUNT(*) FROM canvas_boards").use { check(it.moveToFirst()) }
            checkpoint(db)
        } finally { staged.close() }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            importTemp.path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        ).use { flattened ->
            flattened.rawQuery("PRAGMA journal_mode=DELETE", null).use {
                check(it.moveToFirst() && it.getString(0).equals("delete", true))
            }
        }
        val oldReminders = live.studyDao().habitsWithReminder()
        checkpoint(live.openHelper.writableDatabase)
        StudyDatabase.closeAndReset()
        dbFile.copyTo(beforeImportFile, overwrite = true)
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        java.nio.file.Files.move(importTemp.toPath(), dbFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        replacedLiveDb = true
        val restoredDb = StudyDatabase.get(context)
        restoredDb.studyDao().cleanOrphanedReferencesAndMedia()
        runCatching {
            val writable = restoredDb.openHelper.writableDatabase
            if (!writable.inTransaction()) writable.execSQL("VACUUM")
        }
        fitness?.let { config ->
            val edit = context.getSharedPreferences(FITNESS_PREFS, Context.MODE_PRIVATE).edit()
            if (config.has("training_plans")) edit.putString("training_plans", config.getString("training_plans")) else edit.remove("training_plans")
            fitnessTargets.forEach { if (config.has(it)) edit.putInt(it, config.getInt(it)) else edit.remove(it) }
            edit.apply()
            val graphEdit = context.getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE).edit()
            val restoredGroups = when {
                config.has("obsidian_group_colors") -> config.optString("obsidian_group_colors", "")
                config.has("obsidian_color_groups") -> config.optString("obsidian_color_groups", "")
                else -> null
            }?.takeIf { it.isNotBlank() }
            if (restoredGroups != null) {
                graphEdit.putString(GRAPH_GROUPS_KEY, restoredGroups)
            } else {
                graphEdit.remove(GRAPH_GROUPS_KEY)
            }
            graphEdit.remove(LEGACY_GRAPH_GROUPS_KEY)
            val restoredRules = if (config.has("obsidian_custom_rules")) {
                config.optString("obsidian_custom_rules", "").takeIf { it.isNotBlank() }
            } else null
            if (restoredRules != null) {
                graphEdit.putString(GRAPH_RULES_KEY, restoredRules)
            } else {
                graphEdit.remove(GRAPH_RULES_KEY)
            }
            graphEdit.apply()
            restoreExtraPrefsBundle(context, config.optJSONObject("extra_prefs"))
        }
        context.getSharedPreferences("studydock_timer", Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            oldReminders.forEach { HabitReminderScheduler.cancel(context, it.id) }
            restoredDb.studyDao().habitsWithReminder().forEach { habit ->
                habit.reminderMinutes?.let { HabitReminderScheduler.schedule(context, habit.id, "${habit.emoji} ${habit.title}", it, habit.repeatDaysMask) }
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            StudyLog.w("StudyExtras", "Falha ao reagendar lembretes após restaurar backup .db", err)
        }
        beforeImportFile.delete()
        cleanupStaleDatabaseImportFiles(context.filesDir, dbFile.parentFile, maxBeforeImportAgeMs = 0L)
        null
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (replacedLiveDb && beforeImportFile.exists()) {
            runCatching {
                StudyDatabase.closeAndReset()
                File("${dbFile.path}-wal").delete()
                File("${dbFile.path}-shm").delete()
                beforeImportFile.copyTo(dbFile, overwrite = true)
            }
        }
        if (e is IllegalArgumentException) {
            StudyLog.w("StudyExtras", "Backup SQLite inválido ou incompatível ao restaurar", e)
        } else {
            StudyLog.e("StudyExtras", "Falha ao restaurar backup SQLite (.db)", e)
        }
        "Falha ao restaurar: ${e.message ?: "erro desconhecido"}"
    } finally {
        temp?.let { context.deleteDatabase(it.name) }
    }
}

private fun xReadStreamBounded(
    input: InputStream,
    maxEntryBytes: Int,
    remainingTotalBytes: Long,
    entryLabel: String
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        if (total > maxEntryBytes) {
            throw IllegalArgumentException("A entrada '$entryLabel' excede o limite de 2 MB.")
        }
        if (total.toLong() > remainingTotalBytes) {
            throw IllegalArgumentException("O arquivo .zip excede o limite total de 32 MB extraídos.")
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

internal suspend fun xExportObsidianVaultZip(context: Context, target: Uri): String {
    return try {
        val dao = StudyDatabase.get(context).studyDao()
        val notes = dao.allNotes().filter { it.trashedAt == null }
        val boards = dao.canvasBoards().first()
        if (notes.isEmpty() && boards.isEmpty()) {
            return "Nenhuma nota encontrada no Segundo Cérebro para exportar."
        }
        val output = context.contentResolver.openOutputStream(target, "wt")
            ?: error("Não foi possível criar o arquivo .zip.")
        val usedPaths = mutableSetOf(
            "_studydock_graph_groups.json",
            "_studydock_graph_rules.json"
        )
        output.use { rawOut ->
            java.util.zip.ZipOutputStream(rawOut.buffered()).use { zip ->
                for (note in notes) {
                    val folderRaw = NoteFrontmatter.properties(note.markdown).folder?.trim().orEmpty()
                    val safeFolder = folderRaw
                        .split('/', '\\')
                        .map { it.replace(Regex("""[<>:"|?*\x00-\x1F]"""), "_").trim() }
                        .filter { it.isNotEmpty() && it != "." && it != ".." }
                        .joinToString("/")
                    val baseTitle = note.title.trim().ifBlank { "Nota-${note.id}" }
                        .replace(Regex("""[/\\<>:"|?*\x00-\x1F]"""), "_")
                    var candidatePath = if (safeFolder.isNotEmpty()) "$safeFolder/$baseTitle.md" else "$baseTitle.md"
                    var suffix = 2
                    while (!usedPaths.add(candidatePath.lowercase(Locale.ROOT))) {
                        candidatePath = if (safeFolder.isNotEmpty()) "$safeFolder/$baseTitle ($suffix).md" else "$baseTitle ($suffix).md"
                        suffix++
                    }
                    val entry = java.util.zip.ZipEntry(candidatePath).apply {
                        time = note.updatedAt
                    }
                    zip.putNextEntry(entry)
                    zip.write(note.markdown.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                for (board in boards) {
                    val baseTitle = board.title.trim().ifBlank { "Canvas-${board.id}" }
                        .replace(Regex("""[/\\<>:"|?*\x00-\x1F]"""), "_")
                    var candidatePath = "$baseTitle.canvas"
                    var suffix = 2
                    while (!usedPaths.add(candidatePath.lowercase(Locale.ROOT))) {
                        candidatePath = "$baseTitle ($suffix).canvas"
                        suffix++
                    }
                    val entry = java.util.zip.ZipEntry(candidatePath).apply {
                        time = board.updatedAt
                    }
                    zip.putNextEntry(entry)
                    zip.write(board.jsonData.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                val graphPrefs = context.getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE)
                val groupColorsJson = graphPrefs.getString(GRAPH_GROUPS_KEY, null)
                    ?: graphPrefs.getString(LEGACY_GRAPH_GROUPS_KEY, null)
                if (!groupColorsJson.isNullOrBlank()) {
                    zip.putNextEntry(java.util.zip.ZipEntry("_studydock_graph_groups.json"))
                    zip.write(groupColorsJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                val customRulesJson = graphPrefs.getString(GRAPH_RULES_KEY, null)
                if (!customRulesJson.isNullOrBlank()) {
                    zip.putNextEntry(java.util.zip.ZipEntry("_studydock_graph_rules.json"))
                    zip.write(customRulesJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        val boardSuffix = if (boards.isNotEmpty()) " e ${boards.size} quadros .canvas" else ""
        "Cofre Obsidian exportado (${notes.size} notas .md$boardSuffix salvas no .zip)."
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        StudyLog.e("StudyExtras", "Falha ao exportar cofre Obsidian (.zip)", e)
        "Falha ao exportar Cofre Obsidian: ${e.message ?: "erro desconhecido"}"
    }
}

internal suspend fun xImportObsidianVaultZip(context: Context, source: Uri): String {
    return try {
        val dao = StudyDatabase.get(context).studyDao()
        val rawStream = context.contentResolver.openInputStream(source)
            ?: error("Não foi possível ler o arquivo selecionado.")

        data class ImportedNoteEntry(val title: String, val folder: String?, val markdown: String, val updatedAt: Long)
        data class ImportedCanvasEntry(val title: String, val jsonData: String, val updatedAt: Long)
        val entriesToImport = mutableListOf<ImportedNoteEntry>()
        val canvasEntriesToImport = mutableListOf<ImportedCanvasEntry>()
        var importedGroupColorsJson: String? = null
        var importedCustomRulesJson: String? = null

        BufferedInputStream(rawStream).use { buffered ->
            buffered.mark(8)
            val magic = ByteArray(4)
            var magicRead = 0
            while (magicRead < 4) {
                val read = buffered.read(magic, magicRead, 4 - magicRead)
                if (read == -1) break
                magicRead += read
            }
            if (magicRead == 0) return "O arquivo selecionado está vazio."
            buffered.reset()

            // Detect ZIP magic header (PK\x03\x04 or empty archive PK\x05\x06) vs standalone .md file
            val isZip = magicRead == 4 &&
                magic[0] == 0x50.toByte() &&
                magic[1] == 0x4B.toByte() &&
                ((magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()) ||
                    (magic[2] == 0x05.toByte() && magic[3] == 0x06.toByte()))

            if (isZip) {
                var totalExtractedBytes = 0L
                var entryCount = 0
                java.util.zip.ZipInputStream(buffered).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entryCount++
                        if (entryCount > MAX_OBSIDIAN_ENTRIES) {
                            throw IllegalArgumentException("O arquivo .zip excede o limite de $MAX_OBSIDIAN_ENTRIES entradas.")
                        }
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        val entryName = normalizeSafeVaultZipEntryPath(entry.name)
                        val isHidden = entryName.startsWith(".") || entryName.contains("/.") || entryName.startsWith("__MACOSX")
                        val isGroupColorsEntry = entryName == "_studydock_graph_groups.json"
                        val isCustomRulesEntry = entryName == "_studydock_graph_rules.json"
                        val isCanvasEntry = !isGroupColorsEntry && !isCustomRulesEntry && !isHidden &&
                            entryName.endsWith(".canvas", ignoreCase = true)
                        val isMarkdownEntry = !isGroupColorsEntry && !isCustomRulesEntry && !isHidden &&
                            (entryName.endsWith(".md", ignoreCase = true) || entryName.endsWith(".txt", ignoreCase = true))

                        if (!isGroupColorsEntry && !isCustomRulesEntry && !isCanvasEntry && !isMarkdownEntry) {
                            zip.closeEntry()
                            continue
                        }

                        if (entry.size > MAX_OBSIDIAN_ENTRY_BYTES) {
                            throw IllegalArgumentException("A entrada '$entryName' excede o limite de 2 MB.")
                        }
                        val remainingBudget = MAX_OBSIDIAN_TOTAL_BYTES - totalExtractedBytes
                        if (remainingBudget <= 0L) {
                            throw IllegalArgumentException("O arquivo .zip excede o limite total de 32 MB extraídos.")
                        }
                        val entryBytes = xReadStreamBounded(
                            input = zip,
                            maxEntryBytes = MAX_OBSIDIAN_ENTRY_BYTES,
                            remainingTotalBytes = remainingBudget,
                            entryLabel = entryName
                        )
                        totalExtractedBytes += entryBytes.size
                        val text = entryBytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")

                        when {
                            isGroupColorsEntry -> {
                                if (text.isNotBlank()) {
                                    val parsed = runCatching { org.json.JSONObject(text) }
                                        .onFailure { err ->
                                            StudyLog.w("StudyExtras", "JSON inválido ou legado em _studydock_graph_groups.json", err)
                                        }
                                        .getOrNull()
                                    if (parsed != null && (parsed.has(GRAPH_GROUPS_KEY) || parsed.has(GRAPH_RULES_KEY) || parsed.has(LEGACY_GRAPH_GROUPS_KEY))) {
                                        val groups = parsed.optString(GRAPH_GROUPS_KEY, "").ifBlank {
                                            parsed.optString(LEGACY_GRAPH_GROUPS_KEY, "")
                                        }
                                        if (groups.isNotBlank()) importedGroupColorsJson = groups
                                        val rules = parsed.optString(GRAPH_RULES_KEY, "")
                                        if (rules.isNotBlank()) importedCustomRulesJson = rules
                                    } else {
                                        importedGroupColorsJson = text
                                    }
                                }
                            }
                            isCustomRulesEntry -> {
                                if (text.isNotBlank()) {
                                    importedCustomRulesJson = text
                                }
                            }
                            isCanvasEntry -> {
                                val validJson = runCatching { org.json.JSONObject(text) }
                                    .onFailure { err -> StudyLog.w("StudyExtras", "Canvas JSON inválido em '$entryName'", err) }
                                    .getOrNull()
                                if (validJson != null) {
                                    val fileName = entryName.substringAfterLast('/')
                                    val title = fileName.replace(Regex("""\.canvas$""", RegexOption.IGNORE_CASE), "")
                                        .trim()
                                        .ifBlank { "Canvas Importado" }
                                    val ts = entry.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                                    canvasEntriesToImport.add(ImportedCanvasEntry(title, text, ts))
                                }
                            }
                            else -> {
                                val segments = entryName.split('/')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() && it != "." && it != ".." }
                                val fileName = segments.lastOrNull() ?: "Nota.md"
                                val title = fileName.replace(Regex("""\.(md|txt)$""", RegexOption.IGNORE_CASE), "")
                                    .trim()
                                    .ifBlank { "Nota Importada" }
                                val folder = if (segments.size > 1) {
                                    segments.dropLast(1)
                                        .map { it.replace(Regex("""[\x00-\x1F]"""), "_").trim() }
                                        .filter { it.isNotEmpty() && it != "." && it != ".." }
                                        .joinToString("/")
                                        .ifBlank { null }
                                } else null
                                val ts = entry.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                                entriesToImport.add(ImportedNoteEntry(title, folder, text, ts))
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } else {
                val contentBytes = xReadStreamBounded(
                    input = buffered,
                    maxEntryBytes = MAX_OBSIDIAN_ENTRY_BYTES,
                    remainingTotalBytes = MAX_OBSIDIAN_ENTRY_BYTES.toLong(),
                    entryLabel = "arquivo.md"
                )
                val content = contentBytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val firstHeading = content.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("# ") }
                    ?.removePrefix("# ")
                    ?.trim()
                val title = firstHeading?.takeIf { it.isNotBlank() } ?: "Nota Importada (${StudyDateFormats.formatNow(StudyDateFormats.IMPORT_DATE_TIME_US)})"
                entriesToImport.add(ImportedNoteEntry(title, null, content, System.currentTimeMillis()))
            }
        }

        if (importedGroupColorsJson != null || importedCustomRulesJson != null) {
            val graphEdit = context.getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE).edit()
            importedGroupColorsJson?.let {
                graphEdit.putString(GRAPH_GROUPS_KEY, it)
                graphEdit.remove(LEGACY_GRAPH_GROUPS_KEY)
            }
            importedCustomRulesJson?.let {
                graphEdit.putString(GRAPH_RULES_KEY, it)
            }
            graphEdit.apply()
        }

        var canvasCount = 0
        if (canvasEntriesToImport.isNotEmpty()) {
            val existingBoardsByTitle = dao.canvasBoards().first()
                .associateBy { it.title.trim().lowercase(Locale.ROOT) }
                .toMutableMap()
            for (boardItem in canvasEntriesToImport) {
                val key = boardItem.title.trim().lowercase(Locale.ROOT)
                val existingBoard = existingBoardsByTitle[key]
                val savedId = dao.upsertCanvasBoard(
                    existingBoard?.copy(
                        jsonData = boardItem.jsonData,
                        updatedAt = maxOf(existingBoard.updatedAt, boardItem.updatedAt)
                    ) ?: CanvasBoard(
                        title = boardItem.title,
                        jsonData = boardItem.jsonData,
                        createdAt = boardItem.updatedAt,
                        updatedAt = boardItem.updatedAt
                    )
                )
                dao.canvasBoardById(savedId)?.let { existingBoardsByTitle[key] = it }
                canvasCount++
            }
        }

        if (entriesToImport.isEmpty()) {
            return when {
                canvasCount > 0 -> "Quadros Canvas importados: $canvasCount arquivo(s) .canvas."
                importedGroupColorsJson != null || importedCustomRulesJson != null ->
                    "Configurações do grafo Obsidian importadas (nenhuma nota .md encontrada no arquivo)."
                else -> "Nenhum arquivo Markdown (.md) encontrado no arquivo selecionado."
            }
        }

        val existingByTitle = dao.allNotes()
            .filter { it.trashedAt == null }
            .associateBy { it.title.trim().lowercase(Locale.ROOT) }
            .toMutableMap()

        var createdCount = 0
        var updatedCount = 0

        for (item in entriesToImport) {
            val (existingProps, _) = NoteFrontmatter.parse(item.markdown)
            val finalMarkdown = if (!item.folder.isNullOrBlank() && existingProps.folder.isNullOrBlank()) {
                NoteFrontmatter.apply(item.markdown, existingProps.copy(folder = item.folder))
            } else {
                item.markdown
            }
            val key = item.title.trim().lowercase(Locale.ROOT)
            val existing = existingByTitle[key]
            if (existing != null) {
                val targetUpdatedAt = maxOf(existing.updatedAt, item.updatedAt)
                val updated = dao.updateNoteContentAndRebase(
                    id = existing.id,
                    title = existing.title,
                    markdown = finalMarkdown,
                    updatedAt = targetUpdatedAt
                )
                if (updated != null) {
                    existingByTitle[key] = updated
                }
                updatedCount++
            } else {
                val insertedId = dao.insertNoteRow(
                    Note(
                        title = item.title.trim(),
                        markdown = finalMarkdown,
                        updatedAt = item.updatedAt
                    )
                )
                val created = dao.findNote(insertedId)
                if (created != null) {
                    existingByTitle[key] = created
                    dao.maybeCreateNoteSnapshot(null, created)
                }
                createdCount++
            }
        }

        // Rebuild [[wikilinks]] across all active notes using lightweight headers so stale links are cleared too.
        val allActiveNotes = dao.allNotes().filter { it.trashedAt == null }
        val activeHeaders = dao.allNoteLinkHeaders().filter { it.trashedAt == null }
        for (note in allActiveNotes) {
            val known = dao.linksFromNote(note.id).associate { it.reference to it.targetId }
            val bound = NoteReferences.boundReferencesFromHeaders(note.markdown, activeHeaders, known)
                .filterValues { it != note.id }
            dao.replaceNoteLinks(note.id, bound)
        }

        val canvasSummary = if (canvasCount > 0) ", $canvasCount quadros .canvas" else ""
        "Cofre Obsidian importado: $createdCount novas notas, $updatedCount atualizadas$canvasSummary (links sincronizados!)."
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is IllegalArgumentException) {
            StudyLog.w("StudyExtras", "Arquivo Obsidian .zip/.md rejeitado na validação de importação", e)
        } else {
            StudyLog.e("StudyExtras", "Falha ao importar cofre Obsidian (.zip/.md)", e)
        }
        "Falha ao importar Cofre Obsidian: ${e.message ?: "erro desconhecido"}"
    }
}
