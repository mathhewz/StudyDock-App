package com.studydock.app.data

import android.content.Context
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MATH_ELO_PREFS = "studydock_math_elo"

/**
 * Motor Elo adaptativo por tópico da Arena de Exatas.
 * Ajusta automaticamente a dificuldade recomendada (BASIC / INTERMEDIATE / ADVANCED)
 * conforme o desempenho real do estudante em cada frente.
 */
object MathEloEngine {
    const val DEFAULT_ELO = 1200
    private const val K_FACTOR = 24.0

    fun difficultyRating(difficulty: MathDifficulty): Int = when (difficulty) {
        MathDifficulty.BASIC -> 1050
        MathDifficulty.INTERMEDIATE -> 1250
        MathDifficulty.ADVANCED -> 1450
    }

    fun recommendedDifficulty(elo: Int): MathDifficulty = when {
        elo < 1120 -> MathDifficulty.BASIC
        elo < 1350 -> MathDifficulty.INTERMEDIATE
        else -> MathDifficulty.ADVANCED
    }

    fun updateElo(currentElo: Int, difficulty: MathDifficulty, correct: Boolean): Int {
        val opponent = difficultyRating(difficulty).toDouble()
        val expected = 1.0 / (1.0 + 10.0.pow((opponent - currentElo.toDouble()) / 400.0))
        val actual = if (correct) 1.0 else 0.0
        return (currentElo + K_FACTOR * (actual - expected)).roundToInt().coerceIn(800, 2200)
    }

    fun getTopicElo(context: Context, topic: MathTopic): Int =
        context.getSharedPreferences(MATH_ELO_PREFS, Context.MODE_PRIVATE)
            .getInt("elo_${topic.name}", DEFAULT_ELO)

    fun recordAttempt(context: Context, topic: MathTopic, difficulty: MathDifficulty, correct: Boolean): Int {
        val prefs = context.getSharedPreferences(MATH_ELO_PREFS, Context.MODE_PRIVATE)
        val key = "elo_${topic.name}"
        val current = prefs.getInt(key, DEFAULT_ELO)
        val next = updateElo(current, difficulty, correct)
        prefs.edit().putInt(key, next).apply()
        return next
    }
}
