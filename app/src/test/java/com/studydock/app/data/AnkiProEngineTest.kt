package com.studydock.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AnkiProEngineTest {

    private val saoPaulo: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val dayMs: Long = 86_400_000L

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, saoPaulo).toInstant().toEpochMilli()

    @Test
    fun fsrsInitializesSdSchedulesGradesHandlesLateVsEarlyAndRespectsDesiredRetention() {
        // Initial S & D ordering across ratings 1 (Again) .. 4 (Easy)
        val s1 = FsrsScheduler.initialStability(1)
        val s2 = FsrsScheduler.initialStability(2)
        val s3 = FsrsScheduler.initialStability(3)
        val s4 = FsrsScheduler.initialStability(4)
        assertTrue(s1 < s2 && s2 < s3 && s3 < s4)

        val d1 = FsrsScheduler.initialDifficulty(1)
        val d2 = FsrsScheduler.initialDifficulty(2)
        val d3 = FsrsScheduler.initialDifficulty(3)
        val d4 = FsrsScheduler.initialDifficulty(4)
        assertTrue(d4 < d3 && d3 < d2 && d2 < d1)
        assertTrue(d1 in 1.0..10.0 && d4 in 1.0..10.0)

        // Graduating new FSRS card across Hard / Good / Easy (empty learningSteps -> immediate day scheduling)
        val now = epochMillis(2026, 11, 14, 10, 0)
        val fsrsDeck = CardDeck(
            id = 1L,
            name = "FSRS Deck",
            schedulerMode = "FSRS",
            learningSteps = "",
            relearningSteps = "",
            desiredRetention = 0.90
        )
        val newCard = Flashcard(id = 10L, front = "F", back = "B", dueAt = now, deckId = 1L)

        val hardOutcome = newCard.afterGradePro(1, now = now, config = fsrsDeck, zone = saoPaulo, deterministic = true)
        val goodOutcome = newCard.afterGradePro(2, now = now, config = fsrsDeck, zone = saoPaulo, deterministic = true)
        val easyOutcome = newCard.afterGradePro(3, now = now, config = fsrsDeck, zone = saoPaulo, deterministic = true)
        val againOutcome = newCard.afterGradePro(0, now = now, config = fsrsDeck, zone = saoPaulo, deterministic = true)

        assertTrue(againOutcome.card.intervalDays >= 1)
        assertTrue(hardOutcome.card.intervalDays >= 1)
        assertTrue(goodOutcome.card.intervalDays >= hardOutcome.card.intervalDays)
        assertTrue(easyOutcome.card.intervalDays > goodOutcome.card.intervalDays)
        assertTrue(easyOutcome.card.fsrsStability > goodOutcome.card.fsrsStability)
        assertTrue(goodOutcome.card.fsrsStability > hardOutcome.card.fsrsStability)
        assertTrue(goodOutcome.card.fsrsDifficulty in 1.0..10.0)

        // Late review yields larger stability & interval growth than early review at equal S/D
        val tLast = epochMillis(2026, 10, 20, 10, 0)
        val matureCard = Flashcard(
            id = 11L,
            front = "Maturo",
            back = "Resposta",
            dueAt = tLast + 10 * dayMs,
            intervalDays = 10,
            repetitions = 3,
            fsrsStability = 10.0,
            fsrsDifficulty = 5.0,
            lastReviewAt = tLast,
            deckId = 1L
        )
        val earlyReview = matureCard.afterGradePro(
            grade = 2,
            now = tLast + 2 * dayMs,
            config = fsrsDeck,
            zone = saoPaulo,
            deterministic = true
        )
        val lateReview = matureCard.afterGradePro(
            grade = 2,
            now = tLast + 20 * dayMs,
            config = fsrsDeck,
            zone = saoPaulo,
            deterministic = true
        )
        assertTrue(lateReview.card.fsrsStability > earlyReview.card.fsrsStability)
        assertTrue(lateReview.card.intervalDays > earlyReview.card.intervalDays)

        // desiredRetention scaling: higher target retention -> shorter intervals
        val strictDeck = fsrsDeck.copy(desiredRetention = 0.95)
        val relaxedDeck = fsrsDeck.copy(desiredRetention = 0.82)
        val strictIvl = FsrsScheduler.nextIntervalDays(20.0, desiredRetention = 0.95)
        val relaxedIvl = FsrsScheduler.nextIntervalDays(20.0, desiredRetention = 0.82)
        assertTrue(strictIvl < relaxedIvl)

        val strictOutcome = matureCard.afterGradePro(2, now = tLast + 10 * dayMs, config = strictDeck, zone = saoPaulo, deterministic = true)
        val relaxedOutcome = matureCard.afterGradePro(2, now = tLast + 10 * dayMs, config = relaxedDeck, zone = saoPaulo, deterministic = true)
        assertTrue(strictOutcome.card.intervalDays < relaxedOutcome.card.intervalDays)

        // Post-graduation interval floor >= 1 even with minimal stability and maximum desiredRetention
        assertTrue(FsrsScheduler.nextIntervalDays(0.01, desiredRetention = 0.99) >= 1)
        val lowStabCard = matureCard.copy(fsrsStability = 0.1, intervalDays = 1)
        val floorOutcome = lowStabCard.afterGradePro(1, now = tLast + dayMs, config = strictDeck, zone = saoPaulo, deterministic = true)
        assertTrue(floorOutcome.card.intervalDays >= 1)
    }

    @Test
    fun sm2ProHandlesLearningRelearningStepsIntervalsEaseFloorAndLapses() {
        val now = epochMillis(2026, 11, 14, 12, 0)
        val sm2Deck = CardDeck(
            id = 2L,
            name = "SM-2 Pro",
            schedulerMode = "SM2",
            learningSteps = "1 10",
            relearningSteps = "15",
            graduatingInterval = 2,
            easyInterval = 6
        )
        val newCard = Flashcard(id = 20L, front = "Pergunta", back = "Resposta", dueAt = now, deckId = 2L)

        // Again on NEW -> step 0 (1 min in LEARNING)
        val step0 = newCard.afterGradePro(0, now = now, config = sm2Deck, zone = saoPaulo, deterministic = true)
        assertEquals("LEARNING", step0.card.learningState)
        assertEquals(0, step0.card.learningStep)
        assertEquals(now + 60_000L, step0.card.dueAt)
        assertEquals(0, step0.card.intervalDays)

        // Good on step 0 -> advances to step 1 (10 min in LEARNING)
        val step1 = step0.card.afterGradePro(2, now = now, config = sm2Deck, zone = saoPaulo, deterministic = true)
        assertEquals("LEARNING", step1.card.learningState)
        assertEquals(1, step1.card.learningStep)
        assertEquals(now + 10 * 60_000L, step1.card.dueAt)
        assertEquals(0, step1.card.intervalDays)

        // Good on final learning step -> graduates with graduatingInterval = 2
        val graduated = step1.card.afterGradePro(2, now = now, config = sm2Deck, zone = saoPaulo, deterministic = true)
        assertNull(graduated.card.learningState)
        assertEquals(2, graduated.card.intervalDays)
        assertEquals(1, graduated.card.repetitions)

        // Easy directly from NEW -> graduates with easyInterval = 6
        val easyGraduated = newCard.afterGradePro(3, now = now, config = sm2Deck, zone = saoPaulo, deterministic = true)
        assertNull(easyGraduated.card.learningState)
        assertEquals(6, easyGraduated.card.intervalDays)

        // Lapse on REVIEW card -> RELEARNING at relearningSteps[0] (15 min), lapses incremented, ease floored >= 1.30
        val reviewCard = Flashcard(
            id = 21L,
            front = "Maduro",
            back = "Verso",
            dueAt = now,
            intervalDays = 10,
            easeFactor = 1.35,
            repetitions = 4,
            lapses = 1,
            deckId = 2L
        )
        val lapsed = reviewCard.afterGradePro(0, now = now, config = sm2Deck, zone = saoPaulo, deterministic = true)
        assertEquals("RELEARNING", lapsed.card.learningState)
        assertEquals(0, lapsed.card.learningStep)
        assertEquals(2, lapsed.card.lapses)
        assertEquals(now + 15 * 60_000L, lapsed.card.dueAt)
        assertTrue(lapsed.card.easeFactor >= 1.30)

        // Graduating back out of RELEARNING restores day scheduling
        val regraduated = lapsed.card.afterGradePro(2, now = lapsed.card.dueAt, config = sm2Deck, zone = saoPaulo, deterministic = true)
        assertNull(regraduated.card.learningState)
        assertTrue(regraduated.card.intervalDays >= 1)

        // Repeated Hard / Again never pushes easeFactor below 1.30
        var card = reviewCard.copy(easeFactor = 1.30)
        repeat(5) {
            card = card.afterGradePro(1, now = now + (it + 1) * dayMs, config = sm2Deck, zone = saoPaulo, deterministic = true).card
            assertTrue(card.easeFactor >= 1.30)
        }
    }

    @Test
    fun ankiLogicalDayRollsOverAtFourAmAndIsBuriedOnOnlyHidesToday() {
        val beforeRollover2Am = epochMillis(2026, 11, 14, 2, 0)
        val justBeforeRollover = epochMillis(2026, 11, 14, 3, 59)
        val exactRollover4Am = epochMillis(2026, 11, 14, 4, 0)

        assertEquals(LocalDate.of(2026, 11, 13), ankiLogicalDay(beforeRollover2Am, dayStartHour = 4, zone = saoPaulo))
        assertEquals(LocalDate.of(2026, 11, 13), ankiLogicalDay(justBeforeRollover, dayStartHour = 4, zone = saoPaulo))
        assertEquals(LocalDate.of(2026, 11, 14), ankiLogicalDay(exactRollover4Am, dayStartHour = 4, zone = saoPaulo))

        val logicalToday = ankiLogicalDay(exactRollover4Am, dayStartHour = 4, zone = saoPaulo)
        val buriedToday = Flashcard(
            id = 30L,
            front = "Enterrado",
            back = "Verso",
            buriedUntilDay = logicalToday.toEpochDay()
        )
        assertTrue(buriedToday.isBuriedOn(logicalToday))
        assertFalse(buriedToday.isBuriedOn(logicalToday.plusDays(1)))
    }

    @Test
    fun leechDetectionTagsAndSuspendsOnlyWhenConfigured() {
        val now = epochMillis(2026, 11, 14, 14, 0)
        val reviewCardBelow = Flashcard(
            id = 40L,
            front = "Difícil",
            back = "Resposta",
            dueAt = now,
            intervalDays = 8,
            repetitions = 3,
            lapses = 5,
            tags = "clinica"
        )
        val noSuspendDeck = CardDeck(id = 3L, name = "Clinica", leechThreshold = 8, leechSuspend = false)
        val suspendDeck = noSuspendDeck.copy(leechSuspend = true)

        // 5 -> 6 lapses (< threshold 8): not a leech yet
        val notLeech = reviewCardBelow.afterGradePro(0, now = now, config = noSuspendDeck, zone = saoPaulo, deterministic = true)
        assertFalse(notLeech.isLeechNow)
        assertFalse(notLeech.card.tags.contains("sanguessuga"))
        assertFalse(notLeech.card.suspended)

        // 7 -> 8 lapses (== threshold 8) with leechSuspend = false -> tagged, NOT suspended
        val leechNoSuspend = reviewCardBelow.copy(lapses = 7)
            .afterGradePro(0, now = now, config = noSuspendDeck, zone = saoPaulo, deterministic = true)
        assertTrue(leechNoSuspend.isLeechNow)
        assertEquals(8, leechNoSuspend.card.lapses)
        assertTrue(leechNoSuspend.card.tags.split(' ').contains("sanguessuga"))
        assertTrue(leechNoSuspend.card.tags.split(' ').contains("clinica"))
        assertFalse(leechNoSuspend.card.suspended)

        // 7 -> 8 lapses with leechSuspend = true -> tagged AND suspended
        val leechWithSuspend = reviewCardBelow.copy(lapses = 7)
            .afterGradePro(0, now = now, config = suspendDeck, zone = saoPaulo, deterministic = true)
        assertTrue(leechWithSuspend.isLeechNow)
        assertTrue(leechWithSuspend.card.tags.split(' ').contains("sanguessuga"))
        assertTrue(leechWithSuspend.card.suspended)
    }

    @Test
    fun cramLeavesCardUntouchedAndReviewPeriodExcludesCramFromMetricsAndCounts() {
        val today = LocalDate.of(2026, 11, 14)
        val now = epochMillis(2026, 11, 14, 15, 0)
        val original = Flashcard(
            id = 50L,
            front = "Cram Front",
            back = "Cram Back",
            dueAt = now + 5 * dayMs,
            intervalDays = 14,
            easeFactor = 2.45,
            repetitions = 6,
            lapses = 1,
            fsrsStability = 14.0,
            fsrsDifficulty = 4.2,
            lastReviewAt = now - 5 * dayMs
        )

        val cramOutcome = original.afterGradePro(grade = 0, now = now, cram = true, durationMs = 2_500L, zone = saoPaulo)
        assertEquals(original, cramOutcome.card)
        assertEquals("CRAM", cramOutcome.review.priorState)
        assertEquals(14, cramOutcome.review.intervalDays)
        assertFalse(cramOutcome.isLeechNow)

        val yesterday = epochMillis(2026, 11, 13, 18, 0)
        val twoDaysAgoCramOnly = epochMillis(2026, 11, 12, 18, 0)
        val reviews = listOf(
            CardReview(id = 1L, cardId = 50L, reviewedAt = now, grade = 2, intervalDays = 16, priorState = "REVIEW", durationMs = 3_000L),
            CardReview(id = 2L, cardId = 51L, reviewedAt = yesterday, grade = 0, intervalDays = 1, priorState = "REVIEW", durationMs = 4_000L),
            cramOutcome.review,
            CardReview(id = 4L, cardId = 52L, reviewedAt = twoDaysAgoCramOnly, grade = 3, intervalDays = 10, priorState = "CRAM", durationMs = 1_500L)
        )

        val period = ReviewPeriod(reviews = reviews, today = today, zone = saoPaulo, days = 7)
        assertEquals(2, period.totalReviews)
        assertEquals(2, period.cramEventsCount)
        assertEquals(2, period.retentionTrials)
        assertEquals(1, period.recalled)
        assertEquals(0.5, period.retentionRate!!, 1e-6)
        assertEquals(1, period.counts[LocalDate.of(2026, 11, 14)])
        assertEquals(1, period.counts[LocalDate.of(2026, 11, 13)])
        // Day with ONLY CRAM activity must not count toward active streak/daily counts
        assertNull(period.counts[LocalDate.of(2026, 11, 12)])
        assertEquals(2, period.dailyBuckets.sumOf { it.second })
    }

    @Test
    fun deckQueueEnforcesAncestorLimitsEvenOnAllOrChildExcludesBuriedSuspendedAndCounts() {
        val today = LocalDate.of(2026, 11, 14)
        val now = epochMillis(2026, 11, 14, 14, 0)

        val parent = CardDeck(id = 1L, name = "Medicina", parentId = null, newLimit = 1, reviewLimit = 1)
        val cardio = CardDeck(id = 2L, name = "Cardiologia", parentId = 1L, newLimit = 10, reviewLimit = 10)
        val neuro = CardDeck(id = 3L, name = "Neuro", parentId = 1L, newLimit = 10, reviewLimit = 10)
        val decks = listOf(parent, cardio, neuro)

        val cards = listOf(
            Flashcard(id = 1L, deckId = 2L, front = "New 1", back = "B", dueAt = now - 1000L),
            Flashcard(id = 2L, deckId = 3L, front = "New 2", back = "B", dueAt = now - 500L),
            Flashcard(id = 3L, deckId = 2L, front = "Rev 1", back = "B", dueAt = now - 1000L, intervalDays = 5, repetitions = 2),
            Flashcard(id = 4L, deckId = 3L, front = "Rev 2", back = "B", dueAt = now - 500L, intervalDays = 5, repetitions = 2),
            Flashcard(id = 5L, deckId = 2L, front = "Learn 1", back = "B", dueAt = now - 100L, learningState = "LEARNING", learningStep = 0),
            Flashcard(id = 6L, deckId = 2L, front = "Buried", back = "B", dueAt = now - 2000L, buriedUntilDay = today.toEpochDay()),
            Flashcard(id = 7L, deckId = 2L, front = "Suspended", back = "B", dueAt = now - 2000L, suspended = true)
        )

        // CRAM review today must NOT consume parent's newLimit/reviewLimit
        val cramOnlyReview = listOf(
            CardReview(id = 99L, cardId = 3L, reviewedAt = now - 60_000L, grade = 2, intervalDays = 5, priorState = "CRAM", deckAtReview = 2L)
        )

        val indexToday = DeckStudyIndex(decks, cards, cramOnlyReview, today, saoPaulo)

        // When deckId == -1L ("Todos"), parent's limit still allows only 1 NEW + 1 REVIEW (+ LEARNING)
        val dueAll = indexToday.due(now, deckId = -1L)
        assertEquals(listOf(5L, 3L, 1L), dueAll.map { it.id })
        assertFalse(dueAll.any { it.id == 6L || it.id == 7L })

        val allCounters = indexToday.countersForDeck(now, deckId = -1L)
        assertEquals(DeckCounters(newCount = 1, learningCount = 1, reviewCount = 1), allCounters)

        // If sibling deck (Neuro, id=3) already reviewed 1 REVIEW card today, Cardio (id=2) has 0 REVIEW remaining due to parent limit!
        val reviewInSibling = listOf(
            CardReview(id = 100L, cardId = 4L, reviewedAt = now - 120_000L, grade = 2, intervalDays = 8, priorState = "REVIEW", deckAtReview = 3L)
        )
        val afterSiblingReview = DeckStudyIndex(decks, cards, reviewInSibling, today, saoPaulo)
        val cardioDue = afterSiblingReview.due(now, deckId = 2L)
        assertFalse(cardioDue.any { it.id == 3L })
        assertEquals(DeckCounters(newCount = 1, learningCount = 1, reviewCount = 0), afterSiblingReview.countersForDeck(now, deckId = 2L))

        // Tomorrow the buried card (id=6) becomes visible in Cardio!
        val tomorrow = today.plusDays(1)
        val nowTomorrow = epochMillis(2026, 11, 15, 10, 0)
        val indexTomorrow = DeckStudyIndex(decks, cards, emptyList(), tomorrow, saoPaulo)
        val cardioTomorrow = indexTomorrow.due(nowTomorrow, deckId = 2L)
        assertTrue(cardioTomorrow.any { it.id == 6L })
        assertFalse(cardioTomorrow.any { it.id == 7L }) // suspended remains excluded
    }

    @Test
    fun cardQueryEngineSupportsAllRequiredFiltersNegationAndOr() {
        val today = LocalDate.of(2026, 11, 14)
        val now = epochMillis(2026, 11, 14, 15, 0)

        val pai = CardDeck(id = 1L, name = "Pai")
        val filho = CardDeck(id = 2L, name = "Filho", parentId = 1L)
        val outro = CardDeck(id = 3L, name = "Outro")
        val decks = listOf(pai, filho, outro)

        val cDueReview = Flashcard(id = 1L, deckId = 2L, front = "Due Review", back = "B", dueAt = now - 1000L, intervalDays = 12, repetitions = 2, tags = "mat/alg/linear", flag = 1)
        val cNew = Flashcard(id = 2L, deckId = 2L, front = "New Card", back = "B", dueAt = now - 1000L, tags = "mat/alg")
        val cSuspended = Flashcard(id = 3L, deckId = 2L, front = "Suspenso", back = "B", dueAt = now - 1000L, intervalDays = 5, repetitions = 1, suspended = true)
        val cBuried = Flashcard(id = 4L, deckId = 3L, front = "Enterrado", back = "B", dueAt = now - 1000L, intervalDays = 10, repetitions = 1, buriedUntilDay = today.toEpochDay())
        val cLeech = Flashcard(id = 5L, deckId = 3L, front = "Leech Card", back = "B", dueAt = now + 5 * dayMs, intervalDays = 15, repetitions = 3, lapses = 8, tags = "sanguessuga mat/geo", flag = 2)
        val all = listOf(cDueReview, cNew, cSuspended, cBuried, cLeech)

        assertEquals(listOf(1L), CardQueryEngine.filter(all, "is:due", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(2L), CardQueryEngine.filter(all, "is:new", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(3L), CardQueryEngine.filter(all, "is:suspended", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(4L), CardQueryEngine.filter(all, "is:buried", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(5L), CardQueryEngine.filter(all, "is:leech", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(1L), CardQueryEngine.filter(all, "flag:1", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), CardQueryEngine.filter(all, "deck:Pai::Filho", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(1L, 2L), CardQueryEngine.filter(all, "tag:mat/alg", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(1L, 4L, 5L), CardQueryEngine.filter(all, "prop:ivl>=10", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(1L, 2L), CardQueryEngine.filter(all, "deck:Pai::Filho -is:suspended", decks, now, saoPaulo).map { it.id })
        assertEquals(listOf(1L, 5L), CardQueryEngine.filter(all, "flag:1 OR is:leech", decks, now, saoPaulo).map { it.id })
    }

    @Test
    fun clozeParserHandlesNestedLatexBracesWithoutPrematureCutoff() {
        val text = """Frações: {{c1::\frac{1}{2}}} e raiz {{c2::\sqrt{x^{2} + {y^{2}}}::fórmula}}."""
        val matches = findClozeMatches(text)

        assertEquals(2, matches.size)
        assertEquals("1", matches[0].number)
        assertEquals("""\frac{1}{2}""", matches[0].answer)
        assertEquals("", matches[0].hint)

        assertEquals("2", matches[1].number)
        assertEquals("""\sqrt{x^{2} + {y^{2}}}""", matches[1].answer)
        assertEquals("fórmula", matches[1].hint)

        val generated = generateCards(CardFormat.CLOZE, text, "Nota extra")
        assertEquals(2, generated.size)
        assertTrue(generated[0].first.contains("[…]"))
        assertFalse(generated[0].first.contains("""\frac{1}{2}"""))
        assertTrue(generated[0].first.contains("""\sqrt{x^{2} + {y^{2}}}"""))
        assertTrue(generated[0].second.contains("""\frac{1}{2}"""))

        assertTrue(generated[1].first.contains("[fórmula]"))
        assertFalse(generated[1].first.contains("""\sqrt{x^{2} + {y^{2}}}"""))
        assertTrue(generated[1].first.contains("""\frac{1}{2}"""))
        assertTrue(generated[1].second.contains("""\sqrt{x^{2} + {y^{2}}}"""))
    }

    @Test
    fun occlusionDoesNotLeakLabelsPreservesIdsOnDeleteAndDeckFileV4RoundTrips() {
        val box1 = OcclusionBox(0.10f, 0.10f, 0.35f, 0.30f, label = "Aorta", id = 1, hideAll = true)
        val box2 = OcclusionBox(0.55f, 0.45f, 0.85f, 0.75f, label = "Ventrículo Esquerdo", id = 2, hideAll = true)
        val encodedAll = encodeOcclusionBoxes(listOf(box1, box2))

        val fullNote = CardAuthorNote(
            format = "occlusion",
            prompt = "Anatomia Cardíaca",
            answer = "Circulação sistêmica",
            imageKey = "heart.png",
            occlusion = encodedAll,
            tags = "med/cardio anatomia",
            typeAnswer = true
        )

        val allVariants = fullNote.variants()
        assertEquals(setOf("c1", "c2"), allVariants.keys)
        val card1Front = allVariants.getValue("c1").first
        val card1Back = allVariants.getValue("c1").second
        assertTrue(card1Front.contains("[Oclusão #1]"))
        assertFalse(card1Front.contains("Aorta"))
        assertFalse(card1Front.contains("Ventrículo"))
        assertTrue(card1Back.contains("#1: Aorta"))

        // Deleting box #1 must keep box #2's id = 2 instead of renumbering to c1!
        val afterDeleteEncoded = encodeOcclusionBoxes(listOf(box2))
        val parsedAfterDelete = parseOcclusionBoxes(afterDeleteEncoded)
        assertEquals(1, parsedAfterDelete.size)
        assertEquals(2, parsedAfterDelete.single().id)

        val noteAfterDelete = fullNote.copy(occlusion = afterDeleteEncoded)
        val variantsAfterDelete = noteAfterDelete.variants()
        assertEquals(setOf("c2"), variantsAfterDelete.keys)
        assertTrue(variantsAfterDelete.getValue("c2").first.contains("[Oclusão #2]"))
        assertFalse(variantsAfterDelete.getValue("c2").first.contains("Ventrículo"))
        assertTrue(variantsAfterDelete.getValue("c2").second.contains("#2: Ventrículo Esquerdo"))

        val fsrsCfg = CardDeck(
            name = "Cardio Pro",
            schedulerMode = "FSRS",
            desiredRetention = 0.88,
            learningSteps = "5 15",
            relearningSteps = "10",
            graduatingInterval = 2,
            easyInterval = 5,
            burySiblings = false,
            leechThreshold = 6,
            leechSuspend = true,
            dayStartHour = 5,
            isFiltered = true,
            filterQuery = "tag:med/cardio"
        )
        val deckFile = DeckFile(
            name = "Cardio Pro",
            notes = listOf(
                SharedCardNote(
                    source = noteAfterDelete,
                    keys = setOf("c2"),
                    tags = "med/cardio anatomia",
                    typeAnswer = true,
                    flag = 3
                )
            ),
            images = mapOf("heart.png" to byteArrayOf(10, 20, 30, 40)),
            deckConfig = fsrsCfg
        )

        val encodedV4 = deckFile.encode()
        assertTrue(encodedV4.contains(DeckFile.V4_FORMAT))
        val decoded = DeckFile.read(StringReader(encodedV4))
        assertEquals("Cardio Pro", decoded.name)
        assertEquals(1, decoded.notes.size)

        val importedNote = decoded.notes.single()
        assertEquals("occlusion", importedNote.source.format)
        assertEquals(afterDeleteEncoded, importedNote.source.occlusion)
        assertEquals("med/cardio anatomia", importedNote.tags)
        assertTrue(importedNote.typeAnswer)
        assertTrue(importedNote.source.typeAnswer)
        assertEquals(3, importedNote.flag)
        assertEquals(setOf("c2"), importedNote.keys)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), decoded.images["heart.png"])

        val importedCfg = decoded.deckConfig!!
        assertEquals("FSRS", importedCfg.schedulerMode)
        assertEquals(0.88, importedCfg.desiredRetention, 1e-6)
        assertEquals("5 15", importedCfg.learningSteps)
        assertFalse(importedCfg.burySiblings)
        assertEquals(6, importedCfg.leechThreshold)
        assertTrue(importedCfg.leechSuspend)
        assertEquals(5, importedCfg.dayStartHour)
        assertTrue(importedCfg.isFiltered)
        assertEquals("tag:med/cardio", importedCfg.filterQuery)
    }

    @Test
    fun csvDeckCodecAndApkgPackageCodecSupportDirectivesRfc4180ClozeMediaAndAnki21bFallback() {
        // 1. CsvDeckCodec: Anki directives + RFC4180 multiline quotes & escaped ""
        val tsvInput = """
            #separator:Tab
            #html:true
            #tags column:4
            Front	Back	Extra	Tags
            "Pergunta ""RFC4180""
            com quebra"	"<b>Resposta</b><br>limpa"	ignorado	"bio/celula anki"
            "Qual {{c1::organela}} gera ATP?"	"Mitocôndria"	nota	"bio/energia"
        """.trimIndent()

        val importedCsv = CsvDeckCodec.importCsv(StringReader(tsvInput), deckName = "Biologia")
        assertEquals(2, importedCsv.notes.size)
        assertEquals("Pergunta \"RFC4180\"\ncom quebra", importedCsv.notes[0].source.prompt)
        assertEquals("Resposta\nlimpa", importedCsv.notes[0].source.answer)
        assertEquals("bio/celula anki", importedCsv.notes[0].tags)
        assertEquals("CLOZE", importedCsv.notes[1].source.format)
        assertEquals(setOf("c1"), importedCsv.notes[1].keys)

        // Round-trip via exportCsv -> importCsv
        val cardsToExport = listOf(
            Flashcard(front = "Termo, com vírgula", back = "Linha 1\nLinha \"2\"", tags = "mat/alg revisao"),
            Flashcard(front = "Simples", back = "Ok", tags = "estudo")
        )
        val exportedCsv = CsvDeckCodec.exportCsv(cardsToExport, separator = ',', includeTags = true)
        val reimported = CsvDeckCodec.importCsv(StringReader(exportedCsv), deckName = "Exported", delimiter = null)
        assertEquals(2, reimported.notes.size)
        assertEquals("Termo, com vírgula", reimported.notes[0].source.prompt)
        assertEquals("Linha 1\nLinha \"2\"", reimported.notes[0].source.answer)
        assertEquals("mat/alg revisao", reimported.notes[0].tags)

        // 2. ApkgPackageCodec.parseRawTables: Cloze model + <img src="..."> media
        val decksJson = """{"100":{"id":100,"name":"Biologia::Citologia"}}"""
        val modelsJson = """
            {
              "200": {
                "id": 200,
                "name": "Cloze Pro",
                "type": 1,
                "flds": [{"ord": 0, "name": "Text"}, {"ord": 1, "name": "Extra"}],
                "tmpls": [{"name": "Cloze", "ord": 0, "qfmt": "{{cloze:Text}}", "afmt": "{{cloze:Text}}<br>{{Extra}}"}]
              }
            }
        """.trimIndent()
        val rawTables = ApkgRawTables(
            decksJson = decksJson,
            modelsJson = modelsJson,
            notes = listOf(
                ApkgNoteRow(
                    id = 1L,
                    mid = 200L,
                    flds = "Organela energética: {{c1::Mitocôndria}} <img src=\"cell.png\">\u001FPotência celular",
                    tags = " bio/citologia exame "
                )
            ),
            cards = listOf(
                ApkgCardRow(
                    id = 10L,
                    nid = 1L,
                    did = 100L,
                    ord = 0,
                    type = 2,
                    queue = 2,
                    due = 10,
                    ivl = 7,
                    factor = 2500,
                    reps = 3,
                    lapses = 0,
                    flags = 2
                )
            )
        )
        val imgBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val parsedRaw = ApkgPackageCodec.parseRawTables(rawTables, mapOf("cell.png" to imgBytes), nowMillis = epochMillis(2026, 11, 14))
        assertTrue(parsedRaw.warnings.isEmpty())
        assertEquals(1, parsedRaw.decks.size)
        assertEquals("Biologia::Citologia", parsedRaw.decks.single().name)
        val apkgCard = parsedRaw.decks.single().cards.single()
        assertEquals("Cloze Pro", apkgCard.noteTypeKey)
        assertTrue(apkgCard.front.contains("[…]"))
        assertFalse(apkgCard.front.contains("Mitocôndria"))
        assertTrue(apkgCard.back.contains("Mitocôndria"))
        assertTrue(apkgCard.back.contains("Potência celular"))
        assertEquals("cell.png", apkgCard.imageName)
        assertArrayEquals(imgBytes, apkgCard.imageBytes)
        assertEquals(7, apkgCard.intervalDays)
        assertEquals(2.5, apkgCard.easeFactor, 1e-6)
        assertEquals(2, apkgCard.flag)

        // 3. ApkgPackageCodec.readApkg: .anki21b (zstd magic) triggers fallback warning while reading collection.anki2
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("collection.anki21b"))
                zos.write(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x01, 0x02))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("collection.anki2"))
                zos.write("sqlite-placeholder".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("media"))
                zos.write("""{"0":"cell.png"}""".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("0"))
                zos.write(imgBytes)
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val tempDir = Files.createTempDirectory("apkg_test_cache").toFile()
        try {
            val result = ApkgPackageCodec.readApkg(ByteArrayInputStream(zipBytes), tempDir) { rawTables }
            assertEquals(1, result.decks.size)
            assertTrue(result.warnings.any { it.contains("collection.anki21b") && it.contains("zstd") })
            assertEquals("cell.png", result.decks.single().cards.single().imageName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun apkgStreamingModeKeepsMediaOnDiskInsteadOfHeap() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki2"))
            zip.write(byteArrayOf(0x53, 0x51, 0x4C, 0x69))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("media"))
            zip.write("""{"0":"diagrama.png"}""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("0"))
            zip.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            zip.closeEntry()
        }

        val rawTables = ApkgRawTables(
            decksJson = """{"1":{"id":1,"name":"Matemática"}}""",
            modelsJson = """{"10":{"id":10,"name":"Basic","type":0,"flds":[{"name":"Front"},{"name":"Back"}],"tmpls":[{"name":"Card 1","ord":0,"qfmt":"{{Front}}","afmt":"{{Back}}"}]}}""",
            notes = listOf(ApkgNoteRow(id = 100L, mid = 10L, flds = "<img src=\"diagrama.png\">\u001FResposta", tags = "ita")),
            cards = listOf(ApkgCardRow(id = 200L, nid = 100L, did = 1L, ord = 0, type = 0, queue = 0, due = 0L, ivl = 0, factor = 2500, reps = 0, lapses = 0, flags = 0))
        )

        val tempDir = Files.createTempDirectory("apkg-stream-test").toFile()
        try {
            val result = ApkgPackageCodec.readApkg(
                ByteArrayInputStream(baos.toByteArray()),
                tempDir,
                keepMediaFilesOnDisk = true
            ) { rawTables }
            val imported = result.decks.single().cards.single()
            assertNull(imported.imageBytes)
            assertNotNull(imported.imageFile)
            assertTrue(imported.imageFile!!.exists())
            assertEquals("diagrama.png", imported.imageName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun evaluateTypeAnswerProducesAccurateMatchWrongMissingAndExtraDiffs() {
        val exact = evaluateTypeAnswer("mitose", "mitose")
        assertEquals(6, exact.size)
        assertTrue(exact.all { it.status == TypeDiffStatus.MATCH })

        val wrong = evaluateTypeAnswer("casa", "caza")
        assertEquals(
            listOf(
                TypeDiffChar('c', TypeDiffStatus.MATCH),
                TypeDiffChar('a', TypeDiffStatus.MATCH),
                TypeDiffChar('z', TypeDiffStatus.WRONG),
                TypeDiffChar('a', TypeDiffStatus.MATCH)
            ),
            wrong
        )

        val missing = evaluateTypeAnswer("mitose", "mito")
        assertEquals(
            listOf(
                TypeDiffChar('m', TypeDiffStatus.MATCH),
                TypeDiffChar('i', TypeDiffStatus.MATCH),
                TypeDiffChar('t', TypeDiffStatus.MATCH),
                TypeDiffChar('o', TypeDiffStatus.MATCH),
                TypeDiffChar('s', TypeDiffStatus.MISSING),
                TypeDiffChar('e', TypeDiffStatus.MISSING)
            ),
            missing
        )

        val extra = evaluateTypeAnswer("mito", "mitose")
        assertEquals(
            listOf(
                TypeDiffChar('m', TypeDiffStatus.MATCH),
                TypeDiffChar('i', TypeDiffStatus.MATCH),
                TypeDiffChar('t', TypeDiffStatus.MATCH),
                TypeDiffChar('o', TypeDiffStatus.MATCH),
                TypeDiffChar('s', TypeDiffStatus.EXTRA),
                TypeDiffChar('e', TypeDiffStatus.EXTRA)
            ),
            extra
        )

        // Cloze / {{type:...}} wrapper & NFD vs NFC normalization support
        val nfdTyped = "ac\u0327a\u0303o"
        val clozeWrapped = evaluateTypeAnswer("{{c1::ação::substantivo}}", nfdTyped)
        assertEquals("ação".length, clozeWrapped.size)
        assertTrue(clozeWrapped.all { it.status == TypeDiffStatus.MATCH })
    }

    @Test
    fun deckStudyIndexComputesSinglePassSubtreeRollupsAndCountersAtScale() {
        val today = LocalDate.of(2026, 11, 14)
        val now = epochMillis(2026, 11, 14, 15, 0)

        val root = CardDeck(id = 1L, name = "Medicina", parentId = null, newLimit = 25, reviewLimit = 35)
        val childA = CardDeck(id = 2L, name = "Clínica", parentId = 1L, newLimit = 15, reviewLimit = 20)
        val grandChildA1 = CardDeck(id = 3L, name = "Cardio", parentId = 2L, newLimit = 8, reviewLimit = 12)
        val childB = CardDeck(id = 4L, name = "Cirurgia", parentId = 1L, newLimit = 20, reviewLimit = 25)
        val siblingRoot = CardDeck(id = 5L, name = "Idiomas", parentId = null, newLimit = 10, reviewLimit = 10)
        val decks = listOf(root, childA, grandChildA1, childB, siblingRoot)

        val cards = mutableListOf<Flashcard>()
        var nextId = 1L
        fun addBatch(
            deckId: Long?,
            count: Int,
            dueAt: Long,
            intervalDays: Int = 0,
            repetitions: Int = 0,
            learningState: String? = null,
            lapses: Int = 0,
            suspended: Boolean = false,
            buriedUntilDay: Long = 0L,
            subject10Count: Int = count
        ): List<Flashcard> {
            val created = ArrayList<Flashcard>(count)
            repeat(count) { i ->
                val card = Flashcard(
                    id = nextId++,
                    subjectId = if (i < subject10Count) 10L else 20L,
                    deckId = deckId,
                    front = "Q-${nextId}",
                    back = "A-${nextId}",
                    dueAt = dueAt,
                    intervalDays = intervalDays,
                    repetitions = repetitions,
                    learningState = learningState,
                    lapses = lapses,
                    suspended = suspended,
                    buriedUntilDay = buriedUntilDay
                )
                cards.add(card)
                created.add(card)
            }
            return created
        }

        // grandChildA1 (id=3): 35 new, 5 learning, 30 review, 10 future, 5 suspended, 4 buried = 89
        addBatch(3L, 35, dueAt = now - 5_000L, subject10Count = 20)
        addBatch(3L, 5, dueAt = now - 4_000L, learningState = "LEARNING", subject10Count = 5)
        addBatch(3L, 30, dueAt = now - 3_000L, intervalDays = 10, repetitions = 3, subject10Count = 18)
        val gcFuture = addBatch(3L, 10, dueAt = now + 2 * dayMs, intervalDays = 12, repetitions = 3, subject10Count = 5)
        addBatch(3L, 5, dueAt = now - 2_000L, suspended = true, subject10Count = 5)
        addBatch(3L, 4, dueAt = now - 2_000L, buriedUntilDay = today.toEpochDay(), subject10Count = 4)

        // childA (id=2): 20 new, 4 relearning, 20 review, 8 future, 4 suspended, 3 buried = 59
        addBatch(2L, 20, dueAt = now - 5_000L, subject10Count = 10)
        addBatch(2L, 4, dueAt = now - 4_000L, learningState = "RELEARNING", lapses = 1, subject10Count = 0)
        addBatch(2L, 20, dueAt = now - 3_000L, intervalDays = 7, repetitions = 2, subject10Count = 10)
        val caFuture = addBatch(2L, 8, dueAt = now + 2 * dayMs, intervalDays = 7, repetitions = 2, subject10Count = 4)
        addBatch(2L, 4, dueAt = now - 2_000L, suspended = true, subject10Count = 4)
        addBatch(2L, 3, dueAt = now - 2_000L, intervalDays = 5, repetitions = 1, buriedUntilDay = today.toEpochDay(), subject10Count = 3)

        // childB (id=4): 30 new, 5 learning, 25 review, 6 future, 4 suspended, 3 buried = 73
        addBatch(4L, 30, dueAt = now - 5_000L, subject10Count = 15)
        addBatch(4L, 5, dueAt = now - 4_000L, learningState = "LEARNING", subject10Count = 5)
        addBatch(4L, 25, dueAt = now - 3_000L, intervalDays = 14, repetitions = 4, subject10Count = 15)
        val cbFuture = addBatch(4L, 6, dueAt = now + 2 * dayMs, intervalDays = 14, repetitions = 4, subject10Count = 3)
        addBatch(4L, 4, dueAt = now - 2_000L, suspended = true, subject10Count = 4)
        addBatch(4L, 3, dueAt = now - 2_000L, buriedUntilDay = today.toEpochDay(), subject10Count = 3)

        // siblingRoot (id=5): 20 new, 3 learning, 20 review, 4 future, 4 suspended, 2 buried = 53
        addBatch(5L, 20, dueAt = now - 5_000L, subject10Count = 0)
        addBatch(5L, 3, dueAt = now - 4_000L, learningState = "LEARNING", subject10Count = 0)
        addBatch(5L, 20, dueAt = now - 3_000L, intervalDays = 6, repetitions = 2, subject10Count = 0)
        val srFuture = addBatch(5L, 4, dueAt = now + 2 * dayMs, intervalDays = 6, repetitions = 2, subject10Count = 0)
        addBatch(5L, 4, dueAt = now - 2_000L, suspended = true, subject10Count = 0)
        addBatch(5L, 2, dueAt = now - 2_000L, intervalDays = 9, repetitions = 2, buriedUntilDay = today.toEpochDay(), subject10Count = 0)

        // unassigned (deckId=null): 5 new, 2 learning, 5 review, 3 suspended = 15
        addBatch(null, 5, dueAt = now - 5_000L, subject10Count = 5)
        addBatch(null, 2, dueAt = now - 4_000L, learningState = "LEARNING", subject10Count = 2)
        addBatch(null, 5, dueAt = now - 3_000L, intervalDays = 4, repetitions = 1, subject10Count = 5)
        addBatch(null, 3, dueAt = now - 2_000L, suspended = true, subject10Count = 3)

        assertEquals(289, cards.size)

        val yesterday = epochMillis(2026, 11, 13, 18, 0)
        val reviews = listOf(
            // grandChildA1: 2 unique NEW, 1 REVIEW, 1 duplicate LEARNING on same card, 1 CRAM
            CardReview(id = 1L, cardId = gcFuture[0].id, reviewedAt = now - 600_000L, grade = 0, intervalDays = 0, priorState = "NEW", deckAtReview = 3L),
            CardReview(id = 2L, cardId = gcFuture[0].id, reviewedAt = now - 300_000L, grade = 2, intervalDays = 1, priorState = "LEARNING", deckAtReview = 3L),
            CardReview(id = 3L, cardId = gcFuture[1].id, reviewedAt = now - 500_000L, grade = 2, intervalDays = 1, priorState = "NEW", deckAtReview = 3L),
            CardReview(id = 4L, cardId = gcFuture[2].id, reviewedAt = now - 400_000L, grade = 2, intervalDays = 15, priorState = "REVIEW", deckAtReview = 3L),
            CardReview(id = 5L, cardId = gcFuture[3].id, reviewedAt = now - 200_000L, grade = 2, intervalDays = 12, priorState = "CRAM", deckAtReview = 3L),
            // childA: 1 NEW, 2 REVIEW
            CardReview(id = 6L, cardId = caFuture[0].id, reviewedAt = now - 500_000L, grade = 2, intervalDays = 1, priorState = "NEW", deckAtReview = 2L),
            CardReview(id = 7L, cardId = caFuture[1].id, reviewedAt = now - 450_000L, grade = 2, intervalDays = 10, priorState = "REVIEW", deckAtReview = 2L),
            CardReview(id = 8L, cardId = caFuture[2].id, reviewedAt = now - 400_000L, grade = 3, intervalDays = 14, priorState = "REVIEW", deckAtReview = 2L),
            // childB: 3 NEW, 1 REVIEW
            CardReview(id = 9L, cardId = cbFuture[0].id, reviewedAt = now - 500_000L, grade = 2, intervalDays = 1, priorState = "NEW", deckAtReview = 4L),
            CardReview(id = 10L, cardId = cbFuture[1].id, reviewedAt = now - 480_000L, grade = 2, intervalDays = 1, priorState = "NEW", deckAtReview = 4L),
            CardReview(id = 11L, cardId = cbFuture[2].id, reviewedAt = now - 460_000L, grade = 2, intervalDays = 1, priorState = "NEW", deckAtReview = 4L),
            CardReview(id = 12L, cardId = cbFuture[3].id, reviewedAt = now - 440_000L, grade = 2, intervalDays = 18, priorState = "REVIEW", deckAtReview = 4L),
            // siblingRoot: legacy null priorState resolved via yesterday history -> REVIEW, plus 1 NEW
            CardReview(id = 13L, cardId = srFuture[0].id, reviewedAt = yesterday, grade = 2, intervalDays = 4, priorState = null, deckAtReview = null),
            CardReview(id = 14L, cardId = srFuture[0].id, reviewedAt = now - 350_000L, grade = 2, intervalDays = 8, priorState = null, deckAtReview = null),
            CardReview(id = 15L, cardId = srFuture[1].id, reviewedAt = now - 320_000L, grade = 2, intervalDays = 1, priorState = "NEW", deckAtReview = 5L)
        )

        val startNs = System.nanoTime()
        val index = DeckStudyIndex(decks, cards, reviews, today, saoPaulo)
        val allDeckIds = listOf(-1L, 0L, 1L, 2L, 3L, 4L, 5L)
        val countersById = allDeckIds.associateWith { index.countersForDeck(now, it) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("DeckStudyIndex build + counters took too long: ${elapsedMs}ms", elapsedMs < 2_000L)

        // Exact subtree reviewed-today rollups (CRAM excluded, cardId deduplicated)
        assertEquals(3, index.doneToday(3L))
        assertEquals(6, index.doneToday(2L))
        assertEquals(4, index.doneToday(4L))
        assertEquals(10, index.doneToday(1L))
        assertEquals(2, index.doneToday(5L))

        // Exact O(1) counters respecting ancestor limits & rollups
        assertEquals(DeckCounters(newCount = 6, learningCount = 5, reviewCount = 11), countersById.getValue(3L))
        assertEquals(DeckCounters(newCount = 12, learningCount = 9, reviewCount = 17), countersById.getValue(2L))
        assertEquals(DeckCounters(newCount = 17, learningCount = 5, reviewCount = 24), countersById.getValue(4L))
        assertEquals(DeckCounters(newCount = 19, learningCount = 14, reviewCount = 31), countersById.getValue(1L))
        assertEquals(DeckCounters(newCount = 9, learningCount = 3, reviewCount = 9), countersById.getValue(5L))
        assertEquals(DeckCounters(newCount = 5, learningCount = 2, reviewCount = 5), countersById.getValue(0L))
        assertEquals(DeckCounters(newCount = 33, learningCount = 19, reviewCount = 45), countersById.getValue(-1L))

        // Exact parity between countersForDeck and due() across every deck (and subject-filtered too)
        for (deckId in allDeckIds) {
            val dueCards = index.due(now, deckId)
            val expected = DeckCounters(
                newCount = dueCards.count { it.reviewState == "NEW" },
                learningCount = dueCards.count { it.reviewState == "LEARNING" || it.reviewState == "RELEARNING" },
                reviewCount = dueCards.count { it.reviewState == "REVIEW" }
            )
            assertEquals("Parity mismatch for deckId=$deckId", expected, countersById.getValue(deckId))

            val subDue = index.due(now, deckId, subjectId = 10L)
            val subExpected = DeckCounters(
                newCount = subDue.count { it.reviewState == "NEW" },
                learningCount = subDue.count { it.reviewState == "LEARNING" || it.reviewState == "RELEARNING" },
                reviewCount = subDue.count { it.reviewState == "REVIEW" }
            )
            assertEquals("Subject parity mismatch for deckId=$deckId", subExpected, index.countersForDeck(now, deckId, subjectId = 10L))
        }

        // Next day: buried cards resurface and full daily limits apply cleanly
        val tomorrow = today.plusDays(1)
        val nowTomorrow = epochMillis(2026, 11, 15, 10, 0)
        val indexTomorrow = DeckStudyIndex(decks, cards, reviews, tomorrow, saoPaulo)
        assertEquals(DeckCounters(newCount = 25, learningCount = 14, reviewCount = 35), indexTomorrow.countersForDeck(nowTomorrow, 1L))
        assertEquals(DeckCounters(newCount = 8, learningCount = 5, reviewCount = 12), indexTomorrow.countersForDeck(nowTomorrow, 3L))
        assertEquals(0, indexTomorrow.doneToday(1L))
    }
}
