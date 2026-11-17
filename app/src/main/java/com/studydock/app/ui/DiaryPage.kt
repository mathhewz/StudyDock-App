@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

private val diaryChecklistLineRegex = Regex("^(\\s*[-*]\\s+\\[)([ xX])(\\]\\s*)(.*)$")
private val diaryNumberedLineRegex = Regex("^\\d+\\. .*")

@Composable
fun DiaryPage(
    entries: List<JournalEntry>,
    sessions: List<StudySession>,
    habits: List<Habit>,
    completions: List<HabitCompletion>,
    onSave: (JournalEntry) -> Unit,
    tasks: List<StudyTask> = emptyList(),
    flashcards: List<Flashcard> = emptyList(),
    cardReviews: List<CardReview> = emptyList(),
    questionLogs: List<QuestionLog> = emptyList(),
    books: List<Book> = emptyList(),
    readingLogs: List<ReadingLog> = emptyList(),
    workouts: List<WorkoutLog> = emptyList(),
    meals: List<MealLog> = emptyList(),
    subjects: List<Subject> = emptyList(),
    notes: List<Note> = emptyList(),
    onDeleteEntry: (JournalEntry) -> Unit = {},
    onOpenWikilink: (String) -> Unit = {},
    onPromoteToNote: (JournalEntry) -> Unit = {},
    onCreateTaskFromDiary: (String, Long?) -> Unit = { _, _ -> },
    onAskTutorFromDiary: (JournalEntry) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val todayKey = remember(java.time.LocalDate.now()) { DiaryAnalytics.dayKeyFromEpoch(System.currentTimeMillis()) }
    var selectedDay by rememberSaveable { mutableLongStateOf(todayKey) }

    val entriesByDay = remember(entries) { entries.associateBy { it.dayKey } }
    val entry = entriesByDay[selectedDay]

    var title by remember(selectedDay) { mutableStateOf(entry?.title.orEmpty()) }
    var bodyField by remember(selectedDay) {
        val initialBody = entry?.body.orEmpty()
        mutableStateOf(TextFieldValue(initialBody, TextRange(initialBody.length)))
    }
    var mood by remember(selectedDay) { mutableStateOf(entry?.mood?.ifBlank { "🙂" } ?: "🙂") }
    var energy by remember(selectedDay) { mutableIntStateOf(entry?.energy?.coerceIn(1, 5) ?: 3) }
    var focus by remember(selectedDay) { mutableIntStateOf(entry?.focus?.coerceIn(1, 5) ?: 3) }
    var tags by remember(selectedDay) { mutableStateOf(entry?.tags.orEmpty()) }
    var promptUsed by remember(selectedDay) { mutableStateOf(entry?.promptUsed.orEmpty()) }
    var dirty by remember(selectedDay) { mutableStateOf(false) }
    var saveLabel by remember(selectedDay) {
        mutableStateOf(
            if (entry == null) "Ainda sem registro"
            else "Salvo ${StudyDateFormats.formatEpoch(entry.updatedAt, StudyDateFormats.TIME_HH_MM_PT)}"
        )
    }

    var previewMode by rememberSaveable { mutableStateOf(false) }
    var customTagInput by remember { mutableStateOf("") }
    var showInsights by rememberSaveable { mutableStateOf(false) }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var historyMoodFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var historyTagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var entryToDelete by remember { mutableStateOf<JournalEntry?>(null) }
    var showCreateTaskDialog by remember { mutableStateOf(false) }
    var showLinkPicker by remember { mutableStateOf(false) }

    val latestEntriesByDay by rememberUpdatedState(entriesByDay)
    val latestOnSave by rememberUpdatedState(onSave)

    fun buildEntrySnapshot(day: Long = selectedDay): JournalEntry {
        val currentStored = latestEntriesByDay[day]
        return JournalEntry(
            dayKey = day,
            title = title.trim(),
            body = bodyField.text,
            mood = mood.ifBlank { "🙂" },
            updatedAt = System.currentTimeMillis(),
            energy = energy.coerceIn(1, 5),
            focus = focus.coerceIn(1, 5),
            tags = DiaryAnalytics.formatTags(DiaryAnalytics.parseTags(tags)),
            promptUsed = promptUsed,
            linkedNoteId = currentStored?.linkedNoteId
        )
    }

    fun flushDirtyIfNeeded(day: Long = selectedDay): JournalEntry {
        val snapshot = buildEntrySnapshot(day)
        if (dirty) {
            latestOnSave(snapshot)
            dirty = false
        }
        return snapshot
    }

    DisposableEffect(selectedDay) {
        val day = selectedDay
        onDispose {
            if (dirty) {
                latestOnSave(buildEntrySnapshot(day))
            }
        }
    }

    LaunchedEffect(selectedDay, entry?.updatedAt, entry?.linkedNoteId) {
        if (!dirty) {
            title = entry?.title.orEmpty()
            val storedBody = entry?.body.orEmpty()
            if (bodyField.text != storedBody) {
                bodyField = TextFieldValue(storedBody, TextRange(storedBody.length))
            }
            mood = entry?.mood?.ifBlank { "🙂" } ?: "🙂"
            energy = entry?.energy?.coerceIn(1, 5) ?: 3
            focus = entry?.focus?.coerceIn(1, 5) ?: 3
            tags = entry?.tags.orEmpty()
            promptUsed = entry?.promptUsed.orEmpty()
            saveLabel = if (entry == null) {
                "Ainda sem registro"
            } else {
                "Salvo ${StudyDateFormats.formatEpoch(entry.updatedAt, StudyDateFormats.TIME_HH_MM_PT)}"
            }
        }
    }

    LaunchedEffect(selectedDay, title, bodyField.text, mood, energy, focus, tags, promptUsed, dirty) {
        if (dirty) {
            saveLabel = "Editando…"
            delay(650)
            saveLabel = "Salvando…"
            latestOnSave(buildEntrySnapshot(selectedDay))
            dirty = false
        }
    }

    fun changeDay(next: Long) {
        if (next == selectedDay) return
        flushDirtyIfNeeded(selectedDay)
        selectedDay = next
    }

    fun insertBodySnippet(snippet: String) {
        val currentText = bodyField.text
        val start = bodyField.selection.start.coerceIn(0, currentText.length)
        val end = bodyField.selection.end.coerceIn(0, currentText.length)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        val nextText = currentText.replaceRange(lo, hi, snippet)
        val nextCursor = lo + snippet.length
        bodyField = TextFieldValue(nextText, TextRange(nextCursor))
        dirty = true
    }

    fun wrapBodySelection(prefix: String, suffix: String, placeholder: String) {
        val currentText = bodyField.text
        val start = bodyField.selection.start.coerceIn(0, currentText.length)
        val end = bodyField.selection.end.coerceIn(0, currentText.length)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        if (lo == hi) {
            val inserted = "$prefix$placeholder$suffix"
            val nextText = currentText.replaceRange(lo, hi, inserted)
            bodyField = TextFieldValue(
                nextText,
                TextRange(lo + prefix.length, lo + prefix.length + placeholder.length)
            )
        } else {
            val selected = currentText.substring(lo, hi)
            val nextText = currentText.replaceRange(lo, hi, "$prefix$selected$suffix")
            bodyField = TextFieldValue(nextText, TextRange(lo + prefix.length, hi + prefix.length))
        }
        dirty = true
    }

    fun prefixCurrentLine(prefix: String) {
        val currentText = bodyField.text
        val cursor = bodyField.selection.start.coerceIn(0, currentText.length)
        val lineStart = currentText.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0))
            .let { if (cursor == 0 || it < 0) 0 else it + 1 }
        val nextText = currentText.replaceRange(lineStart, lineStart, prefix)
        bodyField = TextFieldValue(nextText, TextRange(cursor + prefix.length))
        dirty = true
    }

    val selectedMillis = remember(selectedDay) { diaryMillis(selectedDay) }
    val daySummary = remember(
        selectedDay, sessions, tasks, cardReviews, questionLogs,
        completions, readingLogs, workouts, meals, subjects
    ) {
        DiaryAnalytics.summarizeDay(
            dayKey = selectedDay,
            sessions = sessions,
            tasks = tasks,
            cardReviews = cardReviews,
            questionLogs = questionLogs,
            habitCompletions = completions,
            readingLogs = readingLogs,
            workouts = workouts,
            meals = meals,
            subjects = subjects
        )
    }

    val dayBooksTouched = remember(books, selectedDay) {
        books.filter { it.currentPage > 0 && DiaryAnalytics.dayKeyFromEpoch(it.updatedAt) == selectedDay }
    }
    val dayCardSubjects = remember(cardReviews, flashcards, subjects, selectedDay) {
        val subjById = subjects.associateBy { it.id }
        val cardSubjById = flashcards.associate { it.id to it.subjectId }
        cardReviews
            .filter { DiaryAnalytics.dayKeyFromEpoch(it.reviewedAt) == selectedDay }
            .mapNotNull { r -> (r.subjectAtReview ?: cardSubjById[r.cardId])?.let { subjById[it]?.name } }
            .distinct()
    }

    val selectedTags = remember(tags) { DiaryAnalytics.parseTags(tags) }
    val allAvailableCheckinTags = remember(selectedTags) {
        (DiaryAnalytics.tagPresets + selectedTags).distinct()
    }

    val defaultPrompt = remember(selectedDay) {
        val prompts = DiaryAnalytics.reflectionPrompts
        val idx = ((selectedDay % prompts.size).toInt()).let { if (it < 0) -it else it }
        prompts[idx]
    }
    val activePrompt = promptUsed.ifBlank { defaultPrompt }

    val stripDays = remember(selectedDay, todayKey) {
        val windowEnd = if (selectedDay <= diaryShiftDay(todayKey, -6) || selectedDay > todayKey) {
            selectedDay
        } else {
            todayKey
        }
        (-6..0).map { offset -> diaryShiftDay(windowEnd, offset) }
    }

    val recordedDaysCount = remember(entries) {
        entries.count { it.title.isNotBlank() || it.body.isNotBlank() || it.tags.isNotBlank() }
    }

    val allHistoryTags = remember(entries) {
        entries.flatMap { DiaryAnalytics.parseTags(it.tags) }.distinct().sorted()
    }
    val moodCorrelations = remember(entries, sessions, questionLogs, completions) {
        DiaryAnalytics.computeMoodCorrelations(entries, sessions, questionLogs, completions)
    }
    val filteredHistory = remember(entries, historyQuery, historyMoodFilter, historyTagFilter) {
        DiaryAnalytics.filterEntries(
            entries = entries,
            query = historyQuery,
            moodFilter = historyMoodFilter,
            tagFilter = historyTagFilter
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "diary_editor_section", contentType = "editor") {
            Column(Modifier.fillMaxWidth()) {
                StudyPageHeader(
            title = "Diário Reflexivo",
            subtitle = "Check-in, Markdown com wikilinks e contexto 360° do seu dia.",
            showGlobalSearch = true,
            actions = {
                if (entry != null) {
                    IconButton(
                        onClick = { entryToDelete = entry },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            "Excluir registro do dia",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )

        // ---------- Seletor de Dia Aprimorado ----------
        Card(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { changeDay(diaryShiftDay(selectedDay, -1)) }) {
                        Icon(Icons.Default.ChevronLeft, "Dia anterior")
                    }
                    Text(
                        StudyDateFormats.formatEpoch(selectedMillis, StudyDateFormats.FULL_WEEKDAY_DAY_MONTH_PT)
                            .replaceFirstChar { it.uppercase() },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val initialDate = DiaryAnalytics.localDateFromDayKey(selectedDay)
                                DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        changeDay(year * 10000L + (month + 1) * 100L + day)
                                    },
                                    initialDate.year,
                                    initialDate.monthValue - 1,
                                    initialDate.dayOfMonth
                                ).show()
                            }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(
                        onClick = { changeDay(diaryShiftDay(selectedDay, 1)) },
                        enabled = selectedDay < todayKey
                    ) {
                        Icon(Icons.Default.ChevronRight, "Próximo dia")
                    }
                    IconButton(
                        onClick = { changeDay(todayKey) },
                        enabled = selectedDay != todayKey
                    ) {
                        Icon(Icons.Default.CalendarMonth, "Ir para hoje", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    stripDays.forEach { dayKey ->
                        val localDate = DiaryAnalytics.localDateFromDayKey(dayKey)
                        val isSelected = dayKey == selectedDay
                        val recorded = entriesByDay[dayKey]
                        val hasContent = recorded != null
                        val isFuture = dayKey > todayKey
                        Surface(
                            onClick = { if (!isFuture) changeDay(dayKey) },
                            enabled = !isFuture,
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                hasContent -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                else -> Color.Transparent
                            },
                            border = if (dayKey == todayKey && !isSelected) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                            } else null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    StudyDateFormats.formatLocalDate(localDate, StudyDateFormats.WEEKDAY_EE_PT)
                                        .take(3)
                                        .replace(".", "")
                                        .uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    localDate.dayOfMonth.toString(),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Box(Modifier.defaultMinSize(minHeight = 20.dp), contentAlignment = Alignment.Center) {
                                    if (recorded != null) {
                                        Text(recorded.mood, style = MaterialTheme.typography.labelSmall)
                                    } else if (dayKey == todayKey) {
                                        Box(
                                            Modifier.size(5.dp).clip(CircleShape).background(
                                                if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val yesterdayKey = diaryShiftDay(todayKey, -1)
                    FilterChip(
                        selected = selectedDay == todayKey,
                        onClick = { changeDay(todayKey) },
                        label = { Text("Hoje") }
                    )
                    FilterChip(
                        selected = selectedDay == yesterdayKey,
                        onClick = { changeDay(yesterdayKey) },
                        label = { Text("Ontem") }
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$recordedDaysCount ${if (recordedDaysCount == 1) "dia" else "dias"} com registro",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- Card de Check-in Estruturado ----------
        Card(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val moodLabel = DiaryAnalytics.moodPresets.firstOrNull { it.first == mood }?.second ?: "Personalizado"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Check-in do dia", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Humor: $moodLabel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }

                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DiaryAnalytics.moodPresets.forEach { (face, label) ->
                        Surface(
                            onClick = { mood = face; dirty = true },
                            shape = CircleShape,
                            color = if (mood == face) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (mood == face) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(face, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }

                ScaleSelectorRow(
                    label = "⚡ Energia",
                    value = energy,
                    onChange = { energy = it; dirty = true }
                )
                ScaleSelectorRow(
                    label = "🎯 Foco",
                    value = focus,
                    onChange = { focus = it; dirty = true }
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tags do dia", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        allAvailableCheckinTags.forEach { tag ->
                            val selected = tag in selectedTags
                            FilterChip(
                                selected = selected,
                                onClick = { tags = DiaryAnalytics.toggleTag(tags, tag); dirty = true },
                                label = { Text("#$tag") }
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = customTagInput,
                            onValueChange = { customTagInput = it },
                            placeholder = { Text("Nova tag personalizada") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val normalized = DiaryAnalytics.normalizeTag(customTagInput)
                                if (normalized.isNotEmpty() && normalized !in selectedTags) {
                                    tags = DiaryAnalytics.formatTags(selectedTags + normalized)
                                    dirty = true
                                }
                                customTagInput = ""
                            }),
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalIconButton(
                            onClick = {
                                val normalized = DiaryAnalytics.normalizeTag(customTagInput)
                                if (normalized.isNotEmpty() && normalized !in selectedTags) {
                                    tags = DiaryAnalytics.formatTags(selectedTags + normalized)
                                    dirty = true
                                }
                                customTagInput = ""
                            },
                            enabled = DiaryAnalytics.normalizeTag(customTagInput).isNotEmpty()
                        ) {
                            Icon(Icons.Default.Add, "Adicionar tag")
                        }
                    }
                }

                Surface(
                    shape = studyCardShape(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lightbulb, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Reflexão guiada",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            activePrompt,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val prompts = DiaryAnalytics.reflectionPrompts
                                    val currentIdx = prompts.indexOf(activePrompt)
                                    val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % prompts.size
                                    promptUsed = prompts[nextIdx]
                                    dirty = true
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Trocar pergunta", style = MaterialTheme.typography.labelMedium)
                            }
                            FilledTonalButton(
                                onClick = {
                                    promptUsed = activePrompt
                                    val block = if (bodyField.text.isBlank()) "### $activePrompt\n" else "\n\n### $activePrompt\n"
                                    val nextText = bodyField.text + block
                                    bodyField = TextFieldValue(nextText, TextRange(nextText.length))
                                    previewMode = false
                                    dirty = true
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.EditNote, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Inserir no texto", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        // ---------- Editor + Preview Markdown ----------
        OutlinedTextField(
            value = title,
            onValueChange = { title = it; dirty = true },
            label = { Text("Título do dia · opcional") },
            placeholder = { Text("Uma frase para lembrar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = !previewMode,
                    onClick = { previewMode = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Editar", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                SegmentedButton(
                    selected = previewMode,
                    onClick = { previewMode = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Preview", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            }
            Text(
                saveLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!previewMode) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FormatActionChip("H2") { prefixCurrentLine("## ") }
                FormatActionChip("**B**") { wrapBodySelection("**", "**", "texto") }
                FormatActionChip("- [ ]") { prefixCurrentLine("- [ ] ") }
                FormatActionChip("[[]]") {
                    if (bodyField.selection.start != bodyField.selection.end) {
                        wrapBodySelection("[[", "]]", "nota")
                    } else if (notes.isNotEmpty()) {
                        showLinkPicker = true
                    } else {
                        val currentText = bodyField.text
                        val pos = bodyField.selection.start.coerceIn(0, currentText.length)
                        val nextText = currentText.replaceRange(pos, pos, "[[]]")
                        bodyField = TextFieldValue(nextText, TextRange(pos + 2))
                        dirty = true
                    }
                }
                FormatActionChip("Lista") { prefixCurrentLine("- ") }
                FormatActionChip("Citação") { prefixCurrentLine("> ") }
            }

            OutlinedTextField(
                value = bodyField,
                onValueChange = { bodyField = it; dirty = true },
                label = { Text("Seu registro em Markdown") },
                placeholder = { Text("O que aconteceu? Conecte ideias com [[nome da nota]] ou listas - [ ]") },
                minLines = 9,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        } else {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (bodyField.text.isBlank()) {
                        Text(
                            "Nada escrito ainda para pré-visualizar.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        DiaryMarkdownPreview(
                            markdown = bodyField.text,
                            onToggleChecklistLine = { lineIndex ->
                                val updated = toggleDiaryChecklistLine(bodyField.text, lineIndex)
                                if (updated != bodyField.text) {
                                    val cursor = bodyField.selection.start.coerceIn(0, updated.length)
                                    bodyField = TextFieldValue(updated, TextRange(cursor))
                                    dirty = true
                                }
                            },
                            onOpenWikilink = onOpenWikilink
                        )
                    }
                }
            }
        }

        // ---------- Card "Seu dia no StudyDock" (Contexto 360°) ----------
        Text(
            "Contexto 360°",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 18.dp, bottom = 7.dp)
        )
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Insights, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Seu dia no StudyDock", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }

                if (!daySummary.hasAnyActivity && dayBooksTouched.isEmpty()) {
                    Text(
                        "Sem atividades registradas nos módulos neste dia.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (daySummary.studySeconds > 0L) {
                            ActivityContextChip(
                                "📚 ${diaryDuration(daySummary.studySeconds)} (${daySummary.sessionCount}x)"
                            )
                        }
                        daySummary.subjectNames.forEach { subjName ->
                            val color = subjects.firstOrNull { it.name == subjName }?.color
                            ActivityContextChip(subjName, subjectColor = color)
                        }
                        if (daySummary.tasksCompletedCount > 0) {
                            ActivityContextChip("✅ ${daySummary.tasksCompletedCount} tarefas")
                        }
                        if (daySummary.flashcardReviewsCount > 0) {
                            val subjSuffix = if (dayCardSubjects.isNotEmpty()) " · ${dayCardSubjects.take(2).joinToString()}" else ""
                            ActivityContextChip("🃏 ${daySummary.flashcardReviewsCount} cards (${daySummary.flashcardAccuracyPercent}%)$subjSuffix")
                        }
                        if (daySummary.questionsDone > 0) {
                            ActivityContextChip("❓ ${daySummary.questionsDone} questões (${daySummary.questionAccuracyPercent}%)")
                        }
                        if (daySummary.habitsCompletedCount > 0) {
                            ActivityContextChip("🌿 ${daySummary.habitsCompletedCount} hábitos")
                        }
                        if (daySummary.pagesRead > 0) {
                            ActivityContextChip("📖 ${daySummary.pagesRead} págs")
                        } else if (dayBooksTouched.isNotEmpty()) {
                            ActivityContextChip("📖 ${dayBooksTouched.first().title}")
                        }
                        if (daySummary.workoutsCount > 0) {
                            ActivityContextChip("🏋️ ${daySummary.workoutsCount} treino (${daySummary.workoutMinutes}m)")
                        }
                        if (daySummary.mealsCount > 0) {
                            ActivityContextChip("🍽️ ${daySummary.mealsCount} refeições (${daySummary.totalCalories} kcal)")
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val markdownSummary = DiaryAnalytics.formatSummaryMarkdown(daySummary)
                        val nextText = if (bodyField.text.isBlank()) {
                            markdownSummary
                        } else {
                            bodyField.text.trimEnd() + "\n\n" + markdownSummary
                        }
                        bodyField = TextFieldValue(nextText, TextRange(nextText.length))
                        dirty = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PostAdd, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Inserir resumo no diário")
                }
            }
        }

        // ---------- Ponte com Ecossistema ----------
        Card(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Conectar ao ecossistema", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val snapshot = flushDirtyIfNeeded(selectedDay)
                            onPromoteToNote(snapshot)
                        }
                    ) {
                        Icon(Icons.Default.Hub, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (entry?.linkedNoteId != null) "Abrir no Segundo Cérebro"
                            else "Promover ao Segundo Cérebro"
                        )
                    }
                    OutlinedButton(onClick = { showCreateTaskDialog = true }) {
                        Icon(Icons.Default.AddTask, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Criar Tarefa no Todo")
                    }
                    OutlinedButton(
                        onClick = {
                            val snapshot = flushDirtyIfNeeded(selectedDay)
                            onAskTutorFromDiary(snapshot)
                        }
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Conversar com Tutor IA")
                    }
                }
            }
        }
            }
        }

        // ---------- Insights (Card Retrátil Humor × Produtividade) ----------
        item(key = "diary_insights_section", contentType = "insights") {
        Card(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { showInsights = !showInsights },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BarChart, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Humor × Produtividade", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Padrões de estudo, energia e foco por humor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        if (showInsights) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (showInsights) "Recolher insights" else "Expandir insights"
                    )
                }

                AnimatedVisibility(visible = showInsights) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val recentMoodDays = remember(entriesByDay, todayKey) {
                            (-13..0).map { offset -> diaryShiftDay(todayKey, offset) }
                        }
                        Text("Últimos 14 dias", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            recentMoodDays.forEach { dKey ->
                                val item = entriesByDay[dKey]
                                val localDate = DiaryAnalytics.localDateFromDayKey(dKey)
                                Surface(
                                    onClick = { changeDay(dKey) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (dKey == selectedDay) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                ) {
                                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(item?.mood ?: "·", style = MaterialTheme.typography.titleSmall)
                                        Text("${localDate.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        if (moodCorrelations.isEmpty()) {
                            Text(
                                "Registre alguns dias para ver como seu humor se relaciona com estudo, energia e hábitos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            moodCorrelations.forEach { stat ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stat.mood, style = MaterialTheme.typography.titleLarge)
                                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                            Text(
                                                "${stat.moodLabel} · ${stat.entriesCount} ${if (stat.entriesCount == 1) "dia" else "dias"}",
                                                fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            val eFmt = String.format(Locale("pt", "BR"), "%.1f", stat.averageEnergy)
                                            val fFmt = String.format(Locale("pt", "BR"), "%.1f", stat.averageFocus)
                                            val hFmt = String.format(Locale("pt", "BR"), "%.1f", stat.averageHabits)
                                            Text(
                                                "Média: ${stat.averageStudyMinutes} min/dia · ⚡$eFmt · 🎯$fFmt · ${stat.averageQuestions}q · $hFmt hábitos",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        // ---------- Histórico Completo (SEM limite .take(8)) ----------
        item(key = "diary_history_controls", contentType = "historyControls") {
            Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Histórico completo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "${filteredHistory.size} ${if (filteredHistory.size == 1) "entrada" else "entradas"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        StudyLocalSearchField(
            query = historyQuery,
            onQueryChange = { historyQuery = it },
            placeholder = "Buscar texto, tag, pergunta ou data",
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = historyMoodFilter == null,
                onClick = { historyMoodFilter = null },
                label = { Text("Todos os humores") }
            )
            DiaryAnalytics.moodPresets.forEach { (face, label) ->
                FilterChip(
                    selected = historyMoodFilter == face,
                    onClick = { historyMoodFilter = if (historyMoodFilter == face) null else face },
                    label = { Text("$face $label") }
                )
            }
        }

        if (allHistoryTags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = historyTagFilter == null,
                    onClick = { historyTagFilter = null },
                    label = { Text("Todas as tags") }
                )
                allHistoryTags.forEach { tag ->
                    FilterChip(
                        selected = historyTagFilter == tag,
                        onClick = { historyTagFilter = if (historyTagFilter == tag) null else tag },
                        label = { Text("#$tag") }
                    )
                }
            }
        }
            }
        }

        if (filteredHistory.isEmpty()) {
            item(key = "diary_history_empty", contentType = "empty") {
                Text(
                    if (entries.isEmpty()) "Seus registros anteriores aparecerão aqui." else "Nenhuma entrada combina com os filtros.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(
                items = filteredHistory,
                key = { it.dayKey },
                contentType = { "historyEntry" }
            ) { old ->
                val oldTags = DiaryAnalytics.parseTags(old.tags)
                Card(
                    onClick = {
                        changeDay(old.dayKey)
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (old.dayKey == selectedDay) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(old.mood, style = MaterialTheme.typography.titleLarge)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    old.title.ifBlank { diaryDateLabel(old.dayKey) },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (old.linkedNoteId != null) {
                                    Icon(Icons.Default.Hub, "Vinculada ao Cérebro", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                }
                            }
                            Text(
                                "${if (old.title.isNotBlank()) "${diaryDateLabel(old.dayKey)} · " else ""}⚡${old.energy}/5 · 🎯${old.focus}/5",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val snippet = old.body.replace('\n', ' ').trim()
                            if (snippet.isNotEmpty()) {
                                Text(
                                    snippet.take(120),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            if (oldTags.isNotEmpty()) {
                                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    oldTags.take(4).forEach { tag ->
                                        Text("#$tag", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { entryToDelete = old }) {
                            Icon(Icons.Default.DeleteOutline, "Excluir entrada", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showLinkPicker) {
        DiaryLinkPickerDialog(
            notes = notes,
            onDismiss = { showLinkPicker = false },
            onPickNote = { targetNote ->
                insertBodySnippet("[[${NoteReferences.escape(targetNote.title)}]]")
                showLinkPicker = false
            },
            onCustomTitle = { custom ->
                insertBodySnippet("[[${NoteReferences.escape(custom)}]]")
                showLinkPicker = false
            }
        )
    }

    if (showCreateTaskDialog) {
        CreateTaskFromDiaryDialog(
            defaultTitle = title.trim().ifBlank { "Ação do diário (${diaryDateLabel(selectedDay)})" },
            subjects = subjects,
            onDismiss = { showCreateTaskDialog = false },
            onCreate = { taskTitle, subjectId ->
                onCreateTaskFromDiary(taskTitle, subjectId)
                showCreateTaskDialog = false
            }
        )
    }

    entryToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text("Excluir registro do diário?") },
            text = { Text("O registro de ${diaryDateLabel(target.dayKey)} será removido permanentemente.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteEntry(target)
                        if (target.dayKey == selectedDay) {
                            dirty = false
                            title = ""
                            bodyField = TextFieldValue("")
                            mood = "🙂"
                            energy = 3
                            focus = 3
                            tags = ""
                            promptUsed = ""
                            saveLabel = "Ainda sem registro"
                        }
                        entryToDelete = null
                    }
                ) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { entryToDelete = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ScaleSelectorRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            (1..5).forEach { level ->
                val selected = value == level
                Surface(
                    onClick = { onChange(level) },
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            level.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatActionChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ActivityContextChip(text: String, subjectColor: Long? = null) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (subjectColor != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(subjectColor.toInt())))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DiaryMarkdownPreview(
    markdown: String,
    onToggleChecklistLine: (Int) -> Unit,
    onOpenWikilink: (String) -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val lines = markdown.lines()
    var inFence = false

    lines.forEachIndexed { index, rawLine ->
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
            inFence = !inFence
            return@forEachIndexed
        }
        if (inFence) {
            Text(line, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            return@forEachIndexed
        }

        val checklist = diaryChecklistLineRegex.matchEntire(line)
        when {
            checklist != null -> {
                val checked = checklist.groupValues[2].equals("x", ignoreCase = true)
                val itemText = checklist.groupValues[4]
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggleChecklistLine(index) }
                    )
                    DiaryInlineText(
                        text = itemText.ifBlank { "Item" },
                        linkColor = linkColor,
                        codeBg = codeBg,
                        onOpenWikilink = onOpenWikilink,
                        strike = checked,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            line.startsWith("### ") -> DiaryInlineText(
                text = line.drop(4),
                linkColor = linkColor,
                codeBg = codeBg,
                onOpenWikilink = onOpenWikilink,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
            )
            line.startsWith("## ") -> DiaryInlineText(
                text = line.drop(3),
                linkColor = linkColor,
                codeBg = codeBg,
                onOpenWikilink = onOpenWikilink,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
            )
            line.startsWith("# ") -> DiaryInlineText(
                text = line.drop(2),
                linkColor = linkColor,
                codeBg = codeBg,
                onOpenWikilink = onOpenWikilink,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
            line.startsWith("> ") -> Row(
                Modifier.padding(vertical = 3.dp).height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(linkColor))
                DiaryInlineText(
                    text = line.drop(2),
                    linkColor = linkColor,
                    codeBg = codeBg,
                    onOpenWikilink = onOpenWikilink,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            line.startsWith("- ") || line.startsWith("* ") -> DiaryInlineText(
                text = "• ${line.drop(2)}",
                linkColor = linkColor,
                codeBg = codeBg,
                onOpenWikilink = onOpenWikilink,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp)
            )
            diaryNumberedLineRegex.matches(line) -> DiaryInlineText(
                text = line,
                linkColor = linkColor,
                codeBg = codeBg,
                onOpenWikilink = onOpenWikilink,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp)
            )
            line == "---" -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
            line.isBlank() -> Spacer(Modifier.height(6.dp))
            else -> DiaryInlineText(
                text = line,
                linkColor = linkColor,
                codeBg = codeBg,
                onOpenWikilink = onOpenWikilink,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun DiaryInlineText(
    text: String,
    linkColor: Color,
    codeBg: Color,
    onOpenWikilink: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    strike: Boolean = false
) {
    val annotated = remember(text, linkColor, codeBg) {
        inlineAnnotated(text, linkColor, codeBg, linkify = true)
    }
    val resolved = style.copy(
        color = if (strike) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textDecoration = if (strike) TextDecoration.LineThrough else null
    )
    ClickableText(
        text = annotated,
        style = resolved,
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations("LINK", offset, offset).firstOrNull()?.let { ann ->
                val target = NoteReferences.parse("[[${ann.item}]]").firstOrNull()?.target
                    ?: ann.item.substringBefore('|').trim()
                if (target.isNotEmpty()) onOpenWikilink(target)
            }
        }
    )
}

@Composable
private fun DiaryLinkPickerDialog(
    notes: List<Note>,
    onDismiss: () -> Unit,
    onPickNote: (Note) -> Unit,
    onCustomTitle: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(notes, query) {
        notes.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }.take(25)
    }
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Inserir [[wikilink]]") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar ou digitar título da nota") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    if (query.isNotBlank() && notes.none { it.title.equals(query.trim(), ignoreCase = true) }) {
                        TextButton(onClick = { onCustomTitle(query.trim()) }) {
                            Text("Criar link [[${query.trim()}]]")
                        }
                    }
                    matches.forEach { note ->
                        TextButton(onClick = { onPickNote(note) }, modifier = Modifier.fillMaxWidth()) {
                            Text(note.title, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun CreateTaskFromDiaryDialog(
    defaultTitle: String,
    subjects: List<Subject>,
    onDismiss: () -> Unit,
    onCreate: (String, Long?) -> Unit
) {
    var taskTitle by remember { mutableStateOf(defaultTitle) }
    var selectedSubjectId by remember { mutableStateOf<Long?>(null) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Criar tarefa a partir do diário") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = taskTitle,
                    onValueChange = { taskTitle = it },
                    label = { Text("Título da tarefa") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (subjects.isNotEmpty()) {
                    val subjName = selectedSubjectId?.let { id -> subjects.firstOrNull { it.id == id }?.name } ?: "Geral (sem matéria)"
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(
                            value = subjName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Matéria · opcional") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("Geral (sem matéria)") }, onClick = { selectedSubjectId = null; expanded = false })
                            subjects.forEach { subj ->
                                DropdownMenuItem(
                                    text = { Text(subj.name) },
                                    onClick = { selectedSubjectId = subj.id; expanded = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (taskTitle.isNotBlank()) onCreate(taskTitle.trim(), selectedSubjectId) },
                enabled = taskTitle.isNotBlank()
            ) { Text("Criar tarefa") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun toggleDiaryChecklistLine(markdown: String, lineIndex: Int): String {
    val lines = markdown.split('\n').toMutableList()
    if (lineIndex !in lines.indices) return markdown
    val raw = lines[lineIndex]
    val hasCr = raw.endsWith('\r')
    val clean = raw.trimEnd('\r')
    val match = diaryChecklistLineRegex.matchEntire(clean) ?: return markdown
    val mark = if (match.groupValues[2].equals("x", ignoreCase = true)) " " else "x"
    val updated = "${match.groupValues[1]}$mark${match.groupValues[3]}${match.groupValues[4]}"
    lines[lineIndex] = if (hasCr) "$updated\r" else updated
    return lines.joinToString("\n")
}

private fun diaryMillis(key: Long): Long =
    DiaryAnalytics.localDateFromDayKey(key)
        .atTime(12, 0)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

private fun diaryShiftDay(key: Long, amount: Int): Long =
    DiaryAnalytics.localDateFromDayKey(key).plusDays(amount.toLong()).let {
        it.year * 10000L + it.monthValue * 100L + it.dayOfMonth
    }

private fun diaryDateLabel(key: Long): String =
    StudyDateFormats.formatEpoch(diaryMillis(key), StudyDateFormats.DATE_D_MMM_YYYY_PT)

private fun diaryDuration(seconds: Long): String {
    val mins = (seconds + 59) / 60
    return if (mins >= 60) "${mins / 60}h ${mins % 60}min" else "$mins min"
}
