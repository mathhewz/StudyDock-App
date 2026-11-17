package com.studydock.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

private const val MAX_INTERVAL_DAYS = 36_500
private val ptBrLocale: Locale = Locale.forLanguageTag("pt-BR")
private val STEP_SPLIT_REGEX = Regex("""[\s,;]+""")
private val TAG_SPLIT_REGEX = Regex("""\s+""")

@Entity(
    tableName = "review_round",
    foreignKeys = [
        ForeignKey(entity = Flashcard::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index("done"),
        Index("position"),
        Index(value = ["cardId", "token", "done"])
    ]
)
data class RoundEntry(
    @PrimaryKey val cardId: Long,
    val token: String,
    val position: Int,
    val done: Boolean = false,
    val elapsedMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val cram: Boolean = false
)

data class RoundCard(
    @Embedded val card: Flashcard,
    @Embedded(prefix = "round_") val entry: RoundEntry,
    val occlusion: String? = null
)

/** Logical study date honoring Anki's next-day rollover hour (default 04:00 local). */
fun ankiLogicalDay(
    epochMillis: Long,
    dayStartHour: Int = 4,
    zone: ZoneId = ZoneId.systemDefault()
): LocalDate {
    val startHour = dayStartHour.coerceIn(0, 23)
    val zdt = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val localDate = zdt.toLocalDate()
    return if (zdt.hour < startHour) localDate.minusDays(1) else localDate
}

internal fun ankiLogicalDayBounds(
    day: LocalDate,
    dayStartHour: Int = 4,
    zone: ZoneId = ZoneId.systemDefault()
): Pair<Long, Long> {
    val startHour = dayStartHour.coerceIn(0, 23)
    val start = day.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
    return start to end
}

/** Whether this card is buried on [day]. Note: `0L` denotes unburied default. */
fun Flashcard.isBuriedOn(day: LocalDate): Boolean =
    buriedUntilDay > 0L && buriedUntilDay >= day.toEpochDay()

val Flashcard.reviewState: String
    get() = when {
        learningState == "LEARNING" || learningState == "RELEARNING" -> learningState
        intervalDays > 0 || repetitions > 0 -> "REVIEW"
        else -> "NEW"
    }

data class ReviewOutcome(
    val card: Flashcard,
    val review: CardReview,
    val isLeechNow: Boolean = false,
    val nextIntervalLabel: String = ""
)

/**
 * Pure-Kotlin FSRS-4.5/5 scheduler using 19 default weights (`w[0..18]`) and flat power decay.
 * Grades map to FSRS ratings `1 = Again, 2 = Hard, 3 = Good, 4 = Easy`.
 */
object FsrsScheduler {
    const val FACTOR: Double = 19.0 / 81.0
    const val DECAY: Double = -0.5

    val DEFAULT_WEIGHTS: DoubleArray = doubleArrayOf(
        0.40255, 1.18385, 3.173, 15.69105,
        7.1949, 0.5345, 1.4604, 0.0046,
        1.54575, 0.1192, 1.01925,
        1.9395, 0.11, 0.29605, 2.2698,
        0.2315, 2.9898,
        0.51655, 0.6621
    )

    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        val t = elapsedDays.coerceAtLeast(0.0)
        return (1.0 + FACTOR * t / stability).pow(DECAY).coerceIn(0.0, 1.0)
    }

    fun initialStability(rating: Int, w: DoubleArray = DEFAULT_WEIGHTS): Double {
        val idx = (rating - 1).coerceIn(0, 3)
        return w[idx].coerceAtLeast(0.01)
    }

    fun initialDifficulty(rating: Int, w: DoubleArray = DEFAULT_WEIGHTS): Double {
        val r = rating.coerceIn(1, 4)
        return (w[4] - exp(w[5] * (r - 1)) + 1.0).coerceIn(1.0, 10.0)
    }

    fun nextDifficulty(difficulty: Double, rating: Int, w: DoubleArray = DEFAULT_WEIGHTS): Double {
        val r = rating.coerceIn(1, 4)
        val deltaD = -w[6] * (r - 3)
        val damped = difficulty + deltaD * (10.0 - difficulty) / 9.0
        val meanReverted = w[7] * initialDifficulty(4, w) + (1.0 - w[7]) * damped
        return meanReverted.coerceIn(1.0, 10.0)
    }

    fun sameDayStability(stability: Double, rating: Int, w: DoubleArray = DEFAULT_WEIGHTS): Double {
        val r = rating.coerceIn(1, 4)
        val s = stability.coerceAtLeast(0.01)
        return (s * exp(w[17] * (r - 3 + w[18]))).coerceAtLeast(0.01)
    }

    fun recallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Int,
        w: DoubleArray = DEFAULT_WEIGHTS
    ): Double {
        val r = rating.coerceIn(2, 4)
        val s = stability.coerceAtLeast(0.01)
        val d = difficulty.coerceIn(1.0, 10.0)
        val hardPenalty = if (r == 2) w[15] else 1.0
        val easyBonus = if (r == 4) w[16] else 1.0
        val gain = exp(w[8]) *
            (11.0 - d) *
            s.pow(-w[9]) *
            (exp(w[10] * (1.0 - retrievability.coerceIn(0.0, 1.0))) - 1.0) *
            hardPenalty *
            easyBonus
        return (s * (1.0 + gain)).coerceAtLeast(0.01)
    }

    fun forgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        w: DoubleArray = DEFAULT_WEIGHTS
    ): Double {
        val s = stability.coerceAtLeast(0.01)
        val d = difficulty.coerceIn(1.0, 10.0)
        val newS = w[11] *
            d.pow(-w[12]) *
            ((s + 1.0).pow(w[13]) - 1.0) *
            exp(w[14] * (1.0 - retrievability.coerceIn(0.0, 1.0)))
        return newS.coerceIn(0.01, s)
    }

    fun nextIntervalDays(stability: Double, desiredRetention: Double = 0.90): Int {
        val r = desiredRetention.coerceIn(0.70, 0.99)
        val s = stability.coerceAtLeast(0.01)
        val raw = (s / FACTOR) * (r.pow(1.0 / DECAY) - 1.0)
        return raw.roundToInt().coerceIn(1, MAX_INTERVAL_DAYS)
    }

    internal fun seededDifficultyFromEase(easeFactor: Double): Double =
        (10.0 - (easeFactor.coerceAtLeast(1.3) - 1.3) * 4.0).coerceIn(1.0, 10.0)
}

