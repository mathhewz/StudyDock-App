@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.ActivityTimer
import com.studydock.app.data.Book
import com.studydock.app.data.BookHighlights
import com.studydock.app.data.BookNoteLink
import com.studydock.app.data.BookReadingEngine
import com.studydock.app.data.BookReadingStats
import com.studydock.app.data.Note
import com.studydock.app.data.ReaderPreferencesStore
import com.studydock.app.data.ReadingLog
import com.studydock.app.data.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val BOOK_DEADLINE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale("pt", "BR"))

private enum class BookSortOption(val label: String) {
    RECENT("Recentes"),
    PROGRESS("% Progresso"),
    REMAINING("Mais páginas restantes"),
    RATING("Avaliação")
}

@Composable
fun BooksPage(
    books: List<Book>,
    notes: List<Note>,
    links: List<BookNoteLink>,
    subjects: List<Subject>,
    initialSubjectId: Long?,
    onSave: (Book, Set<Long>, (Long) -> Unit) -> Unit,
    onRead: (Long) -> Unit,
    onDelete: (Book) -> Unit,
    onOpenNote: (Long) -> Unit,
    onCreateNote: (Long, String) -> Unit,
    readingLogs: List<ReadingLog> = emptyList(),
    activityTimers: List<ActivityTimer> = emptyList(),
    onCreateFlashcardFromQuote: ((Book, String, String) -> Unit)? = null,
    onCreateNoteFromQuote: ((Book, String, String) -> Unit)? = null,
    onOpenBookDetail: ((Long) -> Unit)? = null,
    onAdvancePages: ((Book, Int) -> Unit)? = null,
    onChangeStatus: (Book, String) -> Unit = { book, status ->
        val nextPage = if (status == "DONE" && book.totalPages != null && book.totalPages > 0) {
            book.totalPages
        } else {
            book.currentPage
        }
        onSave(
            book.copy(
                status = status,
                currentPage = nextPage,
                updatedAt = System.currentTimeMillis()
            ),
            links.asSequence().filter { it.bookId == book.id }.map { it.noteId }.toSet()
        ) {}
    },
    onOpenReader: (Book) -> Unit = {}
) {
    var editingBook by remember { mutableStateOf<Book?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Book?>(null) }
    var filter by rememberSaveable { mutableStateOf("Todos") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSubjectFilter by rememberSaveable(initialSubjectId) { mutableStateOf<Long?>(initialSubjectId) }
    var sortOption by rememberSaveable { mutableStateOf(BookSortOption.RECENT) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var subjectMenuExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("bookshelf", 0) }
    var columns by remember { mutableIntStateOf(prefs.getInt("columns", 1).coerceIn(1, 3)) }
    var wishlistQueueIds by remember(context) {
        mutableStateOf(ReaderPreferencesStore.readWishlistQueue(context))
    }

    val effectiveSubjectId = initialSubjectId ?: selectedSubjectFilter
    val shelf = remember(books, effectiveSubjectId) {
        when {
            initialSubjectId != null -> books.filter { it.subjectId == initialSubjectId }
            selectedSubjectFilter == -1L -> books.filter { it.subjectId == null }
            selectedSubjectFilter != null -> books.filter { it.subjectId == selectedSubjectFilter }
            else -> books
        }
    }

    val shelfSummary = remember(shelf, readingLogs, activityTimers) {
        BookReadingEngine.computeShelfSummary(shelf, readingLogs, activityTimers)
    }

    val statsByBookId = remember(shelf, readingLogs, activityTimers) {
        BookReadingEngine.computeAllBookStats(shelf, readingLogs, activityTimers)
    }

    val highlightsByBookId = remember(shelf) {
        shelf.associate { book -> book.id to BookHighlights.parse(book) }
    }

    val visible = remember(shelf, filter, searchQuery, sortOption, statsByBookId, highlightsByBookId, wishlistQueueIds) {
        val q = searchQuery.trim()
        val filtered = shelf.filter { book ->
            val statusMatches = when (filter) {
                "Lendo" -> book.status == "READING"
                "Quero ler" -> book.status == "WISHLIST"
                "Lido", "Lidos" -> book.status == "DONE"
                else -> true
            }
            if (!statusMatches) return@filter false
            if (q.isEmpty()) return@filter true
            val highlights = highlightsByBookId[book.id].orEmpty()
            book.title.contains(q, ignoreCase = true) ||
                book.author.contains(q, ignoreCase = true) ||
                book.categoryTag.contains(q, ignoreCase = true) ||
                book.currentChapter.contains(q, ignoreCase = true) ||
                book.documentName.contains(q, ignoreCase = true) ||
                book.summary.contains(q, ignoreCase = true) ||
                book.quotes.contains(q, ignoreCase = true) ||
                highlights.any { hl ->
                    hl.text.contains(q, ignoreCase = true) ||
                        hl.chapter.contains(q, ignoreCase = true) ||
                        hl.note.contains(q, ignoreCase = true)
                }
        }
        val baseSorted = when (sortOption) {
            BookSortOption.RECENT -> filtered.sortedByDescending { maxOf(it.updatedAt, it.lastReadAt) }
            BookSortOption.PROGRESS -> filtered.sortedWith(
                compareByDescending<Book> { statsByBookId[it.id]?.progressFraction ?: 0f }
                    .thenByDescending { it.updatedAt }
            )
            BookSortOption.REMAINING -> filtered.sortedWith(
                compareByDescending<Book> { book ->
                    val rem = statsByBookId[book.id]?.remainingPages ?: (book.totalPages ?: 0)
                    if (rem > 0) rem else -1
                }.thenByDescending { it.updatedAt }
            )
            BookSortOption.RATING -> filtered.sortedWith(
                compareByDescending<Book> { it.rating }
                    .thenByDescending { it.updatedAt }
            )
        }
        // In "Quero ler", keep books marked in the priority queue ("Na fila") at the top
        if (filter == "Quero ler") {
            baseSorted.sortedByDescending { it.id in wishlistQueueIds }
        } else {
            baseSorted
        }
    }

    fun handleAdvancePages(book: Book, delta: Int) {
        val maxPages = book.totalPages?.takeIf { it > 0 } ?: Int.MAX_VALUE
        val nextCurr = (book.currentPage + delta).coerceIn(0, maxPages)
        if (nextCurr <= book.currentPage) return
        if (onAdvancePages != null && book.id > 0L) {
            onAdvancePages(book, nextCurr)
        } else {
            val noteIds = links.filter { it.bookId == book.id }.map { it.noteId }.toSet()
            val nextStatus = if (book.totalPages != null && book.totalPages > 0 && nextCurr >= book.totalPages) {
                "DONE"
            } else {
                "READING"
            }
            onSave(
                book.copy(
                    currentPage = nextCurr,
                    status = nextStatus,
                    updatedAt = System.currentTimeMillis()
                ),
                noteIds
            ) {}
        }
    }

    fun handleStatusTransition(book: Book, nextStatus: String) {
        val noteIds = links.filter { it.bookId == book.id }.map { it.noteId }.toSet()
        if (nextStatus == "DONE" && book.totalPages != null && book.totalPages > 0 && book.currentPage < book.totalPages) {
            onSave(
                book.copy(
                    status = "DONE",
                    currentPage = book.totalPages,
                    updatedAt = System.currentTimeMillis()
                ),
                noteIds
            ) {}
        } else {
            onChangeStatus(book, nextStatus)
        }
        val statusMsg = when (nextStatus) {
            "READING" -> "“${book.title}” movido para Lendo!"
            "DONE" -> "Parabéns! “${book.title}” marcado como Lido!"
            "WISHLIST" -> "“${book.title}” movido para Quero ler."
            else -> "Status atualizado."
        }
        Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
    }

    fun handleQuickRating(book: Book, star: Int) {
        val nextRating = if (book.rating == star) 0 else star.coerceIn(1, 5)
        val noteIds = links.filter { it.bookId == book.id }.map { it.noteId }.toSet()
        onSave(
            book.copy(rating = nextRating, updatedAt = System.currentTimeMillis()),
            noteIds
        ) {}
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        val pageTitle = subjects.find { it.id == initialSubjectId }?.let { "Livros · ${it.name}" } ?: "Livros & Leituras"
        StudyPageHeader(
            title = pageTitle,
            subtitle = "Estantes Quero ler, Lendo e Lido + Modo Leitura ReadEra.",
            showGlobalSearch = true,
            actions = {
                FilledTonalIconButton(
                    onClick = { creating = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Add, "Adicionar livro")
                }
            }
        )

        StudyCard(emphasized = true) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BookMetric("Lendo", shelfSummary.readingCount.toString())
                    BookMetric("Quero ler", shelfSummary.wishlistCount.toString())
                    BookMetric("Lidos", shelfSummary.doneCount.toString())
                    BookMetric("Páginas lidas", shelfSummary.totalPagesRead.toString())
                    BookMetric("Ritmo médio", "~${shelfSummary.averagePagesPerHour} pág/h")
                    BookMetric("Streak", "${shelfSummary.readingStreakDays}d")
                    BookMetric("Destaques", shelfSummary.totalHighlights.toString())
                }
                if (shelfSummary.pagesToday > 0 || shelfSummary.pagesThisWeek > 0) {
                    Text(
                        buildList {
                            if (shelfSummary.pagesToday > 0) add("Hoje: +${shelfSummary.pagesToday} págs")
                            if (shelfSummary.pagesThisWeek > 0) add("Semana: ${shelfSummary.pagesThisWeek} págs")
                            if (wishlistQueueIds.isNotEmpty()) add("Na fila: ${shelf.count { it.status == "WISHLIST" && it.id in wishlistQueueIds }}")
                        }.joinToString("  •  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                    )
                }
            }
        }

        // Shelf Tabs with Clear Counters (Lendo, Quero ler, Lido, Todos)
        val shelfTabs = remember(shelfSummary) {
            listOf(
                Triple("Todos", "Todos", shelfSummary.totalBooks),
                Triple("Lendo", "Lendo", shelfSummary.readingCount),
                Triple("Quero ler", "Quero ler", shelfSummary.wishlistCount),
                Triple("Lido", "Lido", shelfSummary.doneCount)
            )
        }
        val normalizedFilter = if (filter == "Lidos") "Lido" else filter

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            shelfTabs.forEach { (key, label, count) ->
                val selected = normalizedFilter == key
                FilterChip(
                    selected = selected,
                    onClick = { filter = key },
                    leadingIcon = {
                        Icon(
                            imageVector = when (key) {
                                "Lendo" -> Icons.Default.AutoStories
                                "Quero ler" -> Icons.Default.BookmarkBorder
                                "Lido" -> Icons.Default.CheckCircleOutline
                                else -> Icons.AutoMirrored.Filled.LibraryBooks
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            "$label ($count)",
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }
        }

        StudyLocalSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Buscar por título, autor, PDF/arquivo, tag ou destaque...",
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (initialSubjectId == null && subjects.isNotEmpty()) {
                Box {
                    val subjectLabel = when (selectedSubjectFilter) {
                        null -> "Matéria: Todas"
                        -1L -> "Sem matéria"
                        else -> subjects.firstOrNull { it.id == selectedSubjectFilter }?.name ?: "Matéria"
                    }
                    FilterChip(
                        selected = selectedSubjectFilter != null,
                        onClick = { subjectMenuExpanded = true },
                        leadingIcon = { Icon(Icons.Default.Book, null, Modifier.size(16.dp)) },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp)) },
                        label = { Text(subjectLabel, maxLines = 1) }
                    )
                    DropdownMenu(
                        expanded = subjectMenuExpanded,
                        onDismissRequest = { subjectMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Todas as matérias") },
                            onClick = { selectedSubjectFilter = null; subjectMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Sem matéria") },
                            onClick = { selectedSubjectFilter = -1L; subjectMenuExpanded = false }
                        )
                        subjects.forEach { subj ->
                            DropdownMenuItem(
                                text = { Text(subj.name) },
                                onClick = { selectedSubjectFilter = subj.id; subjectMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            Box {
                AssistChip(
                    onClick = { sortMenuExpanded = true },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp)) },
                    label = { Text(sortOption.label) }
                )
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    BookSortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            trailingIcon = if (sortOption == option) {
                                { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                            } else null,
                            onClick = {
                                sortOption = option
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.width(4.dp))
            listOf("Lista", "2 col", "3 col").forEachIndexed { index, label ->
                FilterChip(
                    selected = columns == index + 1,
                    onClick = {
                        columns = index + 1
                        prefs.edit().putInt("columns", columns).apply()
                    },
                    label = { Text(label) }
                )
            }
        }

        val goldStar = LocalStudySemanticColors.current.goldStar

        if (shelf.isEmpty()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = studyCardShape(isHero = true)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.AutoStories,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp)
                        )
                        Text(
                            "Monte sua biblioteca de estudo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 9.dp)
                        )
                        Text(
                            "Organize suas estantes (Quero ler, Lendo e Lido), vincule arquivos PDF/TXT/MD no Modo Leitura ReadEra e transforme citações em flashcards ou notas com 1 toque.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Button(onClick = { creating = true }, modifier = Modifier.padding(top = 12.dp)) {
                            Icon(Icons.Default.Add, null)
                            Text(" Adicionar primeiro livro")
                        }
                    }
                }
            }
        } else if (visible.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 14.dp, bottom = 16.dp)
            ) {
                Text(
                    "Nenhum livro encontrado nesta estante ou busca.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (columns > 1) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val effectiveCols = if (columns == 3 && maxWidth < 360.dp) 2 else columns
                LazyVerticalGrid(
                    GridCells.Fixed(effectiveCols),
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(visible, key = { it.id }) { book ->
                        val stats = statsByBookId[book.id]
                            ?: BookReadingEngine.computeBookStats(book, readingLogs, activityTimers)
                        val isQueued = book.id in wishlistQueueIds
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = studyCardShape(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (onOpenBookDetail != null && book.id > 0L) {
                                        onOpenBookDetail(book.id)
                                    } else {
                                        editingBook = book
                                    }
                                }
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box {
                                    BookCover(book.coverUri, Modifier.fillMaxWidth().aspectRatio(.68f))
                                    BookStatusBadge(
                                        status = book.status,
                                        isQueued = isQueued,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(6.dp)
                                    )
                                }
                                Text(
                                    book.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                if (book.author.isNotBlank()) {
                                    Text(
                                        book.author,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (book.rating > 0) {
                                    Text(
                                        "★".repeat(book.rating.coerceIn(1, 5)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = goldStar
                                    )
                                }
                                if (book.totalPages != null && book.totalPages > 0 && book.status != "WISHLIST") {
                                    LinearProgressIndicator(
                                        progress = { stats.progressFraction },
                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Text(
                                        "${stats.progressPercent}% · ${book.currentPage}/${book.totalPages}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else if (book.status == "WISHLIST" && book.totalPages != null && book.totalPages > 0) {
                                    Text(
                                        "${book.totalPages} págs",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                when (book.status) {
                                    "WISHLIST" -> {
                                        FilledTonalButton(
                                            onClick = { handleStatusTransition(book, "READING") },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "Começar a ler",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    "DONE" -> {
                                        OutlinedButton(
                                            onClick = { handleStatusTransition(book, "READING") },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp)
                                        ) {
                                            Icon(Icons.Default.Replay, null, Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "Quero reler",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    else -> {
                                        FilledTonalButton(
                                            onClick = { onOpenReader(book) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null, Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "Modo Leitura",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.id }) { book ->
                    val noteIds = links.filter { it.bookId == book.id }.map { it.noteId }.toSet()
                    val stats = statsByBookId[book.id]
                        ?: BookReadingEngine.computeBookStats(book, readingLogs, activityTimers)
                    val isQueued = book.id in wishlistQueueIds
                    BookShelfCard(
                        book = book,
                        stats = stats,
                        noteCount = noteIds.size,
                        subjectName = subjects.firstOrNull { it.id == book.subjectId }?.name,
                        isWishlistQueued = isQueued,
                        onToggleWishlistQueue = {
                            wishlistQueueIds = ReaderPreferencesStore.toggleWishlistQueue(context, book.id)
                        },
                        onOpenReader = { onOpenReader(book) },
                        onRead = { onRead(book.id) },
                        onChangeStatus = { newStatus -> handleStatusTransition(book, newStatus) },
                        onRateBook = { star -> handleQuickRating(book, star) },
                        onClick = {
                            if (onOpenBookDetail != null && book.id > 0L) {
                                onOpenBookDetail(book.id)
                            } else {
                                editingBook = book
                            }
                        },
                        onQuickEdit = { editingBook = book },
                        onMore = { deleteTarget = book },
                        onQuickAdvancePages = { delta -> handleAdvancePages(book, delta) }
                    )
                }
            }
        }
    }

    if (creating || editingBook != null) {
        val targetBook = editingBook
        val currentNoteIds = remember(targetBook?.id, links) {
            links.filter { it.bookId == targetBook?.id }.map { it.noteId }.toSet()
        }
        BookQuickEditDialog(
            book = targetBook,
            subjects = subjects,
            initialSubjectId = initialSubjectId,
            pacePagesPerHour = targetBook?.let { statsByBookId[it.id]?.pagesPerHour } ?: shelfSummary.averagePagesPerHour,
            onDismiss = {
                creating = false
                editingBook = null
            },
            onSave = { updatedBook ->
                onSave(updatedBook, currentNoteIds) { savedId ->
                    creating = false
                    editingBook = null
                    if (targetBook == null && savedId > 0L && onOpenBookDetail != null) {
                        onOpenBookDetail(savedId)
                    }
                }
            },
            onDelete = if (targetBook != null) {
                { bookToDelete ->
                    creating = false
                    editingBook = null
                    deleteTarget = bookToDelete
                }
            } else null
        )
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remover livro da estante?") },
            text = { Text("As notas e flashcards criados a partir dele continuam salvos.") },
            confirmButton = {
                Button(onClick = {
                    deleteTarget?.let(onDelete)
                    deleteTarget = null
                    creating = false
                    editingBook = null
                }) { Text("Remover") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
internal fun BookStatusBadge(
    status: String,
    modifier: Modifier = Modifier,
    isQueued: Boolean = false
) {
    val semantic = LocalStudySemanticColors.current
    val (bg, fg, label) = when (status) {
        "DONE" -> Triple(
            semantic.success.copy(alpha = 0.16f),
            semantic.success,
            "✓ Lido"
        )
        "WISHLIST" -> Triple(
            if (isQueued) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            if (isQueued) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            if (isQueued) "⭐ Na fila · Quero ler" else "Quero ler"
        )
        else -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "📖 Lendo"
        )
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        modifier = modifier
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun BookMetric(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 6.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1
        )
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun BookShelfCard(
    book: Book,
    stats: BookReadingStats,
    noteCount: Int,
    subjectName: String?,
    isWishlistQueued: Boolean,
    onToggleWishlistQueue: () -> Unit,
    onOpenReader: () -> Unit,
    onRead: () -> Unit,
    onChangeStatus: (String) -> Unit,
    onRateBook: (Int) -> Unit,
    onClick: () -> Unit,
    onQuickEdit: () -> Unit,
    onMore: () -> Unit,
    onQuickAdvancePages: (Int) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val goldStar = LocalStudySemanticColors.current.goldStar

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (book.status == "WISHLIST" && isWishlistQueued) {
            BorderStroke(1.2.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f))
        } else null,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                BookCover(book.coverUri, Modifier.width(70.dp).height(102.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (book.rating > 0) {
                            Text(
                                "★".repeat(book.rating.coerceIn(1, 5)),
                                color = goldStar,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (book.author.isNotBlank()) {
                        Text(
                            book.author,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BookStatusBadge(
                            status = book.status,
                            isQueued = isWishlistQueued
                        )
                        if (!subjectName.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    subjectName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (book.categoryTag.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    book.categoryTag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (book.documentUri.isNotBlank() || book.documentName.isNotBlank()) {
                            val docBadge = book.documentName.ifBlank { "Documento" }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    "📄 $docBadge",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (book.currentChapter.isNotBlank()) {
                        Text(
                            "Cap.: ${book.currentChapter}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    when (book.status) {
                        "WISHLIST" -> {
                            val totalPages = book.totalPages?.takeIf { it > 0 }
                            val estInfo = if (totalPages != null) {
                                val estMinutes = ((totalPages * 60.0) / stats.pagesPerHour.coerceAtLeast(1)).roundToInt()
                                val h = estMinutes / 60
                                val m = estMinutes % 60
                                val timeStr = if (h > 0) "${h}h ${m}m" else "${m}min"
                                "$totalPages páginas · Estimativa: ~$timeStr (~${stats.pagesPerHour} pág/h)"
                            } else {
                                "Defina o total de páginas para estimar o tempo de leitura"
                            }
                            Text(
                                estInfo,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        "DONE" -> {
                            val totalPages = book.totalPages?.takeIf { it > 0 } ?: book.currentPage.takeIf { it > 0 }
                            val pagesText = totalPages?.let { "$it págs lidas" } ?: "Leitura concluída"
                            val timeText = if (stats.totalMinutes > 0) {
                                val h = stats.totalMinutes / 60
                                val m = stats.totalMinutes % 60
                                if (h > 0) " · Tempo: ${h}h ${m}m" else " · Tempo: ${m}min"
                            } else ""
                            Text(
                                "$pagesText$timeText · ~${stats.pagesPerHour} pág/h",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        else -> {
                            val pageLabel = if (book.totalPages != null && book.totalPages > 0) {
                                "Pág. ${book.currentPage}/${book.totalPages} (${stats.progressPercent}%)"
                            } else {
                                "Página ${book.currentPage}"
                            }
                            val etaLabel = if (stats.estimatedMinutesLeft > 0) {
                                val h = stats.estimatedMinutesLeft / 60
                                val m = stats.estimatedMinutesLeft % 60
                                " · falta ~" + (if (h > 0) "${h}h ${m}m" else "${m}min")
                            } else ""
                            Text(
                                "$pageLabel · ~${stats.pagesPerHour} pág/h$etaLabel",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            if (book.totalPages != null && book.totalPages > 0) {
                                LinearProgressIndicator(
                                    progress = { stats.progressFraction },
                                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }

                    FlowRow(
                        Modifier.padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (book.status == "READING") {
                            val dailyPages = stats.requiredPagesPerDay?.let { ceil(it).toInt().coerceAtLeast(1) }
                                ?: (stats.pagesLast7Days / 7.0).roundToInt().takeIf { it > 0 }
                            if (dailyPages != null) {
                                Text(
                                    if (stats.requiredPagesPerDay != null) "🎯 Meta: $dailyPages pág/dia" else "📈 Ritmo: ~$dailyPages pág/dia",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (stats.pagesLast7Days > 0) {
                            Text(
                                "+${stats.pagesLast7Days} pág/7d",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (noteCount > 0) {
                            Text(
                                "📝 $noteCount ${if (noteCount == 1) "nota" else "notas"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (stats.highlightCount > 0) {
                            Text(
                                "❝ ${stats.highlightCount} ${if (stats.highlightCount == 1) "destaque" else "destaques"}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "Opções do livro")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Abrir Modo Leitura (ReadEra)") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null) },
                            onClick = { menuOpen = false; onOpenReader() }
                        )
                        DropdownMenuItem(
                            text = { Text("Abrir detalhes") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                            onClick = { menuOpen = false; onClick() }
                        )
                        DropdownMenuItem(
                            text = { Text("Timer de leitura") },
                            leadingIcon = { Icon(Icons.Default.Timer, null) },
                            onClick = { menuOpen = false; onRead() }
                        )
                        if (book.status != "READING") {
                            DropdownMenuItem(
                                text = { Text(if (book.status == "DONE") "Quero reler (Lendo)" else "Começar a ler") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                onClick = { menuOpen = false; onChangeStatus("READING") }
                            )
                        }
                        if (book.status != "WISHLIST") {
                            DropdownMenuItem(
                                text = { Text("Mover para Quero ler") },
                                leadingIcon = { Icon(Icons.Default.BookmarkBorder, null) },
                                onClick = { menuOpen = false; onChangeStatus("WISHLIST") }
                            )
                        }
                        if (book.status != "DONE") {
                            DropdownMenuItem(
                                text = { Text("Marcar como Lido") },
                                leadingIcon = { Icon(Icons.Default.CheckCircleOutline, null) },
                                onClick = { menuOpen = false; onChangeStatus("DONE") }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Edição rápida") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; onQuickEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Remover") },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                            onClick = { menuOpen = false; onMore() }
                        )
                    }
                }
            }

            // State-specific 1-tap action bar
            when (book.status) {
                "WISHLIST" -> {
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { onChangeStatus("READING") },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 38.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Começar a ler", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilterChip(
                            selected = isWishlistQueued,
                            onClick = onToggleWishlistQueue,
                            leadingIcon = {
                                Icon(
                                    if (isWishlistQueued) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(if (isWishlistQueued) "Na fila prioritária" else "Destacar na fila")
                            }
                        )
                        FilledTonalButton(
                            onClick = onOpenReader,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 38.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Modo Leitura", maxLines = 1)
                        }
                    }
                }

                "DONE" -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                "Sua avaliação:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            (1..5).forEach { star ->
                                IconButton(
                                    onClick = { onRateBook(star) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (star <= book.rating) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = "$star estrelas",
                                        tint = if (star <= book.rating) goldStar else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { onChangeStatus("READING") },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.heightIn(min = 36.dp)
                            ) {
                                Icon(Icons.Default.Replay, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Quero reler", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = onOpenReader,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.heightIn(min = 36.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Abrir no Leitor", maxLines = 1)
                            }
                            TextButton(
                                onClick = { onChangeStatus("WISHLIST") },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 36.dp)
                            ) {
                                Text("Voltar p/ Quero ler", maxLines = 1)
                            }
                        }
                    }
                }

                else -> {
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = onOpenReader,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 38.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Abrir Modo Leitura", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(
                            onClick = onRead,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 38.dp)
                        ) {
                            Icon(Icons.Default.Timer, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Timer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        AssistChip(
                            onClick = { onQuickAdvancePages(1) },
                            label = { Text("+1 pág", maxLines = 1) }
                        )
                        AssistChip(
                            onClick = { onQuickAdvancePages(5) },
                            label = { Text("+5 págs", maxLines = 1) }
                        )
                        AssistChip(
                            onClick = { onQuickAdvancePages(10) },
                            label = { Text("+10 págs", maxLines = 1) }
                        )
                        AssistChip(
                            onClick = { onChangeStatus("DONE") },
                            leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(15.dp)) },
                            label = { Text("Concluir", maxLines = 1) }
                        )
                    }
                }
            }
        }
    }
}

private val BookCoverBitmapCache = object : android.util.LruCache<String, ImageBitmap>(16 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        ((value.width.toLong() * value.height.toLong() * 4L) / 1024L).coerceAtLeast(1L).toInt()
}

@Composable
fun BookCover(uri: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cleanUri = uri?.trim().orEmpty()
    var bitmap by remember(cleanUri) {
        mutableStateOf(if (cleanUri.isEmpty()) null else BookCoverBitmapCache.get(cleanUri))
    }
    LaunchedEffect(cleanUri) {
        if (cleanUri.isEmpty()) {
            bitmap = null
            return@LaunchedEffect
        }
        BookCoverBitmapCache.get(cleanUri)?.let { cached ->
            bitmap = cached
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val parsed = Uri.parse(cleanUri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 400 || bounds.outHeight / sample > 600) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
            }.getOrNull()
        }
        if (decoded != null) {
            BookCoverBitmapCache.put(cleanUri, decoded)
        }
        bitmap = decoded
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = "Capa do livro",
            modifier = modifier.clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoStories, "Sem capa", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
fun BookQuickEditDialog(
    book: Book?,
    subjects: List<Subject>,
    initialSubjectId: Long? = book?.subjectId,
    pacePagesPerHour: Int = BookReadingEngine.DEFAULT_PAGES_PER_HOUR,
    onDismiss: () -> Unit,
    onSave: (Book) -> Unit,
    onDelete: ((Book) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember(book?.id) { mutableStateOf(book?.title.orEmpty()) }
    var author by remember(book?.id) { mutableStateOf(book?.author.orEmpty()) }
    var coverUri by remember(book?.id) { mutableStateOf(book?.coverUri) }
    var documentUri by remember(book?.id) { mutableStateOf(book?.documentUri.orEmpty()) }
    var documentName by remember(book?.id) { mutableStateOf(book?.documentName.orEmpty()) }
    var categoryTag by remember(book?.id) { mutableStateOf(book?.categoryTag.orEmpty()) }
    var currentChapter by remember(book?.id) { mutableStateOf(book?.currentChapter.orEmpty()) }
    var summary by remember(book?.id) { mutableStateOf(book?.summary.orEmpty()) }
    var currentPage by remember(book?.id) { mutableStateOf(book?.currentPage?.toString().orEmpty()) }
    var totalPages by remember(book?.id) { mutableStateOf(book?.totalPages?.toString().orEmpty()) }
    var status by remember(book?.id) { mutableStateOf(book?.status ?: "READING") }
    var subjectId by remember(book?.id) { mutableStateOf(book?.subjectId ?: initialSubjectId) }
    var rating by remember(book?.id) { mutableIntStateOf((book?.rating ?: 0).coerceIn(0, 5)) }
    var targetFinishAt by remember(book?.id) { mutableStateOf(book?.targetFinishAt) }
    var subjectExpanded by remember { mutableStateOf(false) }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            coverUri = uri.toString()
        }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                val resolvedName = withContext(Dispatchers.IO) {
                    resolveDocumentDisplayName(context, uri)
                }
                val detectedPages = withContext(Dispatchers.IO) {
                    detectAttachedDocumentPages(context, uri, resolvedName)
                }
                documentUri = uri.toString()
                documentName = resolvedName
                if (title.isBlank()) {
                    title = resolvedName.substringBeforeLast('.').ifBlank { resolvedName }
                }
                if (totalPages.isBlank() && detectedPages != null && detectedPages > 0) {
                    totalPages = detectedPages.toString()
                }
            }
        }
    }

    val total = totalPages.toIntOrNull()?.takeIf { it > 0 }
    val current = if (status == "DONE" && total != null) {
        total
    } else {
        (currentPage.toIntOrNull()?.coerceAtLeast(0) ?: 0).let { pages ->
            total?.let(pages::coerceAtMost) ?: pages
        }
    }

    fun buildBookSnapshot(): Book {
        val now = System.currentTimeMillis()
        val base = book ?: Book(
            title = title.trim().ifBlank { "Livro" },
            author = author.trim(),
            createdAt = now
        )
        return base.copy(
            title = title.trim().ifBlank { "Livro" },
            author = author.trim(),
            coverUri = coverUri,
            summary = summary.trim(),
            currentPage = current,
            totalPages = total,
            status = status,
            subjectId = subjectId,
            rating = rating.coerceIn(0, 5),
            categoryTag = categoryTag.trim(),
            currentChapter = currentChapter.trim(),
            targetFinishAt = targetFinishAt,
            documentUri = documentUri.trim(),
            documentName = documentName.trim(),
            updatedAt = now
        )
    }

    val goldStar = LocalStudySemanticColors.current.goldStar

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (book == null) "Adicionar livro" else "Editar livro",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BookCover(coverUri, Modifier.width(78.dp).height(112.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Capa e Arquivo de Leitura", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Escolha uma capa ou vincule um PDF / TXT / MD para ler no Modo ReadEra.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(onClick = { coverPicker.launch(arrayOf("image/*")) }) {
                                Icon(Icons.Default.Image, null, Modifier.size(16.dp))
                                Text(" Capa", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilledTonalButton(
                                onClick = {
                                    documentPicker.launch(
                                        arrayOf("application/pdf", "text/plain", "text/markdown", "text/*")
                                    )
                                }
                            ) {
                                Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                                Text(
                                    if (documentUri.isBlank()) " PDF/TXT/MD" else " Trocar Doc",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!coverUri.isNullOrBlank()) {
                                TextButton(onClick = { coverUri = null }) {
                                    Text("Limpar capa")
                                }
                            }
                        }
                        if (documentName.isNotBlank() || documentUri.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    "📄 ${documentName.ifBlank { "Documento vinculado" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                TextButton(
                                    onClick = {
                                        documentUri = ""
                                        documentName = ""
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("Desvincular", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("Autor") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = categoryTag,
                        onValueChange = { categoryTag = it },
                        label = { Text("Tag / Categoria") },
                        placeholder = { Text("Ex.: Técnico") },
                        singleLine = true,
                        modifier = Modifier.weight(0.9f)
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = subjectExpanded,
                    onExpandedChange = { subjectExpanded = !subjectExpanded }
                ) {
                    OutlinedTextField(
                        value = subjects.firstOrNull { it.id == subjectId }?.name ?: "Sem matéria",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Matéria relacionada") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subjectExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = subjectExpanded,
                        onDismissRequest = { subjectExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sem matéria") },
                            onClick = { subjectId = null; subjectExpanded = false }
                        )
                        subjects.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = { subjectId = item.id; subjectExpanded = false }
                            )
                        }
                    }
                }

                Text(
                    "Estante e Progresso",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = status == "READING",
                        onClick = { status = "READING" },
                        shape = SegmentedButtonDefaults.itemShape(0, 3)
                    ) { Text("Lendo", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    SegmentedButton(
                        selected = status == "WISHLIST",
                        onClick = { status = "WISHLIST" },
                        shape = SegmentedButtonDefaults.itemShape(1, 3)
                    ) { Text("Quero ler", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    SegmentedButton(
                        selected = status == "DONE",
                        onClick = {
                            status = "DONE"
                            if (total != null) currentPage = total.toString()
                        },
                        shape = SegmentedButtonDefaults.itemShape(2, 3)
                    ) { Text("Lido", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currentPage,
                        onValueChange = { currentPage = it.filter(Char::isDigit) },
                        label = { Text("Página atual") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = totalPages,
                        onValueChange = { totalPages = it.filter(Char::isDigit) },
                        label = { Text("Total de páginas") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = currentChapter,
                    onValueChange = { currentChapter = it },
                    label = { Text("Capítulo atual (opcional)") },
                    placeholder = { Text("Ex.: Cap. 3 — Estruturas de Dados") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (total != null && total > 0) {
                    val pct = (current * 100 / total).coerceIn(0, 100)
                    val remPages = (total - current).coerceAtLeast(0)
                    val remMins = if (pacePagesPerHour > 0) (remPages * 60) / pacePagesPerHour else 0
                    LinearProgressIndicator(
                        progress = { (current.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        "$pct% concluído · faltam $remPages páginas (~${remMins / 60}h ${remMins % 60}m em ~$pacePagesPerHour pág/h)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Target finish date picker
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val zone = java.time.ZoneId.systemDefault()
                            val initialDate = java.time.Instant.ofEpochMilli(
                                targetFinishAt ?: (System.currentTimeMillis() + 14 * 86_400_000L)
                            ).atZone(zone).toLocalDate()
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    targetFinishAt = java.time.LocalDate.of(y, m + 1, d)
                                        .atTime(23, 59, 59)
                                        .atZone(zone)
                                        .toInstant()
                                        .toEpochMilli()
                                },
                                initialDate.year,
                                initialDate.monthValue - 1,
                                initialDate.dayOfMonth
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Event, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            targetFinishAt?.let {
                                "Prazo: " + BOOK_DEADLINE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                            } ?: "Definir prazo de leitura",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (targetFinishAt != null) {
                        IconButton(onClick = { targetFinishAt = null }) {
                            Icon(Icons.Default.Close, "Remover prazo")
                        }
                    }
                }

                // Star rating (1-5)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Avaliação:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(end = 6.dp)
                    )
                    (1..5).forEach { star ->
                        IconButton(
                            onClick = { rating = if (rating == star) 0 else star },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (star <= rating) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "$star estrelas",
                                tint = if (star <= rating) goldStar else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Resumo / Fichamento geral") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val snapshot = buildBookSnapshot()
                    if (snapshot.title.isNotBlank()) {
                        onSave(snapshot)
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            Row {
                if (book != null && onDelete != null) {
                    TextButton(onClick = { onDelete(book) }) {
                        Text("Remover")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            }
        }
    )
}
