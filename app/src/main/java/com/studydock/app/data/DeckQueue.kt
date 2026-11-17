package com.studydock.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

private const val UNBOUNDED_CUTOFF_MS = 4_102_444_800_000L // 2100-01-01T00:00:00Z
private const val UNASSIGNED_DECK_IDX = -1
private const val UNKNOWN_DECK_IDX = -2
private const val STATE_NEW: Byte = 0
private const val STATE_REVIEW: Byte = 1
private const val STATE_LEARNING: Byte = 2
private val ZERO_COUNTERS = DeckCounters(0, 0, 0)

/** Per-deck remaining queue counters ready for study. */
data class DeckCounters(
    val newCount: Int,
    val learningCount: Int,
    val reviewCount: Int
)

/** Positive keys are persisted decks; 0 and -1 are the unassigned and all-decks filters. */
class DeckTree(decks: List<CardDeck>) {
    private val byId = decks.associateBy { it.id }

    fun lineage(member: Long?): List<CardDeck> {
        val result = mutableListOf<CardDeck>()
        val seen = mutableSetOf<Long>()
        var current = member?.let(byId::get)
        while (current != null && seen.add(current.id)) {
            result.add(current)
            current = current.parentId?.let(byId::get)
        }
        return result
    }

    private val paths = byId.keys.associateWith { lineage(it) }
    private val ancestorIdSets: Map<Long, Set<Long>> = paths.mapValues { (_, list) ->
        HashSet<Long>(list.size * 2 + 1).apply { for (d in list) add(d.id) }
    }
    private val labelsById: Map<Long, String> = paths.mapValues { (_, list) ->
        list.asReversed().joinToString(" › ") { it.name }
    }

    fun ancestors(member: Long?) = paths[member].orEmpty()
    fun contains(selected: Long, member: Long?): Boolean = when (selected) {
        -1L -> true
        0L -> member == null
        else -> selected == member || ancestorIdSets[member]?.contains(selected) == true
    }

    fun label(id: Long): String = labelsById[id].orEmpty()
    val ordered = decks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { labelsById[it.id].orEmpty() })
}

/**
 * Quotas are logical-day counts honoring each deck's rollover [CardDeck.dayStartHour],
 * full parent/ancestor daily limits, buried cards, and excluding CRAM reviews.
 */
