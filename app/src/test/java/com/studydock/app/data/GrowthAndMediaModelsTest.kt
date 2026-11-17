package com.studydock.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GrowthAndMediaModelsTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val dayMs = 86_400_000L

    private fun noon(date: LocalDate): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun bookHighlightsPureLegacyParsingAndListCrudWorkWithoutJson() {
        assertTrue(BookHighlights.parse(null).isEmpty())
        assertTrue(BookHighlights.parse("   ").isEmpty())

        val legacyBook = Book(
            id = 1L,
            title = "Clean Code",
            author = "Robert Martin",
            quotes = "Nomes revelam intenção (p. 18)\nFunções devem ser pequenas\nRegra do escoteiro (pág. 42)",
            highlightsJson = "",
            updatedAt = 9_000L
        )

        val parsed = BookHighlights.parse(legacyBook)
        assertEquals(3, parsed.size)
        assertEquals("Nomes revelam intenção", parsed[0].text)
        assertEquals(18, parsed[0].page)
        assertEquals("Funções devem ser pequenas", parsed[1].text)
        assertNull(parsed[1].page)
        assertEquals("Regra do escoteiro", parsed[2].text)
        assertEquals(42, parsed[2].page)
        assertEquals(3, BookHighlights.count(legacyBook))

        // Add ignores blank text, normalizes fields
        val unchanged = BookHighlights.add(parsed, BookHighlight(text = "   "))
        assertEquals(3, unchanged.size)

        val added = BookHighlights.add(
            parsed,
            BookHighlight(id = "h_new", text = "  Código limpo é legível ", page = 99, createdAt = 12_000L)
        )
        assertEquals(4, added.size)
        assertEquals("Código limpo é legível", added.last().text)

        val updated = BookHighlights.update(added, added.last().copy(page = 101))
        assertEquals(101, updated.last().page)

        val removed = BookHighlights.remove(updated, "legacy_1")
        assertEquals(3, removed.size)

        val legacyString = BookHighlights.toLegacyQuotes(removed)
        assertTrue(legacyString.contains("Nomes revelam intenção (p. 18)"))
        assertFalse(legacyString.contains("Funções devem ser pequenas"))
        assertTrue(legacyString.contains("Código limpo é legível (p. 101)"))
    }

    @Test
    fun bookReadingEngineComputesProgressPaceDeadlinesAndShelfSummary() {
        val today = LocalDate.of(2026, 11, 13)
        val todayNoon = noon(today)

        val book1 = Book(
            id = 1L,
            title = "Algoritmos",
            author = "Cormen",
            currentPage = 100,
            totalPages = 400,
            status = "READING",
            targetFinishAt = todayNoon + 10 * dayMs,
            quotes = "Complexidade assintótica (p. 43)\nDivisão e conquista (p. 65)"
        )
        val book2 = Book(
            id = 2L,
            title = "Desejado",
            author = "Autor B",
            currentPage = 50, // WISHLIST should not inflate shelf totalPagesRead
            totalPages = 200,
            status = "WISHLIST"
        )
        val book3 = Book(
            id = 3L,
            title = "Concluído",
            author = "Autor C",
            currentPage = 180,
            totalPages = 180,
            status = "DONE"
        )
        val clampedOverBook = Book(
            id = 4L,
            title = "Sem Total",
            author = "Autor D",
            currentPage = 40,
            totalPages = null,
            status = "READING"
        )

        val timerStart = todayNoon - 3_600_000L // 1 hour timer
        val timers = listOf(
            ActivityTimer(
                id = 1L,
                mode = "Leitura",
                title = "Algoritmos",
                bookId = 1L,
                startedAt = timerStart,
                runningSince = null,
                elapsedMs = 3_600_000L,
                endedAt = todayNoon
            ),
            // Workout timer must be ignored by reading engine
            ActivityTimer(
                id = 2L,
                mode = "Treino",
                title = "Algoritmos",
                bookId = 1L,
                startedAt = timerStart,
                runningSince = null,
                elapsedMs = 3_600_000L,
                endedAt = todayNoon
            )
        )
        val logs = listOf(
            // Paired with timer 1 -> 60 min must NOT be double counted
            ReadingLog(id = 1L, title = "Algoritmos", pages = 40, loggedAt = timerStart, bookId = 1L, durationMinutes = 60),
            // Unlinked log on yesterday -> adds 30 min
            ReadingLog(id = 2L, title = "Algoritmos", pages = 20, loggedAt = todayNoon - dayMs, bookId = 1L, durationMinutes = 30),
            // Older log outside 7-day window (10 days ago)
            ReadingLog(id = 3L, title = "Concluído", pages = 50, loggedAt = todayNoon - 10 * dayMs, bookId = 3L, durationMinutes = 0)
        )

        val stats1 = BookReadingEngine.computeBookStats(book1, logs, timers, now = todayNoon, zone = zone)
        assertEquals(25, stats1.progressPercent)
        assertEquals(0.25f, stats1.progressFraction, 1e-4f)
        assertEquals(300, stats1.remainingPages)
        assertEquals(90, stats1.totalMinutes)
        assertTrue(stats1.isBookSpecificPace)
        // 60 logged pages across 1.5h = 40 pages/h
        assertEquals(40, stats1.pagesPerHour)
        assertEquals(450, stats1.estimatedMinutesLeft) // 300 / 40 * 60 = 450
        assertEquals(40, stats1.pagesToday)
        assertEquals(60, stats1.pagesLast7Days)
        assertEquals(30.0, stats1.requiredPagesPerDay!!, 1e-6)
        assertEquals(10, stats1.daysRemainingToTarget)
        assertEquals(2, stats1.highlightCount)

        // Book without totalPages keeps fraction = 0f and remainingPages = 0
        val statsNoTotal = BookReadingEngine.computeBookStats(clampedOverBook, emptyList(), emptyList(), now = todayNoon, zone = zone)
        assertEquals(0, statsNoTotal.progressPercent)
        assertEquals(0f, statsNoTotal.progressFraction, 1e-6f)
        assertEquals(0, statsNoTotal.remainingPages)
        assertFalse(statsNoTotal.isBookSpecificPace)
        assertEquals(BookReadingEngine.DEFAULT_PAGES_PER_HOUR, statsNoTotal.pagesPerHour)

        val shelf = BookReadingEngine.computeShelfSummary(
            books = listOf(book1, book2, book3, clampedOverBook),
            readingLogs = logs,
            activityTimers = timers,
            now = todayNoon,
            zone = zone
        )
        assertEquals(4, shelf.totalBooks)
        assertEquals(2, shelf.readingCount)
        assertEquals(1, shelf.wishlistCount)
        assertEquals(1, shelf.doneCount)
        assertEquals(320, shelf.totalPagesRead) // 100 + 180 + 40 (excludes WISHLIST 50)
        assertEquals(90, shelf.totalReadingMinutes)
        assertEquals(2, shelf.readingStreakDays)
        assertEquals(2, shelf.bestReadingStreakDays)
        assertEquals(40, shelf.pagesToday)
        assertEquals(60, shelf.pagesThisWeek)
        assertEquals(2, shelf.totalHighlights)

        // FocusAudioStore pure helper
        assertEquals(FocusAudioStore.REPEAT_OFF, FocusAudioStore.normalizeRepeatMode(" off "))
        assertEquals(FocusAudioStore.REPEAT_ONE, FocusAudioStore.normalizeRepeatMode("one"))
        assertEquals(FocusAudioStore.REPEAT_ALL, FocusAudioStore.normalizeRepeatMode("unknown"))
        assertTrue(FocusAudioStore.allSoundKeys.contains("brown"))
    }

    @Test
    fun goalProgressEngineComputesAllMetricTypesAndPaceRules() {
        val today = LocalDate.of(2026, 11, 13)
        val now = noon(today)

        assertEquals(GoalMetricType.STUDY_HOURS, GoalMetricType.fromId(" study_hours "))
        assertEquals(GoalMetricType.COUNTDOWN, GoalMetricType.fromId("unknown_metric"))

        val subjectId = 10L
        val sessions = listOf(
            StudySession(subjectId = subjectId, startedAt = now - 7200_000L, endedAt = now, durationSeconds = 7200L), // 2h
            StudySession(subjectId = 99L, startedAt = now - 3600_000L, endedAt = now, durationSeconds = 3600L), // other subject
            StudySession(subjectId = subjectId, startedAt = now, endedAt = now, durationSeconds = 5000L, isActive = true) // ignored
        )
        val questionLogs = listOf(
            QuestionLog(subjectId = subjectId, topic = "T1", questionCount = 20, correctCount = 15, loggedAt = now),
            QuestionLog(subjectId = 99L, topic = "T2", questionCount = 10, correctCount = 10, loggedAt = now)
        )
        val books = listOf(
            Book(id = 1L, title = "Livro A", author = "X", currentPage = 50, subjectId = subjectId),
            Book(id = 2L, title = "Livro B", author = "Y", currentPage = 30, subjectId = 99L)
        )
        val readingLogs = listOf(
            ReadingLog(title = "Livro A", pages = 70, loggedAt = now, bookId = 1L, subjectId = subjectId), // 70 > 50
            ReadingLog(title = "Artigo Avulso", pages = 15, loggedAt = now) // unlinked global only
        )
        val flashcards = listOf(
            Flashcard(id = 1L, subjectId = subjectId, front = "F", back = "B"),
            Flashcard(id = 2L, subjectId = 99L, front = "F2", back = "B2")
        )
        val cardReviews = listOf(
            CardReview(cardId = 1L, reviewedAt = now, grade = 3, intervalDays = 1, subjectAtReview = null),
            CardReview(cardId = 2L, reviewedAt = now, grade = 2, intervalDays = 1, subjectAtReview = subjectId),
            CardReview(cardId = 2L, reviewedAt = now, grade = 2, intervalDays = 1, subjectAtReview = 99L)
        )
        val tasks = listOf(
            StudyTask(subjectId = subjectId, title = "T1", dueAt = now, completed = true),
            StudyTask(subjectId = subjectId, title = "T2", dueAt = now, completed = false),
            StudyTask(subjectId = 99L, title = "T3", dueAt = now, completed = true)
        )
        val habits = listOf(
            HabitCompletion(habitId = 1L, dayKey = 20261113L, status = "DONE"),
            HabitCompletion(habitId = 2L, dayKey = 20261113L, status = "PARTIAL")
        )

        fun eval(goal: Goal) = GoalProgressEngine.compute(
            goal, sessions, questionLogs, books, readingLogs, flashcards, cardReviews, tasks, habits, now
        )

        // STUDY_HOURS scoped to subject 10: 2h of 10h target -> 0.2f
        val hoursSnap = eval(
            Goal(
                title = "10h Matéria",
                targetAt = now + 8 * dayMs,
                createdAt = now - 2 * dayMs,
                metricType = GoalMetricType.STUDY_HOURS.id,
                targetValue = 10.0,
                subjectId = subjectId
            )
        )
        assertEquals(2.0, hoursSnap.current, 1e-6)
        assertEquals(0.2f, hoursSnap.progressFraction, 1e-4f)
        assertTrue(hoursSnap.isOnTrack) // elapsed fraction 2/10 = 0.2 >= 0.2 - 0.05
        assertEquals("1 págs".replace("págs", "").let { hoursSnap.displayCurrent }.let { "2 h" }, hoursSnap.displayCurrent)
        assertEquals("1 h/dia", hoursSnap.requiredDailyPaceLabel)

        // QUESTIONS scoped vs global
        val qSnap = eval(Goal(title = "Q", targetAt = now + 5 * dayMs, metricType = GoalMetricType.QUESTIONS.id, targetValue = 40.0, subjectId = subjectId))
        assertEquals(20.0, qSnap.current, 1e-6)
        assertEquals(0.5f, qSnap.progressFraction, 1e-4f)

        // ACCURACY: 15/20 = 75% vs 80% target
        val accSnap = eval(Goal(title = "Acc", targetAt = now + 5 * dayMs, metricType = GoalMetricType.ACCURACY.id, targetValue = 80.0, subjectId = subjectId))
        assertEquals(75.0, accSnap.current, 1e-6)
        assertFalse(accSnap.isOnTrack)
        assertEquals("Abaixo do alvo", accSnap.paceStatusLabel)
        assertEquals("Faltam 5 p.p.", accSnap.requiredDailyPaceLabel)

        // ACCURACY with zero scoped questions
        val emptyAccSnap = eval(Goal(title = "Acc0", targetAt = now + 5 * dayMs, metricType = GoalMetricType.ACCURACY.id, targetValue = 80.0, subjectId = 777L))
        assertEquals("Sem questões ainda", emptyAccSnap.paceStatusLabel)
        assertTrue(emptyAccSnap.isOnTrack)

        // PAGES global includes max(currentPage, logged) + unlinked logs = 70 + 30 + 15 = 115
        val pagesGlobal = eval(Goal(title = "Págs", targetAt = now + 5 * dayMs, metricType = GoalMetricType.PAGES.id, targetValue = 200.0))
        assertEquals(115.0, pagesGlobal.current, 1e-6)
        // PAGES scoped to subjectId = 70 (ignores unlinked and other subjects)
        val pagesScoped = eval(Goal(title = "Págs S", targetAt = now + 5 * dayMs, metricType = GoalMetricType.PAGES.id, targetValue = 100.0, subjectId = subjectId))
        assertEquals(70.0, pagesScoped.current, 1e-6)

        // FLASHCARDS scoped: 2 reviews (card 1 + card 2 with subjectAtReview=10)
        val fcSnap = eval(Goal(title = "FC", targetAt = now + 5 * dayMs, metricType = GoalMetricType.FLASHCARDS.id, targetValue = 10.0, subjectId = subjectId))
        assertEquals(2.0, fcSnap.current, 1e-6)

        // TASKS scoped
        val tasksSnap = eval(Goal(title = "Tasks", targetAt = now + 5 * dayMs, metricType = GoalMetricType.TASKS.id, targetValue = 4.0, subjectId = subjectId))
        assertEquals(1.0, tasksSnap.current, 1e-6)

        // HABITS counts DONE only
        val habitsSnap = eval(Goal(title = "Hab", targetAt = now + 5 * dayMs, metricType = GoalMetricType.HABITS.id, targetValue = 5.0))
        assertEquals(1.0, habitsSnap.current, 1e-6)

        // MANUAL overdue behind target
        val manualExpired = eval(Goal(title = "Manual", targetAt = now - dayMs, metricType = GoalMetricType.MANUAL.id, currentValue = 3.0, targetValue = 10.0))
        assertFalse(manualExpired.isOnTrack)
        assertEquals("Prazo encerrado", manualExpired.paceStatusLabel)

        // COUNTDOWN SINCE & UNTIL
        val sinceSnap = eval(Goal(title = "Desde", targetAt = now - 3 * dayMs, direction = "SINCE", unit = "DAYS", metricType = GoalMetricType.COUNTDOWN.id))
        assertEquals(3.0, sinceSnap.current, 1e-6)
        assertEquals("3 dias decorridos", sinceSnap.paceStatusLabel)
        assertTrue(sinceSnap.isOnTrack)

        val todayCountdown = eval(Goal(title = "Hoje", targetAt = now, direction = "UNTIL", unit = "DAYS", metricType = GoalMetricType.COUNTDOWN.id))
        assertEquals("É hoje", todayCountdown.paceStatusLabel)

        val pastCountdown = eval(Goal(title = "Passou", targetAt = now - 2 * dayMs, direction = "UNTIL", unit = "DAYS", metricType = GoalMetricType.COUNTDOWN.id))
        assertEquals("Passou há 2 dias", pastCountdown.paceStatusLabel)
        assertFalse(pastCountdown.isOnTrack)
    }

    @Test
    fun diaryAnalyticsSummarizesDayCorrelatesMoodAndFiltersEntries() {
        val today = LocalDate.of(2026, 11, 13)
        val dayKey = DiaryAnalytics.dayKeyFromEpoch(noon(today), zone)
        assertEquals(20261113L, dayKey)
        assertEquals(today, DiaryAnalytics.localDateFromDayKey(dayKey))
        // Invalid day-of-month falls back safely to 1st of month
        assertEquals(LocalDate.of(2026, 2, 1), DiaryAnalytics.localDateFromDayKey(20260231L))

        val subjects = listOf(Subject(id = 1L, name = "Direito"), Subject(id = 2L, name = "Português"))
        val summary = DiaryAnalytics.summarizeDay(
            dayKey = dayKey,
            sessions = listOf(
                StudySession(subjectId = 1L, startedAt = noon(today), endedAt = noon(today) + 3600_000L, durationSeconds = 3600L),
                StudySession(subjectId = 2L, startedAt = noon(today), endedAt = noon(today) + 1800_000L, durationSeconds = 1800L)
            ),
            tasks = listOf(
                StudyTask(title = "Ler súmulas", dueAt = noon(today), completed = true, completedAt = noon(today)),
                StudyTask(title = "Pendente", dueAt = noon(today), completed = false)
            ),
            cardReviews = listOf(
                CardReview(cardId = 1L, reviewedAt = noon(today), grade = 3, intervalDays = 2),
                CardReview(cardId = 2L, reviewedAt = noon(today), grade = 0, intervalDays = 1)
            ),
            questionLogs = listOf(
                QuestionLog(subjectId = 1L, topic = "Atos", questionCount = 10, correctCount = 8, loggedAt = noon(today))
            ),
            habitCompletions = listOf(
                HabitCompletion(habitId = 1L, dayKey = dayKey, status = "DONE"),
                HabitCompletion(habitId = 2L, dayKey = dayKey, status = "PARTIAL"),
                HabitCompletion(habitId = 3L, dayKey = dayKey, status = "SKIPPED")
            ),
            readingLogs = listOf(
                ReadingLog(title = "Doutrina", pages = 25, loggedAt = noon(today))
            ),
            workouts = listOf(
                WorkoutLog(title = "Corrida", exercises = "5km", durationMinutes = 35, startedAt = noon(today))
            ),
            meals = listOf(
                MealLog(mealType = "Almoço", foods = "Arroz e frango", calories = 650, loggedAt = noon(today))
            ),
            subjects = subjects,
            zone = zone
        )

        assertTrue(summary.hasAnyActivity)
        assertEquals(5400L, summary.studySeconds)
        assertEquals(listOf("Direito", "Português"), summary.subjectNames)
        assertEquals(1, summary.tasksCompletedCount)
        assertEquals(2, summary.flashcardReviewsCount)
        assertEquals(50, summary.flashcardAccuracyPercent)
        assertEquals(10, summary.questionsDone)
        assertEquals(80, summary.questionAccuracyPercent)
        assertEquals(2, summary.habitsCompletedCount)
        assertEquals(25, summary.pagesRead)
        assertEquals(35, summary.workoutMinutes)
        assertEquals(650, summary.totalCalories)

        val md = DiaryAnalytics.formatSummaryMarkdown(summary)
        assertTrue(md.contains("1h 30min"))
        assertTrue(md.contains("Direito, Português"))
        assertTrue(md.contains("Ler súmulas"))

        val emptyMd = DiaryAnalytics.formatSummaryMarkdown(
            DiaryAnalytics.summarizeDay(
                dayKey = 20260101L,
                sessions = emptyList(),
                tasks = emptyList(),
                cardReviews = emptyList(),
                questionLogs = emptyList(),
                habitCompletions = emptyList(),
                readingLogs = emptyList(),
                workouts = emptyList(),
                meals = emptyList(),
                subjects = emptyList(),
                zone = zone
            )
        )
        assertTrue(emptyMd.contains("Sem atividades registradas"))

        // Mood correlations & entry filtering
        val entries = listOf(
            JournalEntry(dayKey = 20261112L, title = "Revisão Geral", body = "Bom ritmo", mood = "😄", energy = 5, focus = 4, tags = "#revisão, foco"),
            JournalEntry(dayKey = 20261113L, title = "Cansado", body = "Pouco tempo", mood = "😴", energy = 2, focus = 2, tags = "cansaco")
        )
        val filtered = DiaryAnalytics.filterEntries(entries, query = "revisao", moodFilter = "😄", tagFilter = "@revisão")
        assertEquals(1, filtered.size)
        assertEquals(20261112L, filtered.single().dayKey)

        val toggledTags = DiaryAnalytics.toggleTag("foco, estudo", "#Foco")
        assertEquals("estudo", toggledTags)

        val correlations = DiaryAnalytics.computeMoodCorrelations(
            entries = entries,
            sessions = listOf(StudySession(startedAt = noon(LocalDate.of(2026, 11, 12)), endedAt = noon(LocalDate.of(2026, 11, 12)) + 3600_000L, durationSeconds = 3600L)),
            zone = zone
        )
        assertEquals(2, correlations.size)
        assertEquals("😄", correlations.first().mood)
        assertEquals(60, correlations.first().averageStudyMinutes)
    }

    @Test
    fun syllabusEngineMistakeSpacedReviewAndSubjectEcosystemWorkTogether() {
        val today = LocalDate.of(2026, 11, 13)
        val now = noon(today)

        // TopicMastery progression
        assertEquals(TopicMastery.LEARNING, TopicMastery.NOT_STARTED.next())
        assertEquals(TopicMastery.MASTERED, TopicMastery.REVIEWING.next())
        assertEquals(TopicMastery.NOT_STARTED, TopicMastery.MASTERED.next())
        assertEquals(TopicMastery.REVIEWING, TopicMastery.fromCode("reviewing"))

        val explicitItem = SyllabusEngine.applyMastery(
            SubjectItem(id = 1L, subjectId = 1L, type = "TOPIC", title = "Crase"),
            TopicMastery.LEARNING
        )
        assertEquals(TopicMastery.LEARNING, SyllabusEngine.masteryOf(explicitItem))

        val topics = SyllabusEngine.buildTopics(
            subjectId = 1L,
            items = listOf(explicitItem),
            questionLogs = listOf(
                QuestionLog(subjectId = 1L, topic = "Crase", questionCount = 10, correctCount = 9, loggedAt = now),
                QuestionLog(subjectId = 1L, topic = "Regência", questionCount = 12, correctCount = 11, loggedAt = now)
            ),
            flashcards = listOf(
                Flashcard(id = 1L, subjectId = 1L, front = "[Português · Pontuação] Uso da vírgula", back = "Regra", dueAt = now - 1000L),
                Flashcard(id = 2L, subjectId = 1L, front = "[Português · ...] Ignorado", back = "B", dueAt = now),
                Flashcard(id = 3L, subjectId = 1L, front = "[Português · Pontuação] Suspenso", back = "C", dueAt = now, suspended = true)
            ),
            mistakes = listOf(
                Mistake(id = 1L, subjectId = 1L, topic = "Concordância", question = "Q", reason = "R", solution = "S", resolved = false)
            ),
            now = now
        )
        assertEquals(4, topics.size)
        assertEquals("Crase", topics[0].title)
        assertFalse(topics[0].isInferred)
        assertEquals(TopicMastery.LEARNING, topics[0].mastery) // explicit item wins over inferred stats

        val regencia = topics.first { it.title == "Regência" }
        assertTrue(regencia.isInferred)
        assertEquals(TopicMastery.MASTERED, regencia.mastery) // >=85% with >=10 questions and 0 open mistakes
        assertEquals(91, regencia.accuracyPercent)

        val pontuacao = topics.first { it.title == "Pontuação" }
        assertEquals(1, pontuacao.flashcardsCount) // suspended card excluded
        assertEquals(1, pontuacao.dueFlashcardsCount)

        val concordancia = topics.first { it.title == "Concordância" }
        assertEquals(TopicMastery.LEARNING, concordancia.mastery)
        assertEquals(1, concordancia.openMistakesCount)

        // MistakeSpacedReview advances through 1 -> 3 -> 7 -> 15 -> 30 days;
        // completing the 30-day step (6th review) graduates to resolved = true; a miss resets to 1 day.
        var mistake = Mistake(id = 5L, subjectId = 1L, topic = "Crase", question = "Quando usar?", reason = "Esqueci regra", solution = "Antes de feminina")
        assertTrue(MistakeSpacedReview.isDue(mistake, now))
        assertEquals("[Português · Crase]\nQuando usar?", MistakeSpacedReview.formatFlashcardFront("Português", mistake))
        assertTrue(MistakeSpacedReview.formatFlashcardBack(mistake).contains("Onde errei: Esqueci regra"))

        val expectedIntervals = listOf(1, 3, 7, 15, 30)
        var cursorNow = now
        for ((step, days) in expectedIntervals.withIndex()) {
            assertEquals(days, MistakeSpacedReview.nextIntervalDays(mistake, rememberedWell = true))
            mistake = MistakeSpacedReview.scheduleNextReview(mistake, rememberedWell = true, now = cursorNow)
            assertEquals(step + 1, mistake.reviewCount)
            assertEquals(cursorNow + days * dayMs, mistake.nextReviewAt)
            assertFalse(mistake.resolved)
            cursorNow += days * dayMs
        }
        assertEquals(30, MistakeSpacedReview.nextIntervalDays(mistake, rememberedWell = true))
        val graduated = MistakeSpacedReview.scheduleNextReview(mistake, rememberedWell = true, now = cursorNow)
        assertEquals(6, graduated.reviewCount)
        assertEquals(cursorNow + 30 * dayMs, graduated.nextReviewAt)
        assertTrue(graduated.resolved)
        assertFalse(MistakeSpacedReview.isDue(graduated, cursorNow + 100 * dayMs))

        val missed = MistakeSpacedReview.scheduleNextReview(graduated, rememberedWell = false, now = cursorNow)
        assertEquals(0, missed.reviewCount)
        assertFalse(missed.resolved)
        assertEquals(cursorNow + dayMs, missed.nextReviewAt)

        // SubjectEcosystemEngine distributes weights among active subjects only
        val subjects = listOf(
            Subject(id = 1L, name = "Português", weight = 3, examDate = now + 15 * dayMs),
            Subject(id = 2L, name = "Direito", weight = 1, targetMinutesPerDay = 45),
            Subject(id = 3L, name = "Arquivada", weight = 10, targetMinutesPerDay = 90, archived = true)
        )
        val overviews = SubjectEcosystemEngine.buildOverview(
            subjects = subjects,
            sessions = listOf(StudySession(subjectId = 1L, startedAt = now - 1000L, endedAt = now, durationSeconds = 1800L)),
            tasks = listOf(StudyTask(subjectId = 1L, title = "Atrasada", dueAt = now - 2 * dayMs, hasDueDate = true)),
            flashcards = listOf(Flashcard(id = 1L, subjectId = 1L, front = "F", back = "B", dueAt = now)),
            mistakes = listOf(Mistake(id = 1L, subjectId = 1L, topic = "T", question = "Q", reason = "R", solution = "S")),
            questionLogs = listOf(QuestionLog(subjectId = 1L, topic = "T", questionCount = 10, correctCount = 8)),
            dailyGoalMinutes = 120,
            now = now,
            zone = zone
        )
        assertEquals(75f, overviews[0].cycleSharePercent, 1e-2f)
        assertEquals(90, overviews[0].recommendedMinutesPerDay)
        assertEquals(15, overviews[0].daysUntilExam)
        assertEquals(1, overviews[0].overdueTasksCount)
        assertEquals(1, overviews[0].dueFlashcardsCount)
        assertEquals(1, overviews[0].dueMistakesCount)
        assertEquals(80, overviews[0].questionAccuracyPercent)

        assertEquals(25f, overviews[1].cycleSharePercent, 1e-2f)
        assertEquals(45, overviews[1].recommendedMinutesPerDay) // explicit targetMinutesPerDay overrides cycle share

        assertEquals(0f, overviews[2].cycleSharePercent, 1e-2f)
        assertEquals(0, overviews[2].recommendedMinutesPerDay)
    }

    @Test
    fun subjectHierarchyOrdersDisplayNamesAndRollsUpChildren() {
        val math = Subject(id = 1L, name = "Matemática", weight = 3)
        val alg = Subject(id = 2L, name = "Álgebra", parentId = 1L, weight = 2)
        val geom = Subject(id = 3L, name = "Geometria", parentId = 1L, weight = 1)
        val phys = Subject(id = 4L, name = "Física", weight = 2)
        val archivedChild = Subject(id = 5L, name = "Trigonometria", parentId = 1L, archived = true)
        val list = listOf(geom, phys, math, archivedChild, alg)
        val byId = list.associateBy { it.id }

        assertEquals("Matemática", SubjectHierarchy.displayName(math, byId))
        assertEquals("Matemática › Álgebra", SubjectHierarchy.displayName(alg, byId))
        assertEquals(math, SubjectHierarchy.parentOf(alg, byId))
        assertEquals(listOf(geom, alg), SubjectHierarchy.childrenOf(1L, list, includeArchived = false))

        val orderedIds = SubjectHierarchy.orderedHierarchically(list).map { it.id }
        // Raízes na ordem original (phys=4, math=1) com filhas agrupadas logo abaixo de math
        assertEquals(listOf(4L, 1L, 3L, 5L, 2L), orderedIds)

        val now = noon(LocalDate.of(2026, 11, 14))
        fun sampleOverview(
            subject: Subject,
            todayFocusSeconds: Long = 0L,
            totalFocusSeconds: Long = 0L,
            openTasksCount: Int = 0,
            overdueTasksCount: Int = 0,
            dueFlashcardsCount: Int = 0,
            dueMistakesCount: Int = 0,
            unresolvedMistakesCount: Int = 0,
            questionsTotal: Int = 0,
            questionsCorrect: Int = 0,
            questionAccuracyPercent: Int? = null
        ) = SubjectEcosystemOverview(
            subject = subject,
            todayFocusSeconds = todayFocusSeconds,
            totalFocusSeconds = totalFocusSeconds,
            lastStudiedAt = null,
            openTasksCount = openTasksCount,
            overdueTasksCount = overdueTasksCount,
            dueFlashcardsCount = dueFlashcardsCount,
            totalFlashcardsCount = dueFlashcardsCount,
            dueMistakesCount = dueMistakesCount,
            unresolvedMistakesCount = unresolvedMistakesCount,
            questionsTotal = questionsTotal,
            questionsCorrect = questionsCorrect,
            questionAccuracyPercent = questionAccuracyPercent,
            cycleSharePercent = 0f,
            recommendedMinutesPerDay = 0,
            daysUntilExam = null
        )

        val overviews = listOf(
            sampleOverview(
                subject = math,
                todayFocusSeconds = 600L,
                totalFocusSeconds = 1800L,
                openTasksCount = 1,
                dueFlashcardsCount = 2,
                questionsTotal = 10,
                questionsCorrect = 8,
                questionAccuracyPercent = 80
            ),
            sampleOverview(
                subject = alg,
                todayFocusSeconds = 1200L,
                totalFocusSeconds = 3600L,
                openTasksCount = 2,
                overdueTasksCount = 1,
                dueFlashcardsCount = 3,
                questionsTotal = 10,
                questionsCorrect = 9,
                questionAccuracyPercent = 90
            ),
            sampleOverview(
                subject = geom,
                todayFocusSeconds = 300L,
                totalFocusSeconds = 900L,
                dueMistakesCount = 1,
                unresolvedMistakesCount = 2
            ),
            sampleOverview(subject = phys, todayFocusSeconds = 500L)
        )
        val rollUp = SubjectHierarchy.rollUpForParent(1L, overviews)!!
        assertEquals(2100L, rollUp.todayFocusSeconds)
        assertEquals(6300L, rollUp.totalFocusSeconds)
        assertEquals(3, rollUp.openTasksCount)
        assertEquals(1, rollUp.overdueTasksCount)
        assertEquals(5, rollUp.dueFlashcardsCount)
        assertEquals(1, rollUp.dueMistakesCount)
        assertEquals(20, rollUp.questionsTotal)
        assertEquals(17, rollUp.questionsCorrect)
        assertEquals(85, rollUp.questionAccuracyPercent)
        assertEquals(listOf(2L, 3L), rollUp.children.map { it.subject.id })
        assertTrue(now > 0L)
    }
}
