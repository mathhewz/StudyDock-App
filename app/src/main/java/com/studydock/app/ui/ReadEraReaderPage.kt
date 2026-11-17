@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)

package com.studydock.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.studydock.app.data.Book
import com.studydock.app.data.BookHighlight
import com.studydock.app.data.BookHighlights
import com.studydock.app.data.Note
import com.studydock.app.data.ReadEraFontFamily
import com.studydock.app.data.ReadEraMarginPreset
import com.studydock.app.data.ReadEraScrollMode
import com.studydock.app.data.ReadEraTheme
import com.studydock.app.data.ReaderPreferencesSnapshot
import com.studydock.app.data.ReaderPreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private enum class ReaderDocumentKind {
    NONE,
    PDF,
    TEXT
}

private class PdfRendererSession(
    val pfd: ParcelFileDescriptor,
    val renderer: PdfRenderer
) {
    val mutex = Mutex()
    val isClosed = AtomicBoolean(false)
    val pageCount: Int = runCatching { renderer.pageCount }.getOrDefault(0)

    suspend fun closeSafely() {
        if (!isClosed.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }

    fun closeQuietlyAsync() {
        if (!isClosed.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }
}

private fun Context.findReaderActivity(): Activity? {
    var cur: Context = this
    while (cur is ContextWrapper) {
        if (cur is Activity) return cur
        cur = cur.baseContext
    }
    return null
}

internal fun resolveDocumentDisplayName(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)?.trim()
                    if (!name.isNullOrEmpty()) return name
                }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.trim()?.ifEmpty { "Documento" } ?: "Documento"
}

private fun classifyDocumentKind(context: Context, uri: Uri, displayName: String): ReaderDocumentKind {
    val mime = runCatching { context.contentResolver.getType(uri)?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
    val lowerName = displayName.lowercase(Locale.ROOT)
    val lowerUri = uri.toString().lowercase(Locale.ROOT)
    return when {
        mime.contains("pdf") || lowerName.endsWith(".pdf") || lowerUri.endsWith(".pdf") -> ReaderDocumentKind.PDF
        else -> ReaderDocumentKind.TEXT
    }
}

internal suspend fun detectAttachedDocumentPages(
    context: Context,
    uri: Uri,
    displayName: String,
    prefs: ReaderPreferencesSnapshot = ReaderPreferencesSnapshot()
): Int? = withContext(Dispatchers.IO) {
    runCatching {
        when (classifyDocumentKind(context, uri, displayName)) {
            ReaderDocumentKind.PDF -> {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { it.pageCount.takeIf { count -> count > 0 } }
                }
            }
            else -> {
                val boundedText = context.contentResolver.openInputStream(uri)?.use { stream ->
                    ReaderPreferencesStore.readBoundedText(stream)
                } ?: return@runCatching null
                withContext(Dispatchers.Default) {
                    ReaderPreferencesStore.paginateText(
                        rawText = boundedText,
                        fontSizeSp = prefs.fontSizeSp,
                        lineHeightMultiplier = prefs.lineHeightMultiplier,
                        marginPreset = prefs.marginPreset
                    ).size.takeIf { count -> count > 0 }
                }
            }
        }
    }.getOrNull()
}

@Stable
private class ReaderTimerClockState(
    initialRunning: Boolean = true,
    initialElapsedSeconds: Long = 0L
) {
    var running by mutableStateOf(initialRunning)
    var elapsedSeconds by mutableLongStateOf(initialElapsedSeconds)
}

