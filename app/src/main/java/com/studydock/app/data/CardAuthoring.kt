package com.studydock.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.Normalizer

data class OcclusionBox(
    val left: Float,   // 0..1 normalized
    val top: Float,    // 0..1 normalized
    val right: Float,  // 0..1 normalized
    val bottom: Float, // 0..1 normalized
    val label: String,
    val id: Int = 0,
    val hideAll: Boolean = true
)

/** Raw authoring fields. Generated flashcards retain their own schedules and IDs. */
@Entity(tableName = "card_author_notes", indices = [androidx.room.Index("imageKey")],
    foreignKeys = [androidx.room.ForeignKey(entity = CardImage::class, parentColumns = ["key"], childColumns = ["imageKey"])])
data class CardAuthorNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val format: String,
    val prompt: String,
    val answer: String,
    val template: String = "",
    val fields: String = "{}",
    val imageKey: String? = null,
    val occlusion: String? = null,
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    @ColumnInfo(defaultValue = "0") val typeAnswer: Boolean = false
)

@Entity(tableName = "card_templates")
data class CardTemplate(@PrimaryKey val name: String, val front: String, val back: String)

data class SharedCardNote(
    val source: CardAuthorNote,
    val keys: Set<String>,
    val tags: String = "",
    val typeAnswer: Boolean = false,
    val flag: Int = 0
)

enum class TypeDiffStatus {
    MATCH,
    WRONG,
    MISSING,
    EXTRA
}

data class TypeDiffChar(
    val ch: Char,
    val status: TypeDiffStatus
)

internal val fieldPlaceholder = Regex("""\{\{(?:type:)?([^{}#\^/:\n]{1,60})\}\}""")
private val templateTokenRegex = Regex("""\{\{(#|/|\^|type:)?([^{}#\^/:\n]{1,60})\}\}""")
private val conditionalBlockRegex = Regex("""\{\{([#^])([^{}#\^/:\n]{1,60})\}\}([\s\S]*?)\{\{/\2\}\}""")

internal fun normalizeNoteTags(raw: String): String =
    raw.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }.distinct().joinToString(" ")

