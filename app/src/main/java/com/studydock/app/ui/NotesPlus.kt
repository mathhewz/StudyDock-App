@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.studydock.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

internal val inlineRegex = Regex(
    """\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|__(.+?)__|(?<!\*)\*(?!\s)(.+?)(?<!\s)\*(?!\*)|(?<![\p{L}\p{N}_])_(?!\s)([^_\n]+?)(?<!\s)_(?![\p{L}\p{N}_])|==(.+?)==|~~(.+?)~~|`(.+?)`|\[([^\]]+?)\]\((https?://[^)\s]+)\)|(?<![\p{L}\p{N}_/&#])#([\p{L}][\p{L}\p{N}_/-]*)"""
)
internal val checklistRegex = Regex("""^(\s*)[-*] \[( |x|X)] (.*)$""")
internal val bulletLineRegex = Regex("""^(\s*)[-*+]\s+(.+)$""")
internal val orderedLineRegex = Regex("""^(\s*)(\d+\.)\s+(.+)$""")
private val dailyNoteTitleRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")
internal val calloutStartRegex = Regex("""^>\s*\[!([A-Za-z0-9_-]+)]([+-])?\s*(.*)$""")
internal val tableSeparatorRegex = Regex("""^\|?(\s*:?-{3,}:?\s*\|)+\s*:?-{3,}:?\s*\|?$""")
internal val tagAutocompleteRegex = Regex("""(?:^|\s)#([\p{L}\p{N}_/-]*)$""")
internal val slashAutocompleteRegex = Regex("""(?:^|\s)(/[a-zA-Z0-9_-]*)$""")
internal val fenceMarkerRegex = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
internal val headingLineRegex = Regex("""^(#{1,6})\s+(.+)$""")

private val isoDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val shortDatePtFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale("pt", "BR"))
private val draftDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM · HH:mm")
internal val snapshotFullFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm")
internal val snapshotShortFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")

internal fun formatEpochMillis(millis: Long, formatter: DateTimeFormatter): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(formatter)

internal object NoteJumpTargets {
    private data class Entry(val target: String, val createdAt: Long = System.currentTimeMillis())
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, Entry>()
    private const val TTL_MS = 15_000L

    fun set(noteId: Long, headingOrBlock: String) {
        pending[noteId] = Entry(headingOrBlock)
    }

    fun consume(noteId: Long): String? {
        val entry = pending.remove(noteId) ?: return null
        return if (System.currentTimeMillis() - entry.createdAt <= TTL_MS) entry.target else null
    }
}

/** #tags usadas no texto da nota e no frontmatter (sem repetir, em minúsculas). */
fun noteTags(markdown: String): List<String> = extractNoteTags(markdown)

// ---------- obsidian vault workspace ----------

private enum class VaultView(val defaultTitle: String) {
    NOTES("Notas"),
    CALENDAR("Calendário Diário"),
    DRAFTS("Rascunhos"),
    TRASH("Lixeira"),
    GRAPH("Grafo"),
    CANVAS("Canvas")
}

private enum class VaultSortOrder(val label: String) {
    RECENT("Mais recentes"),
    TITLE("Título (A–Z)"),
    LINKS("Mais conexões")
}

private data class NoteItemMeta(
    val note: Note,
    val folder: String,
    val aliases: List<String>,
    val tags: List<String>,
    val previewLine: String,
    val outgoingCount: Int,
    val incomingCount: Int,
    val openTaskCount: Int,
    val updatedLabel: String
)

private data class VaultDerivedIndex(
    val activeNotes: List<Note>,
    val items: List<NoteItemMeta>,
    val metaById: Map<Long, NoteItemMeta>,
    val tagsByNote: Map<Long, List<String>>,
    val folderTree: List<FolderTreeNode>,
    val tagTree: List<TagTreeNode>,
    val unfiledCount: Int,
    val pinnedCount: Int,
    val totalLinks: Int,
    val folderCount: Int
)

private fun fastPlainPreview(markdownBody: String): String {
    var fallbackHeading = ""
    for (raw in markdownBody.lineSequence()) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "---" || trimmed.startsWith("```") || trimmed.startsWith("~~~")) continue
        val cleaned = trimmed
            .removePrefix("- [ ] ")
            .removePrefix("- [x] ")
            .removePrefix("- [X] ")
            .removePrefix("> ")
            .trimStart('#', '-', '*', ' ')
            .replace("**", "")
            .replace("==", "")
            .replace("~~", "")
            .replace("`", "")
            .replace("[[", "")
            .replace("]]", "")
            .trim()
        if (cleaned.isEmpty()) continue
        if (trimmed.startsWith("#")) {
            if (fallbackHeading.isEmpty()) fallbackHeading = cleaned
            continue
        }
        return cleaned.take(140)
    }
    return fallbackHeading.take(140).ifBlank { "Nota sem conteúdo" }
}

private fun buildVaultDerivedIndex(
    notes: List<Note>,
    tasks: List<StudyTask>,
    links: List<NoteLink>
): VaultDerivedIndex {
    val activeNotes = notes.filter { it.trashedAt == null }
    val openTasksByNote = HashMap<Long, Int>()
    for (task in tasks) {
        val noteId = task.noteId
        if (!task.completed && noteId != null) {
            openTasksByNote[noteId] = (openTasksByNote[noteId] ?: 0) + 1
        }
    }
    val incomingByTarget = HashMap<Long, Int>()
    for (link in links) {
        incomingByTarget[link.targetId] = (incomingByTarget[link.targetId] ?: 0) + 1
    }

    val distinctFolders = HashSet<String>()
    val items = ArrayList<NoteItemMeta>(activeNotes.size)
    val metaById = HashMap<Long, NoteItemMeta>(activeNotes.size)
    val tagsByNote = HashMap<Long, List<String>>(activeNotes.size)
    var unfiledCount = 0
    var pinnedCount = 0
    var totalLinks = 0

    for (note in activeNotes) {
        val props = NoteFrontmatter.properties(note.markdown)
        val folder = props.folder?.trim().orEmpty()
        if (folder.isEmpty()) {
            unfiledCount++
        } else {
            distinctFolders.add(folder.lowercase(Locale.ROOT))
        }
        if (note.pinned) pinnedCount++
        val tags = extractNoteTags(note.markdown)
        val outgoingCount = NoteReferences.parse(note.markdown).size
        totalLinks += outgoingCount
        val bodyOnly = NoteFrontmatter.body(note.markdown)
        val meta = NoteItemMeta(
            note = note,
            folder = folder,
            aliases = props.aliases,
            tags = tags,
            previewLine = fastPlainPreview(bodyOnly),
            outgoingCount = outgoingCount,
            incomingCount = incomingByTarget[note.id] ?: 0,
            openTaskCount = openTasksByNote[note.id] ?: 0,
            updatedLabel = formatEpochMillis(note.updatedAt, shortDatePtFormatter)
        )
        items.add(meta)
        metaById[note.id] = meta
        tagsByNote[note.id] = tags
    }

    val folderTree = buildFolderTree(activeNotes)
    val tagTree = buildTagTree(activeNotes)
    return VaultDerivedIndex(
        activeNotes = activeNotes,
        items = items,
        metaById = metaById,
        tagsByNote = tagsByNote,
        folderTree = folderTree,
        tagTree = tagTree,
        unfiledCount = unfiledCount,
        pinnedCount = pinnedCount,
        totalLinks = totalLinks,
        folderCount = distinctFolders.size
    )
}

