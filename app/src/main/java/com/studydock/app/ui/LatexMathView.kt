package com.studydock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutIdParentData
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

// ---------- Public Segment Contract ----------

sealed interface MathSegment {
    data class Text(val value: String) : MathSegment
    data class InlineMath(val latex: String) : MathSegment
    data class BlockMath(val latex: String) : MathSegment
}

private val currencyPrefixes = setOf("US", "CA", "AU", "NZ", "HK", "SG")
private val multiWhitespaceRegex = Regex("\\s+")

private class BoundedLruCache<K, V>(private val maxEntries: Int) {
    private val lock = Any()
    private val map = object : LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    fun get(key: K): V? = synchronized(lock) { map[key] }

    fun put(key: K, value: V): V = synchronized(lock) {
        map[key] = value
        value
    }
}

private val mathSegmentsCache = BoundedLruCache<String, List<MathSegment>>(256)

private fun hasNoMathOrMarkupHint(text: String): Boolean {
    for (i in 0 until text.length) {
        when (text[i]) {
            '$', '\\', '[', '*', '_', '#' -> return false
        }
    }
    return true
}

private fun isEscapedAt(text: String, index: Int): Boolean {
    var backslashes = 0
    var p = index - 1
    while (p >= 0 && text[p] == '\\') {
        backslashes++
        p--
    }
    return backslashes % 2 == 1
}

private fun isCurrencyPrefix(text: String, dollarIndex: Int): Boolean {
    if (dollarIndex <= 0) return false
    val next = text.getOrNull(dollarIndex + 1)
    if (next == null || (!next.isDigit() && !next.isWhitespace())) return false
    val prev = text[dollarIndex - 1]
    if ((prev == 'R' || prev == 'r' || prev == 'U' || prev == 'u') &&
        (dollarIndex == 1 || !text[dollarIndex - 2].isLetter())
    ) {
        return true
    }
    if (dollarIndex >= 2) {
        val two = text.substring(dollarIndex - 2, dollarIndex).uppercase()
        if (two in currencyPrefixes && (dollarIndex == 2 || !text[dollarIndex - 3].isLetter())) {
            return true
        }
    }
    return false
}

private fun unescapePlainDollars(raw: String): String {
    if (!raw.contains("\\$")) return raw
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '\\' && i + 1 < raw.length && raw[i + 1] == '$') {
            sb.append('$')
            i += 2
        } else {
            sb.append(raw[i])
            i++
        }
    }
    return sb.toString()
}

private fun findClosingDoubleDollar(text: String, startFrom: Int): Int {
    var j = startFrom
    while (j < text.length - 1) {
        if (text[j] == '$' && text[j + 1] == '$' && !isEscapedAt(text, j)) {
            return j
        }
        j++
    }
    return -1
}

private fun findClosingSingleDollar(text: String, openIndex: Int): Int {
    val afterOpen = text.getOrNull(openIndex + 1) ?: return -1
    if (afterOpen.isWhitespace() || afterOpen == '\n' || afterOpen == '\r') return -1
    if (isCurrencyPrefix(text, openIndex)) return -1

    var j = openIndex + 1
    while (j < text.length) {
        val c = text[j]
        if (c == '\n' || c == '\r') return -1
        if (c == '$' && !isEscapedAt(text, j)) {
            if (j + 1 < text.length && text[j + 1] == '$') return -1
            val beforeClose = text[j - 1]
            val afterClose = text.getOrNull(j + 1)
            val validClose = !beforeClose.isWhitespace() &&
                (afterClose == null || !afterClose.isDigit())
            if (validClose) {
                return j
            }
            return -1
        }
        j++
    }
    return -1
}

private fun findClosingParenMath(text: String, openIndex: Int): Int {
    var j = openIndex + 2
    while (j < text.length - 1) {
        if (text[j] == '\n' && text.getOrNull(j + 1) == '\n') return -1
        if (text[j] == '\\' && text[j + 1] == ')' && !isEscapedAt(text, j)) return j
        j++
    }
    return -1
}

private fun findClosingBracketMath(text: String, openIndex: Int): Int {
    // Avoid colliding with escaped wikilinks like \[[Nota]]
    if (text.getOrNull(openIndex + 2) == '[') return -1
    var j = openIndex + 2
    while (j < text.length - 1) {
        if (text[j] == '\\' && text[j + 1] == ']' && !isEscapedAt(text, j)) {
            val inner = text.substring(openIndex + 2, j)
            val looksLikeMath = inner.contains('\\') || inner.contains('^') || inner.contains('_') ||
                inner.contains('=') || inner.contains('+') || inner.contains('/')
            return if (looksLikeMath && inner.isNotBlank()) j else -1
        }
        j++
    }
    return -1
}

fun parseMathSegments(text: String): List<MathSegment> {
    if (text.isEmpty()) return emptyList()
    if (!text.contains('$') && !text.contains("\\(") && !text.contains("\\[")) {
        return listOf(MathSegment.Text(text))
    }
    if (text.length <= 4096) {
        mathSegmentsCache.get(text)?.let { return it }
    }

    val result = mutableListOf<MathSegment>()
    val plain = StringBuilder()
    var inInlineCodeTicks = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            val value = unescapePlainDollars(plain.toString())
            if (value.isNotEmpty()) {
                val prev = result.lastOrNull()
                if (prev is MathSegment.Text) {
                    result[result.lastIndex] = MathSegment.Text(prev.value + value)
                } else {
                    result.add(MathSegment.Text(value))
                }
            }
            plain.clear()
        }
    }

    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '`' && !isEscapedAt(text, i)) {
            var tickEnd = i + 1
            while (tickEnd < text.length && text[tickEnd] == '`') tickEnd++
            val len = tickEnd - i
            if (inInlineCodeTicks == 0) inInlineCodeTicks = len
            else if (inInlineCodeTicks == len) inInlineCodeTicks = 0
            plain.append(text, i, tickEnd)
            i = tickEnd
            continue
        }

        if (inInlineCodeTicks != 0) {
            if (inInlineCodeTicks == 1 && ch == '\n' && text.getOrNull(i + 1) == '\n') {
                inInlineCodeTicks = 0
            }
            plain.append(ch)
            i++
            continue
        }

        if (ch == '\\' && i + 1 < text.length && !isEscapedAt(text, i)) {
            val next = text[i + 1]
            if (next == '$') {
                plain.append('$')
                i += 2
                continue
            }
            if (next == '(') {
                val close = findClosingParenMath(text, i)
                if (close > i + 2) {
                    val inner = text.substring(i + 2, close).trim()
                    if (inner.isNotEmpty()) {
                        flushPlain()
                        result.add(MathSegment.InlineMath(inner))
                        i = close + 2
                        continue
                    }
                }
            }
            if (next == '[') {
                val close = findClosingBracketMath(text, i)
                if (close > i + 2) {
                    val inner = text.substring(i + 2, close).trim()
                    if (inner.isNotEmpty()) {
                        flushPlain()
                        result.add(MathSegment.BlockMath(inner))
                        i = close + 2
                        continue
                    }
                }
            }
        }

        if (ch == '$' && !isEscapedAt(text, i)) {
            if (i + 1 < text.length && text[i + 1] == '$') {
                val close = findClosingDoubleDollar(text, i + 2)
                if (close >= i + 2) {
                    val inner = text.substring(i + 2, close).trim()
                    if (inner.isNotEmpty()) {
                        flushPlain()
                        result.add(MathSegment.BlockMath(inner))
                        i = close + 2
                        continue
                    }
                }
                plain.append("$$")
                i += 2
                continue
            }

            val close = findClosingSingleDollar(text, i)
            if (close > i + 1) {
                val inner = text.substring(i + 1, close).trim()
                if (inner.isNotEmpty()) {
                    flushPlain()
                    result.add(MathSegment.InlineMath(inner))
                    i = close + 1
                    continue
                }
            }
        }

        plain.append(ch)
        i++
    }
    flushPlain()
    if (text.length <= 4096) {
        mathSegmentsCache.put(text, result)
    }
    return result
}

fun containsMathSyntax(text: String): Boolean {
    if (!text.contains('$') && !text.contains("\\(") && !text.contains("\\[")) return false
    return parseMathSegments(text).any { it is MathSegment.InlineMath || it is MathSegment.BlockMath }
}

// ---------- AST Model & Parser ----------

private enum class AccentKind { VEC, OVER_ARROW, BAR, HAT, TILDE, DOT, DDOT, UNDERLINE }

private enum class TextVariant { NORMAL, BOLD, ITALIC, MONOSPACE, UPRIGHT, DOUBLE_STRUCK, CALLIGRAPHIC }

private sealed interface TexNode {
    data class Row(val children: List<TexNode>) : TexNode
    data class Symbol(
        val text: String,
        val italic: Boolean = false,
        val bold: Boolean = false,
        val upright: Boolean = false,
        val isOperator: Boolean = false,
        val scale: Float = 1f
    ) : TexNode

    data class Fraction(val numerator: TexNode, val denominator: TexNode, val drawBar: Boolean = true) : TexNode
    data class Root(val radicand: TexNode, val index: TexNode? = null) : TexNode
    data class Script(val base: TexNode, val sub: TexNode? = null, val sup: TexNode? = null) : TexNode
    data class BigOp(
        val symbol: String,
        val sub: TexNode? = null,
        val sup: TexNode? = null,
        val stackLimits: Boolean = true
    ) : TexNode

    data class Accent(val base: TexNode, val kind: AccentKind) : TexNode
    data class Delimited(val left: String, val right: String, val inner: TexNode) : TexNode
    data class Matrix(
        val rows: List<List<TexNode>>,
        val leftDelim: String,
        val rightDelim: String,
        val leftAligned: Boolean = false
    ) : TexNode

    data class TextRun(val text: String, val variant: TextVariant = TextVariant.UPRIGHT) : TexNode
    data class Space(val em: Float) : TexNode
    data class Boxed(val inner: TexNode) : TexNode
    data class Cancel(val inner: TexNode) : TexNode
    data class Colored(val color: Color?, val inner: TexNode) : TexNode
    data class LineStack(val rows: List<TexNode>) : TexNode
}

private val greekSymbols = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
    "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
    "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
    "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
    "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
    "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω"
)

private val mathSymbols = mapOf(
    "infty" to "∞", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
    "aleph" to "ℵ", "emptyset" to "∅", "varnothing" to "∅", "degree" to "°",
    "circ" to "∘", "prime" to "′", "angle" to "∠", "triangle" to "△",
    "square" to "□", "checkmark" to "✓", "dagger" to "†", "ddagger" to "‡",
    "dots" to "…", "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
    "partial" to "∂", "nabla" to "∇",
    "forall" to "∀", "exists" to "∃", "nexists" to "∄",
    "neg" to "¬", "lnot" to "¬", "therefore" to "∴", "because" to "∵",
    "top" to "⊤", "bot" to "⊥", "models" to "⊨", "vdash" to "⊢"
)

