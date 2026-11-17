package com.studydock.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val MEDIA_DAY_MS = 86_400_000L

// =====================================================================
// Destaques estruturados de livros (BookHighlight, BookHighlights)
// =====================================================================

/** Structured book highlight / quote persisted in [Book.highlightsJson]. */
data class BookHighlight(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val page: Int? = null,
    val chapter: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * JSON serialization and legacy-quote fallback helpers for [Book.highlightsJson]
 * and [Book.quotes].
 */
object BookHighlights {

    private val legacyPageRegex = Regex(
        pattern = """^(.*?)\s*\((?:p\.?|pág\.?)\s*(\d+)\)\s*$""",
        option = RegexOption.IGNORE_CASE
    )

    /** Parses a raw `highlightsJson` string into [BookHighlight] entries. */
    fun parse(json: String?): List<BookHighlight> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val text = obj.optString("text").trim()
                    if (text.isEmpty()) continue
                    val id = obj.optString("id").trim().ifEmpty { "hl_$index" }
                    val page = if (obj.has("page") && !obj.isNull("page")) {
                        obj.optInt("page", -1).takeIf { it >= 0 }
                    } else {
                        null
                    }
                    val chapter = obj.optString("chapter").trim()
                    val note = obj.optString("note").trim()
                    val createdAt = obj.optLong("createdAt", 0L).coerceAtLeast(0L)
                    add(
                        BookHighlight(
                            id = id,
                            text = text,
                            page = page,
                            chapter = chapter,
                            note = note,
                            createdAt = createdAt
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Parses highlights from [book], reading [Book.highlightsJson] first and falling back
     * transparently to [Book.quotes] (extracting `(p. X)` when present).
     */
    fun parse(book: Book): List<BookHighlight> {
        if (book.highlightsJson.isNotBlank()) {
            val structured = parse(book.highlightsJson)
            if (structured.isNotEmpty() || book.quotes.isBlank()) return structured
        }
        return parseLegacyQuotes(book.quotes, book.updatedAt)
    }

    /** Parses legacy newline-separated [Book.quotes] lines into [BookHighlight] entries. */
    fun parseLegacyQuotes(quotes: String?, fallbackCreatedAt: Long = 0L): List<BookHighlight> {
        if (quotes.isNullOrBlank()) return emptyList()
        return quotes.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexed { index, rawLine ->
                val match = legacyPageRegex.matchEntire(rawLine)
                val extractedText = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val extractedPage = match?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 }
                val useMatch = match != null && extractedText.isNotEmpty()
                BookHighlight(
                    id = "legacy_$index",
                    text = if (useMatch) extractedText else rawLine,
                    page = if (useMatch) extractedPage else null,
                    chapter = "",
                    note = "",
                    createdAt = fallbackCreatedAt.coerceAtLeast(0L)
                )
            }
            .toList()
    }

    /** Encodes [highlights] to a JSON string (or empty string when none remain). */
    fun serialize(highlights: List<BookHighlight>): String {
        val valid = highlights.filter { it.text.isNotBlank() }
        if (valid.isEmpty()) return ""
        val array = JSONArray()
        valid.forEachIndexed { index, item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id.trim().ifEmpty { "hl_$index" })
                    put("text", item.text.trim())
                    if (item.page != null && item.page >= 0) {
                        put("page", item.page)
                    }
                    put("chapter", item.chapter.trim())
                    put("note", item.note.trim())
                    put("createdAt", item.createdAt.coerceAtLeast(0L))
                }
            )
        }
        return array.toString()
    }

    /** Formats [highlights] into legacy newline-separated [Book.quotes] text for backward compatibility. */
    fun toLegacyQuotes(highlights: List<BookHighlight>): String =
        highlights.asSequence()
            .filter { it.text.isNotBlank() }
            .map { item ->
                val cleanText = item.text.trim().replace(Regex("\\s*\\R\\s*"), " ")
                val page = item.page
                if (page != null && page > 0) "$cleanText (p. $page)" else cleanText
            }
            .joinToString("\n")

    /** Appends [highlight] to [highlights], normalizing blank IDs and whitespace. */
    fun add(highlights: List<BookHighlight>, highlight: BookHighlight): List<BookHighlight> {
        val cleanText = highlight.text.trim()
        if (cleanText.isEmpty()) return highlights
        val normalized = highlight.copy(
            id = highlight.id.trim().ifEmpty { UUID.randomUUID().toString() },
            text = cleanText,
            page = highlight.page?.takeIf { it >= 0 },
            chapter = highlight.chapter.trim(),
            note = highlight.note.trim(),
            createdAt = highlight.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        return highlights + normalized
    }

    /** Appends [highlight] to the highlights parsed from [book]. */
    fun add(book: Book, highlight: BookHighlight): List<BookHighlight> =
        add(parse(book), highlight)

    /** Replaces the highlight matching [highlight.id] inside [highlights]. */
    fun update(highlights: List<BookHighlight>, highlight: BookHighlight): List<BookHighlight> {
        val cleanText = highlight.text.trim()
        if (cleanText.isEmpty()) return remove(highlights, highlight.id)
        val normalized = highlight.copy(
            text = cleanText,
            page = highlight.page?.takeIf { it >= 0 },
            chapter = highlight.chapter.trim(),
            note = highlight.note.trim()
        )
        var replaced = false
        val updated = highlights.map { existing ->
            if (existing.id == normalized.id) {
                replaced = true
                normalized
            } else {
                existing
            }
        }
        return if (replaced) updated else updated + normalized
    }

    /** Replaces the highlight matching [highlight.id] inside [book]. */
    fun update(book: Book, highlight: BookHighlight): List<BookHighlight> =
        update(parse(book), highlight)

    /** Removes the highlight matching [highlightId] from [highlights]. */
    fun remove(highlights: List<BookHighlight>, highlightId: String): List<BookHighlight> =
        highlights.filterNot { it.id == highlightId }

    /** Removes the highlight matching [highlightId] from [book]. */
    fun remove(book: Book, highlightId: String): List<BookHighlight> =
        remove(parse(book), highlightId)

    /** Returns the number of parsed highlights in [book]. */
    fun count(book: Book): Int = parse(book).size

    /** Returns `(highlightsJson, legacyQuotes)` synchronized for DB persistence. */
    fun encodeForBook(highlights: List<BookHighlight>): Pair<String, String> =
        serialize(highlights) to toLegacyQuotes(highlights)

    /** Returns a copy of [book] with both [Book.highlightsJson] and [Book.quotes] updated. */
    fun withHighlights(
        book: Book,
        highlights: List<BookHighlight>,
        now: Long = System.currentTimeMillis()
    ): Book {
        val (json, legacy) = encodeForBook(highlights)
        return book.copy(highlightsJson = json, quotes = legacy, updatedAt = now)
    }
}

// =====================================================================
// Estatísticas e ritmo de leitura (BookReadingEngine)
// =====================================================================

/** Computed reading pace, progress, goal pace, and activity metrics for a single [Book]. */
data class BookReadingStats(
    val bookId: Long,
    val progressPercent: Int,
    val progressFraction: Float,
    val remainingPages: Int,
    val pagesPerHour: Int,
    val isBookSpecificPace: Boolean,
    val totalMinutes: Int,
    val estimatedMinutesLeft: Int,
    val pagesLast7Days: Int,
    val pagesToday: Int,
    val requiredPagesPerDay: Double?,
    val daysRemainingToTarget: Int?,
    val highlightCount: Int,
    val lastReadAt: Long?
)

/** Aggregated reading metrics across the entire book shelf. */
data class ReadingShelfSummary(
    val totalBooks: Int,
    val readingCount: Int,
    val wishlistCount: Int,
    val doneCount: Int,
    val totalPagesRead: Int,
    val averagePagesPerHour: Int,
    val totalReadingMinutes: Int,
    val readingStreakDays: Int,
    val bestReadingStreakDays: Int,
    val pagesToday: Int,
    val pagesThisWeek: Int,
    val totalHighlights: Int
)

/** Computes per-book and shelf-wide reading analytics from [Book], [ReadingLog], and [ActivityTimer]. */
object BookReadingEngine {

    const val DEFAULT_PAGES_PER_HOUR = 24
    private const val MIN_GLOBAL_HOURS_FOR_PACE = 0.15
    private const val MIN_BOOK_MS_FOR_BLEND = 180_000L
    private const val FULL_BOOK_MS_FOR_PACE = 900_000L

    private fun isReadingTimer(timer: ActivityTimer): Boolean =
        timer.mode.equals("Leitura", ignoreCase = true) ||
            timer.mode.equals("READING", ignoreCase = true)

    /** Returns [readingLogs] associated with [book] by `bookId` or normalized legacy `title`. */
    fun logsForBook(book: Book, readingLogs: List<ReadingLog>): List<ReadingLog> {
        val normalizedBookTitle = book.title.trim()
        return readingLogs.filter { log ->
            if (log.bookId != null) {
                book.id > 0L && log.bookId == book.id
            } else {
                normalizedBookTitle.isNotEmpty() &&
                    log.title.trim().equals(normalizedBookTitle, ignoreCase = true)
            }
        }
    }

