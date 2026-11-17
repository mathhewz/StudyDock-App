@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.studydock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CardContent(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current
) {
    val colors = MaterialTheme.colorScheme
    val fallbackSize = LocalTextStyle.current.fontSize.takeIf { it != TextUnit.Unspecified } ?: 16.sp
    val baseFontSize = style.fontSize.takeIf { it != TextUnit.Unspecified } ?: fallbackSize
    val baseColor = style.color.takeIf { it != Color.Unspecified } ?: colors.onSurface
    val resolvedStyle = style.copy(color = baseColor)
    val isDark = colors.surface.luminance() < 0.5f
    val highlightBg = if (isDark) Color(0xFFF59E0B).copy(alpha = 0.28f) else Color(0xFFFEF08A)
    val clozeBg = colors.primary.copy(alpha = 0.16f)

    val blocks = remember(text, colors.primary, colors.surfaceVariant, highlightBg, clozeBg, baseFontSize, baseColor) {
        buildCardRenderBlocks(
            raw = text,
            primaryColor = colors.primary,
            codeBg = colors.surfaceVariant,
            highlightBg = highlightBg,
            clozeBg = clozeBg,
            baseFontSize = baseFontSize,
            baseTextColor = baseColor
        )
    }

    if (blocks.isEmpty()) {
        Text("", modifier = modifier, style = resolvedStyle)
        return
    }

    if (blocks.size == 1 && blocks[0] is CardRenderBlock.TextLine) {
        val single = blocks[0] as CardRenderBlock.TextLine
        Text(text = single.annotated, modifier = modifier, style = resolvedStyle)
        return
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is CardRenderBlock.SpacerLine -> Spacer(Modifier.height(4.dp))
                is CardRenderBlock.FormulaBlock -> {
                    LatexFormulaBlock(
                        latex = block.latex,
                        textStyle = resolvedStyle,
                        color = block.color ?: baseColor
                    )
                }
                is CardRenderBlock.TextLine -> {
                    Text(
                        text = block.annotated,
                        modifier = Modifier.fillMaxWidth(),
                        style = resolvedStyle
                    )
                }
                is CardRenderBlock.MixedLine -> {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        block.runs.forEach { run ->
                            when (run) {
                                is CardMixedRun.TextRun -> {
                                    Text(
                                        text = run.annotated,
                                        style = resolvedStyle,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                }
                                is CardMixedRun.MathRun -> {
                                    if (run.isCloze) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterVertically)
                                                .background(clozeBg, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            LatexInlineFormula(
                                                latex = run.latex,
                                                textStyle = resolvedStyle.copy(fontSize = baseFontSize),
                                                color = run.color ?: colors.primary
                                            )
                                        }
                                    } else {
                                        LatexInlineFormula(
                                            latex = run.latex,
                                            textStyle = resolvedStyle.copy(fontSize = baseFontSize),
                                            color = run.color ?: baseColor,
                                            modifier = Modifier
                                                .align(Alignment.CenterVertically)
                                                .padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface CardRenderBlock {
    data object SpacerLine : CardRenderBlock
    data class TextLine(val annotated: AnnotatedString) : CardRenderBlock
    data class FormulaBlock(val latex: String, val color: Color?) : CardRenderBlock
    data class MixedLine(val runs: List<CardMixedRun>) : CardRenderBlock
}

private sealed interface CardMixedRun {
    data class TextRun(val annotated: AnnotatedString) : CardMixedRun
    data class MathRun(val latex: String, val color: Color?, val isCloze: Boolean) : CardMixedRun
}

private data class SpanFrame(val color: Color?, val isCloze: Boolean)

private class HtmlStyleCursor {
    var bold = 0
    var italic = 0
    var underline = 0
    var strike = 0
    var sub = 0
    var sup = 0
    var code = 0
    var highlight = 0
    var heading = 0
    val colors = ArrayDeque<Color>()
    val spans = ArrayDeque<SpanFrame>()

    val activeColor: Color? get() = colors.lastOrNull() ?: spans.lastOrNull { it.color != null }?.color
    val inCloze: Boolean get() = spans.any { it.isCloze }

    fun toSpanStyle(
        primaryColor: Color,
        codeBg: Color,
        highlightBg: Color,
        clozeBg: Color,
        baseFontSize: TextUnit
    ): SpanStyle {
        val textDecoration = when {
            underline > 0 && strike > 0 -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            underline > 0 -> TextDecoration.Underline
            strike > 0 -> TextDecoration.LineThrough
            else -> null
        }
        val baseline = when {
            sup > 0 -> BaselineShift.Superscript
            sub > 0 -> BaselineShift.Subscript
            else -> null
        }
        val fontSize = when {
            sup > 0 || sub > 0 -> (baseFontSize.value * 0.78f).sp
            heading > 0 -> (baseFontSize.value * 1.14f).sp
            code > 0 -> (baseFontSize.value * 0.92f).sp
            else -> TextUnit.Unspecified
        }
        val bg = when {
            inCloze -> clozeBg
            highlight > 0 -> highlightBg
            code > 0 -> codeBg
            else -> Color.Unspecified
        }
        val fg = when {
            activeColor != null -> activeColor!!
            inCloze -> primaryColor
            else -> Color.Unspecified
        }
        return SpanStyle(
            color = fg,
            background = bg,
            fontWeight = if (bold > 0 || heading > 0 || inCloze) FontWeight.Bold else null,
            fontStyle = if (italic > 0) FontStyle.Italic else null,
            fontFamily = if (code > 0) FontFamily.Monospace else null,
            fontSize = fontSize,
            baselineShift = baseline,
            textDecoration = textDecoration
        )
    }
}

private val styleScriptRegex = Regex("""<(style|script)\b[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
private val htmlCommentRegex = Regex("""<!--[\s\S]*?-->""")
private val soundTagRegex = Regex("""\[sound:[^\]\n]+\]""", RegexOption.IGNORE_CASE)
private val brTagRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val hrTagRegex = Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE)
private val listTokenRegex = Regex("""<(/?)(ul|ol|li)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val blockBreakTagRegex = Regex("""<(/?)(p|div|section|article|blockquote|tr|table|thead|tbody)(?=\s|/?>)[^<>$\n]*>""", RegexOption.IGNORE_CASE)
private val cellTagRegex = Regex("""<(/?)(td|th)(?=\s|/?>)[^<>$\n]*>""", RegexOption.IGNORE_CASE)
private val headingOpenRegex = Regex("""<h[1-6](?=\s|/?>)[^<>$\n]*>""", RegexOption.IGNORE_CASE)
private val headingCloseRegex = Regex("""</h[1-6]>""", RegexOption.IGNORE_CASE)
private val supportedInlineTagRegex = Regex(
    """</?(?:b|strong|i|em|u|s|strike|del|sub|sup|code|mark|font|span|h)(?=\s|/?>)[^<>$\n]*>""",
    RegexOption.IGNORE_CASE
)
private val unknownHtmlTagRegex = Regex(
    """</?(?:img|a|meta|link|pre|small|big|center|cite|q|abbr|kbd|var|samp|figure|figcaption|caption|colgroup|col|label|input|button|svg|path)(?=\s|/?>)[^<>$\n]*>""",
    RegexOption.IGNORE_CASE
)
private val emptyInlineTagRegex = Regex(
    """<(b|strong|i|em|u|s|strike|del|sub|sup|code|mark|font|span)(?=\s|/?>)[^<>$\n]*>\s*</\1>""",
    RegexOption.IGNORE_CASE
)
private val tripleNewlineRegex = Regex("""\n{3,}""")
private val clozeOpenRegex = Regex("""\{\{c[1-9]\d*::""", RegexOption.IGNORE_CASE)

private fun normalizeCardHtml(raw: String): String {
    if (raw.isBlank()) return ""
    var s = raw.replace("\r\n", "\n").replace('\r', '\n')
    s = styleScriptRegex.replace(s, "")
    s = htmlCommentRegex.replace(s, "")
    s = soundTagRegex.replace(s, "")
    s = brTagRegex.replace(s, "\n")
    s = hrTagRegex.replace(s, "\n")
    s = headingOpenRegex.replace(s, "\n<h>")
    s = headingCloseRegex.replace(s, "</h>\n")
    s = expandHtmlLists(s)
    s = blockBreakTagRegex.replace(s) { m ->
        val closing = m.groupValues[1] == "/"
        if (closing) "\n" else "\n"
    }
    s = cellTagRegex.replace(s) { m ->
        val closing = m.groupValues[1] == "/"
        if (closing) " · " else ""
    }
    s = unknownHtmlTagRegex.replace(s, "")
    s = collapseRawClozes(s)
    repeat(6) {
        val next = emptyInlineTagRegex.replace(s, "")
        if (next == s) return@repeat
        s = next
    }
    s = s.replace(tripleNewlineRegex, "\n\n")
    return s.trim('\n')
}

private fun collapseRawClozes(input: String): String {
    if (!input.contains("{{c", ignoreCase = true)) return input
    val sb = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        val match = clozeOpenRegex.find(input, i)
        if (match == null) {
            sb.append(input, i, input.length)
            break
        }
        sb.append(input, i, match.range.first)
        var pos = match.range.last + 1
        var depth = 0
        var closeStart = -1
        while (pos < input.length - 1) {
            when {
                input[pos] == '{' -> { depth++; pos++ }
                input[pos] == '}' && depth > 0 -> { depth--; pos++ }
                input[pos] == '}' && input[pos + 1] == '}' && depth == 0 -> {
                    closeStart = pos
                    break
                }
                else -> pos++
            }
        }
        if (closeStart < 0) {
            sb.append(input, match.range.first, match.range.last + 1)
            i = match.range.last + 1
        } else {
            val body = input.substring(match.range.last + 1, closeStart)
            var sep = -1
            var braceDepth = 0
            var k = 0
            while (k < body.length - 1) {
                when (body[k]) {
                    '{' -> braceDepth++
                    '}' -> if (braceDepth > 0) braceDepth--
                    ':' -> if (braceDepth == 0 && body[k + 1] == ':') {
                        sep = k
                        break
                    }
                }
                k++
            }
            val hint = if (sep >= 0) body.substring(sep + 2).trim() else ""
            sb.append(if (hint.isNotEmpty()) "[$hint]" else "[…]")
            i = closeStart + 2
        }
    }
    return sb.toString()
}

private fun expandHtmlLists(input: String): String {
    if (!input.contains("<ul", ignoreCase = true) &&
        !input.contains("<ol", ignoreCase = true) &&
        !input.contains("<li", ignoreCase = true)
    ) {
        return input
    }
    val out = StringBuilder(input.length)
    val stack = ArrayDeque<Pair<Boolean, Int>>() // isOrdered to itemCounter
    var cursor = 0
    for (m in listTokenRegex.findAll(input)) {
        out.append(input, cursor, m.range.first)
        val closing = m.groupValues[1] == "/"
        val tag = m.groupValues[2].lowercase()
        when (tag) {
            "ul" -> {
                if (!closing) {
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    stack.addLast(false to 0)
                } else {
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                }
            }
            "ol" -> {
                if (!closing) {
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    stack.addLast(true to 0)
                } else {
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                }
            }
            "li" -> {
                if (!closing) {
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    val indent = "  ".repeat((stack.size - 1).coerceAtLeast(0))
                    val top = stack.lastOrNull()
                    if (top != null && top.first) {
                        val nextNum = top.second + 1
                        stack[stack.lastIndex] = true to nextNum
                        out.append(indent).append(nextNum).append(". ")
                    } else {
                        out.append(indent).append("• ")
                    }
                } else {
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                }
            }
        }
        cursor = m.range.last + 1
    }
    out.append(input, cursor, input.length)
    return out.toString()
}

private val numericEntityRegex = Regex("""&#(x?[0-9a-fA-F]+);""")

private fun decodeHtmlEntities(input: String): String {
    if (!input.contains('&')) return input
    var s = input
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&ndash;", "–")
        .replace("&mdash;", "—")
        .replace("&times;", "×")
        .replace("&divide;", "÷")
        .replace("&le;", "≤")
        .replace("&ge;", "≥")
        .replace("&ne;", "≠")
        .replace("&rarr;", "→")
        .replace("&larr;", "←")
        .replace("&harr;", "↔")
    s = numericEntityRegex.replace(s) { m ->
        val raw = m.groupValues[1]
        val code = if (raw.startsWith("x", ignoreCase = true)) {
            raw.substring(1).toIntOrNull(16)
        } else {
            raw.toIntOrNull()
        }
        if (code != null && code in 32..0x10FFFF) {
            String(Character.toChars(code))
        } else {
            m.value
        }
    }
    return s
}

private val namedHtmlColors = mapOf(
    "red" to Color(0xFFEF4444),
    "blue" to Color(0xFF3B82F6),
    "green" to Color(0xFF10B981),
    "yellow" to Color(0xFFEAB308),
    "orange" to Color(0xFFF97316),
    "purple" to Color(0xFF8B5CF6),
    "pink" to Color(0xFFEC4899),
    "teal" to Color(0xFF14B8A6),
    "cyan" to Color(0xFF06B6D4),
    "magenta" to Color(0xFFD946EF),
    "gray" to Color(0xFF6B7280),
    "grey" to Color(0xFF6B7280),
    "brown" to Color(0xFF92400E),
    "black" to Color(0xFF111827),
    "white" to Color(0xFFF9FAFB)
)

private val attrColorRegex = Regex("""\bcolor\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
private val styleColorRegex = Regex("""(?:^|[;\s"])color\s*:\s*([^;"'\n]+)""", RegexOption.IGNORE_CASE)
private val classClozeRegex = Regex("""\bclass\s*=\s*(?:"[^"]*\bcloze\b[^"]*"|'[^']*\bcloze\b[^']*'|cloze\b)""", RegexOption.IGNORE_CASE)
private val rgbFuncRegex = Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([0-9.]+))?\s*\)""", RegexOption.IGNORE_CASE)

private fun parseHtmlColor(raw: String?): Color? {
    val clean = raw?.trim()?.lowercase()?.ifEmpty { null } ?: return null
    namedHtmlColors[clean]?.let { return it }
    if (clean.startsWith("#")) {
        val hex = clean.substring(1)
        val argb = when (hex.length) {
            3 -> {
                val r = hex[0].digitToIntOrNull(16)?.let { it * 17 } ?: return null
                val g = hex[1].digitToIntOrNull(16)?.let { it * 17 } ?: return null
                val b = hex[2].digitToIntOrNull(16)?.let { it * 17 } ?: return null
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() } ?: return null
            8 -> hex.toLongOrNull(16)?.toInt() ?: return null
            else -> return null
        }
        return Color(argb)
    }
    rgbFuncRegex.matchEntire(clean)?.let { m ->
        val r = m.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val g = m.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val b = m.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val a = m.groupValues[4].toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        return Color(r / 255f, g / 255f, b / 255f, a)
    }
    return null
}

private val inlineCardMarkupRegex = Regex(
    """\{\{c\d+::([\s\S]+?)\}\}|""" + // 1: raw cloze
        """\*\*([^\n*](?:[^\n]*?[^\n*])?)\*\*|""" + // 2: **bold**
        """__([^\n_](?:[^\n]*?[^\n_])?)__|""" + // 3: __bold__
        """==([^\n=](?:[^\n]*?[^\n=])?)==|""" + // 4: ==highlight==
        """~~([^\n~](?:[^\n]*?[^\n~])?)~~|""" + // 5: ~~strike~~
        """`([^`\n]+)`|""" + // 6: `code`
        """(?<!\w)\*([^\s*](?:[^*\n]*?[^\s*])?)\*(?!\w)|""" + // 7: *italic*
        """(?<!\w)_([^\s_](?:[^_\n]*?[^\s_])?)_(?!\w)|""" + // 8: _italic_
        """(?<!\[)\[([^\]\n]+)\](?![\]\(])""" // 9: [cloze]
)

private fun isClozeBracketCandidate(inner: String): Boolean {
    val trimmed = inner.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.startsWith("sound:", ignoreCase = true)) return false
    if (trimmed == "..." || trimmed == "…") return true
    if (trimmed.startsWith("Oclusão #", ignoreCase = true)) return true
    if (trimmed.length > 48) return false
    // Skip numeric citations like [1] or coordinate/math intervals like [0, 1] or [a, b]
    if (trimmed.all { it.isDigit() || it == ',' || it == ' ' || it == '-' || it == '.' }) return false
    if (',' in trimmed && trimmed.length <= 16) return false
    return trimmed.any { it.isLetter() }
}

private fun appendInlineMarkupText(
    builder: AnnotatedString.Builder,
    chunk: String,
    cursor: HtmlStyleCursor,
    primaryColor: Color,
    codeBg: Color,
    highlightBg: Color,
    clozeBg: Color,
    baseFontSize: TextUnit
) {
    if (chunk.isEmpty()) return
    val baseStyle = cursor.toSpanStyle(primaryColor, codeBg, highlightBg, clozeBg, baseFontSize)
    var last = 0
    for (match in inlineCardMarkupRegex.findAll(chunk)) {
        if (match.range.first > last) {
            val plain = chunk.substring(last, match.range.first)
            if (baseStyle != SpanStyle()) {
                builder.pushStyle(baseStyle)
                builder.append(plain)
                builder.pop()
            } else {
                builder.append(plain)
            }
        }
        val rawCloze = match.groups[1]?.value
        val bold1 = match.groups[2]?.value
        val bold2 = match.groups[3]?.value
        val highlight = match.groups[4]?.value
        val strike = match.groups[5]?.value
        val code = match.groups[6]?.value
        val italic1 = match.groups[7]?.value
        val italic2 = match.groups[8]?.value
        val bracket = match.groups[9]?.value

        when {
            rawCloze != null -> {
                val hint = rawCloze.substringAfter("::", "").trim()
                val label = if (hint.isNotEmpty()) "[$hint]" else "[…]"
                builder.pushStyle(baseStyle)
                builder.pushStyle(SpanStyle(color = primaryColor, background = clozeBg, fontWeight = FontWeight.Bold))
                builder.append(label)
                builder.pop()
                builder.pop()
            }
            bold1 != null || bold2 != null -> {
                builder.pushStyle(baseStyle)
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                builder.append(bold1 ?: bold2!!)
                builder.pop()
                builder.pop()
            }
            highlight != null -> {
                builder.pushStyle(baseStyle)
                builder.pushStyle(SpanStyle(background = highlightBg))
                builder.append(highlight)
                builder.pop()
                builder.pop()
            }
            strike != null -> {
                builder.pushStyle(baseStyle)
                builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                builder.append(strike)
                builder.pop()
                builder.pop()
            }
            code != null -> {
                builder.pushStyle(baseStyle)
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        fontSize = (baseFontSize.value * 0.92f).sp
                    )
                )
                builder.append(code)
                builder.pop()
                builder.pop()
            }
            italic1 != null || italic2 != null -> {
                builder.pushStyle(baseStyle)
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                builder.append(italic1 ?: italic2!!)
                builder.pop()
                builder.pop()
            }
            bracket != null -> {
                if (isClozeBracketCandidate(bracket)) {
                    builder.pushStyle(baseStyle)
                    builder.pushStyle(SpanStyle(color = primaryColor, background = clozeBg, fontWeight = FontWeight.Bold))
                    builder.append("[$bracket]")
                    builder.pop()
                    builder.pop()
                } else {
                    if (baseStyle != SpanStyle()) {
                        builder.pushStyle(baseStyle)
                        builder.append(match.value)
                        builder.pop()
                    } else {
                        builder.append(match.value)
                    }
                }
            }
        }
        last = match.range.last + 1
    }
    if (last < chunk.length) {
        val tail = chunk.substring(last)
        if (baseStyle != SpanStyle()) {
            builder.pushStyle(baseStyle)
            builder.append(tail)
            builder.pop()
        } else {
            builder.append(tail)
        }
    }
}

private fun applyInlineTag(tagText: String, cursor: HtmlStyleCursor) {
    val closing = tagText.startsWith("</")
    val inner = tagText.removePrefix("</").removePrefix("<").removeSuffix(">").trim()
    val name = inner.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
    when (name) {
        "b", "strong" -> if (closing) cursor.bold = (cursor.bold - 1).coerceAtLeast(0) else cursor.bold++
        "i", "em" -> if (closing) cursor.italic = (cursor.italic - 1).coerceAtLeast(0) else cursor.italic++
        "u" -> if (closing) cursor.underline = (cursor.underline - 1).coerceAtLeast(0) else cursor.underline++
        "s", "strike", "del" -> if (closing) cursor.strike = (cursor.strike - 1).coerceAtLeast(0) else cursor.strike++
        "sub" -> if (closing) cursor.sub = (cursor.sub - 1).coerceAtLeast(0) else cursor.sub++
        "sup" -> if (closing) cursor.sup = (cursor.sup - 1).coerceAtLeast(0) else cursor.sup++
        "code" -> if (closing) cursor.code = (cursor.code - 1).coerceAtLeast(0) else cursor.code++
        "mark" -> if (closing) cursor.highlight = (cursor.highlight - 1).coerceAtLeast(0) else cursor.highlight++
        "h" -> if (closing) cursor.heading = (cursor.heading - 1).coerceAtLeast(0) else cursor.heading++
        "font" -> {
            if (closing) {
                if (cursor.colors.isNotEmpty()) cursor.colors.removeLast()
            } else {
                val m = attrColorRegex.find(inner)
                val parsed = m?.let { parseHtmlColor(it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }) }
                cursor.colors.addLast(parsed ?: cursor.activeColor ?: Color.Unspecified)
                if (cursor.colors.lastOrNull() == Color.Unspecified) cursor.colors.removeLast()
            }
        }
        "span" -> {
            if (closing) {
                if (cursor.spans.isNotEmpty()) cursor.spans.removeLast()
            } else {
                val colorMatch = styleColorRegex.find(inner)
                val parsedColor = parseHtmlColor(colorMatch?.groupValues?.get(1))
                val isCloze = classClozeRegex.containsMatchIn(inner)
                cursor.spans.addLast(SpanFrame(parsedColor, isCloze))
            }
        }
    }
}

private fun parseHtmlAndMarkupSegment(
    lineText: String,
    cursor: HtmlStyleCursor,
    primaryColor: Color,
    codeBg: Color,
    highlightBg: Color,
    clozeBg: Color,
    baseFontSize: TextUnit
): AnnotatedString = buildAnnotatedString {
    var pos = 0
    for (tag in supportedInlineTagRegex.findAll(lineText)) {
        if (tag.range.first > pos) {
            val decoded = decodeHtmlEntities(lineText.substring(pos, tag.range.first))
            appendInlineMarkupText(this, decoded, cursor, primaryColor, codeBg, highlightBg, clozeBg, baseFontSize)
        }
        applyInlineTag(tag.value, cursor)
        pos = tag.range.last + 1
    }
    if (pos < lineText.length) {
        val decoded = decodeHtmlEntities(lineText.substring(pos))
        appendInlineMarkupText(this, decoded, cursor, primaryColor, codeBg, highlightBg, clozeBg, baseFontSize)
    }
}

private fun buildCardRenderBlocks(
    raw: String,
    primaryColor: Color,
    codeBg: Color,
    highlightBg: Color,
    clozeBg: Color,
    baseFontSize: TextUnit,
    baseTextColor: Color
): List<CardRenderBlock> {
    val normalized = normalizeCardHtml(raw)
    if (normalized.isEmpty()) return emptyList()
    val segments = parseMathSegments(normalized)
    val cursor = HtmlStyleCursor()

    val blocks = mutableListOf<CardRenderBlock>()
    val currentLineRuns = mutableListOf<CardMixedRun>()

    fun flushCurrentLine(emitBlankIfEmpty: Boolean) {
        if (currentLineRuns.isEmpty()) {
            if (emitBlankIfEmpty) blocks.add(CardRenderBlock.SpacerLine)
            return
        }
        val hasMath = currentLineRuns.any { it is CardMixedRun.MathRun }
        if (!hasMath) {
            val combined = buildAnnotatedString {
                currentLineRuns.forEach { run ->
                    if (run is CardMixedRun.TextRun) append(run.annotated)
                }
            }
            if (combined.isNotEmpty()) {
                blocks.add(CardRenderBlock.TextLine(combined))
            } else if (emitBlankIfEmpty) {
                blocks.add(CardRenderBlock.SpacerLine)
            }
        } else {
            val mergedRuns = ArrayList<CardMixedRun>(currentLineRuns.size)
            var pendingTexts: MutableList<AnnotatedString>? = null
            fun flushPendingTexts() {
                val list = pendingTexts ?: return
                when (list.size) {
                    0 -> Unit
                    1 -> mergedRuns.add(CardMixedRun.TextRun(list[0]))
                    else -> mergedRuns.add(
                        CardMixedRun.TextRun(
                            buildAnnotatedString {
                                list.forEach { append(it) }
                            }
                        )
                    )
                }
                list.clear()
            }
            for (run in currentLineRuns) {
                when (run) {
                    is CardMixedRun.TextRun -> {
                        if (run.annotated.isNotEmpty()) {
                            val list = pendingTexts ?: mutableListOf<AnnotatedString>().also { pendingTexts = it }
                            list.add(run.annotated)
                        }
                    }
                    is CardMixedRun.MathRun -> {
                        flushPendingTexts()
                        mergedRuns.add(run)
                    }
                }
            }
            flushPendingTexts()
            val nonEmpty = mergedRuns.any {
                when (it) {
                    is CardMixedRun.TextRun -> it.annotated.isNotEmpty()
                    is CardMixedRun.MathRun -> it.latex.isNotBlank()
                }
            }
            if (nonEmpty) {
                blocks.add(CardRenderBlock.MixedLine(mergedRuns))
            }
        }
        currentLineRuns.clear()
    }

    for (seg in segments) {
        when (seg) {
            is MathSegment.BlockMath -> {
                flushCurrentLine(emitBlankIfEmpty = false)
                blocks.add(CardRenderBlock.FormulaBlock(decodeHtmlEntities(seg.latex), cursor.activeColor ?: baseTextColor))
            }
            is MathSegment.InlineMath -> {
                currentLineRuns.add(
                    CardMixedRun.MathRun(
                        latex = decodeHtmlEntities(seg.latex),
                        color = cursor.activeColor,
                        isCloze = cursor.inCloze
                    )
                )
            }
            is MathSegment.Text -> {
                val pieces = seg.value.split('\n')
                pieces.forEachIndexed { idx, linePiece ->
                    if (linePiece.isNotEmpty()) {
                        val cleanPiece = if (linePiece.trimStart().startsWith("#")) {
                            val trimmed = linePiece.trimStart()
                            val hashes = trimmed.takeWhile { it == '#' }.length
                            if (hashes in 1..6 && trimmed.getOrNull(hashes) == ' ') {
                                "<b>${trimmed.substring(hashes + 1)}</b>"
                            } else {
                                linePiece
                            }
                        } else {
                            linePiece
                        }
                        val ann = parseHtmlAndMarkupSegment(
                            cleanPiece,
                            cursor,
                            primaryColor,
                            codeBg,
                            highlightBg,
                            clozeBg,
                            baseFontSize
                        )
                        if (ann.isNotEmpty()) {
                            currentLineRuns.add(CardMixedRun.TextRun(ann))
                        }
                    }
                    if (idx < pieces.lastIndex) {
                        flushCurrentLine(emitBlankIfEmpty = currentLineRuns.isEmpty())
                    }
                }
            }
        }
    }
    flushCurrentLine(emitBlankIfEmpty = false)
    while (blocks.lastOrNull() is CardRenderBlock.SpacerLine) {
        blocks.removeAt(blocks.lastIndex)
    }
    return blocks
}

object ParametricCardEngine {
    private val varRegex = Regex("""\{\{var:([a-zA-Z_]\w*):(-?\d+)\.\.(-?\d+)\}\}""")
    private val evalRegex = Regex("""\{\{eval:([^}]+)\}\}""")

    fun render(front: String, back: String, seed: Long): Pair<String, String> {
        val rng = kotlin.random.Random(seed)
        val env = HashMap<String, Int>()
        val renderedFront = varRegex.replace(front) { m ->
            val name = m.groupValues[1]
            val lo = m.groupValues[2].toIntOrNull() ?: 1
            val hi = m.groupValues[3].toIntOrNull() ?: 9
            val value = env.getOrPut(name) {
                if (hi >= lo) rng.nextInt(lo, hi + 1) else lo
            }
            value.toString()
        }
        val renderedBack = evalRegex.replace(varRegex.replace(back) { m ->
            val name = m.groupValues[1]
            val lo = m.groupValues[2].toIntOrNull() ?: 1
            val hi = m.groupValues[3].toIntOrNull() ?: 9
            env.getOrPut(name) { if (hi >= lo) rng.nextInt(lo, hi + 1) else lo }.toString()
        }) { m ->
            evalSimpleExpr(m.groupValues[1], env)?.toString() ?: m.value
        }
        return renderedFront to renderedBack
    }

    private fun evalSimpleExpr(expr: String, env: Map<String, Int>): Int? {
        var substituted = expr.trim()
        env.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
            substituted = substituted.replace(Regex("""\b${Regex.escape(k)}\b"""), v.toString())
        }
        val tokens = ArrayList<String>()
        var i = 0
        val s = substituted.replace(" ", "")
        while (i < s.length) {
            val ch = s[i]
            if ((ch == '+' || ch == '-') && (tokens.isEmpty() || tokens.last() in listOf("+", "-", "*", "/"))) {
                val start = i
                i++
                while (i < s.length && s[i].isDigit()) i++
                if (i == start + 1) return null
                tokens.add(s.substring(start, i))
            } else if (ch.isDigit()) {
                val start = i
                while (i < s.length && s[i].isDigit()) i++
                tokens.add(s.substring(start, i))
            } else if (ch == '+' || ch == '-' || ch == '*' || ch == '/') {
                tokens.add(ch.toString())
                i++
            } else {
                return null
            }
        }
        if (tokens.isEmpty()) return null
        val firstPass = ArrayList<String>()
        var idx = 0
        while (idx < tokens.size) {
            val tok = tokens[idx]
            if ((tok == "*" || tok == "/") && firstPass.isNotEmpty() && idx + 1 < tokens.size) {
                val left = firstPass.removeAt(firstPass.lastIndex).toIntOrNull() ?: return null
                val right = tokens[idx + 1].toIntOrNull() ?: return null
                val res = if (tok == "*") left * right else if (right != 0) left / right else return null
                firstPass.add(res.toString())
                idx += 2
            } else {
                firstPass.add(tok)
                idx++
            }
        }
        var acc = firstPass[0].toIntOrNull() ?: return null
        var j = 1
        while (j + 1 < firstPass.size) {
            val op = firstPass[j]
            val rhs = firstPass[j + 1].toIntOrNull() ?: return null
            acc = if (op == "+") acc + rhs else if (op == "-") acc - rhs else return null
            j += 2
        }
        return acc
    }
}
