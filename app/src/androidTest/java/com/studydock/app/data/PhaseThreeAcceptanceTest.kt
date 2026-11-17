package com.studydock.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.studydock.app.timer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PhaseThreeAcceptanceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "phase-three-${UUID.randomUUID()}.db"
    private var database: StudyDatabase? = null

    private fun openDao(): StudyDao {
        val db = StudyDatabase.openNamed(context, dbName).also { database = it }
        return db.studyDao()
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun databaseVersion22HasNewColumnsAndPersistsStudySessions() = runBlocking {
        val dao = openDao()
        val subjectId = dao.addSubject(Subject(name = "Física Quântica", color = 0xFF3578B5, targetMinutesPerDay = 90))
        val storedSubject = dao.subjects().first().first { it.id == subjectId }
        assertEquals(90, storedSubject.targetMinutesPerDay)

        val session = StudySession(
            subjectId = subjectId,
            startedAt = System.currentTimeMillis() - 3600_000L,
            endedAt = System.currentTimeMillis(),
            durationSeconds = 3000L,
            isActive = false,
            topic = "Princípio da Incerteza",
            mode = "POMODORO",
            breakSeconds = 600L
        )

        val sessionId = dao.addSession(session)
        val stored = dao.sessions().first().first { it.id == sessionId }

        assertEquals(subjectId, stored.subjectId)
        assertEquals(3000L, stored.durationSeconds)
        assertEquals(600L, stored.breakSeconds)
        assertEquals("Princípio da Incerteza", stored.topic)
        assertEquals("POMODORO", stored.mode)
        assertFalse(stored.isActive)
    }

    @Test
    fun sessionManagerTracksFocusAndBreakSeparately() = runBlocking {
        val dao = openDao()
        val subjectId = dao.addSubject(Subject(name = "Direito Constitucional"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val manager = StudySessionManager.get(context, dao, scope)
        manager.resetOrDiscard()

        manager.setSubject(subjectId)
        manager.setTopic("Controle de Constitucionalidade")
        manager.setMode(StudyTimerMode.STOPWATCH)

        // 1. Start focusing
        manager.startOrResume()
        assertEquals(TimerState.RUNNING, manager.snapshot.value.state)
        assertEquals(subjectId, manager.snapshot.value.subjectId)
        assertEquals("Controle de Constitucionalidade", manager.snapshot.value.topic)

        delay(1200)

        // 2. Pause: Focus stops, break accumulates
        manager.pause()
        assertEquals(TimerState.PAUSED, manager.snapshot.value.state)
        val focusAtPause = manager.snapshot.value.elapsedFocusSeconds
        assertTrue("Deveria ter acumulado pelo menos 1s de foco: $focusAtPause", focusAtPause >= 1L)

        delay(1200)

        // Focus should remain unchanged while paused
        assertEquals(focusAtPause, manager.snapshot.value.elapsedFocusSeconds)
        val breakAtPause = manager.snapshot.value.elapsedBreakSeconds
        assertTrue("Deveria ter acumulado pelo menos 1s de pausa: $breakAtPause", breakAtPause >= 1L)

        // 3. Resume focusing
        manager.startOrResume()
        assertEquals(TimerState.RUNNING, manager.snapshot.value.state)

        delay(1200)
        assertTrue(manager.snapshot.value.elapsedFocusSeconds > focusAtPause)

        // 4. Save and finish
        val saved = manager.saveAndFinish()
        assertNotNull(saved)
        assertEquals(TimerState.IDLE, manager.snapshot.value.state)

        val stored = dao.sessions().first().first { it.id == saved!!.id }
        assertFalse(stored.isActive)
        assertEquals(subjectId, stored.subjectId)
        assertEquals("Controle de Constitucionalidade", stored.topic)
        assertEquals("STOPWATCH", stored.mode)
        assertTrue(stored.durationSeconds >= 2L)
        assertTrue(stored.breakSeconds >= 1L)

        scope.cancel()
    }

    @Test
    fun countdownTimerReachesTargetAndStops() = runBlocking {
        val dao = openDao()
        val subjectId = dao.addSubject(Subject(name = "Matemática"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val manager = StudySessionManager.get(context, dao, scope)
        manager.resetOrDiscard()

        manager.setSubject(subjectId)
        manager.setMode(StudyTimerMode.COUNTDOWN)
        manager.setCountdownTarget(2L) // 2 seconds

        manager.startOrResume()
        assertEquals(TimerState.RUNNING, manager.snapshot.value.state)

        // Wait for countdown to finish (2.5s)
        delay(2600)

        assertEquals(TimerState.COMPLETED, manager.snapshot.value.state)
        assertEquals(2L, manager.snapshot.value.elapsedFocusSeconds)

        val saved = manager.saveAndFinish()
        assertNotNull(saved)
        assertEquals("COUNTDOWN", saved!!.mode)
        assertEquals(2L, saved.durationSeconds)

        scope.cancel()
    }

    @Test
    fun discardSessionDoesNotLeaveOrphanActiveRecords() = runBlocking {
        val dao = openDao()
        val subjectId = dao.addSubject(Subject(name = "História"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val manager = StudySessionManager.get(context, dao, scope)
        manager.resetOrDiscard()

        manager.setSubject(subjectId)
        manager.startOrResume()
        delay(600)

        manager.resetOrDiscard()
        delay(200)

        assertEquals(TimerState.IDLE, manager.snapshot.value.state)
        assertEquals(0L, manager.snapshot.value.elapsedFocusSeconds)

        val activeInDb = dao.activeSession()
        assertNull("Nenhuma sessão ativa deveria restar no banco após descarte", activeInDb)

        scope.cancel()
    }

    @Test
    fun analyticsCalculatesSummaryAndComparisonCorrectly() {
        val zone = java.time.ZoneId.of("America/Sao_Paulo")
        val today = java.time.LocalDate.of(2026, 11, 12)

        val currentDayEpoch = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val yesterdayEpoch = today.minusDays(1).atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val prevPeriodEpoch = today.minusDays(10).atTime(16, 0).atZone(zone).toInstant().toEpochMilli()

        val sessions = listOf(
            StudySession(id = 1, subjectId = 10, startedAt = currentDayEpoch, endedAt = currentDayEpoch + 3600_000L, durationSeconds = 3600L, breakSeconds = 300L),
            StudySession(id = 2, subjectId = 10, startedAt = currentDayEpoch + 7200_000L, endedAt = currentDayEpoch + 9000_000L, durationSeconds = 1800L, breakSeconds = 0L),
            StudySession(id = 3, subjectId = 20, startedAt = yesterdayEpoch, endedAt = yesterdayEpoch + 7200_000L, durationSeconds = 7200L, breakSeconds = 600L),
            StudySession(id = 4, subjectId = 20, startedAt = prevPeriodEpoch, endedAt = prevPeriodEpoch + 3600_000L, durationSeconds = 3600L, breakSeconds = 0L)
        )

        // Filter last 7 days
        val (current, previous) = StudyAnalyticsEngine.filterSessionsInPeriod(sessions, daysBack = 7, today = today, zone = zone)
        assertEquals(3, current.size) // Sessions 1, 2, 3
        assertEquals(1, previous.size) // Session 4

        val summary = StudyAnalyticsEngine.computeSummary(current, sessions, daysInPeriod = 7, dailyGoalSeconds = 5000L, today = today, zone = zone)
        assertEquals(12600L, summary.totalFocusSeconds) // 3600 + 1800 + 7200
        assertEquals(900L, summary.totalBreakSeconds)   // 300 + 0 + 600
        assertEquals(3, summary.sessionCount)
        assertEquals(2, summary.activeDaysCount) // today and yesterday
        assertEquals(6300L, summary.dailyAverageSeconds) // 12600 / 2
        assertEquals(4200L, summary.sessionAverageSeconds) // 12600 / 3
        assertEquals(7200L, summary.longestSessionSeconds)
        assertEquals(2, summary.goalsAchievedDaysCount) // both days exceeded 5000s

        val comparison = StudyAnalyticsEngine.computePeriodComparison(current, previous)
        assertEquals(12600L, comparison.currentTotalSeconds)
        assertEquals(3600L, comparison.previousTotalSeconds)
        assertTrue(comparison.isPositive)
        assertEquals(250f, comparison.percentageDiff, 0.1f) // ((12600 - 3600) / 3600) * 100 = 250%
    }

    @Test
    fun analyticsStreakCalculationHandlesGapsAndRecords() {
        val zone = java.time.ZoneId.of("America/Sao_Paulo")
        val today = java.time.LocalDate.of(2026, 11, 12)

        fun sessionAt(day: java.time.LocalDate): StudySession {
            val epoch = day.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
            return StudySession(id = 0, subjectId = 1, startedAt = epoch, endedAt = epoch + 3600_000L, durationSeconds = 3600L)
        }

        // Streak 1: Today, yesterday, day before yesterday (3 days)
        // Gap: 3 days ago missing
        // Streak 2: 4, 5, 6, 7, 8 days ago (5 days) -> record!
        val sessions = listOf(
            sessionAt(today),
            sessionAt(today.minusDays(1)),
            sessionAt(today.minusDays(2)),
            // gap at minusDays(3)
            sessionAt(today.minusDays(4)),
            sessionAt(today.minusDays(5)),
            sessionAt(today.minusDays(6)),
            sessionAt(today.minusDays(7)),
            sessionAt(today.minusDays(8))
        )

        val (currentStreak, bestStreak) = StudyAnalyticsEngine.computeStreaks(sessions, today = today, zone = zone)
        assertEquals(3, currentStreak)
        assertEquals(5, bestStreak)
    }

    @Test
    fun analyticsSubjectDistributionCalculatesCorrectPercentages() {
        val subjects = listOf(
            Subject(id = 1, name = "Cálculo I", color = 0xFF112233),
            Subject(id = 2, name = "Estrutura de Dados", color = 0xFF445566)
        )

        val sessions = listOf(
            StudySession(subjectId = 1, startedAt = 1000, endedAt = 4600, durationSeconds = 3600L), // 1 hour
            StudySession(subjectId = 1, startedAt = 5000, endedAt = 8600, durationSeconds = 3600L), // 1 hour -> total 2h (66.6%)
            StudySession(subjectId = 2, startedAt = 9000, endedAt = 12600, durationSeconds = 3600L) // 1 hour -> total 1h (33.3%)
        )

        val dist = StudyAnalyticsEngine.computeSubjectDistributions(sessions, subjects)
        assertEquals(2, dist.size)
        assertEquals(1L, dist[0].subjectId)
        assertEquals("Cálculo I", dist[0].name)
        assertEquals(7200L, dist[0].focusSeconds)
        assertEquals(2, dist[0].sessionCount)
        assertEquals(0.666f, dist[0].percentage, 0.01f)

        assertEquals(2L, dist[1].subjectId)
        assertEquals("Estrutura de Dados", dist[1].name)
        assertEquals(3600L, dist[1].focusSeconds)
        assertEquals(1, dist[1].sessionCount)
        assertEquals(0.333f, dist[1].percentage, 0.01f)
    }

    @Test
    fun analyticsHeatmapProducesAccurateIntensityLevels() {
        val zone = java.time.ZoneId.of("America/Sao_Paulo")
        val today = java.time.LocalDate.of(2026, 11, 12)

        fun session(day: java.time.LocalDate, duration: Long): StudySession {
            val epoch = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            return StudySession(subjectId = 1, startedAt = epoch, endedAt = epoch + duration * 1000L, durationSeconds = duration)
        }

        val sessions = listOf(
            session(today, 0L),                  // intensity 0
            session(today.minusDays(1), 1800L),  // 30 min -> intensity 1 (<1h)
            session(today.minusDays(2), 5400L),  // 1.5h -> intensity 2 (1h-2h)
            session(today.minusDays(3), 10800L), // 3h -> intensity 3 (2h-4h)
            session(today.minusDays(4), 18000L)  // 5h -> intensity 4 (4h+)
        )

        val heatmap = StudyAnalyticsEngine.computeHeatmap(sessions, weeksCount = 2, today = today, zone = zone)
        val mapByDate = heatmap.associateBy { it.date }

        assertEquals(0, mapByDate[today]!!.intensity)
        assertEquals(1, mapByDate[today.minusDays(1)]!!.intensity)
        assertEquals(2, mapByDate[today.minusDays(2)]!!.intensity)
        assertEquals(3, mapByDate[today.minusDays(3)]!!.intensity)
        assertEquals(4, mapByDate[today.minusDays(4)]!!.intensity)
    }
}