@Composable
private fun ReaderFooterStatusText(
    clockState: ReaderTimerClockState,
    currentPage: Int,
    totalPages: Int,
    pct: Int,
    textColor: Color
) {
    Text(
        text = "${formatReaderTimer(clockState.elapsedSeconds)}  •  $currentPage / $totalPages ($pct%)",
        color = textColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun ReaderTimerHudPill(
    clockState: ReaderTimerClockState,
    accentColor: Color,
    secondaryTextColor: Color
) {
    val isRunning = clockState.running
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isRunning) accentColor.copy(alpha = 0.16f) else secondaryTextColor.copy(alpha = 0.14f),
        border = BorderStroke(
            1.dp,
            if (isRunning) accentColor.copy(alpha = 0.5f) else secondaryTextColor.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { clockState.running = !isRunning }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isRunning) "Pausar timer" else "Retomar timer",
                tint = if (isRunning) accentColor else secondaryTextColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = formatReaderTimer(clockState.elapsedSeconds),
                color = if (isRunning) accentColor else secondaryTextColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatReaderTimer(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hrs = safe / 3600L
    val mins = (safe % 3600L) / 60L
    val secs = safe % 60L
    return if (hrs > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", mins, secs)
    }
}

/**
 * Full-screen immersive reading mode inspired by **ReadEra**.
 *
 * Supports local `.pdf` rendering via [PdfRenderer], paginated/scrollable `.txt` and `.md` documents,
 * and an embedded Study Summary & Notes reader when no external file is attached yet.
 */
@Composable
fun ReadEraReaderPage(
    book: Book,
    linkedNotes: List<Note> = emptyList(),
    onBack: () -> Unit,
    onAttachDocument: (book: Book, uriString: String, fileName: String, totalPagesHint: Int?) -> Unit = { _, _, _, _ -> },
    onRecordProgress: (book: Book, page: Int, totalPagesHint: Int?, durationMinutes: Int) -> Unit = { _, _, _, _ -> },
    onAddHighlight: (book: Book, quote: String, page: Int?) -> Unit = { _, _, _ -> },
    onSaveHighlightFull: ((book: Book, highlight: BookHighlight) -> Unit)? = null,
    onChangeStatus: (book: Book, newStatus: String) -> Unit = { _, _ -> },
    onUpdateBook: (Book) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onCreateLinkedNote: (Book) -> Unit = {},
    onOpenSounds: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findReaderActivity() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    var prefs by remember { mutableStateOf(ReaderPreferencesStore.readSnapshot(context)) }
    var bookmarks by remember(book.id) { mutableStateOf(ReaderPreferencesStore.readBookmarks(context, book.id)) }
    var invertPdfInDark by rememberSaveable { mutableStateOf(true) }

    fun updatePrefs(transform: (ReaderPreferencesSnapshot) -> ReaderPreferencesSnapshot) {
        val next = transform(prefs)
        prefs = next
        ReaderPreferencesStore.writeSnapshot(context, next)
    }

    // Screen brightness, keep-screen-on & orientation lock lifecycle management
    DisposableEffect(activity) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness
        val originalOrientation = activity?.requestedOrientation
        onDispose {
            if (window != null && originalBrightness != null) {
                val lp = window.attributes
                lp.screenBrightness = originalBrightness
                window.attributes = lp
            }
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
            if (activity != null && originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }

    LaunchedEffect(prefs.brightness, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        val lp = window.attributes
        lp.screenBrightness = if (prefs.brightness < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            prefs.brightness.coerceIn(0.05f, 1.0f)
        }
        window.attributes = lp
    }

    LaunchedEffect(prefs.keepScreenOn, activity) {
        val window = activity?.window
        view.keepScreenOn = prefs.keepScreenOn
        if (prefs.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(prefs.lockOrientation, activity) {
        activity ?: return@LaunchedEffect
        activity.requestedOrientation = if (prefs.lockOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Active document URI & name (syncs with book updates and immediate picker selection)
    var activeDocUri by remember(book.id, book.documentUri) { mutableStateOf(book.documentUri.trim()) }
    var activeDocName by remember(book.id, book.documentName) { mutableStateOf(book.documentName.trim()) }

    // Integrated discreet reading timer isolated in ReaderTimerClockState
    val clockState = remember(book.id) { ReaderTimerClockState() }
    var flushedMinutes by rememberSaveable(book.id) { mutableIntStateOf(0) }

    LaunchedEffect(clockState, clockState.running) {
        while (clockState.running) {
            delay(1_000L)
            clockState.elapsedSeconds += 1L
        }
    }

    // Document loading state
    var docKind by remember { mutableStateOf(ReaderDocumentKind.NONE) }
    var isLoadingDoc by remember { mutableStateOf(false) }
    var docErrorMessage by remember { mutableStateOf<String?>(null) }
    var rawTextContent by remember { mutableStateOf("") }
    var textPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var pdfSession by remember { mutableStateOf<PdfRendererSession?>(null) }
    var pdfPageCount by remember { mutableIntStateOf(0) }
    var pdfZoom by rememberSaveable(book.id) { mutableFloatStateOf(1f) }

    // Clean up PdfRenderer safely when session changes or page leaves composition
    DisposableEffect(pdfSession) {
        val activeSession = pdfSession
        onDispose {
            activeSession?.closeQuietlyAsync()
        }
    }

    // Text pages recomputed off the main thread when typography or rawTextContent changes
    LaunchedEffect(
        rawTextContent,
        prefs.fontSizeSp,
        prefs.lineHeightMultiplier,
        prefs.marginPreset
    ) {
        textPages = if (rawTextContent.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                ReaderPreferencesStore.paginateText(
                    rawText = rawTextContent,
                    fontSizeSp = prefs.fontSizeSp,
                    lineHeightMultiplier = prefs.lineHeightMultiplier,
                    marginPreset = prefs.marginPreset
                )
            }
        }
    }

    val totalPages = remember(docKind, pdfPageCount, textPages.size, book.totalPages, book.currentPage) {
        when (docKind) {
            ReaderDocumentKind.PDF -> pdfPageCount.coerceAtLeast(1)
            ReaderDocumentKind.TEXT -> textPages.size.coerceAtLeast(1)
            ReaderDocumentKind.NONE -> (book.totalPages?.takeIf { it > 0 } ?: maxOf(book.currentPage, 1)).coerceAtLeast(1)
        }
    }

    var currentPage by rememberSaveable(book.id) {
        mutableIntStateOf(book.currentPage.coerceAtLeast(1))
    }

    LaunchedEffect(totalPages, isLoadingDoc) {
        if (!isLoadingDoc && totalPages > 0 && currentPage > totalPages) {
            currentPage = totalPages
        } else if (currentPage < 1) {
            currentPage = 1
        }
    }

    // Load document whenever activeDocUri changes
    LaunchedEffect(activeDocUri) {
        val cleanUriStr = activeDocUri.trim()
        if (cleanUriStr.isEmpty()) {
            val prev = pdfSession
            pdfSession = null
            prev?.closeSafely()
            pdfPageCount = 0
            rawTextContent = ""
            docErrorMessage = null
            docKind = ReaderDocumentKind.NONE
            isLoadingDoc = false
            return@LaunchedEffect
        }

        isLoadingDoc = true
        docErrorMessage = null
        val uri = runCatching { Uri.parse(cleanUriStr) }.getOrNull()
        if (uri == null) {
            isLoadingDoc = false
            docKind = ReaderDocumentKind.NONE
            docErrorMessage = "URI de documento inválida. Selecione o arquivo novamente."
            return@LaunchedEffect
        }

        val resolvedName = activeDocName.ifBlank { resolveDocumentDisplayName(context, uri) }
        if (activeDocName.isBlank()) activeDocName = resolvedName
        val detectedKind = classifyDocumentKind(context, uri, resolvedName)

        try {
            when (detectedKind) {
                ReaderDocumentKind.PDF -> {
                    val opened = withContext(Dispatchers.IO) {
                        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                            ?: throw IOException("Não foi possível abrir o descritor do PDF.")
                        try {
                            val renderer = PdfRenderer(pfd)
                            PdfRendererSession(pfd, renderer)
                        } catch (t: Throwable) {
                            runCatching { pfd.close() }
                            throw t
                        }
                    }
                    val prev = pdfSession
                    pdfSession = opened
                    prev?.closeSafely()
                    pdfPageCount = opened.pageCount.coerceAtLeast(1)
                    rawTextContent = ""
                    docKind = ReaderDocumentKind.PDF
                    val initialTarget = book.currentPage.takeIf { it in 1..pdfPageCount } ?: 1
                    currentPage = initialTarget
                }

                ReaderDocumentKind.TEXT, ReaderDocumentKind.NONE -> {
                    val loadedText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            ReaderPreferencesStore.readBoundedText(stream)
                        } ?: throw IOException("Não foi possível abrir o arquivo de texto.")
                    }
                    val pages = withContext(Dispatchers.Default) {
                        ReaderPreferencesStore.paginateText(
                            rawText = loadedText,
                            fontSizeSp = prefs.fontSizeSp,
                            lineHeightMultiplier = prefs.lineHeightMultiplier,
                            marginPreset = prefs.marginPreset
                        )
                    }
                    val prev = pdfSession
                    pdfSession = null
                    prev?.closeSafely()
                    pdfPageCount = 0
                    textPages = pages
                    rawTextContent = loadedText
                    docKind = ReaderDocumentKind.TEXT
                    val count = pages.size.coerceAtLeast(1)
                    val initialTarget = book.currentPage.takeIf { it in 1..count } ?: 1
                    currentPage = initialTarget
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            val prev = pdfSession
            pdfSession = null
            prev?.closeSafely()
            docKind = ReaderDocumentKind.NONE
            docErrorMessage = "A permissão de leitura deste arquivo expirou no Android. Toque abaixo para reabrir o arquivo."
        } catch (e: Exception) {
            val prev = pdfSession
            pdfSession = null
            prev?.closeSafely()
            docKind = ReaderDocumentKind.NONE
            docErrorMessage = "Não foi possível abrir “$resolvedName” (${e.localizedMessage ?: "arquivo indisponível"}). Toque abaixo para reabrir."
        } finally {
            isLoadingDoc = false
        }
    }

    // OpenDocument launcher for .pdf, .txt, .md
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        scope.launch {
            val fileName = withContext(Dispatchers.IO) { resolveDocumentDisplayName(context, uri) }
            val pagesHint = withContext(Dispatchers.IO) {
                detectAttachedDocumentPages(context, uri, fileName, prefs)
            }
            activeDocName = fileName
            activeDocUri = uri.toString()
            onAttachDocument(book, uri.toString(), fileName, pagesHint)
            Toast.makeText(context, "Documento vinculado: $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchDocumentPicker() {
        documentLauncher.launch(
            arrayOf(
                "application/pdf",
                "text/plain",
                "text/markdown",
                "text/*"
            )
        )
    }

    fun flushReadingProgress(targetPage: Int = currentPage) {
        val totalMinutesNow = (clockState.elapsedSeconds / 60L).toInt()
        val deltaMinutes = (totalMinutesNow - flushedMinutes).coerceAtLeast(0)
        if (deltaMinutes > 0) {
            flushedMinutes = totalMinutesNow
        }
        val hint = totalPages.takeIf { it > 0 && docKind != ReaderDocumentKind.NONE } ?: book.totalPages
        onRecordProgress(book, targetPage.coerceAtLeast(1), hint, deltaMinutes)
    }

    fun handleExitReader() {
        flushReadingProgress(currentPage)
        onBack()
    }

    BackHandler(onBack = ::handleExitReader)

    val lifecycleOwner = LocalLifecycleOwner.current
    val latestCurrentPage by rememberUpdatedState(currentPage)
    val latestBook by rememberUpdatedState(book)
    val latestTotalPages by rememberUpdatedState(totalPages)
    val latestDocKind by rememberUpdatedState(docKind)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val totalMinutesNow = (clockState.elapsedSeconds / 60L).toInt()
                val deltaMinutes = (totalMinutesNow - flushedMinutes).coerceAtLeast(0)
                if (deltaMinutes > 0) {
                    flushedMinutes = totalMinutesNow
                    val hint = latestTotalPages.takeIf { it > 0 && latestDocKind != ReaderDocumentKind.NONE } ?: latestBook.totalPages
                    onRecordProgress(latestBook, latestCurrentPage.coerceAtLeast(1), hint, deltaMinutes)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sync progress when currentPage advances
    fun navigateToPage(targetPage: Int) {
        val coerced = targetPage.coerceIn(1, totalPages.coerceAtLeast(1))
        if (coerced != currentPage) {
            currentPage = coerced
            val hint = totalPages.takeIf { it > 0 && docKind != ReaderDocumentKind.NONE } ?: book.totalPages
            onRecordProgress(book, coerced, hint, 0)
        }
    }

    // UI overlays state
    var hudVisible by rememberSaveable { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showHighlightDialog by remember { mutableStateOf(false) }
    var showContentsSheet by remember { mutableStateOf(false) }
    var showJumpPageDialog by remember { mutableStateOf(false) }
    var showEditSummaryDialog by remember { mutableStateOf(false) }
    var highlightInitialQuote by remember { mutableStateOf("") }

    val theme = prefs.theme
    val bgColor = Color(theme.bgHex)
    val textColor = Color(theme.textHex)
    val secondaryTextColor = Color(theme.secondaryTextHex)
    val surfaceColor = Color(theme.surfaceHex)
    val accentColor = Color(theme.accentHex)
    val composeFontFamily = remember(prefs.fontFamily) {
        when (prefs.fontFamily) {
            ReadEraFontFamily.SERIF -> FontFamily.Serif
            ReadEraFontFamily.SANS -> FontFamily.SansSerif
            ReadEraFontFamily.MONO -> FontFamily.Monospace
        }
    }

    val highlights = remember(book.highlightsJson, book.quotes, book.updatedAt) {
        BookHighlights.parse(book)
    }
    val isCurrentPageBookmarked = currentPage in bookmarks

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Main Reader Canvas
        when {
            isLoadingDoc -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = accentColor)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Preparando leitura no modo ReadEra...",
                        color = textColor,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (activeDocName.isNotBlank()) {
                        Text(
                            activeDocName,
                            color = secondaryTextColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            docErrorMessage != null -> {
                ReadEraDocumentErrorView(
                    book = book,
                    errorMessage = docErrorMessage!!,
                    textColor = textColor,
                    secondaryTextColor = secondaryTextColor,
                    surfaceColor = surfaceColor,
                    accentColor = accentColor,
                    onReopenDocument = ::launchDocumentPicker,
                    onUseEmbeddedNotesMode = {
                        docErrorMessage = null
                        activeDocUri = ""
                        docKind = ReaderDocumentKind.NONE
                    }
                )
            }

            docKind == ReaderDocumentKind.PDF && pdfSession != null -> {
                ReadEraPdfViewport(
                    session = pdfSession!!,
                    currentPage = currentPage,
                    totalPages = totalPages,
                    scrollMode = prefs.scrollMode,
                    tapZonesEnabled = prefs.tapZonesEnabled,
                    theme = theme,
                    invertInDark = invertPdfInDark,
                    zoom = pdfZoom,
                    onZoomChange = { pdfZoom = it.coerceIn(1f, 3f) },
                    onPageChanged = ::navigateToPage,
                    onToggleHud = { hudVisible = !hudVisible },
                    onPermissionError = { msg -> docErrorMessage = msg }
                )
            }

            docKind == ReaderDocumentKind.TEXT && textPages.isNotEmpty() -> {
                ReadEraTextViewport(
                    pages = textPages,
                    currentPage = currentPage,
                    scrollMode = prefs.scrollMode,
                    tapZonesEnabled = prefs.tapZonesEnabled,
                    fontFamily = composeFontFamily,
                    fontSizeSp = prefs.fontSizeSp,
                    lineHeightMultiplier = prefs.lineHeightMultiplier,
                    marginPreset = prefs.marginPreset,
                    textColor = textColor,
                    secondaryTextColor = secondaryTextColor,
                    surfaceColor = surfaceColor,
                    accentColor = accentColor,
                    bookmarks = bookmarks,
                    onPageChanged = ::navigateToPage,
                    onToggleHud = { hudVisible = !hudVisible },
                    onQuickQuotePage = { pageText ->
                        highlightInitialQuote = pageText.lineSequence().firstOrNull { it.isNotBlank() }?.take(280).orEmpty()
                        showHighlightDialog = true
                    }
                )
            }

            else -> {
                // Embedded Notes, Summary & Highlights Reader Mode (when no PDF/TXT attached yet)
                ReadEraEmbeddedBookViewport(
                    book = book,
                    highlights = highlights,
                    linkedNotes = linkedNotes,
                    currentPage = currentPage,
                    totalPages = totalPages,
                    fontFamily = composeFontFamily,
                    fontSizeSp = prefs.fontSizeSp,
                    lineHeightMultiplier = prefs.lineHeightMultiplier,
                    marginPreset = prefs.marginPreset,
                    textColor = textColor,
                    secondaryTextColor = secondaryTextColor,
                    surfaceColor = surfaceColor,
                    accentColor = accentColor,
                    onToggleHud = { hudVisible = !hudVisible },
                    onAttachFile = ::launchDocumentPicker,
                    onEditSummary = { showEditSummaryDialog = true },
                    onAddHighlight = {
                        highlightInitialQuote = ""
                        showHighlightDialog = true
                    },
                    onOpenNote = onOpenNote,
                    onCreateLinkedNote = { onCreateLinkedNote(book) },
                    onAdvancePage = { delta -> navigateToPage(currentPage + delta) }
                )
            }
        }

        // Subtle status footer when HUD is hidden (authentic ReadEra micro status bar)
        AnimatedVisibility(
            visible = !hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor.copy(alpha = 0.86f))
                    .padding(horizontal = prefs.marginPreset.horizontalDp.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = book.currentChapter.ifBlank { activeDocName.ifBlank { book.title } },
                    color = secondaryTextColor.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val pct = if (totalPages > 0) ((currentPage * 100f) / totalPages).roundToInt().coerceIn(0, 100) else 0
                ReaderFooterStatusText(
                    clockState = clockState,
                    currentPage = currentPage,
                    totalPages = totalPages,
                    pct = pct,
                    textColor = secondaryTextColor.copy(alpha = 0.75f)
                )
            }
        }

        // Top HUD Bar
        AnimatedVisibility(
            visible = hudVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = surfaceColor.copy(alpha = 0.97f),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = ::handleExitReader) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Voltar e salvar progresso",
                                tint = textColor
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = book.title,
                                color = textColor,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subLabel = buildString {
                                when (docKind) {
                                    ReaderDocumentKind.PDF -> append("PDF")
                                    ReaderDocumentKind.TEXT -> append(
                                        if (activeDocName.endsWith(".md", ignoreCase = true)) "Markdown" else "TXT"
                                    )
                                    ReaderDocumentKind.NONE -> append("Modo Estudo")
                                }
                                val detail = activeDocName.ifBlank { book.currentChapter }
                                if (detail.isNotBlank()) {
                                    append(" · ")
                                    append(detail)
                                }
                            }
                            Text(
                                text = subLabel,
                                color = secondaryTextColor,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Integrated Discreet Reading Timer Pill
                        ReaderTimerHudPill(
                            clockState = clockState,
                            accentColor = accentColor,
                            secondaryTextColor = secondaryTextColor
                        )

                        // Bookmark current page
                        IconButton(
                            onClick = {
                                bookmarks = ReaderPreferencesStore.toggleBookmark(context, book.id, currentPage)
                                val msg = if (currentPage in bookmarks) {
                                    "Marcador salvo na página $currentPage"
                                } else {
                                    "Marcador removido da página $currentPage"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = if (isCurrentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Marcar página",
                                tint = if (isCurrentPageBookmarked) accentColor else textColor
                            )
                        }

                        // Quick Highlight / Quote button
                        IconButton(
                            onClick = {
                                highlightInitialQuote = ""
                                showHighlightDialog = true
                            }
                        ) {
                            Icon(
                                Icons.Default.FormatQuote,
                                contentDescription = "Destacar / Salvar citação",
                                tint = textColor
                            )
                        }

                        // Contents / Bookmarks / Highlights drawer sheet
                        IconButton(onClick = { showContentsSheet = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = "Sumário, Marcadores e Destaques",
                                tint = textColor
                            )
                        }

                        // ReadEra "Aa" Typography & Theme Settings button
                        FilledTonalButton(
                            onClick = { showSettingsSheet = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = accentColor.copy(alpha = 0.18f),
                                contentColor = accentColor
                            ),
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .height(34.dp)
                        ) {
                            Text("Aa", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }
                    }

                    HorizontalDivider(color = secondaryTextColor.copy(alpha = 0.16f))
                }
            }
        }

        // Bottom HUD Bar
        AnimatedVisibility(
            visible = hudVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = surfaceColor.copy(alpha = 0.97f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // PDF Zoom quick bar when PDF is active
                    if (docKind == ReaderDocumentKind.PDF) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Zoom do PDF: ${(pdfZoom * 100).roundToInt()}%",
                                color = secondaryTextColor,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1f to "100%", 1.25f to "125%", 1.5f to "150%", 2f to "200%").forEach { (z, label) ->
                                    val selected = kotlin.math.abs(pdfZoom - z) < 0.08f
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) accentColor else bgColor.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { pdfZoom = z }
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (selected) Color.White else textColor,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    var sliderDraftPage by remember(book.id) { mutableStateOf<Float?>(null) }
                    val displayCurrentPage = sliderDraftPage?.roundToInt()?.coerceIn(1, totalPages) ?: currentPage

                    // Page navigation row + Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { navigateToPage(currentPage - 1) },
                            enabled = currentPage > 1,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Página anterior",
                                tint = if (currentPage > 1) textColor else secondaryTextColor.copy(alpha = 0.38f)
                            )
                        }

                        if (totalPages > 1) {
                            Slider(
                                value = (sliderDraftPage ?: currentPage.toFloat()).coerceIn(1f, totalPages.toFloat()),
                                onValueChange = { value -> sliderDraftPage = value },
                                onValueChangeFinished = {
                                    val target = (sliderDraftPage ?: currentPage.toFloat()).roundToInt().coerceIn(1, totalPages)
                                    sliderDraftPage = null
                                    navigateToPage(target)
                                },
                                valueRange = 1f..totalPages.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = accentColor,
                                    activeTrackColor = accentColor,
                                    inactiveTrackColor = secondaryTextColor.copy(alpha = 0.24f)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { 1f },
                                color = accentColor,
                                trackColor = secondaryTextColor.copy(alpha = 0.24f),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }

                        IconButton(
                            onClick = { navigateToPage(currentPage + 1) },
                            enabled = currentPage < totalPages,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Próxima página",
                                tint = if (currentPage < totalPages) textColor else secondaryTextColor.copy(alpha = 0.38f)
                            )
                        }
                    }

                    // Page Counter + Quick Actions Row
                    val progressPct = if (totalPages > 0) {
                        ((displayCurrentPage.toFloat() / totalPages.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
                    } else 0
                    val remPages = (totalPages - displayCurrentPage).coerceAtLeast(0)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showJumpPageDialog = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Página $displayCurrentPage de $totalPages ($progressPct%)",
                                color = textColor,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (remPages > 0) "· faltam $remPages" else "· última página",
                                color = secondaryTextColor,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (onOpenSounds != null) {
                                IconButton(
                                    onClick = onOpenSounds,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Headphones,
                                        contentDescription = "Sons de foco",
                                        tint = secondaryTextColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            TextButton(
                                onClick = ::launchDocumentPicker,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (activeDocUri.isBlank()) "Abrir PDF/TXT/MD" else "Trocar arquivo",
                                    color = accentColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Bottom Action Buttons: Destacar Citação & Concluir Leitura (when on last page or reading)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                highlightInitialQuote = ""
                                showHighlightDialog = true
                            },
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FormatQuote, null, tint = accentColor, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Destacar / Citação",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        val isAtEnd = totalPages > 0 && currentPage >= totalPages
                        if (isAtEnd || book.status != "DONE") {
                            Button(
                                onClick = {
                                    val finalTarget = maxOf(currentPage, totalPages)
                                    flushReadingProgress(finalTarget)
                                    onChangeStatus(book, "DONE")
                                    Toast.makeText(
                                        context,
                                        "Parabéns! “${book.title}” marcado como Lido!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isAtEnd) accentColor else accentColor.copy(alpha = 0.82f),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (isAtEnd) "Concluir Leitura!" else "Marcar Concluído",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ReadEra "Aa" Settings Bottom Sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = surfaceColor,
            contentColor = textColor
        ) {
            ReadEraSettingsSheetContent(
                prefs = prefs,
                isPdf = docKind == ReaderDocumentKind.PDF,
                invertPdfInDark = invertPdfInDark,
                onInvertPdfInDarkChange = { invertPdfInDark = it },
                textColor = textColor,
                secondaryTextColor = secondaryTextColor,
                accentColor = accentColor,
                onUpdatePrefs = ::updatePrefs
            )
        }
    }

    // Table of Contents, Bookmarks & Saved Highlights Sheet
    if (showContentsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showContentsSheet = false },
            containerColor = surfaceColor,
            contentColor = textColor
        ) {
            ReadEraContentsAndBookmarksSheet(
                book = book,
                bookmarks = bookmarks.sorted(),
                highlights = highlights,
                currentPage = currentPage,
                textColor = textColor,
                secondaryTextColor = secondaryTextColor,
                accentColor = accentColor,
                onSelectPage = { page ->
                    navigateToPage(page)
                    showContentsSheet = false
                },
                onRemoveBookmark = { page ->
                    bookmarks = ReaderPreferencesStore.toggleBookmark(context, book.id, page)
                },
                onAddHighlightClick = {
                    showContentsSheet = false
                    highlightInitialQuote = ""
                    showHighlightDialog = true
                }
            )
        }
    }

    // Quick Highlight / Quote Dialog
    if (showHighlightDialog) {
        ReadEraHighlightDialog(
            book = book,
            currentPage = currentPage,
            initialQuote = highlightInitialQuote,
            onDismiss = { showHighlightDialog = false },
            onSave = { quote, pageNum, chapter, note ->
                val cleanQuote = quote.trim()
                if (cleanQuote.isNotEmpty()) {
                    val created = BookHighlight(
                        text = cleanQuote,
                        page = pageNum,
                        chapter = chapter.trim(),
                        note = note.trim()
                    )
                    if (onSaveHighlightFull != null) {
                        onSaveHighlightFull(book, created)
                    } else {
                        onAddHighlight(book, cleanQuote, pageNum)
                    }
                    Toast.makeText(context, "Citação salva na página ${pageNum ?: currentPage}!", Toast.LENGTH_SHORT).show()
                }
                showHighlightDialog = false
            }
        )
    }

    // Jump to exact page dialog
    if (showJumpPageDialog) {
        var pageInput by remember(currentPage) { mutableStateOf(currentPage.toString()) }
        var chapterInput by remember(book.currentChapter) { mutableStateOf(book.currentChapter) }
        AlertDialog(
            onDismissRequest = { showJumpPageDialog = false },
            title = { Text("Ir para página / capítulo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { pageInput = it.filter(Char::isDigit).take(6) },
                        label = { Text("Número da página (1 a $totalPages)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = chapterInput,
                        onValueChange = { chapterInput = it },
                        label = { Text("Capítulo atual (opcional)") },
                        placeholder = { Text("Ex.: Cap. 5 — Árvores Binárias") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pageInput.toIntOrNull()?.let { target ->
                            navigateToPage(target)
                        }
                        val cleanChapter = chapterInput.trim()
                        if (cleanChapter != book.currentChapter) {
                            onUpdateBook(book.copy(currentChapter = cleanChapter, updatedAt = System.currentTimeMillis()))
                        }
                        showJumpPageDialog = false
                    }
                ) {
                    Text("Ir")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpPageDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Edit Summary / Fichamento dialog for Embedded Study Mode
    if (showEditSummaryDialog) {
        var summaryDraft by remember(book.summary) { mutableStateOf(book.summary) }
        var chapterDraft by remember(book.currentChapter) { mutableStateOf(book.currentChapter) }
        var totalPagesDraft by remember(book.totalPages) { mutableStateOf(book.totalPages?.toString().orEmpty()) }
        AlertDialog(
            modifier = Modifier.imePadding(),
            onDismissRequest = { showEditSummaryDialog = false },
            title = { Text("Editar Fichamento & Progresso") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = chapterDraft,
                            onValueChange = { chapterDraft = it },
                            label = { Text("Capítulo atual") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                        OutlinedTextField(
                            value = totalPagesDraft,
                            onValueChange = { totalPagesDraft = it.filter(Char::isDigit).take(6) },
                            label = { Text("Total págs") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = summaryDraft,
                        onValueChange = { summaryDraft = it },
                        label = { Text("Resumo / Anotações de leitura") },
                        minLines = 6,
                        maxLines = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updatedTotal = totalPagesDraft.toIntOrNull()?.takeIf { it > 0 } ?: book.totalPages
                        onUpdateBook(
                            book.copy(
                                summary = summaryDraft.trim(),
                                currentChapter = chapterDraft.trim(),
                                totalPages = updatedTotal,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        showEditSummaryDialog = false
                    }
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditSummaryDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

// =====================================================================
// Renderizador Real de PDF (PdfRenderer + Cache + Pinch/Zoom + Dark Filter)
// =====================================================================

@Composable
private fun ReadEraPdfViewport(
    session: PdfRendererSession,
    currentPage: Int,
    totalPages: Int,
    scrollMode: ReadEraScrollMode,
    tapZonesEnabled: Boolean,
    theme: ReadEraTheme,
    invertInDark: Boolean,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onPageChanged: (Int) -> Unit,
    onToggleHud: () -> Unit,
    onPermissionError: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val baseWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx().coerceIn(480, 2048) }
    }

    // Byte-bounded in-memory cache of rendered RGB_565 pages keyed by "${pageIndex}_$scaleBucket"
    val bitmapCache = remember(session) {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).coerceAtLeast(16_384L)
        val cacheSizeKb = (maxMemoryKb / 8L).toInt().coerceIn(8_192, 49_152)
        object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    DisposableEffect(session) {
        onDispose {
            bitmapCache.evictAll()
        }
    }

    val darkColorFilter = remember(theme, invertInDark) {
        if (theme.isDark && invertInDark) {
            // Invert luminance softly to match ReadEra dark/twilight reading comfort
            val matrix = ColorMatrix(
                floatArrayOf(
                    -0.88f, 0f, 0f, 0f, 235f,
                    0f, -0.88f, 0f, 0f, 238f,
                    0f, 0f, -0.88f, 0f, 245f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorFilter.colorMatrix(matrix)
        } else if (theme == ReadEraTheme.SEPIA) {
            val matrix = ColorMatrix(
                floatArrayOf(
                    0.96f, 0f, 0f, 0f, 0f,
                    0f, 0.92f, 0f, 0f, 0f,
                    0f, 0f, 0.84f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorFilter.colorMatrix(matrix)
        } else {
            null
        }
    }

    if (scrollMode == ReadEraScrollMode.PAGINATED) {
        val pagerState = rememberPagerState(
            initialPage = (currentPage - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0)),
            pageCount = { totalPages.coerceAtLeast(1) }
        )

        LaunchedEffect(currentPage, totalPages) {
            val targetIdx = (currentPage - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            if (pagerState.currentPage != targetIdx) {
                pagerState.scrollToPage(targetIdx)
            }
        }

        LaunchedEffect(pagerState.currentPage) {
            val pageOneBased = pagerState.currentPage + 1
            if (pageOneBased != currentPage) {
                onPageChanged(pageOneBased)
            }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = zoom <= 1.05f,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            PdfPageSurface(
                session = session,
                pageIndex = pageIndex,
                baseWidthPx = baseWidthPx,
                zoom = zoom,
                onZoomChange = onZoomChange,
                bitmapCache = bitmapCache,
                colorFilter = darkColorFilter,
                tapZonesEnabled = tapZonesEnabled,
                onPrevPage = { if (currentPage > 1) onPageChanged(currentPage - 1) },
                onNextPage = { if (currentPage < totalPages) onPageChanged(currentPage + 1) },
                onToggleHud = onToggleHud,
                onPermissionError = onPermissionError
            )
        }
    } else {
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = (currentPage - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        )
        val latestTotalPages by rememberUpdatedState(totalPages)
        val latestCurrentPage by rememberUpdatedState(currentPage)
        val latestOnPageChanged by rememberUpdatedState(onPageChanged)

        LaunchedEffect(currentPage, totalPages) {
            val targetIdx = (currentPage - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != targetIdx) {
                listState.scrollToItem(targetIdx)
            }
        }

        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { firstVisibleIndex ->
                    val visiblePage = firstVisibleIndex + 1
                    if (visiblePage in 1..latestTotalPages && visiblePage != latestCurrentPage) {
                        latestOnPageChanged(visiblePage)
                    }
                }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 48.dp, horizontal = 8.dp)
        ) {
            items(totalPages, key = { it }) { pageIndex ->
                PdfPageSurface(
                    session = session,
                    pageIndex = pageIndex,
                    baseWidthPx = baseWidthPx,
                    zoom = zoom,
                    onZoomChange = onZoomChange,
                    bitmapCache = bitmapCache,
                    colorFilter = darkColorFilter,
                    tapZonesEnabled = false,
                    onPrevPage = {},
                    onNextPage = {},
                    onToggleHud = onToggleHud,
                    onPermissionError = onPermissionError
                )
            }
        }
    }
}

@Composable
private fun PdfPageSurface(
    session: PdfRendererSession,
    pageIndex: Int,
    baseWidthPx: Int,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    bitmapCache: LruCache<String, Bitmap>,
    colorFilter: ColorFilter?,
    tapZonesEnabled: Boolean,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleHud: () -> Unit,
    onPermissionError: (String) -> Unit
) {
    val scaleBucket = if (zoom >= 1.65f) 2 else 1
    val cacheKey = "${pageIndex}_$scaleBucket"
    var pageBitmap by remember(session, cacheKey) {
        mutableStateOf(bitmapCache.get(cacheKey))
    }
    var panOffset by remember(pageIndex) { mutableStateOf(Offset.Zero) }

    LaunchedEffect( zoom ) {
        if (zoom <= 1.02f) {
            panOffset = Offset.Zero
        }
    }

    LaunchedEffect(session, cacheKey, baseWidthPx) {
        val cached = bitmapCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            pageBitmap = cached
            return@LaunchedEffect
        }
        try {
            val rendered = withContext(Dispatchers.IO) {
                session.mutex.withLock {
                    if (session.isClosed.get() || pageIndex !in 0 until session.pageCount) return@withLock null
                    session.renderer.openPage(pageIndex).use { page ->
                        if (session.isClosed.get()) return@use null
                        val rawW = page.width.coerceAtLeast(1)
                        val rawH = page.height.coerceAtLeast(1)
                        val targetW = (baseWidthPx * scaleBucket).coerceIn(480, 2400)
                        val targetH = ((targetW.toFloat() / rawW.toFloat()) * rawH.toFloat())
                            .roundToInt()
                            .coerceIn(480, 3200)
                        // PdfRenderer requires ARGB_8888; copy opaque white page to RGB_565 immediately to halve RAM.
                        val renderBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        renderBmp.eraseColor(android.graphics.Color.WHITE)
                        val matrix = Matrix().apply {
                            setScale(targetW.toFloat() / rawW.toFloat(), targetH.toFloat() / rawH.toFloat())
                        }
                        page.render(renderBmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        if (session.isClosed.get()) {
                            renderBmp.recycle()
                            null
                        } else {
                            val compactBmp = renderBmp.copy(Bitmap.Config.RGB_565, false)
                            if (compactBmp != null) {
                                renderBmp.recycle()
                                compactBmp
                            } else {
                                renderBmp
                            }
                        }
                    }
                }
            }
            if (rendered != null && !session.isClosed.get()) {
                bitmapCache.put(cacheKey, rendered)
                pageBitmap = rendered
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            if (!session.isClosed.get()) {
                onPermissionError("Permissão do PDF expirada. Toque abaixo para reabrir o documento.")
            }
        } catch (e: Exception) {
            if (!session.isClosed.get()) {
                onPermissionError("Erro ao renderizar página ${pageIndex + 1} do PDF.")
            }
        }
    }

    LaunchedEffect(session, pageIndex, scaleBucket, baseWidthPx) {
        val nextIdx = pageIndex + 1
        val nextKey = "${nextIdx}_$scaleBucket"
        if (nextIdx >= session.pageCount || bitmapCache.get(nextKey) != null) return@LaunchedEffect
        delay(220L)
        withContext(Dispatchers.IO) {
            runCatching {
                session.mutex.withLock {
                    if (session.isClosed.get() || bitmapCache.get(nextKey) != null) return@withLock
                    session.renderer.openPage(nextIdx).use { nextPage ->
                        if (session.isClosed.get()) return@use
                        val rawW = nextPage.width.coerceAtLeast(1)
                        val rawH = nextPage.height.coerceAtLeast(1)
                        val targetW = (baseWidthPx * scaleBucket).coerceIn(480, 2400)
                        val targetH = ((targetW.toFloat() / rawW.toFloat()) * rawH.toFloat()).roundToInt().coerceIn(480, 3200)
                        val renderBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        renderBmp.eraseColor(android.graphics.Color.WHITE)
                        val matrix = Matrix().apply {
                            setScale(targetW.toFloat() / rawW.toFloat(), targetH.toFloat() / rawH.toFloat())
                        }
                        nextPage.render(renderBmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val compactBmp = renderBmp.copy(Bitmap.Config.RGB_565, false)
                        if (compactBmp != null) renderBmp.recycle()
                        bitmapCache.put(nextKey, compactBmp ?: renderBmp)
                    }
                }
            }
        }
    }

    LaunchedEffect(session, pageIndex, scaleBucket, baseWidthPx) {
        val nextIdx = pageIndex + 1
        val nextKey = "${nextIdx}_$scaleBucket"
        if (nextIdx >= session.pageCount || bitmapCache.get(nextKey) != null) return@LaunchedEffect
        delay(220L)
        withContext(Dispatchers.IO) {
            try {
                session.mutex.withLock {
                    if (session.isClosed.get() || bitmapCache.get(nextKey) != null) return@withLock
                    session.renderer.openPage(nextIdx).use { nextPage ->
                        if (session.isClosed.get()) return@use
                        val rawW = nextPage.width.coerceAtLeast(1)
                        val rawH = nextPage.height.coerceAtLeast(1)
                        val targetW = (baseWidthPx * scaleBucket).coerceIn(480, 2400)
                        val targetH = ((targetW.toFloat() / rawW.toFloat()) * rawH.toFloat()).roundToInt().coerceIn(480, 3200)
                        val renderBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        renderBmp.eraseColor(android.graphics.Color.WHITE)
                        val matrix = Matrix().apply {
                            setScale(targetW.toFloat() / rawW.toFloat(), targetH.toFloat() / rawH.toFloat())
                        }
                        nextPage.render(renderBmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val compactBmp = renderBmp.copy(Bitmap.Config.RGB_565, false)
                        if (compactBmp != null) renderBmp.recycle()
                        bitmapCache.put(nextKey, compactBmp ?: renderBmp)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    val latestZoom by rememberUpdatedState(zoom)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)
    val latestOnPrevPage by rememberUpdatedState(onPrevPage)
    val latestOnNextPage by rememberUpdatedState(onNextPage)
    val latestOnToggleHud by rememberUpdatedState(onToggleHud)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(tapZonesEnabled, zoom) {
                detectTapGestures(
                    onDoubleTap = {
                        val nextZoom = if (latestZoom > 1.15f) 1f else 1.75f
                        latestOnZoomChange(nextZoom)
                        if (nextZoom == 1f) panOffset = Offset.Zero
                    },
                    onTap = { pos ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        if (tapZonesEnabled && latestZoom <= 1.05f) {
                            when {
                                pos.x < width * 0.24f -> latestOnPrevPage()
                                pos.x > width * 0.76f -> latestOnNextPage()
                                else -> latestOnToggleHud()
                            }
                        } else {
                            latestOnToggleHud()
                        }
                    }
                )
            }
            .pointerInput(pageIndex) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var multiTouchUsed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) break
                        if (pressedCount >= 2) {
                            multiTouchUsed = true
                            val gestureZoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val curZoom = latestZoom
                            val updatedZoom = (curZoom * gestureZoom).coerceIn(1f, 3f)
                            if (gestureZoom != 1f) {
                                latestOnZoomChange(updatedZoom)
                            }
                            if (updatedZoom > 1.02f && pan != Offset.Zero) {
                                val maxPanX = (size.width * (updatedZoom - 1f)) / 2f
                                val maxPanY = (size.height * (updatedZoom - 1f)) / 2f
                                panOffset = Offset(
                                    x = (panOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                    y = (panOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                                )
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (!multiTouchUsed && latestZoom > 1.05f) {
                            val pan = event.calculatePan()
                            if (pan != Offset.Zero) {
                                val curZoom = latestZoom
                                val maxPanX = (size.width * (curZoom - 1f)) / 2f
                                val maxPanY = (size.height * (curZoom - 1f)) / 2f
                                panOffset = Offset(
                                    x = (panOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                    y = (panOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                                )
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = pageBitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Página ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                colorFilter = colorFilter,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 12.dp)
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panOffset.x
                        translationY = panOffset.y
                    }
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
    }
}

// =====================================================================
// Leitor Paginado / Rolagem Contínua de TXT & Markdown
// =====================================================================

@Composable
private fun ReadEraTextViewport(
    pages: List<String>,
    currentPage: Int,
    scrollMode: ReadEraScrollMode,
    tapZonesEnabled: Boolean,
    fontFamily: FontFamily,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    marginPreset: ReadEraMarginPreset,
    textColor: Color,
    secondaryTextColor: Color,
    surfaceColor: Color,
    accentColor: Color,
    bookmarks: Set<Int>,
    onPageChanged: (Int) -> Unit,
    onToggleHud: () -> Unit,
    onQuickQuotePage: (String) -> Unit
) {
    val totalPages = pages.size.coerceAtLeast(1)

    if (scrollMode == ReadEraScrollMode.PAGINATED) {
        val pagerState = rememberPagerState(
            initialPage = (currentPage - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0)),
            pageCount = { totalPages }
        )

        LaunchedEffect(currentPage, totalPages) {
            val targetIdx = (currentPage - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            if (pagerState.currentPage != targetIdx) {
                pagerState.scrollToPage(targetIdx)
            }
        }

        LaunchedEffect(pagerState.currentPage) {
            val pageOneBased = pagerState.currentPage + 1
            if (pageOneBased != currentPage) {
                onPageChanged(pageOneBased)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            val pageText = pages.getOrElse(pageIndex) { "" }
            val pageNum = pageIndex + 1
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tapZonesEnabled, currentPage, totalPages) {
                        detectTapGestures(
                            onTap = { pos ->
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                if (tapZonesEnabled) {
                                    when {
                                        pos.x < width * 0.24f -> if (currentPage > 1) onPageChanged(currentPage - 1) else onToggleHud()
                                        pos.x > width * 0.76f -> if (currentPage < totalPages) onPageChanged(currentPage + 1) else onToggleHud()
                                        else -> onToggleHud()
                                    }
                                } else {
                                    onToggleHud()
                                }
                            }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = marginPreset.horizontalDp.dp,
                            end = marginPreset.horizontalDp.dp,
                            top = (marginPreset.verticalDp + 44).dp,
                            bottom = (marginPreset.verticalDp + 68).dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pageNum in bookmarks) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Bookmark, null, tint = accentColor, modifier = Modifier.size(15.dp))
                            Text(
                                "Página $pageNum marcada",
                                color = accentColor,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    SelectionContainer {
                        ReadEraFormattedTextBlock(
                            text = pageText,
                            fontFamily = fontFamily,
                            fontSizeSp = fontSizeSp,
                            lineHeightMultiplier = lineHeightMultiplier,
                            textColor = textColor,
                            secondaryTextColor = secondaryTextColor,
                            surfaceColor = surfaceColor,
                            accentColor = accentColor
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { onQuickQuotePage(pageText) }) {
                            Icon(Icons.Default.FormatQuote, null, tint = accentColor, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Citar trecho desta página",
                                color = accentColor,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    } else {
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = (currentPage - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        )
        val latestCurrentPage by rememberUpdatedState(currentPage)
        val latestTotalPages by rememberUpdatedState(totalPages)
        val latestOnPageChanged by rememberUpdatedState(onPageChanged)

        LaunchedEffect(currentPage, totalPages) {
            val targetIdx = (currentPage - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != targetIdx) {
                listState.scrollToItem(targetIdx)
            }
        }

        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { idx ->
                    val pageNum = idx + 1
                    if (pageNum in 1..latestTotalPages && pageNum != latestCurrentPage) {
                        latestOnPageChanged(pageNum)
                    }
                }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onToggleHud() })
                },
            contentPadding = PaddingValues(
                start = marginPreset.horizontalDp.dp,
                end = marginPreset.horizontalDp.dp,
                top = 64.dp,
                bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            itemsIndexed(pages, key = { idx, _ -> idx }) { idx, pageText ->
                val pageNum = idx + 1
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "PÁGINA $pageNum",
                            color = secondaryTextColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (pageNum in bookmarks) {
                            Icon(Icons.Default.Bookmark, null, tint = accentColor, modifier = Modifier.size(16.dp))
                        }
                    }
                    SelectionContainer {
                        ReadEraFormattedTextBlock(
                            text = pageText,
                            fontFamily = fontFamily,
                            fontSizeSp = fontSizeSp,
                            lineHeightMultiplier = lineHeightMultiplier,
                            textColor = textColor,
                            secondaryTextColor = secondaryTextColor,
                            surfaceColor = surfaceColor,
                            accentColor = accentColor
                        )
                    }
                    HorizontalDivider(color = secondaryTextColor.copy(alpha = 0.15f))
                }
            }
        }
    }
}

@Composable
private fun ReadEraFormattedTextBlock(
    text: String,
    fontFamily: FontFamily,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    textColor: Color,
    secondaryTextColor: Color,
    surfaceColor: Color,
    accentColor: Color
) {
    val blocks = remember(text) { text.split(Regex("\n{2,}")) }
    val lineHeightSp = (fontSizeSp * lineHeightMultiplier).sp

    Column(verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.65f).dp)) {
        blocks.forEach { rawBlock ->
            val block = rawBlock.trim()
            if (block.isEmpty()) return@forEach
            when {
                block.startsWith("# ") -> {
                    Text(
                        text = block.removePrefix("# ").trim(),
                        color = textColor,
                        fontFamily = fontFamily,
                        fontSize = (fontSizeSp * 1.38f).sp,
                        lineHeight = (fontSizeSp * 1.38f * 1.25f).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                block.startsWith("## ") -> {
                    Text(
                        text = block.removePrefix("## ").trim(),
                        color = textColor,
                        fontFamily = fontFamily,
                        fontSize = (fontSizeSp * 1.2f).sp,
                        lineHeight = (fontSizeSp * 1.2f * 1.28f).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                block.startsWith("### ") -> {
                    Text(
                        text = block.removePrefix("### ").trim(),
                        color = accentColor,
                        fontFamily = fontFamily,
                        fontSize = (fontSizeSp * 1.08f).sp,
                        lineHeight = (fontSizeSp * 1.08f * 1.3f).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                block.startsWith(">") -> {
                    val quoteClean = block.lines().joinToString("\n") { it.trimStart().removePrefix(">").trimStart() }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = surfaceColor.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = quoteClean,
                            color = secondaryTextColor,
                            fontFamily = fontFamily,
                            fontStyle = FontStyle.Italic,
                            fontSize = fontSizeSp.sp,
                            lineHeight = lineHeightSp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                else -> {
                    if (containsMathSyntax(block)) {
                        RichInlineMathText(
                            text = block,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = fontFamily,
                                fontSize = fontSizeSp.sp,
                                lineHeight = lineHeightSp
                            ),
                            color = textColor
                        )
                    } else {
                        Text(
                            text = block,
                            color = textColor,
                            fontFamily = fontFamily,
                            fontSize = fontSizeSp.sp,
                            lineHeight = lineHeightSp
                        )
                    }
                }
            }
        }
    }
}

// =====================================================================
// Modo Notas / Resumo Embutido (quando não há PDF/TXT vinculado)
// =====================================================================

@Composable
private fun ReadEraEmbeddedBookViewport(
    book: Book,
    highlights: List<BookHighlight>,
    linkedNotes: List<Note>,
    currentPage: Int,
    totalPages: Int,
    fontFamily: FontFamily,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    marginPreset: ReadEraMarginPreset,
    textColor: Color,
    secondaryTextColor: Color,
    surfaceColor: Color,
    accentColor: Color,
    onToggleHud: () -> Unit,
    onAttachFile: () -> Unit,
    onEditSummary: () -> Unit,
    onAddHighlight: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onCreateLinkedNote: () -> Unit,
    onAdvancePage: (Int) -> Unit
) {
    val lineHeightSp = (fontSizeSp * lineHeightMultiplier).sp

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleHud() })
            },
        contentPadding = PaddingValues(
            start = marginPreset.horizontalDp.dp,
            end = marginPreset.horizontalDp.dp,
            top = 70.dp,
            bottom = 130.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Banner to attach local PDF / TXT / MD
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = surfaceColor,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = accentColor.copy(alpha = 0.16f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = accentColor)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Modo Leitura ReadEra · Livro Físico / Fichamento",
                                color = textColor,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Acompanhe seu livro físico com timer, páginas e citações, ou importe um arquivo PDF, TXT ou Markdown do aparelho.",
                                color = secondaryTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onAttachFile,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Importar PDF / TXT / MD", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    // Quick page advance chips for physical book reading
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Avançar pág.:",
                            color = secondaryTextColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                        listOf(1, 5, 10, 15).forEach { delta ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = accentColor.copy(alpha = 0.14f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onAdvancePage(delta) }
                            ) {
                                Text(
                                    "+$delta pág${if (delta > 1) "s" else ""}",
                                    color = accentColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Book Title, Chapter & Summary in ReadEra typography
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = book.title,
                    color = textColor,
                    fontFamily = fontFamily,
                    fontSize = (fontSizeSp * 1.32f).sp,
                    lineHeight = (fontSizeSp * 1.32f * 1.25f).sp,
                    fontWeight = FontWeight.Bold
                )
                if (book.author.isNotBlank()) {
                    Text(
                        text = book.author,
                        color = secondaryTextColor,
                        fontFamily = fontFamily,
                        fontSize = (fontSizeSp * 0.95f).sp
                    )
                }
                if (book.currentChapter.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "Capítulo atual: ${book.currentChapter}",
                            color = accentColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(color = secondaryTextColor.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "RESUMO & FICHAMENTO",
                        color = accentColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onEditSummary) {
                        Icon(Icons.Default.Edit, null, tint = accentColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Editar fichamento", color = accentColor, style = MaterialTheme.typography.labelSmall)
                    }
                }

                SelectionContainer {
                    Text(
                        text = book.summary.ifBlank {
                            "Nenhum resumo ou fichamento escrito ainda para este livro. Toque em “Editar fichamento” acima para escrever seus pontos-chave diretamente no visual ReadEra."
                        },
                        color = if (book.summary.isBlank()) secondaryTextColor else textColor,
                        fontFamily = fontFamily,
                        fontSize = fontSizeSp.sp,
                        lineHeight = lineHeightSp
                    )
                }
            }
        }

        // Highlights section styled in ReadEra palette
        item {
            HorizontalDivider(color = secondaryTextColor.copy(alpha = 0.2f))
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "DESTAQUES & CITAÇÕES (${highlights.size})",
                    color = accentColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onAddHighlight) {
                    Icon(Icons.Default.Add, null, tint = accentColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nova citação", color = accentColor, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (highlights.isEmpty()) {
            item {
                Text(
                    "Nenhuma citação destacada ainda. Use o botão “Destacar / Citação” na barra inferior para salvar trechos importantes.",
                    color = secondaryTextColor,
                    fontFamily = fontFamily,
                    fontSize = (fontSizeSp * 0.9f).sp,
                    lineHeight = (fontSizeSp * 0.9f * lineHeightMultiplier).sp
                )
            }
        } else {
            itemsIndexed(highlights, key = { _, hl -> hl.id }) { _, hl ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = surfaceColor,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hl.page != null && hl.page > 0) {
                                Text(
                                    "Pág. ${hl.page}",
                                    color = accentColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (hl.chapter.isNotBlank()) {
                                Text(
                                    hl.chapter,
                                    color = secondaryTextColor,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = "“${hl.text}”",
                                color = textColor,
                                fontFamily = fontFamily,
                                fontStyle = FontStyle.Italic,
                                fontSize = fontSizeSp.sp,
                                lineHeight = lineHeightSp
                            )
                        }
                        if (hl.note.isNotBlank()) {
                            Text(
                                text = "💡 ${hl.note}",
                                color = secondaryTextColor,
                                fontFamily = fontFamily,
                                fontSize = (fontSizeSp * 0.88f).sp
                            )
                        }
                    }
                }
            }
        }

        // Linked Second Brain Notes in ReadEra palette
        item {
            HorizontalDivider(color = secondaryTextColor.copy(alpha = 0.2f))
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "NOTAS VINCULADAS (${linkedNotes.size})",
                    color = accentColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onCreateLinkedNote) {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, null, tint = accentColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Criar nota", color = accentColor, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (linkedNotes.isEmpty()) {
            item {
                Text(
                    "Nenhuma nota do Segundo Cérebro vinculada a este livro ainda.",
                    color = secondaryTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            itemsIndexed(linkedNotes, key = { _, note -> note.id }) { _, note ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = surfaceColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenNote(note.id) }
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                note.title,
                                color = textColor,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = (fontSizeSp * 1.02f).sp
                            )
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = accentColor, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            text = note.markdown.take(360).trim(),
                            color = secondaryTextColor,
                            fontFamily = fontFamily,
                            fontSize = (fontSizeSp * 0.9f).sp,
                            lineHeight = (fontSizeSp * 0.9f * lineHeightMultiplier).sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadEraDocumentErrorView(
    book: Book,
    errorMessage: String,
    textColor: Color,
    secondaryTextColor: Color,
    surfaceColor: Color,
    accentColor: Color,
    onReopenDocument: () -> Unit,
    onUseEmbeddedNotesMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = surfaceColor,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    "Arquivo local precisa ser reaberto",
                    color = textColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    errorMessage,
                    color = secondaryTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onReopenDocument,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reabrir arquivo (PDF / TXT / MD)")
                }
                TextButton(onClick = onUseEmbeddedNotesMode) {
                    Text(
                        "Continuar no Modo Estudo / Fichamento de “${book.title}”",
                        color = accentColor,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// =====================================================================
// Bottom Sheet ReadEra "Aa" (Temas, Brilho, Tipografia, Margens, Tela)
// =====================================================================

@Composable
private fun ReadEraSettingsSheetContent(
    prefs: ReaderPreferencesSnapshot,
    isPdf: Boolean,
    invertPdfInDark: Boolean,
    onInvertPdfInDarkChange: (Boolean) -> Unit,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    onUpdatePrefs: ((ReaderPreferencesSnapshot) -> ReaderPreferencesSnapshot) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Configurações de Leitura (ReadEra)",
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // 1. Color Themes (Dia, Sépia, Crepúsculo, Noturno AMOLED)
        Text(
            "Tema de cor",
            color = secondaryTextColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReadEraTheme.entries.forEach { item ->
                val selected = prefs.theme == item
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(item.bgHex),
                    border = BorderStroke(
                        width = if (selected) 2.5.dp else 1.dp,
                        color = if (selected) Color(item.accentHex) else Color(item.secondaryTextHex).copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onUpdatePrefs { it.copy(theme = item) } }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Aa",
                            color = Color(item.textHex),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            item.label,
                            color = Color(item.secondaryTextHex),
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (isPdf && prefs.theme.isDark) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Modo Noturno Inteligente no PDF",
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Adapta páginas claras do PDF para o tema escuro ReadEra",
                        color = secondaryTextColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Switch(
                    checked = invertPdfInDark,
                    onCheckedChange = onInvertPdfInDarkChange
                )
            }
        }

        // 2. Screen Brightness Control
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Brightness6, null, tint = accentColor, modifier = Modifier.size(18.dp))
                    Text(
                        "Brilho da tela",
                        color = secondaryTextColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FilterChip(
                    selected = prefs.brightness < 0f,
                    onClick = {
                        onUpdatePrefs {
                            it.copy(brightness = if (it.brightness < 0f) 0.65f else -1f)
                        }
                    },
                    label = { Text("Automático (Sistema)") }
                )
            }
            Slider(
                value = if (prefs.brightness < 0f) 0.65f else prefs.brightness.coerceIn(0.05f, 1.0f),
                onValueChange = { value ->
                    onUpdatePrefs { it.copy(brightness = value.coerceIn(0.05f, 1.0f)) }
                },
                valueRange = 0.05f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor
                )
            )
        }

        // 3. Font Family (Serif, Sans-Serif, Monospace)
        Text(
            "Fonte",
            color = secondaryTextColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReadEraFontFamily.entries.forEachIndexed { idx, font ->
                SegmentedButton(
                    selected = prefs.fontFamily == font,
                    onClick = { onUpdatePrefs { it.copy(fontFamily = font) } },
                    shape = SegmentedButtonDefaults.itemShape(idx, ReadEraFontFamily.entries.size)
                ) {
                    Text(font.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // 4. Font Size (14sp..28sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tamanho da fonte: ${prefs.fontSizeSp.roundToInt()} sp",
                    color = secondaryTextColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { onUpdatePrefs { it.copy(fontSizeSp = (it.fontSizeSp - 1f).coerceAtLeast(ReaderPreferencesStore.MIN_FONT_SIZE_SP)) } },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text("A-") }
                    OutlinedButton(
                        onClick = { onUpdatePrefs { it.copy(fontSizeSp = (it.fontSizeSp + 1f).coerceAtMost(ReaderPreferencesStore.MAX_FONT_SIZE_SP)) } },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text("A+") }
                }
            }
            Slider(
                value = prefs.fontSizeSp,
                onValueChange = { sz -> onUpdatePrefs { it.copy(fontSizeSp = sz) } },
                valueRange = ReaderPreferencesStore.MIN_FONT_SIZE_SP..ReaderPreferencesStore.MAX_FONT_SIZE_SP,
                colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
            )
        }

        // 5. Line Spacing (1.25f..1.95f)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Espaçamento entre linhas: ${String.format(Locale.ROOT, "%.2fx", prefs.lineHeightMultiplier)}",
                color = secondaryTextColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = prefs.lineHeightMultiplier,
                onValueChange = { lh -> onUpdatePrefs { it.copy(lineHeightMultiplier = lh) } },
                valueRange = ReaderPreferencesStore.MIN_LINE_HEIGHT..ReaderPreferencesStore.MAX_LINE_HEIGHT,
                colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
            )
        }

        // 6. Margins (Estreita, Média, Larga)
        Text(
            "Margens da página",
            color = secondaryTextColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReadEraMarginPreset.entries.forEachIndexed { idx, margin ->
                SegmentedButton(
                    selected = prefs.marginPreset == margin,
                    onClick = { onUpdatePrefs { it.copy(marginPreset = margin) } },
                    shape = SegmentedButtonDefaults.itemShape(idx, ReadEraMarginPreset.entries.size)
                ) {
                    Text(margin.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // 7. Page Navigation Mode (Paginado vs Rolagem Contínua)
        Text(
            "Modo de rolagem",
            color = secondaryTextColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReadEraScrollMode.entries.forEachIndexed { idx, mode ->
                SegmentedButton(
                    selected = prefs.scrollMode == mode,
                    onClick = { onUpdatePrefs { it.copy(scrollMode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(idx, ReadEraScrollMode.entries.size)
                ) {
                    Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        HorizontalDivider(color = secondaryTextColor.copy(alpha = 0.2f))

        // 8. Keep Screen On, Orientation Lock, Tap Zones
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Manter tela acesa", color = textColor, fontWeight = FontWeight.Medium)
                Text("Impede que a tela apague durante a leitura", color = secondaryTextColor, style = MaterialTheme.typography.labelSmall)
            }
            Switch(
                checked = prefs.keepScreenOn,
                onCheckedChange = { checked -> onUpdatePrefs { it.copy(keepScreenOn = checked) } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Travar orientação da tela", color = textColor, fontWeight = FontWeight.Medium)
                Text("Evita rotação acidental ao ler deitado", color = secondaryTextColor, style = MaterialTheme.typography.labelSmall)
            }
            Switch(
                checked = prefs.lockOrientation,
                onCheckedChange = { checked -> onUpdatePrefs { it.copy(lockOrientation = checked) } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Toque nas bordas para virar página", color = textColor, fontWeight = FontWeight.Medium)
                Text("Bordas esquerda/direita avançam ou voltam páginas", color = secondaryTextColor, style = MaterialTheme.typography.labelSmall)
            }
            Switch(
                checked = prefs.tapZonesEnabled,
                onCheckedChange = { checked -> onUpdatePrefs { it.copy(tapZonesEnabled = checked) } }
            )
        }
    }
}

// =====================================================================
// Gaveta ReadEra: Marcadores de Página & Destaques Salvos
// =====================================================================

@Composable
private fun ReadEraContentsAndBookmarksSheet(
    book: Book,
    bookmarks: List<Int>,
    highlights: List<BookHighlight>,
    currentPage: Int,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    onSelectPage: (Int) -> Unit,
    onRemoveBookmark: (Int) -> Unit,
    onAddHighlightClick: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            book.title,
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = tab == 0,
                onClick = { tab = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) {
                Text("Destaques (${highlights.size})")
            }
            SegmentedButton(
                selected = tab == 1,
                onClick = { tab = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) {
                Text("Marcadores (${bookmarks.size})")
            }
        }

        if (tab == 0) {
            if (highlights.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.FormatQuote, null, tint = accentColor, modifier = Modifier.size(32.dp))
                    Text("Nenhuma citação salva ainda", color = textColor, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = onAddHighlightClick) {
                        Text("Destacar página $currentPage")
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    itemsIndexed(highlights, key = { _, hl -> hl.id }) { _, hl ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = secondaryTextColor.copy(alpha = 0.1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    hl.page?.takeIf { it > 0 }?.let(onSelectPage)
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = hl.page?.let { "Página $it" } ?: "Destaque",
                                        color = accentColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (hl.chapter.isNotBlank()) {
                                        Text(
                                            text = hl.chapter,
                                            color = secondaryTextColor,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                Text(
                                    text = "“${hl.text}”",
                                    color = textColor,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        } else {
            if (bookmarks.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.BookmarkBorder, null, tint = accentColor, modifier = Modifier.size(32.dp))
                    Text("Nenhuma página marcada", color = textColor, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Toque no ícone de marcador na barra superior para guardar páginas importantes.",
                        color = secondaryTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    itemsIndexed(bookmarks, key = { _, page -> page }) { _, page ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = secondaryTextColor.copy(alpha = 0.1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectPage(page) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Bookmark, null, tint = accentColor, modifier = Modifier.size(18.dp))
                                    Text(
                                        "Página $page",
                                        color = textColor,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveBookmark(page) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Remover marcador", tint = secondaryTextColor, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadEraHighlightDialog(
    book: Book,
    currentPage: Int,
    initialQuote: String,
    onDismiss: () -> Unit,
    onSave: (quote: String, page: Int?, chapter: String, note: String) -> Unit
) {
    var quoteText by remember(initialQuote) { mutableStateOf(initialQuote) }
    var pageText by remember(currentPage) { mutableStateOf(currentPage.toString()) }
    var chapterText by remember(book.currentChapter) { mutableStateOf(book.currentChapter) }
    var noteText by remember { mutableStateOf("") }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Destacar Citação", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = quoteText,
                    onValueChange = { quoteText = it },
                    label = { Text("Trecho / Citação do livro *") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pageText,
                        onValueChange = { pageText = it.filter(Char::isDigit).take(6) },
                        label = { Text("Página") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.9f)
                    )
                    OutlinedTextField(
                        value = chapterText,
                        onValueChange = { chapterText = it },
                        label = { Text("Capítulo") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Nota pessoal / Reflexão (opcional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    enabled = quoteText.isNotBlank(),
                    onClick = {
                        val pageNum = pageText.toIntOrNull()?.takeIf { it > 0 } ?: currentPage
                        val (extractTitle, extractBody) = IncrementalReadingEngine.buildIncrementalExtract(
                            bookTitle = book.title,
                            page = pageNum,
                            excerpt = quoteText.trim()
                        )
                        if (chapterText.isBlank()) chapterText = extractTitle
                        noteText = extractBody
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Gerar Extrato de Leitura Incremental (Feynman)")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = quoteText.isNotBlank(),
                onClick = {
                    onSave(
                        quoteText.trim(),
                        pageText.toIntOrNull()?.takeIf { it > 0 },
                        chapterText.trim(),
                        noteText.trim()
                    )
                }
            ) {
                Text("Salvar destaque")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

object IncrementalReadingEngine {
    fun buildIncrementalExtract(bookTitle: String, page: Int, excerpt: String): Pair<String, String> {
        val cleanTitle = "Leitura Incremental · $bookTitle (p. $page)"
        val body = "> $excerpt\n\n#leitura-incremental\n- [ ] Destilar pelo Método de Feynman (p. $page)"
        return cleanTitle to body
    }
}