class DeckStudyIndex(
    decks: List<CardDeck>,
    cards: List<Flashcard>,
    reviews: List<CardReview>,
    private val today: LocalDate,
    private val zone: ZoneId,
    candidates: Set<Long>? = null
) {
    val tree = DeckTree(decks)
    private val todayEpochDay = today.toEpochDay()
    private val orderedDecks = tree.ordered
    private val deckCount = orderedDecks.size
    private val deckArray = Array(deckCount) { orderedDecks[it] }
    private val deckIdToIdx = HashMap<Long, Int>(deckCount * 2 + 1)
    private val deckNewLimit = IntArray(deckCount)
    private val deckReviewLimit = IntArray(deckCount)
    private val deckDayStartHour = IntArray(deckCount)
    private val deckAncestorIndices: Array<IntArray>
    private val parentIndices = IntArray(deckCount)
    private val childrenIndices: Array<IntArray>
    private val topDownOrder: IntArray
    private val rootIndices: IntArray
    private val hasCycle: Boolean

    private val introUsedArr = IntArray(deckCount)
    private val reviewUsedArr = IntArray(deckCount)
    private val doneCountArr = IntArray(deckCount)

    private val ordered: List<Flashcard>
    private val cardCount: Int
    private val cardArray: Array<Flashcard>
    private val cardDeckIdx: IntArray
    private val cardDueAt: LongArray
    private val cardSubjectId: LongArray
    private val cardBuriedUntilDay: LongArray
    private val cardDayStartHour: IntArray
    private val cardStateCode: ByteArray
    private val cardAncestorIndices: Array<IntArray>

    private class CounterTable(
        val allCounters: DeckCounters,
        val unassignedCounters: DeckCounters,
        val byDeckIdx: Array<DeckCounters>,
        val unknownByDeckId: Map<Long, DeckCounters>
    )

    private data class CacheEntry(
        val now: Long,
        val subjectId: Long,
        val table: CounterTable
    )

    private val counterCacheLock = Any()
    private val counterCache = ArrayList<CacheEntry>(4)

    init {
        for (i in 0 until deckCount) {
            val d = deckArray[i]
            deckIdToIdx[d.id] = i
            deckNewLimit[i] = d.newLimit ?: Int.MAX_VALUE
            deckReviewLimit[i] = d.reviewLimit ?: Int.MAX_VALUE
            deckDayStartHour[i] = d.dayStartHour.coerceIn(0, 23)
        }

        val childBuckets = Array(deckCount) { mutableListOf<Int>() }
        val rootsList = ArrayList<Int>(deckCount)
        for (i in 0 until deckCount) {
            val pid = deckArray[i].parentId
            val pIdx = if (pid != null) (deckIdToIdx[pid] ?: -1) else -1
            parentIndices[i] = pIdx
            if (pIdx >= 0) childBuckets[pIdx].add(i) else rootsList.add(i)
        }
        childrenIndices = Array(deckCount) { childBuckets[it].toIntArray() }
        rootIndices = rootsList.toIntArray()

        val visitState = ByteArray(deckCount)
        var cycleFound = false
        for (start in 0 until deckCount) {
            if (visitState[start] != 0.toByte()) continue
            var cur = start
            while (cur >= 0 && visitState[cur] == 0.toByte()) {
                visitState[cur] = 1
                cur = parentIndices[cur]
            }
            if (cur >= 0 && visitState[cur] == 1.toByte()) {
                cycleFound = true
                break
            }
            var mark = start
            while (mark >= 0 && visitState[mark] == 1.toByte()) {
                visitState[mark] = 2
                mark = parentIndices[mark]
            }
        }
        hasCycle = cycleFound

        topDownOrder = IntArray(deckCount)
        if (!cycleFound) {
            var head = 0
            var tail = 0
            for (r in rootIndices) topDownOrder[tail++] = r
            while (head < tail) {
                val cur = topDownOrder[head++]
                for (child in childrenIndices[cur]) topDownOrder[tail++] = child
            }
        }

        deckAncestorIndices = Array(deckCount) { i ->
            val chain = tree.ancestors(deckArray[i].id)
            IntArray(chain.size) { k -> deckIdToIdx.getValue(chain[k].id) }
        }

        val filtered = ArrayList<Flashcard>(cards.size)
        for (card in cards) {
            if (!card.suspended && (candidates == null || card.id in candidates)) {
                filtered.add(card)
            }
        }
        filtered.sortWith(
            compareBy<Flashcard> {
                when (it.reviewState) {
                    "LEARNING", "RELEARNING" -> 0
                    "REVIEW" -> 1
                    else -> 2
                }
            }.thenBy { it.dueAt }.thenBy { it.id }
        )
        ordered = filtered
        cardCount = filtered.size
        cardArray = Array(cardCount) { filtered[it] }
        cardDeckIdx = IntArray(cardCount)
        cardDueAt = LongArray(cardCount)
        cardSubjectId = LongArray(cardCount)
        cardBuriedUntilDay = LongArray(cardCount)
        cardDayStartHour = IntArray(cardCount)
        cardStateCode = ByteArray(cardCount)
        val emptyChain = IntArray(0)
        cardAncestorIndices = Array(cardCount) { i ->
            val c = cardArray[i]
            val dId = c.deckId
            val dIdx = if (dId == null) UNASSIGNED_DECK_IDX else (deckIdToIdx[dId] ?: UNKNOWN_DECK_IDX)
            cardDeckIdx[i] = dIdx
            cardDueAt[i] = c.dueAt
            cardSubjectId[i] = c.subjectId ?: 0L
            cardBuriedUntilDay[i] = c.buriedUntilDay
            cardDayStartHour[i] = if (dIdx >= 0) deckDayStartHour[dIdx] else 4
            cardStateCode[i] = when (c.reviewState) {
                "NEW" -> STATE_NEW
                "REVIEW" -> STATE_REVIEW
                else -> STATE_LEARNING
            }
            if (dIdx >= 0) deckAncestorIndices[dIdx] else emptyChain
        }

        if (reviews.isNotEmpty()) {
            val boundsCalculated = BooleanArray(24)
            val boundsStart = LongArray(24)
            val boundsEnd = LongArray(24)
            fun ensureBounds(hour: Int) {
                if (!boundsCalculated[hour]) {
                    val (s, e) = ankiLogicalDayBounds(today, hour, zone)
                    boundsStart[hour] = s
                    boundsEnd[hour] = e
                    boundsCalculated[hour] = true
                }
            }
            ensureBounds(4)
            for (i in 0 until deckCount) ensureBounds(deckDayStartHour[i])

            var legacyCardDecks: HashMap<Long, Long?>? = null
            fun cardDeckForLegacy(cardId: Long): Long? {
                val map = legacyCardDecks ?: HashMap<Long, Long?>(cards.size * 2 + 1).also { m ->
                    for (c in cards) m[c.id] = c.deckId
                    legacyCardDecks = m
                }
                return map[cardId]
            }

            val previousDays = HashSet<Long>()
            val todayRows = ArrayList<CardReview>()
            for (row in reviews) {
                if (row.priorState == "CRAM") continue
                val deckId = if (row.priorState == null) cardDeckForLegacy(row.cardId) else row.deckAtReview
                val dIdx = if (deckId != null) (deckIdToIdx[deckId] ?: -1) else -1
                val hour = if (dIdx >= 0) deckDayStartHour[dIdx] else 4
                val revMs = row.reviewedAt
                if (revMs < boundsStart[hour]) {
                    previousDays.add(row.cardId)
                } else if (revMs < boundsEnd[hour]) {
                    todayRows.add(row)
                }
            }

            if (todayRows.isNotEmpty()) {
                todayRows.sortWith(compareBy<CardReview> { it.reviewedAt }.thenBy { it.id })
                val gradedToday = HashSet<Long>(todayRows.size * 2 + 1)
                val doneSets = Array<HashSet<Long>?>(deckCount) { null }
                for (row in todayRows) {
                    val firstToday = gradedToday.add(row.cardId)
                    val deckId = if (row.priorState == null) cardDeckForLegacy(row.cardId) else row.deckAtReview
                    val dIdx = if (deckId != null) (deckIdToIdx[deckId] ?: -1) else -1
                    if (dIdx < 0) continue
                    val state = row.priorState ?: if (row.cardId in previousDays) "REVIEW" else "NEW"
                    val chain = deckAncestorIndices[dIdx]
                    for (ancIdx in chain) {
                        val set = doneSets[ancIdx] ?: HashSet<Long>().also { doneSets[ancIdx] = it }
                        set.add(row.cardId)
                        if (firstToday) {
                            when (state) {
                                "NEW" -> introUsedArr[ancIdx]++
                                "REVIEW" -> reviewUsedArr[ancIdx]++
                            }
                        }
                    }
                }
                for (i in 0 until deckCount) {
                    doneCountArr[i] = doneSets[i]?.size ?: 0
                }
            }
        }
    }

    fun doneToday(deckId: Long): Int {
        val idx = deckIdToIdx[deckId] ?: return 0
        return doneCountArr[idx]
    }

    fun due(now: Long, deckId: Long = -1L, subjectId: Long = -1L): List<Flashcard> {
        val newCounts = introUsedArr.copyOf()
        val reviews = reviewUsedArr.copyOf()
        val unbounded = now >= UNBOUNDED_CUTOFF_MS
        val baseEpochDay: Long
        val localHour: Int
        if (unbounded || now <= 0L) {
            baseEpochDay = todayEpochDay
            localHour = 24
        } else {
            val zdt = Instant.ofEpochMilli(now).atZone(zone)
            baseEpochDay = zdt.toLocalDate().toEpochDay()
            localHour = zdt.hour
        }
        val selectedAll = deckId == -1L
        val selectedUnassigned = deckId == 0L
        val selectedIdx = if (!selectedAll && !selectedUnassigned) deckIdToIdx[deckId] else null

        val result = ArrayList<Flashcard>()
        for (i in 0 until cardCount) {
            if (!unbounded && cardDueAt[i] > now) continue
            val dIdx = cardDeckIdx[i]
            val matchesDeck = when {
                selectedAll -> true
                selectedUnassigned -> dIdx == UNASSIGNED_DECK_IDX
                selectedIdx != null -> dIdx >= 0 && tree.contains(deckId, cardArray[i].deckId)
                else -> cardArray[i].deckId == deckId
            }
            if (!matchesDeck) continue
            if (subjectId != -1L && cardSubjectId[i] != subjectId) continue
            val buriedUntil = cardBuriedUntilDay[i]
            if (buriedUntil > 0L) {
                val thresh = if (localHour < cardDayStartHour[i]) baseEpochDay - 1L else baseEpochDay
                if (buriedUntil >= thresh) continue
            }

            val state = cardStateCode[i]
            if (state == STATE_LEARNING) {
                result.add(cardArray[i])
                continue
            }
            val isNew = state == STATE_NEW
            val counts = if (isNew) newCounts else reviews
            val limits = if (isNew) deckNewLimit else deckReviewLimit
            val chain = cardAncestorIndices[i]
            var allowed = true
            for (k in chain.indices) {
                val ancIdx = chain[k]
                val limit = limits[ancIdx]
                if (limit != Int.MAX_VALUE && counts[ancIdx] >= limit) {
                    allowed = false
                    break
                }
            }
            if (!allowed) continue
            for (k in chain.indices) {
                counts[chain[k]]++
            }
            result.add(cardArray[i])
        }
        return result
    }

    private fun countFromDue(now: Long, deckId: Long, subjectId: Long): DeckCounters {
        var newCount = 0
        var learningCount = 0
        var reviewCount = 0
        for (card in due(now, deckId, subjectId)) {
            when (card.reviewState) {
                "NEW" -> newCount++
                "REVIEW" -> reviewCount++
                else -> learningCount++
            }
        }
        return DeckCounters(newCount = newCount, learningCount = learningCount, reviewCount = reviewCount)
    }

    private fun computeCounterTable(now: Long, subjectId: Long): CounterTable {
        if (hasCycle) {
            val byIdx = Array(deckCount) { countFromDue(now, deckArray[it].id, subjectId) }
            return CounterTable(
                allCounters = countFromDue(now, -1L, subjectId),
                unassignedCounters = countFromDue(now, 0L, subjectId),
                byDeckIdx = byIdx,
                unknownByDeckId = emptyMap()
            )
        }

        val unbounded = now >= UNBOUNDED_CUTOFF_MS
        val baseEpochDay: Long
        val localHour: Int
        if (unbounded || now <= 0L) {
            baseEpochDay = todayEpochDay
            localHour = 24
        } else {
            val zdt = Instant.ofEpochMilli(now).atZone(zone)
            baseEpochDay = zdt.toLocalDate().toEpochDay()
            localHour = zdt.hour
        }

        val directNew = IntArray(deckCount)
        val directLearning = IntArray(deckCount)
        val directReview = IntArray(deckCount)
        var unassignedNew = 0
        var unassignedLearning = 0
        var unassignedReview = 0
        var unknownNew = 0
        var unknownLearning = 0
        var unknownReview = 0
        var unknownBuckets: HashMap<Long, IntArray>? = null

        for (i in 0 until cardCount) {
            if (!unbounded && cardDueAt[i] > now) continue
            if (subjectId != -1L && cardSubjectId[i] != subjectId) continue
            val buriedUntil = cardBuriedUntilDay[i]
            if (buriedUntil > 0L) {
                val thresh = if (localHour < cardDayStartHour[i]) baseEpochDay - 1L else baseEpochDay
                if (buriedUntil >= thresh) continue
            }

            val dIdx = cardDeckIdx[i]
            val state = cardStateCode[i]
            if (dIdx >= 0) {
                when (state) {
                    STATE_NEW -> directNew[dIdx]++
                    STATE_REVIEW -> directReview[dIdx]++
                    else -> directLearning[dIdx]++
                }
            } else if (dIdx == UNASSIGNED_DECK_IDX) {
                when (state) {
                    STATE_NEW -> unassignedNew++
                    STATE_REVIEW -> unassignedReview++
                    else -> unassignedLearning++
                }
            } else {
                val rawDeckId = cardArray[i].deckId ?: continue
                val map = unknownBuckets ?: HashMap<Long, IntArray>().also { unknownBuckets = it }
                val triple = map.getOrPut(rawDeckId) { IntArray(3) }
                when (state) {
                    STATE_NEW -> {
                        triple[0]++
                        unknownNew++
                    }
                    STATE_REVIEW -> {
                        triple[2]++
                        unknownReview++
                    }
                    else -> {
                        triple[1]++
                        unknownLearning++
                    }
                }
            }
        }

        val subNew = IntArray(deckCount)
        val subLearning = IntArray(deckCount)
        val subReview = IntArray(deckCount)
        val selfRemNew = IntArray(deckCount) { idx ->
            val lim = deckNewLimit[idx]
            if (lim == Int.MAX_VALUE) Int.MAX_VALUE else (lim - introUsedArr[idx]).coerceAtLeast(0)
        }
        val selfRemReview = IntArray(deckCount) { idx ->
            val lim = deckReviewLimit[idx]
            if (lim == Int.MAX_VALUE) Int.MAX_VALUE else (lim - reviewUsedArr[idx]).coerceAtLeast(0)
        }

        for (k in deckCount - 1 downTo 0) {
            val idx = topDownOrder[k]
            var n = directNew[idx]
            var l = directLearning[idx]
            var r = directReview[idx]
            for (child in childrenIndices[idx]) {
                n += subNew[child]
                l += subLearning[child]
                r += subReview[child]
            }
            if (n > selfRemNew[idx]) n = selfRemNew[idx]
            if (r > selfRemReview[idx]) r = selfRemReview[idx]
            subNew[idx] = n
            subLearning[idx] = l
            subReview[idx] = r
        }

        val ancestorCapNew = IntArray(deckCount)
        val ancestorCapReview = IntArray(deckCount)
        val byDeckIdx = Array(deckCount) { ZERO_COUNTERS }
        for (k in 0 until deckCount) {
            val idx = topDownOrder[k]
            val p = parentIndices[idx]
            val capN = if (p < 0) Int.MAX_VALUE else minOf(ancestorCapNew[p], selfRemNew[p])
            val capR = if (p < 0) Int.MAX_VALUE else minOf(ancestorCapReview[p], selfRemReview[p])
            ancestorCapNew[idx] = capN
            ancestorCapReview[idx] = capR
            byDeckIdx[idx] = DeckCounters(
                newCount = minOf(subNew[idx], capN),
                learningCount = subLearning[idx],
                reviewCount = minOf(subReview[idx], capR)
            )
        }

        var allN = unassignedNew + unknownNew
        var allL = unassignedLearning + unknownLearning
        var allR = unassignedReview + unknownReview
        for (root in rootIndices) {
            allN += subNew[root]
            allL += subLearning[root]
            allR += subReview[root]
        }

        val unknownMap = unknownBuckets?.mapValues { (_, t) -> DeckCounters(t[0], t[1], t[2]) }.orEmpty()
        return CounterTable(
            allCounters = DeckCounters(allN, allL, allR),
            unassignedCounters = DeckCounters(unassignedNew, unassignedLearning, unassignedReview),
            byDeckIdx = byDeckIdx,
            unknownByDeckId = unknownMap
        )
    }

    /** Returns due New, Learning/Relearning, and Review counts under current ancestor quotas. */
    fun countersForDeck(now: Long, deckId: Long, subjectId: Long = -1L): DeckCounters {
        val table = synchronized(counterCacheLock) {
            var found: CounterTable? = null
            for (i in 0 until counterCache.size) {
                val entry = counterCache[i]
                if (entry.now == now && entry.subjectId == subjectId) {
                    found = entry.table
                    break
                }
            }
            found
        } ?: computeCounterTable(now, subjectId).also { computed ->
            synchronized(counterCacheLock) {
                if (counterCache.size >= 4) counterCache.removeAt(0)
                counterCache.add(CacheEntry(now, subjectId, computed))
            }
        }

        return when (deckId) {
            -1L -> table.allCounters
            0L -> table.unassignedCounters
            else -> {
                val idx = deckIdToIdx[deckId]
                if (idx != null) table.byDeckIdx[idx] else (table.unknownByDeckId[deckId] ?: ZERO_COUNTERS)
            }
        }
    }
}

