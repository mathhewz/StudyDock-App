package com.studydock.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val DAY_MS = 86_400_000L
private val ptBrLocale = Locale("pt", "BR")
private val shortDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", ptBrLocale)
private val DIACRITICS_REGEX = Regex("\\p{M}+")
private val GROWTH_WHITESPACE_REGEX = Regex("\\s+")
private val TAG_SPLIT_REGEX = Regex("[\\s,;]+")

private fun normalizeGrowthToken(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(DIACRITICS_REGEX, "")
        .lowercase(Locale.ROOT)
        .trim()

private fun formatGrowthNumber(value: Double): String {
    val safe = if (value.isFinite()) value else 0.0
    return if (abs(safe - safe.roundToLong()) < 0.005) {
        safe.roundToLong().toString()
    } else {
        String.format(ptBrLocale, "%.1f", safe)
    }
}

private fun formatMetricValue(value: Double, unit: String): String {
    val num = formatGrowthNumber(value)
    val cleanUnit = unit.trim()
    return when {
        cleanUnit.isEmpty() -> num
        cleanUnit == "%" -> "$num%"
        else -> "$num $cleanUnit"
    }
}

private fun formatDurationCompact(seconds: Long): String {
    val totalMinutes = (seconds.coerceAtLeast(0L) + 30L) / 60L
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}min"
        hours > 0 -> "${hours}h"
        else -> "${mins}min"
    }
}

// =====================================================================
// Metas (GoalMetricType, GoalMilestones, GoalProgressEngine)
// =====================================================================

/** Supported tracking modes for [Goal] progress calculation. */
enum class GoalMetricType(
    val id: String,
    val label: String,
    val defaultUnit: String
) {
    COUNTDOWN("COUNTDOWN", "Contagem / Prazo", "dias"),
    STUDY_HOURS("STUDY_HOURS", "Horas de estudo", "h"),
    QUESTIONS("QUESTIONS", "Questões resolvidas", "questões"),
    ACCURACY("ACCURACY", "Acurácia em questões", "%"),
    PAGES("PAGES", "Páginas lidas", "págs"),
    FLASHCARDS("FLASHCARDS", "Flashcards revisados", "cartões"),
    TASKS("TASKS", "Tarefas concluídas", "tarefas"),
    HABITS("HABITS", "Hábitos cumpridos", "dias"),
    MANUAL("MANUAL", "Progresso manual", "unid.");

    companion object {
        /** Resolves a [GoalMetricType] from its persisted [id], defaulting to [COUNTDOWN]. */
        fun fromId(id: String): GoalMetricType =
            entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: COUNTDOWN
    }
}

/** Single checkable sub-milestone inside a [Goal]. */
data class GoalMilestone(
    val id: String,
    val title: String,
    val done: Boolean = false
)

/** JSON serialization and manipulation helpers for [Goal.milestonesJson] using `org.json`. */
object GoalMilestones {

    /** Parses a JSON array string into [GoalMilestone] entries, returning empty on invalid/blank input. */
    fun parse(json: String?): List<GoalMilestone> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val title = obj.optString("title").trim()
                    if (title.isEmpty()) continue
                    val id = obj.optString("id").trim().ifEmpty { "m_$index" }
                    val done = obj.optBoolean("done", false)
                    add(GoalMilestone(id = id, title = title, done = done))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Encodes [milestones] to a JSON string (or empty string when none remain). */
    fun serialize(milestones: List<GoalMilestone>): String {
        val valid = milestones.filter { it.title.isNotBlank() }
        if (valid.isEmpty()) return ""
        val array = JSONArray()
        valid.forEachIndexed { index, milestone ->
            array.put(
                JSONObject().apply {
                    put("id", milestone.id.trim().ifEmpty { "m_$index" })
                    put("title", milestone.title.trim())
                    put("done", milestone.done)
                }
            )
        }
        return array.toString()
    }

    /** Toggles the `done` state of the milestone matching [milestoneId]. */
    fun toggle(json: String?, milestoneId: String): String {
        val current = parse(json)
        if (current.isEmpty()) return ""
        return serialize(current.map { if (it.id == milestoneId) it.copy(done = !it.done) else it })
    }

    /** Appends a new milestone with [title] to [json]. */
    fun add(json: String?, title: String): String {
        val clean = title.trim()
        val current = parse(json)
        if (clean.isEmpty()) return serialize(current)
        return serialize(current + GoalMilestone(id = UUID.randomUUID().toString(), title = clean, done = false))
    }

    /** Removes the milestone matching [milestoneId] from [json]. */
    fun remove(json: String?, milestoneId: String): String {
        val current = parse(json)
        if (current.isEmpty()) return ""
        return serialize(current.filterNot { it.id == milestoneId })
    }

    /** Returns `(completedCount, totalCount)` for [json]. */
    fun summary(json: String?): Pair<Int, Int> {
        val list = parse(json)
        return list.count { it.done } to list.size
    }
}

/** Computed snapshot of a [Goal]'s current progress, display strings, and pace status. */
data class GoalProgressSnapshot(
    val goal: Goal,
    val metricType: GoalMetricType,
    val current: Double,
    val target: Double,
    val progressFraction: Float,
    val displayCurrent: String,
    val displayTarget: String,
    val requiredDailyPaceLabel: String?,
    val paceStatusLabel: String?,
    val isOnTrack: Boolean,
    val milestones: List<GoalMilestone>
)

/** Computes live progress and daily pace for [Goal] across StudyDock data streams. */
object GoalProgressEngine {

