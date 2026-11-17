package com.studydock.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private var database: StudyDatabase? = null
    private val createdDbNames = mutableListOf<String>()

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun nextDbName(prefix: String): String =
        "$prefix-${UUID.randomUUID()}.db".also(createdDbNames::add)

    @After
    fun tearDown() {
        database?.close()
        database = null
        val context = appContext()
        createdDbNames.forEach { name ->
            context.deleteDatabase(name)
        }
        createdDbNames.clear()
    }

    @Test
    fun migration26To27CreatesAllExpectedIndicesAndPreservesRows() {
        assertEquals(26, StudyDatabase.MIGRATION_26_27.startVersion)
        assertEquals(27, StudyDatabase.MIGRATION_26_27.endVersion)

        val context = appContext()
        val dbName = nextDbName("studydock-v26-direct")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(26) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `subjects` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL,
                                `color` INTEGER NOT NULL,
                                `nextStep` TEXT,
                                `targetMinutesPerDay` INTEGER NOT NULL DEFAULT 0,
                                `weight` INTEGER NOT NULL DEFAULT 1,
                                `examDate` INTEGER,
                                `archived` INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `study_sessions` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `subjectId` INTEGER,
                                `startedAt` INTEGER NOT NULL,
                                `endedAt` INTEGER NOT NULL,
                                `durationSeconds` INTEGER NOT NULL,
                                `isActive` INTEGER NOT NULL DEFAULT 0,
                                `topic` TEXT NOT NULL DEFAULT '',
                                `mode` TEXT NOT NULL DEFAULT 'STOPWATCH',
                                `breakSeconds` INTEGER NOT NULL DEFAULT 0,
                                `taskId` INTEGER,
                                FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `reading_logs` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `title` TEXT NOT NULL,
                                `pages` INTEGER NOT NULL,
                                `loggedAt` INTEGER NOT NULL,
                                `bookId` INTEGER,
                                `subjectId` INTEGER,
                                `durationMinutes` INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `study_tasks` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `subjectId` INTEGER,
                                `title` TEXT NOT NULL,
                                `dueAt` INTEGER NOT NULL,
                                `completed` INTEGER NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `completedAt` INTEGER,
                                `previousTaskId` INTEGER,
                                `priority` INTEGER NOT NULL DEFAULT 4,
                                `recurrence` TEXT NOT NULL DEFAULT 'NONE',
                                `noteId` INTEGER,
                                `details` TEXT NOT NULL DEFAULT '',
                                `hasDueDate` INTEGER NOT NULL DEFAULT 1,
                                `estimatedMinutes` INTEGER NOT NULL DEFAULT 0,
                                `subtasksJson` TEXT NOT NULL DEFAULT '',
                                `tags` TEXT NOT NULL DEFAULT '',
                                `bookId` INTEGER,
                                `afterCompletion` INTEGER NOT NULL DEFAULT 0,
                                FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `notes` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `title` TEXT NOT NULL,
                                `markdown` TEXT NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                `pinned` INTEGER NOT NULL DEFAULT 0,
                                `revision` INTEGER NOT NULL DEFAULT 0,
                                `trashedAt` INTEGER
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `journal_entries` (
                                `dayKey` INTEGER NOT NULL,
                                `title` TEXT NOT NULL,
                                `body` TEXT NOT NULL,
                                `mood` TEXT NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                `energy` INTEGER NOT NULL DEFAULT 3,
                                `focus` INTEGER NOT NULL DEFAULT 3,
                                `tags` TEXT NOT NULL DEFAULT '',
                                `promptUsed` TEXT NOT NULL DEFAULT '',
                                `linkedNoteId` INTEGER,
                                PRIMARY KEY(`dayKey`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `calendar_events` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `title` TEXT NOT NULL,
                                `at` INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO `subjects` (`id`, `name`, `color`) VALUES (1, 'Algoritmos', -16776961)")
            db.execSQL(
                "INSERT INTO `study_sessions` (`id`, `subjectId`, `startedAt`, `endedAt`, `durationSeconds`, `isActive`) VALUES (10, 1, 1700000000000, 1700001800000, 1800, 0)",
            )
            db.execSQL(
                "INSERT INTO `reading_logs` (`id`, `title`, `pages`, `loggedAt`, `bookId`, `subjectId`, `durationMinutes`) VALUES (20, 'Grafos', 32, 1700005000000, 7, 1, 45)",
            )
            db.execSQL(
                "INSERT INTO `study_tasks` (`id`, `subjectId`, `title`, `dueAt`, `completed`, `createdAt`) VALUES (30, 1, 'Resolver lista 1', 1700010000000, 0, 1699990000000)",
            )
            db.execSQL(
                "INSERT INTO `notes` (`id`, `title`, `markdown`, `updatedAt`, `pinned`, `revision`, `trashedAt`) VALUES (40, 'Resumo BFS', '# BFS', 1700020000000, 1, 2, NULL)",
            )
            db.execSQL(
                "INSERT INTO `journal_entries` (`dayKey`, `title`, `body`, `mood`, `updatedAt`) VALUES (20250115, 'Bom dia', 'Foco alto', 'GREAT', 1700030000000)",
            )
            db.execSQL(
                "INSERT INTO `calendar_events` (`id`, `title`, `at`) VALUES (50, 'Simulado', 1700040000000)",
            )

            val indicesBefore = readIndexTableMap(db)
            EXPECTED_V27_INDICES.keys.forEach { expectedName ->
                assertFalse("Index $expectedName should not exist at v26", expectedName in indicesBefore)
            }

            StudyDatabase.MIGRATION_26_27.migrate(db)
            // Running again validates CREATE INDEX IF NOT EXISTS idempotency.
            StudyDatabase.MIGRATION_26_27.migrate(db)

            val indicesAfter = readIndexTableMap(db)
            EXPECTED_V27_INDICES.forEach { (indexName, expectedSpec) ->
                val (expectedTable, expectedColumn) = expectedSpec
                assertEquals(
                    "Missing or mismatched table for $indexName",
                    expectedTable,
                    indicesAfter[indexName],
                )
                assertEquals(
                    "Unexpected indexed column for $indexName",
                    listOf(expectedColumn),
                    readIndexedColumns(db, indexName),
                )
            }

            assertEquals(1800, querySingleInt(db, "SELECT durationSeconds FROM study_sessions WHERE id = 10"))
            assertEquals(32, querySingleInt(db, "SELECT pages FROM reading_logs WHERE id = 20"))
            assertEquals("Resolver lista 1", querySingleString(db, "SELECT title FROM study_tasks WHERE id = 30"))
            assertEquals("Resumo BFS", querySingleString(db, "SELECT title FROM notes WHERE id = 40"))
            assertEquals("GREAT", querySingleString(db, "SELECT mood FROM journal_entries WHERE dayKey = 20250115"))
            assertEquals("Simulado", querySingleString(db, "SELECT title FROM calendar_events WHERE id = 50"))
        }
    }

    @Test
    fun upgradeFrom16To27ViaOpenNamedPreservesDataAndVerifiesV27Schema() = runBlocking {
        val context = appContext()
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        val dbName = nextDbName("studydock-v16-to-v27")
        val statements = instrumentationContext.assets.open("studydock-v16.sql")
            .bufferedReader()
            .use { it.readText() }
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        statements.forEach(db::execSQL)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO `subjects` (`id`, `name`, `color`, `nextStep`) VALUES (1, 'Física', -65536, 'Revisar óptica')")
            db.execSQL(
                "INSERT INTO `study_sessions` (`id`, `subjectId`, `startedAt`, `endedAt`, `durationSeconds`, `isActive`) VALUES (101, 1, 1700000000000, 1700003600000, 3600, 0)",
            )
            db.execSQL(
                "INSERT INTO `reading_logs` (`id`, `title`, `pages`, `loggedAt`) VALUES (102, 'Halliday Cap. 3', 24, 1700001000000)",
            )
            db.execSQL(
                "INSERT INTO `study_tasks` (`id`, `subjectId`, `title`, `dueAt`, `completed`, `createdAt`, `priority`, `recurrence`, `details`) VALUES (103, 1, 'Fazer 10 questões', 1700010000000, 0, 1699990000000, 1, 'NONE', '')",
            )
            db.execSQL(
                "INSERT INTO `notes` (`id`, `title`, `markdown`, `updatedAt`, `pinned`) VALUES (104, 'Leis de Newton', '# Dinâmica', 1700020000000, 0)",
            )
            db.execSQL(
                "INSERT INTO `journal_entries` (`dayKey`, `title`, `body`, `mood`, `updatedAt`) VALUES (20250201, 'Estudo sólido', 'Terminei o capítulo', 'GOOD', 1700030000000)",
            )
            db.execSQL(
                "INSERT INTO `calendar_events` (`id`, `title`, `at`) VALUES (105, 'Prova Parcial', 1700040000000)",
            )
        }

        val upgraded = StudyDatabase.openNamed(context, dbName).also { database = it }
        val dao = upgraded.studyDao()

        val sessions = dao.recentSessions(0L).first()
        val readings = dao.recentReadingLogs(0L).first()
        val openTasks = dao.openTasks().first()
        val activeNotesCount = dao.activeNotesCount().first()
        val journals = dao.recentJournalEntries(0L).first()
        val events = dao.upcomingEvents(0L).first()

        val readable = upgraded.openHelper.readableDatabase
        // StudyDatabase is now v32; openNamed migrates v16 -> v32 while preserving all expected indices.
        assertEquals(32, querySingleInt(readable, "PRAGMA user_version"))

        val indices = readIndexTableMap(readable)
        assertSingleColumnIndices(readable, indices, EXPECTED_V27_INDICES + EXPECTED_V28_INDICES + EXPECTED_V29_INDICES)
        assertMultiColumnIndices(readable, indices, EXPECTED_V30_INDICES + EXPECTED_V31_INDICES)

        assertEquals(1, sessions.size)
        assertEquals(3600, sessions.single().durationSeconds)
        assertEquals(1, readings.size)
        assertEquals("Halliday Cap. 3", readings.single().title)
        assertEquals(1, openTasks.size)
        assertEquals("Fazer 10 questões", openTasks.single().title)
        assertEquals(1, activeNotesCount)
        assertEquals(1, journals.size)
        assertEquals(20250201L, journals.single().dayKey)
        assertEquals(1, events.size)
        assertEquals("Prova Parcial", events.single().title)
    }

    @Test
    fun migration27To28AddsHabitCustomizationTablesAndBookDocColumns() = runBlocking {
        assertEquals(27, StudyDatabase.MIGRATION_27_28.startVersion)
        assertEquals(28, StudyDatabase.MIGRATION_27_28.endVersion)

        val context = appContext()
        val dbName = nextDbName("studydock-v27-to-v28")
        val legacyCreatedAt = 1700000000000L
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        V27_SCHEMA_STATEMENTS.forEach(db::execSQL)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO `habits` (`id`, `title`, `emoji`, `createdAt`, `area`, `kind`, `repeatDaysMask`, `targetValue`, `targetUnit`, `targetPeriod`, `reminderMinutes`) " +
                    "VALUES (10, 'Ler 20 páginas', '📚', $legacyCreatedAt, 'Noite', 'GOOD', 127, 20.0, 'páginas', 'dia', 1260)",
            )
            db.execSQL(
                "INSERT INTO `books` (`id`, `title`, `author`, `coverUri`, `summary`, `currentPage`, `totalPages`, `quotes`, `status`, `subjectId`, `createdAt`, `updatedAt`, `rating`, `categoryTag`, `currentChapter`, `targetFinishAt`, `highlightsJson`) " +
                    "VALUES (20, 'Clean Code', 'Robert Martin', NULL, 'Boas práticas', 45, 431, 'Nomes significativos (p. 18)', 'READING', NULL, 1700001000000, 1700002000000, 5, 'Arquitetura', 'Cap. 2', NULL, '')",
            )
        }

        val migrated = Room.databaseBuilder(context, StudyDatabase::class.java, dbName)
            .addMigrations(
                StudyDatabase.MIGRATION_27_28,
                StudyDatabase.MIGRATION_28_29,
                StudyDatabase.MIGRATION_29_30,
                StudyDatabase.MIGRATION_30_31,
                StudyDatabase.MIGRATION_31_32,
            )
            .build()
            .also { database = it }

        val dao = migrated.studyDao()
        val readable = migrated.openHelper.readableDatabase
        assertEquals(32, querySingleInt(readable, "PRAGMA user_version"))

        val indicesAfter = readIndexTableMap(readable)
        assertSingleColumnIndices(readable, indicesAfter, EXPECTED_V28_INDICES + EXPECTED_V29_INDICES)
        assertMultiColumnIndices(readable, indicesAfter, EXPECTED_V30_INDICES + EXPECTED_V31_INDICES)

        val legacyHabit = dao.habits().first().single { it.id == 10L }
        assertEquals("Ler 20 páginas", legacyHabit.title)
        assertEquals("📚", legacyHabit.emoji)
        assertEquals("default", legacyHabit.iconKey)
        assertEquals(0xFF228BE6L, legacyHabit.color)
        assertEquals("", legacyHabit.description)
        assertEquals(legacyCreatedAt, legacyHabit.createdAt)
        assertEquals(legacyHabit.createdAt, legacyHabit.startedAt)

        val legacyBook = dao.bookById(20L)
        assertNotNull(legacyBook)
        legacyBook!!
        assertEquals("Clean Code", legacyBook.title)
        assertEquals("Robert Martin", legacyBook.author)
        assertEquals("", legacyBook.documentUri)
        assertEquals("", legacyBook.documentName)
        assertEquals(0L, legacyBook.lastReadAt)

        val noteId = dao.addHabitNote(
            HabitNote(
                habitId = legacyHabit.id,
                content = "Leitura fluida antes de dormir",
                dayKey = 20261113L,
                createdAt = 1700050000000L,
                mood = "🙂",
            ),
        )
        val taskId = dao.addHabitTask(
            HabitTask(
                habitId = legacyHabit.id,
                title = "Separar marcador de página",
                completed = false,
                createdAt = 1700060000000L,
            ),
        )
        assertTrue(noteId > 0L)
        assertTrue(taskId > 0L)

        val notes = dao.habitNotes(legacyHabit.id).first()
        assertEquals(1, notes.size)
        assertEquals("Leitura fluida antes de dormir", notes.single().content)
        assertEquals(20261113L, notes.single().dayKey)
        assertEquals("🙂", notes.single().mood)

        dao.setHabitTaskCompleted(taskId, true)
        val tasks = dao.habitTasks(legacyHabit.id).first()
        assertEquals(1, tasks.size)
        assertEquals("Separar marcador de página", tasks.single().title)
        assertTrue(tasks.single().completed)

        // Cascade delete on Habit must remove all child habit_notes and habit_tasks.
        dao.deleteHabit(legacyHabit)
        assertTrue(dao.habits().first().none { it.id == legacyHabit.id })
        assertTrue(dao.habitNotes(legacyHabit.id).first().isEmpty())
        assertTrue(dao.habitTasks(legacyHabit.id).first().isEmpty())
        assertTrue(dao.allHabitNotes().first().isEmpty())
        assertTrue(dao.allHabitTasks().first().isEmpty())
        assertEquals(0, querySingleInt(readable, "SELECT COUNT(*) FROM habit_notes WHERE habitId = 10"))
        assertEquals(0, querySingleInt(readable, "SELECT COUNT(*) FROM habit_tasks WHERE habitId = 10"))
    }

    @Test
    fun migration28To29AddsAnkiProObsidianProColumnsAndTables() = runBlocking {
        assertEquals(28, StudyDatabase.MIGRATION_28_29.startVersion)
        assertEquals(29, StudyDatabase.MIGRATION_28_29.endVersion)

        val context = appContext()
        val dbName = nextDbName("studydock-v28-to-v29")
        val legacyUpdatedAt = 1700000000000L
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(28) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        V27_SCHEMA_STATEMENTS.forEach(db::execSQL)
                        StudyDatabase.MIGRATION_27_28.migrate(db)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO `subjects` (`id`, `name`, `color`) VALUES (1, 'Biologia', -16776961)")
            db.execSQL(
                "INSERT INTO `card_decks` (`id`, `name`, `parentId`, `newLimit`, `reviewLimit`, `dailyGoal`) " +
                    "VALUES (1, 'Medicina', NULL, 20, 100, 30), (2, 'Cardiologia', 1, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO `card_author_notes` (`id`, `format`, `prompt`, `answer`, `template`, `fields`, `imageKey`, `occlusion`) " +
                    "VALUES (10, 'BASIC', 'Qual a função da aorta?', 'Artéria principal', '', '{}', NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO `flashcards` (`id`, `subjectId`, `front`, `back`, `dueAt`, `intervalDays`, `easeFactor`, `repetitions`, `authorNoteId`, `variantKey`, `sourceNoteId`, `sourceExcerpt`, `suspended`, `deckId`, `learningState`, `lapses`) " +
                    "VALUES (100, 1, 'Qual a função da aorta?', 'Artéria principal', 1700005000000, 5, 2.5, 2, 10, 'front', NULL, NULL, 0, 2, NULL, 1)",
            )
            db.execSQL(
                "INSERT INTO `notes` (`id`, `title`, `markdown`, `updatedAt`, `pinned`, `revision`, `trashedAt`) " +
                    "VALUES (50, 'Nota v28', 'Conteúdo legado [[Outra]]', $legacyUpdatedAt, 1, 3, NULL)",
            )
        }

        val migrated = Room.databaseBuilder(context, StudyDatabase::class.java, dbName)
            .addMigrations(
                StudyDatabase.MIGRATION_28_29,
                StudyDatabase.MIGRATION_29_30,
                StudyDatabase.MIGRATION_30_31,
                StudyDatabase.MIGRATION_31_32,
            )
            .build()
            .also { database = it }

        val dao = migrated.studyDao()
        val readable = migrated.openHelper.readableDatabase
        assertEquals(32, querySingleInt(readable, "PRAGMA user_version"))

        val indicesAfter = readIndexTableMap(readable)
        assertSingleColumnIndices(readable, indicesAfter, EXPECTED_V29_INDICES)
        assertMultiColumnIndices(readable, indicesAfter, EXPECTED_V30_INDICES + EXPECTED_V31_INDICES)

        // Legacy CardDeck rows preserved with Anki Pro defaults
        val decks = dao.listDecks().associateBy { it.id }
        val rootDeck = decks.getValue(1L)
        assertEquals("Medicina", rootDeck.name)
        assertEquals(20, rootDeck.newLimit)
        assertEquals("SM2", rootDeck.schedulerMode)
        assertEquals("1 10", rootDeck.learningSteps)
        assertEquals("10", rootDeck.relearningSteps)
        assertEquals(1, rootDeck.graduatingInterval)
        assertEquals(4, rootDeck.easyInterval)
        assertEquals(0.9, rootDeck.desiredRetention, 1e-6)
        assertTrue(rootDeck.burySiblings)
        assertEquals(8, rootDeck.leechThreshold)
        assertFalse(rootDeck.leechSuspend)
        assertEquals(4, rootDeck.dayStartHour)
        assertFalse(rootDeck.isFiltered)
        assertEquals("", rootDeck.filterQuery)

        // Legacy Flashcard & CardAuthorNote rows preserved with v29 columns defaulted
        val card = dao.listFlashcards().single { it.id == 100L }
        assertEquals("Qual a função da aorta?", card.front)
        assertEquals(2L, card.deckId)
        assertEquals(5, card.intervalDays)
        assertEquals("", card.tags)
        assertEquals(0, card.flag)
        assertEquals(0L, card.buriedUntilDay)
        assertEquals(0, card.learningStep)
        assertEquals(0.0, card.fsrsStability, 1e-6)
        assertEquals(0.0, card.fsrsDifficulty, 1e-6)
        assertEquals(0L, card.lastReviewAt)
        assertEquals("", card.noteTypeKey)

        val author = dao.authorNote(10L)!!
        assertEquals("BASIC", author.format)
        assertEquals("", author.tags)
        assertFalse(author.typeAnswer)

        // New note_snapshots table + CASCADE delete when parent note is purged
        val snapId = dao.insertNoteSnapshot(
            NoteSnapshot(
                noteId = 50L,
                title = "Nota v28",
                markdown = "Versão salva",
                revision = 3L,
                savedAt = legacyUpdatedAt + 1000L,
            ),
        )
        assertEquals("Versão salva", dao.noteSnapshotById(snapId)?.markdown)
        assertEquals(1, dao.noteSnapshots().first().size)
        dao.trashNote(50L, expectedRevision = 3L)
        dao.purgeTrashedNote(50L)
        assertEquals(0, querySingleInt(readable, "SELECT COUNT(*) FROM note_snapshots WHERE noteId = 50"))

        // New canvas_boards table CRUD
        val boardId = dao.upsertCanvasBoard(CanvasBoard(title = "Mapa v29", jsonData = """{"nodes":[]}"""))
        val boards = dao.canvasBoards().first()
        assertEquals(1, boards.size)
        assertEquals("Mapa v29", boards.single().title)
        dao.deleteCanvasBoard(boardId)
        assertTrue(dao.canvasBoards().first().isEmpty())
    }

    @Test
    fun migration29To30() = runBlocking {
        assertEquals(29, StudyDatabase.MIGRATION_29_30.startVersion)
        assertEquals(30, StudyDatabase.MIGRATION_29_30.endVersion)

        val context = appContext()
        val dbName = nextDbName("studydock-v29-to-v30")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(29) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        V27_SCHEMA_STATEMENTS.forEach(db::execSQL)
                        StudyDatabase.MIGRATION_27_28.migrate(db)
                        StudyDatabase.MIGRATION_28_29.migrate(db)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO `subjects` (`id`, `name`, `color`) VALUES (1, 'Física', -16776961)")
            db.execSQL(
                "INSERT INTO `notes` (`id`, `title`, `markdown`, `updatedAt`, `pinned`, `revision`, `trashedAt`) " +
                    "VALUES (5, 'Eletromagnetismo', '# Maxwell', 1700000000000, 0, 1, NULL)",
            )
            db.execSQL(
                "INSERT INTO `books` (`id`, `title`, `author`, `coverUri`, `summary`, `currentPage`, `totalPages`, `quotes`, `status`, `subjectId`, `createdAt`, `updatedAt`) " +
                    "VALUES (7, 'Griffiths', 'David J. Griffiths', NULL, 'ED', 10, 620, '', 'READING', 1, 1700000000000, 1700000000000)",
            )
            db.execSQL("INSERT INTO `card_decks` (`id`, `name`, `parentId`) VALUES (1, 'Exatas', NULL)")
            db.execSQL(
                "INSERT INTO `flashcards` (`id`, `subjectId`, `front`, `back`, `dueAt`, `intervalDays`, `easeFactor`, `repetitions`, `sourceNoteId`, `suspended`, `deckId`, `lapses`) " +
                    "VALUES (100, 1, 'Lei de Gauss', 'Fluxo elétrico', 1700010000000, 4, 2.5, 2, 5, 0, 1, 0), " +
                    "(101, 1, 'Suspenso', 'Verso', 1700020000000, 8, 2.5, 3, 5, 1, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO `study_tasks` (`id`, `subjectId`, `title`, `dueAt`, `completed`, `createdAt`, `previousTaskId`, `noteId`, `bookId`) " +
                    "VALUES (200, 1, 'Revisar capítulo 1', 1700030000000, 0, 1700000000000, NULL, 5, 7), " +
                    "(201, 1, 'Revisar capítulo 2', 1700040000000, 0, 1700001000000, 200, 5, 7)",
            )
            db.execSQL(
                "INSERT INTO `review_round` (`cardId`, `token`, `position`, `done`, `elapsedMs`, `cram`) " +
                    "VALUES (100, 'tok-open', 0, 0, 500, 0), (101, 'tok-done', 1, 1, 900, 1)",
            )

            val indicesBefore = readIndexTableMap(db)
            EXPECTED_V30_INDICES.keys.forEach { expectedName ->
                assertFalse("Index $expectedName should not exist at v29", expectedName in indicesBefore)
            }
        }

        val migrated = Room.databaseBuilder(context, StudyDatabase::class.java, dbName)
            .addMigrations(
                StudyDatabase.MIGRATION_29_30,
                StudyDatabase.MIGRATION_30_31,
                StudyDatabase.MIGRATION_31_32,
            )
            .build()
            .also { database = it }

        val dao = migrated.studyDao()
        val writable = migrated.openHelper.writableDatabase
        assertEquals(32, querySingleInt(writable, "PRAGMA user_version"))

        // Validate idempotency of CREATE INDEX IF NOT EXISTS on v30 database.
        StudyDatabase.MIGRATION_29_30.migrate(writable)

        val indicesAfter = readIndexTableMap(writable)
        assertMultiColumnIndices(writable, indicesAfter, EXPECTED_V30_INDICES)

        // Preserved rows across flashcards, study_tasks, and review_round
        val cards = dao.listFlashcards().associateBy { it.id }
        assertEquals(2, cards.size)
        assertEquals("Lei de Gauss", cards.getValue(100L).front)
        assertEquals(5L, cards.getValue(100L).sourceNoteId)
        assertFalse(cards.getValue(100L).suspended)
        assertTrue(cards.getValue(101L).suspended)

        val tasks = dao.openTasks().first().associateBy { it.id }
        assertEquals(2, tasks.size)
        assertEquals(5L, tasks.getValue(200L).noteId)
        assertEquals(7L, tasks.getValue(200L).bookId)
        assertEquals(200L, tasks.getValue(201L).previousTaskId)

        val round = dao.reviewRound().first().associateBy { it.card.id }
        assertEquals(2, round.size)
        assertEquals("tok-open", round.getValue(100L).entry.token)
        assertFalse(round.getValue(100L).entry.done)
        assertTrue(round.getValue(101L).entry.cram)
        assertTrue(dao.observeHasOpenScheduledRound().first())
    }

    @Test
    fun migration30To31AddsSubjectParentHierarchyAndIndex() = runBlocking {
        assertEquals(30, StudyDatabase.MIGRATION_30_31.startVersion)
        assertEquals(31, StudyDatabase.MIGRATION_30_31.endVersion)

        val context = appContext()
        val dbName = nextDbName("studydock-v30-to-v31")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(30) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        V27_SCHEMA_STATEMENTS.forEach(db::execSQL)
                        StudyDatabase.MIGRATION_27_28.migrate(db)
                        StudyDatabase.MIGRATION_28_29.migrate(db)
                        StudyDatabase.MIGRATION_29_30.migrate(db)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO `subjects` (`id`, `name`, `color`, `nextStep`, `targetMinutesPerDay`, `weight`, `examDate`, `archived`) " +
                    "VALUES (1, 'Matemática', 4285357813, 'Equações', 60, 3, NULL, 0)",
            )
        }

        val migrated = Room.databaseBuilder(context, StudyDatabase::class.java, dbName)
            .addMigrations(
                StudyDatabase.MIGRATION_30_31,
                StudyDatabase.MIGRATION_31_32,
            )
            .build()
            .also { database = it }

        val dao = migrated.studyDao()
        val writable = migrated.openHelper.writableDatabase
        assertEquals(32, querySingleInt(writable, "PRAGMA user_version"))

        // Idempotency check on already-migrated v31 schema
        StudyDatabase.MIGRATION_30_31.migrate(writable)

        val indicesAfter = readIndexTableMap(writable)
        assertMultiColumnIndices(writable, indicesAfter, EXPECTED_V30_INDICES + EXPECTED_V31_INDICES)

        val parentSubject = dao.subjects().first().single { it.id == 1L }
        assertEquals("Matemática", parentSubject.name)
        assertEquals(null, parentSubject.parentId)

        val childId = dao.addSubject(
            Subject(
                name = "Geometria Analítica",
                color = parentSubject.color,
                weight = 2,
                parentId = parentSubject.id,
            ),
        )
        val allSubjects = dao.subjects().first().associateBy { it.id }
        assertEquals(parentSubject.id, allSubjects.getValue(childId).parentId)
    }

    @Test
    fun upgradeFrom1To31ViaOpenNamedPreservesDataAndVerifiesV31Schema() = runBlocking {
        val context = appContext()
        val dbName = nextDbName("studydock-v1-to-v30")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `study_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_sessions_subjectId` ON `study_sessions` (`subjectId`)")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO `subjects` (`id`, `name`, `color`) VALUES (1, 'Matemática v1', -16776961)")
            db.execSQL("INSERT INTO `study_sessions` (`id`, `subjectId`, `startedAt`, `endedAt`, `durationSeconds`) VALUES (10, 1, 1700000000000, 1700001800000, 1800)")
        }

        val upgraded = StudyDatabase.openNamed(context, dbName).also { database = it }
        val dao = upgraded.studyDao()
        val readable = upgraded.openHelper.readableDatabase
        assertEquals(32, querySingleInt(readable, "PRAGMA user_version"))

        val indices = readIndexTableMap(readable)
        assertSingleColumnIndices(readable, indices, EXPECTED_V27_INDICES + EXPECTED_V28_INDICES + EXPECTED_V29_INDICES)
        assertMultiColumnIndices(readable, indices, EXPECTED_V30_INDICES + EXPECTED_V31_INDICES)

        val subject = dao.subjects().first().single { it.id == 1L }
        assertEquals("Matemática v1", subject.name)
        assertEquals(1, subject.weight)
        assertFalse(subject.archived)
        assertEquals(null, subject.parentId)

        val session = dao.recentSessions(0L).first().single { it.id == 10L }
        assertEquals(1800L, session.durationSeconds)
        assertEquals("STOPWATCH", session.mode)
    }

    private fun assertSingleColumnIndices(
        db: SupportSQLiteDatabase,
        indexTableMap: Map<String, String>,
        expected: Map<String, Pair<String, String>>,
    ) {
        expected.forEach { (indexName, expectedSpec) ->
            assertEquals("Missing or mismatched table for $indexName", expectedSpec.first, indexTableMap[indexName])
            assertEquals(
                "Unexpected indexed column for $indexName",
                listOf(expectedSpec.second),
                readIndexedColumns(db, indexName),
            )
        }
    }

    private fun assertMultiColumnIndices(
        db: SupportSQLiteDatabase,
        indexTableMap: Map<String, String>,
        expected: Map<String, Pair<String, List<String>>>,
    ) {
        expected.forEach { (indexName, expectedSpec) ->
            assertEquals("Missing or mismatched table for $indexName", expectedSpec.first, indexTableMap[indexName])
            assertEquals(
                "Unexpected indexed columns for $indexName",
                expectedSpec.second,
                readIndexedColumns(db, indexName),
            )
        }
    }

    private fun readIndexTableMap(db: SupportSQLiteDatabase): Map<String, String> {
        val result = linkedMapOf<String, String>()
        db.query("SELECT name, tbl_name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow("name")
            val tableCol = cursor.getColumnIndexOrThrow("tbl_name")
            while (cursor.moveToNext()) {
                result[cursor.getString(nameCol)] = cursor.getString(tableCol)
            }
        }
        return result
    }

    private fun readIndexedColumns(db: SupportSQLiteDatabase, indexName: String): List<String> {
        val columns = mutableListOf<String>()
        db.query("PRAGMA index_info(`$indexName`)").use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameCol)
            }
        }
        return columns
    }

    private fun querySingleInt(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            assertTrue("Expected row for query: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun querySingleString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            assertTrue("Expected row for query: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    companion object {
        private val EXPECTED_V27_INDICES: Map<String, Pair<String, String>> = mapOf(
            "index_study_sessions_startedAt" to ("study_sessions" to "startedAt"),
            "index_reading_logs_loggedAt" to ("reading_logs" to "loggedAt"),
            "index_reading_logs_bookId" to ("reading_logs" to "bookId"),
            "index_study_tasks_completed" to ("study_tasks" to "completed"),
            "index_study_tasks_dueAt" to ("study_tasks" to "dueAt"),
            "index_notes_trashedAt" to ("notes" to "trashedAt"),
            "index_notes_updatedAt" to ("notes" to "updatedAt"),
            "index_journal_entries_dayKey" to ("journal_entries" to "dayKey"),
            "index_calendar_events_at" to ("calendar_events" to "at"),
        )

        private val EXPECTED_V28_INDICES: Map<String, Pair<String, String>> = mapOf(
            "index_habit_notes_habitId" to ("habit_notes" to "habitId"),
            "index_habit_notes_dayKey" to ("habit_notes" to "dayKey"),
            "index_habit_notes_createdAt" to ("habit_notes" to "createdAt"),
            "index_habit_tasks_habitId" to ("habit_tasks" to "habitId"),
            "index_habit_tasks_createdAt" to ("habit_tasks" to "createdAt"),
        )

        private val EXPECTED_V29_INDICES: Map<String, Pair<String, String>> = mapOf(
            "index_note_snapshots_noteId" to ("note_snapshots" to "noteId"),
            "index_note_snapshots_savedAt" to ("note_snapshots" to "savedAt"),
            "index_canvas_boards_updatedAt" to ("canvas_boards" to "updatedAt"),
        )

        private val EXPECTED_V30_INDICES: Map<String, Pair<String, List<String>>> = mapOf(
            "index_flashcards_suspended" to ("flashcards" to listOf("suspended")),
            "index_flashcards_suspended_dueAt" to ("flashcards" to listOf("suspended", "dueAt")),
            "index_flashcards_sourceNoteId" to ("flashcards" to listOf("sourceNoteId")),
            "index_study_tasks_noteId" to ("study_tasks" to listOf("noteId")),
            "index_study_tasks_bookId" to ("study_tasks" to listOf("bookId")),
            "index_study_tasks_previousTaskId" to ("study_tasks" to listOf("previousTaskId")),
            "index_review_round_done" to ("review_round" to listOf("done")),
            "index_review_round_position" to ("review_round" to listOf("position")),
            "index_review_round_cardId_token_done" to ("review_round" to listOf("cardId", "token", "done")),
        )

        private val EXPECTED_V31_INDICES: Map<String, Pair<String, List<String>>> = mapOf(
            "index_subjects_parentId" to ("subjects" to listOf("parentId")),
        )

        private val V27_SCHEMA_STATEMENTS: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `note_drafts` (`key` TEXT NOT NULL, `noteId` INTEGER, `baseRevision` INTEGER, `title` TEXT NOT NULL, `markdown` TEXT NOT NULL, `bindings` TEXT NOT NULL, `selectionStart` INTEGER NOT NULL, `selectionEnd` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `pending` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_note_drafts_noteId` ON `note_drafts` (`noteId`)",
            "CREATE TABLE IF NOT EXISTS `note_links` (`sourceId` INTEGER NOT NULL, `reference` TEXT NOT NULL, `targetId` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `reference`), FOREIGN KEY(`sourceId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`targetId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_note_links_targetId` ON `note_links` (`targetId`)",
            "CREATE TABLE IF NOT EXISTS `card_images` (`key` TEXT NOT NULL, `bytes` BLOB NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `review_round` (`cardId` INTEGER NOT NULL, `token` TEXT NOT NULL, `position` INTEGER NOT NULL, `done` INTEGER NOT NULL, `elapsedMs` INTEGER NOT NULL, `cram` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`cardId`), FOREIGN KEY(`cardId`) REFERENCES `flashcards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `card_author_notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `format` TEXT NOT NULL, `prompt` TEXT NOT NULL, `answer` TEXT NOT NULL, `template` TEXT NOT NULL, `fields` TEXT NOT NULL, `imageKey` TEXT, `occlusion` TEXT, FOREIGN KEY(`imageKey`) REFERENCES `card_images`(`key`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_card_author_notes_imageKey` ON `card_author_notes` (`imageKey`)",
            "CREATE TABLE IF NOT EXISTS `card_templates` (`name` TEXT NOT NULL, `front` TEXT NOT NULL, `back` TEXT NOT NULL, PRIMARY KEY(`name`))",
            "CREATE TABLE IF NOT EXISTS `card_decks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `parentId` INTEGER, `newLimit` INTEGER, `reviewLimit` INTEGER, `dailyGoal` INTEGER, FOREIGN KEY(`parentId`) REFERENCES `card_decks`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_card_decks_parentId_name` ON `card_decks` (`parentId`, `name`)",
            "CREATE TABLE IF NOT EXISTS `mistakes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER NOT NULL, `topic` TEXT NOT NULL, `question` TEXT NOT NULL, `reason` TEXT NOT NULL, `solution` TEXT NOT NULL, `createdAt` INTEGER NOT NULL DEFAULT 0, `nextReviewAt` INTEGER NOT NULL DEFAULT 0, `reviewCount` INTEGER NOT NULL DEFAULT 0, `resolved` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_mistakes_subjectId` ON `mistakes` (`subjectId`)",
            "CREATE TABLE IF NOT EXISTS `activity_timers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mode` TEXT NOT NULL, `title` TEXT NOT NULL, `bookId` INTEGER, `startedAt` INTEGER NOT NULL, `runningSince` INTEGER, `elapsedMs` INTEGER NOT NULL, `endedAt` INTEGER, `series` TEXT NOT NULL, `restUntil` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `reading_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `pages` INTEGER NOT NULL, `loggedAt` INTEGER NOT NULL, `bookId` INTEGER, `subjectId` INTEGER, `durationMinutes` INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS `index_reading_logs_loggedAt` ON `reading_logs` (`loggedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_reading_logs_bookId` ON `reading_logs` (`bookId`)",
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, `nextStep` TEXT, `targetMinutesPerDay` INTEGER NOT NULL DEFAULT 0, `weight` INTEGER NOT NULL DEFAULT 1, `examDate` INTEGER, `archived` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS `study_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, `isActive` INTEGER NOT NULL DEFAULT 0, `topic` TEXT NOT NULL DEFAULT '', `mode` TEXT NOT NULL DEFAULT 'STOPWATCH', `breakSeconds` INTEGER NOT NULL DEFAULT 0, `taskId` INTEGER, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_study_sessions_subjectId` ON `study_sessions` (`subjectId`)",
            "CREATE INDEX IF NOT EXISTS `index_study_sessions_startedAt` ON `study_sessions` (`startedAt`)",
            "CREATE TABLE IF NOT EXISTS `subject_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `details` TEXT NOT NULL, `targetAt` INTEGER, `currentValue` REAL NOT NULL, `targetValue` REAL, `unit` TEXT NOT NULL, `completed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_subject_items_subjectId` ON `subject_items` (`subjectId`)",
            "CREATE INDEX IF NOT EXISTS `index_subject_items_type` ON `subject_items` (`type`)",
            "CREATE INDEX IF NOT EXISTS `index_subject_items_targetAt` ON `subject_items` (`targetAt`)",
            "CREATE TABLE IF NOT EXISTS `question_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER, `topic` TEXT NOT NULL, `questionCount` INTEGER NOT NULL, `correctCount` INTEGER NOT NULL, `loggedAt` INTEGER NOT NULL, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_question_logs_subjectId` ON `question_logs` (`subjectId`)",
            "CREATE INDEX IF NOT EXISTS `index_question_logs_loggedAt` ON `question_logs` (`loggedAt`)",
            "CREATE TABLE IF NOT EXISTS `workout_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `exercises` TEXT NOT NULL, `durationMinutes` INTEGER NOT NULL, `notes` TEXT NOT NULL, `startedAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_workout_logs_startedAt` ON `workout_logs` (`startedAt`)",
            "CREATE TABLE IF NOT EXISTS `meal_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mealType` TEXT NOT NULL, `foods` TEXT NOT NULL, `calories` INTEGER NOT NULL, `proteinGrams` REAL NOT NULL, `carbsGrams` REAL NOT NULL, `fatGrams` REAL NOT NULL, `loggedAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_meal_logs_loggedAt` ON `meal_logs` (`loggedAt`)",
            "CREATE TABLE IF NOT EXISTS `journal_entries` (`dayKey` INTEGER NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `mood` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `energy` INTEGER NOT NULL DEFAULT 3, `focus` INTEGER NOT NULL DEFAULT 3, `tags` TEXT NOT NULL DEFAULT '', `promptUsed` TEXT NOT NULL DEFAULT '', `linkedNoteId` INTEGER, PRIMARY KEY(`dayKey`))",
            "CREATE INDEX IF NOT EXISTS `index_journal_entries_dayKey` ON `journal_entries` (`dayKey`)",
            "CREATE TABLE IF NOT EXISTS `calendar_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `at` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_calendar_events_at` ON `calendar_events` (`at`)",
            "CREATE TABLE IF NOT EXISTS `study_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER, `title` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, `previousTaskId` INTEGER, `priority` INTEGER NOT NULL DEFAULT 4, `recurrence` TEXT NOT NULL DEFAULT 'NONE', `noteId` INTEGER, `details` TEXT NOT NULL DEFAULT '', `hasDueDate` INTEGER NOT NULL DEFAULT 1, `estimatedMinutes` INTEGER NOT NULL DEFAULT 0, `subtasksJson` TEXT NOT NULL DEFAULT '', `tags` TEXT NOT NULL DEFAULT '', `bookId` INTEGER, `afterCompletion` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_study_tasks_subjectId` ON `study_tasks` (`subjectId`)",
            "CREATE INDEX IF NOT EXISTS `index_study_tasks_completed` ON `study_tasks` (`completed`)",
            "CREATE INDEX IF NOT EXISTS `index_study_tasks_dueAt` ON `study_tasks` (`dueAt`)",
            "CREATE TABLE IF NOT EXISTS `habit_areas` (`name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`name`))",
            "CREATE TABLE IF NOT EXISTS `habits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `emoji` TEXT NOT NULL DEFAULT '✨', `createdAt` INTEGER NOT NULL, `area` TEXT NOT NULL DEFAULT 'Dia', `kind` TEXT NOT NULL DEFAULT 'GOOD', `repeatDaysMask` INTEGER NOT NULL DEFAULT 127, `targetValue` REAL NOT NULL DEFAULT 1.0, `targetUnit` TEXT NOT NULL DEFAULT 'vezes', `targetPeriod` TEXT NOT NULL DEFAULT 'dia', `reminderMinutes` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_habits_area` ON `habits` (`area`)",
            "CREATE INDEX IF NOT EXISTS `index_habits_createdAt` ON `habits` (`createdAt`)",
            "CREATE TABLE IF NOT EXISTS `habit_completions` (`habitId` INTEGER NOT NULL, `dayKey` INTEGER NOT NULL, `status` TEXT NOT NULL DEFAULT 'DONE', `value` REAL NOT NULL DEFAULT 1.0, `note` TEXT, `loggedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`habitId`, `dayKey`), FOREIGN KEY(`habitId`) REFERENCES `habits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_habit_completions_dayKey` ON `habit_completions` (`dayKey`)",
            "CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `targetAt` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `direction` TEXT NOT NULL DEFAULT 'COUNTDOWN', `unit` TEXT NOT NULL DEFAULT 'DAYS', `roundUp` INTEGER NOT NULL DEFAULT 1, `color` INTEGER NOT NULL DEFAULT 4284569305, `style` TEXT NOT NULL DEFAULT 'RING', `createdAt` INTEGER NOT NULL DEFAULT 0, `metricType` TEXT NOT NULL DEFAULT 'COUNTDOWN', `currentValue` REAL NOT NULL DEFAULT 0.0, `targetValue` REAL NOT NULL DEFAULT 0.0, `metricUnit` TEXT NOT NULL DEFAULT '', `subjectId` INTEGER, `milestonesJson` TEXT NOT NULL DEFAULT '', `pinned` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `markdown` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `pinned` INTEGER NOT NULL DEFAULT 0, `revision` INTEGER NOT NULL DEFAULT 0, `trashedAt` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_notes_trashedAt` ON `notes` (`trashedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_notes_updatedAt` ON `notes` (`updatedAt`)",
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `coverUri` TEXT, `summary` TEXT NOT NULL, `currentPage` INTEGER NOT NULL, `totalPages` INTEGER, `quotes` TEXT NOT NULL, `status` TEXT NOT NULL, `subjectId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `rating` INTEGER NOT NULL DEFAULT 0, `categoryTag` TEXT NOT NULL DEFAULT '', `currentChapter` TEXT NOT NULL DEFAULT '', `targetFinishAt` INTEGER, `highlightsJson` TEXT NOT NULL DEFAULT '', FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_books_subjectId` ON `books` (`subjectId`)",
            "CREATE TABLE IF NOT EXISTS `book_note_links` (`bookId` INTEGER NOT NULL, `noteId` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `noteId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_book_note_links_noteId` ON `book_note_links` (`noteId`)",
            "CREATE TABLE IF NOT EXISTS `flashcards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER, `front` TEXT NOT NULL, `back` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `easeFactor` REAL NOT NULL, `repetitions` INTEGER NOT NULL, `authorNoteId` INTEGER, `variantKey` TEXT, `sourceNoteId` INTEGER, `sourceExcerpt` TEXT, `suspended` INTEGER NOT NULL DEFAULT 0, `deckId` INTEGER, `learningState` TEXT, `lapses` INTEGER NOT NULL, FOREIGN KEY(`deckId`) REFERENCES `card_decks`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_flashcards_subjectId` ON `flashcards` (`subjectId`)",
            "CREATE INDEX IF NOT EXISTS `index_flashcards_dueAt` ON `flashcards` (`dueAt`)",
            "CREATE INDEX IF NOT EXISTS `index_flashcards_deckId` ON `flashcards` (`deckId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_flashcards_authorNoteId_variantKey` ON `flashcards` (`authorNoteId`, `variantKey`)",
            "CREATE TABLE IF NOT EXISTS `card_reviews` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardId` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, `grade` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `durationMs` INTEGER, `priorState` TEXT, `priorIntervalDays` INTEGER, `deckAtReview` INTEGER, `subjectAtReview` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_card_reviews_cardId` ON `card_reviews` (`cardId`)",
            "CREATE INDEX IF NOT EXISTS `index_card_reviews_reviewedAt` ON `card_reviews` (`reviewedAt`)",
        )
    }
}