/** Cards still scheduled before the next rollover cutoff, including intraday retries. */
internal fun remainingToday(index: DeckStudyIndex, until: Long, deckId: Long = -1L, subjectId: Long = -1L) =
    index.due(until, deckId, subjectId).size

/**
 * Pure-Kotlin Anki Browser Query DSL supporting:
 * - Free text & `"exact phrase"` search across front, back, and tags
 * - State predicates (`is:due`, `is:new`, `is:learn`, `is:review`, `is:suspended`, `is:buried`, `is:leech`)
 * - Flags (`flag:0`..`flag:7`)
 * - Hierarchy-aware `deck:Name::Sub` / prefix and `tag:parent/child`
 * - Numeric property comparisons (`prop:ivl>=30`, `prop:lapses>=5`, `prop:reps==0`, `prop:ease<2.0`)
 * - Boolean composition via `-negation`, implicit AND, explicit `OR`, and parentheses `(...)`
 */
object DeckQueue {
    /** Remove da fila ativa cartões irmãos (mesmo `authorNoteId`) quando `burySiblings` está ativo. */
    fun filterSiblingsAfterReview(
        reviewedCard: Flashcard,
        remainingQueue: List<Flashcard>,
        burySiblings: Boolean
    ): List<Flashcard> {
        val noteId = reviewedCard.authorNoteId ?: return remainingQueue
        if (!burySiblings) return remainingQueue
        return remainingQueue.filter { it.id == reviewedCard.id || it.authorNoteId != noteId }
    }

