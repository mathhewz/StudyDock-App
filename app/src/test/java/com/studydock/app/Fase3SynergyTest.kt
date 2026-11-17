package com.studydock.app

import com.studydock.app.data.AlgebraicNormalizer
import com.studydock.app.data.CalendarEvent
import com.studydock.app.data.FeynmanPersona
import com.studydock.app.data.FeynmanSocraticEngine
import com.studydock.app.data.JournalEntry
import com.studydock.app.data.MathArenaSocraticExtensions
import com.studydock.app.data.MentalBattery
import com.studydock.app.data.Note
import com.studydock.app.data.NoteEvergreenScheduler
import com.studydock.app.data.NoteLink
import com.studydock.app.data.StudyTask
import com.studydock.app.data.TodoistProEngine
import com.studydock.app.data.UnifiedBrainEngine
import com.studydock.app.data.WorkoutLog
import com.studydock.app.ui.LatexSnippetExpander
import com.studydock.app.ui.ParametricCardEngine
import com.studydock.app.ui.parseBlindProofBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class Fase3SynergyTest {

    @Test
    fun todoistProBatteryFilterAndRescheduleAndKarma() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 11, 16)
        val noon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        val t1 = StudyTask(id = 1L, title = "Resolver lista ITA", dueAt = noon, priority = 1, tags = "@alta", estimatedMinutes = 45)
        val t2 = StudyTask(id = 2L, title = "Baixar PDF edital", dueAt = noon, priority = 4, tags = "@baixa", estimatedMinutes = 10, completed = true)

        assertEquals(MentalBattery.HIGH, TodoistProEngine.inferMentalBattery(t1))
        assertEquals(MentalBattery.LOW, TodoistProEngine.inferMentalBattery(t2))
        assertTrue(TodoistProEngine.matchesBooleanFilter(t1, "(hoje | atrasadas) & p1 & !@leitura", today = today, zone = zone))
        assertFalse(TodoistProEngine.matchesBooleanFilter(t2, "(hoje | atrasadas) & p1 & !@leitura", today = today, zone = zone))
        assertFalse(TodoistProEngine.matchesBooleanFilter(t2, "p1", today = today, zone = zone))

        val events = listOf(CalendarEvent(id = 1L, title = "Aula", at = noon + 3_600_000L))
        val rescheduled = TodoistProEngine.recalculateDaySchedule(listOf(t1), events, nowMillis = noon, sleepHour = 23, zone = zone)
        assertEquals(1, rescheduled.size)
        assertTrue(rescheduled.first().hasDueDate)
        assertEquals(10, TodoistProEngine.computeTodoKarma(listOf(t1, t2)))
    }

    @Test
    fun obsidianProSnippetsEvergreenAndBlindProof() {
        val expanded = LatexSnippetExpander.tryExpandPostfix("x//", 3)
        assertNotNull(expanded)
        assertEquals("\\frac{x}{}", expanded!!.first)

        val blocks = parseBlindProofBlocks(":::proof Teorema\nPasso 1\nPasso 2\n:::")
        assertEquals(1, blocks.size)
        assertEquals(listOf("Passo 1", "Passo 2"), blocks.first().steps)

        val now = System.currentTimeMillis()
        val note = Note(id = 1L, title = "Desigualdades", markdown = "QM >= AM >= GM >= HM", updatedAt = now - 5 * 86_400_000L)
        val queue = NoteEvergreenScheduler.queueForRefinement(listOf(note), listOf(NoteLink(1L, "AM-GM", 2L)), nowMillis = now)
        assertEquals(1, queue.size)
        assertTrue(queue.first().dueForRefinement)
    }

    @Test
    fun ankiParametricFeynmanMathArenaAndUnifiedBrain() {
        val (f1, b1) = ParametricCardEngine.render("Calcule {{var:a:3..5}} + {{var:b:2..4}}", "Resposta: {{eval:a+b}}", seed = 42L)
        assertFalse(f1.contains("{{var:"))
        assertFalse(b1.contains("{{eval:"))
        val (_, bMixed) = ParametricCardEngine.render("{{var:a:3..3}} {{var:b:4..4}}", "{{eval:a*b-2}}", seed = 1L)
        assertEquals("10", bMixed)

        val jargon = FeynmanSocraticEngine.detectJargonWords("Um homomorfismo preserva estrutura.")
        assertEquals(listOf("homomorfismo"), jargon)
        val prompt = FeynmanSocraticEngine.buildFeynmanPrompt("Álgebra", 2, FeynmanPersona.ITA_IME, "Um homomorfismo...")
        assertTrue(prompt.contains("Banca ITA/IME"))

        assertTrue(AlgebraicNormalizer.areEquivalent("x^2 + 2·x", "x**2+2*x"))
        val challenge = MathArenaSocraticExtensions.generateFallaciousProof(0L)
        assertTrue(challenge.steps.isNotEmpty())
        val hint2 = MathArenaSocraticExtensions.unlockHint(2)
        assertEquals(0.60, hint2.eloMultiplier, 0.001)

        val todayKey = 20261116L
        assertTrue(
            UnifiedBrainEngine.shouldEnableCognitiveMaintenanceMode(
                recentEntries = listOf(JournalEntry(dayKey = todayKey, energy = 2, focus = 3)),
                recentWorkouts = emptyList(),
                todayKey = todayKey
            )
        )
        val receipt = UnifiedBrainEngine.buildSessionAutopsyReceipt(5400L, 34, 91, 420, 6, 14)
        assertTrue(receipt.contains("90 min") && receipt.contains("+34 flashcards") && receipt.contains("+14 ELO"))
    }
}
