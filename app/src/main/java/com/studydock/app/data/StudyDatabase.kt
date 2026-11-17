package com.studydock.app.data

import java.time.Instant
import java.time.ZoneId
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "activity_timers")
data class ActivityTimer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String, val title: String, val bookId: Long?, val startedAt: Long,
    val runningSince: Long?, val elapsedMs: Long = 0, val endedAt: Long? = null,
    val series: String = "", val restUntil: Long = 0
) {
    fun duration(now: Long) = elapsedMs + (runningSince?.let { (now - it).coerceAtLeast(0L) } ?: 0L)
}

@Entity(tableName = "reading_logs", indices = [Index("loggedAt"), Index("bookId")])
data class ReadingLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val pages: Int,
    val loggedAt: Long,
    val bookId: Long? = null,
    val subjectId: Long? = null,
    @ColumnInfo(defaultValue = "0") val durationMinutes: Int = 0
)

@Entity(tableName = "subjects", indices = [Index("parentId")])
data class Subject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF6D5EF5,
    val nextStep: String? = null,
    @ColumnInfo(defaultValue = "0") val targetMinutesPerDay: Int = 0,
    @ColumnInfo(defaultValue = "1") val weight: Int = 1,
    val examDate: Long? = null,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val parentId: Long? = null
)

@Entity(tableName = "study_sessions", foreignKeys = [ForeignKey(entity = Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("subjectId"), Index("startedAt")])
data class StudySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long? = null,
    val startedAt: Long,
    val endedAt: Long,
    val durationSeconds: Long,
    @ColumnInfo(defaultValue = "0") val isActive: Boolean = false,
    @ColumnInfo(defaultValue = "''") val topic: String = "",
    @ColumnInfo(defaultValue = "'STOPWATCH'") val mode: String = "STOPWATCH",
    @ColumnInfo(defaultValue = "0") val breakSeconds: Long = 0L,
    val taskId: Long? = null,
    @ColumnInfo(defaultValue = "0") val appExitCount: Int = 0,
    @ColumnInfo(defaultValue = "100") val purityPercent: Int = 100
)

@Entity(tableName = "subject_items", foreignKeys = [ForeignKey(entity = Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.CASCADE)], indices = [Index("subjectId"), Index("type"), Index("targetAt")])
data class SubjectItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val type: String,
    val title: String,
    val details: String = "",
    val targetAt: Long? = null,
    val currentValue: Double = 0.0,
    val targetValue: Double? = null,
    val unit: String = "vezes",
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "mistakes", foreignKeys = [ForeignKey(entity = Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.CASCADE)], indices = [Index("subjectId")])
data class Mistake(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val topic: String,
    val question: String,
    val reason: String,
    val solution: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val nextReviewAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val reviewCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val resolved: Boolean = false
)

@Entity(tableName = "question_logs", foreignKeys = [ForeignKey(entity = Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("subjectId"), Index("loggedAt")])
data class QuestionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long?,
    val topic: String,
    val questionCount: Int,
    val correctCount: Int,
    val loggedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "workout_logs", indices = [Index("startedAt")])
data class WorkoutLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val exercises: String,
    val durationMinutes: Int,
    val notes: String = "",
    val startedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "meal_logs", indices = [Index("loggedAt")])
data class MealLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealType: String,
    val foods: String,
    val calories: Int = 0,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val loggedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "journal_entries", indices = [Index("dayKey")])
data class JournalEntry(
    @PrimaryKey val dayKey: Long,
    val title: String = "",
    val body: String = "",
    val mood: String = "🙂",
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "3") val energy: Int = 3,
    @ColumnInfo(defaultValue = "3") val focus: Int = 3,
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    @ColumnInfo(defaultValue = "''") val promptUsed: String = "",
    val linkedNoteId: Long? = null
)

@Entity(tableName = "calendar_events", indices = [Index("at")])
data class CalendarEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val at: Long)

@Entity(
    tableName = "study_tasks",
    foreignKeys = [ForeignKey(entity = Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("subjectId"), Index("completed"), Index("dueAt"), Index("noteId"), Index("bookId"), Index("previousTaskId")]
)
data class StudyTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val subjectId: Long? = null, val title: String, val dueAt: Long, val completed: Boolean = false, val createdAt: Long = System.currentTimeMillis(), val completedAt: Long? = null, val previousTaskId: Long? = null,
    @ColumnInfo(defaultValue = "4") val priority: Int = 4,
    @ColumnInfo(defaultValue = "'NONE'") val recurrence: String = "NONE",
    val noteId: Long? = null,
    @ColumnInfo(defaultValue = "''") val details: String = "",
    @ColumnInfo(defaultValue = "1") val hasDueDate: Boolean = true,
    @ColumnInfo(defaultValue = "0") val estimatedMinutes: Int = 0,
    @ColumnInfo(defaultValue = "''") val subtasksJson: String = "",
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    val bookId: Long? = null,
    @ColumnInfo(defaultValue = "0") val afterCompletion: Boolean = false
)

@Entity(tableName = "habit_areas")
data class HabitArea(@PrimaryKey val name: String, val sortOrder: Int = 0)

@Entity(tableName = "habits", indices = [Index("area"), Index("createdAt")])
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(defaultValue = "'✨'") val emoji: String = "✨",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'Dia'") val area: String = "Dia",
    @ColumnInfo(defaultValue = "'GOOD'") val kind: String = "GOOD",
    @ColumnInfo(defaultValue = "127") val repeatDaysMask: Int = 127,
    @ColumnInfo(defaultValue = "1.0") val targetValue: Double = 1.0,
    @ColumnInfo(defaultValue = "'vezes'") val targetUnit: String = "vezes",
    @ColumnInfo(defaultValue = "'dia'") val targetPeriod: String = "dia",
    val reminderMinutes: Int? = null,
    @ColumnInfo(defaultValue = "'default'") val iconKey: String = "default",
    @ColumnInfo(defaultValue = "4280454118") val color: Long = 0xFF228BE6L,
    @ColumnInfo(defaultValue = "''") val description: String = "",
    @ColumnInfo(defaultValue = "0") val startedAt: Long = 0L,
    @ColumnInfo(defaultValue = "'ANYTIME'") val timeOfDay: String = "ANYTIME",
    val stackAfterHabitId: Long? = null,
    @ColumnInfo(defaultValue = "0.0") val miniTarget: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val eliteTarget: Double = 0.0,
    @ColumnInfo(defaultValue = "2") val freezeTokens: Int = 2,
    @ColumnInfo(defaultValue = "0") val vacationUntilDay: Long = 0L
)

@Entity(
    tableName = "habit_notes",
    foreignKeys = [ForeignKey(entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("habitId"), Index("dayKey"), Index("createdAt")]
)
data class HabitNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val content: String,
    val dayKey: Long,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val mood: String = ""
)

@Entity(
    tableName = "habit_tasks",
    foreignKeys = [ForeignKey(entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("habitId"), Index("createdAt")]
)
data class HabitTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val title: String,
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "habit_completions", primaryKeys = ["habitId", "dayKey"], foreignKeys = [ForeignKey(entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE)], indices = [Index("dayKey")])
data class HabitCompletion(val habitId: Long, val dayKey: Long, @ColumnInfo(defaultValue = "'DONE'") val status: String = "DONE", @ColumnInfo(defaultValue = "1.0") val value: Double = 1.0, val note: String? = null, @ColumnInfo(defaultValue = "0") val loggedAt: Long = System.currentTimeMillis())

@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val targetAt: Long,
    val completed: Boolean = false,
    @ColumnInfo(defaultValue = "'COUNTDOWN'") val direction: String = "COUNTDOWN",
    @ColumnInfo(defaultValue = "'DAYS'") val unit: String = "DAYS",
    @ColumnInfo(defaultValue = "1") val roundUp: Boolean = true,
    @ColumnInfo(defaultValue = "4284569305") val color: Long = 0xFF6156D9L,
    @ColumnInfo(defaultValue = "'RING'") val style: String = "RING",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'COUNTDOWN'") val metricType: String = "COUNTDOWN",
    @ColumnInfo(defaultValue = "0.0") val currentValue: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val targetValue: Double = 0.0,
    @ColumnInfo(defaultValue = "''") val metricUnit: String = "",
    val subjectId: Long? = null,
    @ColumnInfo(defaultValue = "''") val milestonesJson: String = "",
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val startAt: Long = 0L,
    val bookId: Long? = null,
    @ColumnInfo(defaultValue = "'AUTO'") val displayFormat: String = "AUTO",
    @ColumnInfo(defaultValue = "'DEFAULT'") val preset: String = "DEFAULT"
)

@Entity(
    tableName = "note_snapshots",
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId"), Index("savedAt")]
)
data class NoteSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val title: String,
    val markdown: String,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0L,
    @ColumnInfo(defaultValue = "0") val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "canvas_boards", indices = [Index("updatedAt")])
data class CanvasBoard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val jsonData: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "notes", indices = [Index("trashedAt"), Index("updatedAt")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val markdown: String,
    val updatedAt: Long = System.currentTimeMillis(), @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0, val trashedAt: Long? = null
)

@Entity(tableName = "books", foreignKeys = [ForeignKey(entity = Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("subjectId")])
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val coverUri: String? = null,
    val summary: String = "",
    val currentPage: Int = 0,
    val totalPages: Int? = null,
    val quotes: String = "",
    val status: String = "READING",
    val subjectId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val rating: Int = 0,
    @ColumnInfo(defaultValue = "''") val categoryTag: String = "",
    @ColumnInfo(defaultValue = "''") val currentChapter: String = "",
    val targetFinishAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val highlightsJson: String = "",
    @ColumnInfo(defaultValue = "''") val documentUri: String = "",
    @ColumnInfo(defaultValue = "''") val documentName: String = "",
    @ColumnInfo(defaultValue = "0") val lastReadAt: Long = 0L
)

@Entity(tableName = "book_note_links", primaryKeys = ["bookId", "noteId"], foreignKeys = [
    ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("noteId")])
data class BookNoteLink(val bookId: Long, val noteId: Long)

@Entity(tableName = "card_decks", foreignKeys = [
    ForeignKey(entity = CardDeck::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL)
], indices = [Index(value = ["parentId", "name"], unique = true)])
data class CardDeck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val parentId: Long? = null, val newLimit: Int? = null, val reviewLimit: Int? = null, val dailyGoal: Int? = null,
    @ColumnInfo(defaultValue = "'SM2'") val schedulerMode: String = "SM2",
    @ColumnInfo(defaultValue = "'1 10'") val learningSteps: String = "1 10",
    @ColumnInfo(defaultValue = "'10'") val relearningSteps: String = "10",
    @ColumnInfo(defaultValue = "1") val graduatingInterval: Int = 1,
    @ColumnInfo(defaultValue = "4") val easyInterval: Int = 4,
    @ColumnInfo(defaultValue = "0.9") val desiredRetention: Double = 0.90,
    @ColumnInfo(defaultValue = "1") val burySiblings: Boolean = true,
    @ColumnInfo(defaultValue = "8") val leechThreshold: Int = 8,
    @ColumnInfo(defaultValue = "0") val leechSuspend: Boolean = false,
    @ColumnInfo(defaultValue = "4") val dayStartHour: Int = 4,
    @ColumnInfo(defaultValue = "0") val isFiltered: Boolean = false,
    @ColumnInfo(defaultValue = "''") val filterQuery: String = "",
    @ColumnInfo(defaultValue = "''") val fsrsWeights: String = "",
    @ColumnInfo(defaultValue = "''") val presetName: String = "",
    @ColumnInfo(defaultValue = "0") val easyDaysMask: Int = 0,
    @ColumnInfo(defaultValue = "'MIX'") val displayOrder: String = "MIX",
    @ColumnInfo(defaultValue = "0") val autoAdvanceSec: Int = 0
)

@Entity(
    tableName = "flashcards",
    foreignKeys = [ForeignKey(entity = CardDeck::class, parentColumns = ["id"], childColumns = ["deckId"], onDelete = ForeignKey.SET_NULL), ForeignKey(entity = Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("subjectId"), Index("dueAt"), Index("deckId"), Index(value = ["authorNoteId", "variantKey"], unique = true), Index("suspended"), Index(value = ["suspended", "dueAt"]), Index("sourceNoteId")]
)
data class Flashcard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long? = null,
    val front: String,
    val back: String,
    val dueAt: Long = System.currentTimeMillis(),
    val intervalDays: Int = 0,
    val easeFactor: Double = 2.5,
    val repetitions: Int = 0,
    val authorNoteId: Long? = null,
    val variantKey: String? = null,
    val sourceNoteId: Long? = null,
    val sourceExcerpt: String? = null,
    @ColumnInfo(defaultValue = "0") val suspended: Boolean = false,
    val deckId: Long? = null,
    val learningState: String? = null,
    val lapses: Int = 0,
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    @ColumnInfo(defaultValue = "0") val flag: Int = 0,
    @ColumnInfo(defaultValue = "0") val buriedUntilDay: Long = 0L,
    @ColumnInfo(defaultValue = "0") val learningStep: Int = 0,
    @ColumnInfo(defaultValue = "0.0") val fsrsStability: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val fsrsDifficulty: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val lastReviewAt: Long = 0L,
    @ColumnInfo(defaultValue = "''") val noteTypeKey: String = ""
)

data class GradedCardResult(
    val outcome: ReviewOutcome,
    val priorCard: Flashcard,
    val priorEntry: RoundEntry,
    val buriedSiblingsBefore: List<Pair<Long, Long>>,
    val removedSiblingEntries: List<RoundEntry>
)

