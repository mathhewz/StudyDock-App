package com.studydock.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.studydock.app.MainActivity
import com.studydock.app.R
import com.studydock.app.timer.StudySessionManager
import com.studydock.app.timer.TimerState

class StudyTimerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StudyTimerWidget::class.java))
            if (ids.isNotEmpty()) updateAll(context, manager, ids)
        }

        private data class WidgetTimerState(
            val state: TimerState,
            val elapsedSeconds: Long,
            val topic: String
        )

        private fun readFallbackState(context: Context): WidgetTimerState {
            val prefs = context.applicationContext.getSharedPreferences("studydock_timer_session_v3", Context.MODE_PRIVATE)
            val stateName = prefs.getString("state", TimerState.IDLE.name) ?: TimerState.IDLE.name
            val state = runCatching { TimerState.valueOf(stateName) }.getOrDefault(TimerState.IDLE)
            val topic = prefs.getString("topic", "").orEmpty()
            val accumulated = prefs.getLong("accumulated_focus_seconds", 0L).coerceAtLeast(0L)
            val lastResumeMono = prefs.getLong("last_resume_monotonic", 0L)
            val runningDelta = if (state == TimerState.RUNNING && lastResumeMono > 0L) {
                ((SystemClock.elapsedRealtime() - lastResumeMono) / 1000L).coerceAtLeast(0L)
            } else {
                0L
            }
            return WidgetTimerState(state, accumulated + runningDelta, topic)
        }

        private fun updateAll(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val snap = StudySessionManager.getInstanceOrNull()?.snapshot?.value
            val resolved = if (snap != null) {
                WidgetTimerState(snap.state, snap.elapsedFocusSeconds, snap.topic)
            } else {
                readFallbackState(context)
            }

            val elapsed = resolved.elapsedSeconds.coerceAtLeast(0L)
            val mins = elapsed / 60L
            val secs = elapsed % 60L
            val timeLabel = "%02d:%02d".format(mins, secs)
            val isRunning = resolved.state == TimerState.RUNNING
            val statusLabel = when (resolved.state) {
                TimerState.RUNNING -> resolved.topic.takeIf { it.isNotBlank() }?.let { "Em foco · $it" } ?: "Sessão em andamento"
                TimerState.PAUSED -> "Pausado · toque para retomar"
                else -> "Toque para iniciar o timer"
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_timer_page", true)
            }
            val pending = PendingIntent.getActivity(
                context,
                4022,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.timer_widget).apply {
                    if (isRunning) {
                        setViewVisibility(R.id.timer_widget_elapsed, View.GONE)
                        setViewVisibility(R.id.timer_widget_chronometer, View.VISIBLE)
                        setChronometer(
                            R.id.timer_widget_chronometer,
                            SystemClock.elapsedRealtime() - elapsed * 1000L,
                            null,
                            true
                        )
                    } else {
                        setViewVisibility(R.id.timer_widget_chronometer, View.GONE)
                        setViewVisibility(R.id.timer_widget_elapsed, View.VISIBLE)
                        setChronometer(R.id.timer_widget_chronometer, SystemClock.elapsedRealtime(), null, false)
                        setTextViewText(R.id.timer_widget_elapsed, timeLabel)
                    }
                    setTextViewText(R.id.timer_widget_status, statusLabel)
                    setOnClickPendingIntent(R.id.timer_widget_root, pending)
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}