private fun parseStepMinutes(spec: String): List<Double> {
    val trimmed = spec.trim()
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split(STEP_SPLIT_REGEX).mapNotNull { token ->
        val t = token.trim().lowercase(Locale.ROOT)
        when {
            t.isEmpty() -> null
            t.endsWith("s") -> t.dropLast(1).toDoubleOrNull()?.div(60.0)?.takeIf { it > 0.0 }
            t.endsWith("m") -> t.dropLast(1).toDoubleOrNull()?.takeIf { it > 0.0 }
            t.endsWith("h") -> t.dropLast(1).toDoubleOrNull()?.times(60.0)?.takeIf { it > 0.0 }
            t.endsWith("d") -> t.dropLast(1).toDoubleOrNull()?.times(1440.0)?.takeIf { it > 0.0 }
            else -> t.toDoubleOrNull()?.takeIf { it > 0.0 }
        }
    }
}

internal fun formatIntervalDaysLabel(days: Int): String = when {
    days <= 1 -> "1 dia"
    days < 30 -> "$days dias"
    days < 365 -> {
        val months = days / 30.0
        val whole = months.roundToInt()
        if (abs(months - whole) < 0.05) {
            if (whole == 1) "1 mês" else "$whole meses"
        } else {
            String.format(ptBrLocale, "%.1f meses", months)
        }
    }
    else -> {
        val years = days / 365.0
        val whole = years.roundToInt()
        if (abs(years - whole) < 0.05) {
            if (whole == 1) "1 ano" else "$whole anos"
        } else {
            String.format(ptBrLocale, "%.1f anos", years)
        }
    }
}