@Entity(tableName = "card_reviews", indices = [Index("cardId"), Index("reviewedAt")])
data class CardReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val cardId: Long,
    val reviewedAt: Long, val grade: Int, val intervalDays: Int,
    val durationMs: Long? = null, val priorState: String? = null, val priorIntervalDays: Int? = null,
    val deckAtReview: Long? = null, val subjectAtReview: Long? = null
)

internal fun normalizeCardTags(raw: String): String =
    raw.split(' ', ',', ';')
        .asSequence()
        .map { it.trim().trimStart('#') }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" ")

private fun mergeCardTag(existing: String, tag: String, add: Boolean): String {
    val target = tag.trim().trimStart('#')
    if (target.isEmpty()) return normalizeCardTags(existing)
    val tokens = normalizeCardTags(existing).split(' ').filter { it.isNotEmpty() }.toMutableList()
    if (add) {
        if (tokens.none { it.equals(target, ignoreCase = true) }) tokens.add(target)
    } else {
        tokens.removeAll { it.equals(target, ignoreCase = true) }
    }
    return tokens.joinToString(" ")
}

internal fun resolveNoteTypeKey(note: CardAuthorNote): String =
    if (note.format.equals("CUSTOM", ignoreCase = true) && note.template.isNotBlank()) {
        note.template.trim()
    } else {
        note.format.trim().ifBlank { "BASIC" }
    }

@Dao
interface StudyDao {
    @Query("UPDATE subjects SET nextStep = :text WHERE id = :id") suspend fun setNextStep(id: Long, text: String)
    @Insert suspend fun addMistake(mistake: Mistake)
    @Update suspend fun updateMistake(mistake: Mistake)
    @Delete suspend fun deleteMistake(mistake: Mistake)
    @Query("SELECT * FROM mistakes ORDER BY id DESC") fun mistakes(): Flow<List<Mistake>>

    @Query("SELECT * FROM activity_timers ORDER BY startedAt DESC") fun activityTimers(): Flow<List<ActivityTimer>>
    @Query("SELECT * FROM activity_timers WHERE id = :id AND endedAt IS NULL") suspend fun openTimer(id: Long): ActivityTimer?
    @Query("SELECT count(*) FROM activity_timers WHERE mode = :mode AND endedAt IS NULL") suspend fun activeTimerCount(mode: String): Int
    @Insert suspend fun insertTimer(timer: ActivityTimer)
    @Update suspend fun updateTimer(timer: ActivityTimer)
    @Transaction suspend fun startActivity(mode: String, title: String, bookId: Long?) {
        if (activeTimerCount(mode) > 0) return
        val now = System.currentTimeMillis()
        insertTimer(ActivityTimer(mode = mode, title = title, bookId = bookId, startedAt = now, runningSince = now))
    }
    @Transaction suspend fun setActivityRunning(id: Long, running: Boolean) {
        val timer = openTimer(id) ?: return
        if ((timer.runningSince != null) == running) return
        val now = System.currentTimeMillis()
        updateTimer(timer.copy(elapsedMs = timer.duration(now), runningSince = now.takeIf { running }))
    }
    @Transaction suspend fun recordSet(id: Long, reps: Int, rest: Int) {
        val timer = openTimer(id) ?: return
        if (timer.mode != "Treino" || timer.runningSince == null || reps <= 0 || rest < 0) return
        val line = "${timer.title} · $reps rep · ${rest}s"
        updateTimer(timer.copy(series = listOf(timer.series, line).filter { it.isNotEmpty() }.joinToString("\n"), restUntil = timer.duration(System.currentTimeMillis()) + rest * 1000L))
    }
    @Query(
        "UPDATE books SET currentPage = :page, " +
            "status = CASE WHEN totalPages IS NOT NULL AND totalPages > 0 AND :page >= totalPages THEN 'DONE' ELSE 'READING' END, " +
            "updatedAt = :now " +
            "WHERE id = :id AND currentPage < :page AND (totalPages IS NULL OR totalPages <= 0 OR totalPages >= :page)"
    )
    suspend fun advanceBook(id: Long, page: Int, now: Long): Int
    @Transaction suspend fun finishActivity(id: Long, page: Int?) {
        val timer = openTimer(id) ?: return
        val now = System.currentTimeMillis()
        val duration = timer.duration(now)
        val durationMinutes = (duration / 60_000L).toInt().coerceAtLeast(0)
        if (timer.mode == "Treino") {
            addWorkoutLog(WorkoutLog(title = timer.title, exercises = timer.series.ifEmpty { timer.title }, durationMinutes = durationMinutes, startedAt = timer.startedAt))
        } else if (timer.bookId != null && page != null) {
            val book = bookById(timer.bookId)
            if (book != null && advanceBook(timer.bookId, page, now) > 0) {
                logReading(
                    ReadingLog(
                        title = book.title,
                        pages = page - book.currentPage,
                        loggedAt = timer.startedAt,
                        bookId = book.id,
                        subjectId = book.subjectId,
                        durationMinutes = durationMinutes
                    )
                )
            }
        }
        updateTimer(timer.copy(elapsedMs = duration, runningSince = null, endedAt = now))
    }

