package com.studydock.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.widget.RemoteViews
import com.studydock.app.MainActivity
import com.studydock.app.R
import com.studydock.app.data.StudyDatabase
import com.studydock.app.data.afterGradePro
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

open class ReviewWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MICRO_GRADE) {
            val grade = intent.getIntExtra(EXTRA_GRADE, 2).coerceIn(0, 3)
            val cardId = intent.getLongExtra(EXTRA_CARD_ID, -1L)
            if (cardId > 0L) {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val dao = StudyDatabase.get(context.applicationContext).studyDao()
                        val card = dao.findCardsByIds(listOf(cardId)).firstOrNull()
                        if (card != null) {
                            val deck = card.deckId?.let { dao.deckById(it) }
                            val outcome = card.afterGradePro(grade = grade, config = deck)
                            dao.updateFlashcard(outcome.card)
                            dao.addCardReview(outcome.review)
                        }
                        performRefresh(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { performRefresh(context.applicationContext) } finally { pending.finish() }
        }
    }

    companion object {
        private const val REFRESH_COOLDOWN_MS = 2_500L
        const val ACTION_MICRO_GRADE = "com.studydock.app.widget.ACTION_MICRO_GRADE"
        const val EXTRA_CARD_ID = "extra_card_id"
        const val EXTRA_GRADE = "extra_grade"

        private val lock = Mutex()
        private val debounceMutex = Mutex()
        private val debounceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var pendingRefreshJob: Job? = null
        @Volatile private var lastRefreshElapsedMs: Long = 0L

        internal suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val hasStandard = manager.getAppWidgetIds(ComponentName(appContext, ReviewWidget::class.java)).isNotEmpty()
            val hasTrend = manager.getAppWidgetIds(ComponentName(appContext, ReviewTrendWidget::class.java)).isNotEmpty()
            if (!hasStandard && !hasTrend) return@withContext

            val shouldDebounce = debounceMutex.withLock {
                val elapsedSinceLast = SystemClock.elapsedRealtime() - lastRefreshElapsedMs
                if (lastRefreshElapsedMs != 0L && elapsedSinceLast < REFRESH_COOLDOWN_MS) {
                    pendingRefreshJob?.cancel()
                    val delayMs = (REFRESH_COOLDOWN_MS - elapsedSinceLast).coerceAtLeast(300L)
                    pendingRefreshJob = debounceScope.launch {
                        delay(delayMs)
                        performRefresh(appContext)
                    }
                    true
                } else {
                    pendingRefreshJob?.cancel()
                    pendingRefreshJob = null
                    false
                }
            }
            if (shouldDebounce) return@withContext
            performRefresh(appContext)
        }

        private suspend fun performRefresh(context: Context) = withContext(Dispatchers.IO) {
            lock.withLock {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, ReviewWidget::class.java))
                val trendIds = manager.getAppWidgetIds(ComponentName(context, ReviewTrendWidget::class.java))
                if (ids.isEmpty() && trendIds.isEmpty()) return@withLock
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                // Only load the 12-week heatmap window (which also covers the 30-day trend and today).
                val firstWeek = today.minusDays(today.dayOfWeek.value - 1L + 11 * 7)
                val sinceMillis = firstWeek.atStartOfDay(zone).toInstant().toEpochMilli()
                val dao = StudyDatabase.get(context).studyDao()
                val reviews = dao.listRecentScheduledReviewsSince(sinceMillis)
                val history = ReviewHistory(reviews, today, zone)
                val streak = if (ids.isNotEmpty() && history.streak >= ChronoUnit.DAYS.between(firstWeek, today).toInt()) {
                    computeExactStreak(dao.listScheduledReviewTimestamps(), today, zone)
                } else {
                    history.streak
                }
                val open = Intent(context, MainActivity::class.java).setAction(OPEN_PROGRESS)
                val openPending = PendingIntent.getActivity(
                    context,
                    0,
                    open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                if (ids.isNotEmpty()) {
                    val standardViews = RemoteViews(context.packageName, R.layout.review_widget)
                    standardViews.setOnClickPendingIntent(R.id.review_widget_root, openPending)
                    val image = Bitmap.createBitmap(480, 112, Bitmap.Config.ARGB_8888)
                    drawReviewChart(Canvas(image), 480f, 112f, history, true)
                    standardViews.setImageViewBitmap(R.id.review_widget_chart, image)
                    standardViews.setContentDescription(R.id.review_widget_chart, "Atividade de revisão nas últimas 12 semanas")
                    standardViews.setTextViewText(R.id.review_widget_today, "${history.reviewedCards} cartões revisados hoje")
                    standardViews.setTextViewText(R.id.review_widget_period, "12 semanas · sequência de $streak dias")
                    manager.updateAppWidget(ids, standardViews)
                }
                if (trendIds.isNotEmpty()) {
                    val trendViews = RemoteViews(context.packageName, R.layout.review_widget)
                    trendViews.setOnClickPendingIntent(R.id.review_widget_root, openPending)
                    val line = Bitmap.createBitmap(480, 112, Bitmap.Config.ARGB_8888)
                    drawReviewChart(Canvas(line), 480f, 112f, history, false)
                    trendViews.setImageViewBitmap(R.id.review_widget_chart, line)
                    trendViews.setContentDescription(R.id.review_widget_chart, "Gráfico de revisões diárias em 30 dias")
                    trendViews.setTextViewText(R.id.review_widget_today, "${history.recent.sum()} revisões em 30 dias")
                    trendViews.setTextViewText(R.id.review_widget_period, "Hoje · ${history.reviewedCards} revisões")
                    manager.updateAppWidget(trendIds, trendViews)
                }
                lastRefreshElapsedMs = SystemClock.elapsedRealtime()
            }
        }
        private fun computeExactStreak(timestamps: List<Long>, today: LocalDate, zone: ZoneId): Int {
            if (timestamps.isEmpty()) return 0
            val days = HashSet<LocalDate>()
            for (ts in timestamps) {
                val d = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
                if (!d.isAfter(today)) days.add(d)
            }
            var cursor = when {
                today in days -> today
                today.minusDays(1) in days -> today.minusDays(1)
                else -> return 0
            }
            var streak = 0
            while (cursor in days) {
                streak++
                cursor = cursor.minusDays(1)
            }
            return streak
        }

        internal const val OPEN_PROGRESS = "com.studydock.app.OPEN_REVIEW_PROGRESS"
    }
}

class ReviewTrendWidget : ReviewWidget()
