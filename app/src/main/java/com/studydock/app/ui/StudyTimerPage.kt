@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.studydock.app.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.*
import com.studydock.app.timer.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudyTimerPage(
    sessionManager: StudySessionManager,
    subjects: List<Subject>,
    sessions: List<StudySession>,
    modifier: Modifier = Modifier,
    notes: List<Note> = emptyList(),
    flashcards: List<Flashcard> = emptyList(),
    tasks: List<StudyTask> = emptyList(),
    onOpenSubjects: () -> Unit,
    onOpenSounds: () -> Unit,
    onReviewFlashcards: ((List<Flashcard>) -> Unit)? = null,
    onOpenFlashcards: (() -> Unit)? = null,
    hasOpenScheduledRound: Boolean = false,
    onCramFlashcards: ((List<Flashcard>) -> Unit)? = null,
    onResumeOpenRound: (() -> Unit)? = null,
    onCompleteTask: ((StudyTask) -> Unit)? = null,
    onOpenNote: ((Long) -> Unit)? = null,
    onToggleSubtask: ((StudyTask, String) -> Unit)? = null,
    onSaveTask: ((StudyTask) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val snapshot by sessionManager.snapshot.collectAsStateWithLifecycle()
    val isRunning = snapshot.state == TimerState.RUNNING
    val isPaused = snapshot.state == TimerState.PAUSED
    val isCompleted = snapshot.state == TimerState.COMPLETED
    val subjectById = remember(subjects) { subjects.associateBy { it.id } }
    val activeOrSelectedSubjects = remember(subjects, snapshot.subjectId) {
        subjects.filter { !it.archived || it.id == snapshot.subjectId }
    }
    val orderedSubjects = remember(activeOrSelectedSubjects) { SubjectHierarchy.orderedHierarchically(activeOrSelectedSubjects) }
    val selectedSubject = subjectById[snapshot.subjectId]
    val selectedSubjectLabel = selectedSubject?.let { SubjectHierarchy.displayName(it, subjectById) }

    // Keep screen on while running
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(isRunning) {
        view.keepScreenOn = isRunning
        onDispose { view.keepScreenOn = false }
    }

    var showPomodoroConfigDialog by remember { mutableStateOf(false) }
    var showCountdownPicker by remember { mutableStateOf(false) }
    var showTopicDialog by remember { mutableStateOf(false) }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    var showFocusModeDialog by remember { mutableStateOf(false) }
    var showFocusSettingsDialog by remember { mutableStateOf(false) }
    var showNotePickerDialog by remember { mutableStateOf(false) }
    var showNoteViewerSheet by remember { mutableStateOf(false) }
    var showSubjectSwitchSheet by remember { mutableStateOf(false) }
    var pendingSwitchTargetId by remember { mutableStateOf<Long?>(null) }
    var hasExplicitSwitchTarget by remember { mutableStateOf(false) }
    var taskToSuggestCompletion by remember { mutableStateOf<StudyTask?>(null) }

    fun openQuickSubjectPicker() {
        pendingSwitchTargetId = null
        hasExplicitSwitchTarget = false
        showSubjectSwitchSheet = true
    }

    fun requestSubjectSelection(targetSubjectId: Long?) {
        if (targetSubjectId == snapshot.subjectId) return
        if (snapshot.elapsedFocusSeconds == 0L) {
            sessionManager.setSubject(targetSubjectId)
        } else {
            pendingSwitchTargetId = targetSubjectId
            hasExplicitSwitchTarget = true
            showSubjectSwitchSheet = true
        }
    }

    // Daily statistics
    val todayMidnight = remember(sessions.size, isRunning) {
        val zone = java.time.ZoneId.systemDefault()
        java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val todaySessions = remember(sessions, todayMidnight) {
        sessions.filter { it.startedAt >= todayMidnight }
    }
    val completedTodaySessions = remember(todaySessions) {
        todaySessions.filter { !it.isActive }
    }
    val todayFocusSeconds = remember(completedTodaySessions, snapshot.elapsedFocusSeconds, isRunning, isPaused, isCompleted) {
        completedTodaySessions.sumOf { it.durationSeconds } +
                (if (isRunning || isPaused || isCompleted) snapshot.elapsedFocusSeconds else 0L)
    }

    // Ghost Pacer: 7-day daily average comparison
    val sevenDayAvgSeconds = remember(sessions, todayMidnight) {
        val sevenDaysAgo = todayMidnight - 7 * 86_400_000L
        val pastWeek = sessions.filter { !it.isActive && it.startedAt in sevenDaysAgo until todayMidnight }
        if (pastWeek.isEmpty()) 0L else pastWeek.sumOf { it.durationSeconds } / 7L
    }

    // Daily goal: same rule as Dashboard/Planner (subject targets, else saved global goal)
    val dailyGoalMinutes = rememberDailyGoalResolution(subjects).goalMinutes
    val dailyGoalSeconds = dailyGoalMinutes * 60L
    val dailyGoalProgress = (todayFocusSeconds.toFloat() / dailyGoalSeconds.coerceAtLeast(1L)).coerceIn(0f, 1f)

    // Ecosystem integrations data
    var nowEpoch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val subjectAllCards = remember(flashcards, selectedSubject) {
        if (selectedSubject == null) emptyList()
        else flashcards.filter { !it.suspended && it.subjectId == selectedSubject.id }
    }
    LaunchedEffect(subjectAllCards) {
        nowEpoch = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val nextDue = subjectAllCards.asSequence()
                .map { it.dueAt }
                .filter { it > now }
                .minOrNull()
            val waitMs = if (nextDue != null) (nextDue - now).coerceIn(500L, 15_000L) else 15_000L
            delay(waitMs)
            nowEpoch = System.currentTimeMillis()
        }
    }
    val logicalToday = remember(nowEpoch) { ankiLogicalDay(nowEpoch, 4) }
    val subjectDueCards = remember(subjectAllCards, nowEpoch, logicalToday) {
        subjectAllCards.filter { it.dueAt <= nowEpoch && !it.isBuriedOn(logicalToday) }
    }
    var pendingCramBatch by remember { mutableStateOf<List<Flashcard>?>(null) }
    val launchSubjectDueOrProtect: () -> Unit = {
        if (subjectDueCards.isNotEmpty()) {
            if (hasOpenScheduledRound && (onCramFlashcards != null || onResumeOpenRound != null)) {
                pendingCramBatch = subjectDueCards
            } else {
                onReviewFlashcards?.invoke(subjectDueCards)
            }
        }
    }
    val launchSubjectCramOrProtect: (List<Flashcard>) -> Unit = { batch ->
        val active = batch.filter { !it.suspended }
        if (active.isNotEmpty() && onCramFlashcards != null) {
            if (hasOpenScheduledRound) {
                pendingCramBatch = active
            } else {
                onCramFlashcards(active)
            }
        }
    }
    val linkedNote = remember(notes, snapshot.linkedNoteId) {
        notes.firstOrNull { it.id == snapshot.linkedNoteId }
    }
    val linkedTask = remember(tasks, snapshot.linkedTaskId) {
        snapshot.linkedTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    }
    val linkedTaskSavedSeconds = remember(sessions, linkedTask?.id) {
        val taskId = linkedTask?.id ?: return@remember 0L
        sessions.filter { !it.isActive && it.taskId == taskId }.sumOf { it.durationSeconds }
    }
    val todayFocusForSubject = remember(completedTodaySessions, selectedSubject, snapshot.elapsedFocusSeconds, isRunning, isPaused, isCompleted) {
        if (selectedSubject == null) 0L
        else completedTodaySessions.filter { it.subjectId == selectedSubject.id }.sumOf { it.durationSeconds } +
                (if (isRunning || isPaused || isCompleted) snapshot.elapsedFocusSeconds else 0L)
    }

    fun finishAndSaveSession() {
        val currentTopic = snapshot.topic.trim()
        val currentSubjectId = snapshot.subjectId
        val currentLinkedTaskId = snapshot.linkedTaskId
        coroutineScope.launch {
            val session = sessionManager.saveAndFinish()
            if (session != null) {
                val matchingTask = currentLinkedTaskId?.let { id ->
                    tasks.firstOrNull { it.id == id && !it.completed }
                } ?: if (currentTopic.isNotBlank()) {
                    tasks.firstOrNull { task ->
                        !task.completed && (task.subjectId == null || task.subjectId == currentSubjectId) &&
                            (task.title.equals(currentTopic, ignoreCase = true) || task.title.contains(currentTopic, ignoreCase = true))
                    }
                } else null
                if (matchingTask != null) {
                    taskToSuggestCompletion = matchingTask
                }
            }
        }
    }

    val semantic = LocalStudySemanticColors.current
    val sectionSpace = StudyTokens.sectionSpacing()
    val cardPad = StudyTokens.cardPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        StudyPageHeader(
            title = "Foco & Estudo",
            subtitle = "Cronômetro, Pomodoro e sessões",
            showGlobalSearch = true,
            actions = {
                IconButton(onClick = { showFocusModeDialog = true }) {
                    Icon(Icons.Outlined.Fullscreen, "Modo Foco Imersivo")
                }
            }
        )

        Spacer(Modifier.height(StudyTokens.itemSpacing()))

        // TOP BAR: Daily Odometer (Inspired by YPT's bold study tracker)
        StudyCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = sectionSpace),
            isHero = true
        ) {
            Column(Modifier.padding(cardPad)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            "TEMPO TOTAL HOJE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatDurationHMS(todayFocusSeconds),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showFocusSettingsDialog = true }) {
                            Icon(
                                if (snapshot.soundOnEnd || snapshot.vibrateOnAlert) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                                "Alertas e Controle de Distrações"
                            )
                        }
                        IconButton(onClick = onOpenSounds) {
                            Icon(Icons.Outlined.Headphones, "Sons relaxantes")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Daily goal bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Meta diária: ${dailyGoalMinutes / 60}h ${if (dailyGoalMinutes % 60 > 0) "${dailyGoalMinutes % 60}m" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${(dailyGoalProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (dailyGoalProgress >= 1f) semantic.success else MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { dailyGoalProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (dailyGoalProgress >= 1f) semantic.success else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                if (sevenDayAvgSeconds > 0L || snapshot.appExitCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (sevenDayAvgSeconds > 0L) {
                            val deltaSec = todayFocusSeconds - sevenDayAvgSeconds
                            val pacerText = if (deltaSec >= 0L) {
                                "⚡ Ghost Pacer: +${formatShortDuration(deltaSec)} acima da sua média (7d)"
                            } else {
                                "👻 Ghost Pacer: faltam ${formatShortDuration(-deltaSec)} p/ sua média (7d)"
                            }
                            Text(
                                pacerText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (deltaSec >= 0L) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (snapshot.appExitCount > 0) {
                            val purity = (100 - snapshot.appExitCount * 5).coerceIn(0, 100)
                            Text(
                                "🛡️ ${snapshot.appExitCount} saída(s) · Pureza $purity%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // MODE SELECTOR: [Cronômetro | Pomodoro | Regressivo | Flow]
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = sectionSpace)
        ) {
            SegmentedButton(
                selected = snapshot.mode == StudyTimerMode.STOPWATCH,
                onClick = { sessionManager.setMode(StudyTimerMode.STOPWATCH) },
                enabled = !isRunning && snapshot.elapsedFocusSeconds == 0L,
                shape = SegmentedButtonDefaults.itemShape(0, 4),
                icon = { Icon(Icons.Default.Timer, null, Modifier.size(16.dp)) }
            ) {
                Text("Cronômetro", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }

            SegmentedButton(
                selected = snapshot.mode == StudyTimerMode.POMODORO,
                onClick = { sessionManager.setMode(StudyTimerMode.POMODORO) },
                enabled = !isRunning && snapshot.elapsedFocusSeconds == 0L,
                shape = SegmentedButtonDefaults.itemShape(1, 4),
                icon = { Icon(Icons.Default.Timelapse, null, Modifier.size(16.dp)) }
            ) {
                Text("Pomodoro", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }

            SegmentedButton(
                selected = snapshot.mode == StudyTimerMode.COUNTDOWN,
                onClick = { sessionManager.setMode(StudyTimerMode.COUNTDOWN) },
                enabled = !isRunning && snapshot.elapsedFocusSeconds == 0L,
                shape = SegmentedButtonDefaults.itemShape(2, 4),
                icon = { Icon(Icons.Default.HourglassTop, null, Modifier.size(16.dp)) }
            ) {
                Text("Regressivo", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }

            SegmentedButton(
                selected = snapshot.mode == StudyTimerMode.FLOWMODORO,
                onClick = { sessionManager.setMode(StudyTimerMode.FLOWMODORO) },
                enabled = !isRunning && snapshot.elapsedFocusSeconds == 0L,
                shape = SegmentedButtonDefaults.itemShape(3, 4),
                icon = { Icon(Icons.Default.Waves, null, Modifier.size(16.dp)) }
            ) {
                Text("Flow", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }

        // SUBJECT SELECTION BAR (Chips with today's study count per subject + Geral / Livre)
        if (subjects.isEmpty()) {
            StudyCard(
                onClick = onOpenSubjects,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = StudyTokens.itemSpacing())
            ) {
                Row(Modifier.padding(cardPad), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Cadastre sua primeira matéria", fontWeight = FontWeight.Bold)
                        Text(
                            "Toque aqui para cadastrar matérias ou inicie direto em Geral / Livre.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = StudyTokens.itemSpacing()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isGeneralChosen = snapshot.subjectId == null
            val generalTodaySeconds = completedTodaySessions.filter { it.subjectId == null }.sumOf { it.durationSeconds } +
                (if (isGeneralChosen && (isRunning || isPaused || isCompleted)) snapshot.elapsedFocusSeconds else 0L)
            FilterChip(
                selected = isGeneralChosen,
                onClick = { requestSubjectSelection(null) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Geral / Livre", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (generalTodaySeconds > 0L) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                formatShortDuration(generalTodaySeconds),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isGeneralChosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )

            orderedSubjects.forEach { subject ->
                val isChosen = snapshot.subjectId == subject.id
                val subjectColor = Color(subject.color.toInt())
                val subjectTodaySeconds = completedTodaySessions.filter { it.subjectId == subject.id }.sumOf { it.durationSeconds } +
                    (if (isChosen && (isRunning || isPaused || isCompleted)) snapshot.elapsedFocusSeconds else 0L)
                val subjectDisplay = SubjectHierarchy.displayName(subject, subjectById)

                FilterChip(
                    selected = isChosen,
                    onClick = { requestSubjectSelection(subject.id) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(subjectDisplay, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subjectTodaySeconds > 0L) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    formatShortDuration(subjectTodaySeconds),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isChosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(subjectColor)
                        )
                    }
                )
            }

            IconButton(onClick = onOpenSubjects) {
                Icon(Icons.Default.Settings, "Gerenciar matérias", Modifier.size(20.dp))
            }
        }

        // TOPIC & TARGET SUB-HEADER
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = sectionSpace),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Topic chip
            Surface(
                onClick = { showTopicDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .heightIn(min = 48.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.EditNote, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        snapshot.topic.ifBlank { "Definir assunto/meta..." },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Mode-specific adjustment button
            when (snapshot.mode) {
                StudyTimerMode.POMODORO -> {
                    TextButton(
                        onClick = { showPomodoroConfigDialog = true },
                        enabled = !isRunning,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Tune, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${snapshot.pomodoroSettings.focusMin}/${snapshot.pomodoroSettings.shortMin} min",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                StudyTimerMode.COUNTDOWN -> {
                    TextButton(
                        onClick = { showCountdownPicker = true },
                        enabled = !isRunning,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${snapshot.countdownTargetSeconds / 60} min",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                StudyTimerMode.FLOWMODORO -> {
                    val earned = StudySessionManager.flowmodoroEarnedBreakSeconds(snapshot.elapsedFocusSeconds)
                    Text(
                        "Flowmodoro · Pausa ganha: ${earned / 60}m · Pureza ${(100 - snapshot.appExitCount * 5).coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    if (!isRunning && earned >= 60L) {
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    sessionManager.saveAndFinish()
                                    sessionManager.setMode(StudyTimerMode.COUNTDOWN)
                                    sessionManager.setCountdownTarget(earned)
                                    sessionManager.setTopic("Pausa Flowmodoro (${earned / 60}m)")
                                    sessionManager.toggle()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Text("☕ Usar pausa (${earned / 60}m)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                StudyTimerMode.STOPWATCH -> {
                    // Stopwatch has no fixed target
                }
            }
        }

        // MAIN HERO TIMER DIAL
        StudyCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            isHero = true
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(cardPad),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header badge + active subject quick switcher
                val statusText = when {
                    snapshot.state == TimerState.COMPLETED -> "META CONCLUÍDA!"
                    snapshot.mode == StudyTimerMode.POMODORO && snapshot.pomodoroPhase != PomodoroPhase.FOCUS -> {
                        val isLong = snapshot.pomodoroPhase == PomodoroPhase.LONG_BREAK
                        if (isRunning) "EM PAUSA ${if (isLong) "LONGA" else "CURTA"}" else "PAUSA PAUSADA"
                    }
                    isRunning -> "EM FOCO CONTÍNUO"
                    isPaused -> "SESSÃO PAUSADA"
                    else -> "PRONTO PARA COMEÇAR"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = when {
                            snapshot.state == TimerState.COMPLETED -> semantic.successContainer
                            snapshot.mode == StudyTimerMode.POMODORO && snapshot.pomodoroPhase != PomodoroPhase.FOCUS -> MaterialTheme.colorScheme.secondaryContainer
                            isRunning -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            statusText,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = when {
                                snapshot.state == TimerState.COMPLETED -> semantic.onSuccessContainer
                                snapshot.mode == StudyTimerMode.POMODORO && snapshot.pomodoroPhase != PomodoroPhase.FOCUS -> MaterialTheme.colorScheme.onSecondaryContainer
                                isRunning -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    FilledTonalButton(
                        onClick = { openQuickSubjectPicker() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 34.dp),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Default.SwapHoriz, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Trocar",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Surface(
                    onClick = { openQuickSubjectPicker() },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedSubject != null) {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(Color(selectedSubject.color.toInt()))
                            )
                        } else {
                            Icon(
                                Icons.Outlined.AutoStories,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(
                            selectedSubjectLabel ?: "Geral / Livre",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isRunning || isPaused || isCompleted || snapshot.elapsedFocusSeconds > 0L) {
                            Text(
                                " · ${formatSwitchDuration(snapshot.elapsedFocusSeconds)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // DIAL RING
                val isPomoBreak = snapshot.mode == StudyTimerMode.POMODORO && snapshot.pomodoroPhase != PomodoroPhase.FOCUS
                val dialProgress = when (snapshot.mode) {
                    StudyTimerMode.STOPWATCH, StudyTimerMode.FLOWMODORO -> {
                        // Smooth rotating pulse / 60-second cycle
                        val secondsPart = (snapshot.elapsedFocusSeconds % 60L).toFloat()
                        secondsPart / 60f
                    }
                    StudyTimerMode.COUNTDOWN -> {
                        (snapshot.elapsedFocusSeconds.toFloat() / snapshot.countdownTargetSeconds.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    }
                    StudyTimerMode.POMODORO -> {
                        val target = if (isPomoBreak) {
                            (if (snapshot.pomodoroPhase == PomodoroPhase.LONG_BREAK) snapshot.pomodoroSettings.longMin else snapshot.pomodoroSettings.shortMin) * 60L
                        } else {
                            snapshot.pomodoroSettings.focusMin * 60L
                        }
                        (snapshot.pomodoroPhaseElapsedSeconds.toFloat() / target.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    }
                }

                val displayedSeconds = calculateDisplaySeconds(snapshot)

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val dialSize = minOf(240.dp, (maxWidth - 8.dp).coerceAtLeast(160.dp))
                    val density = LocalDensity.current
                    val clockSizeSp = ((dialSize.value * 0.158f) / density.fontScale.coerceAtLeast(1f)).coerceIn(20f, 40f).sp

                    Box(
                        modifier = Modifier.size(dialSize),
                        contentAlignment = Alignment.Center
                    ) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val secondaryColor = MaterialTheme.colorScheme.secondary
                        val successColor = semantic.success
                        val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            val strokeW = 12.dp.toPx()
                            // Background track
                            drawCircle(
                                color = trackColor,
                                style = Stroke(width = strokeW)
                            )

                            // Progress arc
                            val sweep = if (snapshot.mode == StudyTimerMode.STOPWATCH && !isRunning && snapshot.elapsedFocusSeconds == 0L) {
                                0f
                            } else {
                                dialProgress * 360f
                            }

                            val arcColor = when {
                                snapshot.state == TimerState.COMPLETED -> successColor
                                isPomoBreak -> secondaryColor
                                else -> primaryColor
                            }

                            drawArc(
                                color = arcColor,
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                style = Stroke(width = strokeW, cap = StrokeCap.Round)
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Text(
                                formatTimeHMS(displayedSeconds),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    fontSize = clockSizeSp,
                                    lineHeight = clockSizeSp * 1.1f
                                ),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Break indicator below primary clock
                            if (snapshot.elapsedBreakSeconds > 0L && !isPomoBreak) {
                                Text(
                                    "Pausas: ${formatShortDuration(snapshot.elapsedBreakSeconds)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else if (snapshot.mode == StudyTimerMode.POMODORO) {
                                Text(
                                    "Ciclo ${snapshot.pomodoroCycle + 1}/${snapshot.pomodoroSettings.cycles}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // PRIMARY ACTION BUTTONS
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isPomoBreak) {
                        FilledTonalButton(
                            onClick = { sessionManager.skipPomodoroBreak() },
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Pular pausa", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Button(
                        onClick = { sessionManager.toggle() },
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null,
                            Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isRunning -> "Pausar"
                                isPaused -> "Retomar"
                                snapshot.mode == StudyTimerMode.POMODORO && isPomoBreak -> "Iniciar Pausa"
                                else -> "Iniciar Foco"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (snapshot.elapsedFocusSeconds > 0L || isPaused) {
                        IconButton(
                            onClick = { showDiscardConfirmDialog = true },
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, "Descartar sessão", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // FINISH & SAVE BUTTON
                if (snapshot.elapsedFocusSeconds >= 5L || isCompleted) {
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { finishAndSaveSession() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Finalizar e Registrar Sessão",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (linkedTask != null) {
            FocusedTaskCard(
                task = linkedTask,
                elapsedFocusSeconds = snapshot.elapsedFocusSeconds,
                savedTaskSeconds = linkedTaskSavedSeconds,
                onCompleteTask = if (!linkedTask.completed && onCompleteTask != null) {
                    { onCompleteTask(linkedTask) }
                } else null,
                onUnlinkTask = { sessionManager.setLinkedTaskId(null) },
                onToggleSubtask = if (onToggleSubtask != null || onSaveTask != null) {
                    { subId ->
                        if (onToggleSubtask != null) onToggleSubtask(linkedTask, subId)
                        else onSaveTask?.invoke(
                            linkedTask.copy(subtasksJson = TaskSubtasks.toggle(linkedTask.subtasksJson, subId))
                        )
                    }
                } else null
            )
        }

        // LOTE 5: ECOSYSTEM INTEGRATION - ANKI FLASHCARDS
        if (subjectDueCards.isNotEmpty() || (selectedSubject != null && subjectAllCards.isNotEmpty())) {
            val hasDue = subjectDueCards.isNotEmpty()
            StudyCard(
                onClick = {
                    if (hasDue && onReviewFlashcards != null) {
                        launchSubjectDueOrProtect()
                    } else if (!hasDue && onCramFlashcards != null) {
                        launchSubjectCramOrProtect(subjectAllCards)
                    } else {
                        onOpenFlashcards?.invoke()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                containerColorOverride = if (hasDue) MaterialTheme.colorScheme.tertiaryContainer else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (hasDue) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Style,
                            null,
                            tint = if (hasDue) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            if (hasDue) "Revisão Anki Pendente" else "Flashcards de ${selectedSubject?.name.orEmpty()}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasDue) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (hasDue) {
                                "${subjectDueCards.size} ${if (subjectDueCards.size == 1) "cartão para revisar" else "cartões para revisar"} em ${selectedSubject?.name ?: "esta matéria"}"
                            } else {
                                "${subjectAllCards.size} ${if (subjectAllCards.size == 1) "cartão vinculado" else "cartões vinculados"} (revisões em dia)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasDue) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (onCramFlashcards != null && (!hasDue || subjectAllCards.size > subjectDueCards.size)) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "⚡ Estudo livre (${subjectAllCards.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (hasDue) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { launchSubjectCramOrProtect(subjectAllCards) }
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (hasDue && onReviewFlashcards != null) {
                                launchSubjectDueOrProtect()
                            } else {
                                onOpenFlashcards?.invoke()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasDue) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            contentColor = if (hasDue) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (hasDue) "Revisar" else "Ver Cartões",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        pendingCramBatch?.let { batch ->
            AlertDialog(
                onDismissRequest = { pendingCramBatch = null },
                title = { Text("Rodada agendada em aberto") },
                text = {
                    Text(
                        "Já existe uma rodada programada de flashcards em andamento. Retome-a para não perder seu progresso ou substitua pelo estudo livre desta matéria (${batch.size} ${if (batch.size == 1) "cartão" else "cartões"})."
                    )
                },
                confirmButton = {
                    if (onCramFlashcards != null) {
                        TextButton(
                            onClick = {
                                pendingCramBatch = null
                                onCramFlashcards(batch)
                            }
                        ) {
                            Text("Iniciar Cram", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                dismissButton = {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = { pendingCramBatch = null }) {
                            Text("Cancelar", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        if (onResumeOpenRound != null || onReviewFlashcards != null) {
                            TextButton(
                                onClick = {
                                    pendingCramBatch = null
                                    onResumeOpenRound?.invoke() ?: onReviewFlashcards?.invoke(batch)
                                }
                            ) {
                                Text("Retomar rodada", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            )
        }

        // LOTE 5: ECOSYSTEM INTEGRATION - OBSIDIAN NOTES
        StudyCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        if (linkedNote != null) linkedNote.title.ifBlank { "Nota sem título" } else "Caderno de Resumos (Obsidian)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (linkedNote != null) "Nota vinculada ao cronômetro" else "Vincule uma nota para consulta rápida durante o foco",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (linkedNote != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = { showNoteViewerSheet = true }) {
                            Icon(Icons.Default.Visibility, "Ver anotações", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { sessionManager.setLinkedNoteId(null) }) {
                            Icon(Icons.Default.Close, "Desvincular nota", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showNotePickerDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Vincular", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // TODAY'S SESSIONS FEED
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Sessões de Hoje", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${completedTodaySessions.size} sessões registradas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                formatShortDuration(completedTodaySessions.sumOf { it.durationSeconds }),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false
            )
        }

        if (completedTodaySessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.LocalLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Nenhuma sessão concluída hoje ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Inicie o cronômetro para bater sua meta diária!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            completedTodaySessions.forEach { session ->
                val subject = subjects.firstOrNull { it.id == session.subjectId }
                SessionItemCard(session, subject)
            }
        }
    }

    // TOPIC INPUT DIALOG
    if (showTopicDialog) {
        var topicDraft by remember { mutableStateOf(snapshot.topic) }
        AlertDialog(
            onDismissRequest = { showTopicDialog = false },
            title = { Text("O que você está estudando?") },
            text = {
                Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = topicDraft,
                        onValueChange = { topicDraft = it },
                        label = { Text("Assunto / Tópico / Meta") },
                        placeholder = { Text("Ex: Teoria Geral, Questões 1 a 20...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    sessionManager.setTopic(topicDraft)
                    showTopicDialog = false
                }) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTopicDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // COUNTDOWN PICKER DIALOG
    if (showCountdownPicker) {
        AlertDialog(
            onDismissRequest = { showCountdownPicker = false },
            title = { Text("Duração da Meta Regressiva") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    val presets = listOf(15, 25, 30, 45, 60, 90, 120)
                    Text("Escolha um tempo alvo:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { min ->
                            val isSelected = snapshot.countdownTargetSeconds == min * 60L
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    sessionManager.setCountdownTarget(min * 60L)
                                    showCountdownPicker = false
                                },
                                label = { Text("$min min", maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountdownPicker = false }) {
                    Text("Fechar")
                }
            }
        )
    }

    // POMODORO SETTINGS DIALOG
    if (showPomodoroConfigDialog) {
        var focusDraft by remember { mutableStateOf(snapshot.pomodoroSettings.focusMin.toString()) }
        var shortDraft by remember { mutableStateOf(snapshot.pomodoroSettings.shortMin.toString()) }
        var longDraft by remember { mutableStateOf(snapshot.pomodoroSettings.longMin.toString()) }
        var cyclesDraft by remember { mutableStateOf(snapshot.pomodoroSettings.cycles.toString()) }

        AlertDialog(
            onDismissRequest = { showPomodoroConfigDialog = false },
            title = { Text("Configurar Pomodoro") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = focusDraft,
                        onValueChange = { focusDraft = it.filter { c -> c.isDigit() } },
                        label = { Text("Foco (minutos)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = shortDraft,
                        onValueChange = { shortDraft = it.filter { c -> c.isDigit() } },
                        label = { Text("Pausa Curta (minutos)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = longDraft,
                        onValueChange = { longDraft = it.filter { c -> c.isDigit() } },
                        label = { Text("Pausa Longa (minutos)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cyclesDraft,
                        onValueChange = { cyclesDraft = it.filter { c -> c.isDigit() } },
                        label = { Text("Ciclos até pausa longa") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val settings = PomodoroSettings(
                        focusMin = focusDraft.toIntOrNull() ?: 25,
                        shortMin = shortDraft.toIntOrNull() ?: 5,
                        longMin = longDraft.toIntOrNull() ?: 15,
                        cycles = cyclesDraft.toIntOrNull() ?: 4
                    )
                    sessionManager.setPomodoroSettings(settings)
                    showPomodoroConfigDialog = false
                }) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPomodoroConfigDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // DISCARD CONFIRMATION DIALOG
    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = { Text("Descartar sessão atual?") },
            text = { Text("O tempo estudado nesta sessão não será contabilizado e a contagem será zerada.") },
            confirmButton = {
                Button(
                    onClick = {
                        sessionManager.resetOrDiscard()
                        showDiscardConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sim, descartar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // TASK COMPLETION SUGGESTION DIALOG (Ecosystem integration)
    taskToSuggestCompletion?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToSuggestCompletion = null },
            title = { Text("Tarefa concluída?") },
            text = { Text("Você acabou de registrar foco sobre \"${task.title}\". Deseja marcar essa atividade acadêmica como realizada?") },
            confirmButton = {
                Button(onClick = {
                    onCompleteTask?.invoke(task)
                    taskToSuggestCompletion = null
                }) {
                    Text("Sim, concluir tarefa")
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToSuggestCompletion = null }) {
                    Text("Manter pendente")
                }
            }
        )
    }

    // NOTE PICKER DIALOG (Obsidian integration)
    if (showNotePickerDialog) {
        var noteQuery by remember { mutableStateOf("") }
        val filteredNotes = remember(notes, noteQuery) {
            if (noteQuery.isBlank()) notes.take(15)
            else notes.filter { it.title.contains(noteQuery, ignoreCase = true) || it.markdown.contains(noteQuery, ignoreCase = true) }
        }

        AlertDialog(
            onDismissRequest = { showNotePickerDialog = false },
            title = { Text("Vincular Nota do Obsidian") },
            text = {
                Column(Modifier.fillMaxWidth().imePadding().heightIn(max = 420.dp)) {
                    OutlinedTextField(
                        value = noteQuery,
                        onValueChange = { noteQuery = it },
                        label = { Text("Buscar nota por título ou conteúdo") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true
                    )
                    if (filteredNotes.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("Nenhuma nota encontrada", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                            items(filteredNotes, key = { it.id }) { note ->
                                ListItem(
                                    headlineContent = { Text(note.title.ifBlank { "Sem título" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(note.markdown.take(60).replace("\n", " "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                                    modifier = Modifier.clickable {
                                        sessionManager.setLinkedNoteId(note.id)
                                        showNotePickerDialog = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showNotePickerDialog = false }) {
                    Text("Fechar")
                }
            }
        )
    }

    // NOTE VIEWER BOTTOM SHEET (Obsidian quick reading during timer)
    if (showNoteViewerSheet && linkedNote != null) {
        ModalBottomSheet(
            onDismissRequest = { showNoteViewerSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            linkedNote.title.ifBlank { "Sem título" },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Consulta de resumo em foco",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showNoteViewerSheet = false }) {
                        Icon(Icons.Default.Close, "Fechar")
                    }
                }

                if (onOpenNote != null) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            showNoteViewerSheet = false
                            onOpenNote(linkedNote.id)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Abrir no editor completo", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text(
                    text = linkedNote.markdown,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
            }
        }
    }

    // FOCUS DISTRACTION & ALERT SETTINGS DIALOG (Lote 4)
    if (showFocusSettingsDialog) {
        val context = LocalContext.current
        val notificationManager = remember(context) {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }
        val dndActive = remember(showFocusSettingsDialog) {
            val filter = notificationManager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }

        AlertDialog(
            onDismissRequest = { showFocusSettingsDialog = false },
            title = { Text("Alertas e Controle de Distrações") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Sons e Vibração do Cronômetro",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Som ao iniciar foco", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Sinal sonoro discreto ao dar partida", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = snapshot.soundOnStart,
                            onCheckedChange = {
                                sessionManager.setAlertPreferences(
                                    soundOnStart = it,
                                    soundOnEnd = snapshot.soundOnEnd,
                                    vibrateOnAlert = snapshot.vibrateOnAlert
                                )
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Som ao concluir ciclo/meta", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Aviso sonoro ao finalizar Pomodoro ou regressivo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = snapshot.soundOnEnd,
                            onCheckedChange = {
                                sessionManager.setAlertPreferences(
                                    soundOnStart = snapshot.soundOnStart,
                                    soundOnEnd = it,
                                    vibrateOnAlert = snapshot.vibrateOnAlert
                                )
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Vibração nos alertas", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Feedback tátil no início e término", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = snapshot.vibrateOnAlert,
                            onCheckedChange = {
                                sessionManager.setAlertPreferences(
                                    soundOnStart = snapshot.soundOnStart,
                                    soundOnEnd = snapshot.soundOnEnd,
                                    vibrateOnAlert = it
                                )
                            }
                        )
                    }

                    HorizontalDivider()

                    Text(
                        "Gerenciamento de Distrações e Notificações",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val notifEnabled = remember(showFocusSettingsDialog) {
                        TimerAlerts.hasNotificationPermission(context)
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (notifEnabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            ) {
                                Icon(
                                    if (notifEnabled) Icons.Outlined.NotificationsActive else Icons.Default.NotificationsOff,
                                    null,
                                    tint = if (notifEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (notifEnabled) "Notificações do App Ativas" else "Notificações Bloqueadas",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (notifEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        if (notifEnabled) "Timer contínuo e avisos de fim de ciclo visíveis" else "Libere para exibir timer contínuo e alertas",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (notifEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            TextButton(onClick = { TimerAlerts.openAppNotificationSettings(context) }) {
                                Text(if (notifEnabled) "Canais" else "Liberar", maxLines = 1, softWrap = false)
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (dndActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val dndPrimaryText = if (dndActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        val dndSecondaryText = if (dndActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Icon(
                                    if (dndActive) Icons.Default.DoNotDisturbOn else Icons.Outlined.NotificationsActive,
                                    null,
                                    tint = dndPrimaryText
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (dndActive) "Não Perturbe Ativo" else "Não Perturbe Desativado",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = dndPrimaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (dndActive) "Notificações externas silenciadas pelo sistema" else "Ative no Android para silenciar outros apps",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = dndSecondaryText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                var dndAuto by remember { mutableStateOf(com.studydock.app.timer.FocusDndController.isEnabled(context)) }
                                Switch(
                                    checked = dndAuto,
                                    onCheckedChange = { enabled ->
                                        if (enabled && !com.studydock.app.timer.FocusDndController.hasPolicyAccess(context)) {
                                            com.studydock.app.timer.FocusDndController.openPolicyAccessSettings(context)
                                        } else {
                                            dndAuto = enabled
                                            com.studydock.app.timer.FocusDndController.setEnabled(context, enabled)
                                        }
                                    }
                                )
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }.onFailure {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }
                            }) {
                                Text("Ajustar", maxLines = 1, softWrap = false)
                            }
                            }
                        }
                    }

                    Text(
                        "Política de Privacidade e Foco: O StudyDock não solicita permissões invasivas de acessibilidade para monitorar ou bloquear aplicativos externos. Utilize o Modo Foco Imersivo com trava de tela aliado ao Não Perturbe nativo do Android.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showFocusSettingsDialog = false }) {
                    Text("Concluído")
                }
            }
        )
    }

    // SUBJECT SWITCH MODAL BOTTOM SHEET
    if (showSubjectSwitchSheet) {
        SubjectSwitchModalSheet(
            snapshot = snapshot,
            subjects = subjects,
            initialTargetId = pendingSwitchTargetId,
            initialTargetExplicit = hasExplicitSwitchTarget,
            onDismiss = { showSubjectSwitchSheet = false },
            onImmediateSelect = { targetId ->
                sessionManager.setSubject(targetId)
                showSubjectSwitchSheet = false
            },
            onConfirmSwitch = { targetId, splitPreviousSegment, newTopic ->
                sessionManager.switchSubjectDuringStudy(
                    newSubjectId = targetId,
                    splitPreviousSegment = splitPreviousSegment,
                    newTopic = newTopic
                )
                showSubjectSwitchSheet = false
            },
            onOpenSubjects = {
                showSubjectSwitchSheet = false
                onOpenSubjects()
            }
        )
    }

    // IMMERSIVE FULLSCREEN FOCUS MODE (Lote 4 AMOLED + anti-toque acidental)
    if (showFocusModeDialog) {
        FocusModeDialog(
            snapshot = snapshot,
            subject = selectedSubject,
            subjects = subjects,
            linkedNote = linkedNote,
            linkedTask = linkedTask,
            todayFocusForSubject = todayFocusForSubject,
            onToggle = { sessionManager.toggle() },
            onFinish = {
                finishAndSaveSession()
                showFocusModeDialog = false
            },
            onSwitchSubject = { targetId, splitPreviousSegment, newTopic ->
                sessionManager.switchSubjectDuringStudy(
                    newSubjectId = targetId,
                    splitPreviousSegment = splitPreviousSegment,
                    newTopic = newTopic
                )
            },
            onOpenSounds = onOpenSounds,
            onOpenSettings = { showFocusSettingsDialog = true },
            onDismiss = { showFocusModeDialog = false }
        )
    }
}

@Composable
private fun SubjectSwitchModalSheet(
    snapshot: SessionSnapshot,
    subjects: List<Subject>,
    initialTargetId: Long?,
    initialTargetExplicit: Boolean,
    onDismiss: () -> Unit,
    onImmediateSelect: (Long?) -> Unit,
    onConfirmSwitch: (newSubjectId: Long?, splitPreviousSegment: Boolean, newTopic: String?) -> Unit,
    onOpenSubjects: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SubjectSwitchContent(
                snapshot = snapshot,
                subjects = subjects,
                initialTargetId = initialTargetId,
                initialTargetExplicit = initialTargetExplicit,
                showHeader = true,
                onDismiss = onDismiss,
                onImmediateSelect = onImmediateSelect,
                onConfirmSwitch = onConfirmSwitch,
                onOpenSubjects = onOpenSubjects
            )
        }
    }
}

@Composable
private fun SubjectSwitchContent(
    snapshot: SessionSnapshot,
    subjects: List<Subject>,
    initialTargetId: Long?,
    initialTargetExplicit: Boolean,
    showHeader: Boolean,
    onDismiss: () -> Unit,
    onImmediateSelect: (Long?) -> Unit,
    onConfirmSwitch: (newSubjectId: Long?, splitPreviousSegment: Boolean, newTopic: String?) -> Unit,
    onOpenSubjects: (() -> Unit)?
) {
    val isElapsedZero = snapshot.elapsedFocusSeconds == 0L
    val subjectById = remember(subjects) { subjects.associateBy { it.id } }
    val orderedSubjects = remember(subjects) { SubjectHierarchy.orderedHierarchically(subjects) }
    val currentSubject = subjectById[snapshot.subjectId]
    val currentName = currentSubject?.let { SubjectHierarchy.displayName(it, subjectById) } ?: "Geral / Livre"
    val currentColor = currentSubject?.let { Color(it.color.toInt()) }

    var selectedTargetId by remember(initialTargetId, initialTargetExplicit) { mutableStateOf(initialTargetId) }
    var hasSelectedTarget by remember(initialTargetId, initialTargetExplicit) { mutableStateOf(initialTargetExplicit) }
    var showTargetPicker by remember(initialTargetExplicit) { mutableStateOf(!initialTargetExplicit) }
    var topicDraft by remember(initialTargetId, initialTargetExplicit) { mutableStateOf("") }

    val targetSubject = if (hasSelectedTarget) subjectById[selectedTargetId] else null
    val targetName = if (hasSelectedTarget) {
        targetSubject?.let { SubjectHierarchy.displayName(it, subjectById) } ?: "Geral / Livre"
    } else {
        "Selecione a matéria"
    }
    val targetColor = targetSubject?.let { Color(it.color.toInt()) }

    if (showHeader) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    if (isElapsedZero) "Selecionar Matéria" else "Trocar Matéria em Andamento",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isElapsedZero) {
                        "Escolha o foco desta sessão ou alterne para Geral / Livre."
                    } else {
                        "Decida se quer salvar o bloco atual ou transferir o tempo."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Fechar")
            }
        }
    }

    if (!isElapsedZero) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "BLOCO ATUAL",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentColor != null) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(currentColor)
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.AutoStories,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "$currentName · ${formatSwitchDuration(snapshot.elapsedFocusSeconds)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (snapshot.topic.isNotBlank()) {
                            Text(
                                snapshot.topic,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )

                    Column(Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "NOVA MATÉRIA",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            if (!showTargetPicker) {
                                Text(
                                    "Alterar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showTargetPicker = true }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (hasSelectedTarget) {
                                if (targetColor != null) {
                                    Box(
                                        Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(targetColor)
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.AutoStories,
                                        null,
                                        Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                targetName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (hasSelectedTarget) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTargetPicker) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isElapsedZero) "Matéria da sessão" else "Escolha a nova matéria",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (onOpenSubjects != null) {
                    TextButton(onClick = onOpenSubjects) {
                        Text("Gerenciar")
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isCurrentGeneral = snapshot.subjectId == null
                FilterChip(
                    selected = hasSelectedTarget && selectedTargetId == null,
                    enabled = isElapsedZero || !isCurrentGeneral,
                    onClick = {
                        if (isElapsedZero) {
                            onImmediateSelect(null)
                        } else {
                            selectedTargetId = null
                            hasSelectedTarget = true
                        }
                    },
                    label = {
                        Text(
                            if (isCurrentGeneral && !isElapsedZero) "Geral / Livre (atual)" else "Geral / Livre",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.AutoStories, null, Modifier.size(16.dp))
                    }
                )

                orderedSubjects.forEach { subject ->
                    val isCurrent = snapshot.subjectId == subject.id
                    val isTarget = hasSelectedTarget && selectedTargetId == subject.id
                    val displayName = SubjectHierarchy.displayName(subject, subjectById)
                    FilterChip(
                        selected = isTarget,
                        enabled = isElapsedZero || !isCurrent,
                        onClick = {
                            if (isElapsedZero) {
                                onImmediateSelect(subject.id)
                            } else {
                                selectedTargetId = subject.id
                                hasSelectedTarget = true
                            }
                        },
                        label = {
                            Text(
                                if (isCurrent && !isElapsedZero) "$displayName (atual)" else displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(subject.color.toInt()))
                            )
                        }
                    )
                }
            }
        }
    }

    if (!isElapsedZero && hasSelectedTarget) {
        OutlinedTextField(
            value = topicDraft,
            onValueChange = { topicDraft = it },
            label = { Text("Tópico em $targetName (opcional)") },
            placeholder = { Text("Ex: Capítulo 3, Lista de exercícios...") },
            supportingText = {
                if (snapshot.topic.isNotBlank() && selectedTargetId != snapshot.subjectId) {
                    Text("Deixe vazio para limpar o tópico anterior ao dividir")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        val topicArg = topicDraft.trim().ifBlank { null }
        val elapsedFormatted = formatSwitchDuration(snapshot.elapsedFocusSeconds)

        Button(
            onClick = { onConfirmSwitch(selectedTargetId, true, topicArg) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Text(
                            "RECOMENDADO",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(
                        "Salvar bloco anterior e iniciar em $targetName",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                Text(
                    "Registra $elapsedFormatted em $currentName e continua contando do zero em $targetName.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )
            }
        }

        OutlinedButton(
            onClick = { onConfirmSwitch(selectedTargetId, false, topicArg) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Mover todo o tempo atual para $targetName",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Reclassifica os $elapsedFormatted atuais para $targetName sem dividir a sessão.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else if (!isElapsedZero) {
        Text(
            "Toque na nova matéria acima para salvar o bloco atual ou reclassificar o tempo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun FocusedTaskCard(
    task: StudyTask,
    elapsedFocusSeconds: Long,
    savedTaskSeconds: Long,
    onCompleteTask: (() -> Unit)?,
    onUnlinkTask: () -> Unit,
    onToggleSubtask: ((String) -> Unit)?
) {
    val semantic = LocalStudySemanticColors.current
    val (pColor, pContainer, onPContainer) = when (task.priority) {
        1 -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        2 -> Triple(semantic.warning, semantic.warningContainer, semantic.onWarningContainer)
        3 -> Triple(semantic.info, semantic.infoContainer, semantic.onInfoContainer)
        else -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }
    val subtasks = remember(task.subtasksJson) { TaskSubtasks.parse(task.subtasksJson) }
    val tags = remember(task.tags) { TaskTagPresets.parseTags(task.tags) }
    val totalElapsedSeconds = savedTaskSeconds + elapsedFocusSeconds
    val estimatedSeconds = task.estimatedMinutes * 60L

    StudyCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        emphasized = true
    ) {
        Column(Modifier.padding(StudyTokens.cardPadding()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = pContainer
                ) {
                    Text(
                        "P${task.priority}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = onPContainer
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(
                        "TAREFA EM FOCO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onCompleteTask != null) {
                    IconButton(onClick = onCompleteTask) {
                        Icon(
                            Icons.Default.CheckCircleOutline,
                            "Concluir tarefa",
                            tint = semantic.success,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                IconButton(onClick = onUnlinkTask) {
                    Icon(
                        Icons.Default.Close,
                        "Desvincular tarefa",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (tags.isNotEmpty() || task.estimatedMinutes > 0 || totalElapsedSeconds > 0L) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val timeLabel = if (task.estimatedMinutes > 0) {
                        "⏱ ${formatShortDuration(totalElapsedSeconds)} / ~${task.estimatedMinutes}m"
                    } else {
                        "⏱ ${formatShortDuration(totalElapsedSeconds)} focados"
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            timeLabel,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                tag,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (estimatedSeconds > 0L) {
                val progress = (totalElapsedSeconds.toFloat() / estimatedSeconds.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (totalElapsedSeconds > estimatedSeconds) semantic.warning else pColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            if (subtasks.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                val (doneCount, totalCount) = TaskSubtasks.summary(subtasks)
                Text(
                    "Subtarefas ($doneCount/$totalCount)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    subtasks.forEach { sub ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = onToggleSubtask != null) { onToggleSubtask?.invoke(sub.id) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = sub.done,
                                onCheckedChange = if (onToggleSubtask != null) { { onToggleSubtask(sub.id) } } else null
                            )
                            Text(
                                sub.title,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = if (sub.done) TextDecoration.LineThrough else null,
                                color = if (sub.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionItemCard(session: StudySession, subject: Subject?) {
    val subjectColor = subject?.let { Color(it.color.toInt()) } ?: MaterialTheme.colorScheme.primary

    StudyCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(subjectColor)
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    subject?.name ?: "Estudo Geral",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (session.topic.isNotBlank()) {
                    Text(
                        session.topic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${StudyDateFormats.formatEpoch(session.startedAt, StudyDateFormats.TIME_HH_MM_DEFAULT)} - ${StudyDateFormats.formatEpoch(session.endedAt, StudyDateFormats.TIME_HH_MM_DEFAULT)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatShortDuration(session.durationSeconds),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false
                )
                if (session.breakSeconds > 0) {
                    Text(
                        "Pausa: ${formatShortDuration(session.breakSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * LOTE 4: Fullscreen Immersive Focus Mode.
 * Minimalist, high-contrast, zero-distraction view for pure study concentration.
 */
@Composable
fun FocusModeDialog(
    snapshot: SessionSnapshot,
    subject: Subject?,
    linkedNote: Note? = null,
    todayFocusForSubject: Long = 0L,
    onToggle: () -> Unit,
    onFinish: () -> Unit,
    onOpenSounds: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    linkedTask: StudyTask? = null,
    subjects: List<Subject> = emptyList(),
    onSwitchSubject: ((newSubjectId: Long?, splitPreviousSegment: Boolean, newTopic: String?) -> Unit)? = null
) {
    val isRunning = snapshot.state == TimerState.RUNNING
    val context = LocalContext.current
    val baseThemeConfig = LocalStudyThemeConfig.current
    val amoledConfig = remember(baseThemeConfig) {
        baseThemeConfig.copy(baseSurface = StudyBaseSurface.AMOLED_BLACK)
    }
    val focusScheme = remember(amoledConfig, context, isRunning) {
        buildStudyColorScheme(amoledConfig, context, isTimerRunning = isRunning)
    }
    val focusSemantic = remember(focusScheme) {
        buildStudySemanticColors(
            StudyBaseSurface.AMOLED_BLACK,
            focusScheme.surfaceContainer,
            focusScheme.surface
        )
    }

    // Anti-accidental touch lock
    var isLocked by remember { mutableStateOf(false) }
    var showInFocusNoteReader by remember { mutableStateOf(false) }
    var showInFocusSwitchDialog by remember { mutableStateOf(false) }
    val unlockAnimatable = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = { if (!isLocked) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(dialogView) {
            val prev = dialogView.keepScreenOn
            dialogView.keepScreenOn = true
            onDispose { dialogView.keepScreenOn = prev }
        }
        MaterialTheme(colorScheme = focusScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = focusScheme.background,
                contentColor = focusScheme.onBackground
            ) {
                if (isLocked) {
                    // LOCKED SCREEN — only shows clock + unlock instruction
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(24.dp)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    // Unlock only if the finger stays down for the full 2s
                                    val holdJob = coroutineScope.launch {
                                        unlockAnimatable.snapTo(0f)
                                        unlockAnimatable.animateTo(
                                            1f,
                                            animationSpec = tween(2000, easing = LinearEasing)
                                        )
                                        isLocked = false
                                        unlockAnimatable.snapTo(0f)
                                    }
                                    waitForUpOrCancellation()
                                    // Released (or cancelled) — abort unless already completed
                                    if (holdJob.isActive) {
                                        holdJob.cancel()
                                        coroutineScope.launch { unlockAnimatable.snapTo(0f) }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val displayedSeconds = calculateDisplaySeconds(snapshot)
                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                val density = LocalDensity.current
                                val targetSp = when {
                                    maxWidth < 330.dp -> 50f
                                    maxWidth < 380.dp -> 58f
                                    else -> 70f
                                }
                                val scaledSp = (targetSp / density.fontScale.coerceAtLeast(1f)).sp
                                Text(
                                    formatTimeHMS(displayedSeconds),
                                    style = MaterialTheme.typography.displayLarge.copy(
                                        fontSize = scaledSp,
                                        lineHeight = (scaledSp.value + 6f).sp
                                    ),
                                    fontWeight = FontWeight.Thin,
                                    color = focusScheme.onBackground,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            Icon(Icons.Default.Lock, null, tint = focusScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Segure para desbloquear",
                                style = MaterialTheme.typography.labelMedium,
                                color = focusScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { unlockAnimatable.value },
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = focusScheme.primary,
                                trackColor = focusScheme.surfaceContainerHighest
                            )
                        }
                    }
                } else {
                    // UNLOCKED — full focus interface
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top header: Subject + Lock + Settings + Sounds + Exit
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                subject?.let {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(it.color.toInt()))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    subject?.name ?: "Geral / Livre",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = focusScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (onSwitchSubject != null) {
                                    IconButton(onClick = { showInFocusSwitchDialog = true }) {
                                        Icon(Icons.Default.SwapHoriz, "Trocar matéria", tint = focusScheme.primary)
                                    }
                                }
                                IconButton(onClick = { isLocked = true }) {
                                    Icon(Icons.Default.Lock, "Travar tela", tint = focusScheme.onSurfaceVariant)
                                }
                                if (onOpenSettings != null) {
                                    IconButton(onClick = onOpenSettings) {
                                        Icon(Icons.Outlined.NotificationsActive, "Alertas e Não Perturbe", tint = focusScheme.onSurfaceVariant)
                                    }
                                }
                                if (onOpenSounds != null) {
                                    IconButton(onClick = { onDismiss(); onOpenSounds() }) {
                                        Icon(Icons.Outlined.Headphones, "Sons ambientes", tint = focusScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, "Sair do modo foco", tint = focusScheme.onBackground)
                                }
                            }
                        }

                        // Center scrollable area so small/landscape displays never clip clock or controls
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (snapshot.topic.isNotBlank()) {
                                    Text(
                                        snapshot.topic,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = focusScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                                if (linkedTask != null) {
                                    val (doneSub, totalSub) = remember(linkedTask.subtasksJson) { TaskSubtasks.summary(linkedTask.subtasksJson) }
                                    val meta = buildList {
                                        add("P${linkedTask.priority}")
                                        if (linkedTask.estimatedMinutes > 0) add("~${linkedTask.estimatedMinutes}m")
                                        if (totalSub > 0) add("$doneSub/$totalSub etapas")
                                    }.joinToString(" · ")
                                    Text(
                                        meta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = focusScheme.primary,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                // Pomodoro phase indicator
                                if (snapshot.mode == StudyTimerMode.POMODORO) {
                                    val (phaseLabel, phaseBg, phaseFg) = when (snapshot.pomodoroPhase) {
                                        PomodoroPhase.FOCUS -> Triple("🎯 FOCO", focusScheme.primaryContainer, focusScheme.onPrimaryContainer)
                                        PomodoroPhase.SHORT_BREAK -> Triple("☕ PAUSA CURTA", focusSemantic.successContainer, focusSemantic.onSuccessContainer)
                                        PomodoroPhase.LONG_BREAK -> Triple("🌿 PAUSA LONGA", focusSemantic.warningContainer, focusSemantic.onWarningContainer)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = phaseBg
                                    ) {
                                        Text(
                                            phaseLabel,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = phaseFg,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Ciclo ${snapshot.pomodoroCycle + 1} de ${snapshot.pomodoroSettings.cycles}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = focusScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }

                                val displayedSeconds = calculateDisplaySeconds(snapshot)

                                BoxWithConstraints(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val density = LocalDensity.current
                                    val targetSp = when {
                                        maxWidth < 330.dp -> 46f
                                        maxWidth < 380.dp -> 54f
                                        else -> 64f
                                    }
                                    val scaledSp = (targetSp / density.fontScale.coerceAtLeast(1f)).sp
                                    Text(
                                        formatTimeHMS(displayedSeconds),
                                        style = MaterialTheme.typography.displayLarge.copy(
                                            fontSize = scaledSp,
                                            lineHeight = (scaledSp.value + 6f).sp
                                        ),
                                        fontWeight = FontWeight.Light,
                                        color = focusScheme.onBackground,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                Text(
                                    when {
                                        snapshot.state == TimerState.RUNNING -> "⚡ Modo Foco Ativo"
                                        snapshot.state == TimerState.PAUSED -> "⏸️ Pausado"
                                        else -> "Pronto"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isRunning) focusScheme.primary else focusScheme.onSurfaceVariant
                                )

                                // Subject daily progress in focus mode
                                if (subject != null && todayFocusForSubject > 0L) {
                                    Spacer(Modifier.height(16.dp))
                                    val targetMin = subject.targetMinutesPerDay.takeIf { it > 0 } ?: 60
                                    val focusMin = todayFocusForSubject / 60
                                    val progress = (focusMin.toFloat() / targetMin).coerceIn(0f, 1f)
                                    Text(
                                        "${focusMin}min / ${targetMin}min hoje em ${subject.name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = focusScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier
                                            .fillMaxWidth(0.6f)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = focusSemantic.success,
                                        trackColor = focusScheme.surfaceContainerHighest
                                    )
                                }

                                // Linked note reminder (clickable for quick reading inside focus mode)
                                if (linkedNote != null) {
                                    Spacer(Modifier.height(14.dp))
                                    Surface(
                                        onClick = { showInFocusNoteReader = true },
                                        shape = RoundedCornerShape(50),
                                        color = focusScheme.surfaceContainerHigh
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.MenuBook,
                                                null,
                                                tint = focusScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "${linkedNote.title.ifBlank { "Nota vinculada" }} · Ler resumo",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = focusScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom controls: Play/Pause and Finish
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onToggle,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) focusScheme.surfaceContainerHighest else focusScheme.primary)
                            ) {
                                Icon(
                                    if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isRunning) "Pausar" else "Continuar",
                                    tint = if (isRunning) focusScheme.onSurface else focusScheme.onPrimary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            if (snapshot.elapsedFocusSeconds > 5L) {
                                Spacer(Modifier.width(20.dp))
                                IconButton(
                                    onClick = onFinish,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(focusSemantic.success)
                                ) {
                                    Icon(Icons.Default.Check, "Finalizar", tint = focusSemantic.onSuccess, modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                } // end if/else isLocked

                // In-Focus Note Reader Overlay (AMOLED dark reader without leaving Focus Mode)
                if (showInFocusNoteReader && linkedNote != null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = focusScheme.background,
                        contentColor = focusScheme.onBackground
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        linkedNote.title.ifBlank { "Resumo" },
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = focusScheme.onBackground,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "Leitura em Modo Foco · ${formatTimeHMS(calculateDisplaySeconds(snapshot))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = focusScheme.primary
                                    )
                                }
                                IconButton(onClick = { showInFocusNoteReader = false }) {
                                    Icon(Icons.Default.Close, "Voltar ao relógio", tint = focusScheme.onBackground)
                                }
                            }
                            HorizontalDivider(color = focusScheme.outlineVariant)
                            Spacer(Modifier.height(12.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = linkedNote.markdown,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = focusScheme.onBackground,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }

                // In-Focus Subject Switch Dialog
                if (showInFocusSwitchDialog && onSwitchSubject != null) {
                    AlertDialog(
                        onDismissRequest = { showInFocusSwitchDialog = false },
                        title = {
                            Text(
                                if (snapshot.elapsedFocusSeconds == 0L) "Selecionar Matéria"
                                else "Trocar Matéria em Andamento"
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .imePadding()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SubjectSwitchContent(
                                    snapshot = snapshot,
                                    subjects = subjects,
                                    initialTargetId = null,
                                    initialTargetExplicit = false,
                                    showHeader = false,
                                    onDismiss = { showInFocusSwitchDialog = false },
                                    onImmediateSelect = { targetId ->
                                        onSwitchSubject(targetId, false, null)
                                        showInFocusSwitchDialog = false
                                    },
                                    onConfirmSwitch = { targetId, splitPreviousSegment, newTopic ->
                                        onSwitchSubject(targetId, splitPreviousSegment, newTopic)
                                        showInFocusSwitchDialog = false
                                    },
                                    onOpenSubjects = null
                                )
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showInFocusSwitchDialog = false }) {
                                Text("Cancelar")
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun calculateDisplaySeconds(snapshot: SessionSnapshot): Long {
    return when (snapshot.mode) {
        StudyTimerMode.STOPWATCH, StudyTimerMode.FLOWMODORO -> snapshot.elapsedFocusSeconds
        StudyTimerMode.COUNTDOWN -> (snapshot.countdownTargetSeconds - snapshot.elapsedFocusSeconds).coerceAtLeast(0L)
        StudyTimerMode.POMODORO -> {
            val isBreak = snapshot.pomodoroPhase != PomodoroPhase.FOCUS
            val target = if (isBreak) {
                (if (snapshot.pomodoroPhase == PomodoroPhase.LONG_BREAK) snapshot.pomodoroSettings.longMin else snapshot.pomodoroSettings.shortMin) * 60L
            } else {
                snapshot.pomodoroSettings.focusMin * 60L
            }
            (target - snapshot.pomodoroPhaseElapsedSeconds).coerceAtLeast(0L)
        }
    }
}

// Helpers for formatted time
private fun formatTimeHMS(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val secs = s % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

private fun formatDurationHMS(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val secs = s % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02dh %02dm %02ds", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%02dm %02ds", minutes, secs)
    }
}

private fun formatShortDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "${s}s"
    }
}

private fun formatSwitchDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val secs = s % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, secs)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, secs)
        else -> "${secs}s"
    }
}
