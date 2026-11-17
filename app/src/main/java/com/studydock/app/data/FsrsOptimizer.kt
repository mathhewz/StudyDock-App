package com.studydock.app.data

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class FsrsCalibrationResult(
    val sampleCount: Int,
    val observedRetention: Double,
    val stabilityScale: Double,
    val recommendedRetention: Double,
    val baselineLogLoss: Double,
    val calibratedLogLoss: Double,
    val optimizedWeights: DoubleArray = FsrsOptimizer.DEFAULT_WEIGHTS,
    val summary: String
)

/**
 * Calibrador e Otimizador FSRS-5 real baseado na trajetória sequencial (S, D) de cada cartão ([CardReview]).
 * Reconstrui a estabilidade S e dificuldade D revisão por revisão usando [FsrsScheduler] com os 19 pesos `w[0..18]`,
 * otimiza os 19 parâmetros por gradiente de diferenças finitas com regularização L2 e simula 90 dias via FSRS-5.
 */
object FsrsOptimizer {
    val DEFAULT_WEIGHTS: DoubleArray
        get() = FsrsScheduler.DEFAULT_WEIGHTS.copyOf()

    private val MIN_BOUNDS = doubleArrayOf(
        0.05, 0.10, 0.20, 0.50,
        1.00, 0.05, 0.05, 0.00,
        0.20, 0.01, 0.10, 0.10,
        0.01, 0.05, 0.20, 0.05,
        1.05, 0.01, 0.01
    )
    private val MAX_BOUNDS = doubleArrayOf(
        10.0, 20.0, 40.0, 100.0,
        10.0, 4.00, 4.00, 0.75,
        4.00, 0.80, 3.50, 6.00,
        0.80, 0.90, 6.00, 1.00,
        6.00, 2.00, 2.00
    )