private val operatorSymbols = mapOf(
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
    "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡",
    "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝",
    "ll" to "≪", "gg" to "≫", "perp" to "⊥", "parallel" to "∥", "mid" to "∣",
    "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
    "ast" to "∗", "star" to "⋆", "oplus" to "⊕", "otimes" to "⊗", "odot" to "⊙",
    "setminus" to "∖", "wedge" to "∧", "vee" to "∨", "land" to "∧", "lor" to "∨",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←",
    "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
    "Leftrightarrow" to "⇔", "iff" to "⟺", "implies" to "⟹", "mapsto" to "↦",
    "uparrow" to "↑", "downarrow" to "↓", "nearrow" to "↗", "searrow" to "↘",
    "in" to "∈", "notin" to "∉", "ni" to "∋",
    "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇",
    "nsubseteq" to "⊈", "cup" to "∪", "cap" to "∩", "uplus" to "⊎",
    "langle" to "⟨", "rangle" to "⟩"
)

private val bigOperators = mapOf(
    "sum" to ("∑" to true),
    "prod" to ("∏" to true),
    "coprod" to ("∐" to true),
    "bigcup" to ("⋃" to true),
    "bigcap" to ("⋂" to true),
    "bigoplus" to ("⊕" to true),
    "bigotimes" to ("⊗" to true),
    "int" to ("∫" to false),
    "iint" to ("∬" to false),
    "iiint" to ("∭" to false),
    "oint" to ("∮" to false),
    "lim" to ("lim" to true),
    "limsup" to ("lim sup" to true),
    "liminf" to ("lim inf" to true),
    "sup" to ("sup" to true),
    "inf" to ("inf" to true),
    "max" to ("max" to true),
    "min" to ("min" to true),
    "argmax" to ("arg max" to true),
    "argmin" to ("arg min" to true)
)

private val uprightFunctions = setOf(
    "sin", "cos", "tan", "sec", "csc", "cot",
    "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
    "log", "ln", "lg", "exp", "det", "dim", "ker", "gcd", "lcm",
    "deg", "hom", "arg", "Pr", "mod", "bmod", "pmod"
)

private val blackboardMap = mapOf(
    'R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ",
    'C' to "ℂ", 'H' to "ℍ", 'P' to "ℙ", 'E' to "𝔼"
)

private val operatorChars = setOf('+', '-', '=', '<', '>', '≤', '≥', '≠', '≈', '±')
private val punctuationChars = setOf('(', ')', '[', ']', '|', ',', '.', ';', ':', '!', '?')

private val namedColorMap = mapOf(
    "red" to Color(0xFFD32F2F),
    "vermelho" to Color(0xFFD32F2F),
    "blue" to Color(0xFF1976D2),
    "azul" to Color(0xFF1976D2),
    "green" to Color(0xFF2E7D32),
    "verde" to Color(0xFF2E7D32),
    "orange" to Color(0xFFEF6C00),
    "laranja" to Color(0xFFEF6C00),
    "purple" to Color(0xFF7B1FA2),
    "violet" to Color(0xFF7B1FA2),
    "roxo" to Color(0xFF7B1FA2),
    "teal" to Color(0xFF00796B),
    "cyan" to Color(0xFF00796B),
    "pink" to Color(0xFFC2185B),
    "magenta" to Color(0xFFC2185B),
    "gray" to Color(0xFF616161),
    "grey" to Color(0xFF616161),
    "cinza" to Color(0xFF616161)
)

private data class EnvSpec(val left: String, val right: String, val leftAligned: Boolean)

private val defaultEnvSpec = EnvSpec("", "", false)

private val matrixEnvironmentSpecs = mapOf(
    "pmatrix" to EnvSpec("(", ")", false),
    "bmatrix" to EnvSpec("[", "]", false),
    "Bmatrix" to EnvSpec("{", "}", false),
    "vmatrix" to EnvSpec("|", "|", false),
    "Vmatrix" to EnvSpec("‖", "‖", false),
    "cases" to EnvSpec("{", "", true),
    "matrix" to defaultEnvSpec,
    "smallmatrix" to defaultEnvSpec,
    "aligned" to EnvSpec("", "", true),
    "align" to EnvSpec("", "", true),
    "align*" to EnvSpec("", "", true),
    "gathered" to EnvSpec("", "", true),
    "gather" to EnvSpec("", "", true),
    "split" to EnvSpec("", "", true)
)

private val texAstCache = BoundedLruCache<String, TexNode>(256)
private val readableMathCache = BoundedLruCache<String, String>(256)

private fun parseNamedColor(name: String): Color? {
    val trimmed = name.trim()
    namedColorMap[trimmed.lowercase()]?.let { return it }
    val clean = trimmed.removePrefix("#")
    if (clean.length == 6 || clean.length == 8) {
        return clean.toLongOrNull(16)?.let { argb ->
            val full = if (clean.length == 6) (0xFF000000L or argb) else argb
            Color(full)
        }
    }
    return null
}

private const val MAX_TEX_DEPTH = 32

private class TexParser(private val src: String) {
    private var pos = 0
    private var depth = 0

    fun parseDocument(): TexNode {
        val rows = parseRowsUntil(stopEnv = null)
        return when {
            rows.isEmpty() -> TexNode.Row(emptyList())
            rows.size == 1 -> rows.first()
            else -> TexNode.LineStack(rows)
        }
    }

    private fun skipSpaces() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char? = src.getOrNull(pos)

    private fun startsWith(token: String): Boolean = src.startsWith(token, pos)

    private fun parseRowsUntil(stopEnv: String?): List<TexNode> {
        val rows = mutableListOf<TexNode>()
        while (pos < src.length) {
            if (stopEnv != null && startsWith("\\end{$stopEnv}")) break
            val row = parseSequence(stopAtRowBreak = true, stopOnEndEnv = stopEnv, stopOnBrace = false, stopOnRight = false)
            rows.add(row)
            if (startsWith("\\\\")) {
                pos += 2
                // Optional [spacing] after \\
                skipSpaces()
                if (peek() == '[') {
                    val endBracket = src.indexOf(']', pos + 1)
                    if (endBracket > pos) pos = endBracket + 1
                }
            } else {
                break
            }
        }
        return rows
    }

    private fun parseSequence(
        stopAtRowBreak: Boolean,
        stopOnEndEnv: String?,
        stopOnBrace: Boolean,
        stopOnRight: Boolean,
        stopOnCell: Boolean = false
    ): TexNode {
        if (depth >= MAX_TEX_DEPTH) return TexNode.TextRun("…", TextVariant.MONOSPACE)
        depth++
        try {
        val items = mutableListOf<TexNode>()
        var chooseSplitIndex = -1
        while (pos < src.length) {
            skipSpaces()
            if (pos >= src.length) break
            val c = src[pos]
            if (stopOnBrace && c == '}') break
            if (stopOnCell && c == '&') break
            if (stopAtRowBreak && startsWith("\\\\")) break
            if (stopOnEndEnv != null && startsWith("\\end{$stopOnEndEnv}")) break
            if (stopOnRight && startsWith("\\right")) break
            if (startsWith("\\choose") && (pos + 7 >= src.length || !src[pos + 7].isLetter())) {
                pos += 7
                chooseSplitIndex = items.size
                continue
            }
            items.add(parseScriptedAtom())
        }
        if (chooseSplitIndex >= 0) {
            val top = collapseRow(items.subList(0, chooseSplitIndex))
            val bot = collapseRow(items.subList(chooseSplitIndex, items.size))
            return TexNode.Delimited("(", ")", TexNode.Fraction(top, bot, drawBar = false))
        }
        return collapseRow(items)
        } finally {
            depth--
        }
    }

    private fun collapseRow(items: List<TexNode>): TexNode = when (items.size) {
        0 -> TexNode.Row(emptyList())
        1 -> items.first()
        else -> TexNode.Row(items)
    }

    private fun parseScriptedAtom(): TexNode {
        skipSpaces()
        if (pos >= src.length) return TexNode.Row(emptyList())
        var base = if (src[pos] == '^' || src[pos] == '_') {
            TexNode.Space(0.05f)
        } else {
            parseAtom()
        }

        // Big operators handle scripts cleanly in BigOp node
        var sub: TexNode? = null
        var sup: TexNode? = null
        var explicitLimits: Boolean? = null

        while (true) {
            skipSpaces()
            if (startsWith("\\limits")) {
                pos += 7
                explicitLimits = true
                continue
            }
            if (startsWith("\\nolimits")) {
                pos += 9
                explicitLimits = false
                continue
            }
            val c = peek() ?: break
            if (c == '_' && sub == null) {
                pos++
                sub = parseGroupOrSingleAtom()
            } else if (c == '^' && sup == null) {
                pos++
                sup = parseGroupOrSingleAtom()
            } else if (c == '\'') {
                var primes = 0
                while (peek() == '\'') {
                    primes++
                    pos++
                }
                val primeText = "′".repeat(primes)
                sup = TexNode.Symbol(primeText, upright = true)
            } else {
                break
            }
        }

        if (base is TexNode.BigOp) {
            val stacked = explicitLimits ?: base.stackLimits
            return if (sub != null || sup != null || explicitLimits != null) {
                base.copy(
                    sub = sub ?: base.sub,
                    sup = sup ?: base.sup,
                    stackLimits = stacked
                )
            } else base
        }

        return if (sub != null || sup != null) {
            TexNode.Script(base, sub = sub, sup = sup)
        } else {
            base
        }
    }

    private fun parseGroupOrSingleAtom(): TexNode {
        skipSpaces()
        if (pos >= src.length) return TexNode.Row(emptyList())
        return if (src[pos] == '{') {
            parseBracedGroup()
        } else {
            parseAtom()
        }
    }

    private fun parseBracedGroup(): TexNode {
        if (peek() != '{') return parseAtom()
        pos++ // skip '{'
        val inner = parseSequence(
            stopAtRowBreak = false,
            stopOnEndEnv = null,
            stopOnBrace = true,
            stopOnRight = false
        )
        if (peek() == '}') pos++
        return inner
    }