private fun filterAndSortNotes(
    index: VaultDerivedIndex,
    query: String,
    activeTag: String?,
    activeFolder: String?,
    pinnedOnly: Boolean,
    sortOrder: VaultSortOrder
): List<NoteItemMeta> {
    val baseItems = if (query.isBlank()) {
        index.items
    } else {
        ObsidianSearchEngine.search(index.activeNotes, query).mapNotNull { index.metaById[it.id] }
    }
    val filtered = baseItems.filter { item ->
        if (pinnedOnly && !item.note.pinned) return@filter false
        val matchesFolder = when {
            activeFolder == null -> true
            activeFolder == "__UNFILED__" -> item.folder.isEmpty()
            else -> item.folder.equals(activeFolder, ignoreCase = true) ||
                item.folder.startsWith("$activeFolder/", ignoreCase = true)
        }
        val matchesTag = activeTag == null || item.tags.any { t ->
            t.equals(activeTag, ignoreCase = true) || t.startsWith("$activeTag/", ignoreCase = true)
        }
        matchesFolder && matchesTag
    }
    val comparator = when (sortOrder) {
        VaultSortOrder.RECENT -> compareByDescending<NoteItemMeta> { it.note.pinned }
            .thenByDescending { it.note.updatedAt }
        VaultSortOrder.TITLE -> compareByDescending<NoteItemMeta> { it.note.pinned }
            .thenBy { it.note.title.lowercase(Locale.ROOT) }
        VaultSortOrder.LINKS -> compareByDescending<NoteItemMeta> { it.note.pinned }
            .thenByDescending { it.incomingCount + it.outgoingCount }
            .thenByDescending { it.note.updatedAt }
    }
    return filtered.sortedWith(comparator)
}

