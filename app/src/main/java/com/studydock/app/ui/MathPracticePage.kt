@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studydock.app.data.MathAnswerMode
import com.studydock.app.data.MathArenaEngine
import com.studydock.app.data.MathDifficulty
import com.studydock.app.data.MathExpr
import com.studydock.app.data.MathPracticeMode
import com.studydock.app.data.MathProblem
import com.studydock.app.data.MathProblemGenerator
import com.studydock.app.data.MathTimerPreset
import com.studydock.app.data.MathTopic
import com.studydock.app.data.MathTopicCategory
import com.studydock.app.data.Subject
import com.studydock.app.data.SubjectHierarchy
import com.studydock.app.data.parseMathInput
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val MATH_OPERATORS = setOf('+', '−', '-', '×', '÷', '·', '=')
private val KEYPAD_ACTION_KEYS = setOf("⌫", "−", "/")
private val KEYPAD_ROWS = listOf(
    listOf("7", "8", "9", "⌫"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "/")
)

@Stable
private class MathArenaClockState {
    var elapsedOnCurrentMs by mutableLongStateOf(0L)
    var sprintElapsedMs by mutableLongStateOf(0L)
}

private enum class MathArenaStage {
    SETUP,
    ACTIVE,
    SUMMARY
}

private enum class MathAttemptOutcome {
    CORRECT,
    WRONG,
    TIMEOUT
}

private data class MathAttemptRecord(
    val problem: MathProblem,
    val userAnswer: String,
    val outcome: MathAttemptOutcome,
    val elapsedMillis: Long,
    val pointsAwarded: Int
)

private class ArenaSoundPlayer {
    private var toneGenerator: ToneGenerator? = null

    private fun ensureGenerator(): ToneGenerator? {
        if (toneGenerator == null) {
            toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 34) }.getOrNull()
        }
        return toneGenerator
    }

    fun playCorrect(streak: Int) {
        runCatching {
            val tone = if (streak >= 3) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP
            ensureGenerator()?.startTone(tone, if (streak >= 3) 140 else 85)
        }
    }

    fun playWrong() {
        runCatching {
            ensureGenerator()?.startTone(ToneGenerator.TONE_PROP_NACK, 160)
        }
    }

    fun release() {
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }
}