    private fun parseRawBracedText(): String {
        skipSpaces()
        if (peek() != '{') {
            return if (pos < src.length) src[pos++].toString() else ""
        }
        pos++ // skip '{'
        val sb = StringBuilder()
        var depth = 1
        while (pos < src.length && depth > 0) {
            val c = src[pos++]
            when (c) {
                '\\' -> {
                    if (pos < src.length) {
                        val next = src[pos++]
                        when (next) {
                            '{', '}', '$', '%', '&', '#', '_' -> sb.append(next)
                            ' ' -> sb.append(' ')
                            else -> sb.append('\\').append(next)
                        }
                    }
                }
                '{' -> {
                    depth++
                    sb.append(c)
                }
                '}' -> {
                    depth--
                    if (depth > 0) sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseBracketOptional(): String? {
        skipSpaces()
        if (peek() != '[') return null
        val start = pos + 1
        var depth = 1
        var p = start
        while (p < src.length && depth > 0) {
            when (src[p]) {
                '[' -> depth++
                ']' -> depth--
            }
            p++
        }
        if (depth == 0) {
            val content = src.substring(start, p - 1)
            pos = p
            return content
        }
        return null
    }

    private fun readDelimiterToken(): String {
        skipSpaces()
        if (pos >= src.length) return ""
        val c = src[pos]
        if (c == '\\') {
            pos++
            if (pos >= src.length) return ""
            if (!src[pos].isLetter()) {
                val ch = src[pos++]
                return when (ch) {
                    '|' -> "‖"
                    else -> ch.toString()
                }
            }
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val cmd = src.substring(start, pos)
            return when (cmd) {
                "langle" -> "⟨"
                "rangle" -> "⟩"
                "lvert", "rvert", "vert" -> "|"
                "lVert", "rVert", "Vert" -> "‖"
                "lfloor" -> "⌊"
                "rfloor" -> "⌋"
                "lceil" -> "⌈"
                "rceil" -> "⌉"
                "lbrace" -> "{"
                "rbrace" -> "}"
                else -> ""
            }
        }
        pos++
        return if (c == '.') "" else c.toString()
    }

    private fun parseAtom(): TexNode {
        skipSpaces()
        if (pos >= src.length) return TexNode.Row(emptyList())
        val c = src[pos]
        if (c == '{') return parseBracedGroup()
        if (c == '\\') return parseCommand()
        pos++
        return when {
            c.isDigit() -> {
                val start = pos - 1
                while (pos < src.length && (src[pos].isDigit() || (src[pos] == '.' && src.getOrNull(pos + 1)?.isDigit() == true))) {
                    pos++
                }
                TexNode.Symbol(src.substring(start, pos), upright = true)
            }
            c.isLetter() -> TexNode.Symbol(c.toString(), italic = true)
            c in operatorChars -> {
                val display = if (c == '-') "−" else c.toString()
                TexNode.Symbol(display, upright = true, isOperator = true)
            }
            c == '*' -> TexNode.Symbol("·", upright = true, isOperator = true)
            c == '/' -> TexNode.Symbol("/", upright = true)
            c in punctuationChars -> TexNode.Symbol(c.toString(), upright = true)
            else -> TexNode.Symbol(c.toString(), upright = true)
        }
    }

    private fun parseCommand(): TexNode {
        pos++ // skip '\'
        if (pos >= src.length) return TexNode.Symbol("\\", upright = true)
        val first = src[pos]
        if (!first.isLetter()) {
            pos++
            return when (first) {
                ',' -> TexNode.Space(0.18f)
                ':', '>' -> TexNode.Space(0.24f)
                ';' -> TexNode.Space(0.32f)
                '!' -> TexNode.Space(0f)
                ' ' -> TexNode.Space(0.28f)
                '|' -> TexNode.Symbol("‖", upright = true)
                '{', '}', '%', '$', '&', '#', '_' -> TexNode.Symbol(first.toString(), upright = true)
                else -> TexNode.Symbol(first.toString(), upright = true)
            }
        }

        val start = pos
        while (pos < src.length && src[pos].isLetter()) pos++
        val cmd = src.substring(start, pos)
        if (pos < src.length && src[pos] == '*') pos++

        // Check optional star e.g. \begin{align*}
        when (cmd) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val num = parseGroupOrSingleAtom()
                val den = parseGroupOrSingleAtom()
                return TexNode.Fraction(num, den, drawBar = true)
            }
            "binom", "tbinom", "dbinom" -> {
                val top = parseGroupOrSingleAtom()
                val bot = parseGroupOrSingleAtom()
                return TexNode.Delimited("(", ")", TexNode.Fraction(top, bot, drawBar = false))
            }
            "pmod" -> {
                val modInner = parseGroupOrSingleAtom()
                return TexNode.Row(
                    listOf(
                        TexNode.Space(0.32f),
                        TexNode.Delimited("(", ")", TexNode.Row(listOf(TexNode.TextRun("mod", TextVariant.UPRIGHT), TexNode.Space(0.22f), modInner)))
                    )
                )
            }
            "sqrt" -> {
                val indexRaw = parseBracketOptional()
                val indexNode = indexRaw?.takeIf { it.isNotBlank() }?.let { TexParser(it).parseDocument() }
                val rad = parseGroupOrSingleAtom()
                return TexNode.Root(rad, indexNode)
            }
            "vec" -> return TexNode.Accent(parseGroupOrSingleAtom(), AccentKind.VEC)
            "overrightarrow" -> return TexNode.Accent(parseGroupOrSingleAtom(), AccentKind.OVER_ARROW)
            "bar", "overline" -> return TexNode.Accent(parseGroupOrSingleAtom(), AccentKind.BAR)
            "underline" -> return TexNode.Accent(parseGroupOrSingleAtom(), AccentKind.UNDERLINE)
            "hat", "widehat" -> return TexNode.Accent(parseGroupOrSingleAtom(), AccentKind.HAT)
            "tilde", "widetilde" -> return TexNode.Accent(parseGroupOrSingleAtom(), AccentKind.TILDE)
            "dot" -> return TexNode.Accent(parseGroupOrSingleAtom(), AccentKind.DOT)
            "ddot" -> return TexNode.Accent(parseGroupOrSingleAtom(), AccentKind.DDOT)
            "boxed" -> return TexNode.Boxed(parseGroupOrSingleAtom())
            "cancel", "bcancel" -> return TexNode.Cancel(parseGroupOrSingleAtom())
            "color", "textcolor" -> {
                val colorName = parseRawBracedText()
                val inner = parseGroupOrSingleAtom()
                return TexNode.Colored(parseNamedColor(colorName), inner)
            }
            "text", "textrm", "mbox" -> return TexNode.TextRun(parseRawBracedText(), TextVariant.NORMAL)
            "mathrm", "operatorname" -> return TexNode.TextRun(parseRawBracedText(), TextVariant.UPRIGHT)
            "mathbf", "textbf", "boldsymbol", "bm" -> {
                val raw = parseRawBracedText()
                return if (raw.startsWith("\\")) {
                    val inner = TexParser(raw).parseDocument()
                    if (inner is TexNode.Symbol) inner.copy(bold = true) else inner
                } else {
                    TexNode.TextRun(raw, TextVariant.BOLD)
                }
            }
            "mathit", "textit" -> return TexNode.TextRun(parseRawBracedText(), TextVariant.ITALIC)
            "mathsf", "mathtt" -> return TexNode.TextRun(parseRawBracedText(), TextVariant.MONOSPACE)
            "mathbb" -> {
                val raw = parseRawBracedText()
                val mapped = raw.map { blackboardMap[it] ?: it.toString() }.joinToString("")
                return TexNode.TextRun(mapped, TextVariant.DOUBLE_STRUCK)
            }
            "mathcal" -> return TexNode.TextRun(parseRawBracedText(), TextVariant.CALLIGRAPHIC)
            "left" -> {
                val leftDelim = readDelimiterToken()
                val inner = parseSequence(
                    stopAtRowBreak = false,
                    stopOnEndEnv = null,
                    stopOnBrace = false,
                    stopOnRight = true
                )
                val rightDelim = if (startsWith("\\right")) {
                    pos += 6
                    readDelimiterToken()
                } else ""
                return TexNode.Delimited(leftDelim, rightDelim, inner)
            }
            "begin" -> return parseEnvironment()
            "quad" -> return TexNode.Space(0.9f)
            "qquad" -> return TexNode.Space(1.6f)
            "thinspace" -> return TexNode.Space(0.18f)
            "enskip" -> return TexNode.Space(0.5f)
            "not" -> {
                skipSpaces()
                val nextNode = parseAtom()
                if (nextNode is TexNode.Symbol) {
                    val negated = when (nextNode.text) {
                        "=" -> "≠"
                        "∈" -> "∉"
                        "⊂" -> "⊄"
                        "⊆" -> "⊈"
                        "≡" -> "≢"
                        else -> "¬" + nextNode.text
                    }
                    return nextNode.copy(text = negated)
                }
                return TexNode.Row(listOf(TexNode.Symbol("¬", upright = true), nextNode))
            }
        }

        greekSymbols[cmd]?.let { return TexNode.Symbol(it, italic = cmd.first().isLowerCase(), upright = cmd.first().isUpperCase()) }
        mathSymbols[cmd]?.let { return TexNode.Symbol(it, upright = true) }
        operatorSymbols[cmd]?.let { return TexNode.Symbol(it, upright = true, isOperator = true) }
        bigOperators[cmd]?.let { (sym, stack) -> return TexNode.BigOp(sym, stackLimits = stack) }
        if (cmd in uprightFunctions) return TexNode.TextRun(cmd, TextVariant.UPRIGHT)

        // Unknown command fallback: show cleanly without crashing
        return TexNode.TextRun(cmd, TextVariant.UPRIGHT)
    }

    private fun parseEnvironment(): TexNode {
        val envName = parseRawBracedText().trim()
        val (leftDelim, rightDelim, isLeftAligned) = matrixEnvironmentSpecs[envName] ?: defaultEnvSpec

        val matrixRows = mutableListOf<List<TexNode>>()
        while (pos < src.length) {
            skipSpaces()
            if (startsWith("\\end{$envName}")) {
                pos += 6 + envName.length // "\end{" + envName + "}"
                break
            }
            val rowCells = mutableListOf<TexNode>()
            while (pos < src.length) {
                val cell = parseSequence(
                    stopAtRowBreak = true,
                    stopOnEndEnv = envName,
                    stopOnBrace = false,
                    stopOnRight = false,
                    stopOnCell = true
                )
                rowCells.add(cell)
                if (peek() == '&') {
                    pos++
                } else {
                    break
                }
            }
            matrixRows.add(rowCells)
            if (startsWith("\\\\")) {
                pos += 2
                skipSpaces()
                if (peek() == '[') {
                    val close = src.indexOf(']', pos + 1)
                    if (close > pos) pos = close + 1
                }
            } else if (startsWith("\\end{$envName}")) {
                pos += 6 + envName.length
                break
            } else {
                break
            }
        }
        return TexNode.Matrix(matrixRows, leftDelim, rightDelim, isLeftAligned)
    }
}

internal fun isTexAstParsable(latex: String): Boolean = runCatching { parseTexAst(latex) }.isSuccess

private fun parseTexAst(latex: String): TexNode {
    val trimmed = latex.trim()
    if (trimmed.length <= 2048) {
        texAstCache.get(trimmed)?.let { return it }
    }
    val parsed = try {
        TexParser(trimmed).parseDocument()
    } catch (_: Exception) {
        TexNode.TextRun(trimmed, TextVariant.MONOSPACE)
    } catch (_: StackOverflowError) {
        TexNode.TextRun(trimmed, TextVariant.MONOSPACE)
    }
    if (trimmed.length <= 2048) {
        texAstCache.put(trimmed, parsed)
    }
    return parsed
}

private fun appendReadableTex(node: TexNode, sb: StringBuilder) {
    when (node) {
        is TexNode.Row -> node.children.forEach { appendReadableTex(it, sb) }
        is TexNode.Symbol -> {
            if (node.isOperator) {
                sb.append(' ').append(node.text).append(' ')
            } else {
                sb.append(node.text)
            }
        }
        is TexNode.Fraction -> {
            sb.append('(')
            appendReadableTex(node.numerator, sb)
            sb.append(")/(")
            appendReadableTex(node.denominator, sb)
            sb.append(')')
        }
        is TexNode.Root -> {
            node.index?.let { appendReadableTex(it, sb) }
            sb.append("√(")
            appendReadableTex(node.radicand, sb)
            sb.append(')')
        }
        is TexNode.Script -> {
            appendReadableTex(node.base, sb)
            node.sub?.let {
                sb.append('_')
                appendReadableTex(it, sb)
            }
            node.sup?.let {
                sb.append('^')
                appendReadableTex(it, sb)
            }
        }
        is TexNode.BigOp -> {
            sb.append(node.symbol)
            node.sub?.let {
                sb.append('_')
                appendReadableTex(it, sb)
            }
            node.sup?.let {
                sb.append('^')
                appendReadableTex(it, sb)
            }
        }
        is TexNode.Accent -> appendReadableTex(node.base, sb)
        is TexNode.Delimited -> {
            sb.append(node.left)
            appendReadableTex(node.inner, sb)
            sb.append(node.right)
        }
        is TexNode.Matrix -> {
            sb.append(node.leftDelim)
            node.rows.forEachIndexed { rIdx, row ->
                if (rIdx > 0) sb.append("; ")
                row.forEachIndexed { cIdx, cell ->
                    if (cIdx > 0) sb.append(", ")
                    appendReadableTex(cell, sb)
                }
            }
            sb.append(node.rightDelim)
        }
        is TexNode.TextRun -> sb.append(node.text)
        is TexNode.Space -> sb.append(' ')
        is TexNode.Boxed -> {
            sb.append('[')
            appendReadableTex(node.inner, sb)
            sb.append(']')
        }
        is TexNode.Cancel -> appendReadableTex(node.inner, sb)
        is TexNode.Colored -> appendReadableTex(node.inner, sb)
        is TexNode.LineStack -> {
            node.rows.forEachIndexed { idx, row ->
                if (idx > 0) sb.append(" | ")
                appendReadableTex(row, sb)
            }
        }
    }
}

/** Readable plain-Unicode approximation used for single-line / ellipsized previews. */
internal fun toReadableMathText(latex: String): String {
    val trimmed = latex.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length <= 2048) {
        readableMathCache.get(trimmed)?.let { return it }
    }
    val sb = StringBuilder(trimmed.length * 2)
    appendReadableTex(parseTexAst(trimmed), sb)
    val readable = multiWhitespaceRegex.replace(sb, " ").trim()
    if (trimmed.length <= 2048) {
        readableMathCache.put(trimmed, readable)
    }
    return readable
}

// ---------- Compose Visual Renderers ----------

@Composable
fun LatexFormulaBlock(
    latex: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val ast = remember(latex) { parseTexAst(latex) }
    val baseFontSp = if (textStyle.fontSize != TextUnit.Unspecified) textStyle.fontSize else 16.sp
    val blockFontSp = (baseFontSp.value * 1.12f).coerceIn(14f, 26f).sp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "Fórmula matemática: $latex" },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            LatexNodeView(
                node = ast,
                baseFontSp = blockFontSp,
                color = color,
                scale = 1f,
                displayMode = true
            )
        }
    }
}

