package com.studydock.app.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Representação estruturada de expressões e equações matemáticas para renderização
 * visual real (frações com barra horizontal, expoentes sobrescritos, radicais com
 * barra superior, parênteses e equações completas).
 */
sealed interface MathExpr {
    data class Text(val value: String) : MathExpr
    data class Var(val symbol: String) : MathExpr
    data class Row(val items: List<MathExpr>) : MathExpr {
        constructor(vararg items: MathExpr) : this(items.toList())
    }
    data class Frac(val numerator: MathExpr, val denominator: MathExpr) : MathExpr
    data class Pow(val base: MathExpr, val exponent: MathExpr) : MathExpr
    data class Sqrt(val radicand: MathExpr, val index: Int = 2) : MathExpr
    data class Eq(val left: MathExpr, val right: MathExpr) : MathExpr
    data class Parenthesized(val inner: MathExpr) : MathExpr

    fun toPlainText(): String = when (this) {
        is Text -> value
        is Var -> symbol
        is Row -> items.joinToString("") { it.toPlainText() }
        is Frac -> {
            val num = numerator.toPlainText()
            val den = denominator.toPlainText()
            val needsNumParen = (numerator is Row && numerator.items.size > 1) ||
                numerator is Frac || numerator is Eq ||
                (numerator is Text && numerator.value.trim().any { it == '+' || it == '−' || it == '-' || it == ' ' })
            val needsDenParen = (denominator is Row && denominator.items.size > 1) ||
                denominator is Frac || denominator is Pow || denominator is Eq ||
                (denominator is Text && denominator.value.trim().any { it == '+' || it == '−' || it == '-' || it == '·' || it == '×' || it == '/' || it == ' ' })
            val numStr = if (needsNumParen) "($num)" else num
            val denStr = if (needsDenParen) "($den)" else den
            "$numStr/$denStr"
        }
        is Pow -> {
            val rawBase = base.toPlainText()
            val needsBaseParen = base is Row || base is Frac || base is Pow || base is Sqrt ||
                (rawBase.length > 1 && !rawBase.endsWith(")"))
            val baseStr = if (needsBaseParen) "($rawBase)" else rawBase
            val expPlain = exponent.toPlainText()
            val sup = toSuperscriptString(expPlain)
            if (sup != null) "$baseStr$sup" else "$baseStr^($expPlain)"
        }
        is Sqrt -> {
            val rad = radicand.toPlainText()
            val prefix = when (index) {
                2 -> "√"
                3 -> "³√"
                4 -> "⁴√"
                else -> "${index}√"
            }
            if (radicand is Row || radicand is Frac) "$prefix($rad)" else "$prefix$rad"
        }
        is Eq -> "${left.toPlainText()} = ${right.toPlainText()}"
        is Parenthesized -> "(${inner.toPlainText()})"
    }

    companion object {
        private fun toSuperscriptString(raw: String): String? {
            if (raw.isEmpty()) return null
            val sb = StringBuilder()
            for (ch in raw) {
                val mapped = when (ch) {
                    '0' -> '⁰'
                    '1' -> '¹'
                    '2' -> '²'
                    '3' -> '³'
                    '4' -> '⁴'
                    '5' -> '⁵'
                    '6' -> '⁶'
                    '7' -> '⁷'
                    '8' -> '⁸'
                    '9' -> '⁹'
                    '+' -> '⁺'
                    '-', '−' -> '⁻'
                    'x' -> 'ˣ'
                    'n' -> 'ⁿ'
                    else -> return null
                }
                sb.append(mapped)
            }
            return sb.toString()
        }
    }
}

enum class MathTopicCategory(val label: String) {
    ALL("Todos"),
    NUMBERS("Aritmética & Razão"),
    ALGEBRA("Álgebra & Equações"),
    GEOMETRY_TRIG("Geometria & Trig."),
    DISCRETE_FUNCS("Funções, Seq. & Prob.")
}

enum class MathTopic(
    val label: String,
    val shortLabel: String,
    val subtitle: String,
    val badgeSymbol: String,
    val category: MathTopicCategory
) {
    ARITHMETIC(
        label = "Aritmética & Expressões",
        shortLabel = "Aritmética",
        subtitle = "Expressões numéricas, sinais e ordem de operações",
        badgeSymbol = "+ − × ÷",
        category = MathTopicCategory.NUMBERS
    ),
    LINEAR_EQ(
        label = "Equações do 1º Grau",
        shortLabel = "1º Grau",
        subtitle = "Isolamento de variável, distributiva e equações fracionárias",
        badgeSymbol = "ax + b = c",
        category = MathTopicCategory.ALGEBRA
    ),
    QUADRATIC_EQ(
        label = "Equações do 2º Grau",
        shortLabel = "2º Grau",
        subtitle = "Raízes reais, soma e produto, discriminante Δ e Bhaskara",
        badgeSymbol = "ax² + bx + c",
        category = MathTopicCategory.ALGEBRA
    ),
    FRACTIONS_PERCENT(
        label = "Frações & Porcentagem",
        shortLabel = "Frações & %",
        subtitle = "Operações com frações reais, descontos, acréscimos e razões",
        badgeSymbol = "a/b · %",
        category = MathTopicCategory.NUMBERS
    ),
    POWERS_ROOTS(
        label = "Potências & Radiciação",
        shortLabel = "Potências & √",
        subtitle = "Propriedades de expoentes, raízes exatas e equações exponenciais",
        badgeSymbol = "xⁿ · √x",
        category = MathTopicCategory.NUMBERS
    ),
    RULE_OF_THREE(
        label = "Proporção & Regra de 3",
        shortLabel = "Regra de 3",
        subtitle = "Proporções visuais a/b = c/x, grandezas diretas e inversas",
        badgeSymbol = "a/b = c/x",
        category = MathTopicCategory.NUMBERS
    ),
    SYSTEMS_2X2(
        label = "Sistemas Lineares 2×2",
        shortLabel = "Sistemas 2×2",
        subtitle = "Sistemas com duas incógnitas por adição ou substituição",
        badgeSymbol = "{ x, y }",
        category = MathTopicCategory.ALGEBRA
    ),
    GEOMETRY(
        label = "Geometria Plana & Espacial",
        shortLabel = "Geometria",
        subtitle = "Pitágoras, áreas, perímetros, volumes e ângulos internos",
        badgeSymbol = "△ · πr²",
        category = MathTopicCategory.GEOMETRY_TRIG
    ),
    TRIGONOMETRY(
        label = "Trigonometria",
        shortLabel = "Trigonometria",
        subtitle = "Razões no triângulo retângulo, arcos notáveis e sen² + cos²",
        badgeSymbol = "sen · cos",
        category = MathTopicCategory.GEOMETRY_TRIG
    ),
    COMBINATORICS_PROB(
        label = "Contagem & Probabilidade",
        shortLabel = "Comb. & Prob.",
        subtitle = "Princípio multiplicativo, fatorial, combinação C(n,k) e chance",
        badgeSymbol = "C(n,k) · P",
        category = MathTopicCategory.DISCRETE_FUNCS
    ),
    FUNCTIONS_SEQ_LOG(
        label = "Funções, P.A./P.G. & Log",
        shortLabel = "Funções & Log",
        subtitle = "f(x), vértice da parábola, progressões P.A./P.G. e logaritmos",
        badgeSymbol = "f(x) · log",
        category = MathTopicCategory.DISCRETE_FUNCS
    ),
    MIXED(
        label = "Desafio Misto (Geral)",
        shortLabel = "Misto Geral",
        subtitle = "Mistura equilibrada entre todas as 11 áreas matemáticas",
        badgeSymbol = "∑ Geral",
        category = MathTopicCategory.ALL
    );

    companion object {
        val concreteTopics: List<MathTopic> = listOf(
            ARITHMETIC,
            LINEAR_EQ,
            QUADRATIC_EQ,
            FRACTIONS_PERCENT,
            POWERS_ROOTS,
            RULE_OF_THREE,
            SYSTEMS_2X2,
            GEOMETRY,
            TRIGONOMETRY,
            COMBINATORICS_PROB,
            FUNCTIONS_SEQ_LOG
        )

        fun topicsForCategory(category: MathTopicCategory): List<MathTopic> =
            if (category == MathTopicCategory.ALL) listOf(MIXED) + concreteTopics
            else concreteTopics.filter { it.category == category }
    }
}

enum class MathPracticeMode(
    val label: String,
    val shortLabel: String,
    val description: String,
    val defaultQuestionCount: Int
) {
    CLASSIC(
        label = "Treino Clássico",
        shortLabel = "Clássico",
        description = "Rodada personalizada nos temas, nível e ritmo que você escolher",
        defaultQuestionCount = 10
    ),
    TIME_ATTACK(
        label = "Contra-Relógio (2 min)",
        shortLabel = "Sprint 2m",
        description = "Máximo de acertos em 2 minutos corridos sem limite de questões",
        defaultQuestionCount = 20
    ),
    SURVIVAL(
        label = "Sobrevivência (3 Vidas)",
        shortLabel = "3 Vidas",
        description = "Cada erro ou tempo esgotado consome 1 vida até zerar",
        defaultQuestionCount = 20
    ),
    PROGRESSIVE(
        label = "Escalada Adaptativa",
        shortLabel = "Escalada",
        description = "Começa no Básico e sobe de nível a cada 3 acertos seguidos",
        defaultQuestionCount = 12
    ),
    SIMULADO(
        label = "Simulado Geral (15q)",
        shortLabel = "Simulado",
        description = "Bateria de 15 questões cobrindo múltiplas áreas em nível misto",
        defaultQuestionCount = 15
    )
}

enum class MathDifficulty(
    val label: String,
    val description: String,
    val basePoints: Int
) {
    BASIC(
        label = "Básico",
        description = "Fundamentos diretos e cálculo mental ágil",
        basePoints = 10
    ),
    INTERMEDIATE(
        label = "Intermediário",
        description = "Múltiplas etapas, sinais e frações em ritmo de prova",
        basePoints = 20
    ),
    ADVANCED(
        label = "Avançado",
        description = "Desafios algébricos densos, sistemas gerais e expoentes",
        basePoints = 35
    )
}

enum class MathTimerPreset(
    val label: String,
    val shortLabel: String,
    val description: String,
    val secondsPerQuestion: Int?,
    val totalSprintSeconds: Int?
) {
    FAST_10S(
        label = "10s / questão",
        shortLabel = "10s",
        description = "Pressão máxima: 10 segundos por equação",
        secondsPerQuestion = 10,
        totalSprintSeconds = null
    ),
    STANDARD_20S(
        label = "20s / questão",
        shortLabel = "20s",
        description = "Ritmo equilibrado: 20 segundos por equação",
        secondsPerQuestion = 20,
        totalSprintSeconds = null
    ),
    CALM_40S(
        label = "40s / questão",
        shortLabel = "40s",
        description = "Resolução detalhada: 40 segundos por equação",
        secondsPerQuestion = 40,
        totalSprintSeconds = null
    ),
    SPRINT_120S(
        label = "Sprint 2 min",
        shortLabel = "Sprint 2m",
        description = "2 minutos no relógio geral — acerte o máximo possível!",
        secondsPerQuestion = null,
        totalSprintSeconds = 120
    ),
    FREE(
        label = "Livre",
        shortLabel = "Livre",
        description = "Sem estourar tempo — apenas mede sua velocidade",
        secondsPerQuestion = null,
        totalSprintSeconds = null
    );

    val isPerQuestion: Boolean get() = secondsPerQuestion != null
    val isSprint: Boolean get() = totalSprintSeconds != null
    val isTimed: Boolean get() = secondsPerQuestion != null || totalSprintSeconds != null
}

enum class MathAnswerMode(
    val label: String,
    val shortLabel: String,
    val description: String
) {
    MULTIPLE_CHOICE(
        label = "Múltipla escolha rápida",
        shortLabel = "4 Opções",
        description = "4 alternativas em grade 2×2 para resposta com 1 toque"
    ),
    KEYPAD(
        label = "Teclado numérico / fração",
        shortLabel = "Teclado",
        description = "Digite valores exatos, negativos, decimais ou frações (ex: -3/4)"
    )
}

/** Converte a expressão em LaTeX para o renderizador nativo e para os flashcards de erros. */
fun MathExpr.toLatex(): String = when (this) {
    is MathExpr.Text -> value
    is MathExpr.Var -> symbol
    is MathExpr.Row -> items.joinToString("") { it.toLatex() }
    is MathExpr.Frac -> "\\frac{${numerator.toLatex()}}{${denominator.toLatex()}}"
    is MathExpr.Pow -> "{${base.toLatex()}}^{${exponent.toLatex()}}"
    is MathExpr.Sqrt -> if (index == 2) "\\sqrt{${radicand.toLatex()}}" else "\\sqrt[$index]{${radicand.toLatex()}}"
    is MathExpr.Eq -> "${left.toLatex()} = ${right.toLatex()}"
    is MathExpr.Parenthesized -> "(${inner.toLatex()})"
}

data class MathProblem(
    val id: String,
    val topic: MathTopic,
    val difficulty: MathDifficulty,
    val promptTitle: String,
    val equation: MathExpr,
    val plainSummary: String,
    val answerDisplay: String,
    val acceptedValues: List<Double>,
    val options: List<String>,
    val steps: List<String>,
    val secondaryEquation: MathExpr? = null,
    val subtopicTag: String = topic.shortLabel
) {
    val promptLatex: String
        get() = listOfNotNull(equation, secondaryEquation).joinToString(" \\quad ") { it.toLatex() }

    val answerLatex: String
        get() = answerDisplay

    fun isCorrect(rawInput: String, tolerance: Double = 1e-4): Boolean {
        val trimmed = rawInput.trim().removeSuffix("°").removeSuffix("%").trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.equals(answerDisplay.trim(), ignoreCase = true)) return true
        val parsed = parseMathInput(trimmed) ?: return false
        return isCorrectValue(parsed, tolerance)
    }

    fun isCorrectValue(value: Double, tolerance: Double = 1e-4): Boolean {
        if (!value.isFinite()) return false
        return acceptedValues.any { target -> abs(value - target) <= tolerance }
    }
}

/**
 * Converte uma entrada textual de resposta matemática em [Double].
 * Aceita:
 * - Inteiros positivos e negativos (`"12"`, `"-7"`, `"−7"`, `"+15"`)
 * - Decimais com vírgula ou ponto (`"1,5"`, `"1.5"`, `"-0,25"`, `".5"`)
 * - Frações simples ou com decimais (`"3/4"`, `"-5/2"`, `" 6 / -8 "`, `"1,5/3"`)
 * Rejeita divisão por zero, múltiplas barras, entradas vazias e NaN/Infinito.
 */
fun parseMathInput(raw: String): Double? {
    val cleaned = raw
        .trim()
        .replace('−', '-')
        .replace('–', '-')
        .replace('—', '-')
        .replace(" ", "")
        .replace(',', '.')

    if (cleaned.isEmpty() || cleaned == "-" || cleaned == "+" || cleaned == ".") return null

    if ('/' in cleaned) {
        val parts = cleaned.split('/')
        if (parts.size != 2) return null
        val numStr = parts[0]
        val denStr = parts[1]
        if (numStr.isEmpty() || denStr.isEmpty()) return null
        val num = numStr.toDoubleOrNull() ?: return null
        val den = denStr.toDoubleOrNull() ?: return null
        if (!num.isFinite() || !den.isFinite() || abs(den) < 1e-12) return null
        val quotient = num / den
        return if (quotient.isFinite()) quotient else null
    }

    val direct = cleaned.toDoubleOrNull() ?: return null
    return if (direct.isFinite()) direct else null
}

