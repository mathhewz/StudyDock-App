package com.studydock.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StudyAnalyticsTest {

    private val saoPaulo: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun epochAt(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = saoPaulo): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun session(
        id: Long = 0L,
        subjectId: Long? = 1L,
        startedAt: Long,
        durationSeconds: Long,
        breakSeconds: Long = 0L,
        isActive: Boolean = false
    ): StudySession = StudySession(
        id = id,
        subjectId = subjectId,
        startedAt = startedAt,
        endedAt = startedAt + durationSeconds.coerceAtLeast(0L) * 1000L,
        durationSeconds = durationSeconds,
        isActive = isActive,
        breakSeconds = breakSeconds
    )

    @Test
    fun resolveDailyGoalHandlesActiveArchivedNegativeAndManualFallback() {
        val activeSubjects = listOf(
            Subject(id = 1L, name = "Matemática", targetMinutesPerDay = 60, archived = false),
            Subject(id = 2L, name = "Física", targetMinutesPerDay = 90, archived = false),
            Subject(id = 3L, name = "Química", targetMinutesPerDay = 120, archived = true),
            Subject(id = 4L, name = "Inválida", targetMinutesPerDay = -30, archived = false)
        )

        assertEquals(150, StudyAnalyticsEngine.resolveDailyGoalMinutes(activeSubjects, manualMinutes = 180))
        assertEquals(150L * 60L, resolveDailyGoalSeconds(activeSubjects, manualMinutes = 180))

        // forceManual ignores active subjects' sum
        assertEquals(240, StudyAnalyticsEngine.resolveDailyGoalMinutes(activeSubjects, manualMinutes = 240, forceManual = true))
        assertEquals(240L * 60L, StudyAnalyticsEngine.resolveDailyGoalSeconds(activeSubjects, manualMinutes = 240, forceManual = true))

        // Empty or zero-target subjects fall back to manualMinutes (or 180 if manualMinutes <= 0)
        assertEquals(120, StudyAnalyticsEngine.resolveDailyGoalMinutes(emptyList(), manualMinutes = 120))
        assertEquals(180, StudyAnalyticsEngine.resolveDailyGoalMinutes(emptyList(), manualMinutes = 0))
        assertEquals(180, StudyAnalyticsEngine.resolveDailyGoalMinutes(emptyList(), manualMinutes = -45))
        assertEquals(
            180,
            StudyAnalyticsEngine.resolveDailyGoalMinutes(
                listOf(Subject(id = 1L, name = "Archived", targetMinutesPerDay = 90, archived = true)),
                manualMinutes = 0
            )
        )
    }

    @Test
    fun computeStreaksHandlesEmptyTodayYesterdayGapsAndMidnightRollover() {
        val today = LocalDate.of(2026, 11, 13)

        assertEquals(0 to 0, StudyAnalyticsEngine.computeStreaks(emptyList(), today = today, zone = saoPaulo))

        // Active, zero, and negative duration sessions must not count toward streaks
        val nonCounting = listOf(
            session(startedAt = epochAt(today, 10), durationSeconds = 0L),
            session(startedAt = epochAt(today, 11), durationSeconds = -300L),
            session(startedAt = epochAt(today, 12), durationSeconds = 1800L, isActive = true)
        )
        assertEquals(0 to 0, StudyAnalyticsEngine.computeStreaks(nonCounting, today = today, zone = saoPaulo))

        // Studied yesterday and two days before, NOT today -> streak still alive at 3
        val aliveFromYesterday = listOf(
            session(startedAt = epochAt(today.minusDays(1), 22), durationSeconds = 1800L),
            session(startedAt = epochAt(today.minusDays(2), 15), durationSeconds = 1200L),
            session(startedAt = epochAt(today.minusDays(2), 18), durationSeconds = 900L), // duplicate same day
            session(startedAt = epochAt(today.minusDays(3), 8), durationSeconds = 600L)
        )
        assertEquals(3 to 3, StudyAnalyticsEngine.computeStreaks(aliveFromYesterday, today = today, zone = saoPaulo))

        // Gap yesterday (only 2 days ago) breaks current streak while preserving best streak
        val brokenStreak = listOf(
            session(startedAt = epochAt(today.minusDays(2), 10), durationSeconds = 1000L),
            session(startedAt = epochAt(today.minusDays(3), 10), durationSeconds = 1000L),
            session(startedAt = epochAt(today.minusDays(4), 10), durationSeconds = 1000L),
            session(startedAt = epochAt(today.minusDays(5), 10), durationSeconds = 1000L)
        )
        assertEquals(0 to 4, StudyAnalyticsEngine.computeStreaks(brokenStreak, today = today, zone = saoPaulo))

        // Day rollover in America/Sao_Paulo (UTC-3): 23:55 and 00:05 next local day are distinct local days
        val rolloverSessions = listOf(
            session(startedAt = epochAt(today.minusDays(1), 23, 55, saoPaulo), durationSeconds = 300L),
            session(startedAt = epochAt(today, 0, 5, saoPaulo), durationSeconds = 300L)
        )
        assertEquals(2 to 2, StudyAnalyticsEngine.computeStreaks(rolloverSessions, today = today, zone = saoPaulo))
        // In UTC, 23:55 BRT (-03) is 02:55 UTC of `today`, and 00:05 BRT (-03) is 03:05 UTC of `today` -> same UTC day!
        assertEquals(1 to 1, StudyAnalyticsEngine.computeStreaks(rolloverSessions, today = today, zone = utc))
    }

    @Test
    fun computeSummaryAndWeeklyMonthlyAggregationsAreAccurateWithEmptyAndEdgeInputs() {
        val today = LocalDate.of(2026, 11, 13)

        // Empty list & daysInPeriod <= 0 guard (no division by zero)
        val emptySummary = StudyAnalyticsEngine.computeSummary(
            currentSessions = emptyList(),
            allSessions = emptyList(),
            daysInPeriod = 0,
            dailyGoalSeconds = 3600L,
            today = today,
            zone = saoPaulo
        )
        assertEquals(0L, emptySummary.totalFocusSeconds)
        assertEquals(0L, emptySummary.totalBreakSeconds)
        assertEquals(0, emptySummary.sessionCount)
        assertEquals(0, emptySummary.activeDaysCount)
        assertEquals(0L, emptySummary.dailyAverageSeconds)
        assertEquals(0L, emptySummary.sessionAverageSeconds)
        assertEquals(0L, emptySummary.longestSessionSeconds)
        assertEquals(0, emptySummary.goalsAchievedDaysCount)
        assertEquals(0f, emptySummary.activeDaysPercentage, 1e-6f)

        // Weekly vs previous week filtering
        val sessions = listOf(
            // Current 7-day window: today, -2d, -6d (boundary included)
            session(id = 1, subjectId = 10L, startedAt = epochAt(today, 9, 0), durationSeconds = 3600L, breakSeconds = 300L),
            session(id = 2, subjectId = 10L, startedAt = epochAt(today, 14, 0), durationSeconds = 1800L, breakSeconds = 120L),
            session(id = 3, subjectId = 20L, startedAt = epochAt(today.minusDays(2), 19, 30), durationSeconds = 7200L, breakSeconds = 600L),
            session(id = 4, subjectId = 20L, startedAt = epochAt(today.minusDays(6), 0, 1), durationSeconds = 1200L),
            // Ignored noise inside current window
            session(id = 5, subjectId = 10L, startedAt = epochAt(today, 16, 0), durationSeconds = 0L),
            session(id = 6, subjectId = 10L, startedAt = epochAt(today, 17, 0), durationSeconds = -600L),
            session(id = 7, subjectId = 10L, startedAt = epochAt(today, 18, 0), durationSeconds = 5000L, isActive = true),
            // Previous 7-day window: -7d (boundary of previous), -13d
            session(id = 8, subjectId = 10L, startedAt = epochAt(today.minusDays(7), 23, 59), durationSeconds = 2400L),
            session(id = 9, subjectId = 20L, startedAt = epochAt(today.minusDays(13), 8, 0), durationSeconds = 1600L),
            // Older than 14 days (excluded from both 7-day windows, included in 30-day window)
            session(id = 10, subjectId = 10L, startedAt = epochAt(today.minusDays(25), 11, 0), durationSeconds = 4000L)
        )

        val (curr7, prev7) = StudyAnalyticsEngine.filterSessionsInPeriod(sessions, daysBack = 7, today = today, zone = saoPaulo)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), curr7.map { it.id }.toSet())
        assertEquals(setOf(8L, 9L), prev7.map { it.id }.toSet())

        val subjects = listOf(
            Subject(id = 10L, name = "A", targetMinutesPerDay = 60),
            Subject(id = 20L, name = "B", targetMinutesPerDay = 30)
        )
        // Resolved goal = 90 min = 5400s -> today (5400s) and -2d (7200s) achieve goal, -6d (1200s) does not
        val summary7 = StudyAnalyticsEngine.computeSummaryForSubjects(
            currentSessions = curr7,
            allSessions = sessions,
            daysInPeriod = 7,
            subjects = subjects,
            today = today,
            zone = saoPaulo
        )
        assertEquals(13800L, summary7.totalFocusSeconds)
        assertEquals(1020L, summary7.totalBreakSeconds)
        assertEquals(4, summary7.sessionCount)
        assertEquals(3, summary7.activeDaysCount)
        assertEquals(4600L, summary7.dailyAverageSeconds)
        assertEquals(3450L, summary7.sessionAverageSeconds)
        assertEquals(7200L, summary7.longestSessionSeconds)
        assertEquals(2, summary7.goalsAchievedDaysCount)
        assertEquals(1, summary7.currentStreakDays)
        // Session #4 (-6d) and #8 (-7d) form a 2-day best streak across allSessions
        assertEquals(2, summary7.bestStreakDays)
        assertEquals(3f / 7f, summary7.activeDaysPercentage, 1e-4f)

        // Monthly (30-day) window includes session #10
        val (curr30, prev30) = StudyAnalyticsEngine.filterSessionsInPeriod(sessions, daysBack = 30, today = today, zone = saoPaulo)
        assertTrue(curr30.any { it.id == 10L })
        assertTrue(prev30.isEmpty())
        val summary30 = StudyAnalyticsEngine.computeSummary(curr30, sessions, daysInPeriod = 30, dailyGoalSeconds = 3600L, today = today, zone = saoPaulo)
        assertEquals(21800L, summary30.totalFocusSeconds)
        assertEquals(6, summary30.activeDaysCount)

        // Period comparison: current = 13800, previous = 4000 -> +245%
        val cmp = StudyAnalyticsEngine.computePeriodComparison(curr7, prev7)
        assertEquals(13800L, cmp.currentTotalSeconds)
        assertEquals(4000L, cmp.previousTotalSeconds)
        assertEquals(9800L, cmp.diffSeconds)
        assertTrue(cmp.isPositive)
        assertEquals(245f, cmp.percentageDiff, 0.1f)
    }

    @Test
    fun dailyBarsHeatmapDistributionsAndPeriodComparisonHandleBoundariesAndNegatives() {
        val today = LocalDate.of(2026, 11, 13)
        val sessions = listOf(
            session(subjectId = 1L, startedAt = epochAt(today, 5, 59), durationSeconds = 1800L),   // night (<1h)
            session(subjectId = 1L, startedAt = epochAt(today.minusDays(1), 6, 0), durationSeconds = 3600L), // morning (1h)
            session(subjectId = 2L, startedAt = epochAt(today.minusDays(2), 12, 0), durationSeconds = 7200L), // afternoon (2h)
            session(subjectId = null, startedAt = epochAt(today.minusDays(3), 18, 0), durationSeconds = 14400L), // evening (4h)
            // Zero/negative/active must never pull totals negative or increase session counts
            session(subjectId = 1L, startedAt = epochAt(today.minusDays(4), 10, 0), durationSeconds = -900L),
            session(subjectId = 1L, startedAt = epochAt(today.minusDays(4), 11, 0), durationSeconds = 0L),
            session(subjectId = 1L, startedAt = epochAt(today.minusDays(4), 12, 0), durationSeconds = 5000L, isActive = true)
        )

        val bars = StudyAnalyticsEngine.computeDailyBarData(sessions, daysBack = 5, today = today, zone = saoPaulo)
        assertEquals(5, bars.size)
        assertEquals(today.minusDays(4) to 0L, bars[0])
        assertEquals(today.minusDays(3) to 14400L, bars[1])
        assertEquals(today.minusDays(2) to 7200L, bars[2])
        assertEquals(today.minusDays(1) to 3600L, bars[3])
        assertEquals(today to 1800L, bars[4])

        val heatmap = StudyAnalyticsEngine.computeHeatmap(sessions, weeksCount = 1, today = today, zone = saoPaulo)
        assertEquals(7, heatmap.size)
        val byDate = heatmap.associateBy { it.date }
        assertEquals(0, byDate[today.minusDays(4)]!!.intensity)
        assertEquals(0L, byDate[today.minusDays(4)]!!.focusSeconds)
        assertEquals(4, byDate[today.minusDays(3)]!!.intensity) // 4h+
        assertEquals(3, byDate[today.minusDays(2)]!!.intensity) // 2h-4h
        assertEquals(2, byDate[today.minusDays(1)]!!.intensity) // 1h-2h
        assertEquals(1, byDate[today]!!.intensity)              // <1h

        val turn = StudyAnalyticsEngine.computeTimeOfDayDistribution(sessions, zone = saoPaulo)
        assertEquals(1800L, turn.nightSeconds)
        assertEquals(3600L, turn.morningSeconds)
        assertEquals(7200L, turn.afternoonSeconds)
        assertEquals(14400L, turn.eveningSeconds)
        assertEquals(27000L, turn.total)
        assertEquals("Noite", turn.mostProductiveTurn)

        assertEquals("Nenhum", StudyAnalyticsEngine.computeTimeOfDayDistribution(emptyList(), saoPaulo).mostProductiveTurn)

        val subjects = listOf(
            Subject(id = 1L, name = "Cálculo", color = 0xFF112233),
            Subject(id = 2L, name = "Física", color = 0xFF445566)
        )
        val dist = StudyAnalyticsEngine.computeSubjectDistributions(sessions, subjects)
        assertEquals(3, dist.size)
        assertEquals(-1L, dist[0].subjectId)
        assertEquals("Sem Matéria", dist[0].name)
        assertEquals(14400L, dist[0].focusSeconds)
        assertEquals(2L, dist[1].subjectId)
        assertEquals(1L, dist[2].subjectId)
        assertEquals(5400L, dist[2].focusSeconds)
        assertEquals(1f, dist.sumOf { it.percentage.toDouble() }.toFloat(), 1e-3f)

        // Period comparison with empty previous -> 100% if current > 0, else 0%
        val zeroPrevCmp = StudyAnalyticsEngine.computePeriodComparison(
            listOf(session(startedAt = 1000L, durationSeconds = 1200L)),
            emptyList()
        )
        assertEquals(100f, zeroPrevCmp.percentageDiff, 1e-4f)
        assertTrue(zeroPrevCmp.isPositive)

        val bothEmptyCmp = StudyAnalyticsEngine.computePeriodComparison(emptyList(), emptyList())
        assertEquals(0L, bothEmptyCmp.diffSeconds)
        assertEquals(0f, bothEmptyCmp.percentageDiff, 1e-4f)
        assertTrue(bothEmptyCmp.isPositive)

        val dropCmp = StudyAnalyticsEngine.computePeriodComparison(
            emptyList(),
            listOf(session(startedAt = 1000L, durationSeconds = 1200L))
        )
        assertEquals(-1200L, dropCmp.diffSeconds)
        assertEquals(-100f, dropCmp.percentageDiff, 1e-4f)
        assertFalse(dropCmp.isPositive)
    }
}
