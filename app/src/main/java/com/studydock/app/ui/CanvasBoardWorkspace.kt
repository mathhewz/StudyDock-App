@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.DashPathEffect
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.*



data class CanvasNodeItem(
    val id: String,
    val type: CanvasNodeType,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val title: String = "",
    val text: String = "",
    val noteId: Long? = null,
    val colorArgb: Int = ObsidianPalette[0]
)

data class CanvasEdgeItem(
    val id: String,
    val fromNode: String,
    val toNode: String,
    val label: String = "",
    val colorArgb: Int? = null
)

data class CanvasDocument(
    val nodes: List<CanvasNodeItem> = emptyList(),
    val edges: List<CanvasEdgeItem> = emptyList(),
    val viewportPanX: Float = 0f,
    val viewportPanY: Float = 0f,
    val viewportScale: Float = 1f
)

private fun finiteFloat(value: Double, fallback: Float): Float =
    if (value.isFinite()) value.toFloat() else fallback

private fun safeFinite(value: Float, fallback: Float): Double =
    if (value.isFinite()) value.toDouble() else fallback.toDouble()

private fun parseCanvasDocument(jsonData: String): CanvasDocument {
    if (jsonData.isBlank()) return CanvasDocument()
    return try {
        val root = JSONObject(jsonData)
        val nodesArr = root.optJSONArray("nodes") ?: JSONArray()
        val edgesArr = root.optJSONArray("edges") ?: JSONArray()

        val nodes = buildList {
            for (i in 0 until nodesArr.length()) {
                val obj = nodesArr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() }
                val type = CanvasNodeType.fromWire(obj.optString("type", "text"))
                val defaultW = if (type == CanvasNodeType.GROUP) 460f else 240f
                val defaultH = if (type == CanvasNodeType.GROUP) 320f else 150f
                val noteIdVal = if (obj.has("noteId") && !obj.isNull("noteId")) {
                    obj.optLong("noteId", -1L).takeIf { it > 0L }
                } else null
                add(
                    CanvasNodeItem(
                        id = id,
                        type = type,
                        x = finiteFloat(obj.optDouble("x", 0.0), 0f),
                        y = finiteFloat(obj.optDouble("y", 0.0), 0f),
                        width = finiteFloat(obj.optDouble("width", defaultW.toDouble()), defaultW).coerceAtLeast(120f),
                        height = finiteFloat(obj.optDouble("height", defaultH.toDouble()), defaultH).coerceAtLeast(80f),
                        title = obj.optString("title", obj.optString("label", "")),
                        text = obj.optString("text", ""),
                        noteId = noteIdVal,
                        colorArgb = obj.optInt("color", ObsidianPalette[0])
                    )
                )
            }
        }

        val validNodeIds = nodes.map { it.id }.toSet()
        val edges = buildList {
            for (i in 0 until edgesArr.length()) {
                val obj = edgesArr.optJSONObject(i) ?: continue
                val from = obj.optString("fromNode", "")
                val to = obj.optString("toNode", "")
                if (from.isBlank() || to.isBlank() || from == to || from !in validNodeIds || to !in validNodeIds) continue
                val colorVal = if (obj.has("color") && !obj.isNull("color")) obj.optInt("color") else null
                add(
                    CanvasEdgeItem(
                        id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                        fromNode = from,
                        toNode = to,
                        label = obj.optString("label", ""),
                        colorArgb = colorVal
                    )
                )
            }
        }

        val vp = root.optJSONObject("viewport")
        CanvasDocument(
            nodes = nodes,
            edges = edges,
            viewportPanX = vp?.let { finiteFloat(it.optDouble("panX", 0.0), 0f) } ?: 0f,
            viewportPanY = vp?.let { finiteFloat(it.optDouble("panY", 0.0), 0f) } ?: 0f,
            viewportScale = (vp?.let { finiteFloat(it.optDouble("scale", 1.0), 1f) } ?: 1f).coerceIn(0.25f, 2.5f)
        )
    } catch (_: Exception) {
        CanvasDocument()
    }
}

private fun encodeCanvasDocument(doc: CanvasDocument): String {
    val root = JSONObject()
    val nodesArr = JSONArray()
    doc.nodes.forEach { n ->
        nodesArr.put(
            JSONObject().apply {
                put("id", n.id)
                put("type", n.type.wire)
                put("x", safeFinite(n.x, 0f))
                put("y", safeFinite(n.y, 0f))
                put("width", safeFinite(n.width, 240f))
                put("height", safeFinite(n.height, 150f))
                put("title", n.title)
                put("text", n.text)
                if (n.noteId != null) put("noteId", n.noteId)
                put("color", n.colorArgb)
            }
        )
    }
    val edgesArr = JSONArray()
    doc.edges.forEach { e ->
        edgesArr.put(
            JSONObject().apply {
                put("id", e.id)
                put("fromNode", e.fromNode)
                put("toNode", e.toNode)
                put("label", e.label)
                if (e.colorArgb != null) put("color", e.colorArgb)
            }
        )
    }
    root.put("version", 1)
    root.put("nodes", nodesArr)
    root.put("edges", edgesArr)
    root.put(
        "viewport",
        JSONObject().apply {
            put("panX", safeFinite(doc.viewportPanX, 0f))
            put("panY", safeFinite(doc.viewportPanY, 0f))
            put("scale", safeFinite(doc.viewportScale, 1f))
        }
    )
    return root.toString()
}

private data class CanvasBezierGeometry(
    val p0: Offset,
    val p1: Offset,
    val p2: Offset,
    val p3: Offset,
    val midpoint: Offset
)

/**
 * Computes screen-space cubic Bezier endpoints docked cleanly onto the perimeters of [from] and [to].
 */
private fun computeEdgeBezier(
    from: CanvasNodeItem,
    to: CanvasNodeItem,
    densityPx: Float,
    scale: Float,
    pan: Offset,
    vCenter: Offset
): CanvasBezierGeometry {
    fun toScreen(wx: Float, wy: Float): Offset =
        Offset(wx * densityPx * scale + pan.x + vCenter.x, wy * densityPx * scale + pan.y + vCenter.y)

    val fromCenter = Offset(from.x + from.width / 2f, from.y + from.height / 2f)
    val toCenter = Offset(to.x + to.width / 2f, to.y + to.height / 2f)
    val dx = toCenter.x - fromCenter.x
    val dy = toCenter.y - fromCenter.y

    val (p0World, p3World, horizontal) = if (abs(dx) >= abs(dy)) {
        if (dx >= 0f) {
            Triple(
                Offset(from.x + from.width, fromCenter.y),
                Offset(to.x, toCenter.y),
                true
            )
        } else {
            Triple(
                Offset(from.x, fromCenter.y),
                Offset(to.x + to.width, toCenter.y),
                true
            )
        }
    } else {
        if (dy >= 0f) {
            Triple(
                Offset(fromCenter.x, from.y + from.height),
                Offset(toCenter.x, to.y),
                false
            )
        } else {
            Triple(
                Offset(fromCenter.x, from.y),
                Offset(toCenter.x, to.y + to.height),
                false
            )
        }
    }

    val p0 = toScreen(p0World.x, p0World.y)
    val p3 = toScreen(p3World.x, p3World.y)
    val handleDist = if (horizontal) {
        max(44f * scale, abs(p3.x - p0.x) * 0.45f)
    } else {
        max(44f * scale, abs(p3.y - p0.y) * 0.45f)
    }

    val (p1, p2) = if (horizontal) {
        val dir = if (p3.x >= p0.x) 1f else -1f
        Offset(p0.x + dir * handleDist, p0.y) to Offset(p3.x - dir * handleDist, p3.y)
    } else {
        val dir = if (p3.y >= p0.y) 1f else -1f
        Offset(p0.x, p0.y + dir * handleDist) to Offset(p3.x, p3.y - dir * handleDist)
    }

    val mid = cubicBezierPoint(p0, p1, p2, p3, 0.5f)
    return CanvasBezierGeometry(p0, p1, p2, p3, mid)
}

