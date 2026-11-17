package com.studydock.app.data

import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.studydock.app.ui.ActiveAnswerClock
import com.studydock.app.ui.ReviewHistory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class PhaseOneAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "phase-one-${UUID.randomUUID()}.db"
    private var database: StudyDatabase? = null
    private fun open(): StudyDao = StudyDatabase.openNamed(context, name).also { database = it }.studyDao()
    private fun reopen(): StudyDao { database?.close(); return open() }
    @After fun cleanup() { database?.close(); context.deleteDatabase(name) }

    @Test fun largeCollectionKeepsQueuesStatisticsAndImportConsistent() = runBlocking {
        var dao = open()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val root = dao.saveDeckSettings(CardDeck(name = "Escala", newLimit = 25, reviewLimit = 60))
        val child = dao.saveDeckSettings(CardDeck(name = "Subbaralho", parentId = root, newLimit = 10, reviewLimit = 20))
        repeat(98) { dao.saveDeckSettings(CardDeck(name = "Área ${it + 1}")) }
        val entries = List(5000) { number ->
            SharedCardNote(CardAuthorNote(format = "REVERSE", prompt = "Pergunta $number", answer = "Resposta $number"), setOf("front", "reverse"))
        }
        val importStart = SystemClock.elapsedRealtime()
        assertEquals(root, dao.importDeck("Escala", entries))
        val importMs = SystemClock.elapsedRealtime() - importStart
        val db = database!!
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE flashcards SET deckId = ? WHERE id % 2 = 0", arrayOf(child))
            sql.execSQL("UPDATE flashcards SET intervalDays = 30, repetitions = 5, dueAt = CASE WHEN id % 2 = 0 THEN ? ELSE ? END WHERE id <= 8000",
                arrayOf(now - 60_000, now + 3 * 86_400_000L))
            sql.execSQL("UPDATE flashcards SET suspended = 1 WHERE id % 20 = 0")
            sql.compileStatement("INSERT INTO card_reviews(cardId, reviewedAt, grade, intervalDays, durationMs, priorState, priorIntervalDays, deckAtReview) VALUES(?,?,?,?,?,?,?,?)").use { statement ->
                repeat(100_000) { index ->
                    val cardId = index % 8000 + 1L
                    val date = today.minusDays(index / 8000 * 28L + 1)
                    statement.bindLong(1, cardId)
                    statement.bindLong(2, date.atStartOfDay(zone).toInstant().toEpochMilli() + index % 8000)
                    statement.bindLong(3, index % 4L)
                    statement.bindLong(4, 30)
                    statement.bindLong(5, 200 + index % 15L * 100)
                    statement.bindString(6, "REVIEW")
                    statement.bindLong(7, 30)
                    statement.bindLong(8, if (cardId % 2 == 0L) child else root)
                    statement.executeInsert()
                }
            }
        }
        dao = reopen()
        val loadStart = SystemClock.elapsedRealtime()
        val cards = dao.listFlashcards()
        val reviews = dao.cardReviews().first()
        val decks = dao.listDecks()
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        val calculationStart = SystemClock.elapsedRealtime()
        val index = DeckStudyIndex(decks, cards, reviews, today, zone)
        val due = index.due(System.currentTimeMillis(), root)
        val period = ReviewPeriod(reviews, today, zone, 365)
        val history = ReviewHistory(reviews, today, zone)
        val forecast = reviewForecast(cards, today, zone)
        val calculationMs = SystemClock.elapsedRealtime() - calculationStart
        assertEquals(10_000, cards.size)
        assertEquals(100, decks.size)
        assertEquals(100_000, reviews.size)
        assertEquals(45, due.size)
        assertEquals(25, due.count { it.reviewState == "NEW" })
        assertEquals(20, due.count { it.reviewState == "REVIEW" })
        assertFalse(due.any { it.suspended })
        assertEquals(100_000, period.retentionTrials)
        assertEquals(75_000, period.recalled)
        assertEquals(13, period.counts.size)
        assertEquals(0, history.reviewedCards)
        assertEquals(100_000, period.dailyBuckets.sumOf { it.second })
        assertEquals(7600, forecast.sum())
        assertTrue("Cálculo da coleção excedeu 30 segundos: $calculationMs ms", calculationMs < 30_000)
        val encoded = dao.exportDeckFile(root, "Escala").encode()
        val reimportStart = SystemClock.elapsedRealtime()
        dao.importDeckFile(DeckFile.read(encoded.reader()))
        val reimportMs = SystemClock.elapsedRealtime() - reimportStart
        assertEquals(cards.map { it.id }.toSet(), dao.listFlashcards().map { it.id }.toSet())
        dao.startRound(due.map { it.id })
        val row = dao.reviewRound().first().first()
        assertEquals(45, dao.reviewRound().first().size)
        assertNotNull(dao.gradeCardPro(row.card, row.entry.token, 2, System.currentTimeMillis(), 1000))
        dao = reopen()
        assertEquals(100_001, dao.cardReviews().first().size)
        assertTrue(dao.reviewRound().first().first { it.card.id == row.card.id }.entry.done)
        val result = JSONObject().put("cards", cards.size).put("reviews", reviews.size).put("decks", decks.size)
            .put("initialImportMs", importMs).put("loadMs", loadMs).put("calculationMs", calculationMs)
            .put("reimportMs", reimportMs).put("exportChars", encoded.length)
        File(context.cacheDir, "studydock-scale-results.json").writeText(result.toString())
        if (InstrumentationRegistry.getArguments().getString("exportScaleFixture") == "true") {
            val current = requireNotNull(database)
            current.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            current.close()
            context.getDatabasePath(name).copyTo(File(context.cacheDir, "studydock-scale.db"), overwrite = true)
        }
    }

    @Test fun movingAnOpenCardRechecksDestinationLimits() = runBlocking {
        val dao = open()
        val from = dao.saveDeckSettings(CardDeck(name = "Origem", newLimit = 2))
        val target = dao.saveDeckSettings(CardDeck(name = "Destino", newLimit = 0))
        dao.saveCardNote(null, null, CardAuthorNote(format = "BASIC", prompt = "Q", answer = "A"), from, null)
        val card = dao.listFlashcards().single()
        dao.startRound(listOf(card.id))
        val row = dao.reviewRound().first().single()
        val author = dao.authorNote(card.authorNoteId!!)!!
        dao.saveCardNote(card.id, author, author, target, null)
        assertEquals(target, dao.findCard(card.id)!!.deckId)
        assertTrue(dao.reviewRound().first().isEmpty())
        assertNull(dao.gradeCardPro(row.card, row.entry.token, 2, System.currentTimeMillis(), 1000))
        assertTrue(dao.cardReviews().first().isEmpty())
    }

    @Test fun dissolvingADeckRechecksDestinationLimits() = runBlocking {
        val dao = open()
        val parent = dao.saveDeckSettings(CardDeck(name = "Pai", newLimit = 0))
        val child = dao.saveDeckSettings(CardDeck(name = "Filho", parentId = parent, newLimit = 5))
        dao.saveCardNote(null, null, CardAuthorNote(format = "BASIC", prompt = "Q", answer = "A"), child, null)
        val card = dao.listFlashcards().single()
        dao.startRound(listOf(card.id))
        val row = dao.reviewRound().first().single()
        dao.dissolveDeck(child)
        assertEquals(parent, dao.findCard(card.id)!!.deckId)
        assertTrue(dao.reviewRound().first().isEmpty())
        assertNull(dao.gradeCardPro(row.card, row.entry.token, 2, System.currentTimeMillis(), 1000))
        assertTrue(dao.cardReviews().first().isEmpty())
    }

    @Test fun concurrentAnswersAndEditsCommitOnlyOnce() = runBlocking {
        val dao = open()
        dao.saveCardNote(null, null, CardAuthorNote(format = "BASIC", prompt = "Q", answer = "A"), null, null)
        val card = dao.listFlashcards().single()
        dao.startRound(listOf(card.id))
        val row = dao.reviewRound().first().single()
        val now = System.currentTimeMillis()
        val answers = List(8) { async { dao.gradeCardPro(card, row.entry.token, 2, now, 500) != null } }.awaitAll()
        assertEquals(1, answers.count { it })
        assertEquals(1, dao.cardReviews().first().size)
        val source = dao.authorNote(card.authorNoteId!!)!!
        val edits = List(4) { number -> async {
            runCatching { dao.saveCardNote(card.id, source, source.copy(answer = "Resposta $number"), null, null) }.isSuccess
        } }.awaitAll()
        assertEquals(1, edits.count { it })
        val restored = reopen()
        assertEquals(1, restored.findCard(card.id)!!.repetitions)
        assertEquals(1, restored.cardReviews().first().size)
        assertEquals(500L, restored.cardReviews().first().single().durationMs)
    }

    @Test fun localCalendarIntervalsAndLearningStatesMatchTheButtons() {
        val card = Flashcard(front = "Q", back = "A", intervalDays = 10, repetitions = 3)
        for (zoneName in listOf("America/Bahia", "America/New_York", "Europe/Berlin")) {
            val zone = ZoneId.of(zoneName)
            for (day in listOf(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 31))) {
                val start = day.atTime(14, 30).atZone(zone)
                val now = start.toInstant().toEpochMilli()
                val fresh = card.copy(intervalDays = 0, repetitions = 0)
                val capped = card.copy(intervalDays = 36_500, repetitions = 100)
                val proDeck = CardDeck(name = "Pro", dayStartHour = 4, learningSteps = "1 10", relearningSteps = "10")
                assertEquals("LEARNING", fresh.afterGradePro(grade = 0, now = now, config = proDeck, zone = zone, deterministic = true).card.reviewState)
                assertEquals("LEARNING", fresh.afterGradePro(grade = 2, now = now, config = proDeck, zone = zone, deterministic = true).card.reviewState)
                assertEquals(4, fresh.afterGradePro(grade = 3, now = now, config = proDeck, zone = zone, deterministic = true).card.intervalDays)
                val proIntervals = (1..3).map {
                    card.afterGradePro(grade = it, now = now, config = proDeck, zone = zone, deterministic = true).card
                }
                assertEquals(listOf(12, 25, 33), proIntervals.map { it.intervalDays })
                proIntervals.forEach { next ->
                    val due = Instant.ofEpochMilli(next.dueAt).atZone(zone)
                    assertEquals(4, due.hour)
                    assertEquals(day.plusDays(next.intervalDays.toLong()), due.toLocalDate())
                }
                val proRelearning = card.afterGradePro(grade = 0, now = now, config = proDeck, zone = zone, deterministic = true).card
                assertEquals("RELEARNING", proRelearning.reviewState)
                assertEquals(1, proRelearning.lapses)
                assertEquals("REVIEW", proRelearning.afterGradePro(grade = 2, now = proRelearning.dueAt, config = proDeck, zone = zone, deterministic = true).card.reviewState)
                val proCapped = capped.afterGradePro(grade = 3, now = now, config = proDeck, zone = zone, deterministic = true).card
                assertEquals(36_500, proCapped.intervalDays)
            }
        }
    }

    @Test fun noteSourceSurvivesEditingAndReviewingItsCard() = runBlocking {
        val dao = open()
        val note = dao.saveNote(Note(title = "Origem", markdown = "Um trecho importante", updatedAt = 1))
        val id = dao.addFlashcard(Flashcard(front = "Q", back = "Um trecho importante", sourceNoteId = note, sourceExcerpt = "Um trecho importante"))
        dao.startRound(listOf(id))
        dao.saveCardNote(id, null, CardAuthorNote(format = "BASIC", prompt = "Pergunta editada", answer = "Resposta editada"), null, null)
        val row = dao.reviewRound().first().single()
        assertEquals(note, row.card.sourceNoteId)
        assertEquals("Um trecho importante", row.card.sourceExcerpt)
        assertNotNull(dao.gradeCardPro(row.card, row.entry.token, 2, System.currentTimeMillis(), 700))
        val restored = reopen()
        assertEquals(note, restored.findCard(id)!!.sourceNoteId)
        restored.deleteCardById(id)
        assertEquals("Origem", restored.noteTitle(note))
        assertEquals(1, restored.cardReviews().first().size)
    }

    @Test fun answerClockCountsOnlyActiveIntervals() {
        var tick = 1000L
        val clock = ActiveAnswerClock { tick }
        clock.start()
        tick += 800
        clock.start()
        assertEquals(800L, clock.drain())
        tick += 60_000
        assertEquals(0L, clock.drain())
        clock.start()
        tick += 200
        assertEquals(200L, clock.drain())
        assertEquals(0L, clock.drain())
    }
}
