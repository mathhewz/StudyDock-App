package com.studydock.app.data

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

// ---------- Subtarefas ----------

data class TaskSubtask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val done: Boolean = false
)

object TaskSubtasks {

    fun parse(json: String?): List<TaskSubtask> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { SubtaskJsonParser(json).parseArray() }.getOrDefault(emptyList())
    }

    fun encode(subtasks: List<TaskSubtask>): String {
        val clean = subtasks.filter { it.title.isNotBlank() }
        if (clean.isEmpty()) return ""
        return buildString {
            append('[')
            clean.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append("{\"id\":\"")
                appendJsonEscaped(item.id.ifBlank { "sub-$index" })
                append("\",\"title\":\"")
                appendJsonEscaped(item.title.trim())
                append("\",\"done\":")
                append(item.done)
                append('}')
            }
            append(']')
        }
    }

    fun summary(json: String?): Pair<Int, Int> = summary(parse(json))

    fun summary(subtasks: List<TaskSubtask>): Pair<Int, Int> =
        subtasks.count { it.done } to subtasks.size

    fun resetAll(json: String?): String {
        val list = parse(json)
        if (list.isEmpty()) return ""
        return encode(list.map { it.copy(done = false) })
    }

    fun toggle(json: String?, subtaskId: String): String {
        val list = parse(json)
        if (list.isEmpty()) return ""
        return encode(list.map { if (it.id == subtaskId) it.copy(done = !it.done) else it })
    }

    fun fullStudyCycleTemplate(): List<TaskSubtask> = listOf(
        TaskSubtask(title = "Estudar teoria"),
        TaskSubtask(title = "Fazer resumo"),
        TaskSubtask(title = "Resolver questões"),
        TaskSubtask(title = "Revisar erros")
    )
}

private fun StringBuilder.appendJsonEscaped(value: String) {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
}

private class SubtaskJsonParser(private val src: String) {
    private var pos = 0

