package com.studydock.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

internal object StudyDateFormats {
    val PT_BR: Locale = Locale("pt", "BR")

    private val TIME_HH_MM_BASE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DATE_DD_MM_BASE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.ROOT)
    private val DATE_TIME_DOT_BASE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM · HH:mm", Locale.ROOT)

    val TIME_HH_MM_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", PT_BR)
    val TIME_HH_MM_DEFAULT: DateTimeFormatter
        get() = TIME_HH_MM_BASE.withLocale(Locale.getDefault())
    val DATE_DD_MM_DEFAULT: DateTimeFormatter
        get() = DATE_DD_MM_BASE.withLocale(Locale.getDefault())
    val DATE_DD_MM_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", PT_BR)
    val DATE_D_M: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M", PT_BR)
    val DATE_D_MMM_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", PT_BR)
    val DATE_D_MMM_YYYY_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", PT_BR)
    val DATE_D_DE_MMM_DE_YYYY_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMM 'de' yyyy", PT_BR)
    val WEEKDAY_EE_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("EE", PT_BR)
    val WEEKDAY_EEE_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", PT_BR)
    val MONTH_MMM_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", PT_BR)
    val DAY_D_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("d", PT_BR)
    val WEEKDAY_FULL_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", PT_BR)
    val WEEKDAY_D_MMM_TIME_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", PT_BR)
    val WEEKDAY_D_DE_MMM_YYYY_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d 'de' MMM yyyy", PT_BR)
    val FULL_WEEKDAY_DAY_MONTH_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", PT_BR)
    val MONTH_YEAR_SHORT_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM, yyyy", PT_BR)
    val DATE_TIME_AS_PT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", PT_BR)
    val DATE_TIME_DOT_DEFAULT: DateTimeFormatter
        get() = DATE_TIME_DOT_BASE.withLocale(Locale.getDefault())
    val IMPORT_DATE_TIME_US: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.US)
    val ISO_LOCAL_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun formatEpoch(epochMillis: Long, formatter: DateTimeFormatter): String =
        formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    fun formatNow(formatter: DateTimeFormatter): String =
        formatter.format(Instant.now().atZone(ZoneId.systemDefault()))

    fun formatCalendar(cal: Calendar, formatter: DateTimeFormatter): String =
        formatEpoch(cal.timeInMillis, formatter)

    fun formatLocalDate(date: LocalDate, formatter: DateTimeFormatter): String =
        date.format(formatter)
}
