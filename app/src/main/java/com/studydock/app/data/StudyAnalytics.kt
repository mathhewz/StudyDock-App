package com.studydock.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StudySummary(
    val totalFocusSeconds: Long,
    val totalBreakSeconds: Long,
    val sessionCount: Int,
    val activeDaysCount: Int,
    val totalDaysInPeriod: Int,
    val dailyAverageSeconds: Long,
    val sessionAverageSeconds: Long,
    val longestSessionSeconds: Long,
    val goalsAchievedDaysCount: Int,
    val currentStreakDays: Int,
    val bestStreakDays: Int,
    val activeDaysPercentage: Float
)

data class SubjectDistribution(
    val subjectId: Long,
    val name: String,
    val color: Long,
    val focusSeconds: Long,
    val percentage: Float,
    val sessionCount: Int
)

data class HeatmapDay(
    val date: LocalDate,
    val focusSeconds: Long,
    val breakSeconds: Long,
    val sessionCount: Int,
    val intensity: Int // 0: 0h, 1: <1h, 2: 1h-2h, 3: 2h-4h, 4: 4h+
)

data class PeriodComparison(
    val currentTotalSeconds: Long,
    val previousTotalSeconds: Long,
    val diffSeconds: Long,
    val percentageDiff: Float,
    val isPositive: Boolean
)

data class TimeOfDayDistribution(
    val morningSeconds: Long,   // 06:00 - 11:59
    val afternoonSeconds: Long, // 12:00 - 17:59
    val eveningSeconds: Long,   // 18:00 - 23:59
    val nightSeconds: Long      // 00:00 - 05:59
) {
    val total: Long get() = morningSeconds + afternoonSeconds + eveningSeconds + nightSeconds
    val mostProductiveTurn: String get() {
        val max = maxOf(morningSeconds, afternoonSeconds, eveningSeconds, nightSeconds)
        return when {
            max == 0L -> "Nenhum"
            max == morningSeconds -> "Manhã"
            max == afternoonSeconds -> "Tarde"
            max == eveningSeconds -> "Noite"
            else -> "Madrugada"
        }
    }
}

object StudyAnalyticsEngine {

    /**
     * Resolves the active daily study goal in minutes, summing non-archived subjects'
     * `targetMinutesPerDay` when configured (unless [forceManual] is true) and falling
     * back to [manualMinutes] (default 180).
     */
    fun resolveDailyGoalMinutes(
        subjects: List<Subject>,
        manualMinutes: Int = 180,
        forceManual: Boolean = false
    ): Int {
        var activeSubjectsTarget = 0L
        for (s in subjects) {
            if (!s.archived && s.targetMinutesPerDay > 0) {
                activeSubjectsTarget += s.targetMinutesPerDay
            }
        }
        val fallback = if (manualMinutes > 0) manualMinutes else 180
        return if (!forceManual && activeSubjectsTarget > 0L) {
            activeSubjectsTarget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            fallback
        }
    }

    /**
     * Resolves the active daily study goal in seconds from non-archived [subjects] or [manualMinutes].
     */
    fun resolveDailyGoalSeconds(
        subjects: List<Subject>,
        manualMinutes: Int = 180,
        forceManual: Boolean = false
    ): Long = resolveDailyGoalMinutes(subjects, manualMinutes, forceManual).toLong() * 60L

    fun computeSummaryForSubjects(
        currentSessions: List<StudySession>,
        allSessions: List<StudySession>,
        daysInPeriod: Int,
        subjects: List<Subject>,
        manualMinutes: Int = 180,
        forceManual: Boolean = false,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): StudySummary = computeSummary(
        currentSessions = currentSessions,
        allSessions = allSessions,
        daysInPeriod = daysInPeriod,
        dailyGoalSeconds = resolveDailyGoalSeconds(subjects, manualMinutes, forceManual),
        today = today,
        zone = zone
    )

