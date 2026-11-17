package com.studydock.app.habits

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import android.os.Build
import androidx.core.app.NotificationCompat
import com.studydock.app.MainActivity
import com.studydock.app.R
import com.studydock.app.data.DiaryAnalytics
import com.studydock.app.data.HabitCompletion
import com.studydock.app.data.StudyDatabase
import com.studydock.app.data.StudyLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

internal const val ALL_HABIT_REMINDER_DAYS_MASK = 0b111_1111

internal fun computeNextHabitReminderMillis(
    nowMillis: Long,
    minutes: Int,
    daysMask: Int,
    timeZone: TimeZone = TimeZone.getDefault()
): Long? {
    val effectiveMask = daysMask and ALL_HABIT_REMINDER_DAYS_MASK
    if (effectiveMask == 0) return null
    val safeMinutes = minutes.coerceIn(0, 23 * 60 + 59)
    val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
    val next = Calendar.getInstance(timeZone).apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, safeMinutes / 60)
        set(Calendar.MINUTE, safeMinutes % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        var attempts = 0
        while (attempts < 7 && (effectiveMask and (1 shl ((get(Calendar.DAY_OF_WEEK) + 5) % 7))) == 0) {
            add(Calendar.DAY_OF_YEAR, 1)
            attempts++
        }
        if ((effectiveMask and (1 shl ((get(Calendar.DAY_OF_WEEK) + 5) % 7))) == 0) {
            return null
        }
    }
    return next.timeInMillis
}

object HabitReminderScheduler {
    private const val TAG = "HabitReminder"
    private const val CHANNEL_ID = "habit_reminders"
    private const val ACTION_REMIND = "com.studydock.app.HABIT_REMINDER"
    internal const val ACTION_COMPLETE_HABIT = "com.studydock.app.HABIT_COMPLETE_FROM_NOTIFICATION"
    private const val EXTRA_HABIT_ID = "habit_id"
    private const val EXTRA_TITLE = "habit_title"
    private const val EXTRA_MINUTES = "habit_minutes"
    private const val EXTRA_DAYS_MASK = "habit_days_mask"

    fun canUseExactAlarms(context: Context): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (alarm == null) {
            StudyLog.w(TAG, "AlarmManager unavailable while checking exact alarm capability")
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarm.canScheduleExactAlarms() }
                .onFailure { StudyLog.w(TAG, "Failed to query canScheduleExactAlarms: ${it::class.java.simpleName}", it) }
                .getOrDefault(false)
        } else {
            true
        }
    }

    fun schedule(context: Context, habitId: Long, title: String, minutes: Int, daysMask: Int = ALL_HABIT_REMINDER_DAYS_MASK) {
        val triggerAtMillis = computeNextHabitReminderMillis(
            nowMillis = System.currentTimeMillis(),
            minutes = minutes,
            daysMask = daysMask
        )
        if (triggerAtMillis == null || title.isBlank()) {
            cancel(context, habitId)
            return
        }
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (alarm == null) {
            StudyLog.w(TAG, "AlarmManager unavailable; cannot schedule habitId=$habitId")
            return
        }
        val intent = reminderIntent(context, habitId).apply {
            action = ACTION_REMIND
            putExtra(EXTRA_HABIT_ID, habitId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MINUTES, minutes.coerceIn(0, 23 * 60 + 59))
            putExtra(EXTRA_DAYS_MASK, daysMask and ALL_HABIT_REMINDER_DAYS_MASK)
        }
        val pending = PendingIntent.getBroadcast(context, habitId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        scheduleAlarmSafely(context, alarm, habitId, triggerAtMillis, pending)
    }

    // Exact alarm permission is checked via canUseExactAlarms() and guarded with SecurityException fallback.
    @SuppressLint("MissingPermission")
    private fun scheduleAlarmSafely(
        context: Context,
        alarm: AlarmManager,
        habitId: Long,
        triggerAtMillis: Long,
        pending: PendingIntent
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canUseExactAlarms(context)) {
                StudyLog.w(TAG, "Exact alarms not permitted on Android 12+; scheduling inexact alarm for habitId=$habitId")
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
                return
            }
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } catch (e: SecurityException) {
                StudyLog.w(TAG, "Exact alarm SecurityException for habitId=$habitId; falling back to inexact alarm", e)
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (e: Exception) {
            StudyLog.e(TAG, "Failed to schedule habit reminder habitId=$habitId", e)
        }
    }

    fun cancel(context: Context, habitId: Long) {
        val pending = PendingIntent.getBroadcast(context, habitId.toInt(), reminderIntent(context, habitId), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) {
            val alarm = context.getSystemService(AlarmManager::class.java)
            if (alarm == null) {
                StudyLog.w(TAG, "AlarmManager unavailable; cannot cancel habitId=$habitId")
                return
            }
            try {
                alarm.cancel(pending)
            } catch (e: Exception) {
                StudyLog.w(TAG, "Failed to cancel habit reminder habitId=$habitId", e)
            }
        }
    }

    private fun reminderIntent(context: Context, habitId: Long) = Intent(context, HabitReminderReceiver::class.java).apply {
        action = ACTION_REMIND
        putExtra(EXTRA_HABIT_ID, habitId)
    }

    internal fun reschedule(context: Context, habitId: Long, title: String, minutes: Int, daysMask: Int) = schedule(context, habitId, title, minutes, daysMask)
    internal fun title(intent: Intent) = intent.getStringExtra(EXTRA_TITLE).orEmpty()
    internal fun minutes(intent: Intent) = intent.getIntExtra(EXTRA_MINUTES, 8 * 60)
    internal fun daysMask(intent: Intent) = intent.getIntExtra(EXTRA_DAYS_MASK, 127)
    internal fun habitId(intent: Intent) = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
    internal fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null) {
            StudyLog.w(TAG, "NotificationManager unavailable; cannot create habit reminder channel")
            return
        }
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lembretes de hábitos",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Avisos nos dias e horários programados para seus hábitos"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }.onFailure {
            StudyLog.w(TAG, "Failed to create habit reminder notification channel", it)
        }
    }
    internal fun channelId() = CHANNEL_ID
}

class HabitReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = HabitReminderScheduler.habitId(intent)
        if (intent.action == HabitReminderScheduler.ACTION_COMPLETE_HABIT) {
            if (id < 0L) return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dao = StudyDatabase.get(context).studyDao()
                    val habit = dao.habits().first().firstOrNull { it.id == id }
                    if (habit != null && habit.kind != "LIMIT") {
                        val now = System.currentTimeMillis()
                        val dayKey = DiaryAnalytics.dayKeyFromEpoch(now)
                        val target = habit.targetValue.coerceAtLeast(1.0)
                        runCatching {
                            dao.saveHabitCompletion(
                                HabitCompletion(
                                    habitId = id,
                                    dayKey = dayKey,
                                    status = "DONE",
                                    value = target,
                                    note = null,
                                    loggedAt = now
                                )
                            )
                        }.onFailure { err ->
                            if (err !is SQLiteConstraintException) throw err
                            StudyLog.w(TAG, "Ignored completion for deleted habitId=$id", err)
                        }
                    }
                    context.getSystemService(NotificationManager::class.java)?.cancel(id.toInt())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    StudyLog.e(TAG, "Failed to mark habitId=$id complete from notification", e)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val title = HabitReminderScheduler.title(intent)
        val minutes = HabitReminderScheduler.minutes(intent)
        val daysMask = HabitReminderScheduler.daysMask(intent)
        if (id < 0 || title.isBlank()) {
            StudyLog.w(TAG, "Ignoring invalid habit reminder broadcast (habitId=$id)")
            return
        }
        try {
            HabitReminderScheduler.ensureChannel(context)
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (notificationManager == null) {
                StudyLog.w(TAG, "NotificationManager unavailable; cannot post reminder for habitId=$id")
            } else {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_habits_page", true)
                    putExtra("open_habit_id", id)
                }
                val openApp = PendingIntent.getActivity(
                    context,
                    id.toInt(),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val completeIntent = Intent(context, HabitReminderReceiver::class.java).apply {
                    action = HabitReminderScheduler.ACTION_COMPLETE_HABIT
                    putExtra("habit_id", id)
                }
                val completePending = PendingIntent.getBroadcast(
                    context,
                    (id.toInt() xor 0x40000),
                    completeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(context, HabitReminderScheduler.channelId())
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle("Hora de cuidar do seu hábito")
                    .setContentText(title)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(title))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(openApp)
                    .addAction(0, "Concluir hoje", completePending)
                    .setAutoCancel(true)
                    .build()
                notificationManager.notify(id.toInt(), notification)
            }
        } catch (e: SecurityException) {
            StudyLog.w(TAG, "Notification permission denied for habitId=$id", e)
        } catch (e: Exception) {
            StudyLog.e(TAG, "Failed to post habit reminder notification habitId=$id", e)
        }
        try {
            HabitReminderScheduler.reschedule(context, id, title, minutes, daysMask)
        } catch (e: Exception) {
            StudyLog.e(TAG, "Failed to reschedule habit reminder habitId=$id", e)
        }
    }

    private companion object {
        const val TAG = "HabitReminderReceiver"
    }
}

/** Reagenda os lembretes de hábitos após reinicialização, atualização do APK ou mudança de horário/fuso. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in RESCHEDULE_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var scheduledCount = 0
                StudyDatabase.get(context).studyDao().habitsWithReminder().forEach { habit ->
                    val minutes = habit.reminderMinutes ?: return@forEach
                    if ((habit.repeatDaysMask and ALL_HABIT_REMINDER_DAYS_MASK) == 0) return@forEach
                    try {
                        HabitReminderScheduler.schedule(context, habit.id, "${habit.emoji} ${habit.title}", minutes, habit.repeatDaysMask)
                        scheduledCount++
                    } catch (e: Exception) {
                        StudyLog.e(TAG, "Failed to reschedule habitId=${habit.id} after action=$action", e)
                    }
                }
                com.studydock.app.data.AutoBackupScheduler.scheduleDaily(context)
                com.studydock.app.ui.ReviewWidget.refresh(context)
                com.studydock.app.ui.GoalProgressWidget.refresh(context)
                com.studydock.app.ui.HabitGridWidget.refresh(context)
                com.studydock.app.ui.StudyTimerWidget.refresh(context)
                StudyLog.i(TAG, "Rescheduled $scheduledCount habit reminders and widgets after action=$action")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StudyLog.e(TAG, "BootReceiver failed to load habit reminders", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
