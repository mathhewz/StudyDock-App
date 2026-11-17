package com.studydock.app.timer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.studydock.app.MainActivity
import com.studydock.app.R
import com.studydock.app.data.StudyDatabase
import com.studydock.app.data.StudyLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class StudySessionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                stopForegroundSafely("ACTION_STOP")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                val db = StudyDatabase.get(applicationContext)
                val manager = StudySessionManager.get(applicationContext, db.studyDao())
                manager.toggle()
            }
            ACTION_FINISH -> {
                val snap = StudySessionManager.getInstanceOrNull()?.snapshot?.value ?: SessionSnapshot()
                runCatching {
                    val notif = buildNotification(snap, "StudyDock · Finalizando")
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                }
                serviceScope.launch {
                    try {
                        val db = StudyDatabase.get(applicationContext)
                        val manager = StudySessionManager.get(applicationContext, db.studyDao())
                        manager.saveAndFinish()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StudyLog.e(TAG, "Failed to finish study session from notification", e)
                    } finally {
                        stopForegroundSafely("ACTION_FINISH")
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
        }

        val initialSnapshot = StudySessionManager.getInstanceOrNull()?.snapshot?.value ?: SessionSnapshot()
        try {
            val notif = buildNotification(initialSnapshot, "StudyDock · Estudos")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            StudyLog.e(TAG, "StudySessionService failed to enter foreground: ${e::class.java.simpleName}", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (notificationJob?.isActive != true) {
            observeSessionUpdates()
        }

        return START_STICKY
    }

    private fun stopForegroundSafely(reason: String) {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { StudyLog.w(TAG, "Failed to stop foreground ($reason): ${it::class.java.simpleName}", it) }
    }

    private fun observeSessionUpdates() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            try {
                val db = StudyDatabase.get(applicationContext)
                val manager = StudySessionManager.get(applicationContext, db.studyDao(), this)
                val subjectsList = mutableMapOf<Long, String>()

                launch {
                    try {
                        db.studyDao().subjects().collectLatest { subjects ->
                            subjectsList.clear()
                            subjects.forEach { subjectsList[it.id] = it.name }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StudyLog.w(TAG, "Failed to collect subjects for timer notification", e)
                    }
                }

                manager.snapshot.collectLatest { snapshot ->
                    if (snapshot.state == TimerState.IDLE) {
                        stopForegroundSafely("IDLE")
                        stopSelf()
                        return@collectLatest
                    }

                    val subjectName = snapshot.subjectId?.let { subjectsList[it] } ?: "Sessão de Estudos"
                    val notification = buildNotification(snapshot, subjectName)
                    val managerCompat = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (managerCompat == null) {
                        StudyLog.w(TAG, "NotificationManager unavailable; cannot update timer notification")
                    } else {
                        try {
                            managerCompat.notify(NOTIFICATION_ID, notification)
                        } catch (e: Exception) {
                            StudyLog.w(TAG, "Failed to update timer notification: ${e::class.java.simpleName}", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StudyLog.e(TAG, "StudySessionService update observer crashed", e)
            }
        }
    }

    private fun buildNotification(snapshot: SessionSnapshot, subjectName: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_timer_page", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, StudySessionService::class.java).apply { action = ACTION_TOGGLE }
        val togglePendingIntent = PendingIntent.getService(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val finishIntent = Intent(this, StudySessionService::class.java).apply { action = ACTION_FINISH }
        val finishPendingIntent = PendingIntent.getService(
            this,
            102,
            finishIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isRunning = snapshot.state == TimerState.RUNNING
        val toggleLabel = if (isRunning) "Pausar" else "Continuar"

        val title = if (snapshot.topic.isNotBlank()) "$subjectName · ${snapshot.topic}" else subjectName

        val timeText = when (snapshot.mode) {
            StudyTimerMode.FLOWMODORO -> {
                val focus = formatTime(snapshot.elapsedFocusSeconds)
                val earned = formatTime(StudySessionManager.flowmodoroEarnedBreakSeconds(snapshot.elapsedFocusSeconds))
                "$focus (Pausa ganha: $earned)"
            }
            StudyTimerMode.STOPWATCH -> {
                val focus = formatTime(snapshot.elapsedFocusSeconds)
                if (snapshot.elapsedBreakSeconds > 0) "$focus (Pausa: ${formatTime(snapshot.elapsedBreakSeconds)})" else focus
            }
            StudyTimerMode.COUNTDOWN -> {
                val left = (snapshot.countdownTargetSeconds - snapshot.elapsedFocusSeconds).coerceAtLeast(0L)
                "Restante: ${formatTime(left)} · Foco: ${formatTime(snapshot.elapsedFocusSeconds)}"
            }
            StudyTimerMode.POMODORO -> {
                if (snapshot.pomodoroPhase == PomodoroPhase.FOCUS) {
                    val target = snapshot.pomodoroSettings.focusMin * 60L
                    val left = (target - snapshot.pomodoroPhaseElapsedSeconds).coerceAtLeast(0L)
                    "Foco: ${formatTime(left)} (Ciclo ${snapshot.pomodoroCycle + 1}/${snapshot.pomodoroSettings.cycles})"
                } else {
                    val isLong = snapshot.pomodoroPhase == PomodoroPhase.LONG_BREAK
                    val target = (if (isLong) snapshot.pomodoroSettings.longMin else snapshot.pomodoroSettings.shortMin) * 60L
                    val left = (target - snapshot.pomodoroPhaseElapsedSeconds).coerceAtLeast(0L)
                    "Pausa (${if (isLong) "Longa" else "Curta"}): ${formatTime(left)}"
                }
            }
        }

        val statusBadge = when (snapshot.state) {
            TimerState.RUNNING -> if (snapshot.mode == StudyTimerMode.POMODORO && snapshot.pomodoroPhase != PomodoroPhase.FOCUS) "☕ Pausa" else "⚡ Em foco"
            TimerState.PAUSED -> "⏸️ Pausado"
            TimerState.COMPLETED -> "🎉 Concluído"
            TimerState.IDLE -> "Parado"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .apply {
                if (isRunning && snapshot.mode == StudyTimerMode.STOPWATCH) {
                    setUsesChronometer(true)
                    setWhen(System.currentTimeMillis() - snapshot.elapsedFocusSeconds * 1000L)
                }
            }
            .setContentTitle(title)
            .setContentText("$statusBadge · $timeText")
            .setSubText(when (snapshot.mode) {
                StudyTimerMode.STOPWATCH -> "Cronômetro"
                StudyTimerMode.POMODORO -> "Pomodoro"
                StudyTimerMode.COUNTDOWN -> "Regressivo"
                StudyTimerMode.FLOWMODORO -> "Flowmodoro"
            })
            .setOngoing(snapshot.state == TimerState.RUNNING || snapshot.state == TimerState.PAUSED)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, toggleLabel, togglePendingIntent)
            .addAction(0, "Finalizar", finishPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null) {
            StudyLog.w(TAG, "NotificationManager unavailable; cannot create timer notification channel")
            return
        }
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sessão de Estudos Contínua",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação interativa exibida durante o cronômetro ou pomodoro de estudos"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }.onFailure {
            StudyLog.w(TAG, "Failed to create study session notification channel", it)
        }
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun formatTime(seconds: Long): String {
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

    companion object {
        private const val TAG = "StudySessionService"
        const val CHANNEL_ID = "study_session_ongoing_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.studydock.app.timer.ACTION_START"
        const val ACTION_STOP = "com.studydock.app.timer.ACTION_STOP"
        const val ACTION_TOGGLE = "com.studydock.app.timer.ACTION_TOGGLE"
        const val ACTION_FINISH = "com.studydock.app.timer.ACTION_FINISH"
    }
}
