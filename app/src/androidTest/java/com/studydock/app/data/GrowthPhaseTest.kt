package com.studydock.app.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class GrowthPhaseTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dbName = "growth-phase-${UUID.randomUUID()}.db"
    private var database: StudyDatabase? = null

    private fun openDao(): StudyDao =
        StudyDatabase.openNamed(context, dbName).also { database = it }.studyDao()

    private fun reopenDao(): StudyDao {
        database?.close()
        return openDao()
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun upgradeFrom16To25PreservesDataAndAppliesV25Defaults() = runBlocking {
        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { old ->
            instrumentation.context.assets.open("studydock-v16.sql").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { old.execSQL(it) }
            }
            old.execSQL(
                "INSERT INTO subjects (id, name, color, nextStep) " +
                    "VALUES (10, 'Direito Constitucional', 4284569305, 'Ler art. 5º')"
            )
            old.execSQL(
                "INSERT INTO mistakes (id, subjectId, topic, question, reason, solution) " +
                    "VALUES (20, 10, 'Controle de Constitucionalidade', 'ADI admite cautelar?', 'Confundi quórum', 'Maioria absoluta')"
            )
            old.execSQL(
                "INSERT INTO journal_entries (dayKey, title, body, mood, updatedAt) " +
                    "VALUES (20261110, 'Dia produtivo', 'Estudei bastante', '🙂', 1000)"
            )
            old.execSQL(
                "INSERT INTO goals (id, title, targetAt, completed, direction, unit, roundUp, color, style, createdAt) " +
                    "VALUES (30, 'Prova OAB', 1900000000000, 0, 'COUNTDOWN', 'DAYS', 1, 4284569305, 'RING', 0)"
            )
            old.version = 16
        }

        val dao = openDao()

        val subject = dao.subjects().first().single { it.id == 10L }
        assertEquals("Direito Constitucional", subject.name)
        assertEquals(0, subject.targetMinutesPerDay)
        assertEquals(1, subject.weight)
        assertNull(subject.examDate)
        assertFalse(subject.archived)

        val mistake = dao.mistakes().first().single { it.id == 20L }
        assertEquals(10L, mistake.subjectId)
        assertEquals("Controle de Constitucionalidade", mistake.topic)
        assertEquals(0L, mistake.createdAt)
        assertEquals(0L, mistake.nextReviewAt)
        assertEquals(0, mistake.reviewCount)
        assertFalse(mistake.resolved)

        val journal = dao.journalEntries().first().single { it.dayKey == 20261110L }
        assertEquals("Dia produtivo", journal.title)
        assertEquals(3, journal.energy)
        assertEquals(3, journal.focus)
        assertEquals("", journal.tags)
        assertEquals("", journal.promptUsed)
        assertNull(journal.linkedNoteId)

        val goal = dao.goals().first().single { it.id == 30L }
        assertEquals("Prova OAB", goal.title)
        assertEquals(0L, goal.createdAt)
        assertEquals("COUNTDOWN", goal.metricType)
        assertEquals(0.0, goal.currentValue, 1e-6)
        assertEquals(0.0, goal.targetValue, 1e-6)
        assertEquals("", goal.metricUnit)
        assertNull(goal.subjectId)
        assertEquals("", goal.milestonesJson)
        assertFalse(goal.pinned)
    }

    @Test
    fun v25DaoSupportsMistakeJournalAndGoalMutations() = runBlocking {
        val dao = openDao()
        val subjectId = dao.addSubject(
            Subject(
                name = "Cálculo II",
                color = 0xFF3578B5L,
                targetMinutesPerDay = 90,
                weight = 3,
                examDate = 1800000000000L,
                archived = false
            )
        )

        dao.addMistake(
            Mistake(
                subjectId = subjectId,
                topic = "Integrais Duplas",
                question = "Inverter ordem de integração",
                reason = "Esqueci de esboçar a região",
                solution = "Desenhar limites em x e y",
                createdAt = 1000L,
                nextReviewAt = 2000L,
                reviewCount = 1,
                resolved = false
            )
        )
        val insertedMistake = dao.mistakes().first().single()
        assertEquals(1, insertedMistake.reviewCount)
        assertFalse(insertedMistake.resolved)

        dao.updateMistake(
            insertedMistake.copy(nextReviewAt = 5000L, reviewCount = 2, resolved = true)
        )
        val updatedMistake = reopenDao().mistakes().first().single()
        assertEquals(5000L, updatedMistake.nextReviewAt)
        assertEquals(2, updatedMistake.reviewCount)
        assertTrue(updatedMistake.resolved)

        dao.deleteMistake(updatedMistake)
        assertTrue(dao.mistakes().first().isEmpty())

        val noteId = dao.saveNote(Note(title = "Resumo Integrais", markdown = "# Notas"))
        dao.saveJournalEntry(
            JournalEntry(
                dayKey = 20261112L,
                title = "Simulado Cálculo",
                body = "Bom foco pela manhã",
                mood = "😄",
                updatedAt = 3000L,
                energy = 4,
                focus = 5,
                tags = "simulado,foco",
                promptUsed = DiaryAnalytics.reflectionPrompts.first(),
                linkedNoteId = noteId
            )
        )
        val storedJournal = dao.journalEntries().first().single { it.dayKey == 20261112L }
        assertEquals(4, storedJournal.energy)
        assertEquals(5, storedJournal.focus)
        assertEquals("simulado,foco", storedJournal.tags)
        assertEquals(noteId, storedJournal.linkedNoteId)

        dao.deleteJournalEntry(20261112L)
        assertTrue(dao.journalEntries().first().isEmpty())

        val milestonesJson = GoalMilestones.add("", "Fechar lista 1")
        dao.addGoal(
            Goal(
                title = "Resolver 100 questões",
                targetAt = 1900000000000L,
                metricType = GoalMetricType.QUESTIONS.id,
                currentValue = 10.0,
                targetValue = 100.0,
                metricUnit = "questões",
                subjectId = subjectId,
                milestonesJson = milestonesJson,
                pinned = true
            )
        )
        val storedGoal = dao.goals().first().single()
        assertEquals("QUESTIONS", storedGoal.metricType)
        assertEquals(subjectId, storedGoal.subjectId)
        assertTrue(storedGoal.pinned)

        dao.updateGoal(storedGoal.copy(targetValue = 150.0, pinned = false))
        val updatedGoal = reopenDao().goals().first().single { it.id == storedGoal.id }
        assertEquals(150.0, updatedGoal.targetValue, 1e-6)
        assertFalse(updatedGoal.pinned)
        assertEquals(1, GoalMilestones.parse(updatedGoal.milestonesJson).size)
    }

    @Test
    fun goalMilestonesSupportsCrudToggleAndMalformedJson() {
        assertTrue(GoalMilestones.parse(null).isEmpty())
        assertTrue(GoalMilestones.parse("{invalid-json").isEmpty())

        val withFirst = GoalMilestones.add("", "Revisar teoria")
        val withSecond = GoalMilestones.add(withFirst, "Fazer 30 exercícios")
        val parsed = GoalMilestones.parse(withSecond)
        assertEquals(2, parsed.size)
        assertEquals("Revisar teoria", parsed[0].title)
        assertFalse(parsed[0].done)

        val toggled = GoalMilestones.toggle(withSecond, parsed[0].id)
        assertEquals(1 to 2, GoalMilestones.summary(toggled))

        val removed = GoalMilestones.remove(toggled, parsed[1].id)
        val remaining = GoalMilestones.parse(removed)
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().done)

        val reserialized = GoalMilestones.serialize(remaining)
        assertEquals(remaining, GoalMilestones.parse(reserialized))
    }

    @Test
    fun goalProgressEngineComputesMetricsScopingAndLegacyCountdown() {
        val dayMs = 86_400_000L
        val now = 1_760_000_000_000L

        val legacyCountdown = Goal(
            id = 1L,
            title = "Meta legada",
            targetAt = now + 5 * dayMs,
            createdAt = 0L,
            metricType = GoalMetricType.COUNTDOWN.id
        )
        val legacySnap = GoalProgressEngine.compute(
            goal = legacyCountdown,
            sessions = emptyList(),
            questionLogs = emptyList(),
            books = emptyList(),
            readingLogs = emptyList(),
            flashcards = emptyList(),
            cardReviews = emptyList(),
            tasks = emptyList(),
            habitCompletions = emptyList(),
            now = now
        )
        assertEquals(GoalMetricType.COUNTDOWN, legacySnap.metricType)
        assertEquals(5.0, legacySnap.current, 1e-6)
        assertEquals(0f, legacySnap.progressFraction, 1e-5f)
        assertTrue(legacySnap.isOnTrack)
        assertEquals("Faltam 5 dias", legacySnap.paceStatusLabel)

        val midpointCountdown = legacyCountdown.copy(createdAt = now - 5 * dayMs)
        val midSnap = GoalProgressEngine.compute(
            goal = midpointCountdown,
            sessions = emptyList(),
            questionLogs = emptyList(),
            books = emptyList(),
            readingLogs = emptyList(),
            flashcards = emptyList(),
            cardReviews = emptyList(),
            tasks = emptyList(),
            habitCompletions = emptyList(),
            now = now
        )
        assertEquals(0.5f, midSnap.progressFraction, 1e-3f)

        val sessions = listOf(
            StudySession(id = 1L, subjectId = 1L, startedAt = now - 10_000L, endedAt = now - 2_800L, durationSeconds = 7200L, isActive = false),
            StudySession(id = 2L, subjectId = 1L, startedAt = now - 1_000L, endedAt = now, durationSeconds = 9999L, isActive = true),
            StudySession(id = 3L, subjectId = 2L, startedAt = now - 20_000L, endedAt = now - 16_400L, durationSeconds = 3600L, isActive = false)
        )
        val questionLogs = listOf(
            QuestionLog(id = 1L, subjectId = 1L, topic = "A", questionCount = 100, correctCount = 85, loggedAt = now),
            QuestionLog(id = 2L, subjectId = 2L, topic = "B", questionCount = 50, correctCount = 25, loggedAt = now)
        )
        val books = listOf(
            Book(id = 1L, title = "Clean Code", author = "Martin", currentPage = 120, subjectId = 1L),
            Book(id = 2L, title = "Outro Livro", author = "Autor", currentPage = 40, subjectId = 2L)
        )
        val readingLogs = listOf(
            ReadingLog(id = 1L, title = "Clean Code", pages = 40, loggedAt = now),
            ReadingLog(id = 2L, title = "Artigo Avulso", pages = 15, loggedAt = now)
        )
        val flashcards = listOf(
            Flashcard(id = 10L, subjectId = 1L, front = "F1", back = "B1"),
            Flashcard(id = 11L, subjectId = 2L, front = "F2", back = "B2")
        )
        val reviews = listOf(
            CardReview(id = 1L, cardId = 10L, reviewedAt = now, grade = 3, intervalDays = 1, subjectAtReview = null),
            CardReview(id = 2L, cardId = 11L, reviewedAt = now, grade = 3, intervalDays = 1, subjectAtReview = 1L),
            CardReview(id = 3L, cardId = 11L, reviewedAt = now, grade = 3, intervalDays = 1, subjectAtReview = 2L)
        )
        val tasks = listOf(
            StudyTask(id = 1L, subjectId = 1L, title = "T1", dueAt = now, completed = true, completedAt = now),
            StudyTask(id = 2L, subjectId = 1L, title = "T2", dueAt = now, completed = false),
            StudyTask(id = 3L, subjectId = 2L, title = "T3", dueAt = now, completed = true, completedAt = now)
        )
        val habits = listOf(
            HabitCompletion(habitId = 1L, dayKey = 20261113L, status = "DONE"),
            HabitCompletion(habitId = 2L, dayKey = 20261113L, status = "DONE"),
            HabitCompletion(habitId = 3L, dayKey = 20261113L, status = "SKIPPED")
        )

        val hoursGoal = Goal(
            title = "Horas",
            targetAt = now + 4 * dayMs,
            createdAt = now - 2 * dayMs,
            metricType = GoalMetricType.STUDY_HOURS.id,
            targetValue = 10.0,
            subjectId = 1L
        )
        val hoursSnap = GoalProgressEngine.compute(
            hoursGoal, sessions, questionLogs, books, readingLogs, flashcards, reviews, tasks, habits, now
        )
        assertEquals(2.0, hoursSnap.current, 1e-6)
        assertEquals(0.2f, hoursSnap.progressFraction, 1e-4f)
        assertEquals("2 h/dia", hoursSnap.requiredDailyPaceLabel)

        val questionsSnap = GoalProgressEngine.compute(
            hoursGoal.copy(metricType = GoalMetricType.QUESTIONS.id, targetValue = 200.0),
            sessions, questionLogs, books, readingLogs, flashcards, reviews, tasks, habits, now
        )
        assertEquals(100.0, questionsSnap.current, 1e-6)

        val accuracySnap = GoalProgressEngine.compute(
            hoursGoal.copy(metricType = GoalMetricType.ACCURACY.id, targetValue = 80.0),
            sessions, questionLogs, books, readingLogs, flashcards, reviews, tasks, habits, now
        )
        assertEquals(85.0, accuracySnap.current, 1e-6)
        assertEquals("Acurácia atingida", accuracySnap.paceStatusLabel)
        assertTrue(accuracySnap.isOnTrack)

        val scopedPagesSnap = GoalProgressEngine.compute(
            hoursGoal.copy(metricType = GoalMetricType.PAGES.id, targetValue = 300.0),
            sessions, questionLogs, books, readingLogs, flashcards, reviews, tasks, habits, now
        )
        assertEquals(120.0, scopedPagesSnap.current, 1e-6)

        val globalPagesSnap = GoalProgressEngine.compute(
            hoursGoal.copy(metricType = GoalMetricType.PAGES.id, targetValue = 300.0, subjectId = null),
            sessions, questionLogs, books, readingLogs, flashcards, reviews, tasks, habits, now
        )
        assertEquals(175.0, globalPagesSnap.current, 1e-6)

        val cardsSnap = GoalProgressEngine.compute(
            hoursGoal.copy(metricType = GoalMetricType.FLASHCARDS.id, targetValue = 10.0),
            sessions, questionLogs, books, readingLogs, flashcards, reviews, tasks, habits, now
        )
        assertEquals(2.0, cardsSnap.current, 1e-6)

        val tasksSnap = GoalProgressEngine.compute(
            hoursGoal.copy(metricType = GoalMetricType.TASKS.id, targetValue = 4.0),
            sessions, questionLogs, books, readingLogs, flashcards, reviews, tasks, habits, now
        )
        assertEquals(1.0, tasksSnap.current, 1e-6)

        val habitsSnap = GoalProgressEngine.compute(
            hoursGoal.copy(metricType = GoalMetricType.HABITS.id, targetValue = 4.0, subjectId = null),
            sessions, questionLogs, books, readingLogs, flashcards, reviews, tasks, habits, now
        )
        assertEquals(2.0, habitsSnap.current, 1e-6)

        val twoSteps = GoalMilestones.add(GoalMilestones.add("", "Passo 1"), "Passo 2")
        val step1Id = GoalMilestones.parse(twoSteps).first().id
        val oneDoneJson = GoalMilestones.toggle(twoSteps, step1Id)
        val manualSnap = GoalProgressEngine.compute(
            hoursGoal.copy(
                metricType = GoalMetricType.MANUAL.id,
                currentValue = 0.0,
                targetValue = 0.0,
                milestonesJson = oneDoneJson,
                subjectId = null
            ),
            sessions, questionLogs, books, readingLogs, flashcards, reviews, tasks, habits, now
        )
        assertEquals(1.0, manualSnap.current, 1e-6)
        assertEquals(2.0, manualSnap.target, 1e-6)
        assertEquals(0.5f, manualSnap.progressFraction, 1e-4f)
    }

    @Test
    fun diaryAnalyticsSummarizesDayCorrelatesMoodsAndFiltersEntries() {
        val zone = ZoneId.of("UTC")
        val targetDate = LocalDate.of(2026, 11, 13)
        val noon = targetDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val prevNoon = targetDate.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dayKey = DiaryAnalytics.dayKeyFromEpoch(noon, zone)
        val prevDayKey = DiaryAnalytics.dayKeyFromEpoch(prevNoon, zone)

        assertEquals(20261113L, dayKey)
        assertEquals(targetDate, DiaryAnalytics.localDateFromDayKey(dayKey))

        val subjects = listOf(Subject(id = 1L, name = "Matemática"))
        val sessions = listOf(
            StudySession(id = 1L, subjectId = 1L, startedAt = noon, endedAt = noon + 5_400_000L, durationSeconds = 5400L, isActive = false),
            StudySession(id = 2L, subjectId = 1L, startedAt = prevNoon, endedAt = prevNoon + 1_800_000L, durationSeconds = 1800L, isActive = false)
        )
        val tasks = listOf(
            StudyTask(id = 1L, subjectId = 1L, title = "Lista Álgebra", dueAt = noon, completed = true, completedAt = noon),
            StudyTask(id = 2L, subjectId = 1L, title = "Pendente", dueAt = noon, completed = false)
        )
        val reviews = listOf(
            CardReview(id = 1L, cardId = 1L, reviewedAt = noon, grade = 3, intervalDays = 1),
            CardReview(id = 2L, cardId = 2L, reviewedAt = noon, grade = 0, intervalDays = 1)
        )
        val questions = listOf(
            QuestionLog(id = 1L, subjectId = 1L, topic = "Funções", questionCount = 10, correctCount = 8, loggedAt = noon)
        )
        val habits = listOf(
            HabitCompletion(habitId = 1L, dayKey = dayKey, status = "DONE"),
            HabitCompletion(habitId = 2L, dayKey = dayKey, status = "PARTIAL"),
            HabitCompletion(habitId = 3L, dayKey = dayKey, status = "SKIPPED")
        )
        val readingLogs = listOf(ReadingLog(id = 1L, title = "Cálculo", pages = 25, loggedAt = noon))
        val workouts = listOf(WorkoutLog(id = 1L, title = "Corrida", exercises = "5km", durationMinutes = 35, startedAt = noon))
        val meals = listOf(MealLog(id = 1L, mealType = "Almoço", foods = "Arroz e frango", calories = 650, loggedAt = noon))

        val summary = DiaryAnalytics.summarizeDay(
            dayKey = dayKey,
            sessions = sessions,
            tasks = tasks,
            cardReviews = reviews,
            questionLogs = questions,
            habitCompletions = habits,
            readingLogs = readingLogs,
            workouts = workouts,
            meals = meals,
            subjects = subjects,
            zone = zone
        )
        assertTrue(summary.hasAnyActivity)
        assertEquals(5400L, summary.studySeconds)
        assertEquals(1, summary.sessionCount)
        assertEquals(listOf("Matemática"), summary.subjectNames)
        assertEquals(1, summary.tasksCompletedCount)
        assertEquals(2, summary.flashcardReviewsCount)
        assertEquals(50, summary.flashcardAccuracyPercent)
        assertEquals(10, summary.questionsDone)
        assertEquals(80, summary.questionAccuracyPercent)
        assertEquals(2, summary.habitsCompletedCount)
        assertEquals(25, summary.pagesRead)
        assertEquals(35, summary.workoutMinutes)
        assertEquals(650, summary.totalCalories)

        val markdown = DiaryAnalytics.formatSummaryMarkdown(summary)
        assertTrue(markdown.contains("1h 30min"))
        assertTrue(markdown.contains("Matemática"))
        assertTrue(markdown.contains("Lista Álgebra"))
        assertTrue(markdown.contains("650 kcal"))

        val emptySummary = DiaryAnalytics.summarizeDay(
            dayKey = 20260101L,
            sessions = sessions,
            tasks = tasks,
            cardReviews = reviews,
            questionLogs = questions,
            habitCompletions = habits,
            readingLogs = readingLogs,
            workouts = workouts,
            meals = meals,
            subjects = subjects,
            zone = zone
        )
        assertFalse(emptySummary.hasAnyActivity)
        assertTrue(DiaryAnalytics.formatSummaryMarkdown(emptySummary).contains("Sem atividades registradas"))

        val entryGreat = JournalEntry(
            dayKey = dayKey,
            title = "Revisão Geral",
            body = "Energia alta nas questões",
            mood = "😄",
            energy = 5,
            focus = 4,
            tags = "Revisão, Foco"
        )
        val entryTired = JournalEntry(
            dayKey = prevDayKey,
            title = "Noite cansativa",
            body = "Pouco rendimento",
            mood = "😴",
            energy = 2,
            focus = 2,
            tags = "cansaço"
        )

        val correlations = DiaryAnalytics.computeMoodCorrelations(
            entries = listOf(entryTired, entryGreat),
            sessions = sessions,
            questionLogs = questions,
            habitCompletions = habits,
            zone = zone
        )
        assertEquals(2, correlations.size)
        assertEquals("😄", correlations.first().mood)
        assertEquals(90, correlations.first().averageStudyMinutes)
        assertEquals(10, correlations.first().averageQuestions)
        assertEquals(2.0, correlations.first().averageHabits, 1e-6)
        assertEquals("😴", correlations[1].mood)
        assertEquals(30, correlations[1].averageStudyMinutes)

        val filteredByTag = DiaryAnalytics.filterEntries(
            entries = listOf(entryTired, entryGreat),
            query = "revisao",
            moodFilter = "😄",
            tagFilter = "revisao"
        )
        assertEquals(listOf(entryGreat), filteredByTag)

        val toggled = DiaryAnalytics.toggleTag("estudo", "Revisão")
        assertEquals(listOf("estudo", "revisão"), DiaryAnalytics.parseTags(toggled))
        assertEquals("estudo", DiaryAnalytics.toggleTag(toggled, "revisão"))
    }

    @Test
    fun syllabusEngineMergesExplicitAndInferredTopicsWithoutClozeFalsePositives() {
        val baseExplicit = SubjectItem(id = 1L, subjectId = 1L, type = "TOPIC", title = "Termodinâmica")
        val reviewingExplicit = SyllabusEngine.applyMastery(baseExplicit, TopicMastery.REVIEWING)
        assertEquals("REVIEWING", reviewingExplicit.unit)
        assertFalse(reviewingExplicit.completed)
        assertEquals(TopicMastery.REVIEWING, SyllabusEngine.masteryOf(reviewingExplicit))

        val masteredExplicit = SyllabusEngine.applyMastery(reviewingExplicit, TopicMastery.MASTERED)
        assertTrue(masteredExplicit.completed)
        assertEquals(TopicMastery.MASTERED, SyllabusEngine.masteryOf(masteredExplicit))

        val now = 1_760_000_000_000L
        val topics = SyllabusEngine.buildTopics(
            subjectId = 1L,
            items = listOf(reviewingExplicit),
            questionLogs = listOf(
                QuestionLog(id = 1L, subjectId = 1L, topic = "Óptica", questionCount = 12, correctCount = 11, loggedAt = now),
                QuestionLog(id = 2L, subjectId = 1L, topic = "Termodinâmica", questionCount = 5, correctCount = 4, loggedAt = now)
            ),
            flashcards = listOf(
                Flashcard(id = 1L, subjectId = 1L, front = "[Física · Ondulatória]\nVelocidade de onda", back = "v = lambda * f", dueAt = now - 1000L),
                Flashcard(id = 2L, subjectId = 1L, front = "[vogal] A primeira letra do alfabeto é a", back = "a", dueAt = now)
            ),
            mistakes = listOf(
                Mistake(id = 1L, subjectId = 1L, topic = "Eletrostática", question = "Campo?", reason = "Sinal", solution = "kQ/d^2", resolved = false)
            ),
            now = now
        )

        val byTitle = topics.associateBy { it.title }
        assertFalse("vogal" in byTitle.keys)

        val thermo = byTitle.getValue("Termodinâmica")
        assertFalse(thermo.isInferred)
        assertEquals(TopicMastery.REVIEWING, thermo.mastery)
        assertEquals(5, thermo.questionsTotal)
        assertEquals(80, thermo.accuracyPercent)

        val optics = byTitle.getValue("Óptica")
        assertTrue(optics.isInferred)
        assertEquals(TopicMastery.MASTERED, optics.mastery)
        assertEquals(91, optics.accuracyPercent)

        val waves = byTitle.getValue("Ondulatória")
        assertTrue(waves.isInferred)
        assertEquals(TopicMastery.REVIEWING, waves.mastery)
        assertEquals(1, waves.flashcardsCount)
        assertEquals(1, waves.dueFlashcardsCount)

        val electro = byTitle.getValue("Eletrostática")
        assertTrue(electro.isInferred)
        assertEquals(TopicMastery.LEARNING, electro.mastery)
        assertEquals(1, electro.openMistakesCount)
    }

    @Test
    fun mistakeSpacedReviewFollows1To30DayScheduleAndFormatsCards() {
        val dayMs = 86_400_000L
        var currentTime = 1_000_000_000L
        var mistake = Mistake(
            id = 5L,
            subjectId = 1L,
            topic = "Crase",
            question = "Há crase antes de verbo?",
            reason = "Confundi com substantivo",
            solution = "Não se usa crase antes de verbo."
        )

        assertTrue(MistakeSpacedReview.isDue(mistake, currentTime))
        assertEquals("[Português · Crase]\nHá crase antes de verbo?", MistakeSpacedReview.formatFlashcardFront("Português", mistake))
        val cardBack = MistakeSpacedReview.formatFlashcardBack(mistake)
        assertTrue(cardBack.startsWith("Não se usa crase antes de verbo."))
        assertTrue(cardBack.contains("Onde errei: Confundi com substantivo"))

        val expectedIntervals = listOf(1, 3, 7, 15, 30)
        expectedIntervals.forEachIndexed { index, expectedDays ->
            assertEquals(expectedDays, MistakeSpacedReview.nextIntervalDays(mistake, rememberedWell = true))
            mistake = MistakeSpacedReview.scheduleNextReview(mistake, rememberedWell = true, now = currentTime)
            assertEquals(index + 1, mistake.reviewCount)
            assertEquals(currentTime + expectedDays * dayMs, mistake.nextReviewAt)
            assertFalse(mistake.resolved)
            assertFalse(MistakeSpacedReview.isDue(mistake, currentTime + dayMs / 2))
            currentTime += expectedDays * dayMs
            assertTrue(MistakeSpacedReview.isDue(mistake, currentTime))
        }

        val graduated = MistakeSpacedReview.scheduleNextReview(mistake, rememberedWell = true, now = currentTime)
        assertTrue(graduated.resolved)
        assertFalse(MistakeSpacedReview.isDue(graduated, currentTime + 365 * dayMs))

        val relapsed = MistakeSpacedReview.scheduleNextReview(graduated, rememberedWell = false, now = currentTime)
        assertFalse(relapsed.resolved)
        assertEquals(0, relapsed.reviewCount)
        assertEquals(currentTime + dayMs, relapsed.nextReviewAt)
    }

    @Test
    fun subjectEcosystemEngineAndAnalyticsGoalRespectWeightsAndArchivedSubjects() {
        val zone = ZoneId.of("UTC")
        val dayMs = 86_400_000L
        val today = LocalDate.of(2026, 11, 13)
        val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        val math = Subject(
            id = 1L,
            name = "Matemática",
            targetMinutesPerDay = 60,
            weight = 3,
            examDate = today.plusDays(10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            archived = false
        )
        val bio = Subject(
            id = 2L,
            name = "Biologia",
            targetMinutesPerDay = 0,
            weight = 1,
            examDate = null,
            archived = false
        )
        val archivedOld = Subject(
            id = 3L,
            name = "Latim",
            targetMinutesPerDay = 120,
            weight = 10,
            archived = true
        )
        val subjects = listOf(math, bio, archivedOld)

        assertEquals(60, StudyAnalyticsEngine.resolveDailyGoalMinutes(subjects, manualMinutes = 180))
        assertEquals(3600L, resolveDailyGoalSeconds(subjects, manualMinutes = 180))
        assertEquals(
            180,
            StudyAnalyticsEngine.resolveDailyGoalMinutes(subjects, manualMinutes = 180, forceManual = true)
        )
        assertEquals(
            180,
            StudyAnalyticsEngine.resolveDailyGoalMinutes(listOf(bio, archivedOld), manualMinutes = 180)
        )

        val overviews = SubjectEcosystemEngine.buildOverview(
            subjects = subjects,
            sessions = listOf(
                StudySession(id = 1L, subjectId = 1L, startedAt = now - 3_600_000L, endedAt = now, durationSeconds = 3600L),
                StudySession(id = 2L, subjectId = 1L, startedAt = now - 3 * dayMs, endedAt = now - 3 * dayMs + 1800_000L, durationSeconds = 1800L)
            ),
            tasks = listOf(
                StudyTask(id = 1L, subjectId = 1L, title = "Atrasada", dueAt = now - 2 * dayMs, completed = false, hasDueDate = true),
                StudyTask(id = 2L, subjectId = 1L, title = "Hoje", dueAt = now + 3_600_000L, completed = false, hasDueDate = true)
            ),
            flashcards = listOf(
                Flashcard(id = 1L, subjectId = 1L, front = "F1", back = "B1", dueAt = now - 1000L),
                Flashcard(id = 2L, subjectId = 1L, front = "F2", back = "B2", dueAt = now + dayMs)
            ),
            mistakes = listOf(
                Mistake(id = 1L, subjectId = 1L, topic = "Log", question = "Q", reason = "R", solution = "S", nextReviewAt = 0L, resolved = false)
            ),
            questionLogs = listOf(
                QuestionLog(id = 1L, subjectId = 1L, topic = "Log", questionCount = 20, correctCount = 15, loggedAt = now)
            ),
            dailyGoalMinutes = 200,
            now = now,
            zone = zone
        )

        val mathOverview = overviews.first { it.subject.id == 1L }
        assertEquals(3600L, mathOverview.todayFocusSeconds)
        assertEquals(5400L, mathOverview.totalFocusSeconds)
        assertEquals(2, mathOverview.openTasksCount)
        assertEquals(1, mathOverview.overdueTasksCount)
        assertEquals(1, mathOverview.dueFlashcardsCount)
        assertEquals(2, mathOverview.totalFlashcardsCount)
        assertEquals(1, mathOverview.dueMistakesCount)
        assertEquals(1, mathOverview.unresolvedMistakesCount)
        assertEquals(75, mathOverview.questionAccuracyPercent)
        assertEquals(75f, mathOverview.cycleSharePercent, 1e-3f)
        assertEquals(60, mathOverview.recommendedMinutesPerDay)
        assertEquals(10, mathOverview.daysUntilExam)

        val bioOverview = overviews.first { it.subject.id == 2L }
        assertEquals(25f, bioOverview.cycleSharePercent, 1e-3f)
        assertEquals(50, bioOverview.recommendedMinutesPerDay)

        val archivedOverview = overviews.first { it.subject.id == 3L }
        assertEquals(0f, archivedOverview.cycleSharePercent, 1e-5f)
        assertEquals(0, archivedOverview.recommendedMinutesPerDay)
    }
}
