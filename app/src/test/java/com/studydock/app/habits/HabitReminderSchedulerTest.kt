package com.studydock.app.habits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class HabitReminderSchedulerTest {

    private val zoneId: ZoneId = ZoneId.of("UTC")
    private val timeZone: TimeZone = TimeZone.getTimeZone("UTC")

    private fun epochUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zoneId).toInstant().toEpochMilli()

    @Test
    fun zeroOrInvalidDaysMaskReturnsNull() {
        val now = epochUtc(2026, 11, 14, 9, 0)
        assertNull(computeNextHabitReminderMillis(now, minutes = 10 * 60, daysMask = 0, timeZone = timeZone))
        // Upper unused bits only (bits 0..6 empty) must also be rejected
        assertNull(computeNextHabitReminderMillis(now, minutes = 10 * 60, daysMask = 1 shl 8, timeZone = timeZone))
    }

    @Test
    fun picksSameDayBeforeReminderAndNextDayAfterReminder() {
        // 2026-11-14 is a Saturday (bit index 5)
        val morning = epochUtc(2026, 11, 14, 7, 30)
        val sameDay = computeNextHabitReminderMillis(morning, minutes = 9 * 60, daysMask = ALL_HABIT_REMINDER_DAYS_MASK, timeZone = timeZone)
        assertEquals(epochUtc(2026, 11, 14, 9, 0), sameDay)

        val afternoon = epochUtc(2026, 11, 14, 15, 0)
        val nextDay = computeNextHabitReminderMillis(afternoon, minutes = 9 * 60, daysMask = ALL_HABIT_REMINDER_DAYS_MASK, timeZone = timeZone)
        assertEquals(epochUtc(2026, 11, 15, 9, 0), nextDay)
    }

    @Test
    fun advancesToMatchingWeekdayBitMask() {
        // 2026-11-14 (Sat 10:00), mask = Monday only (bit 0) -> should jump to Mon 2026-11-16 08:15
        val saturdayMorning = epochUtc(2026, 11, 14, 10, 0)
        val mondayOnlyMask = 1 shl 0
        val next = computeNextHabitReminderMillis(saturdayMorning, minutes = 8 * 60 + 15, daysMask = mondayOnlyMask, timeZone = timeZone)
        assertEquals(epochUtc(2026, 11, 16, 8, 15), next)

        // Mask = Sunday only (bit 6) -> Sun 2026-11-15 08:15
        val sundayOnlyMask = 1 shl 6
        val nextSun = computeNextHabitReminderMillis(saturdayMorning, minutes = 8 * 60 + 15, daysMask = sundayOnlyMask, timeZone = timeZone)
        assertEquals(epochUtc(2026, 11, 15, 8, 15), nextSun)
    }
}