    /** Returns reading [activityTimers] associated with [book] by `bookId` or normalized `title`. */
    fun timersForBook(book: Book, activityTimers: List<ActivityTimer>): List<ActivityTimer> {
        val normalizedBookTitle = book.title.trim()
        return activityTimers.filter { timer ->
            if (!isReadingTimer(timer)) return@filter false
            if (timer.bookId != null) {
                book.id > 0L && timer.bookId == book.id
            } else {
                normalizedBookTitle.isNotEmpty() &&
                    timer.title.trim().equals(normalizedBookTitle, ignoreCase = true)
            }
        }
    }

    /** Computes [BookReadingStats] for every book in [books] in O(B + L + T) using pre-indexed logs and timers. */
    fun computeAllBookStats(
        books: List<Book>,
        readingLogs: List<ReadingLog>,
        activityTimers: List<ActivityTimer>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Map<Long, BookReadingStats> {
        if (books.isEmpty()) return emptyMap()
        val allReadingTimers = activityTimers.filter(::isReadingTimer)
        val fallbackPace = computeFallbackPace(readingLogs, allReadingTimers, now)

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()

        val logsById = HashMap<Long, MutableList<ReadingLog>>()
        val legacyLogsByTitle = HashMap<String, MutableList<ReadingLog>>()
        for (log in readingLogs) {
            val bid = log.bookId
            if (bid != null) logsById.getOrPut(bid) { mutableListOf() }.add(log)
            else {
                val key = log.title.trim().lowercase(Locale.ROOT)
                if (key.isNotEmpty()) legacyLogsByTitle.getOrPut(key) { mutableListOf() }.add(log)
            }
        }

        val timersById = HashMap<Long, MutableList<ActivityTimer>>()
        val legacyTimersByTitle = HashMap<String, MutableList<ActivityTimer>>()
        for (timer in allReadingTimers) {
            val bid = timer.bookId
            if (bid != null) timersById.getOrPut(bid) { mutableListOf() }.add(timer)
            else {
                val key = timer.title.trim().lowercase(Locale.ROOT)
                if (key.isNotEmpty()) legacyTimersByTitle.getOrPut(key) { mutableListOf() }.add(timer)
            }
        }

        val result = HashMap<Long, BookReadingStats>(books.size * 2)
        for (book in books) {
            val keyTitle = book.title.trim().lowercase(Locale.ROOT)
            val bookLogs = buildList {
                if (book.id > 0L) logsById[book.id]?.let(::addAll)
                if (keyTitle.isNotEmpty()) legacyLogsByTitle[keyTitle]?.let(::addAll)
            }
            val bookTimers = buildList {
                if (book.id > 0L) timersById[book.id]?.let(::addAll)
                if (keyTitle.isNotEmpty()) legacyTimersByTitle[keyTitle]?.let(::addAll)
            }
            result[book.id] = computeBookStatsWithContext(
                book, bookLogs, bookTimers, fallbackPace, now, todayStart, tomorrowStart, weekStart
            )
        }
        return result
    }

    /** Computes [BookReadingStats] for [book] at [now] in [zone]. */
    fun computeBookStats(
        book: Book,
        readingLogs: List<ReadingLog>,
        activityTimers: List<ActivityTimer>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): BookReadingStats {
        val bookLogs = logsForBook(book, readingLogs)
        val bookTimers = timersForBook(book, activityTimers)
        val allReadingTimers = activityTimers.filter(::isReadingTimer)
        val fallbackPace = computeFallbackPace(readingLogs, allReadingTimers, now)

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()

        return computeBookStatsWithContext(
            book, bookLogs, bookTimers, fallbackPace, now, todayStart, tomorrowStart, weekStart
        )
    }

    private fun computeFallbackPace(
        readingLogs: List<ReadingLog>,
        allReadingTimers: List<ActivityTimer>,
        now: Long
    ): Int {
        val globalMs = computeReadingDurationMs(readingLogs, allReadingTimers, now)
        val globalHours = globalMs / 3_600_000.0
        val globalLoggedPages = readingLogs.asSequence().filter { it.pages > 0 }.sumOf { it.pages }
        return if (globalHours >= MIN_GLOBAL_HOURS_FOR_PACE && globalLoggedPages > 0) {
            (globalLoggedPages / globalHours).roundToInt().coerceIn(5, 120)
        } else {
            DEFAULT_PAGES_PER_HOUR
        }
    }

    private fun computeBookStatsWithContext(
        book: Book,
        bookLogs: List<ReadingLog>,
        bookTimers: List<ActivityTimer>,
        fallbackPace: Int,
        now: Long,
        todayStart: Long,
        tomorrowStart: Long,
        weekStart: Long
    ): BookReadingStats {

        val totalPages = book.totalPages?.takeIf { it > 0 }
        val rawCurrent = book.currentPage.coerceAtLeast(0)
        val currentPage = totalPages?.let { rawCurrent.coerceAtMost(it) } ?: rawCurrent

        val progressFraction = when {
            book.status == "DONE" && totalPages != null -> 1f
            totalPages != null -> (currentPage.toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
        val progressPercent = (progressFraction * 100f).roundToInt().coerceIn(0, 100)
        val remainingPages = if (totalPages != null && book.status != "DONE") {
            (totalPages - currentPage).coerceAtLeast(0)
        } else {
            0
        }

        val bookMs = computeReadingDurationMs(bookLogs, bookTimers, now)
        val bookHours = bookMs / 3_600_000.0
        val bookLoggedPages = bookLogs.filter { it.pages > 0 }.sumOf { it.pages }
        val bookPagesForPace = when {
            bookLoggedPages > 0 -> bookLoggedPages
            book.status != "WISHLIST" -> currentPage
            else -> 0
        }

        val isBookSpecificPace = bookMs >= MIN_BOOK_MS_FOR_BLEND && bookPagesForPace > 0
        val pagesPerHour = if (isBookSpecificPace) {
            val rawBookPace = (bookPagesForPace / bookHours).roundToInt().coerceIn(5, 120)
            if (bookMs >= FULL_BOOK_MS_FOR_PACE) {
                rawBookPace
            } else {
                val weight = ((bookMs - MIN_BOOK_MS_FOR_BLEND).toDouble() /
                    (FULL_BOOK_MS_FOR_PACE - MIN_BOOK_MS_FOR_BLEND).toDouble()).coerceIn(0.0, 1.0)
                val blended = fallbackPace * (1.0 - weight) + rawBookPace * weight
                blended.roundToInt().coerceIn(5, 120)
            }
        } else {
            fallbackPace
        }

        val totalMinutes = (bookMs / 60_000L).toInt().coerceAtLeast(0)
        val estimatedMinutesLeft = if (remainingPages > 0 && pagesPerHour > 0) {
            ((remainingPages * 60.0) / pagesPerHour).roundToInt().coerceAtLeast(1)
        } else {
            0
        }

        val pagesToday = bookLogs
            .filter { it.pages > 0 && it.loggedAt in todayStart until tomorrowStart }
            .sumOf { it.pages }
        val pagesLast7Days = bookLogs
            .filter { it.pages > 0 && it.loggedAt in weekStart until tomorrowStart }
            .sumOf { it.pages }

        val targetAt = book.targetFinishAt
        val (requiredPagesPerDay, daysRemainingToTarget) = if (
            targetAt != null &&
            book.status != "DONE" &&
            remainingPages > 0
        ) {
            val daysLeft = if (targetAt > now) {
                ceil((targetAt - now) / MEDIA_DAY_MS.toDouble()).toInt().coerceAtLeast(1)
            } else {
                0
            }
            val divisor = daysLeft.coerceAtLeast(1)
            (remainingPages.toDouble() / divisor.toDouble()) to daysLeft
        } else {
            null to null
        }

        val lastLogAt = bookLogs.maxOfOrNull { it.loggedAt }
        val lastTimerAt = bookTimers.maxOfOrNull { it.endedAt ?: it.runningSince ?: it.startedAt }
        val bookTouchedAt = book.lastReadAt.takeIf { it > 0L }
        val lastReadAt = listOfNotNull(bookTouchedAt, lastLogAt, lastTimerAt).maxOrNull()

        return BookReadingStats(
            bookId = book.id,
            progressPercent = progressPercent,
            progressFraction = progressFraction,
            remainingPages = remainingPages,
            pagesPerHour = pagesPerHour,
            isBookSpecificPace = isBookSpecificPace,
            totalMinutes = totalMinutes,
            estimatedMinutesLeft = estimatedMinutesLeft,
            pagesLast7Days = pagesLast7Days,
            pagesToday = pagesToday,
            requiredPagesPerDay = requiredPagesPerDay,
            daysRemainingToTarget = daysRemainingToTarget,
            highlightCount = BookHighlights.count(book),
            lastReadAt = lastReadAt
        )
    }

    /** Computes [ReadingShelfSummary] across [books], [readingLogs], and [activityTimers] at [now] in [zone]. */
    fun computeShelfSummary(
        books: List<Book>,
        readingLogs: List<ReadingLog>,
        activityTimers: List<ActivityTimer>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): ReadingShelfSummary {
        val totalPagesRead = books.filter { it.status != "WISHLIST" }.sumOf { book ->
            val pages = book.currentPage.coerceAtLeast(0)
            book.totalPages?.takeIf { it > 0 }?.let { pages.coerceAtMost(it) } ?: pages
        }
        val allReadingTimers = activityTimers.filter(::isReadingTimer)
        val totalMs = computeReadingDurationMs(readingLogs, allReadingTimers, now)
        val totalReadingMinutes = (totalMs / 60_000L).toInt().coerceAtLeast(0)
        val totalHours = totalMs / 3_600_000.0

        val loggedPages = readingLogs.filter { it.pages > 0 }.sumOf { it.pages }
        val pacePages = if (loggedPages > 0) loggedPages else totalPagesRead
        val averagePagesPerHour = if (totalHours >= MIN_GLOBAL_HOURS_FOR_PACE && pacePages > 0) {
            (pacePages / totalHours).roundToInt().coerceIn(5, 120)
        } else {
            DEFAULT_PAGES_PER_HOUR
        }

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()

        val pagesToday = readingLogs
            .filter { it.pages > 0 && it.loggedAt in todayStart until tomorrowStart }
            .sumOf { it.pages }
        val pagesThisWeek = readingLogs
            .filter { it.pages > 0 && it.loggedAt in weekStart until tomorrowStart }
            .sumOf { it.pages }

        val activeDays = buildSet {
            readingLogs.forEach { log ->
                if (log.pages > 0 || log.durationMinutes > 0) {
                    val day = Instant.ofEpochMilli(log.loggedAt).atZone(zone).toLocalDate()
                    if (!day.isAfter(today)) add(day)
                }
            }
            allReadingTimers.forEach { timer ->
                if (timer.duration(now) >= 60_000L) {
                    val day = Instant.ofEpochMilli(timer.startedAt).atZone(zone).toLocalDate()
                    if (!day.isAfter(today)) add(day)
                }
            }
        }
        val (currentStreak, bestStreak) = computeReadingStreaks(activeDays, today)

        return ReadingShelfSummary(
            totalBooks = books.size,
            readingCount = books.count { it.status == "READING" },
            wishlistCount = books.count { it.status == "WISHLIST" },
            doneCount = books.count { it.status == "DONE" },
            totalPagesRead = totalPagesRead,
            averagePagesPerHour = averagePagesPerHour,
            totalReadingMinutes = totalReadingMinutes,
            readingStreakDays = currentStreak,
            bestReadingStreakDays = bestStreak,
            pagesToday = pagesToday,
            pagesThisWeek = pagesThisWeek,
            totalHighlights = books.sumOf { BookHighlights.count(it) }
        )
    }

    private fun computeReadingDurationMs(
        logs: List<ReadingLog>,
        timers: List<ActivityTimer>,
        now: Long
    ): Long {
        val timerMs = timers.sumOf { it.duration(now).coerceAtLeast(0L) }
        if (timers.isEmpty()) {
            return logs.asSequence().filter { it.durationMinutes > 0 }.sumOf { it.durationMinutes.toLong() } * 60_000L
        }
        val timersById = HashMap<Long, MutableList<ActivityTimer>>()
        val timersByTitle = HashMap<String, MutableList<ActivityTimer>>()
        for (timer in timers) {
            if (timer.duration(now) < 60_000L) continue
            val bid = timer.bookId
            if (bid != null) timersById.getOrPut(bid) { mutableListOf() }.add(timer)
            val key = timer.title.trim().lowercase(Locale.ROOT)
            if (key.isNotEmpty()) timersByTitle.getOrPut(key) { mutableListOf() }.add(timer)
        }
        val unlinkedLogMinutes = logs.asSequence()
            .filter { it.durationMinutes > 0 }
            .filter { log ->
                val candidates = if (log.bookId != null) {
                    timersById[log.bookId].orEmpty()
                } else {
                    timersByTitle[log.title.trim().lowercase(Locale.ROOT)].orEmpty()
                }
                candidates.none { timer ->
                    val endWindow = (timer.endedAt ?: now) + 5_000L
                    log.loggedAt >= timer.startedAt - 5_000L && log.loggedAt <= endWindow
                }
            }
            .sumOf { it.durationMinutes.toLong() }
        return timerMs + unlinkedLogMinutes * 60_000L
    }

    private fun computeReadingStreaks(activeDays: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
        if (activeDays.isEmpty()) return 0 to 0

        var currentStreak = 0
        var checkDay = if (today in activeDays) today else today.minusDays(1)
        while (checkDay in activeDays) {
            currentStreak++
            checkDay = checkDay.minusDays(1)
        }

        var bestStreak = 0
        var running = 0
        var previous: LocalDate? = null
        for (day in activeDays.sorted()) {
            running = if (previous == null || day == previous.plusDays(1)) running + 1 else 1
            if (running > bestStreak) bestStreak = running
            previous = day
        }

        return currentStreak to maxOf(currentStreak, bestStreak)
    }
}

// =====================================================================
// Áudio, Mixes & Playlists (FocusAudioStore)
// =====================================================================

/** Local audio track persisted inside a [MusicPlaylist]. */
data class AudioTrackItem(
    val uri: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L
)

/** User playlist persisted in `SharedPreferences("studydock_audio")`. */
data class MusicPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tracks: List<AudioTrackItem> = emptyList(),
    val loopMode: String = FocusAudioStore.REPEAT_ALL,
    val shuffle: Boolean = false
)

/** Saved ambient sound mix with per-channel volumes and master gain. */
data class CustomSoundMix(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val volumes: Map<String, Float> = emptyMap(),
    val masterVolume: Float = 0.85f
)

/** Complete synchronized playback & preference state stored in `SharedPreferences("studydock_audio")`. */
data class AudioPlaybackSnapshot(
    val ambientPlaying: Boolean = false,
    val activeSounds: Set<String> = setOf("brown"),
    val soundVolumes: Map<String, Float> = emptyMap(),
    val ambientMasterVolume: Float = 0.85f,
    val musicPlaying: Boolean = false,
    val activePlaylistId: String = FocusAudioStore.DEFAULT_PLAYLIST_ID,
    val currentTrackIndex: Int = 0,
    val currentTrackTitle: String = "",
    val currentTrackArtist: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: String = FocusAudioStore.REPEAT_ALL,
    val sleepTimerEndAt: Long? = null,
    val autoPlayOnStudy: Boolean = false,
    val autoPlayOnReading: Boolean = false,
    val autoPauseOnBreak: Boolean = true,
    val selectedStudyTarget: String = "",
    val selectedReadingTarget: String = ""
)

/**
 * Thread-safe reader/writer for `SharedPreferences("studydock_audio")`, managing playback snapshots,
 * custom ambient mixes, multi-playlists, and automatic migration from legacy `"music_playlist"`.
 */
object FocusAudioStore {

    const val PREFS_NAME = "studydock_audio"
    const val DEFAULT_PLAYLIST_ID = "favorites"
    const val DEFAULT_PLAYLIST_NAME = "Favoritas"

    const val REPEAT_OFF = "OFF"
    const val REPEAT_ALL = "ALL"
    const val REPEAT_ONE = "ONE"

    private const val KEY_ACTIVE_SOUNDS = "active_sounds"
    private const val KEY_VOLUME_PREFIX = "volume_"
    private const val KEY_AMBIENT_PLAYING = "ambient_playing"
    private const val KEY_AMBIENT_MASTER_VOLUME = "ambient_master_volume"
    private const val KEY_MUSIC_PLAYING = "music_playing"
    private const val KEY_MUSIC_INDEX = "music_index"
    private const val KEY_ACTIVE_PLAYLIST_ID = "active_playlist_id"
    private const val KEY_CURRENT_TRACK_TITLE = "current_track_title"
    private const val KEY_CURRENT_TRACK_ARTIST = "current_track_artist"
    private const val KEY_POSITION_MS = "music_position_ms"
    private const val KEY_DURATION_MS = "music_duration_ms"
    private const val KEY_SHUFFLE = "music_shuffle"
    private const val KEY_REPEAT_MODE = "music_repeat_mode"
    private const val KEY_SLEEP_TIMER_END_AT = "sleep_timer_end_at"
    private const val KEY_AUTO_PLAY_STUDY = "auto_play_on_study"
    private const val KEY_AUTO_PLAY_READING = "auto_play_on_reading"
    private const val KEY_AUTO_PAUSE_BREAK = "auto_pause_on_break"
    private const val KEY_SELECTED_STUDY_TARGET = "selected_study_target"
    private const val KEY_SELECTED_READING_TARGET = "selected_reading_target"
    private const val KEY_PLAYLISTS = "music_playlists_v2"
    private const val KEY_LEGACY_PLAYLIST = "music_playlist"
    private const val KEY_LEGACY_PLAYLIST_MIGRATED = "legacy_playlist_migrated"
    private const val KEY_CUSTOM_MIXES = "custom_sound_mixes_v1"

    val defaultSoundVolumes: Map<String, Float> = mapOf(
        "lofi" to 0.22f,
        "soft_piano" to 0.26f,
        "wind_chimes" to 0.24f,
        "ambient_pad" to 0.26f,
        "singing_bowl" to 0.28f,
        "white" to 0.25f,
        "pink" to 0.30f,
        "brown" to 0.38f,
        "green_noise" to 0.34f,
        "rain" to 0.38f,
        "rain_window" to 0.35f,
        "waterfall" to 0.34f,
        "coffee" to 0.25f,
        "fire" to 0.26f,
        "waves" to 0.35f,
        "mountain_wind" to 0.30f,
        "fan" to 0.34f,
        "train" to 0.32f,
        "forest" to 0.28f,
        "forest_stream" to 0.34f,
        "forest_rain" to 0.36f,
        "forest_night" to 0.30f,
        "birds" to 0.30f,
        "mourning_dove" to 0.32f,
        "binaural_alpha" to 0.30f,
        "binaural_theta" to 0.30f,
        "binaural_beta" to 0.28f,
        "binaural_gamma" to 0.25f,
        "binaural_delta" to 0.30f,
        "library" to 0.32f,
        "thunder" to 0.34f,
        "keyboard" to 0.28f
    )

    val allSoundKeys: List<String> get() = defaultSoundVolumes.keys.toList()

    /** Opens `"studydock_audio"` [SharedPreferences] cleanly. */
    fun prefs(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Normalizes a loop/repeat mode token into [REPEAT_OFF], [REPEAT_ALL], or [REPEAT_ONE]. */
    fun normalizeRepeatMode(raw: String?): String = when (raw?.trim()?.uppercase(Locale.ROOT)) {
        REPEAT_OFF -> REPEAT_OFF
        REPEAT_ONE -> REPEAT_ONE
        else -> REPEAT_ALL
    }

    /** Reads the full [AudioPlaybackSnapshot] from [context]. */
    fun readSnapshot(context: Context): AudioPlaybackSnapshot = readSnapshot(prefs(context))

    /** Reads the full [AudioPlaybackSnapshot] from [prefs]. */
    @Synchronized
    fun readSnapshot(prefs: SharedPreferences): AudioPlaybackSnapshot {
        val activeSounds = if (prefs.contains(KEY_ACTIVE_SOUNDS)) {
            prefs.getStringSet(KEY_ACTIVE_SOUNDS, emptySet())?.toSet().orEmpty()
        } else {
            setOf("brown")
        }

        val soundVolumes = LinkedHashMap<String, Float>(defaultSoundVolumes.size)
        for ((key, defaultVol) in defaultSoundVolumes) {
            soundVolumes[key] = prefs.getFloat("$KEY_VOLUME_PREFIX$key", defaultVol).coerceIn(0f, 1f)
        }
        prefs.all.forEach { (prefKey, value) ->
            if (prefKey.startsWith(KEY_VOLUME_PREFIX) && value is Number) {
                val soundKey = prefKey.removePrefix(KEY_VOLUME_PREFIX)
                if (soundKey.isNotEmpty() && !soundVolumes.containsKey(soundKey)) {
                    soundVolumes[soundKey] = value.toFloat().coerceIn(0f, 1f)
                }
            }
        }

        val sleepRaw = prefs.getLong(KEY_SLEEP_TIMER_END_AT, 0L)
        return AudioPlaybackSnapshot(
            ambientPlaying = prefs.getBoolean(KEY_AMBIENT_PLAYING, false),
            activeSounds = activeSounds,
            soundVolumes = soundVolumes,
            ambientMasterVolume = prefs.getFloat(KEY_AMBIENT_MASTER_VOLUME, 0.85f).coerceIn(0f, 1f),
            musicPlaying = prefs.getBoolean(KEY_MUSIC_PLAYING, false),
            activePlaylistId = prefs.getString(KEY_ACTIVE_PLAYLIST_ID, DEFAULT_PLAYLIST_ID)
                ?.trim()
                ?.ifEmpty { DEFAULT_PLAYLIST_ID }
                ?: DEFAULT_PLAYLIST_ID,
            currentTrackIndex = prefs.getInt(KEY_MUSIC_INDEX, 0).coerceAtLeast(0),
            currentTrackTitle = prefs.getString(KEY_CURRENT_TRACK_TITLE, "").orEmpty(),
            currentTrackArtist = prefs.getString(KEY_CURRENT_TRACK_ARTIST, "").orEmpty(),
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
            durationMs = prefs.getLong(KEY_DURATION_MS, 0L).coerceAtLeast(0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = normalizeRepeatMode(prefs.getString(KEY_REPEAT_MODE, REPEAT_ALL)),
            sleepTimerEndAt = sleepRaw.takeIf { it > 0L },
            autoPlayOnStudy = prefs.getBoolean(KEY_AUTO_PLAY_STUDY, false),
            autoPlayOnReading = prefs.getBoolean(KEY_AUTO_PLAY_READING, false),
            autoPauseOnBreak = prefs.getBoolean(KEY_AUTO_PAUSE_BREAK, true),
            selectedStudyTarget = prefs.getString(KEY_SELECTED_STUDY_TARGET, "").orEmpty(),
            selectedReadingTarget = prefs.getString(KEY_SELECTED_READING_TARGET, "").orEmpty()
        )
    }

    /** Writes [snapshot] completely to `"studydock_audio"`. */
    fun writeSnapshot(context: Context, snapshot: AudioPlaybackSnapshot) {
        writeSnapshot(prefs(context), snapshot)
    }

    /** Writes [snapshot] completely to [prefs]. */
    @Synchronized
    fun writeSnapshot(prefs: SharedPreferences, snapshot: AudioPlaybackSnapshot) {
        prefs.edit().apply {
            putBoolean(KEY_AMBIENT_PLAYING, snapshot.ambientPlaying)
            putStringSet(KEY_ACTIVE_SOUNDS, snapshot.activeSounds.toSet())
            snapshot.soundVolumes.forEach { (key, value) ->
                putFloat("$KEY_VOLUME_PREFIX$key", value.coerceIn(0f, 1f))
            }
            putFloat(KEY_AMBIENT_MASTER_VOLUME, snapshot.ambientMasterVolume.coerceIn(0f, 1f))
            putBoolean(KEY_MUSIC_PLAYING, snapshot.musicPlaying)
            putString(
                KEY_ACTIVE_PLAYLIST_ID,
                snapshot.activePlaylistId.trim().ifEmpty { DEFAULT_PLAYLIST_ID }
            )
            putInt(KEY_MUSIC_INDEX, snapshot.currentTrackIndex.coerceAtLeast(0))
            putString(KEY_CURRENT_TRACK_TITLE, snapshot.currentTrackTitle)
            putString(KEY_CURRENT_TRACK_ARTIST, snapshot.currentTrackArtist)
            putLong(KEY_POSITION_MS, snapshot.positionMs.coerceAtLeast(0L))
            putLong(KEY_DURATION_MS, snapshot.durationMs.coerceAtLeast(0L))
            putBoolean(KEY_SHUFFLE, snapshot.shuffle)
            putString(KEY_REPEAT_MODE, normalizeRepeatMode(snapshot.repeatMode))
            putLong(KEY_SLEEP_TIMER_END_AT, snapshot.sleepTimerEndAt?.takeIf { it > 0L } ?: 0L)
            putBoolean(KEY_AUTO_PLAY_STUDY, snapshot.autoPlayOnStudy)
            putBoolean(KEY_AUTO_PLAY_READING, snapshot.autoPlayOnReading)
            putBoolean(KEY_AUTO_PAUSE_BREAK, snapshot.autoPauseOnBreak)
            putString(KEY_SELECTED_STUDY_TARGET, snapshot.selectedStudyTarget)
            putString(KEY_SELECTED_READING_TARGET, snapshot.selectedReadingTarget)
        }.apply()
    }

    /** Updates only ambient playback fields without clobbering unrelated snapshot keys. */
    fun updateAmbientState(
        context: Context,
        ambientPlaying: Boolean? = null,
        activeSounds: Set<String>? = null,
        soundVolumes: Map<String, Float>? = null,
        masterVolume: Float? = null
    ) {
        updateAmbientState(prefs(context), ambientPlaying, activeSounds, soundVolumes, masterVolume)
    }

    /** Updates only ambient playback fields in [prefs]. */
    @Synchronized
    fun updateAmbientState(
        prefs: SharedPreferences,
        ambientPlaying: Boolean? = null,
        activeSounds: Set<String>? = null,
        soundVolumes: Map<String, Float>? = null,
        masterVolume: Float? = null
    ) {
        prefs.edit().apply {
            ambientPlaying?.let { putBoolean(KEY_AMBIENT_PLAYING, it) }
            activeSounds?.let { putStringSet(KEY_ACTIVE_SOUNDS, it.toSet()) }
            soundVolumes?.forEach { (key, value) ->
                putFloat("$KEY_VOLUME_PREFIX$key", value.coerceIn(0f, 1f))
            }
            masterVolume?.let { putFloat(KEY_AMBIENT_MASTER_VOLUME, it.coerceIn(0f, 1f)) }
        }.apply()
    }

    /** Updates only local-music playback fields without clobbering ambient or auto-audio keys. */
    fun updateMusicPlayback(
        context: Context,
        musicPlaying: Boolean? = null,
        activePlaylistId: String? = null,
        currentTrackIndex: Int? = null,
        currentTrackTitle: String? = null,
        currentTrackArtist: String? = null,
        positionMs: Long? = null,
        durationMs: Long? = null,
        shuffle: Boolean? = null,
        repeatMode: String? = null
    ) {
        updateMusicPlayback(
            prefs(context),
            musicPlaying,
            activePlaylistId,
            currentTrackIndex,
            currentTrackTitle,
            currentTrackArtist,
            positionMs,
            durationMs,
            shuffle,
            repeatMode
        )
    }

    /** Updates only local-music playback fields in [prefs]. */
    @Synchronized
    fun updateMusicPlayback(
        prefs: SharedPreferences,
        musicPlaying: Boolean? = null,
        activePlaylistId: String? = null,
        currentTrackIndex: Int? = null,
        currentTrackTitle: String? = null,
        currentTrackArtist: String? = null,
        positionMs: Long? = null,
        durationMs: Long? = null,
        shuffle: Boolean? = null,
        repeatMode: String? = null
    ) {
        prefs.edit().apply {
            musicPlaying?.let { putBoolean(KEY_MUSIC_PLAYING, it) }
            activePlaylistId?.let {
                putString(KEY_ACTIVE_PLAYLIST_ID, it.trim().ifEmpty { DEFAULT_PLAYLIST_ID })
            }
            currentTrackIndex?.let { putInt(KEY_MUSIC_INDEX, it.coerceAtLeast(0)) }
            currentTrackTitle?.let { putString(KEY_CURRENT_TRACK_TITLE, it) }
            currentTrackArtist?.let { putString(KEY_CURRENT_TRACK_ARTIST, it) }
            positionMs?.let { putLong(KEY_POSITION_MS, it.coerceAtLeast(0L)) }
            durationMs?.let { putLong(KEY_DURATION_MS, it.coerceAtLeast(0L)) }
            shuffle?.let { putBoolean(KEY_SHUFFLE, it) }
            repeatMode?.let { putString(KEY_REPEAT_MODE, normalizeRepeatMode(it)) }
        }.apply()
    }

    /** Updates [AudioPlaybackSnapshot.sleepTimerEndAt] (`null` clears the timer). */
    fun setSleepTimerEndAt(context: Context, endAt: Long?) {
        setSleepTimerEndAt(prefs(context), endAt)
    }

    /** Updates [AudioPlaybackSnapshot.sleepTimerEndAt] inside [prefs]. */
    @Synchronized
    fun setSleepTimerEndAt(prefs: SharedPreferences, endAt: Long?) {
        prefs.edit().putLong(KEY_SLEEP_TIMER_END_AT, endAt?.takeIf { it > 0L } ?: 0L).apply()
    }

    /** Updates auto-play / auto-pause preferences without touching playback state. */
    fun updateAutoAudioPreferences(
        context: Context,
        autoPlayOnStudy: Boolean? = null,
        autoPlayOnReading: Boolean? = null,
        autoPauseOnBreak: Boolean? = null,
        selectedStudyTarget: String? = null,
        selectedReadingTarget: String? = null
    ) {
        updateAutoAudioPreferences(
            prefs(context),
            autoPlayOnStudy,
            autoPlayOnReading,
            autoPauseOnBreak,
            selectedStudyTarget,
            selectedReadingTarget
        )
    }

    /** Updates auto-play / auto-pause preferences inside [prefs]. */
    @Synchronized
    fun updateAutoAudioPreferences(
        prefs: SharedPreferences,
        autoPlayOnStudy: Boolean? = null,
        autoPlayOnReading: Boolean? = null,
        autoPauseOnBreak: Boolean? = null,
        selectedStudyTarget: String? = null,
        selectedReadingTarget: String? = null
    ) {
        prefs.edit().apply {
            autoPlayOnStudy?.let { putBoolean(KEY_AUTO_PLAY_STUDY, it) }
            autoPlayOnReading?.let { putBoolean(KEY_AUTO_PLAY_READING, it) }
            autoPauseOnBreak?.let { putBoolean(KEY_AUTO_PAUSE_BREAK, it) }
            selectedStudyTarget?.let { putString(KEY_SELECTED_STUDY_TARGET, it) }
            selectedReadingTarget?.let { putString(KEY_SELECTED_READING_TARGET, it) }
        }.apply()
    }

    /**
     * Reads all [MusicPlaylist] entries, automatically migrating any legacy `"music_playlist"`
     * tracks into a playlist named `"Favoritas"`.
     */
    fun readPlaylists(context: Context): List<MusicPlaylist> = readPlaylists(prefs(context))

    /** Reads all [MusicPlaylist] entries from [prefs] with legacy migration support. */
    @Synchronized
    fun readPlaylists(prefs: SharedPreferences): List<MusicPlaylist> {
        val raw = prefs.getString(KEY_PLAYLISTS, null)
        if (raw != null) {
            return parsePlaylistsJson(raw)
        }
        val legacyTracks = parseLegacyTracks(prefs.getString(KEY_LEGACY_PLAYLIST, null))
        val initialPlaylist = MusicPlaylist(
            id = DEFAULT_PLAYLIST_ID,
            name = DEFAULT_PLAYLIST_NAME,
            tracks = legacyTracks,
            loopMode = REPEAT_ALL,
            shuffle = false
        )
        val result = listOf(initialPlaylist)
        if (legacyTracks.isNotEmpty() || !prefs.getBoolean(KEY_LEGACY_PLAYLIST_MIGRATED, false)) {
            savePlaylistsLocked(prefs, result)
            if (!prefs.contains(KEY_ACTIVE_PLAYLIST_ID)) {
                prefs.edit().putString(KEY_ACTIVE_PLAYLIST_ID, DEFAULT_PLAYLIST_ID).apply()
            }
        }
        return result
    }

    /** Persists [playlists] to `"studydock_audio"`. */
    fun savePlaylists(context: Context, playlists: List<MusicPlaylist>) {
        savePlaylists(prefs(context), playlists)
    }

    /** Persists [playlists] to [prefs]. */
    @Synchronized
    fun savePlaylists(prefs: SharedPreferences, playlists: List<MusicPlaylist>) {
        savePlaylistsLocked(prefs, playlists)
    }

    private fun savePlaylistsLocked(prefs: SharedPreferences, playlists: List<MusicPlaylist>) {
        val array = JSONArray()
        playlists.forEachIndexed { index, playlist ->
            val cleanName = playlist.name.trim().ifEmpty { "Playlist ${index + 1}" }
            val cleanId = playlist.id.trim().ifEmpty { "pl_$index" }
            array.put(
                JSONObject().apply {
                    put("id", cleanId)
                    put("name", cleanName)
                    put("loopMode", normalizeRepeatMode(playlist.loopMode))
                    put("shuffle", playlist.shuffle)
                    put("tracks", serializeTracks(playlist.tracks))
                }
            )
        }
        prefs.edit()
            .putString(KEY_PLAYLISTS, array.toString())
            .putBoolean(KEY_LEGACY_PLAYLIST_MIGRATED, true)
            .apply()
    }

    /** Returns the currently selected [MusicPlaylist], falling back to the first playlist if missing. */
    fun activePlaylist(context: Context): MusicPlaylist? = activePlaylist(prefs(context))

    /** Returns the currently selected [MusicPlaylist] from [prefs]. */
    @Synchronized
    fun activePlaylist(prefs: SharedPreferences): MusicPlaylist? {
        val playlists = readPlaylists(prefs)
        val activeId = prefs.getString(KEY_ACTIVE_PLAYLIST_ID, DEFAULT_PLAYLIST_ID)
        return playlists.firstOrNull { it.id == activeId } ?: playlists.firstOrNull()
    }

    /** Selects [playlistId] as the active playlist in `"studydock_audio"`. */
    fun setActivePlaylist(context: Context, playlistId: String) {
        setActivePlaylist(prefs(context), playlistId)
    }

    /** Selects [playlistId] as the active playlist in [prefs]. */
    @Synchronized
    fun setActivePlaylist(prefs: SharedPreferences, playlistId: String) {
        prefs.edit()
            .putString(KEY_ACTIVE_PLAYLIST_ID, playlistId.trim().ifEmpty { DEFAULT_PLAYLIST_ID })
            .apply()
    }

    /** Replaces the playlist matching [playlist.id] inside `"studydock_audio"`. */
    fun updatePlaylist(context: Context, playlist: MusicPlaylist): List<MusicPlaylist> =
        updatePlaylist(prefs(context), playlist)

    /** Replaces the playlist matching [playlist.id] inside [prefs]. */
    @Synchronized
    fun updatePlaylist(prefs: SharedPreferences, playlist: MusicPlaylist): List<MusicPlaylist> {
        val current = readPlaylists(prefs)
        val normalized = playlist.copy(
            name = playlist.name.trim().ifEmpty { DEFAULT_PLAYLIST_NAME },
            tracks = sanitizeTracks(playlist.tracks),
            loopMode = normalizeRepeatMode(playlist.loopMode)
        )
        val updated = current.map { if (it.id == normalized.id) normalized else it }
        savePlaylistsLocked(prefs, updated)
        return updated
    }

    /** Creates and persists a new [MusicPlaylist], returning the created playlist. */
    fun createPlaylist(
        context: Context,
        name: String,
        tracks: List<AudioTrackItem> = emptyList()
    ): MusicPlaylist = createPlaylist(prefs(context), name, tracks)

    /** Creates and persists a new [MusicPlaylist] inside [prefs]. */
    @Synchronized
    fun createPlaylist(
        prefs: SharedPreferences,
        name: String,
        tracks: List<AudioTrackItem> = emptyList()
    ): MusicPlaylist {
        val current = readPlaylists(prefs)
        val cleanName = name.trim().ifEmpty { "Playlist ${current.size + 1}" }
        val created = MusicPlaylist(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            tracks = sanitizeTracks(tracks),
            loopMode = REPEAT_ALL,
            shuffle = false
        )
        savePlaylistsLocked(prefs, current + created)
        return created
    }

    /** Renames the playlist matching [playlistId] and returns the updated list. */
    fun renamePlaylist(context: Context, playlistId: String, newName: String): List<MusicPlaylist> =
        renamePlaylist(prefs(context), playlistId, newName)

    /** Renames the playlist matching [playlistId] in [prefs]. */
    @Synchronized
    fun renamePlaylist(
        prefs: SharedPreferences,
        playlistId: String,
        newName: String
    ): List<MusicPlaylist> {
        val clean = newName.trim()
        val current = readPlaylists(prefs)
        if (clean.isEmpty()) return current
        val updated = current.map { if (it.id == playlistId) it.copy(name = clean) else it }
        savePlaylistsLocked(prefs, updated)
        return updated
    }

    /** Deletes the playlist matching [playlistId] and returns the updated list. */
    fun deletePlaylist(context: Context, playlistId: String): List<MusicPlaylist> =
        deletePlaylist(prefs(context), playlistId)

    /** Deletes the playlist matching [playlistId] from [prefs], re-pointing `activePlaylistId` if needed. */
    @Synchronized
    fun deletePlaylist(prefs: SharedPreferences, playlistId: String): List<MusicPlaylist> {
        val current = readPlaylists(prefs)
        val updated = current.filterNot { it.id == playlistId }
        savePlaylistsLocked(prefs, updated)
        val activeId = prefs.getString(KEY_ACTIVE_PLAYLIST_ID, DEFAULT_PLAYLIST_ID)
        if (activeId == playlistId) {
            val fallbackId = updated.firstOrNull()?.id ?: DEFAULT_PLAYLIST_ID
            prefs.edit().putString(KEY_ACTIVE_PLAYLIST_ID, fallbackId).apply()
        }
        return updated
    }

    /** Replaces the tracks of [playlistId] and returns the updated playlist list. */
    fun updatePlaylistTracks(
        context: Context,
        playlistId: String,
        tracks: List<AudioTrackItem>
    ): List<MusicPlaylist> = updatePlaylistTracks(prefs(context), playlistId, tracks)

    /** Replaces the tracks of [playlistId] inside [prefs]. */
    @Synchronized
    fun updatePlaylistTracks(
        prefs: SharedPreferences,
        playlistId: String,
        tracks: List<AudioTrackItem>
    ): List<MusicPlaylist> {
        val cleanTracks = sanitizeTracks(tracks)
        val current = readPlaylists(prefs)
        val updated = current.map { if (it.id == playlistId) it.copy(tracks = cleanTracks) else it }
        savePlaylistsLocked(prefs, updated)
        return updated
    }

    /** Reads all saved [CustomSoundMix] entries from `"studydock_audio"`. */
    fun readCustomMixes(context: Context): List<CustomSoundMix> = readCustomMixes(prefs(context))

    /** Reads all saved [CustomSoundMix] entries from [prefs]. */
    @Synchronized
    fun readCustomMixes(prefs: SharedPreferences): List<CustomSoundMix> {
        val raw = prefs.getString(KEY_CUSTOM_MIXES, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val name = obj.optString("name").trim()
                    if (name.isEmpty()) continue
                    val id = obj.optString("id").trim().ifEmpty { "mix_$index" }
                    val master = obj.optDouble("masterVolume", 0.85).toFloat().coerceIn(0f, 1f)
                    val volObj = obj.optJSONObject("volumes")
                    val volumes = LinkedHashMap<String, Float>()
                    if (volObj != null) {
                        val keys = volObj.keys()
                        while (keys.hasNext()) {
                            val soundKey = keys.next()
                            val level = volObj.optDouble(soundKey, Double.NaN)
                            if (soundKey.isNotBlank() && !level.isNaN()) {
                                volumes[soundKey] = level.toFloat().coerceIn(0f, 1f)
                            }
                        }
                    }
                    add(CustomSoundMix(id = id, name = name, volumes = volumes, masterVolume = master))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Persists all [CustomSoundMix] entries to `"studydock_audio"`. */
    fun saveCustomMixes(context: Context, mixes: List<CustomSoundMix>) {
        saveCustomMixes(prefs(context), mixes)
    }

    /** Persists all [CustomSoundMix] entries to [prefs]. */
    @Synchronized
    fun saveCustomMixes(prefs: SharedPreferences, mixes: List<CustomSoundMix>) {
        saveCustomMixesLocked(prefs, mixes)
    }

    /** Upserts [mix] into saved mixes and returns the updated list. */
    fun saveCustomMix(context: Context, mix: CustomSoundMix): List<CustomSoundMix> =
        saveCustomMix(prefs(context), mix)

    /** Upserts [mix] inside [prefs] and returns the updated list. */
    @Synchronized
    fun saveCustomMix(prefs: SharedPreferences, mix: CustomSoundMix): List<CustomSoundMix> {
        val cleanName = mix.name.trim()
        val current = readCustomMixes(prefs)
        if (cleanName.isEmpty()) return current
        val normalized = mix.copy(
            id = mix.id.trim().ifEmpty { UUID.randomUUID().toString() },
            name = cleanName,
            volumes = mix.volumes.mapValues { (_, v) -> v.coerceIn(0f, 1f) },
            masterVolume = mix.masterVolume.coerceIn(0f, 1f)
        )
        val exists = current.any { it.id == normalized.id }
        val updated = if (exists) {
            current.map { if (it.id == normalized.id) normalized else it }
        } else {
            current + normalized
        }
        saveCustomMixesLocked(prefs, updated)
        return updated
    }

    /** Deletes the custom mix matching [mixId] and returns the updated list. */
    fun deleteCustomMix(context: Context, mixId: String): List<CustomSoundMix> =
        deleteCustomMix(prefs(context), mixId)

    /** Deletes the custom mix matching [mixId] from [prefs]. */
    @Synchronized
    fun deleteCustomMix(prefs: SharedPreferences, mixId: String): List<CustomSoundMix> {
        val updated = readCustomMixes(prefs).filterNot { it.id == mixId }
        saveCustomMixesLocked(prefs, updated)
        return updated
    }

    private fun saveCustomMixesLocked(prefs: SharedPreferences, mixes: List<CustomSoundMix>) {
        val array = JSONArray()
        mixes.filter { it.name.isNotBlank() }.forEachIndexed { index, mix ->
            array.put(
                JSONObject().apply {
                    put("id", mix.id.trim().ifEmpty { "mix_$index" })
                    put("name", mix.name.trim())
                    put("masterVolume", mix.masterVolume.coerceIn(0f, 1f).toDouble())
                    put(
                        "volumes",
                        JSONObject().apply {
                            mix.volumes.forEach { (k, v) ->
                                if (k.isNotBlank()) put(k, v.coerceIn(0f, 1f).toDouble())
                            }
                        }
                    )
                }
            )
        }
        prefs.edit().putString(KEY_CUSTOM_MIXES, array.toString()).apply()
    }

    private fun parsePlaylistsJson(raw: String): List<MusicPlaylist> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val id = obj.optString("id").trim().ifEmpty { "pl_$index" }
                    val name = obj.optString("name").trim().ifEmpty { "Playlist ${index + 1}" }
                    val loopMode = normalizeRepeatMode(obj.optString("loopMode", REPEAT_ALL))
                    val shuffle = obj.optBoolean("shuffle", false)
                    val tracks = parseTracksArray(obj.optJSONArray("tracks"))
                    add(
                        MusicPlaylist(
                            id = id,
                            name = name,
                            tracks = tracks,
                            loopMode = loopMode,
                            shuffle = shuffle
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseLegacyTracks(raw: String?): List<AudioTrackItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { parseTracksArray(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun parseTracksArray(array: JSONArray?): List<AudioTrackItem> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val uri = obj.optString("uri").trim()
                if (uri.isEmpty()) continue
                val title = obj.optString("title").trim().ifEmpty { "Faixa ${index + 1}" }
                val artist = obj.optString("artist").trim()
                val durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L)
                add(AudioTrackItem(uri = uri, title = title, artist = artist, durationMs = durationMs))
            }
        }
    }

    private fun serializeTracks(tracks: List<AudioTrackItem>): JSONArray =
        JSONArray().apply {
            sanitizeTracks(tracks).forEach { track ->
                put(
                    JSONObject().apply {
                        put("uri", track.uri)
                        put("title", track.title)
                        put("artist", track.artist)
                        put("durationMs", track.durationMs)
                    }
                )
            }
        }

    private fun sanitizeTracks(tracks: List<AudioTrackItem>): List<AudioTrackItem> =
        tracks.filter { it.uri.isNotBlank() }.map {
            it.copy(
                uri = it.uri.trim(),
                title = it.title.trim().ifEmpty { "Faixa de música" },
                artist = it.artist.trim(),
                durationMs = it.durationMs.coerceAtLeast(0L)
            )
        }
}

// =====================================================================
// Modo Leitura estilo ReadEra (ReaderPreferencesStore, Temas & Paginação)
// =====================================================================

/** Authentic ReadEra color palettes for comfortable long-form reading. */
enum class ReadEraTheme(
    val key: String,
    val label: String,
    val bgHex: Long,
    val textHex: Long,
    val secondaryTextHex: Long,
    val surfaceHex: Long,
    val accentHex: Long,
    val isDark: Boolean
) {
    DAY(
        key = "DAY",
        label = "Dia (Papel)",
        bgHex = 0xFFFAF8F5L,
        textHex = 0xFF1F1D1AL,
        secondaryTextHex = 0xFF635D54L,
        surfaceHex = 0xFFEFECE6L,
        accentHex = 0xFFB85D19L,
        isDark = false
    ),
    SEPIA(
        key = "SEPIA",
        label = "Sépia",
        bgHex = 0xFFF4ECD8L,
        textHex = 0xFF3B2F23L,
        secondaryTextHex = 0xFF6E5C49L,
        surfaceHex = 0xFFE6DCC5L,
        accentHex = 0xFF9E4B10L,
        isDark = false
    ),
    TWILIGHT(
        key = "TWILIGHT",
        label = "Crepúsculo",
        bgHex = 0xFF242933L,
        textHex = 0xFFD8DEE9L,
        secondaryTextHex = 0xFF9AA5B8L,
        surfaceHex = 0xFF2E3440L,
        accentHex = 0xFF88C0D0L,
        isDark = true
    ),
    AMOLED(
        key = "AMOLED",
        label = "Noturno AMOLED",
        bgHex = 0xFF000000L,
        textHex = 0xFFD4D4D4L,
        secondaryTextHex = 0xFF8A8A8AL,
        surfaceHex = 0xFF141414L,
        accentHex = 0xFFE5A93CL,
        isDark = true
    );

    companion object {
        fun fromKey(raw: String?): ReadEraTheme =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: SEPIA
    }
}

/** Font family options in ReadEra reading mode. */
enum class ReadEraFontFamily(val key: String, val label: String) {
    SERIF("SERIF", "Serif"),
    SANS("SANS", "Sans-Serif"),
    MONO("MONO", "Monospace");

    companion object {
        fun fromKey(raw: String?): ReadEraFontFamily =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: SERIF
    }
}

/** Page margin presets in ReadEra reading mode. */
enum class ReadEraMarginPreset(
    val key: String,
    val label: String,
    val horizontalDp: Int,
    val verticalDp: Int
) {
    NARROW("NARROW", "Estreita", 12, 10),
    MEDIUM("MEDIUM", "Média", 22, 16),
    WIDE("WIDE", "Larga", 36, 24);

    companion object {
        fun fromKey(raw: String?): ReadEraMarginPreset =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: MEDIUM
    }
}

/** Navigation / page-turn mode inside ReadEra reader. */
enum class ReadEraScrollMode(val key: String, val label: String) {
    PAGINATED("PAGINATED", "Paginado"),
    VERTICAL("VERTICAL", "Rolagem contínua");

    companion object {
        fun fromKey(raw: String?): ReadEraScrollMode =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: PAGINATED
    }
}

/** Complete persisted snapshot of ReadEra reader settings. */
data class ReaderPreferencesSnapshot(
    val theme: ReadEraTheme = ReadEraTheme.SEPIA,
    val fontFamily: ReadEraFontFamily = ReadEraFontFamily.SERIF,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.55f,
    val marginPreset: ReadEraMarginPreset = ReadEraMarginPreset.MEDIUM,
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = true,
    val lockOrientation: Boolean = false,
    val scrollMode: ReadEraScrollMode = ReadEraScrollMode.PAGINATED,
    val tapZonesEnabled: Boolean = true
)

/**
 * Thread-safe persistence for ReadEra reader preferences, book page bookmarks,
 * wishlist priority queue ("Fila de próximas leituras"), and text pagination.
 */
object ReaderPreferencesStore {

    const val PREFS_NAME = "studydock_reader"

    const val MIN_FONT_SIZE_SP = 14f
    const val MAX_FONT_SIZE_SP = 28f
    const val MIN_LINE_HEIGHT = 1.25f
    const val MAX_LINE_HEIGHT = 1.95f
    const val MAX_TEXT_CHARS = 1_500_000

    /** Reads UTF-8 text from [stream] up to [maxChars] characters to prevent OOM on large files. */
    fun readBoundedText(stream: InputStream, maxChars: Int = MAX_TEXT_CHARS): String {
        require(maxChars >= 0) { "maxChars must be non-negative" }
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            val sb = StringBuilder()
            val buffer = CharArray(8192)
            while (sb.length < maxChars) {
                val toRead = minOf(buffer.size, maxChars - sb.length)
                val read = reader.read(buffer, 0, toRead)
                if (read == -1) break
                sb.append(buffer, 0, read)
            }
            return sb.toString()
        }
    }

    private const val KEY_THEME = "reader_theme"
    private const val KEY_FONT_FAMILY = "reader_font_family"
    private const val KEY_FONT_SIZE_SP = "reader_font_size_sp"
    private const val KEY_LINE_HEIGHT = "reader_line_height"
    private const val KEY_MARGIN_PRESET = "reader_margin_preset"
    private const val KEY_BRIGHTNESS = "reader_brightness"
    private const val KEY_KEEP_SCREEN_ON = "reader_keep_screen_on"
    private const val KEY_LOCK_ORIENTATION = "reader_lock_orientation"
    private const val KEY_SCROLL_MODE = "reader_scroll_mode"
    private const val KEY_TAP_ZONES = "reader_tap_zones"
    private const val KEY_WISHLIST_QUEUE = "wishlist_priority_ids"
    private const val KEY_BOOKMARKS_PREFIX = "book_bookmarks_"

    fun prefs(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSnapshot(context: Context): ReaderPreferencesSnapshot = readSnapshot(prefs(context))

    @Synchronized
    fun readSnapshot(prefs: SharedPreferences): ReaderPreferencesSnapshot {
        val rawBrightness = prefs.getFloat(KEY_BRIGHTNESS, -1f)
        val normalizedBrightness = if (rawBrightness < 0f) -1f else rawBrightness.coerceIn(0.05f, 1.0f)
        return ReaderPreferencesSnapshot(
            theme = ReadEraTheme.fromKey(prefs.getString(KEY_THEME, ReadEraTheme.SEPIA.key)),
            fontFamily = ReadEraFontFamily.fromKey(prefs.getString(KEY_FONT_FAMILY, ReadEraFontFamily.SERIF.key)),
            fontSizeSp = prefs.getFloat(KEY_FONT_SIZE_SP, 18f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
            lineHeightMultiplier = prefs.getFloat(KEY_LINE_HEIGHT, 1.55f).coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT),
            marginPreset = ReadEraMarginPreset.fromKey(prefs.getString(KEY_MARGIN_PRESET, ReadEraMarginPreset.MEDIUM.key)),
            brightness = normalizedBrightness,
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
            lockOrientation = prefs.getBoolean(KEY_LOCK_ORIENTATION, false),
            scrollMode = ReadEraScrollMode.fromKey(prefs.getString(KEY_SCROLL_MODE, ReadEraScrollMode.PAGINATED.key)),
            tapZonesEnabled = prefs.getBoolean(KEY_TAP_ZONES, true)
        )
    }

    fun writeSnapshot(context: Context, snapshot: ReaderPreferencesSnapshot) {
        writeSnapshot(prefs(context), snapshot)
    }

    @Synchronized
    fun writeSnapshot(prefs: SharedPreferences, snapshot: ReaderPreferencesSnapshot) {
        val normalizedBrightness = if (snapshot.brightness < 0f) -1f else snapshot.brightness.coerceIn(0.05f, 1.0f)
        prefs.edit().apply {
            putString(KEY_THEME, snapshot.theme.key)
            putString(KEY_FONT_FAMILY, snapshot.fontFamily.key)
            putFloat(KEY_FONT_SIZE_SP, snapshot.fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))
            putFloat(KEY_LINE_HEIGHT, snapshot.lineHeightMultiplier.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT))
            putString(KEY_MARGIN_PRESET, snapshot.marginPreset.key)
            putFloat(KEY_BRIGHTNESS, normalizedBrightness)
            putBoolean(KEY_KEEP_SCREEN_ON, snapshot.keepScreenOn)
            putBoolean(KEY_LOCK_ORIENTATION, snapshot.lockOrientation)
            putString(KEY_SCROLL_MODE, snapshot.scrollMode.key)
            putBoolean(KEY_TAP_ZONES, snapshot.tapZonesEnabled)
        }.apply()
    }

    fun updatePreferences(
        context: Context,
        theme: ReadEraTheme? = null,
        fontFamily: ReadEraFontFamily? = null,
        fontSizeSp: Float? = null,
        lineHeightMultiplier: Float? = null,
        marginPreset: ReadEraMarginPreset? = null,
        brightness: Float? = null,
        keepScreenOn: Boolean? = null,
        lockOrientation: Boolean? = null,
        scrollMode: ReadEraScrollMode? = null,
        tapZonesEnabled: Boolean? = null
    ): ReaderPreferencesSnapshot = updatePreferences(
        prefs(context),
        theme,
        fontFamily,
        fontSizeSp,
        lineHeightMultiplier,
        marginPreset,
        brightness,
        keepScreenOn,
        lockOrientation,
        scrollMode,
        tapZonesEnabled
    )

    @Synchronized
    fun updatePreferences(
        prefs: SharedPreferences,
        theme: ReadEraTheme? = null,
        fontFamily: ReadEraFontFamily? = null,
        fontSizeSp: Float? = null,
        lineHeightMultiplier: Float? = null,
        marginPreset: ReadEraMarginPreset? = null,
        brightness: Float? = null,
        keepScreenOn: Boolean? = null,
        lockOrientation: Boolean? = null,
        scrollMode: ReadEraScrollMode? = null,
        tapZonesEnabled: Boolean? = null
    ): ReaderPreferencesSnapshot {
        val current = readSnapshot(prefs)
        val updated = current.copy(
            theme = theme ?: current.theme,
            fontFamily = fontFamily ?: current.fontFamily,
            fontSizeSp = (fontSizeSp ?: current.fontSizeSp).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
            lineHeightMultiplier = (lineHeightMultiplier ?: current.lineHeightMultiplier).coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT),
            marginPreset = marginPreset ?: current.marginPreset,
            brightness = brightness?.let { if (it < 0f) -1f else it.coerceIn(0.05f, 1.0f) } ?: current.brightness,
            keepScreenOn = keepScreenOn ?: current.keepScreenOn,
            lockOrientation = lockOrientation ?: current.lockOrientation,
            scrollMode = scrollMode ?: current.scrollMode,
            tapZonesEnabled = tapZonesEnabled ?: current.tapZonesEnabled
        )
        writeSnapshot(prefs, updated)
        return updated
    }

    /** Returns the set of book IDs marked as priority in the "Quero ler" shelf queue. */
    fun readWishlistQueue(context: Context): Set<Long> = readWishlistQueue(prefs(context))

    @Synchronized
    fun readWishlistQueue(prefs: SharedPreferences): Set<Long> =
        prefs.getStringSet(KEY_WISHLIST_QUEUE, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0L }
            .toSet()

    /** Toggles whether [bookId] is highlighted in the "Quero ler" priority queue. */
    fun toggleWishlistQueue(context: Context, bookId: Long): Set<Long> =
        toggleWishlistQueue(prefs(context), bookId)

    @Synchronized
    fun toggleWishlistQueue(prefs: SharedPreferences, bookId: Long): Set<Long> {
        if (bookId <= 0L) return readWishlistQueue(prefs)
        val current = readWishlistQueue(prefs).toMutableSet()
        if (!current.add(bookId)) {
            current.remove(bookId)
        }
        prefs.edit().putStringSet(KEY_WISHLIST_QUEUE, current.map { it.toString() }.toSet()).apply()
        return current
    }

    /** Reads bookmarked page numbers (1-based) for [bookId]. */
    fun readBookmarks(context: Context, bookId: Long): Set<Int> = readBookmarks(prefs(context), bookId)

    @Synchronized
    fun readBookmarks(prefs: SharedPreferences, bookId: Long): Set<Int> {
        if (bookId <= 0L) return emptySet()
        return prefs.getStringSet("$KEY_BOOKMARKS_PREFIX$bookId", emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
            .toSortedSet()
    }

    /** Toggles a 1-based page bookmark for [bookId] and returns the updated bookmark set. */
    fun toggleBookmark(context: Context, bookId: Long, page: Int): Set<Int> =
        toggleBookmark(prefs(context), bookId, page)

    @Synchronized
    fun toggleBookmark(prefs: SharedPreferences, bookId: Long, page: Int): Set<Int> {
        if (bookId <= 0L || page <= 0) return readBookmarks(prefs, bookId)
        val current = readBookmarks(prefs, bookId).toMutableSet()
        if (!current.add(page)) {
            current.remove(page)
        }
        prefs.edit()
            .putStringSet("$KEY_BOOKMARKS_PREFIX$bookId", current.map { it.toString() }.toSet())
            .apply()
        return current.toSortedSet()
    }

    /**
     * Breaks plain text or Markdown content into comfortable, deterministic pages
     * calibrated to [fontSizeSp], [lineHeightMultiplier], and [marginPreset].
     */
    fun paginateText(
        rawText: String,
        fontSizeSp: Float = 18f,
        lineHeightMultiplier: Float = 1.55f,
        marginPreset: ReadEraMarginPreset = ReadEraMarginPreset.MEDIUM
    ): List<String> {
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()

        val sizeFactor = (18f / fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))
        val lineFactor = (1.55f / lineHeightMultiplier.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT))
        val marginFactor = when (marginPreset) {
            ReadEraMarginPreset.NARROW -> 1.15f
            ReadEraMarginPreset.MEDIUM -> 1.0f
            ReadEraMarginPreset.WIDE -> 0.84f
        }
        val targetCharsPerPage = (1450f * sizeFactor * lineFactor * marginFactor)
            .roundToInt()
            .coerceIn(550, 2600)

        val paragraphs = normalized.split(Regex("\n{2,}"))
        val pages = ArrayList<String>()
        val currentBuilder = StringBuilder()

        fun flushPage() {
            val content = currentBuilder.toString().trim()
            if (content.isNotEmpty()) {
                pages.add(content)
            }
            currentBuilder.setLength(0)
        }

        for (rawPara in paragraphs) {
            val para = rawPara.trim()
            if (para.isEmpty()) continue

            val isMajorHeading = para.startsWith("# ") || para.startsWith("## ")
            if (isMajorHeading && currentBuilder.length >= targetCharsPerPage * 0.45f) {
                flushPage()
            }

            if (currentBuilder.isNotEmpty() && currentBuilder.length + para.length + 2 > targetCharsPerPage) {
                if (para.length <= targetCharsPerPage) {
                    flushPage()
                    currentBuilder.append(para)
                    continue
                }
            }

            if (para.length > targetCharsPerPage) {
                // Split very long paragraph along sentence or word boundaries
                var remaining = para
                while (remaining.isNotEmpty()) {
                    val available = (targetCharsPerPage - currentBuilder.length - if (currentBuilder.isEmpty()) 0 else 2)
                        .coerceAtLeast(180)
                    if (remaining.length <= available) {
                        if (currentBuilder.isNotEmpty()) currentBuilder.append("\n\n")
                        currentBuilder.append(remaining)
                        remaining = ""
                    } else {
                        val slice = remaining.substring(0, available)
                        val breakIdx = slice.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                            .takeIf { it >= available / 2 }
                            ?: slice.lastIndexOf(' ').takeIf { it >= available / 3 }
                            ?: available
                        val chunk = remaining.substring(0, breakIdx + 1).trim()
                        if (chunk.isNotEmpty()) {
                            if (currentBuilder.isNotEmpty()) currentBuilder.append("\n\n")
                            currentBuilder.append(chunk)
                        }
                        flushPage()
                        remaining = remaining.substring((breakIdx + 1).coerceAtMost(remaining.length)).trimStart()
                    }
                }
            } else {
                if (currentBuilder.isNotEmpty()) currentBuilder.append("\n\n")
                currentBuilder.append(para)
                if (currentBuilder.length >= targetCharsPerPage) {
                    flushPage()
                }
            }
        }
        flushPage()
        return if (pages.isEmpty()) listOf(normalized) else pages
    }
}
