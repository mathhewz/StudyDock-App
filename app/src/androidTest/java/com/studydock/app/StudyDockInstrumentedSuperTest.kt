package com.studydock.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.studydock.app.data.CardAuthorNote
import com.studydock.app.data.CardDeck
import com.studydock.app.data.Flashcard
import com.studydock.app.data.Note
import com.studydock.app.data.StudyDao
import com.studydock.app.data.StudyDatabase
import com.studydock.app.data.Subject
import com.studydock.app.data.reviewState
import com.studydock.app.ui.encodeExtraPrefsBundle
import com.studydock.app.ui.restoreExtraPrefsBundle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Superteste instrumentado único validando Room DAO, projeções leves,
 * agendador gradeCardPro, edição rápida sincronizada e backup/restore de preferências.
 */
class StudyDockInstrumentedSuperTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "supertest-${UUID.randomUUID()}.db"
    private var database: StudyDatabase? = null

    private fun open(): StudyDao = StudyDatabase.openNamed(context, dbName).also { database = it }.studyDao()

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun endToEndRoomProjectionsProSchedulerAndPrefsBackup() = runBlocking {
        val dao = open()

        // 1. Note headers projection caps markdown at 2048 chars while keeping full note intact
        val longBody = "A".repeat(4096)
        val noteId = dao.saveNote(Note(title = "Nota Longa", markdown = longBody, updatedAt = 100L))
        val header = dao.noteHeadersFlow().first().single { it.id == noteId }
        assertEquals(2048, header.markdown.length)
        assertEquals(4096, dao.findNote(noteId)!!.markdown.length)

        // 2. Scoped flashcard flows by subject & note
        val subjId = dao.addSubject(Subject(name = "Cálculo I", color = 0xFF2563EB))
        val deckId = dao.saveDeckSettings(CardDeck(name = "Cálculo I · Arena"))
        dao.saveCardNote(
            originalId = null,
            expected = null,
            note = CardAuthorNote(format = "BASIC", prompt = "Derivada de $\\ln x$", answer = "$1/x$", tags = "arena-exatas"),
            deckId = deckId,
            subjectId = subjId,
            image = null
        )
        val scopedCards = dao.flashcardsForSubject(subjId).first()
        assertEquals(1, scopedCards.size)
        val card = scopedCards.single()

        // 3. gradeCardPro + revertLastReview consistency
        dao.startRound(listOf(card.id))
        val row = dao.reviewRound().first().single()
        val now = System.currentTimeMillis()
        val graded = dao.gradeCardPro(row.card, row.entry.token, 2, now, 650L)
        assertNotNull(graded)
        assertEquals("LEARNING", graded!!.outcome.card.reviewState)
        assertEquals(1, dao.reviewsForCard(card.id).size)

        dao.revertLastReview(
            priorCard = graded.priorCard,
            priorEntry = graded.priorEntry,
            reviewReviewedAt = graded.outcome.review.reviewedAt,
            buriedSiblingsBefore = graded.buriedSiblingsBefore,
            removedSiblingEntries = graded.removedSiblingEntries
        )
        assertTrue(dao.reviewsForCard(card.id).isEmpty())
        assertEquals("NEW", dao.findCard(card.id)!!.reviewState)

        // 4. saveQuickCardEdit syncs card_author_notes
        dao.saveQuickCardEdit(card, "Derivada de \$e^x\$", "\$e^x\$", "calculo, arena")
        val syncedAuthor = dao.authorNote(card.authorNoteId!!)!!
        assertEquals("Derivada de \$e^x\$", syncedAuthor.prompt)
        assertEquals("\$e^x\$", syncedAuthor.answer)

        // 5. Extra SharedPreferences backup & restore round-trip
        val settingsPrefs = context.getSharedPreferences("studydock_settings", Context.MODE_PRIVATE)
        settingsPrefs.edit().putInt("daily_goal_min", 240).putBoolean("dark_mode_pref_test", true).apply()
        val bundle = encodeExtraPrefsBundle(context)
        settingsPrefs.edit().clear().apply()
        assertFalse(settingsPrefs.contains("daily_goal_min"))
        restoreExtraPrefsBundle(context, bundle)
        assertEquals(240, settingsPrefs.getInt("daily_goal_min", 0))
        assertTrue(settingsPrefs.getBoolean("dark_mode_pref_test", false))
    }
}
