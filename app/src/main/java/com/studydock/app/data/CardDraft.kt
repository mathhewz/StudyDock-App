package com.studydock.app.data

internal enum class CardFormat(val label: String) {
    BASIC("Básico"), REVERSE("Nos dois sentidos"), CLOZE("Lacunas")
}

internal data class ClozeDeletionMatch(
    val start: Int,
    val endExclusive: Int,
    val number: String,
    val answer: String,
    val hint: String
)

private fun splitClozeBody(body: String): Pair<String, String> {
    var depth = 0
    var i = 0
    while (i < body.length) {
        val ch = body[i]
        if (ch == '\\' && i + 1 < body.length) {
            i += 2
            continue
        }
        when (ch) {
            '{' -> depth++
            '}' -> if (depth > 0) depth--
            ':' -> if (depth == 0 && i + 1 < body.length && body[i + 1] == ':') {
                return body.substring(0, i) to body.substring(i + 2)
            }
        }
        i++
    }
    return body to ""
}

/**
 * Scans [text] for `{{cN::answer}}` / `{{cN::answer::hint}}` deletions while allowing
 * arbitrarily nested `{...}` (such as LaTeX commands) and escaped braces `\{` / `\}` inside.
 */
internal fun findClozeMatches(text: String): List<ClozeDeletionMatch> {
    if (!text.contains("{{c")) return emptyList()
    val matches = mutableListOf<ClozeDeletionMatch>()
    var i = 0
    while (i < text.length) {
        if (!text.startsWith("{{c", i)) {
            i++
            continue
        }
        val numStart = i + 3
        if (numStart >= text.length || text[numStart] !in '1'..'9') {
            i += 2
            continue
        }
        var numEnd = numStart + 1
        while (numEnd < text.length && text[numEnd].isDigit()) numEnd++
        if (!text.startsWith("::", numEnd)) {
            i = numEnd
            continue
        }
        val number = text.substring(numStart, numEnd)
        val bodyStart = numEnd + 2
        var j = bodyStart
        var depth = 0
        var closedAt = -1
        while (j < text.length) {
            val ch = text[j]
            if (ch == '\\' && j + 1 < text.length) {
                j += 2
                continue
            }
            if (depth == 0 && ch == '}' && j + 1 < text.length && text[j + 1] == '}') {
                closedAt = j + 2
                break
            }
            when (ch) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
            }
            j++
        }
        if (closedAt == -1) {
            i += 2
            continue
        }
        val body = text.substring(bodyStart, closedAt - 2)
        val (answer, hint) = splitClozeBody(body)
        matches.add(ClozeDeletionMatch(i, closedAt, number, answer, hint))
        i = closedAt
    }
    return matches
}

private fun replaceClozes(
    text: String,
    matches: List<ClozeDeletionMatch>,
    transform: (ClozeDeletionMatch) -> String
): String {
    if (matches.isEmpty()) return text
    return buildString(text.length) {
        var cursor = 0
        for (match in matches) {
            append(text, cursor, match.start)
            append(transform(match))
            cursor = match.endExclusive
        }
        append(text, cursor, text.length)
    }
}

internal fun generateCards(format: CardFormat, front: String, back: String): List<Pair<String, String>> {
    val question = front.trim()
    val answer = back.trim()
    if (question.isEmpty()) return emptyList()
    if (format != CardFormat.CLOZE) {
        if (answer.isEmpty()) return emptyList()
        val pair = question to answer
        return if (format == CardFormat.REVERSE) listOf(pair, answer to question).distinct() else listOf(pair)
    }
    val groups = findClozeMatches(question)
    if (groups.isEmpty() || groups.any { it.answer.isBlank() }) return emptyList()
    val fullText = replaceClozes(question, groups) { it.answer }
    val solution = listOf(fullText, answer).filter { it.isNotEmpty() }.joinToString("\n\n")
    return groups.map { it.number }.distinct().map { number ->
        val prompt = replaceClozes(question, groups) { match ->
            if (match.number == number) "[" + match.hint.ifBlank { "…" } + "]"
            else match.answer
        }
        prompt to solution
    }
}

internal fun clozeNumbers(prompt: String): List<String> =
    findClozeMatches(prompt).map { it.number }.distinct()