    @Query("SELECT * FROM subjects ORDER BY name") fun subjects(): Flow<List<Subject>>
    @Insert suspend fun addSubject(subject: Subject): Long
    @Update suspend fun updateSubject(subject: Subject)
    @Delete suspend fun deleteSubject(subject: Subject)
    @Query("UPDATE subjects SET parentId = NULL WHERE parentId = :parentId") suspend fun unlinkChildSubjects(parentId: Long)
    @Query("DELETE FROM subjects WHERE parentId = :parentId") suspend fun deleteChildSubjects(parentId: Long)
    @Query("SELECT * FROM study_sessions ORDER BY startedAt DESC") fun sessions(): Flow<List<StudySession>>
    @Query("SELECT * FROM study_sessions WHERE isActive = 1 ORDER BY id DESC LIMIT 1") suspend fun activeSession(): StudySession?
    @Query("SELECT * FROM study_sessions WHERE subjectId = :subjectId AND isActive = 1 ORDER BY id DESC LIMIT 1") suspend fun activeSessionForSubject(subjectId: Long): StudySession?
    @Insert suspend fun addSession(session: StudySession): Long
    @Update suspend fun updateSession(session: StudySession)
    @Query("DELETE FROM study_sessions WHERE id = :sessionId") suspend fun deleteSession(sessionId: Long)
    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM study_sessions WHERE startedAt >= :since") fun secondsSince(since: Long): Flow<Long>
    @Query("SELECT * FROM calendar_events ORDER BY at") fun events(): Flow<List<CalendarEvent>>
    @Insert suspend fun addEvent(event: CalendarEvent)
    @Delete suspend fun deleteEvent(event: CalendarEvent)
    @Query("SELECT * FROM study_tasks ORDER BY dueAt, createdAt") fun tasks(): Flow<List<StudyTask>>
    @Insert suspend fun addTask(task: StudyTask)
    @Insert suspend fun addTasks(tasks: List<StudyTask>)
    @Query("SELECT * FROM study_tasks WHERE id = :id") suspend fun taskById(id: Long): StudyTask?
    @Query("SELECT EXISTS(SELECT 1 FROM study_tasks WHERE previousTaskId = :id)") suspend fun hasSuccessor(id: Long): Boolean
    @Transaction suspend fun changeTaskCompletion(id: Long, complete: Boolean) {
        val task = taskById(id) ?: return
        if (task.completed == complete) return
        val now = System.currentTimeMillis()
        setTaskCompleted(id, complete, now)
        if (complete && !hasSuccessor(id)) {
            nextTaskOccurrence(task, now)?.let { due ->
                val preservedRecurrence = if (task.recurrence == "MONTHLY" && !task.afterCompletion && task.hasDueDate) {
                    val anchorDay = Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).toLocalDate().dayOfMonth
                    if (anchorDay >= 29) "MONTHLY:$anchorDay" else task.recurrence
                } else {
                    task.recurrence
                }
                addTask(
                    task.copy(
                        id = 0,
                        previousTaskId = id,
                        dueAt = due,
                        hasDueDate = true,
                        completed = false,
                        completedAt = null,
                        createdAt = now,
                        recurrence = preservedRecurrence,
                        subtasksJson = TaskSubtasks.resetAll(task.subtasksJson)
                    )
                )
            }
        }
    }
    @Query("UPDATE study_tasks SET completed = :completed, completedAt = CASE WHEN :completed THEN COALESCE(completedAt, :now) ELSE NULL END WHERE id = :taskId") suspend fun setTaskCompleted(taskId: Long, completed: Boolean, now: Long = System.currentTimeMillis())
    @Delete suspend fun deleteTask(task: StudyTask)
    @Update suspend fun updateTask(task: StudyTask)
    @Query("UPDATE study_tasks SET noteId = NULL WHERE noteId = :noteId") suspend fun unlinkTasksFromNote(noteId: Long)
    @Query("UPDATE study_tasks SET dueAt = :dueAt, hasDueDate = :hasDueDate WHERE id IN (:ids)") suspend fun updateTasksSchedule(ids: List<Long>, dueAt: Long, hasDueDate: Boolean)
    @Transaction suspend fun rescheduleTasks(ids: List<Long>, dueAt: Long, hasDueDate: Boolean = true) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { batch -> updateTasksSchedule(batch, dueAt, hasDueDate) }
    }
    @Query("UPDATE study_tasks SET dueAt = :dueAt, hasDueDate = 1 WHERE id = :id") suspend fun updateTaskDueAt(id: Long, dueAt: Long)
    @Transaction suspend fun applyRescheduleMap(newDueById: Map<Long, Long>) {
        for ((id, dueAt) in newDueById) {
            updateTaskDueAt(id, dueAt)
        }
    }
    @Query("UPDATE study_tasks SET subtasksJson = :subtasksJson WHERE id = :id") suspend fun updateTaskSubtasks(id: Long, subtasksJson: String)
    @Query("SELECT title FROM books WHERE id = :id") suspend fun bookTitle(id: Long): String?
    @Query("SELECT * FROM books WHERE id = :id") suspend fun bookById(id: Long): Book?
    @Query("UPDATE books SET highlightsJson = :highlightsJson, quotes = :quotes, updatedAt = :now WHERE id = :bookId")
    suspend fun updateBookHighlights(bookId: Long, highlightsJson: String, quotes: String, now: Long = System.currentTimeMillis())
    @Transaction suspend fun advanceBookPage(bookId: Long, newPage: Int, now: Long = System.currentTimeMillis(), durationMinutes: Int = 0): Boolean {
        val book = bookById(bookId) ?: return false
        if (newPage <= book.currentPage) return false
        if (advanceBook(bookId, newPage, now) > 0) {
            logReading(
                ReadingLog(
                    title = book.title,
                    pages = newPage - book.currentPage,
                    loggedAt = now,
                    bookId = book.id,
                    subjectId = book.subjectId,
                    durationMinutes = durationMinutes.coerceAtLeast(0)
                )
            )
            return true
        }
        return false
    }
    @Query("SELECT * FROM subject_items ORDER BY completed, targetAt, createdAt DESC") fun subjectItems(): Flow<List<SubjectItem>>
    @Insert suspend fun addSubjectItem(item: SubjectItem): Long
    @Update suspend fun updateSubjectItem(item: SubjectItem)
    @Delete suspend fun deleteSubjectItem(item: SubjectItem)
    @Query("SELECT * FROM question_logs ORDER BY loggedAt DESC") fun questionLogs(): Flow<List<QuestionLog>>
    @Insert suspend fun addQuestionLog(log: QuestionLog): Long
    @Query("SELECT * FROM workout_logs ORDER BY startedAt DESC") fun workoutLogs(): Flow<List<WorkoutLog>>
    @Insert suspend fun addWorkoutLog(log: WorkoutLog): Long
    @Delete suspend fun deleteWorkoutLog(log: WorkoutLog)
    @Query("SELECT * FROM meal_logs ORDER BY loggedAt DESC") fun mealLogs(): Flow<List<MealLog>>
    @Insert suspend fun addMealLog(log: MealLog): Long
    @Delete suspend fun deleteMealLog(log: MealLog)
    @Query("SELECT * FROM journal_entries ORDER BY dayKey DESC") fun journalEntries(): Flow<List<JournalEntry>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveJournalEntry(entry: JournalEntry)
    @Query("DELETE FROM journal_entries WHERE dayKey = :dayKey") suspend fun deleteJournalEntry(dayKey: Long)
    @Query("SELECT * FROM habits ORDER BY createdAt") fun habits(): Flow<List<Habit>>
    @Query("SELECT * FROM habits WHERE reminderMinutes IS NOT NULL") suspend fun habitsWithReminder(): List<Habit>
    @Update suspend fun updateHabit(habit: Habit)
    @Delete suspend fun deleteHabit(habit: Habit)
    @Insert suspend fun addHabit(habit: Habit): Long
    @Query("SELECT * FROM habit_areas ORDER BY sortOrder, name") fun habitAreas(): Flow<List<HabitArea>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addHabitArea(area: HabitArea)
    @Query("SELECT * FROM habit_completions WHERE dayKey = :dayKey") fun completionsForDay(dayKey: Long): Flow<List<HabitCompletion>>
    @Query("SELECT * FROM habit_completions WHERE dayKey BETWEEN :fromDay AND :toDay") fun habitCompletionsBetween(fromDay: Long, toDay: Long): Flow<List<HabitCompletion>>
    @Query("SELECT * FROM habit_completions ORDER BY dayKey DESC, loggedAt DESC") fun allHabitCompletions(): Flow<List<HabitCompletion>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveHabitCompletion(completion: HabitCompletion)
    @Query("DELETE FROM habit_completions WHERE habitId = :habitId AND dayKey = :dayKey") suspend fun removeHabitCompletion(habitId: Long, dayKey: Long)
    @Query("SELECT * FROM habit_notes ORDER BY dayKey DESC, createdAt DESC") fun allHabitNotes(): Flow<List<HabitNote>>
    @Query("SELECT * FROM habit_notes WHERE habitId = :habitId ORDER BY dayKey DESC, createdAt DESC") fun habitNotes(habitId: Long): Flow<List<HabitNote>>
    @Insert suspend fun addHabitNote(note: HabitNote): Long
    @Update suspend fun updateHabitNote(note: HabitNote)
    @Delete suspend fun deleteHabitNote(note: HabitNote)
    @Query("SELECT * FROM habit_tasks ORDER BY completed, createdAt") fun allHabitTasks(): Flow<List<HabitTask>>
    @Query("SELECT * FROM habit_tasks WHERE habitId = :habitId ORDER BY completed, createdAt") fun habitTasks(habitId: Long): Flow<List<HabitTask>>
    @Insert suspend fun addHabitTask(task: HabitTask): Long
    @Update suspend fun updateHabitTask(task: HabitTask)
    @Query("UPDATE habit_tasks SET completed = :completed WHERE id = :taskId") suspend fun setHabitTaskCompleted(taskId: Long, completed: Boolean)
    @Delete suspend fun deleteHabitTask(task: HabitTask)
        @Query("SELECT * FROM workout_plans ORDER BY updatedAt DESC, id DESC") fun workoutPlans(): Flow<List<WorkoutPlanEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertWorkoutPlan(plan: WorkoutPlanEntity): Long
    @Delete suspend fun deleteWorkoutPlan(plan: WorkoutPlanEntity)
    @Query("SELECT * FROM goals ORDER BY completed, targetAt") fun goals(): Flow<List<Goal>>
    @Insert suspend fun addGoal(goal: Goal)
    @Update suspend fun updateGoal(goal: Goal)
    @Delete suspend fun deleteGoal(goal: Goal)
    @Query("UPDATE goals SET completed = :completed WHERE id = :goalId") suspend fun setGoalCompleted(goalId: Long, completed: Boolean)
    @Query("SELECT * FROM notes WHERE trashedAt IS NULL ORDER BY updatedAt DESC") fun notes(): Flow<List<Note>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceNoteRow(note: Note): Long
    @Delete suspend fun removeNoteRow(note: Note)
    @Query("UPDATE notes SET title = :title, markdown = :markdown, updatedAt = :updatedAt WHERE id = :id") suspend fun updateNoteContent(id: Long, title: String, markdown: String, updatedAt: Long)
    @Query("UPDATE notes SET pinned = :pinned WHERE id = :id") suspend fun setNotePinned(id: Long, pinned: Boolean)
    @Query("SELECT title FROM notes WHERE id = :id AND trashedAt IS NULL") suspend fun noteTitle(id: Long): String?
    @Query("SELECT id FROM notes WHERE title = :title AND trashedAt IS NULL ORDER BY id LIMIT 1") suspend fun noteIdByTitle(title: String): Long?
    @Query("SELECT * FROM notes WHERE id != :id AND trashedAt IS NULL AND (id IN (SELECT sourceId FROM note_links WHERE targetId = :id) OR markdown LIKE '%[[' || :title || ']]%' OR markdown LIKE '%[[' || :title || '|%' OR markdown LIKE '%[[' || :title || '#%')")
    suspend fun notesLinkingTo(id: Long, title: String): List<Note>
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") suspend fun allNotes(): List<Note>
    @Query("SELECT id, title, SUBSTR(markdown, 1, 2048) AS markdown, updatedAt, pinned, revision, trashedAt FROM notes WHERE trashedAt IS NULL ORDER BY updatedAt DESC")
    fun noteHeadersFlow(): Flow<List<Note>>
    @Query("SELECT id, title, trashedAt, SUBSTR(markdown, 1, 2048) AS headMarkdown FROM notes ORDER BY updatedAt DESC")
    suspend fun allNoteLinkHeaders(): List<NoteLinkHeader>
    @Query("SELECT * FROM notes WHERE trashedAt IS NULL AND LOWER(title) = LOWER(:title) ORDER BY id")
    suspend fun activeNotesByTitle(title: String): List<Note>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun findNote(id: Long): Note?
    @Query("SELECT * FROM notes WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC") fun trashedNotes(): Flow<List<Note>>
    @Insert suspend fun insertNoteRow(note: Note): Long
    @Update suspend fun updateNoteRow(note: Note)
    @Query("DELETE FROM notes WHERE id = :id AND trashedAt IS NOT NULL") suspend fun removeTrashedNoteRow(id: Long)
    @Query("DELETE FROM notes WHERE trashedAt IS NOT NULL") suspend fun removeAllTrashedNoteRows()
    @Query("UPDATE flashcards SET sourceNoteId = NULL WHERE sourceNoteId = :noteId") suspend fun clearFlashcardSourceNote(noteId: Long)
    @Query("UPDATE flashcards SET sourceNoteId = NULL WHERE sourceNoteId IS NOT NULL AND sourceNoteId NOT IN (SELECT id FROM notes)") suspend fun clearDanglingFlashcardSourceNotes()
    @Query("UPDATE study_tasks SET noteId = NULL WHERE noteId = :noteId") suspend fun clearTaskNoteRef(noteId: Long)
    @Query("UPDATE study_tasks SET noteId = NULL WHERE noteId IS NOT NULL AND noteId NOT IN (SELECT id FROM notes)") suspend fun clearDanglingTaskNoteRefs()
    @Query("UPDATE journal_entries SET linkedNoteId = NULL WHERE linkedNoteId = :noteId") suspend fun clearJournalNoteRef(noteId: Long)
    @Query("UPDATE journal_entries SET linkedNoteId = NULL WHERE linkedNoteId IS NOT NULL AND linkedNoteId NOT IN (SELECT id FROM notes)") suspend fun clearDanglingJournalNoteRefs()

    @Transaction suspend fun deleteNote(note: Note) {
        clearFlashcardSourceNote(note.id)
        clearTaskNoteRef(note.id)
        clearJournalNoteRef(note.id)
        removeNoteRow(note)
    }

    @Transaction suspend fun purgeTrashedNote(id: Long) {
        removeTrashedNoteRow(id)
        clearDanglingFlashcardSourceNotes()
        clearDanglingTaskNoteRefs()
        clearDanglingJournalNoteRefs()
    }

    @Transaction suspend fun emptyTrashedNotes() {
        removeAllTrashedNoteRows()
        clearDanglingFlashcardSourceNotes()
        clearDanglingTaskNoteRefs()
        clearDanglingJournalNoteRefs()
    }

    @Query("SELECT id, noteId, title, SUBSTR(markdown, 1, 512) AS markdown, revision, savedAt FROM note_snapshots ORDER BY savedAt DESC, id DESC") fun noteSnapshots(): Flow<List<NoteSnapshot>>
    @Query("SELECT * FROM note_snapshots WHERE id = :id") suspend fun noteSnapshotById(id: Long): NoteSnapshot?
    @Query("SELECT * FROM note_snapshots WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC LIMIT 1")
    suspend fun latestNoteSnapshot(noteId: Long): NoteSnapshot?
    @Insert suspend fun insertNoteSnapshot(s: NoteSnapshot): Long
    @Query("DELETE FROM note_snapshots WHERE noteId = :noteId AND id NOT IN (SELECT id FROM note_snapshots WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC LIMIT :keep)")
    suspend fun deleteExcessNoteSnapshots(noteId: Long, keep: Int)
    suspend fun pruneNoteSnapshots(noteId: Long, keep: Int = 25) = deleteExcessNoteSnapshots(noteId, keep)

    @Query("SELECT * FROM canvas_boards ORDER BY updatedAt DESC, id DESC") fun canvasBoards(): Flow<List<CanvasBoard>>
    @Query("SELECT * FROM canvas_boards WHERE id = :id") suspend fun canvasBoardById(id: Long): CanvasBoard?
    @Insert suspend fun insertCanvasBoard(board: CanvasBoard): Long
    @Update suspend fun updateCanvasBoard(board: CanvasBoard): Int
    @Transaction suspend fun upsertCanvasBoard(board: CanvasBoard): Long {
        val now = System.currentTimeMillis()
        val created = if (board.createdAt == 0L) now else board.createdAt
        if (board.id == 0L) {
            val updated = if (board.updatedAt == 0L) now else board.updatedAt
            return insertCanvasBoard(board.copy(createdAt = created, updatedAt = updated))
        }
        val existing = canvasBoardById(board.id)
        if (existing == null) {
            val updated = if (board.updatedAt == 0L) now else board.updatedAt
            return insertCanvasBoard(board.copy(createdAt = created, updatedAt = updated))
        }
        val next = board.copy(
            createdAt = existing.createdAt,
            updatedAt = if (board.updatedAt > existing.updatedAt) board.updatedAt else now
        )
        updateCanvasBoard(next)
        return next.id
    }
    @Query("DELETE FROM canvas_boards WHERE id = :id") suspend fun deleteCanvasBoard(id: Long)

    suspend fun maybeCreateNoteSnapshot(prev: Note?, saved: Note) {
        if (saved.id <= 0L || saved.trashedAt != null) return
        val latest = latestNoteSnapshot(saved.id)
        if (latest != null && latest.title == saved.title && latest.markdown == saved.markdown && latest.revision == saved.revision) {
            return
        }
        if (prev == null && saved.markdown.isBlank()) return
        val now = System.currentTimeMillis()
        if (shouldCreateSnapshot(prev?.title ?: "", prev?.markdown ?: "", saved.title, saved.markdown, latest?.savedAt, now)) {
            insertNoteSnapshot(
                NoteSnapshot(
                    noteId = saved.id,
                    title = saved.title,
                    markdown = saved.markdown,
                    revision = saved.revision,
                    savedAt = now
                )
            )
            pruneNoteSnapshots(saved.id, 25)
        }
    }

    @Transaction suspend fun saveNote(note: Note): Long {
        val prev = if (note.id != 0L) findNote(note.id) else null
        val savedId = if (prev == null) {
            replaceNoteRow(note)
        } else {
            val nextNote = if (note.revision <= prev.revision) note.copy(revision = prev.revision + 1) else note
            updateNoteRow(nextNote)
            nextNote.id
        }
        val saved = findNote(savedId)
        if (saved != null && saved.trashedAt == null) {
            maybeCreateNoteSnapshot(prev, saved)
        }
        return savedId
    }

    @Query("SELECT * FROM note_links") fun noteLinks(): Flow<List<NoteLink>>
    @Query("SELECT * FROM note_links WHERE sourceId = :id") suspend fun linksFromNote(id: Long): List<NoteLink>
    @Query("SELECT * FROM note_links WHERE targetId = :id") suspend fun linksToNote(id: Long): List<NoteLink>
    @Query("DELETE FROM note_links WHERE sourceId = :id") suspend fun clearNoteLinks(id: Long)
    @Insert suspend fun insertNoteLinks(links: List<NoteLink>)
    @Transaction suspend fun replaceNoteLinks(id: Long, links: Map<String, Long>) {
        clearNoteLinks(id)
        if (links.isNotEmpty()) insertNoteLinks(links.map { (reference, target) -> NoteLink(id, reference, target) })
    }
    @Query("SELECT * FROM note_drafts WHERE pending = 1 ORDER BY updatedAt DESC") fun noteDrafts(): Flow<List<NoteDraft>>
    @Query("SELECT * FROM note_drafts WHERE `key` = :key") suspend fun noteDraft(key: String): NoteDraft?
    @Query("SELECT * FROM note_drafts WHERE noteId = :id ORDER BY pending DESC, updatedAt DESC LIMIT 1")
    suspend fun draftForNote(id: Long): NoteDraft?
    @Query("SELECT * FROM note_drafts WHERE pending = 1 ORDER BY updatedAt DESC LIMIT 1") suspend fun latestNoteDraft(): NoteDraft?
    @Insert suspend fun insertNoteDraftRow(draft: NoteDraft)
    @Update suspend fun updateNoteDraftRow(draft: NoteDraft)
    @Query("DELETE FROM note_drafts WHERE `key` = :key AND revision = :revision") suspend fun discardNoteDraft(key: String, revision: Long): Int
    @Query("UPDATE note_drafts SET baseRevision = :next, revision = revision + 1 WHERE noteId = :id AND baseRevision = :previous")
    suspend fun rebaseNoteDrafts(id: Long, previous: Long, next: Long)
    @Query(
        "UPDATE note_drafts SET baseRevision = :newRevision, " +
            "title = CASE WHEN pending = 0 THEN :title ELSE title END, " +
            "markdown = CASE WHEN pending = 0 THEN :markdown ELSE markdown END, " +
            "revision = revision + 1 " +
            "WHERE noteId = :noteId"
    )
    suspend fun rebaseNoteDrafts(noteId: Long, newRevision: Long, title: String, markdown: String)
    @Transaction suspend fun storeNoteDraft(draft: NoteDraft, expectedRevision: Long?): NoteDraft {
        val previous = noteDraft(draft.key)
        if (previous?.revision != expectedRevision) throw DraftConflict()
        val next = draft.copy(revision = (expectedRevision ?: -1L) + 1)
        if (previous == null) insertNoteDraftRow(next) else updateNoteDraftRow(next)
        return next
    }
    @Transaction suspend fun publishDraft(key: String): PublishedNote {
        val existingId = noteDraft(key)?.noteId
        val prev = existingId?.let { findNote(it) }
        val published = publishNoteDraft(this, key)
        if (published.note.trashedAt == null) {
            maybeCreateNoteSnapshot(prev, published.note)
        }
        return published
    }
    @Transaction suspend fun updateNoteContentAndRebase(
        id: Long,
        title: String,
        markdown: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Note? {
        val current = findNote(id) ?: return null
        val nextRevision = current.revision + 1
        val updated = current.copy(title = title, markdown = markdown, updatedAt = updatedAt, revision = nextRevision)
        updateNoteRow(updated)
        rebaseNoteDrafts(id, nextRevision, title, markdown)
        maybeCreateNoteSnapshot(current, updated)
        return updated
    }
    @Transaction suspend fun restoreNoteFromSnapshot(snapshotId: Long): Note? {
        val snap = noteSnapshotById(snapshotId) ?: return null
        val current = findNote(snap.noteId) ?: return null
        val updated = updateNoteContentAndRebase(current.id, snap.title, snap.markdown, System.currentTimeMillis()) ?: return null
        val known = linksFromNote(updated.id).associate { it.reference to it.targetId }
        val bound = NoteReferences.boundReferencesFromHeaders(updated.markdown, allNoteLinkHeaders(), known)
        replaceNoteLinks(updated.id, bound)
        if (current.title != updated.title) {
            rewriteIncomingLinks(this, updated.id, updated.title)
        }
        return updated
    }
    @Transaction suspend fun trashNote(id: Long, expectedRevision: Long) = setNoteTrash(this, id, true, expectedRevision)
    @Transaction suspend fun restoreNote(id: Long) = setNoteTrash(this, id, false)
    @Transaction suspend fun linkMention(sourceId: Long, start: Int, end: Int, targetId: Long, targetTitle: String): Boolean =
        linkUnlinkedMentionInNote(this, sourceId, start, end, targetId, targetTitle)
    @Transaction suspend fun getOrCreateDailyNote(title: String, markdown: String): Long {
        val existingId = noteIdByTitle(title)
        return existingId ?: insertNoteRow(Note(title = title, markdown = markdown))
    }
    @Query("SELECT * FROM books ORDER BY updatedAt DESC") fun books(): Flow<List<Book>>
    @Insert suspend fun addBook(book: Book): Long
    @Update suspend fun updateBook(book: Book)
    @Delete suspend fun removeBookRow(book: Book)
    @Query("UPDATE study_tasks SET bookId = NULL WHERE bookId = :bookId") suspend fun clearTaskBookRef(bookId: Long)
    @Query("UPDATE study_tasks SET bookId = NULL WHERE bookId IS NOT NULL AND bookId NOT IN (SELECT id FROM books)") suspend fun clearDanglingTaskBookRefs()
    @Query("UPDATE reading_logs SET bookId = NULL WHERE bookId = :bookId") suspend fun clearReadingLogBookRef(bookId: Long)
    @Query("UPDATE reading_logs SET bookId = NULL WHERE bookId IS NOT NULL AND bookId NOT IN (SELECT id FROM books)") suspend fun clearDanglingReadingLogBookRefs()
    @Query("UPDATE activity_timers SET bookId = NULL WHERE bookId = :bookId") suspend fun clearActivityTimerBookRef(bookId: Long)
    @Query("UPDATE activity_timers SET bookId = NULL WHERE bookId IS NOT NULL AND bookId NOT IN (SELECT id FROM books)") suspend fun clearDanglingActivityTimerBookRefs()

    @Transaction suspend fun deleteBook(book: Book) {
        clearTaskBookRef(book.id)
        clearReadingLogBookRef(book.id)
        clearActivityTimerBookRef(book.id)
        removeBookRow(book)
    }
    @Query(
        "UPDATE books SET documentUri = :uri, documentName = :name, " +
            "totalPages = CASE WHEN :totalPagesHint IS NOT NULL AND :totalPagesHint > 0 AND (totalPages IS NULL OR totalPages <= 0) THEN :totalPagesHint ELSE totalPages END, " +
            "lastReadAt = :now, updatedAt = :now WHERE id = :bookId"
    )
    suspend fun attachBookDocument(bookId: Long, uri: String, name: String, totalPagesHint: Int?, now: Long)
    @Query("UPDATE books SET status = :status, updatedAt = :now WHERE id = :bookId")
    suspend fun setBookStatus(bookId: Long, status: String, now: Long)
    @Query("UPDATE books SET lastReadAt = :now WHERE id = :bookId")
    suspend fun touchBookReadAt(bookId: Long, now: Long)
    @Query("SELECT * FROM book_note_links") fun bookNoteLinks(): Flow<List<BookNoteLink>>
    @Query("DELETE FROM book_note_links WHERE bookId = :bookId") suspend fun deleteBookNoteLinks(bookId: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addBookNoteLink(link: BookNoteLink)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addBookNoteLinks(links: List<BookNoteLink>)
    @Query("SELECT * FROM reading_logs ORDER BY loggedAt") fun readingLogs(): Flow<List<ReadingLog>>
    @Query("SELECT currentPage FROM books WHERE id = :id") suspend fun previousPage(id: Long): Int?
    @Insert suspend fun logReading(log: ReadingLog)
    @Transaction suspend fun saveBookProgress(book: Book, links: List<Long>): Long {
        val previous = if (book.id != 0L) previousPage(book.id) else null
        val now = System.currentTimeMillis()
        val id = if (book.id == 0L) addBook(book) else book.id.also { updateBook(book.copy(updatedAt = now)) }
        val pagesDelta = when {
            book.status == "WISHLIST" -> 0
            book.id == 0L -> book.currentPage.coerceAtLeast(0)
            previous != null && book.currentPage > previous -> book.currentPage - previous
            else -> 0
        }
        if (pagesDelta > 0) {
            logReading(
                ReadingLog(
                    title = book.title,
                    pages = pagesDelta,
                    loggedAt = now,
                    bookId = id,
                    subjectId = book.subjectId,
                    durationMinutes = 0
                )
            )
        }
        replaceBookNoteLinks(id, links)
        return id
    }
    @Transaction suspend fun replaceBookNoteLinks(bookId: Long, noteIds: List<Long>) {
        deleteBookNoteLinks(bookId)
        if (noteIds.isNotEmpty()) addBookNoteLinks(noteIds.distinct().map { BookNoteLink(bookId, it) })
    }
    @Query("SELECT * FROM flashcards ORDER BY dueAt") fun flashcards(): Flow<List<Flashcard>>
    @Query("SELECT * FROM flashcards WHERE subjectId = :subjectId ORDER BY dueAt") fun flashcardsForSubject(subjectId: Long): Flow<List<Flashcard>>
    @Query("SELECT * FROM flashcards WHERE sourceNoteId = :noteId ORDER BY id") fun flashcardsForNote(noteId: Long): Flow<List<Flashcard>>
    @Query("SELECT * FROM flashcards WHERE sourceNoteId = :noteId AND suspended = 0 ORDER BY dueAt") suspend fun activeFlashcardsForNote(noteId: Long): List<Flashcard>
    @Query("SELECT * FROM card_decks ORDER BY name COLLATE NOCASE") fun cardDecks(): Flow<List<CardDeck>>
    @Query("SELECT * FROM card_decks") suspend fun listDecks(): List<CardDeck>
    @Update suspend fun updateDeck(deck: CardDeck): Int
    @Query("DELETE FROM card_decks WHERE id = :id") suspend fun removeDeckRow(id: Long)
    @Query("UPDATE card_decks SET parentId = :parent WHERE parentId = :id") suspend fun reparentChildren(id: Long, parent: Long?)
    @Query("UPDATE flashcards SET deckId = :parent WHERE deckId = :id") suspend fun moveDeckContents(id: Long, parent: Long?)
    @Query("UPDATE flashcards SET suspended = :value WHERE id = :id") suspend fun setSuspended(id: Long, value: Boolean)
    @Query("SELECT * FROM card_decks WHERE id = :id") suspend fun deckById(id: Long): CardDeck?
    suspend fun cardDecksSnapshot(): List<CardDeck> = listDecks()

    @Transaction suspend fun getOrCreateRootDeck(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Informe o nome do baralho." }
        val current = listDecks().find { it.parentId == null && it.name.equals(trimmed, true) }
        return current?.id ?: insertDeck(CardDeck(name = trimmed))
    }

    @Transaction suspend fun getOrCreateDeckPath(path: String): Long {
        val segments = path.split("::").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return getOrCreateRootDeck("Importado")
        var parentId: Long? = null
        var currentId = 0L
        val all = listDecks().toMutableList()
        for (segment in segments) {
            val found = all.find { it.parentId == parentId && it.name.equals(segment, ignoreCase = true) }
            if (found != null) {
                currentId = found.id
            } else {
                val created = CardDeck(name = segment, parentId = parentId)
                currentId = insertDeck(created)
                all.add(created.copy(id = currentId))
            }
            parentId = currentId
        }
        return currentId
    }

    @Transaction suspend fun saveDeckSettings(input: CardDeck): Long {
        val deck = input.copy(
            name = input.name.trim(),
            learningSteps = input.learningSteps.trim(),
            relearningSteps = input.relearningSteps.trim(),
            schedulerMode = if (input.schedulerMode.equals("FSRS", ignoreCase = true)) "FSRS" else "SM2",
            filterQuery = input.filterQuery.trim()
        )
        require(deck.name.isNotEmpty()) { "Informe o nome do baralho." }
        require(listOfNotNull(deck.newLimit, deck.reviewLimit).all { it >= 0 } && (deck.dailyGoal ?: 1) > 0) { "Confira os limites e a meta." }
        require(deck.graduatingInterval >= 1 && deck.easyInterval >= 1) { "Confira os intervalos de graduação e Fácil." }
        require(deck.desiredRetention in 0.70..0.99) { "A retenção desejada deve ficar entre 70% e 99%." }
        require(deck.leechThreshold >= 1 && deck.dayStartHour in 0..23) { "Confira o limite de sanguessuga e o início do dia." }
        val decks = listDecks()
        require(deck.parentId == null || decks.any { it.id == deck.parentId }) { "O baralho pai não está disponível." }
        require(deck.id == 0L || !DeckTree(decks).contains(deck.id, deck.parentId)) { "Um baralho não pode ficar dentro de si mesmo." }
        require(decks.none { it.id != deck.id && it.parentId == deck.parentId && it.name.equals(deck.name, true) }) { "Já existe um baralho com esse nome neste nível." }
        if (deck.id == 0L) return insertDeck(deck).also { check(it > 0) { "Não foi possível criar o baralho." } }
        val previous = decks.firstOrNull { it.id == deck.id }
        check(updateDeck(deck) == 1) { "Este baralho já foi excluído." }
        if (previous?.newLimit != deck.newLimit ||
            previous?.reviewLimit != deck.reviewLimit ||
            previous?.parentId != deck.parentId ||
            previous?.dayStartHour != deck.dayStartHour
        ) {
            applyRoundLimits()
        }
        return deck.id
    }

    @Transaction suspend fun dissolveDeck(id: Long) {
        val all = listDecks()
        val deck = all.find { it.id == id } ?: return
        val siblings = all.filter { it.id != id && it.parentId == deck.parentId }
        require(all.filter { it.parentId == id }.none { child -> siblings.any { it.name.equals(child.name, true) } }) {
            "Um subbaralho tem o mesmo nome de um baralho no destino. Renomeie-o antes de excluir."
        }
        // Temporarily detach children so a child with its parent's name can take its place.
        val children = all.filter { it.parentId == id }
        moveDeckContents(id, deck.parentId)
        removeDeckRow(id)
        if (children.isNotEmpty()) updateDecks(children.map { it.copy(parentId = deck.parentId) })
        applyRoundLimits()
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDeck(deck: CardDeck): Long
    @Update suspend fun updateDecks(decks: List<CardDeck>)
    @Query("SELECT id FROM card_decks WHERE name = :name") suspend fun deckNamed(name: String): Long
    @Query("SELECT * FROM flashcards WHERE deckId = :id") suspend fun cardsInDeck(id: Long): List<Flashcard>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCardImage(image: CardImage)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCardImages(images: List<CardImage>)
    @Query("SELECT bytes FROM card_images WHERE `key` = :key") suspend fun imageBytes(key: String): ByteArray?
    @Query("SELECT i.bytes FROM card_images i JOIN card_author_notes n ON n.imageKey = i.`key` WHERE n.id = :id")
    suspend fun answerImage(id: Long): ByteArray?
    @Query("DELETE FROM card_images WHERE `key` = :key AND NOT EXISTS (SELECT 1 FROM card_author_notes WHERE imageKey = :key)")
    suspend fun discardUnusedImage(key: String)
    @Query("DELETE FROM card_images WHERE `key` IN (:keys) AND NOT EXISTS (SELECT 1 FROM card_author_notes WHERE imageKey = card_images.`key`)")
    suspend fun discardUnusedImagesChunk(keys: List<String>)
    @Query("DELETE FROM card_author_notes WHERE NOT EXISTS (SELECT 1 FROM flashcards WHERE authorNoteId = card_author_notes.id)")
    suspend fun discardOrphanedAuthorNotes()
    @Query("DELETE FROM card_images WHERE NOT EXISTS (SELECT 1 FROM card_author_notes WHERE imageKey = card_images.`key`)")
    suspend fun discardOrphanedImages()

    @Transaction
    suspend fun cleanOrphanedReferencesAndMedia() {
        discardOrphanedAuthorNotes()
        discardOrphanedImages()
        clearDanglingFlashcardSourceNotes()
        clearDanglingTaskNoteRefs()
        clearDanglingJournalNoteRefs()
        clearDanglingTaskBookRefs()
        clearDanglingReadingLogBookRefs()
        clearDanglingActivityTimerBookRefs()
    }

    @Insert suspend fun addAuthorNote(note: CardAuthorNote): Long
    @Insert suspend fun addAuthorNotes(notes: List<CardAuthorNote>): List<Long>
    @Update suspend fun updateAuthorNote(note: CardAuthorNote)
    @Query("SELECT * FROM card_author_notes WHERE id = :id") suspend fun authorNote(id: Long): CardAuthorNote?
    @Query("SELECT * FROM card_author_notes WHERE id IN (:ids)") suspend fun authorNotesByIdsChunk(ids: List<Long>): List<CardAuthorNote>
    suspend fun authorNotesByIds(ids: List<Long>): List<CardAuthorNote> =
        if (ids.isEmpty()) emptyList() else ids.distinct().chunked(500).flatMap { authorNotesByIdsChunk(it) }
    @Query("SELECT * FROM flashcards WHERE authorNoteId = :id") suspend fun siblingCards(id: Long): List<Flashcard>
    @Query("SELECT * FROM flashcards WHERE authorNoteId IN (:ids)") suspend fun siblingCardsByAuthorIdsChunk(ids: List<Long>): List<Flashcard>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addCardTemplate(template: CardTemplate): Long
    @Query("SELECT * FROM card_templates ORDER BY name COLLATE NOCASE") fun cardTemplates(): Flow<List<CardTemplate>>

    @Transaction suspend fun saveCardNote(originalId: Long?, expected: CardAuthorNote?, note: CardAuthorNote, deckId: Long?, subjectId: Long?, image: CardImage? = null) {
        persistCardNote(originalId, expected, note, deckId, subjectId, image, sourceNoteId = null, sourceExcerpt = null)
    }

    @Transaction suspend fun saveCardNoteWithSource(
        note: CardAuthorNote,
        deckId: Long?,
        subjectId: Long?,
        sourceNoteId: Long?,
        sourceExcerpt: String? = null
    ) {
        persistCardNote(null, null, note, deckId, subjectId, null, sourceNoteId, sourceExcerpt)
    }

    suspend fun persistCardNote(
        originalId: Long?,
        expected: CardAuthorNote?,
        note: CardAuthorNote,
        deckId: Long?,
        subjectId: Long?,
        image: CardImage? = null,
        sourceNoteId: Long? = null,
        sourceExcerpt: String? = null
    ) {
        val cleanTags = normalizeCardTags(note.tags)
        val savedNote = note.copy(tags = cleanTags)
        image?.let {
            require(it.key == savedNote.imageKey)
            CardPictures.validate(it)
            insertCardImage(it)
        }
        val variants = savedNote.variants()
        require(variants.isNotEmpty()) { "Preencha os campos para gerar pelo menos um cartão." }
        val original = originalId?.let { requireNotNull(findCard(it)) { "O cartão já foi excluído." } }
        val sourceId = original?.authorNoteId
        val noteId = if (sourceId == null) {
            require(expected == null) { "O vínculo do cartão mudou. Abra o editor novamente." }
            addAuthorNote(savedNote.copy(id = 0))
        } else {
            check(authorNote(sourceId) == expected) { "A nota foi alterada. Abra o editor novamente." }
            updateAuthorNote(savedNote.copy(id = sourceId))
            sourceId
        }
        val noteTypeKey = resolveNoteTypeKey(savedNote)
        val resolvedSourceNoteId = sourceNoteId ?: original?.sourceNoteId
        val resolvedExcerpt = sourceExcerpt ?: original?.sourceExcerpt
        val siblings = if (sourceId != null) siblingCards(sourceId) else listOfNotNull(original)
        val remaining = variants.toMutableMap()
        val cardsToUpdate = mutableListOf<Flashcard>()
        val cardsToInsert = mutableListOf<Flashcard>()
        for (card in siblings) {
            val key = card.variantKey ?: "front"
            val generated = remaining.remove(key)
            if (generated == null) {
                // Keep the removed variant as an independent card with its review history.
                val detached = expected?.imageKey?.let {
                    addAuthorNote(CardAuthorNote(format = "BASIC", prompt = card.front, answer = card.back, imageKey = it, tags = card.tags))
                }
                cardsToUpdate.add(card.copy(authorNoteId = detached, variantKey = detached?.let { "front" }))
            } else {
                val move = card.id == originalId
                cardsToUpdate.add(
                    card.copy(
                        front = generated.first,
                        back = generated.second,
                        authorNoteId = noteId,
                        variantKey = key,
                        tags = cleanTags,
                        noteTypeKey = noteTypeKey,
                        deckId = if (move) deckId else card.deckId,
                        subjectId = if (move) subjectId else card.subjectId
                    )
                )
            }
        }
        for ((key, generated) in remaining) {
            cardsToInsert.add(
                Flashcard(
                    front = generated.first,
                    back = generated.second,
                    deckId = deckId,
                    subjectId = subjectId,
                    sourceNoteId = resolvedSourceNoteId,
                    sourceExcerpt = resolvedExcerpt,
                    authorNoteId = noteId,
                    variantKey = key,
                    tags = cleanTags,
                    noteTypeKey = noteTypeKey
                )
            )
        }
        if (cardsToUpdate.isNotEmpty()) updateFlashcards(cardsToUpdate)
        if (cardsToInsert.isNotEmpty()) addFlashcards(cardsToInsert)
        expected?.imageKey?.takeIf { it != savedNote.imageKey }?.let { discardUnusedImage(it) }
        if (savedNote.format == "CUSTOM") addCardTemplate(CardTemplate(savedNote.template, savedNote.prompt, savedNote.answer))
        if (original != null && original.deckId != deckId) applyRoundLimits()
    }

    @Query("SELECT * FROM card_author_notes WHERE id IN (SELECT authorNoteId FROM flashcards WHERE deckId = :deckId)")
    suspend fun sourcesInDeck(deckId: Long): List<CardAuthorNote>

    suspend fun collectDescendantDeckIds(rootDeckId: Long): Set<Long> {
        val childrenByParent = listDecks().groupBy { it.parentId }
        val collected = linkedSetOf(rootDeckId)
        val queue = ArrayDeque<Long>()
        queue.addLast(rootDeckId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in childrenByParent[current].orEmpty()) {
                if (collected.add(child.id)) queue.addLast(child.id)
            }
        }
        return collected
    }

    @Query("SELECT * FROM flashcards WHERE deckId IS NULL")
    suspend fun listUnassignedFlashcards(): List<Flashcard>

    @Query("SELECT * FROM flashcards WHERE deckId IN (:deckIds)")
    suspend fun listFlashcardsInDecksChunk(deckIds: List<Long>): List<Flashcard>

    suspend fun listFlashcardsInDecks(deckIds: Collection<Long>): List<Flashcard> =
        if (deckIds.isEmpty()) emptyList() else deckIds.distinct().chunked(500).flatMap { listFlashcardsInDecksChunk(it) }

    @Query("SELECT id, front, back, subjectId, deckId, easeFactor, intervalDays, repetitions, dueAt, suspended, authorNoteId, variantKey, learningState, lapses, tags, flag, buriedUntilDay, noteTypeKey, learningStep, fsrsStability, fsrsDifficulty, lastReviewAt, sourceNoteId, '' AS sourceExcerpt FROM flashcards")
    suspend fun listFlashcardsForQueryValidation(): List<Flashcard>

    suspend fun cardsInDeckScope(deckId: Long): List<Flashcard> = when {
        deckId < 0L -> listFlashcards()
        deckId == 0L -> listUnassignedFlashcards()
        else -> {
            val deckIds = collectDescendantDeckIds(deckId)
            listFlashcardsInDecks(deckIds)
        }
    }

    @Transaction suspend fun sharedNotes(deckId: Long): List<SharedCardNote> {
        val cards = cardsInDeckScope(deckId)
        val result = cards.filter { it.authorNoteId == null }.mapTo(mutableListOf()) {
            val cleanTags = normalizeCardTags(it.tags)
            SharedCardNote(
                source = CardAuthorNote(format = "BASIC", prompt = it.front, answer = it.back, tags = cleanTags),
                keys = setOf("front"),
                tags = cleanTags,
                flag = it.flag
            )
        }
        val grouped = cards.filter { it.authorNoteId != null }.groupBy { it.authorNoteId!! }
        for (note in authorNotesByIds(grouped.keys.toList())) {
            val variantCards = grouped[note.id].orEmpty()
            val variants = variantCards.mapNotNull { it.variantKey }.toSet()
            val mergedTags = normalizeCardTags(note.tags.ifBlank { variantCards.firstOrNull { it.tags.isNotBlank() }?.tags.orEmpty() })
            val mergedFlag = variantCards.maxOfOrNull { it.flag } ?: 0
            result.add(
                SharedCardNote(
                    source = note.contentKey().copy(occlusion = note.occlusion, tags = mergedTags, typeAnswer = note.typeAnswer),
                    keys = variants,
                    tags = mergedTags,
                    typeAnswer = note.typeAnswer,
                    flag = mergedFlag
                )
            )
        }
        return result
    }

    @Transaction suspend fun exportDeckFile(deckId: Long, name: String): DeckFile {
        val notes = sharedNotes(deckId)
        var size = 0L
        val images = notes.mapNotNull { it.source.imageKey }.distinct().associateWith { key ->
            requireNotNull(imageBytes(key)).also {
                size += it.size
                require(size <= DeckFile.MAX_IMAGE_BYTES) { "Baralho grande demais para compartilhar." }
            }
        }
        return DeckFile(name, notes, images)
    }

    @Transaction suspend fun importDeckFile(file: DeckFile): Long {
        require(file.images.values.sumOf { it.size.toLong() } <= DeckFile.MAX_IMAGE_BYTES) { "Baralho grande demais para importar." }
        require(file.notes.mapNotNull { it.source.imageKey }.toSet() == file.images.keys) { "Imagem ausente no baralho." }
        val imageBatch = mutableListOf<CardImage>()
        var imageBatchBytes = 0
        for ((key, bytes) in file.images) {
            val image = CardImage(key, bytes)
            CardPictures.validate(image)
            imageBatch.add(image)
            imageBatchBytes += bytes.size
            if (imageBatch.size >= 50 || imageBatchBytes >= 512 * 1024) {
                insertCardImages(imageBatch)
                imageBatch.clear()
                imageBatchBytes = 0
            }
        }
        if (imageBatch.isNotEmpty()) insertCardImages(imageBatch)
        return importDeck(file.name, file.notes)
    }

    @Transaction suspend fun importDeck(name: String, entries: List<SharedCardNote>): Long {
        val id = getOrCreateRootDeck(name)
        val scopeCards = cardsInDeckScope(id)
        val authorIds = scopeCards.mapNotNull { it.authorNoteId }.distinct()
        val scopeAuthors = authorNotesByIds(authorIds)
        val authorsById = scopeAuthors.associateBy { it.id }
        val existing = mutableMapOf<CardAuthorNote, MutableSet<String>>()
        val groupedByAuthor = LinkedHashMap<Long, MutableList<Flashcard>>()
        for (card in scopeCards) {
            val authorId = card.authorNoteId
            if (authorId == null) {
                val cleanTags = normalizeCardTags(card.tags)
                val key = CardAuthorNote(format = "BASIC", prompt = card.front, answer = card.back, tags = cleanTags).contentKey()
                existing.getOrPut(key) { mutableSetOf() }.add("front")
            } else {
                groupedByAuthor.getOrPut(authorId) { mutableListOf() }.add(card)
            }
        }
        for ((authorId, variantCards) in groupedByAuthor) {
            val note = authorsById[authorId] ?: continue
            val mergedTags = normalizeCardTags(note.tags.ifBlank { variantCards.firstOrNull { it.tags.isNotBlank() }?.tags.orEmpty() })
            val key = note.contentKey().copy(occlusion = note.occlusion, tags = mergedTags, typeAnswer = note.typeAnswer).contentKey()
            val bucket = existing.getOrPut(key) { mutableSetOf() }
            for (c in variantCards) c.variantKey?.let(bucket::add)
        }
        val sources = scopeAuthors.groupBy { it.contentKey() }
            .mapValues { (_, notes) -> notes.map { it.id }.toMutableList() }.toMutableMap()
        val siblingsByAuthor = mutableMapOf<Long, MutableSet<String?>>()
        if (authorIds.isNotEmpty()) {
            for (chunk in authorIds.chunked(500)) {
                for (card in siblingCardsByAuthorIdsChunk(chunk)) {
                    card.authorNoteId?.let { siblingsByAuthor.getOrPut(it) { mutableSetOf() }.add(card.variantKey) }
                }
            }
        }
        val flushBatch = mutableListOf<Flashcard>()
        val addedTemplates = mutableSetOf<String>()
        for (entry in entries) {
            val mergedTags = normalizeCardTags(entry.tags.ifBlank { entry.source.tags })
            val mergedTypeAnswer = entry.typeAnswer || entry.source.typeAnswer
            val mergedFlag = entry.flag.coerceIn(0, 7)
            val source = entry.source.contentKey().copy(tags = mergedTags, typeAnswer = mergedTypeAnswer)
            val variants = source.variants()
            require(entry.keys.isNotEmpty() && variants.keys.containsAll(entry.keys)) { "Modelo ou campos inválidos no baralho." }
            val seen = existing.getOrPut(source) { mutableSetOf() }
            val missing = entry.keys - seen
            if (missing.isEmpty()) continue
            val noteTypeKey = resolveNoteTypeKey(source)
            // Join complementary variants without moving a sibling already filed in another deck.
            val candidates = sources.getOrPut(source) { mutableListOf() }
            val noteId = candidates.firstOrNull { candidate ->
                siblingsByAuthor[candidate].orEmpty().none { it in missing }
            } ?: addAuthorNote(source).also { candidates.add(it) }
            val knownVariants = siblingsByAuthor.getOrPut(noteId) { mutableSetOf() }
            for (key in missing) {
                val (front, back) = variants.getValue(key)
                flushBatch.add(
                    Flashcard(
                        deckId = id,
                        front = front,
                        back = back,
                        authorNoteId = noteId,
                        variantKey = key,
                        tags = mergedTags,
                        flag = mergedFlag,
                        noteTypeKey = noteTypeKey
                    )
                )
                knownVariants.add(key)
                if (flushBatch.size >= 500) {
                    addFlashcards(flushBatch)
                    flushBatch.clear()
                }
            }
            if (source.format == "CUSTOM" && addedTemplates.add(source.template)) {
                addCardTemplate(CardTemplate(source.template, source.prompt, source.answer))
            }
            seen.addAll(missing)
        }
        if (flushBatch.isNotEmpty()) addFlashcards(flushBatch)
        return id
    }

    @Transaction suspend fun importApkgPreparedDeck(
        deckName: String,
        images: List<CardImage>,
        cards: List<Pair<CardAuthorNote?, Flashcard>>
    ): Long {
        val deckId = getOrCreateDeckPath(deckName)
        val imageBatch = mutableListOf<CardImage>()
        var imageBatchBytes = 0
        for (img in images) {
            CardPictures.validate(img)
            imageBatch.add(img)
            imageBatchBytes += img.bytes.size
            if (imageBatch.size >= 50 || imageBatchBytes >= 512 * 1024) {
                insertCardImages(imageBatch)
                imageBatch.clear()
                imageBatchBytes = 0
            }
        }
        if (imageBatch.isNotEmpty()) insertCardImages(imageBatch)
        val existingSignatures = cardsInDeck(deckId).mapTo(HashSet()) {
            Triple(it.front.trim(), it.back.trim(), it.variantKey ?: "front")
        }
        val incomingCards = cards.filter { (_, card) ->
            val sig = Triple(card.front.trim(), card.back.trim(), card.variantKey ?: "front")
            existingSignatures.add(sig)
        }
        val authorNotes = incomingCards.mapNotNull { it.first?.copy(id = 0) }
        val insertedAuthorIds = if (authorNotes.isEmpty()) {
            emptyList()
        } else {
            authorNotes.chunked(500).flatMap { addAuthorNotes(it) }
        }
        var authorCursor = 0
        val flushBatch = mutableListOf<Flashcard>()
        for ((author, card) in incomingCards) {
            val resolvedAuthorId = if (author != null) insertedAuthorIds[authorCursor++] else null
            flushBatch.add(
                card.copy(
                    deckId = deckId,
                    authorNoteId = resolvedAuthorId,
                    variantKey = if (resolvedAuthorId != null) (card.variantKey ?: "front") else null
                )
            )
            if (flushBatch.size >= 500) {
                addFlashcards(flushBatch)
                flushBatch.clear()
            }
        }
        if (flushBatch.isNotEmpty()) addFlashcards(flushBatch)
        return deckId
    }

    @Insert suspend fun addFlashcards(cards: List<Flashcard>)
    @Insert suspend fun addFlashcard(card: Flashcard): Long
    @Update suspend fun updateFlashcard(card: Flashcard)
    @Update suspend fun updateFlashcards(cards: List<Flashcard>)
    @Query("UPDATE flashcards SET front = :front, back = :back, subjectId = :subjectId, deckId = :deckId WHERE id = :id")
    suspend fun editFlashcard(id: Long, subjectId: Long?, deckId: Long?, front: String, back: String)
    @Query("SELECT * FROM flashcards WHERE id = :id") suspend fun findCard(id: Long): Flashcard?
    @Query("SELECT * FROM flashcards WHERE id IN (:ids)") suspend fun findCardsByIdsChunk(ids: List<Long>): List<Flashcard>
    suspend fun findCardsByIds(ids: List<Long>): List<Flashcard> =
        if (ids.isEmpty()) emptyList() else ids.distinct().chunked(500).flatMap { findCardsByIdsChunk(it) }

    @Query("UPDATE flashcards SET deckId = :deckId WHERE id IN (:ids)") suspend fun moveCardsToDeckChunk(ids: List<Long>, deckId: Long?)
    @Transaction suspend fun moveCardsToDeck(ids: List<Long>, deckId: Long?) {
        if (ids.isEmpty()) return
        for (chunk in ids.distinct().chunked(500)) moveCardsToDeckChunk(chunk, deckId)
        applyRoundLimits()
    }

    @Query("UPDATE flashcards SET suspended = :suspended WHERE id IN (:ids)") suspend fun setCardsSuspendedChunk(ids: List<Long>, suspended: Boolean)
    @Query("DELETE FROM review_round WHERE cardId IN (:ids) AND done = 0") suspend fun discardPendingEntriesChunk(ids: List<Long>)
    @Transaction suspend fun setCardsSuspended(ids: List<Long>, suspended: Boolean) {
        if (ids.isEmpty()) return
        for (chunk in ids.distinct().chunked(500)) {
            setCardsSuspendedChunk(chunk, suspended)
            if (suspended) discardPendingEntriesChunk(chunk)
        }
    }

    @Query("UPDATE flashcards SET flag = :flag WHERE id IN (:ids)") suspend fun setCardsFlagChunk(ids: List<Long>, flag: Int)
    @Transaction suspend fun setCardsFlag(ids: List<Long>, flag: Int) {
        if (ids.isEmpty()) return
        val normalizedFlag = flag.coerceIn(0, 7)
        for (chunk in ids.distinct().chunked(500)) setCardsFlagChunk(chunk, normalizedFlag)
    }

    @Query("UPDATE flashcards SET buriedUntilDay = :untilDay WHERE id IN (:ids)") suspend fun buryCardsUntilChunk(ids: List<Long>, untilDay: Long)
    @Transaction suspend fun buryCardsUntil(ids: List<Long>, untilDay: Long) {
        if (ids.isEmpty()) return
        for (chunk in ids.distinct().chunked(500)) buryCardsUntilChunk(chunk, untilDay)
    }

    @Query("UPDATE flashcards SET dueAt = :dueAt, intervalDays = :intervalDays, learningState = NULL, learningStep = 0 WHERE id IN (:ids)")
    suspend fun rescheduleCardsDueChunk(ids: List<Long>, dueAt: Long, intervalDays: Int)
    @Transaction suspend fun rescheduleCardsDue(ids: List<Long>, dueAt: Long, intervalDays: Int) {
        if (ids.isEmpty()) return
        val safeDays = intervalDays.coerceAtLeast(0)
        for (chunk in ids.distinct().chunked(500)) {
            rescheduleCardsDueChunk(chunk, dueAt, safeDays)
            if (safeDays > 0) discardPendingEntriesChunk(chunk)
        }
    }

    @Query("UPDATE flashcards SET easeFactor = 2.5, intervalDays = 0, repetitions = 0, dueAt = 0, lapses = 0, learningState = NULL, learningStep = 0, fsrsStability = 0.0, fsrsDifficulty = 0.0, lastReviewAt = 0, buriedUntilDay = 0 WHERE id IN (:ids)")
    suspend fun resetCardsProgressChunk(ids: List<Long>)
    @Transaction suspend fun resetCardsProgress(ids: List<Long>) {
        if (ids.isEmpty()) return
        for (chunk in ids.distinct().chunked(500)) {
            resetCardsProgressChunk(chunk)
            discardPendingEntriesChunk(chunk)
        }
    }

    @Query("DELETE FROM flashcards WHERE id IN (:ids)") suspend fun deleteCardsChunk(ids: List<Long>)
    @Query("DELETE FROM card_reviews WHERE cardId IN (:ids)") suspend fun deleteReviewsForCardsChunk(ids: List<Long>)
    @Query("DELETE FROM card_reviews WHERE cardId = :cardId") suspend fun deleteReviewsForCard(cardId: Long)
    @Transaction suspend fun deleteCardsByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val uniqueIds = ids.distinct()
        val cards = findCardsByIds(uniqueIds)
        val authorIds = cards.mapNotNull { it.authorNoteId }.distinct()
        val authors = authorNotesByIds(authorIds)
        val imageKeys = authors.mapNotNull { it.imageKey }.distinct()
        for (chunk in uniqueIds.chunked(500)) {
            deleteReviewsForCardsChunk(chunk)
            deleteCardsChunk(chunk)
        }
        for (chunk in authorIds.chunked(500)) discardUnusedAuthorsChunk(chunk)
        for (chunk in imageKeys.chunked(500)) discardUnusedImagesChunk(chunk)
    }

    @Transaction suspend fun updateCardsTag(ids: List<Long>, tag: String, add: Boolean) {
        if (ids.isEmpty() || tag.isBlank()) return
        val updated = findCardsByIds(ids).map { it.copy(tags = mergeCardTag(it.tags, tag, add)) }
        if (updated.isNotEmpty()) updateFlashcards(updated)
    }

    @Transaction suspend fun saveQuickCardEdit(card: Flashcard, front: String, back: String, tags: String) {
        val cleanTags = normalizeCardTags(tags)
        val cleanFront = front.trim()
        val cleanBack = back.trim()
        val stored = findCard(card.id) ?: return
        updateFlashcard(stored.copy(front = cleanFront, back = cleanBack, tags = cleanTags))
        val authorId = stored.authorNoteId ?: return
        val author = authorNote(authorId) ?: return
        val updatedAuthor = when {
            author.format.equals("BASIC", ignoreCase = true) ->
                author.copy(prompt = cleanFront, answer = cleanBack, tags = cleanTags)
            author.format.equals("REVERSE", ignoreCase = true) && stored.variantKey == "reverse" ->
                author.copy(prompt = cleanBack, answer = cleanFront, tags = cleanTags)
            author.format.equals("REVERSE", ignoreCase = true) ->
                author.copy(prompt = cleanFront, answer = cleanBack, tags = cleanTags)
            else -> author.copy(tags = cleanTags)
        }
        updateAuthorNote(updatedAuthor)
    }

    @Query("UPDATE flashcards SET flag = :flag WHERE id = :cardId") suspend fun setCardFlag(cardId: Long, flag: Int)
    @Query("UPDATE flashcards SET buriedUntilDay = :untilDay WHERE id = :cardId") suspend fun setCardBuriedUntil(cardId: Long, untilDay: Long)
    @Transaction suspend fun buryCardAndDiscardRound(cardId: Long, untilDay: Long) {
        buryCardsUntil(listOf(cardId), untilDay)
        discardPendingEntry(cardId)
    }

    @Query("""SELECT f.*, r.cardId AS round_cardId, r.token AS round_token, r.position AS round_position,
        r.done AS round_done, r.elapsedMs AS round_elapsedMs, r.cram AS round_cram,
        n.occlusion AS occlusion
        FROM review_round r
        JOIN flashcards f ON f.id = r.cardId
        LEFT JOIN card_author_notes n ON n.id = f.authorNoteId
        ORDER BY r.position""")
    fun reviewRound(): Flow<List<RoundCard>>
    @Query("DELETE FROM review_round") suspend fun clearReviewRound()
    @Insert suspend fun insertRoundEntries(entries: List<RoundEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRoundEntry(entry: RoundEntry)
    @Query("SELECT * FROM review_round WHERE cardId = :id AND token = :token AND done = 0")
    suspend fun roundEntry(id: Long, token: String): RoundEntry?
    @Query("SELECT * FROM review_round WHERE cardId IN (:ids) AND token = :token AND done = 0")
    suspend fun pendingRoundEntriesForCards(ids: List<Long>, token: String): List<RoundEntry>
    @Query("DELETE FROM review_round WHERE cardId IN (:ids) AND token = :token AND done = 0")
    suspend fun discardPendingEntriesForCards(ids: List<Long>, token: String)
    @Query("UPDATE review_round SET elapsedMs = elapsedMs + :duration WHERE cardId = :id AND token = :token AND done = 0 AND EXISTS (SELECT 1 FROM flashcards WHERE id = :id AND dueAt = :dueAt)")
    suspend fun addRoundTime(id: Long, token: String, dueAt: Long, duration: Long)
    @Update suspend fun updateRoundEntry(entry: RoundEntry)
    @Query("SELECT MAX(position) FROM review_round") suspend fun maxRoundPosition(): Int?
    @Query("SELECT * FROM flashcards") suspend fun listFlashcards(): List<Flashcard>
    @Query("SELECT * FROM card_reviews WHERE reviewedAt >= :start AND reviewedAt < :end ORDER BY reviewedAt, id")
    suspend fun reviewsBetween(start: Long, end: Long): List<CardReview>
    @Query("DELETE FROM card_reviews WHERE id = (SELECT id FROM card_reviews WHERE cardId = :cardId AND reviewedAt = :reviewedAt ORDER BY id DESC LIMIT 1)")
    suspend fun deleteCardReview(cardId: Long, reviewedAt: Long)
    @Query("SELECT cardId FROM review_round WHERE done = 0 AND cram = 0") suspend fun pendingScheduledRoundIds(): List<Long>
    @Query("DELETE FROM review_round WHERE cardId = :id AND done = 0") suspend fun discardPendingEntry(id: Long)
    @Query("SELECT EXISTS(SELECT 1 FROM review_round r JOIN flashcards f ON f.id = r.cardId WHERE r.done = 0 AND f.suspended = 0)")
    suspend fun hasOpenRound(): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM review_round WHERE cram = 0 AND done = 0)")
    fun observeHasOpenScheduledRound(): Flow<Boolean>
    @Query("SELECT * FROM card_reviews WHERE reviewedAt >= :sinceMillis AND (priorState IS NULL OR UPPER(priorState) != 'CRAM') ORDER BY reviewedAt DESC")
    suspend fun listRecentScheduledReviewsSince(sinceMillis: Long): List<CardReview>
    @Query("SELECT reviewedAt FROM card_reviews WHERE (priorState IS NULL OR UPPER(priorState) != 'CRAM') ORDER BY reviewedAt DESC")
    suspend fun listScheduledReviewTimestamps(): List<Long>

    suspend fun availableInRound(candidates: Set<Long>, now: Long, cutoff: Long): List<Flashcard> {
        if (candidates.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayReviews = reviewsBetween(midnight, tomorrow)
        // Only legacy events need older answers to distinguish a first introduction.
        val hasLegacy = todayReviews.any { it.priorState == null }
        val reviews = if (hasLegacy) reviewsBetween(0, tomorrow) else todayReviews
        val cards = if (hasLegacy) listFlashcards() else findCardsByIds(candidates.toList())
        return DeckStudyIndex(listDecks(), cards, reviews, today, zone, candidates).due(cutoff)
    }

    @Transaction suspend fun applyRoundLimits() {
        val pending = pendingScheduledRoundIds().toSet()
        if (pending.isEmpty()) return
        // Keep intraday repetitions that already belong to the round but are not due yet.
        val allowed = availableInRound(pending, System.currentTimeMillis(), Long.MAX_VALUE).mapTo(mutableSetOf()) { it.id }
        val toRemove = (pending - allowed).toList()
        if (toRemove.isNotEmpty()) {
            for (chunk in toRemove.chunked(500)) discardPendingEntriesChunk(chunk)
        }
    }

    @Transaction suspend fun startRound(ids: List<Long>) {
        if (hasOpenRound()) return
        val now = System.currentTimeMillis()
        val cards = availableInRound(ids.toSet(), now, now)
        check(cards.isNotEmpty()) { "A fila mudou. Confira os cartões disponíveis." }
        val token = java.util.UUID.randomUUID().toString()
        clearReviewRound()
        insertRoundEntries(cards.mapIndexed { position, card -> RoundEntry(card.id, token, position, cram = false) })
    }

    @Transaction suspend fun startCramRound(ids: List<Long>) {
        val cardsById = findCardsByIds(ids).filterNot { it.suspended }.associateBy { it.id }
        val cards = ids.distinct().mapNotNull { cardsById[it] }
        check(cards.isNotEmpty()) { "Nenhum cartão ativo disponível para revisar." }
        val token = java.util.UUID.randomUUID().toString()
        clearReviewRound()
        insertRoundEntries(cards.mapIndexed { position, card -> RoundEntry(card.id, token, position, cram = true) })
    }

    @Transaction suspend fun gradeCardPro(
        card: Flashcard,
        token: String,
        grade: Int,
        now: Long,
        durationMs: Long,
        allowEarly: Boolean = false
    ): GradedCardResult? {
        require(grade in 0..3)
        val entry = roundEntry(card.id, token) ?: return null
        val stored = findCard(card.id) ?: return null
        if (stored.suspended || stored.dueAt != card.dueAt) return null
        if (!entry.cram && !allowEarly && stored.dueAt > now) return null
        val totalDuration = entry.elapsedMs + durationMs.coerceAtLeast(0)
        val deck = stored.deckId?.let { deckById(it) }
        val zone = ZoneId.systemDefault()
        val outcome = stored.afterGradePro(
            grade = grade,
            now = now,
            config = deck,
            cram = entry.cram,
            durationMs = totalDuration,
            zone = zone
        )
        if (!entry.cram) {
            updateFlashcard(outcome.card)
        }
        addCardReview(outcome.review)
        val nextPosition = if (grade == 0 && entry.cram) (maxRoundPosition() ?: entry.position) + 1 else entry.position
        updateRoundEntry(entry.copy(done = grade != 0, elapsedMs = 0, position = nextPosition))

        val buriedSiblingsBefore = mutableListOf<Pair<Long, Long>>()
        val removedSiblingEntries = mutableListOf<RoundEntry>()
        if (!entry.cram && (deck?.burySiblings != false) && stored.authorNoteId != null) {
            val untilDay = ankiLogicalDay(now, deck?.dayStartHour ?: 4, zone).toEpochDay()
            val siblings = siblingCards(stored.authorNoteId).filter {
                it.id != stored.id && !it.suspended && it.buriedUntilDay < untilDay
            }
            if (siblings.isNotEmpty()) {
                val siblingIds = siblings.map { it.id }
                for (sibling in siblings) buriedSiblingsBefore.add(sibling.id to sibling.buriedUntilDay)
                buryCardsUntil(siblingIds, untilDay)
                for (chunk in siblingIds.chunked(500)) {
                    removedSiblingEntries.addAll(pendingRoundEntriesForCards(chunk, token))
                    discardPendingEntriesForCards(chunk, token)
                }
            }
        }
        return GradedCardResult(
            outcome = outcome,
            priorCard = stored,
            priorEntry = entry,
            buriedSiblingsBefore = buriedSiblingsBefore,
            removedSiblingEntries = removedSiblingEntries
        )
    }

    @Transaction suspend fun revertLastReview(
        priorCard: Flashcard,
        priorEntry: RoundEntry,
        reviewReviewedAt: Long,
        buriedSiblingsBefore: List<Pair<Long, Long>>,
        removedSiblingEntries: List<RoundEntry>
    ) {
        updateFlashcard(priorCard)
        upsertRoundEntry(priorEntry)
        deleteCardReview(priorCard.id, reviewReviewedAt)
        for ((siblingId, prevUntilDay) in buriedSiblingsBefore) {
            setCardBuriedUntil(siblingId, prevUntilDay)
        }
        if (removedSiblingEntries.isNotEmpty()) {
            insertRoundEntries(removedSiblingEntries)
        }
    }
    @Delete suspend fun removeFlashcardRow(card: Flashcard)
    @Query("DELETE FROM card_author_notes WHERE id = :id AND NOT EXISTS (SELECT 1 FROM flashcards WHERE authorNoteId = :id)")
    suspend fun discardUnusedAuthor(id: Long)
    @Query("DELETE FROM card_author_notes WHERE id IN (:ids) AND NOT EXISTS (SELECT 1 FROM flashcards WHERE authorNoteId = card_author_notes.id)")
    suspend fun discardUnusedAuthorsChunk(ids: List<Long>)

    @Transaction suspend fun deleteFlashcard(card: Flashcard) {
        val current = findCard(card.id) ?: return
        val author = current.authorNoteId?.let { authorNote(it) }
        removeFlashcardRow(current)
        if (author != null) {
            discardUnusedAuthor(author.id)
            author.imageKey?.let { discardUnusedImage(it) }
        }
    }

    @Transaction suspend fun deleteCardById(id: Long) {
        findCard(id)?.let { deleteFlashcard(it) }
    }
    @Insert suspend fun addCardReview(review: CardReview)
    @Query("SELECT * FROM card_reviews ORDER BY reviewedAt DESC") fun cardReviews(): Flow<List<CardReview>>
    @Query("SELECT * FROM card_reviews WHERE cardId = :cardId ORDER BY reviewedAt DESC")
    suspend fun reviewsForCard(cardId: Long): List<CardReview>

    @Query("SELECT * FROM study_sessions WHERE startedAt >= :sinceMillis ORDER BY startedAt DESC")
    fun recentSessions(sinceMillis: Long): Flow<List<StudySession>>

    @Query("SELECT * FROM card_reviews WHERE reviewedAt >= :sinceMillis ORDER BY reviewedAt DESC")
    fun recentCardReviews(sinceMillis: Long): Flow<List<CardReview>>

    @Query("SELECT * FROM question_logs WHERE loggedAt >= :sinceMillis ORDER BY loggedAt DESC")
    fun recentQuestionLogs(sinceMillis: Long): Flow<List<QuestionLog>>

    @Query("SELECT * FROM reading_logs WHERE loggedAt >= :sinceMillis ORDER BY loggedAt DESC")
    fun recentReadingLogs(sinceMillis: Long): Flow<List<ReadingLog>>

    @Query("SELECT * FROM habit_completions WHERE dayKey >= :sinceDayKey ORDER BY dayKey DESC, loggedAt DESC")
    fun recentHabitCompletions(sinceDayKey: Long): Flow<List<HabitCompletion>>

    @Query("SELECT COUNT(*) FROM flashcards WHERE suspended = 0 AND dueAt <= :nowMillis")
    fun dueFlashcardsCount(nowMillis: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM goals WHERE completed = 0")
    fun activeGoalsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notes WHERE trashedAt IS NULL")
    fun activeNotesCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM books")
    fun booksCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM study_sessions")
    fun sessionsCount(): Flow<Int>

    @Query("SELECT * FROM journal_entries WHERE dayKey >= :sinceDayKey ORDER BY dayKey DESC")
    fun recentJournalEntries(sinceDayKey: Long): Flow<List<JournalEntry>>

    @Query("SELECT * FROM calendar_events WHERE at >= :sinceMillis ORDER BY at")
    fun upcomingEvents(sinceMillis: Long): Flow<List<CalendarEvent>>

    @Query("SELECT * FROM study_tasks WHERE completed = 0 ORDER BY dueAt, createdAt")
    fun openTasks(): Flow<List<StudyTask>>
}

@Entity(tableName = "workout_plans")
data class WorkoutPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "''") val movesJson: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis()
)

