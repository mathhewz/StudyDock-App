package com.studydock.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import com.studydock.app.MainActivity
import com.studydock.app.R
import com.studydock.app.data.StudyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

class HabitGridWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                refreshAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refresh(context: Context) {
            scope.launch { refreshAll(context.applicationContext) }
        }

        private suspend fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HabitGridWidget::class.java))
            if (ids.isEmpty()) return

            val dao = StudyDatabase.get(context).studyDao()
            val habits = dao.habits().first()
            val today = LocalDate.now()
            val sinceKey = habitStudioKeyFromLocalDate(today.minusDays(27))
            val todayKey = habitStudioKeyFromLocalDate(today)
            val completions = dao.habitCompletionsBetween(sinceKey, todayKey).first()

            val primary = habits.firstOrNull()
            val views = RemoteViews(context.packageName, R.layout.habit_grid_widget)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_habits_page", true)
            }
            views.setOnClickPendingIntent(
                R.id.habit_widget_root,
                PendingIntent.getActivity(context, 4056, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            if (primary != null) {
                val logs = completions.filter { it.habitId == primary.id }.associateBy { it.dayKey }
                val score = habitStudioExponentialScore(primary, logs.values.toList(), habitStudioKeyFromLocalDate(today))
                views.setTextViewText(R.id.habit_widget_title, "${primary.emoji} ${primary.title}")
                views.setTextViewText(R.id.habit_widget_subtitle, "Score $score% · 28 dias")
                views.setImageViewBitmap(R.id.habit_widget_grid, drawGridBitmap(today, logs, primary.color.toInt(), primary.miniTarget, primary.eliteTarget))
            } else {
                views.setTextViewText(R.id.habit_widget_title, "Hábitos · StudyDock")
                views.setTextViewText(R.id.habit_widget_subtitle, "Crie um hábito para acompanhar")
            }
            manager.updateAppWidget(ids, views)
        }

        private fun drawGridBitmap(
            today: LocalDate,
            logs: Map<Long, com.studydock.app.data.HabitCompletion>,
            accentInt: Int,
            mini: Double,
            elite: Double
        ): Bitmap {
            val cols = 14
            val rows = 2
            val cell = 22f
            val gap = 5f
            val w = (cols * (cell + gap)).toInt()
            val h = (rows * (cell + gap)).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val baseColor = accentInt and 0x00FFFFFF

            for (i in 0 until 28) {
                val date = today.minusDays((27 - i).toLong())
                val key = habitStudioKeyFromLocalDate(date)
                val log = logs[key]
                val alpha = when {
                    log == null -> 0x26
                    log.status == "SKIPPED" -> 0x44
                    log.status == "DONE" && elite > 0.0 && log.value >= elite -> 0xFF
                    log.status == "DONE" -> 0xCC
                    log.status == "PARTIAL" && mini > 0.0 && log.value >= mini -> 0x80
                    log.status == "PARTIAL" -> 0x55
                    else -> 0x26
                }
                paint.color = (alpha shl 24) or baseColor
                val col = i % cols
                val row = i / cols
                val x = col * (cell + gap)
                val y = row * (cell + gap)
                canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), 5f, 5f, paint)
            }
            return bmp
        }
    }
}
