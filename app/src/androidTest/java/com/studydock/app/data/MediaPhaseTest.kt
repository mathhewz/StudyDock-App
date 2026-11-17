package com.studydock.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class MediaPhaseTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dbName = "media-phase-${UUID.randomUUID()}.db"
    private val v25DbName = "media-phase-v25-${UUID.randomUUID()}.db"
    private val audioPrefsName = "test-audio-${UUID.randomUUID()}"
    private var database: StudyDatabase? = null

    private fun openDao(): StudyDao =
        StudyDatabase.openNamed(context, dbName).also { database = it }.studyDao()

    private fun reopenDao(): StudyDao {
        database?.close()
        return openDao()
    }

    private fun audioPrefs(): SharedPreferences =
        context.getSharedPreferences(audioPrefsName, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
        context.deleteDatabase(v25DbName)
        audioPrefs().edit().clear().commit()
        context.deleteSharedPreferences(audioPrefsName)
    }

    @Test
    fun upgradeFrom16To26PreservesBooksAndReadingLogsWithV26Defaults() = runBlocking {
        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { old ->
            instrumentation.context.assets.open("studydock-v16.sql").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { old.execSQL(it) }
            }
            old.execSQL(
                "INSERT INTO subjects (id, name, color, nextStep) " +
                    "VALUES (10, 'Engenharia de Software', 4284569305, 'Capítulo 3')"
            )
            old.execSQL(
                "INSERT INTO books (id, title, author, coverUri, summary, currentPage, totalPages, quotes, status, subjectId, createdAt, updatedAt) " +
                    "VALUES (20, 'Clean Architecture', 'Robert Martin', NULL, 'Resumo base', 50, 320, 'Dependências apontam para dentro (p. 42)', 'READING', 10, 1000, 2000)"
            )
            old.execSQL(
                "INSERT INTO reading_logs (id, title, pages, loggedAt) " +
                    "VALUES (30, 'Clean Architecture', 25, 1500)"
            )
            old.version = 16
        }

        val dao = openDao()

        val book = dao.bookById(20L)
        assertNotNull(book)
        book!!
        assertEquals("Clean Architecture", book.title)
        assertEquals("Robert Martin", book.author)
        assertEquals(50, book.currentPage)
        assertEquals(320, book.totalPages)
        assertEquals(10L, book.subjectId)
        assertEquals(0, book.rating)
        assertEquals("", book.categoryTag)
        assertEquals("", book.currentChapter)
        assertNull(book.targetFinishAt)
        assertEquals("", book.highlightsJson)

        val parsedHighlights = BookHighlights.parse(book)
        assertEquals(1, parsedHighlights.size)
        assertEquals("Dependências apontam para dentro", parsedHighlights.single().text)
        assertEquals(42, parsedHighlights.single().page)

        val log = dao.readingLogs().first().single { it.id == 30L }
        assertEquals("Clean Architecture", log.title)
        assertEquals(25, log.pages)
        assertEquals(1500L, log.loggedAt)
        assertNull(log.bookId)
        assertNull(log.subjectId)
        assertEquals(0, log.durationMinutes)
    }

    @Test
    fun migration25To26AltersBooksAndReadingLogsPreservingRows() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(v25DbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(26) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL(
                "CREATE TABLE books (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, coverUri TEXT, summary TEXT NOT NULL, currentPage INTEGER NOT NULL, totalPages INTEGER, quotes TEXT NOT NULL, status TEXT NOT NULL, subjectId INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE reading_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, pages INTEGER NOT NULL, loggedAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO books (id, title, author, coverUri, summary, currentPage, totalPages, quotes, status, subjectId, createdAt, updatedAt) " +
                    "VALUES (5, 'DDD', 'Eric Evans', NULL, 'Domínios', 80, 500, 'Linguagem ubíqua (p. 20)', 'READING', 2, 100, 200)"
            )
            db.execSQL(
                "INSERT INTO reading_logs (id, title, pages, loggedAt) VALUES (9, 'DDD', 30, 5000)"
            )

            StudyDatabase.MIGRATION_25_26.migrate(db)

            db.query("SELECT id, title, rating, categoryTag, currentChapter, targetFinishAt, highlightsJson FROM books WHERE id = 5").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("DDD", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals("", cursor.getString(3))
                assertEquals("", cursor.getString(4))
                assertTrue(cursor.isNull(5))
                assertEquals("", cursor.getString(6))
            }

            db.query("SELECT id, title, pages, loggedAt, bookId, subjectId, durationMinutes FROM reading_logs WHERE id = 9").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("DDD", cursor.getString(1))
                assertEquals(30, cursor.getInt(2))
                assertEquals(5000L, cursor.getLong(3))
                assertTrue(cursor.isNull(4))
                assertTrue(cursor.isNull(5))
                assertEquals(0, cursor.getInt(6))
            }
        }
    }

    @Test
    fun readingFlowPromotesToDoneAndLogsBookIdSubjectIdAndDuration() = runBlocking {
        val dao = openDao()
        val subjectId = dao.addSubject(Subject(name = "Direito Civil"))

        // 1. New WISHLIST book saved with currentPage > 0 must NOT create a ReadingLog
        val wishlistId = dao.saveBookProgress(
            Book(
                title = "Manual Desejado",
                author = "Autor A",
                currentPage = 25,
                totalPages = 200,
                status = "WISHLIST",
                subjectId = subjectId
            ),
            emptyList()
        )
        assertTrue(wishlistId > 0L)
        assertTrue(dao.readingLogs().first().isEmpty())

        // 2. New READING book saved with currentPage > 0 MUST log initial ReadingLog with bookId & subjectId
        val activeBookId = dao.saveBookProgress(
            Book(
                title = "Código Civil Comentado",
                author = "Autor B",
                currentPage = 40,
                totalPages = 100,
                status = "READING",
                subjectId = subjectId,
                rating = 5,
                categoryTag = "Jurídico",
                currentChapter = "Cap. 2",
                targetFinishAt = 1_900_000_000_000L
            ),
            emptyList()
        )
        val initialLog = dao.readingLogs().first().single()
        assertEquals(activeBookId, initialLog.bookId)
        assertEquals(subjectId, initialLog.subjectId)
        assertEquals(40, initialLog.pages)
        assertEquals(0, initialLog.durationMinutes)

        // 3. Advancing to totalPages promotes status automatically to "DONE"
        assertTrue(dao.advanceBookPage(activeBookId, 100))
        val finishedBook = reopenDao().bookById(activeBookId)!!
        assertEquals(100, finishedBook.currentPage)
        assertEquals("DONE", finishedBook.status)
        assertEquals(5, finishedBook.rating)
        assertEquals("Jurídico", finishedBook.categoryTag)
        assertEquals("Cap. 2", finishedBook.currentChapter)

        val logsAfterAdvance = dao.readingLogs().first().filter { it.bookId == activeBookId }
        assertEquals(2, logsAfterAdvance.size)
        assertEquals(60, logsAfterAdvance.last().pages)
        assertEquals(subjectId, logsAfterAdvance.last().subjectId)

        // Advancing past totalPages is rejected
        assertFalse(dao.advanceBookPage(activeBookId, 101))

        // 4. finishActivity logs ReadingLog with bookId, subjectId, durationMinutes and promotes to DONE
        val timedBookId = dao.addBook(
            Book(
                title = "Livro Cronometrado",
                author = "Autor C",
                currentPage = 20,
                totalPages = 80,
                status = "READING",
                subjectId = subjectId
            )
        )
        val startAt = System.currentTimeMillis() - 30 * 60_000L
        dao.insertTimer(
            ActivityTimer(
                mode = "Leitura",
                title = "Livro Cronometrado",
                bookId = timedBookId,
                startedAt = startAt,
                runningSince = null,
                elapsedMs = 30 * 60_000L
            )
        )
        val timerId = dao.activityTimers().first().single { it.bookId == timedBookId }.id
        dao.finishActivity(timerId, 80)

        val timedBook = dao.bookById(timedBookId)!!
        assertEquals(80, timedBook.currentPage)
        assertEquals("DONE", timedBook.status)

        val timedLog = dao.readingLogs().first().single { it.bookId == timedBookId }
        assertEquals(60, timedLog.pages)
        assertEquals(subjectId, timedLog.subjectId)
        assertEquals(30, timedLog.durationMinutes)

        // 5. updateBookHighlights updates both highlightsJson and legacy quotes
        val hl = BookHighlight(id = "h1", text = "Princípio da boa-fé", page = 12, chapter = "Cap. 1", note = "Revisar", createdAt = 10_000L)
        val (json, legacy) = BookHighlights.encodeForBook(listOf(hl))
        dao.updateBookHighlights(timedBookId, json, legacy)
        val reloaded = reopenDao().bookById(timedBookId)!!
        assertEquals(json, reloaded.highlightsJson)
        assertEquals("Princípio da boa-fé (p. 12)", reloaded.quotes)
        assertEquals(1, BookHighlights.parse(reloaded).size)
    }

    @Test
    fun bookHighlightsSupportsStructuredJsonLegacyFallbackAndCrud() {
        assertTrue(BookHighlights.parse("{broken-json").isEmpty())

        val legacyBook = Book(
            id = 1L,
            title = "O Poder do Hábito",
            author = "Charles Duhigg",
            quotes = "Deixa, rotina e recompensa (p. 42)\nSem número de página\nHábito angular (pág. 108)",
            highlightsJson = "",
            updatedAt = 5_000L
        )
        val fromLegacy = BookHighlights.parse(legacyBook)
        assertEquals(3, fromLegacy.size)
        assertEquals("Deixa, rotina e recompensa", fromLegacy[0].text)
        assertEquals(42, fromLegacy[0].page)
        assertEquals("Sem número de página", fromLegacy[1].text)
        assertNull(fromLegacy[1].page)
        assertEquals("Hábito angular", fromLegacy[2].text)
        assertEquals(108, fromLegacy[2].page)

        val addedList = BookHighlights.add(
            fromLegacy,
            BookHighlight(id = "custom_1", text = "Crença consolida a mudança", page = 155, chapter = "Cap. 3", note = "Importante", createdAt = 9_000L)
        )
        assertEquals(4, addedList.size)

        val updatedList = BookHighlights.update(
            addedList,
            addedList.last().copy(note = "Cai em prova", page = 156)
        )
        assertEquals("Cai em prova", updatedList.last().note)
        assertEquals(156, updatedList.last().page)

        val removedList = BookHighlights.remove(updatedList, fromLegacy[1].id)
        assertEquals(3, removedList.size)

        val syncedBook = BookHighlights.withHighlights(legacyBook, removedList, now = 12_000L)
        val reparsed = BookHighlights.parse(syncedBook)
        assertEquals(removedList, reparsed)
        assertTrue(syncedBook.quotes.contains("Crença consolida a mudança (p. 156)"))
        assertFalse(syncedBook.quotes.contains("Sem número de página"))
    }

    @Test
    fun bookReadingEngineComputesBookStatsAndShelfSummary() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 11, 13)
        val noon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dayMs = 86_400_000L

        val hlJson = BookHighlights.serialize(
            listOf(
                BookHighlight(id = "h1", text = "Trecho A", page = 10, createdAt = noon),
                BookHighlight(id = "h2", text = "Trecho B", page = 25, createdAt = noon)
            )
        )
        val book1 = Book(
            id = 1L,
            title = "Algoritmos",
            author = "Cormen",
            currentPage = 30,
            totalPages = 100,
            status = "READING",
            targetFinishAt = noon + 5 * dayMs,
            highlightsJson = hlJson
        )
        val book2 = Book(
            id = 2L,
            title = "Sistemas Operacionais",
            author = "Tanenbaum",
            currentPage = 10,
            totalPages = 200,
            status = "READING"
        )
        val book3 = Book(
            id = 3L,
            title = "Livro Concluído",
            author = "Autor",
            currentPage = 150,
            totalPages = 150,
            status = "DONE"
        )

        val timerStart = noon - 3_600_000L
        val timers = listOf(
            ActivityTimer(
                id = 1L,
                mode = "Leitura",
                title = "Algoritmos",
                bookId = 1L,
                startedAt = timerStart,
                runningSince = null,
                elapsedMs = 3_600_000L,
                endedAt = noon
            ),
            ActivityTimer(
                id = 2L,
                mode = "Leitura",
                title = "Algoritmos",
                bookId = 1L,
                startedAt = noon - dayMs,
                runningSince = null,
                elapsedMs = 1_800_000L,
                endedAt = noon - dayMs + 1_800_000L
            )
        )
        val logs = listOf(
            // Paired with timer 1 (same bookId & startedAt) -> must NOT double count 60 min
            ReadingLog(id = 1L, title = "Algoritmos", pages = 30, loggedAt = timerStart, bookId = 1L, durationMinutes = 60),
            ReadingLog(id = 2L, title = "Algoritmos", pages = 15, loggedAt = noon - dayMs, bookId = 1L, durationMinutes = 0),
            ReadingLog(id = 3L, title = "Algoritmos", pages = 10, loggedAt = noon - 2 * dayMs, bookId = 1L, durationMinutes = 0),
            // Gap on -3 days, then older 4-day streak (-4, -5, -6, -7 days)
            ReadingLog(id = 4L, title = "Livro Concluído", pages = 20, loggedAt = noon - 4 * dayMs, bookId = 3L),
            ReadingLog(id = 5L, title = "Livro Concluído", pages = 20, loggedAt = noon - 5 * dayMs, bookId = 3L),
            ReadingLog(id = 6L, title = "Livro Concluído", pages = 20, loggedAt = noon - 6 * dayMs, bookId = 3L),
            ReadingLog(id = 7L, title = "Livro Concluído", pages = 20, loggedAt = noon - 7 * dayMs, bookId = 3L)
        )

        val stats1 = BookReadingEngine.computeBookStats(book1, logs, timers, now = noon, zone = zone)
        assertEquals(30, stats1.progressPercent)
        assertEquals(0.3f, stats1.progressFraction, 1e-4f)
        assertEquals(70, stats1.remainingPages)
        assertEquals(90, stats1.totalMinutes)
        assertTrue(stats1.isBookSpecificPace)
        // 55 logged pages over 1.5h = 36.66 -> 37 pages/h
        assertEquals(37, stats1.pagesPerHour)
        assertEquals(30, stats1.pagesToday)
        assertEquals(55, stats1.pagesLast7Days)
        assertEquals(14.0, stats1.requiredPagesPerDay!!, 1e-6)
        assertEquals(5, stats1.daysRemainingToTarget)
        assertEquals(2, stats1.highlightCount)

        // Book 2 has no timers -> smooth fallback to shelf pace (90 min total, 135 logged pages -> 90 pág/h)
        val stats2 = BookReadingEngine.computeBookStats(book2, logs, timers, now = noon, zone = zone)
        assertFalse(stats2.isBookSpecificPace)
        assertEquals(90, stats2.pagesPerHour)
        assertEquals(0, stats2.totalMinutes)

        val shelf = BookReadingEngine.computeShelfSummary(
            books = listOf(book1, book2, book3),
            readingLogs = logs,
            activityTimers = timers,
            now = noon,
            zone = zone
        )
        assertEquals(3, shelf.totalBooks)
        assertEquals(2, shelf.readingCount)
        assertEquals(0, shelf.wishlistCount)
        assertEquals(1, shelf.doneCount)
        assertEquals(190, shelf.totalPagesRead)
        assertEquals(90, shelf.totalReadingMinutes)
        assertEquals(3, shelf.readingStreakDays)
        assertEquals(4, shelf.bestReadingStreakDays)
        assertEquals(30, shelf.pagesToday)
        // Week window is [today - 6d, today]: logs 1..6 = 30 + 15 + 10 + 20 + 20 + 20 = 115
        assertEquals(115, shelf.pagesThisWeek)
        assertEquals(2, shelf.totalHighlights)
    }

    @Test
    fun focusAudioStorePersistsSnapshotMigratesLegacyPlaylistAndManagesMixes() {
        val prefs = audioPrefs()
        prefs.edit().clear().commit()

        val legacyArray = JSONArray()
            .put(JSONObject().put("uri", "content://music/1").put("title", "Nocturne"))
            .put(JSONObject().put("uri", "content://music/2").put("title", "Rainy Study"))
        prefs.edit().putString("music_playlist", legacyArray.toString()).commit()

        val migrated = FocusAudioStore.readPlaylists(prefs)
        assertEquals(1, migrated.size)
        assertEquals(FocusAudioStore.DEFAULT_PLAYLIST_ID, migrated.single().id)
        assertEquals(FocusAudioStore.DEFAULT_PLAYLIST_NAME, migrated.single().name)
        assertEquals(2, migrated.single().tracks.size)
        assertEquals("Nocturne", migrated.single().tracks[0].title)

        // Create, rename, update tracks, delete playlists without re-triggering legacy migration
        val created = FocusAudioStore.createPlaylist(
            prefs,
            "Lo-fi Foco",
            listOf(AudioTrackItem(uri = "content://music/3", title = "Warm Keys", artist = "StudyDock", durationMs = 180_000L))
        )
        assertEquals(2, FocusAudioStore.readPlaylists(prefs).size)
        FocusAudioStore.setActivePlaylist(prefs, created.id)
        assertEquals(created.id, FocusAudioStore.activePlaylist(prefs)?.id)

        val renamed = FocusAudioStore.renamePlaylist(prefs, created.id, "Foco Profundo")
        assertEquals("Foco Profundo", renamed.first { it.id == created.id }.name)

        val afterDelete = FocusAudioStore.deletePlaylist(prefs, created.id)
        assertEquals(1, afterDelete.size)
        assertEquals(FocusAudioStore.DEFAULT_PLAYLIST_ID, FocusAudioStore.activePlaylist(prefs)?.id)

        // Custom mixes CRUD
        val mix = CustomSoundMix(
            id = "mix_library",
            name = "Biblioteca & Chuva",
            volumes = mapOf("library" to 0.55f, "rain" to 0.40f),
            masterVolume = 0.80f
        )
        FocusAudioStore.saveCustomMix(prefs, mix)
        val storedMixes = FocusAudioStore.readCustomMixes(prefs)
        assertEquals(1, storedMixes.size)
        assertEquals("Biblioteca & Chuva", storedMixes.single().name)
        assertEquals(0.55f, storedMixes.single().volumes["library"]!!, 1e-4f)
        assertEquals(0.80f, storedMixes.single().masterVolume, 1e-4f)

        FocusAudioStore.deleteCustomMix(prefs, "mix_library")
        assertTrue(FocusAudioStore.readCustomMixes(prefs).isEmpty())

        // Snapshot full round-trip + targeted partial updates
        val snapshot = AudioPlaybackSnapshot(
            ambientPlaying = true,
            activeSounds = setOf("binaural_alpha", "rain"),
            soundVolumes = mapOf("binaural_alpha" to 0.45f, "rain" to 0.60f),
            ambientMasterVolume = 0.90f,
            musicPlaying = true,
            activePlaylistId = FocusAudioStore.DEFAULT_PLAYLIST_ID,
            currentTrackIndex = 1,
            currentTrackTitle = "Rainy Study",
            currentTrackArtist = "Artist X",
            positionMs = 42_000L,
            durationMs = 240_000L,
            shuffle = true,
            repeatMode = FocusAudioStore.REPEAT_ONE,
            sleepTimerEndAt = 1_900_000_999_000L,
            autoPlayOnStudy = true,
            autoPlayOnReading = true,
            autoPauseOnBreak = false,
            selectedStudyTarget = "mix:mix_library",
            selectedReadingTarget = "playlist:favorites"
        )
        FocusAudioStore.writeSnapshot(prefs, snapshot)

        val readBack = FocusAudioStore.readSnapshot(prefs)
        assertTrue(readBack.ambientPlaying)
        assertEquals(setOf("binaural_alpha", "rain"), readBack.activeSounds)
        assertEquals(0.45f, readBack.soundVolumes["binaural_alpha"]!!, 1e-4f)
        assertEquals(0.90f, readBack.ambientMasterVolume, 1e-4f)
        assertTrue(readBack.musicPlaying)
        assertEquals(1, readBack.currentTrackIndex)
        assertEquals("Rainy Study", readBack.currentTrackTitle)
        assertEquals("Artist X", readBack.currentTrackArtist)
        assertEquals(42_000L, readBack.positionMs)
        assertEquals(240_000L, readBack.durationMs)
        assertTrue(readBack.shuffle)
        assertEquals(FocusAudioStore.REPEAT_ONE, readBack.repeatMode)
        assertEquals(1_900_000_999_000L, readBack.sleepTimerEndAt)
        assertTrue(readBack.autoPlayOnStudy)
        assertTrue(readBack.autoPlayOnReading)
        assertFalse(readBack.autoPauseOnBreak)
        assertEquals("mix:mix_library", readBack.selectedStudyTarget)
        assertEquals("playlist:favorites", readBack.selectedReadingTarget)

        // Partial update must preserve unrelated fields
        FocusAudioStore.updateMusicPlayback(prefs, positionMs = 95_000L, musicPlaying = false)
        FocusAudioStore.setSleepTimerEndAt(prefs, null)
        val afterPartial = FocusAudioStore.readSnapshot(prefs)
        assertFalse(afterPartial.musicPlaying)
        assertEquals(95_000L, afterPartial.positionMs)
        assertNull(afterPartial.sleepTimerEndAt)
        assertTrue(afterPartial.ambientPlaying)
        assertTrue(afterPartial.autoPlayOnStudy)
        assertEquals("mix:mix_library", afterPartial.selectedStudyTarget)
    }
}