internal fun templateFields(front: String, back: String): List<String> =
    templateTokenRegex.findAll("$front\n$back")
        .map { it.groupValues[2].trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

private fun evaluateTemplateConditionals(pattern: String, values: Map<String, Any?>): String {
    var current = pattern
    repeat(16) {
        var changed = false
        val next = conditionalBlockRegex.replace(current) { match ->
            val kind = match.groupValues[1]
            val fieldName = match.groupValues[2].trim()
            val body = match.groupValues[3]
            // Wait for inner nested blocks of the exact same key to resolve first if present.
            if (body.contains("{{#$fieldName}}") || body.contains("{{^$fieldName}}")) {
                return@replace match.value
            }
            changed = true
            val present = MiniJson.optString(values, fieldName).isNotBlank()
            val keep = if (kind == "#") present else !present
            if (keep) body else ""
        }
        current = next
        if (!changed) return current
    }
    return current
}

internal fun CardAuthorNote.variants(): Map<String, Pair<String, String>> {
    if (format.equals("occlusion", ignoreCase = true)) {
        val boxes = parseOcclusionBoxes(occlusion)
        if (boxes.isEmpty()) return emptyMap()
        val cleanPrompt = prompt.trim()
        val cleanAnswer = answer.trim()
        val usedKeys = HashSet<String>(boxes.size)
        return buildMap {
            boxes.forEachIndexed { idx, box ->
                var boxId = box.id.takeIf { it > 0 } ?: (idx + 1)
                while (!usedKeys.add("c$boxId")) boxId++
                // Never expose box labels on the front so sibling labels cannot spoil the prompt.
                val front = if (cleanPrompt.isNotEmpty()) "$cleanPrompt\n[Oclusão #$boxId]" else "[Oclusão #$boxId]"
                val labelPart = box.label.trim().ifBlank { "Região #$boxId" }
                val back = buildString {
                    append('#').append(boxId).append(": ").append(labelPart)
                    if (cleanAnswer.isNotEmpty()) append("\n\n").append(cleanAnswer)
                }
                put("c$boxId", front to back)
            }
        }
    }
    if (format == "CUSTOM") {
        val values = MiniJson.parseObject(fields)
        val expandedPrompt = evaluateTemplateConditionals(prompt, values)
        val expandedAnswer = evaluateTemplateConditionals(answer, values)
        val allNames = templateFields(prompt, answer)
        val activeNames = templateFields(expandedPrompt, expandedAnswer)
        if (allNames.isEmpty() || activeNames.any { MiniJson.optString(values, it).isBlank() }) return emptyMap()
        val frontHasType = expandedPrompt.contains("{{type:")
        val renderedPrompt = templateTokenRegex.replace(expandedPrompt) { match ->
            val prefix = match.groupValues[1]
            val key = match.groupValues[2].trim()
            if (prefix == "type:") "" else MiniJson.optString(values, key)
        }.trim()
        val front = renderedPrompt.ifBlank { if (frontHasType) "[Digite a resposta]" else "" }
        val back = templateTokenRegex.replace(expandedAnswer) { match ->
            MiniJson.optString(values, match.groupValues[2].trim())
        }.trim()
        return if (front.isBlank() || back.isBlank()) emptyMap() else mapOf("front" to (front to back))
    }
    val kind = CardFormat.valueOf(format)
    val entries = generateCards(kind, prompt, answer)
    val keys = when (kind) {
        CardFormat.BASIC -> listOf("front")
        CardFormat.REVERSE -> listOf("front", "reverse")
        CardFormat.CLOZE -> clozeNumbers(prompt).map { "c$it" }
    }
    return keys.zip(entries).toMap()
}

internal fun CardAuthorNote.contentKey(): CardAuthorNote {
    val cleanOcclusion = occlusion?.takeIf { it.isNotBlank() }
    val cleanTags = normalizeNoteTags(tags)
    if (format != "CUSTOM") {
        return copy(id = 0, template = "", fields = "{}", occlusion = cleanOcclusion, tags = cleanTags)
    }
    val source = MiniJson.parseObject(fields)
    val ordered = LinkedHashMap<String, Any?>()
    templateFields(prompt, answer).forEach { ordered[it] = MiniJson.optString(source, it) }
    return copy(id = 0, fields = MiniJson.stringify(ordered), occlusion = cleanOcclusion, tags = cleanTags)
}

internal fun encodeOcclusionBoxes(boxes: List<OcclusionBox>): String? {
    if (boxes.isEmpty()) return null
    val array = boxes.map { box ->
        linkedMapOf<String, Any?>(
            "l" to box.left.toDouble(),
            "t" to box.top.toDouble(),
            "r" to box.right.toDouble(),
            "b" to box.bottom.toDouble(),
            "label" to box.label,
            "id" to box.id,
            "hideAll" to box.hideAll
        )
    }
    return MiniJson.stringify(array)
}

internal fun parseOcclusionBoxes(raw: String?): List<OcclusionBox> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        MiniJson.parseArray(raw).mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            val obj = entry as? Map<String, Any?> ?: return@mapNotNull null
            val l = MiniJson.optDouble(obj, "l", 0.0).toFloat().coerceIn(0f, 1f)
            val t = MiniJson.optDouble(obj, "t", 0.0).toFloat().coerceIn(0f, 1f)
            val r = MiniJson.optDouble(obj, "r", 0.0).toFloat().coerceIn(l, 1f)
            val b = MiniJson.optDouble(obj, "b", 0.0).toFloat().coerceIn(t, 1f)
            OcclusionBox(
                left = l,
                top = t,
                right = r,
                bottom = b,
                label = MiniJson.optString(obj, "label", ""),
                id = MiniJson.optInt(obj, "id", 0),
                hideAll = MiniJson.optBoolean(obj, "hideAll", true)
            )
        }.filter { it.right - it.left > 0f && it.bottom - it.top > 0f }
    }.getOrDefault(emptyList())
}

private val soundTagRegex = Regex("""\[sound:[^\]]+]""")
private val htmlBreakRegex = Regex("""(?i)<br\s*/?>|</(?:p|div|li|tr|h[1-6])>""")
private val htmlTagRegex = Regex("""<[^>]+>""")
private val whitespaceRegex = Regex("""\s+""")

private fun decodeBasicEntities(text: String): String =
    text.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

