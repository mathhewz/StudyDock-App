package com.studydock.app.timer

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.os.VibrationEffect
import android.provider.Settings
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.studydock.app.data.StudyDao
import com.studydock.app.data.StudyLog
import com.studydock.app.data.StudySession
import com.studydock.app.data.Subject
import com.studydock.app.ui.StudyNotificationPrefs
import com.studydock.app.ui.TimerAlerts
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

enum class StudyTimerMode {
    STOPWATCH, // Cronômetro crescente
    POMODORO,  // Pomodoro
    COUNTDOWN, // Temporizador regressivo
    FLOWMODORO // Cronômetro progressivo com descanso proporcional (T/5)
}

enum class TimerState {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED
}

enum class PomodoroPhase {
    FOCUS,
    SHORT_BREAK,
    LONG_BREAK
}

data class PomodoroSettings(
    val focusMin: Int = 25,
    val shortMin: Int = 5,
    val longMin: Int = 15,
    val cycles: Int = 4
) {
    fun sanitized(): PomodoroSettings = copy(
        focusMin = focusMin.coerceIn(1, 180),
        shortMin = shortMin.coerceIn(1, 60),
        longMin = longMin.coerceIn(1, 120),
        cycles = cycles.coerceIn(1, 12)
    )
}

data class SessionSnapshot(
    val state: TimerState = TimerState.IDLE,
    val mode: StudyTimerMode = StudyTimerMode.STOPWATCH,
    val subjectId: Long? = null,
    val topic: String = "",
    val elapsedFocusSeconds: Long = 0L,
    val elapsedBreakSeconds: Long = 0L,
    val countdownTargetSeconds: Long = 45 * 60L,
    val pomodoroPhase: PomodoroPhase = PomodoroPhase.FOCUS,
    val pomodoroCycle: Int = 0,
    val pomodoroSettings: PomodoroSettings = PomodoroSettings(),
    val startedWallEpoch: Long = 0L,
    val activeSessionDbId: Long? = null,
    val linkedNoteId: Long? = null,
    val linkedTaskId: Long? = null,
    val soundOnStart: Boolean = true,
    val soundOnEnd: Boolean = true,
    val vibrateOnAlert: Boolean = true,
    val recoveredFromKill: Boolean = false,
    val appExitCount: Int = 0,
    val pomodoroPhaseElapsedSeconds: Long = 0L
)

/**
 * Singleton engine for managing study sessions with monotonic precision,
 * separate tracking of focus and breaks, transactional Room persistence,
 * crash recovery, and Foreground Service updates.
 */
