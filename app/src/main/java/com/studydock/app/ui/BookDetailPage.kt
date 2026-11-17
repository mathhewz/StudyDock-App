@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.ActivityTimer
import com.studydock.app.data.Book
import com.studydock.app.data.BookHighlight
import com.studydock.app.data.BookHighlights
import com.studydock.app.data.BookReadingEngine
import com.studydock.app.data.BookReadingStats
import com.studydock.app.data.Note
import com.studydock.app.data.ReaderPreferencesStore
import com.studydock.app.data.ReadingLog
import com.studydock.app.data.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.ceil

private sealed class BookHistoryEvent(val timestamp: Long) {
    data class LogEntry(val log: ReadingLog) : BookHistoryEvent(log.loggedAt)
    data class TimerEntry(val timer: ActivityTimer) : BookHistoryEvent(timer.startedAt)
}

@Composable
fun BookDetailPage(
    book: Book,
    subjects: List<Subject>,
    notes: List<Note>,
    linkedNoteIds: Set<Long>,
    readingLogs: List<ReadingLog>,
    activityTimers: List<ActivityTimer>,
    onBack: () -> Unit,
    onReadWithTimer: (Book) -> Unit,
    onSaveBook: (Book, Set<Long>) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onAdvancePage: (Book, Int) -> Unit,
    onUpdateNoteLinks: (Long, Set<Long>) -> Unit = { _, ids -> onSaveBook(book, ids) },
    onUpdateHighlights: ((Book, List<BookHighlight>) -> Unit)? = null,
    onOpenNote: (Long) -> Unit = {},
    onCreateLinkedNote: (Book) -> Unit = {},
    onCreateFlashcardFromHighlight: ((Book, String, String) -> Unit)? = null,
    onCreateNoteFromHighlight: ((Book, String, String) -> Unit)? = null,
    onCreateReadingGoal: ((Book) -> Unit)? = null,
    onCreateReadingTask: ((Book) -> Unit)? = null,
    onOpenTutorForBook: ((Book, List<BookHighlight>) -> Unit)? = null,
    initialTab: Int = 0,
    onOpenReader: (Book) -> Unit = {},
    onChangeStatus: (Book, String) -> Unit = { target, newStatus ->
        val nextCurrent = if (newStatus == "DONE" && target.totalPages != null && target.totalPages > 0) {
            target.totalPages
        } else {
            target.currentPage
        }
        onSaveBook(
            target.copy(status = newStatus, currentPage = nextCurrent, updatedAt = System.currentTimeMillis()),
            linkedNoteIds
        )
    },
    onAttachDocument: ((Book, String, String, Int?) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable(book.id) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var showQuickEdit by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNotePicker by remember { mutableStateOf(false) }
    var wishlistQueueIds by remember(context, book.id) {
        mutableStateOf(ReaderPreferencesStore.readWishlistQueue(context))
    }
    val isWishlistQueued = book.id in wishlistQueueIds

    // Highlights state
    val highlights = remember(book.highlightsJson, book.quotes, book.updatedAt) {
        BookHighlights.parse(book)
    }
    var highlightQuery by rememberSaveable(book.id) { mutableStateOf("") }
    var editingHighlight by remember { mutableStateOf<BookHighlight?>(null) }
    var addingHighlight by remember { mutableStateOf(false) }
    var highlightForFlashcard by remember { mutableStateOf<BookHighlight?>(null) }
    var flashcardQuestion by remember { mutableStateOf("") }

    val stats = remember(book, readingLogs, activityTimers) {
        BookReadingEngine.computeBookStats(book, readingLogs, activityTimers)
    }
    val subject = remember(subjects, book.subjectId) {
        subjects.firstOrNull { it.id == book.subjectId }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                val fileName = withContext(Dispatchers.IO) {
                    resolveDocumentDisplayName(context, uri)
                }
                val detectedPages = withContext(Dispatchers.IO) {
                    detectAttachedDocumentPages(context, uri, fileName)
                }
                if (onAttachDocument != null) {
                    onAttachDocument(book, uri.toString(), fileName, detectedPages)
                } else {
                    val resolvedTotal = if (book.totalPages == null || book.totalPages <= 0) detectedPages else book.totalPages
                    onSaveBook(
                        book.copy(
                            documentUri = uri.toString(),
                            documentName = fileName,
                            totalPages = resolvedTotal,
                            lastReadAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ),
                        linkedNoteIds
                    )
                }
                Toast.makeText(context, "Documento vinculado: $fileName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchAttachDocument() {
        documentPicker.launch(
            arrayOf("application/pdf", "text/plain", "text/markdown", "text/*")
        )
    }

    fun persistHighlights(nextList: List<BookHighlight>) {
        val updatedBook = BookHighlights.withHighlights(book, nextList)
        if (onUpdateHighlights != null) {
            onUpdateHighlights(updatedBook, nextList)
        } else {
            onSaveBook(updatedBook, linkedNoteIds)
        }
    }

    fun persistBookUpdate(updated: Book) {
        onSaveBook(updated.copy(updatedAt = System.currentTimeMillis()), linkedNoteIds)
    }

    fun applyStatusChange(newStatus: String) {
        if (book.status == newStatus) return
        if (newStatus == "DONE" && book.totalPages != null && book.totalPages > 0 && book.currentPage < book.totalPages) {
            persistBookUpdate(book.copy(status = "DONE", currentPage = book.totalPages))
        } else {
            onChangeStatus(book, newStatus)
        }
    }

    fun applyPageAdvance(targetPage: Int) {
        val maxPages = book.totalPages?.takeIf { it > 0 } ?: Int.MAX_VALUE
        val cleanPage = targetPage.coerceIn(0, maxPages)
        if (cleanPage > book.currentPage && book.id > 0L) {
            onAdvancePage(book, cleanPage)
        } else if (cleanPage != book.currentPage) {
            val nextStatus = if (book.totalPages != null && book.totalPages > 0 && cleanPage >= book.totalPages) {
                "DONE"
            } else if (cleanPage > 0 && book.status == "WISHLIST") {
                "READING"
            } else {
                book.status
            }
            persistBookUpdate(book.copy(currentPage = cleanPage, status = nextStatus))
        }
    }

    val goldStar = LocalStudySemanticColors.current.goldStar

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        StudyPageHeader(
            title = "Detalhes do Livro",
            onBack = onBack,
            showGlobalSearch = true,
            actions = {
                IconButton(onClick = { onOpenReader(book) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, "Abrir Modo Leitura")
                }
                IconButton(onClick = { showQuickEdit = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Edit, "Editar livro")
                }
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.DeleteOutline, "Excluir livro")
                }
            }
        )

        // Hero Card
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = studyCardShape(isHero = true)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    BookCover(book.coverUri, Modifier.width(86.dp).height(126.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (book.author.isNotBlank()) {
                            Text(
                                book.author,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            BookStatusBadge(
                                status = book.status,
                                isQueued = isWishlistQueued
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    subject?.name ?: "Sem matéria",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            if (book.categoryTag.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        book.categoryTag,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (book.documentUri.isNotBlank() || book.documentName.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        "📄 ${book.documentName.ifBlank { "PDF/TXT/MD" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        // Clickable stars 1..5
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            (1..5).forEach { star ->
                                IconButton(
                                    onClick = {
                                        val nextRating = if (book.rating == star) 0 else star
                                        persistBookUpdate(book.copy(rating = nextRating))
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = if (star <= book.rating) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = "$star estrelas",
                                        tint = if (star <= book.rating) goldStar else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Dual CTA Row: Modo Leitura ReadEra + Timer
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onOpenReader(book) },
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Abrir Modo Leitura", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(
                        onClick = { onReadWithTimer(book) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Timer, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Timer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                // Status Switcher (Lendo / Quero ler / Lido)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = book.status == "READING",
                        onClick = { applyStatusChange("READING") },
                        shape = SegmentedButtonDefaults.itemShape(0, 3)
                    ) { Text("Lendo", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    SegmentedButton(
                        selected = book.status == "WISHLIST",
                        onClick = { applyStatusChange("WISHLIST") },
                        shape = SegmentedButtonDefaults.itemShape(1, 3)
                    ) { Text("Quero ler", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    SegmentedButton(
                        selected = book.status == "DONE",
                        onClick = { applyStatusChange("DONE") },
                        shape = SegmentedButtonDefaults.itemShape(2, 3)
                    ) { Text("Lido", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                }

                // Contextual 1-tap transition & queue actions
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (book.status) {
                        "WISHLIST" -> {
                            AssistChip(
                                onClick = { applyStatusChange("READING") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)) },
                                label = { Text("Começar a ler agora") }
                            )
                            FilterChip(
                                selected = isWishlistQueued,
                                onClick = {
                                    wishlistQueueIds = ReaderPreferencesStore.toggleWishlistQueue(context, book.id)
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isWishlistQueued) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(if (isWishlistQueued) "Na fila de próximas leituras" else "Destacar na fila")
                                }
                            )
                        }
                        "READING" -> {
                            AssistChip(
                                onClick = { applyStatusChange("DONE") },
                                leadingIcon = { Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(16.dp)) },
                                label = { Text("Concluir leitura") }
                            )
                            AssistChip(
                                onClick = { applyStatusChange("WISHLIST") },
                                leadingIcon = { Icon(Icons.Default.BookmarkBorder, null, Modifier.size(16.dp)) },
                                label = { Text("Voltar para Quero ler") }
                            )
                        }
                        "DONE" -> {
                            AssistChip(
                                onClick = { applyStatusChange("READING") },
                                leadingIcon = { Icon(Icons.Default.Replay, null, Modifier.size(16.dp)) },
                                label = { Text("Quero reler") }
                            )
                            AssistChip(
                                onClick = { applyStatusChange("WISHLIST") },
                                leadingIcon = { Icon(Icons.Default.BookmarkBorder, null, Modifier.size(16.dp)) },
                                label = { Text("Voltar para Quero ler") }
                            )
                        }
                    }
                }
            }
        }

        // 3 Internal Tabs
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 0.dp,
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Progresso", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(Icons.AutoMirrored.Filled.TrendingUp, null, Modifier.size(18.dp)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Destaques (${highlights.size})", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(Icons.Default.FormatQuote, null, Modifier.size(18.dp)) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Notas & Histórico", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(Icons.Default.History, null, Modifier.size(18.dp)) }
            )
        }

        when (selectedTab) {
            0 -> BookProgressTab(
                book = book,
                stats = stats,
                highlights = highlights,
                onAdvanceDelta = { delta -> applyPageAdvance(book.currentPage + delta) },
                onSetExactPage = { exactPage -> applyPageAdvance(exactPage) },
                onSaveChapter = { newChapter ->
                    persistBookUpdate(book.copy(currentChapter = newChapter.trim()))
                    Toast.makeText(context, "Capítulo atualizado!", Toast.LENGTH_SHORT).show()
                },
                onSaveTargetFinishAt = { newTarget ->
                    persistBookUpdate(book.copy(targetFinishAt = newTarget))
                },
                onOpenReader = { onOpenReader(book) },
                onAttachDocument = ::launchAttachDocument,
                onCreateReadingGoal = onCreateReadingGoal,
                onCreateReadingTask = onCreateReadingTask,
                onOpenTutorForBook = onOpenTutorForBook,
                onEditBook = { showQuickEdit = true }
            )

            1 -> BookHighlightsTab(
                book = book,
                highlights = highlights,
                query = highlightQuery,
                onQueryChange = { highlightQuery = it },
                onAddClick = { addingHighlight = true },
                onEditHighlight = { editingHighlight = it },
                onDeleteHighlight = { hl ->
                    persistHighlights(BookHighlights.remove(highlights, hl.id))
                    Toast.makeText(context, "Destaque removido.", Toast.LENGTH_SHORT).show()
                },
                onFlashcardFromHighlight = { hl ->
                    val topicHint = hl.chapter.ifBlank { book.title.ifBlank { "a leitura" } }
                    flashcardQuestion = "Sobre $topicHint: explique ou complete a ideia destacada"
                    highlightForFlashcard = hl
                },
                onObsidianNoteFromHighlight = { hl ->
                    val quoteWithMeta = buildString {
                        append(hl.text.trim())
                        val meta = buildList {
                            if (hl.page != null && hl.page > 0) add("p. ${hl.page}")
                            if (hl.chapter.isNotBlank()) add(hl.chapter)
                        }
                        if (meta.isNotEmpty()) append(" (${meta.joinToString(" · ")})")
                    }
                    val noteContext = hl.note.ifBlank { book.summary }
                    onCreateNoteFromHighlight?.invoke(book, quoteWithMeta, noteContext)
                }
            )

            2 -> BookNotesAndHistoryTab(
                book = book,
                notes = notes,
                linkedNoteIds = linkedNoteIds,
                readingLogs = readingLogs,
                activityTimers = activityTimers,
                onOpenNote = onOpenNote,
                onCreateLinkedNote = { onCreateLinkedNote(book) },
                onOpenNotePicker = { showNotePicker = true },
                onUnlinkNote = { noteId ->
                    onUpdateNoteLinks(book.id, linkedNoteIds - noteId)
                }
            )
        }
    }

    if (showQuickEdit) {
        BookQuickEditDialog(
            book = book,
            subjects = subjects,
            initialSubjectId = book.subjectId,
            pacePagesPerHour = stats.pagesPerHour,
            onDismiss = { showQuickEdit = false },
            onSave = { updated ->
                showQuickEdit = false
                persistBookUpdate(updated)
            },
            onDelete = {
                showQuickEdit = false
                showDeleteConfirm = true
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remover livro da estante?") },
            text = { Text("As notas e flashcards criados a partir de “${book.title}” continuam salvos.") },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    onDeleteBook(book)
                }) { Text("Remover") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") }
            }
        )
    }

    if (addingHighlight || editingHighlight != null) {
        val initial = editingHighlight
        BookHighlightEditorDialog(
            initial = initial,
            suggestedPage = book.currentPage.takeIf { it > 0 },
            suggestedChapter = book.currentChapter,
            onDismiss = {
                addingHighlight = false
                editingHighlight = null
            },
            onSave = { savedHighlight ->
                val nextList = if (initial == null) {
                    BookHighlights.add(highlights, savedHighlight)
                } else {
                    BookHighlights.update(highlights, savedHighlight)
                }
                persistHighlights(nextList)
                addingHighlight = false
                editingHighlight = null
            }
        )
    }

    highlightForFlashcard?.let { hl ->
        val backContent = remember(hl) {
            buildString {
                append(hl.text.trim())
                if (hl.page != null && hl.page > 0) append("\n\n(p. ${hl.page})")
                if (hl.note.isNotBlank()) append("\nNota: ${hl.note.trim()}")
            }
        }
        AlertDialog(
            modifier = Modifier.imePadding(),
            onDismissRequest = { highlightForFlashcard = null },
            title = { Text("Virar Flashcard (Anki)") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = flashcardQuestion,
                        onValueChange = { flashcardQuestion = it },
                        label = { Text("Frente (Pergunta)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Verso (Destaque do livro):", style = MaterialTheme.typography.labelMedium)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Text(backContent, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = flashcardQuestion.isNotBlank(),
                    onClick = {
                        onCreateFlashcardFromHighlight?.invoke(book, flashcardQuestion.trim(), backContent)
                        highlightForFlashcard = null
                    }
                ) { Text("Criar Flashcard") }
            },
            dismissButton = {
                TextButton(onClick = { highlightForFlashcard = null }) { Text("Cancelar") }
            }
        )
    }

    if (showNotePicker) {
        val currentSelection = remember(linkedNoteIds) {
            mutableStateListOf<Long>().apply { addAll(linkedNoteIds) }
        }
        AlertDialog(
            modifier = Modifier.imePadding(),
            onDismissRequest = { showNotePicker = false },
            title = { Text("Ligar notas ao livro") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (notes.isEmpty()) {
                        Text(
                            "Nenhuma nota cadastrada ainda.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    notes.forEach { note ->
                        val selected = note.id in currentSelection
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selected) currentSelection.remove(note.id) else currentSelection.add(note.id)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    if (checked) currentSelection.add(note.id) else currentSelection.remove(note.id)
                                }
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(note.title, fontWeight = FontWeight.Medium)
                                Text(
                                    note.markdown.take(90),
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateNoteLinks(book.id, currentSelection.toSet())
                    showNotePicker = false
                }) { Text("Salvar vínculos") }
            },
            dismissButton = {
                TextButton(onClick = { showNotePicker = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun BookProgressTab(
    book: Book,
    stats: BookReadingStats,
    highlights: List<BookHighlight>,
    onAdvanceDelta: (Int) -> Unit,
    onSetExactPage: (Int) -> Unit,
    onSaveChapter: (String) -> Unit,
    onSaveTargetFinishAt: (Long?) -> Unit,
    onOpenReader: () -> Unit,
    onAttachDocument: () -> Unit,
    onCreateReadingGoal: ((Book) -> Unit)?,
    onCreateReadingTask: ((Book) -> Unit)?,
    onOpenTutorForBook: ((Book, List<BookHighlight>) -> Unit)?,
    onEditBook: () -> Unit
) {
    val context = LocalContext.current
    var chapterDraft by remember(book.id, book.currentChapter) { mutableStateOf(book.currentChapter) }
    var exactPageInput by remember(book.id, book.currentPage) { mutableStateOf(book.currentPage.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ReadEra Document & Reader Mode Card
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ChromeReaderMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp).size(22.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Modo Leitura ReadEra",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (book.documentName.isNotBlank() || book.documentUri.isNotBlank()) {
                                "Arquivo vinculado: ${book.documentName.ifBlank { "Documento local" }}"
                            } else {
                                "Leia com temas Sépia/AMOLED, controle de brilho, PDF real ou TXT/Markdown paginado."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenReader,
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Abrir Leitor", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onAttachDocument,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (book.documentUri.isBlank()) "Importar PDF/TXT" else "Trocar Arquivo",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Progress Card (Circular + Linear + Quick Advance)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
                        CircularProgressIndicator(
                            progress = { stats.progressFraction },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 7.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            "${stats.progressPercent}%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (book.totalPages != null && book.totalPages > 0) {
                                "Página ${book.currentPage} de ${book.totalPages}"
                            } else {
                                "Página atual: ${book.currentPage}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (stats.remainingPages > 0) {
                                "Faltam ${stats.remainingPages} páginas" +
                                    if (stats.estimatedMinutesLeft > 0) {
                                        " (~${stats.estimatedMinutesLeft / 60}h ${stats.estimatedMinutesLeft % 60}m)"
                                    } else ""
                            } else if (book.status == "DONE") {
                                "Leitura concluída!"
                            } else {
                                "Defina o total de páginas para calcular o tempo restante"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = { stats.progressFraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                // Quick advance chips
                Text(
                    "Avançar leitura rápido",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 5, 10, 20).forEach { delta ->
                        FilledTonalButton(
                            onClick = { onAdvanceDelta(delta) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("+$delta ${if (delta == 1) "pág" else "págs"}")
                        }
                    }
                }

                // Exact page input + Chapter quick edit
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = exactPageInput,
                        onValueChange = { exactPageInput = it.filter(Char::isDigit) },
                        label = { Text("Ir para página") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = exactPageInput.toIntOrNull()?.let { it != book.currentPage } == true,
                        onClick = {
                            exactPageInput.toIntOrNull()?.let(onSetExactPage)
                        }
                    ) {
                        Text("Atualizar")
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = chapterDraft,
                        onValueChange = { chapterDraft = it },
                        label = { Text("Capítulo atual") },
                        placeholder = { Text("Ex.: Cap. 4 — Grafos") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        enabled = chapterDraft.trim() != book.currentChapter,
                        onClick = { onSaveChapter(chapterDraft) }
                    ) {
                        Text("Salvar cap.")
                    }
                }
            }
        }

        // Reading Stats Grid
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Métricas de Leitura",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        title = "Ritmo do livro",
                        value = "~${stats.pagesPerHour} pág/h",
                        subtitle = if (stats.isBookSpecificPace) "Medido neste livro" else "Média da estante",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        title = "Últimos 7 dias",
                        value = "${stats.pagesLast7Days} págs",
                        subtitle = "Hoje: +${stats.pagesToday} págs",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val etaText = if (stats.estimatedMinutesLeft > 0) {
                        "${stats.estimatedMinutesLeft / 60}h ${stats.estimatedMinutesLeft % 60}m"
                    } else "—"
                    StatTile(
                        title = "Tempo restante",
                        value = etaText,
                        subtitle = "Lido: ${stats.totalMinutes} min",
                        modifier = Modifier.weight(1f)
                    )
                    val targetDailyText = stats.requiredPagesPerDay?.let {
                        "${ceil(it).toInt().coerceAtLeast(1)} pág/dia"
                    } ?: "Sem prazo"
                    val targetSub = book.targetFinishAt?.let {
                        val dateStr = StudyDateFormats.formatEpoch(it, StudyDateFormats.DATE_DD_MM_PT)
                        "Até $dateStr (${stats.daysRemainingToTarget ?: 0}d)"
                    } ?: "Toque abaixo p/ definir"
                    StatTile(
                        title = "Meta diária",
                        value = targetDailyText,
                        subtitle = targetSub,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val zone = java.time.ZoneId.systemDefault()
                            val d0 = java.time.Instant.ofEpochMilli(
                                book.targetFinishAt ?: (System.currentTimeMillis() + 14 * 86_400_000L)
                            ).atZone(zone).toLocalDate()
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    onSaveTargetFinishAt(
                                        java.time.LocalDate.of(y, m + 1, d).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
                                    )
                                },
                                d0.year,
                                d0.monthValue - 1,
                                d0.dayOfMonth
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Event, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (book.targetFinishAt == null) "Definir prazo de conclusão" else "Alterar prazo")
                    }
                    if (book.targetFinishAt != null) {
                        TextButton(onClick = { onSaveTargetFinishAt(null) }) {
                            Text("Limpar prazo")
                        }
                    }
                }
            }
        }

        // Ecosystem Actions Card
        StudyCard(emphasized = true) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ações de Ecossistema",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Conecte este livro às suas Metas, Tarefas ou ao Tutor IA com 1 toque.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onCreateReadingGoal != null) {
                        FilledTonalButton(onClick = { onCreateReadingGoal(book) }) {
                            Icon(Icons.Default.Flag, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Criar Meta de Páginas")
                        }
                    }
                    if (onCreateReadingTask != null) {
                        FilledTonalButton(onClick = { onCreateReadingTask(book) }) {
                            Icon(Icons.Default.Checklist, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Criar Tarefa de Leitura")
                        }
                    }
                    if (onOpenTutorForBook != null) {
                        Button(onClick = { onOpenTutorForBook(book, highlights) }) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Abrir no Tutor IA")
                        }
                    }
                }
            }
        }

        // Summary Card
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Resumo & Fichamento",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onEditBook) {
                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Editar")
                    }
                }
                Text(
                    book.summary.ifBlank { "Nenhum resumo geral registrado ainda. Toque em Editar para adicionar seu fichamento." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (book.summary.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BookHighlightsTab(
    book: Book,
    highlights: List<BookHighlight>,
    query: String,
    onQueryChange: (String) -> Unit,
    onAddClick: () -> Unit,
    onEditHighlight: (BookHighlight) -> Unit,
    onDeleteHighlight: (BookHighlight) -> Unit,
    onFlashcardFromHighlight: (BookHighlight) -> Unit,
    onObsidianNoteFromHighlight: (BookHighlight) -> Unit
) {
    val context = LocalContext.current
    val filtered = remember(highlights, query) {
        val q = query.trim()
        if (q.isEmpty()) highlights
        else highlights.filter { hl ->
            hl.text.contains(q, ignoreCase = true) ||
                hl.chapter.contains(q, ignoreCase = true) ||
                hl.note.contains(q, ignoreCase = true) ||
                (hl.page?.toString()?.contains(q) == true)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StudyLocalSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = "Buscar nos destaques..."
            )
            FilledTonalButton(
                onClick = onAddClick,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Novo destaque")
            }
        }

        if (highlights.isEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.FormatQuote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    Text("Nenhum destaque salvo ainda", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Registre citações com página, capítulo e nota pessoal, e transforme em Flashcard ou Nota Obsidian com 1 toque.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onAddClick, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Default.Add, null)
                        Text(" Adicionar primeiro destaque")
                    }
                }
            }
        } else if (filtered.isEmpty()) {
            Text(
                "Nenhum destaque corresponde à busca.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        filtered.forEach { hl ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hl.page != null && hl.page > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        "Pág. ${hl.page}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (hl.chapter.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        hl.chapter,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { onEditHighlight(hl) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Edit, "Editar destaque", Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDeleteHighlight(hl) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.DeleteOutline, "Excluir destaque", Modifier.size(18.dp))
                        }
                    }

                    Text(
                        "“${hl.text}”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic
                    )

                    if (hl.note.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "💡 ${hl.note}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { onFlashcardFromHighlight(hl) },
                            leadingIcon = { Icon(Icons.Default.Style, null, Modifier.size(16.dp)) },
                            label = { Text("Virar Flashcard (Anki)") }
                        )
                        AssistChip(
                            onClick = { onObsidianNoteFromHighlight(hl) },
                            leadingIcon = { Icon(Icons.Default.EditNote, null, Modifier.size(16.dp)) },
                            label = { Text("Virar Nota Obsidian") }
                        )
                        AssistChip(
                            onClick = { context.openAnkiCard(book.title, hl.text) },
                            label = { Text("AnkiDroid externo") }
                        )
                        AssistChip(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Destaque", hl.text))
                                Toast.makeText(context, "Destaque copiado!", Toast.LENGTH_SHORT).show()
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp)) },
                            label = { Text("Copiar") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookHighlightEditorDialog(
    initial: BookHighlight?,
    suggestedPage: Int?,
    suggestedChapter: String,
    onDismiss: () -> Unit,
    onSave: (BookHighlight) -> Unit
) {
    var text by remember(initial?.id) { mutableStateOf(initial?.text.orEmpty()) }
    var pageInput by remember(initial?.id) {
        mutableStateOf((initial?.page ?: suggestedPage)?.toString().orEmpty())
    }
    var chapter by remember(initial?.id) {
        mutableStateOf(initial?.chapter ?: suggestedChapter)
    }
    var note by remember(initial?.id) { mutableStateOf(initial?.note.orEmpty()) }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) "Novo destaque" else "Editar destaque",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Citação / Trecho destacado") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { pageInput = it.filter(Char::isDigit) },
                        label = { Text("Página") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.4f)
                    )
                    OutlinedTextField(
                        value = chapter,
                        onValueChange = { chapter = it },
                        label = { Text("Capítulo") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f)
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota pessoal / Reflexão (opcional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    val saved = (initial ?: BookHighlight(text = text.trim())).copy(
                        text = text.trim(),
                        page = pageInput.toIntOrNull()?.takeIf { it > 0 },
                        chapter = chapter.trim(),
                        note = note.trim()
                    )
                    onSave(saved)
                }
            ) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun BookNotesAndHistoryTab(
    book: Book,
    notes: List<Note>,
    linkedNoteIds: Set<Long>,
    readingLogs: List<ReadingLog>,
    activityTimers: List<ActivityTimer>,
    onOpenNote: (Long) -> Unit,
    onCreateLinkedNote: () -> Unit,
    onOpenNotePicker: () -> Unit,
    onUnlinkNote: (Long) -> Unit
) {
    val linkedNotes = remember(notes, linkedNoteIds) {
        notes.filter { it.id in linkedNoteIds }
    }
    val historyEvents = remember(book, readingLogs, activityTimers) {
        val logs = BookReadingEngine.logsForBook(book, readingLogs).map { BookHistoryEvent.LogEntry(it) }
        val timers = BookReadingEngine.timersForBook(book, activityTimers).map { BookHistoryEvent.TimerEntry(it) }
        (logs + timers).sortedByDescending { it.timestamp }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Linked Notes Section
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Notas do Segundo Cérebro (${linkedNotes.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Notas Markdown conectadas diretamente a este livro.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenNotePicker) {
                        Icon(Icons.Default.Link, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Ligar")
                    }
                }

                if (linkedNotes.isEmpty()) {
                    Text(
                        "Nenhuma nota ligada a este livro ainda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    linkedNotes.forEach { note ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenNote(note.id) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(note.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        note.markdown.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onUnlinkNote(note.id) }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Close, "Desligar nota", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onCreateLinkedNote,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Criar nova nota ligada a este livro")
                }
            }
        }

        // Reading History Timeline Section
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Histórico de Sessões & Leituras (${historyEvents.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (historyEvents.isEmpty()) {
                    Text(
                        "Nenhuma sessão de leitura ou avanço de páginas registrado para este livro ainda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    historyEvents.forEach { event ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            when (event) {
                                is BookHistoryEvent.LogEntry -> {
                                    val log = event.log
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.MenuBook,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                            Text(
                                                "+${log.pages} ${if (log.pages == 1) "página lida" else "páginas lidas"}" +
                                                    if (log.durationMinutes > 0) " · ${log.durationMinutes} min" else "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                StudyDateFormats.formatEpoch(log.loggedAt, StudyDateFormats.DATE_TIME_AS_PT),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                is BookHistoryEvent.TimerEntry -> {
                                    val timer = event.timer
                                    val mins = (timer.duration(System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Timer,
                                            null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                            Text(
                                                "Sessão de leitura (${mins} min)" +
                                                    if (timer.endedAt == null) " · Em andamento" else "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                StudyDateFormats.formatEpoch(timer.startedAt, StudyDateFormats.DATE_TIME_AS_PT),
                                                style = MaterialTheme.typography.labelSmall,
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
}