    /** Display Order v3: MIX, NEW_FIRST, REVIEW_FIRST */
    fun applyDisplayOrder(cards: List<Flashcard>, displayOrder: String): List<Flashcard> = when (displayOrder.uppercase()) {
        "NEW_FIRST" -> cards.sortedBy { if (it.reviewState == "NEW") 0 else if (it.reviewState == "LEARNING" || it.reviewState == "RELEARNING") 1 else 2 }
        "REVIEW_FIRST" -> cards.sortedBy { if (it.reviewState == "REVIEW") 0 else if (it.reviewState == "LEARNING" || it.reviewState == "RELEARNING") 1 else 2 }
        else -> cards
    }
}

object CardQueryEngine {

    private sealed interface Expr {
        fun matches(card: Flashcard, ctx: EvalContext): Boolean
    }

    private class EvalContext(
        val now: Long,
        val baseEpochDay: Long,
        val localHour: Int,
        val zone: ZoneId,
        val tree: DeckTree,
        val deckById: Map<Long, CardDeck>,
        val deckPathsById: Map<Long, String>
    ) {
        private var cachedCard: Flashcard? = null
        private var cachedTags: List<String> = emptyList()

        fun isBuriedNow(card: Flashcard): Boolean {
            if (card.buriedUntilDay <= 0L) return false
            val hour = card.deckId?.let { deckById[it]?.dayStartHour?.coerceIn(0, 23) } ?: 4
            val threshold = if (localHour < hour) baseEpochDay - 1L else baseEpochDay
            return card.buriedUntilDay >= threshold
        }

        fun tagsFor(card: Flashcard): List<String> {
            if (cachedCard === card) return cachedTags
            val parsed = splitTags(card.tags)
            cachedCard = card
            cachedTags = parsed
            return parsed
        }
    }

