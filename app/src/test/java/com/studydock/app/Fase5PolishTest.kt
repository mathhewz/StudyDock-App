package com.studydock.app

import com.studydock.app.data.PeriodicNotesEngine
import com.studydock.app.timer.FlipToFocusDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Fase5PolishTest {

    @Test
    fun flipToFocusAndOvertimeCalculation() {
        assertTrue(FlipToFocusDetector.isFaceDownOnTable(accelZ = -9.6f, proximityCm = 0.0f, maxProximityCm = 5.0f))
        assertFalse(FlipToFocusDetector.isFaceDownOnTable(accelZ = 9.6f, proximityCm = 0.0f, maxProximityCm = 5.0f))
        assertFalse(FlipToFocusDetector.isFaceDownOnTable(accelZ = -9.6f, proximityCm = 5.0f, maxProximityCm = 5.0f))

        assertEquals(1800L, FlipToFocusDetector.computeOvertimeSeconds(elapsedFocusSeconds = 9000L, dailyGoalSeconds = 7200L))
        assertEquals(0L, FlipToFocusDetector.computeOvertimeSeconds(elapsedFocusSeconds = 3600L, dailyGoalSeconds = 7200L))
    }

    @Test
    fun periodicNotesTitlesAndTaskRollover() {
        val date = LocalDate.of(2026, 11, 16)
        assertEquals("Diário · 2026-11-16", PeriodicNotesEngine.dailyNoteTitle(date))
        assertEquals("Mês · 2026-11", PeriodicNotesEngine.monthlyNoteTitle(date))
        assertTrue(PeriodicNotesEngine.weeklyNoteTitle(date).startsWith("Semana · 2026-W"))

        val prevMd = """
            # Diário · 2026-11-15
            - [x] Tarefa concluída
            - [ ] Revisar desigualdade de Cauchy-Schwarz
            - [ ] Resolver 5 problemas IMO
        """.trimIndent()
        val (title, body) = PeriodicNotesEngine.buildDailyNoteWithRollover(date, prevMd)
        assertEquals("Diário · 2026-11-16", title)
        assertTrue(body.contains("- [ ] Revisar desigualdade de Cauchy-Schwarz"))
        assertTrue(body.contains("- [ ] Resolver 5 problemas IMO"))
        assertFalse(body.contains("Tarefa concluída"))
    }

    @Test
    fun zstdRfc8878AndAnki21bProtobufMediaAndFsrsRealLoss() {
        // 1. Build a valid RFC 8878 Zstd frame with a Raw_Block containing "SQLite format 3\u0000"
        val payload = "SQLite format 3\u0000test".toByteArray(Charsets.UTF_8)
        val bh = 1 or (0 shl 1) or (payload.size shl 3) // Last_Block=1, Block_Type=0 (Raw), Size=payload.size
        val zstdFrame = byteArrayOf(
            0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), // Magic
            0x20, // FHD: Single_Segment_Flag=1, FCS_Flag=0 (1 byte FCS)
            payload.size.toByte(), // FCS
            (bh and 0xFF).toByte(),
            ((bh ushr 8) and 0xFF).toByte(),
            ((bh ushr 16) and 0xFF).toByte()
        ) + payload
        val decompressed = com.studydock.app.data.ApkgPackageCodec.tryDecompressZstd(zstdFrame)
        org.junit.Assert.assertNotNull(decompressed)
        assertEquals("SQLite format 3\u0000test", String(decompressed!!, Charsets.UTF_8))

        // 2. Build Anki 21b MediaEntries protobuf: field 1 (0x0A) length 10 -> field 1 (0x0A) length 8 "card.png"
        val pbMedia = byteArrayOf(0x0A, 10, 0x0A, 8) + "card.png".toByteArray(Charsets.UTF_8)
        val mediaMap = com.studydock.app.data.ApkgPackageCodec.parseAnki21bMediaProtobuf(pbMedia)
        assertEquals("card.png", mediaMap["0"])

        // 3. Verify FSRS sequence log-loss genuinely changes when w[8] (recall stability gain) changes across multi-step reviews
        val now = 1_700_000_000_000L
        val multiStepReviews = listOf(
            com.studydock.app.data.CardReview(id = 1L, cardId = 10L, reviewedAt = now - 10 * 86_400_000L, grade = 2, intervalDays = 3, priorIntervalDays = 1, priorState = "REVIEW"),
            com.studydock.app.data.CardReview(id = 2L, cardId = 10L, reviewedAt = now - 7 * 86_400_000L, grade = 2, intervalDays = 8, priorIntervalDays = 3, priorState = "REVIEW"),
            com.studydock.app.data.CardReview(id = 3L, cardId = 10L, reviewedAt = now, grade = 0, intervalDays = 1, priorIntervalDays = 8, priorState = "REVIEW")
        )
        val wA = com.studydock.app.data.FsrsOptimizer.DEFAULT_WEIGHTS
        val wB = wA.copyOf().also { it[8] = it[8] * 1.5 }
        val lossA = com.studydock.app.data.FsrsOptimizer.computeSequenceLogLoss(multiStepReviews, wA)
        val lossB = com.studydock.app.data.FsrsOptimizer.computeSequenceLogLoss(multiStepReviews, wB)
        assertTrue(kotlin.math.abs(lossA - lossB) > 1e-6)
    }
}
