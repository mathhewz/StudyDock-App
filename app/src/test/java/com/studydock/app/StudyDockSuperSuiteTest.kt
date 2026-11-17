package com.studydock.app

import com.studydock.app.data.ApkgCardRow
import com.studydock.app.data.ApkgNoteRow
import com.studydock.app.data.ApkgPackageCodec
import com.studydock.app.data.ApkgRawTables
import com.studydock.app.data.CardDeck
import com.studydock.app.data.CardQueryEngine
import com.studydock.app.data.DeckStudyIndex
import com.studydock.app.data.Flashcard
import com.studydock.app.data.MathProblemGenerator
import com.studydock.app.data.MathDifficulty
import com.studydock.app.data.MathTopic
import com.studydock.app.data.afterGradePro
import com.studydock.app.data.reviewState
import com.studydock.app.timer.PomodoroSettings
import com.studydock.app.timer.SessionSnapshot
import com.studydock.app.ui.isTexAstParsable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Superteste unificado (JVM) validando em uma única execução:
 * FSRS-5, SM-2, DeckStudyIndex, CardQueryEngine, streaming .apkg,
 * MathArenaEngine, parser LaTeX e configurações do StudySessionManager.
 */
class StudyDockSuperSuiteTest {

    @Test
    fun superSuite_fsrsAndSm2SchedulersAndQueueLimits() {
        val zone = ZoneId.of("America/Fortaleza")
        val day = LocalDate.of(2026, 11, 15)
        val now = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

        val fsrsDeck = CardDeck(id = 1L, name = "Cálculo FSRS", schedulerMode = "FSRS", desiredRetention = 0.90)
        val newCard = Flashcard(id = 10L, deckId = 1L, front = "Derivada de $\\sin x$", back = "$\\cos x$", dueAt = now)

        val goodStep = newCard.afterGradePro(grade = 2, now = now, config = fsrsDeck, zone = zone, deterministic = true).card
        assertEquals("LEARNING", goodStep.reviewState)
        assertEquals(1, goodStep.learningStep)

        val graduated = goodStep.afterGradePro(grade = 2, now = goodStep.dueAt, config = fsrsDeck, zone = zone, deterministic = true).card
        assertEquals("REVIEW", graduated.reviewState)
        assertTrue(graduated.intervalDays >= 1)
        assertNotNull(graduated.fsrsStability)

        val sm2Deck = CardDeck(id = 2L, name = "SM2", schedulerMode = "SM2", dayStartHour = 4, leechThreshold = 2, leechSuspend = true)
        val leechTarget = Flashcard(id = 11L, deckId = 2L, front = "Q", back = "A", intervalDays = 10, repetitions = 3, lapses = 1, dueAt = now)
        val lapsed = leechTarget.afterGradePro(grade = 0, now = now, config = sm2Deck, zone = zone, deterministic = true)
        assertTrue(lapsed.isLeechNow)
        assertTrue(lapsed.card.suspended)

        val index = DeckStudyIndex(listOf(fsrsDeck, sm2Deck), listOf(newCard, lapsed.card), emptyList(), day, zone)
        assertEquals(listOf(newCard.id), index.due(now, fsrsDeck.id).map { it.id })
        assertTrue(index.due(now, sm2Deck.id).isEmpty())
    }

    @Test
    fun superSuite_cardQueryEngineFiltersLeechAndDueWindow() {
        val now = 1_700_000_000_000L
        val deck = CardDeck(id = 5L, name = "Física", leechThreshold = 4)
        val dueSoon = Flashcard(id = 1L, deckId = 5L, front = "F = m a", back = "2ª Lei", dueAt = now + 2 * 86_400_000L)
        val dueFar = Flashcard(id = 2L, deckId = 5L, front = "E = m c^2", back = "Relatividade", dueAt = now + 15 * 86_400_000L)
        val leechCard = Flashcard(id = 3L, deckId = 5L, front = "Difícil", back = "Resposta", lapses = 4, dueAt = now + 10 * 86_400_000L)

        val leeches = CardQueryEngine.filter(listOf(dueSoon, dueFar, leechCard), "is:leech", listOf(deck), now)
        assertEquals(listOf(3L), leeches.map { it.id })

        val dueIn5Days = CardQueryEngine.filter(listOf(dueSoon, dueFar, leechCard), "prop:due<=5", listOf(deck), now)
        assertEquals(listOf(1L), dueIn5Days.map { it.id })
    }