@Composable
fun MathPracticePage(
    subjects: List<Subject>,
    initialSubjectId: Long? = null,
    onSavePracticeResult: (
        subjectId: Long?,
        topicLabel: String,
        totalQuestions: Int,
        correctCount: Int,
        durationSeconds: Long,
        mistakes: List<Triple<String, String, String>>
    ) -> Unit = { _, _, _, _, _, _ -> },
    onCreateFlashcardsFromMistakes: (
        subjectId: Long?,
        cards: List<Pair<String, String>>
    ) -> Unit = { _, _ -> },
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activeSubjects = remember(subjects) {
        SubjectHierarchy.orderedHierarchically(subjects.filter { !it.archived })
    }
    val subjectById = remember(subjects) { subjects.associateBy { it.id } }

    val defaultSubjectId = remember(activeSubjects, initialSubjectId) {
        when {
            initialSubjectId != null && activeSubjects.any { it.id == initialSubjectId } -> initialSubjectId
            else -> activeSubjects.firstOrNull {
                val n = it.name.lowercase(Locale.getDefault())
                "matem" in n || "exata" in n || "cálculo" in n || "calculo" in n || "álgebra" in n || "algebra" in n || "geometr" in n
            }?.id
        }
    }

    var selectedSubjectId by rememberSaveable(defaultSubjectId) { mutableStateOf(defaultSubjectId) }
    var selectedMode by rememberSaveable { mutableStateOf(MathPracticeMode.CLASSIC) }
    var selectedCategory by rememberSaveable { mutableStateOf(MathTopicCategory.ALL) }
    // Comma-separated MathTopic.name values; blank == Todas as 11 frentes (Misto Geral).
    var selectedTopicsRaw by rememberSaveable { mutableStateOf("") }
    var selectedDifficulty by rememberSaveable { mutableStateOf(MathDifficulty.INTERMEDIATE) }
    var adaptiveEloEnabled by rememberSaveable { mutableStateOf(false) }
    var selectedTimerPreset by rememberSaveable { mutableStateOf(MathTimerPreset.STANDARD_20S) }
    var selectedAnswerMode by rememberSaveable { mutableStateOf(MathAnswerMode.MULTIPLE_CHOICE) }
    var selectedQuestionCount by rememberSaveable { mutableIntStateOf(10) }

    var sfxEnabled by rememberSaveable { mutableStateOf(StudyNotificationPrefs.isMathArenaSfxEnabled(context)) }
    val soundPlayer = remember { ArenaSoundPlayer() }
    DisposableEffect(Unit) {
        onDispose { soundPlayer.release() }
    }

    fun toggleSfx() {
        val next = !sfxEnabled
        sfxEnabled = next
        StudyNotificationPrefs.setMathArenaSfxEnabled(context, next)
    }

    val selectedConcreteTopics: Set<MathTopic> = remember(selectedTopicsRaw) {
        selectedTopicsRaw
            .split(',')
            .mapNotNull { token ->
                val clean = token.trim()
                if (clean.isEmpty()) null
                else MathTopic.concreteTopics.firstOrNull { it.name == clean }
            }
            .toSet()
    }
    var stage by rememberSaveable { mutableStateOf(MathArenaStage.SETUP) }
    var previewSeed by rememberSaveable { mutableLongStateOf(20261115L) }

    val resolvedPool: List<MathTopic> = remember(selectedConcreteTopics) {
        if (selectedConcreteTopics.isEmpty()) MathTopic.concreteTopics else selectedConcreteTopics.toList()
    }
    val avgTopicElo = remember(resolvedPool, stage) {
        resolvedPool
            .map { com.studydock.app.data.MathEloEngine.getTopicElo(context, it) }
            .ifEmpty { listOf(com.studydock.app.data.MathEloEngine.DEFAULT_ELO) }
            .average()
            .toInt()
    }
    val effectiveDifficulty = if (adaptiveEloEnabled) {
        com.studydock.app.data.MathEloEngine.recommendedDifficulty(avgTopicElo)
    } else {
        selectedDifficulty
    }
    val representativeTopic = if (selectedConcreteTopics.size == 1) {
        selectedConcreteTopics.first()
    } else {
        MathTopic.MIXED
    }
    val topicSummaryLabel = when {
        selectedConcreteTopics.isEmpty() -> "Todas as 11 frentes"
        selectedConcreteTopics.size == 1 -> selectedConcreteTopics.first().shortLabel
        selectedConcreteTopics.size <= 2 -> selectedConcreteTopics.joinToString(" + ") { it.shortLabel }
        else -> "${selectedConcreteTopics.size} frentes selecionadas"
    }

    val activeTimerPreset = when {
        selectedMode == MathPracticeMode.TIME_ATTACK -> MathTimerPreset.SPRINT_120S
        selectedTimerPreset.isSprint -> MathTimerPreset.STANDARD_20S
        else -> selectedTimerPreset
    }
    val targetTotalQuestions = when (selectedMode) {
        MathPracticeMode.SIMULADO -> 15
        MathPracticeMode.SURVIVAL -> selectedQuestionCount.coerceAtLeast(15)
        MathPracticeMode.TIME_ATTACK -> Int.MAX_VALUE
        MathPracticeMode.CLASSIC, MathPracticeMode.PROGRESSIVE -> selectedQuestionCount
    }

    val problems = remember { mutableStateListOf<MathProblem>() }
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    val attempts = remember { mutableStateListOf<MathAttemptRecord>() }

    var currentStreak by rememberSaveable { mutableIntStateOf(0) }
    var bestStreak by rememberSaveable { mutableIntStateOf(0) }
    var totalScore by rememberSaveable { mutableIntStateOf(0) }
    var livesRemaining by rememberSaveable { mutableIntStateOf(3) }
    var progressiveDifficulty by rememberSaveable { mutableStateOf(MathDifficulty.BASIC) }
    var progressiveHitsSinceStep by rememberSaveable { mutableIntStateOf(0) }

    var sessionStartMs by rememberSaveable { mutableLongStateOf(0L) }
    var questionStartMs by rememberSaveable { mutableLongStateOf(0L) }
    val clockState = remember { MathArenaClockState() }

    var keypadBuffer by rememberSaveable { mutableStateOf("") }
    var currentAttempt by remember { mutableStateOf<MathAttemptRecord?>(null) }
    var showStepsInActive by rememberSaveable { mutableStateOf(false) }
    var resultSaved by rememberSaveable { mutableStateOf(false) }
    var mistakeCardsCreated by rememberSaveable { mutableStateOf(false) }

    fun generateNextAdaptiveProblem(
        pool: List<MathTopic>,
        difficulty: MathDifficulty,
        itemIndex: Int,
        seedSalt: Long
    ): MathProblem {
        val chosen = pool[itemIndex % pool.size]
        return MathProblemGenerator.generate(
            topic = chosen,
            difficulty = difficulty,
            seed = seedSalt xor (itemIndex.toLong() * 7919L),
            index = itemIndex
        )
    }

    fun startNewSession(
        overridePool: List<MathTopic>? = null,
        overrideMode: MathPracticeMode? = null
    ) {
        val mode = overrideMode ?: selectedMode
        val pool = (overridePool ?: resolvedPool).ifEmpty { MathTopic.concreteTopics }
        val now = System.currentTimeMillis()
        val generated: List<MathProblem> = when (mode) {
            MathPracticeMode.SIMULADO -> {
                val partA = MathProblemGenerator.generateSessionForTopics(pool, MathDifficulty.BASIC, 5, now)
                val partB = MathProblemGenerator.generateSessionForTopics(pool, MathDifficulty.INTERMEDIATE, 5, now + 101L)
                val partC = MathProblemGenerator.generateSessionForTopics(pool, MathDifficulty.ADVANCED, 5, now + 202L)
                partA + partB + partC
            }
            MathPracticeMode.PROGRESSIVE -> {
                listOf(generateNextAdaptiveProblem(pool, MathDifficulty.BASIC, 0, now))
            }
            MathPracticeMode.TIME_ATTACK -> {
                MathProblemGenerator.generateSessionForTopics(pool, effectiveDifficulty, 25, now)
            }
            MathPracticeMode.SURVIVAL -> {
                MathProblemGenerator.generateSessionForTopics(pool, effectiveDifficulty, selectedQuestionCount.coerceAtLeast(15), now)
            }
            MathPracticeMode.CLASSIC -> {
                MathProblemGenerator.generateSessionForTopics(pool, effectiveDifficulty, selectedQuestionCount, now)
            }
        }
        problems.clear()
        problems.addAll(generated)
        currentIndex = 0
        attempts.clear()
        currentStreak = 0
        bestStreak = 0
        totalScore = 0
        livesRemaining = 3
        progressiveDifficulty = MathDifficulty.BASIC
        progressiveHitsSinceStep = 0
        sessionStartMs = now
        questionStartMs = now
        clockState.elapsedOnCurrentMs = 0L
        clockState.sprintElapsedMs = 0L
        keypadBuffer = ""
        currentAttempt = null
        showStepsInActive = false
        resultSaved = false
        mistakeCardsCreated = false
        stage = MathArenaStage.ACTIVE
    }

    fun submitAnswer(rawAnswer: String, isTimeout: Boolean = false) {
        if (stage != MathArenaStage.ACTIVE || currentAttempt != null) return
        val problem = problems.getOrNull(currentIndex) ?: return
        val elapsedMs = clockState.elapsedOnCurrentMs.coerceAtLeast(100L)

        val outcome = when {
            isTimeout -> MathAttemptOutcome.TIMEOUT
            problem.isCorrect(rawAnswer) -> MathAttemptOutcome.CORRECT
            else -> MathAttemptOutcome.WRONG
        }

        val points = if (outcome == MathAttemptOutcome.CORRECT) {
            val base = problem.difficulty.basePoints
            val streakBonus = (currentStreak * 4).coerceAtMost(24)
            val speedBonus = when {
                elapsedMs <= 4_000L -> 10
                elapsedMs <= 8_000L -> 6
                elapsedMs <= 14_000L -> 3
                else -> 0
            }
            base + streakBonus + speedBonus
        } else {
            0
        }

        if (outcome == MathAttemptOutcome.CORRECT) {
            currentStreak += 1
            if (currentStreak > bestStreak) bestStreak = currentStreak
            totalScore += points
            if (selectedMode == MathPracticeMode.PROGRESSIVE) {
                progressiveHitsSinceStep += 1
                if (progressiveHitsSinceStep >= 3) {
                    progressiveHitsSinceStep = 0
                    progressiveDifficulty = when (progressiveDifficulty) {
                        MathDifficulty.BASIC -> MathDifficulty.INTERMEDIATE
                        MathDifficulty.INTERMEDIATE -> MathDifficulty.ADVANCED
                        MathDifficulty.ADVANCED -> MathDifficulty.ADVANCED
                    }
                }
            }
            if (sfxEnabled) soundPlayer.playCorrect(currentStreak)
        } else {
            currentStreak = 0
            if (selectedMode == MathPracticeMode.SURVIVAL) {
                livesRemaining = (livesRemaining - 1).coerceAtLeast(0)
            }
            if (selectedMode == MathPracticeMode.PROGRESSIVE) {
                progressiveHitsSinceStep = 0
                progressiveDifficulty = when (progressiveDifficulty) {
                    MathDifficulty.ADVANCED -> MathDifficulty.INTERMEDIATE
                    MathDifficulty.INTERMEDIATE -> MathDifficulty.BASIC
                    MathDifficulty.BASIC -> MathDifficulty.BASIC
                }
            }
            if (sfxEnabled) soundPlayer.playWrong()
        }

        com.studydock.app.data.MathEloEngine.recordAttempt(context, problem.topic, problem.difficulty, outcome == MathAttemptOutcome.CORRECT)
        val record = MathAttemptRecord(
            problem = problem,
            userAnswer = if (isTimeout) "" else rawAnswer.trim(),
            outcome = outcome,
            elapsedMillis = elapsedMs,
            pointsAwarded = points
        )
        currentAttempt = record
        attempts.add(record)
        showStepsInActive = outcome != MathAttemptOutcome.CORRECT
    }

    fun advanceToNextQuestion() {
        val isSprintExpired = activeTimerPreset.totalSprintSeconds != null &&
            clockState.sprintElapsedMs >= activeTimerPreset.totalSprintSeconds!! * 1000L
        val outOfLives = selectedMode == MathPracticeMode.SURVIVAL && livesRemaining <= 0
        val reachedTargetCount = if (selectedMode == MathPracticeMode.TIME_ATTACK) {
            false
        } else {
            currentIndex + 1 >= targetTotalQuestions
        }

        if (isSprintExpired || outOfLives || reachedTargetCount) {
            stage = MathArenaStage.SUMMARY
            currentAttempt = null
            return
        }

        val nextIdx = currentIndex + 1
        if (nextIdx >= problems.size) {
            val pool = resolvedPool.ifEmpty { MathTopic.concreteTopics }
            val batch = if (selectedMode == MathPracticeMode.PROGRESSIVE) {
                listOf(generateNextAdaptiveProblem(pool, progressiveDifficulty, nextIdx, System.currentTimeMillis()))
            } else {
                MathProblemGenerator.generateSessionForTopics(pool, effectiveDifficulty, 15, System.currentTimeMillis())
            }
            problems.addAll(batch)
        }

        currentIndex = nextIdx
        currentAttempt = null
        keypadBuffer = ""
        showStepsInActive = false
        questionStartMs = System.currentTimeMillis()
        clockState.elapsedOnCurrentMs = 0L
    }

    LaunchedEffect(stage, currentIndex, currentAttempt, activeTimerPreset) {
        if (stage != MathArenaStage.ACTIVE) return@LaunchedEffect
        var lastTick = SystemClock.elapsedRealtime()
        while (stage == MathArenaStage.ACTIVE) {
            delay(80L)
            val now = SystemClock.elapsedRealtime()
            val delta = (now - lastTick).coerceAtLeast(0L)
            lastTick = now

            if (currentAttempt == null) {
                clockState.elapsedOnCurrentMs += delta
                clockState.sprintElapsedMs += delta

                val perQSec = activeTimerPreset.secondsPerQuestion
                if (perQSec != null && clockState.elapsedOnCurrentMs >= perQSec * 1000L) {
                    clockState.elapsedOnCurrentMs = perQSec * 1000L
                    submitAnswer(rawAnswer = "", isTimeout = true)
                }

                val sprintSec = activeTimerPreset.totalSprintSeconds
                if (sprintSec != null && clockState.sprintElapsedMs >= sprintSec * 1000L) {
                    clockState.sprintElapsedMs = sprintSec * 1000L
                    submitAnswer(rawAnswer = "", isTimeout = true)
                    stage = MathArenaStage.SUMMARY
                }
            }
        }
    }

    when (stage) {
        MathArenaStage.SETUP -> {
            MathArenaSetupScreen(
                subjects = activeSubjects,
                subjectById = subjectById,
                selectedSubjectId = selectedSubjectId,
                onSelectSubjectId = { selectedSubjectId = it },
                selectedMode = selectedMode,
                onSelectMode = { mode ->
                    selectedMode = mode
                    if (mode == MathPracticeMode.SIMULADO) {
                        selectedQuestionCount = 15
                    } else if (mode == MathPracticeMode.SURVIVAL && selectedQuestionCount < 15) {
                        selectedQuestionCount = 20
                    }
                },
                selectedCategory = selectedCategory,
                onSelectCategory = { selectedCategory = it },
                selectedConcreteTopics = selectedConcreteTopics,
                onToggleTopic = { topic ->
                    selectedTopicsRaw = if (topic == MathTopic.MIXED) {
                        ""
                    } else {
                        val updated = selectedConcreteTopics.toMutableSet()
                        if (!updated.add(topic)) updated.remove(topic)
                        updated.joinToString(",") { it.name }
                    }
                },
                onSelectAllTopics = { selectedTopicsRaw = "" },
                topicSummaryLabel = topicSummaryLabel,
                resolvedPool = resolvedPool,
                selectedDifficulty = selectedDifficulty,
                onSelectDifficulty = { selectedDifficulty = it },
                adaptiveEloEnabled = adaptiveEloEnabled,
                avgTopicElo = avgTopicElo,
                onToggleAdaptiveElo = { adaptiveEloEnabled = it },
                selectedTimerPreset = activeTimerPreset,
                onSelectTimerPreset = { selectedTimerPreset = it },
                selectedAnswerMode = selectedAnswerMode,
                onSelectAnswerMode = { selectedAnswerMode = it },
                selectedQuestionCount = selectedQuestionCount,
                onSelectQuestionCount = { selectedQuestionCount = it },
                sfxEnabled = sfxEnabled,
                onToggleSfx = ::toggleSfx,
                previewSeed = previewSeed,
                onRefreshPreview = { previewSeed = System.currentTimeMillis() },
                onStartChallenge = { startNewSession() },
                onBack = onBack
            )
        }

        MathArenaStage.ACTIVE -> {
            val activeProblem = problems.getOrNull(currentIndex)
            if (activeProblem == null) {
                stage = MathArenaStage.SETUP
            } else {
                val isSprintExpired by remember(activeTimerPreset) {
                    derivedStateOf {
                        activeTimerPreset.isSprint &&
                            (activeTimerPreset.totalSprintSeconds?.let { clockState.sprintElapsedMs >= it * 1000L } == true)
                    }
                }
                val forceFinishNext = (selectedMode == MathPracticeMode.SURVIVAL && livesRemaining <= 0) ||
                    isSprintExpired ||
                    (selectedMode != MathPracticeMode.TIME_ATTACK && currentIndex + 1 >= targetTotalQuestions)

                MathActiveArenaScreen(
                    problem = activeProblem,
                    mode = selectedMode,
                    livesRemaining = livesRemaining,
                    progressiveDifficulty = progressiveDifficulty,
                    questionIndex = currentIndex,
                    totalQuestions = if (selectedMode == MathPracticeMode.TIME_ATTACK) attempts.size + 1 else targetTotalQuestions,
                    isLastQuestionForNext = forceFinishNext,
                    timerPreset = activeTimerPreset,
                    answerMode = selectedAnswerMode,
                    clockState = clockState,
                    currentStreak = currentStreak,
                    totalScore = totalScore,
                    sfxEnabled = sfxEnabled,
                    onToggleSfx = ::toggleSfx,
                    keypadBuffer = keypadBuffer,
                    onKeypadChange = { keypadBuffer = it },
                    attempt = currentAttempt,
                    showSteps = showStepsInActive,
                    onToggleSteps = { showStepsInActive = !showStepsInActive },
                    onSubmitAnswer = { ans -> submitAnswer(ans, isTimeout = false) },
                    onNextQuestion = { advanceToNextQuestion() },
                    onFinishNow = {
                        currentAttempt = null
                        stage = MathArenaStage.SUMMARY
                    },
                    onBackToSetup = {
                        currentAttempt = null
                        stage = MathArenaStage.SETUP
                    }
                )
            }
        }

        MathArenaStage.SUMMARY -> {
            val totalAnswered = attempts.size
            val correctCount = attempts.count { it.outcome == MathAttemptOutcome.CORRECT }
            val totalDurationMs = attempts.sumOf { it.elapsedMillis }
            val totalDurationSec = ((totalDurationMs + 999L) / 1000L).coerceAtLeast(1L)

            MathSessionSummaryScreen(
                subjects = activeSubjects,
                subjectById = subjectById,
                selectedSubjectId = selectedSubjectId,
                onSelectSubjectId = { selectedSubjectId = it },
                selectedMode = selectedMode,
                selectedTopic = representativeTopic,
                topicSummaryLabel = topicSummaryLabel,
                selectedDifficulty = selectedDifficulty,
                selectedTimerPreset = activeTimerPreset,
                attempts = attempts,
                bestStreak = bestStreak,
                totalScore = totalScore,
                resultSaved = resultSaved,
                mistakeCardsCreated = mistakeCardsCreated,
                onCreateMistakeFlashcards = {
                    val wrongCards = attempts
                        .filter { it.outcome != MathAttemptOutcome.CORRECT }
                        .map { rec ->
                            val front = buildString {
                                appendLine("**[${rec.problem.topic.shortLabel} · ${rec.problem.subtopicTag}]** ${rec.problem.promptTitle}")
                                appendLine()
                                append("$$")
                                append(rec.problem.promptLatex)
                                append("$$")
                            }
                            val back = buildString {
                                append("**Resposta:** ")
                                append("$$")
                                append(rec.problem.answerLatex)
                                append("$$")
                                if (rec.problem.steps.isNotEmpty()) {
                                    appendLine()
                                    appendLine()
                                    append(rec.problem.steps.joinToString("\n"))
                                }
                            }
                            front to back
                        }
                    if (wrongCards.isNotEmpty()) {
                        onCreateFlashcardsFromMistakes(selectedSubjectId, wrongCards)
                        mistakeCardsCreated = true
                        Toast.makeText(
                            context,
                            "${wrongCards.size} flashcard(s) LaTeX gerados dos erros!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onSaveResult = {
                    if (totalAnswered == 0) return@MathSessionSummaryScreen
                    val mistakesPayload = attempts
                        .filter { it.outcome != MathAttemptOutcome.CORRECT }
                        .map { rec ->
                            val questionText = "[${rec.problem.topic.shortLabel} · ${rec.problem.subtopicTag}] ${rec.problem.promptTitle} ${rec.problem.plainSummary}"
                            val userText = when (rec.outcome) {
                                MathAttemptOutcome.TIMEOUT -> "Tempo esgotado"
                                else -> rec.userAnswer.ifBlank { "Sem resposta" }
                            }
                            val reasonText = "Resposta dada: $userText | Correta: ${rec.problem.answerDisplay}"
                            val solutionText = buildString {
                                append("Resposta correta: ${rec.problem.answerDisplay}")
                                if (rec.problem.steps.isNotEmpty()) {
                                    append("\n")
                                    append(rec.problem.steps.joinToString("\n"))
                                }
                            }
                            Triple(questionText, reasonText, solutionText)
                        }

                    val distinctFronts = attempts.map { it.problem.topic.shortLabel }.distinct()
                    val frontSummary = when {
                        distinctFronts.isEmpty() -> topicSummaryLabel
                        distinctFronts.size <= 3 -> distinctFronts.joinToString(", ")
                        else -> "${distinctFronts.take(3).joinToString(", ")} (+${distinctFronts.size - 3})"
                    }
                    val topicLabel = "Arena (${selectedMode.shortLabel}) — $frontSummary"
                    onSavePracticeResult(
                        selectedSubjectId,
                        topicLabel,
                        totalAnswered,
                        correctCount,
                        totalDurationSec,
                        mistakesPayload
                    )
                    resultSaved = true
                    Toast.makeText(
                        context,
                        if (mistakesPayload.isEmpty()) {
                            "Sessão salva no histórico de questões!"
                        } else {
                            "Sessão salva + ${mistakesPayload.size} erro(s) enviado(s) ao Caderno de Erros!"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onPracticeWeakTopic = { weakTopic ->
                    selectedTopicsRaw = weakTopic.name
                    selectedMode = MathPracticeMode.CLASSIC
                    startNewSession(
                        overridePool = listOf(weakTopic),
                        overrideMode = MathPracticeMode.CLASSIC
                    )
                },
                onPlayAgain = { startNewSession() },
                onConfigureArena = { stage = MathArenaStage.SETUP },
                onBack = onBack
            )
        }
    }
}

// ============================================================================
// 1. TELA DE SETUP DA ARENA MATEMÁTICA
// ============================================================================

@Composable
private fun MathArenaSetupScreen(
    subjects: List<Subject>,
    subjectById: Map<Long, Subject>,
    selectedSubjectId: Long?,
    onSelectSubjectId: (Long?) -> Unit,
    selectedMode: MathPracticeMode,
    onSelectMode: (MathPracticeMode) -> Unit,
    selectedCategory: MathTopicCategory,
    onSelectCategory: (MathTopicCategory) -> Unit,
    selectedConcreteTopics: Set<MathTopic>,
    onToggleTopic: (MathTopic) -> Unit,
    onSelectAllTopics: () -> Unit,
    topicSummaryLabel: String,
    resolvedPool: List<MathTopic>,
    selectedDifficulty: MathDifficulty,
    onSelectDifficulty: (MathDifficulty) -> Unit,
    adaptiveEloEnabled: Boolean = false,
    avgTopicElo: Int = 1200,
    onToggleAdaptiveElo: (Boolean) -> Unit = {},
    selectedTimerPreset: MathTimerPreset,
    onSelectTimerPreset: (MathTimerPreset) -> Unit,
    selectedAnswerMode: MathAnswerMode,
    onSelectAnswerMode: (MathAnswerMode) -> Unit,
    selectedQuestionCount: Int,
    onSelectQuestionCount: (Int) -> Unit,
    sfxEnabled: Boolean,
    onToggleSfx: () -> Unit,
    previewSeed: Long,
    onRefreshPreview: () -> Unit,
    onStartChallenge: () -> Unit,
    onBack: (() -> Unit)?
) {
    var previewExpanded by rememberSaveable { mutableStateOf(true) }
    val previewDifficulty = when (selectedMode) {
        MathPracticeMode.PROGRESSIVE -> MathDifficulty.BASIC
        MathPracticeMode.SIMULADO -> MathDifficulty.INTERMEDIATE
        else -> selectedDifficulty
    }
    val previewTopic = remember(resolvedPool, previewSeed) {
        val safe = resolvedPool.ifEmpty { MathTopic.concreteTopics }
        safe[((previewSeed and 0x7FFFFFFFL) % safe.size).toInt()]
    }
    val previewProblem = remember(previewTopic, previewDifficulty, previewSeed) {
        MathProblemGenerator.generate(
            topic = previewTopic,
            difficulty = previewDifficulty,
            seed = previewSeed
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Cabeçalho limpo
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Functions,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Arena de Exatas",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "11 frentes · subtópicos e ritmo de prova",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleSfx, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = if (sfxEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = if (sfxEnabled) "Silenciar sons da arena" else "Ativar sons da arena",
                        tint = if (sfxEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 1. Modo de Desafio
            SetupSectionCard(
                title = "1. Modo de Desafio",
                subtitle = selectedMode.description
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MathPracticeMode.entries.forEach { mode ->
                        val selected = mode == selectedMode
                        val modeIcon = when (mode) {
                            MathPracticeMode.CLASSIC -> Icons.Filled.Functions
                            MathPracticeMode.TIME_ATTACK -> Icons.Filled.Bolt
                            MathPracticeMode.SURVIVAL -> Icons.Filled.Favorite
                            MathPracticeMode.PROGRESSIVE -> Icons.AutoMirrored.Filled.TrendingUp
                            MathPracticeMode.SIMULADO -> Icons.Filled.EmojiEvents
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = BorderStroke(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .widthIn(min = 120.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelectMode(mode) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = modeIcon,
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = mode.shortLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // 2. Áreas & Tópicos (Seleção única ou múltipla, nunca vaga)
            SetupSectionCard(
                title = "2. Frentes de Exatas",
                subtitle = if (selectedConcreteTopics.isEmpty()) {
                    "Todas as 11 frentes ativas · toque para focar em temas específicos"
                } else {
                    "Selecionado: $topicSummaryLabel (toque em outros para combinar)"
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedConcreteTopics.isEmpty(),
                        onClick = onSelectAllTopics,
                        label = {
                            Text(
                                "Todas (11)",
                                fontWeight = if (selectedConcreteTopics.isEmpty()) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    MathTopicCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat && !(cat == MathTopicCategory.ALL && selectedConcreteTopics.isEmpty()),
                            onClick = { onSelectCategory(cat) },
                            label = { Text(cat.label) }
                        )
                    }
                }

                val visibleTopics = remember(selectedCategory) {
                    MathTopic.topicsForCategory(selectedCategory).filter { it != MathTopic.MIXED }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (rowTopics in visibleTopics.chunked(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (topic in rowTopics) {
                                val selected = topic in selectedConcreteTopics
                                Surface(
                                    shape = RoundedCornerShape(13.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(13.dp))
                                        .clickable { onToggleTopic(topic) }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = topic.badgeSymbol,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Serif,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            if (selected) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = topic.shortLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = topic.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (rowTopics.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // 3. Ajustes da Sessão (Nível · Ritmo · Quantidade · Resposta · Matéria)
            SetupSectionCard(
                title = "3. Ajustes da Rodada",
                subtitle = "Dificuldade, tempo, forma de resposta e vínculo de matéria"
            ) {
                if (selectedMode == MathPracticeMode.PROGRESSIVE || selectedMode == MathPracticeMode.SIMULADO) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (selectedMode == MathPracticeMode.PROGRESSIVE) {
                                "Nível automático: começa no Básico e sobe a cada 3 acertos seguidos."
                            } else {
                                "Simulado balanceado: 5 questões Básicas + 5 Intermediárias + 5 Avançadas."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    Text(
                        text = "Dificuldade",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MathDifficulty.entries.forEach { diff ->
                            val selected = diff == selectedDifficulty
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectDifficulty(diff) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = diff.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "+${diff.basePoints} pts",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Modo Adaptativo (Elo: $avgTopicElo)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (adaptiveEloEnabled) {
                                    "Nível automático pelo Elo: ${com.studydock.app.data.MathEloEngine.recommendedDifficulty(avgTopicElo).label}"
                                } else {
                                    "Sobe ou desce a dificuldade sozinho por tópico."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = adaptiveEloEnabled, onCheckedChange = onToggleAdaptiveElo)
                    }
                }

                if (selectedMode != MathPracticeMode.TIME_ATTACK) {
                    Text(
                        text = "Ritmo por questão",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MathTimerPreset.entries.filter { !it.isSprint }.forEach { preset ->
                            FilterChip(
                                selected = preset == selectedTimerPreset,
                                onClick = { onSelectTimerPreset(preset) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Timer, null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    Text(
                                        preset.label,
                                        fontWeight = if (preset == selectedTimerPreset) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }

                if (selectedMode == MathPracticeMode.CLASSIC || selectedMode == MathPracticeMode.PROGRESSIVE) {
                    Text(
                        text = "Quantidade de questões",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 10, 15, 20).forEach { count ->
                            FilterChip(
                                selected = count == selectedQuestionCount,
                                onClick = { onSelectQuestionCount(count) },
                                label = { Text("${count}q") }
                            )
                        }
                    }
                }

                Text(
                    text = "Formato da resposta",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MathAnswerMode.entries.forEach { mode ->
                        val selected = mode == selectedAnswerMode
                        val icon = if (mode == MathAnswerMode.MULTIPLE_CHOICE) Icons.Filled.TouchApp else Icons.Filled.Keyboard
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectAnswerMode(mode) },
                            leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
                            label = { Text(mode.shortLabel) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (subjects.isNotEmpty()) {
                    Text(
                        text = "Vincular a matéria / submatéria (Histórico & Erros)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedSubjectId == null,
                            onClick = { onSelectSubjectId(null) },
                            label = { Text("Geral") }
                        )
                        subjects.forEach { subject ->
                            val isSelected = selectedSubjectId == subject.id
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectSubjectId(subject.id) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(Color(subject.color.toInt()))
                                    )
                                },
                                label = {
                                    Text(
                                        text = SubjectHierarchy.displayName(subject, subjectById),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 4. Amostra Compacta e Recolhível
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { previewExpanded = !previewExpanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "${previewProblem.topic.shortLabel} · ${previewProblem.subtopicTag}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                            Text(
                                text = "Prévia (${previewDifficulty.label})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onRefreshPreview, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Refresh, "Gerar outro exemplo", modifier = Modifier.size(16.dp))
                            }
                            Icon(
                                imageVector = if (previewExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    AnimatedVisibility(visible = previewExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = previewProblem.promptTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 14.dp, vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    MathProblemEquationDisplay(
                                        problem = previewProblem,
                                        baseFontSize = 22.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }

        // Barra Inferior Fixa de Início Rápido
        Surface(
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedMode.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$topicSummaryLabel · ${selectedTimerPreset.shortLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = onStartChallenge,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Iniciar", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun SetupSectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

// ============================================================================
// 2. TELA ATIVA DO DESAFIO (EQUAÇÕES REAIS + CRONÔMETRO + TECLADO/OPÇÕES)
// ============================================================================

@Composable
private fun MathActiveArenaScreen(
    problem: MathProblem,
    mode: MathPracticeMode,
    livesRemaining: Int,
    progressiveDifficulty: MathDifficulty,
    questionIndex: Int,
    totalQuestions: Int,
    isLastQuestionForNext: Boolean,
    timerPreset: MathTimerPreset,
    answerMode: MathAnswerMode,
    clockState: MathArenaClockState,
    currentStreak: Int,
    totalScore: Int,
    sfxEnabled: Boolean,
    onToggleSfx: () -> Unit,
    keypadBuffer: String,
    onKeypadChange: (String) -> Unit,
    attempt: MathAttemptRecord?,
    showSteps: Boolean,
    onToggleSteps: () -> Unit,
    onSubmitAnswer: (String) -> Unit,
    onNextQuestion: () -> Unit,
    onFinishNow: () -> Unit,
    onBackToSetup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Barra Superior: Configurar, Progresso, Vidas/Escalada, Streak, Score e SFX
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onBackToSetup,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Configurar Arena",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onToggleSfx,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (sfxEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = if (sfxEnabled) "Silenciar sons" else "Ativar sons",
                        tint = if (sfxEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (mode == MathPracticeMode.TIME_ATTACK) {
                            "#${questionIndex + 1}"
                        } else {
                            "${questionIndex + 1}/$totalQuestions"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
                if (mode == MathPracticeMode.SURVIVAL) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) { idx ->
                            Icon(
                                imageVector = if (idx < livesRemaining) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = null,
                                tint = if (idx < livesRemaining) Color(0xFFEF4444) else MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (currentStreak >= 2) {
                        Color(0xFFF59E0B).copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = "🔥$currentStreak",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (currentStreak >= 2) Color(0xFFD97706) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "$totalScore pts",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }

                if (mode == MathPracticeMode.TIME_ATTACK || questionIndex > 0) {
                    TextButton(
                        onClick = onFinishNow,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Encerrar", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // Barra + Relógio Regressivo Animado (isolado para recompor apenas o cronômetro a 12,5 Hz)
        ArenaTimerCard(
            mode = mode,
            timerPreset = timerPreset,
            clockState = clockState
        )

        // Card Lousa / Equação Central
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            border = BorderStroke(
                width = 1.5.dp,
                color = when (attempt?.outcome) {
                    MathAttemptOutcome.CORRECT -> Color(0xFF16A34A)
                    MathAttemptOutcome.WRONG, MathAttemptOutcome.TIMEOUT -> Color(0xFFEF4444)
                    null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = "${problem.topic.shortLabel} · ${problem.subtopicTag}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = if (mode == MathPracticeMode.PROGRESSIVE) {
                            "Escalada: ${progressiveDifficulty.label}"
                        } else {
                            problem.difficulty.label
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = problem.promptTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Lousa central onde a equação estruturada MathExpr é desenhada
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 116.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        MathProblemEquationDisplay(
                            problem = problem,
                            baseFontSize = 28.sp
                        )
                    }
                }
            }
        }

        // Feedback de Acerto / Erro / Tempo Esgotado + Passo a Passo
        if (attempt != null) {
            AttemptFeedbackCard(
                attempt = attempt,
                showSteps = showSteps,
                onToggleSteps = onToggleSteps,
                onNextQuestion = onNextQuestion,
                isLastQuestion = isLastQuestionForNext
            )
        }

        // Área de Resposta Rápida (Múltipla Escolha 2x2 OU Teclado Matemático Embutido)
        if (answerMode == MathAnswerMode.MULTIPLE_CHOICE) {
            MultipleChoiceGrid(
                options = problem.options,
                correctAnswer = problem.answerDisplay,
                attempt = attempt,
                onSelectOption = { chosen -> onSubmitAnswer(chosen) }
            )
        } else {
            EmbeddedMathKeypad(
                buffer = keypadBuffer,
                enabled = attempt == null,
                onBufferChange = onKeypadChange,
                onSubmit = {
                    if (keypadBuffer.isNotBlank()) {
                        onSubmitAnswer(keypadBuffer)
                    }
                }
            )
        }
    }
}

@Composable
private fun ArenaTimerCard(
    mode: MathPracticeMode,
    timerPreset: MathTimerPreset,
    clockState: MathArenaClockState
) {
    val elapsedQuestionMs = clockState.elapsedOnCurrentMs
    val sprintElapsedMs = clockState.sprintElapsedMs
    val totalWindowMs: Long? = when {
        timerPreset.secondsPerQuestion != null -> timerPreset.secondsPerQuestion * 1000L
        timerPreset.totalSprintSeconds != null -> timerPreset.totalSprintSeconds * 1000L
        else -> null
    }
    val usedWindowMs: Long = when {
        timerPreset.secondsPerQuestion != null -> elapsedQuestionMs
        timerPreset.totalSprintSeconds != null -> sprintElapsedMs
        else -> elapsedQuestionMs
    }
    val remainingMs: Long? = totalWindowMs?.let { (it - usedWindowMs).coerceAtLeast(0L) }
    val remainingSecFloat: Float? = remainingMs?.let { it / 1000f }

    val targetTimerColor = when {
        remainingSecFloat == null -> MaterialTheme.colorScheme.primary
        remainingSecFloat <= 2.0f -> Color(0xFFEF4444)
        remainingSecFloat <= 5.0f -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.primary
    }
    val animatedTimerColor by animateColorAsState(
        targetValue = targetTimerColor,
        animationSpec = tween(durationMillis = 180),
        label = "timerColor"
    )

    val timerProgressTarget = if (totalWindowMs != null && totalWindowMs > 0L) {
        (remainingMs!!.toFloat() / totalWindowMs.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = timerProgressTarget,
        animationSpec = tween(durationMillis = 80),
        label = "timerProgress"
    )

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (timerPreset.isSprint) Icons.Filled.Bolt else Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = animatedTimerColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = buildString {
                            append(mode.shortLabel)
                            append(" · ")
                            append(
                                when {
                                    timerPreset.isSprint -> "Sprint 2 min"
                                    timerPreset.isPerQuestion -> "${timerPreset.secondsPerQuestion}s por questão"
                                    else -> "Ritmo livre"
                                }
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val timeDisplay = if (remainingSecFloat != null) {
                    if (timerPreset.isSprint) {
                        val totalSec = remainingMs!! / 1000L
                        val m = totalSec / 60L
                        val s = totalSec % 60L
                        String.format(Locale.US, "%02d:%02d", m, s)
                    } else {
                        String.format(Locale.US, "%.1fs", remainingSecFloat)
                    }
                } else {
                    String.format(Locale.US, "%.1fs", elapsedQuestionMs / 1000f)
                }

                Text(
                    text = timeDisplay,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = animatedTimerColor
                )
            }

            if (timerPreset.isTimed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(animatedTimerColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun MultipleChoiceGrid(
    options: List<String>,
    correctAnswer: String,
    attempt: MathAttemptRecord?,
    onSelectOption: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val rows = options.chunked(2)
        for (rowOptions in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (option in rowOptions) {
                    val isAnswered = attempt != null
                    val isCorrectOption = option == correctAnswer
                    val isChosenOption = attempt?.userAnswer == option

                    val containerColor = when {
                        !isAnswered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        isCorrectOption -> Color(0xFF16A34A).copy(alpha = 0.18f)
                        isChosenOption -> Color(0xFFEF4444).copy(alpha = 0.18f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    }
                    val borderColor = when {
                        !isAnswered -> MaterialTheme.colorScheme.outlineVariant
                        isCorrectOption -> Color(0xFF16A34A)
                        isChosenOption -> Color(0xFFEF4444)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = containerColor,
                        border = BorderStroke(if (isAnswered && (isCorrectOption || isChosenOption)) 2.dp else 1.dp, borderColor),
                        modifier = Modifier
                            .weight(1f)
                            .height(74.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !isAnswered) { onSelectOption(option) }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp)
                        ) {
                            OptionValueView(optionText = option)
                        }
                    }
                }
                if (rowOptions.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OptionValueView(optionText: String) {
    if ('/' in optionText) {
        val parts = optionText.split('/')
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            MathEquationView(
                expr = MathExpr.Frac(MathExpr.Text(parts[0]), MathExpr.Text(parts[1])),
                fontSize = 20.sp
            )
            return
        }
    }
    Text(
        text = optionText.replace('-', '−'),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun EmbeddedMathKeypad(
    buffer: String,
    enabled: Boolean,
    onBufferChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val parsedValue = remember(buffer) { parseMathInput(buffer) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Visor do teclado
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SUA RESPOSTA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (buffer.isEmpty()) "Digite usando o teclado..." else buffer.replace('-', '−'),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (buffer.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }

                    // Se o usuário digitou uma fração válida a/b, mostra o preview da barra horizontal real
                    if ('/' in buffer) {
                        val parts = buffer.split('/')
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            ) {
                                Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    MathEquationView(
                                        expr = MathExpr.Frac(
                                            MathExpr.Text(parts[0].replace('-', '−')),
                                            MathExpr.Text(parts[1].replace('-', '−'))
                                        ),
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    } else if (parsedValue != null) {
                        Text(
                            text = "= ${MathArenaEngine.formatCleanDouble(parsedValue)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            fun appendChar(ch: String) {
                if (!enabled) return
                when (ch) {
                    "-" -> {
                        val next = if (buffer.startsWith("-")) buffer.removePrefix("-") else "-$buffer"
                        onBufferChange(next)
                    }
                    "/" -> {
                        if ('/' !in buffer && buffer.isNotEmpty() && buffer != "-") {
                            onBufferChange("$buffer/")
                        }
                    }
                    "," -> {
                        val lastSegment = buffer.substringAfterLast('/')
                        if (',' !in lastSegment && '.' !in lastSegment) {
                            val prefix = if (lastSegment.isEmpty() || lastSegment == "-") "${buffer}0" else buffer
                            onBufferChange("$prefix,")
                        }
                    }
                    else -> {
                        if (buffer.length < 14) {
                            onBufferChange(buffer + ch)
                        }
                    }
                }
            }

            for (row in KEYPAD_ROWS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (key in row) {
                        val isAction = key in KEYPAD_ACTION_KEYS
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAction) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = enabled) {
                                    when (key) {
                                        "⌫" -> if (buffer.isNotEmpty()) onBufferChange(buffer.dropLast(1))
                                        "−" -> appendChar("-")
                                        else -> appendChar(key)
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (key == "⌫") {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                                        contentDescription = "Apagar",
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Linha final: Limpar | 0 | , | Responder
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { onBufferChange("") }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Limpar",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { appendChar("0") }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "0",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(0.8f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { appendChar(",") }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = ",",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Button(
                    onClick = onSubmit,
                    enabled = enabled && parsedValue != null && !buffer.endsWith(',') && !buffer.endsWith('.'),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Responder",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun AttemptFeedbackCard(
    attempt: MathAttemptRecord,
    showSteps: Boolean,
    onToggleSteps: () -> Unit,
    onNextQuestion: () -> Unit,
    isLastQuestion: Boolean
) {
    val isCorrect = attempt.outcome == MathAttemptOutcome.CORRECT
    val accentColor = if (isCorrect) Color(0xFF16A34A) else Color(0xFFEF4444)
    val elapsedSec = String.format(Locale.US, "%.1fs", attempt.elapsedMillis / 1000f)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.12f)
        ),
        border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isCorrect) Icons.Filled.CheckCircle else Icons.Filled.Close,
                        contentDescription = null,
                        tint = accentColor
                    )
                    Column {
                        Text(
                            text = when (attempt.outcome) {
                                MathAttemptOutcome.CORRECT -> "Correto! +${attempt.pointsAwarded} pts ($elapsedSec)"
                                MathAttemptOutcome.TIMEOUT -> "Tempo esgotado! Resposta correta: ${attempt.problem.answerDisplay}"
                                MathAttemptOutcome.WRONG -> "Incorreto (você respondeu ${attempt.userAnswer}) · Correta: ${attempt.problem.answerDisplay}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Button(
                    onClick = onNextQuestion,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isLastQuestion) "Ver Resumo" else "Próxima →",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Botão expandir/recolher "Ver passo a passo"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onToggleSteps() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (showSteps) "Ocultar passo a passo" else "Ver passo a passo da resolução",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (showSteps) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(visible = showSteps) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        attempt.problem.steps.forEach { step ->
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 3. RESUMO FINAL DA SESSÃO
// ============================================================================

@Composable
private fun MathSessionSummaryScreen(
    subjects: List<Subject>,
    subjectById: Map<Long, Subject>,
    selectedSubjectId: Long?,
    onSelectSubjectId: (Long?) -> Unit,
    selectedMode: MathPracticeMode,
    selectedTopic: MathTopic,
    topicSummaryLabel: String,
    selectedDifficulty: MathDifficulty,
    selectedTimerPreset: MathTimerPreset,
    attempts: List<MathAttemptRecord>,
    bestStreak: Int,
    totalScore: Int,
    resultSaved: Boolean,
    mistakeCardsCreated: Boolean,
    onCreateMistakeFlashcards: () -> Unit,
    onSaveResult: () -> Unit,
    onPracticeWeakTopic: (MathTopic) -> Unit,
    onPlayAgain: () -> Unit,
    onConfigureArena: () -> Unit,
    onBack: (() -> Unit)?
) {
    val totalAnswered = attempts.size
    val correctCount = attempts.count { it.outcome == MathAttemptOutcome.CORRECT }
    val accuracyPercent = if (totalAnswered > 0) {
        ((correctCount * 100.0) / totalAnswered.toDouble()).roundToInt()
    } else {
        0
    }
    val avgMillis = if (totalAnswered > 0) {
        attempts.sumOf { it.elapsedMillis } / totalAnswered
    } else {
        0L
    }
    val avgSecFormatted = String.format(Locale.US, "%.1fs", avgMillis / 1000f)
    val wrongAttempts = remember(attempts.toList()) {
        attempts.filter { it.outcome != MathAttemptOutcome.CORRECT }
    }
    val topicBreakdown = remember(attempts.toList()) {
        attempts
            .groupBy { it.problem.topic }
            .map { (topic, list) ->
                val hits = list.count { it.outcome == MathAttemptOutcome.CORRECT }
                val pct = ((hits * 100.0) / list.size).roundToInt()
                val subtopics = list.groupBy { it.problem.subtopicTag }
                    .map { (tag, subList) ->
                        "$tag (${subList.count { it.outcome == MathAttemptOutcome.CORRECT }}/${subList.size})"
                    }
                Triple(topic, hits to list.size, pct to subtopics)
            }
            .sortedBy { it.third.first }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar"
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.EmojiEvents,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(32.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Resumo Final da Rodada",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "${selectedMode.shortLabel} · $topicSummaryLabel · ${selectedTimerPreset.shortLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Grid 2x2 de métricas principais
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryMetricCard(
                label = "Taxa de Acerto",
                value = "$accuracyPercent%",
                subtitle = "$correctCount de $totalAnswered corretas",
                accentColor = if (accuracyPercent >= 70) Color(0xFF16A34A) else Color(0xFFF59E0B),
                modifier = Modifier.weight(1f)
            )
            SummaryMetricCard(
                label = "Tempo Médio",
                value = avgSecFormatted,
                subtitle = "por equação",
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryMetricCard(
                label = "Melhor Sequência",
                value = "🔥 x$bestStreak",
                subtitle = "combo máximo",
                accentColor = Color(0xFFD97706),
                modifier = Modifier.weight(1f)
            )
            SummaryMetricCard(
                label = "Pontuação",
                value = "$totalScore pts",
                subtitle = "bônus de velocidade & streak",
                accentColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
        }

        // Diagnóstico por Frente & Subtópicos
        if (topicBreakdown.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Desempenho por Frente & Subtópico",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    topicBreakdown.forEach { (topic, counts, meta) ->
                        val (hits, total) = counts
                        val (pct, subtopics) = meta
                        val badgeColor = when {
                            pct >= 80 -> Color(0xFF16A34A)
                            pct < 60 -> Color(0xFFEF4444)
                            else -> Color(0xFFF59E0B)
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${topic.shortLabel} · $pct% ($hits/$total)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeColor
                                    )
                                    Text(
                                        text = subtopics.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (pct < 80) {
                                    TextButton(
                                        onClick = { onPracticeWeakTopic(topic) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Treinar", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Vinculação de Matéria antes de salvar
        if (subjects.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Matéria / Submatéria para salvar o resultado:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedSubjectId == null,
                            onClick = { onSelectSubjectId(null) },
                            label = { Text("Geral") }
                        )
                        subjects.forEach { subj ->
                            FilterChip(
                                selected = selectedSubjectId == subj.id,
                                onClick = { onSelectSubjectId(subj.id) },
                                label = { Text(SubjectHierarchy.displayName(subj, subjectById)) }
                            )
                        }
                    }
                }
            }
        }

        if (wrongAttempts.isNotEmpty()) {
            OutlinedButton(
                onClick = onCreateMistakeFlashcards,
                enabled = !mistakeCardsCreated,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (mistakeCardsCreated) Icons.Filled.CheckCircle else Icons.Filled.AutoAwesome,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (mistakeCardsCreated) {
                        "Flashcards LaTeX dos erros gerados"
                    } else {
                        "Gerar Flashcards LaTeX dos Erros (${wrongAttempts.size})"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Botões Principais: Salvar no Histórico & Erros + Nova Rodada
        Button(
            onClick = onSaveResult,
            enabled = !resultSaved && totalAnswered > 0,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = if (resultSaved) Icons.Filled.CheckCircle else Icons.Filled.Save,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (resultSaved) {
                    "Salvo no Histórico & Caderno de Erros"
                } else {
                    "Salvar no Histórico & Erros"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onConfigureArena,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Configurar", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onPlayAgain,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Nova Rodada", fontWeight = FontWeight.Bold)
            }
        }

        // Revisão das Questões Erradas com Passo a Passo
        if (wrongAttempts.isEmpty() && totalAnswered > 0) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF16A34A).copy(alpha = 0.12f)
                ),
                border = BorderStroke(1.dp, Color(0xFF16A34A).copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF16A34A),
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "Rodada Perfeita (100%)!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Você acertou todas as equações desta sessão sem nenhum erro.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (wrongAttempts.isNotEmpty()) {
            Text(
                text = "Revisão das Questões Erradas (${wrongAttempts.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )

            wrongAttempts.forEach { rec ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = rec.problem.promptTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                MathProblemEquationDisplay(
                                    problem = rec.problem,
                                    baseFontSize = 20.sp
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Sua resposta: ${
                                    if (rec.outcome == MathAttemptOutcome.TIMEOUT) {
                                        "Tempo esgotado"
                                    } else {
                                        rec.userAnswer.ifBlank { "—" }
                                    }
                                }",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFEF4444)
                            )
                            Text(
                                text = "Correta: ${rec.problem.answerDisplay}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF16A34A)
                            )
                        }

                        HorizontalDivider()

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            rec.problem.steps.forEach { step ->
                                Text(
                                    text = step,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SummaryMetricCard(
    label: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = accentColor
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// 4. RENDERIZADOR VISUAL DE EQUAÇÕES REAIS (MathEquationView)
// ============================================================================

@Composable
fun MathProblemEquationDisplay(
    problem: MathProblem,
    baseFontSize: TextUnit = 26.sp,
    modifier: Modifier = Modifier
) {
    // Se for sistema 2x2 e possuir secondaryEquation, renderiza chave vertical real { com as 2 equações empilhadas!
    if (problem.topic == MathTopic.SYSTEMS_2X2 &&
        problem.secondaryEquation != null &&
        problem.equation is MathExpr.Row
    ) {
        val firstEq = problem.equation.items.firstOrNull { it is MathExpr.Eq }
        if (firstEq != null) {
            val braceColor = MaterialTheme.colorScheme.primary
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(16.dp)
                        .height(82.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val strokePx = 2.6.dp.toPx()
                    val path = Path().apply {
                        moveTo(w * 0.85f, strokePx)
                        cubicTo(w * 0.25f, strokePx, w * 0.35f, h * 0.42f, w * 0.12f, h * 0.5f)
                        cubicTo(w * 0.35f, h * 0.58f, w * 0.25f, h - strokePx, w * 0.85f, h - strokePx)
                    }
                    drawPath(
                        path = path,
                        color = braceColor,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    MathEquationView(expr = firstEq, fontSize = baseFontSize * 0.9f)
                    MathEquationView(expr = problem.secondaryEquation, fontSize = baseFontSize * 0.9f)
                }
            }
            return
        }
    }

    MathEquationView(
        expr = problem.equation,
        fontSize = baseFontSize,
        modifier = modifier
    )
}

@Composable
fun MathEquationView(
    expr: MathExpr,
    fontSize: TextUnit = 26.sp,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val varColor = MaterialTheme.colorScheme.primary
    val opColor = MaterialTheme.colorScheme.secondary

    when (expr) {
        is MathExpr.Text -> {
            StyledMathText(
                raw = expr.value,
                fontSize = fontSize,
                numberColor = textColor,
                operatorColor = opColor,
                modifier = modifier
            )
        }

        is MathExpr.Var -> {
            Text(
                text = expr.symbol,
                fontSize = fontSize,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.ExtraBold,
                color = varColor,
                modifier = modifier.padding(horizontal = 1.dp)
            )
        }

        is MathExpr.Row -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                expr.items.forEach { child ->
                    MathEquationView(expr = child, fontSize = fontSize)
                }
            }
        }

        is MathExpr.Eq -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MathEquationView(expr = expr.left, fontSize = fontSize)
                Text(
                    text = " = ",
                    fontSize = fontSize,
                    fontWeight = FontWeight.ExtraBold,
                    color = varColor,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                MathEquationView(expr = expr.right, fontSize = fontSize)
            }
        }

        is MathExpr.Parenthesized -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "(",
                    fontSize = fontSize * 1.08f,
                    fontWeight = FontWeight.Bold,
                    color = opColor
                )
                MathEquationView(expr = expr.inner, fontSize = fontSize)
                Text(
                    text = ")",
                    fontSize = fontSize * 1.08f,
                    fontWeight = FontWeight.Bold,
                    color = opColor
                )
            }
        }

        is MathExpr.Frac -> {
            val childFontSize = (fontSize.value * 0.84f).coerceAtLeast(14f).sp
            Column(
                modifier = modifier
                    .padding(horizontal = 4.dp)
                    .width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MathEquationView(expr = expr.numerator, fontSize = childFontSize)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(min = 20.dp)
                        .height(2.2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(textColor)
                )
                Box(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MathEquationView(expr = expr.denominator, fontSize = childFontSize)
                }
            }
        }

        is MathExpr.Pow -> {
            val expFontSize = (fontSize.value * 0.64f).coerceAtLeast(11f).sp
            val verticalOffset = (-fontSize.value * 0.34f).dp
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.Top
            ) {
                Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                    MathEquationView(expr = expr.base, fontSize = fontSize)
                }
                Box(
                    modifier = Modifier
                        .offset(y = verticalOffset)
                        .padding(start = 1.dp)
                ) {
                    MathEquationView(expr = expr.exponent, fontSize = expFontSize)
                }
            }
        }

        is MathExpr.Sqrt -> {
            val indexFontSize = (fontSize.value * 0.55f).coerceAtLeast(10f).sp
            val strokeColor = textColor
            Row(
                modifier = modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (expr.index != 2) {
                    Text(
                        text = expr.index.toString(),
                        fontSize = indexFontSize,
                        fontWeight = FontWeight.Bold,
                        color = varColor,
                        modifier = Modifier
                            .align(Alignment.Top)
                            .offset(x = 2.dp, y = (-2).dp)
                    )
                }

                // Símbolo de radical √ com barra superior (vinculum) cobrindo todo o radicando
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(
                        modifier = Modifier
                            .width(15.dp)
                            .fillMaxHeight()
                    ) {
                        val w = size.width
                        val h = size.height
                        val stroke = 2.2.dp.toPx()
                        val path = Path().apply {
                            moveTo(0f, h * 0.58f)
                            lineTo(w * 0.26f, h * 0.52f)
                            lineTo(w * 0.56f, h - stroke)
                            lineTo(w, stroke * 0.6f)
                        }
                        drawPath(
                            path = path,
                            color = strokeColor,
                            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }

                    Column(
                        modifier = Modifier.width(IntrinsicSize.Max),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.2.dp)
                                .background(strokeColor)
                        )
                        Box(
                            modifier = Modifier.padding(start = 3.dp, end = 4.dp, top = 2.dp)
                        ) {
                            MathEquationView(
                                expr = expr.radicand,
                                fontSize = (fontSize.value * 0.92f).sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StyledMathText(
    raw: String,
    fontSize: TextUnit,
    numberColor: Color,
    operatorColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        var idx = 0
        while (idx < raw.length) {
            val ch = raw[idx]
            if (ch in MATH_OPERATORS) {
                val displayOp = if (ch == '-') '−' else ch
                Text(
                    text = displayOp.toString(),
                    fontSize = fontSize,
                    fontWeight = FontWeight.ExtraBold,
                    color = operatorColor,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                idx++
            } else {
                val start = idx
                while (idx < raw.length && raw[idx] !in MATH_OPERATORS) {
                    idx++
                }
                val token = raw.substring(start, idx)
                if (token.isNotBlank()) {
                    Text(
                        text = token,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        color = numberColor
                    )
                } else {
                    Spacer(Modifier.width(3.dp))
                }
            }
        }
    }
}
