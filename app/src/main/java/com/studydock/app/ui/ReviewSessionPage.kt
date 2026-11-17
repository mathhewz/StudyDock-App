@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.studydock.app.ui

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.studydock.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val grades = listOf("De novo", "Difícil", "Bom", "Fácil")
private const val REVIEW_PREFS_NAME = "studydock_review_prefs"
private const val PREF_TTS_AUTO = "tts_auto_play"
private const val PREF_TTS_LANG = "tts_lang_mode"
private const val TTS_LANG_AUTO = "AUTO"
private const val TTS_LANG_PT = "PT-BR"
private const val TTS_LANG_EN = "EN-US"

/** Draining closes an interval, so leaving the UI twice cannot count it twice. */
internal class ActiveAnswerClock(private val monotonic: () -> Long) {
    private var started: Long? = null
    fun start() { if (started == null) started = monotonic() }
    fun peek(): Long = started?.let { (monotonic() - it).coerceAtLeast(0) } ?: 0L
    fun drain(): Long {
        val from = started ?: return 0
        started = null
        return (monotonic() - from).coerceAtLeast(0)
    }
}

private fun formatReviewSeconds(totalSeconds: Long): String {
    val sec = totalSeconds.coerceAtLeast(0L)
    val mins = sec / 60L
    val rem = sec % 60L
    return if (mins > 0) "${mins}m ${rem}s" else "${rem}s"
}

private val ttsBrTagRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val ttsHtmlStripRegex = Regex("""<[^>]+>""")
private val ttsSoundRegex = Regex("""\[sound:[^\]\n]+]""", RegexOption.IGNORE_CASE)
private val ttsTypeRegex = Regex("""\{\{type:([^}]+)\}\}""", RegexOption.IGNORE_CASE)
private val ttsMarkdownRegex = Regex("""(\*\*|__|~~|==|`)""")
private val ttsWhitespaceRegex = Regex("""\s+""")
private val ttsNonWordRegex = Regex("""[^a-zà-ÿ]+""")
private val cardTypeTagRegex = Regex("""\{\{type:([^}]+)\}\}""", RegexOption.IGNORE_CASE)
private val reviewTagSplitRegex = Regex("[,\\s]+")

