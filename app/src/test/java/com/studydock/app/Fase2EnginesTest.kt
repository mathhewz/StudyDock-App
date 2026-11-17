package com.studydock.app

import com.studydock.app.data.CardReview
import com.studydock.app.data.DeckQueue
import com.studydock.app.data.Flashcard
import com.studydock.app.data.FsrsOptimizer
import com.studydock.app.data.Habit
import com.studydock.app.data.HabitCompletion
import com.studydock.app.data.StudyAnalyticsEngine
import com.studydock.app.data.StudySession
import com.studydock.app.data.adjustIntervalForEasyDays
import com.studydock.app.timer.StudySessionManager
import com.studydock.app.ui.habitStudioCurrentStreak
import com.studydock.app.ui.habitStudioExponentialScore
import com.studydock.app.ui.habitStudioKeyFromLocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class Fase2EnginesTest {

    @Test
    fun fsrsOptimizerWeightsRoundtripAnd90DaySimulation() {
        val serialized = FsrsOptimizer.serializeWeights(FsrsOptimizer.DEFAULT_WEIGHTS)
        val parsed = FsrsOptimizer.parseWeights(serialized)
        assertNotNull(parsed)
        assertEquals(19, parsed!!.size)
        assertEquals(FsrsOptimizer.DEFAULT_WEIGHTS[0], parsed[0], 0.001)

        val now = System.currentTimeMillis()
        val reviews = (1..25).map { idx ->
            CardReview(
                id = idx.toLong(),
                cardId = idx.toLong(),
                reviewedAt = now - idx * 86_400_000L,
                grade = if (idx % 5 == 0) 0 else 2,
                intervalDays = idx + 1,
                priorIntervalDays = idx,
                priorState = "REVIEW"
            )
        }
        val cal = FsrsOptimizer.calibrate(reviews, currentDesiredRetention = 0.90)
        assertEquals(25, cal.sampleCount)
        assertEquals(19, cal.optimizedWeights.size)

        val cards = (1..30).map { i ->
            Flashcard(id = i.toLong(), front = "Q$i", back = "A$i", dueAt = now + (i % 5) * 86_400_000L, intervalDays = if (i % 2 == 0) 3 else 0, repetitions = if (i % 2 == 0) 2 else 0)
        }
        val sim = FsrsOptimizer.simulate90Days(cards, newPerDay = 10, desiredRetention = 0.90, easyDaysMask = 1 shl 6)
        assertEquals(90, sim.size)
        assertTrue(sim.sumOf { it.second } > 0)
    }

    @Test
    fun siblingBuryingAndDisplayOrderAndEasyDays() {
        val c1 = Flashcard(id = 1L, front = "F1", back = "B1", authorNoteId = 99L, intervalDays = 0, repetitions = 0)
        val c2 = Flashcard(id = 2L, front = "F2", back = "B2", authorNoteId = 99L, intervalDays = 5, repetitions = 2)
        val c3 = Flashcard(id = 3L, front = "F3", back = "B3", authorNoteId = 100L, intervalDays = 4, repetitions = 2)

        val remaining = DeckQueue.filterSiblingsAfterReview(c1, listOf(c1, c2, c3), burySiblings = true)
        assertEquals(listOf(1L, 3L), remaining.map { it.id })

        val newFirst = DeckQueue.applyDisplayOrder(listOf(c2, c1, c3), "NEW_FIRST")
        assertEquals(1L, newFirst.first().id)

        val reviewFirst = DeckQueue.applyDisplayOrder(listOf(c1, c2, c3), "REVIEW_FIRST")
        assertTrue(reviewFirst.first().id in listOf(2L, 3L))

        val zone = ZoneId.of("UTC")
        // Fixed Monday 2026-11-16T12:00:00Z -> +6 days = Sunday (bit 6)
        val mondayNoon = LocalDate.of(2026, 11, 16).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val adjusted = adjustIntervalForEasyDays(rawDays = 6, now = mondayNoon, easyDaysMask = 1 shl 6, zone = zone)
        assertTrue(adjusted != 6)
    }

    @Test
    fun flowmodoroAnd24hGridAndGhostPacer() {
        assertEquals(60L, StudySessionManager.flowmodoroEarnedBreakSeconds(120L))
        assertEquals(600L, StudySessionManager.flowmodoroEarnedBreakSeconds(3000L))

        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 11, 16)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val sToday = StudySession(
            id = 1L,
            subjectId = 7L,
            startedAt = dayStart + 60 * 60_000L, // 01:00
            endedAt = dayStart + 90 * 60_000L,   // 01:30
            durationSeconds = 1800L,
            isActive = false
        )
        val grid = StudyAnalyticsEngine.compute24hTenMinGrid(listOf(sToday), date = today, zone = zone)
        assertEquals(144, grid.size)
        assertEquals(7L, grid[6]) // 01:00 -> slot 6
        assertEquals(7L, grid[8]) // 01:20..01:30 -> slot 8

        val yesterday = today.minusDays(1L)
        val yStart = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        val sYest = StudySession(
            id = 2L,
            subjectId = 7L,
            startedAt = yStart + 60 * 60_000L,
            endedAt = yStart + 90 * 60_000L,
            durationSeconds = 1800L,
            isActive = false
        )
        val ghost = StudyAnalyticsEngine.computeGhostPacerSeconds(listOf(sYest), currentMinuteOfDay = 120, today = today, zone = zone)
        assertEquals(1800L, ghost)
    }

    @Test
    fun habitExponentialScoreAndSkippedNeutralStreak() {
        val today = LocalDate.now()
        val d2 = today.minusDays(2)
        val d1 = today.minusDays(1)
        val startMs = d2.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val habit = Habit(id = 1L, title = "Estudar", createdAt = startMs, startedAt = startMs, targetValue = 2.0, miniTarget = 1.0)
        val logs = listOf(
            HabitCompletion(habitId = 1L, dayKey = habitStudioKeyFromLocalDate(d2), status = "DONE", value = 2.0),
            HabitCompletion(habitId = 1L, dayKey = habitStudioKeyFromLocalDate(d1), status = "SKIPPED", value = 0.0),
            HabitCompletion(habitId = 1L, dayKey = habitStudioKeyFromLocalDate(today), status = "PARTIAL", value = 1.0)
        )
        val streak = habitStudioCurrentStreak(habit, logs, habitStudioKeyFromLocalDate(today))
        assertEquals(2, streak)

        val score = habitStudioExponentialScore(habit, logs, habitStudioKeyFromLocalDate(today))
        assertTrue(score > 0)
    }
}
