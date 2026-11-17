@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.ChromeReaderMode
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.ActivityTimer
import com.studydock.app.data.Book
import com.studydock.app.data.WorkoutLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.roundToInt

private fun elapsedLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

@Composable
internal fun ActivityTimerHub(
    flow: Flow<List<ActivityTimer>>,
    books: List<Book>,
    workouts: List<WorkoutLog>,
    start: (String, String, Long?) -> Unit,
    setRunning: (Long, Boolean) -> Unit,
    recordSet: (Long, Int, Int) -> Unit,
    finish: (Long, Int?) -> Unit,
    readingBook: Long?,
    trainingExercise: String?,
    onOpenSounds: () -> Unit = {},
    onQuickHighlight: (Long, String, Int?, String, String) -> Unit = { _, _, _, _, _ -> },
    onOpenReader: (Book) -> Unit = {},
    study: @Composable () -> Unit
) {
    var mode by rememberSaveable { mutableStateOf(when { readingBook != null -> "Leitura"; trainingExercise != null -> "Treino"; else -> "Estudo" }) }
    val timers by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val sectionSpace = StudyTokens.sectionSpacing()
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            listOf("Estudo", "Treino", "Leitura").forEachIndexed { index, label ->
                SegmentedButton(mode == label, { mode = label }, shape = SegmentedButtonDefaults.itemShape(index, 3), icon = {}) {
                    Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (mode == "Estudo") { study(); return@Column }
        key(mode) {
            val active = timers.firstOrNull { it.mode == mode && it.endedAt == null }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = sectionSpace),
                verticalArrangement = Arrangement.spacedBy(sectionSpace)
            ) {
                StudyPageHeader(
                    title = if (mode == "Treino") "Timer de treino" else "Timer de leitura",
                    showGlobalSearch = true,
                    actions = {
                        if (mode == "Leitura") {
                            FilledTonalButton(
                                onClick = onOpenSounds,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Headphones, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Sons", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                )
                if (active == null) {
                    if (mode == "Leitura") {
                        val defaultBookId = readingBook ?: books.firstOrNull { it.status == "READING" }?.id
                        var bookId by rememberSaveable(readingBook) { mutableStateOf(defaultBookId) }
                        val book = books.find { it.id == bookId }
                        TimerChoice("Livro", book?.title ?: "Leitura livre", listOf(null to "Leitura livre") + books.map { it.id to it.title }) { bookId = it }
                        book?.let {
                            ReadingBookProgressCard(
                                book = it,
                                onOpenReader = { onOpenReader(it) }
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { start(mode, book?.title ?: "Leitura livre", book?.id) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Iniciar timer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (book != null) {
                                FilledTonalButton(
                                    onClick = { onOpenReader(book) },
                                    modifier = Modifier.weight(1.1f)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Modo Leitura", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    } else {
                        val context = LocalContext.current
                        val prefs = remember(context) {
                            context.getSharedPreferences("studydock_fitness", Context.MODE_PRIVATE)
                        }
                        val names = remember(prefs, workouts) { exerciseNames(prefs, workouts) }
                        var name by rememberSaveable { mutableStateOf(trainingExercise ?: names.first()) }
                        TimerChoice("Exercício", name.ifBlank { "Outro exercício" }, names.map { it to it } + ("" to "Outro exercício")) { name = it }
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nome do exercício") })
                        Button({ start(mode, name.trim(), null) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
                            Text("Iniciar treino", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else key(active.id) {
                    if (mode == "Treino") PreviousWorkout(active.title, workouts)
                    RunningActivity(
                        timer = active,
                        book = books.find { it.id == active.bookId },
                        setRunning = setRunning,
                        recordSet = recordSet,
                        finish = finish,
                        onQuickHighlight = onQuickHighlight,
                        onOpenReader = onOpenReader
                    )
                }
                val history = remember(timers, mode) { timers.filter { it.mode == mode && it.endedAt != null }.take(10) }
                if (history.isNotEmpty()) {
                    Text("Últimas sessões", style = MaterialTheme.typography.titleMedium)
                    history.forEach {
                        ListItem(
                            headlineContent = { Text(it.title) },
                            supportingContent = { Text(StudyDateFormats.formatEpoch(it.startedAt, StudyDateFormats.DATE_TIME_DOT_DEFAULT)) },
                            trailingContent = { Text(elapsedLabel(it.elapsedMs)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingBookProgressCard(
    book: Book,
    onOpenReader: (() -> Unit)? = null
) {
    val maxPage = book.totalPages?.takeIf { it > 0 }
    val current = maxPage?.let { book.currentPage.coerceIn(0, it) } ?: book.currentPage.coerceAtLeast(0)
    val fraction = maxPage?.let { (current.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    val pct = fraction?.let { (it * 100f).roundToInt().coerceIn(0, 100) }
    StudyCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (pct != null) {
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (book.currentChapter.isNotBlank()) {
                Text(
                    "Capítulo · ${book.currentChapter.trim()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (book.documentName.isNotBlank()) {
                Text(
                    "📄 ${book.documentName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (maxPage != null) "Página $current / $maxPage" else "Página atual · $current",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onOpenReader != null) {
                    TextButton(
                        onClick = onOpenReader,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Abrir no ReadEra", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
            }
        }
    }
}

@Composable
private fun <T> TimerChoice(label: String, selected: String, options: List<Pair<T, String>>, choose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "$label · $selected ▾",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { choose(value); open = false }) }
        }
    }
}

@Composable
private fun RunningActivity(
    timer: ActivityTimer,
    book: Book?,
    setRunning: (Long, Boolean) -> Unit,
    recordSet: (Long, Int, Int) -> Unit,
    finish: (Long, Int?) -> Unit,
    onQuickHighlight: (Long, String, Int?, String, String) -> Unit,
    onOpenReader: (Book) -> Unit = {}
) {
    val running = timer.runningSince != null
    KeepScreenOnEffect(running)
    val baseElapsed = remember(timer.id, timer.runningSince, timer.elapsedMs) {
        timer.duration(System.currentTimeMillis())
    }
    val startMono = remember(timer.id, timer.runningSince, timer.elapsedMs) {
        SystemClock.elapsedRealtime()
    }
    var monoNow by remember(timer.id, timer.runningSince) { mutableLongStateOf(startMono) }
    LaunchedEffect(timer.runningSince, startMono) {
        if (running) {
            while (true) {
                monoNow = SystemClock.elapsedRealtime()
                delay(250)
            }
        }
    }
    val elapsed = if (running) baseElapsed + (monoNow - startMono).coerceAtLeast(0L) else baseElapsed
    StudyCard(emphasized = true, isHero = true, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(timer.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(elapsedLabel(elapsed), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, softWrap = false)
            OutlinedButton({ setRunning(timer.id, !running) }) {
                Text(if (running) "Pausar" else "Continuar", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    var finalPage by rememberSaveable { mutableStateOf("") }
    var showHighlightDialog by remember { mutableStateOf(false) }
    if (timer.mode == "Treino") {
        var reps by rememberSaveable { mutableStateOf("10") }
        var rest by rememberSaveable { mutableStateOf("60") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(reps, { reps = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true, label = { Text("Repetições", maxLines = 1, overflow = TextOverflow.Ellipsis) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(rest, { rest = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true, label = { Text("Descanso (s)", maxLines = 1, overflow = TextOverflow.Ellipsis) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        FilledTonalButton(onClick = { recordSet(timer.id, reps.toInt(), rest.toInt()) }, enabled = running && (reps.toIntOrNull() ?: 0) > 0 && rest.toIntOrNull() != null) {
            Text("Registrar série", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (timer.restUntil > elapsed) Text("Descanso · ${(timer.restUntil - elapsed + 999) / 1000}s")
        if (timer.series.isNotEmpty()) Text(timer.series, style = MaterialTheme.typography.bodySmall)
    } else if (book != null) {
        val maxPage = book.totalPages?.takeIf { it > 0 }
        ReadingBookProgressCard(
            book = book,
            onOpenReader = { onOpenReader(book) }
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { onOpenReader(book) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Modo Leitura", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(
                onClick = { showHighlightDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FormatQuote, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Salvar destaque", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        OutlinedTextField(
            value = finalPage,
            onValueChange = { finalPage = it.filter(Char::isDigit).take(9) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Página onde parei (opcional)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingText = { Text("Página atual · ${book.currentPage}" + (maxPage?.let { " de $it" } ?: "")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(5, 10, 20).forEach { delta ->
                val target = (book.currentPage + delta).let { candidate ->
                    maxPage?.let { candidate.coerceAtMost(it) } ?: candidate
                }
                val selected = finalPage.toIntOrNull() == target && target > book.currentPage
                FilterChip(
                    selected = selected,
                    enabled = target > book.currentPage,
                    onClick = { finalPage = target.toString() },
                    label = { Text("+$delta págs (p. $target)", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
    val maxPage = book?.totalPages?.takeIf { it > 0 }
    val valid = book == null || finalPage.isEmpty() || finalPage.toIntOrNull()?.let { it >= book.currentPage && (maxPage == null || it <= maxPage) } == true
    Button({ finish(timer.id, finalPage.toIntOrNull()) }, Modifier.fillMaxWidth(), enabled = valid) {
        Text("Encerrar e salvar", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    if (showHighlightDialog && book != null) {
        QuickHighlightDialog(
            book = book,
            suggestedPage = finalPage.toIntOrNull() ?: book.currentPage.takeIf { it > 0 },
            onDismiss = { showHighlightDialog = false },
            onSave = { text, page, chapter, note ->
                onQuickHighlight(book.id, text, page, chapter, note)
                showHighlightDialog = false
            }
        )
    }
}

@Composable
private fun QuickHighlightDialog(
    book: Book,
    suggestedPage: Int?,
    onDismiss: () -> Unit,
    onSave: (text: String, page: Int?, chapter: String, note: String) -> Unit
) {
    var quoteText by rememberSaveable(book.id) { mutableStateOf("") }
    var pageText by rememberSaveable(book.id, suggestedPage) { mutableStateOf(suggestedPage?.toString().orEmpty()) }
    var chapterText by rememberSaveable(book.id) { mutableStateOf(book.currentChapter) }
    var noteText by rememberSaveable(book.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salvar destaque") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(book.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = quoteText,
                    onValueChange = { quoteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Trecho / citação *") },
                    minLines = 3,
                    maxLines = 6
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pageText,
                        onValueChange = { pageText = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Página") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = chapterText,
                        onValueChange = { chapterText = it },
                        modifier = Modifier.weight(1.6f),
                        singleLine = true,
                        label = { Text("Capítulo") }
                    )
                }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nota pessoal (opcional)") },
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        quoteText.trim(),
                        pageText.trim().toIntOrNull(),
                        chapterText.trim(),
                        noteText.trim()
                    )
                },
                enabled = quoteText.isNotBlank()
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