    private sealed interface Token {
        data object LParen : Token
        data object RParen : Token
        data object Or : Token
        data object Minus : Token
        data class Word(val raw: String, val quoted: Boolean) : Token
    }

    private fun tokenize(query: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = query.length
        while (i < n) {
            while (i < n && query[i].isWhitespace()) i++
            if (i >= n) break
            when (val ch = query[i]) {
                '(' -> {
                    tokens.add(Token.LParen)
                    i++
                }
                ')' -> {
                    tokens.add(Token.RParen)
                    i++
                }
                '-' -> {
                    if (i + 1 < n && !query[i + 1].isWhitespace() && query[i + 1] != ')') {
                        tokens.add(Token.Minus)
                        i++
                    } else {
                        i++
                    }
                }
                '"' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < n) {
                        val c = query[i]
                        if (c == '\\' && i + 1 < n && query[i + 1] == '"') {
                            sb.append('"')
                            i += 2
                        } else if (c == '"') {
                            i++
                            break
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    tokens.add(Token.Word(sb.toString(), quoted = true))
                }
                else -> {
                    val sb = StringBuilder()
                    var hadQuotes = false
                    while (i < n) {
                        val c = query[i]
                        if (c.isWhitespace() || c == '(' || c == ')') break
                        if (c == '"') {
                            hadQuotes = true
                            i++
                            while (i < n) {
                                val q = query[i]
                                if (q == '\\' && i + 1 < n && query[i + 1] == '"') {
                                    sb.append('"')
                                    i += 2
                                } else if (q == '"') {
                                    i++
                                    break
                                } else {
                                    sb.append(q)
                                    i++
                                }
                            }
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    val word = sb.toString()
                    if (!hadQuotes && word == "OR") tokens.add(Token.Or)
                    else tokens.add(Token.Word(word, quoted = hadQuotes))
                }
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun parse(): Expr? {
            if (tokens.isEmpty()) return null
            return parseOr()
        }

        private fun parseOr(): Expr {
            val branches = ArrayList<Expr>(2)
            parseAnd()?.let { branches.add(it) }
            while (pos < tokens.size && tokens[pos] == Token.Or) {
                pos++
                parseAnd()?.let { branches.add(it) }
            }
            return when (branches.size) {
                0 -> AlwaysTrue
                1 -> branches[0]
                else -> OrExpr(branches)
            }
        }

        private fun parseAnd(): Expr? {
            val parts = ArrayList<Expr>(4)
            while (pos < tokens.size) {
                val t = tokens[pos]
                if (t == Token.RParen || t == Token.Or) break
                val expr = parseUnary()
                if (expr != null) parts.add(expr)
            }
            return when (parts.size) {
                0 -> null
                1 -> parts[0]
                else -> AndExpr(parts)
            }
        }

        private fun parseUnary(): Expr? {
            if (pos >= tokens.size) return null
            if (tokens[pos] == Token.Minus) {
                pos++
                val inner = parseUnary() ?: return null
                return NotExpr(inner)
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Expr? {
            if (pos >= tokens.size) return null
            return when (val t = tokens[pos]) {
                Token.LParen -> {
                    pos++
                    val inner = parseOr()
                    if (pos < tokens.size && tokens[pos] == Token.RParen) pos++
                    inner
                }
                is Token.Word -> {
                    pos++
                    compileTerm(t.raw, t.quoted)
                }
                Token.RParen, Token.Or, Token.Minus -> {
                    pos++
                    null
                }
            }
        }
    }

    private data object AlwaysTrue : Expr {
        override fun matches(card: Flashcard, ctx: EvalContext) = true
    }

    private class NotExpr(private val inner: Expr) : Expr {
        override fun matches(card: Flashcard, ctx: EvalContext) = !inner.matches(card, ctx)
    }

    private class AndExpr(private val children: List<Expr>) : Expr {
        override fun matches(card: Flashcard, ctx: EvalContext) = children.all { it.matches(card, ctx) }
    }

    private class OrExpr(private val children: List<Expr>) : Expr {
        override fun matches(card: Flashcard, ctx: EvalContext) = children.any { it.matches(card, ctx) }
    }

    private class TextExpr(private val needle: String) : Expr {
        override fun matches(card: Flashcard, ctx: EvalContext): Boolean {
            if (needle.isEmpty()) return true
            return card.front.contains(needle, ignoreCase = true) ||
                card.back.contains(needle, ignoreCase = true) ||
                card.tags.contains(needle, ignoreCase = true)
        }
    }

    private class IsExpr(private val kind: String) : Expr {
        override fun matches(card: Flashcard, ctx: EvalContext): Boolean {
            val state = card.reviewState
            return when (kind) {
                "due" -> !card.suspended && !ctx.isBuriedNow(card) && card.dueAt <= ctx.now && state != "NEW"
                "new" -> state == "NEW"
                "learn", "learning" -> state == "LEARNING" || state == "RELEARNING"
                "relearn", "relearning" -> state == "RELEARNING"
                "review" -> state == "REVIEW"
                "suspended", "suspend" -> card.suspended
                "buried", "bury" -> ctx.isBuriedNow(card)
                "leech" -> {
                    val tags = ctx.tagsFor(card)
                    val threshold = card.deckId?.let { ctx.deckById[it]?.leechThreshold?.coerceAtLeast(1) } ?: 8
                    hasTagExactOrSub(tags, "sanguessuga", "sanguessuga/", "sanguessuga::") ||
                        hasTagExactOrSub(tags, "leech", "leech/", "leech::") ||
                        card.lapses >= threshold
                }
                else -> false
            }
        }
    }

    private class FlagExpr(private val flag: Int) : Expr {
        override fun matches(card: Flashcard, ctx: EvalContext) = card.flag == flag
    }

    private class DeckExpr(private val patternRaw: String) : Expr {
        private val normalized = patternRaw.trim().replace(" › ", "::").trim(':')
        private val regex = if ('*' in normalized) wildcardToRegex(normalized) else null
        private val matchNone = normalized.equals("none", ignoreCase = true)
        private val hasColons = "::" in normalized
        private var cachedCtx: EvalContext? = null
        private var cachedMatchingIds: Set<Long> = emptySet()

        private fun matchingIdsFor(ctx: EvalContext): Set<Long> {
            if (cachedCtx === ctx) return cachedMatchingIds
            val ids = HashSet<Long>(ctx.deckPathsById.size + 1)
            val subPrefix = "$normalized::"
            for ((id, fullPath) in ctx.deckPathsById) {
                val ok = when {
                    regex != null -> regex.matches(fullPath)
                    fullPath.equals(normalized, ignoreCase = true) || fullPath.startsWith(subPrefix, ignoreCase = true) -> true
                    !hasColons -> ctx.tree.ancestors(id).any { it.name.equals(normalized, ignoreCase = true) }
                    else -> false
                }
                if (ok) ids.add(id)
            }
            cachedCtx = ctx
            cachedMatchingIds = ids
            return ids
        }

        override fun matches(card: Flashcard, ctx: EvalContext): Boolean {
            if (normalized.isEmpty()) return true
            val id = card.deckId ?: return matchNone
            return id in matchingIdsFor(ctx)
        }
    }

    private class TagExpr(private val patternRaw: String) : Expr {
        private val normalized = patternRaw.trim()
        private val isNone = normalized.equals("none", ignoreCase = true)
        private val slashPrefix = "$normalized/"
        private val colonPrefix = "$normalized::"
        private val regex = if ('*' in normalized) wildcardToRegex(normalized) else null

        override fun matches(card: Flashcard, ctx: EvalContext): Boolean {
            if (isNone) {
                return card.tags.isBlank()
            }
            if (normalized.isEmpty()) return card.tags.isNotBlank()
            val tags = ctx.tagsFor(card)
            if (tags.isEmpty()) return false
            if (regex != null) return tags.any { regex.matches(it) }
            return tags.any { tag ->
                tag.equals(normalized, ignoreCase = true) ||
                    tag.startsWith(slashPrefix, ignoreCase = true) ||
                    tag.startsWith(colonPrefix, ignoreCase = true)
            }
        }
    }

    private class PropExpr(
        private val field: String,
        private val op: String,
        private val target: Double
    ) : Expr {
        override fun matches(card: Flashcard, ctx: EvalContext): Boolean {
            val actual = when (field) {
                "ivl", "interval" -> card.intervalDays.toDouble()
                "lapses" -> card.lapses.toDouble()
                "reps" -> card.repetitions.toDouble()
                "ease", "ef" -> card.easeFactor
                "s", "stability" -> card.fsrsStability
                "d", "difficulty" -> card.fsrsDifficulty
                "due" -> {
                    val hour = card.deckId?.let { ctx.deckById[it]?.dayStartHour?.coerceIn(0, 23) } ?: 4
                    val cardLogicalEpoch = ankiLogicalDay(card.dueAt, hour, ctx.zone).toEpochDay()
                    val todayLogicalEpoch = if (ctx.localHour < hour) ctx.baseEpochDay - 1L else ctx.baseEpochDay
                    (cardLogicalEpoch - todayLogicalEpoch).toDouble()
                }
                else -> return false
            }
            return when (op) {
                ">=" -> actual >= target - 1e-9
                "<=" -> actual <= target + 1e-9
                ">" -> actual > target + 1e-9
                "<" -> actual < target - 1e-9
                "==", "=" -> abs(actual - target) < 1e-6
                "!=" -> abs(actual - target) >= 1e-6
                else -> false
            }
        }
    }

    private val propRegex = Regex("""^([a-zA-Z]+)(>=|<=|!=|==|=|>|<)(-?\d+(?:\.\d+)?)$""")

    private fun compileTerm(raw: String, quoted: Boolean): Expr {
        if (quoted && ':' !in raw) return TextExpr(raw)
        val colonIdx = raw.indexOf(':')
        if (colonIdx > 0 && colonIdx < raw.length - 1) {
            val key = raw.substring(0, colonIdx).lowercase()
            val value = raw.substring(colonIdx + 1)
            when (key) {
                "is" -> return IsExpr(value.lowercase())
                "flag" -> {
                    val flagNum = value.toIntOrNull()
                    if (flagNum != null) return FlagExpr(flagNum)
                }
                "deck" -> return DeckExpr(value)
                "tag" -> return TagExpr(value)
                "prop" -> {
                    val m = propRegex.matchEntire(value.trim())
                    if (m != null) {
                        val field = m.groupValues[1].lowercase()
                        val op = m.groupValues[2]
                        val num = m.groupValues[3].toDoubleOrNull()
                        if (num != null) return PropExpr(field, op, num)
                    }
                }
            }
        }
        return TextExpr(raw)
    }

    private fun splitTags(raw: String): List<String> {
        val len = raw.length
        if (len == 0) return emptyList()
        var i = 0
        while (i < len && raw[i].isWhitespace()) i++
        if (i >= len) return emptyList()
        val result = ArrayList<String>(4)
        while (i < len) {
            val start = i
            while (i < len && !raw[i].isWhitespace()) i++
            result.add(raw.substring(start, i))
            while (i < len && raw[i].isWhitespace()) i++
        }
        return result
    }

    private fun hasTagExactOrSub(tags: List<String>, target: String, slashPrefix: String, colonPrefix: String): Boolean {
        for (i in 0 until tags.size) {
            val it = tags[i]
            if (it.equals(target, ignoreCase = true) ||
                it.startsWith(slashPrefix, ignoreCase = true) ||
                it.startsWith(colonPrefix, ignoreCase = true)
            ) return true
        }
        return false
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (ch in pattern) {
            if (ch == '*') sb.append(".*")
            else sb.append(Regex.escape(ch.toString()))
        }
        sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /** Filters [cards] using the Anki Browser query string [query]. Blank queries return [cards]. */
    fun filter(
        cards: List<Flashcard>,
        query: String,
        decks: List<CardDeck>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Flashcard> {
        if (query.isBlank() || cards.isEmpty()) return cards
        val expr = Parser(tokenize(query)).parse() ?: return cards
        val tree = DeckTree(decks)
        val deckById = decks.associateBy { it.id }
        val deckPaths = decks.associate { d ->
            d.id to tree.ancestors(d.id).asReversed().joinToString("::") { it.name }
        }
        val baseEpochDay: Long
        val localHour: Int
        if (now >= UNBOUNDED_CUTOFF_MS || now <= 0L) {
            baseEpochDay = LocalDate.now(zone).toEpochDay()
            localHour = 24
        } else {
            val zdt = Instant.ofEpochMilli(now).atZone(zone)
            baseEpochDay = zdt.toLocalDate().toEpochDay()
            localHour = zdt.hour
        }
        val ctx = EvalContext(
            now = now,
            baseEpochDay = baseEpochDay,
            localHour = localHour,
            zone = zone,
            tree = tree,
            deckById = deckById,
            deckPathsById = deckPaths
        )
        return cards.filter { expr.matches(it, ctx) }
    }
}