private fun cubicBezierPoint(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    val tt = t * t
    val uu = u * u
    val uuu = uu * u
    val ttt = tt * t
    return Offset(
        uuu * p0.x + 3f * uu * t * p1.x + 3f * u * tt * p2.x + ttt * p3.x,
        uuu * p0.y + 3f * uu * t * p1.y + 3f * u * tt * p2.y + ttt * p3.y
    )
}

private fun distanceToBezier(geom: CanvasBezierGeometry, pt: Offset): Float {
    var minDist = Float.MAX_VALUE
    for (step in 0..14) {
        val t = step / 14f
        val curvePt = cubicBezierPoint(geom.p0, geom.p1, geom.p2, geom.p3, t)
        val d = (curvePt - pt).getDistance()
        if (d < minDist) minDist = d
    }
    return minDist
}

private fun stripNotePreview(markdown: String): String {
    val body = NoteFrontmatter.body(markdown)
    return body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("```") && !it.startsWith("---") }
        .take(5)
        .joinToString("\n") { line ->
            line.removePrefix("#").removePrefix("#").removePrefix("#").trim()
        }
        .ifBlank { "Nota sem conteúdo" }
}

private sealed interface CanvasDragTarget {
    data object Pan : CanvasDragTarget
    data class MoveNode(val nodeId: String, val childIds: List<String>) : CanvasDragTarget
    data class ResizeNode(val nodeId: String, val minWidth: Float, val minHeight: Float) : CanvasDragTarget
}

/**
 * Cached Paint/Path/RectF objects for zero-allocation Canvas edge & dot-grid rendering.
 */
private class CanvasBoardDrawCache {
    val dotPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }
    val edgePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
    }
    val arrowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }
    val labelPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val pillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }
    val pillStrokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.STROKE }
    val edgePath = AndroidPath()
    val arrowPath = AndroidPath()
    val pillRect = RectF()
    val fontMetrics = AndroidPaint.FontMetrics()
}

/**
 * Full-bleed infinite Obsidian-style visual whiteboard (.canvas) workspace supporting free-text cards,
 * Vault note cards, layered visual groups, directional labeled Bezier connections,
 * pinch/pan navigation, card resizing, undo stack, and debounced auto-save.
 */