    /**
     * Computes a [GoalProgressSnapshot] for [goal] at [now].
     * When `goal.subjectId != null`, subject-aware metrics are scoped to that subject.
     * Legacy goals with `goal.createdAt == 0L` are handled without blowing up elapsed fractions.
     */
    fun compute(
        goal: Goal,
        sessions: List<StudySession>,
        questionLogs: List<QuestionLog>,
        books: List<Book>,
        readingLogs: List<ReadingLog>,
        flashcards: List<Flashcard>,
        cardReviews: List<CardReview>,
        tasks: List<StudyTask>,
        habitCompletions: List<HabitCompletion>,
        now: Long = System.currentTimeMillis()
    ): GoalProgressSnapshot {
        val type = GoalMetricType.fromId(goal.metricType)
        val milestones = GoalMilestones.parse(goal.milestonesJson)
        val sid = goal.subjectId

        if (type == GoalMetricType.COUNTDOWN) {
            return computeCountdownSnapshot(goal, milestones, now)
        }

        val unit = goal.metricUnit.ifBlank { type.defaultUnit }

        var rawCurrent = 0.0
        var totalQuestionCountForAccuracy = 0
        when (type) {
            GoalMetricType.STUDY_HOURS -> {
                val secs = sessions.asSequence()
                    .filter { !it.isActive && it.durationSeconds > 0 && (sid == null || it.subjectId == sid) }
                    .sumOf { it.durationSeconds }
                rawCurrent = secs / 3600.0
            }
            GoalMetricType.QUESTIONS -> {
                rawCurrent = questionLogs.asSequence()
                    .filter { it.questionCount > 0 && (sid == null || it.subjectId == sid) }
                    .sumOf { it.questionCount }
                    .toDouble()
            }
            GoalMetricType.ACCURACY -> {
                val scoped = questionLogs.filter { it.questionCount > 0 && (sid == null || it.subjectId == sid) }
                val totalQ = scoped.sumOf { it.questionCount }
                val correctQ = scoped.sumOf { it.correctCount.coerceIn(0, it.questionCount) }
                totalQuestionCountForAccuracy = totalQ
                rawCurrent = if (totalQ > 0) (correctQ * 100.0 / totalQ) else 0.0
            }
            GoalMetricType.PAGES -> {
                rawCurrent = computePagesRead(sid, goal.bookId, books, readingLogs).toDouble()
            }
            GoalMetricType.FLASHCARDS -> {
                val cardSubjects = flashcards.associate { it.id to it.subjectId }
                rawCurrent = cardReviews.count { review ->
                    val reviewSubject = review.subjectAtReview ?: cardSubjects[review.cardId]
                    sid == null || reviewSubject == sid
                }.toDouble()
            }
            GoalMetricType.TASKS -> {
                rawCurrent = tasks.count { it.completed && (sid == null || it.subjectId == sid) }.toDouble()
            }
            GoalMetricType.HABITS -> {
                rawCurrent = habitCompletions.count { it.status == "DONE" }.toDouble()
            }
            GoalMetricType.MANUAL -> {
                rawCurrent = if (goal.targetValue <= 0.0 && milestones.isNotEmpty() && goal.currentValue <= 0.0) {
                    milestones.count { it.done }.toDouble()
                } else {
                    goal.currentValue.coerceAtLeast(0.0)
                }
            }
            GoalMetricType.COUNTDOWN -> Unit
        }

        val target = when {
            type == GoalMetricType.ACCURACY -> goal.targetValue.coerceIn(0.0, 100.0)
            type == GoalMetricType.MANUAL && goal.targetValue <= 0.0 && milestones.isNotEmpty() -> milestones.size.toDouble()
            else -> goal.targetValue.coerceAtLeast(0.0)
        }

        val progressFraction = when {
            goal.completed -> 1f
            target > 0.0 -> (rawCurrent / target).toFloat().coerceIn(0f, 1f)
            milestones.isNotEmpty() -> (milestones.count { it.done }.toFloat() / milestones.size.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

        val displayCurrent = formatMetricValue(rawCurrent, unit)
        val displayTarget = if (target > 0.0) formatMetricValue(target, unit) else "—"

        if (type == GoalMetricType.ACCURACY) {
            val accuracyReached = target > 0.0 && totalQuestionCountForAccuracy > 0 && rawCurrent >= target
            val isOnTrack = goal.completed || accuracyReached || (totalQuestionCountForAccuracy == 0 && goal.targetAt > now)
            val paceStatus = when {
                goal.completed -> "Concluída"
                totalQuestionCountForAccuracy == 0 -> "Sem questões ainda"
                accuracyReached -> "Acurácia atingida"
                goal.targetAt <= now -> "Prazo encerrado"
                else -> "Abaixo do alvo"
            }
            val paceDetail = when {
                goal.completed || accuracyReached -> null
                totalQuestionCountForAccuracy == 0 -> "Resolva questões para medir"
                else -> "Faltam ${formatGrowthNumber((target - rawCurrent).coerceAtLeast(0.0))} p.p."
            }
            return GoalProgressSnapshot(
                goal = goal,
                metricType = type,
                current = rawCurrent,
                target = target,
                progressFraction = progressFraction,
                displayCurrent = displayCurrent,
                displayTarget = displayTarget,
                requiredDailyPaceLabel = paceDetail,
                paceStatusLabel = paceStatus,
                isOnTrack = isOnTrack,
                milestones = milestones
            )
        }

        val reached = target > 0.0 && rawCurrent >= target
        val requiredDailyPaceLabel = if (!goal.completed && !reached && target > 0.0 && goal.targetAt > now) {
            val remaining = (target - rawCurrent).coerceAtLeast(0.0)
            val daysLeft = ceil((goal.targetAt - now) / DAY_MS.toDouble()).toLong().coerceAtLeast(1L)
            "${formatMetricValue(remaining / daysLeft.toDouble(), unit)}/dia"
        } else {
            null
        }

        val effectiveStart = if (goal.startAt > 0L) goal.startAt else goal.createdAt
        val (isOnTrack, paceStatusLabel) = when {
            goal.completed -> true to "Concluída"
            reached -> true to "Meta atingida"
            goal.targetAt <= now -> false to "Prazo encerrado"
            effectiveStart > 0L && effectiveStart < goal.targetAt && target > 0.0 -> {
                val expectedFraction = ((now - effectiveStart).toDouble() / (goal.targetAt - effectiveStart).toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
                val onTrack = progressFraction >= (expectedFraction - 0.05f)
                onTrack to if (onTrack) "No prazo" else "Abaixo do ritmo"
            }
            else -> true to "Em andamento"
        }

        return GoalProgressSnapshot(
            goal = goal,
            metricType = type,
            current = rawCurrent,
            target = target,
            progressFraction = progressFraction,
            displayCurrent = displayCurrent,
            displayTarget = displayTarget,
            requiredDailyPaceLabel = requiredDailyPaceLabel,
            paceStatusLabel = paceStatusLabel,
            isOnTrack = isOnTrack,
            milestones = milestones
        )
    }

    private fun computePagesRead(
        subjectId: Long?,
        bookId: Long?,
        books: List<Book>,
        readingLogs: List<ReadingLog>
    ): Int {
        val scopedBooks = when {
            bookId != null && bookId > 0L -> books.filter { it.id == bookId }
            subjectId != null -> books.filter { it.subjectId == subjectId }
            else -> books
        }
        val logsByTitle = readingLogs
            .filter { it.pages > 0 }
            .groupBy { it.title.trim().lowercase(Locale.ROOT) }
        val logsByBookId = readingLogs
            .filter { it.pages > 0 && it.bookId != null }
            .groupBy { it.bookId!! }
        val pagesFromBooks = scopedBooks.sumOf { book ->
            val key = book.title.trim().lowercase(Locale.ROOT)
            val loggedById = logsByBookId[book.id]?.sumOf { it.pages } ?: 0
            val logged = maxOf(loggedById, logsByTitle[key]?.sumOf { it.pages } ?: 0)
            maxOf(book.currentPage.coerceAtLeast(0), logged)
        }
        val unlinkedLogPages = if (subjectId == null && (bookId == null || bookId <= 0L)) {
            val knownBookTitles = books.map { it.title.trim().lowercase(Locale.ROOT) }.toSet()
            readingLogs.asSequence()
                .filter { it.pages > 0 && it.title.trim().lowercase(Locale.ROOT) !in knownBookTitles }
                .sumOf { it.pages }
        } else {
            0
        }
        return pagesFromBooks + unlinkedLogPages
    }

    private fun computeCountdownSnapshot(
        goal: Goal,
        milestones: List<GoalMilestone>,
        now: Long
    ): GoalProgressSnapshot {
        val unitMs = when (goal.unit) {
            "HOURS" -> 3_600_000.0
            "WEEKS" -> 604_800_000.0
            "MONTHS" -> 2_629_746_000.0
            "YEARS" -> 31_556_952_000.0
            else -> DAY_MS.toDouble()
        }
        val isSince = goal.direction == "SINCE"
        val delta = if (isSince) now - goal.targetAt else goal.targetAt - now
        val raw = delta.coerceAtLeast(0L) / unitMs
        val amount = if (goal.roundUp) ceil(raw).toLong() else floor(raw).toLong()
        val unitLabel = countdownUnitWord(goal.unit, amount)
        val effectiveStart = if (goal.startAt > 0L) goal.startAt else goal.createdAt

        val progressFraction = when {
            goal.completed -> 1f
            isSince -> 0f
            delta <= 0L -> 1f
            effectiveStart > 0L && effectiveStart < goal.targetAt ->
                ((now - effectiveStart).toDouble() / (goal.targetAt - effectiveStart).toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
            else -> 0f
        }

        val paceStatus = when {
            goal.completed -> "Concluída"
            isSince -> "$amount $unitLabel decorridos"
            delta < 0L -> {
                val pastAmount = ceil((-delta) / unitMs).toLong().coerceAtLeast(1L)
                "Passou há $pastAmount ${countdownUnitWord(goal.unit, pastAmount)}"
            }
            amount == 0L -> "É hoje"
            else -> "Faltam $amount $unitLabel"
        }

        val targetSpan = if (!isSince && effectiveStart > 0L && effectiveStart < goal.targetAt) {
            maxOf(1.0, ((goal.targetAt - effectiveStart) / unitMs))
        } else {
            amount.toDouble()
        }

        val targetDate = Instant.ofEpochMilli(goal.targetAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(shortDateFormatter)

        return GoalProgressSnapshot(
            goal = goal,
            metricType = GoalMetricType.COUNTDOWN,
            current = amount.toDouble(),
            target = targetSpan,
            progressFraction = progressFraction,
            displayCurrent = "$amount $unitLabel",
            displayTarget = targetDate,
            requiredDailyPaceLabel = null,
            paceStatusLabel = paceStatus,
            isOnTrack = goal.completed || isSince || delta >= 0L,
            milestones = milestones
        )
    }

    private fun countdownUnitWord(unit: String, amount: Long): String = when (unit) {
        "HOURS" -> if (amount == 1L) "hora" else "horas"
        "WEEKS" -> if (amount == 1L) "semana" else "semanas"
        "MONTHS" -> if (amount == 1L) "mês" else "meses"
        "YEARS" -> if (amount == 1L) "ano" else "anos"
        else -> if (amount == 1L) "dia" else "dias"
    }
}

// =====================================================================
// Diário Reflexivo 360° (DiaryAnalytics)
// =====================================================================

/** Cross-module activity summary for a single diary `dayKey` (`YYYYMMDD`). */
data class DayActivitySummary(
    val dayKey: Long,
    val studySeconds: Long,
    val sessionCount: Int,
    val subjectNames: List<String>,
    val tasksCompletedCount: Int,
    val completedTaskTitles: List<String>,
    val flashcardReviewsCount: Int,
    val flashcardAccuracyPercent: Int,
    val questionsDone: Int,
    val questionsCorrect: Int,
    val questionAccuracyPercent: Int,
    val habitsCompletedCount: Int,
    val pagesRead: Int,
    val workoutsCount: Int,
    val workoutMinutes: Int,
    val mealsCount: Int,
    val totalCalories: Int
) {
    val hasAnyActivity: Boolean
        get() = studySeconds > 0L ||
            tasksCompletedCount > 0 ||
            flashcardReviewsCount > 0 ||
            questionsDone > 0 ||
            habitsCompletedCount > 0 ||
            pagesRead > 0 ||
            workoutsCount > 0 ||
            mealsCount > 0
}

/** Aggregated productivity and wellbeing averages grouped by mood emoji. */
data class MoodCorrelationStat(
    val mood: String,
    val moodLabel: String,
    val entriesCount: Int,
    val averageStudyMinutes: Int,
    val averageEnergy: Double,
    val averageFocus: Double,
    val averageQuestions: Int,
    val averageHabits: Double
)

/** Analytics, presets, tags, and search helpers for [JournalEntry]. */
object DiaryAnalytics {

    val moodPresets: List<Pair<String, String>> = listOf(
        "😄" to "Ótimo",
        "🙂" to "Bem",
        "😌" to "Calmo",
        "😐" to "Neutro",
        "😔" to "Desanimado",
        "😣" to "Estressado",
        "😴" to "Cansado"
    )

    val tagPresets: List<String> = listOf(
        "estudo",
        "revisão",
        "simulado",
        "foco",
        "cansaço",
        "vitória",
        "gratidão",
        "saúde"
    )

    val reflectionPrompts: List<String> = listOf(
        "O que avançou melhor nos estudos hoje e por quê?",
        "Qual erro ou dúvida de hoje merece virar revisão amanhã?",
        "O que drenou minha energia e o que me ajudou a manter o foco?",
        "Qual foi a maior vitória de hoje, mesmo que pequena?",
        "Qual é a prioridade número 1 para começar bem amanhã?",
        "Explique com suas palavras o conceito mais difícil que você estudou hoje.",
        "Como equilibrar ritmo, descanso e constância nos próximos dias?",
        "O que eu faria diferente se recomeçasse o dia de hoje?"
    )

    /** Converts an epoch millisecond timestamp to a `YYYYMMDD` [Long] key in [zone]. */
    fun dayKeyFromEpoch(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return d.year * 10000L + d.monthValue * 100L + d.dayOfMonth
    }

    /** Parses a `YYYYMMDD` [dayKey] into a [LocalDate]. */
    fun localDateFromDayKey(dayKey: Long): LocalDate {
        val year = (dayKey / 10000L).toInt().coerceIn(1970, 9999)
        val month = ((dayKey % 10000L) / 100L).toInt().coerceIn(1, 12)
        val day = (dayKey % 100L).toInt().coerceIn(1, 31)
        return runCatching { LocalDate.of(year, month, day) }
            .getOrElse { LocalDate.of(year, month, 1) }
    }

    /** Aggregates all StudyDock records occurring on [dayKey]. */
    fun summarizeDay(
        dayKey: Long,
        sessions: List<StudySession>,
        tasks: List<StudyTask>,
        cardReviews: List<CardReview>,
        questionLogs: List<QuestionLog>,
        habitCompletions: List<HabitCompletion>,
        readingLogs: List<ReadingLog>,
        workouts: List<WorkoutLog>,
        meals: List<MealLog>,
        subjects: List<Subject>,
        zone: ZoneId = ZoneId.systemDefault()
    ): DayActivitySummary {
        val subjectsById = subjects.associateBy { it.id }

        val daySessions = sessions.filter {
            !it.isActive && it.durationSeconds > 0L && dayKeyFromEpoch(it.startedAt, zone) == dayKey
        }
        val studySeconds = daySessions.sumOf { it.durationSeconds }
        val subjectNames = daySessions
            .groupBy { it.subjectId }
            .entries
            .sortedByDescending { (_, list) -> list.sumOf { it.durationSeconds } }
            .mapNotNull { (subjectId, _) -> subjectId?.let { subjectsById[it]?.name } }

        val completedTasks = tasks.filter { task ->
            if (!task.completed) return@filter false
            val completionTs = task.completedAt ?: if (task.hasDueDate) task.dueAt else null
            completionTs != null && dayKeyFromEpoch(completionTs, zone) == dayKey
        }

        val dayReviews = cardReviews.filter { dayKeyFromEpoch(it.reviewedAt, zone) == dayKey }
        val recalledReviews = dayReviews.count { it.grade > 0 }
        val flashcardAccuracy = if (dayReviews.isEmpty()) 0 else (recalledReviews * 100 / dayReviews.size)

        val dayQuestions = questionLogs.filter { it.questionCount > 0 && dayKeyFromEpoch(it.loggedAt, zone) == dayKey }
        val questionsDone = dayQuestions.sumOf { it.questionCount }
        val questionsCorrect = dayQuestions.sumOf { it.correctCount.coerceIn(0, it.questionCount) }
        val questionAccuracy = if (questionsDone == 0) 0 else (questionsCorrect * 100 / questionsDone)

        val habitsDone = habitCompletions.count {
            it.dayKey == dayKey && (it.status == "DONE" || it.status == "PARTIAL")
        }

        val pagesRead = readingLogs
            .filter { it.pages > 0 && dayKeyFromEpoch(it.loggedAt, zone) == dayKey }
            .sumOf { it.pages }

        val dayWorkouts = workouts.filter { dayKeyFromEpoch(it.startedAt, zone) == dayKey }
        val workoutMinutes = dayWorkouts.sumOf { it.durationMinutes.coerceAtLeast(0) }

        val dayMeals = meals.filter { dayKeyFromEpoch(it.loggedAt, zone) == dayKey }
        val totalCalories = dayMeals.sumOf { it.calories.coerceAtLeast(0) }

        return DayActivitySummary(
            dayKey = dayKey,
            studySeconds = studySeconds,
            sessionCount = daySessions.size,
            subjectNames = subjectNames,
            tasksCompletedCount = completedTasks.size,
            completedTaskTitles = completedTasks.map { it.title },
            flashcardReviewsCount = dayReviews.size,
            flashcardAccuracyPercent = flashcardAccuracy,
            questionsDone = questionsDone,
            questionsCorrect = questionsCorrect,
            questionAccuracyPercent = questionAccuracy,
            habitsCompletedCount = habitsDone,
            pagesRead = pagesRead,
            workoutsCount = dayWorkouts.size,
            workoutMinutes = workoutMinutes,
            mealsCount = dayMeals.size,
            totalCalories = totalCalories
        )
    }

    /** Formats [summary] into clean Markdown bullet points ready to append to a diary entry or note. */
    fun formatSummaryMarkdown(summary: DayActivitySummary): String {
        if (!summary.hasAnyActivity) {
            return "## Resumo do dia no StudyDock\n- Sem atividades registradas neste dia."
        }
        return buildString {
            appendLine("## Resumo do dia no StudyDock")
            if (summary.studySeconds > 0L) {
                val subjectsPart = if (summary.subjectNames.isNotEmpty()) {
                    " · ${summary.subjectNames.joinToString(", ")}"
                } else {
                    ""
                }
                appendLine("- **Estudo:** ${formatDurationCompact(summary.studySeconds)} (${summary.sessionCount} ${if (summary.sessionCount == 1) "sessão" else "sessões"}$subjectsPart)")
            }
            if (summary.tasksCompletedCount > 0) {
                val titles = summary.completedTaskTitles.take(4).joinToString(", ")
                val suffix = if (summary.completedTaskTitles.size > 4) "…" else ""
                appendLine("- **Tarefas concluídas:** ${summary.tasksCompletedCount} ($titles$suffix)")
            }
            if (summary.flashcardReviewsCount > 0) {
                appendLine("- **Flashcards:** ${summary.flashcardReviewsCount} revisões (${summary.flashcardAccuracyPercent}% de acerto)")
            }
            if (summary.questionsDone > 0) {
                appendLine("- **Questões:** ${summary.questionsDone} feitas · ${summary.questionsCorrect} certas (${summary.questionAccuracyPercent}%)")
            }
            if (summary.habitsCompletedCount > 0) {
                appendLine("- **Hábitos cumpridos:** ${summary.habitsCompletedCount}")
            }
            if (summary.pagesRead > 0) {
                appendLine("- **Leitura:** ${summary.pagesRead} páginas")
            }
            if (summary.workoutsCount > 0) {
                appendLine("- **Treino:** ${summary.workoutsCount} (${summary.workoutMinutes} min)")
            }
            if (summary.mealsCount > 0) {
                appendLine("- **Alimentação:** ${summary.mealsCount} refeições (${summary.totalCalories} kcal)")
            }
        }.trimEnd()
    }

    /** Computes per-mood averages for study time, energy, focus, questions, and habits. */
    fun computeMoodCorrelations(
        entries: List<JournalEntry>,
        sessions: List<StudySession>,
        questionLogs: List<QuestionLog> = emptyList(),
        habitCompletions: List<HabitCompletion> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<MoodCorrelationStat> {
        if (entries.isEmpty()) return emptyList()

        val studySecondsByDay = sessions
            .filter { !it.isActive && it.durationSeconds > 0L }
            .groupBy { dayKeyFromEpoch(it.startedAt, zone) }
            .mapValues { (_, list) -> list.sumOf { it.durationSeconds } }

        val questionsByDay = questionLogs
            .filter { it.questionCount > 0 }
            .groupBy { dayKeyFromEpoch(it.loggedAt, zone) }
            .mapValues { (_, list) -> list.sumOf { it.questionCount } }

        val habitsByDay = habitCompletions
            .filter { it.status == "DONE" || it.status == "PARTIAL" }
            .groupingBy { it.dayKey }
            .eachCount()

        val labelMap = moodPresets.toMap()

        return entries
            .groupBy { it.mood.ifBlank { "🙂" } }
            .map { (mood, group) ->
                val count = group.size
                val totalStudyMinutes = group.sumOf { ((studySecondsByDay[it.dayKey] ?: 0L) / 60L).toInt() }
                val totalQuestions = group.sumOf { questionsByDay[it.dayKey] ?: 0 }
                val totalHabits = group.sumOf { habitsByDay[it.dayKey] ?: 0 }
                MoodCorrelationStat(
                    mood = mood,
                    moodLabel = labelMap[mood] ?: mood,
                    entriesCount = count,
                    averageStudyMinutes = totalStudyMinutes / count,
                    averageEnergy = group.map { it.energy.coerceIn(1, 5) }.average(),
                    averageFocus = group.map { it.focus.coerceIn(1, 5) }.average(),
                    averageQuestions = totalQuestions / count,
                    averageHabits = totalHabits.toDouble() / count.toDouble()
                )
            }
            .sortedWith(
                compareByDescending<MoodCorrelationStat> { it.averageStudyMinutes }
                    .thenByDescending { it.entriesCount }
            )
    }

    /** Filters [entries] by search [query], [moodFilter], and [tagFilter], newest day first. */
    fun filterEntries(
        entries: List<JournalEntry>,
        query: String = "",
        moodFilter: String? = null,
        tagFilter: String? = null
    ): List<JournalEntry> {
        val cleanQuery = normalizeGrowthToken(query)
        val cleanMood = moodFilter?.trim()?.takeIf { it.isNotEmpty() }
        val cleanTag = tagFilter?.let(::normalizeTag)?.let(::normalizeGrowthToken)?.takeIf { it.isNotEmpty() }

        return entries
            .filter { entry ->
                if (cleanMood != null && entry.mood != cleanMood) return@filter false
                if (cleanTag != null && parseTags(entry.tags).none { normalizeGrowthToken(it) == cleanTag }) {
                    return@filter false
                }
                if (cleanQuery.isNotEmpty()) {
                    val dateText = localDateFromDayKey(entry.dayKey).format(shortDateFormatter)
                    val haystack = normalizeGrowthToken(
                        "${entry.title} ${entry.body} ${entry.tags} ${entry.promptUsed} $dateText ${entry.dayKey}"
                    )
                    if (cleanQuery !in haystack) return@filter false
                }
                true
            }
            .sortedByDescending { it.dayKey }
    }

    /** Normalizes a diary tag token (strips leading `#`/`@`, trims punctuation, lowercases). */
    fun normalizeTag(raw: String): String =
        raw.trim { it.isWhitespace() || it == ',' || it == ';' }
            .trimStart('#', '@')
            .replace(GROWTH_WHITESPACE_REGEX, "-")
            .lowercase(ptBrLocale)

    /** Splits a raw tag string into distinct normalized tags. */
    fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(TAG_SPLIT_REGEX)
            .map(::normalizeTag)
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** Serializes normalized tags back to a comma-separated string. */
    fun formatTags(tags: Iterable<String>): String =
        tags.map(::normalizeTag)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

    /** Toggles [tag] inside [raw] and returns the updated serialized tag string. */
    fun toggleTag(raw: String, tag: String): String {
        val normalized = normalizeTag(tag)
        if (normalized.isEmpty()) return formatTags(parseTags(raw))
        val current = parseTags(raw).toMutableList()
        if (normalized in current) current.remove(normalized) else current.add(normalized)
        return formatTags(current)
    }
}

// =====================================================================
// Edital Verticalizado (SyllabusEngine)
// =====================================================================

/** Mastery stage for a topic in a subject's vertical syllabus. */
enum class TopicMastery(val code: String, val label: String) {
    NOT_STARTED("NOT_STARTED", "Não visto"),
    LEARNING("LEARNING", "Teoria"),
    REVIEWING("REVIEWING", "Revisão"),
    MASTERED("MASTERED", "Dominado");

    /** Cycles to the next stage in one-tap progression (`Dominado` wraps to `Não visto`). */
    fun next(): TopicMastery = when (this) {
        NOT_STARTED -> LEARNING
        LEARNING -> REVIEWING
        REVIEWING -> MASTERED
        MASTERED -> NOT_STARTED
    }

    companion object {
        fun fromCode(code: String?): TopicMastery =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: NOT_STARTED
    }
}

/** Aggregated syllabus topic combining explicit `SubjectItem(type="TOPIC")` rows and inferred metrics. */
data class VerticalSyllabusTopic(
    val key: String,
    val title: String,
    val mastery: TopicMastery,
    val sourceItem: SubjectItem? = null,
    val isInferred: Boolean = false,
    val questionsTotal: Int = 0,
    val questionsCorrect: Int = 0,
    val accuracyPercent: Int? = null,
    val flashcardsCount: Int = 0,
    val dueFlashcardsCount: Int = 0,
    val openMistakesCount: Int = 0,
    val resolvedMistakesCount: Int = 0
)

/** Builds and updates the vertical syllabus (`Edital Verticalizado`) for a subject. */
object SyllabusEngine {

    private val subjectTopicBracketRegex = Regex("""^\[[^·\]\n]+·\s*([^\]\n]{2,60})]""")
    private val headerLineBracketRegex = Regex("""^\[([^\]\n]{2,60})]\s*\n""")

    /** Reads the persisted [TopicMastery] from a `SubjectItem(type="TOPIC")`. */
    fun masteryOf(item: SubjectItem): TopicMastery {
        if (item.completed) return TopicMastery.MASTERED
        val byUnit = TopicMastery.entries.firstOrNull { it.code.equals(item.unit.trim(), ignoreCase = true) }
        if (byUnit != null) return byUnit
        return when {
            item.currentValue >= 3.0 -> TopicMastery.MASTERED
            item.currentValue >= 2.0 -> TopicMastery.REVIEWING
            item.currentValue >= 1.0 -> TopicMastery.LEARNING
            else -> TopicMastery.NOT_STARTED
        }
    }

    /** Encodes [mastery] onto a [SubjectItem] while keeping `completed` synchronized. */
    fun applyMastery(item: SubjectItem, mastery: TopicMastery): SubjectItem =
        item.copy(
            unit = mastery.code,
            completed = mastery == TopicMastery.MASTERED,
            currentValue = mastery.ordinal.toDouble()
        )

    /**
     * Merges explicit `SubjectItem(type = "TOPIC")` entries with topics inferred from
     * [questionLogs], bracketed [flashcards], and [mistakes] for [subjectId].
     */
    fun buildTopics(
        subjectId: Long,
        items: List<SubjectItem>,
        questionLogs: List<QuestionLog>,
        flashcards: List<Flashcard>,
        mistakes: List<Mistake>,
        now: Long = System.currentTimeMillis()
    ): List<VerticalSyllabusTopic> {
        val explicitItems = items
            .filter { it.subjectId == subjectId && it.type.equals("TOPIC", ignoreCase = true) && it.title.isNotBlank() }
            .sortedWith(compareBy<SubjectItem> { it.createdAt }.thenBy { it.id })

        val scopedQuestions = questionLogs.filter { it.subjectId == subjectId && it.questionCount > 0 }
        val scopedCards = flashcards.filter { it.subjectId == subjectId && !it.suspended }
        val scopedMistakes = mistakes.filter { it.subjectId == subjectId }

        val topicNamesByKey = linkedMapOf<String, String>()
        val explicitByKey = linkedMapOf<String, SubjectItem>()

        for (item in explicitItems) {
            val key = normalizeGrowthToken(item.title)
            if (key.isEmpty()) continue
            explicitByKey.putIfAbsent(key, item)
            topicNamesByKey.putIfAbsent(key, item.title.trim())
        }

        for (log in scopedQuestions) {
            val clean = log.topic.trim()
            val key = normalizeGrowthToken(clean)
            if (key.isNotEmpty()) topicNamesByKey.putIfAbsent(key, clean)
        }

        for (mistake in scopedMistakes) {
            val clean = mistake.topic.trim()
            val key = normalizeGrowthToken(clean)
            if (key.isNotEmpty()) topicNamesByKey.putIfAbsent(key, clean)
        }

        for (card in scopedCards) {
            val trimmedFront = card.front.trim()
            val match = subjectTopicBracketRegex.find(trimmedFront)
                ?: headerLineBracketRegex.find(trimmedFront)
                ?: continue
            val clean = match.groupValues[1].trim()
            if (clean == "...") continue
            val key = normalizeGrowthToken(clean)
            if (key.isNotEmpty()) topicNamesByKey.putIfAbsent(key, clean)
        }

        val questionsByKey = scopedQuestions.groupBy { normalizeGrowthToken(it.topic) }
        val mistakesByKey = scopedMistakes.groupBy { normalizeGrowthToken(it.topic) }
        val cardSearchText = scopedCards.map { card ->
            card to normalizeGrowthToken("${card.front} ${card.back} ${card.sourceExcerpt.orEmpty()}")
        }

        fun buildEntry(key: String, title: String, explicitItem: SubjectItem?): VerticalSyllabusTopic {
            val qLogs = questionsByKey[key].orEmpty()
            val qTotal = qLogs.sumOf { it.questionCount }
            val qCorrect = qLogs.sumOf { it.correctCount.coerceIn(0, it.questionCount) }
            val accuracy = if (qTotal > 0) (qCorrect * 100 / qTotal) else null

            val mList = mistakesByKey[key].orEmpty()
            val openMistakes = mList.count { !it.resolved }
            val resolvedMistakes = mList.count { it.resolved }

            val matchingCards = cardSearchText.filter { (_, haystack) ->
                if (key.length <= 1) haystack == key else key in haystack
            }
            val cardsCount = matchingCards.size
            val dueCardsCount = matchingCards.count { (card, _) -> card.dueAt <= now }

            val mastery = if (explicitItem != null) {
                masteryOf(explicitItem)
            } else {
                when {
                    accuracy != null && accuracy >= 85 && qTotal >= 10 && openMistakes == 0 -> TopicMastery.MASTERED
                    (qTotal >= 5 && (accuracy ?: 0) >= 60) || cardsCount > 0 -> TopicMastery.REVIEWING
                    qTotal > 0 || mList.isNotEmpty() -> TopicMastery.LEARNING
                    else -> TopicMastery.NOT_STARTED
                }
            }

            return VerticalSyllabusTopic(
                key = key,
                title = title,
                mastery = mastery,
                sourceItem = explicitItem,
                isInferred = explicitItem == null,
                questionsTotal = qTotal,
                questionsCorrect = qCorrect,
                accuracyPercent = accuracy,
                flashcardsCount = cardsCount,
                dueFlashcardsCount = dueCardsCount,
                openMistakesCount = openMistakes,
                resolvedMistakesCount = resolvedMistakes
            )
        }

        val explicitTopics = explicitByKey.entries.map { (key, item) ->
            buildEntry(key, topicNamesByKey[key] ?: item.title.trim(), item)
        }
        val inferredTopics = topicNamesByKey.entries
            .filter { (key, _) -> key !in explicitByKey }
            .sortedBy { it.value.lowercase(ptBrLocale) }
            .map { (key, title) -> buildEntry(key, title, null) }

        return explicitTopics + inferredTopics
    }
}

// =====================================================================
// Revisão Espaçada do Caderno de Erros (MistakeSpacedReview)
// =====================================================================

/** Spaced-repetition scheduler (1, 3, 7, 15, 30 days) and Anki exporter for [Mistake]. */
object MistakeSpacedReview {

    val intervalsDays: List<Int> = listOf(1, 3, 7, 15, 30)

    /** Returns `true` when [mistake] is unresolved and its scheduled review date has arrived. */
    fun isDue(mistake: Mistake, now: Long = System.currentTimeMillis()): Boolean {
        if (mistake.resolved) return false
        return mistake.nextReviewAt <= 0L || mistake.nextReviewAt <= now
    }

    /** Preview of the next review interval in days for UI action labels. */
    fun nextIntervalDays(mistake: Mistake, rememberedWell: Boolean): Int {
        if (!rememberedWell) return intervalsDays.first()
        val index = mistake.reviewCount.coerceIn(0, intervalsDays.lastIndex)
        return intervalsDays[index]
    }

    /**
     * Schedules the next spaced review for [mistake].
     * Successful reviews advance along `[1, 3, 7, 15, 30]` days and mark `resolved = true`
     * after completing the 30-day step; missed reviews reset `reviewCount` to `0` and schedule in 1 day.
     */
    fun scheduleNextReview(
        mistake: Mistake,
        rememberedWell: Boolean,
        now: Long = System.currentTimeMillis()
    ): Mistake {
        if (!rememberedWell) {
            return mistake.copy(
                reviewCount = 0,
                nextReviewAt = now + intervalsDays.first() * DAY_MS,
                resolved = false
            )
        }
        val currentCount = mistake.reviewCount.coerceAtLeast(0)
        val interval = intervalsDays[currentCount.coerceIn(0, intervalsDays.lastIndex)]
        val nextCount = currentCount + 1
        val graduated = currentCount >= intervalsDays.lastIndex && nextCount > intervalsDays.size
        return mistake.copy(
            reviewCount = nextCount,
            nextReviewAt = now + interval * DAY_MS,
            resolved = graduated || mistake.resolved
        )
    }

    /** Builds the front text of an Anki flashcard derived from [mistake]. */
    fun formatFlashcardFront(subjectName: String, mistake: Mistake): String = buildString {
        val header = listOf(subjectName.trim(), mistake.topic.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        if (header.isNotEmpty()) append("[$header]\n")
        append(mistake.question.trim())
    }

    /** Builds the back text of an Anki flashcard derived from [mistake]. */
    fun formatFlashcardBack(mistake: Mistake): String = buildString {
        append(mistake.solution.trim())
        if (mistake.reason.isNotBlank()) {
            append("\n\n⚠️ Onde errei: ")
            append(mistake.reason.trim())
        }
    }
}

// =====================================================================
// Ecossistema de Matérias (SubjectEcosystemEngine)
// =====================================================================

/** Cross-module snapshot of a [Subject] for the Subjects command center and study cycle card. */
data class SubjectEcosystemOverview(
    val subject: Subject,
    val todayFocusSeconds: Long,
    val totalFocusSeconds: Long,
    val lastStudiedAt: Long?,
    val openTasksCount: Int,
    val overdueTasksCount: Int,
    val dueFlashcardsCount: Int,
    val totalFlashcardsCount: Int,
    val dueMistakesCount: Int,
    val unresolvedMistakesCount: Int,
    val questionsTotal: Int,
    val questionsCorrect: Int,
    val questionAccuracyPercent: Int?,
    val cycleSharePercent: Float,
    val recommendedMinutesPerDay: Int,
    val daysUntilExam: Int?
)

/** Builds [SubjectEcosystemOverview] entries and cycle shares (`0f..100f`) across subjects. */
object SubjectEcosystemEngine {

    /**
     * Computes ecosystem metrics for every subject in [subjects].
     * `cycleSharePercent` (`0f..100f`) is distributed proportionally across non-archived subjects
     * using `weight.coerceAtLeast(1)`. Archived subjects receive `cycleSharePercent = 0f`.
     */
    fun buildOverview(
        subjects: List<Subject>,
        sessions: List<StudySession>,
        tasks: List<StudyTask>,
        flashcards: List<Flashcard>,
        mistakes: List<Mistake>,
        questionLogs: List<QuestionLog>,
        dailyGoalMinutes: Int = 180,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<SubjectEcosystemOverview> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val activeWeightSum = subjects
            .asSequence()
            .filter { !it.archived }
            .sumOf { it.weight.coerceAtLeast(1) }

        val validSessionsBySubject = sessions
            .filter { !it.isActive && it.durationSeconds > 0L && it.subjectId != null }
            .groupBy { it.subjectId!! }

        val openTasksBySubject = tasks
            .filter { !it.completed && it.subjectId != null }
            .groupBy { it.subjectId!! }

        val activeCardsBySubject = flashcards
            .filter { !it.suspended && it.subjectId != null }
            .groupBy { it.subjectId!! }

        val mistakesBySubject = mistakes.groupBy { it.subjectId }

        val questionsBySubject = questionLogs
            .filter { it.questionCount > 0 && it.subjectId != null }
            .groupBy { it.subjectId!! }

        return subjects.map { subject ->
            val subjSessions = validSessionsBySubject[subject.id].orEmpty()
            val todayFocus = subjSessions
                .filter { it.startedAt in todayStart until tomorrowStart }
                .sumOf { it.durationSeconds }
            val totalFocus = subjSessions.sumOf { it.durationSeconds }
            val lastStudied = subjSessions.maxOfOrNull { it.startedAt }

            val subjTasks = openTasksBySubject[subject.id].orEmpty()
            val overdueTasks = subjTasks.count { it.hasDueDate && it.dueAt < todayStart }

            val subjCards = activeCardsBySubject[subject.id].orEmpty()
            val dueCards = subjCards.count { it.dueAt <= now }

            val subjMistakes = mistakesBySubject[subject.id].orEmpty()
            val unresolvedMistakes = subjMistakes.count { !it.resolved }
            val dueMistakes = subjMistakes.count { MistakeSpacedReview.isDue(it, now) }

            val subjQuestions = questionsBySubject[subject.id].orEmpty()
            val qTotal = subjQuestions.sumOf { it.questionCount }
            val qCorrect = subjQuestions.sumOf { it.correctCount.coerceIn(0, it.questionCount) }
            val qAccuracy = if (qTotal > 0) (qCorrect * 100 / qTotal) else null

            val sharePercent = if (subject.archived || activeWeightSum <= 0) {
                0f
            } else {
                (subject.weight.coerceAtLeast(1).toFloat() / activeWeightSum.toFloat()) * 100f
            }

            val recommendedMinutes = when {
                subject.archived -> 0
                subject.targetMinutesPerDay > 0 -> subject.targetMinutesPerDay
                dailyGoalMinutes > 0 -> (dailyGoalMinutes * (sharePercent / 100f)).roundToInt()
                else -> 0
            }

            val daysUntilExam = subject.examDate?.let { examMs ->
                val examDate = Instant.ofEpochMilli(examMs).atZone(zone).toLocalDate()
                ChronoUnit.DAYS.between(today, examDate).toInt()
            }

            SubjectEcosystemOverview(
                subject = subject,
                todayFocusSeconds = todayFocus,
                totalFocusSeconds = totalFocus,
                lastStudiedAt = lastStudied,
                openTasksCount = subjTasks.size,
                overdueTasksCount = overdueTasks,
                dueFlashcardsCount = dueCards,
                totalFlashcardsCount = subjCards.size,
                dueMistakesCount = dueMistakes,
                unresolvedMistakesCount = unresolvedMistakes,
                questionsTotal = qTotal,
                questionsCorrect = qCorrect,
                questionAccuracyPercent = qAccuracy,
                cycleSharePercent = sharePercent,
                recommendedMinutesPerDay = recommendedMinutes,
                daysUntilExam = daysUntilExam
            )
        }
    }
}

/**
 * Hierarquia simples de 2 níveis entre Matéria (raiz, parentId == null) e Submatéria (filha).
 * Mantém nome completo legível em seletores ("Matemática › Álgebra") e consolida métricas
 * da matéria mãe com suas submatérias.
 */
data class SubjectRollUp(
    val rootOverview: SubjectEcosystemOverview,
    val children: List<SubjectEcosystemOverview>,
    val todayFocusSeconds: Long,
    val totalFocusSeconds: Long,
    val openTasksCount: Int,
    val overdueTasksCount: Int,
    val dueFlashcardsCount: Int,
    val totalFlashcardsCount: Int,
    val dueMistakesCount: Int,
    val unresolvedMistakesCount: Int,
    val questionsTotal: Int,
    val questionsCorrect: Int
) {
    val questionAccuracyPercent: Int?
        get() = if (questionsTotal > 0) (questionsCorrect * 100) / questionsTotal else null
}

object SubjectHierarchy {

    fun isRoot(subject: Subject): Boolean = subject.parentId == null

    fun parentOf(subject: Subject, byId: Map<Long, Subject>): Subject? =
        subject.parentId?.let(byId::get)?.takeIf { it.id != subject.id }

    fun rootSubjects(subjects: List<Subject>): List<Subject> =
        subjects.filter { it.parentId == null }

    fun childrenOf(
        parentId: Long,
        subjects: List<Subject>,
        includeArchived: Boolean = true
    ): List<Subject> = subjects.filter {
        it.parentId == parentId && (includeArchived || !it.archived)
    }

    fun displayName(
        subject: Subject,
        byId: Map<Long, Subject>,
        separator: String = " › "
    ): String {
        val parent = parentOf(subject, byId)
        return if (parent != null) "${parent.name}$separator${subject.name}" else subject.name
    }

    fun orderedHierarchically(subjects: List<Subject>): List<Subject> {
        if (subjects.isEmpty()) return emptyList()
        val ids = subjects.mapTo(HashSet()) { it.id }
        val byParent = subjects.groupBy { s ->
            val pid = s.parentId
            if (pid != null && pid != s.id && pid in ids) pid else null
        }
        val roots = byParent[null].orEmpty()
        val result = ArrayList<Subject>(subjects.size)
        roots.forEach { root ->
            result.add(root)
            result.addAll(byParent[root.id].orEmpty())
        }
        return result
    }

    fun rollUpForParent(
        parentId: Long,
        overviews: List<SubjectEcosystemOverview>
    ): SubjectRollUp? {
        val root = overviews.firstOrNull { it.subject.id == parentId } ?: return null
        val children = overviews.filter { it.subject.parentId == parentId && it.subject.id != parentId }
        val all = if (children.isEmpty()) listOf(root) else listOf(root) + children
        return SubjectRollUp(
            rootOverview = root,
            children = children,
            todayFocusSeconds = all.sumOf { it.todayFocusSeconds },
            totalFocusSeconds = all.sumOf { it.totalFocusSeconds },
            openTasksCount = all.sumOf { it.openTasksCount },
            overdueTasksCount = all.sumOf { it.overdueTasksCount },
            dueFlashcardsCount = all.sumOf { it.dueFlashcardsCount },
            totalFlashcardsCount = all.sumOf { it.totalFlashcardsCount },
            dueMistakesCount = all.sumOf { it.dueMistakesCount },
            unresolvedMistakesCount = all.sumOf { it.unresolvedMistakesCount },
            questionsTotal = all.sumOf { it.questionsTotal },
            questionsCorrect = all.sumOf { it.questionsCorrect }
        )
    }
}

// ---------------------------------------------------------------------------
// Hábitos: catálogo de ícones/cores e padrões (pico de foco, hábitos perdidos)
// ---------------------------------------------------------------------------

data class HabitIconSpec(
    val key: String,
    val label: String,
    val category: String,
    val keywords: List<String>,
    val fallbackEmoji: String
)

object HabitIconCatalog {
    const val DEFAULT_KEY = "default"

    val colors: List<Long> = listOf(
        0xFF2268F5L, // azul
        0xFF2BB5E8L, // ciano
        0xFF19A957L, // verde
        0xFFE53935L, // vermelho
        0xFF5D534FL, // taupe
        0xFFF59E0BL, // laranja
        0xFF7C3AEDL, // roxo
        0xFFEC4899L, // rosa
        0xFF14B8A6L, // teal
        0xFF4F46E5L, // índigo
        0xFF84CC16L, // lima
        0xFFD97706L  // âmbar
    )

    val icons: List<HabitIconSpec> = listOf(
        HabitIconSpec("default", "Padrão", "Geral", listOf("padrao", "geral", "estrela", "brilho", "habito"), "✨"),
        HabitIconSpec("book", "Livro", "Estudo", listOf("livro", "ler", "leitura", "estudar", "book", "read"), "📚"),
        HabitIconSpec("write", "Escrever", "Estudo", listOf("escrever", "caneta", "diario", "redacao", "write", "journal"), "✍️"),
        HabitIconSpec("water", "Água", "Saúde", listOf("agua", "beber", "hidratar", "hidratacao", "water", "drink"), "💧"),
        HabitIconSpec("document", "Documento", "Trabalho", listOf("documento", "papel", "relatorio", "arquivo", "document"), "📄"),
        HabitIconSpec("edit_tools", "Ferramentas", "Trabalho", listOf("ferramentas", "editar", "lapis", "regua", "projeto", "tools"), "🛠️"),
        HabitIconSpec("stretch", "Alongar", "Saúde", listOf("alongar", "alongamento", "yoga", "flexibilidade", "stretch"), "🤸"),
        HabitIconSpec("leaf", "Natureza", "Bem-estar", listOf("folha", "natureza", "planta", "verde", "vegano", "leaf"), "🍃"),
        HabitIconSpec("heart_break", "Coração partido", "Parar", listOf("coracao", "partido", "termino", "vicio", "recaida", "parar", "heart"), "💔"),
        HabitIconSpec("workout", "Treino", "Saúde", listOf("treino", "academia", "musculacao", "exercicio", "peso", "workout", "gym"), "🏋️"),
        HabitIconSpec("meditate", "Meditar", "Bem-estar", listOf("meditar", "meditacao", "respirar", "mindfulness", "calma", "meditate"), "🧘"),
        HabitIconSpec("paint", "Pintar", "Criatividade", listOf("pintar", "pintura", "arte", "desenho", "desenhar", "paint", "art"), "🎨"),
        HabitIconSpec("chat", "Conversar", "Social", listOf("conversar", "conversa", "falar", "ligar", "amigos", "familia", "chat"), "💬"),
        HabitIconSpec("run", "Correr", "Saúde", listOf("correr", "corrida", "caminhar", "caminhada", "cardio", "run"), "🏃"),
        HabitIconSpec("sleep", "Dormir", "Saúde", listOf("dormir", "sono", "cama", "descansar", "sleep"), "😴"),
        HabitIconSpec("science", "Ciência", "Estudo", listOf("ciencia", "quimica", "fisica", "biologia", "laboratorio", "science"), "🔬"),
        HabitIconSpec("knit", "Tricô", "Criatividade", listOf("trico", "croche", "costura", "artesanato", "knit"), "🧶"),
        HabitIconSpec("scroll", "Pergaminho", "Estudo", listOf("pergaminho", "historia", "revisao", "resumo", "scroll"), "📜"),
        HabitIconSpec("food_clean", "Comer bem", "Saúde", listOf("comida", "alimentacao", "dieta", "salada", "saudavel", "food"), "🥗"),
        HabitIconSpec("brain", "Cérebro", "Estudo", listOf("cerebro", "mente", "pensar", "foco", "memoria", "brain"), "🧠"),
        HabitIconSpec("music", "Música", "Criatividade", listOf("musica", "instrumento", "tocar", "cantar", "violao", "piano", "music"), "🎵"),
        HabitIconSpec("code", "Programar", "Trabalho", listOf("programar", "codigo", "programacao", "dev", "code"), "💻"),
        HabitIconSpec("timer", "Timer", "Produtividade", listOf("timer", "cronometro", "pomodoro", "tempo", "foco"), "⏱️"),
        HabitIconSpec("sun", "Manhã", "Rotina", listOf("sol", "manha", "acordar", "cedo", "dia", "sun", "morning"), "☀️"),
        HabitIconSpec("moon", "Noite", "Rotina", listOf("lua", "noite", "deitar", "rotina noturna", "moon", "night"), "🌙"),
        HabitIconSpec("no_phone", "Sem celular", "Parar", listOf("celular", "telefone", "redes", "sociais", "tela", "detox", "phone"), "📵")
    )

    private val byKey: Map<String, HabitIconSpec> = icons.associateBy { it.key }

    fun findIcon(key: String?): HabitIconSpec =
        key?.trim()?.takeIf { it.isNotEmpty() }?.let { byKey[it] } ?: byKey.getValue(DEFAULT_KEY)

    fun search(query: String): List<HabitIconSpec> {
        val q = normalizeGrowthToken(query)
        if (q.isEmpty()) return icons
        val tokens = q.split(GROWTH_WHITESPACE_REGEX).filter { it.isNotEmpty() }
        return icons.filter { spec ->
            val haystack = buildList {
                add(spec.key.replace('_', ' '))
                add(spec.label)
                add(spec.category)
                addAll(spec.keywords)
            }.map { normalizeGrowthToken(it) }
            tokens.all { token -> haystack.any { it.contains(token) } }
        }
    }
}

enum class FocusTimeZone(val id: String, val label: String, val rangeLabel: String) {
    MORNING("MORNING", "Manhã", "7h–12h"),
    AFTERNOON("AFTERNOON", "Tarde", "12h–17h"),
    EVENING("EVENING", "Noite", "17h–7h");

    companion object {
        fun fromId(id: String?): FocusTimeZone? = entries.firstOrNull { it.id == id }
    }
}

data class PeakFocusZoneStat(
    val zone: FocusTimeZone,
    val completionCount: Int,
    val scheduledDenominator: Int,
    val successRatePercent: Int?,
    val shareOfCompletions: Float
)

data class MissedHabitsSummary(
    val skippedCount: Int,
    val failedCount: Int,
    val evaluatedScheduledCount: Int,
    val trackedDaysCount: Int,
    val minDaysToUnlock: Int = 3
) {
    val unlocked: Boolean get() = trackedDaysCount >= minDaysToUnlock
    val remainingDaysToUnlock: Int get() = (minDaysToUnlock - trackedDaysCount).coerceAtLeast(0)
    val totalMissed: Int get() = skippedCount + failedCount
}

data class HabitPatternsSnapshot(
    val kind: String,
    val rangeDays: Int,
    val trackedDaysCount: Int,
    val peakZones: List<PeakFocusZoneStat>,
    val missed: MissedHabitsSummary,
    val bestZone: FocusTimeZone?
)

object HabitPatternsEngine {
    private val successStatuses = setOf("DONE", "PARTIAL")
    private val failedStatuses = setOf("FAILED", "MISSED")
    private val validStatuses = successStatuses + failedStatuses + "SKIPPED"

    fun classifyHourToZone(hourOfDay: Int): FocusTimeZone = when (hourOfDay) {
        in 7..11 -> FocusTimeZone.MORNING
        in 12..16 -> FocusTimeZone.AFTERNOON
        else -> FocusTimeZone.EVENING
    }

    /** Mesmo alinhamento de bits de `isScheduledOn` (OrganizingPages): segunda = bit 0 … domingo = bit 6. */
    fun isScheduledOn(habit: Habit, date: LocalDate): Boolean =
        (habit.repeatDaysMask and (1 shl (date.dayOfWeek.value - 1))) != 0

    private fun keyToDate(dayKey: Long): LocalDate? = runCatching {
        LocalDate.of((dayKey / 10000L).toInt(), ((dayKey / 100L) % 100L).toInt(), (dayKey % 100L).toInt())
    }.getOrNull()

    private fun dateToKey(date: LocalDate): Long = date.year * 10000L + date.monthValue * 100L + date.dayOfMonth

    private fun epochToKey(epochMillis: Long, zoneId: ZoneId): Long =
        dateToKey(Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate())

    private fun habitStartKey(habit: Habit, zoneId: ZoneId): Long {
        val start = if (habit.startedAt > 0L) habit.startedAt else habit.createdAt
        return if (start > 0L) epochToKey(start, zoneId) else 0L
    }

    fun computePatterns(
        habits: List<Habit>,
        completions: List<HabitCompletion>,
        todayDayKey: Long,
        rangeDays: Int = 28,
        kindFilter: String = "GOOD",
        habitIdFilter: Long? = null,
        areaFilter: String? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): HabitPatternsSnapshot {
        val safeRange = rangeDays.coerceIn(1, 3660)
        val normalizedKind = kindFilter.trim().uppercase(Locale.ROOT)
        val filterKind = normalizedKind.isNotEmpty() && normalizedKind != "ALL"
        val filtered = habits.filter { h ->
            (!filterKind || h.kind.equals(normalizedKind, ignoreCase = true)) &&
                (habitIdFilter == null || h.id == habitIdFilter) &&
                (areaFilter.isNullOrBlank() || h.area == areaFilter)
        }
        val today = keyToDate(todayDayKey) ?: Instant.now().atZone(zoneId).toLocalDate()
        val todayKey = dateToKey(today)
        val windowStartDate = today.minusDays((safeRange - 1).toLong())
        val windowStartKey = dateToKey(windowStartDate)
        val habitById = filtered.associateBy { it.id }

        val windowCompletions = completions.filter { c ->
            c.habitId in habitById && c.dayKey in windowStartKey..todayKey &&
                c.status.uppercase(Locale.ROOT) in validStatuses
        }
        val trackedDays = windowCompletions.map { it.dayKey }.distinct().size

        // --- Perdidos (pulados / falhas) ---
        var skipped = 0
        var failed = 0
        var evaluated = 0
        val completionByHabitDay = windowCompletions.associateBy { it.habitId to it.dayKey }
        val completionsByHabit = windowCompletions.groupBy { it.habitId }
        val scheduledPerHabit = HashMap<Long, Int>()
        filtered.forEach { habit ->
            val startKey = habitStartKey(habit, zoneId)
            var date = windowStartDate
            var scheduledCount = 0
            while (!date.isAfter(today)) {
                val key = dateToKey(date)
                if (key >= startKey && isScheduledOn(habit, date)) {
                    val onVacation = habit.vacationUntilDay > 0L && key <= habit.vacationUntilDay
                    val record = completionByHabitDay[habit.id to key]
                    val status = record?.status?.uppercase(Locale.ROOT).orEmpty()
                    val meetsMini = record != null && habit.miniTarget > 0.0 && record.value >= habit.miniTarget && status !in failedStatuses
                    when {
                        status == "SKIPPED" -> { skipped++; evaluated++ }
                        status in failedStatuses -> { failed++; evaluated++ }
                        status in successStatuses || meetsMini -> evaluated++
                        onVacation -> Unit
                        record == null && key < todayKey -> { failed++; evaluated++ }
                    }
                    if (!onVacation || status in successStatuses || meetsMini) scheduledCount++
                }
                date = date.plusDays(1)
            }
            // Registros SKIPPED/FAILED em dias não programados também contam como perdidos.
            completionsByHabit[habit.id].orEmpty().forEach { c ->
                val d = keyToDate(c.dayKey) ?: return@forEach
                if (c.dayKey >= startKey && isScheduledOn(habit, d)) return@forEach
                when (c.status.uppercase(Locale.ROOT)) {
                    "SKIPPED" -> { skipped++; evaluated++ }
                    in failedStatuses -> { failed++; evaluated++ }
                }
            }
            scheduledPerHabit[habit.id] = scheduledCount
        }
        val missed = MissedHabitsSummary(
            skippedCount = skipped,
            failedCount = failed,
            evaluatedScheduledCount = evaluated,
            trackedDaysCount = trackedDays
        )

        // --- Pico de foco ---
        fun zoneOf(c: HabitCompletion): FocusTimeZone? {
            if (c.loggedAt > 0L) return classifyHourToZone(Instant.ofEpochMilli(c.loggedAt).atZone(zoneId).hour)
            val reminder = habitById[c.habitId]?.reminderMinutes ?: return null
            return classifyHourToZone((reminder / 60).coerceIn(0, 23))
        }
        val successes = windowCompletions.filter { it.status.uppercase(Locale.ROOT) in successStatuses }
        val zoneCounts = FocusTimeZone.entries.associateWith { 0 }.toMutableMap()
        val habitZoneVotes = HashMap<Long, MutableMap<FocusTimeZone, Int>>()
        successes.forEach { c ->
            val z = zoneOf(c) ?: return@forEach
            zoneCounts[z] = (zoneCounts[z] ?: 0) + 1
            val votes = habitZoneVotes.getOrPut(c.habitId) { mutableMapOf() }
            votes[z] = (votes[z] ?: 0) + 1
        }
        // Zona "de casa" de cada hábito: lembrete, senão a zona onde mais é concluído.
        val denominators = FocusTimeZone.entries.associateWith { 0 }.toMutableMap()
        filtered.forEach { habit ->
            val home = habit.reminderMinutes?.let { classifyHourToZone((it / 60).coerceIn(0, 23)) }
                ?: habitZoneVotes[habit.id]?.maxByOrNull { it.value }?.key
                ?: return@forEach
            denominators[home] = (denominators[home] ?: 0) + (scheduledPerHabit[habit.id] ?: 0)
        }
        val totalSuccesses = zoneCounts.values.sum()
        val peakZones = FocusTimeZone.entries.map { zone ->
            val count = zoneCounts[zone] ?: 0
            val denominator = denominators[zone] ?: 0
            val rate = if (!missed.unlocked || count == 0) null
            else if (denominator > 0) ((count * 100.0) / denominator).roundToInt().coerceIn(0, 100)
            else null
            PeakFocusZoneStat(
                zone = zone,
                completionCount = count,
                scheduledDenominator = denominator,
                successRatePercent = rate,
                shareOfCompletions = if (totalSuccesses > 0) count.toFloat() / totalSuccesses else 0f
            )
        }
        val bestZone = if (!missed.unlocked || totalSuccesses == 0) null
        else peakZones.maxWithOrNull(compareBy<PeakFocusZoneStat> { it.completionCount }.thenBy { it.successRatePercent ?: -1 })?.zone

        return HabitPatternsSnapshot(
            kind = if (filterKind) normalizedKind else "ALL",
            rangeDays = safeRange,
            trackedDaysCount = trackedDays,
            peakZones = peakZones,
            missed = missed,
            bestZone = bestZone
        )
    }
}

object UnifiedBrainEngine {
    /**
     * Agendador Adaptativo Bio-Cognitivo:
     * Ativa Modo de Manutenção Cognitiva se energia/foco no diário estiver <= 2 hoje
     * ou se houver treino intenso (> 60 min) registrado nas últimas 12 horas.
     */
    fun shouldEnableCognitiveMaintenanceMode(
        recentEntries: List<JournalEntry>,
        recentWorkouts: List<WorkoutLog>,
        todayKey: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val todayEntry = recentEntries.firstOrNull { it.dayKey == todayKey }
        if (todayEntry != null && (todayEntry.energy <= 2 || todayEntry.focus <= 2)) return true
        val twelveHoursAgo = nowMillis - 12L * 3_600_000L
        return recentWorkouts.any { it.startedAt >= twelveHoursAgo && it.durationMinutes >= 60 }
    }

    /** Autópsia Automática da Sessão de Foco ao encerrar o cronômetro. */
    fun buildSessionAutopsyReceipt(
        durationSeconds: Long,
        reviewedCards: Int,
        retentionPct: Int,
        wordsWritten: Int,
        pagesRead: Int,
        eloDelta: Int
    ): String {
        val mins = (durationSeconds / 60L).coerceAtLeast(1L)
        val parts = mutableListOf<String>()
        if (reviewedCards > 0) parts.add("+$reviewedCards flashcards (${retentionPct.coerceIn(0, 100)}% retenção)")
        if (wordsWritten > 0) parts.add("+$wordsWritten palavras")
        if (pagesRead > 0) parts.add("+$pagesRead páginas lidas")
        if (eloDelta != 0) parts.add("${if (eloDelta > 0) "+" else ""}$eloDelta ELO")
        val summary = if (parts.isEmpty()) "sessão de foco pura" else parts.joinToString(", ")
        return "Em $mins min de foco líquido: $summary."
    }
}