@Composable
fun NotesListPage(
    notes: List<Note>, tasks: List<StudyTask>, drafts: List<NoteDraft>, trash: List<Note>, links: List<NoteLink>,
    onCreate: () -> Unit, onOpen: (Long) -> Unit, onDaily: () -> Unit, onTogglePin: (Note) -> Unit,
    onDraft: (String) -> Unit, onDiscardDraft: (NoteDraft) -> Unit, onRestore: (Long) -> Unit,
    onCreateWithTemplate: ((NoteTemplate) -> Unit)? = null,
    onPurgeNote: ((Long) -> Unit)? = null,
    onEmptyTrash: (() -> Unit)? = null,
    onExportVaultZip: (() -> Unit)? = null,
    onImportVaultZip: (() -> Unit)? = null,
    canvasBoards: List<CanvasBoard> = emptyList(),
    onSaveCanvasBoard: (suspend (CanvasBoard) -> Long)? = null,
    onDeleteCanvasBoard: (suspend (Long) -> Unit)? = null,
    onCreateOrOpenTitle: (suspend (String) -> Long)? = null,
    onExitWorkspace: (() -> Unit)? = null,
    requestedCanvasBoardId: Long? = null,
    onRequestedCanvasBoardConsumed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val openGlobalSearch = LocalGlobalSearchWithQueryOpener.current

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var vaultView by rememberSaveable { mutableStateOf(VaultView.NOTES) }
    LaunchedEffect(requestedCanvasBoardId) {
        if (requestedCanvasBoardId != null) {
            vaultView = VaultView.CANVAS
        }
    }
    var sortOrder by rememberSaveable { mutableStateOf(VaultSortOrder.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var showSearchHelp by rememberSaveable { mutableStateOf(false) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var pinnedOnly by rememberSaveable { mutableStateOf(false) }

    var discard by remember { mutableStateOf<NoteDraft?>(null) }
    var purgeTarget by remember { mutableStateOf<Note?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showNewNoteSheet by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var contextMenuItem by remember { mutableStateOf<NoteItemMeta?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Heavy frontmatter/tag/tree indexing runs off the main thread.
    var derivedIndex by remember { mutableStateOf<VaultDerivedIndex?>(null) }
    LaunchedEffect(notes, tasks, links) {
        derivedIndex = withContext(Dispatchers.Default) {
            buildVaultDerivedIndex(notes, tasks, links)
        }
    }

    var debouncedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            debouncedQuery = ""
        } else {
            delay(110)
            debouncedQuery = query
        }
    }

    var visibleItems by remember { mutableStateOf<List<NoteItemMeta>>(emptyList()) }
    LaunchedEffect(derivedIndex, debouncedQuery, tagFilter, folderFilter, pinnedOnly, sortOrder) {
        val idx = derivedIndex ?: return@LaunchedEffect
        visibleItems = withContext(Dispatchers.Default) {
            filterAndSortNotes(idx, debouncedQuery, tagFilter, folderFilter, pinnedOnly, sortOrder)
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(60)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    BackHandler(enabled = drawerState.isOpen || vaultView != VaultView.NOTES || searchOpen || query.isNotEmpty()) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            vaultView != VaultView.NOTES -> vaultView = VaultView.NOTES
            searchOpen || query.isNotEmpty() -> {
                query = ""
                searchOpen = false
                showSearchHelp = false
            }
        }
    }

    val openOrCreateDailyIso: (String) -> Unit = { dateIso ->
        val todayIso = LocalDate.now().format(isoDateFormatter)
        val existing = notes.firstOrNull { it.trashedAt == null && it.title.trim() == dateIso }
        when {
            existing != null -> onOpen(existing.id)
            onCreateOrOpenTitle != null -> {
                scope.launch {
                    val id = onCreateOrOpenTitle(dateIso)
                    if (id > 0L) onOpen(id)
                }
            }
            dateIso == todayIso -> onDaily()
            onCreateWithTemplate != null -> {
                onCreateWithTemplate(
                    NoteTemplate(
                        id = "daily_$dateIso",
                        title = "Nota Diária $dateIso",
                        description = "Registro diário",
                        defaultTitle = dateIso,
                        initialMarkdown = "# $dateIso\n\n## Foco do dia\n- [ ] \n\n## O que estudei\n\n## Anotações\n\n#diario"
                    )
                )
            }
            else -> {
                Toast.makeText(context, "Nota diária de $dateIso ainda não criada.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = vaultView != VaultView.GRAPH && vaultView != VaultView.CANVAS,
        drawerContent = {
            VaultLeftDrawerContent(
                index = derivedIndex,
                notesCount = notes.count { it.trashedAt == null },
                draftsCount = drafts.size,
                trashCount = trash.size,
                activeView = vaultView,
                selectedFolder = folderFilter,
                selectedTag = tagFilter,
                pinnedOnly = pinnedOnly,
                onExitWorkspace = {
                    scope.launch { drawerState.close() }
                    onExitWorkspace?.invoke() ?: backDispatcher?.onBackPressed()
                },
                onSelectAllNotes = {
                    pinnedOnly = false
                    folderFilter = null
                    tagFilter = null
                    vaultView = VaultView.NOTES
                    scope.launch { drawerState.close() }
                },
                onSelectPinned = {
                    pinnedOnly = true
                    folderFilter = null
                    tagFilter = null
                    vaultView = VaultView.NOTES
                    scope.launch { drawerState.close() }
                },
                onOpenTodayDaily = {
                    scope.launch { drawerState.close() }
                    onDaily()
                },
                onOpenCalendar = {
                    vaultView = VaultView.CALENDAR
                    scope.launch { drawerState.close() }
                },
                onSelectFolder = { path ->
                    pinnedOnly = false
                    folderFilter = if (folderFilter == path) null else path
                    vaultView = VaultView.NOTES
                    scope.launch { drawerState.close() }
                },
                onSelectTag = { tag ->
                    pinnedOnly = false
                    tagFilter = if (tagFilter == tag) null else tag
                    vaultView = VaultView.NOTES
                    scope.launch { drawerState.close() }
                },
                onNewFolder = {
                    scope.launch { drawerState.close() }
                    showNewFolderDialog = true
                },
                onOpenDrafts = {
                    vaultView = VaultView.DRAFTS
                    scope.launch { drawerState.close() }
                },
                onOpenTrash = {
                    vaultView = VaultView.TRASH
                    scope.launch { drawerState.close() }
                },
                onOpenTemplates = {
                    scope.launch { drawerState.close() }
                    showTemplateDialog = true
                },
                onExportVaultZip = onExportVaultZip?.let { export ->
                    {
                        scope.launch { drawerState.close() }
                        export()
                    }
                },
                onImportVaultZip = onImportVaultZip?.let { imp ->
                    {
                        scope.launch { drawerState.close() }
                        imp()
                    }
                }
            )
        }
    ) {
        when (vaultView) {
            VaultView.GRAPH -> {
                KnowledgeGraph(
                    notes = notes,
                    bindings = links,
                    onOpen = onOpen,
                    modifier = Modifier.fillMaxSize(),
                    onCreateMissingNote = onCreateOrOpenTitle?.let { creator ->
                        { missingTitle ->
                            scope.launch {
                                val id = creator(missingTitle)
                                if (id > 0L) onOpen(id)
                            }
                        }
                    },
                    onBack = { vaultView = VaultView.NOTES },
                    onCreateNote = { showNewNoteSheet = true },
                    showHeader = true
                )
            }

            VaultView.CANVAS -> {
                CanvasBoardWorkspace(
                    boards = canvasBoards,
                    notes = notes,
                    onSaveBoard = onSaveCanvasBoard ?: { 0L },
                    onDeleteBoard = onDeleteCanvasBoard ?: {},
                    onOpenNote = onOpen,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { vaultView = VaultView.NOTES },
                    onCreateNote = onCreate,
                    showHeader = true,
                    requestedBoardId = requestedCanvasBoardId,
                    onRequestedBoardConsumed = onRequestedCanvasBoardConsumed
                )
            }

            VaultView.NOTES, VaultView.CALENDAR, VaultView.DRAFTS, VaultView.TRASH -> {
                val activeTitle = when (vaultView) {
                    VaultView.NOTES -> when {
                        folderFilter == "__UNFILED__" -> "Sem pasta"
                        folderFilter != null -> folderFilter!!.substringAfterLast('/').ifBlank { folderFilter!! }
                        tagFilter != null -> "#$tagFilter"
                        pinnedOnly -> "Fixadas"
                        else -> "Notas"
                    }
                    VaultView.CALENDAR -> "Calendário Diário"
                    VaultView.DRAFTS -> if (drafts.isEmpty()) "Rascunhos" else "Rascunhos (${drafts.size})"
                    VaultView.TRASH -> if (trash.isEmpty()) "Lixeira" else "Lixeira (${trash.size})"
                    else -> "Notas"
                }

                val hasActiveFilter = vaultView == VaultView.NOTES &&
                    (folderFilter != null || tagFilter != null || pinnedOnly || debouncedQuery.isNotBlank())
                val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Column(Modifier.fillMaxSize().imePadding()) {
                        VaultMinimalTopBar(
                            title = activeTitle,
                            isSubView = vaultView != VaultView.NOTES,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onReturnToNotes = { vaultView = VaultView.NOTES },
                            overflowExpanded = showOverflowMenu,
                            onToggleOverflow = { showOverflowMenu = it },
                            overflowContent = {
                                if (vaultView == VaultView.TRASH && trash.isNotEmpty() && onEmptyTrash != null) {
                                    DropdownMenuItem(
                                        text = { Text("Esvaziar lixeira", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showOverflowMenu = false
                                            confirmEmptyTrash = true
                                        }
                                    )
                                    HorizontalDivider(thickness = 0.5.dp)
                                }
                                if (vaultView == VaultView.NOTES) {
                                    VaultSortOrder.entries.forEach { order ->
                                        DropdownMenuItem(
                                            text = { Text(order.label) },
                                            leadingIcon = {
                                                Icon(
                                                    if (sortOrder == order) Icons.Default.Check else Icons.Default.SwapVert,
                                                    null
                                                )
                                            },
                                            onClick = {
                                                sortOrder = order
                                                showOverflowMenu = false
                                            }
                                        )
                                    }
                                    HorizontalDivider(thickness = 0.5.dp)
                                }
                                DropdownMenuItem(
                                    text = { Text("Nova pasta") },
                                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showNewFolderDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Nota diária de hoje") },
                                    leadingIcon = { Icon(Icons.Default.Today, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onDaily()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Criar a partir de modelo") },
                                    leadingIcon = { Icon(Icons.Default.PostAdd, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showTemplateDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (trash.isEmpty()) "Lixeira" else "Lixeira (${trash.size})") },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        vaultView = VaultView.TRASH
                                    }
                                )
                                if (onExportVaultZip != null) {
                                    DropdownMenuItem(
                                        text = { Text("Exportar Vault (.zip)") },
                                        leadingIcon = { Icon(Icons.Default.FolderZip, null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            onExportVaultZip()
                                        }
                                    )
                                }
                                if (onImportVaultZip != null) {
                                    DropdownMenuItem(
                                        text = { Text("Importar Vault (.zip)") },
                                        leadingIcon = { Icon(Icons.Default.UploadFile, null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            onImportVaultZip()
                                        }
                                    )
                                }
                            }
                        )

                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )

                        if (derivedIndex == null && notes.isNotEmpty()) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                        }

                        AnimatedVisibility(
                            visible = (searchOpen || query.isNotEmpty()) && vaultView != VaultView.CALENDAR,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            VaultSearchCapsule(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = if (vaultView == VaultView.NOTES) {
                                    "Buscar notas (tag:, path:, \"…\", /regex/, -)"
                                } else {
                                    "Filtrar itens…"
                                },
                                showHelpToggle = vaultView == VaultView.NOTES,
                                showHelp = showSearchHelp,
                                onToggleHelp = { showSearchHelp = !showSearchHelp },
                                onOpenGlobalSearch = openGlobalSearch?.let { open ->
                                    { open(query) }
                                },
                                onClose = {
                                    query = ""
                                    searchOpen = false
                                    showSearchHelp = false
                                },
                                focusRequester = searchFocusRequester
                            )
                        }

                        AnimatedVisibility(
                            visible = hasActiveFilter,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            ActiveVaultFilterStrip(
                                resultCount = visibleItems.size,
                                folderFilter = folderFilter,
                                tagFilter = tagFilter,
                                pinnedOnly = pinnedOnly,
                                query = debouncedQuery,
                                onClearFolder = { folderFilter = null },
                                onClearTag = { tagFilter = null },
                                onClearPinned = { pinnedOnly = false },
                                onClearQuery = { query = "" }
                            )
                        }

                        when (vaultView) {
                            VaultView.NOTES -> {
                                if (derivedIndex != null && visibleItems.isEmpty()) {
                                    VaultEmptyPlaceholder(
                                        message = if (notes.none { it.trashedAt == null }) {
                                            "Crie sua primeira nota e conecte ideias com [[nome da nota]]."
                                        } else {
                                            "Nenhuma nota combina com esse filtro."
                                        },
                                        actionLabel = if (hasActiveFilter) "Limpar filtros" else "Nova nota",
                                        onAction = {
                                            if (hasActiveFilter) {
                                                folderFilter = null
                                                tagFilter = null
                                                pinnedOnly = false
                                                query = ""
                                            } else {
                                                onCreate()
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        contentPadding = PaddingValues(top = 4.dp, bottom = 104.dp)
                                    ) {
                                        items(
                                            items = visibleItems,
                                            key = { it.note.id },
                                            contentType = { "note_row" }
                                        ) { item ->
                                            ObsidianNoteRow(
                                                item = item,
                                                onClick = { onOpen(item.note.id) },
                                                onLongClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    contextMenuItem = item
                                                }
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                            )
                                        }
                                    }
                                }
                            }

                            VaultView.CALENDAR -> {
                                DailyNotesCalendarTab(
                                    notes = notes,
                                    metaById = derivedIndex?.metaById.orEmpty(),
                                    tagsByNote = derivedIndex?.tagsByNote.orEmpty(),
                                    onOpenNote = onOpen,
                                    onLongClickNote = { meta ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextMenuItem = meta
                                    },
                                    onOpenOrCreateDaily = openOrCreateDailyIso,
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                            }

                            VaultView.DRAFTS -> {
                                val matchingDrafts = remember(drafts, debouncedQuery) {
                                    if (debouncedQuery.isBlank()) drafts
                                    else drafts.filter {
                                        it.title.contains(debouncedQuery, true) ||
                                            it.markdown.contains(debouncedQuery, true)
                                    }
                                }
                                if (matchingDrafts.isEmpty()) {
                                    VaultEmptyPlaceholder(
                                        message = "Nenhum rascunho pendente.",
                                        actionLabel = "Voltar às notas",
                                        onAction = { vaultView = VaultView.NOTES },
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        contentPadding = PaddingValues(top = 4.dp, bottom = 104.dp)
                                    ) {
                                        items(
                                            items = matchingDrafts,
                                            key = { it.key },
                                            contentType = { "draft_row" }
                                        ) { draft ->
                                            VaultDraftRow(
                                                draft = draft,
                                                onOpen = { onDraft(draft.key) },
                                                onDiscard = { discard = draft }
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                            )
                                        }
                                    }
                                }
                            }

                            VaultView.TRASH -> {
                                val matchingTrash = remember(trash, debouncedQuery) {
                                    if (debouncedQuery.isBlank()) trash
                                    else trash.filter {
                                        it.title.contains(debouncedQuery, true) ||
                                            it.markdown.contains(debouncedQuery, true)
                                    }
                                }
                                if (trash.isNotEmpty() && onEmptyTrash != null) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${trash.size} item(ns) na lixeira",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        TextButton(
                                            onClick = { confirmEmptyTrash = true },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Esvaziar lixeira")
                                        }
                                    }
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                }
                                if (matchingTrash.isEmpty()) {
                                    VaultEmptyPlaceholder(
                                        message = "Nenhuma nota na lixeira.",
                                        actionLabel = "Voltar às notas",
                                        onAction = { vaultView = VaultView.NOTES },
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        contentPadding = PaddingValues(top = 4.dp, bottom = 104.dp)
                                    ) {
                                        items(
                                            items = matchingTrash,
                                            key = { it.id },
                                            contentType = { "trash_row" }
                                        ) { trashedNote ->
                                            VaultTrashRow(
                                                note = trashedNote,
                                                onRestore = { onRestore(trashedNote.id) },
                                                onPurge = onPurgeNote?.let { { purgeTarget = trashedNote } }
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                            )
                                        }
                                    }
                                }
                            }

                            else -> Unit
                        }
                    }

                    if (!imeOpen) {
                        VaultFloatingBottomPill(
                            searchActive = searchOpen || query.isNotEmpty(),
                            onSearchClick = {
                                if (vaultView == VaultView.CALENDAR) vaultView = VaultView.NOTES
                                searchOpen = !searchOpen || query.isNotEmpty()
                            },
                            onGraphClick = { vaultView = VaultView.GRAPH },
                            onCanvasClick = { vaultView = VaultView.CANVAS },
                            onNewNote = onCreate,
                            onNewNoteLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showNewNoteSheet = true
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }

    contextMenuItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { contextMenuItem = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    item.note.title.ifBlank { "Nota sem título" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        SheetActionRow(
                            icon = Icons.Outlined.Description,
                            label = "Abrir nota",
                            onClick = {
                                contextMenuItem = null
                                onOpen(item.note.id)
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SheetActionRow(
                            icon = Icons.Default.PushPin,
                            label = if (item.note.pinned) "Desafixar do topo" else "Fixar no topo",
                            onClick = {
                                contextMenuItem = null
                                onTogglePin(item.note)
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SheetActionRow(
                            icon = Icons.Default.ContentCopy,
                            label = "Copiar link [[${item.note.title}]]",
                            onClick = {
                                clipboard.setText(AnnotatedString("[[${item.note.title}]]"))
                                Toast.makeText(context, "Wikilink copiado.", Toast.LENGTH_SHORT).show()
                                contextMenuItem = null
                            }
                        )
                        if (item.folder.isNotBlank() && onCreateWithTemplate != null) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            SheetActionRow(
                                icon = Icons.Default.CreateNewFolder,
                                label = "Nova nota em \"${item.folder}\"",
                                onClick = {
                                    contextMenuItem = null
                                    onCreateWithTemplate(
                                        NoteTemplate(
                                            id = "folder_${item.folder}",
                                            title = "Nova nota",
                                            description = "Nota na pasta ${item.folder}",
                                            defaultTitle = "",
                                            initialMarkdown = "---\nfolder: ${item.folder}\n---\n\n"
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewNoteSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewNoteSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Criar no Vault",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        SheetActionRow(
                            icon = Icons.Default.EditNote,
                            label = "Nova nota em branco",
                            onClick = {
                                showNewNoteSheet = false
                                onCreate()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SheetActionRow(
                            icon = Icons.Default.Today,
                            label = "Nota diária de hoje",
                            onClick = {
                                showNewNoteSheet = false
                                onDaily()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SheetActionRow(
                            icon = Icons.Default.PostAdd,
                            label = "Criar a partir de modelo",
                            onClick = {
                                showNewNoteSheet = false
                                showTemplateDialog = true
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SheetActionRow(
                            icon = Icons.Default.CreateNewFolder,
                            label = "Nova pasta",
                            onClick = {
                                showNewNoteSheet = false
                                showNewFolderDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        NewVaultFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onCreateFolder = { folderPath, noteTitle ->
                showNewFolderDialog = false
                val cleanFolder = folderPath.trim().trim('/')
                if (cleanFolder.isNotEmpty()) {
                    val resolvedTitle = noteTitle.trim().ifBlank { cleanFolder.substringAfterLast('/') }
                    if (onCreateWithTemplate != null) {
                        onCreateWithTemplate(
                            NoteTemplate(
                                id = "new_folder_$cleanFolder",
                                title = "Nota em $cleanFolder",
                                description = "Nova pasta no Vault",
                                defaultTitle = resolvedTitle,
                                initialMarkdown = "---\nfolder: $cleanFolder\n---\n\n# $resolvedTitle\n\n"
                            )
                        )
                    } else {
                        onCreate()
                        Toast.makeText(context, "Defina `folder: $cleanFolder` em Propriedades.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showTemplateDialog) {
        TemplatePickerDialog(
            notes = notes,
            onDismiss = { showTemplateDialog = false },
            onSelect = { template ->
                showTemplateDialog = false
                val expandedTitle = expandTemplate(template.defaultTitle, template.defaultTitle)
                val expandedBody = expandTemplate(template.initialMarkdown, expandedTitle)
                val resolved = template.copy(defaultTitle = expandedTitle, initialMarkdown = expandedBody)
                onCreateWithTemplate?.invoke(resolved) ?: onCreate()
            }
        )
    }

    discard?.let { draft ->
        AlertDialog(
            onDismissRequest = { discard = null },
            modifier = Modifier.imePadding(),
            title = { Text("Descartar rascunho?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (draft.noteId == null) "O texto deste rascunho será removido."
                        else "A edição em rascunho será removida. A nota publicada continuará disponível."
                    )
                }
            },
            confirmButton = { TextButton({ onDiscardDraft(draft); discard = null }) { Text("Descartar") } },
            dismissButton = { TextButton({ discard = null }) { Text("Cancelar") } }
        )
    }

    purgeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("Excluir permanentemente?") },
            text = { Text("A nota “${target.title}” será removida definitivamente e não poderá ser recuperada.") },
            confirmButton = {
                TextButton(
                    onClick = { onPurgeNote?.invoke(target.id); purgeTarget = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Excluir") }
            },
            dismissButton = { TextButton({ purgeTarget = null }) { Text("Cancelar") } }
        )
    }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("Esvaziar lixeira?") },
            text = { Text("Todas as ${trash.size} notas na lixeira serão excluídas permanentemente.") },
            confirmButton = {
                TextButton(
                    onClick = { onEmptyTrash?.invoke(); confirmEmptyTrash = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Esvaziar") }
            },
            dismissButton = { TextButton({ confirmEmptyTrash = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun VaultMinimalTopBar(
    title: String,
    isSubView: Boolean,
    onOpenDrawer: () -> Unit,
    onReturnToNotes: () -> Unit,
    overflowExpanded: Boolean,
    onToggleOverflow: (Boolean) -> Unit,
    overflowContent: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalIconButton(
            onClick = if (isSubView) onReturnToNotes else onOpenDrawer,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                if (isSubView) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                contentDescription = if (isSubView) "Voltar às notas" else "Abrir menu do Vault"
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Box {
            IconButton(
                onClick = { onToggleOverflow(true) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Opções do Vault")
            }
            DropdownMenu(
                expanded = overflowExpanded,
                onDismissRequest = { onToggleOverflow(false) },
                content = overflowContent
            )
        }
    }
}

@Composable
private fun VaultSearchCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    showHelpToggle: Boolean,
    showHelp: Boolean,
    onToggleHelp: () -> Unit,
    onOpenGlobalSearch: (() -> Unit)?,
    onClose: () -> Unit,
    focusRequester: FocusRequester
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
                if (onOpenGlobalSearch != null) {
                    TextButton(
                        onClick = onOpenGlobalSearch,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Global", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (showHelpToggle) {
                    IconButton(onClick = onToggleHelp, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            "Sintaxe de busca",
                            tint = if (showHelp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, "Limpar busca")
                }
            }
        }

        if (showHelpToggle && showHelp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("tag:", "path:", "file:", "\"frase exata\"", "/regex/", "-excluir").forEach { op ->
                    AssistChip(
                        onClick = { onQueryChange((if (query.isBlank()) "" else "$query ") + op) },
                        label = { Text(op, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveVaultFilterStrip(
    resultCount: Int,
    folderFilter: String?,
    tagFilter: String?,
    pinnedOnly: Boolean,
    query: String,
    onClearFolder: () -> Unit,
    onClearTag: () -> Unit,
    onClearPinned: () -> Unit,
    onClearQuery: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$resultCount resultado(s)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (pinnedOnly) {
            RemovableFilterPill("Fixadas", onClear = onClearPinned)
        }
        if (folderFilter != null) {
            val label = if (folderFilter == "__UNFILED__") "Sem pasta" else folderFilter
            RemovableFilterPill(label, onClear = onClearFolder)
        }
        if (tagFilter != null) {
            RemovableFilterPill("#$tagFilter", onClear = onClearTag)
        }
        if (query.isNotBlank()) {
            RemovableFilterPill("\"$query\"", onClear = onClearQuery)
        }
    }
}

@Composable
private fun RemovableFilterPill(label: String, onClear: () -> Unit) {
    Surface(
        onClick = onClear,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        modifier = Modifier.heightIn(min = 36.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                Icons.Default.Close,
                "Remover filtro $label",
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun VaultFloatingBottomPill(
    searchActive: Boolean,
    onSearchClick: () -> Unit,
    onGraphClick: () -> Unit,
    onCanvasClick: () -> Unit,
    onNewNote: () -> Unit,
    onNewNoteLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onSearchClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Buscar no Vault",
                    tint = if (searchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onGraphClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Hub, contentDescription = "Grafo de conhecimento")
            }
            IconButton(onClick = onCanvasClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Dashboard, contentDescription = "Lousas Canvas")
            }
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .width(0.8.dp)
                    .height(22.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            )
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                    .combinedClickable(
                        onClick = onNewNote,
                        onLongClick = onNewNoteLongPress
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.EditNote,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Nova nota",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ObsidianNoteRow(
    item: NoteItemMeta,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.note.title.ifBlank { "Nota sem título" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.aliases.isNotEmpty()) {
                    Text(
                        text = item.aliases.first(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = item.previewLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (item.note.pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Fixada",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = item.updatedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val rightMeta = when {
                item.folder.isNotBlank() -> item.folder.substringAfterLast('/')
                item.tags.isNotEmpty() -> "#${item.tags.first()}"
                item.openTaskCount > 0 -> "${item.openTaskCount} pend."
                else -> null
            }
            if (rightMeta != null) {
                Text(
                    text = rightMeta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp)
                )
            }
        }
    }
}

@Composable
private fun VaultDraftRow(
    draft: NoteDraft,
    onOpen: () -> Unit,
    onDiscard: () -> Unit
) {
    val updatedLabel = remember(draft.updatedAt) {
        formatEpochMillis(draft.updatedAt, draftDateTimeFormatter)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                draft.title.ifBlank { "Nota sem título" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                draft.markdown.trim().replace('\n', ' ').take(120).ifBlank { "Rascunho vazio" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                updatedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        IconButton(onClick = onDiscard, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Descartar rascunho")
        }
    }
}

@Composable
private fun VaultTrashRow(
    note: Note,
    onRestore: () -> Unit,
    onPurge: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                note.title.ifBlank { "Nota sem título" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                note.markdown.trim().replace('\n', ' ').take(120).ifBlank { "Nota vazia" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRestore, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Restore, contentDescription = "Restaurar nota", tint = MaterialTheme.colorScheme.primary)
        }
        if (onPurge != null) {
            IconButton(onClick = onPurge, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.DeleteForever, contentDescription = "Excluir definitivamente", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun VaultEmptyPlaceholder(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Outlined.Description,
                null,
                Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            FilledTonalButton(
                onClick = onAction,
                shape = studyControlShape(),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun VaultLeftDrawerContent(
    index: VaultDerivedIndex?,
    notesCount: Int,
    draftsCount: Int,
    trashCount: Int,
    activeView: VaultView,
    selectedFolder: String?,
    selectedTag: String?,
    pinnedOnly: Boolean,
    onExitWorkspace: () -> Unit,
    onSelectAllNotes: () -> Unit,
    onSelectPinned: () -> Unit,
    onOpenTodayDaily: () -> Unit,
    onOpenCalendar: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onSelectTag: (String) -> Unit,
    onNewFolder: () -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTemplates: () -> Unit,
    onExportVaultZip: (() -> Unit)?,
    onImportVaultZip: (() -> Unit)?
) {
    var foldersExpanded by rememberSaveable { mutableStateOf(false) }
    var tagsExpanded by rememberSaveable { mutableStateOf(false) }
    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }
    val expandedTags = remember { mutableStateMapOf<String, Boolean>() }

    ModalDrawerSheet(
        windowInsets = WindowInsets(0),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item(key = "exit_studydock") {
                DrawerNavRow(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = "Voltar ao StudyDock",
                    selected = false,
                    onClick = onExitWorkspace,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            item(key = "vault_header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Obsidian Vault",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "$notesCount notas · ${index?.folderCount ?: 0} pastas · ${index?.totalLinks ?: 0} links",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }

            item(key = "all_notes") {
                DrawerNavRow(
                    icon = Icons.Outlined.Description,
                    label = "Todas as notas",
                    badge = notesCount.toString(),
                    selected = activeView == VaultView.NOTES && selectedFolder == null && selectedTag == null && !pinnedOnly,
                    onClick = onSelectAllNotes
                )
            }

            item(key = "pinned_notes") {
                DrawerNavRow(
                    icon = Icons.Default.PushPin,
                    label = "Fixadas",
                    badge = (index?.pinnedCount ?: 0).takeIf { it > 0 }?.toString(),
                    selected = activeView == VaultView.NOTES && pinnedOnly,
                    onClick = onSelectPinned
                )
            }

            item(key = "daily_today") {
                DrawerNavRow(
                    icon = Icons.Default.Today,
                    label = "Nota diária de hoje",
                    selected = false,
                    onClick = onOpenTodayDaily
                )
            }

            item(key = "daily_calendar") {
                DrawerNavRow(
                    icon = Icons.Default.CalendarMonth,
                    label = "Calendário Diário",
                    selected = activeView == VaultView.CALENDAR,
                    onClick = onOpenCalendar
                )
            }

            item(key = "folders_header") {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { foldersExpanded = !foldersExpanded }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (foldersExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (foldersExpanded) "Recolher pastas" else "Expandir pastas",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Pastas",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${index?.folderTree?.size ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onNewFolder, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Nova pasta", modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (foldersExpanded && index != null) {
                item(key = "folders_tree") {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        if (index.folderTree.isEmpty() && index.unfiledCount == 0) {
                            Text(
                                "Nenhuma pasta ainda. Use `folder:` ou + acima.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        } else {
                            index.folderTree.forEach { node ->
                                FolderTreeRow(
                                    node = node,
                                    depth = 0,
                                    expandedMap = expandedFolders,
                                    selectedPath = selectedFolder,
                                    onSelect = onSelectFolder
                                )
                            }
                            if (index.unfiledCount > 0) {
                                DrawerNavRow(
                                    icon = Icons.Outlined.Description,
                                    label = "Sem pasta",
                                    badge = index.unfiledCount.toString(),
                                    selected = selectedFolder == "__UNFILED__",
                                    onClick = { onSelectFolder("__UNFILED__") }
                                )
                            }
                        }
                    }
                }
            }

            item(key = "tags_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { tagsExpanded = !tagsExpanded }
                        .padding(start = 12.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (tagsExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (tagsExpanded) "Recolher tags" else "Expandir tags",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Tags",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${index?.tagTree?.size ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (tagsExpanded && index != null) {
                item(key = "tags_tree") {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        if (index.tagTree.isEmpty()) {
                            Text(
                                "Nenhuma #tag encontrada nas notas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        } else {
                            index.tagTree.forEach { node ->
                                TagTreeRow(
                                    node = node,
                                    depth = 0,
                                    expandedMap = expandedTags,
                                    selectedTag = selectedTag,
                                    onSelect = onSelectTag
                                )
                            }
                        }
                    }
                }
            }

            item(key = "tools_divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
            }

            item(key = "drafts_nav") {
                DrawerNavRow(
                    icon = Icons.Default.EditNote,
                    label = "Rascunhos",
                    badge = draftsCount.takeIf { it > 0 }?.toString(),
                    selected = activeView == VaultView.DRAFTS,
                    onClick = onOpenDrafts
                )
            }

            item(key = "trash_nav") {
                DrawerNavRow(
                    icon = Icons.Default.DeleteOutline,
                    label = "Lixeira",
                    badge = trashCount.takeIf { it > 0 }?.toString(),
                    selected = activeView == VaultView.TRASH,
                    onClick = onOpenTrash
                )
            }

            item(key = "templates_nav") {
                DrawerNavRow(
                    icon = Icons.Default.PostAdd,
                    label = "Modelos (Templates)",
                    selected = false,
                    onClick = onOpenTemplates
                )
            }

            if (onExportVaultZip != null) {
                item(key = "export_zip") {
                    DrawerNavRow(
                        icon = Icons.Default.FolderZip,
                        label = "Exportar Vault (.zip)",
                        selected = false,
                        onClick = onExportVaultZip
                    )
                }
            }

            if (onImportVaultZip != null) {
                item(key = "import_zip") {
                    DrawerNavRow(
                        icon = Icons.Default.UploadFile,
                        label = "Importar Vault (.zip)",
                        selected = false,
                        onClick = onImportVaultZip
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerNavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    tint: Color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!badge.isNullOrBlank()) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!trailing.isNullOrBlank()) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NewVaultFolderDialog(
    onDismiss: () -> Unit,
    onCreateFolder: (folderPath: String, firstNoteTitle: String) -> Unit
) {
    var folderPath by remember { mutableStateOf("") }
    var firstTitle by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("Nova pasta no Vault") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Pastas organizam notas pelo campo YAML `folder:`. Use `/` para subpastas (ex: Faculdade/Anatomia).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = folderPath,
                    onValueChange = { folderPath = it },
                    label = { Text("Caminho da pasta") },
                    placeholder = { Text("Ex: Biologia/Genética") },
                    singleLine = true,
                    shape = studyControlShape(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = firstTitle,
                    onValueChange = { firstTitle = it },
                    label = { Text("Primeira nota (opcional)") },
                    placeholder = { Text("Índice da pasta") },
                    singleLine = true,
                    shape = studyControlShape(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreateFolder(folderPath, firstTitle) },
                enabled = folderPath.isNotBlank()
            ) { Text("Criar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun FolderTreeRow(
    node: FolderTreeNode,
    depth: Int,
    expandedMap: MutableMap<String, Boolean>,
    selectedPath: String?,
    onSelect: (String) -> Unit
) {
    val isExpanded = expandedMap[node.fullPath] ?: false
    val isSelected = selectedPath.equals(node.fullPath, ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .clickable { onSelect(node.fullPath) }
            .padding(start = (depth * 14).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.children.isNotEmpty()) {
            IconButton(
                onClick = { expandedMap[node.fullPath] = !isExpanded },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Recolher ${node.name}" else "Expandir ${node.name}",
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Icon(
            Icons.Default.FolderOpen,
            null,
            Modifier.size(18.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Text(
            node.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${node.totalNotes}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (isExpanded) {
        node.children.forEach { child ->
            FolderTreeRow(child, depth + 1, expandedMap, selectedPath, onSelect)
        }
    }
}

@Composable
private fun TagTreeRow(
    node: TagTreeNode,
    depth: Int,
    expandedMap: MutableMap<String, Boolean>,
    selectedTag: String?,
    onSelect: (String) -> Unit
) {
    val isExpanded = expandedMap[node.fullTag] ?: false
    val isSelected = selectedTag.equals(node.fullTag, ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .clickable { onSelect(node.fullTag) }
            .padding(start = (depth * 14).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.children.isNotEmpty()) {
            IconButton(
                onClick = { expandedMap[node.fullTag] = !isExpanded },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Recolher ${node.segment}" else "Expandir ${node.segment}",
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Icon(
            Icons.Default.Tag,
            null,
            Modifier.size(16.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (depth == 0) "#${node.segment}" else "/${node.segment}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${node.totalNotes}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (isExpanded) {
        node.children.forEach { child ->
            TagTreeRow(child, depth + 1, expandedMap, selectedTag, onSelect)
        }
    }
}

@Composable
private fun DailyNotesCalendarTab(
    notes: List<Note>,
    metaById: Map<Long, NoteItemMeta>,
    tagsByNote: Map<Long, List<String>>,
    onOpenNote: (Long) -> Unit,
    onLongClickNote: (NoteItemMeta) -> Unit,
    onOpenOrCreateDaily: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ptBr = remember { Locale("pt", "BR") }
    val today = remember { LocalDate.now() }
    var currentMonth by remember { mutableStateOf(YearMonth.from(today)) }

    val dailyByDate = remember(notes) {
        notes.filter { it.trashedAt == null && dailyNoteTitleRegex.matches(it.title.trim()) }
            .associateBy { it.title.trim() }
    }
    val recentDailyNotes = remember(notes, tagsByNote) {
        notes.filter {
            it.trashedAt == null && (
                dailyNoteTitleRegex.matches(it.title.trim()) ||
                    "diario" in tagsByDate(tagsByNote[it.id].orEmpty())
                )
        }.sortedByDescending { it.title }
    }

    val firstDay = currentMonth.atDay(1)
    val leadingBlanks = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val daysInMonth = currentMonth.lengthOfMonth()
    val totalCells = ((leadingBlanks + daysInMonth + 6) / 7) * 7
    val monthLabel = remember(currentMonth) {
        val m = currentMonth.month.getDisplayName(JavaTextStyle.FULL, ptBr)
            .replaceFirstChar { it.titlecase(ptBr) }
        "$m ${currentMonth.year}"
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 104.dp)
    ) {
        item(key = "calendar_grid") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton({ currentMonth = currentMonth.minusMonths(1) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ChevronLeft, "Mês anterior")
                    }
                    Text(monthLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { currentMonth = YearMonth.from(today) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Hoje") }
                        IconButton({ currentMonth = currentMonth.plusMonths(1) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.ChevronRight, "Próximo mês")
                        }
                    }
                }

                Row(Modifier.fillMaxWidth()) {
                    listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom").forEach { d ->
                        Text(
                            d,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                for (weekStart in 0 until totalCells step 7) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (offset in 0 until 7) {
                            val cellIdx = weekStart + offset
                            val dayNum = cellIdx - leadingBlanks + 1
                            if (dayNum in 1..daysInMonth) {
                                val date = currentMonth.atDay(dayNum)
                                val iso = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
                                val hasNote = iso in dailyByDate
                                val isToday = date == today
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                hasNote -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                                isToday -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .then(
                                            if (isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                            else Modifier
                                        )
                                        .clickable { onOpenOrCreateDaily(iso) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "$dayNum",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isToday || hasNote) FontWeight.Bold else FontWeight.Normal,
                                            color = if (hasNote) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (hasNote) {
                                            Box(
                                                Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f).height(48.dp))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        }

        item(key = "daily_header") {
            Text(
                if (recentDailyNotes.isEmpty()) "Toque em qualquer dia no calendário para abrir ou criar a nota diária."
                else "Notas Diárias (${recentDailyNotes.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        items(
            items = recentDailyNotes,
            key = { "daily_${it.id}" },
            contentType = { "daily_note_row" }
        ) { note ->
            val meta = metaById[note.id] ?: NoteItemMeta(
                note = note,
                folder = "",
                aliases = emptyList(),
                tags = tagsByNote[note.id].orEmpty(),
                previewLine = fastPlainPreview(note.markdown),
                outgoingCount = 0,
                incomingCount = 0,
                openTaskCount = 0,
                updatedLabel = formatEpochMillis(note.updatedAt, shortDatePtFormatter)
            )
            ObsidianNoteRow(
                item = meta,
                onClick = { onOpenNote(note.id) },
                onLongClick = { onLongClickNote(meta) }
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }
    }
}

private fun tagsByDate(tags: List<String>): Set<String> = tags.map { it.substringBefore('/').lowercase(Locale.ROOT) }.toSet()

// ---------- editor ----------

internal enum class NoteViewMode(val label: String) {
    EDIT("Editar"),
    SPLIT("Dividido"),
    READ("Leitura")
}

internal enum class ContextHubTab(val label: String) {
    BACKLINKS("Backlinks"),
    MENTIONS("Menções"),
    LINKS("Links"),
    TASKS("Tarefas"),
    FLASHCARDS("Flashcards")
}