    fun parseArray(): List<TaskSubtask> {
        skipWs()
        if (peek() != '[') return emptyList()
        pos++
        val result = mutableListOf<TaskSubtask>()
        skipWs()
        if (peek() == ']') {
            pos++
            return result
        }
        var index = 0
        while (pos < src.length) {
            skipWs()
            if (peek() == '{') {
                parseObject(index)?.let { result.add(it) }
                index++
            } else {
                skipValue()
            }
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return result
                }
                else -> return result
            }
        }
        return result
    }

    private fun parseObject(index: Int): TaskSubtask? {
        if (peek() != '{') return null
        pos++
        var id = ""
        var title = ""
        var done = false
        skipWs()
        if (peek() == '}') {
            pos++
            return null
        }
        while (pos < src.length) {
            skipWs()
            if (peek() != '"') break
            val key = readString() ?: break
            skipWs()
            if (peek() != ':') break
            pos++
            skipWs()
            when (key) {
                "id" -> id = readString().orEmpty()
                "title" -> title = readString().orEmpty()
                "done" -> done = readBoolean() ?: false
                else -> skipValue()
            }
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    break
                }
                else -> break
            }
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null
        return TaskSubtask(
            id = id.ifBlank { "sub-$index" },
            title = cleanTitle,
            done = done
        )
    }

    private fun readString(): String? {
        if (peek() != '"') {
            skipValue()
            return null
        }
        pos++
        val sb = StringBuilder()
        while (pos < src.length) {
            val ch = src[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) return sb.toString()
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 <= src.length) {
                                val hex = src.substring(pos, pos + 4)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                pos += 4
                            }
                        }
                        else -> sb.append(esc)
                    }
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun readBoolean(): Boolean? {
        return when {
            src.startsWith("true", pos) -> {
                pos += 4
                true
            }
            src.startsWith("false", pos) -> {
                pos += 5
                false
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun skipValue() {
        skipWs()
        when (peek()) {
            '"' -> readString()
            '{' -> {
                pos++
                var depth = 1
                while (pos < src.length && depth > 0) {
                    when (src[pos]) {
                        '"' -> readString()
                        '{' -> {
                            depth++
                            pos++
                        }
                        '}' -> {
                            depth--
                            pos++
                        }
                        else -> pos++
                    }
                }
            }
            '[' -> {
                pos++
                var depth = 1
                while (pos < src.length && depth > 0) {
                    when (src[pos]) {
                        '"' -> readString()
                        '[' -> {
                            depth++
                            pos++
                        }
                        ']' -> {
                            depth--
                            pos++
                        }
                        else -> pos++
                    }
                }
            }
            else -> {
                while (pos < src.length && src[pos] !in charArrayOf(',', '}', ']', ' ', '\t', '\n', '\r')) {
                    pos++
                }
            }
        }
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char? = if (pos < src.length) src[pos] else null
}

// ---------- Normalização & Tags ----------

internal fun normalizeTaskToken(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)

object TaskTagPresets {
    const val TEORIA = "@teoria"
    const val QUESTOES = "@questoes"
    const val REVISAO = "@revisao"
    const val LEITURA = "@leitura"
    const val SIMULADO = "@simulado"
    const val RESUMO = "@resumo"

    val all: List<String> = listOf(TEORIA, QUESTOES, REVISAO, LEITURA, SIMULADO, RESUMO)

    fun label(tag: String): String = when (normalizeTag(tag)) {
        TEORIA -> "Teoria"
        QUESTOES -> "Questões"
        REVISAO -> "Revisão"
        LEITURA -> "Leitura"
        SIMULADO -> "Simulado"
        RESUMO -> "Resumo"
        else -> normalizeTag(tag).removePrefix("@").replaceFirstChar { it.uppercase() }
    }

    fun normalizeTag(raw: String): String {
        val cleaned = normalizeTaskToken(raw)
            .trim { it.isWhitespace() || it == ',' || it == ';' }
            .trimStart('@', '#')
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        return if (cleaned.isEmpty()) "" else "@$cleaned"
    }

    fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(Regex("[\\s,;]+"))
            .map { normalizeTag(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun formatTags(tags: Iterable<String>): String =
        tags.map { normalizeTag(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")

    fun toggleTag(raw: String, tag: String): String {
        val normalized = normalizeTag(tag)
        if (normalized.isEmpty()) return formatTags(parseTags(raw))
        val list = parseTags(raw).toMutableList()
        if (normalized in list) list.remove(normalized) else list.add(normalized)
        return formatTags(list)
    }

    fun hasTag(task: StudyTask, tag: String): Boolean {
        val normalized = normalizeTag(tag)
        return normalized.isNotEmpty() && normalized in parseTags(task.tags)
    }

    fun isQuestionTask(task: StudyTask): Boolean = com.studydock.app.data.isQuestionTask(task)

    fun isReadingTask(task: StudyTask): Boolean = com.studydock.app.data.isReadingTask(task)
}

private val questionKeywordRegex =
    Regex("\\b(quest(?:ao|oes)|simulado(?:s)?|exercicio(?:s)?|lista(?:s)?|caderno de erros)\\b")

private val readingKeywordRegex =
    Regex("\\b(ler|leitura|capitulo(?:s)?|cap\\.?|pagina(?:s)?|livro(?:s)?|artigo(?:s)?)\\b")

fun isQuestionTask(task: StudyTask): Boolean {
    val tags = TaskTagPresets.parseTags(task.tags)
    if (TaskTagPresets.QUESTOES in tags || TaskTagPresets.SIMULADO in tags || "@exercicios" in tags) {
        return true
    }
    val text = normalizeTaskToken("${task.title} ${task.details}")
    return questionKeywordRegex.containsMatchIn(text)
}

fun isReadingTask(task: StudyTask): Boolean {
    if (task.bookId != null) return true
    val tags = TaskTagPresets.parseTags(task.tags)
    if (TaskTagPresets.LEITURA in tags) return true
    val text = normalizeTaskToken("${task.title} ${task.details}")
    return readingKeywordRegex.containsMatchIn(text)
}

// ---------- Datas & Recorrência ----------

fun noonTimestamp(
    baseMillis: Long = System.currentTimeMillis(),
    offsetDays: Int = 0,
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    val date = Instant.ofEpochMilli(baseMillis).atZone(zone).toLocalDate().plusDays(offsetDays.toLong())
    return date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
}

fun taskRecurrenceLabel(recurrence: String, afterCompletion: Boolean = false): String {
    val base = when {
        recurrence == "DAILY" -> "Todo dia"
        recurrence == "WEEKDAYS" -> "Seg–Sex"
        recurrence == "WEEKLY" -> "Toda semana"
        recurrence == "MONTHLY" || recurrence.startsWith("MONTHLY:") -> "Todo mês"
        recurrence.startsWith("EVERY_N_DAYS:") -> {
            val n = recurrence.substringAfter("EVERY_N_DAYS:").trim().toIntOrNull() ?: 1
            if (n <= 1) "Todo dia" else "A cada $n dias"
        }
        else -> return "Não repete"
    }
    return if (afterCompletion) "$base · após concluir" else base
}

fun nextTaskOccurrence(
    task: StudyTask,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): Long? {
    val rec = task.recurrence.trim()
    if (rec.isEmpty() || rec == "NONE") return null

    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dueZoned = Instant.ofEpochMilli(task.dueAt).atZone(zone)
    val timeOfDay = if (task.hasDueDate) dueZoned.toLocalTime() else LocalTime.of(12, 0)
    val baseDate = if (task.afterCompletion || !task.hasDueDate) today else dueZoned.toLocalDate()

    val nextDate: LocalDate = when {
        rec == "DAILY" -> {
            val candidate = baseDate.plusDays(1)
            if (candidate.isAfter(today)) candidate else today.plusDays(1)
        }
        rec == "WEEKDAYS" -> {
            var candidate = if (baseDate.isBefore(today)) today.plusDays(1) else baseDate.plusDays(1)
            while (
                candidate.dayOfWeek == DayOfWeek.SATURDAY ||
                candidate.dayOfWeek == DayOfWeek.SUNDAY ||
                !candidate.isAfter(today)
            ) {
                candidate = candidate.plusDays(1)
            }
            candidate
        }
        rec == "WEEKLY" -> {
            if (!baseDate.isAfter(today)) {
                val days = ChronoUnit.DAYS.between(baseDate, today)
                val steps = days / 7L + 1L
                baseDate.plusDays(steps * 7L)
            } else {
                baseDate.plusDays(7L)
            }
        }
        rec == "MONTHLY" || rec.startsWith("MONTHLY:") -> {
            val preferredDay = rec.substringAfter("MONTHLY:", "").trim().toIntOrNull()?.coerceIn(1, 31)
                ?: baseDate.dayOfMonth
            var months = if (!baseDate.isAfter(today)) {
                ChronoUnit.MONTHS.between(baseDate.withDayOfMonth(1), today.withDayOfMonth(1)).coerceAtLeast(0L)
            } else {
                1L
            }
            fun candidateFor(m: Long): LocalDate {
                val ym = java.time.YearMonth.from(baseDate.plusMonths(m))
                return ym.atDay(preferredDay.coerceAtMost(ym.lengthOfMonth()))
            }
            while (!candidateFor(months).isAfter(today)) {
                months++
            }
            candidateFor(months)
        }
        rec.startsWith("EVERY_N_DAYS:") -> {
            val n = rec.substringAfter("EVERY_N_DAYS:").trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
            if (!baseDate.isAfter(today)) {
                val days = ChronoUnit.DAYS.between(baseDate, today)
                val steps = days / n.toLong() + 1L
                baseDate.plusDays(steps * n.toLong())
            } else {
                baseDate.plusDays(n.toLong())
            }
        }
        else -> return null
    }

    return nextDate.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
}

// ---------- Parser Rápido (NLP) ----------

data class TaskQuickParseResult(
    val title: String,
    val dueAt: Long?,
    val hasDueDate: Boolean = true,
    val priority: Int? = null,
    val subjectId: Long? = null,
    val recurrence: String? = null,
    val afterCompletion: Boolean = false,
    val estimatedMinutes: Int = 0,
    val tags: List<String> = emptyList(),
    val bookId: Long? = null
) {
    fun toStudyTask(
        defaultSubject: Long? = null,
        noteId: Long? = null,
        defaultHasDueDate: Boolean = true,
        now: Long = System.currentTimeMillis()
    ): StudyTask {
        val effectiveHasDueDate = when {
            dueAt != null -> true
            recurrence != null && recurrence != "NONE" -> true
            !hasDueDate -> false
            else -> defaultHasDueDate
        }
        return StudyTask(
            subjectId = subjectId ?: defaultSubject,
            title = title,
            dueAt = dueAt ?: noonTimestamp(now, 0),
            hasDueDate = effectiveHasDueDate,
            priority = priority ?: 4,
            recurrence = recurrence ?: "NONE",
            noteId = noteId,
            estimatedMinutes = estimatedMinutes,
            tags = TaskTagPresets.formatTags(tags),
            bookId = bookId,
            afterCompletion = afterCompletion
        )
    }
}

object TaskQuickParser {

    private const val END_BOUND = "(?=\\s|[.,;:!?)])"
    private val priorityRegex = Regex("(?i)(?<=\\s|[(])(?:p|!)([1-4])$END_BOUND")
    private val subjectRegex = Regex("(?<=\\s)#([\\p{L}\\p{N}_-]+)$END_BOUND")
    private val tagRegex = Regex("(?<=\\s)@([\\p{L}\\p{N}_-]+)$END_BOUND")
    private val hoursMinutesRegex = Regex("(?i)(?<=\\s|[(])~(\\d{1,2})h(?:(\\d{1,2})(?:m(?:in)?)?)?$END_BOUND")
    private val minutesRegex = Regex("(?i)(?<=\\s|[(])~(\\d{1,4})m(?:in)?$END_BOUND")
    private val pomodorosRegex = Regex("(?i)(?<=\\s|[(])(\\d{1,2})p(?:om(?:odoros?)?)?$END_BOUND")
    private val explicitTimeRegex = Regex("(?i)(?<=\\s)(?:[àa]s\\s+)([01]?\\d|2[0-3])(?:[:h]([0-5]\\d))?h?$END_BOUND")
    private val colonTimeRegex = Regex("(?i)(?<=\\s)([01]?\\d|2[0-3]):([0-5]\\d)$END_BOUND")
    private val afterCompletionRegex = Regex("(?i)(?<=\\s)(?:ap[oó]s|depois\\s+de)\\s+concluir$END_BOUND")
    private val everyNDaysRegex = Regex("(?i)(?<=\\s)a\\s+cada\\s+(\\d{1,3})\\s+dias?$END_BOUND")
    private val weekdaysRegex = Regex("(?i)(?<=\\s)(?:(?:nos\\s+)?dias\\s+[uú]teis|(?:de\\s+)?seg(?:unda)?\\s*(?:a|-)\\s*sex(?:ta)?)$END_BOUND")
    private val dailyRegex = Regex("(?i)(?<=\\s)(?:todos\\s+os\\s+dias|todo\\s+dia|diariamente)$END_BOUND")
    private val weeklyRegex = Regex("(?i)(?<=\\s)(?:toda\\s+semana|semanalmente)$END_BOUND")
    private val monthlyRegex = Regex("(?i)(?<=\\s)(?:todo\\s+m[eê]s|mensalmente)$END_BOUND")
    private val inboxRegex = Regex("(?i)(?<=\\s)(?:sem\\s+data|inbox|algum\\s+dia)$END_BOUND")
    private val dayAfterTomorrowRegex = Regex("(?i)(?<=\\s)depois\\s+de\\s+amanh[ãa]$END_BOUND")
    private val tomorrowRegex = Regex("(?i)(?<=\\s)amanh[ãa]$END_BOUND")
    private val todayRegex = Regex("(?i)(?<=\\s)hoje$END_BOUND")
    private val nextWeekRegex = Regex("(?i)(?<=\\s)(?:semana\\s+que\\s+vem|pr[óo]xima\\s+semana)$END_BOUND")
    private val inNDaysRegex = Regex("(?i)(?<=\\s)(?:em|daqui\\s+a)\\s+(\\d{1,3})\\s+dias?$END_BOUND")
    private val slashDateRegex = Regex("(?<=\\s)(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$END_BOUND")
    private val weekdayRegex = Regex(
        "(?i)(?<=\\s)(?:(?:no|na|nesta|neste)\\s+)?(toda\\s+|todo\\s+)?(segunda|ter[çc]a|quarta|quinta|sexta|s[áa]bado|domingo)(?:-feira)?$END_BOUND"
    )

    private fun stripRange(source: String, range: IntRange): String =
        source.replaceRange(range.first, range.last + 1, " ")

    private fun normalizeSubjectKey(name: String): String =
        normalizeTaskToken(name).replace(Regex("[\\s_-]+"), "")

    fun parse(
        input: String,
        subjects: List<Subject>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): TaskQuickParseResult {
        var text = " " + input.trim() + " "
        var priority: Int? = null
        var subjectId: Long? = null
        var recurrence: String? = null
        var afterCompletion = false
        var estimatedMinutes = 0
        var hasDueDate = true
        var due: Long? = null
        var parsedTime: LocalTime? = null
        val tags = mutableListOf<String>()

        priorityRegex.find(text)?.let { m ->
            priority = m.groupValues[1].toInt()
            text = stripRange(text, m.range)
        }

        // Match first #token that maps to a known Subject
        for (m in subjectRegex.findAll(text).toList()) {
            val token = normalizeSubjectKey(m.groupValues[1])
            val match = subjects.firstOrNull { normalizeSubjectKey(it.name) == token }
                ?: subjects.firstOrNull { normalizeSubjectKey(it.name).startsWith(token) }
            if (match != null) {
                subjectId = match.id
                text = stripRange(text, m.range)
                break
            }
        }

        // Collect all @tags from right to left so indices stay valid
        val tagMatches = tagRegex.findAll(text).toList()
        if (tagMatches.isNotEmpty()) {
            tagMatches.forEach { m ->
                val normalized = TaskTagPresets.normalizeTag("@" + m.groupValues[1])
                if (normalized.isNotEmpty() && normalized !in tags) tags.add(normalized)
            }
            for (m in tagMatches.asReversed()) {
                text = stripRange(text, m.range)
            }
        }

        // Time estimates (~1h30m, ~30m, 2p)
        hoursMinutesRegex.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val mins = m.groupValues[2].toIntOrNull() ?: 0
            val total = h * 60 + mins
            if (total > 0) {
                estimatedMinutes = total.coerceIn(1, 24 * 60)
                text = stripRange(text, m.range)
            }
        }
        if (estimatedMinutes == 0) {
            minutesRegex.find(text)?.let { m ->
                val mins = m.groupValues[1].toIntOrNull() ?: 0
                if (mins > 0) {
                    estimatedMinutes = mins.coerceIn(1, 24 * 60)
                    text = stripRange(text, m.range)
                }
            }
        }
        if (estimatedMinutes == 0) {
            pomodorosRegex.find(text)?.let { m ->
                val count = m.groupValues[1].toIntOrNull() ?: 0
                if (count in 1..20) {
                    estimatedMinutes = count * 25
                    text = stripRange(text, m.range)
                }
            }
        }

        explicitTimeRegex.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 12
            val min = m.groupValues[2].toIntOrNull() ?: 0
            parsedTime = LocalTime.of(h.coerceIn(0, 23), min.coerceIn(0, 59))
            text = stripRange(text, m.range)
        }
        if (parsedTime == null) {
            colonTimeRegex.find(text)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 12
                val min = m.groupValues[2].toIntOrNull() ?: 0
                parsedTime = LocalTime.of(h.coerceIn(0, 23), min.coerceIn(0, 59))
                text = stripRange(text, m.range)
            }
        }

        afterCompletionRegex.find(text)?.let { m ->
            afterCompletion = true
            text = stripRange(text, m.range)
        }

        // Recurrence rules (specific patterns before general)
        everyNDaysRegex.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            if (n >= 1) {
                recurrence = if (n == 1) "DAILY" else "EVERY_N_DAYS:$n"
                text = stripRange(text, m.range)
            }
        }
        if (recurrence == null) {
            weekdaysRegex.find(text)?.let { m ->
                recurrence = "WEEKDAYS"
                text = stripRange(text, m.range)
            }
        }
        if (recurrence == null) {
            for ((rule, kind) in listOf(
                dailyRegex to "DAILY",
                weeklyRegex to "WEEKLY",
                monthlyRegex to "MONTHLY"
            )) {
                rule.find(text)?.let { m ->
                    recurrence = kind
                    text = stripRange(text, m.range)
                }
                if (recurrence != null) break
            }
        }

        // Dates
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        dayAfterTomorrowRegex.find(text)?.let { m ->
            due = noonTimestamp(now, 2, zone)
            text = stripRange(text, m.range)
        }
        if (due == null) {
            tomorrowRegex.find(text)?.let { m ->
                due = noonTimestamp(now, 1, zone)
                text = stripRange(text, m.range)
            }
        }
        if (due == null) {
            todayRegex.find(text)?.let { m ->
                due = noonTimestamp(now, 0, zone)
                text = stripRange(text, m.range)
            }
        }
        if (due == null) {
            nextWeekRegex.find(text)?.let { m ->
                due = noonTimestamp(now, 7, zone)
                text = stripRange(text, m.range)
            }
        }
        if (due == null) {
            inNDaysRegex.find(text)?.let { m ->
                val offset = m.groupValues[1].toIntOrNull() ?: 0
                due = noonTimestamp(now, offset, zone)
                text = stripRange(text, m.range)
            }
        }
        if (due == null) {
            slashDateRegex.find(text)?.let { m ->
                val day = m.groupValues[1].toIntOrNull()
                val month = m.groupValues[2].toIntOrNull()
                val yearRaw = m.groupValues[3]
                if (day != null && month != null) {
                    val year = if (yearRaw.isNotEmpty()) {
                        yearRaw.toInt().let { y -> if (y < 100) 2000 + y else y }
                    } else {
                        today.year
                    }
                    var parsedDate = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                    if (parsedDate != null) {
                        if (yearRaw.isEmpty() && parsedDate.isBefore(today)) {
                            parsedDate = runCatching { LocalDate.of(year + 1, month, day) }.getOrNull() ?: parsedDate
                        }
                        due = parsedDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                        text = stripRange(text, m.range)
                    }
                }
            }
        }
        if (due == null) {
            weekdayRegex.find(text)?.let { m ->
                val targetDow = when (normalizeTaskToken(m.groupValues[2])) {
                    "segunda" -> DayOfWeek.MONDAY
                    "terca" -> DayOfWeek.TUESDAY
                    "quarta" -> DayOfWeek.WEDNESDAY
                    "quinta" -> DayOfWeek.THURSDAY
                    "sexta" -> DayOfWeek.FRIDAY
                    "sabado" -> DayOfWeek.SATURDAY
                    "domingo" -> DayOfWeek.SUNDAY
                    else -> null
                }
                if (targetDow != null) {
                    var delta = (targetDow.value - today.dayOfWeek.value + 7) % 7
                    if (delta == 0) delta = 7
                    due = noonTimestamp(now, delta, zone)
                    if (m.groupValues[1].isNotBlank() && recurrence == null) {
                        recurrence = "WEEKLY"
                    }
                    text = stripRange(text, m.range)
                }
            }
        }

        // Inbox keywords (only mark undated if no explicit date was matched)
        if (due == null) {
            inboxRegex.find(text)?.let { m ->
                hasDueDate = false
                text = stripRange(text, m.range)
            }
        }

        // If user asked for WEEKDAYS without an explicit date and today is weekend, start on Monday
        if (due == null && hasDueDate && recurrence == "WEEKDAYS") {
            val dow = today.dayOfWeek
            if (dow == DayOfWeek.SATURDAY) due = noonTimestamp(now, 2, zone)
            else if (dow == DayOfWeek.SUNDAY) due = noonTimestamp(now, 1, zone)
        }

        parsedTime?.let { timeOfDay ->
            val baseMillis = due
            if (baseMillis != null) {
                val datePart = Instant.ofEpochMilli(baseMillis).atZone(zone).toLocalDate()
                due = datePart.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
            } else if (hasDueDate) {
                val candidate = today.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
                due = if (candidate >= now) candidate else today.plusDays(1).atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
            }
        }

        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        return TaskQuickParseResult(
            title = cleaned.ifBlank { input.trim() },
            dueAt = due,
            hasDueDate = hasDueDate,
            priority = priority,
            subjectId = subjectId,
            recurrence = recurrence,
            afterCompletion = afterCompletion,
            estimatedMinutes = estimatedMinutes,
            tags = tags
        )
    }
}

// ---------- Reagendamento Inteligente ----------

fun computeBalancedReschedule(
    overdue: List<StudyTask>,
    allOpen: List<StudyTask>,
    dailyTargetMinutes: Int,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): Map<Long, Long> {
    val candidates = overdue.filter { it.id != 0L }
    if (candidates.isEmpty()) return emptyMap()

    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val capacity = (if (dailyTargetMinutes > 0) dailyTargetMinutes else 180).coerceAtLeast(30)
    val horizonDays = 14
    val dayLoads = IntArray(horizonDays)

    val overdueIds = candidates.mapTo(HashSet()) { it.id }
    fun taskWeight(task: StudyTask): Int =
        if (task.estimatedMinutes > 0) task.estimatedMinutes else 25

    for (task in allOpen) {
        if (task.completed || !task.hasDueDate || task.id in overdueIds) continue
        val dueDate = Instant.ofEpochMilli(task.dueAt).atZone(zone).toLocalDate()
        val offset = ChronoUnit.DAYS.between(today, dueDate).toInt()
        if (offset in 0 until horizonDays) {
            dayLoads[offset] += taskWeight(task)
        }
    }

    val sortedOverdue = candidates.sortedWith(
        compareBy<StudyTask>({ it.priority }, { it.dueAt }, { it.createdAt })
    )

    val result = LinkedHashMap<Long, Long>(sortedOverdue.size)
    for (task in sortedOverdue) {
        val weight = taskWeight(task)
        var chosenOffset = -1
        for (offset in 0 until horizonDays) {
            if (dayLoads[offset] + weight <= capacity || dayLoads[offset] == 0) {
                chosenOffset = offset
                break
            }
        }
        if (chosenOffset < 0) {
            // Fallback to the lightest day within the next 7 days
            chosenOffset = (0 until minOf(7, horizonDays)).minByOrNull { dayLoads[it] } ?: 0
        }
        dayLoads[chosenOffset] += weight
        result[task.id] = noonTimestamp(now, chosenOffset, zone)
    }
    return result
}

// ---------- Sincronização com Checklists Markdown ----------

data class NoteTaskLine(
    val lineIndex: Int,
    val checked: Boolean,
    val text: String
)

object NoteChecklists {

    private val checklistLineRegex = Regex("^(\\s*[-*]\\s+\\[)([ xX])(\\]\\s+)(.+)$")
    private val fenceRegex = Regex("^\\s*(?:```|~~~).*")

    fun extract(markdown: String): List<NoteTaskLine> {
        if (markdown.isEmpty()) return emptyList()
        val lines = markdown.split('\n')
        val result = mutableListOf<NoteTaskLine>()
        var inFrontmatter = false
        var inFence = false

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trim()
            if (index == 0 && trimmed == "---") {
                inFrontmatter = true
                return@forEachIndexed
            }
            if (inFrontmatter) {
                if (trimmed == "---") inFrontmatter = false
                return@forEachIndexed
            }
            if (fenceRegex.matches(line)) {
                inFence = !inFence
                return@forEachIndexed
            }
            if (inFence) return@forEachIndexed

            checklistLineRegex.matchEntire(line)?.let { m ->
                val text = m.groupValues[4].trim()
                if (text.isNotEmpty()) {
                    result.add(
                        NoteTaskLine(
                            lineIndex = index,
                            checked = m.groupValues[2].equals("x", ignoreCase = true),
                            text = text
                        )
                    )
                }
            }
        }
        return result
    }

    fun setLineChecked(markdown: String, lineIndex: Int, checked: Boolean): String {
        val lines = markdown.split('\n').toMutableList()
        if (lineIndex !in lines.indices) return markdown
        val raw = lines[lineIndex]
        val hasCr = raw.endsWith('\r')
        val clean = raw.trimEnd('\r')
        val match = checklistLineRegex.matchEntire(clean) ?: return markdown
        val mark = if (checked) "x" else " "
        val updated = "${match.groupValues[1]}$mark${match.groupValues[3]}${match.groupValues[4]}"
        lines[lineIndex] = if (hasCr) "$updated\r" else updated
        return lines.joinToString("\n")
    }

    fun toggleLine(markdown: String, lineIndex: Int): String {
        val current = extract(markdown).firstOrNull { it.lineIndex == lineIndex } ?: return markdown
        return setLineChecked(markdown, lineIndex, !current.checked)
    }

    fun syncTaskCompletion(
        markdown: String,
        taskTitle: String,
        completed: Boolean,
        subjects: List<Subject> = emptyList()
    ): String {
        val targetNorm = normalizeTaskToken(taskTitle.trim())
        if (targetNorm.isEmpty()) return markdown
        val match = extract(markdown).firstOrNull { line ->
            val rawNorm = normalizeTaskToken(line.text)
            val parsedNorm = normalizeTaskToken(TaskQuickParser.parse(line.text, subjects).title)
            rawNorm == targetNorm || parsedNorm == targetNorm
        } ?: return markdown
        if (match.checked == completed) return markdown
        return setLineChecked(markdown, match.lineIndex, completed)
    }

    fun uncoveredLines(
        markdown: String,
        linkedTasks: List<StudyTask>,
        subjects: List<Subject> = emptyList()
    ): List<NoteTaskLine> {
        if (linkedTasks.isEmpty()) return extract(markdown)
        val taskTitles = linkedTasks.map { normalizeTaskToken(it.title.trim()) }.toSet()
        return extract(markdown).filter { line ->
            val rawNorm = normalizeTaskToken(line.text)
            val parsedNorm = normalizeTaskToken(TaskQuickParser.parse(line.text, subjects).title)
            rawNorm !in taskTitles && parsedNorm !in taskTitles
        }
    }
}

enum class MentalBattery(val label: String, val emoji: String) {
    HIGH("Alta Voltagem", "🔴"),
    MEDIUM("Fluxo Mecânico", "🟡"),
    LOW("Bateria Fraca", "🟢")
}

object TodoistProEngine {

    fun inferMentalBattery(task: StudyTask): MentalBattery {
        val lowerTags = task.tags.lowercase()
        val lowerTitle = task.title.lowercase()
        return when {
            "@alta" in lowerTags || "lista" in lowerTitle || "prova" in lowerTitle || "simulado" in lowerTitle || task.priority == 1 -> MentalBattery.HIGH
            "@baixa" in lowerTags || "baixar" in lowerTitle || "organizar" in lowerTitle || "limpar" in lowerTitle || task.priority == 4 && task.estimatedMinutes <= 15 -> MentalBattery.LOW
            else -> MentalBattery.MEDIUM
        }
    }

    /** Filtro booleano estilo Todoist: ex. `(hoje | atrasadas) & p1 & !@leitura` */
    fun matchesBooleanFilter(
        task: StudyTask,
        expression: String,
        subjectsById: Map<Long, String> = emptyMap(),
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val expr = expression.trim()
        if (expr.isEmpty()) return true
        val taskDate = Instant.ofEpochMilli(task.dueAt).atZone(zone).toLocalDate()

        fun evalAtom(rawToken: String): Boolean {
            val token = rawToken.trim().lowercase()
            if (token.isEmpty()) return true
            return when {
                token == "hoje" || token == "today" -> task.hasDueDate && taskDate == today
                token == "atrasadas" || token == "overdue" -> task.hasDueDate && !task.completed && taskDate.isBefore(today)
                token == "inbox" -> !task.hasDueDate
                token.length == 2 && token[0] == 'p' && token[1] in '1'..'4' -> task.priority == token[1].digitToInt()
                token.startsWith("@") -> TaskTagPresets.hasTag(task, token.drop(1))
                token.startsWith("#") -> {
                    val sub = task.subjectId?.let { subjectsById[it] }?.lowercase().orEmpty()
                    sub.contains(token.drop(1))
                }
                else -> task.title.lowercase().contains(token)
            }
        }

        var pos = 0
        fun skipWs() {
            while (pos < expr.length && expr[pos].isWhitespace()) pos++
        }

        lateinit var parseOr: () -> Boolean

        fun parsePrimary(): Boolean {
            skipWs()
            if (pos >= expr.length) return true
            if (expr[pos] == '!') {
                pos++
                return !parsePrimary()
            }
            if (expr[pos] == '(') {
                pos++
                val inner = parseOr()
                skipWs()
                if (pos < expr.length && expr[pos] == ')') pos++
                return inner
            }
            val start = pos
            while (pos < expr.length && expr[pos] !in charArrayOf('&', '|', '(', ')')) pos++
            return evalAtom(expr.substring(start, pos))
        }

        fun parseAnd(): Boolean {
            var left = parsePrimary()
            while (true) {
                skipWs()
                if (pos < expr.length && expr[pos] == '&') {
                    pos++
                    val right = parsePrimary()
                    left = left && right
                } else break
            }
            return left
        }

        parseOr = {
            var left = parseAnd()
            while (true) {
                skipWs()
                if (pos < expr.length && expr[pos] == '|') {
                    pos++
                    val right = parseAnd()
                    left = left || right
                } else break
            }
            left
        }

        return parseOr()
    }

    /**
     * Recalcular Rota do Dia: encaixa tarefas restantes nos intervalos livres até [sleepHour],
     * preservando blocos de [CalendarEvent] e movendo excedentes P3/P4 para amanhã.
     */
    fun recalculateDaySchedule(
        tasks: List<StudyTask>,
        events: List<CalendarEvent>,
        nowMillis: Long = System.currentTimeMillis(),
        sleepHour: Int = 23,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<StudyTask> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val sleepMillis = today.atTime(sleepHour.coerceIn(18, 23), 59).atZone(zone).toInstant().toEpochMilli()
        val tomorrowNoon = today.plusDays(1L).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        val busyMinutes = events
            .count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today } * 60L
        var availableMin = (((sleepMillis - nowMillis) / 60_000L) - busyMinutes).coerceAtLeast(0L)

        val sorted = tasks.filter { !it.completed }.sortedWith(compareBy<StudyTask> { it.priority }.thenBy { it.dueAt })
        var cursorMillis = nowMillis + 5 * 60_000L
        return sorted.map { task ->
            val est = task.estimatedMinutes.coerceAtLeast(15).toLong()
            if (availableMin >= est && cursorMillis + est * 60_000L <= sleepMillis) {
                val scheduledAt = cursorMillis
                cursorMillis += est * 60_000L
                availableMin -= est
                task.copy(dueAt = scheduledAt, hasDueDate = true)
            } else {
                task.copy(dueAt = tomorrowNoon, hasDueDate = true)
            }
        }
    }

    fun computeTodoKarma(tasks: List<StudyTask>): Int {
        val completed = tasks.count { it.completed }
        val highPriorityBonus = tasks.count { it.completed && it.priority <= 2 } * 5
        return completed * 10 + highPriorityBonus
    }
}