internal fun normalizeTypeAnswerText(raw: String, stripClozeWrappers: Boolean): String {
    val withoutCloze = if (stripClozeWrappers) {
        val matches = findClozeMatches(raw)
        if (matches.isEmpty()) raw else buildString(raw.length) {
            var cursor = 0
            for (m in matches) {
                append(raw, cursor, m.start)
                append(m.answer)
                cursor = m.endExclusive
            }
            append(raw, cursor, raw.length)
        }
    } else raw
    val plain = decodeBasicEntities(
        withoutCloze
            .replace(soundTagRegex, " ")
            .replace(htmlBreakRegex, " ")
            .replace(htmlTagRegex, "")
    )
    val nfc = Normalizer.normalize(plain, Normalizer.Form.NFC)
    return nfc.trim().replace(whitespaceRegex, " ")
}

/**
 * Computes a character-level comparison between the cleaned expected answer and user input,
 * following Anki Desktop's type-in-the-answer normalization and diff statuses.
 */
fun evaluateTypeAnswer(expected: String, typed: String): List<TypeDiffChar> {
    val exp = normalizeTypeAnswerText(expected, stripClozeWrappers = true)
    val typ = normalizeTypeAnswerText(typed, stripClozeWrappers = false)
    if (exp.isEmpty() && typ.isEmpty()) return emptyList()

    var prefix = 0
    val minLen = minOf(exp.length, typ.length)
    while (prefix < minLen && exp[prefix] == typ[prefix]) prefix++

    var suffix = 0
    while (suffix < minLen - prefix &&
        exp[exp.length - 1 - suffix] == typ[typ.length - 1 - suffix]
    ) {
        suffix++
    }

    val result = ArrayList<TypeDiffChar>(maxOf(exp.length, typ.length))
    for (k in 0 until prefix) result.add(TypeDiffChar(exp[k], TypeDiffStatus.MATCH))

    val midExp = exp.substring(prefix, exp.length - suffix)
    val midTyp = typ.substring(prefix, typ.length - suffix)

    if (midExp.isEmpty()) {
        midTyp.forEach { result.add(TypeDiffChar(it, TypeDiffStatus.EXTRA)) }
    } else if (midTyp.isEmpty()) {
        midExp.forEach { result.add(TypeDiffChar(it, TypeDiffStatus.MISSING)) }
    } else if (midExp.length * midTyp.length > 250_000) {
        val shared = minOf(midExp.length, midTyp.length)
        for (i in 0 until shared) {
            val status = if (midExp[i] == midTyp[i]) TypeDiffStatus.MATCH else TypeDiffStatus.WRONG
            result.add(TypeDiffChar(midTyp[i], status))
        }
        for (i in shared until midExp.length) result.add(TypeDiffChar(midExp[i], TypeDiffStatus.MISSING))
        for (i in shared until midTyp.length) result.add(TypeDiffChar(midTyp[i], TypeDiffStatus.EXTRA))
    } else {
        val n = midExp.length
        val m = midTyp.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val subCost = dp[i - 1][j - 1] + if (midExp[i - 1] == midTyp[j - 1]) 0 else 1
                val delCost = dp[i - 1][j] + 1
                val insCost = dp[i][j - 1] + 1
                dp[i][j] = minOf(subCost, delCost, insCost)
            }
        }
        val midOps = ArrayList<TypeDiffChar>(n + m)
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && midExp[i - 1] == midTyp[j - 1] && dp[i][j] == dp[i - 1][j - 1] -> {
                    midOps.add(TypeDiffChar(midExp[i - 1], TypeDiffStatus.MATCH))
                    i--
                    j--
                }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    midOps.add(TypeDiffChar(midTyp[j - 1], TypeDiffStatus.WRONG))
                    i--
                    j--
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> {
                    midOps.add(TypeDiffChar(midExp[i - 1], TypeDiffStatus.MISSING))
                    i--
                }
                else -> {
                    midOps.add(TypeDiffChar(midTyp[j - 1], TypeDiffStatus.EXTRA))
                    j--
                }
            }
        }
        for (k in midOps.lastIndex downTo 0) result.add(midOps[k])
    }

    for (k in exp.length - suffix until exp.length) result.add(TypeDiffChar(exp[k], TypeDiffStatus.MATCH))
    return result
}