@Composable
fun CanvasBoardWorkspace(
    boards: List<CanvasBoard>,
    notes: List<Note>,
    onSaveBoard: suspend (CanvasBoard) -> Long,
    onDeleteBoard: suspend (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onCreateNote: (() -> Unit)? = null,
    showHeader: Boolean = true,
    requestedBoardId: Long? = null,
    onRequestedBoardConsumed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val densityPx = density.density
    val touchSlop = LocalViewConfiguration.current.touchSlop

    val activeNotes = remember(notes) { notes.filter { it.trashedAt == null } }
    val notesById = remember(activeNotes) { activeNotes.associateBy { it.id } }

    var activeBoardId by rememberSaveable { mutableStateOf(boards.firstOrNull()?.id) }
    var pendingAutoFitBoardId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(boards, requestedBoardId) {
        if (requestedBoardId != null && boards.any { it.id == requestedBoardId }) {
            activeBoardId = requestedBoardId
            pendingAutoFitBoardId = requestedBoardId
            onRequestedBoardConsumed()
        } else if (boards.isEmpty()) {
            activeBoardId = null
        } else if (activeBoardId == null || boards.none { it.id == activeBoardId }) {
            activeBoardId = boards.first().id
        }
    }

    val activeBoard = remember(boards, activeBoardId) {
        boards.firstOrNull { it.id == activeBoardId }
    }

    var doc by remember { mutableStateOf(CanvasDocument()) }
    var undoStack by remember(activeBoard?.id) { mutableStateOf<List<CanvasDocument>>(emptyList()) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var loadedBoardId by remember { mutableStateOf<Long?>(null) }
    var docVersion by remember { mutableLongStateOf(0L) }
    var isSaving by remember { mutableStateOf(false) }

    // Selection & tool state
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var selectedEdgeId by remember { mutableStateOf<String?>(null) }
    var connectMode by remember { mutableStateOf(false) }
    var pendingConnectSourceId by remember { mutableStateOf<String?>(null) }
    var spawnCascadeCount by remember { mutableIntStateOf(0) }

    // Sheet & menu states
    var showBoardMenu by remember { mutableStateOf(false) }
    var showCreateBoardSheet by remember { mutableStateOf(false) }
    var showRenameBoardSheet by remember { mutableStateOf(false) }
    var showDeleteBoardConfirm by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<CanvasNodeItem?>(null) }
    var colorPickerNode by remember { mutableStateOf<CanvasNodeItem?>(null) }
    var editingEdge by remember { mutableStateOf<CanvasEdgeItem?>(null) }
    var showNotePicker by remember { mutableStateOf(false) }
    var swapTargetNodeId by remember { mutableStateOf<String?>(null) }

    // Load document only when switching active board
    LaunchedEffect(activeBoard?.id) {
        val board = activeBoard
        if (board == null) {
            loadedBoardId = null
            doc = CanvasDocument()
            pan = Offset.Zero
            scale = 1f
            docVersion = 0L
        } else if (board.id != loadedBoardId) {
            val parsed = parseCanvasDocument(board.jsonData)
            doc = parsed
            pan = Offset(parsed.viewportPanX, parsed.viewportPanY)
            scale = parsed.viewportScale
            loadedBoardId = board.id
            docVersion = 0L
            undoStack = emptyList()
            selectedNodeId = null
            selectedEdgeId = null
            pendingConnectSourceId = null
            connectMode = false
        }
    }

    // Debounced auto-save (~600ms) with dispose flush so edits are never lost on navigation
    var lastSavedVersion by remember(activeBoard?.id) { mutableLongStateOf(0L) }
    val currentActiveBoard by rememberUpdatedState(activeBoard)
    val currentDoc by rememberUpdatedState(doc)
    val currentPan by rememberUpdatedState(pan)
    val currentScale by rememberUpdatedState(scale)
    val currentDocVersion by rememberUpdatedState(docVersion)
    val currentLoadedBoardId by rememberUpdatedState(loadedBoardId)
    val currentOnSaveBoard by rememberUpdatedState(onSaveBoard)
    val currentLastSavedVersion by rememberUpdatedState(lastSavedVersion)

    LaunchedEffect(activeBoard?.id, docVersion) {
        val board = activeBoard ?: return@LaunchedEffect
        val targetVersion = docVersion
        if (targetVersion == 0L || loadedBoardId != board.id) return@LaunchedEffect
        delay(600L)
        isSaving = true
        try {
            val payload = encodeCanvasDocument(
                doc.copy(viewportPanX = pan.x, viewportPanY = pan.y, viewportScale = scale)
            )
            withContext(kotlinx.coroutines.NonCancellable) {
                onSaveBoard(board.copy(jsonData = payload, updatedAt = System.currentTimeMillis()))
            }
            lastSavedVersion = targetVersion
        } catch (e: CancellationException) {
            throw e
        } finally {
            isSaving = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val board = currentActiveBoard
            val ver = currentDocVersion
            if (board != null && currentLoadedBoardId == board.id && ver > currentLastSavedVersion) {
                val payload = encodeCanvasDocument(
                    currentDoc.copy(viewportPanX = currentPan.x, viewportPanY = currentPan.y, viewportScale = currentScale)
                )
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
                    try {
                        currentOnSaveBoard(board.copy(jsonData = payload, updatedAt = System.currentTimeMillis()))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        com.studydock.app.data.StudyLog.w("KnowledgeGraph", "Falha ao salvar quadro Canvas ao sair", e)
                    }
                }
            }
        }
    }

    fun pushUndoSnapshot(snapshot: CanvasDocument) {
        undoStack = (undoStack + snapshot).takeLast(MAX_CANVAS_UNDO)
    }

    fun mutateDoc(recordUndo: Boolean = true, bumpVersion: Boolean = true, transform: (CanvasDocument) -> CanvasDocument) {
        if (recordUndo) pushUndoSnapshot(doc)
        doc = transform(doc)
        if (bumpVersion) docVersion++
    }

    fun undoLastChange() {
        val previous = undoStack.lastOrNull() ?: return
        undoStack = undoStack.dropLast(1)
        doc = previous
        if (selectedNodeId != null && previous.nodes.none { it.id == selectedNodeId }) {
            selectedNodeId = null
        }
        if (selectedEdgeId != null && previous.edges.none { it.id == selectedEdgeId }) {
            selectedEdgeId = null
        }
        docVersion++
    }

    fun spawnCenterWorldDp(): Offset {
        val offsetStep = (spawnCascadeCount % 5) * 24f - 48f
        spawnCascadeCount++
        val wx = (-pan.x / (densityPx * scale)) - 120f + offsetStep
        val wy = (-pan.y / (densityPx * scale)) - 75f + offsetStep
        return Offset(wx, wy)
    }

    fun addTextCard() {
        val pos = spawnCenterWorldDp()
        val item = CanvasNodeItem(
            id = UUID.randomUUID().toString(),
            type = CanvasNodeType.TEXT,
            x = pos.x,
            y = pos.y,
            width = 240f,
            height = 150f,
            title = "Ideia",
            text = "Toque em Editar para escrever markdown curto…",
            colorArgb = ObsidianPalette[0]
        )
        mutateDoc { it.copy(nodes = it.nodes + item) }
        selectedNodeId = item.id
        selectedEdgeId = null
        editingNode = item
    }

    fun addGroupCard() {
        val pos = spawnCenterWorldDp()
        val item = CanvasNodeItem(
            id = UUID.randomUUID().toString(),
            type = CanvasNodeType.GROUP,
            x = pos.x - 100f,
            y = pos.y - 70f,
            width = 460f,
            height = 320f,
            title = "Grupo",
            colorArgb = ObsidianPalette[2]
        )
        mutateDoc { it.copy(nodes = it.nodes + item) }
        selectedNodeId = item.id
        selectedEdgeId = null
        editingNode = item
    }

    fun addNoteCard(note: Note) {
        val pos = spawnCenterWorldDp()
        val item = CanvasNodeItem(
            id = UUID.randomUUID().toString(),
            type = CanvasNodeType.NOTE,
            x = pos.x,
            y = pos.y,
            width = 260f,
            height = 170f,
            title = note.title,
            noteId = note.id,
            colorArgb = ObsidianPalette[1]
        )
        mutateDoc { it.copy(nodes = it.nodes + item) }
        selectedNodeId = item.id
        selectedEdgeId = null
    }

    fun fitAllNodes(viewportW: Float, viewportH: Float) {
        if (doc.nodes.isEmpty()) {
            scale = 1f
            pan = Offset.Zero
            docVersion++
            return
        }
        val minX = doc.nodes.minOf { it.x } * densityPx
        val minY = doc.nodes.minOf { it.y } * densityPx
        val maxX = doc.nodes.maxOf { it.x + it.width } * densityPx
        val maxY = doc.nodes.maxOf { it.y + it.height } * densityPx
        val contentW = (maxX - minX).coerceAtLeast(160f)
        val contentH = (maxY - minY).coerceAtLeast(160f)
        val newScale = min(
            (viewportW - 64f).coerceAtLeast(80f) / contentW,
            (viewportH - 120f).coerceAtLeast(80f) / contentH
        ).coerceIn(0.25f, 1.35f)
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        scale = newScale
        pan = Offset(-centerX * newScale, -centerY * newScale)
        docVersion++
    }

    val drawCache = remember { CanvasBoardDrawCache() }
    val dotColorArgb = remember(scheme.onSurfaceVariant) { withAlphaArgb(scheme.onSurfaceVariant.toArgb(), 0.16f) }
    val defaultEdgeArgb = remember(scheme.onSurfaceVariant) { withAlphaArgb(scheme.onSurfaceVariant.toArgb(), 0.72f) }
    val primaryArgb = remember(scheme.primary) { scheme.primary.toArgb() }
    val onSurfaceArgb = remember(scheme.onSurface) { scheme.onSurface.toArgb() }
    val onPrimaryContainerArgb = remember(scheme.onPrimaryContainer) { scheme.onPrimaryContainer.toArgb() }
    val primaryContainerArgb = remember(scheme.primaryContainer) { scheme.primaryContainer.toArgb() }
    val surfaceHighestArgb = remember(scheme.surfaceContainerHighest) { scheme.surfaceContainerHighest.toArgb() }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            .clipToBounds()
    ) {
        val viewportW = constraints.maxWidth.toFloat().coerceAtLeast(100f)
        val viewportH = constraints.maxHeight.toFloat().coerceAtLeast(100f)
        val vCenter = Offset(viewportW / 2f, viewportH / 2f)

        LaunchedEffect(loadedBoardId, pendingAutoFitBoardId, viewportW, viewportH) {
            val targetId = pendingAutoFitBoardId
            if (targetId != null && loadedBoardId == targetId && doc.nodes.isNotEmpty()) {
                fitAllNodes(viewportW, viewportH)
                pendingAutoFitBoardId = null
            }
        }

        if (activeBoard == null) {
            // Subtle dot-grid background behind onboarding
            Canvas(Modifier.fillMaxSize()) {
                val gridStepPx = 28f * densityPx
                drawCache.dotPaint.color = dotColorArgb
                val originX = vCenter.x % gridStepPx
                val originY = vCenter.y % gridStepPx
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    var gx = originX - gridStepPx
                    while (gx < size.width + gridStepPx) {
                        var gy = originY - gridStepPx
                        while (gy < size.height + gridStepPx) {
                            if (gx >= -4f && gy >= -4f) {
                                native.drawCircle(gx, gy, 1.6f, drawCache.dotPaint)
                            }
                            gy += gridStepPx
                        }
                        gx += gridStepPx
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Dashboard, null, tint = scheme.primary, modifier = Modifier.size(48.dp))
                Text(
                    "Quadro Visual (Canvas)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Organize ideias livres, notas do Vault e conexões com setas num quadro infinito.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = { showCreateBoardSheet = true },
                    shape = CircleShape,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Criar primeiro Quadro")
                }
            }
        } else {
            val currentOnOpenNote by rememberUpdatedState(onOpenNote)

            fun handleNodeTap(node: CanvasNodeItem) {
                if (connectMode) {
                    val srcId = pendingConnectSourceId
                    if (srcId == null) {
                        pendingConnectSourceId = node.id
                        selectedNodeId = node.id
                        selectedEdgeId = null
                    } else if (srcId != node.id) {
                        val newEdge = CanvasEdgeItem(
                            id = UUID.randomUUID().toString(),
                            fromNode = srcId,
                            toNode = node.id,
                            label = ""
                        )
                        mutateDoc { it.copy(edges = it.edges + newEdge) }
                        pendingConnectSourceId = null
                        selectedEdgeId = newEdge.id
                        selectedNodeId = null
                    } else {
                        pendingConnectSourceId = null
                    }
                } else {
                    if (selectedNodeId == node.id) {
                        if (node.type == CanvasNodeType.NOTE && node.noteId != null) {
                            currentOnOpenNote(node.noteId)
                        } else {
                            editingNode = node
                        }
                    } else {
                        selectedNodeId = node.id
                        selectedEdgeId = null
                    }
                }
            }

            val groupNodes = remember(doc.nodes, selectedNodeId) {
                doc.nodes.filter { it.type == CanvasNodeType.GROUP }
                    .sortedBy { if (it.id == selectedNodeId) 1 else 0 }
            }
            val cardNodes = remember(doc.nodes, selectedNodeId) {
                doc.nodes.filter { it.type != CanvasNodeType.GROUP }
                    .sortedBy { if (it.id == selectedNodeId) 1 else 0 }
            }
            val nodesMap = remember(doc.nodes) { doc.nodes.associateBy { it.id } }
            val currentNodesMap by rememberUpdatedState(nodesMap)
            val currentHandleNodeTap by rememberUpdatedState(::handleNodeTap)

            // Unified stationary gesture surface
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(activeBoard.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPos = down.position
                            val gestureCenter = Offset(size.width / 2f, size.height / 2f)
                            val worldDownDp = Offset(
                                (downPos.x - pan.x - gestureCenter.x) / (densityPx * scale),
                                (downPos.y - pan.y - gestureCenter.y) / (densityPx * scale)
                            )

                            val orderedHitCandidates = (
                                doc.nodes.filter { it.type != CanvasNodeType.GROUP }
                                    .sortedBy { if (it.id == selectedNodeId) 1 else 0 }
                                    .asReversed() +
                                    doc.nodes.filter { it.type == CanvasNodeType.GROUP }
                                        .sortedBy { if (it.id == selectedNodeId) 1 else 0 }
                                        .asReversed()
                                )
                            val resizeZoneDp = (26f / scale.coerceAtLeast(0.4f)).coerceIn(18f, 44f)
                            val hitResizeNode = orderedHitCandidates.firstOrNull { n ->
                                worldDownDp.x in (n.x + n.width - resizeZoneDp)..(n.x + n.width + resizeZoneDp * 0.35f) &&
                                    worldDownDp.y in (n.y + n.height - resizeZoneDp)..(n.y + n.height + resizeZoneDp * 0.35f)
                            }
                            val hitCard = if (hitResizeNode == null) {
                                orderedHitCandidates.firstOrNull { n ->
                                    worldDownDp.x in n.x..(n.x + n.width) &&
                                        worldDownDp.y in n.y..(n.y + n.height)
                                }
                            } else null

                            val dragTarget: CanvasDragTarget = when {
                                hitResizeNode != null -> CanvasDragTarget.ResizeNode(
                                    nodeId = hitResizeNode.id,
                                    minWidth = if (hitResizeNode.type == CanvasNodeType.GROUP) 220f else 140f,
                                    minHeight = if (hitResizeNode.type == CanvasNodeType.GROUP) 140f else 90f
                                )
                                hitCard != null -> {
                                    val containedChildren = if (hitCard.type == CanvasNodeType.GROUP) {
                                        doc.nodes.filter { child ->
                                            child.id != hitCard.id && child.type != CanvasNodeType.GROUP &&
                                                (child.x + child.width / 2f) in hitCard.x..(hitCard.x + hitCard.width) &&
                                                (child.y + child.height / 2f) in hitCard.y..(hitCard.y + hitCard.height)
                                        }.map { it.id }
                                    } else emptyList()
                                    CanvasDragTarget.MoveNode(hitCard.id, containedChildren)
                                }
                                else -> CanvasDragTarget.Pan
                            }

                            var totalMoved = 0f
                            var dragStarted = false
                            var dragUndoPushed = false
                            var wasMultiTouch = false
                            var viewportDirty = false

                            while (true) {
                                val initEvent = awaitPointerEvent(PointerEventPass.Initial)
                                val pressedCount = initEvent.changes.count { it.pressed }

                                if (pressedCount >= 2) {
                                    wasMultiTouch = true
                                    dragStarted = true
                                    val zoomChange = initEvent.calculateZoom()
                                    val panChange = initEvent.calculatePan()
                                    val centroid = initEvent.calculateCentroid(useCurrent = true)
                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        val oldScale = scale
                                        val newScale = (oldScale * zoomChange).coerceIn(0.25f, 2.5f)
                                        if (centroid != Offset.Unspecified) {
                                            val worldAtCentroid = (centroid - pan - gestureCenter) / oldScale
                                            pan = centroid + panChange - gestureCenter - (worldAtCentroid * newScale)
                                        } else {
                                            pan += panChange
                                        }
                                        scale = newScale
                                        viewportDirty = true
                                    }
                                    initEvent.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (pressedCount == 1 && !wasMultiTouch) {
                                    val change = initEvent.changes.firstOrNull { it.pressed } ?: continue
                                    val delta = change.position - change.previousPosition
                                    totalMoved += delta.getDistance()

                                    if (!dragStarted && totalMoved > touchSlop) {
                                        dragStarted = true
                                        when (dragTarget) {
                                            is CanvasDragTarget.MoveNode -> {
                                                selectedNodeId = dragTarget.nodeId
                                                selectedEdgeId = null
                                            }
                                            is CanvasDragTarget.ResizeNode -> {
                                                selectedNodeId = dragTarget.nodeId
                                                selectedEdgeId = null
                                            }
                                            CanvasDragTarget.Pan -> Unit
                                        }
                                    }

                                    if (dragStarted) {
                                        if (delta != Offset.Zero) {
                                            val deltaDp = delta / (densityPx * scale)
                                            when (dragTarget) {
                                                is CanvasDragTarget.Pan -> {
                                                    pan += delta
                                                    viewportDirty = true
                                                }
                                                is CanvasDragTarget.MoveNode -> {
                                                    val shouldPushUndo = !dragUndoPushed
                                                    if (shouldPushUndo) dragUndoPushed = true
                                                    val moveSet = (dragTarget.childIds + dragTarget.nodeId).toSet()
                                                    mutateDoc(recordUndo = shouldPushUndo, bumpVersion = false) { d ->
                                                        d.copy(
                                                            nodes = d.nodes.map { n ->
                                                                if (n.id in moveSet) n.copy(x = n.x + deltaDp.x, y = n.y + deltaDp.y)
                                                                else n
                                                            }
                                                        )
                                                    }
                                                    viewportDirty = true
                                                }
                                                is CanvasDragTarget.ResizeNode -> {
                                                    val shouldPushUndo = !dragUndoPushed
                                                    if (shouldPushUndo) dragUndoPushed = true
                                                    mutateDoc(recordUndo = shouldPushUndo, bumpVersion = false) { d ->
                                                        d.copy(
                                                            nodes = d.nodes.map { n ->
                                                                if (n.id == dragTarget.nodeId) {
                                                                    n.copy(
                                                                        width = (n.width + deltaDp.x).coerceAtLeast(dragTarget.minWidth),
                                                                        height = (n.height + deltaDp.y).coerceAtLeast(dragTarget.minHeight)
                                                                    )
                                                                } else n
                                                            }
                                                        )
                                                    }
                                                    viewportDirty = true
                                                }
                                            }
                                        }
                                        initEvent.changes.forEach { it.consume() }
                                    }
                                } else if (pressedCount == 0) {
                                    if (dragStarted) {
                                        initEvent.changes.forEach { it.consume() }
                                        if (viewportDirty) docVersion++
                                        break
                                    } else {
                                        val mainUp = awaitPointerEvent(PointerEventPass.Main)
                                        if (mainUp.changes.none { it.isConsumed }) {
                                            val tappedCard = hitResizeNode ?: hitCard
                                            if (tappedCard != null) {
                                                currentHandleNodeTap(tappedCard)
                                            } else {
                                                val liveNodesMap = currentNodesMap
                                                val edgeHit = doc.edges.mapNotNull { e ->
                                                    val f = liveNodesMap[e.fromNode] ?: return@mapNotNull null
                                                    val t = liveNodesMap[e.toNode] ?: return@mapNotNull null
                                                    val geom = computeEdgeBezier(f, t, densityPx, scale, pan, gestureCenter)
                                                    e to distanceToBezier(geom, downPos)
                                                }.minByOrNull { it.second }?.takeIf { it.second <= 34f }?.first

                                                if (edgeHit != null) {
                                                    selectedEdgeId = edgeHit.id
                                                    selectedNodeId = null
                                                } else {
                                                    selectedNodeId = null
                                                    selectedEdgeId = null
                                                }
                                            }
                                        }
                                        break
                                    }
                                }
                            }
                        }
                    }
            ) {
                // Layer 1: Zero-allocation Infinite World-Aligned Dot Grid
                Canvas(Modifier.fillMaxSize()) {
                    val gridStepPx = 28f * densityPx * scale
                    val dotRadius = (1.6f * scale.coerceIn(0.6f, 1.5f)).coerceAtLeast(1.0f)
                    if (gridStepPx >= 10f) {
                        drawCache.dotPaint.color = dotColorArgb
                        val originX = (pan.x + vCenter.x) % gridStepPx
                        val originY = (pan.y + vCenter.y) % gridStepPx
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            var gx = originX - gridStepPx
                            while (gx < size.width + gridStepPx) {
                                var gy = originY - gridStepPx
                                while (gy < size.height + gridStepPx) {
                                    if (gx >= -4f && gy >= -4f) {
                                        native.drawCircle(gx, gy, dotRadius, drawCache.dotPaint)
                                    }
                                    gy += gridStepPx
                                }
                                gx += gridStepPx
                            }
                        }
                    }
                }

                // Layer 2: Visual Groups (rendered BEHIND edges and cards)
                UnboundedCanvasLayer(Modifier.fillMaxSize()) {
                    groupNodes.forEach { group ->
                        key(group.id) {
                            val screenX = group.x * densityPx * scale + pan.x + vCenter.x
                            val screenY = group.y * densityPx * scale + pan.y + vCenter.y
                            CanvasGroupCard(
                                group = group,
                                isSelected = group.id == selectedNodeId,
                                isConnectSource = group.id == pendingConnectSourceId,
                                modifier = Modifier
                                    .size(group.width.dp, group.height.dp)
                                    .graphicsLayer {
                                        transformOrigin = TransformOrigin(0f, 0f)
                                        translationX = screenX
                                        translationY = screenY
                                        scaleX = scale
                                        scaleY = scale
                                    }
                            )
                        }
                    }
                }

                // Layer 3: Zero-allocation Directional Bezier Arrows & Label Pills
                val edgeStrokePx = with(density) { 2.4.dp.toPx() }
                val selectedStrokePx = with(density) { 3.6.dp.toPx() }
                val labelTextBasePx = with(density) { 11.5.sp.toPx() }
                val pillPadH = with(density) { 7.dp.toPx() }
                val pillPadV = with(density) { 3.dp.toPx() }
                val pillRadius = with(density) { 8.dp.toPx() }
                val pillStrokePx = with(density) { 1.2.dp.toPx() }

                Canvas(Modifier.fillMaxSize()) {
                    val scaleClamped = scale.coerceIn(0.6f, 1.3f)
                    val curEdgeStroke = edgeStrokePx * scaleClamped
                    val curSelectedStroke = selectedStrokePx * scaleClamped
                    val curLabelPx = labelTextBasePx * scale.coerceIn(0.65f, 1.25f)

                    val edgePaint = drawCache.edgePaint
                    val arrowPaint = drawCache.arrowPaint
                    val labelPaint = drawCache.labelPaint
                    labelPaint.textSize = curLabelPx
                    labelPaint.getFontMetrics(drawCache.fontMetrics)
                    val fm = drawCache.fontMetrics
                    val pillPaint = drawCache.pillPaint
                    val pillStrokePaint = drawCache.pillStrokePaint
                    pillStrokePaint.strokeWidth = pillStrokePx
                    val edgePath = drawCache.edgePath
                    val arrowPath = drawCache.arrowPath
                    val pillRect = drawCache.pillRect

                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val dScale = densityPx * scale
                        val ox = pan.x + vCenter.x
                        val oy = pan.y + vCenter.y

                        for (edge in doc.edges) {
                            val from = nodesMap[edge.fromNode] ?: continue
                            val to = nodesMap[edge.toNode] ?: continue

                            val fromCx = from.x + from.width * 0.5f
                            val fromCy = from.y + from.height * 0.5f
                            val toCx = to.x + to.width * 0.5f
                            val toCy = to.y + to.height * 0.5f
                            val wDx = toCx - fromCx
                            val wDy = toCy - fromCy
                            val horizontal = abs(wDx) >= abs(wDy)

                            val p0wx: Float
                            val p0wy: Float
                            val p3wx: Float
                            val p3wy: Float
                            if (horizontal) {
                                if (wDx >= 0f) {
                                    p0wx = from.x + from.width; p0wy = fromCy
                                    p3wx = to.x; p3wy = toCy
                                } else {
                                    p0wx = from.x; p0wy = fromCy
                                    p3wx = to.x + to.width; p3wy = toCy
                                }
                            } else {
                                if (wDy >= 0f) {
                                    p0wx = fromCx; p0wy = from.y + from.height
                                    p3wx = toCx; p3wy = to.y
                                } else {
                                    p0wx = fromCx; p0wy = from.y
                                    p3wx = toCx; p3wy = to.y + to.height
                                }
                            }

                            val p0x = p0wx * dScale + ox
                            val p0y = p0wy * dScale + oy
                            val p3x = p3wx * dScale + ox
                            val p3y = p3wy * dScale + oy

                            val handleDist = if (horizontal) {
                                max(44f * scale, abs(p3x - p0x) * 0.45f)
                            } else {
                                max(44f * scale, abs(p3y - p0y) * 0.45f)
                            }

                            val p1x: Float
                            val p1y: Float
                            val p2x: Float
                            val p2y: Float
                            if (horizontal) {
                                val dir = if (p3x >= p0x) 1f else -1f
                                p1x = p0x + dir * handleDist; p1y = p0y
                                p2x = p3x - dir * handleDist; p2y = p3y
                            } else {
                                val dir = if (p3y >= p0y) 1f else -1f
                                p1x = p0x; p1y = p0y + dir * handleDist
                                p2x = p3x; p2y = p3y - dir * handleDist
                            }

                            val isSel = edge.id == selectedEdgeId
                            val baseColorArgb = edge.colorArgb ?: if (isSel) primaryArgb else defaultEdgeArgb

                            edgePaint.color = baseColorArgb
                            edgePaint.strokeWidth = if (isSel) curSelectedStroke else curEdgeStroke

                            edgePath.rewind()
                            edgePath.moveTo(p0x, p0y)
                            edgePath.cubicTo(p1x, p1y, p2x, p2y, p3x, p3y)
                            native.drawPath(edgePath, edgePaint)

                            val tx = p3x - p2x
                            val ty = p3y - p2y
                            val tLen = max(hypot(tx, ty), 1f)
                            val ux = tx / tLen
                            val uy = ty / tLen
                            val pxPerp = -uy
                            val pyPerp = ux
                            val arrowLen = max(13f * scaleClamped, 8f)
                            val arrowHalfW = arrowLen * 0.48f

                            arrowPaint.color = baseColorArgb
                            arrowPath.rewind()
                            arrowPath.moveTo(p3x, p3y)
                            arrowPath.lineTo(p3x - ux * arrowLen + pxPerp * arrowHalfW, p3y - uy * arrowLen + pyPerp * arrowHalfW)
                            arrowPath.lineTo(p3x - ux * arrowLen - pxPerp * arrowHalfW, p3y - uy * arrowLen - pyPerp * arrowHalfW)
                            arrowPath.close()
                            native.drawPath(arrowPath, arrowPaint)

                            val label = edge.label.trim()
                            if (label.isNotEmpty() || isSel) {
                                val displayLabel = if (label.isEmpty()) "Seta" else label
                                val midX = 0.125f * p0x + 0.375f * p1x + 0.375f * p2x + 0.125f * p3x
                                val midY = 0.125f * p0y + 0.375f * p1y + 0.375f * p2y + 0.125f * p3y
                                val textW = labelPaint.measureText(displayLabel)
                                pillRect.set(
                                    midX - textW / 2f - pillPadH,
                                    midY + (fm.ascent - fm.descent) / 2f - pillPadV,
                                    midX + textW / 2f + pillPadH,
                                    midY - (fm.ascent - fm.descent) / 2f + pillPadV
                                )
                                pillPaint.color = if (isSel) primaryContainerArgb else surfaceHighestArgb
                                pillStrokePaint.color = baseColorArgb
                                native.drawRoundRect(pillRect, pillRadius, pillRadius, pillPaint)
                                native.drawRoundRect(pillRect, pillRadius, pillRadius, pillStrokePaint)
                                labelPaint.color = if (isSel) onPrimaryContainerArgb else onSurfaceArgb
                                val baseline = midY - (fm.ascent + fm.descent) / 2f
                                native.drawText(displayLabel, midX - textW / 2f, baseline, labelPaint)
                            }
                        }
                    }
                }

                // Layer 4: Clean Foreground Cards (NO cramped sub-48dp corner buttons!)
                UnboundedCanvasLayer(Modifier.fillMaxSize()) {
                    cardNodes.forEach { node ->
                        key(node.id) {
                            val screenX = node.x * densityPx * scale + pan.x + vCenter.x
                            val screenY = node.y * densityPx * scale + pan.y + vCenter.y
                            val linkedNote = node.noteId?.let { notesById[it] }
                            CanvasContentCard(
                                node = node,
                                linkedNote = linkedNote,
                                isSelected = node.id == selectedNodeId,
                                isConnectSource = node.id == pendingConnectSourceId,
                                modifier = Modifier
                                    .size(node.width.dp, node.height.dp)
                                    .graphicsLayer {
                                        transformOrigin = TransformOrigin(0f, 0f)
                                        translationX = screenX
                                        translationY = screenY
                                        scaleX = scale
                                        scaleY = scale
                                    }
                            )
                        }
                    }
                }
            }
        }

        // Minimal Floating Top Pill
        if (showHeader) {
            Surface(
                shape = CircleShape,
                color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = scheme.onSurface)
                        }
                    }

                    // Board Switcher Dropdown
                    Box(Modifier.weight(1f, fill = false)) {
                        TextButton(
                            onClick = { showBoardMenu = true },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Dashboard, null, Modifier.size(18.dp), tint = scheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                activeBoard?.title ?: "Lousas",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(2.dp))
                            Icon(Icons.Default.ArrowDropDown, null, tint = scheme.onSurfaceVariant)
                        }

                        DropdownMenu(
                            expanded = showBoardMenu,
                            onDismissRequest = { showBoardMenu = false }
                        ) {
                            boards.forEach { b ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            b.title,
                                            fontWeight = if (b.id == activeBoard?.id) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (b.id == activeBoard?.id) Icons.Default.Check else Icons.Default.Dashboard,
                                            null
                                        )
                                    },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                    onClick = {
                                        showBoardMenu = false
                                        val previous = activeBoard
                                        if (previous != null && b.id != previous.id && docVersion > 0L) {
                                            val snapshotPayload = encodeCanvasDocument(
                                                doc.copy(viewportPanX = pan.x, viewportPanY = pan.y, viewportScale = scale)
                                            )
                                            scope.launch {
                                                onSaveBoard(previous.copy(jsonData = snapshotPayload, updatedAt = System.currentTimeMillis()))
                                            }
                                        }
                                        activeBoardId = b.id
                                    }
                                )
                            }
                            if (boards.isNotEmpty()) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Novo Quadro Canvas") },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                onClick = {
                                    showBoardMenu = false
                                    showCreateBoardSheet = true
                                }
                            )
                            if (activeBoard != null) {
                                DropdownMenuItem(
                                    text = { Text("Renomear quadro") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                    onClick = {
                                        showBoardMenu = false
                                        showRenameBoardSheet = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Excluir quadro", color = scheme.error) },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = scheme.error) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                    onClick = {
                                        showBoardMenu = false
                                        showDeleteBoardConfirm = true
                                    }
                                )
                            }
                        }
                    }

                    if (activeBoard != null) {
                        IconButton(
                            onClick = { fitAllNodes(viewportW, viewportH) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.CenterFocusStrong, "Enquadrar tudo", tint = scheme.onSurface)
                        }

                        Box(
                            Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    scale = 1f
                                    docVersion++
                                }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${(scale * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = ::undoLastChange,
                            enabled = undoStack.isNotEmpty(),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                "Desfazer",
                                tint = if (undoStack.isNotEmpty()) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }

                        // Silent save indicator dot
                        if (isSaving) {
                            Box(
                                Modifier.padding(end = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = scheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Connect-mode banner (cleanly positioned below top pill)
        if (activeBoard != null && connectMode) {
            val srcTitle = pendingConnectSourceId?.let { id ->
                doc.nodes.firstOrNull { it.id == id }?.let { n ->
                    n.noteId?.let { nid -> notesById[nid]?.title } ?: n.title.ifBlank { "Card" }
                }
            }
            Surface(
                shape = CircleShape,
                color = scheme.primaryContainer,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (showHeader) 70.dp else 12.dp, start = 12.dp, end = 12.dp)
            ) {
                Row(
                    Modifier.padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Timeline, null, Modifier.size(18.dp), tint = scheme.onPrimaryContainer)
                    Text(
                        if (srcTitle == null) "Toque no card de origem"
                        else "Origem: ${srcTitle.take(16)} → destino",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    TextButton(
                        onClick = {
                            connectMode = false
                            pendingConnectSourceId = null
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Cancelar")
                    }
                }
            }
        }

        // Bottom Floating Contextual Selection Pill + Minimal Bottom Toolbar (>= 48dp touch targets)
        if (activeBoard != null) {
            val selNode = selectedNodeId?.let { id -> doc.nodes.firstOrNull { it.id == id } }
            val selEdge = selectedEdgeId?.let { id -> doc.edges.firstOrNull { it.id == id } }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selNode != null) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = scheme.surfaceContainerHigh.copy(alpha = 0.96f),
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        Row(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                onClick = { editingNode = selNode },
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Editar", maxLines = 1)
                            }
                            if (selNode.type == CanvasNodeType.NOTE && selNode.noteId != null && selNode.noteId in notesById) {
                                FilledTonalButton(
                                    onClick = { onOpenNote(selNode.noteId) },
                                    shape = CircleShape,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Abrir Nota", maxLines = 1)
                                }
                            }
                            TextButton(
                                onClick = { colorPickerNode = selNode },
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(selNode.colorArgb)))
                                Spacer(Modifier.width(6.dp))
                                Text("Cor", maxLines = 1)
                            }
                            TextButton(
                                onClick = {
                                    connectMode = true
                                    pendingConnectSourceId = selNode.id
                                },
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Timeline, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Conectar", maxLines = 1)
                            }
                            IconButton(
                                onClick = {
                                    mutateDoc { d ->
                                        d.copy(
                                            nodes = d.nodes.filterNot { it.id == selNode.id },
                                            edges = d.edges.filterNot { it.fromNode == selNode.id || it.toNode == selNode.id }
                                        )
                                    }
                                    selectedNodeId = null
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, "Remover", tint = scheme.error)
                            }
                        }
                    }
                } else if (selEdge != null) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = scheme.surfaceContainerHigh.copy(alpha = 0.96f),
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        Row(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { editingEdge = selEdge },
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (selEdge.label.isBlank()) "Rótulo" else "Editar", maxLines = 1)
                            }
                            TextButton(
                                onClick = {
                                    mutateDoc { d ->
                                        d.copy(
                                            edges = d.edges.map { e ->
                                                if (e.id == selEdge.id) e.copy(fromNode = e.toNode, toNode = e.fromNode)
                                                else e
                                            }
                                        )
                                    }
                                },
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Inverter", maxLines = 1)
                            }
                            IconButton(
                                onClick = {
                                    mutateDoc { d -> d.copy(edges = d.edges.filterNot { it.id == selEdge.id }) }
                                    selectedEdgeId = null
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, "Remover conexão", tint = scheme.error)
                            }
                        }
                    }
                }

                // Minimal Floating Bottom Toolbar (+ Texto, + Nota, + Grupo, Conectar)
                Surface(
                    shape = CircleShape,
                    color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        TextButton(
                            onClick = ::addTextCard,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NoteAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("+ Texto", maxLines = 1)
                        }
                        TextButton(
                            onClick = {
                                swapTargetNodeId = null
                                showNotePicker = true
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Description, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("+ Nota", maxLines = 1)
                        }
                        TextButton(
                            onClick = ::addGroupCard,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.SelectAll, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("+ Grupo", maxLines = 1)
                        }
                        if (connectMode) {
                            FilledTonalButton(
                                onClick = {
                                    connectMode = false
                                    pendingConnectSourceId = null
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Timeline, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Conectar", maxLines = 1)
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    connectMode = true
                                    pendingConnectSourceId = null
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Timeline, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Conectar", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    // SHEET: Create New Canvas Board
    if (showCreateBoardSheet) {
        BoardTitleModalSheet(
            sheetTitle = "Novo Quadro Canvas",
            initialTitle = "",
            placeholder = "Ex.: Mapa de Física, Revisão Enem…",
            confirmLabel = "Criar",
            allowBlank = true,
            onConfirm = { entered ->
                val boardTitle = entered.trim().ifBlank { "Quadro ${boards.size + 1}" }
                showCreateBoardSheet = false
                val now = System.currentTimeMillis()
                scope.launch {
                    val newId = onSaveBoard(
                        CanvasBoard(
                            title = boardTitle,
                            jsonData = encodeCanvasDocument(CanvasDocument()),
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    activeBoardId = newId
                }
            },
            onDismiss = { showCreateBoardSheet = false }
        )
    }

    // SHEET: Rename Active Board
    if (showRenameBoardSheet && activeBoard != null) {
        BoardTitleModalSheet(
            sheetTitle = "Renomear Quadro",
            initialTitle = activeBoard.title,
            placeholder = "Nome do quadro",
            confirmLabel = "Salvar",
            allowBlank = false,
            onConfirm = { entered ->
                val clean = entered.trim()
                if (clean.isNotEmpty()) {
                    showRenameBoardSheet = false
                    val payload = encodeCanvasDocument(
                        doc.copy(viewportPanX = pan.x, viewportPanY = pan.y, viewportScale = scale)
                    )
                    scope.launch {
                        onSaveBoard(activeBoard.copy(title = clean, jsonData = payload, updatedAt = System.currentTimeMillis()))
                    }
                }
            },
            onDismiss = { showRenameBoardSheet = false }
        )
    }

    // DIALOG: Delete Board Confirmation
    if (showDeleteBoardConfirm && activeBoard != null) {
        AlertDialog(
            onDismissRequest = { showDeleteBoardConfirm = false },
            title = { Text("Excluir quadro \"${activeBoard.title}\"?") },
            text = { Text("Todos os cards e conexões deste quadro serão removidos permanentemente.") },
            confirmButton = {
                Button(
                    onClick = {
                        val targetId = activeBoard.id
                        showDeleteBoardConfirm = false
                        scope.launch { onDeleteBoard(targetId) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteBoardConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancelar") }
            }
        )
    }

    // SHEET: Edit Card Text / Group / Note Swap
    editingNode?.let { target ->
        CanvasNodeEditSheet(
            target = target,
            onSwapNote = {
                editingNode = null
                swapTargetNodeId = target.id
                showNotePicker = true
            },
            onSave = { newTitle, newBody, newColor ->
                mutateDoc { d ->
                    d.copy(
                        nodes = d.nodes.map { n ->
                            if (n.id == target.id) {
                                n.copy(
                                    title = if (target.type == CanvasNodeType.NOTE) n.title else newTitle.trim(),
                                    text = if (target.type == CanvasNodeType.TEXT) newBody else n.text,
                                    colorArgb = newColor
                                )
                            } else n
                        }
                    )
                }
                editingNode = null
            },
            onDismiss = { editingNode = null }
        )
    }

    // SHEET: Quick Color Picker for Selected Node
    colorPickerNode?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { colorPickerNode = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = scheme.surfaceContainerLow,
            contentColor = scheme.onSurface
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Cor do Card", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                PaletteSwatchesFlow(
                    selectedArgb = target.colorArgb,
                    onSelect = { argb ->
                        mutateDoc { d ->
                            d.copy(
                                nodes = d.nodes.map { n ->
                                    if (n.id == target.id) n.copy(colorArgb = argb) else n
                                }
                            )
                        }
                        colorPickerNode = null
                    }
                )
            }
        }
    }

    // SHEET: Edit Edge Label & Color
    editingEdge?.let { edge ->
        CanvasEdgeEditSheet(
            edge = edge,
            onSave = { label, colorArgb ->
                mutateDoc { d ->
                    d.copy(
                        edges = d.edges.map { e ->
                            if (e.id == edge.id) e.copy(label = label.trim(), colorArgb = colorArgb) else e
                        }
                    )
                }
                editingEdge = null
            },
            onDelete = {
                mutateDoc { d -> d.copy(edges = d.edges.filterNot { it.id == edge.id }) }
                selectedEdgeId = null
                editingEdge = null
            },
            onDismiss = { editingEdge = null }
        )
    }

    // SHEET: Note Picker for Adding / Swapping Vault Note Card
    if (showNotePicker) {
        CanvasNotePickerSheet(
            notes = activeNotes,
            onSelectNote = { note ->
                val swapId = swapTargetNodeId
                if (swapId != null) {
                    mutateDoc { d ->
                        d.copy(
                            nodes = d.nodes.map { n ->
                                if (n.id == swapId) n.copy(noteId = note.id, title = note.title) else n
                            }
                        )
                    }
                } else {
                    addNoteCard(note)
                }
                swapTargetNodeId = null
                showNotePicker = false
            },
            onCreateNote = onCreateNote?.let { creator ->
                {
                    showNotePicker = false
                    swapTargetNodeId = null
                    creator()
                }
            },
            onDismiss = {
                showNotePicker = false
                swapTargetNodeId = null
            }
        )
    }
}

@Composable
private fun BoardTitleModalSheet(
    sheetTitle: String,
    initialTitle: String,
    placeholder: String,
    confirmLabel: String,
    allowBlank: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow,
        contentColor = scheme.onSurface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(sheetTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Nome do quadro") },
                placeholder = { Text(placeholder) },
                singleLine = true,
                shape = studyControlShape(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = studyControlShape(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Cancelar") }
                Button(
                    onClick = { onConfirm(title) },
                    enabled = allowBlank || title.isNotBlank(),
                    shape = studyControlShape(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text(confirmLabel) }
            }
        }
    }
}

@Composable
private fun CanvasNodeEditSheet(
    target: CanvasNodeItem,
    onSwapNote: () -> Unit,
    onSave: (title: String, body: String, colorArgb: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var title by remember(target.id) { mutableStateOf(target.title) }
    var body by remember(target.id) { mutableStateOf(target.text) }
    var colorArgb by remember(target.id) { mutableIntStateOf(target.colorArgb) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow,
        contentColor = scheme.onSurface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                when (target.type) {
                    CanvasNodeType.GROUP -> "Editar Grupo Visual"
                    CanvasNodeType.NOTE -> "Editar Card de Nota"
                    CanvasNodeType.TEXT -> "Editar Card de Texto"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (target.type != CanvasNodeType.NOTE) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (target.type == CanvasNodeType.GROUP) "Rótulo da seção" else "Título") },
                    singleLine = true,
                    shape = studyControlShape(),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedButton(
                    onClick = onSwapNote,
                    shape = studyControlShape(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Trocar nota vinculada")
                }
            }

            if (target.type == CanvasNodeType.TEXT) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Texto (Markdown curto)") },
                    minLines = 3,
                    maxLines = 7,
                    shape = studyControlShape(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text("Cor do card", style = MaterialTheme.typography.labelLarge)
            PaletteSwatchesFlow(
                selectedArgb = colorArgb,
                onSelect = { colorArgb = it }
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = studyControlShape(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Cancelar") }
                Button(
                    onClick = { onSave(title, body, colorArgb) },
                    shape = studyControlShape(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Aplicar") }
            }
        }
    }
}

@Composable
private fun CanvasEdgeEditSheet(
    edge: CanvasEdgeItem,
    onSave: (label: String, colorArgb: Int?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var label by remember(edge.id) { mutableStateOf(edge.label) }
    var colorArgb by remember(edge.id) { mutableStateOf(edge.colorArgb) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow,
        contentColor = scheme.onSurface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Editar Conexão", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Rótulo da seta") },
                placeholder = { Text("Ex.: causa, depende de, exemplo…") },
                singleLine = true,
                shape = studyControlShape(),
                modifier = Modifier.fillMaxWidth()
            )
            Text("Cor da seta", style = MaterialTheme.typography.labelLarge)
            PaletteSwatchesFlow(
                selectedArgb = colorArgb,
                onSelect = { argb -> colorArgb = if (colorArgb == argb) null else argb },
                colors = ObsidianPalette.take(8)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDelete,
                    shape = studyControlShape(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Excluir") }
                Button(
                    onClick = { onSave(label, colorArgb) },
                    shape = studyControlShape(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Salvar") }
            }
        }
    }
}

@Composable
private fun CanvasNotePickerSheet(
    notes: List<Note>,
    onSelectNote: (Note) -> Unit,
    onCreateNote: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var noteQuery by remember { mutableStateOf("") }
    val filtered = remember(notes, noteQuery) {
        val q = noteQuery.trim()
        val base = notes.sortedByDescending { it.updatedAt }
        if (q.isEmpty()) base
        else base.filter {
            it.title.contains(q, ignoreCase = true) || it.markdown.contains(q, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow,
        contentColor = scheme.onSurface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Vincular Nota do Vault",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (onCreateNote != null) {
                    TextButton(
                        onClick = onCreateNote,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Nova nota")
                    }
                }
            }

            StudyLocalSearchField(
                query = noteQuery,
                onQueryChange = { noteQuery = it },
                placeholder = "Buscar nota…",
                modifier = Modifier.fillMaxWidth()
            )

            if (filtered.isEmpty()) {
                Text(
                    "Nenhuma nota disponível para vincular.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.id }) { note ->
                        StudyCard(
                            onClick = { onSelectNote(note) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    note.title,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stripNotePreview(note.markdown),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
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

/**
 * Unbounded layout container placing canvas items at origin (0, 0) without constraining their
 * world dimensions to the screen viewport, so graphicsLayer handles world->screen translation/scale.
 */
@Composable
private fun UnboundedCanvasLayer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val unbounded = Constraints(0, Int.MAX_VALUE, 0, Int.MAX_VALUE)
        val placeables = measurables.map { it.measure(unbounded) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}

@Composable
private fun CanvasGroupCard(
    group: CanvasNodeItem,
    isSelected: Boolean,
    isConnectSource: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val accent = Color(group.colorArgb)
    val borderColor = when {
        isConnectSource -> scheme.primary
        isSelected -> accent
        else -> accent.copy(alpha = 0.55f)
    }
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.11f))
            .border(width = if (isSelected || isConnectSource) 2.5.dp else 1.5.dp, color = borderColor, shape = shape)
            .padding(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = accent.copy(alpha = 0.22f).compositeOver(scheme.surfaceContainerHigh),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Text(
                group.title.ifBlank { "Grupo" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        CanvasResizeCorner(
            tint = borderColor,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun CanvasContentCard(
    node: CanvasNodeItem,
    linkedNote: Note?,
    isSelected: Boolean,
    isConnectSource: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val accent = Color(node.colorArgb)
    val cardBg = accent.copy(alpha = 0.10f).compositeOver(scheme.surfaceContainerHigh)
    val borderColor = when {
        isConnectSource -> scheme.primary
        isSelected -> accent
        else -> accent.copy(alpha = 0.55f)
    }
    val shape = RoundedCornerShape(16.dp)

    Surface(
        shape = shape,
        color = cardBg,
        tonalElevation = if (isSelected) 6.dp else 2.dp,
        shadowElevation = if (isSelected) 8.dp else 2.dp,
        modifier = modifier.border(
            width = if (isSelected || isConnectSource) 2.5.dp else 1.2.dp,
            color = borderColor,
            shape = shape
        )
    ) {
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                    if (node.type == CanvasNodeType.NOTE) {
                        Icon(Icons.Default.Description, null, Modifier.size(14.dp), tint = accent)
                    }
                    val headerText = when (node.type) {
                        CanvasNodeType.NOTE -> linkedNote?.title ?: node.title.ifBlank { "Nota vinculada" }
                        CanvasNodeType.TEXT -> node.title.ifBlank { "Nota livre" }
                        CanvasNodeType.GROUP -> node.title.ifBlank { "Grupo" }
                    }
                    Text(
                        headerText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(color = accent.copy(alpha = 0.25f))

                if (node.type == CanvasNodeType.NOTE) {
                    if (linkedNote != null) {
                        Text(
                            stripNotePreview(linkedNote.markdown),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Text(
                            "Nota não encontrada ou na lixeira.",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = scheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text(
                        node.text.ifBlank { "Card vazio" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (node.text.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            CanvasResizeCorner(
                tint = borderColor,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
private fun CanvasResizeCorner(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val stroke = 1.8.dp.toPx()
        drawLine(
            color = tint.copy(alpha = 0.8f),
            start = Offset(size.width * 0.35f, size.height),
            end = Offset(size.width, size.height * 0.35f),
            strokeWidth = stroke
        )
        drawLine(
            color = tint.copy(alpha = 0.8f),
            start = Offset(size.width * 0.7f, size.height),
            end = Offset(size.width, size.height * 0.7f),
            strokeWidth = stroke
        )
    }
}