private fun cleanTextForTts(raw: String): String {
    if (raw.isBlank()) return ""
    var s = raw
        .replace(ttsBrTagRegex, ". ")
        .replace(ttsSoundRegex, " ")
        .replace(ttsTypeRegex, " resposta ")
        .replace(ttsHtmlStripRegex, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("$$", " ")
        .replace("$", " ")
        .replace("\\(", " ")
        .replace("\\)", " ")
        .replace("\\[", " ")
        .replace("\\]", " ")
        .replace("[…]", " lacuna ")
        .replace("[...]", " lacuna ")
    s = ttsMarkdownRegex.replace(s, "")
    return s.replace(ttsWhitespaceRegex, " ").trim()
}

private val ptAccentRegex = Regex("""[áàâãéêíóôõúçÁÀÂÃÉÊÍÓÔÕÚÇ]""")
private val enStopwords = setOf(
    "the", "and", "is", "are", "was", "were", "with", "from", "that", "this",
    "what", "when", "which", "where", "who", "how", "have", "has", "for", "you", "not"
)
private val ptStopwords = setOf(
    "que", "para", "como", "uma", "uns", "umas", "dos", "das", "nos", "nas",
    "por", "com", "não", "são", "está", "entre", "sobre", "qual", "quais", "pela", "pelo"
)

private fun resolveTtsLocale(text: String, mode: String): Locale {
    return when (mode) {
        TTS_LANG_PT -> Locale("pt", "BR")
        TTS_LANG_EN -> Locale.US
        else -> {
            var ptWordCount = 0
            var enWordCount = 0
            for (word in text.lowercase().split(ttsNonWordRegex)) {
                if (word in ptStopwords) ptWordCount++
                if (word in enStopwords) enWordCount++
            }
            val ptHints = ptAccentRegex.findAll(text).count() * 2 + ptWordCount
            if (enWordCount > ptHints) Locale.US else Locale("pt", "BR")
        }
    }
}

private fun nextTtsLangMode(current: String): String = when (current) {
    TTS_LANG_AUTO -> TTS_LANG_PT
    TTS_LANG_PT -> TTS_LANG_EN
    else -> TTS_LANG_AUTO
}

private fun ttsLangBadgeLabel(mode: String): String = when (mode) {
    TTS_LANG_PT -> "PT-BR"
    TTS_LANG_EN -> "EN"
    else -> "Auto PT/EN"
}

private class ScratchStroke(
    val color: Color,
    val widthPx: Float,
    val isEraser: Boolean,
    val path: Path,
    val strokeStyle: Stroke = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
)

@Stable
private class LiveReviewTimerState {
    var clock: ActiveAnswerClock? by mutableStateOf(null)
    var baseCardElapsedMs: Long by mutableLongStateOf(0L)
    var isRunning: Boolean by mutableStateOf(false)
}

@Composable
private fun ReviewElapsedTimerPill(
    baseRoundMs: Long,
    hasReady: Boolean,
    timerState: LiveReviewTimerState,
    modifier: Modifier = Modifier
) {
    val activeClock = timerState.clock
    val running = timerState.isRunning
    var liveMs by remember(activeClock, running) {
        mutableLongStateOf(activeClock?.peek() ?: 0L)
    }
    LaunchedEffect(activeClock, running) {
        if (activeClock == null || !running) {
            liveMs = activeClock?.peek() ?: 0L
            return@LaunchedEffect
        }
        while (true) {
            liveMs = activeClock.peek()
            delay(1000L)
        }
    }
    val roundSeconds = ((baseRoundMs + liveMs) / 1000L).coerceAtLeast(0L)
    val cardSeconds = ((timerState.baseCardElapsedMs + liveMs) / 1000L).coerceAtLeast(0L)
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Text(
            if (hasReady) "⏱ ${formatReviewSeconds(cardSeconds)} · Rodada ${formatReviewSeconds(roundSeconds)}"
            else "⏱ Rodada ${formatReviewSeconds(roundSeconds)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ReviewGradeButtonsBar(
    row: RoundCard,
    activeDeck: CardDeck?,
    submitting: Boolean,
    onAnswer: (Int) -> Unit
) {
    val card = row.card
    var previewNow by remember(card.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(card.id) {
        while (true) {
            delay(15_000L)
            previewNow = System.currentTimeMillis()
        }
    }
    val previewLabels = remember(
        card.id,
        card.dueAt,
        card.intervalDays,
        card.repetitions,
        card.lapses,
        card.easeFactor,
        card.learningState,
        card.learningStep,
        card.fsrsStability,
        card.fsrsDifficulty,
        activeDeck,
        previewNow / 15_000L
    ) {
        if (row.entry.cram) emptyList() else card.previewIntervals(now = previewNow, config = activeDeck)
    }

    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val semantic = LocalStudySemanticColors.current
    val scheme = MaterialTheme.colorScheme
    val gradeColors = listOf(scheme.error, semantic.warning, semantic.success, semantic.info)
    val buttonShape = studyControlShape()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val useSingleRow = maxWidth >= 312.dp * fontScale
        @Composable
        fun GradeButton(grade: Int, label: String, modifier: Modifier) {
            val intervalText = if (row.entry.cram) {
                if (grade == 0) "Repetir" else "Manter"
            } else {
                previewLabels.getOrElse(grade) { "—" }
            }
            OutlinedButton(
                onClick = { onAnswer(grade) },
                enabled = !submitting,
                modifier = modifier,
                shape = buttonShape,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = gradeColors[grade],
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        intervalText,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (useSingleRow) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                grades.forEachIndexed { grade, label ->
                    GradeButton(grade, label, Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GradeButton(0, grades[0], Modifier.weight(1f))
                    GradeButton(1, grades[1], Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GradeButton(2, grades[2], Modifier.weight(1f))
                    GradeButton(3, grades[3], Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun RoundBanner(round: List<RoundCard>, resume: () -> Unit, finish: () -> Unit) {
    val pending = round.filter { !it.entry.done && !it.card.suspended }
    val remaining = pending.size
    if (remaining == 0) return
    val isCram = pending.any { it.entry.cram }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val labelText = if (isCram) "Estudo livre em aberto · $remaining" else "Rodada em aberto · $remaining"
        if (maxWidth >= 320.dp * fontScale) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    labelText,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = resume,
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
                ) { Text("Retomar", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton(
                    onClick = { confirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
                ) { Text("Encerrar", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    labelText,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = resume,
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
                    ) { Text("Retomar", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    TextButton(
                        onClick = { confirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
                    ) { Text("Encerrar", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    if (confirm) EndRoundDialog({ confirm = false }) { confirm = false; finish() }
}

@Composable
private fun EndRoundDialog(dismiss: () -> Unit, finish: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.imePadding(),
        title = { Text("Encerrar esta rodada?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("As respostas já registradas e as datas de revisão ficam salvas.")
            }
        },
        confirmButton = { Button(finish) { Text("Encerrar") } },
        dismissButton = { TextButton(dismiss) { Text("Continuar estudando") } }
    )
}

@Composable
internal fun ReviewPage(
    round: List<RoundCard>?,
    reviews: List<CardReview>,
    notes: List<Note> = emptyList(),
    loadImage: suspend (Long) -> ByteArray?,
    onGrade: (RoundCard, Int, Long, Boolean, (String?) -> Unit) -> Unit,
    onTime: (RoundCard, Long) -> Unit,
    onPause: () -> Unit,
    onFinish: () -> Unit,
    decks: List<CardDeck> = emptyList(),
    canUndo: Boolean = false,
    onUndo: (suspend () -> Boolean)? = null,
    onBuryCard: ((Flashcard) -> Unit)? = null,
    onSuspendCard: ((Flashcard) -> Unit)? = null,
    onSetFlag: ((Flashcard, Int) -> Unit)? = null,
    onDeleteCard: ((Flashcard) -> Unit)? = null,
    onSaveQuickEdit: (suspend (Flashcard, String, String, String) -> Unit)? = null,
    loadAuthor: (suspend (Long) -> CardAuthorNote?)? = null,
    loadNote: (suspend (Long) -> Note?)? = null,
    loadCardReviews: (suspend (Long) -> List<CardReview>)? = null
) {
    if (round == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Shared preferences for TTS (engine is created lazily on demand)
    val reviewPrefs = remember(context) { context.getSharedPreferences(REVIEW_PREFS_NAME, Context.MODE_PRIVATE) }
    var autoPlayTts by remember { mutableStateOf(reviewPrefs.getBoolean(PREF_TTS_AUTO, false)) }
    var ttsLangMode by remember { mutableStateOf(reviewPrefs.getString(PREF_TTS_LANG, TTS_LANG_AUTO) ?: TTS_LANG_AUTO) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsFailed by remember { mutableStateOf(false) }
    var speakingSide by remember { mutableStateOf<String?>(null) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var pendingSpeak by remember { mutableStateOf<Pair<String, String>?>(null) }

    DisposableEffect(context) {
        onDispose {
            pendingSpeak = null
            speakingSide = null
            ttsReady = false
            val engine = ttsEngine
            ttsEngine = null
            if (engine != null) {
                runCatching { engine.stop() }
                runCatching { engine.shutdown() }
            }
        }
    }

    DisposableEffect(owner, ttsEngine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                pendingSpeak = null
                speakingSide = null
                runCatching { ttsEngine?.stop() }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    fun stopSpeaking() {
        pendingSpeak = null
        speakingSide = null
        runCatching { ttsEngine?.stop() }
    }

    fun dispatchSpeak(engine: TextToSpeech, cleanText: String, side: String) {
        val locale = resolveTtsLocale(cleanText, ttsLangMode)
        val avail = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            runCatching { engine.setLanguage(Locale.getDefault()) }
        }
        speakingSide = side
        engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "$side:${UUID.randomUUID()}")
    }

    fun speakText(raw: String, side: String) {
        val clean = cleanTextForTts(raw)
        if (clean.isBlank()) return
        if (speakingSide == side) {
            stopSpeaking()
            return
        }
        val existing = ttsEngine
        if (existing != null) {
            if (ttsReady) {
                dispatchSpeak(existing, clean, side)
            } else if (!ttsFailed) {
                pendingSpeak = clean to side
            }
            return
        }
        if (ttsFailed) return
        pendingSpeak = clean to side
        lateinit var created: TextToSpeech
        created = TextToSpeech(context.applicationContext) { status ->
            scope.launch(Dispatchers.Main) {
                if (ttsEngine !== created) return@launch
                val ok = (status == TextToSpeech.SUCCESS)
                ttsReady = ok
                ttsFailed = !ok
                if (ok) {
                    val queued = pendingSpeak
                    pendingSpeak = null
                    if (queued != null) {
                        dispatchSpeak(created, queued.first, queued.second)
                    }
                } else {
                    pendingSpeak = null
                }
            }
        }
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val sideTag = utteranceId?.substringBefore(':')
                scope.launch(Dispatchers.Main) {
                    if (ttsEngine === created) speakingSide = sideTag
                }
            }
            override fun onDone(utteranceId: String?) {
                scope.launch(Dispatchers.Main) {
                    if (ttsEngine === created) speakingSide = null
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                scope.launch(Dispatchers.Main) {
                    if (ttsEngine === created) speakingSide = null
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                scope.launch(Dispatchers.Main) {
                    if (ttsEngine === created) speakingSide = null
                }
            }
        })
        ttsEngine = created
    }

    val eligible = remember(round) { round.filterNot { it.card.suspended } }
    val pending = remember(eligible) { eligible.filterNot { it.entry.done } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var forceAdvanceCardId by rememberSaveable { mutableStateOf<Long?>(null) }
    var forceAdvanceToken by rememberSaveable { mutableStateOf<String?>(null) }
    var forceAdvanceDueAt by rememberSaveable { mutableStateOf<Long?>(null) }
    fun RoundCard.isForcedReady(): Boolean =
        forceAdvanceCardId == card.id && forceAdvanceToken == entry.token && forceAdvanceDueAt == card.dueAt

    val ready = pending.firstOrNull {
        it.entry.cram || it.card.dueAt <= now || it.isForcedReady()
    }
    val nextDue = pending.minOfOrNull { it.card.dueAt }
    LaunchedEffect(round, ready?.card?.id, nextDue) {
        now = System.currentTimeMillis()
        if (ready == null && nextDue != null) {
            while (now < nextDue) {
                delay((nextDue - System.currentTimeMillis()).coerceIn(1, 1000))
                now = System.currentTimeMillis()
            }
        }
    }

    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var showScheduling by rememberSaveable { mutableStateOf(false) }
    var showScratchpad by rememberSaveable { mutableStateOf(false) }
    var undoing by remember { mutableStateOf(false) }
    var childDialogOrInputBlocking by remember { mutableStateOf(false) }
    val timerState = remember { LiveReviewTimerState() }

    fun triggerUndo() {
        val undoAction = onUndo ?: return
        if (!canUndo || undoing) return
        stopSpeaking()
        undoing = true
        scope.launch {
            try {
                undoAction()
            } finally {
                undoing = false
            }
        }
    }

    val finished = eligible.size - pending.size
    val isCramRound = eligible.any { it.entry.cram }
    var baseRoundMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(eligible, reviews) {
        baseRoundMs = withContext(Dispatchers.Default) {
            val doneIds = HashSet<Long>(eligible.size)
            var activeMs = 0L
            for (rc in eligible) {
                activeMs += rc.entry.elapsedMs
                if (rc.entry.done) doneIds.add(rc.card.id)
            }
            var doneMs = 0L
            if (doneIds.isNotEmpty()) {
                val latestByCard = HashMap<Long, Pair<Long, Long>>(doneIds.size)
                for (rev in reviews) {
                    if (rev.cardId in doneIds) {
                        val prev = latestByCard[rev.cardId]
                        if (prev == null || rev.reviewedAt > prev.first) {
                            latestByCard[rev.cardId] = rev.reviewedAt to (rev.durationMs ?: 0L)
                        }
                    }
                }
                for (entry in latestByCard.values) {
                    doneMs += entry.second
                }
            }
            (doneMs + activeMs).coerceAtLeast(0L)
        }
    }

    val rootRequester = remember { FocusRequester() }
    LaunchedEffect(ready == null) {
        if (ready == null) {
            timerState.isRunning = false
            timerState.clock = null
            rootRequester.requestFocus()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .padding(vertical = StudyTokens.itemSpacing())
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.action != AndroidKey.ACTION_UP) return@onPreviewKeyEvent false
                val blocked = confirmEnd || showScheduling || childDialogOrInputBlocking
                if (!blocked && canUndo && onUndo != null && key.keyCode == AndroidKey.KEYCODE_Z && !key.isShiftPressed) {
                    triggerUndo()
                    return@onPreviewKeyEvent true
                }
                false
            }
            .then(if (ready == null) Modifier.focusRequester(rootRequester).focusable() else Modifier),
        verticalArrangement = Arrangement.spacedBy(StudyTokens.itemSpacing())
    ) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        var sessionMenu by remember { mutableStateOf(false) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val inlineSessionButtons = maxWidth >= 360.dp * fontScale
            StudyPageHeader(
                title = "Revisão",
                subtitle = if (isCramRound) "Estudo livre · não altera intervalos" else null,
                showGlobalSearch = false,
                actions = {
                    if (onUndo != null) {
                        IconButton(
                            onClick = ::triggerUndo,
                            enabled = canUndo && !undoing,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer última revisão (Z)", modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(
                        onClick = { showScratchpad = !showScratchpad },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.Brush,
                            if (showScratchpad) "Ocultar rascunho" else "Abrir rascunho",
                            tint = if (showScratchpad) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton({ showScheduling = true }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Outlined.Info, "Como funciona o agendamento", modifier = Modifier.size(20.dp))
                    }
                    if (pending.isNotEmpty()) {
                        if (inlineSessionButtons) {
                            TextButton(
                                onClick = { stopSpeaking(); onPause() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text("Pausar", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            TextButton(
                                onClick = { confirmEnd = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text("Encerrar", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else {
                            Box {
                                IconButton({ sessionMenu = true }, modifier = Modifier.size(38.dp)) {
                                    Icon(Icons.Default.MoreVert, "Opções da sessão", modifier = Modifier.size(20.dp))
                                }
                                DropdownMenu(sessionMenu, { sessionMenu = false }) {
                                    DropdownMenuItem(text = { Text("Pausar rodada") }, onClick = { sessionMenu = false; stopSpeaking(); onPause() })
                                    DropdownMenuItem(text = { Text("Encerrar rodada") }, onClick = { sessionMenu = false; confirmEnd = true })
                                }
                            }
                        }
                    }
                }
            )
        }
        LinearProgressIndicator(progress = { finished.toFloat() / eligible.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())

        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "$finished de ${eligible.size} cartões",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Spacer(Modifier.weight(1f))
            ReviewElapsedTimerPill(
                baseRoundMs = baseRoundMs,
                hasReady = ready != null,
                timerState = timerState,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            // TTS quick controls (Language PT/EN/Auto + Auto-play toggle)
            FilterChip(
                selected = autoPlayTts,
                onClick = {
                    val next = !autoPlayTts
                    autoPlayTts = next
                    reviewPrefs.edit().putBoolean(PREF_TTS_AUTO, next).apply()
                    if (!next) stopSpeaking()
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(15.dp))
                },
                label = {
                    Text(
                        if (autoPlayTts) "Auto áudio" else "Áudio manual",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                },
                modifier = Modifier.height(30.dp)
            )
            AssistChip(
                onClick = {
                    val next = nextTtsLangMode(ttsLangMode)
                    ttsLangMode = next
                    reviewPrefs.edit().putString(PREF_TTS_LANG, next).apply()
                },
                label = {
                    Text(
                        ttsLangBadgeLabel(ttsLangMode),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                },
                modifier = Modifier.height(30.dp)
            )
        }

        if (ready != null) {
            val allowEarly = ready.isForcedReady()
            key(ready.entry.token, ready.card.id, ready.card.dueAt, ready.entry.position) {
                ReviewPrompt(
                    row = ready,
                    reviews = reviews,
                    notes = notes,
                    decks = decks,
                    loadImage = loadImage,
                    loadAuthor = loadAuthor,
                    loadNote = loadNote,
                    loadCardReviews = loadCardReviews,
                    onGrade = { row, grade, ms, cb ->
                        stopSpeaking()
                        onGrade(row, grade, ms, allowEarly) { message ->
                            if (message == null && allowEarly) {
                                forceAdvanceCardId = null
                                forceAdvanceToken = null
                                forceAdvanceDueAt = null
                            }
                            cb(message)
                        }
                    },
                    onTime = onTime,
                    onBuryCard = onBuryCard,
                    onSuspendCard = onSuspendCard,
                    onSetFlag = onSetFlag,
                    onDeleteCard = onDeleteCard,
                    onSaveQuickEdit = onSaveQuickEdit,
                    showScratchpad = showScratchpad,
                    autoPlayTts = autoPlayTts,
                    ttsFailed = ttsFailed,
                    speakingSide = speakingSide,
                    onSpeak = ::speakText,
                    onStopSpeak = ::stopSpeaking,
                    timerState = timerState,
                    onBlockingInputChanged = { blocking -> childDialogOrInputBlocking = blocking },
                    dialogOpen = confirmEnd || showScheduling
                )
            }
        } else if (nextDue != null) {
            val seconds = ((nextDue - now + 999) / 1000).coerceAtLeast(0)
            Text("Próxima repetição em ${seconds / 60}min ${seconds % 60}s", style = MaterialTheme.typography.titleMedium)
            Text("Você pode pausar, desfazer (Z) ou revisar sem esperar.")
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        pending.minByOrNull { it.card.dueAt }?.let { target ->
                            forceAdvanceCardId = target.card.id
                            forceAdvanceToken = target.entry.token
                            forceAdvanceDueAt = target.card.dueAt
                        }
                    },
                    shape = studyControlShape()
                ) {
                    Text("Revisar agora sem esperar", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            val completedRoundSeconds = (baseRoundMs / 1000L).coerceAtLeast(0L)
            Text("Rodada concluída", style = MaterialTheme.typography.headlineSmall)
            if (completedRoundSeconds > 0L) {
                Text(
                    "Tempo dedicado na revisão: ${formatReviewSeconds(completedRoundSeconds)} (contabilizado nas estatísticas de estudo).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canUndo && onUndo != null) {
                    OutlinedButton(::triggerUndo, enabled = !undoing, shape = studyControlShape()) {
                        Text("Desfazer última revisão", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Button(onFinish, shape = studyControlShape()) {
                    Text("Voltar aos cartões", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (showScheduling) SchedulingInfoDialog { showScheduling = false }
    if (confirmEnd) EndRoundDialog({ confirmEnd = false }) { confirmEnd = false; stopSpeaking(); onFinish() }
}

@Composable
private fun ColumnScope.ReviewPrompt(
    row: RoundCard,
    reviews: List<CardReview>,
    notes: List<Note>,
    decks: List<CardDeck>,
    loadImage: suspend (Long) -> ByteArray?,
    loadAuthor: (suspend (Long) -> CardAuthorNote?)?,
    onGrade: (RoundCard, Int, Long, (String?) -> Unit) -> Unit,
    onTime: (RoundCard, Long) -> Unit,
    onBuryCard: ((Flashcard) -> Unit)?,
    onSuspendCard: ((Flashcard) -> Unit)?,
    onSetFlag: ((Flashcard, Int) -> Unit)?,
    onDeleteCard: ((Flashcard) -> Unit)?,
    onSaveQuickEdit: (suspend (Flashcard, String, String, String) -> Unit)?,
    loadNote: (suspend (Long) -> Note?)?,
    loadCardReviews: (suspend (Long) -> List<CardReview>)?,
    showScratchpad: Boolean,
    autoPlayTts: Boolean,
    ttsFailed: Boolean,
    speakingSide: String?,
    onSpeak: (String, String) -> Unit,
    onStopSpeak: () -> Unit,
    timerState: LiveReviewTimerState,
    onBlockingInputChanged: (Boolean) -> Unit,
    dialogOpen: Boolean
) {
    val card = row.card
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var revealed by rememberSaveable { mutableStateOf(false) }
    val cardDeckCfg = remember(decks, card.deckId) { decks.firstOrNull { it.id == card.deckId } }
    val autoAdvanceSec = cardDeckCfg?.autoAdvanceSec ?: 0
    LaunchedEffect(card.id, revealed, autoAdvanceSec, dialogOpen) {
        if (!revealed && autoAdvanceSec > 0 && !dialogOpen) {
            kotlinx.coroutines.delay(autoAdvanceSec * 1000L)
            revealed = true
        }
    }
    var writing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var textFieldFocused by remember { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var showFlagPicker by rememberSaveable { mutableStateOf(false) }
    var showQuickEdit by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Optimistic card fields after Quick Edit / Flag action until DB stream refreshes
    var editedFront by remember(card.id, card.front) { mutableStateOf(card.front) }
    var editedBack by remember(card.id, card.back) { mutableStateOf(card.back) }
    var editedTags by remember(card.id, card.tags) { mutableStateOf(card.tags) }
    var activeFlag by remember(card.id, card.flag) { mutableIntStateOf(card.flag) }

    var authorNote by remember(card.id, card.authorNoteId) { mutableStateOf<CardAuthorNote?>(null) }
    LaunchedEffect(card.authorNoteId, loadAuthor) {
        authorNote = card.authorNoteId?.let { id -> loadAuthor?.invoke(id) }
    }

    val expectsTypedAnswer = remember(editedFront, editedBack, authorNote) {
        authorNote?.typeAnswer == true ||
            editedFront.contains("{{type:", ignoreCase = true) ||
            editedBack.contains("{{type:", ignoreCase = true) ||
            editedFront.contains("[Digite a resposta]") ||
            authorNote?.prompt?.contains("{{type:", ignoreCase = true) == true ||
            authorNote?.template?.contains("{{type:", ignoreCase = true) == true
    }
    LaunchedEffect(expectsTypedAnswer) {
        if (expectsTypedAnswer) writing = true
    }

    val paramSeed = remember(card.id, card.repetitions, card.lapses) {
        card.id xor (card.repetitions.toLong() * 131L + card.lapses.toLong() * 977L)
    }
    val (paramFront, paramBack) = remember(editedFront, editedBack, paramSeed) {
        ParametricCardEngine.render(editedFront, editedBack, paramSeed)
    }
    val displayFront = remember(paramFront) {
        cardTypeTagRegex.replace(paramFront) { "[Digite a resposta]" }
    }
    val displayBack = remember(paramBack) {
        cardTypeTagRegex.replace(paramBack) { m -> m.groupValues[1] }
    }
    val frontTtsClean = remember(displayFront) { cleanTextForTts(displayFront) }
    val backTtsClean = remember(displayBack) { cleanTextForTts(displayBack) }
    val expectedTypeTarget = remember(paramFront, paramBack, displayBack) {
        val fromBack = cardTypeTagRegex.find(paramBack)?.groupValues?.getOrNull(1)
        val fromFront = cardTypeTagRegex.find(paramFront)?.groupValues?.getOrNull(1)
        fromBack ?: fromFront ?: displayBack
    }

    val occlusionRaw = remember(row.occlusion, authorNote?.occlusion) {
        row.occlusion?.takeIf { it.isNotBlank() } ?: authorNote?.occlusion
    }
    val occlusionBoxes = remember(occlusionRaw) { parseOcclusionBoxes(occlusionRaw) }
    val isOcclusionCard = occlusionBoxes.isNotEmpty() ||
        authorNote?.format.equals("occlusion", ignoreCase = true)
    val targetBoxId = remember(card.variantKey) { parseTargetOcclusionBoxId(card.variantKey) }
    val effectiveHideAll = occlusionBoxes.firstOrNull()?.hideAll ?: true

    val clock = remember { ActiveAnswerClock(SystemClock::elapsedRealtime) }
    val owner = LocalLifecycleOwner.current
    val modalOpen = showInfo || showSource || showQuickEdit || showFlagPicker || confirmDelete || dialogOpen
    val focused = !submitting && !modalOpen
    LaunchedEffect(modalOpen, textFieldFocused) {
        onBlockingInputChanged(modalOpen || textFieldFocused)
    }

    SideEffect {
        if (timerState.clock !== clock) timerState.clock = clock
        timerState.baseCardElapsedMs = row.entry.elapsedMs
        timerState.isRunning = focused
    }
    DisposableEffect(clock) {
        onDispose {
            if (timerState.clock === clock) {
                timerState.isRunning = false
                timerState.clock = null
            }
        }
    }

    val saveTime by rememberUpdatedState(onTime)
    DisposableEffect(owner, focused) {
        fun flush() {
            val ms = clock.drain()
            if (ms > 0) saveTime(row, ms)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && focused) clock.start()
            if (event == Lifecycle.Event.ON_PAUSE) {
                onStopSpeak()
                flush()
            }
        }
        owner.lifecycle.addObserver(observer)
        if (focused && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) clock.start()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            flush()
        }
    }

    // Auto-play TTS when enabled (initializes TTS lazily on first card requiring speech)
    LaunchedEffect(card.id, revealed, autoPlayTts) {
        if (autoPlayTts && !ttsFailed) {
            if (!revealed) {
                if (frontTtsClean.isNotBlank()) onSpeak(displayFront, "front")
            } else {
                if (backTtsClean.isNotBlank()) onSpeak(displayBack, "back")
            }
        }
    }

    fun answer(grade: Int) {
        if (submitting || !revealed) return
        submitting = true
        onStopSpeak()
        val ms = clock.drain()
        onGrade(row, grade, ms) { message ->
            if (message != null) {
                submitting = false
                error = message
                clock.start()
            }
        }
    }

    val requester = remember { FocusRequester() }
    val answerFieldRequester = remember { FocusRequester() }
    LaunchedEffect(revealed, focused, expectsTypedAnswer) {
        if (!focused) return@LaunchedEffect
        if (expectsTypedAnswer && !revealed) {
            runCatching { answerFieldRequester.requestFocus() }
        } else {
            runCatching { requester.requestFocus() }
        }
    }

    val activeDeck = remember(decks, card.deckId) { decks.firstOrNull { it.id == card.deckId } }
    val parsedTags = remember(editedTags) {
        if (editedTags.isBlank()) emptyList()
        else editedTags.split(reviewTagSplitRegex).filter { it.isNotBlank() }
    }
    var sourceNote by remember(card.sourceNoteId, notes) {
        mutableStateOf(notes.firstOrNull { it.id == card.sourceNoteId })
    }
    LaunchedEffect(card.sourceNoteId, showSource, loadNote) {
        val nid = card.sourceNoteId ?: return@LaunchedEffect
        if (sourceNote == null && showSource && loadNote != null) {
            sourceNote = loadNote(nid)
        }
    }

    // Scratchpad strokes automatically clear whenever card / entry changes
    val scratchStrokes = remember(card.id, row.entry.token) { mutableStateListOf<ScratchStroke>() }

    val hasAnyQuickAction = onSetFlag != null || onSaveQuickEdit != null ||
        onBuryCard != null || onSuspendCard != null || onDeleteCard != null

    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.action != AndroidKey.ACTION_UP || modalOpen || textFieldFocused) return@onPreviewKeyEvent false
                when {
                    !revealed && (key.keyCode == AndroidKey.KEYCODE_SPACE ||
                        key.keyCode == AndroidKey.KEYCODE_ENTER ||
                        key.keyCode == AndroidKey.KEYCODE_NUMPAD_ENTER) -> {
                        revealed = true
                        true
                    }
                    revealed && key.keyCode in AndroidKey.KEYCODE_1..AndroidKey.KEYCODE_4 -> {
                        answer(key.keyCode - AndroidKey.KEYCODE_1)
                        true
                    }
                    key.keyCode == AndroidKey.KEYCODE_F && onSetFlag != null -> {
                        showFlagPicker = true
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(requester)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StudyCard(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(StudyTokens.cardPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Pergunta",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (activeFlag in 1..7) {
                        val flagColor = ankiFlagColor(activeFlag)
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = flagColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, flagColor.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(enabled = onSetFlag != null) { showFlagPicker = true }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Flag, contentDescription = ankiFlagName(activeFlag), tint = flagColor, modifier = Modifier.size(13.dp))
                                Text(ankiFlagName(activeFlag), style = MaterialTheme.typography.labelSmall, color = flagColor, maxLines = 1)
                            }
                        }
                    } else if (onSetFlag != null) {
                        IconButton(onClick = { showFlagPicker = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Flag, "Marcar bandeira (F)", modifier = Modifier.size(17.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onSpeak(displayFront, "front") },
                        enabled = !ttsFailed && frontTtsClean.isNotBlank(),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (speakingSide == "front") Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            if (speakingSide == "front") "Parar leitura" else "Ouvir pergunta",
                            tint = if (speakingSide == "front") MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    TextButton(
                        onClick = { showInfo = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text("Histórico", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (hasAnyQuickAction) {
                        Box {
                            IconButton(onClick = { showQuickMenu = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.MoreVert, "Ações rápidas do cartão", modifier = Modifier.size(19.dp))
                            }
                            DropdownMenu(expanded = showQuickMenu, onDismissRequest = { showQuickMenu = false }) {
                                if (onSetFlag != null) {
                                    DropdownMenuItem(
                                        text = { Text("Bandeira (1–7)") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Flag,
                                                null,
                                                tint = if (activeFlag in 1..7) ankiFlagColor(activeFlag) else LocalContentColor.current
                                            )
                                        },
                                        onClick = { showQuickMenu = false; showFlagPicker = true }
                                    )
                                }
                                if (onSaveQuickEdit != null) {
                                    DropdownMenuItem(
                                        text = { Text("Editar rapidamente") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = { showQuickMenu = false; showQuickEdit = true }
                                    )
                                }
                                if (onBuryCard != null) {
                                    DropdownMenuItem(
                                        text = { Text("Enterrar até amanhã") },
                                        leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                                        onClick = { showQuickMenu = false; onStopSpeak(); onBuryCard(card) }
                                    )
                                }
                                if (onSuspendCard != null) {
                                    DropdownMenuItem(
                                        text = { Text("Suspender cartão") },
                                        leadingIcon = { Icon(Icons.Default.PauseCircleOutline, null) },
                                        onClick = { showQuickMenu = false; onStopSpeak(); onSuspendCard(card) }
                                    )
                                }
                                if (onDeleteCard != null) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Excluir cartão", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { showQuickMenu = false; confirmDelete = true }
                                    )
                                }
                            }
                        }
                    }
                }

                CardContent(displayFront, style = MaterialTheme.typography.headlineSmall)

                val showPictureOnFront = isOcclusionCard || displayFront.contains("🖼️") || displayFront.contains("[Imagem:")
                if (showPictureOnFront) {
                    card.authorNoteId?.let { id ->
                        if (isOcclusionCard && occlusionBoxes.isNotEmpty()) {
                            AnswerPicture(
                                identity = authorNote?.imageKey ?: id,
                                occlusionBoxes = occlusionBoxes,
                                activeVariantKey = card.variantKey,
                                targetBoxId = targetBoxId,
                                revealed = revealed,
                                hideAll = effectiveHideAll
                            ) { loadImage(id) }
                        } else {
                            AnswerPicture(id) { loadImage(id) }
                        }
                    }
                }

                if (!revealed && !writing) {
                    TextButton({ writing = true }) {
                        Text("Escrever minha resposta", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (writing && !revealed) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .focusRequester(answerFieldRequester)
                            .onFocusChanged { textFieldFocused = it.isFocused },
                        label = { Text(if (expectsTypedAnswer) "Digite sua resposta" else "Minha resposta") },
                        placeholder = if (expectsTypedAnswer) {
                            { Text("Pressione Enter para conferir") }
                        } else null,
                        singleLine = expectsTypedAnswer,
                        keyboardOptions = if (expectsTypedAnswer) {
                            KeyboardOptions(imeAction = ImeAction.Done)
                        } else KeyboardOptions.Default,
                        keyboardActions = if (expectsTypedAnswer) {
                            KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                textFieldFocused = false
                                revealed = true
                            })
                        } else KeyboardActions.Default,
                        shape = studyControlShape()
                    )
                }

                if (revealed) {
                    HorizontalDivider()
                    if (expectsTypedAnswer || draft.isNotBlank()) {
                        TypeAnswerDiffCard(expected = expectedTypeTarget, typed = draft)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Resposta do cartão", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        IconButton(
                            onClick = { onSpeak(displayBack, "back") },
                            enabled = !ttsFailed && backTtsClean.isNotBlank(),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (speakingSide == "back") Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                if (speakingSide == "back") "Parar leitura" else "Ouvir resposta",
                                tint = if (speakingSide == "back") MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    CardContent(displayBack, style = MaterialTheme.typography.titleMedium)

                    if (!showPictureOnFront) {
                        card.authorNoteId?.let { id -> AnswerPicture(id) { loadImage(id) } }
                    }
                    if (parsedTags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            parsedTags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (card.sourceNoteId != null && (sourceNote != null || loadNote != null)) {
                        TextButton({ showSource = true }) { Text("Ver trecho na nota", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (showSource) {
                            sourceNote?.let { loadedNote ->
                                SourceExcerpt(loadedNote, card.sourceExcerpt.orEmpty()) { showSource = false }
                            }
                        }
                    }
                }
            }
        }

        if (showScratchpad) {
            ReviewScratchpadCard(strokes = scratchStrokes)
        }

        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        if (!revealed) {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    textFieldFocused = false
                    revealed = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = studyControlShape()
            ) {
                Text("Mostrar resposta", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            ReviewGradeButtonsBar(
                row = row,
                activeDeck = activeDeck,
                submitting = submitting,
                onAnswer = ::answer
            )
        }
    }

    if (showInfo) CardHistory(card, reviews, loadCardReviews) { showInfo = false }
    if (showFlagPicker && onSetFlag != null) {
        FlagPickerDialog(
            currentFlag = activeFlag,
            onSelectFlag = { nextFlag ->
                activeFlag = nextFlag
                onSetFlag(card, nextFlag)
                showFlagPicker = false
            },
            onDismiss = { showFlagPicker = false }
        )
    }
    if (showQuickEdit && onSaveQuickEdit != null) {
        QuickEditCardDialog(
            initialFront = editedFront,
            initialBack = editedBack,
            initialTags = editedTags,
            onSave = { f, b, t ->
                onSaveQuickEdit(card, f, b, t)
                editedFront = f
                editedBack = b
                editedTags = t
                showQuickEdit = false
            },
            onDismiss = { showQuickEdit = false }
        )
    }
    if (confirmDelete && onDeleteCard != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir cartão?") },
            text = {
                Text(
                    "Esta ação remove o cartão \"${editedFront.replace('\n', ' ').take(80)}\" e todo o seu histórico de revisões permanentemente."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onStopSpeak()
                        onDeleteCard(card)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton({ confirmDelete = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun TypeAnswerDiffCard(expected: String, typed: String) {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.surface.luminance() < 0.5f
    val diffs = remember(expected, typed) { evaluateTypeAnswer(expected, typed) }
    val matchCount = remember(diffs) { diffs.count { it.status == TypeDiffStatus.MATCH } }
    val total = diffs.size.coerceAtLeast(1)
    val isExact = diffs.isNotEmpty() && diffs.all { it.status == TypeDiffStatus.MATCH }
    val pct = (matchCount * 100) / total

    val greenFg = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
    val greenBg = Color(0xFF22C55E).copy(alpha = 0.18f)
    val redFg = scheme.error
    val redBg = scheme.error.copy(alpha = 0.18f)
    val missingFg = scheme.onSurfaceVariant
    val missingBg = scheme.surfaceContainerHighest

    val diffText = remember(diffs, greenFg, greenBg, redFg, redBg, missingFg, missingBg) {
        buildAnnotatedString {
            diffs.forEach { cell ->
                val ch = if (cell.ch == ' ' && cell.status != TypeDiffStatus.MATCH) '␣' else cell.ch
                when (cell.status) {
                    TypeDiffStatus.MATCH -> {
                        pushStyle(SpanStyle(color = greenFg, background = greenBg, fontWeight = FontWeight.SemiBold))
                        append(ch)
                        pop()
                    }
                    TypeDiffStatus.WRONG -> {
                        pushStyle(
                            SpanStyle(
                                color = redFg,
                                background = redBg,
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                        append(ch)
                        pop()
                    }
                    TypeDiffStatus.MISSING -> {
                        pushStyle(SpanStyle(color = missingFg, background = missingBg))
                        append(ch)
                        pop()
                    }
                    TypeDiffStatus.EXTRA -> {
                        pushStyle(
                            SpanStyle(
                                color = redFg,
                                background = redBg,
                                textDecoration = TextDecoration.LineThrough
                            )
                        )
                        append(ch)
                        pop()
                    }
                }
            }
        }
    }

    Surface(
        shape = studyControlShape(),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (isExact) greenFg.copy(alpha = 0.5f) else scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Comparação da resposta", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isExact) greenBg else scheme.surfaceContainerHigh
                ) {
                    Text(
                        if (isExact) "100% exato!" else "$pct% semelhante",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isExact) greenFg else scheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            if (typed.isBlank()) {
                Text(
                    "Nenhuma resposta digitada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Text(
                text = diffText,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Verde: certo", style = MaterialTheme.typography.labelSmall, color = greenFg)
                Text("Vermelho: errado/extra", style = MaterialTheme.typography.labelSmall, color = redFg)
                Text("Cinza: faltando", style = MaterialTheme.typography.labelSmall, color = missingFg)
            }
        }
    }
}

@Composable
private fun ReviewScratchpadCard(strokes: MutableList<ScratchStroke>) {
    val scheme = MaterialTheme.colorScheme
    val inkDefault = scheme.onSurface
    val palette = remember(inkDefault) {
        listOf(inkDefault, Color(0xFF2563EB), Color(0xFFDC2626), Color(0xFF16A34A))
    }
    var selectedColorIdx by remember { mutableIntStateOf(0) }
    var eraserMode by remember { mutableStateOf(false) }
    val activePath = remember { Path() }
    var activeHasPoints by remember { mutableStateOf(false) }
    var activePathTick by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val penWidthPx = with(density) { 2.8.dp.toPx() }
    val eraserWidthPx = with(density) { 20.dp.toPx() }
    val penStroke = remember(penWidthPx) { Stroke(width = penWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    val eraserStroke = remember(eraserWidthPx) { Stroke(width = eraserWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    val activeColor = palette.getOrElse(selectedColorIdx) { inkDefault }
    val latestColor by rememberUpdatedState(activeColor)
    val latestEraser by rememberUpdatedState(eraserMode)

    Surface(
        shape = studyControlShape(),
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Rascunho",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                Spacer(Modifier.weight(1f))
                palette.forEachIndexed { idx, col ->
                    val selected = !eraserMode && selectedColorIdx == idx
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(col)
                            .border(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) scheme.primary else scheme.outline,
                                shape = CircleShape
                            )
                            .clickable {
                                eraserMode = false
                                selectedColorIdx = idx
                            }
                            .align(Alignment.CenterVertically)
                    )
                }
                FilterChip(
                    selected = eraserMode,
                    onClick = { eraserMode = !eraserMode },
                    leadingIcon = { Icon(Icons.Default.CleaningServices, null, Modifier.size(14.dp)) },
                    label = { Text("Borracha", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp).align(Alignment.CenterVertically)
                )
                IconButton(
                    onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                    enabled = strokes.isNotEmpty(),
                    modifier = Modifier.size(28.dp).align(Alignment.CenterVertically)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer traço", Modifier.size(16.dp))
                }
                TextButton(
                    onClick = {
                        strokes.clear()
                        activePath.reset()
                        activeHasPoints = false
                        activePathTick++
                    },
                    enabled = strokes.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Text("Limpar", style = MaterialTheme.typography.labelSmall)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceContainerLow)
                    .border(1.dp, scheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .pointerInput(penStroke, eraserStroke) {
                            detectTapGestures { pos ->
                                val path = Path().apply {
                                    moveTo(pos.x, pos.y)
                                    lineTo(pos.x + 0.5f, pos.y + 0.5f)
                                }
                                val erase = latestEraser
                                strokes.add(
                                    ScratchStroke(
                                        color = latestColor,
                                        widthPx = if (erase) eraserWidthPx else penWidthPx,
                                        isEraser = erase,
                                        path = path,
                                        strokeStyle = if (erase) eraserStroke else penStroke
                                    )
                                )
                            }
                        }
                        .pointerInput(penStroke, eraserStroke) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    activePath.reset()
                                    activePath.moveTo(start.x, start.y)
                                    activePath.lineTo(start.x + 0.1f, start.y + 0.1f)
                                    activeHasPoints = true
                                    activePathTick++
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    activePath.lineTo(change.position.x, change.position.y)
                                    activePathTick++
                                },
                                onDragEnd = {
                                    if (activeHasPoints) {
                                        val committedPath = Path().apply { addPath(activePath) }
                                        val erase = latestEraser
                                        strokes.add(
                                            ScratchStroke(
                                                color = latestColor,
                                                widthPx = if (erase) eraserWidthPx else penWidthPx,
                                                isEraser = erase,
                                                path = committedPath,
                                                strokeStyle = if (erase) eraserStroke else penStroke
                                            )
                                        )
                                        activePath.reset()
                                        activeHasPoints = false
                                        activePathTick++
                                    }
                                },
                                onDragCancel = {
                                    activePath.reset()
                                    activeHasPoints = false
                                    activePathTick++
                                }
                            )
                        }
                ) {
                    @Suppress("UNUSED_VARIABLE")
                    val drawInvalidation = activePathTick
                    for (i in 0 until strokes.size) {
                        val s = strokes[i]
                        drawPath(
                            path = s.path,
                            color = if (s.isEraser) Color.Black else s.color,
                            blendMode = if (s.isEraser) BlendMode.Clear else BlendMode.SrcOver,
                            style = s.strokeStyle
                        )
                    }
                    if (activeHasPoints) {
                        drawPath(
                            path = activePath,
                            color = if (eraserMode) Color.Black else activeColor,
                            blendMode = if (eraserMode) BlendMode.Clear else BlendMode.SrcOver,
                            style = if (eraserMode) eraserStroke else penStroke
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlagPickerDialog(
    currentFlag: Int,
    onSelectFlag: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bandeira do cartão") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { flag ->
                    val color = ankiFlagColor(flag)
                    Surface(
                        shape = studyControlShape(),
                        color = if (currentFlag == flag) color.copy(alpha = 0.14f) else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(studyControlShape())
                            .clickable { onSelectFlag(flag) }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Flag, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                            Text("$flag · ${ankiFlagName(flag)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (currentFlag == flag) {
                                Text("Ativa", style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (currentFlag != 0) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    TextButton(onClick = { onSelectFlag(0) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Remover bandeira")
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun QuickEditCardDialog(
    initialFront: String,
    initialBack: String,
    initialTags: String,
    onSave: suspend (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var front by rememberSaveable { mutableStateOf(initialFront) }
    var back by rememberSaveable { mutableStateOf(initialBack) }
    var tags by rememberSaveable { mutableStateOf(initialTags) }
    var saving by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        title = { Text("Editar cartão") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it; editError = null },
                    label = { Text("Frente") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    shape = studyControlShape()
                )
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it; editError = null },
                    label = { Text("Verso") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    shape = studyControlShape()
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags (separadas por espaço)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = studyControlShape()
                )
                if (editError != null) {
                    Text(editError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && front.isNotBlank() && back.isNotBlank(),
                onClick = {
                    saving = true
                    editError = null
                    scope.launch {
                        try {
                            onSave(front.trim(), back.trim(), tags.trim())
                        } catch (e: Exception) {
                            editError = e.message ?: "Não foi possível salvar a edição."
                            saving = false
                        }
                    }
                }
            ) {
                Text(if (saving) "Salvando…" else "Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancelar") }
        }
    )
}

@Composable
private fun SourceExcerpt(note: Note, excerpt: String, dismiss: () -> Unit) {
    val at = note.markdown.indexOf(excerpt)
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.imePadding(), title = { Text(note.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (at >= 0 && excerpt.isNotEmpty()) {
                Text(note.markdown.substring((at - 350).coerceAtLeast(0), at))
                Text(excerpt, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(note.markdown.drop(at + excerpt.length).take(350))
            } else Text(note.markdown)
        }
    }, confirmButton = { TextButton(dismiss) { Text("Voltar ao cartão") } })
}

@Composable
internal fun CardHistory(
    card: Flashcard,
    reviews: List<CardReview>,
    loadCardReviews: (suspend (Long) -> List<CardReview>)? = null,
    dismiss: () -> Unit
) {
    var loadedEvents by remember(card.id, reviews) {
        mutableStateOf(
            if (loadCardReviews != null) null
            else reviews.filter { it.cardId == card.id }.sortedByDescending { it.reviewedAt }
        )
    }
    LaunchedEffect(card.id, loadCardReviews) {
        if (loadCardReviews != null) {
            loadedEvents = loadCardReviews(card.id)
        }
    }
    val events = loadedEvents.orEmpty()
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.imePadding(), title = { Text("Histórico do cartão") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "next_due", contentType = "summary") { Text("Próxima revisão · ${date.format(Date(card.dueAt))}") }
            if (card.fsrsStability > 0.0) {
                item(key = "fsrs_stats", contentType = "summary") {
                    Text(
                        String.format(Locale.getDefault(), "FSRS · Estabilidade %.1fd · Dificuldade %.1f/10", card.fsrsStability, card.fsrsDifficulty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            item(key = "events_count", contentType = "summary") { Text(if (events.isEmpty()) "Primeira revisão pendente." else "${events.size} respostas registradas") }
            items(events, key = { it.id }, contentType = { "review_event" }) { event ->
                Column {
                    val isCram = event.priorState.equals("CRAM", ignoreCase = true)
                    Text(
                        "${date.format(Date(event.reviewedAt))} · ${grades.getOrNull(event.grade) ?: "—"}" + if (isCram) " (Livre)" else "",
                        fontWeight = FontWeight.Medium
                    )
                    val interval = if (event.intervalDays == 0) "1 min" else "${event.intervalDays} d"
                    Text("Intervalo · $interval" + (event.durationMs?.let { " · ${it / 1000}s de resposta" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("Fechar") } })
}

@Composable
internal fun SchedulingInfoDialog(dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Agendamento do StudyDock") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Os intervalos exibidos nos botões usam o agendador configurado do baralho (FSRS-4.5/5 ou SM-2 Pro), respeitando passos de aprendizagem e reaprendizagem.")
            Text("Atalhos na revisão: Espaço/Enter revela a resposta, 1–4 avalia, F abre as bandeiras (1–7) e Z / Ctrl+Z desfaz a última revisão.")
            Text("Em Estudo livre (Cram), as revisões não alteram intervalos nem poluem as estatísticas de retenção e sequência diária.")
            Text("Cartões enterrados ficam adiados até o dia lógico seguinte (rollover configurável por baralho). Sanguessugas são sinalizadas automaticamente ao atingir o limite de lapsos.")
        }
    }, confirmButton = { TextButton(dismiss) { Text("Entendi") } })
}