@Database(entities = [NoteDraft::class, NoteLink::class, NoteSnapshot::class, CanvasBoard::class, CardImage::class, RoundEntry::class, CardAuthorNote::class, CardTemplate::class, CardDeck::class, Mistake::class, ActivityTimer::class, ReadingLog::class, Subject::class, StudySession::class, SubjectItem::class, QuestionLog::class, WorkoutLog::class, MealLog::class, JournalEntry::class, CalendarEvent::class, StudyTask::class, HabitArea::class, Habit::class, HabitCompletion::class, Goal::class, Note::class, Book::class, BookNoteLink::class, Flashcard::class, CardReview::class, HabitNote::class, HabitTask::class, WorkoutPlanEntity::class], version = 32, exportSchema = true)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao
    companion object {
        @Volatile private var instance: StudyDatabase? = null
        private val _databaseEpoch = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val databaseEpoch: kotlinx.coroutines.flow.StateFlow<Long> = _databaseEpoch

        /** Fecha o banco e esquece a instância (usado ao restaurar um backup). */
        fun closeAndReset() {
            synchronized(this) {
                try {
                    instance?.close()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    StudyLog.w("StudyDatabase", "Erro ao fechar instância do banco", e)
                } finally {
                    instance = null
                    _databaseEpoch.value = _databaseEpoch.value + 1L
                }
            }
        }
        fun get(context: Context): StudyDatabase = instance ?: synchronized(this) {
            instance ?: openNamed(context, "studydock.db").also {
                instance = it
                _databaseEpoch.value = _databaseEpoch.value + 1L
            }
        }
        internal fun openNamed(context: Context, name: String): StudyDatabase =
            Room.databaseBuilder(context.applicationContext, StudyDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, REVIEW_MIGRATION_18_19, CARD_IMAGE_MIGRATION_19_20, NOTES_MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32).build()
        internal val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                fun addColIfMissing(table: String, col: String, ddl: String) {
                    val exists = db.query("PRAGMA table_info(`$table`)").use { c ->
                        val idx = c.getColumnIndex("name")
                        var found = false
                        while (c.moveToNext()) {
                            if (idx >= 0 && c.getString(idx) == col) { found = true; break }
                        }
                        found
                    }
                    if (!exists) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$col` $ddl")
                }
                addColIfMissing("study_sessions", "appExitCount", "INTEGER NOT NULL DEFAULT 0")
                addColIfMissing("study_sessions", "purityPercent", "INTEGER NOT NULL DEFAULT 100")
                addColIfMissing("habits", "timeOfDay", "TEXT NOT NULL DEFAULT 'ANYTIME'")
                addColIfMissing("habits", "stackAfterHabitId", "INTEGER DEFAULT NULL")
                addColIfMissing("habits", "miniTarget", "REAL NOT NULL DEFAULT 0.0")
                addColIfMissing("habits", "eliteTarget", "REAL NOT NULL DEFAULT 0.0")
                addColIfMissing("habits", "freezeTokens", "INTEGER NOT NULL DEFAULT 2")
                addColIfMissing("habits", "vacationUntilDay", "INTEGER NOT NULL DEFAULT 0")
                addColIfMissing("goals", "startAt", "INTEGER NOT NULL DEFAULT 0")
                addColIfMissing("goals", "bookId", "INTEGER DEFAULT NULL")
                addColIfMissing("goals", "displayFormat", "TEXT NOT NULL DEFAULT 'AUTO'")
                addColIfMissing("goals", "preset", "TEXT NOT NULL DEFAULT 'DEFAULT'")
                addColIfMissing("card_decks", "fsrsWeights", "TEXT NOT NULL DEFAULT ''")
                addColIfMissing("card_decks", "presetName", "TEXT NOT NULL DEFAULT ''")
                addColIfMissing("card_decks", "easyDaysMask", "INTEGER NOT NULL DEFAULT 0")
                addColIfMissing("card_decks", "displayOrder", "TEXT NOT NULL DEFAULT 'MIX'")
                addColIfMissing("card_decks", "autoAdvanceSec", "INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `workout_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `movesJson` TEXT NOT NULL DEFAULT '', `updatedAt` INTEGER NOT NULL DEFAULT 0)")
            }
        }
        internal val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val hasParentId = db.query("PRAGMA table_info(`subjects`)").use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    var found = false
                    while (cursor.moveToNext()) {
                        if (nameIdx >= 0 && cursor.getString(nameIdx) == "parentId") {
                            found = true
                            break
                        }
                    }
                    found
                }
                if (!hasParentId) {
                    db.execSQL("ALTER TABLE `subjects` ADD COLUMN `parentId` INTEGER DEFAULT NULL")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subjects_parentId` ON `subjects` (`parentId`)")
            }
        }
        internal val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_flashcards_suspended` ON `flashcards` (`suspended`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_flashcards_suspended_dueAt` ON `flashcards` (`suspended`, `dueAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_flashcards_sourceNoteId` ON `flashcards` (`sourceNoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_noteId` ON `study_tasks` (`noteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_bookId` ON `study_tasks` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_previousTaskId` ON `study_tasks` (`previousTaskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_round_done` ON `review_round` (`done`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_round_position` ON `review_round` (`position`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_round_cardId_token_done` ON `review_round` (`cardId`, `token`, `done`)")
            }
        }
        internal val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE card_decks ADD COLUMN schedulerMode TEXT NOT NULL DEFAULT 'SM2'")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN learningSteps TEXT NOT NULL DEFAULT '1 10'")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN relearningSteps TEXT NOT NULL DEFAULT '10'")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN graduatingInterval INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN easyInterval INTEGER NOT NULL DEFAULT 4")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN desiredRetention REAL NOT NULL DEFAULT 0.9")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN burySiblings INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN leechThreshold INTEGER NOT NULL DEFAULT 8")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN leechSuspend INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN dayStartHour INTEGER NOT NULL DEFAULT 4")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN isFiltered INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN filterQuery TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE flashcards ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN flag INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN buriedUntilDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN learningStep INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN fsrsStability REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN fsrsDifficulty REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN lastReviewAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN noteTypeKey TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE card_author_notes ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE card_author_notes ADD COLUMN typeAnswer INTEGER NOT NULL DEFAULT 0")

                db.execSQL("CREATE TABLE IF NOT EXISTS `note_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, `title` TEXT NOT NULL, `markdown` TEXT NOT NULL, `revision` INTEGER NOT NULL DEFAULT 0, `savedAt` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_snapshots_noteId` ON `note_snapshots` (`noteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_snapshots_savedAt` ON `note_snapshots` (`savedAt`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `canvas_boards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `jsonData` TEXT NOT NULL, `createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_canvas_boards_updatedAt` ON `canvas_boards` (`updatedAt`)")
            }
        }
        internal val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN iconKey TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE habits ADD COLUMN color INTEGER NOT NULL DEFAULT 4280454118")
                db.execSQL("ALTER TABLE habits ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE habits ADD COLUMN startedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE habits SET startedAt = createdAt WHERE startedAt = 0")
                db.execSQL("ALTER TABLE books ADD COLUMN documentUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN documentName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN lastReadAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `habit_notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `habitId` INTEGER NOT NULL, `content` TEXT NOT NULL, `dayKey` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `mood` TEXT NOT NULL DEFAULT '', FOREIGN KEY(`habitId`) REFERENCES `habits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_notes_habitId` ON `habit_notes` (`habitId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_notes_dayKey` ON `habit_notes` (`dayKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_notes_createdAt` ON `habit_notes` (`createdAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `habit_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `habitId` INTEGER NOT NULL, `title` TEXT NOT NULL, `completed` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`habitId`) REFERENCES `habits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_tasks_habitId` ON `habit_tasks` (`habitId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_tasks_createdAt` ON `habit_tasks` (`createdAt`)")
            }
        }
        internal val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_sessions_startedAt` ON `study_sessions` (`startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_logs_loggedAt` ON `reading_logs` (`loggedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_logs_bookId` ON `reading_logs` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_completed` ON `study_tasks` (`completed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_dueAt` ON `study_tasks` (`dueAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_trashedAt` ON `notes` (`trashedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_updatedAt` ON `notes` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_entries_dayKey` ON `journal_entries` (`dayKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_events_at` ON `calendar_events` (`at`)")
            }
        }
        internal val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN rating INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN categoryTag TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN currentChapter TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN targetFinishAt INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN highlightsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reading_logs ADD COLUMN bookId INTEGER")
                db.execSQL("ALTER TABLE reading_logs ADD COLUMN subjectId INTEGER")
                db.execSQL("ALTER TABLE reading_logs ADD COLUMN durationMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }
        internal val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subjects ADD COLUMN weight INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE subjects ADD COLUMN examDate INTEGER")
                db.execSQL("ALTER TABLE subjects ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN nextReviewAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN reviewCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN resolved INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN energy INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN focus INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN promptUsed TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN linkedNoteId INTEGER")
                db.execSQL("ALTER TABLE goals ADD COLUMN metricType TEXT NOT NULL DEFAULT 'COUNTDOWN'")
                db.execSQL("ALTER TABLE goals ADD COLUMN currentValue REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE goals ADD COLUMN targetValue REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE goals ADD COLUMN metricUnit TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE goals ADD COLUMN subjectId INTEGER")
                db.execSQL("ALTER TABLE goals ADD COLUMN milestonesJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE goals ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }
        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN hasDueDate INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN estimatedMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN subtasksJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN bookId INTEGER")
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN afterCompletion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN taskId INTEGER")
            }
        }
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE review_round ADD COLUMN cram INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE card_author_notes ADD COLUMN occlusion TEXT")
            }
        }
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN topic TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'STOPWATCH'")
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN breakSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE subjects ADD COLUMN targetMinutesPerDay INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE card_author_notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, format TEXT NOT NULL, prompt TEXT NOT NULL, answer TEXT NOT NULL, template TEXT NOT NULL, fields TEXT NOT NULL)")
                db.execSQL("CREATE TABLE card_templates (name TEXT NOT NULL PRIMARY KEY, front TEXT NOT NULL, back TEXT NOT NULL)")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN authorNoteId INTEGER")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN variantKey TEXT")
                db.execSQL("CREATE UNIQUE INDEX index_flashcards_authorNoteId_variantKey ON flashcards(authorNoteId, variantKey)")
            }
        }
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE card_decks ADD COLUMN parentId INTEGER REFERENCES card_decks(id) ON DELETE SET NULL")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN newLimit INTEGER")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN reviewLimit INTEGER")
                db.execSQL("ALTER TABLE card_decks ADD COLUMN dailyGoal INTEGER")
                db.execSQL("DROP INDEX index_card_decks_name")
                db.execSQL("CREATE UNIQUE INDEX index_card_decks_parentId_name ON card_decks(parentId,name)")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN suspended INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE card_decks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX index_card_decks_name ON card_decks(name)")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN deckId INTEGER REFERENCES card_decks(id) ON DELETE SET NULL")
                db.execSQL("CREATE INDEX index_flashcards_deckId ON flashcards(deckId)")
            }
        }
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subjects ADD COLUMN nextStep TEXT")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN sourceNoteId INTEGER")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN sourceExcerpt TEXT")
                db.execSQL("CREATE TABLE mistakes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, subjectId INTEGER NOT NULL, topic TEXT NOT NULL, question TEXT NOT NULL, reason TEXT NOT NULL, solution TEXT NOT NULL, FOREIGN KEY(subjectId) REFERENCES subjects(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_mistakes_subjectId ON mistakes(subjectId)")
            }
        }
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN previousTaskId INTEGER")
            }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS habits (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS habit_completions (habitId INTEGER NOT NULL, dayKey INTEGER NOT NULL, PRIMARY KEY(habitId, dayKey), FOREIGN KEY(habitId) REFERENCES habits(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_habit_completions_dayKey ON habit_completions(dayKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS goals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, targetAt INTEGER NOT NULL, completed INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, markdown TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS flashcards (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, subjectId INTEGER, front TEXT NOT NULL, back TEXT NOT NULL, dueAt INTEGER NOT NULL, intervalDays INTEGER NOT NULL, easeFactor REAL NOT NULL, repetitions INTEGER NOT NULL, lapses INTEGER NOT NULL, FOREIGN KEY(subjectId) REFERENCES subjects(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashcards_subjectId ON flashcards(subjectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashcards_dueAt ON flashcards(dueAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS card_reviews (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardId INTEGER NOT NULL, reviewedAt INTEGER NOT NULL, grade INTEGER NOT NULL, intervalDays INTEGER NOT NULL, FOREIGN KEY(cardId) REFERENCES flashcards(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_card_reviews_cardId ON card_reviews(cardId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_card_reviews_reviewedAt ON card_reviews(reviewedAt)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS habit_areas (name TEXT NOT NULL PRIMARY KEY, sortOrder INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO habit_areas(name, sortOrder) VALUES ('Manhã',0),('Tarde',1),('Noite',2),('Dia',3)")
                db.execSQL("ALTER TABLE habits ADD COLUMN area TEXT NOT NULL DEFAULT 'Dia'")
                db.execSQL("ALTER TABLE habits ADD COLUMN kind TEXT NOT NULL DEFAULT 'GOOD'")
                db.execSQL("ALTER TABLE habits ADD COLUMN repeatDaysMask INTEGER NOT NULL DEFAULT 127")
                db.execSQL("ALTER TABLE habits ADD COLUMN targetValue REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE habits ADD COLUMN targetUnit TEXT NOT NULL DEFAULT 'vezes'")
                db.execSQL("ALTER TABLE habits ADD COLUMN targetPeriod TEXT NOT NULL DEFAULT 'dia'")
                db.execSQL("ALTER TABLE habits ADD COLUMN reminderMinutes INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_habits_area ON habits(area)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_habits_createdAt ON habits(createdAt)")
                db.execSQL("ALTER TABLE habit_completions ADD COLUMN status TEXT NOT NULL DEFAULT 'DONE'")
                db.execSQL("ALTER TABLE habit_completions ADD COLUMN value REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE habit_completions ADD COLUMN note TEXT")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE goals ADD COLUMN direction TEXT NOT NULL DEFAULT 'COUNTDOWN'")
                db.execSQL("ALTER TABLE goals ADD COLUMN unit TEXT NOT NULL DEFAULT 'DAYS'")
                db.execSQL("ALTER TABLE goals ADD COLUMN roundUp INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE goals ADD COLUMN color INTEGER NOT NULL DEFAULT 4284569305")
                db.execSQL("ALTER TABLE goals ADD COLUMN style TEXT NOT NULL DEFAULT 'RING'")
                db.execSQL("ALTER TABLE goals ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS study_tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, subjectId INTEGER, title TEXT NOT NULL, dueAt INTEGER NOT NULL, completed INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(subjectId) REFERENCES subjects(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_study_tasks_subjectId ON study_tasks(subjectId)")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN emoji TEXT NOT NULL DEFAULT '✨'")
                db.execSQL("ALTER TABLE habit_completions ADD COLUMN loggedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS books (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, coverUri TEXT, summary TEXT NOT NULL, currentPage INTEGER NOT NULL, totalPages INTEGER, quotes TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS book_note_links (bookId INTEGER NOT NULL, noteId INTEGER NOT NULL, PRIMARY KEY(bookId, noteId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_note_links_noteId ON book_note_links(noteId)")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN isActive INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN subjectId INTEGER REFERENCES subjects(id) ON UPDATE NO ACTION ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_subjectId ON books(subjectId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS subject_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, subjectId INTEGER NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, details TEXT NOT NULL, targetAt INTEGER, currentValue REAL NOT NULL, targetValue REAL, unit TEXT NOT NULL, completed INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(subjectId) REFERENCES subjects(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subject_items_subjectId ON subject_items(subjectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subject_items_type ON subject_items(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subject_items_targetAt ON subject_items(targetAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS question_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, subjectId INTEGER, topic TEXT NOT NULL, questionCount INTEGER NOT NULL, correctCount INTEGER NOT NULL, loggedAt INTEGER NOT NULL, FOREIGN KEY(subjectId) REFERENCES subjects(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_question_logs_subjectId ON question_logs(subjectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_question_logs_loggedAt ON question_logs(loggedAt)")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS workout_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, exercises TEXT NOT NULL, durationMinutes INTEGER NOT NULL, notes TEXT NOT NULL, startedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workout_logs_startedAt ON workout_logs(startedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS meal_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, mealType TEXT NOT NULL, foods TEXT NOT NULL, calories INTEGER NOT NULL, proteinGrams REAL NOT NULL, carbsGrams REAL NOT NULL, fatGrams REAL NOT NULL, loggedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_meal_logs_loggedAt ON meal_logs(loggedAt)")
            }
        }
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE activity_timers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, mode TEXT NOT NULL, title TEXT NOT NULL, bookId INTEGER, startedAt INTEGER NOT NULL, runningSince INTEGER, elapsedMs INTEGER NOT NULL, endedAt INTEGER, series TEXT NOT NULL, restUntil INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN completedAt INTEGER")
                db.execSQL("CREATE TABLE reading_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, pages INTEGER NOT NULL, loggedAt INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 4")
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN recurrence TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN noteId INTEGER")
                db.execSQL("ALTER TABLE study_tasks ADD COLUMN details TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS journal_entries (dayKey INTEGER NOT NULL PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, mood TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }
    }
}
