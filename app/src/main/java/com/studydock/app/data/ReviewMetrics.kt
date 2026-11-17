package com.studydock.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class ReviewPeriod(
    reviews: List<CardReview>,
    val today: LocalDate,
    val zone: ZoneId,
    val days: Int
) {
    init { require(days > 0) }
    val firstDay: LocalDate = today.minusDays(days - 1L)
    private val start = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
    private val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    // Exclude CRAM reviews so filtered/cram sessions never distort scheduled progress or retention.
    val events = reviews.filter { it.reviewedAt in start until end && it.grade in 0..3 && it.priorState != "CRAM" }
    val cramEventsCount = reviews.count { it.reviewedAt in start until end && it.grade in 0..3 && it.priorState == "CRAM" }
    val counts = events.groupingBy { Instant.ofEpochMilli(it.reviewedAt).atZone(zone).toLocalDate() }.eachCount()
    val grades = events.groupingBy { it.grade }.eachCount()
    private val timed = events.mapNotNull { it.durationMs }.filter { it >= 0 }
    val timedCount = timed.size
    val measuredMs = timed.sum()
    val averageMs = if (timed.isEmpty()) null else measuredMs / timed.size
    // Count the first scheduled review of each card per local day. Intraday retries measure relearning.
    private val eligible = events.filter { it.priorState == "REVIEW" }
        .groupBy { it.cardId to Instant.ofEpochMilli(it.reviewedAt).atZone(zone).toLocalDate() }
        .values.map { records -> records.minWith(compareBy<CardReview> { it.reviewedAt }.thenBy { it.id }) }
    val retentionTrials = eligible.size
    val recalled = eligible.count { it.grade > 0 }
    val legacyCount = events.count { it.priorState == null }
    val totalReviews: Int get() = events.size
    val againCount: Int get() = grades[0] ?: 0
    val hardCount: Int get() = grades[1] ?: 0
    val goodCount: Int get() = grades[2] ?: 0
    val easyCount: Int get() = grades[3] ?: 0
    val correctCount: Int get() = events.count { it.grade > 0 }
    val retentionRate: Double? get() = if (retentionTrials > 0) recalled.toDouble() / retentionTrials else null
    val dailyBuckets: List<Pair<LocalDate, Int>> = buildList {
        val stride = when { days <= 30 -> 1; days <= 90 -> 7; else -> 30 }
        var day = firstDay
        while (!day.isAfter(today)) {
            val length = minOf(stride.toLong(), today.toEpochDay() - day.toEpochDay() + 1).toInt()
            add(day to (0 until length).sumOf { counts[day.plusDays(it.toLong())] ?: 0 })
            day = day.plusDays(length.toLong())
        }
    }
}

internal enum class IntervalGroup(val label: String) {
    NEW("Novos"), LEARNING("Aprendizagem ou reaprendizagem"), YOUNG("Jovens · 1–20 dias"),
    MATURE("Maduros · 21 dias ou mais"), SUSPENDED("Suspensos")
}

internal fun Flashcard.intervalGroup(): IntervalGroup = when {
    suspended -> IntervalGroup.SUSPENDED
    reviewState == "NEW" -> IntervalGroup.NEW
    reviewState != "REVIEW" -> IntervalGroup.LEARNING
    intervalDays < 21 -> IntervalGroup.YOUNG
    else -> IntervalGroup.MATURE
}

internal fun reviewForecast(cards: List<Flashcard>, today: LocalDate, zone: ZoneId): List<Int> {
    val counts = IntArray(7)
    for (card in cards) {
        if (card.suspended || card.reviewState == "NEW") continue
        val date = Instant.ofEpochMilli(card.dueAt).atZone(zone).toLocalDate()
        val offset = (date.toEpochDay() - today.toEpochDay()).coerceAtLeast(0)
        if (offset < counts.size) counts[offset.toInt()]++
    }
    return counts.toList()
}

/** Projects scheduled daily review load over the next [days] starting from [today]. */
fun reviewLoadProjection(
    cards: List<Flashcard>,
    today: LocalDate,
    days: Int = 14,
    zone: ZoneId = ZoneId.systemDefault()
): List<Pair<LocalDate, Int>> {
    if (days <= 0) return emptyList()
    val counts = IntArray(days)
    val todayEpoch = today.toEpochDay()
    for (card in cards) {
        if (card.suspended || card.reviewState == "NEW") continue
        val dueDate = Instant.ofEpochMilli(card.dueAt).atZone(zone).toLocalDate()
        val effectiveEpoch = if (card.isBuriedOn(today)) {
            maxOf(dueDate.toEpochDay(), card.buriedUntilDay + 1L)
        } else {
            dueDate.toEpochDay()
        }
        val offset = (effectiveEpoch - todayEpoch).coerceAtLeast(0L)
        if (offset < days) counts[offset.toInt()]++
    }
    return List(days) { i -> today.plusDays(i.toLong()) to counts[i] }
}

data class FsrsMemorySummary(
    val trackedCount: Int,
    val averageStabilityDays: Double,
    val averageDifficulty: Double,
    val averageRetrievability: Double,
    val leechCount: Int,
    val suspendedCount: Int,
    val buriedCount: Int,
    val flagCounts: Map<Int, Int>
)

private val summaryTagSplitRegex = Regex("""\s+""")

/** Summarizes FSRS stability/difficulty/retrievability, leech/buried/suspended counts, and flag distribution. */
fun fsrsMemorySummary(
    cards: List<Flashcard>,
    decks: List<CardDeck> = emptyList(),
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): FsrsMemorySummary {
    val deckById = decks.associateBy { it.id }
    var tracked = 0
    var sumStability = 0.0
    var diffCount = 0
    var sumDifficulty = 0.0
    var sumRetrievability = 0.0
    var leeches = 0
    var suspendedCount = 0
    var buriedCount = 0
    val flags = LinkedHashMap<Int, Int>()

    for (card in cards) {
        if (card.suspended) suspendedCount++
        val dayHour = card.deckId?.let { deckById[it]?.dayStartHour } ?: 4
        if (card.isBuriedOn(ankiLogicalDay(now, dayHour, zone))) buriedCount++
        val flag = card.flag.coerceIn(0, 7)
        flags[flag] = (flags[flag] ?: 0) + 1
        if (card.tags.isNotBlank()) {
            val tagTokens = card.tags.trim().split(summaryTagSplitRegex)
            if (tagTokens.any { it.equals("sanguessuga", ignoreCase = true) || it.equals("leech", ignoreCase = true) }) {
                leeches++
            }
        }
        if (card.fsrsStability > 0.0) {
            tracked++
            sumStability += card.fsrsStability
            val elapsedDays = if (card.lastReviewAt in 1L..now) {
                (now - card.lastReviewAt) / 86_400_000.0
            } else {
                card.intervalDays.toDouble().coerceAtLeast(0.0)
            }
            sumRetrievability += FsrsScheduler.retrievability(elapsedDays, card.fsrsStability)
            if (card.fsrsDifficulty > 0.0) {
                diffCount++
                sumDifficulty += card.fsrsDifficulty
            }
        }
    }

    return FsrsMemorySummary(
        trackedCount = tracked,
        averageStabilityDays = if (tracked > 0) sumStability / tracked else 0.0,
        averageDifficulty = if (diffCount > 0) sumDifficulty / diffCount else 0.0,
        averageRetrievability = if (tracked > 0) sumRetrievability / tracked else 0.0,
        leechCount = leeches,
        suspendedCount = suspendedCount,
        buriedCount = buriedCount,
        flagCounts = flags
    )
}
