package com.studydock.app.timer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.studydock.app.MainActivity

/**
 * Quick Settings Tile para iniciar/pausar o timer de estudo com 1 toque
 * nas Configurações Rápidas do Android.
 */
class StudyTimerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val manager = StudySessionManager.getInstanceOrNull()
        val snap = manager?.snapshot?.value
        if (manager != null && snap != null && snap.state != TimerState.IDLE) {
            runCatching {
                manager.toggle()
                refreshTileState()
            }.onFailure { openTimerActivity() }
        } else {
            openTimerActivity()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openTimerActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_timer_page", true)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    4021,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val snap = StudySessionManager.getInstanceOrNull()?.snapshot?.value
        val state: TimerState
        val elapsedSec: Long
        val topic: String
        if (snap != null) {
            state = snap.state
            elapsedSec = snap.elapsedFocusSeconds
            topic = snap.topic
        } else {
            val prefs = applicationContext.getSharedPreferences("studydock_timer_session_v3", Context.MODE_PRIVATE)
            val rawState = prefs.getString("state", TimerState.IDLE.name) ?: TimerState.IDLE.name
            state = runCatching { TimerState.valueOf(rawState) }.getOrDefault(TimerState.IDLE)
            val acc = prefs.getLong("accumulated_focus", prefs.getLong("accumulated_focus_seconds", 0L)).coerceAtLeast(0L)
            val lastMono = prefs.getLong("last_resume_monotonic", 0L)
            val delta = if (state == TimerState.RUNNING && lastMono > 0L) {
                ((SystemClock.elapsedRealtime() - lastMono) / 1000L).coerceAtLeast(0L)
            } else 0L
            elapsedSec = acc + delta
            topic = prefs.getString("topic", "").orEmpty()
        }

        val running = state == TimerState.RUNNING
        val paused = state == TimerState.PAUSED
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = when {
            running -> formatQuickTime(elapsedSec)
            paused -> "Pausado"
            else -> "Estudar"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                running -> topic.takeIf { it.isNotBlank() } ?: "Em foco"
                paused -> topic.takeIf { it.isNotBlank() } ?: "Retomar"
                else -> "StudyDock"
            }
        }
        tile.updateTile()
    }

    private fun formatQuickTime(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val m = s / 60L
        val r = s % 60L
        return "%02d:%02d".format(m, r)
    }
}
