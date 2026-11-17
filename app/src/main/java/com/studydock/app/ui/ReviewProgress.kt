@file:OptIn(ExperimentalLayoutApi::class)

package com.studydock.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val FMT_DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
private val FMT_FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val FMT_MONTH_YEAR_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM/yy", Locale("pt", "BR"))
private val FMT_WEEKDAY_DAY_MONTH_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd/MM", Locale("pt", "BR"))
private val PROGRESS_TAG_SPLIT_REGEX = Regex("[,\\s]+")

private val chartPaintLocal = object : ThreadLocal<Paint>() {
    override fun initialValue(): Paint = Paint(Paint.ANTI_ALIAS_FLAG)
}

private data class AnkiPalette(
    val newBar: Color,
    val newText: Color,
    val learnBar: Color,
    val learnText: Color,
    val relearnBar: Color,
    val relearnText: Color,
    val youngBar: Color,
    val youngText: Color,
    val matureBar: Color,
    val matureText: Color,
    val suspendedBar: Color,
    val againBar: Color,
    val againText: Color,
    val hardBar: Color,
    val hardText: Color,
    val goodBar: Color,
    val goodText: Color,
    val easyBar: Color,
    val easyText: Color
)

private fun accessibleAnkiText(desired: Color, background: Color, fallback: Color): Color {
    if (contrastRatio(desired, background) >= 4.5) return desired
    val nudged = lerp(desired, fallback, 0.45f)
    return if (contrastRatio(nudged, background) >= 4.5) nudged else fallback
}

@Composable
private fun rememberAnkiPalette(): AnkiPalette {
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalStudySemanticColors.current
    val isDark = LocalStudyThemeConfig.current.baseSurface.isDark || relativeLuminance(scheme.surface) < 0.25
    val kpiBg = scheme.surfaceContainerLow

    val newBar = semantic.info
    val learnBar = semantic.warning
    val relearnBar = scheme.error
    val youngBar = semantic.success
    val matureBar = if (isDark) Color(0xFF2DD4BF) else Color(0xFF065F46)

    val newText = accessibleAnkiText(newBar, kpiBg, scheme.primary)
    val learnText = accessibleAnkiText(learnBar, kpiBg, semantic.onWarningContainer)
    val relearnText = accessibleAnkiText(relearnBar, kpiBg, scheme.onErrorContainer)
    val youngText = accessibleAnkiText(youngBar, kpiBg, semantic.onSuccessContainer)
    val matureText = accessibleAnkiText(matureBar, kpiBg, semantic.onSuccessContainer)

    return AnkiPalette(
        newBar = newBar,
        newText = newText,
        learnBar = learnBar,
        learnText = learnText,
        relearnBar = relearnBar,
        relearnText = relearnText,
        youngBar = youngBar,
        youngText = youngText,
        matureBar = matureBar,
        matureText = matureText,
        suspendedBar = scheme.outline,
        againBar = relearnBar,
        againText = relearnText,
        hardBar = learnBar,
        hardText = learnText,
        goodBar = youngBar,
        goodText = youngText,
        easyBar = newBar,
        easyText = newText
    )
}

internal class ReviewHistory(reviews: List<CardReview>, val today: LocalDate, private val zone: ZoneId) {
    private val scheduledReviews = reviews.filter { !it.priorState.equals("CRAM", ignoreCase = true) }
    private fun day(review: CardReview) = com.studydock.app.data.ankiLogicalDay(review.reviewedAt, 4, zone)
    val firstWeek: LocalDate = today.minusDays(today.dayOfWeek.value - 1L + 11 * 7)
    val counts: Map<LocalDate, Int> = scheduledReviews.groupingBy(::day).eachCount()
    val recent: List<Int> = (29 downTo 0).map { counts[today.minusDays(it.toLong())] ?: 0 }
    val daily: List<CardReview> = scheduledReviews.filter { day(it) == today }
    val reviewedCards: Int = daily.distinctBy { it.cardId }.size
    val again: Int = daily.count { it.grade == 0 }
    val activeDays: Int = recent.count { it > 0 }
    val streak: Int
    val longest: Int

    // Rich metrics for Today & Heatmap (excluding CRAM reviews)
    val todayDurationMs: Long = daily.mapNotNull { it.durationMs }.filter { it >= 0 }.sum()
    val todayAvgMs: Long? = daily.mapNotNull { it.durationMs }.filter { it >= 0 }.let {
        if (it.isEmpty()) null else it.sum() / it.size
    }
    val todayGrades: Map<Int, Int> = daily.groupingBy { it.grade }.eachCount()
    val todayLearning: Int = daily.count { it.priorState == "NEW" || it.priorState == "LEARNING" }
    val todayReview: Int = daily.count { it.priorState == "REVIEW" }
    val todayRelearning: Int = daily.count { it.priorState == "RELEARNING" }
    val totalAllTimeReviews: Int = scheduledReviews.size
    val totalActiveDaysAllTime: Int = counts.count { it.value > 0 }

    init {
        var run = 0
        var record = 0
        var last: LocalDate? = null
        for (d in counts.keys.filter { it <= today }.sorted()) {
            run = if (last == d.minusDays(1)) run + 1 else 1
            record = maxOf(run, record)
            last = d
        }
        longest = record
        streak = if (last == today || last == today.minusDays(1)) run else 0
    }
}

private val widgetPalette = intArrayOf(
    0xff263b50.toInt(),
    0xff215885.toInt(),
    0xff197ec0.toInt(),
    0xff39a8ee.toInt(),
    0xff83d3ff.toInt()
)

