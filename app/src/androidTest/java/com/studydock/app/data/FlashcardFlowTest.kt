package com.studydock.app.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FlashcardFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "flashcard-test-${UUID.randomUUID()}.db"
    private var database: StudyDatabase? = null
    private fun open(): StudyDao = StudyDatabase.openNamed(context, name).also { database = it }.studyDao()
    private fun reopen(): StudyDao {
        database?.close()
        return open()
    }

    @After fun cleanup() {
        database?.close()
        context.deleteDatabase(name)
    }

    @Test fun upgradeFrom16PreservesCardsAndPastReviews() = runBlocking {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            instrumentation.context.assets.open("studydock-v16.sql").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { old.execSQL(it) }
            }
            old.execSQL("INSERT INTO card_decks (id,name) VALUES(3,'Biologia')")
            old.execSQL("INSERT INTO flashcards (id,front,back,dueAt,intervalDays,easeFactor,repetitions,lapses,deckId) VALUES(4,'Frente','Verso',800,0,2.2,0,1,3)")
            old.execSQL("INSERT INTO card_reviews VALUES(1,4,700,2,6)")
            old.version = 16
        }
        val dao = open()
        val card = dao.findCard(4)!!
        assertEquals("RELEARNING", card.reviewState)
        assertEquals(3L, card.deckId)
        assertNull(card.authorNoteId)
        assertEquals("Verso", card.back)
        assertFalse(card.suspended)
        dao.deleteFlashcard(card)
        val oldReview = reopen().cardReviews().first().single()
        assertEquals(4L, oldReview.cardId)
        assertNull(oldReview.durationMs)
        assertNull(oldReview.priorState)
    }

    @Test fun authoringGeneratesAndEditsSiblingsWithoutResettingSchedules() = runBlocking {
        val dao = open()
        val reverse = CardAuthorNote(format = "REVERSE", prompt = "Planeta vermelho", answer = "Marte")
        dao.saveCardNote(null, null, reverse, null, null)
        val cards = dao.flashcards().first()
        assertEquals(2, cards.size)
        val first = cards.single { it.variantKey == "front" }
        val sibling = cards.single { it.variantKey == "reverse" }
        dao.updateFlashcard(sibling.copy(intervalDays = 24, repetitions = 5))
        val note = dao.authorNote(first.authorNoteId!!)!!
        dao.saveCardNote(first.id, note, note.copy(prompt = "Qual é o planeta vermelho?"), null, null)
        assertEquals(24, dao.findCard(sibling.id)!!.intervalDays)
        assertEquals("Qual é o planeta vermelho?", dao.findCard(sibling.id)!!.back)
        assertTrue(runCatching { dao.saveCardNote(first.id, note, note, null, null) }.isFailure)
        val cloze = CardAuthorNote(format = "CLOZE", prompt = "{{c1::A::vogal}} e {{c2::B}}", answer = "Letras")
        dao.saveCardNote(null, null, cloze, null, null)
        assertEquals(setOf("c1","c2"), cloze.variants().keys)
        assertEquals("[vogal] e B", cloze.variants().getValue("c1").first)
        val model = CardAuthorNote(format = "CUSTOM", template = "Termos", prompt = "{{Palavra}}?", answer = "{{Sentido}}",
            fields = """{"Palavra":"Órbita","Sentido":"Trajetória"}""")
        dao.saveCardNote(null, null, model, null, null)
        val restored = reopen()
        assertEquals("Termos", restored.cardTemplates().first().single().name)
        assertTrue(restored.flashcards().first().any { it.front == "Órbita?" && it.back == "Trajetória" })
        val invalid = model.copy(fields = "{}")
        assertTrue(runCatching { restored.saveCardNote(null, null, invalid, null, null) }.isFailure)
        assertEquals(5, restored.flashcards().first().size)
    }

    @Test fun deckHierarchyLimitsMovesAndHistory() = runBlocking {
        val dao = open()
        val parent = dao.saveDeckSettings(CardDeck(name = "Biologia", newLimit = 1, dailyGoal = 5))
        val child = dao.saveDeckSettings(CardDeck(name = "Células", parentId = parent))
        suspend fun create(name: String) = dao.addFlashcard(Flashcard(front = name, back = "Resposta", deckId = child, dueAt = 1))
        val a = create("A")
        val b = create("B")
        val c = create("C")
        dao.setSuspended(c, true)
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.of("America/Bahia")
        val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        suspend fun index(date: java.time.LocalDate = day) = DeckStudyIndex(dao.listDecks(), dao.flashcards().first(), dao.cardReviews().first(), date, zone)
        assertEquals(listOf(a), index().due(now, parent).map { it.id })
        dao.startRound(listOf(a))
        val row = dao.reviewRound().first().single()
        assertNotNull(dao.gradeCardPro(row.card, row.entry.token, 2, now, 300))
        assertTrue(index().due(now, parent).isEmpty())
        assertEquals(1, index().doneToday(parent))
        assertEquals(listOf(b), index(day.plusDays(1)).due(now, child).map { it.id })
        assertTrue(runCatching { dao.saveDeckSettings(CardDeck(parent, "Biologia", child)) }.isFailure)
        assertTrue(runCatching { dao.saveDeckSettings(CardDeck(name = "cÉluLas", parentId = parent)) }.isFailure)
        dao.saveDeckSettings(CardDeck(child, "Citologia", parent))
        dao.dissolveDeck(child)
        assertEquals(parent, dao.findCard(a)!!.deckId)
        assertEquals(child, dao.cardReviews().first().single().deckAtReview)
        dao.dissolveDeck(parent)
        assertNull(dao.findCard(b)!!.deckId)
        dao.setSuspended(c, false)
        assertEquals(setOf(b,c), index().due(now, 0).map { it.id }.toSet())
    }

    @Test fun sharedDeckRoundTripMergesVariantsAndRollsBackMalformedImports() = runBlocking {
        val dao = open()
        val reverse = CardAuthorNote(format = "REVERSE", prompt = "H", answer = "Hidrogênio")
        val first = SharedCardNote(reverse, setOf("front"))
        val other = SharedCardNote(reverse, setOf("reverse"))
        val id = dao.importDeck("Elementos", listOf(first))
        val shared = DeckFile.read(DeckFile("Elementos", listOf(other, first)).encode().reader())
        dao.importDeck(shared.name, shared.notes)
        val cards = dao.cardsInDeck(id)
        assertEquals(2, cards.size)
        assertEquals(1, cards.map { it.authorNoteId }.distinct().size)
        val reexported = DeckFile.read(DeckFile("Elementos", dao.sharedNotes(id)).encode().reader())
        assertEquals(setOf("front","reverse"), reexported.notes.single().keys)
        dao.importDeck(reexported.name, reexported.notes)
        assertEquals(cards.map { it.id }.toSet(), dao.cardsInDeck(id).map { it.id }.toSet())
        val custom = CardAuthorNote(format = "CUSTOM", prompt = "{{A}}", answer = "{{B}}", template = "Teste",
            fields = """{"A":"H","B":"Hidrogênio"}""")
        val reordered = custom.copy(fields = """{"B":"Hidrogênio","A":"H"}""")
        dao.importDeck("Elementos", listOf(custom, reordered).map { SharedCardNote(it, setOf("front")) })
        assertEquals(3, dao.cardsInDeck(id).size)
        val legacy = DeckFile.read("""{"format":"StudyDock.deck.v1","name":"Antigo","cards":[{"front":"Q","back":"A"}]}""".reader())
        assertEquals("A", dao.cardsInDeck(dao.importDeck(legacy.name, legacy.notes)).single().back)
        assertTrue(runCatching {
            dao.importDeck("Incompleto", listOf(first, other.copy(keys = setOf("inexistente"))))
        }.isFailure)
        assertFalse(reopen().listDecks().any { it.name == "Incompleto" })
    }

    @Test fun roundHonorsChangedLimitsAndKeepsThePendingRetry() = runBlocking {
        val dao = open()
        val deck = CardDeck(name = "História")
        val id = dao.saveDeckSettings(deck)
        val ids = (1..4).map { dao.addFlashcard(Flashcard(front = "Q$it", back = "A", deckId = id, dueAt = 1)) }
        dao.saveDeckSettings(deck.copy(id = id, newLimit = 3))
        dao.startRound(ids)
        val firstRound = dao.reviewRound().first()
        assertEquals(ids.take(3), firstRound.map { it.card.id })
        dao.startRound(ids.takeLast(1))
        assertEquals(firstRound, dao.reviewRound().first())
        val first = firstRound.first()
        assertNotNull(dao.gradeCardPro(first.card, first.entry.token, 0, System.currentTimeMillis(), 2000))
        dao.saveDeckSettings(deck.copy(id = id, newLimit = 1))
        val retry = reopen().reviewRound().first().single()
        assertEquals(first.card.id, retry.card.id)
        assertEquals("LEARNING", retry.card.reviewState)
        assertFalse(retry.entry.done)
    }

    @Test fun roundRecordsEachAnswerOnceAndSurvivesReopening() = runBlocking {
        var dao = open()
        val id = dao.addFlashcard(Flashcard(front = "Qual?", back = "Esta", dueAt = 1))
        dao.startRound(listOf(id, id))
        val first = dao.reviewRound().first().single()
        dao.addRoundTime(id, first.entry.token, first.card.dueAt, 1200)
        dao = reopen()
        val resumed = dao.reviewRound().first().single()
        assertEquals(1200L, resumed.entry.elapsedMs)
        val time = System.currentTimeMillis() - 900_000L
        assertNotNull(dao.gradeCardPro(resumed.card, resumed.entry.token, 0, time, 800))
        assertNull(dao.gradeCardPro(resumed.card, resumed.entry.token, 0, time, 800))
        val retry = dao.reviewRound().first().single()
        assertEquals("LEARNING", retry.card.reviewState)
        assertFalse(retry.entry.done)
        assertNull(dao.gradeCardPro(retry.card, retry.entry.token, 2, time + 59_999, 300))
        val step1 = dao.gradeCardPro(retry.card, retry.entry.token, 2, time + 60_000, 300)
        assertNotNull(step1)
        assertEquals("LEARNING", step1!!.outcome.card.reviewState)
        assertEquals(1, step1.outcome.card.learningStep)
        assertTrue(dao.reviewRound().first().single().entry.done)
        dao.clearReviewRound()
        dao.startRound(listOf(id))
        val step1Row = dao.reviewRound().first().single()
        assertNotNull(dao.gradeCardPro(step1Row.card, step1Row.entry.token, 2, step1Row.card.dueAt, 400))
        val reviews = dao.cardReviews().first()
        assertEquals(listOf(400L, 300L, 2000L), reviews.map { it.durationMs })
        assertEquals(listOf("LEARNING", "LEARNING", "NEW"), reviews.map { it.priorState })
        assertTrue(dao.reviewRound().first().single().entry.done)
        dao.clearReviewRound()
        dao = reopen()
        assertTrue(dao.reviewRound().first().isEmpty())
        assertEquals("REVIEW", dao.findCard(id)!!.reviewState)
        assertEquals(3, dao.cardReviews().first().size)
    }
}
