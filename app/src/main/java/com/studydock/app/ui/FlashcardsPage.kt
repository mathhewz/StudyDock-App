@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.studydock.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TAG_SPLIT_REGEX = Regex("[\\s,;]+")
private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")
private val NEXT_REVIEW_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm")

private data class DeckTreeRow(
    val deck: CardDeck,
    val depth: Int,
    val hasChildren: Boolean
)

private enum class BrowserSort(val label: String) {
    DUE("Vencimento"),
    INTERVAL("Intervalo"),
    LAPSES("Mais erros"),
    STABILITY("Estabilidade FSRS"),
    RECENT("Mais recentes")
}

internal fun ankiFlagColor(flag: Int): Color = when (flag) {
    1 -> Color(0xFFEF4444)
    2 -> Color(0xFFF97316)
    3 -> Color(0xFF22C55E)
    4 -> Color(0xFF3B82F6)
    5 -> Color(0xFFEC4899)
    6 -> Color(0xFF06B6D4)
    7 -> Color(0xFFA855F7)
    else -> Color.Transparent
}

internal fun ankiFlagName(flag: Int): String = when (flag) {
    1 -> "Vermelha"
    2 -> "Laranja"
    3 -> "Verde"
    4 -> "Azul"
    5 -> "Rosa"
    6 -> "Turquesa"
    7 -> "Roxa"
    else -> "Sem bandeira"
}

private fun parseTagsList(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.split(TAG_SPLIT_REGEX)
        .mapNotNull { token -> token.trim().removePrefix("#").takeIf { it.isNotEmpty() } }
        .distinct()
}

private fun toggleQueryToken(current: String, token: String): String {
    val tokens = current.trim().split(WHITESPACE_SPLIT_REGEX).filter { it.isNotBlank() }.toMutableList()
    val existingIdx = tokens.indexOfFirst { it.equals(token, ignoreCase = true) }
    if (existingIdx >= 0) {
        tokens.removeAt(existingIdx)
    } else {
        tokens.add(token)
    }
    return tokens.joinToString(" ")
}