    fun filterSessionsInPeriod(
        sessions: List<StudySession>,
        daysBack: Int,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<List<StudySession>, List<StudySession>> {
        if (sessions.isEmpty() || daysBack <= 0) return emptyList<StudySession>() to emptyList()

        val days = daysBack.toLong()
        val startOfCurrent = today.minusDays(days - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfCurrent = today.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfPrevious = today.minusDays(days * 2L - 1L).atStartOfDay(zone).toInstant().toEpochMilli()

        val currentSessions = ArrayList<StudySession>()
        val previousSessions = ArrayList<StudySession>()
        for (session in sessions) {
            val startedAt = session.startedAt
            when {
                startedAt >= startOfCurrent && startedAt < endOfCurrent -> currentSessions.add(session)
                startedAt >= startOfPrevious && startedAt < startOfCurrent -> previousSessions.add(session)
            }
        }
        return currentSessions to previousSessions
    }

    fun computeSummary(
        currentSessions: List<StudySession>,
        allSessions: List<StudySession>,
        daysInPeriod: Int,
        dailyGoalSeconds: Long = 4 * 3600L,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): StudySummary {
        var totalFocus = 0L
        var totalBreak = 0L
        var sessionCount = 0
        var longest = 0L
        val focusByDay = HashMap<LocalDate, Long>()

        for (session in currentSessions) {
            if (session.isActive || session.durationSeconds <= 0L) continue
            val duration = session.durationSeconds
            totalFocus += duration
            if (session.breakSeconds > 0L) {
                totalBreak += session.breakSeconds
            }
            sessionCount++
            if (duration > longest) longest = duration
            for ((day, sliceSec) in splitSessionAcrossDays(session, zone)) {
                focusByDay[day] = (focusByDay[day] ?: 0L) + sliceSec
            }
        }

        val activeDays = focusByDay.size
        val dailyAverage = if (activeDays > 0) totalFocus / activeDays else 0L
        val sessionAverage = if (sessionCount > 0) totalFocus / sessionCount else 0L
        val goalsAchieved = if (dailyGoalSeconds > 0L) {
            focusByDay.values.count { it >= dailyGoalSeconds }
        } else {
            0
        }

        val (currentStreak, bestStreak) = computeStreaks(allSessions, today, zone)
        val safeDaysInPeriod = daysInPeriod.coerceAtLeast(0)
        val activePercentage = if (safeDaysInPeriod > 0) {
            (activeDays.toFloat() / safeDaysInPeriod.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        return StudySummary(
            totalFocusSeconds = totalFocus,
            totalBreakSeconds = totalBreak,
            sessionCount = sessionCount,
            activeDaysCount = activeDays,
            totalDaysInPeriod = safeDaysInPeriod,
            dailyAverageSeconds = dailyAverage,
            sessionAverageSeconds = sessionAverage,
            longestSessionSeconds = longest,
            goalsAchievedDaysCount = goalsAchieved,
            currentStreakDays = currentStreak,
            bestStreakDays = bestStreak,
            activeDaysPercentage = activePercentage
        )
    }


    /** Grade 24h em 144 blocos de 10 minutos (retorna subjectId ou 1L se ativo sem matéria, 0L se vazio). */
    fun compute24hTenMinGrid(
        sessions: List<StudySession>,
        date: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): LongArray {
        val grid = LongArray(144)
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
        for (s in sessions) {
            if (s.isActive || s.durationSeconds <= 0L) continue
            val sEnd = if (s.endedAt > s.startedAt) s.endedAt else s.startedAt + s.durationSeconds * 1000L
            if (sEnd <= dayStart || s.startedAt >= dayEnd) continue
            val clampedStart = maxOf(s.startedAt, dayStart)
            val clampedEnd = minOf(sEnd, dayEnd)
            val startSlot = ((clampedStart - dayStart) / 600_000L).toInt().coerceIn(0, 143)
            val endSlot = (((clampedEnd - 1L) - dayStart) / 600_000L).toInt().coerceIn(startSlot, 143)
            val tag = s.subjectId ?: 1L
            for (slot in startSlot..endSlot) {
                grid[slot] = tag
            }
        }
        return grid
    }

    /** Ghost Pacer: média acumulada de foco (segundos) até o minuto atual do dia nos últimos dias ativos. */
    fun computeGhostPacerSeconds(
        sessions: List<StudySession>,
        currentMinuteOfDay: Int,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        if (sessions.isEmpty() || currentMinuteOfDay <= 0) return 0L
        val byDay = HashMap<LocalDate, Long>()
        val cutoffMillis = currentMinuteOfDay.coerceIn(1, 1440) * 60_000L
        for (s in sessions) {
            if (s.isActive || s.durationSeconds <= 0L) continue
            val d = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate()
            if (!d.isBefore(today)) continue
            val dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayCutoff = dayStart + cutoffMillis
            if (s.startedAt >= dayCutoff) continue
            val sEnd = minOf(if (s.endedAt > s.startedAt) s.endedAt else s.startedAt + s.durationSeconds * 1000L, dayCutoff)
            val sec = ((sEnd - s.startedAt) / 1000L).coerceAtLeast(0L)
            byDay[d] = (byDay[d] ?: 0L) + sec
        }
        if (byDay.isEmpty()) return 0L
        return byDay.values.sum() / byDay.size
    }

    private fun splitSessionAcrossDays(
        session: StudySession,
        zone: ZoneId
    ): List<Pair<LocalDate, Long>> {
        val totalSec = session.durationSeconds
        if (totalSec <= 0L) return emptyList()
        val endMs = if (session.endedAt > session.startedAt) session.endedAt else session.startedAt + totalSec * 1000L
        val startMs = session.startedAt
        val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli((endMs - 1L).coerceAtLeast(startMs)).atZone(zone).toLocalDate()
        if (startDate == endDate) return listOf(startDate to totalSec)

        val totalSpanMs = (endMs - startMs).coerceAtLeast(1L).toDouble()
        val out = ArrayList<Pair<LocalDate, Long>>(2)
        var cursorDate = startDate
        var assignedSec = 0L
        while (!cursorDate.isAfter(endDate)) {
            val dayStartMs = cursorDate.atStartOfDay(zone).toInstant().toEpochMilli().coerceAtLeast(startMs)
            val nextDayStartMs = cursorDate.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli().coerceAtMost(endMs)
            val sliceMs = (nextDayStartMs - dayStartMs).coerceAtLeast(0L)
            val sliceSec = if (cursorDate == endDate) {
                (totalSec - assignedSec).coerceAtLeast(0L)
            } else {
                kotlin.math.round(totalSec * (sliceMs.toDouble() / totalSpanMs)).toLong().coerceIn(0L, totalSec - assignedSec)
            }
            if (sliceSec > 0L) {
                out.add(cursorDate to sliceSec)
                assignedSec += sliceSec
            }
            cursorDate = cursorDate.plusDays(1L)
        }
        return out
    }

    fun computeStreaks(
        allSessions: List<StudySession>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<Int, Int> {
        if (allSessions.isEmpty()) return 0 to 0

        val activeDays = HashSet<LocalDate>()
        for (session in allSessions) {
            if (!session.isActive && session.durationSeconds > 0L) {
                for ((day, _) in splitSessionAcrossDays(session, zone)) activeDays.add(day)
            }
        }
        if (activeDays.isEmpty()) return 0 to 0

        // Current Streak
        var currentStreak = 0
        var checkDay = if (today in activeDays) today else today.minusDays(1L)
        while (checkDay in activeDays) {
            currentStreak++
            checkDay = checkDay.minusDays(1L)
        }

        // Best Streak in History in O(K) without sorting
        var bestStreak = currentStreak
        for (day in activeDays) {
            if (day.minusDays(1L) !in activeDays) {
                var runningStreak = 1
                var next = day.plusDays(1L)
                while (next in activeDays) {
                    runningStreak++
                    next = next.plusDays(1L)
                }
                if (runningStreak > bestStreak) bestStreak = runningStreak
            }
        }

        return currentStreak to bestStreak
    }

    private class SubjectAccumulator(var focusSeconds: Long = 0L, var sessionCount: Int = 0)

    fun computeSubjectDistributions(
        sessions: List<StudySession>,
        subjects: List<Subject>
    ): List<SubjectDistribution> {
        if (sessions.isEmpty()) return emptyList()

        val grouped = HashMap<Long?, SubjectAccumulator>()
        var totalSeconds = 0L
        for (session in sessions) {
            if (session.isActive || session.durationSeconds <= 0L) continue
            totalSeconds += session.durationSeconds
            val acc = grouped.getOrPut(session.subjectId) { SubjectAccumulator() }
            acc.focusSeconds += session.durationSeconds
            acc.sessionCount++
        }
        if (totalSeconds <= 0L || grouped.isEmpty()) return emptyList()

        val subjectsById = subjects.associateBy { it.id }
        val totalDouble = totalSeconds.toDouble()
        return grouped.entries.map { (subjectId, acc) ->
            val subj = subjectId?.let { subjectsById[it] }
            SubjectDistribution(
                subjectId = subjectId ?: -1L,
                name = subj?.name ?: "Sem Matéria",
                color = subj?.color ?: 0xFF8E8E93,
                focusSeconds = acc.focusSeconds,
                percentage = (acc.focusSeconds.toDouble() / totalDouble).toFloat().coerceIn(0f, 1f),
                sessionCount = acc.sessionCount
            )
        }.sortedByDescending { it.focusSeconds }
    }

    fun computePeriodComparison(
        currentSessions: List<StudySession>,
        previousSessions: List<StudySession>
    ): PeriodComparison {
        var currentTotal = 0L
        for (session in currentSessions) {
            if (!session.isActive && session.durationSeconds > 0L) {
                currentTotal += session.durationSeconds
            }
        }
        var previousTotal = 0L
        for (session in previousSessions) {
            if (!session.isActive && session.durationSeconds > 0L) {
                previousTotal += session.durationSeconds
            }
        }
        val diff = currentTotal - previousTotal

        val percentage = when {
            previousTotal <= 0L && currentTotal > 0L -> 100f
            previousTotal <= 0L -> 0f
            else -> ((diff.toDouble() / previousTotal.toDouble()) * 100.0).toFloat().coerceIn(-100f, 999f)
        }

        return PeriodComparison(
            currentTotalSeconds = currentTotal,
            previousTotalSeconds = previousTotal,
            diffSeconds = diff,
            percentageDiff = percentage,
            isPositive = diff >= 0L
        )
    }

    fun computeTimeOfDayDistribution(
        sessions: List<StudySession>,
        zone: ZoneId = ZoneId.systemDefault()
    ): TimeOfDayDistribution {
        if (sessions.isEmpty()) return TimeOfDayDistribution(0L, 0L, 0L, 0L)
        var morning = 0L
        var afternoon = 0L
        var evening = 0L
        var night = 0L

        for (s in sessions) {
            if (s.isActive || s.durationSeconds <= 0L) continue
            val hour = Instant.ofEpochMilli(s.startedAt).atZone(zone).hour
            when (hour) {
                in 6..11 -> morning += s.durationSeconds
                in 12..17 -> afternoon += s.durationSeconds
                in 18..23 -> evening += s.durationSeconds
                else -> night += s.durationSeconds
            }
        }

        return TimeOfDayDistribution(morning, afternoon, evening, night)
    }

    fun computeDailyBarData(
        sessions: List<StudySession>,
        daysBack: Int,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Pair<LocalDate, Long>> {
        if (daysBack <= 0) return emptyList()

        val totals = HashMap<LocalDate, Long>()
        if (sessions.isNotEmpty()) {
            val startDate = today.minusDays((daysBack - 1).toLong())
            val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = today.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
            for (session in sessions) {
                if (session.isActive || session.durationSeconds <= 0L) continue
                if (session.endedAt < startMillis && session.startedAt >= endMillis) continue
                for ((date, sliceSec) in splitSessionAcrossDays(session, zone)) {
                    if (!date.isBefore(startDate) && !date.isAfter(today)) {
                        totals[date] = (totals[date] ?: 0L) + sliceSec
                    }
                }
            }
        }

        val result = ArrayList<Pair<LocalDate, Long>>(daysBack)
        for (offset in daysBack - 1 downTo 0) {
            val date = today.minusDays(offset.toLong())
            result.add(date to (totals[date] ?: 0L))
        }
        return result
    }

    private class DayAccumulator(var focusSeconds: Long = 0L, var breakSeconds: Long = 0L, var sessionCount: Int = 0)

    fun computeHeatmap(
        sessions: List<StudySession>,
        weeksCount: Int = 18,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<HeatmapDay> {
        if (weeksCount <= 0) return emptyList()

        val totalDaysLong = weeksCount.toLong() * 7L
        val totalDays = totalDaysLong.coerceAtMost(3650L).toInt()
        val byDay = HashMap<LocalDate, DayAccumulator>()
        if (sessions.isNotEmpty()) {
            val startDate = today.minusDays(totalDays.toLong() - 1L)
            val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = today.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
            for (session in sessions) {
                if (session.isActive || session.durationSeconds <= 0L) continue
                val slices = splitSessionAcrossDays(session, zone)
                var breakAssigned = false
                for ((date, sliceSec) in slices) {
                    if (!date.isBefore(startDate) && !date.isAfter(today)) {
                        val acc = byDay.getOrPut(date) { DayAccumulator() }
                        acc.focusSeconds += sliceSec
                        if (!breakAssigned && session.breakSeconds > 0L) {
                            acc.breakSeconds += session.breakSeconds
                            breakAssigned = true
                        }
                        acc.sessionCount++
                    }
                }
            }
        }

        val result = ArrayList<HeatmapDay>(totalDays)
        for (offset in totalDays - 1 downTo 0) {
            val date = today.minusDays(offset.toLong())
            val acc = byDay[date]
            val focus = acc?.focusSeconds ?: 0L
            val breaks = acc?.breakSeconds ?: 0L
            val count = acc?.sessionCount ?: 0

            val intensity = when {
                focus == 0L -> 0
                focus < 3600L -> 1       // < 1h
                focus < 7200L -> 2       // 1h - 2h
                focus < 14400L -> 3      // 2h - 4h
                else -> 4                // 4h+
            }

            result.add(
                HeatmapDay(
                    date = date,
                    focusSeconds = focus,
                    breakSeconds = breaks,
                    sessionCount = count,
                    intensity = intensity
                )
            )
        }
        return result
    }
}

/**
 * Convenience top-level wrapper delegating to [StudyAnalyticsEngine.resolveDailyGoalSeconds].
 */
fun resolveDailyGoalSeconds(
    subjects: List<Subject>,
    manualMinutes: Int = 180
): Long = StudyAnalyticsEngine.resolveDailyGoalSeconds(subjects, manualMinutes)
