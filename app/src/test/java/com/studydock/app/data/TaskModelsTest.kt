package com.studydock.app.data

import androidx.compose.ui.graphics.Color
import com.studydock.app.ui.accessibleAccentOnSurface
import com.studydock.app.ui.accessibleOnColor
import com.studydock.app.ui.contrastRatio
import com.studydock.app.ui.formatMinutesCompact
import com.studydock.app.ui.nextWeekendNoon
import com.studydock.app.ui.parseQuickTask
import com.studydock.app.ui.priorityColor
import com.studydock.app.ui.priorityQuadrantTitle
import com.studydock.app.ui.prioritySectionTitle
import com.studydock.app.ui.studiedMinutesForTask
import com.studydock.app.ui.toStudyTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TaskModelsTest {

    private val saoPaulo: ZoneId = ZoneId.of("America/Sao_Paulo")

    private fun epochAt(date: LocalDate, hour: Int = 12, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(saoPaulo).toInstant().toEpochMilli()

    private fun localDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(saoPaulo).toLocalDate()

    private fun localTime(millis: Long): LocalTime =
        Instant.ofEpochMilli(millis).atZone(saoPaulo).toLocalTime()

    @Test
    fun nextTaskOccurrenceComputesRecurrenceRulesAndEdgeCases() {
        val friday = LocalDate.of(2026, 11, 13)
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        val now = epochAt(friday, 10, 0)

        // NONE, blank, and invalid rules return null
        assertNull(nextTaskOccurrence(StudyTask(title = "T", dueAt = now, recurrence = "NONE"), now, saoPaulo))
        assertNull(nextTaskOccurrence(StudyTask(title = "T", dueAt = now, recurrence = ""), now, saoPaulo))
        assertNull(nextTaskOccurrence(StudyTask(title = "T", dueAt = now, recurrence = "YEARLY"), now, saoPaulo))
        assertNull(nextTaskOccurrence(StudyTask(title = "T", dueAt = now, recurrence = "EVERY_N_DAYS:0"), now, saoPaulo))
        assertNull(nextTaskOccurrence(StudyTask(title = "T", dueAt = now, recurrence = "EVERY_N_DAYS:-3"), now, saoPaulo))
        assertNull(nextTaskOccurrence(StudyTask(title = "T", dueAt = now, recurrence = "EVERY_N_DAYS:abc"), now, saoPaulo))

        // DAILY preserves time of day and skips past overdue dates to tomorrow
        val overdueDaily = StudyTask(
            title = "Daily",
            dueAt = epochAt(friday.minusDays(4), 8, 30),
            recurrence = "DAILY"
        )
        val nextDaily = nextTaskOccurrence(overdueDaily, now, saoPaulo)!!
        assertEquals(LocalDate.of(2026, 11, 14), localDate(nextDaily))
        assertEquals(LocalTime.of(8, 30), localTime(nextDaily))

        // WEEKDAYS skips Saturday and Sunday from Friday
        val fridayWeekdays = StudyTask(title = "Weekdays", dueAt = epochAt(friday, 9, 15), recurrence = "WEEKDAYS")
        val nextWeekday = nextTaskOccurrence(fridayWeekdays, now, saoPaulo)!!
        assertEquals(LocalDate.of(2026, 11, 16), localDate(nextWeekday))
        assertEquals(DayOfWeek.MONDAY, localDate(nextWeekday).dayOfWeek)
        assertEquals(LocalTime.of(9, 15), localTime(nextWeekday))

        // WEEKLY schedule-anchored keeps weekday cadence even when overdue by 10 days
        val sundayAnchor = LocalDate.of(2026, 11, 1) // 12 days before Nov 13 (Sunday)
        val scheduleWeekly = StudyTask(
            title = "Weekly",
            dueAt = epochAt(sundayAnchor, 18, 0),
            recurrence = "WEEKLY",
            afterCompletion = false
        )
        val nextScheduleWeekly = nextTaskOccurrence(scheduleWeekly, now, saoPaulo)!!
        assertEquals(LocalDate.of(2026, 11, 15), localDate(nextScheduleWeekly))
        assertEquals(DayOfWeek.SUNDAY, localDate(nextScheduleWeekly).dayOfWeek)

        // WEEKLY afterCompletion=true anchors from today instead of overdue date
        val completionWeekly = scheduleWeekly.copy(afterCompletion = true)
        val nextCompletionWeekly = nextTaskOccurrence(completionWeekly, now, saoPaulo)!!
        assertEquals(LocalDate.of(2026, 11, 20), localDate(nextCompletionWeekly))

        // MONTHLY handles end-of-month clamping (Jan 31 -> Feb 28)
        val febNow = epochAt(LocalDate.of(2026, 2, 20), 10, 0)
        val monthEndTask = StudyTask(
            title = "Mensal",
            dueAt = epochAt(LocalDate.of(2026, 1, 31), 14, 0),
            recurrence = "MONTHLY"
        )
        val nextMonthly = nextTaskOccurrence(monthEndTask, febNow, saoPaulo)!!
        assertEquals(LocalDate.of(2026, 2, 28), localDate(nextMonthly))
        assertEquals(LocalTime.of(14, 0), localTime(nextMonthly))

        // MONTHLY:31 preserves the 31st anchor day after February clamping (Feb 28 -> Mar 31)
        val marNow = epochAt(LocalDate.of(2026, 3, 1), 10, 0)
        val clampedFebTask = monthEndTask.copy(dueAt = nextMonthly, recurrence = "MONTHLY:31")
        val nextMarchMonthly = nextTaskOccurrence(clampedFebTask, marNow, saoPaulo)!!
        assertEquals(LocalDate.of(2026, 3, 31), localDate(nextMarchMonthly))

        // EVERY_N_DAYS:3 steps cleanly past today
        val every3 = StudyTask(
            title = "A cada 3 dias",
            dueAt = epochAt(LocalDate.of(2026, 11, 7), 16, 45),
            recurrence = "EVERY_N_DAYS:3"
        )
        val nextEvery3 = nextTaskOccurrence(every3, now, saoPaulo)!!
        assertEquals(LocalDate.of(2026, 11, 16), localDate(nextEvery3))

        // hasDueDate = false defaults to 12:00 anchored from today
        val undatedDaily = StudyTask(
            title = "Undated",
            dueAt = epochAt(LocalDate.of(2026, 1, 1), 6, 0),
            hasDueDate = false,
            recurrence = "DAILY"
        )
        val nextUndated = nextTaskOccurrence(undatedDaily, now, saoPaulo)!!
        assertEquals(LocalDate.of(2026, 11, 14), localDate(nextUndated))
        assertEquals(LocalTime.of(12, 0), localTime(nextUndated))

        assertEquals("Todo dia", taskRecurrenceLabel("DAILY"))
        assertEquals("Seg–Sex · após concluir", taskRecurrenceLabel("WEEKDAYS", afterCompletion = true))
        assertEquals("A cada 4 dias", taskRecurrenceLabel("EVERY_N_DAYS:4"))
        assertEquals("Não repete", taskRecurrenceLabel("NONE"))
    }

    @Test
    fun taskSubtasksEncodeParseToggleResetAndHandleMalformedJson() {
        assertTrue(TaskSubtasks.parse(null).isEmpty())
        assertTrue(TaskSubtasks.parse("   ").isEmpty())
        assertTrue(TaskSubtasks.parse("not json").isEmpty())
        assertTrue(TaskSubtasks.parse("{}").isEmpty())

        val initial = listOf(
            TaskSubtask(id = "s1", title = "Ler \"capítulo 1\"\ne anotar", done = true),
            TaskSubtask(id = "", title = "Resolver 10 questões", done = false),
            TaskSubtask(id = "blank", title = "   ", done = false) // must be filtered out
        )
        val encoded = TaskSubtasks.encode(initial)
        val parsed = TaskSubtasks.parse(encoded)
        assertEquals(2, parsed.size)
        assertEquals("s1", parsed[0].id)
        assertEquals("Ler \"capítulo 1\"\ne anotar", parsed[0].title)
        assertTrue(parsed[0].done)
        assertEquals("sub-1", parsed[1].id)
        assertEquals("Resolver 10 questões", parsed[1].title)
        assertFalse(parsed[1].done)

        assertEquals(1 to 2, TaskSubtasks.summary(encoded))

        val toggled = TaskSubtasks.toggle(encoded, "sub-1")
        assertEquals(2 to 2, TaskSubtasks.summary(toggled))

        val reset = TaskSubtasks.resetAll(toggled)
        assertEquals(0 to 2, TaskSubtasks.summary(reset))

        // Extra JSON fields / malformed partial items should not crash parser
        val messyJson = """[{"id":"a","extra":{"nested":[1,2]},"title":"Item válido","done":true},{"title":"   "}]"""
        val messyParsed = TaskSubtasks.parse(messyJson)
        assertEquals(1, messyParsed.size)
        assertEquals("Item válido", messyParsed.single().title)
        assertTrue(messyParsed.single().done)

        assertEquals(4, TaskSubtasks.fullStudyCycleTemplate().size)
        assertEquals("", TaskSubtasks.encode(emptyList()))
        assertEquals("", TaskSubtasks.resetAll(""))
        assertEquals("", TaskSubtasks.toggle("", "s1"))
    }

    @Test
    fun taskQuickParserParsesNaturalLanguagePrioritySubjectTagsDatesAndEstimates() {
        val friday = LocalDate.of(2026, 11, 13)
        val now = epochAt(friday, 9, 0)
        val subjects = listOf(
            Subject(id = 10L, name = "Direito Constitucional"),
            Subject(id = 20L, name = "Matemática")
        )

        val full = TaskQuickParser.parse(
            "Resolver lista de exercícios p1 #mat @questoes @revisao ~1h30m amanhã a cada 3 dias após concluir",
            subjects = subjects,
            now = now,
            zone = saoPaulo
        )
        assertEquals("Resolver lista de exercícios", full.title)
        assertEquals(1, full.priority)
        assertEquals(20L, full.subjectId)
        assertEquals(listOf("@questoes", "@revisao"), full.tags)
        assertEquals(90, full.estimatedMinutes)
        assertEquals("EVERY_N_DAYS:3", full.recurrence)
        assertTrue(full.afterCompletion)
        assertTrue(full.hasDueDate)
        assertEquals(LocalDate.of(2026, 11, 14), localDate(full.dueAt!!))

        val task = full.toStudyTask()
        assertEquals(1, task.priority)
        assertEquals(20L, task.subjectId)
        assertEquals("@questoes @revisao", task.tags)
        assertEquals("EVERY_N_DAYS:3", task.recurrence)
        assertTrue(task.afterCompletion)

        // Pomodoro estimate & inbox keyword
        val inboxParsed = TaskQuickParser.parse(
            "Revisar resumo !2 #direito 2p inbox",
            subjects = subjects,
            now = now,
            zone = saoPaulo
        )
        assertEquals("Revisar resumo", inboxParsed.title)
        assertEquals(2, inboxParsed.priority)
        assertEquals(10L, inboxParsed.subjectId)
        assertEquals(50, inboxParsed.estimatedMinutes)
        assertFalse(inboxParsed.hasDueDate)
        assertNull(inboxParsed.dueAt)

        // Weekday recurrence ("toda sexta" on Friday schedules next week & WEEKLY)
        val nextFriday = TaskQuickParser.parse("Simulado toda sexta", subjects, now, saoPaulo)
        assertEquals("Simulado", nextFriday.title)
        assertEquals("WEEKLY", nextFriday.recurrence)
        assertEquals(LocalDate.of(2026, 11, 20), localDate(nextFriday.dueAt!!))

        // Explicit time of day ("às 14:30") and trailing punctuation tolerance
        val timed = TaskQuickParser.parse("Simulado de Física amanhã. às 14:30", subjects, now, saoPaulo)
        assertEquals("Simulado de Física .", timed.title)
        assertEquals(LocalDate.of(2026, 11, 14), localDate(timed.dueAt!!))
        assertEquals(LocalTime.of(14, 30), localTime(timed.dueAt!!))

        // Past dd/MM without year rolls forward to next year
        val pastSlash = TaskQuickParser.parse("Revisar 10/01", subjects, now, saoPaulo)
        assertEquals(LocalDate.of(2027, 1, 10), localDate(pastSlash.dueAt!!))

        // Only tokens should never produce blank title
        val onlyTokens = TaskQuickParser.parse("p1 hoje", subjects, now, saoPaulo)
        assertEquals("p1 hoje", onlyTokens.title)

        // UI bridge parseQuickTask
        val uiQuick = parseQuickTask("Estudar p3 ~45m", subjects)
        assertEquals(3, uiQuick.priority)
        assertEquals(45, uiQuick.estimatedMinutes)
        assertEquals(3, uiQuick.toStudyTask().priority)
    }

    @Test
    fun tagNormalizationAndTaskClassificationWorkAccurately() {
        assertEquals("@revisao", TaskTagPresets.normalizeTag("  #Revisão!! "))
        assertEquals("@simuladinho", TaskTagPresets.normalizeTag("@Simuladinho"))
        assertEquals("", TaskTagPresets.normalizeTag("@@@   "))

        val parsed = TaskTagPresets.parseTags("revisao, @QUESTÕES; #revisão  teoria")
        assertEquals(listOf("@revisao", "@questoes", "@teoria"), parsed)
        assertEquals("@revisao @questoes @teoria", TaskTagPresets.formatTags(parsed))

        val toggledOn = TaskTagPresets.toggleTag("@revisao", "leitura")
        assertEquals("@revisao @leitura", toggledOn)
        val toggledOff = TaskTagPresets.toggleTag(toggledOn, "@revisao")
        assertEquals("@leitura", toggledOff)

        val qByKeyword = StudyTask(title = "Resolver 20 questões de direito", dueAt = 0L)
        val qByTag = StudyTask(title = "Bloco 1", dueAt = 0L, tags = "@simulado")
        val rByBook = StudyTask(title = "Avançar", dueAt = 0L, bookId = 5L)
        val rByKeyword = StudyTask(title = "Ler capítulo 4", dueAt = 0L)
        val neutral = StudyTask(title = "Organizar mesa", dueAt = 0L)

        assertTrue(isQuestionTask(qByKeyword))
        assertTrue(isQuestionTask(qByTag))
        assertFalse(isQuestionTask(neutral))

        assertTrue(isReadingTask(rByBook))
        assertTrue(isReadingTask(rByKeyword))
        assertFalse(isReadingTask(neutral))
    }

    @Test
    fun computeBalancedRescheduleDistributesOverdueTasksRespectingPriorityAndDailyLoad() {
        val today = LocalDate.of(2026, 11, 13)
        val now = epochAt(today, 9, 0)

        assertTrue(computeBalancedReschedule(emptyList(), emptyList(), 120, now, saoPaulo).isEmpty())

        // Unpersisted task id == 0L must be ignored
        val unsaved = StudyTask(id = 0L, title = "Unsaved", dueAt = epochAt(today.minusDays(1)))
        assertTrue(computeBalancedReschedule(listOf(unsaved), listOf(unsaved), 120, now, saoPaulo).isEmpty())

        val overdueHigh = StudyTask(
            id = 1L,
            title = "Urgente P1",
            dueAt = epochAt(today.minusDays(1)),
            priority = 1,
            estimatedMinutes = 40
        )
        val overdueLow = StudyTask(
            id = 2L,
            title = "Backlog P4",
            dueAt = epochAt(today.minusDays(3)),
            priority = 4,
            estimatedMinutes = 40
        )
        val existingToday = StudyTask(
            id = 3L,
            title = "Já agendada hoje",
            dueAt = epochAt(today),
            estimatedMinutes = 50
        )
        val allOpen = listOf(overdueHigh, overdueLow, existingToday)

        // Daily target = 90m; today already has 50m -> fits P1 (40m => 90m), pushes P4 (40m) to tomorrow
        val plan = computeBalancedReschedule(
            overdue = listOf(overdueLow, overdueHigh),
            allOpen = allOpen,
            dailyTargetMinutes = 90,
            now = now,
            zone = saoPaulo
        )
        assertEquals(2, plan.size)
        assertEquals(today, localDate(plan.getValue(1L)))
        assertEquals(today.plusDays(1), localDate(plan.getValue(2L)))
    }

    @Test
    fun noteChecklistsExtractToggleSyncAndDetectUncoveredLines() {
        val md = """
---
tags: [tarefas]
---
# Plano

```markdown
- [ ] Não deve ser extraído dentro de fence
```

- [ ] Revisar Direito Civil p1
* [X] Fazer simulado #dir
- [ ]   
""".trimStart()

        val extracted = NoteChecklists.extract(md)
        assertEquals(2, extracted.size)
        assertFalse(extracted[0].checked)
        assertEquals("Revisar Direito Civil p1", extracted[0].text)
        assertTrue(extracted[1].checked)
        assertEquals("Fazer simulado #dir", extracted[1].text)

        val toggled = NoteChecklists.toggleLine(md, extracted[0].lineIndex)
        assertTrue(NoteChecklists.extract(toggled)[0].checked)

        // Out-of-bounds or non-checklist line returns markdown unchanged
        assertEquals(md, NoteChecklists.setLineChecked(md, 0, true))
        assertEquals(md, NoteChecklists.setLineChecked(md, 999, true))

        // Sync by parsed title (without "p1") checks line 0
        val synced = NoteChecklists.syncTaskCompletion(md, "Revisar Direito Civil", completed = true)
        assertTrue(NoteChecklists.extract(synced)[0].checked)

        val linked = listOf(StudyTask(title = "Revisar Direito Civil", dueAt = 0L))
        val uncovered = NoteChecklists.uncoveredLines(md, linked)
        assertEquals(1, uncovered.size)
        assertEquals("Fazer simulado #dir", uncovered.single().text)
    }

    @Test
    fun taskUiPureHelpersComputeMinutesColorsAndPriorityLabels() {
        val task = StudyTask(id = 42L, title = "Estudar Grafos", dueAt = 0L)
        val sessions = listOf(
            StudySession(taskId = 42L, startedAt = 1000L, endedAt = 2000L, durationSeconds = 1800L),
            StudySession(taskId = null, topic = "estudar grafos", startedAt = 3000L, endedAt = 4000L, durationSeconds = 600L),
            StudySession(taskId = 42L, startedAt = 5000L, endedAt = 6000L, durationSeconds = 900L, isActive = true)
        )
        assertEquals(40L, studiedMinutesForTask(task, sessions))
        assertEquals("0m", formatMinutesCompact(0))
        assertEquals("45m", formatMinutesCompact(45))
        assertEquals("1h30m", formatMinutesCompact(90))
        assertEquals("2h", formatMinutesCompact(120))

        val friday = LocalDate.of(2026, 11, 13)
        assertEquals(LocalDate.of(2026, 11, 14), localDate(nextWeekendNoon(epochAt(friday, 10, 0), saoPaulo)))
        val saturday = LocalDate.of(2026, 11, 14)
        assertEquals(LocalDate.of(2026, 11, 15), localDate(nextWeekendNoon(epochAt(saturday, 10, 0), saoPaulo)))
        val sunday = LocalDate.of(2026, 11, 15)
        assertEquals(LocalDate.of(2026, 11, 21), localDate(nextWeekendNoon(epochAt(sunday, 10, 0), saoPaulo)))

        assertEquals("P1 · Urgente + Importante", prioritySectionTitle(1))
        assertEquals("P4 · Backlog / Normal", prioritySectionTitle(99))
        assertEquals("P2 Importante / Agendar", priorityQuadrantTitle(2))

        val p1Dark = priorityColor(1, Color.White)
        assertNotNull(p1Dark)
        val accessible = accessibleAccentOnSurface(Color(0xFFFFFF00), Color.White)
        assertTrue(contrastRatio(accessible, Color.White) >= 4.5)
        val onYellow = accessibleOnColor(Color(0xFFFFFF00))
        assertTrue(contrastRatio(onYellow, Color(0xFFFFFF00)) >= 4.5)
    }
}