object MathArenaEngine {
    fun parseInput(raw: String): Double? = parseMathInput(raw)

    fun gcd(a: Long, b: Long): Long {
        var x = abs(a)
        var y = abs(b)
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0L) 1L else x
    }

    fun formatReducedFraction(numerator: Int, denominator: Int): String {
        require(denominator != 0) { "Denominator cannot be zero" }
        val sign = if ((numerator < 0) xor (denominator < 0)) -1 else 1
        val absNum = abs(numerator.toLong())
        val absDen = abs(denominator.toLong())
        val divisor = gcd(absNum, absDen)
        val redNum = (absNum / divisor) * sign
        val redDen = absDen / divisor
        return if (redDen == 1L) "$redNum" else "$redNum/$redDen"
    }

    fun formatCleanDouble(value: Double): String {
        if (!value.isFinite()) return "0"
        val roundedLong = value.roundToLong()
        if (abs(value - roundedLong.toDouble()) < 1e-6) {
            return if (roundedLong == 0L) "0" else roundedLong.toString()
        }
        val twoDec = (value * 100.0).roundToLong() / 100.0
        if (abs(twoDec) < 1e-9) return "0"
        return String.format(Locale.US, "%.2f", twoDec)
            .trimEnd('0')
            .trimEnd('.')
            .let { if (it == "-0") "0" else it }
    }
}

object MathProblemGenerator {

    fun parseInput(raw: String): Double? = parseMathInput(raw)

    fun checkAnswer(problem: MathProblem, rawInput: String, tolerance: Double = 1e-4): Boolean =
        problem.isCorrect(rawInput, tolerance)

    fun generateSession(
        topic: MathTopic = MathTopic.MIXED,
        difficulty: MathDifficulty = MathDifficulty.INTERMEDIATE,
        count: Int = 10,
        seed: Long? = null
    ): List<MathProblem> {
        val pool = if (topic == MathTopic.MIXED) MathTopic.concreteTopics else listOf(topic)
        return generateSessionForTopics(
            topics = pool,
            difficulty = difficulty,
            count = count,
            seed = seed
        )
    }

    fun generateSessionForTopics(
        topics: Collection<MathTopic>,
        difficulty: MathDifficulty = MathDifficulty.INTERMEDIATE,
        count: Int = 10,
        seed: Long? = null
    ): List<MathProblem> {
        val safeCount = count.coerceIn(1, 100)
        val concretePool = topics
            .filter { it != MathTopic.MIXED }
            .distinct()
            .ifEmpty { MathTopic.concreteTopics }
        val baseSeed = seed ?: System.currentTimeMillis()
        val sessionRandom = Random(baseSeed)
        val results = ArrayList<MathProblem>(safeCount)
        val usedSignatures = HashSet<String>()
        val shuffledPool = if (concretePool.size > 1) {
            concretePool.shuffled(Random(baseSeed xor 0x5A5A5A5AL))
        } else {
            concretePool
        }

        for (i in 0 until safeCount) {
            val resolvedTopic = when {
                concretePool.size == 1 -> concretePool.first()
                i < shuffledPool.size -> shuffledPool[i]
                else -> concretePool[sessionRandom.nextInt(concretePool.size)]
            }

            var candidate: MathProblem? = null
            for (attempt in 0 until 16) {
                val itemSeed = sessionRandom.nextLong() xor ((i.toLong() + 1L) * 0x9E3779B97F4A7C15UL.toLong()) xor attempt.toLong()
                val generated = generate(
                    topic = resolvedTopic,
                    difficulty = difficulty,
                    random = Random(itemSeed),
                    index = i
                )
                if (usedSignatures.add(generated.plainSummary) || attempt == 15) {
                    candidate = generated
                    break
                }
            }
            results.add(candidate!!)
        }
        return results
    }

    fun generate(
        topic: MathTopic = MathTopic.MIXED,
        difficulty: MathDifficulty = MathDifficulty.INTERMEDIATE,
        seed: Long? = null,
        index: Int = 0
    ): MathProblem {
        val rng = Random(seed ?: (System.currentTimeMillis() xor (index.toLong() * 31L)))
        return generate(topic, difficulty, rng, index)
    }

    fun generate(
        topic: MathTopic,
        difficulty: MathDifficulty,
        random: Random,
        index: Int = 0
    ): MathProblem {
        val concreteTopic = if (topic == MathTopic.MIXED) {
            val list = MathTopic.concreteTopics
            list[random.nextInt(list.size)]
        } else {
            topic
        }
        return when (concreteTopic) {
            MathTopic.ARITHMETIC -> generateArithmetic(difficulty, random, index)
            MathTopic.LINEAR_EQ -> generateLinearEq(difficulty, random, index)
            MathTopic.QUADRATIC_EQ -> generateQuadraticEq(difficulty, random, index)
            MathTopic.FRACTIONS_PERCENT -> generateFractionsPercent(difficulty, random, index)
            MathTopic.POWERS_ROOTS -> generatePowersRoots(difficulty, random, index)
            MathTopic.RULE_OF_THREE -> generateRuleOfThree(difficulty, random, index)
            MathTopic.SYSTEMS_2X2 -> generateSystems2x2(difficulty, random, index)
            MathTopic.GEOMETRY -> generateGeometry(difficulty, random, index)
            MathTopic.TRIGONOMETRY -> generateTrigonometry(difficulty, random, index)
            MathTopic.COMBINATORICS_PROB -> generateCombinatoricsProb(difficulty, random, index)
            MathTopic.FUNCTIONS_SEQ_LOG -> generateFunctionsSeqLog(difficulty, random, index)
            MathTopic.MIXED -> generateArithmetic(difficulty, random, index)
        }
    }

