package com.studydock.app.timer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

private const val DND_SETTINGS_FILE = "studydock_settings"
private const val KEY_FOCUS_DND_ENABLED = "focus_dnd_enabled"
private const val KEY_PREV_FILTER = "focus_dnd_prev_filter"
private const val KEY_MANAGED_ACTIVE = "focus_dnd_managed_active"

/**
 * Controla o modo Não Perturbe (DND) automático durante sessões de foco ativas.
 * Só altera o filtro quando o usuário habilita a opção e concede acesso à política de notificações.
 */
object FocusDndController {
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(DND_SETTINGS_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_FOCUS_DND_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(DND_SETTINGS_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FOCUS_DND_ENABLED, enabled)
            .apply()
        if (!enabled) restoreIfManaged(context)
    }

    fun hasPolicyAccess(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    fun openPolicyAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun syncWithTimerState(context: Context, isRunning: Boolean) {
        if (isRunning) {
            if (!isEnabled(context) || !hasPolicyAccess(context)) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val prefs = context.getSharedPreferences(DND_SETTINGS_FILE, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_MANAGED_ACTIVE, false)) {
                prefs.edit()
                    .putInt(KEY_PREV_FILTER, nm.currentInterruptionFilter)
                    .putBoolean(KEY_MANAGED_ACTIVE, true)
                    .apply()
            }
            if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
            }
        } else {
            restoreIfManaged(context)
        }
    }

    fun restoreIfManaged(context: Context) {
        val prefs = context.getSharedPreferences(DND_SETTINGS_FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_MANAGED_ACTIVE, false)) return
        val prev = prefs.getInt(KEY_PREV_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
        prefs.edit().putBoolean(KEY_MANAGED_ACTIVE, false).apply()
        if (!hasPolicyAccess(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.setInterruptionFilter(prev) }
    }
}