@Composable
fun LatexInlineFormula(
    latex: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val ast = remember(latex) { parseTexAst(latex) }
    val baseFontSp = if (textStyle.fontSize != TextUnit.Unspecified) textStyle.fontSize else 15.sp
    Box(modifier = modifier) {
        LatexNodeView(
            node = ast,
            baseFontSp = baseFontSp,
            color = color,
            scale = 1f,
            displayMode = false
        )
    }
}

@Composable
fun RichInlineMathText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    // Fast-path plain prose without math or markdown delimiters to a single Text composable
    if (hasNoMathOrMarkupHint(text) || (!text.contains('$') && !text.contains("\\(") && !text.contains("\\["))) {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    val segments = remember(text) { parseMathSegments(text) }
    val hasMath = remember(segments) {
        segments.any { it is MathSegment.InlineMath || it is MathSegment.BlockMath }
    }

    if (!hasMath) {
        val plain = remember(segments, text) {
            if (segments.isEmpty()) text
            else if (segments.size == 1 && segments[0] is MathSegment.Text) (segments[0] as MathSegment.Text).value
            else buildString {
                segments.forEach { seg ->
                    if (seg is MathSegment.Text) append(seg.value)
                }
            }
        }
        Text(
            text = plain,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    if (maxLines < Int.MAX_VALUE) {
        val previewString = remember(segments) {
            buildPreviewMathText(segments)
        }
        Text(
            text = previewString,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    // Group into inline runs separated by BlockMath cards
    val paragraphs = remember(segments) { groupMathParagraphs(segments) }
    if (paragraphs.isEmpty()) return

    val baseFontSp = if (style.fontSize != TextUnit.Unspecified) style.fontSize else 15.sp

    if (paragraphs.size == 1 && paragraphs[0] is List<*>) {
        @Suppress("UNCHECKED_CAST")
        InlineMathParagraph(
            inlineSegs = paragraphs[0] as List<MathSegment>,
            style = style,
            color = color,
            baseFontSp = baseFontSp,
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        paragraphs.forEach { item ->
            when (item) {
                is MathSegment.BlockMath -> {
                    LatexFormulaBlock(
                        latex = item.latex,
                        textStyle = style,
                        color = color
                    )
                }
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    InlineMathParagraph(
                        inlineSegs = item as List<MathSegment>,
                        style = style,
                        color = color,
                        baseFontSp = baseFontSp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private fun buildPreviewMathText(segments: List<MathSegment>): String {
    val sb = StringBuilder()
    for (seg in segments) {
        val part = when (seg) {
            is MathSegment.Text -> seg.value.trim()
            is MathSegment.InlineMath -> toReadableMathText(seg.latex)
            is MathSegment.BlockMath -> toReadableMathText(seg.latex)
        }
        if (part.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(part)
        }
    }
    return multiWhitespaceRegex.replace(sb, " ").trim()
}

private fun trimAdjacentBlockNewlines(
    segs: MutableList<MathSegment>,
    afterBlock: Boolean,
    beforeBlock: Boolean
) {
    if (segs.isEmpty()) return
    if (afterBlock) {
        val first = segs.first()
        if (first is MathSegment.Text) {
            var i = 0
            while (i < first.value.length && (first.value[i] == '\r' || first.value[i] == '\n')) i++
            if (i > 0) segs[0] = MathSegment.Text(first.value.substring(i))
        }
    }
    if (beforeBlock) {
        val last = segs.last()
        if (last is MathSegment.Text) {
            var end = last.value.length
            while (end > 0 && (last.value[end - 1] == '\r' || last.value[end - 1] == '\n')) end--
            if (end < last.value.length) segs[segs.lastIndex] = MathSegment.Text(last.value.substring(0, end))
        }
    }
}

private fun hasVisibleInlineContent(segs: List<MathSegment>): Boolean {
    var hasText = false
    for (seg in segs) {
        when (seg) {
            is MathSegment.InlineMath -> if (seg.latex.isNotBlank()) return true
            is MathSegment.Text -> if (seg.value.isNotBlank()) hasText = true
            is MathSegment.BlockMath -> return true
        }
    }
    return hasText
}

private fun groupMathParagraphs(segments: List<MathSegment>): List<Any> {
    val out = mutableListOf<Any>() // List<MathSegment> for inline paragraph, or MathSegment.BlockMath
    val currentInline = mutableListOf<MathSegment>()
    var seenBlock = false
    for (seg in segments) {
        if (seg is MathSegment.BlockMath) {
            if (currentInline.isNotEmpty()) {
                trimAdjacentBlockNewlines(currentInline, afterBlock = seenBlock, beforeBlock = true)
                if (hasVisibleInlineContent(currentInline)) {
                    out.add(currentInline.toList())
                }
                currentInline.clear()
            }
            out.add(seg)
            seenBlock = true
        } else {
            currentInline.add(seg)
        }
    }
    if (currentInline.isNotEmpty()) {
        trimAdjacentBlockNewlines(currentInline, afterBlock = seenBlock, beforeBlock = false)
        if (hasVisibleInlineContent(currentInline)) {
            out.add(currentInline.toList())
        }
    }
    return out
}

private data class InlineMathEntry(
    val id: String,
    val ast: TexNode,
    val sizePx: IntSize
)

private data class InlineMathParagraphData(
    val annotated: AnnotatedString,
    val entries: List<InlineMathEntry>
)

@Composable
private fun InlineMathParagraph(
    inlineSegs: List<MathSegment>,
    style: TextStyle,
    color: Color,
    baseFontSp: TextUnit,
    modifier: Modifier = Modifier
) {
    val hasInlineMath = remember(inlineSegs) {
        inlineSegs.any { it is MathSegment.InlineMath && it.latex.isNotBlank() }
    }
    if (!hasInlineMath) {
        val plainText = remember(inlineSegs) {
            if (inlineSegs.size == 1 && inlineSegs[0] is MathSegment.Text) {
                (inlineSegs[0] as MathSegment.Text).value
            } else {
                buildString {
                    inlineSegs.forEach { seg ->
                        if (seg is MathSegment.Text) append(seg.value)
                    }
                }
            }
        }
        if (plainText.isNotEmpty()) {
            Text(
                text = plainText,
                modifier = modifier,
                style = style,
                color = color
            )
        }
        return
    }

    val density = LocalDensity.current
    val localTextStyle = LocalTextStyle.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)

    val paragraphData = remember(inlineSegs, baseFontSp, density, localTextStyle, textMeasurer) {
        val idByLatex = LinkedHashMap<String, String>()
        val entries = mutableListOf<InlineMathEntry>()
        val annotated = buildAnnotatedString {
            inlineSegs.forEach { seg ->
                when (seg) {
                    is MathSegment.Text -> append(seg.value)
                    is MathSegment.InlineMath -> {
                        if (seg.latex.isNotBlank()) {
                            val id = idByLatex.getOrPut(seg.latex) {
                                val nextId = "m_${idByLatex.size}"
                                val ast = parseTexAst(seg.latex)
                                val sizePx = measureTexNodePx(
                                    node = ast,
                                    baseFontSp = baseFontSp,
                                    scale = 1f,
                                    displayMode = false,
                                    density = density,
                                    localTextStyle = localTextStyle,
                                    textMeasurer = textMeasurer
                                )
                                entries.add(
                                    InlineMathEntry(
                                        id = nextId,
                                        ast = ast,
                                        sizePx = sizePx
                                    )
                                )
                                nextId
                            }
                            val alt = toReadableMathText(seg.latex).ifEmpty { "□" }
                            appendInlineContent(id = id, alternateText = alt)
                        }
                    }
                    is MathSegment.BlockMath -> Unit
                }
            }
        }
        InlineMathParagraphData(annotated = annotated, entries = entries)
    }

    if (paragraphData.annotated.isEmpty()) return

    val hPadPx = with(density) { 2.dp.roundToPx() } * 2
    val inlineContent = remember(paragraphData, baseFontSp, color, density, hPadPx) {
        paragraphData.entries.associate { entry ->
            val wSp = with(density) { (entry.sizePx.width + hPadPx + 4).coerceAtLeast(1).toSp() }
            val hSp = with(density) { (entry.sizePx.height + 4).coerceAtLeast(1).toSp() }
            entry.id to InlineTextContent(
                placeholder = Placeholder(
                    width = wSp,
                    height = hSp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LatexNodeView(
                        node = entry.ast,
                        baseFontSp = baseFontSp,
                        color = color,
                        scale = 1f,
                        displayMode = false,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }

    Text(
        text = paragraphData.annotated,
        inlineContent = inlineContent,
        modifier = modifier,
        style = style,
        color = color
    )
}

private fun delimiterWidthDp(symbol: String): Dp = when (symbol) {
    "‖" -> 7.dp
    "|" -> 5.dp
    "{", "}" -> 9.dp
    else -> 8.dp
}

private fun measureTexNodePx(
    node: TexNode,
    baseFontSp: TextUnit,
    scale: Float,
    displayMode: Boolean,
    density: Density,
    localTextStyle: TextStyle,
    textMeasurer: TextMeasurer
): IntSize {
    val effectiveScale = scale.coerceIn(0.56f, 1.45f)
    val fontSize = (baseFontSp.value * effectiveScale).sp
    val strokeDp = (1.15f * effectiveScale).coerceAtLeast(0.85f).dp

    return when (node) {
        is TexNode.Row -> {
            var w = 0
            var h = 0
            for (child in node.children) {
                val s = measureTexNodePx(child, baseFontSp, effectiveScale, displayMode, density, localTextStyle, textMeasurer)
                w += s.width
                if (s.height > h) h = s.height
            }
            IntSize(w, h)
        }

        is TexNode.LineStack -> {
            val gapPx = with(density) { 4.dp.roundToPx() }
            var maxW = 0
            var totalH = 0
            for (i in node.rows.indices) {
                val s = measureTexNodePx(node.rows[i], baseFontSp, effectiveScale, displayMode, density, localTextStyle, textMeasurer)
                if (s.width > maxW) maxW = s.width
                totalH += s.height
                if (i > 0) totalH += gapPx
            }
            IntSize(maxW, totalH)
        }

        is TexNode.Symbol -> {
            val hPad = if (node.isOperator) (3.dp * effectiveScale) else 0.3.dp
            val hPadPx = with(density) { hPad.roundToPx() } * 2
            val measured = textMeasurer.measure(
                text = node.text,
                style = localTextStyle.merge(
                    TextStyle(
                        fontSize = (fontSize.value * node.scale).sp,
                        fontFamily = if (node.upright) FontFamily.Default else FontFamily.Serif,
                        fontStyle = if (node.italic && !node.upright) FontStyle.Italic else FontStyle.Normal,
                        fontWeight = if (node.bold) FontWeight.Bold else FontWeight.Normal
                    )
                ),
                softWrap = false,
                maxLines = 1
            ).size
            IntSize(measured.width + hPadPx, measured.height)
        }

        is TexNode.TextRun -> {
            val family = when (node.variant) {
                TextVariant.MONOSPACE -> FontFamily.Monospace
                TextVariant.ITALIC, TextVariant.CALLIGRAPHIC -> FontFamily.Serif
                else -> FontFamily.Default
            }
            val fontStyle = when (node.variant) {
                TextVariant.ITALIC, TextVariant.CALLIGRAPHIC -> FontStyle.Italic
                else -> FontStyle.Normal
            }
            val weight = when (node.variant) {
                TextVariant.BOLD, TextVariant.DOUBLE_STRUCK -> FontWeight.Bold
                else -> FontWeight.Normal
            }
            val hPadPx = with(density) { 1.dp.roundToPx() } * 2
            val measured = textMeasurer.measure(
                text = node.text,
                style = localTextStyle.merge(
                    TextStyle(
                        fontSize = fontSize,
                        fontFamily = family,
                        fontStyle = fontStyle,
                        fontWeight = weight
                    )
                ),
                softWrap = false,
                maxLines = 1
            ).size
            IntSize(measured.width + hPadPx, measured.height)
        }

        is TexNode.Space -> {
            val w = with(density) { (fontSize.value * node.em).dp.roundToPx() }.coerceAtLeast(0)
            IntSize(w, 0)
        }

        is TexNode.Fraction -> {
            val childScale = (effectiveScale * (if (displayMode) 0.86f else 0.78f)).coerceAtLeast(0.58f)
            val numSize = measureTexNodePx(node.numerator, baseFontSp, childScale, displayMode, density, localTextStyle, textMeasurer)
            val denSize = measureTexNodePx(node.denominator, baseFontSp, childScale, displayMode, density, localTextStyle, textMeasurer)
            val outerHPx = with(density) { 3.dp.roundToPx() } * 2
            val innerHPx = with(density) { 3.dp.roundToPx() } * 2
            val innerVPx = with(density) { 1.dp.roundToPx() } * 2
            val barHPx = with(density) { (if (node.drawBar) strokeDp else 2.dp).roundToPx() }.coerceAtLeast(if (node.drawBar) 1 else 0)
            val innerW = max(numSize.width + innerHPx, denSize.width + innerHPx)
            val totalH = numSize.height + innerVPx + barHPx + denSize.height + innerVPx
            IntSize(outerHPx + innerW, totalH)
        }

        is TexNode.Root -> {
            val radicalWidthDp = (fontSize.value * 0.62f).coerceAtLeast(9f).dp
            val topPadDp = (strokeDp * 3.2f).coerceAtLeast(3.dp)
            val indexScale = (effectiveScale * 0.58f).coerceAtLeast(0.52f)
            val radSize = measureTexNodePx(node.radicand, baseFontSp, effectiveScale, displayMode, density, localTextStyle, textMeasurer)
            val idxSize = node.index?.let {
                measureTexNodePx(it, baseFontSp, indexScale, false, density, localTextStyle, textMeasurer)
            }
            val outerHPx = with(density) { 1.dp.roundToPx() } * 2
            val radStartPx = maxOf(with(density) { (if (node.index != null) radicalWidthDp + 4.dp else radicalWidthDp).roundToPx() }, (idxSize?.width ?: 0) + with(density) { 4.dp.roundToPx() })
            val radEndPx = with(density) { 3.dp.roundToPx() }
            val radTopPx = with(density) { topPadDp.roundToPx() }
            val radBottomPx = with(density) { 1.dp.roundToPx() }
            val idxExtraPx = with(density) { 2.dp.roundToPx() }
            val idxW = if (idxSize != null) idxSize.width + idxExtraPx else 0
            val idxH = if (idxSize != null) idxSize.height + idxExtraPx else 0
            val innerW = max(radStartPx + radSize.width + radEndPx, idxW)
            val innerH = max(radTopPx + radSize.height + radBottomPx, idxH)
            IntSize(outerHPx + innerW, innerH)
        }

        is TexNode.Script -> {
            val scriptScale = (effectiveScale * 0.68f).coerceAtLeast(0.56f)
            val baseSize = measureTexNodePx(node.base, baseFontSp, effectiveScale, displayMode, density, localTextStyle, textMeasurer)
            val supSize = node.sup?.let { measureTexNodePx(it, baseFontSp, scriptScale, false, density, localTextStyle, textMeasurer) }
            val subSize = node.sub?.let { measureTexNodePx(it, baseFontSp, scriptScale, false, density, localTextStyle, textMeasurer) }
            val supRaise = if (supSize != null) (supSize.height * 0.42f).roundToInt() else 0
            val subDrop = if (subSize != null) (subSize.height * 0.34f).roundToInt() else 0
            val baseTop = supRaise
            val baseBottom = baseTop + baseSize.height
            val subY = if (subSize != null) {
                (baseBottom - subSize.height + subDrop).coerceAtLeast(supSize?.height ?: baseTop)
            } else 0
            val w = baseSize.width + max(supSize?.width ?: 0, subSize?.width ?: 0)
            val h = max(baseBottom, if (subSize != null) subY + subSize.height else 0)
            IntSize(w, h)
        }

        is TexNode.BigOp -> {
            val limitScale = (effectiveScale * 0.66f).coerceAtLeast(0.56f)
            val isWordOp = node.symbol.all { it.isLetter() || it == ' ' }
            val symbolFontSp = if (isWordOp) fontSize else (fontSize.value * (if (displayMode) 1.38f else 1.18f)).sp
            val symMeasured = textMeasurer.measure(
                text = node.symbol,
                style = localTextStyle.merge(
                    TextStyle(
                        fontSize = symbolFontSp,
                        fontFamily = if (isWordOp) FontFamily.Default else FontFamily.Serif,
                        fontWeight = if (isWordOp) FontWeight.Medium else FontWeight.Normal
                    )
                ),
                softWrap = false,
                maxLines = 1
            ).size
            val supSize = node.sup?.let { measureTexNodePx(it, baseFontSp, limitScale, false, density, localTextStyle, textMeasurer) }
            val subSize = node.sub?.let { measureTexNodePx(it, baseFontSp, limitScale, false, density, localTextStyle, textMeasurer) }
            if (node.stackLimits) {
                val hPadPx = with(density) { 3.dp.roundToPx() } * 2
                val w = max(symMeasured.width, max(supSize?.width ?: 0, subSize?.width ?: 0)) + hPadPx
                val h = (supSize?.height ?: 0) + symMeasured.height + (subSize?.height ?: 0)
                IntSize(w, h)
            } else {
                val hPadPx = with(density) { 2.dp.roundToPx() } * 2
                val supRaise = if (supSize != null) (supSize.height * 0.42f).roundToInt() else 0
                val subDrop = if (subSize != null) (subSize.height * 0.34f).roundToInt() else 0
                val baseTop = supRaise
                val baseBottom = baseTop + symMeasured.height
                val subY = if (subSize != null) {
                    (baseBottom - subSize.height + subDrop).coerceAtLeast(supSize?.height ?: baseTop)
                } else 0
                val w = symMeasured.width + max(supSize?.width ?: 0, subSize?.width ?: 0) + hPadPx
                val h = max(baseBottom, if (subSize != null) subY + subSize.height else 0)
                IntSize(w, h)
            }
        }

        is TexNode.Accent -> {
            val topSpace = if (node.kind == AccentKind.UNDERLINE) 0.dp else (fontSize.value * 0.34f).coerceAtLeast(5f).dp
            val botSpace = if (node.kind == AccentKind.UNDERLINE) 3.dp else 0.dp
            val baseSize = measureTexNodePx(node.base, baseFontSp, effectiveScale, displayMode, density, localTextStyle, textMeasurer)
            val hPadPx = with(density) { 1.dp.roundToPx() } * 2
            val vPadPx = with(density) { topSpace.roundToPx() + botSpace.roundToPx() }
            IntSize(baseSize.width + hPadPx, baseSize.height + vPadPx)
        }

        is TexNode.Delimited -> {
            val leftW = if (node.left.isNotEmpty()) with(density) { delimiterWidthDp(node.left).roundToPx() } else 0
            val rightW = if (node.right.isNotEmpty()) with(density) { delimiterWidthDp(node.right).roundToPx() } else 0
            val innerSize = measureTexNodePx(node.inner, baseFontSp, effectiveScale, displayMode, density, localTextStyle, textMeasurer)
            val innerPadPx = with(density) { 2.dp.roundToPx() } * 2
            val minHPx = with(density) { (fontSize.value * 1.25f).dp.coerceAtLeast(18.dp).roundToPx() }
            IntSize(leftW + innerSize.width + innerPadPx + rightW, max(innerSize.height, minHPx))
        }

        is TexNode.Matrix -> {
            val cols = node.rows.maxOfOrNull { it.size } ?: 0
            if (cols == 0) return IntSize.Zero
            val cellScale = (effectiveScale * 0.92f).coerceAtLeast(0.62f)
            val colWidths = IntArray(cols)
            val rowHeights = IntArray(node.rows.size)
            for (r in node.rows.indices) {
                val row = node.rows[r]
                for (c in 0 until cols) {
                    val cell = row.getOrNull(c) ?: continue
                    val s = measureTexNodePx(cell, baseFontSp, cellScale, false, density, localTextStyle, textMeasurer)
                    if (s.width > colWidths[c]) colWidths[c] = s.width
                    if (s.height > rowHeights[r]) rowHeights[r] = s.height
                }
            }
            val hGapPx = with(density) { 10.dp.roundToPx() }
            val vGapPx = with(density) { 5.dp.roundToPx() }
            val gridW = colWidths.sum() + max(0, cols - 1) * hGapPx
            val gridH = rowHeights.sum() + max(0, node.rows.size - 1) * vGapPx
            val gridPadH = with(density) { 4.dp.roundToPx() } * 2
            val gridPadV = with(density) { 2.dp.roundToPx() } * 2
            val outerPadH = with(density) { 2.dp.roundToPx() } * 2
            val leftW = if (node.leftDelim.isNotEmpty()) with(density) { delimiterWidthDp(node.leftDelim).roundToPx() } else 0
            val rightW = if (node.rightDelim.isNotEmpty()) with(density) { delimiterWidthDp(node.rightDelim).roundToPx() } else 0
            val minHPx = with(density) { (node.rows.size * fontSize.value * 1.25f).dp.coerceAtLeast(18.dp).roundToPx() }
            val middleW = gridW + gridPadH
            val middleH = gridH + gridPadV
            IntSize(outerPadH + leftW + middleW + rightW, max(middleH, minHPx))
        }

        is TexNode.Boxed -> {
            val innerSize = measureTexNodePx(node.inner, baseFontSp, effectiveScale, displayMode, density, localTextStyle, textMeasurer)
            val outerH = with(density) { 2.dp.roundToPx() } * 2
            val innerH = with(density) { 6.dp.roundToPx() } * 2
            val innerV = with(density) { 3.dp.roundToPx() } * 2
            IntSize(innerSize.width + outerH + innerH, innerSize.height + innerV)
        }

        is TexNode.Cancel -> measureTexNodePx(node.inner, baseFontSp, effectiveScale, displayMode, density, localTextStyle, textMeasurer)
        is TexNode.Colored -> measureTexNodePx(node.inner, baseFontSp, effectiveScale, displayMode, density, localTextStyle, textMeasurer)
    }
}

// ---------- Recursive Compose AST Renderer ----------

@Composable
private fun LatexNodeView(
    node: TexNode,
    baseFontSp: TextUnit,
    color: Color,
    scale: Float,
    displayMode: Boolean,
    modifier: Modifier = Modifier
) {
    val effectiveScale = scale.coerceIn(0.56f, 1.45f)
    val fontSize = (baseFontSp.value * effectiveScale).sp
    val strokeDp = (1.15f * effectiveScale).coerceAtLeast(0.85f).dp

    when (node) {
        is TexNode.Row -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                node.children.forEach { child ->
                    LatexNodeView(
                        node = child,
                        baseFontSp = baseFontSp,
                        color = color,
                        scale = effectiveScale,
                        displayMode = displayMode
                    )
                }
            }
        }

        is TexNode.LineStack -> {
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                node.rows.forEach { row ->
                    LatexNodeView(
                        node = row,
                        baseFontSp = baseFontSp,
                        color = color,
                        scale = effectiveScale,
                        displayMode = displayMode
                    )
                }
            }
        }

        is TexNode.Symbol -> {
            val hPad = if (node.isOperator) (3.dp * effectiveScale) else 0.3.dp
            Text(
                text = node.text,
                color = color,
                fontSize = (fontSize.value * node.scale).sp,
                fontFamily = if (node.upright) FontFamily.Default else FontFamily.Serif,
                fontStyle = if (node.italic && !node.upright) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (node.bold) FontWeight.Bold else FontWeight.Normal,
                softWrap = false,
                modifier = modifier.padding(horizontal = hPad)
            )
        }

        is TexNode.TextRun -> {
            val family = when (node.variant) {
                TextVariant.MONOSPACE -> FontFamily.Monospace
                TextVariant.ITALIC, TextVariant.CALLIGRAPHIC -> FontFamily.Serif
                else -> FontFamily.Default
            }
            val fontStyle = when (node.variant) {
                TextVariant.ITALIC, TextVariant.CALLIGRAPHIC -> FontStyle.Italic
                else -> FontStyle.Normal
            }
            val weight = when (node.variant) {
                TextVariant.BOLD, TextVariant.DOUBLE_STRUCK -> FontWeight.Bold
                else -> FontWeight.Normal
            }
            Text(
                text = node.text,
                color = color,
                fontSize = fontSize,
                fontFamily = family,
                fontStyle = fontStyle,
                fontWeight = weight,
                softWrap = false,
                modifier = modifier.padding(horizontal = 1.dp)
            )
        }

        is TexNode.Space -> {
            Spacer(modifier = modifier.width((fontSize.value * node.em).dp))
        }

        is TexNode.Fraction -> {
            val childScale = (effectiveScale * (if (displayMode) 0.86f else 0.78f)).coerceAtLeast(0.58f)
            FractionLayout(
                drawBar = node.drawBar,
                strokeDp = strokeDp,
                color = color,
                modifier = modifier,
                numerator = { LatexNodeView(node.numerator, baseFontSp, color, childScale, displayMode) },
                denominator = { LatexNodeView(node.denominator, baseFontSp, color, childScale, displayMode) }
            )
        }

        is TexNode.Root -> {
            val density = LocalDensity.current
            val strokePx = with(density) { strokeDp.toPx() }
            val radicalWidthDp = (fontSize.value * 0.62f).coerceAtLeast(9f).dp
            val topPadDp = (strokeDp * 3.2f).coerceAtLeast(3.dp)
            val indexScale = (effectiveScale * 0.58f).coerceAtLeast(0.52f)
            val indexCharLen = remember(node.index) {
                if (node.index == null) 0 else {
                    val sb = StringBuilder()
                    appendReadableTex(node.index, sb)
                    sb.length.coerceAtLeast(1)
                }
            }
            val extraIndexOffsetDp = if (node.index != null) {
                ((indexCharLen - 1).coerceAtLeast(0) * fontSize.value * 0.34f + 4f).dp
            } else 0.dp
            val radicalPath = remember { Path() }
            val radicalStroke = remember(strokePx) {
                Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            }

            Box(modifier = modifier.padding(horizontal = 1.dp)) {
                if (node.index != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(end = 2.dp, bottom = 2.dp)
                    ) {
                        LatexNodeView(node.index, baseFontSp, color, indexScale, displayMode = false)
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(
                            start = radicalWidthDp + extraIndexOffsetDp,
                            top = topPadDp,
                            end = 3.dp,
                            bottom = 1.dp
                        )
                ) {
                    LatexNodeView(node.radicand, baseFontSp, color, effectiveScale, displayMode)
                }
                Canvas(modifier = Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    val leftInset = extraIndexOffsetDp.toPx()
                    val radW = radicalWidthDp.toPx() + leftInset
                    val topY = strokePx * 1.1f
                    radicalPath.rewind()
                    radicalPath.moveTo(leftInset + 1f, h * 0.56f)
                    radicalPath.lineTo(leftInset + (radW - leftInset) * 0.28f, h * 0.48f)
                    radicalPath.lineTo(leftInset + (radW - leftInset) * 0.56f, h - strokePx * 1.1f)
                    radicalPath.lineTo(radW - strokePx * 0.4f, topY)
                    radicalPath.lineTo(w - 1f, topY)
                    drawPath(
                        path = radicalPath,
                        color = color,
                        style = radicalStroke
                    )
                }
            }
        }

        is TexNode.Script -> {
            val scriptScale = (effectiveScale * 0.68f).coerceAtLeast(0.56f)
            ScriptLayout(
                modifier = modifier,
                base = { LatexNodeView(node.base, baseFontSp, color, effectiveScale, displayMode) },
                sup = node.sup?.let { supNode ->
                    { LatexNodeView(supNode, baseFontSp, color, scriptScale, displayMode = false) }
                },
                sub = node.sub?.let { subNode ->
                    { LatexNodeView(subNode, baseFontSp, color, scriptScale, displayMode = false) }
                }
            )
        }

        is TexNode.BigOp -> {
            val limitScale = (effectiveScale * 0.66f).coerceAtLeast(0.56f)
            val isWordOp = node.symbol.all { it.isLetter() || it == ' ' }
            val symbolFontSp = if (isWordOp) fontSize else (fontSize.value * (if (displayMode) 1.38f else 1.18f)).sp

            if (node.stackLimits) {
                Column(
                    modifier = modifier.padding(horizontal = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (node.sup != null) {
                        LatexNodeView(node.sup, baseFontSp, color, limitScale, displayMode = false)
                    }
                    Text(
                        text = node.symbol,
                        color = color,
                        fontSize = symbolFontSp,
                        fontFamily = if (isWordOp) FontFamily.Default else FontFamily.Serif,
                        fontWeight = if (isWordOp) FontWeight.Medium else FontWeight.Normal,
                        softWrap = false
                    )
                    if (node.sub != null) {
                        LatexNodeView(node.sub, baseFontSp, color, limitScale, displayMode = false)
                    }
                }
            } else {
                ScriptLayout(
                    modifier = modifier.padding(horizontal = 2.dp),
                    base = {
                        Text(
                            text = node.symbol,
                            color = color,
                            fontSize = symbolFontSp,
                            fontFamily = FontFamily.Serif,
                            softWrap = false
                        )
                    },
                    sup = node.sup?.let { supNode ->
                        { LatexNodeView(supNode, baseFontSp, color, limitScale, displayMode = false) }
                    },
                    sub = node.sub?.let { subNode ->
                        { LatexNodeView(subNode, baseFontSp, color, limitScale, displayMode = false) }
                    }
                )
            }
        }

        is TexNode.Accent -> {
            val density = LocalDensity.current
            val strokePx = with(density) { strokeDp.toPx() }
            val topSpace = if (node.kind == AccentKind.UNDERLINE) 0.dp else (fontSize.value * 0.34f).coerceAtLeast(5f).dp
            val botSpace = if (node.kind == AccentKind.UNDERLINE) 3.dp else 0.dp
            val accentPath = remember { Path() }
            val accentStroke = remember(strokePx) {
                Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            }
            Box(
                modifier = modifier
                    .padding(horizontal = 1.dp)
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val topMid = (topSpace.toPx() * 0.55f).coerceAtLeast(strokePx)
                        when (node.kind) {
                            AccentKind.BAR -> {
                                drawLine(color, Offset(1f, topMid), Offset(w - 1f, topMid), strokeWidth = strokePx, cap = StrokeCap.Round)
                            }
                            AccentKind.UNDERLINE -> {
                                val y = h - strokePx
                                drawLine(color, Offset(1f, y), Offset(w - 1f, y), strokeWidth = strokePx, cap = StrokeCap.Round)
                            }
                            AccentKind.VEC, AccentKind.OVER_ARROW -> {
                                val arrowEnd = (w - 1.5f).coerceAtLeast(6f)
                                val head = (4.dp.toPx()).coerceAtMost(w * 0.38f)
                                drawLine(color, Offset(1.5f, topMid), Offset(arrowEnd, topMid), strokeWidth = strokePx, cap = StrokeCap.Round)
                                accentPath.rewind()
                                accentPath.moveTo(arrowEnd - head, topMid - head * 0.65f)
                                accentPath.lineTo(arrowEnd, topMid)
                                accentPath.lineTo(arrowEnd - head, topMid + head * 0.65f)
                                drawPath(accentPath, color, style = accentStroke)
                            }
                            AccentKind.HAT -> {
                                val peakY = (topMid - 2.dp.toPx()).coerceAtLeast(strokePx * 0.6f)
                                val baseY = topMid + 1.5.dp.toPx()
                                accentPath.rewind()
                                accentPath.moveTo(2f, baseY)
                                accentPath.lineTo(w / 2f, peakY)
                                accentPath.lineTo(w - 2f, baseY)
                                drawPath(accentPath, color, style = accentStroke)
                            }
                            AccentKind.TILDE -> {
                                accentPath.rewind()
                                accentPath.moveTo(1.5f, topMid + 1f)
                                accentPath.cubicTo(w * 0.3f, topMid - 3.dp.toPx(), w * 0.7f, topMid + 3.dp.toPx(), w - 1.5f, topMid - 1f)
                                drawPath(accentPath, color, style = accentStroke)
                            }
                            AccentKind.DOT -> {
                                drawCircle(color, radius = strokePx * 0.95f, center = Offset(w / 2f, topMid))
                            }
                            AccentKind.DDOT -> {
                                val dx = (w * 0.18f).coerceAtLeast(2.2.dp.toPx())
                                drawCircle(color, radius = strokePx * 0.9f, center = Offset(w / 2f - dx, topMid))
                                drawCircle(color, radius = strokePx * 0.9f, center = Offset(w / 2f + dx, topMid))
                            }
                        }
                    }
                    .padding(top = topSpace, bottom = botSpace)
            ) {
                LatexNodeView(node.base, baseFontSp, color, effectiveScale, displayMode)
            }
        }

        is TexNode.Delimited -> {
            val minDelimH = (fontSize.value * 1.25f).dp
            DelimitedRowLayout(
                minHeightDp = minDelimH,
                modifier = modifier,
                left = if (node.left.isNotEmpty()) {
                    { delimMod ->
                        StretchyDelimiter(
                            symbol = node.left,
                            isLeft = true,
                            color = color,
                            strokeDp = strokeDp,
                            minHeightDp = minDelimH,
                            modifier = delimMod
                        )
                    }
                } else null,
                right = if (node.right.isNotEmpty()) {
                    { delimMod ->
                        StretchyDelimiter(
                            symbol = node.right,
                            isLeft = false,
                            color = color,
                            strokeDp = strokeDp,
                            minHeightDp = minDelimH,
                            modifier = delimMod
                        )
                    }
                } else null
            ) {
                Box(Modifier.padding(horizontal = 2.dp)) {
                    LatexNodeView(node.inner, baseFontSp, color, effectiveScale, displayMode)
                }
            }
        }

        is TexNode.Matrix -> {
            val cols = node.rows.maxOfOrNull { it.size } ?: 0
            if (cols == 0) return
            val cellScale = (effectiveScale * 0.92f).coerceAtLeast(0.62f)
            val minDelimH = (node.rows.size * fontSize.value * 1.25f).dp
            DelimitedRowLayout(
                minHeightDp = minDelimH,
                modifier = modifier.padding(horizontal = 2.dp),
                left = if (node.leftDelim.isNotEmpty()) {
                    { delimMod ->
                        StretchyDelimiter(
                            symbol = node.leftDelim,
                            isLeft = true,
                            color = color,
                            strokeDp = strokeDp,
                            minHeightDp = minDelimH,
                            modifier = delimMod
                        )
                    }
                } else null,
                right = if (node.rightDelim.isNotEmpty()) {
                    { delimMod ->
                        StretchyDelimiter(
                            symbol = node.rightDelim,
                            isLeft = false,
                            color = color,
                            strokeDp = strokeDp,
                            minHeightDp = minDelimH,
                            modifier = delimMod
                        )
                    }
                } else null
            ) {
                MatrixGridLayout(
                    rows = node.rows.size,
                    cols = cols,
                    leftAligned = node.leftAligned,
                    hGap = 10.dp,
                    vGap = 5.dp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    node.rows.forEach { row ->
                        for (c in 0 until cols) {
                            val cell = row.getOrNull(c) ?: TexNode.Row(emptyList())
                            Box(contentAlignment = if (node.leftAligned) Alignment.CenterStart else Alignment.Center) {
                                LatexNodeView(cell, baseFontSp, color, cellScale, displayMode = false)
                            }
                        }
                    }
                }
            }
        }

        is TexNode.Boxed -> {
            Surface(
                modifier = modifier.padding(horizontal = 2.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Transparent,
                border = BorderStroke(strokeDp, color)
            ) {
                Box(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    LatexNodeView(node.inner, baseFontSp, color, effectiveScale, displayMode)
                }
            }
        }

        is TexNode.Cancel -> {
            val density = LocalDensity.current
            val strokePx = with(density) { strokeDp.toPx() }
            val cancelColor = remember(color) { color.copy(alpha = 0.78f) }
            Box(
                modifier = modifier.drawBehind {
                    drawLine(
                        color = cancelColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f),
                        strokeWidth = strokePx,
                        cap = StrokeCap.Round
                    )
                }
            ) {
                LatexNodeView(node.inner, baseFontSp, color, effectiveScale, displayMode)
            }
        }

        is TexNode.Colored -> {
            LatexNodeView(node.inner, baseFontSp, node.color ?: color, effectiveScale, displayMode, modifier)
        }
    }
}

@Composable
private fun StretchyDelimiter(
    symbol: String,
    isLeft: Boolean,
    color: Color,
    strokeDp: Dp,
    minHeightDp: Dp,
    modifier: Modifier = Modifier
) {
    val widthDp = delimiterWidthDp(symbol)
    val density = LocalDensity.current
    val strokePx = with(density) { strokeDp.toPx() }
    val delimPath = remember { Path() }
    val delimStroke = remember(strokePx) {
        Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    Canvas(
        modifier = modifier
            .width(widthDp)
            .fillMaxHeight()
            .defaultMinSize(minHeight = minHeightDp.coerceAtLeast(18.dp))
    ) {
        val w = size.width
        val h = size.height
        val padY = strokePx
        when (symbol) {
            "(" -> {
                delimPath.rewind()
                delimPath.moveTo(w * 0.85f, padY)
                delimPath.cubicTo(w * 0.15f, h * 0.28f, w * 0.15f, h * 0.72f, w * 0.85f, h - padY)
                drawPath(delimPath, color, style = delimStroke)
            }
            ")" -> {
                delimPath.rewind()
                delimPath.moveTo(w * 0.15f, padY)
                delimPath.cubicTo(w * 0.85f, h * 0.28f, w * 0.85f, h * 0.72f, w * 0.15f, h - padY)
                drawPath(delimPath, color, style = delimStroke)
            }
            "[" -> {
                delimPath.rewind()
                delimPath.moveTo(w * 0.85f, padY)
                delimPath.lineTo(w * 0.25f, padY)
                delimPath.lineTo(w * 0.25f, h - padY)
                delimPath.lineTo(w * 0.85f, h - padY)
                drawPath(delimPath, color, style = delimStroke)
            }
            "]" -> {
                delimPath.rewind()
                delimPath.moveTo(w * 0.15f, padY)
                delimPath.lineTo(w * 0.75f, padY)
                delimPath.lineTo(w * 0.75f, h - padY)
                delimPath.lineTo(w * 0.15f, h - padY)
                drawPath(delimPath, color, style = delimStroke)
            }
            "{" -> {
                delimPath.rewind()
                delimPath.moveTo(w * 0.85f, padY)
                delimPath.cubicTo(w * 0.35f, padY, w * 0.45f, h * 0.44f, w * 0.15f, h * 0.5f)
                delimPath.cubicTo(w * 0.45f, h * 0.56f, w * 0.35f, h - padY, w * 0.85f, h - padY)
                drawPath(delimPath, color, style = delimStroke)
            }
            "}" -> {
                delimPath.rewind()
                delimPath.moveTo(w * 0.15f, padY)
                delimPath.cubicTo(w * 0.65f, padY, w * 0.55f, h * 0.44f, w * 0.85f, h * 0.5f)
                delimPath.cubicTo(w * 0.55f, h * 0.56f, w * 0.65f, h - padY, w * 0.15f, h - padY)
                drawPath(delimPath, color, style = delimStroke)
            }
            "|" -> {
                drawLine(color, Offset(w * 0.5f, padY), Offset(w * 0.5f, h - padY), strokeWidth = strokePx, cap = StrokeCap.Round)
            }
            "‖" -> {
                drawLine(color, Offset(w * 0.32f, padY), Offset(w * 0.32f, h - padY), strokeWidth = strokePx, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.68f, padY), Offset(w * 0.68f, h - padY), strokeWidth = strokePx, cap = StrokeCap.Round)
            }
            "⟨" -> {
                delimPath.rewind()
                delimPath.moveTo(w * 0.85f, padY)
                delimPath.lineTo(w * 0.2f, h * 0.5f)
                delimPath.lineTo(w * 0.85f, h - padY)
                drawPath(delimPath, color, style = delimStroke)
            }
            "⟩" -> {
                delimPath.rewind()
                delimPath.moveTo(w * 0.15f, padY)
                delimPath.lineTo(w * 0.8f, h * 0.5f)
                delimPath.lineTo(w * 0.15f, h - padY)
                drawPath(delimPath, color, style = delimStroke)
            }
            else -> {
                val x = if (isLeft) w * 0.65f else w * 0.35f
                drawLine(color, Offset(x, padY), Offset(x, h - padY), strokeWidth = strokePx, cap = StrokeCap.Round)
            }
        }
    }
}

private class FractionMeasurePolicy(
    private val barGapDp: Dp,
    private val hasBar: Boolean
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val numP = measurables.first { it.layoutId == "num" }.measure(childConstraints)
        val denP = measurables.first { it.layoutId == "den" }.measure(childConstraints)
        val barM = measurables.firstOrNull { it.layoutId == "bar" }
        val innerWidth = max(numP.width, denP.width)
        val barGapPx = barGapDp.roundToPx().coerceAtLeast(if (hasBar) 1 else 0)
        val barP = barM?.measure(Constraints.fixed(innerWidth, barGapPx))
        val barHeight = barP?.height ?: barGapPx
        val totalHeight = numP.height + barHeight + denP.height

        return layout(innerWidth, totalHeight) {
            numP.placeRelative((innerWidth - numP.width) / 2, 0)
            barP?.placeRelative(0, numP.height)
            denP.placeRelative((innerWidth - denP.width) / 2, numP.height + barHeight)
        }
    }

    private val IntrinsicMeasurable.tagId: Any?
        get() = (parentData as? LayoutIdParentData)?.layoutId

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val numW = measurables.first { it.tagId == "num" }.minIntrinsicWidth(height)
        val denW = measurables.first { it.tagId == "den" }.minIntrinsicWidth(height)
        return max(numW, denW)
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val numW = measurables.first { it.tagId == "num" }.maxIntrinsicWidth(height)
        val denW = measurables.first { it.tagId == "den" }.maxIntrinsicWidth(height)
        return max(numW, denW)
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val numH = measurables.first { it.tagId == "num" }.minIntrinsicHeight(width)
        val denH = measurables.first { it.tagId == "den" }.minIntrinsicHeight(width)
        val barGapPx = barGapDp.roundToPx().coerceAtLeast(if (hasBar) 1 else 0)
        return numH + barGapPx + denH
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        minIntrinsicHeight(measurables, width)
}

@Composable
private fun FractionLayout(
    drawBar: Boolean,
    strokeDp: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    numerator: @Composable () -> Unit,
    denominator: @Composable () -> Unit
) {
    val barGapDp = if (drawBar) strokeDp else 2.dp
    val policy = remember(barGapDp, drawBar) { FractionMeasurePolicy(barGapDp, drawBar) }
    Layout(
        modifier = modifier.padding(horizontal = 3.dp),
        measurePolicy = policy,
        content = {
            Box(
                modifier = Modifier
                    .layoutId("num")
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                numerator()
            }
            Box(
                modifier = Modifier
                    .layoutId("den")
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                denominator()
            }
            if (drawBar) {
                Box(
                    modifier = Modifier
                        .layoutId("bar")
                        .drawBehind {
                            drawLine(
                                color = color,
                                start = Offset(0f, size.height / 2f),
                                end = Offset(size.width, size.height / 2f),
                                strokeWidth = size.height
                            )
                        }
                )
            }
        }
    )
}

private class DelimitedRowMeasurePolicy(private val minHeightDp: Dp) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val middleP = measurables.first { it.layoutId == "middle" }.measure(childConstraints)
        val minHPx = minHeightDp.roundToPx()
        val rawDelimH = max(middleP.height, minHPx)
        val delimH = if (constraints.hasBoundedHeight) rawDelimH.coerceAtMost(constraints.maxHeight) else rawDelimH
        val delimConstraints = Constraints(
            minWidth = 0,
            maxWidth = childConstraints.maxWidth,
            minHeight = delimH,
            maxHeight = delimH
        )
        val leftP = measurables.firstOrNull { it.layoutId == "left" }?.measure(delimConstraints)
        val rightP = measurables.firstOrNull { it.layoutId == "right" }?.measure(delimConstraints)

        val totalWidth = (leftP?.width ?: 0) + middleP.width + (rightP?.width ?: 0)
        val totalHeight = max(middleP.height, max(leftP?.height ?: 0, rightP?.height ?: 0))

        return layout(totalWidth, totalHeight) {
            var x = 0
            if (leftP != null) {
                leftP.placeRelative(x, (totalHeight - leftP.height) / 2)
                x += leftP.width
            }
            middleP.placeRelative(x, (totalHeight - middleP.height) / 2)
            x += middleP.width
            if (rightP != null) {
                rightP.placeRelative(x, (totalHeight - rightP.height) / 2)
            }
        }
    }

    private val IntrinsicMeasurable.tagId: Any?
        get() = (parentData as? LayoutIdParentData)?.layoutId

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val l = measurables.firstOrNull { it.tagId == "left" }?.minIntrinsicWidth(height) ?: 0
        val m = measurables.first { it.tagId == "middle" }.minIntrinsicWidth(height)
        val r = measurables.firstOrNull { it.tagId == "right" }?.minIntrinsicWidth(height) ?: 0
        return l + m + r
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val l = measurables.firstOrNull { it.tagId == "left" }?.maxIntrinsicWidth(height) ?: 0
        val m = measurables.first { it.tagId == "middle" }.maxIntrinsicWidth(height)
        val r = measurables.firstOrNull { it.tagId == "right" }?.maxIntrinsicWidth(height) ?: 0
        return l + m + r
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val m = measurables.first { it.tagId == "middle" }.minIntrinsicHeight(width)
        return max(m, minHeightDp.roundToPx())
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        minIntrinsicHeight(measurables, width)
}

@Composable
private fun DelimitedRowLayout(
    minHeightDp: Dp,
    modifier: Modifier = Modifier,
    left: (@Composable (Modifier) -> Unit)?,
    right: (@Composable (Modifier) -> Unit)?,
    middle: @Composable () -> Unit
) {
    val effectiveMinHeightDp = minHeightDp.coerceAtLeast(18.dp)
    val policy = remember(effectiveMinHeightDp) { DelimitedRowMeasurePolicy(effectiveMinHeightDp) }
    Layout(
        modifier = modifier,
        measurePolicy = policy,
        content = {
            left?.invoke(Modifier.layoutId("left"))
            Box(Modifier.layoutId("middle")) { middle() }
            right?.invoke(Modifier.layoutId("right"))
        }
    )
}

private object ScriptMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val baseP = measurables.first { it.layoutId == "base" }.measure(childConstraints)
        val supP = measurables.firstOrNull { it.layoutId == "sup" }?.measure(childConstraints)
        val subP = measurables.firstOrNull { it.layoutId == "sub" }?.measure(childConstraints)

        val supRaise = if (supP != null) (supP.height * 0.42f).roundToInt() else 0
        val subDrop = if (subP != null) (subP.height * 0.34f).roundToInt() else 0

        val baseTop = supRaise
        val baseBottom = baseTop + baseP.height

        val supY = 0
        val subY = (baseBottom - (subP?.height ?: 0) + subDrop).coerceAtLeast(
            if (supP != null) supP.height else baseTop
        )

        val totalWidth = baseP.width + max(supP?.width ?: 0, subP?.width ?: 0)
        val totalHeight = max(baseBottom, (subP?.let { subY + it.height } ?: 0))

        return layout(totalWidth, totalHeight) {
            baseP.placeRelative(0, baseTop)
            supP?.placeRelative(baseP.width, supY)
            subP?.placeRelative(baseP.width, subY)
        }
    }

    private val IntrinsicMeasurable.tagId: Any?
        get() = (parentData as? LayoutIdParentData)?.layoutId

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val b = measurables.first { it.tagId == "base" }.minIntrinsicWidth(height)
        val supW = measurables.firstOrNull { it.tagId == "sup" }?.minIntrinsicWidth(height) ?: 0
        val subW = measurables.firstOrNull { it.tagId == "sub" }?.minIntrinsicWidth(height) ?: 0
        return b + max(supW, subW)
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val b = measurables.first { it.tagId == "base" }.maxIntrinsicWidth(height)
        val supW = measurables.firstOrNull { it.tagId == "sup" }?.maxIntrinsicWidth(height) ?: 0
        val subW = measurables.firstOrNull { it.tagId == "sub" }?.maxIntrinsicWidth(height) ?: 0
        return b + max(supW, subW)
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val b = measurables.first { it.tagId == "base" }.minIntrinsicHeight(width)
        val supH = measurables.firstOrNull { it.tagId == "sup" }?.minIntrinsicHeight(width) ?: 0
        val subH = measurables.firstOrNull { it.tagId == "sub" }?.minIntrinsicHeight(width) ?: 0
        return b + (supH * 0.42f).roundToInt() + (subH * 0.34f).roundToInt()
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        minIntrinsicHeight(measurables, width)
}

@Composable
private fun ScriptLayout(
    modifier: Modifier = Modifier,
    base: @Composable () -> Unit,
    sup: (@Composable () -> Unit)?,
    sub: (@Composable () -> Unit)?
) {
    Layout(
        modifier = modifier,
        measurePolicy = ScriptMeasurePolicy,
        content = {
            Box(Modifier.layoutId("base")) { base() }
            if (sup != null) Box(Modifier.layoutId("sup")) { sup() }
            if (sub != null) Box(Modifier.layoutId("sub")) { sub() }
        }
    )
}

@Composable
private fun MatrixGridLayout(
    rows: Int,
    cols: Int,
    leftAligned: Boolean,
    hGap: Dp,
    vGap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val policy = remember(rows, cols, leftAligned, hGap, vGap) {
        object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                val placeables = measurables.map { it.measure(childConstraints) }
                val colWidths = IntArray(cols)
                val rowHeights = IntArray(rows)
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        if (idx < placeables.size) {
                            val p = placeables[idx]
                            if (p.width > colWidths[c]) colWidths[c] = p.width
                            if (p.height > rowHeights[r]) rowHeights[r] = p.height
                        }
                    }
                }
                val hGapPx = hGap.roundToPx()
                val vGapPx = vGap.roundToPx()
                val totalW = colWidths.sum() + max(0, cols - 1) * hGapPx
                val totalH = rowHeights.sum() + max(0, rows - 1) * vGapPx

                return layout(totalW, totalH) {
                    var y = 0
                    for (r in 0 until rows) {
                        var x = 0
                        for (c in 0 until cols) {
                            val idx = r * cols + c
                            if (idx < placeables.size) {
                                val p = placeables[idx]
                                val dx = if (leftAligned) 0 else (colWidths[c] - p.width) / 2
                                val dy = (rowHeights[r] - p.height) / 2
                                p.placeRelative(x + dx, y + dy)
                            }
                            x += colWidths[c] + hGapPx
                        }
                        y += rowHeights[r] + vGapPx
                    }
                }
            }

            override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
                val colWidths = IntArray(cols)
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        if (idx < measurables.size) {
                            colWidths[c] = max(colWidths[c], measurables[idx].minIntrinsicWidth(height))
                        }
                    }
                }
                return colWidths.sum() + max(0, cols - 1) * hGap.roundToPx()
            }

            override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
                val colWidths = IntArray(cols)
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        if (idx < measurables.size) {
                            colWidths[c] = max(colWidths[c], measurables[idx].maxIntrinsicWidth(height))
                        }
                    }
                }
                return colWidths.sum() + max(0, cols - 1) * hGap.roundToPx()
            }

            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
                val rowHeights = IntArray(rows)
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        if (idx < measurables.size) {
                            rowHeights[r] = max(rowHeights[r], measurables[idx].minIntrinsicHeight(width))
                        }
                    }
                }
                return rowHeights.sum() + max(0, rows - 1) * vGap.roundToPx()
            }

            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
                minIntrinsicHeight(measurables, width)
        }
    }

    Layout(
        modifier = modifier,
        measurePolicy = policy,
        content = content
    )
}