    // ========================================================================
    // 1. ARITHMETIC (Expressões numéricas & cálculo mental)
    // ========================================================================
    private fun generateArithmetic(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem {
        return when (difficulty) {
            MathDifficulty.BASIC -> {
                val variant = rng.nextInt(3)
                when (variant) {
                    0 -> {
                        val a = rng.nextInt(4, 15)
                        val b = rng.nextInt(2, 11)
                        val c = rng.nextInt(2, 7)
                        val d = rng.nextInt(3, 18)
                        val sum = a + b
                        val prod = sum * c
                        val ans = prod - d
                        val expr = MathExpr.Row(
                            MathExpr.Parenthesized(MathExpr.Row(MathExpr.Text("$a + $b"))),
                            MathExpr.Text(" × $c − $d")
                        )
                        val summary = "($a + $b) × $c − $d"
                        buildIntegerProblem(
                            id = "arith_b_${index}_${rng.nextInt(1000, 9999)}",
                            topic = MathTopic.ARITHMETIC,
                            difficulty = difficulty,
                            promptTitle = "Calcule o valor da expressão numérica:",
                            equation = expr,
                            plainSummary = summary,
                            answer = ans,
                            customDistractors = listOf(
                                a + b * c - d, // erro de prioridade sem parênteses
                                prod + d,      // erro de sinal na subtração
                                ans + c
                            ),
                            steps = listOf(
                                "1º Passo (Parênteses): Resolva a soma dentro dos parênteses: $a + $b = $sum.",
                                "2º Passo (Multiplicação): Multiplique o resultado por $c: $sum × $c = $prod.",
                                "3º Passo (Subtração): Finalize subtraindo $d: $prod − $d = $ans."
                            ),
                            rng = rng
                        )
                    }
                    1 -> {
                        val a = rng.nextInt(5, 13)
                        val b = rng.nextInt(3, 9)
                        val d = rng.nextInt(2, 7)
                        val q = rng.nextInt(2, 9)
                        val c = d * q
                        val prod = a * b
                        val ans = prod - q
                        val expr = MathExpr.Row(
                            MathExpr.Text("$a × $b − "),
                            MathExpr.Frac(MathExpr.Text("$c"), MathExpr.Text("$d"))
                        )
                        val summary = "$a × $b − $c/$d"
                        buildIntegerProblem(
                            id = "arith_b_${index}_${rng.nextInt(1000, 9999)}",
                            topic = MathTopic.ARITHMETIC,
                            difficulty = difficulty,
                            promptTitle = "Calcule o valor da expressão:",
                            equation = expr,
                            plainSummary = summary,
                            answer = ans,
                            customDistractors = listOf(
                                prod + q,
                                (prod - c) / d.coerceAtLeast(1),
                                ans - d
                            ),
                            steps = listOf(
                                "1º Passo (Multiplicação): Calcule $a × $b = $prod.",
                                "2º Passo (Divisão/Fração): Calcule $c ÷ $d = $q.",
                                "3º Passo (Subtração): Efetue $prod − $q = $ans."
                            ),
                            rng = rng
                        )
                    }
                    else -> {
                        val a = rng.nextInt(12, 35)
                        val b = rng.nextInt(4, 12)
                        val c = rng.nextInt(2, 6)
                        val d = rng.nextInt(5, 16)
                        val prod = b * c
                        val ans = a - prod + d
                        val expr = MathExpr.Text("$a − $b × $c + $d")
                        val summary = "$a − $b × $c + $d"
                        buildIntegerProblem(
                            id = "arith_b_${index}_${rng.nextInt(1000, 9999)}",
                            topic = MathTopic.ARITHMETIC,
                            difficulty = difficulty,
                            promptTitle = "Respeitando a ordem das operações, calcule:",
                            equation = expr,
                            plainSummary = summary,
                            answer = ans,
                            customDistractors = listOf(
                                (a - b) * c + d, // subtraiu antes de multiplicar
                                a - (prod + d),  // somou antes de subtrair
                                a + prod + d
                            ),
                            steps = listOf(
                                "1º Passo (Prioridade da Multiplicação): Calcule primeiro $b × $c = $prod.",
                                "2º Passo (Da esquerda para a direita): Faça $a − $prod = ${a - prod}.",
                                "3º Passo (Soma final): Some $d ao resultado: ${a - prod} + $d = $ans."
                            ),
                            rng = rng
                        )
                    }
                }
            }
            MathDifficulty.INTERMEDIATE -> {
                val variant = rng.nextInt(2)
                if (variant == 0) {
                    val a = rng.nextInt(3, 9)
                    val b = rng.nextInt(2, 6)
                    val c = rng.nextInt(3, 8)
                    val d = rng.nextInt(2, 6)
                    val sum = a + b
                    val sq = sum * sum
                    val prod = c * d
                    val ans = sq - prod
                    val expr = MathExpr.Row(
                        MathExpr.Pow(
                            MathExpr.Parenthesized(MathExpr.Text("$a + $b")),
                            MathExpr.Text("2")
                        ),
                        MathExpr.Text(" − $c × $d")
                    )
                    val summary = "($a + $b)² − $c × $d"
                    buildIntegerProblem(
                        id = "arith_i_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.ARITHMETIC,
                        difficulty = difficulty,
                        promptTitle = "Calcule o valor da expressão:",
                        equation = expr,
                        plainSummary = summary,
                        answer = ans,
                        customDistractors = listOf(
                            (a * a + b * b) - prod, // erro clássico (a+b)² = a² + b²
                            sq + prod,
                            sum * 2 - prod          // multiplicou por 2 em vez de elevar ao quadrado
                        ),
                        steps = listOf(
                            "1º Passo (Parênteses): Some os termos internos: $a + $b = $sum.",
                            "2º Passo (Potência e Multiplicação): ($sum)² = $sq e $c × $d = $prod.",
                            "3º Passo (Subtração): Calcule $sq − $prod = $ans."
                        ),
                        rng = rng
                    )
                } else {
                    val den = rng.nextInt(3, 8)
                    val q = rng.nextInt(4, 12)
                    val b = rng.nextInt(2, 6)
                    val prodTarget = den * q
                    val a = rng.nextInt(2, 7)
                    val c = prodTarget - a * b
                    val e = rng.nextInt(3, 14)
                    val ans = q - e
                    val cSign = if (c >= 0) "+ $c" else "− ${abs(c)}"
                    val expr = MathExpr.Row(
                        MathExpr.Frac(
                            MathExpr.Text("$a × $b $cSign"),
                            MathExpr.Text("$den")
                        ),
                        MathExpr.Text(" − $e")
                    )
                    val summary = "($a × $b $cSign)/$den − $e"
                    buildIntegerProblem(
                        id = "arith_i_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.ARITHMETIC,
                        difficulty = difficulty,
                        promptTitle = "Simplifique a expressão fracionária:",
                        equation = expr,
                        plainSummary = summary,
                        answer = ans,
                        customDistractors = listOf(
                            q + e,
                            -ans,
                            ans + den
                        ),
                        steps = listOf(
                            "1º Passo (Numerador): Multiplique $a × $b = ${a * b} e combine com $c, obtendo $prodTarget.",
                            "2º Passo (Divisão da Fração): Divida $prodTarget por $den: $prodTarget ÷ $den = $q.",
                            "3º Passo (Subtração): Faça $q − $e = $ans."
                        ),
                        rng = rng
                    )
                }
            }
            MathDifficulty.ADVANCED -> {
                val a = rng.nextInt(2, 6)
                val b = rng.nextInt(6, 12)
                val diff = a - b // negativo
                val sq = diff * diff
                val den = rng.nextInt(2, 6)
                val q = rng.nextInt(3, 9)
                val c = den * q - sq
                val d = rng.nextInt(2, 6)
                val e = rng.nextInt(2, 5)
                // ((a - b)² + c) / den - d × (-e) = q + d*e
                val prodNeg = d * e
                val ans = q + prodNeg
                val cText = if (c >= 0) " + $c" else " − ${abs(c)}"
                val expr = MathExpr.Row(
                    MathExpr.Frac(
                        MathExpr.Row(
                            MathExpr.Pow(
                                MathExpr.Parenthesized(MathExpr.Text("$a − $b")),
                                MathExpr.Text("2")
                            ),
                            MathExpr.Text(cText)
                        ),
                        MathExpr.Text("$den")
                    ),
                    MathExpr.Text(" − $d × "),
                    MathExpr.Parenthesized(MathExpr.Text("−$e"))
                )
                val summary = "(($a − $b)²$cText)/$den − $d × (−$e)"
                buildIntegerProblem(
                    id = "arith_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.ARITHMETIC,
                    difficulty = difficulty,
                    promptTitle = "Calcule o valor exato da expressão:",
                    equation = expr,
                    plainSummary = summary,
                    answer = ans,
                    customDistractors = listOf(
                        q - prodNeg, // errou regra de sinais -d * (-e)
                        -ans,
                        ans - 2 * q
                    ),
                    steps = listOf(
                        "1º Passo (Potência com base negativa): ($a − $b)² = ($diff)² = $sq.",
                        "2º Passo (Fração): Numerador $sq$cText = ${sq + c}; dividindo por $den obtemos ${sq + c} ÷ $den = $q.",
                        "3º Passo (Regra de sinais): −$d × (−$e) = +$prodNeg.",
                        "4º Passo (Resultado): Some $q + $prodNeg = $ans."
                    ),
                    rng = rng
                )
            }
        }
    }

    // ========================================================================
    // 2. LINEAR_EQ (Equações do 1º Grau)
    // ========================================================================
    private fun generateLinearEq(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem {
        return when (difficulty) {
            MathDifficulty.BASIC -> {
                val x = nonZeroInt(rng, -9, 12)
                val a = rng.nextInt(2, 9)
                val b = nonZeroInt(rng, -18, 18)
                val c = a * x + b
                val bStr = if (b >= 0) " + $b" else " − ${abs(b)}"
                val eq = MathExpr.Eq(
                    MathExpr.Row(MathExpr.Text("$a"), MathExpr.Var("x"), MathExpr.Text(bStr)),
                    MathExpr.Text("$c")
                )
                val summary = "${a}x$bStr = $c"
                val rhsAfterB = c - b
                buildIntegerProblem(
                    id = "lin_b_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.LINEAR_EQ,
                    difficulty = difficulty,
                    promptTitle = "Resolva a equação do 1º grau para x:",
                    equation = eq,
                    plainSummary = summary,
                    answer = x,
                    customDistractors = listOf(
                        -x,
                        (c + b) / a.coerceAtLeast(1), // errou o sinal ao passar b para o outro lado
                        x + (if (x >= 0) 2 else -2)
                    ),
                    steps = listOf(
                        "1º Passo (Isolar o termo com x): Passe ${if (b >= 0) "+$b" else "$b"} para o outro lado invertendo o sinal: ${a}x = $c ${if (b >= 0) "− $b" else "+ ${abs(b)}"}.",
                        "2º Passo (Simplificar): ${a}x = $rhsAfterB.",
                        "3º Passo (Dividir pelo coeficiente $a): x = $rhsAfterB ÷ $a = $x."
                    ),
                    rng = rng
                )
            }
            MathDifficulty.INTERMEDIATE -> {
                // a(x + b) = cx + d
                val x = nonZeroInt(rng, -8, 10)
                val a = rng.nextInt(2, 7)
                var c = rng.nextInt(1, 6)
                if (c == a) c += 1
                val b = nonZeroInt(rng, -6, 7)
                val d = a * (x + b) - c * x
                val bStr = if (b >= 0) " + $b" else " − ${abs(b)}"
                val dStr = when { d > 0 -> " + $d"; d < 0 -> " − ${abs(d)}"; else -> "" }
                val cPrefix = if (c == 1) "" else "$c"
                val eq = MathExpr.Eq(
                    MathExpr.Row(
                        MathExpr.Text("$a"),
                        MathExpr.Parenthesized(MathExpr.Row(MathExpr.Var("x"), MathExpr.Text(bStr)))
                    ),
                    MathExpr.Row(MathExpr.Text(cPrefix), MathExpr.Var("x"), MathExpr.Text(dStr))
                )
                val summary = "$a(x$bStr) = ${cPrefix}x$dStr"
                val ab = a * b
                val coeffDiff = a - c
                val rhs = d - ab
                buildIntegerProblem(
                    id = "lin_i_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.LINEAR_EQ,
                    difficulty = difficulty,
                    promptTitle = "Determine o valor de x na equação:",
                    equation = eq,
                    plainSummary = summary,
                    answer = x,
                    customDistractors = listOf(
                        -x,
                        (d - b) / coeffDiff, // esqueceu de multiplicar a * b na distributiva
                        x + 3
                    ),
                    steps = listOf(
                        "1º Passo (Distributiva): Multiplique $a pelos termos dos parênteses: ${a}x ${if (ab >= 0) "+ $ab" else "− ${abs(ab)}"} = ${cPrefix}x$dStr.",
                        "2º Passo (Agrupar termos semelhantes): (${a} − $c)x = $d ${if (ab >= 0) "− $ab" else "+ ${abs(ab)}"} ⇒ ${coeffDiff}x = $rhs.",
                        "3º Passo (Isolar x): x = $rhs ÷ $coeffDiff = $x."
                    ),
                    rng = rng
                )
            }
            MathDifficulty.ADVANCED -> {
                // Equação com frações: (ax + b)/c + d = (ex + f)/g -> com mesmo MMC limpo
                // Vamos gerar: (a*x + b) / m = (c*x + d) / n onde a solução x é inteira!
                val x = nonZeroInt(rng, -7, 9)
                val m = rng.nextInt(2, 5)
                val n = rng.nextInt(2, 6).let { if (it == m) it + 1 else it }
                val a = rng.nextInt(1, 5)
                val c = rng.nextInt(1, 5).let { if (it * m == a * n) it + 1 else it }
                val kLeft = rng.nextInt(-4, 6)
                val b = m * kLeft - a * x
                // Queremos (a*x + b)/m == kLeft, então fazemos (c*x + d)/n == kLeft => d = n * kLeft - c * x
                val d = n * kLeft - c * x

                val aPrefix = if (a == 1) "" else "$a"
                val cPrefix = if (c == 1) "" else "$c"
                val bStr = when {
                    b > 0 -> " + $b"
                    b < 0 -> " − ${abs(b)}"
                    else -> ""
                }
                val dStr = when {
                    d > 0 -> " + $d"
                    d < 0 -> " − ${abs(d)}"
                    else -> ""
                }

                val eq = MathExpr.Eq(
                    MathExpr.Frac(
                        MathExpr.Row(MathExpr.Text(aPrefix), MathExpr.Var("x"), MathExpr.Text(bStr)),
                        MathExpr.Text("$m")
                    ),
                    MathExpr.Frac(
                        MathExpr.Row(MathExpr.Text(cPrefix), MathExpr.Var("x"), MathExpr.Text(dStr)),
                        MathExpr.Text("$n")
                    )
                )
                val summary = "(${aPrefix}x$bStr)/$m = (${cPrefix}x$dStr)/$n"
                val na = n * a
                val nb = n * b
                val mc = m * c
                val md = m * d
                val coeff = na - mc
                val rhs = md - nb
                buildIntegerProblem(
                    id = "lin_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.LINEAR_EQ,
                    difficulty = difficulty,
                    promptTitle = "Resolva a equação fracionária para x:",
                    equation = eq,
                    plainSummary = summary,
                    answer = x,
                    customDistractors = listOf(
                        -x,
                        x + m,
                        x - n
                    ),
                    steps = listOf(
                        "1º Passo (Multiplicação cruzada): $n·(${aPrefix}x$bStr) = $m·(${cPrefix}x$dStr).",
                        "2º Passo (Expandir os dois lados): ${na}x ${if (nb >= 0) "+ $nb" else "− ${abs(nb)}"} = ${mc}x ${if (md >= 0) "+ $md" else "− ${abs(md)}"}.",
                        "3º Passo (Isolar x): (${na} − $mc)x = $md ${if (nb >= 0) "− $nb" else "+ ${abs(nb)}"} ⇒ ${coeff}x = $rhs ⇒ x = $x."
                    ),
                    rng = rng
                )
            }
        }
    }

    // ========================================================================
    // 3. QUADRATIC_EQ (Equações do 2º Grau)
    // ========================================================================
    private fun generateQuadraticEq(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem {
        val r1 = rng.nextInt(-6, 6)
        val r2 = rng.nextInt(r1 + 1, 9) // Garante r2 > r1 (duas raízes reais distintas!)
        val a = if (difficulty == MathDifficulty.ADVANCED) rng.nextInt(2, 4) else 1
        val b = -a * (r1 + r2)
        val c = a * r1 * r2
        val delta = b * b - 4 * a * c

        val eq = buildQuadraticExpr(a, b, c)
        val summary = formatQuadraticPlain(a, b, c)

        val mode = when (difficulty) {
            MathDifficulty.BASIC -> if (rng.nextBoolean()) 0 else 2 // Maior raiz ou Soma das raízes
            MathDifficulty.INTERMEDIATE -> rng.nextInt(3)          // Maior raiz, Menor raiz ou Soma
            MathDifficulty.ADVANCED -> rng.nextInt(4)              // Maior, Menor, Discriminante Δ ou Produto/Soma
        }

        return when (mode) {
            0 -> {
                // Maior raiz (r2)
                buildIntegerProblem(
                    id = "quad_${difficulty.name.lowercase()}_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.QUADRATIC_EQ,
                    difficulty = difficulty,
                    promptTitle = "Encontre a MAIOR raiz real da equação:",
                    equation = eq,
                    plainSummary = "Maior raiz de $summary",
                    answer = r2,
                    customDistractors = listOf(
                        r1,   // a menor raiz
                        -r2,  // erro de sinal em Bhaskara/Girard
                        r1 + r2
                    ),
                    steps = listOf(
                        "1º Passo (Coeficientes): a = $a, b = $b, c = $c.",
                        "2º Passo (Discriminante Δ): Δ = ($b)² − 4·($a)·($c) = ${b * b} − ${4 * a * c} = $delta (√Δ = ${kotlin.math.sqrt(delta.toDouble()).roundToInt()}).",
                        "3º Passo (Fórmula de Bhaskara): x = (−($b) ± ${kotlin.math.sqrt(delta.toDouble()).roundToInt()}) / ${2 * a} ⇒ raízes x₁ = $r1 e x₂ = $r2.",
                        "4º Passo (Conclusão): A maior raiz é $r2."
                    ),
                    rng = rng
                )
            }
            1 -> {
                // Menor raiz (r1)
                buildIntegerProblem(
                    id = "quad_${difficulty.name.lowercase()}_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.QUADRATIC_EQ,
                    difficulty = difficulty,
                    promptTitle = "Encontre a MENOR raiz real da equação:",
                    equation = eq,
                    plainSummary = "Menor raiz de $summary",
                    answer = r1,
                    customDistractors = listOf(
                        r2,   // a maior raiz
                        -r1,  // erro de sinal
                        r1 * r2
                    ),
                    steps = listOf(
                        "1º Passo (Coeficientes): a = $a, b = $b, c = $c.",
                        "2º Passo (Discriminante Δ): Δ = ($b)² − 4·($a)·($c) = $delta (√Δ = ${kotlin.math.sqrt(delta.toDouble()).roundToInt()}).",
                        "3º Passo (Raízes): x = (${-b} ± ${kotlin.math.sqrt(delta.toDouble()).roundToInt()}) / ${2 * a} ⇒ x₁ = $r1 e x₂ = $r2.",
                        "4º Passo (Conclusão): A menor raiz é $r1."
                    ),
                    rng = rng
                )
            }
            2 -> {
                // Soma das raízes (r1 + r2 = -b/a)
                val sumRoots = r1 + r2
                buildIntegerProblem(
                    id = "quad_${difficulty.name.lowercase()}_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.QUADRATIC_EQ,
                    difficulty = difficulty,
                    promptTitle = "Qual é a SOMA das raízes (x₁ + x₂) da equação:",
                    equation = eq,
                    plainSummary = "Soma das raízes de $summary",
                    answer = sumRoots,
                    customDistractors = listOf(
                        -sumRoots, // esqueceu o sinal menos em -b/a
                        r1 * r2,   // calculou o produto c/a em vez da soma
                        r2
                    ),
                    steps = listOf(
                        "1º Passo (Relações de Girard): A soma das raízes de ax² + bx + c = 0 é dada por S = −b / a.",
                        "2º Passo (Identificar coeficientes): Aqui temos a = $a e b = $b.",
                        "3º Passo (Cálculo): S = −($b) / $a = $sumRoots (as raízes são $r1 e $r2, e $r1 + $r2 = $sumRoots)."
                    ),
                    rng = rng
                )
            }
            else -> {
                // Discriminante Δ = b² - 4ac
                buildIntegerProblem(
                    id = "quad_${difficulty.name.lowercase()}_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.QUADRATIC_EQ,
                    difficulty = difficulty,
                    promptTitle = "Calcule o discriminante (Δ = b² − 4ac) de:",
                    equation = eq,
                    plainSummary = "Δ de $summary",
                    answer = delta,
                    customDistractors = listOf(
                        b * b + 4 * a * c, // errou o sinal de -4ac
                        kotlin.math.sqrt(delta.toDouble()).roundToInt(), // tirou a raiz e respondeu √Δ
                        abs(b * b - a * c)
                    ),
                    steps = listOf(
                        "1º Passo (Identificar coeficientes): a = $a, b = $b e c = $c.",
                        "2º Passo (Aplicar a fórmula do Δ): Δ = b² − 4·a·c = ($b)² − 4·($a)·($c).",
                        "3º Passo (Resultado): Δ = ${b * b} − (${4 * a * c}) = $delta."
                    ),
                    rng = rng
                )
            }
        }
    }

    private fun buildQuadraticExpr(a: Int, b: Int, c: Int): MathExpr {
        val items = mutableListOf<MathExpr>()
        val aPrefix = when (a) {
            1 -> ""
            -1 -> "−"
            else -> "$a"
        }
        if (aPrefix.isNotEmpty()) {
            items.add(MathExpr.Text(aPrefix))
        }
        items.add(MathExpr.Pow(MathExpr.Var("x"), MathExpr.Text("2")))

        if (b != 0) {
            val sign = if (b > 0) " + " else " − "
            val absB = abs(b)
            val bText = if (absB == 1) sign else "$sign$absB"
            items.add(MathExpr.Text(bText))
            items.add(MathExpr.Var("x"))
        }

        if (c != 0) {
            val sign = if (c > 0) " + " else " − "
            items.add(MathExpr.Text("$sign${abs(c)}"))
        }

        return MathExpr.Eq(MathExpr.Row(items), MathExpr.Text("0"))
    }

    private fun formatQuadraticPlain(a: Int, b: Int, c: Int): String {
        val aStr = if (a == 1) "x²" else "${a}x²"
        val bStr = when {
            b == 0 -> ""
            b == 1 -> " + x"
            b == -1 -> " − x"
            b > 0 -> " + ${b}x"
            else -> " − ${abs(b)}x"
        }
        val cStr = when {
            c == 0 -> ""
            c > 0 -> " + $c"
            else -> " − ${abs(c)}"
        }
        return "$aStr$bStr$cStr = 0"
    }

    // ========================================================================
    // 4. FRACTIONS_PERCENT (Frações & Porcentagem)
    // ========================================================================
    private fun generateFractionsPercent(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem {
        val isFraction = rng.nextBoolean()
        if (!isFraction) {
            return when (difficulty) {
                MathDifficulty.BASIC -> {
                    val percents = listOf(10, 15, 20, 25, 30, 40, 50, 75)
                    val p = percents[rng.nextInt(percents.size)]
                    val multiplier = rng.nextInt(2, 12)
                    val base = 20 * multiplier // garante inteiro limpo para todos os percents acima
                    val ans = (p * base) / 100
                    val expr = MathExpr.Row(
                        MathExpr.Frac(MathExpr.Text("$p"), MathExpr.Text("100")),
                        MathExpr.Text(" × $base")
                    )
                    val summary = "$p% de $base"
                    buildIntegerProblem(
                        id = "fp_b_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.FRACTIONS_PERCENT,
                        difficulty = difficulty,
                        promptTitle = "Calcule $p% de $base:",
                        equation = expr,
                        plainSummary = summary,
                        answer = ans,
                        customDistractors = listOf(
                            base - ans, // calculou o complementar (100 - p)%
                            ans + 10,
                            (ans / 2).coerceAtLeast(1)
                        ),
                        steps = listOf(
                            "1º Passo (Transformar em fração): $p% equivale a $p/100.",
                            "2º Passo (Multiplicar pelo total): ($p × $base) / 100 = ${p * base} / 100.",
                            "3º Passo (Dividir): ${p * base} ÷ 100 = $ans."
                        ),
                        rng = rng
                    )
                }
                MathDifficulty.INTERMEDIATE -> {
                    // Desconto ou acréscimo percentual
                    val isDiscount = rng.nextBoolean()
                    val percents = listOf(10, 15, 20, 25, 30, 40)
                    val p = percents[rng.nextInt(percents.size)]
                    val base = 20 * rng.nextInt(3, 15)
                    val delta = (base * p) / 100
                    val ans = if (isDiscount) base - delta else base + delta
                    val signStr = if (isDiscount) "−" else "+"
                    val expr = MathExpr.Row(
                        MathExpr.Text("$base $signStr "),
                        MathExpr.Frac(MathExpr.Text("$p"), MathExpr.Text("100")),
                        MathExpr.Text(" × $base")
                    )
                    val actionTitle = if (isDiscount) {
                        "Valor final após DESCONTO de $p% sobre $base:"
                    } else {
                        "Valor final após ACRÉSCIMO de $p% sobre $base:"
                    }
                    val summary = "$base $signStr $p% de $base"
                    buildIntegerProblem(
                        id = "fp_i_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.FRACTIONS_PERCENT,
                        difficulty = difficulty,
                        promptTitle = actionTitle,
                        equation = expr,
                        plainSummary = summary,
                        answer = ans,
                        customDistractors = listOf(
                            delta, // respondeu apenas o desconto/acréscimo
                            if (isDiscount) base + delta else base - delta, // inverteu desconto/acréscimo
                            base - p
                        ),
                        steps = listOf(
                            "1º Passo (Calcular a parcela percentual): $p% de $base = ($p/100) × $base = $delta.",
                            "2º Passo (${if (isDiscount) "Subtrair o desconto" else "Somar o acréscimo"}): $base $signStr $delta = $ans.",
                            "3º Passo (Atalho por fator): $base × ${if (isDiscount) "0,${100 - p}" else "1,$p"} = $ans."
                        ),
                        rng = rng
                    )
                }
                MathDifficulty.ADVANCED -> {
                    // Descobrir o valor original antes do desconto/aumento: x * (1 +- p/100) = final
                    val pList = listOf(20, 25, 50)
                    val p = pList[rng.nextInt(pList.size)]
                    val isIncrease = rng.nextBoolean()
                    val original = 20 * rng.nextInt(4, 15)
                    val finalVal = if (isIncrease) original * (100 + p) / 100 else original * (100 - p) / 100
                    val factorNum = if (isIncrease) 100 + p else 100 - p
                    val eq = MathExpr.Eq(
                        MathExpr.Row(
                            MathExpr.Frac(MathExpr.Text("$factorNum"), MathExpr.Text("100")),
                            MathExpr.Text(" · "),
                            MathExpr.Var("x")
                        ),
                        MathExpr.Text("$finalVal")
                    )
                    val summary = "($factorNum/100)·x = $finalVal"
                    buildIntegerProblem(
                        id = "fp_a_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.FRACTIONS_PERCENT,
                        difficulty = difficulty,
                        promptTitle = "Após ${if (isIncrease) "aumento" else "desconto"} de $p%, o valor passou a ser $finalVal. Calcule o valor original x:",
                        equation = eq,
                        plainSummary = summary,
                        answer = original,
                        customDistractors = listOf(
                            finalVal - (finalVal * p) / 100, // aplicou p% sobre o valor final!
                            finalVal + (finalVal * p) / 100,
                            original + 20
                        ),
                        steps = listOf(
                            "1º Passo (Fator multiplicativo): ${if (isIncrease) "100% + $p% = $factorNum%" else "100% − $p% = $factorNum%"} = $factorNum/100.",
                            "2º Passo (Montar a equação): ($factorNum/100) · x = $finalVal.",
                            "3º Passo (Isolar x): x = ($finalVal × 100) ÷ $factorNum = $original."
                        ),
                        rng = rng
                    )
                }
            }
        } else {
            // Operações com frações reais (barra horizontal) e resposta em fração irredutível ou inteiro
            return when (difficulty) {
                MathDifficulty.BASIC -> {
                    val den = rng.nextInt(3, 9)
                    val n1 = rng.nextInt(1, den)
                    val n2 = rng.nextInt(1, den)
                    val isAdd = rng.nextBoolean() || n1 <= n2
                    val numRes = if (isAdd) n1 + n2 else n1 - n2
                    val opStr = if (isAdd) " + " else " − "
                    val expr = MathExpr.Row(
                        MathExpr.Frac(MathExpr.Text("$n1"), MathExpr.Text("$den")),
                        MathExpr.Text(opStr),
                        MathExpr.Frac(MathExpr.Text("$n2"), MathExpr.Text("$den"))
                    )
                    val reduced = MathArenaEngine.formatReducedFraction(numRes, den)
                    buildFractionProblem(
                        id = "frac_b_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.FRACTIONS_PERCENT,
                        difficulty = difficulty,
                        promptTitle = "Calcule e simplifique a fração:",
                        equation = expr,
                        plainSummary = "$n1/$den$opStr$n2/$den",
                        numerator = numRes,
                        denominator = den,
                        customFractionDistractors = listOf(
                            Pair(numRes, den * 2), // somou os denominadores!
                            Pair(if (isAdd) n1 - n2 else n1 + n2, den),
                            Pair(numRes + 1, den)
                        ),
                        steps = listOf(
                            "1º Passo (Mesmo denominador): Como os denominadores são iguais a $den, mantemos o denominador.",
                            "2º Passo (Operar numeradores): $n1$opStr$n2 = $numRes, gerando $numRes/$den.",
                            "3º Passo (Simplificar): Fração irredutível = $reduced."
                        ),
                        rng = rng
                    )
                }
                MathDifficulty.INTERMEDIATE -> {
                    val d1 = rng.nextInt(2, 6)
                    val d2 = rng.nextInt(2, 7).let { if (it == d1) it + 1 else it }
                    val n1 = rng.nextInt(1, d1 + 2)
                    val n2 = rng.nextInt(1, d2 + 2)
                    val isAdd = rng.nextBoolean()
                    val numRes = if (isAdd) (n1 * d2 + n2 * d1) else (n1 * d2 - n2 * d1)
                    val denRes = d1 * d2
                    val opStr = if (isAdd) " + " else " − "
                    val expr = MathExpr.Row(
                        MathExpr.Frac(MathExpr.Text("$n1"), MathExpr.Text("$d1")),
                        MathExpr.Text(opStr),
                        MathExpr.Frac(MathExpr.Text("$n2"), MathExpr.Text("$d2"))
                    )
                    val reduced = MathArenaEngine.formatReducedFraction(numRes, denRes)
                    buildFractionProblem(
                        id = "frac_i_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.FRACTIONS_PERCENT,
                        difficulty = difficulty,
                        promptTitle = "Calcule o resultado na forma irredutível:",
                        equation = expr,
                        plainSummary = "$n1/$d1$opStr$n2/$d2",
                        numerator = numRes,
                        denominator = denRes,
                        customFractionDistractors = listOf(
                            Pair(if (isAdd) n1 + n2 else n1 - n2, d1 + d2), // somou numerador com numerador e den com den!
                            Pair(-numRes, denRes),
                            Pair(numRes + d1, denRes)
                        ),
                        steps = listOf(
                            "1º Passo (Denominador comum): Multiplique os denominadores: $d1 × $d2 = $denRes.",
                            "2º Passo (Numerador equivalente): ($n1 × $d2)$opStr($n2 × $d1) = ${n1 * d2}$opStr${n2 * d1} = $numRes.",
                            "3º Passo (Simplificar $numRes/$denRes): Resultado irredutível = $reduced."
                        ),
                        rng = rng
                    )
                }
                MathDifficulty.ADVANCED -> {
                    // Divisão de frações ou expressão combinada: (a/b) ÷ (c/d)
                    val a = rng.nextInt(2, 8)
                    val b = rng.nextInt(2, 7)
                    val c = rng.nextInt(2, 7)
                    val d = rng.nextInt(2, 8)
                    val numRes = a * d
                    val denRes = b * c
                    val expr = MathExpr.Frac(
                        MathExpr.Frac(MathExpr.Text("$a"), MathExpr.Text("$b")),
                        MathExpr.Frac(MathExpr.Text("$c"), MathExpr.Text("$d"))
                    )
                    val reduced = MathArenaEngine.formatReducedFraction(numRes, denRes)
                    buildFractionProblem(
                        id = "frac_a_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.FRACTIONS_PERCENT,
                        difficulty = difficulty,
                        promptTitle = "Simplifique a divisão de frações:",
                        equation = expr,
                        plainSummary = "($a/$b) ÷ ($c/$d)",
                        numerator = numRes,
                        denominator = denRes,
                        customFractionDistractors = listOf(
                            Pair(a * c, b * d), // multiplicou direto sem inverter a segunda!
                            Pair(denRes, numRes), // inverteu o resultado final
                            Pair(numRes + 1, denRes)
                        ),
                        steps = listOf(
                            "1º Passo (Regra da divisão de frações): Conserve a primeira fração ($a/$b) e multiplique pelo inverso da segunda ($d/$c).",
                            "2º Passo (Multiplicar): ($a × $d) / ($b × $c) = $numRes/$denRes.",
                            "3º Passo (Simplificar pelo MDC): Resultado irredutível = $reduced."
                        ),
                        rng = rng
                    )
                }
            }
        }
    }

    // ========================================================================
    // 5. POWERS_ROOTS (Potências & Radiciação)
    // ========================================================================
    private fun generatePowersRoots(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem {
        return when (difficulty) {
            MathDifficulty.BASIC -> {
                val rootVal = rng.nextInt(3, 13)
                val rad = rootVal * rootVal
                val base = rng.nextInt(2, 6)
                val exp = rng.nextInt(2, 4)
                val powVal = base.toDouble().pow(exp.toDouble()).roundToInt()
                val ans = rootVal + powVal
                val expr = MathExpr.Row(
                    MathExpr.Sqrt(MathExpr.Text("$rad")),
                    MathExpr.Text(" + "),
                    MathExpr.Pow(MathExpr.Text("$base"), MathExpr.Text("$exp"))
                )
                val summary = "√$rad + $base^$exp"
                buildIntegerProblem(
                    id = "pr_b_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.POWERS_ROOTS,
                    difficulty = difficulty,
                    promptTitle = "Calcule o valor da expressão com raiz e potência:",
                    equation = expr,
                    plainSummary = summary,
                    answer = ans,
                    customDistractors = listOf(
                        rootVal + base * exp, // fez base * exp em vez de base^exp
                        rad / 2 + powVal,     // dividiu o radicando por 2 em vez de tirar a raiz
                        powVal - rootVal
                    ),
                    steps = listOf(
                        "1º Passo (Raiz quadrada): √$rad = $rootVal, pois $rootVal² = $rad.",
                        "2º Passo (Potenciação): $base^$exp = $powVal.",
                        "3º Passo (Soma): $rootVal + $powVal = $ans."
                    ),
                    rng = rng
                )
            }
            MathDifficulty.INTERMEDIATE -> {
                val variant = rng.nextInt(2)
                if (variant == 0) {
                    // Propriedade de potências: (b^m · b^n) / b^p = b^(m + n - p)
                    val base = rng.nextInt(2, 6)
                    val m = rng.nextInt(3, 7)
                    val n = rng.nextInt(2, 6)
                    val finalExp = rng.nextInt(2, 4)
                    val p = (m + n) - finalExp
                    val ans = base.toDouble().pow(finalExp.toDouble()).roundToInt()
                    val expr = MathExpr.Frac(
                        MathExpr.Row(
                            MathExpr.Pow(MathExpr.Text("$base"), MathExpr.Text("$m")),
                            MathExpr.Text(" · "),
                            MathExpr.Pow(MathExpr.Text("$base"), MathExpr.Text("$n"))
                        ),
                        MathExpr.Pow(MathExpr.Text("$base"), MathExpr.Text("$p"))
                    )
                    val summary = "($base^$m · $base^$n) / $base^$p"
                    buildIntegerProblem(
                        id = "pr_i_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.POWERS_ROOTS,
                        difficulty = difficulty,
                        promptTitle = "Simplifique usando propriedades de potência e calcule:",
                        equation = expr,
                        plainSummary = summary,
                        answer = ans,
                        customDistractors = listOf(
                            finalExp,          // respondeu o expoente em vez do valor da potência
                            base * finalExp,   // multiplicou base * expoente
                            base.toDouble().pow((finalExp + 1).toDouble()).roundToInt()
                        ),
                        steps = listOf(
                            "1º Passo (Produto de mesma base no numerador): $base^$m · $base^$n = $base^($m + $n) = $base^${m + n}.",
                            "2º Passo (Divisão de mesma base): $base^${m + n} ÷ $base^$p = $base^(${m + n} − $p) = $base^$finalExp.",
                            "3º Passo (Valor numérico): $base^$finalExp = $ans."
                        ),
                        rng = rng
                    )
                } else {
                    // Raiz cúbica + raiz quadrada pitagórica: ³√(c³) + √(a² + b²)
                    val cubeRoot = rng.nextInt(2, 6)
                    val cubeRad = cubeRoot * cubeRoot * cubeRoot
                    val triples = listOf(Triple(3, 4, 5), Triple(6, 8, 10), Triple(5, 12, 13), Triple(8, 15, 17))
                    val (a, b, hyp) = triples[rng.nextInt(triples.size)]
                    val ans = cubeRoot + hyp
                    val expr = MathExpr.Row(
                        MathExpr.Sqrt(MathExpr.Text("$cubeRad"), index = 3),
                        MathExpr.Text(" + "),
                        MathExpr.Sqrt(
                            MathExpr.Row(
                                MathExpr.Pow(MathExpr.Text("$a"), MathExpr.Text("2")),
                                MathExpr.Text(" + "),
                                MathExpr.Pow(MathExpr.Text("$b"), MathExpr.Text("2"))
                            ),
                            index = 2
                        )
                    )
                    val summary = "³√$cubeRad + √($a² + $b²)"
                    buildIntegerProblem(
                        id = "pr_i_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.POWERS_ROOTS,
                        difficulty = difficulty,
                        promptTitle = "Calcule o valor da expressão radical:",
                        equation = expr,
                        plainSummary = summary,
                        answer = ans,
                        customDistractors = listOf(
                            cubeRoot + a + b, // tirou a raiz de a² e b² separadamente: √(a²+b²) != a+b!
                            hyp - cubeRoot,
                            ans + 2
                        ),
                        steps = listOf(
                            "1º Passo (Raiz cúbica): ³√$cubeRad = $cubeRoot, pois $cubeRoot³ = $cubeRad.",
                            "2º Passo (Interior da raiz quadrada): $a² + $b² = ${a * a} + ${b * b} = ${hyp * hyp}.",
                            "3º Passo (Raiz quadrada e soma): √${hyp * hyp} = $hyp ⇒ $cubeRoot + $hyp = $ans."
                        ),
                        rng = rng
                    )
                }
            }
            MathDifficulty.ADVANCED -> {
                // Equação exponencial: b^(ax + c) = b^d (ou representada como valor numérico de b^d)
                val bases = listOf(2, 3, 5)
                val base = bases[rng.nextInt(bases.size)]
                val x = nonZeroInt(rng, -3, 6)
                val a = rng.nextInt(2, 4)
                val d = rng.nextInt(2, 5)
                val c = d - a * x
                val rhsVal = base.toDouble().pow(d.toDouble()).roundToInt()
                val cStr = when {
                    c > 0 -> " + $c"
                    c < 0 -> " − ${abs(c)}"
                    else -> ""
                }
                val eq = MathExpr.Eq(
                    MathExpr.Pow(
                        MathExpr.Text("$base"),
                        MathExpr.Row(MathExpr.Text("$a"), MathExpr.Var("x"), MathExpr.Text(cStr))
                    ),
                    MathExpr.Text("$rhsVal")
                )
                val summary = "$base^(${a}x$cStr) = $rhsVal"
                buildIntegerProblem(
                    id = "pr_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.POWERS_ROOTS,
                    difficulty = difficulty,
                    promptTitle = "Resolva a equação exponencial para x:",
                    equation = eq,
                    plainSummary = summary,
                    answer = x,
                    customDistractors = listOf(
                        -x,
                        d,
                        (d + c) / a.coerceAtLeast(1)
                    ),
                    steps = listOf(
                        "1º Passo (Igualar as bases): Fatore $rhsVal na base $base ⇒ $rhsVal = $base^$d.",
                        "2º Passo (Igualar os expoentes): ${a}x$cStr = $d.",
                        "3º Passo (Isolar x): ${a}x = ${d - c} ⇒ x = ${d - c} ÷ $a = $x."
                    ),
                    rng = rng
                )
            }
        }
    }

    // ========================================================================
    // 6. RULE_OF_THREE (Proporção & Regra de 3)
    // ========================================================================
    private fun generateRuleOfThree(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem {
        return when (difficulty) {
            MathDifficulty.BASIC -> {
                // a / b = c / x  => x = b * k, c = a * k
                val a = rng.nextInt(2, 9)
                val b = rng.nextInt(3, 11).let { if (it == a) it + 1 else it }
                val k = rng.nextInt(2, 8)
                val c = a * k
                val x = b * k
                val eq = MathExpr.Eq(
                    MathExpr.Frac(MathExpr.Text("$a"), MathExpr.Text("$b")),
                    MathExpr.Frac(MathExpr.Text("$c"), MathExpr.Var("x"))
                )
                val summary = "$a/$b = $c/x"
                buildIntegerProblem(
                    id = "rot_b_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.RULE_OF_THREE,
                    difficulty = difficulty,
                    promptTitle = "Calcule x na proporção direta:",
                    equation = eq,
                    plainSummary = summary,
                    answer = x,
                    customDistractors = listOf(
                        (a * c) / b.coerceAtLeast(1), // multiplicou errado
                        x + b,
                        c + (b - a) // pensou em soma constante em vez de razão!
                    ),
                    steps = listOf(
                        "1º Passo (Propriedade fundamental das proporções): O produto dos meios é igual ao produto dos extremos ($a · x = $b · $c).",
                        "2º Passo (Multiplicar): ${a}x = ${b * c}.",
                        "3º Passo (Dividir por $a): x = ${b * c} ÷ $a = $x."
                    ),
                    rng = rng
                )
            }
            MathDifficulty.INTERMEDIATE -> {
                // (x + d) / b = c / a
                val a = rng.nextInt(2, 7)
                val b = rng.nextInt(3, 9).let { if (it == a) it + 1 else it }
                val k = rng.nextInt(2, 6)
                val c = a * k
                val targetSum = b * k
                val d = nonZeroInt(rng, -6, 8)
                val x = targetSum - d
                val dStr = if (d >= 0) " + $d" else " − ${abs(d)}"
                val eq = MathExpr.Eq(
                    MathExpr.Frac(
                        MathExpr.Row(MathExpr.Var("x"), MathExpr.Text(dStr)),
                        MathExpr.Text("$b")
                    ),
                    MathExpr.Frac(MathExpr.Text("$c"), MathExpr.Text("$a"))
                )
                val summary = "(x$dStr)/$b = $c/$a"
                buildIntegerProblem(
                    id = "rot_i_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.RULE_OF_THREE,
                    difficulty = difficulty,
                    promptTitle = "Encontre o valor de x na proporção:",
                    equation = eq,
                    plainSummary = summary,
                    answer = x,
                    customDistractors = listOf(
                        targetSum + d, // errou sinal ao isolar x
                        targetSum,     // esqueceu de subtrair d
                        -x
                    ),
                    steps = listOf(
                        "1º Passo (Simplificar a razão à direita): $c / $a = $k.",
                        "2º Passo (Multiplicar por $b): x$dStr = $b × $k = $targetSum.",
                        "3º Passo (Isolar x): x = $targetSum ${if (d >= 0) "− $d" else "+ ${abs(d)}"} = $x."
                    ),
                    rng = rng
                )
            }
            MathDifficulty.ADVANCED -> {
                // Grandezas inversamente proporcionais ou proporção dupla: (x + a)/m = (x + b)/n
                val x = nonZeroInt(rng, -6, 12)
                val m = rng.nextInt(2, 6)
                val n = rng.nextInt(m + 1, 8)
                val k = nonZeroInt(rng, 2, 5)
                val a = m * k - x
                val b = n * k - x
                val aStr = when {
                    a > 0 -> " + $a"
                    a < 0 -> " − ${abs(a)}"
                    else -> ""
                }
                val bStr = when {
                    b > 0 -> " + $b"
                    b < 0 -> " − ${abs(b)}"
                    else -> ""
                }
                val eq = MathExpr.Eq(
                    MathExpr.Frac(
                        MathExpr.Row(MathExpr.Var("x"), MathExpr.Text(aStr)),
                        MathExpr.Text("$m")
                    ),
                    MathExpr.Frac(
                        MathExpr.Row(MathExpr.Var("x"), MathExpr.Text(bStr)),
                        MathExpr.Text("$n")
                    )
                )
                val summary = "(x$aStr)/$m = (x$bStr)/$n"
                val na = n * a
                val mb = m * b
                buildIntegerProblem(
                    id = "rot_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.RULE_OF_THREE,
                    difficulty = difficulty,
                    promptTitle = "Determine x na proporção algébrica:",
                    equation = eq,
                    plainSummary = summary,
                    answer = x,
                    customDistractors = listOf(
                        -x,
                        x + k,
                        (mb + na) / (n - m)
                    ),
                    steps = listOf(
                        "1º Passo (Multiplicar cruzado): $n·(x$aStr) = $m·(x$bStr).",
                        "2º Passo (Distributiva): ${n}x ${if (na >= 0) "+ $na" else "− ${abs(na)}"} = ${m}x ${if (mb >= 0) "+ $mb" else "− ${abs(mb)}"}.",
                        "3º Passo (Isolar x): (${n} − $m)x = ${mb - na} ⇒ ${n - m}x = ${mb - na} ⇒ x = $x."
                    ),
                    rng = rng
                )
            }
        }
    }

    // ========================================================================
    // 7. SYSTEMS_2X2 (Sistemas Lineares 2×2)
    // ========================================================================
    private fun generateSystems2x2(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem {
        val x = nonZeroInt(rng, -5, 8)
        val y = nonZeroInt(rng, -5, 8)

        val (a1, b1, a2, b2) = when (difficulty) {
            MathDifficulty.BASIC -> {
                // x + y = S ; x - y = D
                Quadruple(1, 1, 1, -1)
            }
            MathDifficulty.INTERMEDIATE -> {
                // a1*x + y = c1 ; a2*x - y = c2 (eliminação direta de y)
                val p = rng.nextInt(2, 5)
                val q = rng.nextInt(1, 4)
                Quadruple(p, 1, q, -1)
            }
            MathDifficulty.ADVANCED -> {
                val p1 = rng.nextInt(2, 5)
                val q1 = rng.nextInt(2, 4)
                val p2 = rng.nextInt(1, 4)
                val q2 = -rng.nextInt(1, 4) // garante determinante p1*q2 - p2*q1 < 0 != 0
                Quadruple(p1, q1, p2, q2)
            }
        }

        val c1 = a1 * x + b1 * y
        val c2 = a2 * x + b2 * y

        val eq1 = formatLinear2VarExpr(a1, b1, c1)
        val eq2 = formatLinear2VarExpr(a2, b2, c2)
        val combinedExpr = MathExpr.Row(
            MathExpr.Text("{ "),
            eq1,
            MathExpr.Text("  ;  "),
            eq2,
            MathExpr.Text(" }")
        )
        val plain1 = formatLinear2VarPlain(a1, b1, c1)
        val plain2 = formatLinear2VarPlain(a2, b2, c2)

        val targetKind = rng.nextInt(3) // 0 -> x, 1 -> y, 2 -> x + y
        val (prompt, ans, summaryTarget) = when (targetKind) {
            0 -> Triple("Resolva o sistema linear 2×2 e encontre o valor de x:", x, "x")
            1 -> Triple("Resolva o sistema linear 2×2 e encontre o valor de y:", y, "y")
            else -> Triple("No sistema 2×2 abaixo, calcule o valor de (x + y):", x + y, "x+y")
        }

        val summary = "{ $plain1 ; $plain2 } ($summaryTarget)"
        return buildIntegerProblem(
            id = "sys_${difficulty.name.lowercase()}_${index}_${rng.nextInt(1000, 9999)}",
            topic = MathTopic.SYSTEMS_2X2,
            difficulty = difficulty,
            promptTitle = prompt,
            equation = eq1,
            secondaryEquation = eq2,
            plainSummary = summary,
            answer = ans,
            customDistractors = listOf(
                when (targetKind) {
                    0 -> y
                    1 -> x
                    else -> x - y
                },
                -ans,
                ans + 2
            ),
            steps = listOf(
                "1º Passo (Sistema): Equação (I): $plain1 | Equação (II): $plain2.",
                if (b1 == -b2) {
                    "2º Passo (Método da Adição): Somando (I) + (II), cancelamos y: (${a1 + a2})x = ${c1 + c2} ⇒ x = $x."
                } else {
                    "2º Passo (Eliminação): Multiplicando para eliminar y, obtemos x = $x."
                },
                "3º Passo (Substituição): Substituindo x = $x na Equação (I), encontramos y = $y.",
                "4º Passo (Resposta pedida): ${
                    when (targetKind) {
                        0 -> "x = $x."
                        1 -> "y = $y."
                        else -> "x + y = $x + ($y) = ${x + y}."
                    }
                }"
            ),
            rng = rng
        )
    }

    private data class Quadruple(val a1: Int, val b1: Int, val a2: Int, val b2: Int)

    private fun formatLinear2VarExpr(a: Int, b: Int, c: Int): MathExpr {
        val items = mutableListOf<MathExpr>()
        val aStr = when (a) {
            1 -> ""
            -1 -> "−"
            else -> "$a"
        }
        if (aStr.isNotEmpty()) items.add(MathExpr.Text(aStr))
        items.add(MathExpr.Var("x"))

        val signB = if (b >= 0) " + " else " − "
        val absB = abs(b)
        val bCoeff = if (absB == 1) signB else "$signB$absB"
        items.add(MathExpr.Text(bCoeff))
        items.add(MathExpr.Var("y"))

        return MathExpr.Eq(MathExpr.Row(items), MathExpr.Text("$c"))
    }

    private fun formatLinear2VarPlain(a: Int, b: Int, c: Int): String {
        val aStr = when (a) {
            1 -> "x"
            -1 -> "−x"
            else -> "${a}x"
        }
        val bStr = when {
            b == 1 -> " + y"
            b == -1 -> " − y"
            b > 0 -> " + ${b}y"
            else -> " − ${abs(b)}y"
        }
        return "$aStr$bStr = $c"
    }

    // ========================================================================
    // 8. GEOMETRY (Geometria Plana & Espacial)
    // ========================================================================
    private fun generateGeometry(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem = when (difficulty) {
        MathDifficulty.BASIC -> {
            when (rng.nextInt(3)) {
                0 -> {
                    val base = rng.nextInt(4, 15)
                    val altura = rng.nextInt(3, 12).let { if (it == base) it + 1 else it }
                    val askArea = rng.nextBoolean()
                    val ans = if (askArea) base * altura else 2 * (base + altura)
                    val expr = MathExpr.Eq(
                        MathExpr.Var(if (askArea) "A" else "P"),
                        if (askArea) {
                            MathExpr.Text("$base × $altura")
                        } else {
                            MathExpr.Row(
                                MathExpr.Text("2 × "),
                                MathExpr.Parenthesized(MathExpr.Text("$base + $altura"))
                            )
                        }
                    )
                    buildIntegerProblem(
                        id = "geo_b_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.GEOMETRY,
                        difficulty = difficulty,
                        promptTitle = if (askArea) {
                            "Calcule a área do retângulo de base $base e altura $altura:"
                        } else {
                            "Calcule o perímetro do retângulo de lados $base e $altura:"
                        },
                        equation = expr,
                        plainSummary = if (askArea) "Área retângulo b=$base h=$altura" else "Perímetro retângulo b=$base h=$altura",
                        answer = ans,
                        customDistractors = listOf(
                            if (askArea) 2 * (base + altura) else base * altura,
                            base + altura,
                            ans + base
                        ),
                        steps = listOf(
                            if (askArea) "1º Passo (Fórmula): Área do retângulo = base × altura."
                            else "1º Passo (Fórmula): Perímetro = 2 × (base + altura).",
                            if (askArea) "2º Passo (Cálculo): A = $base × $altura = $ans."
                            else "2º Passo (Cálculo): P = 2 × ($base + $altura) = 2 × ${base + altura} = $ans."
                        ),
                        rng = rng,
                        subtopicTag = if (askArea) "Área do Retângulo" else "Perímetro"
                    )
                }
                1 -> {
                    val base = rng.nextInt(2, 9) * 2
                    val altura = rng.nextInt(3, 12)
                    val ans = (base * altura) / 2
                    val expr = MathExpr.Eq(
                        MathExpr.Var("A"),
                        MathExpr.Frac(MathExpr.Text("$base × $altura"), MathExpr.Text("2"))
                    )
                    buildIntegerProblem(
                        id = "geo_b_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.GEOMETRY,
                        difficulty = difficulty,
                        promptTitle = "Calcule a área do triângulo (base $base, altura $altura):",
                        equation = expr,
                        plainSummary = "Área triângulo ($base × $altura)/2",
                        answer = ans,
                        customDistractors = listOf(
                            base * altura,
                            base + altura,
                            ans + altura
                        ),
                        steps = listOf(
                            "1º Passo (Fórmula): Área do triângulo = (base × altura) ÷ 2.",
                            "2º Passo (Produto): $base × $altura = ${base * altura}.",
                            "3º Passo (Divisão): ${base * altura} ÷ 2 = $ans."
                        ),
                        rng = rng,
                        subtopicTag = "Área do Triângulo"
                    )
                }
                else -> {
                    val alpha = rng.nextInt(28, 72)
                    val beta = rng.nextInt(30, 76)
                    val ans = 180 - alpha - beta
                    val expr = MathExpr.Eq(
                        MathExpr.Row(MathExpr.Text("${alpha}° + ${beta}° + "), MathExpr.Var("x")),
                        MathExpr.Text("180°")
                    )
                    buildIntegerProblem(
                        id = "geo_b_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.GEOMETRY,
                        difficulty = difficulty,
                        promptTitle = "Num triângulo com ângulos ${alpha}° e ${beta}°, encontre x (em graus):",
                        equation = expr,
                        plainSummary = "${alpha}° + ${beta}° + x = 180°",
                        answer = ans,
                        customDistractors = listOf(
                            alpha + beta,
                            360 - alpha - beta,
                            90 - alpha
                        ),
                        steps = listOf(
                            "1º Passo (Soma interna): Os ângulos internos de qualquer triângulo somam 180°.",
                            "2º Passo (Somar os dados): ${alpha}° + ${beta}° = ${alpha + beta}°.",
                            "3º Passo (Subtrair de 180°): x = 180 − ${alpha + beta} = $ans°."
                        ),
                        rng = rng,
                        subtopicTag = "Ângulos no Triângulo"
                    )
                }
            }
        }
        MathDifficulty.INTERMEDIATE -> {
            when (rng.nextInt(3)) {
                0 -> {
                    val baseTriples = listOf(
                        Triple(3, 4, 5),
                        Triple(6, 8, 10),
                        Triple(9, 12, 15),
                        Triple(5, 12, 13),
                        Triple(8, 15, 17)
                    )
                    val (a, b, c) = baseTriples[rng.nextInt(baseTriples.size)]
                    val askHypotenuse = rng.nextBoolean()
                    val ans = if (askHypotenuse) c else a
                    val expr = if (askHypotenuse) {
                        MathExpr.Eq(
                            MathExpr.Row(
                                MathExpr.Pow(MathExpr.Text("$a"), MathExpr.Text("2")),
                                MathExpr.Text(" + "),
                                MathExpr.Pow(MathExpr.Text("$b"), MathExpr.Text("2"))
                            ),
                            MathExpr.Pow(MathExpr.Var("x"), MathExpr.Text("2"))
                        )
                    } else {
                        MathExpr.Eq(
                            MathExpr.Row(
                                MathExpr.Pow(MathExpr.Var("x"), MathExpr.Text("2")),
                                MathExpr.Text(" + "),
                                MathExpr.Pow(MathExpr.Text("$b"), MathExpr.Text("2"))
                            ),
                            MathExpr.Pow(MathExpr.Text("$c"), MathExpr.Text("2"))
                        )
                    }
                    buildIntegerProblem(
                        id = "geo_i_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.GEOMETRY,
                        difficulty = difficulty,
                        promptTitle = if (askHypotenuse) {
                            "No triângulo retângulo de catetos $a e $b, ache a hipotenusa x:"
                        } else {
                            "Com hipotenusa $c e cateto $b, encontre o outro cateto x:"
                        },
                        equation = expr,
                        plainSummary = if (askHypotenuse) "Pitágoras: $a² + $b² = x²" else "Pitágoras: x² + $b² = $c²",
                        answer = ans,
                        customDistractors = listOf(
                            if (askHypotenuse) a + b else c - b,
                            ans * ans,
                            ans + 2
                        ),
                        steps = if (askHypotenuse) {
                            listOf(
                                "1º Passo (Pitágoras): x² = $a² + $b² = ${a * a} + ${b * b} = ${c * c}.",
                                "2º Passo (Raiz positiva): x = √${c * c} = $c."
                            )
                        } else {
                            listOf(
                                "1º Passo (Pitágoras): x² = $c² − $b² = ${c * c} − ${b * b} = ${a * a}.",
                                "2º Passo (Raiz positiva): x = √${a * a} = $a."
                            )
                        },
                        rng = rng,
                        subtopicTag = "Pitágoras"
                    )
                }
                1 -> {
                    val bMinor = rng.nextInt(4, 11)
                    val bMajor = bMinor + rng.nextInt(3, 9)
                    val h = rng.nextInt(2, 6) * 2
                    val ans = ((bMajor + bMinor) * h) / 2
                    val expr = MathExpr.Eq(
                        MathExpr.Var("A"),
                        MathExpr.Frac(
                            MathExpr.Row(
                                MathExpr.Parenthesized(MathExpr.Text("$bMajor + $bMinor")),
                                MathExpr.Text(" × $h")
                            ),
                            MathExpr.Text("2")
                        )
                    )
                    buildIntegerProblem(
                        id = "geo_i_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.GEOMETRY,
                        difficulty = difficulty,
                        promptTitle = "Calcule a área do trapézio (B=$bMajor, b=$bMinor, h=$h):",
                        equation = expr,
                        plainSummary = "Área trapézio (($bMajor+$bMinor)×$h)/2",
                        answer = ans,
                        customDistractors = listOf(
                            (bMajor + bMinor) * h,
                            (bMajor * h) / 2,
                            ans + h
                        ),
                        steps = listOf(
                            "1º Passo (Soma das bases): B + b = $bMajor + $bMinor = ${bMajor + bMinor}.",
                            "2º Passo (Fórmula): A = (${bMajor + bMinor} × $h) ÷ 2 = ${(bMajor + bMinor) * h} ÷ 2 = $ans."
                        ),
                        rng = rng,
                        subtopicTag = "Área do Trapézio"
                    )
                }
                else -> {
                    val a = rng.nextInt(2, 7)
                    val b = rng.nextInt(2, 7)
                    val c = rng.nextInt(3, 8)
                    val ans = a * b * c
                    val expr = MathExpr.Eq(
                        MathExpr.Var("V"),
                        MathExpr.Text("$a × $b × $c")
                    )
                    buildIntegerProblem(
                        id = "geo_i_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.GEOMETRY,
                        difficulty = difficulty,
                        promptTitle = "Calcule o volume do bloco retangular ($a × $b × $c):",
                        equation = expr,
                        plainSummary = "Volume bloco $a × $b × $c",
                        answer = ans,
                        customDistractors = listOf(
                            2 * (a * b + b * c + a * c),
                            a + b + c,
                            ans + a * b
                        ),
                        steps = listOf(
                            "1º Passo (Volume do paralelepípedo): V = comprimento × largura × altura.",
                            "2º Passo (Multiplicação): V = $a × $b × $c = ${a * b} × $c = $ans."
                        ),
                        rng = rng,
                        subtopicTag = "Volume Espacial"
                    )
                }
            }
        }
        MathDifficulty.ADVANCED -> {
            if (rng.nextBoolean()) {
                val deltas = listOf(
                    Triple(3, 4, 5),
                    Triple(6, 8, 10),
                    Triple(5, 12, 13),
                    Triple(8, 15, 17)
                )
                val (dx, dy, dist) = deltas[rng.nextInt(deltas.size)]
                val x1 = rng.nextInt(-3, 5)
                val y1 = rng.nextInt(-3, 5)
                val x2 = x1 + dx
                val y2 = y1 + dy
                val expr = MathExpr.Eq(
                    MathExpr.Var("d"),
                    MathExpr.Sqrt(
                        MathExpr.Row(
                            MathExpr.Pow(MathExpr.Parenthesized(MathExpr.Text("$x2 − ($x1)")), MathExpr.Text("2")),
                            MathExpr.Text(" + "),
                            MathExpr.Pow(MathExpr.Parenthesized(MathExpr.Text("$y2 − ($y1)")), MathExpr.Text("2"))
                        )
                    )
                )
                buildIntegerProblem(
                    id = "geo_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.GEOMETRY,
                    difficulty = difficulty,
                    promptTitle = "Calcule a distância entre os pontos ($x1, $y1) e ($x2, $y2):",
                    equation = expr,
                    plainSummary = "d(($x1,$y1), ($x2,$y2)) = √($dx² + $dy²)",
                    answer = dist,
                    customDistractors = listOf(
                        dx + dy,
                        dist * dist,
                        abs(dx - dy)
                    ),
                    steps = listOf(
                        "1º Passo (Variações Δx e Δy): Δx = $x2 − ($x1) = $dx; Δy = $y2 − ($y1) = $dy.",
                        "2º Passo (Distância): d = √($dx² + $dy²) = √(${dx * dx} + ${dy * dy}) = √${dist * dist} = $dist."
                    ),
                    rng = rng,
                    subtopicTag = "Geometria Analítica"
                )
            } else {
                val askDiagonals = rng.nextBoolean()
                if (askDiagonals) {
                    val n = rng.nextInt(5, 13)
                    val ans = (n * (n - 3)) / 2
                    val expr = MathExpr.Eq(
                        MathExpr.Var("D"),
                        MathExpr.Frac(
                            MathExpr.Row(MathExpr.Text("$n × "), MathExpr.Parenthesized(MathExpr.Text("$n − 3"))),
                            MathExpr.Text("2")
                        )
                    )
                    buildIntegerProblem(
                        id = "geo_a_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.GEOMETRY,
                        difficulty = difficulty,
                        promptTitle = "Quantas diagonais possui um polígono convexo de $n lados?",
                        equation = expr,
                        plainSummary = "Diagonais n=$n: ($n($n−3))/2",
                        answer = ans,
                        customDistractors = listOf(
                            n * (n - 3),
                            (n * (n - 1)) / 2,
                            ans + n
                        ),
                        steps = listOf(
                            "1º Passo (Fórmula das diagonais): D = n·(n − 3) ÷ 2.",
                            "2º Passo (Substituir n = $n): D = $n × ${n - 3} ÷ 2 = ${n * (n - 3)} ÷ 2 = $ans."
                        ),
                        rng = rng,
                        subtopicTag = "Polígonos & Diagonais"
                    )
                } else {
                    val validN = listOf(5, 6, 8, 9, 10, 12)
                    val n = validN[rng.nextInt(validN.size)]
                    val ans = ((n - 2) * 180) / n
                    val expr = MathExpr.Eq(
                        MathExpr.Var("αᵢ"),
                        MathExpr.Frac(
                            MathExpr.Row(MathExpr.Parenthesized(MathExpr.Text("$n − 2")), MathExpr.Text(" × 180°")),
                            MathExpr.Text("$n")
                        )
                    )
                    buildIntegerProblem(
                        id = "geo_a_${index}_${rng.nextInt(1000, 9999)}",
                        topic = MathTopic.GEOMETRY,
                        difficulty = difficulty,
                        promptTitle = "Qual a medida de cada ângulo interno de um polígono regular de $n lados?",
                        equation = expr,
                        plainSummary = "Ângulo interno regular n=$n",
                        answer = ans,
                        customDistractors = listOf(
                            360 / n,
                            (n - 2) * 180,
                            ans + 12
                        ),
                        steps = listOf(
                            "1º Passo (Soma interna): Sᵢ = (n − 2) × 180° = ${n - 2} × 180° = ${(n - 2) * 180}°.",
                            "2º Passo (Dividir por $n vértices): αᵢ = ${(n - 2) * 180}° ÷ $n = $ans°."
                        ),
                        rng = rng,
                        subtopicTag = "Polígonos Regulares"
                    )
                }
            }
        }
    }

    // ========================================================================
    // 9. TRIGONOMETRY (Trigonometria)
    // ========================================================================
    private fun generateTrigonometry(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem = when (difficulty) {
        MathDifficulty.BASIC -> {
            if (rng.nextBoolean()) {
                val c1 = rng.nextInt(1, 5) * 2
                val c2 = rng.nextInt(1, 6)
                val useCos60 = rng.nextBoolean()
                val fnLabel = if (useCos60) "cos(60°)" else "sen(30°)"
                val term2Label = if (rng.nextBoolean()) "tg(45°)" else "sen(90°)"
                // c1 * (1/2) + c2 * 1 = c1/2 + c2
                val ans = (c1 / 2) + c2
                val expr = MathExpr.Text("$c1·$fnLabel + $c2·$term2Label")
                buildIntegerProblem(
                    id = "trig_b_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.TRIGONOMETRY,
                    difficulty = difficulty,
                    promptTitle = "Calcule o valor usando os ângulos notáveis:",
                    equation = expr,
                    plainSummary = "$c1·$fnLabel + $c2·$term2Label",
                    answer = ans,
                    customDistractors = listOf(
                        c1 + c2,
                        (c1 / 2) - c2,
                        ans + 2
                    ),
                    steps = listOf(
                        "1º Passo (Valores notáveis): $fnLabel = 1/2 e $term2Label = 1.",
                        "2º Passo (Substituir): $c1 × (1/2) + $c2 × 1 = ${c1 / 2} + $c2 = $ans."
                    ),
                    rng = rng,
                    subtopicTag = "Ângulos Notáveis"
                )
            } else {
                val triples = listOf(Triple(3, 4, 5), Triple(5, 12, 13), Triple(8, 15, 17))
                val (op, adj, hip) = triples[rng.nextInt(triples.size)]
                val ratioKind = rng.nextInt(3)
                val (fnName, num, den) = when (ratioKind) {
                    0 -> Triple("sen(θ)", op, hip)
                    1 -> Triple("cos(θ)", adj, hip)
                    else -> Triple("tg(θ)", op, adj)
                }
                val expr = MathExpr.Eq(
                    MathExpr.Text(fnName),
                    MathExpr.Var("?")
                )
                buildFractionProblem(
                    id = "trig_b_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.TRIGONOMETRY,
                    difficulty = difficulty,
                    promptTitle = "No triângulo retângulo com cateto oposto = $op, adjacente = $adj e hipotenusa = $hip, calcule:",
                    equation = expr,
                    plainSummary = "$fnName (op=$op, adj=$adj, hip=$hip)",
                    numerator = num,
                    denominator = den,
                    customFractionDistractors = listOf(
                        Pair(adj, hip),
                        Pair(op, hip),
                        Pair(op, adj),
                        Pair(adj, op),
                        Pair(den, num)
                    ),
                    steps = listOf(
                        "1º Passo (Definição SOH-CAH-TOA): sen = op/hip, cos = adj/hip, tg = op/adj.",
                        "2º Passo (Substituir): $fnName = $num/$den."
                    ),
                    rng = rng,
                    subtopicTag = "Razões no Triângulo"
                )
            }
        }
        MathDifficulty.INTERMEDIATE -> {
            if (rng.nextBoolean()) {
                val triples = listOf(Triple(3, 4, 5), Triple(5, 12, 13), Triple(8, 15, 17), Triple(7, 24, 25))
                val (a, b, c) = triples[rng.nextInt(triples.size)]
                val expr = MathExpr.Row(
                    MathExpr.Eq(MathExpr.Text("sen(θ)"), MathExpr.Frac(MathExpr.Text("$a"), MathExpr.Text("$c"))),
                    MathExpr.Text("  ⇒  cos(θ) = ?")
                )
                buildFractionProblem(
                    id = "trig_i_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.TRIGONOMETRY,
                    difficulty = difficulty,
                    promptTitle = "Para 0° < θ < 90°, dado sen(θ) = $a/$c, calcule cos(θ):",
                    equation = expr,
                    plainSummary = "sen(θ)=$a/$c ⇒ cos(θ)",
                    numerator = b,
                    denominator = c,
                    customFractionDistractors = listOf(
                        Pair(c - a, c),
                        Pair(a, b),
                        Pair(b * b, c * c)
                    ),
                    steps = listOf(
                        "1º Passo (Relação Fundamental): sen²(θ) + cos²(θ) = 1.",
                        "2º Passo (Calcular cos²θ): cos²(θ) = 1 − ($a/$c)² = (${c * c} − ${a * a})/${c * c} = ${b * b}/${c * c}.",
                        "3º Passo (1º quadrante): Como θ é agudo, cos(θ) = $b/$c."
                    ),
                    rng = rng,
                    subtopicTag = "Relação Fundamental"
                )
            } else {
                val variant = rng.nextInt(3)
                when (variant) {
                    0 -> {
                        val k = rng.nextInt(1, 5)
                        // 4 * cos²(30°) + k = 4*(3/4) + k = 3 + k
                        val ans = 3 + k
                        val expr = MathExpr.Row(
                            MathExpr.Text("4·"),
                            MathExpr.Pow(MathExpr.Text("cos(30°)"), MathExpr.Text("2")),
                            MathExpr.Text(" + $k·tg(45°)")
                        )
                        buildIntegerProblem(
                            id = "trig_i_${index}_${rng.nextInt(1000, 9999)}",
                            topic = MathTopic.TRIGONOMETRY,
                            difficulty = difficulty,
                            promptTitle = "Simplifique a expressão trigonométrica:",
                            equation = expr,
                            plainSummary = "4·cos²(30°) + $k·tg(45°)",
                            answer = ans,
                            customDistractors = listOf(2 + k, 4 + k, ans + 2),
                            steps = listOf(
                                "1º Passo (Notáveis): cos(30°) = √3/2 ⇒ cos²(30°) = 3/4, e tg(45°) = 1.",
                                "2º Passo (Calcular): 4 × (3/4) + $k × 1 = 3 + $k = $ans."
                            ),
                            rng = rng,
                            subtopicTag = "Identidades Notáveis"
                        )
                    }
                    1 -> {
                        val k = rng.nextInt(2, 6)
                        // k * (sen²(45°) * 2) = k
                        val ans = k + 2
                        val expr = MathExpr.Row(
                            MathExpr.Text("${2 * k}·"),
                            MathExpr.Pow(MathExpr.Text("sen(45°)"), MathExpr.Text("2")),
                            MathExpr.Text(" + 2·sen(90°)")
                        )
                        buildIntegerProblem(
                            id = "trig_i_${index}_${rng.nextInt(1000, 9999)}",
                            topic = MathTopic.TRIGONOMETRY,
                            difficulty = difficulty,
                            promptTitle = "Calcule o valor exato da expressão:",
                            equation = expr,
                            plainSummary = "${2 * k}·sen²(45°) + 2·sen(90°)",
                            answer = ans,
                            customDistractors = listOf(k, k + 4, 2 * k + 2),
                            steps = listOf(
                                "1º Passo (Notáveis): sen(45°) = √2/2 ⇒ sen²(45°) = 2/4 = 1/2, e sen(90°) = 1.",
                                "2º Passo (Calcular): ${2 * k} × (1/2) + 2 × 1 = $k + 2 = $ans."
                            ),
                            rng = rng,
                            subtopicTag = "Identidades Notáveis"
                        )
                    }
                    else -> {
                        val extra = rng.nextInt(2, 7)
                        val ans = 1 + extra
                        val expr = MathExpr.Row(
                            MathExpr.Pow(MathExpr.Text("sen(60°)"), MathExpr.Text("2")),
                            MathExpr.Text(" + "),
                            MathExpr.Pow(MathExpr.Text("cos(60°)"), MathExpr.Text("2")),
                            MathExpr.Text(" + $extra")
                        )
                        buildIntegerProblem(
                            id = "trig_i_${index}_${rng.nextInt(1000, 9999)}",
                            topic = MathTopic.TRIGONOMETRY,
                            difficulty = difficulty,
                            promptTitle = "Use a relação fundamental para resolver:",
                            equation = expr,
                            plainSummary = "sen²(60°) + cos²(60°) + $extra",
                            answer = ans,
                            customDistractors = listOf(extra, extra + 2, 2 + extra),
                            steps = listOf(
                                "1º Passo (Identidade): Para todo ângulo θ, sen²(θ) + cos²(θ) = 1.",
                                "2º Passo (Soma final): 1 + $extra = $ans."
                            ),
                            rng = rng,
                            subtopicTag = "Relação Fundamental"
                        )
                    }
                }
            }
        }
        MathDifficulty.ADVANCED -> {
            if (rng.nextBoolean()) {
                val a = rng.nextInt(1, 5) * 2
                val b = rng.nextInt(1, 5) * 2
                // a * cos(120°) + b * sen(150°) = a*(-1/2) + b*(1/2) = (b - a)/2
                val ans = (b - a) / 2
                val expr = MathExpr.Text("$a·cos(120°) + $b·sen(150°)")
                buildIntegerProblem(
                    id = "trig_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.TRIGONOMETRY,
                    difficulty = difficulty,
                    promptTitle = "Reduza ao 1º quadrante e calcule:",
                    equation = expr,
                    plainSummary = "$a·cos(120°) + $b·sen(150°)",
                    answer = ans,
                    customDistractors = listOf(
                        (a + b) / 2,
                        (a - b) / 2,
                        ans + 2
                    ),
                    steps = listOf(
                        "1º Passo (2º Quadrante): 120° = 180° − 60° ⇒ cos(120°) = −cos(60°) = −1/2.",
                        "2º Passo (Seno no 2ºQ): 150° = 180° − 30° ⇒ sen(150°) = +sen(30°) = +1/2.",
                        "3º Passo (Calcular): $a·(−1/2) + $b·(1/2) = −${a / 2} + ${b / 2} = $ans."
                    ),
                    rng = rng,
                    subtopicTag = "Redução de Quadrante"
                )
            } else {
                val c = rng.nextInt(1, 5)
                // (c*tg²(60°) + 2*sen(30°)) / cos(60°) = (3c + 1) / (1/2) = 6c + 2
                val ans = 6 * c + 2
                val expr = MathExpr.Frac(
                    MathExpr.Row(
                        MathExpr.Text("$c·"),
                        MathExpr.Pow(MathExpr.Text("tg(60°)"), MathExpr.Text("2")),
                        MathExpr.Text(" + 2·sen(30°)")
                    ),
                    MathExpr.Text("cos(60°)")
                )
                buildIntegerProblem(
                    id = "trig_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.TRIGONOMETRY,
                    difficulty = difficulty,
                    promptTitle = "Simplifique a razão trigonométrica:",
                    equation = expr,
                    plainSummary = "($c·tg²(60°) + 2·sen(30°)) / cos(60°)",
                    answer = ans,
                    customDistractors = listOf(
                        3 * c + 1,
                        (3 * c + 1) / 2,
                        ans - 2
                    ),
                    steps = listOf(
                        "1º Passo (Numerador): tg(60°) = √3 ⇒ tg²(60°) = 3, e sen(30°) = 1/2. Logo $c×3 + 2×(1/2) = ${3 * c + 1}.",
                        "2º Passo (Dividir por cos 60° = 1/2): ${3 * c + 1} ÷ (1/2) = ${3 * c + 1} × 2 = $ans."
                    ),
                    rng = rng,
                    subtopicTag = "Expressões Trig."
                )
            }
        }
    }

    // ========================================================================
    // 10. COMBINATORICS_PROB (Análise Combinatória & Probabilidade)
    // ========================================================================
    private fun generateCombinatoricsProb(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem = when (difficulty) {
        MathDifficulty.BASIC -> {
            if (rng.nextBoolean()) {
                val a = rng.nextInt(3, 8)
                val b = rng.nextInt(2, 7)
                val c = rng.nextInt(2, 5)
                val ans = a * b * c
                val expr = MathExpr.Text("$a × $b × $c")
                buildIntegerProblem(
                    id = "comb_b_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.COMBINATORICS_PROB,
                    difficulty = difficulty,
                    promptTitle = "Um cardápio oferece $a pratos, $b bebidas e $c sobremesas. De quantas formas distintas montar uma refeição completa?",
                    equation = expr,
                    plainSummary = "PFC: $a × $b × $c",
                    answer = ans,
                    customDistractors = listOf(
                        a + b + c,
                        a * b,
                        ans + c
                    ),
                    steps = listOf(
                        "1º Passo (Princípio Fundamental da Contagem): Multiplicamos as opções independentes.",
                        "2º Passo (Produto): $a × $b × $c = ${a * b} × $c = $ans formas."
                    ),
                    rng = rng,
                    subtopicTag = "Princípio Multiplicativo"
                )
            } else {
                val total = rng.nextInt(8, 21)
                val fav = rng.nextInt(2, total - 1)
                val expr = MathExpr.Eq(
                    MathExpr.Var("P"),
                    MathExpr.Frac(MathExpr.Text("$fav"), MathExpr.Text("$total"))
                )
                buildFractionProblem(
                    id = "comb_b_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.COMBINATORICS_PROB,
                    difficulty = difficulty,
                    promptTitle = "Uma urna tem $total bolas idênticas, sendo $fav verdes. Qual a probabilidade irredutível de sortear uma verde?",
                    equation = expr,
                    plainSummary = "Probabilidade simples $fav/$total",
                    numerator = fav,
                    denominator = total,
                    customFractionDistractors = listOf(
                        Pair(total - fav, total),
                        Pair(fav, total - fav),
                        Pair(fav + 1, total)
                    ),
                    steps = listOf(
                        "1º Passo (Razão de Laplace): P = (casos favoráveis) ÷ (casos possíveis) = $fav/$total.",
                        "2º Passo (Simplificar): Forma irredutível = ${MathArenaEngine.formatReducedFraction(fav, total)}."
                    ),
                    rng = rng,
                    subtopicTag = "Probabilidade Simples"
                )
            }
        }
        MathDifficulty.INTERMEDIATE -> {
            if (rng.nextBoolean()) {
                val n = rng.nextInt(5, 13)
                val ans = n * (n - 1)
                val expr = MathExpr.Frac(
                    MathExpr.Text("$n!"),
                    MathExpr.Text("${n - 2}!")
                )
                buildIntegerProblem(
                    id = "comb_i_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.COMBINATORICS_PROB,
                    difficulty = difficulty,
                    promptTitle = "Simplifique a razão fatorial:",
                    equation = expr,
                    plainSummary = "$n! / ${n - 2}!",
                    answer = ans,
                    customDistractors = listOf(
                        n * (n - 1) / 2,
                        n * (n - 2),
                        ans + n
                    ),
                    steps = listOf(
                        "1º Passo (Abrir o maior fatorial): $n! = $n × ${n - 1} × ${n - 2}!.",
                        "2º Passo (Cancelar ${n - 2}!): Fica apenas $n × ${n - 1} = $ans."
                    ),
                    rng = rng,
                    subtopicTag = "Fatorial"
                )
            } else {
                val choose3 = rng.nextBoolean()
                val n = if (choose3) rng.nextInt(5, 9) else rng.nextInt(5, 11)
                val k = if (choose3) 3 else 2
                val ans = if (choose3) (n * (n - 1) * (n - 2)) / 6 else (n * (n - 1)) / 2
                val expr = MathExpr.Eq(
                    MathExpr.Text("C($n, $k)"),
                    MathExpr.Frac(
                        MathExpr.Text("$n!"),
                        MathExpr.Text("$k! · ${n - k}!")
                    )
                )
                buildIntegerProblem(
                    id = "comb_i_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.COMBINATORICS_PROB,
                    difficulty = difficulty,
                    promptTitle = "De um grupo de $n pessoas, quantas comissões distintas de $k membros podem ser formadas?",
                    equation = expr,
                    plainSummary = "Combinação C($n, $k)",
                    answer = ans,
                    customDistractors = listOf(
                        if (choose3) n * (n - 1) * (n - 2) else n * (n - 1),
                        ans + n,
                        (ans - k).coerceAtLeast(1)
                    ),
                    steps = listOf(
                        "1º Passo (Fórmula da Combinação): C($n, $k) = $n! / ($k! · ${n - k}!).",
                        if (choose3) "2º Passo (Simplificar): ($n × ${n - 1} × ${n - 2}) ÷ 6 = $ans."
                        else "2º Passo (Simplificar): ($n × ${n - 1}) ÷ 2 = $ans."
                    ),
                    rng = rng,
                    subtopicTag = "Combinação C(n,k)"
                )
            }
        }
        MathDifficulty.ADVANCED -> {
            if (rng.nextBoolean()) {
                val red = rng.nextInt(3, 7)
                val blue = rng.nextInt(2, 6)
                val total = red + blue
                val num = red * (red - 1)
                val den = total * (total - 1)
                val expr = MathExpr.Eq(
                    MathExpr.Var("P"),
                    MathExpr.Row(
                        MathExpr.Frac(MathExpr.Text("$red"), MathExpr.Text("$total")),
                        MathExpr.Text(" × "),
                        MathExpr.Frac(MathExpr.Text("${red - 1}"), MathExpr.Text("${total - 1}"))
                    )
                )
                buildFractionProblem(
                    id = "comb_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.COMBINATORICS_PROB,
                    difficulty = difficulty,
                    promptTitle = "Numa urna com $red bolas vermelhas e $blue azuis, tirando 2 sem reposição, qual a probabilidade de ambas serem vermelhas?",
                    equation = expr,
                    plainSummary = "Prob. sem reposição ($red/$total)×(${red - 1}/${total - 1})",
                    numerator = num,
                    denominator = den,
                    customFractionDistractors = listOf(
                        Pair(red * red, total * total), // com reposição!
                        Pair(red, total),
                        Pair(red * blue, total * (total - 1))
                    ),
                    steps = listOf(
                        "1º Passo (1ª retirada): Probabilidade de sair vermelha = $red/$total.",
                        "2º Passo (2ª retirada sem reposição): Restam ${red - 1} vermelhas em ${total - 1} bolas ⇒ ${red - 1}/${total - 1}.",
                        "3º Passo (Multiplicar e simplificar): $num/$den = ${MathArenaEngine.formatReducedFraction(num, den)}."
                    ),
                    rng = rng,
                    subtopicTag = "Prob. Condicional"
                )
            } else {
                val cases = listOf(
                    Triple(5, 2, 60),    // 5!/2! = 60
                    Triple(6, 2, 360),   // 6!/2! = 360
                    Triple(6, 3, 120),   // 6!/3! = 120
                    Triple(7, 3, 840)    // 7!/3! = 840
                )
                val picked = cases[rng.nextInt(cases.size)]
                val n = picked.first
                val rep = picked.second
                val extraDen = if (n >= 6 && rng.nextBoolean()) 2 else 1
                val ans = picked.third / extraDen
                val denExpr = if (extraDen == 2) MathExpr.Text("$rep! · 2!") else MathExpr.Text("$rep!")
                val denStr = if (extraDen == 2) "$rep! · 2!" else "$rep!"
                val expr = MathExpr.Frac(MathExpr.Text("$n!"), denExpr)
                buildIntegerProblem(
                    id = "comb_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.COMBINATORICS_PROB,
                    difficulty = difficulty,
                    promptTitle = "Calcule o total de anagramas distintos (permutação com repetição):",
                    equation = expr,
                    plainSummary = "$n! / ($denStr)",
                    answer = ans,
                    customDistractors = listOf(
                        picked.third * 2,
                        ans / 2,
                        ans + 60
                    ),
                    steps = listOf(
                        "1º Passo (Permutação com repetição): Dividimos $n! pelo fatorial das letras repetidas ($denStr).",
                        "2º Passo (Cálculo): Resultado = $ans anagramas."
                    ),
                    rng = rng,
                    subtopicTag = "Permutação c/ Repetição"
                )
            }
        }
    }

    // ========================================================================
    // 11. FUNCTIONS_SEQ_LOG (Funções, Progressões P.A./P.G. e Logaritmos)
    // ========================================================================
    private fun generateFunctionsSeqLog(
        difficulty: MathDifficulty,
        rng: Random,
        index: Int
    ): MathProblem = when (difficulty) {
        MathDifficulty.BASIC -> {
            if (rng.nextBoolean()) {
                val a = nonZeroInt(rng, -4, 6)
                val b = rng.nextInt(-8, 10)
                val x = rng.nextInt(-3, 7)
                val fx = a * x + b
                val askOutput = rng.nextBoolean()
                val ans = if (askOutput) fx else x
                val bText = when {
                    b > 0 -> " + $b"
                    b < 0 -> " − ${abs(b)}"
                    else -> ""
                }
                val expr = if (askOutput) {
                    MathExpr.Row(
                        MathExpr.Eq(MathExpr.Text("f(x)"), MathExpr.Row(MathExpr.Text("$a"), MathExpr.Var("x"), MathExpr.Text(bText))),
                        MathExpr.Text("  ⇒  f($x) = ?")
                    )
                } else {
                    MathExpr.Row(
                        MathExpr.Eq(MathExpr.Text("f(x)"), MathExpr.Row(MathExpr.Text("$a"), MathExpr.Var("x"), MathExpr.Text(bText))),
                        MathExpr.Text("  ,  f(x) = $fx")
                    )
                }
                buildIntegerProblem(
                    id = "func_b_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.FUNCTIONS_SEQ_LOG,
                    difficulty = difficulty,
                    promptTitle = if (askOutput) "Dada f(x), calcule o valor de f($x):" else "Para qual valor de x temos f(x) = $fx?",
                    equation = expr,
                    plainSummary = if (askOutput) "f(x)=${a}x$bText; f($x)" else "f(x)=${a}x$bText = $fx",
                    answer = ans,
                    customDistractors = listOf(-ans, ans + a, ans + 2),
                    steps = if (askOutput) {
                        listOf(
                            "1º Passo (Substituir x = $x): f($x) = $a·($x)$bText.",
                            "2º Passo (Calcular): ${a * x}$bText = $fx."
                        )
                    } else {
                        listOf(
                            "1º Passo (Igualar): ${a}x$bText = $fx ⇒ ${a}x = ${fx - b}.",
                            "2º Passo (Isolar x): x = ${fx - b} ÷ $a = $x."
                        )
                    },
                    rng = rng,
                    subtopicTag = "Função do 1º Grau"
                )
            } else {
                val a1 = rng.nextInt(-4, 11)
                val r = rng.nextInt(2, 8)
                val n = rng.nextInt(5, 13)
                val an = a1 + (n - 1) * r
                val expr = MathExpr.Eq(
                    MathExpr.Text("aₙ"),
                    MathExpr.Text("$a1 + ($n − 1)·$r")
                )
                buildIntegerProblem(
                    id = "func_b_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.FUNCTIONS_SEQ_LOG,
                    difficulty = difficulty,
                    promptTitle = "Numa P.A. com a₁ = $a1 e razão r = $r, encontre o ${n}º termo (a$n):",
                    equation = expr,
                    plainSummary = "P.A. a1=$a1 r=$r n=$n",
                    answer = an,
                    customDistractors = listOf(
                        a1 + n * r, // esqueceu o (n - 1)!
                        a1 + (n - 2) * r,
                        an + r
                    ),
                    steps = listOf(
                        "1º Passo (Termo geral da P.A.): aₙ = a₁ + (n − 1)·r.",
                        "2º Passo (Substituir): a$n = $a1 + (${n - 1}) × $r = $a1 + ${(n - 1) * r} = $an."
                    ),
                    rng = rng,
                    subtopicTag = "Progressão Aritmética"
                )
            }
        }
        MathDifficulty.INTERMEDIATE -> {
            if (rng.nextBoolean()) {
                val a1 = rng.nextInt(1, 5)
                val q = if (rng.nextBoolean()) 2 else 3
                val n = if (q == 3) rng.nextInt(3, 5) else rng.nextInt(4, 6)
                val qPow = q.toDouble().pow(n - 1).roundToInt()
                val ans = a1 * qPow
                val expr = MathExpr.Eq(
                    MathExpr.Text("a$n"),
                    MathExpr.Row(
                        MathExpr.Text("$a1 × "),
                        MathExpr.Pow(MathExpr.Text("$q"), MathExpr.Text("${n - 1}"))
                    )
                )
                buildIntegerProblem(
                    id = "func_i_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.FUNCTIONS_SEQ_LOG,
                    difficulty = difficulty,
                    promptTitle = "Numa P.G. com a₁ = $a1 e razão q = $q, calcule o ${n}º termo (a$n):",
                    equation = expr,
                    plainSummary = "P.G. a1=$a1 q=$q n=$n",
                    answer = ans,
                    customDistractors = listOf(
                        a1 + (n - 1) * q, // confundiu P.G. com P.A.!
                        ans * q,
                        ans / q
                    ),
                    steps = listOf(
                        "1º Passo (Termo geral da P.G.): aₙ = a₁ · qⁿ⁻¹.",
                        "2º Passo (Potência): $q^${n - 1} = $qPow.",
                        "3º Passo (Multiplicar por a₁): $a1 × $qPow = $ans."
                    ),
                    rng = rng,
                    subtopicTag = "Progressão Geométrica"
                )
            } else {
                val bases = listOf(2, 3, 5, 10)
                val b1 = bases[rng.nextInt(3)]
                val e1 = rng.nextInt(2, 5)
                val arg1 = b1.toDouble().pow(e1).roundToInt()
                val b2 = bases[rng.nextInt(bases.size)]
                val e2 = if (b2 == 10) rng.nextInt(1, 4) else rng.nextInt(2, 4)
                val arg2 = b2.toDouble().pow(e2).roundToInt()
                val isAdd = rng.nextBoolean()
                val ans = if (isAdd) e1 + e2 else e1 - e2
                val op = if (isAdd) " + " else " − "
                val expr = MathExpr.Text("${formatLogTerm(b1, arg1)}$op${formatLogTerm(b2, arg2)}")
                buildIntegerProblem(
                    id = "func_i_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.FUNCTIONS_SEQ_LOG,
                    difficulty = difficulty,
                    promptTitle = "Calcule o valor exato da expressão logarítmica:",
                    equation = expr,
                    plainSummary = "log_$b1($arg1)${op}log_$b2($arg2)",
                    answer = ans,
                    customDistractors = listOf(
                        if (isAdd) e1 - e2 else e1 + e2,
                        e1 * e2,
                        ans + 1
                    ),
                    steps = listOf(
                        "1º Passo (1º Log): $b1^x = $arg1 ⇒ log_$b1($arg1) = $e1.",
                        "2º Passo (2º Log): $b2^y = $arg2 ⇒ log_$b2($arg2) = $e2.",
                        "3º Passo (Combinar): $e1$op$e2 = $ans."
                    ),
                    rng = rng,
                    subtopicTag = "Logaritmos"
                )
            }
        }
        MathDifficulty.ADVANCED -> {
            if (rng.nextBoolean()) {
                val a = if (rng.nextBoolean()) 1 else 2
                val xv = nonZeroInt(rng, -3, 4)
                val yv = rng.nextInt(-5, 9)
                val b = -2 * a * xv
                val c = a * xv * xv + yv
                val askYv = rng.nextBoolean()
                val ans = if (askYv) yv else xv
                val eqBody = (buildQuadraticExpr(a, b, c) as MathExpr.Eq).left
                val expr = MathExpr.Eq(MathExpr.Text("f(x)"), eqBody)
                buildIntegerProblem(
                    id = "func_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.FUNCTIONS_SEQ_LOG,
                    difficulty = difficulty,
                    promptTitle = if (askYv) {
                        "Calcule a ordenada do vértice (yᵥ = valor mínimo) da parábola:"
                    } else {
                        "Calcule a abscissa do vértice (xᵥ) da função quadrática:"
                    },
                    equation = expr,
                    plainSummary = "Vértice f(x) = ${formatQuadraticPlain(a, b, c).removeSuffix(" = 0")} (${if (askYv) "yv" else "xv"})",
                    answer = ans,
                    customDistractors = listOf(
                        if (askYv) xv else yv,
                        -ans,
                        ans + 2
                    ),
                    steps = listOf(
                        "1º Passo (Abscissa xᵥ): xᵥ = −b / (2a) = −($b) / (2·$a) = $xv.",
                        if (askYv) {
                            "2º Passo (Substituir xᵥ em f): yᵥ = f($xv) = $a·($xv)² + ($b)·($xv) + ($c) = $yv."
                        } else {
                            "2º Passo (Conferência): O eixo de simetria passa em x = $xv."
                        }
                    ),
                    rng = rng,
                    subtopicTag = "Vértice da Parábola"
                )
            } else {
                val b = if (rng.nextBoolean()) 2 else 3
                val k = if (b == 2) rng.nextInt(3, 6) else rng.nextInt(2, 4)
                val targetPow = b.toDouble().pow(k).roundToInt()
                val a = rng.nextInt(2, 6)
                val x = rng.nextInt(2, 10)
                val c = targetPow - a * x
                val cSign = when { c > 0 -> " + $c"; c < 0 -> " − ${abs(c)}"; else -> "" }
                val expr = MathExpr.Eq(
                    MathExpr.Text(formatLogTerm(b, "${a}x$cSign")),
                    MathExpr.Text("$k")
                )
                buildIntegerProblem(
                    id = "func_a_${index}_${rng.nextInt(1000, 9999)}",
                    topic = MathTopic.FUNCTIONS_SEQ_LOG,
                    difficulty = difficulty,
                    promptTitle = "Resolva a equação logarítmica para encontrar x:",
                    equation = expr,
                    plainSummary = "log_$b(${a}x$cSign) = $k",
                    answer = x,
                    customDistractors = listOf(
                        x + 2,
                        (targetPow + c) / a,
                        -x
                    ),
                    steps = listOf(
                        "1º Passo (Definição de log): ${a}x$cSign = $b^$k = $targetPow.",
                        "2º Passo (Isolar x): ${a}x = $targetPow − ($c) = ${a * x} ⇒ x = $x."
                    ),
                    rng = rng,
                    subtopicTag = "Equação Logarítmica"
                )
            }
        }
    }

    // ========================================================================
    // Builders de MathProblem e Distratores Inteligentes
    // ========================================================================
    private fun buildIntegerProblem(
        id: String,
        topic: MathTopic,
        difficulty: MathDifficulty,
        promptTitle: String,
        equation: MathExpr,
        plainSummary: String,
        answer: Int,
        customDistractors: List<Int>,
        steps: List<String>,
        rng: Random,
        secondaryEquation: MathExpr? = null,
        subtopicTag: String = topic.shortLabel
    ): MathProblem {
        val answerStr = answer.toString()
        val optionsSet = LinkedHashSet<Int>()
        optionsSet.add(answer)

        for (d in customDistractors) {
            if (optionsSet.size < 4 && d != answer) {
                optionsSet.add(d)
            }
        }

        val fallbackOffsets = listOf(-answer, answer + 1, answer - 1, answer + 2, answer - 2, answer + 3, answer - 3, answer + 5, answer - 4)
        for (candidate in fallbackOffsets) {
            if (optionsSet.size >= 4) break
            if (candidate != answer) {
                optionsSet.add(candidate)
            }
        }

        var delta = 6
        while (optionsSet.size < 4) {
            optionsSet.add(answer + delta)
            delta++
        }

        val shuffledOptions = optionsSet.map { it.toString() }.shuffled(rng)
        return MathProblem(
            id = id,
            topic = topic,
            difficulty = difficulty,
            promptTitle = promptTitle,
            equation = equation,
            plainSummary = plainSummary,
            answerDisplay = answerStr,
            acceptedValues = listOf(answer.toDouble()),
            options = shuffledOptions,
            steps = steps,
            secondaryEquation = secondaryEquation,
            subtopicTag = subtopicTag
        )
    }

    private fun buildFractionProblem(
        id: String,
        topic: MathTopic,
        difficulty: MathDifficulty,
        promptTitle: String,
        equation: MathExpr,
        plainSummary: String,
        numerator: Int,
        denominator: Int,
        customFractionDistractors: List<Pair<Int, Int>>,
        steps: List<String>,
        rng: Random,
        subtopicTag: String = topic.shortLabel
    ): MathProblem {
        val answerStr = MathArenaEngine.formatReducedFraction(numerator, denominator)
        val exactVal = numerator.toDouble() / denominator.toDouble()

        val chosenStrings = ArrayList<String>(4)
        val chosenValues = ArrayList<Double>(4)
        chosenStrings.add(answerStr)
        chosenValues.add(exactVal)

        fun tryAddOption(num: Int, den: Int) {
            if (chosenStrings.size >= 4 || den == 0) return
            val formatted = MathArenaEngine.formatReducedFraction(num, den)
            val value = num.toDouble() / den.toDouble()
            if (formatted !in chosenStrings && chosenValues.none { abs(it - value) < 1e-3 }) {
                chosenStrings.add(formatted)
                chosenValues.add(value)
            }
        }

        for ((dNum, dDen) in customFractionDistractors) {
            tryAddOption(dNum, dDen)
        }

        val g = MathArenaEngine.gcd(numerator.toLong(), denominator.toLong()).toInt()
        val redNum = numerator / g
        val redDen = denominator / g

        val fallbacks = listOf(
            Pair(-redNum, redDen),
            Pair(redNum + 1, redDen),
            Pair(redNum - 1, redDen),
            Pair(redDen, if (redNum == 0) 2 else redNum),
            Pair(redNum + 2, redDen),
            Pair(redNum, redDen + 1),
            Pair(redNum + 3, redDen)
        )
        for ((fNum, fDen) in fallbacks) {
            tryAddOption(fNum, fDen)
        }

        var extra = 4
        while (chosenStrings.size < 4) {
            tryAddOption(redNum + extra, redDen)
            extra++
        }

        return MathProblem(
            id = id,
            topic = topic,
            difficulty = difficulty,
            promptTitle = promptTitle,
            equation = equation,
            plainSummary = plainSummary,
            answerDisplay = answerStr,
            acceptedValues = listOf(exactVal),
            options = chosenStrings.shuffled(rng),
            steps = steps,
            subtopicTag = subtopicTag
        )
    }

    private fun formatLogTerm(base: Int, arg: Any): String {
        val sub = when (base) {
            2 -> "₂"
            3 -> "₃"
            5 -> "₅"
            10 -> "₁₀"
            else -> "$base"
        }
        return "log$sub($arg)"
    }

    private fun nonZeroInt(rng: Random, minInclusive: Int, maxInclusive: Int): Int {
        var v = rng.nextInt(minInclusive, maxInclusive + 1)
        if (v == 0) v = if (maxInclusive >= 1) 2 else -2
        return v
    }
}

object AlgebraicNormalizer {
    fun normalize(expr: String): String = expr
        .replace(" ", "")
        .replace("**", "^")
        .replace("·", "*")
        .replace("×", "*")
        .lowercase()
        .trim()

    fun areEquivalent(a: String, b: String): Boolean = normalize(a) == normalize(b)
}

data class FallaciousProofChallenge(
    val title: String,
    val steps: List<String>,
    val faultyLineIndex: Int,
    val counterexampleHint: String
)

data class HintLadderState(
    val level: Int,
    val eloMultiplier: Double,
    val scheduleTwinAfterHours: Int
)

object MathArenaSocraticExtensions {
    private val challenges = listOf(
        FallaciousProofChallenge(
            title = "Prova falaciosa: 2 = 1",
            steps = listOf(
                "1) Seja a = b (com a, b ≠ 0).",
                "2) Multiplique ambos os lados por a: a² = ab.",
                "3) Subtraia b²: a² - b² = ab - b².",
                "4) Fatore: (a - b)(a + b) = b(a - b).",
                "5) Divida por (a - b): a + b = b → 2b = b → 2 = 1."
            ),
            faultyLineIndex = 4,
            counterexampleHint = "Como a = b, temos (a - b) = 0; a divisão por zero é inválida."
        ),
        FallaciousProofChallenge(
            title = "Prova falaciosa: √(-1·-1) = -1",
            steps = listOf(
                "1) 1 = √1 = √((-1)·(-1)).",
                "2) Pela regra √(ab) = √a · √b, temos √(-1) · √(-1).",
                "3) Como √(-1) = i, segue i · i = i² = -1."
            ),
            faultyLineIndex = 1,
            counterexampleHint = "A identidade √(ab) = √a · √b nos reais exige a ≥ 0 e b ≥ 0."
        )
    )

    fun generateFallaciousProof(seed: Long): FallaciousProofChallenge =
        challenges[(kotlin.math.abs(seed) % challenges.size).toInt()]

    fun unlockHint(level: Int): HintLadderState = when (level.coerceIn(0, 3)) {
        1 -> HintLadderState(level = 1, eloMultiplier = 0.85, scheduleTwinAfterHours = 0)
        2 -> HintLadderState(level = 2, eloMultiplier = 0.60, scheduleTwinAfterHours = 0)
        3 -> HintLadderState(level = 3, eloMultiplier = 0.0, scheduleTwinAfterHours = 72)
        else -> HintLadderState(level = 0, eloMultiplier = 1.0, scheduleTwinAfterHours = 0)
    }
}