internal fun drawReviewChart(
    canvas: android.graphics.Canvas, width: Float, height: Float, history: ReviewHistory, heatmap: Boolean,
    palette: IntArray = widgetPalette, selectedDay: LocalDate? = null, outline: Int = android.graphics.Color.WHITE,
    firstWeekOverride: LocalDate? = null, endDayOverride: LocalDate? = null
) {
    val paint = requireNotNull(chartPaintLocal.get())
    paint.style = Paint.Style.FILL
    paint.strokeCap = Paint.Cap.BUTT
    if (heatmap) {
        val first = firstWeekOverride ?: history.firstWeek
        val limitDay = endDayOverride ?: history.today
        val cellW = width / 12f
        val cellH = height / 7f
        for (i in 0 until 84) {
            val day = first.plusDays(i.toLong())
            if (day > limitDay) break
            val count = history.counts[day] ?: 0
            val level = when (count) {
                0 -> 0
                in 1..4 -> 1
                in 5..14 -> 2
                in 15..29 -> 3
                else -> 4
            }
            paint.color = palette[level]
            val x = (i / 7) * cellW
            val y = (i % 7) * cellH
            canvas.drawRoundRect(x + 1.5f, y + 1.5f, x + cellW - 1.5f, y + cellH - 1.5f, 4f, 4f, paint)
            if (day == selectedDay) {
                paint.color = outline
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.2f
                canvas.drawRoundRect(x + 2.5f, y + 2.5f, x + cellW - 2.5f, y + cellH - 2.5f, 4f, 4f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    } else {
        val peak = history.recent.max().coerceAtLeast(1)
        val margin = 4f
        fun y(count: Int) = height - margin - (height - 2 * margin) * count / peak
        val step = (width - 2 * margin) / 29f
        paint.color = palette[0]
        for (i in 0..2) {
            canvas.drawLine(
                margin,
                margin + (height - 2 * margin) * i / 2,
                width - margin,
                margin + (height - 2 * margin) * i / 2,
                paint
            )
        }
        paint.color = palette[4]
        paint.strokeWidth = 3f
        paint.strokeCap = Paint.Cap.ROUND
        history.recent.zipWithNext().forEachIndexed { i, (a, b) ->
            canvas.drawLine(margin + i * step, y(a), margin + (i + 1) * step, y(b), paint)
        }
    }
}

@Composable
internal fun ReviewDashboard(history: ReviewHistory, pending: Int, librarySize: Int) {
    val totalGoal = history.reviewedCards + pending
    val progress = if (totalGoal == 0) 0f else history.reviewedCards.toFloat() / totalGoal
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalStudySemanticColors.current
    val palette = rememberAnkiPalette()
    val fontScale = LocalDensity.current.fontScale

    Column(verticalArrangement = Arrangement.spacedBy(StudyTokens.itemSpacing())) {
        // 1. TODAY'S ANKI SUMMARY CARD
        AnkiSectionCard(
            title = "Hoje no Anki",
            subtitle = if (history.daily.isEmpty()) "Nenhuma revisão concluída hoje ainda"
            else "${history.daily.size} respostas em ${elapsedReviewTime(history.todayDurationMs)}",
            icon = Icons.Default.Bolt
        ) {
            val paceText = history.todayAvgMs?.let {
                String.format(Locale.getDefault(), "%.1fs/cartão", it / 1000.0)
            } ?: "—"
            val correctRate = if (history.daily.isEmpty()) "—"
            else "${(100.0 * (history.daily.size - history.again) / history.daily.size).roundToInt()}%"
            val todayKpis = listOf(
                Triple("Cartões únicos", "${history.reviewedCards}", scheme.primary),
                Triple("Restantes hoje", "$pending", if (pending > 0) palette.learnText else palette.youngText),
                Triple("Ritmo médio", paceText, palette.newText),
                Triple("Acerto hoje", correctRate, palette.youngText)
            )

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stackRing = maxWidth < 330.dp * fontScale
                if (stackRing) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { 1f },
                                modifier = Modifier.fillMaxSize(),
                                color = scheme.surfaceContainerHighest,
                                strokeWidth = 10.dp
                            )
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxSize(),
                                color = palette.youngBar,
                                strokeWidth = 10.dp,
                                strokeCap = StrokeCap.Round
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (totalGoal == 0) "100%" else "${(progress * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    "da fila",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                        AnkiKpiGrid(todayKpis, preferTwoColumns = true)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { 1f },
                                modifier = Modifier.fillMaxSize(),
                                color = scheme.surfaceContainerHighest,
                                strokeWidth = 10.dp
                            )
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxSize(),
                                color = palette.youngBar,
                                strokeWidth = 10.dp,
                                strokeCap = StrokeCap.Round
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (totalGoal == 0) "100%" else "${(progress * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    "da fila",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }

                        Box(Modifier.weight(1f)) {
                            AnkiKpiGrid(todayKpis, preferTwoColumns = true)
                        }
                    }
                }
            }

            if (history.daily.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
                // Today's buttons breakdown bar
                Text(
                    "Botões pressionados hoje",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                val g0 = history.todayGrades[0] ?: 0
                val g1 = history.todayGrades[1] ?: 0
                val g2 = history.todayGrades[2] ?: 0
                val g3 = history.todayGrades[3] ?: 0
                StackedHorizontalBar(
                    segments = listOf(
                        g0 to palette.againBar,
                        g1 to palette.hardBar,
                        g2 to palette.goodBar,
                        g3 to palette.easyBar
                    ),
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LegendDot("De novo ($g0)", palette.againBar)
                    LegendDot("Difícil ($g1)", palette.hardBar)
                    LegendDot("Bom ($g2)", palette.goodBar)
                    LegendDot("Fácil ($g3)", palette.easyBar)
                }

                // Today's phase chips
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PhaseBadge("Aprendizagem", history.todayLearning, semantic.warningContainer, semantic.onWarningContainer)
                    PhaseBadge("Revisão", history.todayReview, semantic.successContainer, semantic.onSuccessContainer)
                    PhaseBadge("Reaprendizagem", history.todayRelearning, scheme.errorContainer, scheme.onErrorContainer)
                }
            }
        }

        // 2. HEATMAP & CONSISTENCY CARD (Anki Review Heatmap Add-on style)
        AnkiSectionCard(
            title = "Mapa de Calor & Ofensiva",
            subtitle = "Atividade nas últimas 12 semanas · ${history.totalAllTimeReviews} revisões na coleção",
            icon = Icons.Default.LocalFireDepartment
        ) {
            AnkiKpiGrid(
                items = listOf(
                    Triple("Sequência atual", "🔥 ${history.streak}d", palette.learnText),
                    Triple("Recorde", "🏆 ${history.longest}d", scheme.primary),
                    Triple("Dias ativos (30d)", "${(history.activeDays * 100f / 30f).roundToInt()}%", palette.youngText)
                )
            )

            ActivityCalendar(history)

            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))

            val avgActive = if (history.activeDays == 0) 0 else (history.recent.sum() / history.activeDays)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Tendência (últimos 30 dias)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${history.recent.sum()} rev · média $avgActive/dia ativo",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary
                )
            }
            ProgressChart(history, Modifier.fillMaxWidth().height(68.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Há 29 dias",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Hoje (${history.daily.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ActivityCalendar(history: ReviewHistory) {
    var monthOffset by rememberSaveable(history.today.toEpochDay()) { mutableIntStateOf(0) }
    val windowEnd = remember(history.today, monthOffset) { history.today.minusMonths(monthOffset.toLong()) }
    val windowFirstWeek = remember(windowEnd) { windowEnd.minusDays(windowEnd.dayOfWeek.value - 1L + 11 * 7) }
    var selectedEpoch by rememberSaveable(history.today.toEpochDay()) { mutableLongStateOf(history.today.toEpochDay()) }
    val clampedEpoch = selectedEpoch.coerceIn(windowFirstWeek.toEpochDay(), windowEnd.toEpochDay())
    val day = LocalDate.ofEpochDay(clampedEpoch)
    val count = history.counts[day] ?: 0
    val detail = day.format(FMT_FULL_DATE) + " · " +
        when (count) {
            0 -> "Nenhuma revisão"
            1 -> "1 revisão feita"
            else -> "$count revisões feitas"
        }
    val periodTitle = if (monthOffset == 0) {
        "Últimas 12 semanas"
    } else {
        "${windowFirstWeek.format(FMT_MONTH_YEAR_PT)} – ${windowEnd.format(FMT_MONTH_YEAR_PT)}"
    }
    val scheme = MaterialTheme.colorScheme
    val ankiPalette = rememberAnkiPalette()
    val legendColors = remember(scheme, ankiPalette.youngBar) {
        val empty = lerp(scheme.surface, scheme.onSurface, .10f)
        listOf(0f, .35f, .58f, .80f, 1f).map { lerp(empty, ankiPalette.youngBar, it) }
    }

    Surface(
        shape = studyControlShape(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = {
                        monthOffset = (monthOffset + 1).coerceAtMost(24)
                        val newEnd = history.today.minusMonths(monthOffset.toLong())
                        selectedEpoch = newEnd.toEpochDay()
                    },
                    enabled = monthOffset < 24,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mês anterior", Modifier.size(20.dp))
                }
                Text(
                    periodTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (monthOffset > 0) {
                    TextButton(
                        onClick = {
                            monthOffset = 0
                            selectedEpoch = history.today.toEpochDay()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("Atual", style = MaterialTheme.typography.labelMedium) }
                }
                IconButton(
                    onClick = {
                        monthOffset = (monthOffset - 1).coerceAtLeast(0)
                        val newEnd = history.today.minusMonths(monthOffset.toLong())
                        selectedEpoch = newEnd.toEpochDay()
                    },
                    enabled = monthOffset > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Período mais recente", Modifier.size(20.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.fillMaxHeight().heightIn(min = 108.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Seg", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Qua", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Sex", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Dom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ProgressChart(
                    history = history,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .fillMaxHeight()
                        .heightIn(min = 108.dp)
                        .pointerInput(windowFirstWeek, windowEnd) {
                            detectTapGestures { tap ->
                                val week = (12 * tap.x / size.width).toInt().coerceIn(0, 11)
                                val weekday = (7 * tap.y / size.height).toInt().coerceIn(0, 6)
                                val target = windowFirstWeek.plusDays(week * 7L + weekday)
                                if (target <= windowEnd) selectedEpoch = target.toEpochDay()
                            }
                        },
                    selectedDay = day,
                    windowFirstWeek = windowFirstWeek,
                    windowEndDay = windowEnd
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    detail,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = { selectedEpoch = (clampedEpoch - 1).coerceAtLeast(windowFirstWeek.toEpochDay()) },
                    enabled = day > windowFirstWeek,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Dia anterior", Modifier.size(20.dp))
                }
                IconButton(
                    onClick = { selectedEpoch = (clampedEpoch + 1).coerceAtMost(windowEnd.toEpochDay()) },
                    enabled = day < windowEnd,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Próximo dia", Modifier.size(20.dp))
                }
            }
            // Anki FSRS intensity legend
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
            ) {
                Text("Menos", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                val levelLabels = listOf("0", "1–4", "5–14", "15–29", "30+")
                legendColors.forEachIndexed { idx, color ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                        Text(levelLabels[idx], style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
                Text("Mais", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProgressChart(
    history: ReviewHistory,
    modifier: Modifier,
    selectedDay: LocalDate? = null,
    windowFirstWeek: LocalDate = history.firstWeek,
    windowEndDay: LocalDate = history.today
) {
    val scheme = MaterialTheme.colorScheme
    val ankiPalette = rememberAnkiPalette()
    val palette = remember(scheme, ankiPalette.youngBar) {
        val empty = lerp(scheme.surface, scheme.onSurface, .10f)
        listOf(0f, .35f, .58f, .80f, 1f).map { lerp(empty, ankiPalette.youngBar, it).toArgb() }.toIntArray()
    }
    val description = if (selectedDay != null) {
        "Atividade nas últimas 12 semanas do período selecionado, de segunda a domingo."
    } else {
        "Revisões diárias nos últimos 30 dias: " + history.recent.joinToString()
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        drawReviewChart(
            drawContext.canvas.nativeCanvas,
            size.width,
            size.height,
            history,
            selectedDay != null,
            palette,
            selectedDay,
            scheme.onSurface.toArgb(),
            windowFirstWeek,
            windowEndDay
        )
    }
}

// ============================================================================
// COMPREHENSIVE ANKI DECK STATISTICS
// ============================================================================

@Composable
internal fun DeckStatistics(
    cards: List<Flashcard>,
    reviews: List<CardReview>,
    today: LocalDate,
    zone: ZoneId,
    decks: List<CardDeck>,
    onOpenLeechFilter: (() -> Unit)? = null
) {
    var days by rememberSaveable { mutableIntStateOf(30) }
    var forecastDays by rememberSaveable { mutableIntStateOf(14) }
    var historyMode by rememberSaveable { mutableIntStateOf(0) } // 0 = Revisões, 1 = Tempo (min)

    var computedPeriod by remember { mutableStateOf<ReviewPeriod?>(null) }
    LaunchedEffect(reviews, today, zone, days) {
        computedPeriod = withContext(Dispatchers.Default) {
            val scheduledReviews = reviews.filter { !it.priorState.equals("CRAM", ignoreCase = true) }
            ReviewPeriod(scheduledReviews, today, zone, days)
        }
    }
    val period = computedPeriod
    if (period == null) {
        StudyCard(Modifier.fillMaxWidth().heightIn(min = 160.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(StudyTokens.cardPadding()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Calculando estatísticas do Anki…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        return
    }

    val tree = remember(decks) { DeckTree(decks) }

    Column(
        verticalArrangement = Arrangement.spacedBy(StudyTokens.itemSpacing())
    ) {
        // 1. FUTURE DUE FORECAST & 14-DAY WORKLOAD PROJECTION
        FutureDueForecastCard(
            cards = cards,
            today = today,
            zone = zone,
            horizonDays = forecastDays,
            onHorizonChange = { forecastDays = it }
        )

        // 2. FSRS & DECK HEALTH SUMMARY (Estabilidade média, Sanguessugas e Bandeiras 1..7)
        FsrsDeckHealthCard(
            cards = cards,
            decks = decks,
            today = today,
            onOpenLeechFilter = onOpenLeechFilter
        )

        // 3. CARD COUNTS & MATURITY PIE/DONUT CHART (Contagem e Maturidade dos Cartões)
        CardCountsDonutCard(cards = cards)

        // 4. REVIEW HISTORY & TIME CHART (Histórico de Revisões e Tempo)
        ReviewHistoryAndTimeCard(
            period = period,
            today = today,
            zone = zone,
            days = days,
            onDaysChange = { days = it },
            historyMode = historyMode,
            onHistoryModeChange = { historyMode = it },
            tree = tree
        )

        // 5. ANSWER BUTTONS & TRUE RETENTION (Botões de Resposta & Retenção Real)
        AnswerButtonsAndRetentionCard(period = period)

        // 6. REVIEW INTERVALS HISTOGRAM (Distribuição de Intervalos)
        ReviewIntervalsHistogramCard(cards = cards)

        // 7. CARD EASE FACTOR DISTRIBUTION (Facilidade dos Cartões)
        CardEaseHistogramCard(cards = cards)

        // 8. HOURLY BREAKDOWN (Desempenho por Hora do Dia)
        HourlyBreakdownCard(reviews = period.events, zone = zone)
    }
}

// ----------------------------------------------------------------------------
// 1. FUTURE DUE FORECAST CARD (Anki "Future Due")
// ----------------------------------------------------------------------------
private data class ForecastDayBucket(
    val date: LocalDate,
    val youngCount: Int,
    val matureCount: Int,
    val learningCount: Int
) {
    val total: Int get() = youngCount + matureCount + learningCount
}

private data class ForecastSummary(
    val buckets: List<ForecastDayBucket>,
    val totalDueInPeriod: Int,
    val avgPerDay: Float,
    val tomorrowDue: Int,
    val dueTodayOrOverdue: Int,
    val cumulative: List<Int>,
    val peakTotal: Int
)

private fun buildEmptyForecastSummary(today: LocalDate, horizonDays: Int): ForecastSummary {
    val buckets = (0 until horizonDays).map { i -> ForecastDayBucket(today.plusDays(i.toLong()), 0, 0, 0) }
    val cumulative = List(horizonDays) { 0 }
    return ForecastSummary(buckets, 0, 0f, 0, 0, cumulative, 0)
}

@Composable
private fun FutureDueForecastCard(
    cards: List<Flashcard>,
    today: LocalDate,
    zone: ZoneId,
    horizonDays: Int,
    onHorizonChange: (Int) -> Unit
) {
    var summaryState by remember {
        mutableStateOf(if (cards.isEmpty()) buildEmptyForecastSummary(today, horizonDays) else null)
    }
    LaunchedEffect(cards, today, zone, horizonDays) {
        if (cards.isEmpty()) {
            summaryState = buildEmptyForecastSummary(today, horizonDays)
            return@LaunchedEffect
        }
        summaryState = withContext(Dispatchers.Default) {
            val young = IntArray(horizonDays)
            val mature = IntArray(horizonDays)
            val learning = IntArray(horizonDays)
            val todayEpoch = today.toEpochDay()
            for (card in cards) {
                if (card.suspended || card.reviewState == "NEW") continue
                val dueDate = Instant.ofEpochMilli(card.dueAt).atZone(zone).toLocalDate()
                val offset = (dueDate.toEpochDay() - todayEpoch).coerceAtLeast(0).toInt()
                if (offset < horizonDays) {
                    when {
                        card.reviewState != "REVIEW" -> learning[offset]++
                        card.intervalDays >= 21 -> mature[offset]++
                        else -> young[offset]++
                    }
                }
            }
            var totalDue = 0
            var peakTotal = 0
            val cumulative = ArrayList<Int>(horizonDays)
            val buckets = ArrayList<ForecastDayBucket>(horizonDays)
            for (i in 0 until horizonDays) {
                val bucket = ForecastDayBucket(today.plusDays(i.toLong()), young[i], mature[i], learning[i])
                val t = bucket.total
                totalDue += t
                if (t > peakTotal) peakTotal = t
                cumulative.add(totalDue)
                buckets.add(bucket)
            }
            ForecastSummary(
                buckets = buckets,
                totalDueInPeriod = totalDue,
                avgPerDay = totalDue.toFloat() / horizonDays.coerceAtLeast(1),
                tomorrowDue = buckets.getOrNull(1)?.total ?: 0,
                dueTodayOrOverdue = buckets.firstOrNull()?.total ?: 0,
                cumulative = cumulative,
                peakTotal = peakTotal
            )
        }
    }

    val palette = rememberAnkiPalette()
    val summary = summaryState
    if (summary == null) {
        AnkiSectionCard(
            title = "Previsão & Projeção de Carga Futura",
            subtitle = "Carga diária e acumulada para os próximos $horizonDays dias",
            icon = Icons.Default.Upcoming
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }

    val buckets = summary.buckets
    var selectedIdx by rememberSaveable(horizonDays, today.toEpochDay()) { mutableIntStateOf(0) }
    val safeIdx = selectedIdx.coerceIn(buckets.indices)
    val selectedBucket = buckets[safeIdx]
    val totalDueInPeriod = summary.totalDueInPeriod
    val avgPerDay = summary.avgPerDay
    val tomorrowDue = summary.tomorrowDue
    val dueTodayOrOverdue = summary.dueTodayOrOverdue
    val cumulative = summary.cumulative

    AnkiSectionCard(
        title = "Previsão & Projeção de Carga Futura",
        subtitle = "Carga diária e acumulada para os próximos $horizonDays dias" +
            (if (summary.peakTotal > 0) " · pico de ${summary.peakTotal} cartões" else ""),
        icon = Icons.Default.Upcoming
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(7 to "7 dias", 14 to "14 dias", 30 to "30 dias").forEach { (d, label) ->
                FilterChip(
                    selected = horizonDays == d,
                    onClick = { onHorizonChange(d) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }

        AnkiKpiGrid(
            items = listOf(
                Triple("Hoje/Atraso", "$dueTodayOrOverdue", palette.learnText),
                Triple("Amanhã", "$tomorrowDue", palette.newText),
                Triple("Total ($horizonDays d)", "$totalDueInPeriod", palette.youngText),
                Triple("Média/dia", String.format(Locale.getDefault(), "%.1f", avgPerDay), palette.matureText)
            ),
            preferTwoColumns = true
        )

        // Stacked Bar Chart Canvas
        val peak = summary.peakTotal.coerceAtLeast(1)
        val outlineColor = MaterialTheme.colorScheme.onSurface
        val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow

        Surface(
            shape = studyControlShape(),
            color = surfaceLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val dayLabel = when (safeIdx) {
                    0 -> "Hoje (${selectedBucket.date.format(FMT_DAY_MONTH)})"
                    1 -> "Amanhã (${selectedBucket.date.format(FMT_DAY_MONTH)})"
                    else -> selectedBucket.date.format(FMT_WEEKDAY_DAY_MONTH_PT)
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(dayLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${selectedBucket.total} cartões (${selectedBucket.matureCount} maduros · ${selectedBucket.youngCount} jovens · acum. ${cumulative.getOrElse(safeIdx) { 0 }})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                val curveColor = MaterialTheme.colorScheme.primary
                val maxCumulative = cumulative.lastOrNull()?.coerceAtLeast(1) ?: 1
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .pointerInput(buckets) {
                            detectTapGestures { pos ->
                                selectedIdx = (pos.x / size.width * buckets.size).toInt().coerceIn(buckets.indices)
                            }
                        }
                ) {
                    val step = size.width / buckets.size
                    val barW = (step * 0.72f).coerceAtLeast(4f)
                    buckets.forEachIndexed { idx, b ->
                        val x = idx * step + (step - barW) / 2f
                        val totalH = (size.height - 8f) * b.total / peak
                        if (b.total > 0) {
                            val matureH = totalH * (b.matureCount.toFloat() / b.total)
                            val youngH = totalH * (b.youngCount.toFloat() / b.total)
                            val learnH = totalH - matureH - youngH

                            var currentY = size.height - matureH
                            if (matureH > 0f) {
                                drawRoundRect(
                                    color = palette.matureBar,
                                    topLeft = Offset(x, currentY),
                                    size = Size(barW, matureH),
                                    cornerRadius = CornerRadius(3f, 3f)
                                )
                            }
                            currentY -= youngH
                            if (youngH > 0f) {
                                drawRoundRect(
                                    color = palette.youngBar,
                                    topLeft = Offset(x, currentY),
                                    size = Size(barW, youngH),
                                    cornerRadius = CornerRadius(3f, 3f)
                                )
                            }
                            currentY -= learnH
                            if (learnH > 0f) {
                                drawRoundRect(
                                    color = palette.learnBar,
                                    topLeft = Offset(x, currentY),
                                    size = Size(barW, learnH),
                                    cornerRadius = CornerRadius(3f, 3f)
                                )
                            }
                        } else {
                            drawRoundRect(
                                color = outlineColor.copy(alpha = 0.08f),
                                topLeft = Offset(x, size.height - 3f),
                                size = Size(barW, 3f),
                                cornerRadius = CornerRadius(2f, 2f)
                            )
                        }

                        if (idx == safeIdx) {
                            drawRoundRect(
                                color = outlineColor.copy(alpha = 0.65f),
                                topLeft = Offset(x - 1.5f, 2f),
                                size = Size(barW + 3f, size.height - 2f),
                                cornerRadius = CornerRadius(4f, 4f),
                                style = Stroke(width = 2f)
                            )
                        }
                    }

                    // Cumulative load projection curve across the horizon
                    if (cumulative.size > 1 && totalDueInPeriod > 0) {
                        val curve = Path()
                        cumulative.forEachIndexed { idx, acc ->
                            val cx = idx * step + step / 2f
                            val cy = size.height - 6f - (size.height - 14f) * (acc.toFloat() / maxCumulative)
                            if (idx == 0) curve.moveTo(cx, cy) else curve.lineTo(cx, cy)
                        }
                        drawPath(
                            path = curve,
                            color = curveColor.copy(alpha = 0.78f),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                            )
                        )
                    }
                }

                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LegendDot("Maduros (≥21d)", palette.matureBar)
                    LegendDot("Jovens (<21d)", palette.youngBar)
                    LegendDot("Em aprendizagem", palette.learnBar)
                    LegendDot("Carga acumulada", curveColor)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 2. CARD COUNTS & MATURITY DONUT CHART (Anki "Card Counts")
// ----------------------------------------------------------------------------
@Composable
private fun CardCountsDonutCard(cards: List<Flashcard>) {
    val palette = rememberAnkiPalette()
    val scheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val bins = remember(cards) { cards.groupingBy { it.intervalGroup() }.eachCount() }
    val newCount = bins[IntervalGroup.NEW] ?: 0
    val learnCount = bins[IntervalGroup.LEARNING] ?: 0
    val youngCount = bins[IntervalGroup.YOUNG] ?: 0
    val matureCount = bins[IntervalGroup.MATURE] ?: 0
    val suspendedCount = bins[IntervalGroup.SUSPENDED] ?: 0
    val total = cards.size
    val leeches = remember(cards) { cards.count { it.lapses >= 4 } }
    val totalLapses = remember(cards) { cards.sumOf { it.lapses } }

    val slices = listOf(
        Triple("Maduros (≥ 21 dias)", matureCount, palette.matureBar),
        Triple("Jovens (1–20 dias)", youngCount, palette.youngBar),
        Triple("Aprendizagem", learnCount, palette.learnBar),
        Triple("Novos", newCount, palette.newBar),
        Triple("Suspensos", suspendedCount, palette.suspendedBar)
    )

    AnkiSectionCard(
        title = "Maturidade da Coleção (Card Counts)",
        subtitle = "$total cartões no filtro atual · $leeches sanguessugas (≥4 falhas)",
        icon = Icons.Default.PieChart
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stackDonut = maxWidth < 330.dp * fontScale
            val donutContent: @Composable () -> Unit = {
                Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                    val trackColor = scheme.surfaceContainerHighest
                    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                        val stroke = 24f
                        if (total == 0) {
                            drawArc(
                                color = trackColor,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = stroke)
                            )
                        } else {
                            var startAngle = -90f
                            slices.forEach { (_, count, color) ->
                                if (count > 0) {
                                    val sweep = 360f * (count.toFloat() / total)
                                    drawArc(
                                        color = color,
                                        startAngle = startAngle,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        style = Stroke(width = stroke, cap = StrokeCap.Butt)
                                    )
                                    startAngle += sweep
                                }
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "$total",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "cartões",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
            val legendContent: @Composable (Modifier) -> Unit = { mod ->
                Column(mod, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slices.forEach { (label, count, color) ->
                        val pct = if (total == 0) 0 else ((count * 100.0) / total).roundToInt()
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                                Spacer(Modifier.width(8.dp))
                                Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                "$count ($pct%)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            if (stackDonut) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    donutContent()
                    legendContent(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    donutContent()
                    legendContent(Modifier.weight(1f))
                }
            }
        }

        if (totalLapses > 0) {
            Surface(
                shape = studyControlShape(),
                color = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Lapso / Esquecimentos acumulados: $totalLapses",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "$leeches cartões críticos",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onErrorContainer
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 3. REVIEW HISTORY & TIME CARD (Anki "Reviews" & "Time")
// ----------------------------------------------------------------------------
private data class HistoryStackPoint(
    val startDay: LocalDate,
    val endDay: LocalDate,
    val learnCount: Int,
    val youngCount: Int,
    val matureCount: Int,
    val relearnCount: Int,
    val totalDurationMs: Long
) {
    val totalReviews: Int get() = learnCount + youngCount + matureCount + relearnCount
    val totalMinutes: Float get() = totalDurationMs / 60_000f
}

private data class ReviewHistorySummary(
    val stackPoints: List<HistoryStackPoint>,
    val compare: List<Map.Entry<Long?, Int>>,
    val peakReviews: Int,
    val peakMs: Long,
    val chartDescription: String
)

private fun buildEmptyReviewHistorySummary(): ReviewHistorySummary =
    ReviewHistorySummary(emptyList(), emptyList(), 1, 1L, "")

@Composable
private fun ReviewHistoryAndTimeCard(
    period: ReviewPeriod,
    today: LocalDate,
    zone: ZoneId,
    days: Int,
    onDaysChange: (Int) -> Unit,
    historyMode: Int,
    onHistoryModeChange: (Int) -> Unit,
    tree: DeckTree
) {
    var summaryState by remember {
        mutableStateOf(if (period.events.isEmpty()) buildEmptyReviewHistorySummary() else null)
    }
    LaunchedEffect(period, today, zone, days) {
        if (period.events.isEmpty()) {
            summaryState = buildEmptyReviewHistorySummary()
            return@LaunchedEffect
        }
        summaryState = withContext(Dispatchers.Default) {
            val stride = when {
                days <= 30 -> 1
                days <= 90 -> 3
                days <= 365 -> 12
                else -> 30
            }
            val byDay = period.events.groupBy { Instant.ofEpochMilli(it.reviewedAt).atZone(zone).toLocalDate() }
            val deckCounts = HashMap<Long?, Int>()
            for (ev in period.events) {
                if (ev.priorState != null) {
                    deckCounts[ev.deckAtReview] = (deckCounts[ev.deckAtReview] ?: 0) + 1
                }
            }
            val compare = deckCounts.entries.sortedByDescending { it.value }
            var peakReviews = 1
            var peakMs = 1L
            val stackPoints = buildList {
                var d = period.firstDay
                while (!d.isAfter(today)) {
                    val len = minOf(stride.toLong(), today.toEpochDay() - d.toEpochDay() + 1).toInt()
                    var learn = 0
                    var young = 0
                    var mature = 0
                    var relearn = 0
                    var ms = 0L
                    for (offset in 0 until len) {
                        val evs = byDay[d.plusDays(offset.toLong())].orEmpty()
                        for (ev in evs) {
                            ms += (ev.durationMs ?: 0L).coerceAtLeast(0L)
                            when {
                                ev.priorState == "RELEARNING" -> relearn++
                                ev.priorState == "REVIEW" && (ev.priorIntervalDays ?: 0) >= 21 -> mature++
                                ev.priorState == "REVIEW" -> young++
                                else -> learn++
                            }
                        }
                    }
                    val point = HistoryStackPoint(d, d.plusDays(len - 1L), learn, young, mature, relearn, ms)
                    if (point.totalReviews > peakReviews) peakReviews = point.totalReviews
                    if (point.totalDurationMs > peakMs) peakMs = point.totalDurationMs
                    add(point)
                    d = d.plusDays(len.toLong())
                }
            }
            ReviewHistorySummary(
                stackPoints = stackPoints,
                compare = compare,
                peakReviews = peakReviews,
                peakMs = peakMs,
                chartDescription = period.dailyBuckets.joinToString { "${it.first}: ${it.second}" }
            )
        }
    }

    val palette = rememberAnkiPalette()
    val fontScale = LocalDensity.current.fontScale
    val totalReviews = period.events.size
    val summary = summaryState
    if (summary == null) {
        AnkiSectionCard(
            title = "Histórico de Revisões & Tempo",
            subtitle = "$totalReviews revisões · ${period.counts.size} dias estudados no período",
            icon = Icons.Default.BarChart
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }

    val stackPoints = summary.stackPoints
    val compare = summary.compare
    var selectedIdx by rememberSaveable(days, today.toEpochDay()) { mutableIntStateOf(Int.MAX_VALUE) }
    val safeIdx = selectedIdx.coerceIn(stackPoints.indices)
    val point = stackPoints.getOrNull(safeIdx)

    AnkiSectionCard(
        title = "Histórico de Revisões & Tempo",
        subtitle = "$totalReviews revisões · ${period.counts.size} dias estudados no período",
        icon = Icons.Default.BarChart
    ) {
        // Period chips
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(7 to "7 dias", 30 to "30 dias", 90 to "90 dias", 365 to "1 ano", 1825 to "5 anos").forEach { (n, label) ->
                FilterChip(
                    selected = days == n,
                    onClick = { onDaysChange(n) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }

        // Mode toggle: Reviews vs Time
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compactModeLabels = maxWidth < 380.dp * fontScale
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = historyMode == 0,
                    onClick = { onHistoryModeChange(0) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) {
                    Text(
                        if (compactModeLabels) "Revisões" else "Quantidade de Revisões",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SegmentedButton(
                    selected = historyMode == 1,
                    onClick = { onHistoryModeChange(1) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) {
                    Text(
                        if (compactModeLabels) "Tempo" else "Tempo de Estudo",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (totalReviews > 0 && point != null) {
            val rangeLabel = if (point.startDay == point.endDay) {
                point.startDay.format(FMT_DAY_MONTH)
            } else {
                "${point.startDay.format(FMT_DAY_MONTH)}–${point.endDay.format(FMT_DAY_MONTH)}"
            }

            Surface(
                shape = studyControlShape(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(rangeLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (historyMode == 0) {
                                "${point.totalReviews} rev (${point.matureCount} mad · ${point.youngCount} jov · ${point.learnCount} apr)"
                            } else {
                                elapsedReviewTime(point.totalDurationMs)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    val outlineColor = MaterialTheme.colorScheme.onSurface
                    val primaryColor = MaterialTheme.colorScheme.primary
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .semantics {
                                contentDescription = summary.chartDescription
                            }
                            .pointerInput(stackPoints) {
                                detectTapGestures { pos ->
                                    selectedIdx = (pos.x / size.width * stackPoints.size).toInt().coerceIn(stackPoints.indices)
                                }
                            }
                    ) {
                        val step = size.width / stackPoints.size
                        val barW = (step * 0.76f).coerceAtLeast(3f)

                        if (historyMode == 0) {
                            val peak = summary.peakReviews
                            stackPoints.forEachIndexed { idx, sp ->
                                val x = idx * step + (step - barW) / 2f
                                val totalH = (size.height - 6f) * sp.totalReviews / peak
                                if (sp.totalReviews > 0) {
                                    val matH = totalH * (sp.matureCount.toFloat() / sp.totalReviews)
                                    val yngH = totalH * (sp.youngCount.toFloat() / sp.totalReviews)
                                    val lrnH = totalH * (sp.learnCount.toFloat() / sp.totalReviews)
                                    val relH = totalH - matH - yngH - lrnH

                                    var y = size.height - matH
                                    if (matH > 0f) drawRect(palette.matureBar, Offset(x, y), Size(barW, matH))
                                    y -= yngH
                                    if (yngH > 0f) drawRect(palette.youngBar, Offset(x, y), Size(barW, yngH))
                                    y -= lrnH
                                    if (lrnH > 0f) drawRect(palette.learnBar, Offset(x, y), Size(barW, lrnH))
                                    y -= relH
                                    if (relH > 0f) drawRect(palette.relearnBar, Offset(x, y), Size(barW, relH))
                                }
                                if (idx == safeIdx) {
                                    drawRect(
                                        color = outlineColor.copy(alpha = 0.6f),
                                        topLeft = Offset(x - 1f, 0f),
                                        size = Size(barW + 2f, size.height),
                                        style = Stroke(width = 2f)
                                    )
                                }
                            }
                        } else {
                            val peakMs = summary.peakMs
                            stackPoints.forEachIndexed { idx, sp ->
                                val x = idx * step + (step - barW) / 2f
                                val h = (size.height - 6f) * (sp.totalDurationMs.toFloat() / peakMs)
                                drawRoundRect(
                                    color = if (idx == safeIdx) primaryColor else palette.newBar.copy(alpha = 0.65f),
                                    topLeft = Offset(x, size.height - h),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(3f, 3f)
                                )
                            }
                        }
                    }

                    if (historyMode == 0) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LegendDot("Maduro", palette.matureBar)
                            LegendDot("Jovem", palette.youngBar)
                            LegendDot("Aprendendo", palette.learnBar)
                            LegendDot("Reaprendendo", palette.relearnBar)
                        }
                    }
                }
            }

            // Summary KPIs for the period
            val measured = if (period.timedCount == 0) "—" else elapsedReviewTime(period.measuredMs)
            val avgSpeed = period.averageMs?.let { elapsedReviewTime(it) } ?: "—"
            val avgPerActiveDay = if (period.counts.isEmpty()) 0 else totalReviews / period.counts.size
            AnkiKpiGrid(
                items = listOf(
                    Triple("Tempo total", measured, palette.newText),
                    Triple("Média/resposta", avgSpeed, palette.learnText),
                    Triple("Média/dia ativo", "$avgPerActiveDay rev", palette.youngText)
                )
            )

            if (compare.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text("Revisões por baralho", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                var expanded by rememberSaveable { mutableStateOf(false) }
                (if (expanded) compare else compare.take(5)).forEach { (id, count) ->
                    val label = if (id == null) "Sem baralho" else tree.label(id).ifBlank { "Baralho removido · $id" }
                    ColoredMetricRow(label, count, totalReviews, MaterialTheme.colorScheme.primary)
                }
                if (compare.size > 5) {
                    TextButton({ expanded = !expanded }) {
                        Text(if (expanded) "Mostrar menos" else "Ver todos (${compare.size})")
                    }
                }
            }
        } else {
            Text(
                "Nenhuma revisão registrada neste período.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ----------------------------------------------------------------------------
// 4. ANSWER BUTTONS & TRUE RETENTION CARD (Anki "Answer Buttons" & "True Retention")
// ----------------------------------------------------------------------------
private class StageGradeSummary(
    val total: Int,
    val passCount: Int,
    val g0: Int,
    val g1: Int,
    val g2: Int,
    val g3: Int
)

private data class RetentionBreakdownSummary(
    val youngRetained: Int,
    val youngTrials: Int,
    val matureRetained: Int,
    val matureTrials: Int,
    val learning: StageGradeSummary,
    val young: StageGradeSummary,
    val mature: StageGradeSummary
)

private fun buildEmptyRetentionSummary(): RetentionBreakdownSummary {
    val zero = StageGradeSummary(0, 0, 0, 0, 0, 0)
    return RetentionBreakdownSummary(0, 0, 0, 0, zero, zero, zero)
}

@Composable
private fun AnswerButtonsAndRetentionCard(period: ReviewPeriod) {
    val palette = rememberAnkiPalette()
    val events = period.events
    val total = events.size

    var breakdownState by remember {
        mutableStateOf(if (events.isEmpty()) buildEmptyRetentionSummary() else null)
    }
    LaunchedEffect(period) {
        if (events.isEmpty()) {
            breakdownState = buildEmptyRetentionSummary()
            return@LaunchedEffect
        }
        breakdownState = withContext(Dispatchers.Default) {
            val firstReviewPerCardDay = HashMap<Pair<Long, LocalDate>, CardReview>()
            val lrn = IntArray(4)
            val yng = IntArray(4)
            val mat = IntArray(4)
            var lrnTotal = 0
            var yngTotal = 0
            var matTotal = 0
            for (ev in events) {
                val gradeIdx = ev.grade.coerceIn(0, 3)
                when {
                    ev.priorState != "REVIEW" -> {
                        lrnTotal++
                        lrn[gradeIdx]++
                    }
                    (ev.priorIntervalDays ?: 0) < 21 -> {
                        yngTotal++
                        yng[gradeIdx]++
                    }
                    else -> {
                        matTotal++
                        mat[gradeIdx]++
                    }
                }
                if (ev.priorState == "REVIEW") {
                    val day = Instant.ofEpochMilli(ev.reviewedAt).atZone(period.zone).toLocalDate()
                    val key = ev.cardId to day
                    val existing = firstReviewPerCardDay[key]
                    if (existing == null || ev.reviewedAt < existing.reviewedAt || (ev.reviewedAt == existing.reviewedAt && ev.id < existing.id)) {
                        firstReviewPerCardDay[key] = ev
                    }
                }
            }
            var youngRetained = 0
            var youngTrials = 0
            var matureRetained = 0
            var matureTrials = 0
            for (ev in firstReviewPerCardDay.values) {
                if ((ev.priorIntervalDays ?: 0) < 21) {
                    youngTrials++
                    if (ev.grade > 0) youngRetained++
                } else {
                    matureTrials++
                    if (ev.grade > 0) matureRetained++
                }
            }
            RetentionBreakdownSummary(
                youngRetained = youngRetained,
                youngTrials = youngTrials,
                matureRetained = matureRetained,
                matureTrials = matureTrials,
                learning = StageGradeSummary(lrnTotal, lrn[1] + lrn[2] + lrn[3], lrn[0], lrn[1], lrn[2], lrn[3]),
                young = StageGradeSummary(yngTotal, yng[1] + yng[2] + yng[3], yng[0], yng[1], yng[2], yng[3]),
                mature = StageGradeSummary(matTotal, mat[1] + mat[2] + mat[3], mat[0], mat[1], mat[2], mat[3])
            )
        }
    }

    val breakdown = breakdownState
    if (breakdown == null) {
        AnkiSectionCard(
            title = "Retenção Real & Botões de Resposta",
            subtitle = "Taxa de lembrança na 1ª tentativa do dia e proporção de respostas",
            icon = Icons.Default.Psychology
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }

    val youngRetained = breakdown.youngRetained
    val youngTrials = breakdown.youngTrials
    val matureRetained = breakdown.matureRetained
    val matureTrials = breakdown.matureTrials
    val totalTrials = period.retentionTrials
    val overallRetention = if (totalTrials == 0) null else (100.0 * period.recalled / totalTrials).roundToInt()
    val youngRetention = if (youngTrials == 0) null else (100.0 * youngRetained / youngTrials).roundToInt()
    val matureRetention = if (matureTrials == 0) null else (100.0 * matureRetained / matureTrials).roundToInt()

    AnkiSectionCard(
        title = "Retenção Real & Botões de Resposta",
        subtitle = "Taxa de lembrança na 1ª tentativa do dia e proporção de respostas",
        icon = Icons.Default.Psychology
    ) {
        AnkiKpiGrid(
            items = listOf(
                Triple("Retenção Geral", overallRetention?.let { "$it%" } ?: "—", palette.youngText),
                Triple("Retenção Jovem", youngRetention?.let { "$it% ($youngRetained/$youngTrials)" } ?: "—", palette.newText),
                Triple("Retenção Madura", matureRetention?.let { "$it% ($matureRetained/$matureTrials)" } ?: "—", palette.matureText)
            )
        )

        if (total > 0) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            val buttons = listOf(
                Triple("1 · De novo (Errei)", period.grades[0] ?: 0, palette.againBar),
                Triple("2 · Difícil", period.grades[1] ?: 0, palette.hardBar),
                Triple("3 · Bom", period.grades[2] ?: 0, palette.goodBar),
                Triple("4 · Fácil", period.grades[3] ?: 0, palette.easyBar)
            )
            buttons.forEach { (label, count, color) ->
                ColoredMetricRow(label, count, total, color)
            }

            if (breakdown.learning.total > 0 || breakdown.young.total > 0 || breakdown.mature.total > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Proporção de botões por fase do cartão",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                listOf(
                    "Aprendendo (${breakdown.learning.total})" to breakdown.learning,
                    "Jovens <21d (${breakdown.young.total})" to breakdown.young,
                    "Maduros ≥21d (${breakdown.mature.total})" to breakdown.mature
                ).forEach { (stageName, stage) ->
                    if (stage.total > 0) {
                        val passPct = (100.0 * stage.passCount / stage.total).roundToInt()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    stageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "$passPct% ok",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            StackedHorizontalBar(
                                segments = listOf(
                                    stage.g0 to palette.againBar,
                                    stage.g1 to palette.hardBar,
                                    stage.g2 to palette.goodBar,
                                    stage.g3 to palette.easyBar
                                ),
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
                            )
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 5. REVIEW INTERVALS HISTOGRAM CARD (Anki "Review Intervals")
// ----------------------------------------------------------------------------
@Composable
private fun ReviewIntervalsHistogramCard(cards: List<Flashcard>) {
    val activeReviewCards = remember(cards) {
        cards.filter { !it.suspended && it.reviewState == "REVIEW" && it.intervalDays > 0 }
    }

    val buckets = remember(activeReviewCards) {
        val defs = listOf(
            "1–3d" to (1..3),
            "4–7d" to (4..7),
            "8–14d" to (8..14),
            "15–20d" to (15..20),
            "21–30d" to (21..30),
            "1–3m" to (31..90),
            "3–6m" to (91..180),
            "6m+" to (181..Int.MAX_VALUE)
        )
        defs.map { (label, range) ->
            label to activeReviewCards.count { it.intervalDays in range }
        }
    }

    val avgInterval = remember(activeReviewCards) {
        if (activeReviewCards.isEmpty()) 0 else activeReviewCards.sumOf { it.intervalDays } / activeReviewCards.size
    }
    val medianInterval = remember(activeReviewCards) {
        if (activeReviewCards.isEmpty()) 0 else {
            val sorted = activeReviewCards.map { it.intervalDays }.sorted()
            sorted[sorted.size / 2]
        }
    }
    val maxInterval = remember(activeReviewCards) {
        activeReviewCards.maxOfOrNull { it.intervalDays } ?: 0
    }

    val palette = rememberAnkiPalette()

    AnkiSectionCard(
        title = "Intervalos de Revisão (Review Intervals)",
        subtitle = "Estabilidade atual dos ${activeReviewCards.size} cartões em revisão",
        icon = Icons.Default.Timeline
    ) {
        AnkiKpiGrid(
            items = listOf(
                Triple("Média", formatDaysCompact(avgInterval), palette.newText),
                Triple("Mediana (50%)", formatDaysCompact(medianInterval), palette.youngText),
                Triple("Maior intervalo", formatDaysCompact(maxInterval), palette.matureText)
            )
        )

        val peak = buckets.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            buckets.forEachIndexed { idx, (label, count) ->
                val isMatureBucket = idx >= 4
                val barColor = if (isMatureBucket) palette.matureBar else palette.youngBar
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (count > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        Modifier
                            .height(64.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        val h = if (count == 0) 3.dp else (64f * count / peak).coerceAtLeast(6f).dp
                        Box(
                            Modifier
                                .fillMaxWidth(0.75f)
                                .height(h)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (count == 0) MaterialTheme.colorScheme.surfaceVariant else barColor)
                        )
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 6. CARD EASE FACTOR DISTRIBUTION (Anki "Card Ease")
// ----------------------------------------------------------------------------
@Composable
private fun CardEaseHistogramCard(cards: List<Flashcard>) {
    val palette = rememberAnkiPalette()
    val studiedCards = remember(cards) {
        cards.filter { !it.suspended && it.reviewState != "NEW" }
    }

    val easeBuckets = remember(studiedCards) {
        val ranges = listOf(
            "130–160%" to (130..160),
            "161–190%" to (161..190),
            "191–220%" to (191..220),
            "221–249%" to (221..249),
            "250% (Padrão)" to (250..255),
            "256–280%" to (256..280),
            "281%+" to (281..1000)
        )
        ranges.map { (label, range) ->
            label to studiedCards.count { (it.easeFactor * 100).roundToInt() in range }
        }
    }

    val avgEase = remember(studiedCards) {
        if (studiedCards.isEmpty()) 250 else (studiedCards.map { it.easeFactor * 100 }.average()).roundToInt()
    }
    val minEase = remember(studiedCards) {
        studiedCards.minOfOrNull { (it.easeFactor * 100).roundToInt() } ?: 250
    }
    val maxEase = remember(studiedCards) {
        studiedCards.maxOfOrNull { (it.easeFactor * 100).roundToInt() } ?: 250
    }

    AnkiSectionCard(
        title = "Facilidade dos Cartões (Card Ease)",
        subtitle = "Multiplicador de intervalo SM-2 dos ${studiedCards.size} cartões já estudados",
        icon = Icons.Default.Speed
    ) {
        AnkiKpiGrid(
            items = listOf(
                Triple("Mínima", "$minEase%", palette.againText),
                Triple("Facilidade Média", "$avgEase%", palette.newText),
                Triple("Máxima", "$maxEase%", palette.youngText)
            )
        )

        if (studiedCards.isNotEmpty()) {
            easeBuckets.forEachIndexed { idx, (label, count) ->
                val color = when {
                    idx <= 1 -> palette.againBar
                    idx <= 3 -> palette.hardBar
                    idx == 4 -> palette.newBar
                    else -> palette.youngBar
                }
                ColoredMetricRow(label, count, studiedCards.size, color)
            }
        } else {
            Text(
                "Estude novos cartões para visualizar a distribuição de facilidade.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ----------------------------------------------------------------------------
// 7. HOURLY BREAKDOWN CARD (Anki "Hourly Breakdown")
// ----------------------------------------------------------------------------
private data class HourlyBreakdownSummary(
    val shiftData: List<Triple<String, Int, Int>>,
    val bestHour: String?
)

private val HOURLY_SHIFTS = listOf(
    "Madrugada (00h–06h)" to (0..5),
    "Manhã (06h–12h)" to (6..11),
    "Tarde (12h–18h)" to (12..17),
    "Noite (18h–24h)" to (18..23)
)

@Composable
private fun HourlyBreakdownCard(reviews: List<CardReview>, zone: ZoneId) {
    val palette = rememberAnkiPalette()
    var summaryState by remember {
        mutableStateOf(
            if (reviews.isEmpty()) HourlyBreakdownSummary(HOURLY_SHIFTS.map { (l, _) -> Triple(l, 0, 0) }, null)
            else null
        )
    }
    LaunchedEffect(reviews, zone) {
        if (reviews.isEmpty()) {
            summaryState = HourlyBreakdownSummary(HOURLY_SHIFTS.map { (l, _) -> Triple(l, 0, 0) }, null)
            return@LaunchedEffect
        }
        summaryState = withContext(Dispatchers.Default) {
            val counts = IntArray(24)
            val passCounts = IntArray(24)
            for (rev in reviews) {
                val h = Instant.ofEpochMilli(rev.reviewedAt).atZone(zone).hour.coerceIn(0, 23)
                counts[h]++
                if (rev.grade > 0) passCounts[h]++
            }
            val shiftData = HOURLY_SHIFTS.map { (label, range) ->
                var c = 0
                var p = 0
                for (h in range) {
                    c += counts[h]
                    p += passCounts[h]
                }
                val pct = if (c == 0) 0 else (100.0 * p / c).roundToInt()
                Triple(label, c, pct)
            }
            var bestHourIdx = -1
            var bestRate = -1.0
            for (h in 0 until 24) {
                if (counts[h] >= 3) {
                    val rate = passCounts[h].toDouble() / counts[h]
                    if (rate > bestRate) {
                        bestRate = rate
                        bestHourIdx = h
                    }
                }
            }
            val bestHour = if (bestHourIdx >= 0) {
                val pct = (100.0 * passCounts[bestHourIdx] / counts[bestHourIdx]).roundToInt()
                String.format(Locale.getDefault(), "%02dh–%02dh (%d%% acerto)", bestHourIdx, (bestHourIdx + 1) % 24, pct)
            } else {
                null
            }
            HourlyBreakdownSummary(shiftData, bestHour)
        }
    }

    val summary = summaryState
    if (summary == null) {
        AnkiSectionCard(
            title = "Desempenho por Hora do Dia (Hourly Breakdown)",
            subtitle = "Volume de revisões e taxa de acerto por turno",
            icon = Icons.Default.Schedule
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }
    val shiftData = summary.shiftData
    val bestHour = summary.bestHour

    AnkiSectionCard(
        title = "Desempenho por Hora do Dia (Hourly Breakdown)",
        subtitle = bestHour?.let { "Pico de retenção às $it" } ?: "Volume de revisões e taxa de acerto por turno",
        icon = Icons.Default.Schedule
    ) {
        val total = reviews.size
        if (total == 0) {
            Text(
                "Sem revisões registradas neste período.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val maxShiftCount = shiftData.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            shiftData.forEach { (label, count, passPct) ->
                val frac = count.toFloat() / maxShiftCount
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (count == 0) "0 rev" else "$count rev · $passPct% acerto",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (passPct >= 85) palette.youngText else if (count > 0) palette.learnText else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)),
                        color = if (passPct >= 85) palette.youngBar else palette.newBar,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            }
        }
    }
}

// ============================================================================
// 8. FSRS & DECK HEALTH SUMMARY (Estabilidade, Sanguessugas e Bandeiras 1..7)
// ============================================================================
private data class FsrsHealthSummary(
    val fsrsCount: Int,
    val avgStability: Double,
    val avgDifficulty: Double,
    val sm2AvgInterval: Int,
    val fsrsDeckCount: Int,
    val leechCards: List<Flashcard>,
    val buriedTodayCount: Int,
    val flagCounts: List<Pair<Int, Int>>,
    val totalFlagged: Int
)

private fun buildEmptyFsrsHealthSummary(decks: List<CardDeck>): FsrsHealthSummary {
    val fsrsDecks = decks.count { it.schedulerMode.equals("FSRS", ignoreCase = true) }
    val emptyFlags = (1..7).map { it to 0 }
    return FsrsHealthSummary(0, 0.0, 0.0, 0, fsrsDecks, emptyList(), 0, emptyFlags, 0)
}

@Composable
private fun FsrsDeckHealthCard(
    cards: List<Flashcard>,
    decks: List<CardDeck>,
    today: LocalDate,
    onOpenLeechFilter: (() -> Unit)? = null
) {
    val palette = rememberAnkiPalette()
    val scheme = MaterialTheme.colorScheme
    var showLeechList by rememberSaveable { mutableStateOf(false) }

    var healthState by remember {
        mutableStateOf(if (cards.isEmpty()) buildEmptyFsrsHealthSummary(decks) else null)
    }
    LaunchedEffect(cards, decks, today) {
        if (cards.isEmpty()) {
            healthState = buildEmptyFsrsHealthSummary(decks)
            return@LaunchedEffect
        }
        healthState = withContext(Dispatchers.Default) {
            val deckThresholdById = HashMap<Long, Int>(decks.size)
            var fsrsDeckCount = 0
            for (deck in decks) {
                deckThresholdById[deck.id] = deck.leechThreshold.coerceAtLeast(1)
                if (deck.schedulerMode.equals("FSRS", ignoreCase = true)) fsrsDeckCount++
            }
            val todayEpoch = today.toEpochDay()
            var fsrsCount = 0
            var stabilitySum = 0.0
            var difficultySum = 0.0
            var sm2Sum = 0L
            var sm2Count = 0
            var buriedTodayCount = 0
            val flagTotals = IntArray(8)
            var totalFlagged = 0
            val leeches = mutableListOf<Flashcard>()

            for (card in cards) {
                if (card.fsrsStability > 0.0) {
                    fsrsCount++
                    stabilitySum += card.fsrsStability
                    difficultySum += card.fsrsDifficulty
                } else if (!card.suspended && card.intervalDays > 0) {
                    sm2Count++
                    sm2Sum += card.intervalDays.toLong()
                }
                if (card.buriedUntilDay >= todayEpoch) buriedTodayCount++
                if (card.flag in 1..7) {
                    flagTotals[card.flag]++
                    totalFlagged++
                }
                val threshold = card.deckId?.let { deckThresholdById[it] } ?: 8
                val hasTag = card.tags.isNotBlank() && card.tags.split(PROGRESS_TAG_SPLIT_REGEX).any { token ->
                    token.equals("sanguessuga", ignoreCase = true) || token.equals("leech", ignoreCase = true)
                }
                if (hasTag || card.lapses >= threshold) {
                    leeches.add(card)
                }
            }
            leeches.sortByDescending { it.lapses }
            FsrsHealthSummary(
                fsrsCount = fsrsCount,
                avgStability = if (fsrsCount == 0) 0.0 else stabilitySum / fsrsCount,
                avgDifficulty = if (fsrsCount == 0) 0.0 else difficultySum / fsrsCount,
                sm2AvgInterval = if (sm2Count == 0) 0 else (sm2Sum / sm2Count).toInt(),
                fsrsDeckCount = fsrsDeckCount,
                leechCards = leeches,
                buriedTodayCount = buriedTodayCount,
                flagCounts = (1..7).map { f -> f to flagTotals[f] },
                totalFlagged = totalFlagged
            )
        }
    }

    val health = healthState
    if (health == null) {
        AnkiSectionCard(
            title = "Saúde do Baralho & FSRS",
            subtitle = "Estabilidade FSRS, sanguessugas e bandeiras (1–7)",
            icon = Icons.Default.AutoAwesome
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }

    val fsrsDeckCount = health.fsrsDeckCount
    val leechCards = health.leechCards
    val buriedTodayCount = health.buriedTodayCount
    val flagCounts = health.flagCounts
    val totalFlagged = health.totalFlagged

    AnkiSectionCard(
        title = "Saúde do Baralho & FSRS",
        subtitle = if (fsrsDeckCount > 0) "$fsrsDeckCount baralho(s) em FSRS · ${health.fsrsCount} cartões calibrados"
        else "Estabilidade FSRS, sanguessugas e bandeiras (1–7)",
        icon = Icons.Default.AutoAwesome
    ) {
        val stabilityLabel = if (health.fsrsCount == 0) {
            if (health.sm2AvgInterval > 0) "${formatDaysCompact(health.sm2AvgInterval)} (SM-2)" else "—"
        } else {
            formatDaysCompact(health.avgStability.roundToInt().coerceAtLeast(1))
        }
        val difficultyLabel = if (health.fsrsCount == 0) "—" else String.format(Locale.getDefault(), "%.1f/10", health.avgDifficulty)

        AnkiKpiGrid(
            items = listOf(
                Triple("Estabilidade média", stabilityLabel, palette.matureText),
                Triple("Dificuldade média", difficultyLabel, palette.newText),
                Triple("Sanguessugas", "${leechCards.size}", if (leechCards.isEmpty()) palette.youngText else palette.againText),
                Triple("Enterrados hoje", "$buriedTodayCount", palette.learnText)
            ),
            preferTwoColumns = true
        )

        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = showLeechList,
                onClick = { showLeechList = !showLeechList },
                enabled = leechCards.isNotEmpty(),
                leadingIcon = {
                    Icon(
                        if (showLeechList) Icons.Default.ExpandLess else Icons.Default.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        if (leechCards.isEmpty()) "Sem sanguessugas"
                        else if (showLeechList) "Ocultar sanguessugas (${leechCards.size})"
                        else "Filtro rápido: Sanguessugas (${leechCards.size})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            if (onOpenLeechFilter != null && leechCards.isNotEmpty()) {
                OutlinedButton(
                    onClick = onOpenLeechFilter,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Abrir no navegador", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (showLeechList && leechCards.isNotEmpty()) {
            Surface(
                shape = studyControlShape(),
                color = scheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    leechCards.take(8).forEach { card ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (card.flag in 1..7) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(ankiFlagColor(card.flag)))
                            }
                            Text(
                                card.front.replace('\n', ' ').trim().ifBlank { "(Sem texto)" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    append("${card.lapses} erros")
                                    if (card.suspended) append(" · susp.")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.againText
                            )
                        }
                    }
                    if (leechCards.size > 8) {
                        Text(
                            "+${leechCards.size - 8} cartão(ões) sanguessuga adicional(is)",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bandeiras (1–7)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (totalFlagged == 0) "Nenhuma marcada" else "$totalFlagged marcada(s)",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
        }

        if (totalFlagged > 0) {
            StackedHorizontalBar(
                segments = flagCounts.filter { it.second > 0 }.map { (flag, count) ->
                    count to ankiFlagColor(flag)
                },
                modifier = Modifier.fillMaxWidth().height(10.dp)
            )
        }

        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            flagCounts.forEach { (flag, count) ->
                val color = ankiFlagColor(flag)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (count > 0) color.copy(alpha = 0.14f) else scheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (count > 0) color.copy(alpha = 0.55f) else scheme.outlineVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Default.Flag,
                            contentDescription = ankiFlagName(flag),
                            tint = if (count > 0) color else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            "${ankiFlagName(flag)}: $count",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (count > 0) scheme.onSurface else scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// REUSABLE ANKI UI COMPONENTS
// ============================================================================

@Composable
private fun AnkiSectionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    StudyCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(StudyTokens.cardPadding()),
            verticalArrangement = Arrangement.spacedBy(StudyTokens.itemSpacing())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = studyControlShape(),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun AnkiKpiGrid(
    items: List<Triple<String, String, Color>>,
    preferTwoColumns: Boolean = false
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth < 220.dp * fontScale -> 1
            preferTwoColumns || items.size == 4 || maxWidth < 380.dp * fontScale -> 2
            else -> items.size.coerceAtMost(3)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { rowItems ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { (label, value, accent) ->
                        AnkiMiniKpi(
                            label = label,
                            value = value,
                            accent = accent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnkiMiniKpi(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = studyControlShape(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun PhaseBadge(
    label: String,
    count: Int,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = studyControlShape(),
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(
            Modifier.padding(vertical = 6.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$count", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = contentColor)
            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StackedHorizontalBar(segments: List<Pair<Int, Color>>, modifier: Modifier = Modifier) {
    val total = segments.sumOf { it.first }.coerceAtLeast(1)
    Canvas(modifier) {
        var currentX = 0f
        segments.forEach { (count, color) ->
            if (count > 0) {
                val w = size.width * (count.toFloat() / total)
                drawRect(color = color, topLeft = Offset(currentX, 0f), size = Size(w, size.height))
                currentX += w
            }
        }
    }
}

@Composable
private fun ColoredMetricRow(label: String, count: Int, total: Int, color: Color) {
    val fraction = if (total == 0) 0f else count.toFloat() / total
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$count · ${(fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

private fun formatDaysCompact(days: Int): String = when {
    days <= 0 -> "0d"
    days < 30 -> "${days}d"
    days < 365 -> String.format(Locale.getDefault(), "%.1f m", days / 30.0)
    else -> String.format(Locale.getDefault(), "%.1f a", days / 365.0)
}

private fun elapsedReviewTime(ms: Long): String = when {
    ms < 60000 -> java.lang.String.format(java.util.Locale.getDefault(), "%.1f s", ms / 1000.0)
    ms < 3600000 -> "${ms / 60000} min ${ms / 1000 % 60} s"
    else -> "${ms / 3600000} h ${ms / 60000 % 60} min"
}