    @Test
    fun superSuite_apkgStreamingReadsMediaFromDisk() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki2"))
            zip.write(byteArrayOf(0x53, 0x51, 0x4C, 0x69))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("media"))
            zip.write("""{"0":"geo.png"}""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("0"))
            zip.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            zip.closeEntry()
        }
        val raw = ApkgRawTables(
            decksJson = """{"1":{"id":1,"name":"Geometria"}}""",
            modelsJson = """{"10":{"id":10,"name":"Basic","type":0,"flds":[{"name":"Front"},{"name":"Back"}],"tmpls":[{"name":"Card 1","ord":0,"qfmt":"{{Front}}","afmt":"{{Back}}"}]}}""",
            notes = listOf(ApkgNoteRow(1L, 10L, "<img src=\"geo.png\">\u001FPitágoras", "math")),
            cards = listOf(ApkgCardRow(2L, 1L, 1L, 0, 0, 0, 0L, 0, 2500, 0, 0, 0))
        )
        val dir = Files.createTempDirectory("supertest-apkg").toFile()
        try {
            val res = ApkgPackageCodec.readApkg(ByteArrayInputStream(baos.toByteArray()), dir, keepMediaFilesOnDisk = true) { raw }
            val card = res.decks.single().cards.single()
            assertNull(card.imageBytes)
            assertNotNull(card.imageFile)
            assertTrue(card.imageFile!!.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun superSuite_mathArenaAndLatexParserAndSessionModels() {
        val topics = listOf(MathTopic.LINEAR_EQ, MathTopic.GEOMETRY, MathTopic.TRIGONOMETRY)
        topics.forEach { topic ->
            val p = MathProblemGenerator.generate(topic, MathDifficulty.INTERMEDIATE, seed = 42L)
            assertTrue(p.promptLatex.isNotBlank())
            assertTrue(p.answerLatex.isNotBlank())
        }

        // Deeply nested LaTeX must never throw StackOverflowError
        val deepTex = "{".repeat(120) + "x" + "}".repeat(120)
        assertTrue(isTexAstParsable(deepTex))

        val settings = PomodoroSettings(focusMin = 999, shortMin = 0, longMin = -5, cycles = 50).sanitized()
        assertEquals(180, settings.focusMin)
        assertEquals(1, settings.shortMin)
        assertEquals(1, settings.longMin)
        assertEquals(12, settings.cycles)

        val snap = SessionSnapshot(appExitCount = 3)
        assertEquals(3, snap.appExitCount)
        assertFalse(snap.recoveredFromKill)
    }

    @Test
    fun superSuite_fsrsOptimizerCalibratesFromCardReviews() {
        val reviews = (1..25).map { idx ->
            com.studydock.app.data.CardReview(
                id = idx.toLong(),
                cardId = 1L,
                reviewedAt = 1_700_000_000_000L + idx * 86_400_000L,
                grade = if (idx % 5 == 0) 0 else 2,
                intervalDays = 10,
                priorState = "REVIEW",
                priorIntervalDays = 10,
                deckAtReview = 1L
            )
        }
        val cal = com.studydock.app.data.FsrsOptimizer.calibrate(reviews, currentDesiredRetention = 0.90, deckId = 1L)
        assertEquals(25, cal.sampleCount)
        assertEquals(0.80, cal.observedRetention, 0.01)
        assertTrue(cal.calibratedLogLoss <= cal.baselineLogLoss)
        assertTrue(cal.recommendedRetention in 0.80..0.95)
    }

    @Test
    fun superSuite_mathEloEngineAdaptsDifficulty() {
        var elo = com.studydock.app.data.MathEloEngine.DEFAULT_ELO
        assertEquals(com.studydock.app.data.MathDifficulty.INTERMEDIATE, com.studydock.app.data.MathEloEngine.recommendedDifficulty(elo))
        repeat(10) {
            elo = com.studydock.app.data.MathEloEngine.updateElo(elo, com.studydock.app.data.MathDifficulty.ADVANCED, correct = true)
        }
        assertEquals(com.studydock.app.data.MathDifficulty.ADVANCED, com.studydock.app.data.MathEloEngine.recommendedDifficulty(elo))
        repeat(15) {
            elo = com.studydock.app.data.MathEloEngine.updateElo(elo, com.studydock.app.data.MathDifficulty.BASIC, correct = false)
        }
        assertEquals(com.studydock.app.data.MathDifficulty.BASIC, com.studydock.app.data.MathEloEngine.recommendedDifficulty(elo))
    }
}
