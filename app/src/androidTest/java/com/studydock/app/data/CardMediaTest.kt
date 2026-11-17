package com.studydock.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class CardMediaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "card-media-${UUID.randomUUID()}.db"
    private var database: StudyDatabase? = null
    private fun open(): StudyDao = StudyDatabase.openNamed(context, name).also { database = it }.studyDao()
    private fun reopen(): StudyDao { database?.close(); return open() }
    private fun image(color: Int): CardImage {
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray().let { CardImage(CardPictures.key(it), it) }
    }

    @After fun cleanup() { database?.close(); context.deleteDatabase(name) }

    @Test fun picturesSurviveSharingReopeningAndIndividualDeletion() = runBlocking {
        var dao = open()
        val image = image(android.graphics.Color.BLUE)
        val deck = dao.saveDeckSettings(CardDeck(name = "Biologia"))
        val source = CardAuthorNote(format = "REVERSE", prompt = "**Célula**", answer = "Unidade da vida", imageKey = image.key)
        dao.saveCardNote(null, null, source, deck, null, image)
        val original = dao.cardsInDeck(deck)
        val front = original.first()
        dao.startRound(listOf(front.id))
        val round = dao.reviewRound().first().single()
        assertNotNull(dao.gradeCardPro(round.card, round.entry.token, 2, System.currentTimeMillis(), 500))
        dao = reopen()
        assertArrayEquals(image.bytes, dao.answerImage(front.authorNoteId!!)!!)
        val shared = DeckFile.read(dao.exportDeckFile(deck, "Importado").encode().reader())
        assertEquals(setOf(image.key), shared.images.keys)
        assertEquals(setOf("front", "reverse"), shared.notes.single().keys)
        val importedId = dao.importDeckFile(shared)
        val imported = dao.cardsInDeck(importedId)
        assertEquals(2, imported.size)
        assertArrayEquals(image.bytes, dao.answerImage(imported.first().authorNoteId!!)!!)
        dao.importDeckFile(shared)
        assertEquals(imported.map { it.id }.toSet(), dao.cardsInDeck(importedId).map { it.id }.toSet())
        dao.deleteCardById(front.id)
        assertTrue(dao.reviewRound().first().isEmpty())
        assertNotNull(dao.authorNote(front.authorNoteId!!))
        dao.deleteCardById(original.last().id)
        assertNull(dao.authorNote(front.authorNoteId!!))
        assertNotNull(dao.imageBytes(image.key)) // The imported note still uses the same bytes.
        imported.forEach { dao.deleteCardById(it.id) }
        dao = reopen()
        assertNull(dao.imageBytes(image.key))
        assertEquals(1, dao.cardReviews().first().size)
        assertEquals(500L, dao.cardReviews().first().single().durationMs)
    }

    @Test fun editingClozeKeepsDetachedCardPictureAndRejectsStaleChanges() = runBlocking {
        var dao = open()
        val picture = image(android.graphics.Color.GREEN)
        val source = CardAuthorNote(format = "CLOZE", prompt = "{{c1::A}} e {{c2::B}}", answer = "", imageKey = picture.key)
        dao.saveCardNote(null, null, source, null, null, picture)
        val cards = dao.listFlashcards()
        val original = cards.single { it.variantKey == "c1" }
        val removedVariant = cards.single { it.variantKey == "c2" }
        val stored = dao.authorNote(original.authorNoteId!!)!!
        dao.saveCardNote(original.id, stored, stored.copy(prompt = "{{c1::A}} e B", imageKey = null), null, null)
        dao = reopen()
        val detached = dao.findCard(removedVariant.id)!!
        assertEquals(removedVariant.front, detached.front)
        assertNotEquals(original.authorNoteId, detached.authorNoteId)
        assertArrayEquals(picture.bytes, dao.answerImage(detached.authorNoteId!!)!!)
        assertNull(dao.answerImage(original.authorNoteId!!))
        assertTrue(runCatching { dao.saveCardNote(original.id, stored, stored, null, null) }.isFailure)
        dao.deleteCardById(detached.id)
        assertNull(dao.imageBytes(picture.key))
        assertEquals(1, dao.listFlashcards().size)
    }

    @Test fun malformedMediaRollsBackTheWholeImport() = runBlocking {
        val dao = open()
        val valid = image(android.graphics.Color.RED)
        val source = CardAuthorNote(format = "BASIC", prompt = "Q", answer = "A", imageKey = valid.key)
        val note = SharedCardNote(source, setOf("front"))
        val file = DeckFile("Inválido", listOf(note), mapOf(valid.key to valid.bytes))
        val wrongHash = file.copy(images = mapOf(valid.key to byteArrayOf(1, 2, 3)))
        assertTrue(runCatching { dao.importDeckFile(wrongHash) }.isFailure)
        assertTrue(runCatching { dao.importDeckFile(file.copy(images = emptyMap())) }.isFailure)
        val badVariant = file.copy(notes = listOf(note, note.copy(keys = setOf("ausente"))))
        assertTrue(runCatching { dao.importDeckFile(badVariant) }.isFailure)
        val unreadable = byteArrayOf(4, 5, 6)
        assertTrue(runCatching { CardPictures.validate(CardImage(CardPictures.key(unreadable), unreadable)) }.isFailure)
        val restored = reopen()
        assertTrue(restored.listDecks().isEmpty())
        assertTrue(restored.listFlashcards().isEmpty())
        assertNull(restored.imageBytes(valid.key))
    }

    @Test fun documentPicturesAreNormalizedBeforeStorage() {
        val bitmap = Bitmap.createBitmap(1900, 1400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        val file = File(context.cacheDir, "picture-test-${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val normalized = CardPictures.fromDocument(context.contentResolver, Uri.fromFile(file))
            CardPictures.validate(normalized)
            val result = BitmapFactory.decodeByteArray(normalized.bytes, 0, normalized.bytes.size)
            assertEquals(1280, result.width)
            assertTrue(result.height in 900..950)
            assertTrue(normalized.bytes.size <= CardPictures.MAX_BYTES)
            result.recycle()
        } finally { bitmap.recycle(); file.delete() }
    }
}