class StudySessionManager private constructor(
    private val appContext: Context,
    @Volatile private var dao: StudyDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    fun updateDao(newDao: StudyDao) {
        this.dao = newDao
    }

    fun recordAppExitIfRunning() {
        synchronized(stateLock) {
            val cur = _snapshot.value
            if (cur.state == TimerState.RUNNING) {
                val next = cur.appExitCount + 1
                _snapshot.value = cur.copy(appExitCount = next)
                prefs.edit().putInt("app_exit_count", next).apply()
            }
        }
    }

    private fun readBootCount(): Int =
        runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    private val cachedBootCount: Int by lazy { readBootCount() }
    private val prefs = appContext.getSharedPreferences("studydock_timer_session_v3", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val stateLock = Any()

    // Internal timing variables using SystemClock.elapsedRealtime()
    private var lastResumeMonotonic: Long = 0L
    private var lastResumeWall: Long = 0L
    private var accumulatedFocusSeconds: Long = 0L
    private var phaseFocusBaseSeconds: Long = 0L

    private var lastPauseMonotonic: Long = 0L
    private var accumulatedBreakSeconds: Long = 0L
    private var phaseBreakBaseSeconds: Long = 0L
    private var residualFocusMs: Long = 0L
    private var residualBreakMs: Long = 0L
    @Volatile private var activeRingtone: android.media.Ringtone? = null

    private fun addFocusMsLocked(deltaMs: Long) {
        val totalMs = residualFocusMs + deltaMs.coerceAtLeast(0L)
        accumulatedFocusSeconds += totalMs / 1000L
        residualFocusMs = totalMs % 1000L
    }

    private fun addBreakMsLocked(deltaMs: Long) {
        val totalMs = residualBreakMs + deltaMs.coerceAtLeast(0L)
        accumulatedBreakSeconds += totalMs / 1000L
        residualBreakMs = totalMs % 1000L
    }

    private var startedWallEpoch: Long = 0L
    private var activeSessionDbId: Long? = null
    private var lastAutoSavedSeconds: Long = -1L
    @Volatile private var taskLinkGeneration: Long = 0L
    private var segmentGeneration: Long = 0L

    private val _snapshot = MutableStateFlow(SessionSnapshot())
    val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

    init {
        val shouldStartForeground = synchronized(stateLock) {
            restoreFromPreferencesLocked()
        }
        if (shouldStartForeground) {
            startForegroundService()
        }
        startTicker()
        scope.launch {
            snapshot.map { it.state == TimerState.RUNNING && (it.mode != StudyTimerMode.POMODORO || it.pomodoroPhase == PomodoroPhase.FOCUS) }
                .distinctUntilChanged()
                .collect { activeFocus ->
                    FocusDndController.syncWithTimerState(appContext, activeFocus)
                }
        }
        scope.launch {
            snapshot.map { it.state == TimerState.RUNNING }
                .distinctUntilChanged()
                .collect {
                    com.studydock.app.ui.StudyTimerWidget.refresh(appContext)
                }
        }
    }

    private inline fun <reified T : Enum<T>> readPersistedEnum(key: String, default: T): T {
        val raw = prefs.getString(key, default.name) ?: default.name
        return runCatching { enumValueOf<T>(raw) }
            .onFailure { StudyLog.w(TAG, "Invalid persisted ${T::class.java.simpleName} for '$key': '$raw'", it) }
            .getOrDefault(default)
    }

    private fun restoreFromPreferencesLocked(): Boolean {
        val restoredState = readPersistedEnum("state", TimerState.IDLE)
        val restoredMode = readPersistedEnum("mode", StudyTimerMode.STOPWATCH)

        val subjectId = prefs.getLong("subject_id", -1L).takeIf { it >= 0L }
        val topic = prefs.getString("topic", "").orEmpty()
        val linkedNoteId = prefs.getLong("linked_note_id", -1L).takeIf { it > 0 }
        val linkedTaskId = prefs.getLong("linked_task_id", -1L).takeIf { it > 0 }

        accumulatedFocusSeconds = prefs.getLong("accumulated_focus", 0L)
        accumulatedBreakSeconds = prefs.getLong("accumulated_break", 0L)
        phaseFocusBaseSeconds = prefs.getLong("phase_focus_base", 0L).coerceIn(0L, accumulatedFocusSeconds)
        phaseBreakBaseSeconds = prefs.getLong("phase_break_base", 0L).coerceIn(0L, accumulatedBreakSeconds)
        startedWallEpoch = prefs.getLong("started_wall_epoch", 0L)
        activeSessionDbId = prefs.getLong("session_db_id", -1L).takeIf { it >= 0L }

        val countdownTarget = prefs.getLong("countdown_target", 45 * 60L).coerceAtLeast(60L)

        val pomoPhase = readPersistedEnum("pomo_phase", PomodoroPhase.FOCUS)
        val pomoCycle = prefs.getInt("pomo_cycle", 0)

        val pomoSettings = PomodoroSettings(
            focusMin = prefs.getInt("pomo_focus_min", 25),
            shortMin = prefs.getInt("pomo_short_min", 5),
            longMin = prefs.getInt("pomo_long_min", 15),
            cycles = prefs.getInt("pomo_cycles", 4)
        ).sanitized()

        val soundOnStart = prefs.getBoolean("sound_on_start", true)
        val soundOnEnd = prefs.getBoolean("sound_on_end", true)
        val vibrateOnAlert = prefs.getBoolean("vibrate_on_alert", true)
        val appExitCount = prefs.getInt("app_exit_count", 0).coerceAtLeast(0)

        lastResumeMonotonic = prefs.getLong("last_resume_monotonic", 0L)
        lastResumeWall = prefs.getLong("last_resume_wall", 0L)
        lastPauseMonotonic = prefs.getLong("last_pause_monotonic", 0L)
        val savedBootCount = prefs.getInt("boot_count", -1)
        val currentBootCount = cachedBootCount
        val sameBoot = savedBootCount != -1 && currentBootCount != -1 && savedBootCount == currentBootCount

        // Handle crash / termination / reboot recovery with ceiling protection (D1)
        var finalState = restoredState
        var recoveredFlag = false
        val nowMonotonic = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val maxContinuousRecoverySec = 2L * 3600L      // 2h ceiling before auto-pausing
        val maxPausedBreakRecoverySec = 4L * 3600L     // 4h ceiling for break counter

        if (restoredState == TimerState.RUNNING) {
            val rawDeltaSeconds = when {
                sameBoot && lastResumeMonotonic > 0L && nowMonotonic >= lastResumeMonotonic ->
                    (nowMonotonic - lastResumeMonotonic) / 1000L
                lastResumeWall > 0L && nowWall >= lastResumeWall ->
                    (nowWall - lastResumeWall) / 1000L
                else -> 0L
            }.coerceAtLeast(0L)

            val modeCapSeconds = when {
                restoredMode == StudyTimerMode.COUNTDOWN ->
                    (countdownTarget - accumulatedFocusSeconds).coerceAtLeast(0L)
                restoredMode == StudyTimerMode.POMODORO && pomoPhase == PomodoroPhase.FOCUS ->
                    (pomoSettings.focusMin * 60L - (accumulatedFocusSeconds - phaseFocusBaseSeconds)).coerceAtLeast(0L)
                restoredMode == StudyTimerMode.POMODORO && pomoPhase == PomodoroPhase.SHORT_BREAK ->
                    (pomoSettings.shortMin * 60L - (accumulatedBreakSeconds - phaseBreakBaseSeconds)).coerceAtLeast(0L)
                restoredMode == StudyTimerMode.POMODORO && pomoPhase == PomodoroPhase.LONG_BREAK ->
                    (pomoSettings.longMin * 60L - (accumulatedBreakSeconds - phaseBreakBaseSeconds)).coerceAtLeast(0L)
                else -> maxContinuousRecoverySec
            }
            val effectiveCap = minOf(modeCapSeconds, maxContinuousRecoverySec)
            val clampedDelta = rawDeltaSeconds.coerceAtMost(effectiveCap)

            if (restoredMode == StudyTimerMode.POMODORO && pomoPhase != PomodoroPhase.FOCUS) {
                accumulatedBreakSeconds += clampedDelta
            } else {
                accumulatedFocusSeconds += clampedDelta
            }

            if (rawDeltaSeconds > effectiveCap || !sameBoot) {
                // Process was dead for longer than the ceiling or device rebooted: pause safely for user review
                finalState = TimerState.PAUSED
                lastResumeMonotonic = 0L
                lastPauseMonotonic = nowMonotonic
                recoveredFlag = true
            } else {
                lastResumeMonotonic = nowMonotonic
                lastResumeWall = nowWall
                finalState = TimerState.RUNNING
            }
        } else if (restoredState == TimerState.PAUSED && lastPauseMonotonic > 0L) {
            if (sameBoot && nowMonotonic >= lastPauseMonotonic) {
                val deltaBreak = ((nowMonotonic - lastPauseMonotonic) / 1000L).coerceIn(0L, maxPausedBreakRecoverySec)
                accumulatedBreakSeconds += deltaBreak
                if (restoredMode == StudyTimerMode.POMODORO && pomoPhase != PomodoroPhase.FOCUS) {
                    phaseBreakBaseSeconds += deltaBreak
                }
            }
            lastPauseMonotonic = nowMonotonic
        }

        val initialPhaseElapsed = when {
            restoredMode != StudyTimerMode.POMODORO -> 0L
            pomoPhase == PomodoroPhase.FOCUS -> (accumulatedFocusSeconds - phaseFocusBaseSeconds).coerceAtLeast(0L)
            else -> (accumulatedBreakSeconds - phaseBreakBaseSeconds).coerceAtLeast(0L)
        }
        _snapshot.value = SessionSnapshot(
            state = finalState,
            mode = restoredMode,
            subjectId = subjectId,
            topic = topic,
            elapsedFocusSeconds = accumulatedFocusSeconds,
            elapsedBreakSeconds = accumulatedBreakSeconds,
            countdownTargetSeconds = countdownTarget,
            pomodoroPhase = pomoPhase,
            pomodoroCycle = pomoCycle,
            pomodoroSettings = pomoSettings,
            startedWallEpoch = startedWallEpoch,
            activeSessionDbId = activeSessionDbId,
            linkedNoteId = linkedNoteId,
            linkedTaskId = linkedTaskId,
            soundOnStart = soundOnStart,
            soundOnEnd = soundOnEnd,
            vibrateOnAlert = vibrateOnAlert,
            recoveredFromKill = recoveredFlag,
            appExitCount = appExitCount,
            pomodoroPhaseElapsedSeconds = initialPhaseElapsed
        )

        // If it was running, notify caller to ensure foreground service is alive outside stateLock
        return finalState == TimerState.RUNNING
    }

    private fun persistToPreferences(@Suppress("UNUSED_PARAMETER") synchronous: Boolean = false) {
        try {
            val s = _snapshot.value
            val editor = prefs.edit()
                .putString("state", s.state.name)
                .putString("mode", s.mode.name)
                .putLong("subject_id", s.subjectId ?: -1L)
                .putLong("linked_note_id", s.linkedNoteId ?: -1L)
                .putLong("linked_task_id", s.linkedTaskId ?: -1L)
                .putString("topic", s.topic)
                .putLong("accumulated_focus", accumulatedFocusSeconds)
                .putLong("accumulated_break", accumulatedBreakSeconds)
                .putLong("phase_focus_base", phaseFocusBaseSeconds)
                .putLong("phase_break_base", phaseBreakBaseSeconds)
                .putLong("started_wall_epoch", startedWallEpoch)
                .putLong("session_db_id", activeSessionDbId ?: -1L)
                .putLong("countdown_target", s.countdownTargetSeconds)
                .putString("pomo_phase", s.pomodoroPhase.name)
                .putInt("pomo_cycle", s.pomodoroCycle)
                .putInt("pomo_focus_min", s.pomodoroSettings.focusMin)
                .putInt("pomo_short_min", s.pomodoroSettings.shortMin)
                .putInt("pomo_long_min", s.pomodoroSettings.longMin)
                .putInt("pomo_cycles", s.pomodoroSettings.cycles)
                .putBoolean("sound_on_start", s.soundOnStart)
                .putBoolean("sound_on_end", s.soundOnEnd)
                .putBoolean("vibrate_on_alert", s.vibrateOnAlert)
                .putInt("app_exit_count", s.appExitCount)
                .putLong("last_resume_monotonic", lastResumeMonotonic)
                .putLong("last_resume_wall", lastResumeWall)
                .putLong("last_pause_monotonic", lastPauseMonotonic)
                .putInt("boot_count", cachedBootCount)
            editor.apply()
        } catch (e: Exception) {
            StudyLog.e(TAG, "Failed to persist timer preferences", e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startTicker() {
        scope.launch {
            _snapshot
                .map { it.state == TimerState.RUNNING || it.state == TimerState.PAUSED }
                .distinctUntilChanged()
                .collectLatest { isActive ->
                    while (isActive) {
                        delay(500)
                        updateTick()
                    }
                }
        }
    }

    /** Forces an immediate monotonic clock recalculation (e.g., when returning from Doze/screen-off). */
    fun refreshNow() {
        updateTick()
    }

    private fun currentFocusSecondsLocked(nowMonotonic: Long = SystemClock.elapsedRealtime()): Long {
        val current = _snapshot.value
        return if (current.state == TimerState.RUNNING &&
            !(current.mode == StudyTimerMode.POMODORO && current.pomodoroPhase != PomodoroPhase.FOCUS)
        ) {
            val delta = ((nowMonotonic - lastResumeMonotonic) / 1000L).coerceAtLeast(0L)
            accumulatedFocusSeconds + delta
        } else {
            accumulatedFocusSeconds
        }
    }

    private fun flushElapsedLocked(nowMonotonic: Long) {
        val current = _snapshot.value
        if (current.state == TimerState.RUNNING) {
            val deltaMs = (nowMonotonic - lastResumeMonotonic).coerceAtLeast(0L)
            if (current.mode == StudyTimerMode.POMODORO && current.pomodoroPhase != PomodoroPhase.FOCUS) {
                addBreakMsLocked(deltaMs)
            } else {
                addFocusMsLocked(deltaMs)
            }
            lastResumeMonotonic = nowMonotonic
            lastResumeWall = System.currentTimeMillis()
        } else if (current.state == TimerState.PAUSED && lastPauseMonotonic > 0L) {
            addBreakMsLocked((nowMonotonic - lastPauseMonotonic).coerceAtLeast(0L))
            lastPauseMonotonic = nowMonotonic
        }
    }

    private fun updateTick() {
        var completionAlertIsBreak: Boolean? = null
        var shouldSyncDb = false

        synchronized(stateLock) {
            val current = _snapshot.value
            val nowMonotonic = SystemClock.elapsedRealtime()

            if (current.state == TimerState.RUNNING) {
                if (lastResumeMonotonic <= 0L) lastResumeMonotonic = nowMonotonic
                val delta = ((nowMonotonic - lastResumeMonotonic) / 1000L).coerceAtLeast(0L)

                if (current.mode == StudyTimerMode.POMODORO && current.pomodoroPhase != PomodoroPhase.FOCUS) {
                    val totalBreak = accumulatedBreakSeconds + delta
                    val phaseBreak = (totalBreak - phaseBreakBaseSeconds).coerceAtLeast(0L)
                    val breakLimit = if (current.pomodoroPhase == PomodoroPhase.LONG_BREAK) {
                        current.pomodoroSettings.longMin * 60L
                    } else {
                        current.pomodoroSettings.shortMin * 60L
                    }

                    if (phaseBreak >= breakLimit) {
                        val cappedBreak = phaseBreakBaseSeconds + breakLimit
                        accumulatedBreakSeconds = cappedBreak
                        phaseFocusBaseSeconds = accumulatedFocusSeconds
                        lastResumeMonotonic = nowMonotonic
                        lastPauseMonotonic = nowMonotonic
                        completionAlertIsBreak = true
                        _snapshot.value = current.copy(
                            state = TimerState.PAUSED,
                            elapsedBreakSeconds = cappedBreak,
                            pomodoroPhase = PomodoroPhase.FOCUS,
                            pomodoroPhaseElapsedSeconds = 0L
                        )
                        persistToPreferences(synchronous = true)
                    } else if (current.elapsedBreakSeconds != totalBreak || current.pomodoroPhaseElapsedSeconds != phaseBreak) {
                        _snapshot.value = current.copy(
                            elapsedBreakSeconds = totalBreak,
                            pomodoroPhaseElapsedSeconds = phaseBreak
                        )
                    }
                } else {
                    val totalFocus = accumulatedFocusSeconds + delta
                    val focusLimit = current.pomodoroSettings.focusMin * 60L
                    val phaseFocus = if (current.mode == StudyTimerMode.POMODORO) {
                        (totalFocus - phaseFocusBaseSeconds).coerceAtLeast(0L)
                    } else {
                        totalFocus
                    }

                    if (current.mode == StudyTimerMode.COUNTDOWN && totalFocus >= current.countdownTargetSeconds) {
                        accumulatedFocusSeconds = current.countdownTargetSeconds
                        _snapshot.value = current.copy(
                            state = TimerState.COMPLETED,
                            elapsedFocusSeconds = current.countdownTargetSeconds
                        )
                        completionAlertIsBreak = false
                        persistToPreferences(synchronous = true)
                        shouldSyncDb = true
                    } else if (current.mode == StudyTimerMode.POMODORO && phaseFocus >= focusLimit) {
                        val cappedFocus = phaseFocusBaseSeconds + focusLimit
                        accumulatedFocusSeconds = cappedFocus
                        phaseBreakBaseSeconds = accumulatedBreakSeconds
                        val nextCycle = current.pomodoroCycle + 1
                        val isLong = nextCycle % current.pomodoroSettings.cycles == 0
                        val nextPhase = if (isLong) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK

                        _snapshot.value = current.copy(
                            state = TimerState.RUNNING,
                            elapsedFocusSeconds = cappedFocus,
                            pomodoroCycle = nextCycle,
                            pomodoroPhase = nextPhase,
                            pomodoroPhaseElapsedSeconds = 0L
                        )
                        completionAlertIsBreak = false
                        lastResumeMonotonic = nowMonotonic
                        persistToPreferences(synchronous = true)
                        shouldSyncDb = true
                    } else {
                        val targetPhaseElapsed = if (current.mode == StudyTimerMode.POMODORO) phaseFocus else 0L
                        if (current.elapsedFocusSeconds != totalFocus || current.pomodoroPhaseElapsedSeconds != targetPhaseElapsed) {
                            _snapshot.value = current.copy(
                                elapsedFocusSeconds = totalFocus,
                                pomodoroPhaseElapsedSeconds = targetPhaseElapsed
                            )
                        }

                        // Auto-sync active session to DB every 15 seconds
                        if (totalFocus - lastAutoSavedSeconds >= 15) {
                            lastAutoSavedSeconds = totalFocus
                            shouldSyncDb = true
                        }
                    }
                }
            } else if (current.state == TimerState.PAUSED && lastPauseMonotonic > 0L) {
                val breakDelta = ((nowMonotonic - lastPauseMonotonic) / 1000L).coerceAtLeast(0L)
                val totalBreak = accumulatedBreakSeconds + breakDelta
                if (current.elapsedBreakSeconds != totalBreak) {
                    _snapshot.value = current.copy(
                        elapsedBreakSeconds = totalBreak
                    )
                }
            }
        }

        completionAlertIsBreak?.let { playCompletionAlert(isBreak = it) }
        if (shouldSyncDb) {
            scope.launch { syncActiveSessionToDb() }
        }
    }

    fun setSubject(id: Long?) {
        synchronized(stateLock) {
            val current = _snapshot.value
            val liveFocus = currentFocusSecondsLocked()
            if (liveFocus == 0L || current.subjectId == id) {
                if (current.subjectId != id) {
                    _snapshot.value = current.copy(subjectId = id)
                    persistToPreferences()
                }
            }
        }
    }

    /**
     * Switches the active subject (or `null` for Geral / Livre) during an ongoing or idle session.
     * When focus time has already elapsed:
     * - [splitPreviousSegment] = true persists the completed block for the previous subject and starts
     *   a fresh segment on [newSubjectId] while keeping the timer running/paused seamlessly.
     * - [splitPreviousSegment] = false reclassifies the current segment onto [newSubjectId] without splitting.
     */
    fun switchSubjectDuringStudy(
        newSubjectId: Long?,
        splitPreviousSegment: Boolean,
        newTopic: String? = null
    ) {
        var splitPrevDbId: Long? = null
        var splitPrevSubjectId: Long? = null
        var splitPrevStarted = 0L
        var splitPrevEnded = 0L
        var splitPrevDuration = 0L
        var splitPrevTopic = ""
        var splitPrevMode = ""
        var splitPrevBreak = 0L
        var splitPrevTaskId: Long? = null
        var splitPrevExitCount = 0
        var shouldPersistSplit = false
        var shouldSyncReclassified = false

        synchronized(stateLock) {
            val nowMonotonic = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            val current = _snapshot.value

            flushElapsedLocked(nowMonotonic)
            val liveFocus = accumulatedFocusSeconds.coerceAtLeast(0L)

            if (liveFocus == 0L) {
                val updatedTopic = newTopic?.trim() ?: current.topic
                if (current.subjectId != newSubjectId || current.topic != updatedTopic) {
                    _snapshot.value = current.copy(
                        subjectId = newSubjectId,
                        topic = updatedTopic
                    )
                    persistToPreferences()
                }
            } else if (!splitPreviousSegment) {
                val updatedTopic = newTopic?.trim() ?: current.topic
                _snapshot.value = current.copy(
                    subjectId = newSubjectId,
                    topic = updatedTopic,
                    elapsedFocusSeconds = accumulatedFocusSeconds,
                    elapsedBreakSeconds = accumulatedBreakSeconds
                )
                persistToPreferences()
                shouldSyncReclassified = activeSessionDbId != null ||
                    current.state == TimerState.RUNNING ||
                    current.state == TimerState.PAUSED ||
                    current.state == TimerState.COMPLETED
            } else {
                val prevSubjectId = current.subjectId
                splitPrevDbId = activeSessionDbId
                splitPrevSubjectId = prevSubjectId
                splitPrevTopic = current.topic.trim()
                splitPrevMode = current.mode.name
                splitPrevDuration = liveFocus
                splitPrevBreak = accumulatedBreakSeconds.coerceAtLeast(0L)
                splitPrevStarted = startedWallEpoch.takeIf { it > 0L } ?: (nowWall - liveFocus * 1000L)
                splitPrevEnded = nowWall
                splitPrevTaskId = current.linkedTaskId
                splitPrevExitCount = current.appExitCount
                shouldPersistSplit = true

                segmentGeneration++
                activeSessionDbId = null
                lastAutoSavedSeconds = -1L
                accumulatedFocusSeconds = 0L
                accumulatedBreakSeconds = 0L
                phaseFocusBaseSeconds = 0L
                phaseBreakBaseSeconds = 0L

                val nextState = if (current.state == TimerState.COMPLETED) TimerState.IDLE else current.state
                when (nextState) {
                    TimerState.RUNNING -> {
                        lastResumeMonotonic = nowMonotonic
                        lastResumeWall = nowWall
                        lastPauseMonotonic = 0L
                        startedWallEpoch = if (current.mode == StudyTimerMode.POMODORO && current.pomodoroPhase != PomodoroPhase.FOCUS) {
                            0L
                        } else {
                            nowWall
                        }
                    }
                    TimerState.PAUSED -> {
                        lastResumeMonotonic = 0L
                        lastResumeWall = 0L
                        lastPauseMonotonic = nowMonotonic
                        startedWallEpoch = 0L
                    }
                    TimerState.IDLE, TimerState.COMPLETED -> {
                        lastResumeMonotonic = 0L
                        lastResumeWall = 0L
                        lastPauseMonotonic = 0L
                        startedWallEpoch = 0L
                    }
                }

                val nextTopic = when {
                    newTopic != null -> newTopic.trim()
                    newSubjectId != prevSubjectId -> ""
                    else -> current.topic
                }
                val nextLinkedTaskId = if (newSubjectId != prevSubjectId) {
                    taskLinkGeneration++
                    null
                } else {
                    current.linkedTaskId
                }

                _snapshot.value = current.copy(
                    state = nextState,
                    subjectId = newSubjectId,
                    topic = nextTopic,
                    elapsedFocusSeconds = 0L,
                    elapsedBreakSeconds = 0L,
                    startedWallEpoch = startedWallEpoch,
                    activeSessionDbId = null,
                    linkedTaskId = nextLinkedTaskId,
                    appExitCount = 0,
                    pomodoroPhaseElapsedSeconds = 0L
                )
                persistToPreferences()
            }
        }

        if (shouldPersistSplit) {
            scope.launch {
                persistSplitSegmentToDb(
                    prevDbId = splitPrevDbId,
                    prevSubjectId = splitPrevSubjectId,
                    prevStarted = splitPrevStarted,
                    prevEnded = splitPrevEnded,
                    prevDuration = splitPrevDuration,
                    prevTopic = splitPrevTopic,
                    prevMode = splitPrevMode,
                    prevBreak = splitPrevBreak,
                    prevTaskId = splitPrevTaskId,
                    prevExitCount = splitPrevExitCount
                )
            }
        } else if (shouldSyncReclassified) {
            scope.launch { syncActiveSessionToDb() }
        }
    }

    /** Convenience delegate that finalizes the previous segment and switches to [newSubjectId]. */
    fun finishCurrentSegmentAndSwitch(
        newSubjectId: Long?,
        newTopic: String? = null
    ) {
        switchSubjectDuringStudy(
            newSubjectId = newSubjectId,
            splitPreviousSegment = true,
            newTopic = newTopic
        )
    }

    fun setTopic(topic: String) {
        synchronized(stateLock) {
            _snapshot.value = _snapshot.value.copy(topic = topic)
            persistToPreferences()
        }
    }

    fun setLinkedNoteId(noteId: Long?) {
        synchronized(stateLock) {
            _snapshot.value = _snapshot.value.copy(linkedNoteId = noteId)
            persistToPreferences()
        }
    }

    fun setLinkedTaskId(taskId: Long?) {
        synchronized(stateLock) {
            taskLinkGeneration++
            _snapshot.value = _snapshot.value.copy(linkedTaskId = taskId?.takeIf { it > 0L })
            persistToPreferences()
        }
    }

    fun setMode(mode: StudyTimerMode) {
        synchronized(stateLock) {
            val current = _snapshot.value
            if (current.state != TimerState.RUNNING && currentFocusSecondsLocked() == 0L) {
                _snapshot.value = current.copy(mode = mode)
                persistToPreferences()
            }
        }
    }

    fun setCountdownTarget(seconds: Long) {
        synchronized(stateLock) {
            val current = _snapshot.value
            if (current.state != TimerState.RUNNING) {
                _snapshot.value = current.copy(countdownTargetSeconds = seconds.coerceIn(60L, 24 * 3600L))
                persistToPreferences()
            }
        }
    }

    fun setPomodoroSettings(settings: PomodoroSettings) {
        val sanitized = settings.sanitized()
        synchronized(stateLock) {
            val current = _snapshot.value
            _snapshot.value = current.copy(pomodoroSettings = sanitized)
            persistToPreferences()
        }
    }

    fun setAlertPreferences(soundOnStart: Boolean, soundOnEnd: Boolean, vibrateOnAlert: Boolean) {
        synchronized(stateLock) {
            val current = _snapshot.value
            _snapshot.value = current.copy(
                soundOnStart = soundOnStart,
                soundOnEnd = soundOnEnd,
                vibrateOnAlert = vibrateOnAlert
            )
            persistToPreferences()
        }
    }

    fun startOrResume() {
        val wasIdle: Boolean
        synchronized(stateLock) {
            val current = _snapshot.value
            if (current.state == TimerState.RUNNING) return
            wasIdle = current.state == TimerState.IDLE && accumulatedFocusSeconds == 0L
            val nowMonotonic = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()

            if (current.state == TimerState.PAUSED && lastPauseMonotonic > 0L) {
                val pauseDelta = ((nowMonotonic - lastPauseMonotonic) / 1000L).coerceAtLeast(0L)
                accumulatedBreakSeconds += pauseDelta
                if (current.mode == StudyTimerMode.POMODORO && current.pomodoroPhase != PomodoroPhase.FOCUS) {
                    phaseBreakBaseSeconds += pauseDelta
                }
                lastPauseMonotonic = 0L
            }

            if (startedWallEpoch == 0L) {
                startedWallEpoch = nowWall - (accumulatedFocusSeconds * 1000L)
            }

            lastResumeMonotonic = nowMonotonic
            lastResumeWall = nowWall

            _snapshot.value = current.copy(
                state = TimerState.RUNNING,
                startedWallEpoch = startedWallEpoch,
                elapsedFocusSeconds = accumulatedFocusSeconds,
                elapsedBreakSeconds = accumulatedBreakSeconds,
                recoveredFromKill = false
            )
            persistToPreferences(synchronous = true)
        }

        if (wasIdle) {
            playStartAlert()
        }

        startForegroundService()
        scope.launch { syncActiveSessionToDb() }
    }

    fun pause() {
        val changed = synchronized(stateLock) {
            val current = _snapshot.value
            if (current.state != TimerState.RUNNING) {
                false
            } else {
                val nowMonotonic = SystemClock.elapsedRealtime()

                val deltaMs = (nowMonotonic - lastResumeMonotonic).coerceAtLeast(0L)
                if (current.mode == StudyTimerMode.POMODORO && current.pomodoroPhase != PomodoroPhase.FOCUS) {
                    addBreakMsLocked(deltaMs)
                } else {
                    addFocusMsLocked(deltaMs)
                }

                lastPauseMonotonic = nowMonotonic
                lastResumeMonotonic = 0L

                val pausedPhaseElapsed = when {
                    current.mode != StudyTimerMode.POMODORO -> 0L
                    current.pomodoroPhase == PomodoroPhase.FOCUS -> (accumulatedFocusSeconds - phaseFocusBaseSeconds).coerceAtLeast(0L)
                    else -> (accumulatedBreakSeconds - phaseBreakBaseSeconds).coerceAtLeast(0L)
                }
                _snapshot.value = current.copy(
                    state = TimerState.PAUSED,
                    elapsedFocusSeconds = accumulatedFocusSeconds,
                    elapsedBreakSeconds = accumulatedBreakSeconds,
                    pomodoroPhaseElapsedSeconds = pausedPhaseElapsed
                )

                persistToPreferences(synchronous = true)
                true
            }
        }
        if (changed) {
            scope.launch { syncActiveSessionToDb() }
        }
    }

    fun toggle() {
        val isRunning = synchronized(stateLock) { _snapshot.value.state == TimerState.RUNNING }
        if (isRunning) {
            pause()
        } else {
            startOrResume()
        }
    }

    fun skipPomodoroBreak() {
        synchronized(stateLock) {
            val current = _snapshot.value
            if (current.mode == StudyTimerMode.POMODORO && current.pomodoroPhase != PomodoroPhase.FOCUS) {
                val nowMonotonic = SystemClock.elapsedRealtime()

                if (current.state == TimerState.RUNNING) {
                    if (lastResumeMonotonic <= 0L) lastResumeMonotonic = nowMonotonic
                    accumulatedBreakSeconds += ((nowMonotonic - lastResumeMonotonic) / 1000L).coerceAtLeast(0L)
                } else if (lastPauseMonotonic > 0L) {
                    accumulatedBreakSeconds += ((nowMonotonic - lastPauseMonotonic) / 1000L).coerceAtLeast(0L)
                    lastPauseMonotonic = 0L
                }

                phaseFocusBaseSeconds = accumulatedFocusSeconds
                lastResumeMonotonic = nowMonotonic
                _snapshot.value = current.copy(
                    pomodoroPhase = PomodoroPhase.FOCUS,
                    elapsedBreakSeconds = accumulatedBreakSeconds,
                    pomodoroPhaseElapsedSeconds = 0L
                )
                persistToPreferences()
            }
        }
    }

    suspend fun saveAndFinish(): StudySession? = mutex.withLock {
        val current: SessionSnapshot
        val finalDuration: Long
        val finalBreak: Long
        val started: Long
        val nowWall = System.currentTimeMillis()
        val existingDbId: Long?
        val capturedGen: Long

        synchronized(stateLock) {
            current = _snapshot.value
            val nowMonotonic = SystemClock.elapsedRealtime()
            flushElapsedLocked(nowMonotonic)
            finalDuration = accumulatedFocusSeconds.coerceAtLeast(0L)
            finalBreak = accumulatedBreakSeconds.coerceAtLeast(0L)
            started = startedWallEpoch.takeIf { it > 0 } ?: (nowWall - finalDuration * 1000L)
            existingDbId = activeSessionDbId
            capturedGen = segmentGeneration

            if (finalDuration == 0L) {
                segmentGeneration++
                taskLinkGeneration++
                clearSessionStateLocked()
            }
        }

        if (finalDuration == 0L) {
            if (existingDbId != null) {
                try {
                    dao.deleteSession(existingDbId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    StudyLog.w(TAG, "Failed to delete empty session id=$existingDbId", e)
                }
            }
            stopForegroundService()
            return null
        }

        val session = StudySession(
            id = existingDbId ?: 0L,
            subjectId = current.subjectId,
            startedAt = started,
            endedAt = nowWall.coerceAtLeast(started),
            durationSeconds = finalDuration,
            isActive = false,
            topic = current.topic.trim(),
            mode = current.mode.name,
            breakSeconds = finalBreak,
            taskId = current.linkedTaskId,
            appExitCount = current.appExitCount,
            purityPercent = computePurityPercent(current.appExitCount)
        )

        val savedSession = try {
            if (existingDbId == null) {
                session.copy(id = dao.addSession(session))
            } else {
                dao.updateSession(session)
                session
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StudyLog.e(
                TAG,
                "Failed to persist finished study session (subjectId=${current.subjectId}, activeSessionDbId=$existingDbId)",
                e
            )
            return null
        }

        synchronized(stateLock) {
            if (capturedGen == segmentGeneration) {
                clearSessionStateLocked()
            }
        }
        stopForegroundService()
        return savedSession
    }

    fun resetOrDiscard() {
        val dbIdToDelete: Long?
        synchronized(stateLock) {
            val genAtDiscard = ++taskLinkGeneration
            segmentGeneration++
            dbIdToDelete = activeSessionDbId
            clearSessionStateLocked(clearTaskIfGenMatches = genAtDiscard)
        }
        scope.launch {
            mutex.withLock {
                dbIdToDelete?.let { sessionId ->
                    try {
                        dao.deleteSession(sessionId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StudyLog.w(
                            TAG,
                            "Failed to delete discarded session id=$sessionId: ${e::class.java.simpleName}",
                            e
                        )
                    }
                }
                stopForegroundService()
            }
        }
    }

    private fun clearSessionStateLocked(clearTaskIfGenMatches: Long? = null) {
        lastResumeMonotonic = 0L
        lastResumeWall = 0L
        lastPauseMonotonic = 0L
        accumulatedFocusSeconds = 0L
        accumulatedBreakSeconds = 0L
        phaseFocusBaseSeconds = 0L
        phaseBreakBaseSeconds = 0L
        startedWallEpoch = 0L
        activeSessionDbId = null
        lastAutoSavedSeconds = -1L

        val current = _snapshot.value
        val nextTaskId = if (clearTaskIfGenMatches == null || clearTaskIfGenMatches == taskLinkGeneration) {
            null
        } else {
            current.linkedTaskId
        }
        _snapshot.value = current.copy(
            state = TimerState.IDLE,
            elapsedFocusSeconds = 0L,
            elapsedBreakSeconds = 0L,
            pomodoroPhase = PomodoroPhase.FOCUS,
            pomodoroCycle = 0,
            activeSessionDbId = null,
            linkedTaskId = nextTaskId,
            recoveredFromKill = false,
            appExitCount = 0,
            pomodoroPhaseElapsedSeconds = 0L
        )

        persistToPreferences(synchronous = true)
    }

    private suspend fun persistSplitSegmentToDb(
        prevDbId: Long?,
        prevSubjectId: Long?,
        prevStarted: Long,
        prevEnded: Long,
        prevDuration: Long,
        prevTopic: String,
        prevMode: String,
        prevBreak: Long,
        prevTaskId: Long?,
        prevExitCount: Int = 0
    ) = mutex.withLock {
        if (prevDuration <= 0L) {
            if (prevDbId != null) {
                try {
                    dao.deleteSession(prevDbId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    StudyLog.w(TAG, "Failed to delete empty split segment id=$prevDbId", e)
                }
            }
            return@withLock
        }

        val session = StudySession(
            id = prevDbId ?: 0L,
            subjectId = prevSubjectId,
            startedAt = prevStarted,
            endedAt = prevEnded.coerceAtLeast(prevStarted),
            durationSeconds = prevDuration,
            isActive = false,
            topic = prevTopic,
            mode = prevMode,
            breakSeconds = prevBreak,
            taskId = prevTaskId,
            appExitCount = prevExitCount,
            purityPercent = computePurityPercent(prevExitCount)
        )

        try {
            if (prevDbId == null) {
                dao.addSession(session)
            } else {
                dao.updateSession(session)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StudyLog.e(
                TAG,
                "Failed to persist split study segment (subjectId=$prevSubjectId, prevDbId=$prevDbId)",
                e
            )
        }
    }

    private suspend fun syncActiveSessionToDb() = mutex.withLock {
        var current: SessionSnapshot? = null
        var started = 0L
        var existingDbId: Long? = null
        var capturedGen = 0L

        synchronized(stateLock) {
            val snap = _snapshot.value
            val focusSecs = snap.elapsedFocusSeconds
            if (focusSecs > 0L && snap.state != TimerState.IDLE) {
                current = snap
                started = startedWallEpoch.takeIf { it > 0 } ?: (System.currentTimeMillis() - focusSecs * 1000L)
                existingDbId = activeSessionDbId
                capturedGen = segmentGeneration
            }
        }
        val activeSnapshot = current ?: return@withLock

        val nowWall = System.currentTimeMillis()
        val session = StudySession(
            id = existingDbId ?: 0L,
            subjectId = activeSnapshot.subjectId,
            startedAt = started,
            endedAt = nowWall.coerceAtLeast(started),
            durationSeconds = activeSnapshot.elapsedFocusSeconds,
            isActive = true,
            topic = activeSnapshot.topic.trim(),
            mode = activeSnapshot.mode.name,
            breakSeconds = activeSnapshot.elapsedBreakSeconds,
            taskId = activeSnapshot.linkedTaskId,
            appExitCount = activeSnapshot.appExitCount,
            purityPercent = computePurityPercent(activeSnapshot.appExitCount)
        )

        try {
            if (existingDbId == null) {
                val newId = dao.addSession(session)
                val stillSameSegment = synchronized(stateLock) {
                    if (capturedGen == segmentGeneration) {
                        activeSessionDbId = newId
                        _snapshot.value = _snapshot.value.copy(activeSessionDbId = newId)
                        persistToPreferences()
                        true
                    } else {
                        false
                    }
                }
                if (!stillSameSegment) {
                    runCatching { dao.deleteSession(newId) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            StudyLog.w(TAG, "Failed to clean up stale synced session id=$newId", it)
                        }
                }
            } else {
                dao.updateSession(session)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StudyLog.w(
                TAG,
                "Failed to sync active study session to DB (subjectId=${activeSnapshot.subjectId}, activeSessionDbId=$existingDbId): ${e::class.java.simpleName}",
                e
            )
        }
    }

    private fun startForegroundService() {
        try {
            val intent = Intent(appContext, StudySessionService::class.java).apply {
                action = StudySessionService.ACTION_START
            }
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            StudyLog.w(TAG, "Unable to start StudySessionService in foreground: ${e::class.java.simpleName}", e)
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(appContext, StudySessionService::class.java).apply {
                action = StudySessionService.ACTION_STOP
            }
            appContext.stopService(intent)
        } catch (e: Exception) {
            StudyLog.w(TAG, "Unable to stop StudySessionService: ${e::class.java.simpleName}", e)
        }
    }

    private fun triggerVibration(oneShotMs: Long? = null, waveform: LongArray? = null, contextLabel: String) {
        try {
            @Suppress("DEPRECATION")
            val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator == null) {
                StudyLog.w(TAG, "Vibrator service unavailable for $contextLabel")
                return
            }
            val effect = when {
                oneShotMs != null -> VibrationEffect.createOneShot(oneShotMs, VibrationEffect.DEFAULT_AMPLITUDE)
                waveform != null -> VibrationEffect.createWaveform(waveform, -1)
                else -> return
            }
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            StudyLog.w(TAG, "Failed to vibrate for $contextLabel: ${e::class.java.simpleName}", e)
        }
    }

    private fun playStartAlert() {
        val s = _snapshot.value
        if (s.vibrateOnAlert) {
            triggerVibration(oneShotMs = 45L, contextLabel = "timer_start")
        }
        if (s.soundOnStart) {
            scope.launch(Dispatchers.Default) {
                try {
                    val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55)
                    toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                    delay(200)
                    runCatching { toneGen.release() }
                        .onFailure { StudyLog.w(TAG, "Failed to release start alert ToneGenerator", it) }
                } catch (e: Exception) {
                    StudyLog.w(TAG, "Failed to play timer start tone: ${e::class.java.simpleName}", e)
                }
            }
        }
    }

    private fun playCompletionAlert(isBreak: Boolean) {
        val s = _snapshot.value
        if (s.vibrateOnAlert) {
            triggerVibration(waveform = longArrayOf(0, 350, 150, 350), contextLabel = "timer_completion(isBreak=$isBreak)")
        }
        if (StudyNotificationPrefs.isTimerEndNotificationEnabled(appContext)) {
            val alertTitle = when {
                isBreak -> "Pausa encerrada"
                s.mode == StudyTimerMode.POMODORO -> "Ciclo Pomodoro concluído!"
                else -> "Meta do temporizador concluída!"
            }
            val alertText = when {
                isBreak -> "Hora de voltar ao próximo bloco de foco no StudyDock."
                s.topic.isNotBlank() -> "Bloco em \"${s.topic}\" finalizado. Bom trabalho!"
                else -> "Seu tempo programado foi concluído."
            }
            TimerAlerts.alert(
                context = appContext,
                title = alertTitle,
                text = alertText,
                vibrate = false,
                silent = true
            )
        }
        if (s.soundOnEnd) {
            scope.launch(Dispatchers.Default) {
                try {
                    val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val ringtone = notificationUri?.let { RingtoneManager.getRingtone(appContext, it) }
                    if (ringtone == null) {
                        StudyLog.w(TAG, "Default notification ringtone unavailable (isBreak=$isBreak)")
                        return@launch
                    }
                    runCatching { activeRingtone?.stop() }
                    activeRingtone = ringtone
                    ringtone.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    ringtone.play()
                } catch (e: Exception) {
                    StudyLog.w(TAG, "Failed to play completion ringtone (isBreak=$isBreak): ${e::class.java.simpleName}", e)
                }
            }
        }
    }

    companion object {
        fun computePurityPercent(appExitCount: Int): Int =
            (100 - appExitCount.coerceAtLeast(0) * 10).coerceIn(0, 100)

        fun flowmodoroEarnedBreakSeconds(focusSeconds: Long): Long =
            (focusSeconds / 5L).coerceAtLeast(60L)

        fun getGlobalDayStartHour(context: Context): Int =
            context.applicationContext.getSharedPreferences("studydock_global_settings", Context.MODE_PRIVATE)
                .getInt("day_start_hour", 4).coerceIn(0, 12)

        fun setGlobalDayStartHour(context: Context, hour: Int) {
            context.applicationContext.getSharedPreferences("studydock_global_settings", Context.MODE_PRIVATE)
                .edit().putInt("day_start_hour", hour.coerceIn(0, 12)).apply()
        }

        private const val TAG = "StudySessionManager"

        @Volatile
        private var instance: StudySessionManager? = null

        fun get(
            context: Context,
            dao: StudyDao,
            @Suppress("UNUSED_PARAMETER") scope: CoroutineScope? = null
        ): StudySessionManager {
            return instance?.also { it.updateDao(dao) } ?: synchronized(this) {
                instance?.also { it.updateDao(dao) } ?: StudySessionManager(
                    appContext = context.applicationContext,
                    dao = dao
                ).also { instance = it }
            }
        }

        fun getInstanceOrNull(): StudySessionManager? = instance
    }
}

object FlipToFocusDetector {
    /**
     * Detecta celular virado com a tela para baixo na mesa:
     * eixo Z do acelerômetro < -8.0 m/s² e sensor de proximidade próximo (<= 1.0 cm).
     */
    fun isFaceDownOnTable(accelZ: Float, proximityCm: Float, maxProximityCm: Float = 5f): Boolean {
        val near = proximityCm <= minOf(1.0f, maxProximityCm * 0.25f)
        return accelZ <= -8.0f && near
    }

    fun computeOvertimeSeconds(elapsedFocusSeconds: Long, dailyGoalSeconds: Long): Long =
        if (dailyGoalSeconds > 0L && elapsedFocusSeconds > dailyGoalSeconds) {
            elapsedFocusSeconds - dailyGoalSeconds
        } else 0L
}