    fun parseWeights(raw: String): DoubleArray? {
        if (raw.isBlank()) return null
        val parts = raw.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size != 19) return null
        return DoubleArray(19) { idx -> parts[idx].coerceIn(MIN_BOUNDS[idx], MAX_BOUNDS[idx]) }
    }

    fun serializeWeights(weights: DoubleArray): String =
        weights.joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }

    fun calibrate(
        reviews: List<CardReview>,
        currentDesiredRetention: Double = 0.90,
        deckId: Long? = null
    ): FsrsCalibrationResult {
        val scoped = reviews.asSequence()
            .filter { !it.priorState.equals("CRAM", ignoreCase = true) }
            .filter { deckId == null || it.deckAtReview == deckId }
            .filter { (it.priorIntervalDays ?: 0) > 0 && it.intervalDays >= 0 }
            .sortedBy { it.reviewedAt }
            .toList()

        if (scoped.size < 10) {
            return FsrsCalibrationResult(
                sampleCount = scoped.size,
                observedRetention = if (scoped.isEmpty()) currentDesiredRetention else scoped.count { it.grade > 0 }.toDouble() / scoped.size,
                stabilityScale = 1.0,
                recommendedRetention = currentDesiredRetention.coerceIn(0.80, 0.95),
                baselineLogLoss = 0.0,
                calibratedLogLoss = 0.0,
                optimizedWeights = DEFAULT_WEIGHTS,
                summary = "Histórico insuficiente (${scoped.size}/10 revisões com intervalo anterior)."
            )
        }

        val observed = scoped.count { it.grade > 0 }.toDouble() / scoped.size.toDouble()
        val baseWeights = DEFAULT_WEIGHTS
        val baselineLoss = computeSequenceLogLoss(scoped, baseWeights)
        val optimized = optimizeWeights(scoped, baseWeights)
        val calibratedLoss = min(baselineLoss, computeSequenceLogLoss(scoped, optimized))

        val scaleRatio = ((optimized[0] / baseWeights[0] + optimized[1] / baseWeights[1] + optimized[2] / baseWeights[2] + optimized[3] / baseWeights[3]) / 4.0)
        val roundedScale = ((scaleRatio * 100.0).roundToInt() / 100.0).coerceIn(0.50, 2.00)

        val recommended = when {
            observed < 0.78 -> (currentDesiredRetention + 0.03).coerceIn(0.80, 0.95)
            observed > 0.94 -> (currentDesiredRetention - 0.02).coerceIn(0.80, 0.95)
            else -> ((observed * 0.5 + currentDesiredRetention * 0.5) * 100.0).roundToInt() / 100.0
        }.coerceIn(0.80, 0.95)

        val obsPct = (observed * 100.0).roundToInt()
        val recPct = (recommended * 100.0).roundToInt()
        val msg = "${scoped.size} revisões · retenção real $obsPct% · log-loss ${"%.3f".format(calibratedLoss)} · sugerida $recPct%"

        return FsrsCalibrationResult(
            sampleCount = scoped.size,
            observedRetention = observed,
            stabilityScale = roundedScale,
            recommendedRetention = recommended,
            baselineLogLoss = baselineLoss,
            calibratedLogLoss = calibratedLoss,
            optimizedWeights = optimized,
            summary = msg
        )
    }

    /**
     * Calcula o log-loss real reconstruindo a trajetória (S, D) de cada cartão ao longo de suas revisões.
     */
    fun computeSequenceLogLoss(
        reviews: List<CardReview>,
        weights: DoubleArray = DEFAULT_WEIGHTS
    ): Double {
        if (reviews.isEmpty()) return 0.0
        val byCard = reviews.groupBy { it.cardId }
        var totalLoss = 0.0
        var count = 0

        for ((_, cardReviews) in byCard) {
            val ordered = cardReviews.sortedBy { it.reviewedAt }
            var s = 0.0
            var d = 0.0
            var prevTimestamp = 0L

            for (rev in ordered) {
                val rating = (rev.grade + 1).coerceIn(1, 4)
                val priorIvl = (rev.priorIntervalDays ?: 0).coerceAtLeast(0)
                val elapsedDays = when {
                    priorIvl > 0 -> priorIvl.toDouble()
                    prevTimestamp in 1L until rev.reviewedAt -> ((rev.reviewedAt - prevTimestamp) / 86_400_000.0).coerceAtLeast(0.25)
                    else -> 1.0
                }

                // Se é a primeira revisão observada mas já tinha priorIntervalDays > 0, semeia S anterior
                if (s <= 0.0 || d <= 0.0) {
                    s = if (priorIvl > 0) max(0.5, priorIvl * (weights[2] / FsrsScheduler.DEFAULT_WEIGHTS[2])) else FsrsScheduler.initialStability(rating, weights)
                    d = FsrsScheduler.initialDifficulty(3, weights)
                }

                val r = FsrsScheduler.retrievability(elapsedDays, s).coerceIn(1e-4, 1.0 - 1e-4)
                val y = if (rev.grade > 0) 1.0 else 0.0
                totalLoss += -(y * ln(r) + (1.0 - y) * ln(1.0 - r))
                count++

                // Atualiza (D, S) usando as equações reais do FSRS-5 e todos os 19 pesos
                d = FsrsScheduler.nextDifficulty(d, rating, weights)
                s = when {
                    rev.grade == 0 -> FsrsScheduler.forgetStability(d, s, r, weights)
                    elapsedDays < 1.0 -> FsrsScheduler.sameDayStability(s, rating, weights)
                    else -> FsrsScheduler.recallStability(d, s, r, rating, weights)
                }
                prevTimestamp = rev.reviewedAt
            }
        }
        return if (count == 0) 0.0 else totalLoss / count.toDouble()
    }

    /**
     * Otimiza todos os 19 pesos `w[0..18]` via descida de gradiente por diferenças finitas + regularização L2.
     */
    fun optimizeWeights(
        reviews: List<CardReview>,
        initialWeights: DoubleArray = DEFAULT_WEIGHTS
    ): DoubleArray {
        val w = initialWeights.copyOf()
        if (reviews.size < 10) return w

        val defaultW = DEFAULT_WEIGHTS
        val l2Lambda = 0.015

        fun objective(candidate: DoubleArray): Double {
            var reg = 0.0
            for (i in 0 until 19) {
                val normDiff = (candidate[i] - defaultW[i]) / max(0.1, defaultW[i])
                reg += normDiff * normDiff
            }
            return computeSequenceLogLoss(reviews, candidate) + l2Lambda * reg
        }

        var currentObj = objective(w)
        val epochs = 4
        for (epoch in 0 until epochs) {
            val lr = 0.12 / (1.0 + epoch * 0.4)
            for (i in 0 until 19) {
                val eps = max(0.005, w[i] * 0.03)
                val orig = w[i]

                w[i] = (orig + eps).coerceIn(MIN_BOUNDS[i], MAX_BOUNDS[i])
                val objPlus = objective(w)

                w[i] = (orig - eps).coerceIn(MIN_BOUNDS[i], MAX_BOUNDS[i])
                val objMinus = objective(w)

                val grad = (objPlus - objMinus) / (2.0 * eps)
                val stepped = (orig - lr * grad * max(0.1, defaultW[i])).coerceIn(MIN_BOUNDS[i], MAX_BOUNDS[i])
                w[i] = stepped
                val steppedObj = objective(w)
                if (steppedObj <= currentObj) {
                    currentObj = steppedObj
                } else {
                    val bestNeighbor = if (objPlus < objMinus && objPlus < currentObj) {
                        currentObj = objPlus
                        (orig + eps).coerceIn(MIN_BOUNDS[i], MAX_BOUNDS[i])
                    } else if (objMinus < currentObj) {
                        currentObj = objMinus
                        (orig - eps).coerceIn(MIN_BOUNDS[i], MAX_BOUNDS[i])
                    } else {
                        orig
                    }
                    w[i] = bestNeighbor
                }
            }
        }
        return w
    }

    /**
     * Simulador FSRS-5 real de 90 dias usando estabilidade (S), dificuldade (D) e [FsrsScheduler.nextIntervalDays].
     */
    fun simulate90Days(
        cards: List<Flashcard>,
        newPerDay: Int = 20,
        desiredRetention: Double = 0.90,
        easyDaysMask: Int = 0,
        weights: DoubleArray = DEFAULT_WEIGHTS
    ): List<Pair<Int, Int>> {
        val dailyCounts = IntArray(90)
        val now = System.currentTimeMillis()
        var newQueued = 0

        fun scheduleCardTrajectory(startDay: Int, initS: Double, initD: Double) {
            var day = startDay
            var s = initS
            var d = initD
            var lastDay = startDay
            while (day in 0..89) {
                dailyCounts[day]++
                val elapsed = max(1, day - lastDay).toDouble()
                val r = FsrsScheduler.retrievability(elapsed, s)
                val rating = if (r >= desiredRetention * 0.85) 3 else 1
                d = FsrsScheduler.nextDifficulty(d, rating, weights)
                s = if (rating == 1) {
                    FsrsScheduler.forgetStability(d, s, r, weights)
                } else {
                    FsrsScheduler.recallStability(d, s, r, rating, weights)
                }
                val rawNextIvl = FsrsScheduler.nextIntervalDays(s, desiredRetention)
                val adjustedIvl = adjustIntervalForEasyDays(
                    rawDays = rawNextIvl,
                    now = now + day * 86_400_000L,
                    easyDaysMask = easyDaysMask
                )
                lastDay = day
                day += max(1, adjustedIvl)
            }
        }

        for (c in cards) {
            if (c.suspended) continue
            val daysUntilDue = ((c.dueAt - now) / 86_400_000L).toInt()
            if (c.intervalDays == 0 && c.repetitions == 0) {
                val introDay = (newQueued / newPerDay.coerceAtLeast(1)).coerceIn(0, 89)
                val s0 = FsrsScheduler.initialStability(3, weights)
                val d0 = FsrsScheduler.initialDifficulty(3, weights)
                scheduleCardTrajectory(introDay, s0, d0)
                newQueued++
            } else {
                val firstDue = daysUntilDue.coerceIn(0, 89)
                val s = if (c.fsrsStability > 0.0) c.fsrsStability else max(1.0, c.intervalDays.toDouble())
                val d = if (c.fsrsDifficulty > 0.0) c.fsrsDifficulty else FsrsScheduler.seededDifficultyFromEase(c.easeFactor)
                scheduleCardTrajectory(firstDue, s, d)
            }
        }
        return dailyCounts.mapIndexed { idx, count -> idx to count }
    }
}
