package com.studydock.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studydock.app.data.*
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudyAnalyticsPage(
    sessions: List<StudySession>,
    subjects: List<Subject>,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier
) {
    var periodDays by remember { mutableIntStateOf(30) }
    var selectedHeatmapDay by remember { mutableStateOf<HeatmapDay?>(null) }

    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }

    val (currentSessions, previousSessions) = remember(sessions, periodDays, today, zone) {
        StudyAnalyticsEngine.filterSessionsInPeriod(sessions, periodDays, today, zone)
    }

    val summary = remember(currentSessions, sessions, periodDays, today, zone) {
        StudyAnalyticsEngine.computeSummary(currentSessions, sessions, periodDays, today = today, zone = zone)
    }

    val comparison = remember(currentSessions, previousSessions) {
        StudyAnalyticsEngine.computePeriodComparison(currentSessions, previousSessions)
    }

    val subjectDistributions = remember(currentSessions, subjects) {
        StudyAnalyticsEngine.computeSubjectDistributions(currentSessions, subjects)
    }

    val timeOfDay = remember(currentSessions, zone) {
        StudyAnalyticsEngine.computeTimeOfDayDistribution(currentSessions, zone)
    }

    val dailyBars = remember(currentSessions, periodDays, today, zone) {
        StudyAnalyticsEngine.computeDailyBarData(currentSessions, minOf(periodDays, 30), today, zone)
    }

    val heatmapDays = remember(sessions, today, zone) {
        StudyAnalyticsEngine.computeHeatmap(sessions, weeksCount = 16, today = today, zone = zone)
    }

    val semantic = LocalStudySemanticColors.current
    val cardPadding = StudyTokens.cardPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        StudyPageHeader(
            title = "Painel Analítico",
            subtitle = "Evolução, consistência e métricas de estudo",
            showGlobalSearch = true,
            actions = {
                TextButton(onClick = onOpenCalendar) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Agenda", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        )

        // PERIOD FILTER CHIPS: [Hoje | 7 Dias | 30 Dias | 90 Dias | 1 Ano]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = listOf(
                1 to "Hoje",
                7 to "7 dias",
                30 to "30 dias",
                90 to "3 meses",
                365 to "1 ano"
            )
            options.forEach { (days, label) ->
                FilterChip(
                    selected = periodDays == days,
                    onClick = { periodDays = days },
                    label = { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
            }
        }

        // MAIN OVERVIEW CARD WITH COMPARISON
        StudyCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            isHero = true
        ) {
            Column(Modifier.padding(cardPadding)) {
                Text(
                    "TEMPO TOTAL DE FOCO",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val avgPurity = remember(currentSessions) {
                        val totalSec = currentSessions.sumOf { it.durationSeconds }
                        if (currentSessions.isEmpty() || totalSec <= 0L) {
                            100
                        } else {
                            (currentSessions.sumOf { it.purityPercent.toLong() * it.durationSeconds } / totalSec).toInt().coerceIn(0, 100)
                        }
                    }
                    Text(
                        "${formatAnalyticsHoursMinutes(summary.totalFocusSeconds)} · Pureza $avgPurity%",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )

                    // Period comparison badge
                    if (comparison.previousTotalSeconds > 0 || comparison.currentTotalSeconds > 0) {
                        val badgeContainer = if (comparison.isPositive) semantic.successContainer else MaterialTheme.colorScheme.errorContainer
                        val badgeContent = if (comparison.isPositive) semantic.onSuccessContainer else MaterialTheme.colorScheme.onErrorContainer
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = badgeContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (comparison.isPositive) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                                    null,
                                    Modifier.size(16.dp),
                                    tint = badgeContent
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${if (comparison.isPositive) "+" else ""}${comparison.percentageDiff.toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeContent,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                if (summary.totalBreakSeconds > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tempo de pausas: ${formatAnalyticsHoursMinutes(summary.totalBreakSeconds)} (${summary.sessionCount} sessões)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))

                // EVOLUTION BAR CHART
                EvolutionBarChart(dailyBars)
            }
        }

        // KPI METRICS GRID (2x2)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnalyticsMetricCard(
                title = "Sequência (Streak)",
                value = if (summary.currentStreakDays > 0) "🔥 ${summary.currentStreakDays} dias" else "0 dias",
                subtext = "Recorde: ${summary.bestStreakDays} dias",
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricCard(
                title = "Dias Ativos",
                value = "${summary.activeDaysCount} de ${summary.totalDaysInPeriod}",
                subtext = "${(summary.activeDaysPercentage * 100).toInt()}% de consistência",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnalyticsMetricCard(
                title = "Média Diária",
                value = formatAnalyticsHoursMinutes(summary.dailyAverageSeconds),
                subtext = "Nos dias com estudo",
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricCard(
                title = "Média por Sessão",
                value = formatAnalyticsHoursMinutes(summary.sessionAverageSeconds),
                subtext = "Maior: ${formatAnalyticsHoursMinutes(summary.longestSessionSeconds)}",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))

        // SUBJECT DISTRIBUTION (YPT Style Multi-color segmented bar + List)
        Text(
            "Distribuição por Disciplina",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        StudyCard(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Column(Modifier.padding(cardPadding)) {
                if (subjectDistributions.isEmpty()) {
                    Text(
                        "Nenhuma sessão de estudo registrada no período selecionado.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Segmented color bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        subjectDistributions.forEach { item ->
                            if (item.percentage > 0.01f) {
                                Box(
                                    modifier = Modifier
                                        .weight(item.percentage.coerceAtLeast(0.01f))
                                        .fillMaxHeight()
                                        .background(Color(item.color.toInt()))
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Subject details rows
                    subjectDistributions.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(item.color.toInt()))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${(item.percentage * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Text(
                                formatAnalyticsHoursMinutes(item.focusSeconds),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ACTIVITY HEATMAP (GitHub / YPT Style Grid)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Mapa de Calor de Atividade",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Menos", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                listOf(0, 1, 2, 3, 4).forEach { lvl ->
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(heatmapColorForIntensity(lvl))
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text("Mais", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        StudyCard(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Column(Modifier.padding(cardPadding)) {
                // Interactive Grid
                HeatmapGrid(
                    days = heatmapDays,
                    onSelectDay = { selectedHeatmapDay = it }
                )

                // Detail inspection card when a day is tapped
                AnimatedVisibility(visible = selectedHeatmapDay != null) {
                    selectedHeatmapDay?.let { day ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    val dateStr = day.date.format(StudyDateFormats.FULL_WEEKDAY_DAY_MONTH_PT)
                                    Text(
                                        dateStr.replaceFirstChar { it.uppercase() },
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${day.sessionCount} sessões registradas",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        formatAnalyticsHoursMinutes(day.focusSeconds),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    if (day.breakSeconds > 0) {
                                        Text(
                                            "Pausas: ${formatAnalyticsHoursMinutes(day.breakSeconds)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // TIME OF DAY PRODUCTIVITY (Manhã, Tarde, Noite, Madrugada)
        Text(
            "Produtividade por Turno",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        StudyCard(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Column(Modifier.padding(cardPadding)) {
                Text(
                    "Turno mais produtivo: ${timeOfDay.mostProductiveTurn}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TurnCard("Manhã", "06h-12h", timeOfDay.morningSeconds, timeOfDay.total, Modifier.weight(1f))
                        TurnCard("Tarde", "12h-18h", timeOfDay.afternoonSeconds, timeOfDay.total, Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TurnCard("Noite", "18h-00h", timeOfDay.eveningSeconds, timeOfDay.total, Modifier.weight(1f))
                        TurnCard("Madrugada", "00h-06h", timeOfDay.nightSeconds, timeOfDay.total, Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        val targetTimelineDate = selectedHeatmapDay?.date ?: today
        Text(
            "Grade Horária YPT (Blocos de 10 min · ${targetTimelineDate.format(StudyDateFormats.DATE_DD_MM_PT)})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        StudyCard(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Column(Modifier.padding(cardPadding)) {
                YptTenMinuteGrid(
                    date = targetTimelineDate,
                    sessions = sessions,
                    subjects = subjects,
                    zone = zone
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun YptTenMinuteGrid(
    date: LocalDate,
    sessions: List<StudySession>,
    subjects: List<Subject>,
    zone: ZoneId
) {
    val subjectColors = remember(subjects) { subjects.associate { it.id to Color(it.color.toInt()) } }
    val primaryColor = MaterialTheme.colorScheme.primary
    val emptyCellColor = MaterialTheme.colorScheme.surfaceContainerHighest

    val dayStartMs = remember(date, zone) { date.atStartOfDay(zone).toInstant().toEpochMilli() }
    val dayEndMs = remember(date, zone) { date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }

    val blockColors = remember(sessions, dayStartMs, dayEndMs, subjectColors, primaryColor) {
        val slots = arrayOfNulls<Color>(144)
        for (s in sessions) {
            if (s.isActive || s.durationSeconds <= 0L) continue
            val sStart = s.startedAt
            val sEnd = s.endedAt.takeIf { it > sStart } ?: (sStart + s.durationSeconds * 1000L)
            if (sEnd <= dayStartMs || sStart >= dayEndMs) continue
            val clippedStart = sStart.coerceAtLeast(dayStartMs)
            val clippedEnd = sEnd.coerceAtMost(dayEndMs)
            val firstSlot = ((clippedStart - dayStartMs) / 600_000L).toInt().coerceIn(0, 143)
            val lastSlot = (((clippedEnd - 1L) - dayStartMs) / 600_000L).toInt().coerceIn(firstSlot, 143)
            val color = s.subjectId?.let { subjectColors[it] } ?: primaryColor
            for (idx in firstSlot..lastSlot) {
                slots[idx] = color
            }
        }
        slots
    }

    val activeHours = remember { (6..23) + (0..5) }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        activeHours.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pair.forEach { hour ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            String.format(java.util.Locale.ROOT, "%02dh", hour),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(26.dp)
                        )
                        for (b in 0 until 6) {
                            val slotColor = blockColors[hour * 6 + b] ?: emptyCellColor
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(slotColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnCard(label: String, hours: String, seconds: Long, total: Long, modifier: Modifier) {
    val pct = if (total > 0L) (seconds.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hours, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(formatShortHours(seconds), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

@Composable
private fun EvolutionBarChart(data: List<Pair<LocalDate, Long>>) {
    if (data.isEmpty()) return
    val peakSeconds = remember(data) { (data.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(3600L) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val barTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = size.width / data.size.toFloat()
                val barWidth = (step * 0.65f).coerceIn(4f, 22f)

                data.forEachIndexed { index, (_, seconds) ->
                    val x = index * step + (step - barWidth) / 2f
                    // Background pill track
                    drawRoundRect(
                        color = barTrackColor,
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, size.height),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )

                    // Value bar
                    val barHeight = (size.height * (seconds.toFloat() / peakSeconds.toFloat())).coerceIn(0f, size.height)
                    if (barHeight > 0f) {
                        drawRoundRect(
                            color = primaryColor,
                            topLeft = Offset(x, size.height - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val firstDate = data.firstOrNull()?.first?.format(StudyDateFormats.DATE_D_M) ?: ""
            val lastDate = data.lastOrNull()?.first?.format(StudyDateFormats.DATE_D_M) ?: "Hoje"
            Text(firstDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Pico: ${formatShortHours(peakSeconds)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(lastDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HeatmapGrid(
    days: List<HeatmapDay>,
    onSelectDay: (HeatmapDay) -> Unit
) {
    val weeks = remember(days) { days.chunked(7) }
    val scrollState = rememberScrollState()
    LaunchedEffect(weeks.size, scrollState.maxValue) {
        if (scrollState.maxValue < Int.MAX_VALUE) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        weeks.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(heatmapColorForIntensity(day.intensity))
                            .clickable { onSelectDay(day) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalyticsMetricCard(
    title: String,
    value: String,
    subtext: String,
    modifier: Modifier = Modifier
) {
    StudyCard(
        modifier = modifier
    ) {
        Column(Modifier.padding(StudyTokens.cardPadding())) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtext,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun heatmapColorForIntensity(intensity: Int): Color {
    val base = MaterialTheme.colorScheme.primary
    return when (intensity) {
        0 -> MaterialTheme.colorScheme.surfaceContainerHighest
        1 -> base.copy(alpha = 0.25f)
        2 -> base.copy(alpha = 0.50f)
        3 -> base.copy(alpha = 0.75f)
        else -> base
    }
}

private fun formatAnalyticsHoursMinutes(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "${s}s"
    }
}

private fun formatShortHours(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    return if (hours > 0) "${hours}h" else "${minutes}m"
}
