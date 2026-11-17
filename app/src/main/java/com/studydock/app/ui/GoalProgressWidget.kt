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
import com.studydock.app.data.Goal
import com.studydock.app.data.GoalProgressSnapshot
import com.studydock.app.data.StudyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

open class GoalProgressWidget : AppWidgetProvider() {
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
        private const val WIDGET_CACHE_PREFS = "studydock_goal_widget_cache"

        fun cacheComputedSnapshots(context: Context, snapshots: List<GoalProgressSnapshot>) {
            if (snapshots.isEmpty()) return
            val edit = context.applicationContext
                .getSharedPreferences(WIDGET_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
            for (snap in snapshots) {
                val pct = (snap.progressFraction * 100f).roundToInt().coerceIn(0, 100)
                val label = if (snap.goal.metricType == "COUNTDOWN") {
                    snap.paceStatusLabel ?: "${snap.current.toLong()}d"
                } else {
                    "$pct% · ${snap.displayCurrent}/${snap.displayTarget}"
                }
                edit.putString("goal_${snap.goal.id}", "$pct|$label")
            }
            edit.apply()
            refresh(context)
        }

        fun refresh(context: Context) {
            scope.launch { refreshAll(context.applicationContext) }
        }

        private suspend fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val singleIds = manager.getAppWidgetIds(ComponentName(context, GoalProgressWidget::class.java))
            val listIds = manager.getAppWidgetIds(ComponentName(context, GoalListWidget::class.java))
            val countUpIds = manager.getAppWidgetIds(ComponentName(context, GoalCountUpWidget::class.java))
            if (singleIds.isEmpty() && listIds.isEmpty() && countUpIds.isEmpty()) return

            val dao = StudyDatabase.get(context).studyDao()
            val goals = dao.goals().first().filter { !it.completed }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pending = PendingIntent.getActivity(
                context,
                4055,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)

            if (singleIds.isNotEmpty()) {
                val primary = goals.firstOrNull { it.pinned } ?: goals.firstOrNull()
                val views = RemoteViews(context.packageName, R.layout.goal_progress_widget)
                views.setOnClickPendingIntent(R.id.goal_widget_root, pending)
                if (primary != null) {
                    val (title, metric, pct) = summarizeGoal(context, primary, now, today, zone)
                    views.setTextViewText(R.id.goal_widget_title, title)
                    views.setTextViewText(R.id.goal_widget_metric, metric)
                    views.setImageViewBitmap(R.id.goal_widget_bar, drawGoalBarBitmap(pct, primary.color.toInt()))
                } else {
                    views.setTextViewText(R.id.goal_widget_title, "Metas · Pretty Progress")
                    views.setTextViewText(R.id.goal_widget_metric, "Crie ou fixe uma meta no StudyDock")
                }
                manager.updateAppWidget(singleIds, views)
            }

            if (listIds.isNotEmpty()) {
                val topGoals = goals.take(3)
                val views = RemoteViews(context.packageName, R.layout.goal_progress_widget)
                views.setOnClickPendingIntent(R.id.goal_widget_root, pending)
                views.setTextViewText(R.id.goal_widget_title, "Próximas Metas (${goals.size})")
                val summary = if (topGoals.isEmpty()) {
                    "Nenhuma meta ativa"
                } else {
                    topGoals.joinToString(" · ") { g ->
                        val (_, metric, _) = summarizeGoal(context, g, now, today, zone)
                        "${g.title.take(14)}: $metric"
                    }
                }
                views.setTextViewText(R.id.goal_widget_metric, summary)
                views.setImageViewBitmap(R.id.goal_widget_bar, drawGoalBarBitmap(100, 0xFF6156D9.toInt()))
                manager.updateAppWidget(listIds, views)
            }

            if (countUpIds.isNotEmpty()) {
                val sinceGoal = goals.firstOrNull { it.direction == "SINCE" } ?: goals.firstOrNull()
                val views = RemoteViews(context.packageName, R.layout.goal_progress_widget)
                views.setOnClickPendingIntent(R.id.goal_widget_root, pending)
                if (sinceGoal != null) {
                    val startDate = Instant.ofEpochMilli(sinceGoal.targetAt).atZone(zone).toLocalDate()
                    val days = ChronoUnit.DAYS.between(startDate, today).coerceAtLeast(0L)
                    views.setTextViewText(R.id.goal_widget_title, "Desde · ${sinceGoal.title}")
                    views.setTextViewText(R.id.goal_widget_metric, "$days dias")
                    views.setImageViewBitmap(R.id.goal_widget_bar, drawGoalBarBitmap(100, sinceGoal.color.toInt()))
                } else {
                    views.setTextViewText(R.id.goal_widget_title, "Contador Desde")
                    views.setTextViewText(R.id.goal_widget_metric, "Sem marco configurado")
                }
                manager.updateAppWidget(countUpIds, views)
            }
        }

        private fun summarizeGoal(context: Context, goal: Goal, now: Long, today: LocalDate, zone: ZoneId): Triple<String, String, Int> {
            if (goal.metricType != "COUNTDOWN" && goal.metricType != "MANUAL") {
                val cached = context.applicationContext
                    .getSharedPreferences(WIDGET_CACHE_PREFS, Context.MODE_PRIVATE)
                    .getString("goal_${goal.id}", null)
                if (!cached.isNullOrBlank() && '|' in cached) {
                    val pct = cached.substringBefore('|').toIntOrNull()?.coerceIn(0, 100)
                    val label = cached.substringAfter('|')
                    if (pct != null && label.isNotBlank()) {
                        return Triple(goal.title, label, pct)
                    }
                }
            }
            val targetDate = Instant.ofEpochMilli(goal.targetAt).atZone(zone).toLocalDate()
            val daysLeft = ChronoUnit.DAYS.between(today, targetDate)
            return if (goal.metricType == "COUNTDOWN") {
                val startMs = goal.startAt.takeIf { it > 0L } ?: goal.createdAt
                val pct = if (goal.targetAt > startMs && startMs > 0L) {
                    (((now - startMs).toDouble() / (goal.targetAt - startMs).toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                } else 50
                val label = if (goal.direction == "SINCE") "${(-daysLeft).coerceAtLeast(0L)}d" else "${daysLeft.coerceAtLeast(0L)}d restantes"
                Triple(goal.title, label, pct)
            } else {
                val pct = if (goal.targetValue > 0.0) ((goal.currentValue / goal.targetValue) * 100.0).roundToInt().coerceIn(0, 100) else 0
                Triple(goal.title, "$pct% · ${goal.currentValue.toInt()}/${goal.targetValue.toInt()}", pct)
            }
        }

        private fun drawGoalBarBitmap(pct: Int, colorInt: Int): Bitmap {
            val w = 360
            val h = 24
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
            val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt }
            canvas.drawRoundRect(RectF(0f, 4f, w.toFloat(), h - 4f), 10f, 10f, bgPaint)
            val fillW = (w * (pct.coerceIn(0, 100) / 100f)).coerceAtLeast(12f)
            canvas.drawRoundRect(RectF(0f, 4f, fillW, h - 4f), 10f, 10f, fgPaint)
            return bmp
        }
    }
}

class GoalListWidget : GoalProgressWidget()
class GoalCountUpWidget : GoalProgressWidget()