private fun formatStepIntervalLabel(minutes: Double): String = when {
    minutes < 1.0 -> "${(minutes * 60.0).roundToInt().coerceAtLeast(1)} s"
    minutes < 60.0 -> "${minutes.roundToInt().coerceAtLeast(1)} min"
    minutes < 1440.0 -> {
        val hours = minutes / 60.0
        val whole = hours.roundToInt()
        if (abs(hours - whole) < 0.05) "${whole} h"
        else String.format(ptBrLocale, "%.1f h", hours)
    }
    else -> formatIntervalDaysLabel((minutes / 1440.0).roundToInt().coerceAtLeast(1))
}

private fun applyIntervalFuzz(days: Int, deterministic: Boolean): Int {
    val base = days.coerceIn(1, MAX_INTERVAL_DAYS)
    if (deterministic || base < 3) return base
    val delta = max(1, (base * 0.05).roundToInt())
    val offset = Random.Default.nextInt(-delta, delta + 1)
    return (base + offset).coerceIn(1, MAX_INTERVAL_DAYS)
}

internal fun nextDayDueMillis(
    now: Long,
    intervalDays: Int,
    dayStartHour: Int = 4,
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    val logicalToday = ankiLogicalDay(now, dayStartHour, zone)
    val startHour = dayStartHour.coerceIn(0, 23)
    return logicalToday
        .plusDays(intervalDays.toLong())
        .atTime(startHour, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}

private fun appendTagIfMissing(rawTags: String, tag: String): String {
    val existing = rawTags.trim().split(TAG_SPLIT_REGEX).filter { it.isNotBlank() }
    if (existing.any { it.equals(tag, ignoreCase = true) }) {
        return existing.joinToString(" ")
    }
    return (existing + tag).joinToString(" ")
}

private data class FsrsStepUpdate(
    val stability: Double,
    val difficulty: Double
)

private fun Flashcard.updateFsrsStateForRating(
    rating: Int,
    now: Long,
    dayStartHour: Int,
    zone: ZoneId,
    weights: DoubleArray = FsrsScheduler.DEFAULT_WEIGHTS
): FsrsStepUpdate {
    val prevS = when {
        fsrsStability > 0.0 -> fsrsStability
        intervalDays > 0 -> intervalDays.toDouble()
        else -> 0.0
    }
    val prevD = when {
        fsrsDifficulty > 0.0 -> fsrsDifficulty.coerceIn(1.0, 10.0)
        repetitions > 0 || intervalDays > 0 -> FsrsScheduler.seededDifficultyFromEase(easeFactor)
        else -> 0.0
    }
    if (prevS <= 0.0 || prevD <= 0.0 || lastReviewAt <= 0L && reviewState == "NEW") {
        val s0 = FsrsScheduler.initialStability(rating, weights)
        val d0 = FsrsScheduler.initialDifficulty(rating, weights)
        return FsrsStepUpdate(s0, d0)
    }
    val nextD = FsrsScheduler.nextDifficulty(prevD, rating, weights)
    val elapsedDaysExact = if (lastReviewAt in 1L..now) {
        (now - lastReviewAt) / 86_400_000.0
    } else {
        intervalDays.toDouble().coerceAtLeast(0.0)
    }
    val sameLogicalDay = lastReviewAt in 1L..now &&
        ankiLogicalDay(lastReviewAt, dayStartHour, zone) == ankiLogicalDay(now, dayStartHour, zone)
    val nextS = if (sameLogicalDay) {
        FsrsScheduler.sameDayStability(prevS, rating, weights)
    } else {
        val r = FsrsScheduler.retrievability(elapsedDaysExact, prevS)
        if (rating == 1) FsrsScheduler.forgetStability(prevD, prevS, r, weights)
        else FsrsScheduler.recallStability(prevD, prevS, r, rating, weights)
    }
    return FsrsStepUpdate(nextS, nextD)
}

/**
 * Full Anki Pro scheduler supporting SM-2 Pro & FSRS-4.5/5, configurable steps, rollover hour,
 * interval fuzzing, automatic leech tagging/suspension, and non-mutating CRAM reviews.
 */
fun Flashcard.afterGradePro(
    grade: Int,
    now: Long = System.currentTimeMillis(),
    config: CardDeck? = null,
    cram: Boolean = false,
    durationMs: Long? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    deterministic: Boolean = false
): ReviewOutcome {
    require(grade in 0..3) { "Grade must be in 0..3, got $grade" }
    val priorState = reviewState
    val priorIvl = intervalDays

    if (cram) {
        val cramReview = CardReview(
            cardId = id,
            reviewedAt = now,
            grade = grade,
            intervalDays = priorIvl,
            durationMs = durationMs?.coerceAtLeast(0L),
            priorState = "CRAM",
            priorIntervalDays = priorIvl,
            deckAtReview = deckId,
            subjectAtReview = subjectId
        )
        return ReviewOutcome(
            card = this,
            review = cramReview,
            isLeechNow = false,
            nextIntervalLabel = "Cram"
        )
    }

    val dayStartHour = (config?.dayStartHour ?: 4).coerceIn(0, 23)
    val isFsrs = config?.schedulerMode?.equals("FSRS", ignoreCase = true) == true
    val desiredRetention = (config?.desiredRetention ?: 0.90).coerceIn(0.70, 0.99)
    val learningSteps = parseStepMinutes(config?.learningSteps ?: "1 10")
    val relearningSteps = parseStepMinutes(config?.relearningSteps ?: "10")
    val graduatingInterval = (config?.graduatingInterval ?: 1).coerceIn(1, MAX_INTERVAL_DAYS)
    val easyInterval = (config?.easyInterval ?: 4).coerceIn(1, MAX_INTERVAL_DAYS)
    val leechThreshold = (config?.leechThreshold ?: 8).coerceAtLeast(1)
    val rating = grade + 1

    val customWeights = config?.fsrsWeights?.let { FsrsOptimizer.parseWeights(it) } ?: FsrsScheduler.DEFAULT_WEIGHTS
    val easyDaysMask = config?.easyDaysMask ?: 0
    val fsrsUpdate = if (isFsrs) updateFsrsStateForRating(rating, now, dayStartHour, zone, customWeights) else null

    fun recordOutcome(nextCard: Flashcard, label: String, leechTriggered: Boolean): ReviewOutcome {
        val review = CardReview(
            cardId = id,
            reviewedAt = now,
            grade = grade,
            intervalDays = nextCard.intervalDays,
            durationMs = durationMs?.coerceAtLeast(0L),
            priorState = priorState,
            priorIntervalDays = priorIvl,
            deckAtReview = deckId,
            subjectAtReview = subjectId
        )
        return ReviewOutcome(
            card = nextCard,
            review = review,
            isLeechNow = leechTriggered,
            nextIntervalLabel = label
        )
    }

    // Helper for graduating or reviewing into a day-based interval.
    fun finishReviewCard(
        rawDays: Int,
        newEase: Double,
        newReps: Int,
        newLapses: Int = lapses,
        leechTriggered: Boolean = false,
        nextTags: String = tags,
        nextSuspended: Boolean = suspended,
        overrideStability: Double? = fsrsUpdate?.stability,
        overrideDifficulty: Double? = fsrsUpdate?.difficulty
    ): ReviewOutcome {
        val fuzzedDays = adjustIntervalForEasyDays(applyIntervalFuzz(rawDays, deterministic), now, easyDaysMask, zone)
        val due = nextDayDueMillis(now, fuzzedDays, dayStartHour, zone)
        val updated = copy(
            dueAt = due,
            intervalDays = fuzzedDays,
            easeFactor = newEase.coerceAtLeast(1.3),
            repetitions = newReps,
            learningState = null,
            learningStep = 0,
            lapses = newLapses,
            tags = nextTags,
            suspended = nextSuspended,
            fsrsStability = overrideStability ?: fsrsStability,
            fsrsDifficulty = overrideDifficulty ?: fsrsDifficulty,
            lastReviewAt = now
        )
        return recordOutcome(updated, formatIntervalDaysLabel(fuzzedDays), leechTriggered)
    }

    // 1. NEW or LEARNING cards
    if (priorState == "NEW" || priorState == "LEARNING") {
        val currentStep = if (priorState == "NEW") 0 else learningStep.coerceAtLeast(0)
        if (grade != 3 && learningSteps.isNotEmpty()) {
            val safeIdx = currentStep.coerceIn(0, learningSteps.lastIndex)
            when (grade) {
                0 -> {
                    val stepMinutes = learningSteps[0]
                    val nextCard = copy(
                        dueAt = now + (stepMinutes * 60_000.0).roundToLong(),
                        intervalDays = 0,
                        learningState = "LEARNING",
                        learningStep = 0,
                        fsrsStability = fsrsUpdate?.stability ?: fsrsStability,
                        fsrsDifficulty = fsrsUpdate?.difficulty ?: fsrsDifficulty,
                        lastReviewAt = now
                    )
                    return recordOutcome(nextCard, formatStepIntervalLabel(stepMinutes), false)
                }
                1 -> {
                    val stepMinutes = if (safeIdx == 0) {
                        val nextStepMin = learningSteps.getOrNull(1) ?: (learningSteps[0] * 1.5)
                        (learningSteps[0] + nextStepMin) / 2.0
                    } else {
                        learningSteps[safeIdx]
                    }
                    val nextCard = copy(
                        dueAt = now + (stepMinutes * 60_000.0).roundToLong(),
                        intervalDays = 0,
                        learningState = "LEARNING",
                        learningStep = safeIdx,
                        fsrsStability = fsrsUpdate?.stability ?: fsrsStability,
                        fsrsDifficulty = fsrsUpdate?.difficulty ?: fsrsDifficulty,
                        lastReviewAt = now
                    )
                    return recordOutcome(nextCard, formatStepIntervalLabel(stepMinutes), false)
                }
                else -> { // grade == 2 (Good)
                    val nextIdx = currentStep + 1
                    if (nextIdx < learningSteps.size) {
                        val stepMinutes = learningSteps[nextIdx]
                        val nextCard = copy(
                            dueAt = now + (stepMinutes * 60_000.0).roundToLong(),
                            intervalDays = 0,
                            learningState = "LEARNING",
                            learningStep = nextIdx,
                            fsrsStability = fsrsUpdate?.stability ?: fsrsStability,
                            fsrsDifficulty = fsrsUpdate?.difficulty ?: fsrsDifficulty,
                            lastReviewAt = now
                        )
                        return recordOutcome(nextCard, formatStepIntervalLabel(stepMinutes), false)
                    }
                }
            }
        }
        // Graduating from NEW/LEARNING via Easy or final Good (or empty learning steps)
        val rawDays = if (isFsrs && fsrsUpdate != null) {
            FsrsScheduler.nextIntervalDays(fsrsUpdate.stability, desiredRetention)
        } else {
            when (grade) {
                3 -> easyInterval
                1 -> max(1, graduatingInterval)
                else -> graduatingInterval
            }
        }
        val ease = if (grade == 3) easeFactor + 0.0 else easeFactor.coerceAtLeast(1.3)
        return finishReviewCard(rawDays, ease, repetitions + 1)
    }

    // 2. RELEARNING cards
    if (priorState == "RELEARNING") {
        val currentStep = learningStep.coerceAtLeast(0)
        if (grade != 3 && relearningSteps.isNotEmpty()) {
            val safeIdx = currentStep.coerceIn(0, relearningSteps.lastIndex)
            when (grade) {
                0 -> {
                    val stepMinutes = relearningSteps[0]
                    val nextCard = copy(
                        dueAt = now + (stepMinutes * 60_000.0).roundToLong(),
                        learningState = "RELEARNING",
                        learningStep = 0,
                        fsrsStability = fsrsUpdate?.stability ?: fsrsStability,
                        fsrsDifficulty = fsrsUpdate?.difficulty ?: fsrsDifficulty,
                        lastReviewAt = now
                    )
                    return recordOutcome(nextCard, formatStepIntervalLabel(stepMinutes), false)
                }
                1 -> {
                    val stepMinutes = if (safeIdx == 0) {
                        val nextStepMin = relearningSteps.getOrNull(1) ?: (relearningSteps[0] * 1.5)
                        (relearningSteps[0] + nextStepMin) / 2.0
                    } else {
                        relearningSteps[safeIdx]
                    }
                    val nextCard = copy(
                        dueAt = now + (stepMinutes * 60_000.0).roundToLong(),
                        learningState = "RELEARNING",
                        learningStep = safeIdx,
                        fsrsStability = fsrsUpdate?.stability ?: fsrsStability,
                        fsrsDifficulty = fsrsUpdate?.difficulty ?: fsrsDifficulty,
                        lastReviewAt = now
                    )
                    return recordOutcome(nextCard, formatStepIntervalLabel(stepMinutes), false)
                }
                else -> { // grade == 2 (Good)
                    val nextIdx = currentStep + 1
                    if (nextIdx < relearningSteps.size) {
                        val stepMinutes = relearningSteps[nextIdx]
                        val nextCard = copy(
                            dueAt = now + (stepMinutes * 60_000.0).roundToLong(),
                            learningState = "RELEARNING",
                            learningStep = nextIdx,
                            fsrsStability = fsrsUpdate?.stability ?: fsrsStability,
                            fsrsDifficulty = fsrsUpdate?.difficulty ?: fsrsDifficulty,
                            lastReviewAt = now
                        )
                        return recordOutcome(nextCard, formatStepIntervalLabel(stepMinutes), false)
                    }
                }
            }
        }
        // Graduating out of RELEARNING back to REVIEW
        val rawDays = if (isFsrs && fsrsUpdate != null) {
            FsrsScheduler.nextIntervalDays(fsrsUpdate.stability, desiredRetention)
        } else {
            val base = intervalDays.coerceAtLeast(1)
            if (grade == 3) max(base + 1, easyInterval.coerceAtLeast(base)) else base
        }
        return finishReviewCard(rawDays, easeFactor.coerceAtLeast(1.3), repetitions + 1)
    }

    // 3. REVIEW cards
    if (grade == 0) {
        val newLapses = lapses + 1
        val isLeechNow = newLapses >= leechThreshold &&
            (newLapses == leechThreshold || (newLapses - leechThreshold) % max(1, leechThreshold / 2) == 0)
        val updatedTags = if (isLeechNow) appendTagIfMissing(tags, "sanguessuga") else tags
        val updatedSuspended = suspended || (isLeechNow && (config?.leechSuspend == true))
        val newEase = (easeFactor - 0.20).coerceAtLeast(1.3)
        val postLapseDays = if (isFsrs && fsrsUpdate != null) {
            FsrsScheduler.nextIntervalDays(fsrsUpdate.stability, desiredRetention)
        } else {
            max(1, (intervalDays * 0.5).roundToInt())
        }
        if (relearningSteps.isEmpty()) {
            return finishReviewCard(
                rawDays = postLapseDays,
                newEase = newEase,
                newReps = repetitions,
                newLapses = newLapses,
                leechTriggered = isLeechNow,
                nextTags = updatedTags,
                nextSuspended = updatedSuspended
            )
        }
        val stepMinutes = relearningSteps[0]
        val lapsedCard = copy(
            dueAt = now + (stepMinutes * 60_000.0).roundToLong(),
            intervalDays = postLapseDays,
            easeFactor = newEase,
            learningState = "RELEARNING",
            learningStep = 0,
            lapses = newLapses,
            tags = updatedTags,
            suspended = updatedSuspended,
            fsrsStability = fsrsUpdate?.stability ?: fsrsStability,
            fsrsDifficulty = fsrsUpdate?.difficulty ?: fsrsDifficulty,
            lastReviewAt = now
        )
        return recordOutcome(lapsedCard, formatStepIntervalLabel(stepMinutes), isLeechNow)
    }

    // REVIEW card recalled (grade 1..3)
    val prevIvl = intervalDays.coerceAtLeast(1)
    val rawDays = if (isFsrs && fsrsUpdate != null) {
        FsrsScheduler.nextIntervalDays(fsrsUpdate.stability, desiredRetention)
    } else {
        val elapsedDays = if (lastReviewAt in 1L..now) {
            (ankiLogicalDay(now, dayStartHour, zone).toEpochDay() -
                ankiLogicalDay(lastReviewAt, dayStartHour, zone).toEpochDay()).toDouble().coerceAtLeast(0.0)
        } else 0.0
        val daysLate = (elapsedDays - prevIvl).coerceAtLeast(0.0)
        val hardIvl = max(prevIvl + 1, (prevIvl * 1.2).roundToInt())
        val goodIvl = max(hardIvl + 1, ((prevIvl + daysLate / 2.0) * easeFactor).roundToInt())
        val easyIvl = max(goodIvl + 1, ((prevIvl + daysLate) * easeFactor * 1.3).roundToInt())
        when (grade) {
            1 -> hardIvl
            2 -> goodIvl
            else -> easyIvl
        }
    }
    val newEase = when (grade) {
        1 -> (easeFactor - 0.15).coerceAtLeast(1.3)
        2 -> easeFactor.coerceAtLeast(1.3)
        else -> easeFactor + 0.15
    }
    return finishReviewCard(rawDays, newEase, repetitions + 1)
}

/** Previews concise interval labels for grades `0..3` ("De novo", "Difícil", "Bom", "Fácil"). */
fun Flashcard.previewIntervals(
    now: Long = System.currentTimeMillis(),
    config: CardDeck? = null,
    zone: ZoneId = ZoneId.systemDefault()
): List<String> = (0..3).map { grade ->
    afterGradePro(
        grade = grade,
        now = now,
        config = config,
        cram = false,
        zone = zone,
        deterministic = true
    ).nextIntervalLabel
}

internal val REVIEW_MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN learningState TEXT")
        db.execSQL("""UPDATE flashcards SET learningState = CASE WHEN EXISTS (
            SELECT 1 FROM card_reviews WHERE cardId = flashcards.id AND grade > 0
        ) THEN 'RELEARNING' ELSE 'LEARNING' END WHERE repetitions = 0 AND intervalDays = 0 AND lapses > 0""")
        db.execSQL("""CREATE TABLE card_reviews_new (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardId INTEGER NOT NULL,
            reviewedAt INTEGER NOT NULL, grade INTEGER NOT NULL, intervalDays INTEGER NOT NULL,
            durationMs INTEGER, priorState TEXT, priorIntervalDays INTEGER, deckAtReview INTEGER, subjectAtReview INTEGER)""")
        db.execSQL("""INSERT INTO card_reviews_new(id, cardId, reviewedAt, grade, intervalDays)
            SELECT id, cardId, reviewedAt, grade, intervalDays FROM card_reviews""")
        db.execSQL("DROP TABLE card_reviews")
        db.execSQL("ALTER TABLE card_reviews_new RENAME TO card_reviews")
        db.execSQL("CREATE INDEX index_card_reviews_cardId ON card_reviews(cardId)")
        db.execSQL("CREATE INDEX index_card_reviews_reviewedAt ON card_reviews(reviewedAt)")
        db.execSQL("""CREATE TABLE review_round (cardId INTEGER NOT NULL PRIMARY KEY, token TEXT NOT NULL,
            position INTEGER NOT NULL, done INTEGER NOT NULL, elapsedMs INTEGER NOT NULL,
            FOREIGN KEY(cardId) REFERENCES flashcards(id) ON DELETE CASCADE)""")
    }
}

/** Ajusta intervalo em dias para evitar dias marcados em `easyDaysMask` (bitmask Seg=0..Dom=6). */
fun adjustIntervalForEasyDays(
    rawDays: Int,
    now: Long,
    easyDaysMask: Int,
    zone: ZoneId = ZoneId.systemDefault()
): Int {
    if (easyDaysMask == 0 || rawDays <= 2) return rawDays
    val baseDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(rawDays.toLong())
    val dowBit = (baseDate.dayOfWeek.value - 1) % 7
    if ((easyDaysMask and (1 shl dowBit)) == 0) return rawDays
    for (delta in listOf(1, -1, 2, -2)) {
        val candDays = rawDays + delta
        if (candDays < 2) continue
        val candDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(candDays.toLong())
        val candBit = (candDate.dayOfWeek.value - 1) % 7
        if ((easyDaysMask and (1 shl candBit)) == 0) return candDays
    }
    return rawDays
}