@Composable
fun FlashcardsPage(
    cards: List<Flashcard>,
    reviews: List<CardReview>,
    subjects: List<Subject>,
    showStats: Boolean,
    selectStats: (Boolean) -> Unit,
    decks: List<CardDeck>,
    index: DeckStudyIndex,
    indexUpdating: Boolean,
    now: Long,
    saveDeck: suspend (CardDeck) -> Long,
    dissolveDeck: suspend (Long) -> Unit,
    deleteCard: suspend (Long) -> Unit,
    setSuspended: suspend (Long, Boolean) -> Unit,
    importDeck: suspend (DeckFile) -> Long,
    exportDeck: suspend (Long, String) -> DeckFile,
    subjectFilter: Long,
    selectSubject: (Long) -> Unit,
    deck: Long,
    selectDeck: (Long) -> Unit,
    templates: List<CardTemplate>,
    loadAuthor: suspend (Long) -> CardAuthorNote?,
    loadImage: suspend (Long) -> ByteArray?,
    saveAuthor: suspend (Long?, CardAuthorNote?, CardAuthorNote, Long?, Long?, CardImage?) -> Unit,
    round: List<RoundCard>?,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onReview: (List<Flashcard>) -> Unit,
    onCramReview: (List<Flashcard>) -> Unit = onReview,
    undoLastReview: (suspend () -> Boolean)? = null,
    canUndoReview: Boolean = false,
    batchMoveDeck: (suspend (List<Long>, Long?) -> Unit)? = null,
    batchSetSuspended: (suspend (List<Long>, Boolean) -> Unit)? = null,
    batchAddTag: (suspend (List<Long>, String) -> Unit)? = null,
    batchSetFlag: (suspend (List<Long>, Int) -> Unit)? = null,
    batchReschedule: (suspend (List<Long>, Int) -> Unit)? = null,
    batchResetCards: (suspend (List<Long>) -> Unit)? = null,
    batchDeleteCards: (suspend (List<Long>) -> Unit)? = null,
    createFilteredDeck: (suspend (String, String, Int) -> Long)? = null,
    importApkg: (suspend (Uri) -> Pair<Long, List<String>>)? = null,
    importCsv: (suspend (String, String) -> Long)? = null,
    exportCsv: (suspend (Long, String) -> String)? = null,
    onBuryCard: (suspend (Long) -> Unit)? = null,
    onSetCardFlag: (suspend (Long, Int) -> Unit)? = null
) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val openGlobalSearch = LocalGlobalSearchWithQueryOpener.current
    val semantic = LocalStudySemanticColors.current
    val scheme = MaterialTheme.colorScheme
    val itemSpacing = StudyTokens.itemSpacing()
    val sectionSpacing = StudyTokens.sectionSpacing()
    val cardPadding = StudyTokens.cardPadding()
    val controlShape = studyControlShape()
    var editor by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var showCustomStudy by rememberSaveable { mutableStateOf(false) }
    var customStudyInitialQuery by rememberSaveable { mutableStateOf("") }
    var configuringDeckId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingDeckId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDeckTree by rememberSaveable { mutableStateOf(true) }
    var expandedDeckIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var showDslHelp by rememberSaveable { mutableStateOf(false) }
    var browserSortName by rememberSaveable { mutableStateOf(BrowserSort.DUE.name) }
    val browserSort = remember(browserSortName) {
        BrowserSort.entries.firstOrNull { it.name == browserSortName } ?: BrowserSort.DUE
    }

    // Multi-selection state
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var batchBusy by remember { mutableStateOf(false) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }
    var showBatchTagDialog by remember { mutableStateOf(false) }
    var showBatchFlagDialog by remember { mutableStateOf(false) }
    var showBatchRescheduleDialog by remember { mutableStateOf(false) }
    var showBatchResetConfirm by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var singleCardFlagTarget by remember { mutableStateOf<Flashcard?>(null) }

    val pageScope = rememberCoroutineScope()
    val tree = index.tree
    val decksById = remember(decks) { decks.associateBy { it.id } }
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    val subjectsById = remember(subjects) { subjects.associateBy { it.id } }
    val dayByRolloverHour = remember(now, zone, decks) {
        buildMap {
            put(4, ankiLogicalDay(now, 4, zone))
            for (d in decks) {
                val h = d.dayStartHour
                if (h !in this) put(h, ankiLogicalDay(now, h, zone))
            }
        }
    }

    LaunchedEffect(deck, decks) {
        if (deck > 0L) {
            val ancestors = tree.ancestors(deck).drop(1).map { it.id }
            if (ancestors.isNotEmpty()) {
                expandedDeckIds = expandedDeckIds + ancestors
            }
        }
    }

    val library = remember(cards, tree, deck, subjectFilter) {
        cards.filter { tree.contains(deck, it.deckId) && (subjectFilter == -1L || (it.subjectId ?: 0L) == subjectFilter) }
    }
    val parsedTagsByCardId = remember(cards) {
        cards.associate { it.id to parseTagsList(it.tags) }
    }
    val allUsedTags = remember(parsedTagsByCardId) {
        parsedTagsByCardId.values.asSequence().flatten().distinct().sorted().toList()
    }
    val libraryTags = remember(library, parsedTagsByCardId) {
        library.asSequence().flatMap { (parsedTagsByCardId[it.id] ?: emptyList()).asSequence() }.distinct().sorted().take(12).toList()
    }

    var filter by rememberSaveable { mutableStateOf("Todos") }
    var historyCard by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeDeckCfg = remember(decks, deck) { decks.firstOrNull { it.id == deck } }
    val due = remember(index, now, deck, subjectFilter, activeDeckCfg) {
        val rawDue = index.due(now, deck, subjectFilter)
        com.studydock.app.data.DeckQueue.applyDisplayOrder(rawDue, activeDeckCfg?.displayOrder ?: "MIX")
    }

    var debouncedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            debouncedQuery = ""
        } else {
            delay(120L)
            debouncedQuery = query
        }
    }

    var visible by remember { mutableStateOf<List<Flashcard>>(emptyList()) }
    var visibleReady by remember { mutableStateOf(false) }
    LaunchedEffect(cards, library, due, decks, debouncedQuery, filter, subjectFilter, now, zone, browserSort) {
        visible = withContext(Dispatchers.Default) {
            val trimmed = debouncedQuery.trim()
            val hasDeckDsl = trimmed.contains("deck:", ignoreCase = true)
            val baseSource = when {
                hasDeckDsl -> cards.filter { subjectFilter == -1L || (it.subjectId ?: 0L) == subjectFilter }
                filter == "Para revisar" -> due
                else -> library
            }.let { list ->
                when (filter) {
                    "Suspensos" -> list.filter { it.suspended }
                    else -> list
                }
            }
            val dslFiltered = if (trimmed.isBlank()) baseSource else {
                CardQueryEngine.filter(baseSource, trimmed, decks, now, zone)
            }
            when (browserSort) {
                BrowserSort.DUE -> dslFiltered.sortedWith(compareBy<Flashcard> { it.suspended }.thenBy { it.dueAt }.thenBy { it.id })
                BrowserSort.INTERVAL -> dslFiltered.sortedWith(compareByDescending<Flashcard> { it.intervalDays }.thenBy { it.dueAt })
                BrowserSort.LAPSES -> dslFiltered.sortedWith(compareByDescending<Flashcard> { it.lapses }.thenBy { it.easeFactor })
                BrowserSort.STABILITY -> dslFiltered.sortedWith(compareByDescending<Flashcard> { it.fsrsStability }.thenByDescending { it.intervalDays })
                BrowserSort.RECENT -> dslFiltered.sortedByDescending { it.id }
            }
        }
        visibleReady = true
    }

    // Prune deleted cards from selection
    LaunchedEffect(cards) {
        val validIds = cards.mapTo(HashSet()) { it.id }
        val cleaned = selectedIds.filterTo(HashSet()) { it in validIds }
        if (cleaned.size != selectedIds.size) {
            selectedIds = cleaned
            if (cleaned.isEmpty()) selectionMode = false
        }
    }

    val filteredReviews = remember(showStats, reviews, tree, deck, subjectFilter) {
        if (!showStats) {
            emptyList()
        } else {
            reviews.filter { review ->
                if (review.priorState == null) deck == -1L && subjectFilter == -1L
                else tree.contains(deck, review.deckAtReview) && (subjectFilter == -1L || subjectFilter == (review.subjectAtReview ?: 0L))
            }
        }
    }
    var history by remember { mutableStateOf<ReviewHistory?>(null) }
    LaunchedEffect(showStats, filteredReviews, today, zone) {
        if (!showStats) {
            history = null
            return@LaunchedEffect
        }
        history = withContext(Dispatchers.Default) {
            ReviewHistory(filteredReviews, today, zone)
        }
    }
    val pending = remember(showStats, index, today, zone, deck, subjectFilter) {
        if (!showStats) 0
        else {
            val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            remainingToday(index, tomorrow - 1, deck, subjectFilter)
        }
    }
    val deckOptions = remember(tree) {
        listOf(-1L to "Todos", 0L to "Sem baralho") + tree.ordered.map { it.id to tree.label(it.id) }
    }
    val subjectOptions = remember(subjects) {
        listOf(-1L to "Todas", 0L to "Geral") + subjects.map { it.id to it.name }
    }

    val rootCounters = remember(index, now, subjectFilter) {
        index.countersForDeck(now, -1L, subjectFilter)
    }
    val unassignedCount = remember(showDeckTree, cards, subjectFilter) {
        if (!showDeckTree) 0
        else cards.count { it.deckId == null && (subjectFilter == -1L || (it.subjectId ?: 0L) == subjectFilter) }
    }

    val visibleTreeRows = remember(showDeckTree, decks, expandedDeckIds) {
        if (!showDeckTree) return@remember emptyList()
        val validIds = decks.mapTo(HashSet()) { it.id }
        val byParent = decks.groupBy { d -> d.parentId?.takeIf { it in validIds } }
        val rows = mutableListOf<DeckTreeRow>()
        val visited = HashSet<Long>()
        fun walk(parentId: Long?, depth: Int) {
            val children = byParent[parentId].orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            for (child in children) {
                if (!visited.add(child.id)) continue
                val childList = byParent[child.id].orEmpty()
                rows.add(DeckTreeRow(child, depth, childList.isNotEmpty()))
                if (childList.isNotEmpty() && child.id in expandedDeckIds) {
                    walk(child.id, depth + 1)
                }
            }
        }
        walk(null, 0)
        rows
    }

    val learningColor = remember(semantic, scheme) {
        lerp(semantic.warning, scheme.error, 0.35f)
    }

    fun runBatch(successMessage: String, block: suspend (List<Long>) -> Unit) {
        val ids = selectedIds.toList()
        if (ids.isEmpty() || batchBusy) return
        batchBusy = true
        pageScope.launch {
            try {
                block(ids)
                selectedIds = emptySet()
                selectionMode = false
                Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.localizedMessage ?: "Falha na ação em lote.", Toast.LENGTH_LONG).show()
            } finally {
                batchBusy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).imePadding(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            item(key = "page_header", contentType = "page_header") {
                val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wideHeader = maxWidth >= 380.dp * fontScale
                    StudyPageHeader(
                        title = "Flashcards",
                        showGlobalSearch = true,
                        actions = {
                            DeckActions(
                                deck = decks.firstOrNull { it.id == deck },
                                exportDeck = exportDeck,
                                importDeck = importDeck,
                                selectDeck = { selected ->
                                    selectSubject(-1L)
                                    selectDeck(selected)
                                },
                                importApkg = importApkg,
                                importCsv = importCsv,
                                exportCsv = exportCsv,
                                cardsCount = library.size
                            )
                            if (wideHeader) {
                                FilledTonalButton(onClick = { editor = 0L }, shape = controlShape) {
                                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Criar cartão", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else {
                                FilledTonalIconButton(onClick = { editor = 0L }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Add, "Criar cartão")
                                }
                            }
                        }
                    )
                }
                Spacer(Modifier.height(itemSpacing))
                DeckLibraryControls(tree, deck, selectDeck, saveDeck, dissolveDeck, reviews)
                if (subjects.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Matéria",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant
                        )
                        subjectOptions.forEach { (id, name) ->
                            FilterChip(
                                subjectFilter == id,
                                { selectSubject(id) },
                                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }

            // Expandable Deck Tree with Anki counters (New | Learning | Review), flattened for LazyColumn virtualization
            item(key = "deck_tree_header", contentType = "deck_tree_header") {
                StudyCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = cardPadding, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(controlShape)
                                .combinedClickable(onClick = { showDeckTree = !showDeckTree })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                if (showDeckTree) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                contentDescription = if (showDeckTree) "Recolher árvore de baralhos" else "Expandir árvore de baralhos",
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Árvore de baralhos",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        AnkiDeckCountersBadge(
                            counters = rootCounters,
                            newColor = semantic.info,
                            learningColor = learningColor,
                            reviewColor = semantic.success
                        )
                        IconButton(
                            onClick = {
                                customStudyInitialQuery = ""
                                showCustomStudy = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.FilterAlt, "Criar baralho filtrado / estudo livre", Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (showDeckTree) {
                item(key = "deck_tree_legend", contentType = "deck_tree_legend") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = cardPadding),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Novos", style = MaterialTheme.typography.labelSmall, color = semantic.info)
                        Text(" · ", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        Text("Aprend.", style = MaterialTheme.typography.labelSmall, color = learningColor)
                        Text(" · ", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        Text("Revisar", style = MaterialTheme.typography.labelSmall, color = semantic.success)
                    }
                }

                item(key = "deck_root_all", contentType = "deck_row") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = controlShape,
                        color = scheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        DeckTreeItemRow(
                            title = "Todos os baralhos",
                            depth = 0,
                            selected = deck == -1L,
                            hasChildren = false,
                            expanded = false,
                            isFiltered = false,
                            isFsrs = false,
                            counters = rootCounters,
                            newColor = semantic.info,
                            learningColor = learningColor,
                            reviewColor = semantic.success,
                            onToggleExpand = {},
                            onSelect = { selectDeck(-1L) },
                            onStudy = {
                                selectDeck(-1L)
                                val batch = index.due(now, -1L, subjectFilter)
                                if (batch.isNotEmpty()) onReview(batch)
                                else {
                                    customStudyInitialQuery = ""
                                    showCustomStudy = true
                                }
                            },
                            onConfigure = null,
                            onFilter = {
                                customStudyInitialQuery = ""
                                showCustomStudy = true
                            }
                        )
                    }
                }

                if (unassignedCount > 0 || deck == 0L) {
                    item(key = "deck_unassigned", contentType = "deck_row") {
                        val unassignedCounters = remember(index, now, subjectFilter) {
                            index.countersForDeck(now, 0L, subjectFilter)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = controlShape,
                            color = scheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f))
                        ) {
                            DeckTreeItemRow(
                                title = "Sem baralho ($unassignedCount)",
                                depth = 0,
                                selected = deck == 0L,
                                hasChildren = false,
                                expanded = false,
                                isFiltered = false,
                                isFsrs = false,
                                counters = unassignedCounters,
                                newColor = semantic.info,
                                learningColor = learningColor,
                                reviewColor = semantic.success,
                                onToggleExpand = {},
                                onSelect = { selectDeck(0L) },
                                onStudy = {
                                    selectDeck(0L)
                                    val batch = index.due(now, 0L, subjectFilter)
                                    if (batch.isNotEmpty()) onReview(batch)
                                },
                                onConfigure = null,
                                onFilter = {
                                    selectDeck(0L)
                                    customStudyInitialQuery = ""
                                    showCustomStudy = true
                                }
                            )
                        }
                    }
                }

                items(visibleTreeRows, key = { "deck_${it.deck.id}" }, contentType = { "deck_row" }) { row ->
                    val itemDeck = row.deck
                    val itemCounters = remember(index, now, itemDeck.id, subjectFilter) {
                        index.countersForDeck(now, itemDeck.id, subjectFilter)
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = controlShape,
                        color = scheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        DeckTreeItemRow(
                            title = itemDeck.name,
                            depth = row.depth,
                            selected = deck == itemDeck.id,
                            hasChildren = row.hasChildren,
                            expanded = itemDeck.id in expandedDeckIds,
                            isFiltered = itemDeck.isFiltered,
                            isFsrs = itemDeck.schedulerMode.equals("FSRS", ignoreCase = true),
                            counters = itemCounters,
                            newColor = semantic.info,
                            learningColor = learningColor,
                            reviewColor = semantic.success,
                            onToggleExpand = {
                                expandedDeckIds = if (itemDeck.id in expandedDeckIds) {
                                    expandedDeckIds - itemDeck.id
                                } else {
                                    expandedDeckIds + itemDeck.id
                                }
                            },
                            onSelect = { selectDeck(itemDeck.id) },
                            onStudy = {
                                selectDeck(itemDeck.id)
                                val deckDue = index.due(now, itemDeck.id, subjectFilter)
                                if (deckDue.isNotEmpty()) {
                                    onReview(deckDue)
                                } else if (itemDeck.isFiltered && itemDeck.filterQuery.isNotBlank()) {
                                    val matched = CardQueryEngine.filter(cards, itemDeck.filterQuery, decks, now, zone)
                                        .filterNot { it.suspended }
                                    if (matched.isNotEmpty()) onCramReview(matched)
                                    else Toast.makeText(context, "Nenhum cartão corresponde ao filtro.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Sem revisões pendentes neste baralho.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onConfigure = { configuringDeckId = itemDeck.id },
                            onFilter = {
                                selectDeck(itemDeck.id)
                                val label = tree.label(itemDeck.id)
                                customStudyInitialQuery = if (itemDeck.isFiltered && itemDeck.filterQuery.isNotBlank()) {
                                    itemDeck.filterQuery
                                } else if (label.contains(' ')) {
                                    "deck:\"$label\""
                                } else {
                                    "deck:$label"
                                }
                                showCustomStudy = true
                            }
                        )
                    }
                }
            }

            if (!showStats) item(key = "study_hero", contentType = "study_hero") {
                StudyCard(
                    modifier = Modifier.fillMaxWidth(),
                    emphasized = true,
                    isHero = true
                ) {
                    Column(Modifier.fillMaxWidth().padding(cardPadding), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                        val active = remember(library) { library.filterNot { it.suspended } }
                        val title = when {
                            indexUpdating -> "Atualizando coleção…"
                            library.isEmpty() -> "Baralho vazio"
                            due.isNotEmpty() -> "${due.size} " + if (due.size == 1) "cartão para revisar" else "cartões para revisar"
                            active.any { it.dueAt <= now } -> "Limite diário atingido"
                            active.isEmpty() -> "Todos os cartões suspensos"
                            else -> "Revisão em dia"
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (canUndoReview && undoLastReview != null) {
                                TextButton(
                                    onClick = {
                                        pageScope.launch {
                                            val undone = undoLastReview()
                                            Toast.makeText(
                                                context,
                                                if (undone) "Última revisão desfeita." else "Nenhuma revisão recente para desfazer.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onPrimaryContainer)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Desfazer")
                                }
                            }
                        }
                        if (indexUpdating) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (!indexUpdating && due.isNotEmpty()) {
                            val counts = remember(due) { due.groupingBy { it.reviewState }.eachCount() }
                            val newCount = counts["NEW"] ?: 0
                            val newLabel = if (newCount == 1) "1 novo" else "$newCount novos"
                            Text(
                                "$newLabel · ${(counts["LEARNING"] ?: 0) + (counts["RELEARNING"] ?: 0)} em aprendizagem · ${counts["REVIEW"] ?: 0} em revisão",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        decksById[deck]?.dailyGoal?.let { goal ->
                            val done = index.doneToday(deck)
                            Text("Meta do baralho · $done de $goal cartões", style = MaterialTheme.typography.labelLarge)
                            LinearProgressIndicator(
                                progress = { (done.toFloat() / goal).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = scheme.onPrimaryContainer,
                                trackColor = scheme.onPrimaryContainer.copy(alpha = 0.22f)
                            )
                        }
                        val heroOutlinedColors = ButtonDefaults.outlinedButtonColors(
                            contentColor = scheme.onPrimaryContainer
                        )
                        val heroOutlinedBorder = BorderStroke(1.dp, scheme.onPrimaryContainer.copy(alpha = 0.45f))
                        val heroTextColors = ButtonDefaults.textButtonColors(
                            contentColor = scheme.onPrimaryContainer
                        )
                        if (round == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else if (round.any { !it.entry.done && !it.card.suspended }) {
                            RoundBanner(round, onResume, onFinish)
                            if (active.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        customStudyInitialQuery = ""
                                        showCustomStudy = true
                                    },
                                    enabled = !indexUpdating,
                                    shape = controlShape,
                                    colors = heroOutlinedColors,
                                    border = heroOutlinedBorder
                                ) {
                                    Icon(Icons.Default.Bolt, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Baralho Filtrado / Estudo Livre", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else {
                            if (due.isNotEmpty()) {
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = { onReview(due) },
                                        enabled = !indexUpdating,
                                        shape = controlShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = scheme.primary,
                                            contentColor = scheme.onPrimary
                                        )
                                    ) { Text("Revisar agora", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    if (due.size > 5) {
                                        TextButton(
                                            onClick = { onReview(due.take(5)) },
                                            enabled = !indexUpdating,
                                            colors = heroTextColors
                                        ) { Text("Só 5 cartões", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            customStudyInitialQuery = ""
                                            showCustomStudy = true
                                        },
                                        enabled = active.isNotEmpty(),
                                        shape = controlShape,
                                        colors = heroOutlinedColors,
                                        border = heroOutlinedBorder
                                    ) {
                                        Icon(Icons.Default.Bolt, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Estudo Filtrado", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            } else if (active.isNotEmpty() || cards.isNotEmpty()) {
                                if (active.isNotEmpty() && active.none { it.dueAt <= now }) {
                                    val next = active.minOf { it.dueAt }
                                    Text("Próxima revisão · " + NEXT_REVIEW_FMT.format(Instant.ofEpochMilli(next).atZone(zone)))
                                }
                                OutlinedButton(
                                    onClick = {
                                        customStudyInitialQuery = ""
                                        showCustomStudy = true
                                    },
                                    shape = controlShape,
                                    colors = heroOutlinedColors,
                                    border = heroOutlinedBorder
                                ) {
                                    Icon(Icons.Default.Bolt, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Estudo Personalizado / Baralho Filtrado", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            item(key = "view_tabs", contentType = "view_tabs") {
                TabRow(
                    selectedTabIndex = if (showStats) 1 else 0,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = !showStats,
                        onClick = { selectStats(false) },
                        text = { Text("Navegador Pro (${visible.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                    Tab(
                        selected = showStats,
                        onClick = {
                            selectionMode = false
                            selectedIds = emptySet()
                            selectStats(true)
                        },
                        text = { Text("Estatísticas", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            if (showStats) {
                item(key = "stats_dashboard", contentType = "stats_dashboard") {
                    val currentHistory = history
                    if (currentHistory == null || indexUpdating) {
                        StudyCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Atualizando estatísticas…", style = MaterialTheme.typography.titleMedium)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                        return@item
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 760.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(sectionSpacing)) {
                                Box(Modifier.weight(1f)) { ReviewDashboard(currentHistory, pending, library.size) }
                                Box(Modifier.weight(1f)) { DeckStatistics(library, filteredReviews, today, zone, decks) }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
                                ReviewDashboard(currentHistory, pending, library.size)
                                DeckStatistics(library, filteredReviews, today, zone, decks)
                            }
                        }
                    }
                }
                return@LazyColumn
            }

            // Card Browser Pro Controls: Base Filter + DSL Search + Quick Filter Chips + Sort + Multi-Select
            item(key = "browser_controls", contentType = "browser_controls") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Todos", "Para revisar", "Suspensos").forEach { option ->
                            FilterChip(
                                filter == option,
                                { filter = option },
                                label = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            StudyLocalSearchField(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = "DSL: is:due tag:X flag:1 prop:ivl>=7 -termo OR",
                                onSearchGlobal = openGlobalSearch
                            )
                        }
                        IconButton(
                            onClick = { showDslHelp = !showDslHelp },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, "Sintaxe da busca DSL")
                        }
                    }

                    if (showDslHelp) {
                        Surface(
                            shape = controlShape,
                            color = scheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Operadores DSL do Navegador Pro", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "• Estados: is:due · is:new · is:learn · is:review · is:suspended · is:buried · is:leech\n" +
                                        "• Bandeira: flag:1 até flag:7 · flag:0\n" +
                                        "• Baralho/Tag: deck:Nome · tag:assunto/subtag\n" +
                                        "• Propriedades: prop:ivl>=7 · prop:lapses>=5 · prop:ease<2.0 · prop:reps==0\n" +
                                        "• Lógica: -excluir · termo1 OR termo2 · \"frase exata\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Horizontal Quick Filter Chips Bar (States, Flags 1..7, Tags)
                    val queryTokens = remember(query) {
                        query.trim().split(WHITESPACE_SPLIT_REGEX).filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            "is:due" to "Vencidos",
                            "is:new" to "Novos",
                            "is:learn" to "Aprendendo",
                            "is:leech" to "Sanguessugas",
                            "is:suspended" to "Suspensos",
                            "is:buried" to "Enterrados"
                        ).forEach { (dsl, label) ->
                            FilterChip(
                                selected = dsl in queryTokens,
                                onClick = { query = toggleQueryToken(query, dsl) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                            )
                        }
                        (1..7).forEach { flagNum ->
                            val token = "flag:$flagNum"
                            FilterChip(
                                selected = token in queryTokens,
                                onClick = { query = toggleQueryToken(query, token) },
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(ankiFlagColor(flagNum))
                                    )
                                },
                                label = { Text("F$flagNum", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                            )
                        }
                        libraryTags.forEach { tag ->
                            val token = "tag:$tag"
                            FilterChip(
                                selected = token.lowercase() in queryTokens,
                                onClick = { query = toggleQueryToken(query, token) },
                                label = { Text("#$tag", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                            )
                        }
                    }

                    // Sort & Multi-Select Toolbar
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        var sortMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { sortMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Ordem: ${browserSort.label}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                BrowserSort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.label) },
                                        onClick = { browserSortName = s.name; sortMenu = false }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                if (selectionMode) {
                                    selectionMode = false
                                    selectedIds = emptySet()
                                } else {
                                    selectionMode = true
                                }
                            },
                            enabled = visible.isNotEmpty() || selectionMode,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Checklist, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (selectionMode) "Sair do lote" else "Selecionar", style = MaterialTheme.typography.labelMedium)
                        }
                        if (visible.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    selectionMode = true
                                    selectedIds = visible.map { it.id }.toSet()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Todos (${visible.size})", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (visibleReady && visible.isEmpty()) item(key = "browser_empty", contentType = "browser_empty") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (library.isEmpty()) "Crie o primeiro cartão desta seleção." else "Nenhum cartão encontrado para este filtro.",
                        color = scheme.onSurfaceVariant
                    )
                    if (library.isEmpty()) {
                        FilledTonalButton(onClick = { editor = 0L }, shape = controlShape) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Criar cartão", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else if (query.isNotBlank() || filter != "Todos") {
                        TextButton(onClick = { query = ""; filter = "Todos" }) {
                            Text("Limpar filtros")
                        }
                    }
                }
            }

            items(visible, key = { "card_${it.id}" }, contentType = { "browser_card" }) { card ->
                val isSelected = card.id in selectedIds
                val cardDeck = card.deckId?.let(decksById::get)
                val rolloverHour = cardDeck?.dayStartHour ?: 4
                val logicalDay = dayByRolloverHour[rolloverHour] ?: ankiLogicalDay(now, rolloverHour, zone)
                val isBuried = card.isBuriedOn(logicalDay)
                val tags = parsedTagsByCardId[card.id] ?: emptyList()
                val isLeech = tags.any { it.equals("sanguessuga", ignoreCase = true) || it.equals("leech", ignoreCase = true) } ||
                    card.lapses >= (cardDeck?.leechThreshold ?: 8)

                StudyCard(
                    modifier = Modifier.fillMaxWidth(),
                    highlighted = isSelected
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        val next = if (isSelected) selectedIds - card.id else selectedIds + card.id
                                        selectedIds = next
                                    } else {
                                        editor = card.id
                                    }
                                },
                                onLongClick = {
                                    selectionMode = true
                                    selectedIds = selectedIds + card.id
                                }
                            )
                            .padding(start = if (selectionMode) 4.dp else cardPadding, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + card.id else selectedIds - card.id
                                }
                            )
                        }
                        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (card.flag in 1..7) {
                                    Icon(
                                        Icons.Default.Flag,
                                        contentDescription = "Bandeira ${ankiFlagName(card.flag)}",
                                        tint = ankiFlagColor(card.flag),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    card.front,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            val subject = card.subjectId?.let { subjectsById[it]?.name } ?: "Geral"
                            val stateText = when {
                                card.suspended -> "Suspenso"
                                isBuried -> "Enterrado"
                                card.reviewState == "NEW" -> "Novo"
                                card.reviewState == "LEARNING" || card.reviewState == "RELEARNING" -> "Aprendendo"
                                else -> "Ivl: ${card.intervalDays}d"
                            }
                            val stateColor = when {
                                card.suspended || isBuried -> scheme.onSurfaceVariant
                                card.reviewState == "NEW" -> semantic.info
                                card.reviewState == "LEARNING" || card.reviewState == "RELEARNING" -> learningColor
                                card.dueAt <= now -> semantic.warning
                                else -> semantic.success
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    subject,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    shape = controlShape,
                                    color = stateColor.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        stateText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = stateColor,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (card.fsrsStability > 0.0) {
                                    Surface(
                                        shape = controlShape,
                                        color = scheme.secondaryContainer.copy(alpha = 0.7f)
                                    ) {
                                        Text(
                                            "FSRS S:${String.format(Locale.US, "%.1f", card.fsrsStability)}d",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (isLeech) {
                                    Surface(
                                        shape = controlShape,
                                        color = scheme.errorContainer
                                    ) {
                                        Text(
                                            "Sanguessuga",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onErrorContainer,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (card.dueAt <= now && !card.suspended && !isBuried && card.reviewState == "REVIEW") {
                                    Text(
                                        "· Vencido",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = semantic.warning,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                tags.take(3).forEach { tag ->
                                    Surface(
                                        shape = controlShape,
                                        color = scheme.surfaceContainerHighest
                                    ) {
                                        Text(
                                            "#$tag",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (tags.size > 3) {
                                    Text(
                                        "+${tags.size - 3}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (!selectionMode) {
                            var menu by remember { mutableStateOf(false) }
                            val scope = rememberCoroutineScope()
                            Box {
                                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Opções do cartão") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Editar ou mover") }, onClick = { menu = false; editor = card.id })
                                    if (onSetCardFlag != null) {
                                        DropdownMenuItem(
                                            text = { Text("Bandeira (${ankiFlagName(card.flag)})") },
                                            onClick = { menu = false; singleCardFlagTarget = card }
                                        )
                                    }
                                    if (onBuryCard != null) {
                                        DropdownMenuItem(
                                            text = { Text("Enterrar até amanhã") },
                                            onClick = {
                                                menu = false
                                                scope.launch {
                                                    runCatching { onBuryCard(card.id) }
                                                        .onFailure { Toast.makeText(context, "Falha ao enterrar o cartão.", Toast.LENGTH_SHORT).show() }
                                                }
                                            }
                                        )
                                    }
                                    DropdownMenuItem(text = { Text("Histórico") }, onClick = { menu = false; historyCard = card.id })
                                    DropdownMenuItem(
                                        text = { Text(if (card.suspended) "Reativar" else "Suspender") },
                                        onClick = {
                                            menu = false
                                            scope.launch {
                                                try {
                                                    setSuspended(card.id, !card.suspended)
                                                } catch (_: android.database.SQLException) {
                                                    Toast.makeText(context, "Falha ao atualizar o cartão.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Excluir cartão", color = scheme.error) },
                                        onClick = {
                                            menu = false
                                            deleteError = null
                                            deleting = card.id
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Contextual Bottom Batch Action Bar
        if (selectionMode || selectedIds.isNotEmpty()) {
            val selectedCards = remember(cards, selectedIds) { cards.filter { it.id in selectedIds } }
            val anyActive = selectedCards.any { !it.suspended }
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = scheme.surfaceContainerHigh
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${selectedIds.size} selecionado(s)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (visible.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    val allVisible = visible.map { it.id }.toSet()
                                    selectedIds = if (selectedIds.containsAll(allVisible)) emptySet() else allVisible
                                },
                                enabled = !batchBusy
                            ) {
                                val allSelected = visible.isNotEmpty() && selectedIds.containsAll(visible.map { it.id })
                                Text(if (allSelected) "Desmarcar visíveis" else "Selecionar visíveis")
                            }
                        }
                        IconButton(
                            onClick = { selectionMode = false; selectedIds = emptySet() },
                            enabled = !batchBusy,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Close, "Fechar seleção em lote")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val hasSel = selectedIds.isNotEmpty() && !batchBusy
                        if (batchMoveDeck != null) {
                            FilledTonalButton(onClick = { showBatchMoveDialog = true }, enabled = hasSel, shape = controlShape) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Mover")
                            }
                        }
                        if (batchAddTag != null) {
                            FilledTonalButton(onClick = { showBatchTagDialog = true }, enabled = hasSel, shape = controlShape) {
                                Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Tags")
                            }
                        }
                        if (batchSetFlag != null) {
                            FilledTonalButton(onClick = { showBatchFlagDialog = true }, enabled = hasSel, shape = controlShape) {
                                Icon(Icons.Default.Flag, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Bandeira")
                            }
                        }
                        FilledTonalButton(
                            onClick = {
                                val targetSuspend = anyActive
                                runBatch(if (targetSuspend) "Cartões suspensos." else "Cartões reativados.") { ids ->
                                    if (batchSetSuspended != null) {
                                        batchSetSuspended(ids, targetSuspend)
                                    } else {
                                        ids.forEach { setSuspended(it, targetSuspend) }
                                    }
                                }
                            },
                            enabled = hasSel,
                            shape = controlShape
                        ) {
                            Icon(if (anyActive) Icons.Default.PauseCircleOutline else Icons.Default.PlayCircleOutline, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (anyActive) "Suspender" else "Reativar")
                        }
                        if (batchReschedule != null) {
                            FilledTonalButton(onClick = { showBatchRescheduleDialog = true }, enabled = hasSel, shape = controlShape) {
                                Icon(Icons.Default.Event, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reescalonar")
                            }
                        }
                        if (batchResetCards != null) {
                            FilledTonalButton(onClick = { showBatchResetConfirm = true }, enabled = hasSel, shape = controlShape) {
                                Icon(Icons.Default.RestartAlt, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Resetar")
                            }
                        }
                        OutlinedButton(
                            onClick = { showBatchDeleteConfirm = true },
                            enabled = hasSel,
                            shape = controlShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error)
                        ) {
                            Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Excluir")
                        }
                    }
                }
            }
        }
    }

    // Single Card Delete Dialog
    cards.find { it.id == deleting }?.let { card ->
        AlertDialog(
            onDismissRequest = { if (!deleteBusy) deleting = null },
            modifier = Modifier.imePadding(),
            title = { Text("Excluir este cartão?") },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CardContent(card.front, style = MaterialTheme.typography.titleMedium)
                    Text("O cartão será removido da biblioteca e da rodada atual. O histórico de respostas continuará nas estatísticas.")
                    if (card.authorNoteId != null && cards.count { it.authorNoteId == card.authorNoteId } > 1) {
                        Text("Os outros cartões gerados deste conteúdo continuarão disponíveis.")
                    }
                    deleteError?.let { Text(it, color = scheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteBusy,
                    onClick = {
                        deleteBusy = true
                        pageScope.launch {
                            try {
                                deleteCard(card.id)
                                deleting = null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                deleteError = "Não foi possível excluir o cartão. Tente novamente."
                            } finally {
                                deleteBusy = false
                            }
                        }
                    }
                ) {
                    Text(if (deleteBusy) "Excluindo…" else "Excluir", color = scheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }, enabled = !deleteBusy) { Text("Cancelar") } }
        )
    }

    cards.find { it.id == historyCard }?.let { CardHistory(it, reviews) { historyCard = null } }

    editor?.let { id ->
        val original = cards.find { it.id == id }
        if (id == 0L || original != null) key(id) {
            CardAuthorEditor(
                card = original,
                deck = deck,
                subject = subjectFilter,
                decks = deckOptions.drop(1),
                subjects = subjectOptions.drop(1),
                templates = templates,
                load = loadAuthor,
                loadImage = loadImage,
                save = saveAuthor,
                onDismiss = { editor = null },
                existingTags = allUsedTags
            )
        }
    }

    decks.find { it.id == configuringDeckId }?.let { targetDeck ->
        DeckOptionsProDialog(
            tree = tree,
            initial = targetDeck,
            editing = true,
            reviews = reviews,
            onDismiss = { configuringDeckId = null },
            onSave = { updated ->
                val savedId = saveDeck(updated)
                configuringDeckId = null
                selectDeck(savedId)
                savedId
            },
            onRequestDelete = {
                configuringDeckId = null
                deletingDeckId = targetDeck.id
            }
        )
    }

    decks.find { it.id == deletingDeckId }?.let { targetDeck ->
        DeleteDeckConfirmDialog(
            tree = tree,
            deck = targetDeck,
            onDismiss = { deletingDeckId = null },
            onConfirmDelete = {
                dissolveDeck(targetDeck.id)
                deletingDeckId = null
                if (deck == targetDeck.id) selectDeck(targetDeck.parentId ?: -1L)
            }
        )
    }

    if (showCustomStudy) {
        val hasOpenRound = round?.any { !it.entry.done && !it.card.suspended } == true
        CustomStudyFilteredDialogPro(
            allCards = cards,
            libraryCards = library,
            decks = decks,
            now = now,
            zone = zone,
            hasOpenRound = hasOpenRound,
            initialDsl = customStudyInitialQuery,
            allTags = allUsedTags,
            canCreatePersistentDeck = createFilteredDeck != null,
            onDismiss = { showCustomStudy = false },
            onStartSession = { selectedBatch, isCram ->
                showCustomStudy = false
                if (selectedBatch.isNotEmpty()) {
                    if (isCram) onCramReview(selectedBatch) else onReview(selectedBatch)
                }
            },
            onCreatePersistentDeck = { name, dsl, limit ->
                val creator = createFilteredDeck ?: return@CustomStudyFilteredDialogPro
                val createdId = creator(name, dsl, limit)
                showCustomStudy = false
                if (createdId > 0L) selectDeck(createdId)
                Toast.makeText(context, "Baralho filtrado \"$name\" criado.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Batch Action Dialogs
    if (showBatchMoveDialog && batchMoveDeck != null) {
        AlertDialog(
            onDismissRequest = { showBatchMoveDialog = false },
            title = { Text("Mover ${selectedIds.size} cartões para") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    ListItem(
                        headlineContent = { Text("Sem baralho") },
                        modifier = Modifier.combinedClickable(onClick = {
                            showBatchMoveDialog = false
                            runBatch("Cartões movidos.") { ids -> batchMoveDeck(ids, null) }
                        })
                    )
                    tree.ordered.forEach { d ->
                        ListItem(
                            headlineContent = { Text(tree.label(d.id), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.combinedClickable(onClick = {
                                showBatchMoveDialog = false
                                runBatch("Cartões movidos para ${d.name}.") { ids -> batchMoveDeck(ids, d.id) }
                            })
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBatchMoveDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showBatchTagDialog && batchAddTag != null) {
        var newTag by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBatchTagDialog = false },
            modifier = Modifier.imePadding(),
            title = { Text("Adicionar tag aos ${selectedIds.size} cartões") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text("Tag (ex: revisao_final)") },
                        singleLine = true,
                        shape = controlShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (allUsedTags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            allUsedTags.take(12).forEach { t ->
                                AssistChip(onClick = { newTag = t }, label = { Text("#$t") })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = newTag.trim().removePrefix("#").isNotBlank(),
                    onClick = {
                        val tagClean = newTag.trim().removePrefix("#").replace(WHITESPACE_SPLIT_REGEX, "_")
                        showBatchTagDialog = false
                        runBatch("Tag #$tagClean aplicada.") { ids -> batchAddTag(ids, tagClean) }
                    },
                    shape = controlShape
                ) { Text("Aplicar") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchTagDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showBatchFlagDialog && batchSetFlag != null) {
        FlagPickerDialog(
            title = "Definir bandeira (${selectedIds.size} cartões)",
            currentFlag = -1,
            onDismiss = { showBatchFlagDialog = false },
            onSelectFlag = { flag ->
                showBatchFlagDialog = false
                runBatch("Bandeira atualizada.") { ids -> batchSetFlag(ids, flag) }
            }
        )
    }

    singleCardFlagTarget?.let { target ->
        if (onSetCardFlag != null) {
            FlagPickerDialog(
                title = "Bandeira do cartão",
                currentFlag = target.flag,
                onDismiss = { singleCardFlagTarget = null },
                onSelectFlag = { flag ->
                    singleCardFlagTarget = null
                    pageScope.launch {
                        runCatching { onSetCardFlag(target.id, flag) }
                            .onFailure { Toast.makeText(context, "Falha ao atualizar bandeira.", Toast.LENGTH_SHORT).show() }
                    }
                }
            )
        }
    }

    if (showBatchRescheduleDialog && batchReschedule != null) {
        var daysText by remember { mutableStateOf("0") }
        val daysInt = daysText.toIntOrNull()
        AlertDialog(
            onDismissRequest = { showBatchRescheduleDialog = false },
            modifier = Modifier.imePadding(),
            title = { Text("Reescalonar ${selectedIds.size} cartões") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Defina em quantos dias a partir de hoje os cartões selecionados vencerão (0 = hoje).", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0 to "Hoje (0d)", 1 to "+1d", 3 to "+3d", 7 to "+7d", 14 to "+14d", 30 to "+30d").forEach { (d, lbl) ->
                            FilterChip(
                                selected = daysInt == d,
                                onClick = { daysText = d.toString() },
                                label = { Text(lbl) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { if (it.all(Char::isDigit)) daysText = it },
                        label = { Text("Dias a partir de hoje") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = controlShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = daysInt != null && daysInt in 0..3650,
                    onClick = {
                        val d = daysInt ?: 0
                        showBatchRescheduleDialog = false
                        runBatch("Cartões reescalonados para +${d}d.") { ids -> batchReschedule(ids, d) }
                    },
                    shape = controlShape
                ) { Text("Reescalonar") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchRescheduleDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showBatchResetConfirm && batchResetCards != null) {
        AlertDialog(
            onDismissRequest = { showBatchResetConfirm = false },
            title = { Text("Resetar progresso de ${selectedIds.size} cartões?") },
            text = { Text("Os cartões voltarão para o estado Novo, zerando intervalo e repetições atuais.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchResetConfirm = false
                        runBatch("Progresso resetado para Novo.") { ids -> batchResetCards(ids) }
                    }
                ) { Text("Resetar", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchResetConfirm = false }) { Text("Cancelar") }
            }
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("Excluir ${selectedIds.size} cartões?") },
            text = { Text("Os cartões selecionados serão removidos permanentemente da coleção.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchDeleteConfirm = false
                        runBatch("Cartões excluídos.") { ids ->
                            if (batchDeleteCards != null) {
                                batchDeleteCards(ids)
                            } else {
                                ids.forEach { deleteCard(it) }
                            }
                        }
                    }
                ) { Text("Excluir", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun AnkiDeckCountersBadge(
    counters: DeckCounters,
    newColor: Color,
    learningColor: Color,
    reviewColor: Color
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            counters.newCount.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (counters.newCount > 0) newColor else newColor.copy(alpha = 0.42f)
        )
        Text(
            counters.learningCount.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (counters.learningCount > 0) learningColor else learningColor.copy(alpha = 0.42f)
        )
        Text(
            counters.reviewCount.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (counters.reviewCount > 0) reviewColor else reviewColor.copy(alpha = 0.42f)
        )
    }
}

@Composable
private fun DeckTreeItemRow(
    title: String,
    depth: Int,
    selected: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    isFiltered: Boolean,
    isFsrs: Boolean,
    counters: DeckCounters,
    newColor: Color,
    learningColor: Color,
    reviewColor: Color,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onStudy: () -> Unit,
    onConfigure: (() -> Unit)?,
    onFilter: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = studyControlShape()
    val indent = (depth * 14).dp.coerceAtMost(56.dp)
    val totalDue = counters.newCount + counters.learningCount + counters.reviewCount

    Surface(
        onClick = onSelect,
        shape = shape,
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(start = indent, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (hasChildren) {
                    IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                            contentDescription = if (expanded) "Recolher subbaralhos" else "Expandir subbaralhos",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(28.dp))
                }
                if (isFiltered) {
                    Icon(Icons.Default.FilterAlt, null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isFsrs) {
                    Surface(
                        shape = shape,
                        color = scheme.tertiaryContainer,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            "FSRS",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                AnkiDeckCountersBadge(counters, newColor, learningColor, reviewColor)
            }
            if (selected) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 28.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(
                        onClick = onStudy,
                        enabled = totalDue > 0 || isFiltered,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Estudar", style = MaterialTheme.typography.labelSmall)
                    }
                    if (onConfigure != null) {
                        TextButton(
                            onClick = onConfigure,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Tune, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Configurações", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TextButton(
                        onClick = onFilter,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.FilterAlt, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Filtrar", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun FlagPickerDialog(
    title: String,
    currentFlag: Int,
    onDismiss: () -> Unit,
    onSelectFlag: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                (0..7).forEach { flag ->
                    ListItem(
                        headlineContent = { Text(ankiFlagName(flag)) },
                        leadingContent = {
                            if (flag == 0) {
                                Icon(Icons.Outlined.Flag, null)
                            } else {
                                Icon(Icons.Default.Flag, null, tint = ankiFlagColor(flag))
                            }
                        },
                        trailingContent = {
                            if (currentFlag == flag) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.combinedClickable(onClick = { onSelectFlag(flag) })
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun CustomStudyFilteredDialogPro(
    allCards: List<Flashcard>,
    libraryCards: List<Flashcard>,
    decks: List<CardDeck>,
    now: Long,
    zone: ZoneId,
    hasOpenRound: Boolean,
    initialDsl: String,
    allTags: List<String>,
    canCreatePersistentDeck: Boolean,
    onDismiss: () -> Unit,
    onStartSession: (List<Flashcard>, Boolean) -> Unit,
    onCreatePersistentDeck: suspend (String, String, Int) -> Unit
) {
    var mode by rememberSaveable { mutableIntStateOf(if (initialDsl.isNotBlank()) 5 else 0) }
    var useWholeCollection by rememberSaveable { mutableStateOf(initialDsl.contains("deck:", ignoreCase = true)) }
    var aheadDays by rememberSaveable { mutableIntStateOf(7) }
    var recentDays by rememberSaveable { mutableIntStateOf(3) }
    var limit by rememberSaveable { mutableIntStateOf(25) }
    var sortMode by rememberSaveable { mutableIntStateOf(0) } // 0 = Embaralhado, 1 = Difíceis, 2 = Vencimento, 3 = Mais recentes
    var cramMode by rememberSaveable { mutableStateOf(true) }
    var dslQuery by rememberSaveable { mutableStateOf(initialDsl) }
    var saveAsDeck by rememberSaveable { mutableStateOf(false) }
    var filteredDeckName by rememberSaveable { mutableStateOf("Estudo Filtrado") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val controlShape = studyControlShape()

    val effectiveScope = remember(useWholeCollection, allCards, libraryCards) {
        (if (useWholeCollection) allCards else libraryCards).filterNot { it.suspended }
    }

    val matchingCards = remember(effectiveScope, decks, mode, aheadDays, recentDays, sortMode, dslQuery, now, zone) {
        val presetFiltered = when (mode) {
            0 -> effectiveScope
            1 -> effectiveScope.filter { it.lapses > 0 || it.easeFactor < 2.25 || it.tags.contains("sanguessuga", ignoreCase = true) }
            2 -> {
                val horizon = now + aheadDays * 86_400_000L
                effectiveScope.filter { it.reviewState != "NEW" && it.dueAt <= horizon }
            }
            3 -> effectiveScope.filter { it.reviewState == "NEW" || it.reviewState == "LEARNING" || it.reviewState == "RELEARNING" }
            4 -> {
                val cutoff = now - recentDays * 86_400_000L
                effectiveScope.filter { it.lastReviewAt >= cutoff || it.dueAt >= cutoff }
            }
            else -> effectiveScope
        }
        val dslFiltered = if (dslQuery.isBlank()) presetFiltered else {
            CardQueryEngine.filter(presetFiltered, dslQuery, decks, now, zone)
        }
        when (sortMode) {
            0 -> dslFiltered.shuffled()
            1 -> dslFiltered.sortedWith(compareByDescending<Flashcard> { it.lapses }.thenBy { it.easeFactor })
            2 -> dslFiltered.sortedBy { it.dueAt }
            else -> dslFiltered.sortedByDescending { it.id }
        }
    }

    val finalBatch = remember(matchingCards, limit) {
        if (limit <= 0) matchingCards else matchingCards.take(limit)
    }

    val effectiveSavedQuery = remember(mode, aheadDays, dslQuery) {
        val trimmed = dslQuery.trim()
        when (mode) {
            1 -> listOf("is:leech", trimmed).filter { it.isNotBlank() }.joinToString(" ")
            2 -> listOf("-is:new prop:due<=$aheadDays", trimmed).filter { it.isNotBlank() }.joinToString(" ")
            3 -> listOf("(is:new OR is:learn)", trimmed).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "is:new" }
            else -> trimmed.ifBlank { "is:due" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.imePadding(),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                Text("Baralho Filtrado / Estudo Livre", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (hasOpenRound) {
                    Text(
                        "Obs.: há uma rodada em aberto; iniciar uma sessão substituirá a rodada atual.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = !useWholeCollection,
                        onClick = { useWholeCollection = false },
                        label = { Text("Seleção atual") }
                    )
                    FilterChip(
                        selected = useWholeCollection,
                        onClick = { useWholeCollection = true },
                        label = { Text("Toda a coleção") }
                    )
                }

                Text("Predefinição", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                val modes = listOf(
                    0 to "🎯 Véspera de Prova (todos os cartões)",
                    1 to "🔥 Difíceis & Sanguessugas",
                    2 to "⏩ Adiantar revisões futuras",
                    3 to "🌱 Novos & Em Aprendizagem",
                    4 to "🕒 Mais recentes",
                    5 to "🔎 Query DSL personalizada"
                )
                modes.forEach { (idx, label) ->
                    val selected = mode == idx
                    Surface(
                        onClick = { mode = idx },
                        shape = controlShape,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = { mode = idx })
                            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }

                if (mode == 2) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Horizonte:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                        listOf(3, 7, 14, 30).forEach { d ->
                            FilterChip(selected = aheadDays == d, onClick = { aheadDays = d }, label = { Text("$d dias") })
                        }
                    }
                } else if (mode == 4) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Janela:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                        listOf(1, 3, 7, 14).forEach { d ->
                            FilterChip(selected = recentDays == d, onClick = { recentDays = d }, label = { Text("${d}d") })
                        }
                    }
                }

                OutlinedTextField(
                    value = dslQuery,
                    onValueChange = { dslQuery = it },
                    label = { Text("Filtro Query DSL (opcional)") },
                    placeholder = { Text("tag:anatomia is:due flag:1 -is:suspended") },
                    singleLine = true,
                    shape = controlShape,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("is:due", "is:leech", "flag:1", "is:new", "prop:ivl>=7").forEach { token ->
                        AssistChip(
                            onClick = { dslQuery = toggleQueryToken(dslQuery, token) },
                            label = { Text(token, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    allTags.take(5).forEach { t ->
                        AssistChip(
                            onClick = { dslQuery = toggleQueryToken(dslQuery, "tag:$t") },
                            label = { Text("#$t", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("Limite e Ordem", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(10 to "10", 25 to "25", 50 to "50", 100 to "100", 0 to "Todos").forEach { (n, label) ->
                        FilterChip(selected = limit == n, onClick = { limit = n }, label = { Text(label) })
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(0 to "Embaralhado", 1 to "Mais difíceis", 2 to "Vencimento", 3 to "Mais recentes").forEach { (s, label) ->
                        FilterChip(selected = sortMode == s, onClick = { sortMode = s }, label = { Text(label) })
                    }
                }

                Text("Modo de agendamento", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = cramMode,
                        onClick = { cramMode = true },
                        label = { Text("Cram (não altera intervalos)") }
                    )
                    FilterChip(
                        selected = !cramMode,
                        onClick = { cramMode = false },
                        label = { Text("Reagendar normalmente") }
                    )
                }

                if (canCreatePersistentDeck) {
                    Surface(
                        shape = controlShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Salvar como baralho filtrado permanente",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(checked = saveAsDeck, onCheckedChange = { saveAsDeck = it })
                            }
                            if (saveAsDeck) {
                                OutlinedTextField(
                                    value = filteredDeckName,
                                    onValueChange = { filteredDeckName = it },
                                    label = { Text("Nome do baralho filtrado") },
                                    singleLine = true,
                                    shape = controlShape,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Surface(
                    shape = controlShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "${finalBatch.size} de ${matchingCards.size} cartões selecionados",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (saveAsDeck && canCreatePersistentDeck) {
                Button(
                    enabled = filteredDeckName.trim().isNotEmpty() && !busy,
                    shape = controlShape,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                onCreatePersistentDeck(filteredDeckName.trim(), effectiveSavedQuery, if (limit <= 0) 200 else limit)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.localizedMessage ?: "Não foi possível salvar o baralho filtrado."
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) {
                    Text(if (busy) "Criando…" else "Salvar baralho filtrado")
                }
            } else {
                Button(
                    onClick = { onStartSession(finalBatch, cramMode) },
                    enabled = finalBatch.isNotEmpty() && !busy,
                    shape = controlShape
                ) {
                    Text(
                        if (cramMode) "Estudar ${finalBatch.size} (Cram)" else "Revisar ${finalBatch.size}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
