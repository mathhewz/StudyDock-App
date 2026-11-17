package com.studydock.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class MathArenaEngineTest {

    private fun containsExpr(expr: MathExpr, predicate: (MathExpr) -> Boolean): Boolean {
        if (predicate(expr)) return true
        return when (expr) {
            is MathExpr.Row -> expr.items.any { containsExpr(it, predicate) }
            is MathExpr.Frac -> containsExpr(expr.numerator, predicate) || containsExpr(expr.denominator, predicate)
            is MathExpr.Pow -> containsExpr(expr.base, predicate) || containsExpr(expr.exponent, predicate)
            is MathExpr.Sqrt -> containsExpr(expr.radicand, predicate)
            is MathExpr.Eq -> containsExpr(expr.left, predicate) || containsExpr(expr.right, predicate)
            is MathExpr.Parenthesized -> containsExpr(expr.inner, predicate)
            is MathExpr.Text, is MathExpr.Var -> false
        }
    }

    @Test
    fun parseMathInputHandlesIntegersNegativesDecimalsFractionsAndRejectsInvalid() {
        // Integers and Unicode signs
        assertEquals(0.0, parseMathInput("0")!!, 1e-9)
        assertEquals(42.0, parseMathInput("  42  ")!!, 1e-9)
        assertEquals(15.0, parseMathInput("+15")!!, 1e-9)
        assertEquals(-12.0, parseMathInput("-12")!!, 1e-9)
        assertEquals(-4.0, parseMathInput("−4")!!, 1e-9)
        assertEquals(-7.0, parseMathInput("–7")!!, 1e-9)
        assertEquals(-9.0, parseMathInput("—9")!!, 1e-9)

        // Decimals with comma and dot
        assertEquals(2.5, parseMathInput("2,5")!!, 1e-9)
        assertEquals(2.5, parseMathInput("2.5")!!, 1e-9)
        assertEquals(-0.25, parseMathInput(" -0,25 ")!!, 1e-9)
        assertEquals(0.5, parseMathInput(".5")!!, 1e-9)

        // Fractions (including Unicode minus, spaces, negative denominator, decimal numerator)
        assertEquals(0.75, parseMathInput("3/4")!!, 1e-9)
        assertEquals(-2.5, parseMathInput("-5/2")!!, 1e-9)
        assertEquals(-2.5, parseMathInput("−5/2")!!, 1e-9)
        assertEquals(-0.75, parseMathInput(" 6 / -8 ")!!, 1e-9)
        assertEquals(0.5, parseMathInput("1,5/3")!!, 1e-9)

        // Delegation consistency
        assertEquals(0.75, MathArenaEngine.parseInput("3/4")!!, 1e-9)
        assertEquals(2.5, MathProblemGenerator.parseInput("2,5")!!, 1e-9)

        // Invalid inputs
        val invalidInputs = listOf(
            "",
            "   ",
            "-",
            "+",
            ".",
            "1/0",
            "5/0.0",
            "-3/0,0",
            "1/2/3",
            "abc",
            "/2",
            "2/",
            "NaN",
            "Infinity",
            "-Infinity"
        )
        for (raw in invalidInputs) {
            assertNull("Expected null for invalid input '$raw'", parseMathInput(raw))
        }
    }

    @Test
    fun mathArenaEngineFormattingAndProblemCheckingWorkAccurately() {
        assertEquals("3/4", MathArenaEngine.formatReducedFraction(6, 8))
        assertEquals("-3/4", MathArenaEngine.formatReducedFraction(6, -8))
        assertEquals("3/4", MathArenaEngine.formatReducedFraction(-6, -8))
        assertEquals("-2", MathArenaEngine.formatReducedFraction(-6, 3))
        assertEquals("0", MathArenaEngine.formatReducedFraction(0, 5))

        assertEquals("4", MathArenaEngine.formatCleanDouble(4.0))
        assertEquals("2.5", MathArenaEngine.formatCleanDouble(2.5))
        assertEquals("1.25", MathArenaEngine.formatCleanDouble(1.25))

        val problem = MathProblem(
            id = "test_frac",
            topic = MathTopic.FRACTIONS_PERCENT,
            difficulty = MathDifficulty.BASIC,
            promptTitle = "Simplifique:",
            equation = MathExpr.Frac(MathExpr.Text("3"), MathExpr.Text("4")),
            plainSummary = "3/4",
            answerDisplay = "3/4",
            acceptedValues = listOf(0.75),
            options = listOf("3/4", "1/2", "4/3", "1/4"),
            steps = listOf("Passo 1")
        )
        assertTrue(problem.isCorrect("3/4"))
        assertTrue(problem.isCorrect("0,75"))
        assertTrue(problem.isCorrect("0.75"))
        assertTrue(problem.isCorrect(" 6 / 8 "))
        assertTrue(MathProblemGenerator.checkAnswer(problem, "0,7500"))
        assertFalse(problem.isCorrect("0.74"))
        assertFalse(problem.isCorrect(""))
        assertFalse(problem.isCorrect("1/0"))
    }

    @Test
    fun generatorProducesValidProblemsAcrossAllTopicsAndDifficultiesWithDeterministicSeed() {
        for (topic in MathTopic.entries) {
            for (difficulty in MathDifficulty.entries) {
                for (trial in 0 until 8) {
                    val seed = 10_000L + topic.ordinal * 100L + difficulty.ordinal * 20L + trial
                    val first = MathProblemGenerator.generate(
                        topic = topic,
                        difficulty = difficulty,
                        random = Random(seed),
                        index = trial
                    )
                    val second = MathProblemGenerator.generate(
                        topic = topic,
                        difficulty = difficulty,
                        random = Random(seed),
                        index = trial
                    )

                    // Deterministic reproducibility for same Random(seed)
                    assertEquals(
                        "Expected deterministic problem for $topic/$difficulty seed=$seed",
                        first,
                        second
                    )

                    if (topic == MathTopic.MIXED) {
                        assertTrue(
                            "MIXED must resolve to a concrete topic",
                            first.topic in MathTopic.concreteTopics
                        )
                    } else {
                        assertEquals(topic, first.topic)
                    }
                    assertEquals(difficulty, first.difficulty)
                    assertTrue(first.promptTitle.isNotBlank())
                    assertTrue(first.plainSummary.isNotBlank())
                    assertTrue(first.subtopicTag.isNotBlank())
                    assertTrue(first.equation.toPlainText().isNotBlank())

                    // Options invariant: exactly 4 distinct options including answerDisplay
                    assertEquals(
                        "Expected 4 options for $topic/$difficulty",
                        4,
                        first.options.size
                    )
                    assertEquals(
                        "Expected 4 distinct options for $topic/$difficulty: ${first.options}",
                        4,
                        first.options.toSet().size
                    )
                    assertTrue(
                        "Options ${first.options} must contain answerDisplay '${first.answerDisplay}'",
                        first.answerDisplay in first.options
                    )

                    // Accepted values invariant: non-empty and matches parseMathInput(answerDisplay)
                    assertTrue(
                        "acceptedValues must not be empty for $topic/$difficulty",
                        first.acceptedValues.isNotEmpty()
                    )
                    val parsedAnswer = parseMathInput(first.answerDisplay)
                    assertNotNull(
                        "answerDisplay '${first.answerDisplay}' must parse cleanly",
                        parsedAnswer
                    )
                    assertTrue(
                        "At least one acceptedValue ${first.acceptedValues} must match parsed answer $parsedAnswer",
                        first.acceptedValues.any { abs(it - parsedAnswer!!) <= 1e-4 }
                    )
                    assertTrue(first.isCorrect(first.answerDisplay))

                    // Didactic steps invariant
                    assertTrue(
                        "steps must not be empty for $topic/$difficulty",
                        first.steps.isNotEmpty()
                    )
                    assertTrue(first.steps.all { it.isNotBlank() })
                }
            }
        }

        // Session determinism and MIXED topic coverage
        val sessionCount = MathTopic.concreteTopics.size.coerceAtLeast(15)
        val sessionA = MathProblemGenerator.generateSession(
            topic = MathTopic.MIXED,
            difficulty = MathDifficulty.INTERMEDIATE,
            count = sessionCount,
            seed = 777L
        )
        val sessionB = MathProblemGenerator.generateSession(
            topic = MathTopic.MIXED,
            difficulty = MathDifficulty.INTERMEDIATE,
            count = sessionCount,
            seed = 777L
        )
        assertEquals(sessionCount, sessionA.size)
        assertEquals(sessionA, sessionB)
        assertEquals(
            "First concreteTopics items must cover all concrete topics",
            MathTopic.concreteTopics.toSet(),
            sessionA.take(MathTopic.concreteTopics.size).map { it.topic }.toSet()
        )
    }

    @Test
    fun generatedMathExprTreesContainExpectedNodesPerTopic() {
        // ARITHMETIC ADVANCED: Frac + Pow + Parenthesized
        val arithAdv = MathProblemGenerator.generate(
            topic = MathTopic.ARITHMETIC,
            difficulty = MathDifficulty.ADVANCED,
            random = Random(1L)
        )
        assertTrue(containsExpr(arithAdv.equation) { it is MathExpr.Frac })
        assertTrue(containsExpr(arithAdv.equation) { it is MathExpr.Pow })
        assertTrue(containsExpr(arithAdv.equation) { it is MathExpr.Parenthesized })

        // LINEAR_EQ: Eq + Var("x"); INTERMEDIATE adds Parenthesized; ADVANCED adds Frac
        val linearBasic = MathProblemGenerator.generate(
            topic = MathTopic.LINEAR_EQ,
            difficulty = MathDifficulty.BASIC,
            random = Random(2L)
        )
        assertTrue(containsExpr(linearBasic.equation) { it is MathExpr.Eq })
        assertTrue(containsExpr(linearBasic.equation) { it is MathExpr.Var && it.symbol == "x" })

        val linearInter = MathProblemGenerator.generate(
            topic = MathTopic.LINEAR_EQ,
            difficulty = MathDifficulty.INTERMEDIATE,
            random = Random(3L)
        )
        assertTrue(containsExpr(linearInter.equation) { it is MathExpr.Eq })
        assertTrue(containsExpr(linearInter.equation) { it is MathExpr.Parenthesized })

        val linearAdv = MathProblemGenerator.generate(
            topic = MathTopic.LINEAR_EQ,
            difficulty = MathDifficulty.ADVANCED,
            random = Random(4L)
        )
        assertTrue(containsExpr(linearAdv.equation) { it is MathExpr.Eq })
        assertTrue(containsExpr(linearAdv.equation) { it is MathExpr.Frac })

        // QUADRATIC_EQ: Eq + Pow(x, 2)
        for (diff in MathDifficulty.entries) {
            val quad = MathProblemGenerator.generate(
                topic = MathTopic.QUADRATIC_EQ,
                difficulty = diff,
                random = Random(10L + diff.ordinal)
            )
            assertTrue(containsExpr(quad.equation) { it is MathExpr.Eq })
            assertTrue(
                containsExpr(quad.equation) {
                    it is MathExpr.Pow && it.base == MathExpr.Var("x") && it.exponent == MathExpr.Text("2")
                }
            )
        }

        // FRACTIONS_PERCENT: always uses Frac; across batch covers nested Frac and Eq
        val fpAdvBatch = (0 until 24).map { idx ->
            MathProblemGenerator.generate(
                topic = MathTopic.FRACTIONS_PERCENT,
                difficulty = MathDifficulty.ADVANCED,
                random = Random(200L + idx),
                index = idx
            )
        }
        assertTrue(fpAdvBatch.all { containsExpr(it.equation) { node -> node is MathExpr.Frac } })
        assertTrue(fpAdvBatch.any { containsExpr(it.equation) { node -> node is MathExpr.Eq } })
        assertTrue(
            fpAdvBatch.any {
                containsExpr(it.equation) { node ->
                    node is MathExpr.Frac && node.numerator is MathExpr.Frac && node.denominator is MathExpr.Frac
                }
            }
        )

        // POWERS_ROOTS: BASIC has Sqrt + Pow; INTERMEDIATE covers Frac and cube Sqrt; ADVANCED has Eq + Pow
        val prBasic = MathProblemGenerator.generate(
            topic = MathTopic.POWERS_ROOTS,
            difficulty = MathDifficulty.BASIC,
            random = Random(5L)
        )
        assertTrue(containsExpr(prBasic.equation) { it is MathExpr.Sqrt && it.index == 2 })
        assertTrue(containsExpr(prBasic.equation) { it is MathExpr.Pow })

        val prInterBatch = (0 until 24).map { idx ->
            MathProblemGenerator.generate(
                topic = MathTopic.POWERS_ROOTS,
                difficulty = MathDifficulty.INTERMEDIATE,
                random = Random(300L + idx),
                index = idx
            )
        }
        assertTrue(prInterBatch.all { containsExpr(it.equation) { node -> node is MathExpr.Pow } })
        assertTrue(prInterBatch.any { containsExpr(it.equation) { node -> node is MathExpr.Frac } })
        assertTrue(prInterBatch.any { containsExpr(it.equation) { node -> node is MathExpr.Sqrt && node.index == 3 } })

        val prAdv = MathProblemGenerator.generate(
            topic = MathTopic.POWERS_ROOTS,
            difficulty = MathDifficulty.ADVANCED,
            random = Random(6L)
        )
        assertTrue(containsExpr(prAdv.equation) { it is MathExpr.Eq })
        assertTrue(containsExpr(prAdv.equation) { it is MathExpr.Pow })

        // RULE_OF_THREE: Eq of two Fracs
        for (diff in MathDifficulty.entries) {
            val rot = MathProblemGenerator.generate(
                topic = MathTopic.RULE_OF_THREE,
                difficulty = diff,
                random = Random(20L + diff.ordinal)
            )
            val eq = rot.equation as MathExpr.Eq
            assertTrue(eq.left is MathExpr.Frac)
            assertTrue(eq.right is MathExpr.Frac)
            assertTrue(containsExpr(rot.equation) { it is MathExpr.Var && it.symbol == "x" })
        }

        // SYSTEMS_2X2: Eq nodes + secondaryEquation + x and y
        for (diff in MathDifficulty.entries) {
            val sys = MathProblemGenerator.generate(
                topic = MathTopic.SYSTEMS_2X2,
                difficulty = diff,
                random = Random(30L + diff.ordinal)
            )
            assertTrue(containsExpr(sys.equation) { it is MathExpr.Eq })
            assertTrue(containsExpr(sys.equation) { it is MathExpr.Var && it.symbol == "x" })
            assertTrue(containsExpr(sys.equation) { it is MathExpr.Var && it.symbol == "y" })
            assertTrue(sys.secondaryEquation is MathExpr.Eq)
        }

        // Novas frentes concretas (Geometria, Trigonometria, Combinatória/Prob, Funções/Seq/Log)
        val newTopics = listOf(
            MathTopic.GEOMETRY,
            MathTopic.TRIGONOMETRY,
            MathTopic.COMBINATORICS_PROB,
            MathTopic.FUNCTIONS_SEQ_LOG
        )
        assertEquals(11, MathTopic.concreteTopics.size)
        assertTrue(newTopics.all { it in MathTopic.concreteTopics })
        assertTrue(MathPracticeMode.entries.size >= 5)

        val customSubset = setOf(MathTopic.GEOMETRY, MathTopic.TRIGONOMETRY)
        val subsetSession = MathProblemGenerator.generateSessionForTopics(
            topics = customSubset,
            difficulty = MathDifficulty.INTERMEDIATE,
            count = 10,
            seed = 777L
        )
        assertEquals(10, subsetSession.size)
        assertTrue(subsetSession.all { it.topic in customSubset })
        assertEquals(customSubset, subsetSession.map { it.topic }.toSet())

        for (cat in MathTopicCategory.entries) {
            assertTrue(MathTopic.topicsForCategory(cat).isNotEmpty())
        }
    }
}
