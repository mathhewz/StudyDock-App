package com.studydock.app.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReviewMetricsTest {
    private val date = LocalDate.of(2026, 3, 8)
    private val zone = ZoneId.of("America/New_York")
    private val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val card = Flashcard(front = "Q", back = "A", dueAt = start)

    @Test fun firstFailureCountsAsLearningInBothCollectionAndForecast() {
        val deck = CardDeck(name = "Test", dayStartHour = 0, learningSteps = "1 10", graduatingInterval = 1)
        val retry = card.afterGradePro(0, start, config = deck, zone = zone, deterministic = true).card
        val step1 = retry.afterGradePro(2, retry.dueAt, config = deck, zone = zone, deterministic = true).card
        assertEquals("LEARNING", step1.reviewState)
        assertEquals(1, step1.learningStep)
        val young = step1.afterGradePro(2, step1.dueAt, config = deck, zone = zone, deterministic = true).card
        assertEquals("REVIEW", young.reviewState)
        val mature = young.copy(intervalDays = 21, dueAt = start - 1)
        val cards = listOf(card, retry, young, mature, retry.copy(suspended = true))
        assertEquals(IntervalGroup.entries.toSet(), cards.map { it.intervalGroup() }.toSet())
        assertEquals(listOf(2,1,0,0,0,0,0), reviewForecast(cards, date, zone))
        assertEquals(end - start, young.dueAt - retry.dueAt + (retry.dueAt - start))
    }

    @Test fun failedCardStaysDueTodayAfterItWasAlreadyAnswered() {
        val failed = card.copy(id = 7).afterGradePro(0, start, zone = zone, deterministic = true).card
        val passed = card.copy(id = 8).afterGradePro(3, start, zone = zone, deterministic = true).card
        val index = DeckStudyIndex(emptyList(), listOf(failed, passed), emptyList(), date, zone)
        assertEquals(1, remainingToday(index, end - 1))
        assertEquals(failed.id, index.due(end - 1).single().id)
    }

    @Test fun observedRetentionUsesFirstScheduledReviewEachDayAndKnownDurations() {
        fun row(id: Long, cardId: Long, time: Long, grade: Int, state: String?, ms: Long?) =
            CardReview(id, cardId, time, grade, 1, durationMs = ms, priorState = state)
        val events = listOf(
            row(1,1,start,0,"REVIEW",1000), row(2,1,start,2,"REVIEW",500),
            row(3,2,start,3,"REVIEW",2000), row(4,3,end - 1,2,null,null),
            row(5,4,end - 1,2,"NEW",500), row(6,2,start - 1,0,"REVIEW",9000),
            row(7,1,end,1,"REVIEW",8000)
        )
        val period = ReviewPeriod(events.reversed(), date, zone, 1)
        assertEquals(5, period.events.size)
        assertEquals(2, period.retentionTrials)
        assertEquals(1, period.recalled)
        assertEquals(1, period.legacyCount)
        assertEquals(1000L, period.averageMs)
        assertEquals(4000L, period.measuredMs)
        assertEquals(listOf(date to 5), period.dailyBuckets)
        val empty = ReviewPeriod(emptyList(), date, zone, 365)
        assertNull(empty.averageMs)
        assertEquals(0, empty.retentionTrials)
        assertEquals(0, empty.dailyBuckets.sumOf { it.second })
    }
}
