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

data class GraphNode(
    val id: Long,
    val title: String,
    val folder: String?,
    val primaryTag: String?,
    val degree: Int,
    val isTagNode: Boolean = false,
    val allTags: List<String> = emptyList(),
    val isGhost: Boolean = false,
    val ghostTitle: String? = null
)

data class GraphEdge(
    val sourceId: Long,
    val targetId: Long,
    val isGhostEdge: Boolean = false
)

data class GraphColorRule(
    val id: String,
    val query: String, // e.g. "tag:#fisica", "#mat", "path:Faculdade"
    val label: String,
    val colorArgb: Int
)

val ObsidianPalette = listOf(
    0xFF8B5CF6.toInt(), // Obsidian Purple
    0xFF3B82F6.toInt(), // Electric Blue
    0xFF06B6D4.toInt(), // Neon Cyan
    0xFF10B981.toInt(), // Emerald Green
    0xFF84CC16.toInt(), // Lime
    0xFFF59E0B.toInt(), // Amber Gold
    0xFFF97316.toInt(), // Bright Orange
    0xFFEF4444.toInt(), // Crimson Red
    0xFFEC4899.toInt(), // Coral Pink
    0xFFD946EF.toInt(), // Fuchsia Magenta
    0xFFA78BFA.toInt(), // Soft Lavender
    0xFF64748B.toInt()  // Cool Slate
)

private const val WORLD_NOMINAL_HALF = 1200f // 2400f x 2400f centered at (0, 0)
private const val TAG_VIRTUAL_BASE_ID = -10_000L
private const val GHOST_VIRTUAL_BASE_ID = -1_000_000L
internal const val MAX_CANVAS_UNDO = 25

internal fun withAlphaArgb(argb: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (argb and 0x00FFFFFF) or (a shl 24)
}

private fun tagMatchesHierarchical(noteTag: String, ruleTag: String): Boolean {
    val n = noteTag.removePrefix("#").trim().lowercase(Locale.ROOT)
    val r = ruleTag.removePrefix("#").trim().lowercase(Locale.ROOT)
    return r.isNotEmpty() && (n == r || n.startsWith("$r/"))
}

private fun pathMatchesPrefix(noteFolder: String?, rulePath: String): Boolean {
    val f = noteFolder?.replace('\\', '/')?.trim()?.trim('/')?.lowercase(Locale.ROOT) ?: return false
    val p = rulePath.replace('\\', '/').trim().trim('/').lowercase(Locale.ROOT)
    return p.isNotEmpty() && (f == p || f.startsWith("$p/"))
}

private fun lookupHierarchicalColor(groupColors: Map<String, Int>, prefix: String, value: String): Int? {
    var current = value.replace('\\', '/').trim().trim('/').lowercase(Locale.ROOT)
    while (current.isNotEmpty()) {
        groupColors["$prefix:$current"]?.let { return it }
        val slash = current.lastIndexOf('/')
        if (slash <= 0) break
        current = current.substring(0, slash)
    }
    return null
}

private fun matchesColorRule(node: GraphNode, ruleQuery: String): Boolean {
    val q = ruleQuery.trim()
    if (q.isEmpty()) return false
    return when {
        q.startsWith("tag:", ignoreCase = true) -> {
            val targetTag = q.substringAfter(":").removePrefix("#").trim()
            node.allTags.any { tagMatchesHierarchical(it, targetTag) }
        }
        q.startsWith("#") -> {
            val targetTag = q.removePrefix("#").trim()
            node.allTags.any { tagMatchesHierarchical(it, targetTag) }
        }
        q.startsWith("path:", ignoreCase = true) || q.startsWith("folder:", ignoreCase = true) -> {
            val targetFolder = q.substringAfter(":").trim()
            pathMatchesPrefix(node.folder, targetFolder)
        }
        else -> node.title.contains(q, ignoreCase = true)
    }
}

private fun worldToScreen(worldPos: Offset, scale: Float, pan: Offset, viewportCenter: Offset): Offset =
    Offset(
        worldPos.x * scale + pan.x + viewportCenter.x,
        worldPos.y * scale + pan.y + viewportCenter.y
    )

private fun screenToWorld(screenPos: Offset, scale: Float, pan: Offset, viewportCenter: Offset): Offset {
    val safeScale = if (scale.isFinite() && abs(scale) >= 1e-4f) scale else 1f
    val wx = (screenPos.x - pan.x - viewportCenter.x) / safeScale
    val wy = (screenPos.y - pan.y - viewportCenter.y) / safeScale
    return Offset(if (wx.isFinite()) wx else 0f, if (wy.isFinite()) wy else 0f)
}

/**
 * Computes a bounded Fruchterman-Reingold layout within the specified rectangle dimensions
 * using primitive arrays to minimize allocations.
 * Retained for deterministic unit/acceptance verification.
 */
fun computeForceDirectedLayout(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    width: Float,
    height: Float,
    pinnedCenterId: Long? = null,
    iterations: Int = 65
): Map<Long, Offset> {
    if (nodes.isEmpty()) return emptyMap()
    val nCount = nodes.size
    if (nCount == 1) return mapOf(nodes.first().id to Offset(width / 2f, height / 2f))

    val cx = width / 2f
    val cy = height / 2f
    val area = (width * height).coerceAtLeast(10_000f)
    val k = sqrt(area / nCount.toFloat()) * 0.75f
    val k2 = k * k

    val minX = min(36f, cx)
    val maxX = max(width - 36f, cx)
    val minY = min(36f, cy)
    val maxY = max(height - 36f, cy)

    val idToIndex = HashMap<Long, Int>(nCount * 2)
    val px = FloatArray(nCount)
    val py = FloatArray(nCount)
    val dx = FloatArray(nCount)
    val dy = FloatArray(nCount)
    var pinnedIdx = -1

    for (i in 0 until nCount) {
        val node = nodes[i]
        idToIndex[node.id] = i
        if (node.id == pinnedCenterId) {
            pinnedIdx = i
            px[i] = cx
            py[i] = cy
        } else {
            val angle = i * 2.3999632f
            val radius = minOf(width, height) * 0.35f * sqrt((i + 1f) / nCount.toFloat())
            px[i] = (cx + cos(angle) * radius).coerceIn(minX, maxX)
            py[i] = (cy + sin(angle) * radius).coerceIn(minY, maxY)
        }
    }

    val edgeSrc = IntArray(edges.size)
    val edgeDst = IntArray(edges.size)
    var validEdgeCount = 0
    for (edge in edges) {
        val sIdx = idToIndex[edge.sourceId]
        val tIdx = idToIndex[edge.targetId]
        if (sIdx != null && tIdx != null && sIdx != tIdx) {
            edgeSrc[validEdgeCount] = sIdx
            edgeDst[validEdgeCount] = tIdx
            validEdgeCount++
        }
    }

    var temperature = width / 10f
    for (iter in 0 until iterations) {
        dx.fill(0f)
        dy.fill(0f)

        for (i in 0 until nCount) {
            val xi = px[i]
            val yi = py[i]
            for (j in i + 1 until nCount) {
                val rx = xi - px[j]
                val ry = yi - py[j]
                val dist = max(hypot(rx, ry), 1f)
                val force = k2 / dist
                val stepX = (rx / dist) * force
                val stepY = (ry / dist) * force
                dx[i] += stepX
                dy[i] += stepY
                dx[j] -= stepX
                dy[j] -= stepY
            }
        }

        for (e in 0 until validEdgeCount) {
            val s = edgeSrc[e]
            val t = edgeDst[e]
            val sx = px[s] - px[t]
            val sy = py[s] - py[t]
            val dist = max(hypot(sx, sy), 1f)
            val force = (dist * dist) / k
            val stepX = (sx / dist) * force
            val stepY = (sy / dist) * force
            dx[s] -= stepX
            dy[s] -= stepY
            dx[t] += stepX
            dy[t] += stepY
        }

        for (i in 0 until nCount) {
            if (i == pinnedIdx) continue
            dx[i] += (cx - px[i]) * 0.04f
            dy[i] += (cy - py[i]) * 0.04f

            val dist = max(hypot(dx[i], dy[i]), 1f)
            val step = min(dist, temperature)
            px[i] = (px[i] + (dx[i] / dist) * step).coerceIn(minX, maxX)
            py[i] = (py[i] + (dy[i] / dist) * step).coerceIn(minY, maxY)
        }
        temperature *= 0.94f
    }

    val result = HashMap<Long, Offset>(nCount * 2)
    for (i in 0 until nCount) {
        result[nodes[i].id] = Offset(px[i], py[i])
    }
    return result
}

/**
 * Computes the undirected neighborhood within [depth] hops from [centerNodeId] (1..3+ hops),
 * returning both the visited notes (ordered by discovery) and internal edges among them.
 */
fun computeNeighborhood(
    centerNodeId: Long,
    allNotes: List<Note>,
    edges: List<Pair<Long, Long>>,
    depth: Int = 1
): Pair<List<Note>, List<Pair<Long, Long>>> {
    val clampedDepth = depth.coerceAtLeast(0)
    val notesById = allNotes.associateBy { it.id }
    if (centerNodeId !in notesById) return emptyList<Note>() to emptyList()

    val adj = HashMap<Long, MutableList<Long>>(edges.size)
    for ((s, t) in edges) {
        adj.getOrPut(s) { mutableListOf() }.add(t)
        adj.getOrPut(t) { mutableListOf() }.add(s)
    }
    val visited = linkedSetOf(centerNodeId)
    var frontier = listOf(centerNodeId)
    for (d in 0 until clampedDepth) {
        val nextFrontier = ArrayList<Long>()
        for (nodeId in frontier) {
            for (nb in adj[nodeId].orEmpty()) {
                if (visited.add(nb)) {
                    nextFrontier.add(nb)
                }
            }
        }
        if (nextFrontier.isEmpty()) break
        frontier = nextFrontier
    }
    val neighborhoodNotes = visited.mapNotNull { notesById[it] }
    val neighborhoodEdges = edges.filter { it.first in visited && it.second in visited }
    return neighborhoodNotes to neighborhoodEdges
}

/**
 * Extracts the neighborhood of a note for Local Graph visualization.
 */
fun localGraphNeighborhood(
    currentNoteId: Long,
    allNotes: List<Note>,
    edges: List<Pair<Long, Long>>,
    depth: Int = 1
): Pair<List<Note>, List<Pair<Long, Long>>> =
    computeNeighborhood(currentNoteId, allNotes, edges, depth)

// ============================================================================
// BACKGROUND GRAPH INDEXING, SIMULATION & ZERO-ALLOCATION RENDERING CACHE
// ============================================================================

private data class GraphIndexData(
    val tagsByNote: Map<Long, List<String>> = emptyMap(),
    val foldersByNote: Map<Long, String?> = emptyMap(),
    val aliasesByNote: Map<Long, List<String>> = emptyMap(),
    val allTags: List<String> = emptyList(),
    val allFolders: List<String> = emptyList(),
    val rawEdges: List<Pair<Long, Long>> = emptyList(),
    val unresolvedByNote: Map<Long, List<String>> = emptyMap(),
    val totalGhostCount: Int = 0
)

private fun buildGraphIndexData(notes: List<Note>, bindings: List<NoteLink>): GraphIndexData {
    if (notes.isEmpty()) return GraphIndexData()

    val tagsByNote = HashMap<Long, List<String>>(notes.size * 2)
    val foldersByNote = HashMap<Long, String?>(notes.size * 2)
    val aliasesByNote = HashMap<Long, List<String>>(notes.size * 2)
    val tagCounts = HashMap<String, Int>()
    val folderSet = HashSet<String>()

    for (note in notes) {
        val (props, body) = fastPropertiesAndBody(note.markdown)
        val folder = props.folder?.trim()?.takeIf { it.isNotBlank() }
        foldersByNote[note.id] = folder
        if (folder != null) folderSet.add(folder)
        aliasesByNote[note.id] = props.aliases

        val noteTags = extractNoteTagsFromParts(props.tags, body)
        tagsByNote[note.id] = noteTags
        for (tag in noteTags) {
            tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
        }
    }

    val allTags = tagCounts.entries.sortedByDescending { it.value }.map { it.key }
    val allFolders = folderSet.sorted()

    val linkIndex = NoteLinkIndex(notes, bindings)
    val activeKeys = HashSet<String>(notes.size * 2)
    for (n in notes) {
        if (n.trashedAt != null) continue
        activeKeys.add(NoteReferences.key(n.title))
        aliasesByNote[n.id].orEmpty().forEach { activeKeys.add(NoteReferences.key(it)) }
    }

    val edgesSet = LinkedHashSet<Pair<Long, Long>>()
    val unresolvedMap = HashMap<Long, List<String>>()
    for (source in notes) {
        val refs = NoteReferences.parse(source.markdown)
        if (refs.isEmpty()) continue
        var missing: MutableList<String>? = null
        for (ref in refs) {
            val resolved = linkIndex.target(source.id, ref)
            if (resolved != null) {
                if (resolved.id != source.id && resolved.trashedAt == null) {
                    edgesSet.add(source.id to resolved.id)
                }
            } else if (linkIndex.candidates(source.id, ref).isEmpty()) {
                val cleanTarget = ref.target.substringBefore('#').trim()
                if (cleanTarget.isNotBlank() && NoteReferences.key(cleanTarget) !in activeKeys) {
                    if (missing == null) missing = mutableListOf()
                    missing.add(cleanTarget)
                }
            }
        }
        if (!missing.isNullOrEmpty()) {
            unresolvedMap[source.id] = missing.distinctBy { NoteReferences.key(it) }
        }
    }

    val ghostKeys = HashSet<String>()
    for (list in unresolvedMap.values) {
        for (title in list) ghostKeys.add(NoteReferences.key(title))
    }

    return GraphIndexData(
        tagsByNote = tagsByNote,
        foldersByNote = foldersByNote,
        aliasesByNote = aliasesByNote,
        allTags = allTags,
        allFolders = allFolders,
        rawEdges = edgesSet.toList(),
        unresolvedByNote = unresolvedMap,
        totalGhostCount = ghostKeys.size
    )
}

private class GraphTopologyInput(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val pinnedId: Long?,
    val reheatOnChange: Boolean = true
)

private class GraphRenderSnapshot(
    val nodeIds: LongArray = LongArray(0),
    val idToIndex: HashMap<Long, Int> = HashMap(0),
    val xs: FloatArray = FloatArray(0),
    val ys: FloatArray = FloatArray(0),
    val isActive: Boolean = false,
    val layoutVersion: Long = 0L
)

private class DragOverride(
    val nodeId: Long,
    val x: Float,
    val y: Float,
    val active: Boolean
)

private class MutableVec2(var x: Float, var y: Float)

/**
 * Single-actor background force-directed physics controller running on [Dispatchers.Default].
 * Uses primitive FloatArrays for all O(N^2) force computations and publishes throttled ~60fps
 * coordinate array snapshots until settled.
 */
private class GraphSimController {
    private val topologyFlow = MutableStateFlow<GraphTopologyInput?>(null)
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    val renderFlow = MutableStateFlow(GraphRenderSnapshot())

    @Volatile var repulsionMultiplier: Float = 1.0f
    @Volatile var springLengthMultiplier: Float = 1.0f
    @Volatile var gravityMultiplier: Float = 1.0f
    @Volatile private var pendingEnergy: Float = 0f
    @Volatile private var dragOverride: DragOverride? = null

    fun updateTopology(nodes: List<GraphNode>, edges: List<GraphEdge>, pinnedId: Long?) {
        topologyFlow.value = GraphTopologyInput(nodes, edges, pinnedId)
        wakeChannel.trySend(Unit)
    }

    fun reheat(energy: Float = 1.0f) {
        pendingEnergy = max(pendingEnergy, energy)
        wakeChannel.trySend(Unit)
    }

    fun setDrag(nodeId: Long, x: Float, y: Float) {
        dragOverride = DragOverride(nodeId, x, y, active = true)
        pendingEnergy = max(pendingEnergy, 0.72f)
        wakeChannel.trySend(Unit)
    }

    fun clearDrag(nodeId: Long, x: Float, y: Float) {
        dragOverride = DragOverride(nodeId, x, y, active = false)
        wakeChannel.trySend(Unit)
    }

    suspend fun runLoop() = withContext(Dispatchers.Default) {
        val persistedPositions = HashMap<Long, MutableVec2>()
        var seenTopology: GraphTopologyInput? = null

        var nCount = 0
        var nodeIds = LongArray(0)
        var nodeDegrees = IntArray(0)
        var idToIndex = HashMap<Long, Int>(0)
        var px = FloatArray(0)
        var py = FloatArray(0)
        var vx = FloatArray(0)
        var vy = FloatArray(0)
        var fx = FloatArray(0)
        var fy = FloatArray(0)

        var edgeSrc = IntArray(0)
        var edgeDst = IntArray(0)
        var validEdgeCount = 0
        var pinIdx = -1
        var worldHalfLimit = WORLD_NOMINAL_HALF
        var energy = 0f
        var layoutVersion = 0L

        val gridHead = IntArray(4096)
        var gridNext = IntArray(256)

        fun saveCurrentPositions() {
            for (i in 0 until nCount) {
                val x = px[i]
                val y = py[i]
                if (!x.isFinite() || !y.isFinite()) continue
                val id = nodeIds[i]
                val existing = persistedPositions[id]
                if (existing != null) {
                    existing.x = x
                    existing.y = y
                } else {
                    persistedPositions[id] = MutableVec2(x, y)
                }
            }
        }

        fun stepPhysics(activeDragIdx: Int) {
            if (nCount <= 1) return
            fx.fill(0f, 0, nCount)
            fy.fill(0f, 0, nCount)

            val idealLen = (155f + sqrt(nCount.toFloat()) * 14f)
                .coerceIn(135f, 340f) * springLengthMultiplier
            val repulsionK = idealLen * idealLen * 0.92f * repulsionMultiplier
            val gMult = gravityMultiplier

            // 1. Coulomb repulsion: spatial uniform grid O(N) for large graphs (>180 nodes), pairwise for smaller graphs
            if (nCount <= 180) {
                for (i in 0 until nCount) {
                    val x1 = px[i]
                    val y1 = py[i]
                    if (!x1.isFinite() || !y1.isFinite()) continue
                    for (j in i + 1 until nCount) {
                        val x2 = px[j]
                        val y2 = py[j]
                        if (!x2.isFinite() || !y2.isFinite()) continue
                        val dx = x1 - x2
                        val dy = y1 - y2
                        val rawSq = dx * dx + dy * dy
                        if (!rawSq.isFinite()) continue
                        val distSq = if (rawSq < 100f) 100f else rawSq
                        val dist = sqrt(distSq)
                        val rep = (repulsionK / distSq).coerceAtMost(58f)
                        val scaleF = rep / dist
                        val rx = dx * scaleF
                        val ry = dy * scaleF
                        fx[i] += rx
                        fy[i] += ry
                        fx[j] -= rx
                        fy[j] -= ry
                    }
                }
            } else {
                val cutoff = (idealLen * 5.0f).coerceAtLeast(240f)
                val maxRepulseDistSq = cutoff * cutoff
                val invCell = 1f / cutoff
                if (gridNext.size < nCount) {
                    gridNext = IntArray(max(nCount * 2, 512))
                }
                gridHead.fill(-1)
                for (i in 0 until nCount) {
                    val x = px[i]
                    val y = py[i]
                    if (!x.isFinite() || !y.isFinite()) {
                        gridNext[i] = -1
                        continue
                    }
                    val cx = kotlin.math.floor(x * invCell).toInt()
                    val cy = kotlin.math.floor(y * invCell).toInt()
                    val h = ((cx * 73856093) xor (cy * 19349663)) and 4095
                    gridNext[i] = gridHead[h]
                    gridHead[h] = i
                }
                for (i in 0 until nCount) {
                    val x1 = px[i]
                    val y1 = py[i]
                    if (!x1.isFinite() || !y1.isFinite()) continue
                    val cx = kotlin.math.floor(x1 * invCell).toInt()
                    val cy = kotlin.math.floor(y1 * invCell).toInt()
                    for (ox in -1..1) {
                        for (oy in -1..1) {
                            val h = (((cx + ox) * 73856093) xor ((cy + oy) * 19349663)) and 4095
                            var j = gridHead[h]
                            while (j != -1) {
                                if (j > i) {
                                    val dx = x1 - px[j]
                                    val dy = y1 - py[j]
                                    val rawSq = dx * dx + dy * dy
                                    if (rawSq <= maxRepulseDistSq && rawSq.isFinite()) {
                                        val distSq = if (rawSq < 100f) 100f else rawSq
                                        val dist = sqrt(distSq)
                                        val rep = (repulsionK / distSq).coerceAtMost(58f)
                                        val scaleF = rep / dist
                                        val rx = dx * scaleF
                                        val ry = dy * scaleF
                                        fx[i] += rx
                                        fy[i] += ry
                                        fx[j] -= rx
                                        fy[j] -= ry
                                    }
                                }
                                j = gridNext[j]
                            }
                        }
                    }
                }
            }

            // 2. Hooke's law spring attraction along edges
            for (e in 0 until validEdgeCount) {
                val s = edgeSrc[e]
                val t = edgeDst[e]
                val dx = px[t] - px[s]
                val dy = py[t] - py[s]
                val dist = max(hypot(dx, dy), 1f)
                val springForce = (dist - idealLen) * 0.082f
                val scaleF = springForce / dist
                val sx = dx * scaleF
                val sy = dy * scaleF
                fx[s] += sx
                fy[s] += sy
                fx[t] -= sx
                fy[t] -= sy
            }

            // 3. Center gravity + soft outer world boundary
            for (i in 0 until nCount) {
                val baseGravity = if (nodeDegrees[i] == 0) 0.022f else 0.011f
                val gravity = baseGravity * gMult
                fx[i] -= px[i] * gravity
                fy[i] -= py[i] * gravity

                val r = hypot(px[i], py[i])
                if (r > worldHalfLimit) {
                    val excess = (r - worldHalfLimit) * 0.06f
                    val k = excess / r
                    fx[i] -= px[i] * k
                    fy[i] -= py[i] * k
                }
            }

            // 4. Integrate velocities & positions
            var maxSpeedSq = 0f
            for (i in 0 until nCount) {
                if (i == activeDragIdx || i == pinIdx) {
                    vx[i] = 0f
                    vy[i] = 0f
                    continue
                }
                var nvx = (vx[i] + fx[i] * energy) * 0.78f
                var nvy = (vy[i] + fy[i] * energy) * 0.78f
                var speedSq = nvx * nvx + nvy * nvy
                if (speedSq > 784f) { // 28f * 28f
                    val scaleV = 28f / sqrt(speedSq)
                    nvx *= scaleV
                    nvy *= scaleV
                    speedSq = 784f
                }
                val nextX = px[i] + nvx
                val nextY = py[i] + nvy
                if (nextX.isFinite() && nextY.isFinite()) {
                    vx[i] = nvx
                    vy[i] = nvy
                    px[i] = nextX
                    py[i] = nextY
                    if (speedSq > maxSpeedSq) maxSpeedSq = speedSq
                } else {
                    vx[i] = 0f
                    vy[i] = 0f
                    if (!px[i].isFinite()) px[i] = 0f
                    if (!py[i].isFinite()) py[i] = 0f
                }
            }

            if (activeDragIdx == -1) {
                energy *= 0.982f
                if (energy <= 0.008f || (energy < 0.14f && maxSpeedSq < 0.005f)) {
                    energy = 0f
                }
            }
        }

        var bufToggle = false
        var xsBuf0 = FloatArray(0)
        var ysBuf0 = FloatArray(0)
        var xsBuf1 = FloatArray(0)
        var ysBuf1 = FloatArray(0)
        while (isActive) {
            val latestTopology = topologyFlow.value
            if (latestTopology != null && latestTopology !== seenTopology) {
                saveCurrentPositions()
                seenTopology = latestTopology

                val nodes = latestTopology.nodes
                val edges = latestTopology.edges
                val pinId = latestTopology.pinnedId
                nCount = nodes.size

                if (px.size < nCount) {
                    px = FloatArray(nCount)
                    py = FloatArray(nCount)
                    vx = FloatArray(nCount)
                    vy = FloatArray(nCount)
                    fx = FloatArray(nCount)
                    fy = FloatArray(nCount)
                }
                if (xsBuf0.size != nCount) {
                    xsBuf0 = FloatArray(nCount)
                    ysBuf0 = FloatArray(nCount)
                    xsBuf1 = FloatArray(nCount)
                    ysBuf1 = FloatArray(nCount)
                }
                nodeIds = LongArray(nCount)
                nodeDegrees = IntArray(nCount)
                idToIndex = HashMap(nCount * 2 + 1)
                pinIdx = -1

                var hasFreshNodes = false
                for (i in 0 until nCount) {
                    val node = nodes[i]
                    nodeIds[i] = node.id
                    nodeDegrees[i] = node.degree
                    idToIndex[node.id] = i
                    vx[i] = 0f
                    vy[i] = 0f
                    if (node.id == pinId) {
                        pinIdx = i
                        px[i] = 0f
                        py[i] = 0f
                    } else {
                        val prev = persistedPositions[node.id]
                        if (prev != null) {
                            px[i] = prev.x
                            py[i] = prev.y
                        } else {
                            hasFreshNodes = true
                            val angle = i * 2.3999632f
                            val seedRadius = (90f + 56f * sqrt((i + 1f)))
                                .coerceAtMost(WORLD_NOMINAL_HALF * 0.85f)
                            px[i] = cos(angle) * seedRadius
                            py[i] = sin(angle) * seedRadius
                        }
                    }
                }

                if (edgeSrc.size < edges.size) {
                    edgeSrc = IntArray(edges.size)
                    edgeDst = IntArray(edges.size)
                }
                validEdgeCount = 0
                for (edge in edges) {
                    val sIdx = idToIndex[edge.sourceId]
                    val tIdx = idToIndex[edge.targetId]
                    if (sIdx != null && tIdx != null && sIdx != tIdx) {
                        edgeSrc[validEdgeCount] = sIdx
                        edgeDst[validEdgeCount] = tIdx
                        validEdgeCount++
                    }
                }

                worldHalfLimit = max(WORLD_NOMINAL_HALF, sqrt(nCount.coerceAtLeast(1).toFloat()) * 220f)
                energy = if (nCount > 1) 1.0f else 0f

                // Pre-settle initial cold layout off the main thread so graph opens structured
                if (nCount > 1 && hasFreshNodes) {
                    val warmupSteps = if (nCount <= 250) 18 else 6
                    for (step in 0 until warmupSteps) {
                        stepPhysics(activeDragIdx = -1)
                        if (energy <= 0f) break
                    }
                }
                saveCurrentPositions()
                layoutVersion++
                renderFlow.value = GraphRenderSnapshot(
                    nodeIds = nodeIds,
                    idToIndex = idToIndex,
                    xs = px.copyOf(nCount),
                    ys = py.copyOf(nCount),
                    isActive = energy > 0f,
                    layoutVersion = layoutVersion
                )
            }

            val req = pendingEnergy
            if (req > 0f) {
                pendingEnergy = 0f
                if (nCount > 1) energy = max(energy, req)
            }

            val ov = dragOverride
            var activeDragIdx = -1
            if (ov != null) {
                val idx = idToIndex[ov.nodeId]
                if (idx != null && idx < nCount) {
                    px[idx] = ov.x
                    py[idx] = ov.y
                    vx[idx] = 0f
                    vy[idx] = 0f
                    if (ov.active) {
                        activeDragIdx = idx
                        energy = max(energy, 0.65f)
                    } else if (dragOverride === ov) {
                        dragOverride = null
                    }
                } else if (!ov.active && dragOverride === ov) {
                    dragOverride = null
                }
            }

            if (nCount > 1 && (energy > 0.008f || activeDragIdx != -1)) {
                stepPhysics(activeDragIdx)
                val active = energy > 0f || activeDragIdx != -1
                if (!active) saveCurrentPositions()
                if (xsBuf0.size != nCount) {
                    xsBuf0 = FloatArray(nCount)
                    ysBuf0 = FloatArray(nCount)
                    xsBuf1 = FloatArray(nCount)
                    ysBuf1 = FloatArray(nCount)
                }
                val outX = if (bufToggle) xsBuf0 else xsBuf1
                val outY = if (bufToggle) ysBuf0 else ysBuf1
                bufToggle = !bufToggle
                System.arraycopy(px, 0, outX, 0, nCount)
                System.arraycopy(py, 0, outY, 0, nCount)
                renderFlow.value = GraphRenderSnapshot(
                    nodeIds = nodeIds,
                    idToIndex = idToIndex,
                    xs = outX,
                    ys = outY,
                    isActive = active,
                    layoutVersion = layoutVersion
                )
                delay(16L)
            } else {
                energy = 0f
                saveCurrentPositions()
                if (renderFlow.value.isActive) {
                    renderFlow.value = GraphRenderSnapshot(
                        nodeIds = nodeIds,
                        idToIndex = idToIndex,
                        xs = px.copyOf(nCount),
                        ys = py.copyOf(nCount),
                        isActive = false,
                        layoutVersion = layoutVersion
                    )
                }
                wakeChannel.receive()
            }
        }
    }
}

/**
 * Cached AndroidPaint / AndroidPath / primitive scratch buffers for zero-allocation Canvas drawing.
 */
private class GraphDrawCache {
    val starPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }
    val normalEdgePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.9f
        style = AndroidPaint.Style.STROKE
    }
    val ghostEdgePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.6f
        style = AndroidPaint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    val activeEdgePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3.5f
        style = AndroidPaint.Style.STROKE
    }
    val arrowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }
    val arrowPath = AndroidPath()

    val glowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }
    val nodePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }
    val ghostFillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }
    val ghostStrokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    val ringPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.STROKE }
    val labelPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val labelMetrics = AndroidPaint.FontMetrics()
    val pillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }

    var screenXs = FloatArray(0)
    var screenYs = FloatArray(0)
    var screenRadii = FloatArray(0)

    fun ensureCapacity(n: Int) {
        if (screenXs.size < n) {
            screenXs = FloatArray(n)
            screenYs = FloatArray(n)
            screenRadii = FloatArray(n)
        }
    }
}

private class GraphRenderMeta(
    val nodeColorsArgb: IntArray,
    val nodeRingColorsArgb: IntArray,
    val nodeLabels: Array<String>,
    val nodeLabelWidths: FloatArray,
    val edgeSrcIdx: IntArray,
    val edgeDstIdx: IntArray,
    val edgeIsGhost: BooleanArray,
    val validEdgeCount: Int
)

private enum class GraphSettingsTab(val title: String) {
    FILTERS("Filtros"),
    GROUPS("Grupos"),
    PHYSICS("Física")
}

// ============================================================================
// KNOWLEDGE GRAPH COMPOSABLE (FULL-BLEED OBSIDIAN MOBILE DESIGN)
// ============================================================================

@Composable
fun KnowledgeGraph(
    notes: List<Note>,
    bindings: List<NoteLink>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    initialSelectedId: Long? = null,
    focusedNoteId: Long? = null,
    onCreateMissingNote: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onCreateNote: (() -> Unit)? = null,
    showHeader: Boolean = true,
    headerTitle: String? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("studydock_obsidian_graph", Context.MODE_PRIVATE) }

    var selectedId by rememberSaveable { mutableStateOf(initialSelectedId ?: focusedNoteId ?: notes.firstOrNull()?.id) }
    var localCenterId by rememberSaveable { mutableStateOf(focusedNoteId) }
    var localDepth by rememberSaveable { mutableIntStateOf(if (focusedNoteId != null) 1 else 2) }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pendingAutoFit by remember { mutableStateOf(true) }

    // Filter & Display states
    var showGhostNodes by rememberSaveable { mutableStateOf(true) }
    var hideOrphans by rememberSaveable { mutableStateOf(false) }
    var showTagNodes by rememberSaveable { mutableStateOf(false) }
    var showArrows by rememberSaveable { mutableStateOf(true) }
    var alwaysShowLabels by rememberSaveable { mutableStateOf(false) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearchField by rememberSaveable { mutableStateOf(false) }

    // Physics parameters (adjustable in real time)
    var repulsionMultiplier by rememberSaveable { mutableFloatStateOf(1.0f) }
    var springLengthMultiplier by rememberSaveable { mutableFloatStateOf(1.0f) }
    var gravityMultiplier by rememberSaveable { mutableFloatStateOf(1.0f) }
    var nodeSizeMultiplier by rememberSaveable { mutableFloatStateOf(1.0f) }
    var labelZoomThreshold by rememberSaveable { mutableFloatStateOf(1.05f) }

    // Unified Settings Sheet & Quick Menus
    var showSettingsSheet by remember { mutableStateOf(false) }
    var settingsTab by rememberSaveable { mutableStateOf(GraphSettingsTab.FILTERS) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showPresetsMenu by remember { mutableStateOf(false) }
    var showQuickCreateNoteSheet by remember { mutableStateOf(false) }
    var searchMatchCycleIndex by remember { mutableIntStateOf(0) }

    // Persisted color overrides and custom rules (loaded off UI thread)
    var groupColors by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var customRules by remember { mutableStateOf<List<GraphColorRule>>(emptyList()) }
    LaunchedEffect(prefs) {
        val (loadedColors, loadedRules) = withContext(Dispatchers.IO) {
            loadGroupColors(prefs) to loadCustomRules(prefs)
        }
        groupColors = loadedColors
        customRules = loadedRules
    }
    var colorMode by rememberSaveable { mutableIntStateOf(0) } // 0 = Por #Tag, 1 = Por Pasta

    fun updateGroupColor(groupKey: String, colorArgb: Int) {
        val next = groupColors.toMutableMap().apply { put(groupKey, colorArgb) }
        groupColors = next
        saveGroupColors(prefs, next)
    }

    fun updateCustomRules(nextRules: List<GraphColorRule>) {
        customRules = nextRules
        saveCustomRules(prefs, nextRules)
    }

    LaunchedEffect(initialSelectedId) {
        if (initialSelectedId != null) {
            selectedId = initialSelectedId
        }
    }

    LaunchedEffect(focusedNoteId) {
        if (focusedNoteId != null) {
            localCenterId = focusedNoteId
            selectedId = focusedNoteId
            pendingAutoFit = true
        }
    }

    // Offload note/link/tag/folder indexing onto Dispatchers.Default
    var asyncIndexData by remember { mutableStateOf<GraphIndexData?>(null) }
    LaunchedEffect(notes, bindings) {
        asyncIndexData = withContext(Dispatchers.Default) {
            buildGraphIndexData(notes, bindings)
        }
    }
    val isIndexReady = notes.isEmpty() || asyncIndexData != null
    val indexData = asyncIndexData ?: GraphIndexData()
    val tagsByNote = indexData.tagsByNote
    val foldersByNote = indexData.foldersByNote
    val aliasesByNote = indexData.aliasesByNote
    val allTags = indexData.allTags
    val allFolders = indexData.allFolders
    val rawEdges = indexData.rawEdges
    val unresolvedByNote = indexData.unresolvedByNote
    val totalGhostCount = indexData.totalGhostCount

    fun stableGhostId(key: String): Long {
        var h = 1125899906842597L
        for (i in key.indices) h = 31L * h + key[i].code.toLong()
        return GHOST_VIRTUAL_BASE_ID - (h and 0x3FFFFFFFL)
    }

    // Apply Local Graph neighborhood if active (1..3 hops) via single O(V + E) BFS
    val (scopedNotes, hopDistanceByNoteId) = remember(notes, rawEdges, localCenterId, localDepth) {
        val centerId = localCenterId
        val notesById = notes.associateBy { it.id }
        if (centerId == null || centerId !in notesById) {
            notes to emptyMap<Long, Int>()
        } else {
            val clampedDepth = localDepth.coerceIn(1, 3)
            val adj = HashMap<Long, MutableList<Long>>(rawEdges.size)
            for ((s, t) in rawEdges) {
                adj.getOrPut(s) { mutableListOf() }.add(t)
                adj.getOrPut(t) { mutableListOf() }.add(s)
            }
            val dists = LinkedHashMap<Long, Int>()
            dists[centerId] = 0
            var frontier = listOf(centerId)
            for (d in 1..clampedDepth) {
                val next = ArrayList<Long>()
                for (id in frontier) {
                    for (nb in adj[id].orEmpty()) {
                        if (nb !in dists) {
                            dists[nb] = d
                            next.add(nb)
                        }
                    }
                }
                if (next.isEmpty()) break
                frontier = next
            }
            val neighborhoodNotes = dists.keys.mapNotNull { notesById[it] }
            neighborhoodNotes to dists
        }
    }

    // Real degrees including unresolved links when ghost nodes are visible
    val degrees = remember(notes, rawEdges, unresolvedByNote, showGhostNodes, tagsByNote, showTagNodes) {
        val linkDegrees = HashMap<Long, Int>(notes.size * 2)
        for ((s, t) in rawEdges) {
            linkDegrees[s] = (linkDegrees[s] ?: 0) + 1
            linkDegrees[t] = (linkDegrees[t] ?: 0) + 1
        }
        notes.associate { n ->
            val ghostDeg = if (showGhostNodes) unresolvedByNote[n.id]?.size ?: 0 else 0
            val tagDeg = if (showTagNodes) tagsByNote[n.id]?.size ?: 0 else 0
            n.id to ((linkDegrees[n.id] ?: 0) + ghostDeg + tagDeg)
        }
    }

    // Structural filters (tag, folder, orphan filter). Search highlights instead of deleting context!
    val filteredNotes = remember(
        scopedNotes,
        hideOrphans,
        selectedTag,
        selectedFolder,
        degrees,
        tagsByNote,
        foldersByNote,
        localCenterId
    ) {
        scopedNotes.filter { note ->
            if (note.id == localCenterId) return@filter true
            val degree = degrees[note.id] ?: 0
            if (hideOrphans && degree == 0) return@filter false
            val noteTagList = tagsByNote[note.id].orEmpty()
            val noteFolder = foldersByNote[note.id]
            if (selectedTag != null && noteTagList.none { tagMatchesHierarchical(it, selectedTag!!) }) return@filter false
            if (selectedFolder != null && !pathMatchesPrefix(noteFolder, selectedFolder!!)) return@filter false
            true
        }
    }

    // Build graph nodes (real notes + ghost nodes + optional #tag virtual nodes) and edges
    val (graphNodes, filteredEdges) = remember(
        filteredNotes,
        rawEdges,
        unresolvedByNote,
        showGhostNodes,
        showTagNodes,
        tagsByNote,
        foldersByNote,
        degrees,
        localCenterId,
        localDepth,
        hopDistanceByNoteId
    ) {
        val cappedFilteredNotes = if (filteredNotes.size > 400) {
            filteredNotes.sortedWith(
                compareByDescending<Note> { it.id == localCenterId }
                    .thenByDescending { degrees[it.id] ?: 0 }
                    .thenByDescending { it.updatedAt }
            ).take(400)
        } else {
            filteredNotes
        }
        val allowedNoteIds = cappedFilteredNotes.map { it.id }.toSet()
        val noteNodes = cappedFilteredNotes.map { note ->
            val tList = tagsByNote[note.id].orEmpty()
            GraphNode(
                id = note.id,
                title = note.title,
                folder = foldersByNote[note.id],
                primaryTag = tList.firstOrNull(),
                degree = degrees[note.id] ?: 0,
                isTagNode = false,
                allTags = tList
            )
        }
        val baseEdges = rawEdges
            .filter { it.first in allowedNoteIds && it.second in allowedNoteIds }
            .map { GraphEdge(it.first, it.second, isGhostEdge = false) }

        val extraNodes = mutableListOf<GraphNode>()
        val extraEdges = mutableListOf<GraphEdge>()

        if (showGhostNodes) {
            val ghostTitleByKey = linkedMapOf<String, String>()
            val ghostDegreeByKey = mutableMapOf<String, Int>()
            val pendingGhostEdges = mutableListOf<Pair<Long, String>>()
            val maxHopForGhostSource = if (localCenterId != null) localDepth.coerceIn(1, 3) - 1 else Int.MAX_VALUE

            val ghostIdMap = HashMap<String, Long>(64)
            for (note in cappedFilteredNotes) {
                val hop = hopDistanceByNoteId[note.id] ?: 0
                if (localCenterId != null && hop > maxHopForGhostSource) continue
                val refs = unresolvedByNote[note.id].orEmpty()
                for (title in refs) {
                    val key = NoteReferences.key(title)
                    if (ghostTitleByKey.size >= 150 && key !in ghostTitleByKey) continue
                    ghostTitleByKey.putIfAbsent(key, title)
                    ghostDegreeByKey[key] = (ghostDegreeByKey[key] ?: 0) + 1
                    pendingGhostEdges.add(note.id to key)
                }
            }

            for ((key, displayTitle) in ghostTitleByKey) {
                val gId = stableGhostId(key)
                ghostIdMap[key] = gId
                extraNodes.add(
                    GraphNode(
                        id = gId,
                        title = displayTitle,
                        folder = null,
                        primaryTag = null,
                        degree = ghostDegreeByKey[key] ?: 1,
                        isTagNode = false,
                        allTags = emptyList(),
                        isGhost = true,
                        ghostTitle = displayTitle
                    )
                )
            }
            for ((sourceId, key) in pendingGhostEdges) {
                val gId = ghostIdMap[key] ?: continue
                extraEdges.add(GraphEdge(sourceId, gId, isGhostEdge = true))
            }
        }

        if (showTagNodes) {
            val tagDegreeMap = LinkedHashMap<String, Int>()
            for (note in cappedFilteredNotes) {
                for (t in tagsByNote[note.id].orEmpty()) {
                    tagDegreeMap[t] = (tagDegreeMap[t] ?: 0) + 1
                }
            }
            val tagNodeList = tagDegreeMap.entries.take(120).mapIndexed { idx, (tag, count) ->
                val virtualId = TAG_VIRTUAL_BASE_ID - idx
                GraphNode(
                    id = virtualId,
                    title = "#$tag",
                    folder = null,
                    primaryTag = tag,
                    degree = count,
                    isTagNode = true,
                    allTags = listOf(tag)
                )
            }
            val tagIdByName = tagNodeList.associate { it.primaryTag!! to it.id }
            val tagEdges = cappedFilteredNotes.flatMap { note ->
                tagsByNote[note.id].orEmpty().mapNotNull { t ->
                    tagIdByName[t]?.let { tagId -> GraphEdge(note.id, tagId, isGhostEdge = false) }
                }
            }
            extraNodes.addAll(tagNodeList)
            extraEdges.addAll(tagEdges)
        }

        (noteNodes + extraNodes) to (baseEdges + extraEdges)
    }

    val nodesById = remember(graphNodes) { graphNodes.associateBy { it.id } }

    // Real-time search matching + neighbor highlighting
    val (searchMatchIds, searchNeighborIds) = remember(query, graphNodes, filteredEdges, aliasesByNote) {
        val rawQ = query.trim()
        if (rawQ.isEmpty()) {
            emptySet<Long>() to emptySet<Long>()
        } else {
            val qTag = rawQ.removePrefix("tag:").removePrefix("#").trim()
            val qPath = rawQ.removePrefix("path:").removePrefix("folder:").trim()
            val isTagQuery = rawQ.startsWith("#") || rawQ.startsWith("tag:", ignoreCase = true)
            val isPathQuery = rawQ.startsWith("path:", ignoreCase = true) || rawQ.startsWith("folder:", ignoreCase = true)

            val matches = graphNodes.filter { node ->
                when {
                    isTagQuery -> node.allTags.any { tagMatchesHierarchical(it, qTag) }
                    isPathQuery -> pathMatchesPrefix(node.folder, qPath)
                    else -> {
                        node.title.contains(rawQ, ignoreCase = true) ||
                            aliasesByNote[node.id].orEmpty().any { it.contains(rawQ, ignoreCase = true) } ||
                            node.allTags.any { it.contains(rawQ.removePrefix("#"), ignoreCase = true) } ||
                            (node.folder?.contains(rawQ, ignoreCase = true) == true)
                    }
                }
            }.map { it.id }.toSet()

            val neighbors = mutableSetOf<Long>()
            if (matches.isNotEmpty()) {
                for (edge in filteredEdges) {
                    if (edge.sourceId in matches) neighbors.add(edge.targetId)
                    if (edge.targetId in matches) neighbors.add(edge.sourceId)
                }
                neighbors.removeAll(matches)
            }
            matches to neighbors
        }
    }

    LaunchedEffect(searchMatchIds) {
        searchMatchCycleIndex = 0
    }

    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val graphTextColor = scheme.onBackground
    val graphMutedColor = scheme.onSurfaceVariant
    val graphAccentColor = scheme.primary
    val graphSurfaceColor = scheme.surfaceContainerHigh

    fun resolveNodeColor(node: GraphNode): Int {
        if (node.isGhost) return scheme.outline.toArgb()
        if (node.isTagNode) {
            val tag = node.primaryTag.orEmpty()
            return lookupHierarchicalColor(groupColors, "tag", tag) ?: defaultColorForKey("tag:${tag.lowercase(Locale.ROOT)}")
        }
        for (rule in customRules) {
            if (matchesColorRule(node, rule.query)) return rule.colorArgb
        }
        if (colorMode == 1 && !node.folder.isNullOrBlank()) {
            return lookupHierarchicalColor(groupColors, "folder", node.folder)
                ?: defaultColorForKey("folder:${node.folder.lowercase(Locale.ROOT)}")
        }
        if (node.primaryTag != null) {
            return lookupHierarchicalColor(groupColors, "tag", node.primaryTag)
                ?: defaultColorForKey("tag:${node.primaryTag.lowercase(Locale.ROOT)}")
        }
        if (!node.folder.isNullOrBlank()) {
            return lookupHierarchicalColor(groupColors, "folder", node.folder)
                ?: defaultColorForKey("folder:${node.folder.lowercase(Locale.ROOT)}")
        }
        return groupColors["default"] ?: ObsidianPalette[0]
    }

    val selectedNode = selectedId?.let { nodesById[it] }
    val selectedNote = selectedId?.let { id -> notes.firstOrNull { it.id == id } }
    val selectedConnectedIds = remember(selectedId, filteredEdges) {
        if (selectedId == null) emptySet()
        else filteredEdges.filter { it.sourceId == selectedId || it.targetId == selectedId }
            .flatMap { listOf(it.sourceId, it.targetId) }.toSet()
    }

    // Background Physics Controller & Cached Draw State (paused when off-screen / stopped)
    val simController = remember { GraphSimController() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(simController, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            simController.runLoop()
        }
    }

    SideEffect {
        simController.repulsionMultiplier = repulsionMultiplier
        simController.springLengthMultiplier = springLengthMultiplier
        simController.gravityMultiplier = gravityMultiplier
    }

    LaunchedEffect(isIndexReady, graphNodes, filteredEdges, localCenterId, focusedNoteId) {
        if (!isIndexReady) return@LaunchedEffect
        val existingIds = graphNodes.map { it.id }.toSet()
        if (selectedId != null && selectedId !in existingIds) {
            selectedId = null
        }
        val pinId = localCenterId ?: focusedNoteId
        simController.updateTopology(graphNodes, filteredEdges, pinId)
    }

    var draggedNodeId by remember { mutableStateOf<Long?>(null) }
    var dragVisualPos by remember { mutableStateOf<Offset?>(null) }

    val drawCache = remember { GraphDrawCache() }
    val labelTextSizePx = with(density) { 11.sp.toPx() }
    SideEffect {
        drawCache.labelPaint.textSize = labelTextSizePx
        drawCache.labelPaint.getFontMetrics(drawCache.labelMetrics)
    }

    val renderMeta = remember(
        graphNodes,
        filteredEdges,
        groupColors,
        customRules,
        colorMode,
        scheme,
        labelTextSizePx
    ) {
        val nCount = graphNodes.size
        val nodeColorsArgb = IntArray(nCount)
        val nodeRingColorsArgb = IntArray(nCount)
        val nodeLabels = Array(nCount) { "" }
        val nodeLabelWidths = FloatArray(nCount)
        val idToIndex = HashMap<Long, Int>(nCount * 2 + 1)
        val measurePaint = drawCache.labelPaint
        measurePaint.textSize = labelTextSizePx

        val ringCandidates = arrayOf(
            scheme.onSurface,
            scheme.onPrimaryContainer,
            scheme.surface,
            scheme.primary
        )

        for (i in 0 until nCount) {
            val node = graphNodes[i]
            idToIndex[node.id] = i
            val cArgb = resolveNodeColor(node)
            nodeColorsArgb[i] = cArgb
            val baseColor = Color(cArgb)
            val bestRing = ringCandidates.maxByOrNull { cand ->
                min(contrastRatio(cand, baseColor), contrastRatio(cand, graphSurfaceColor))
            } ?: if (relativeLuminance(baseColor) < 0.25) scheme.onSurface else scheme.onPrimaryContainer
            nodeRingColorsArgb[i] = bestRing.toArgb()

            val rawTitle = if (node.isGhost) "+ ${node.title}" else node.title
            val truncated = rawTitle.take(26)
            nodeLabels[i] = truncated
            nodeLabelWidths[i] = measurePaint.measureText(truncated)
        }

        val edgeSrcIdx = IntArray(filteredEdges.size)
        val edgeDstIdx = IntArray(filteredEdges.size)
        val edgeIsGhost = BooleanArray(filteredEdges.size)
        var validCount = 0
        for (edge in filteredEdges) {
            val sIdx = idToIndex[edge.sourceId]
            val tIdx = idToIndex[edge.targetId]
            if (sIdx != null && tIdx != null) {
                edgeSrcIdx[validCount] = sIdx
                edgeDstIdx[validCount] = tIdx
                edgeIsGhost[validCount] = edge.isGhostEdge
                validCount++
            }
        }

        GraphRenderMeta(
            nodeColorsArgb = nodeColorsArgb,
            nodeRingColorsArgb = nodeRingColorsArgb,
            nodeLabels = nodeLabels,
            nodeLabelWidths = nodeLabelWidths,
            edgeSrcIdx = edgeSrcIdx,
            edgeDstIdx = edgeDstIdx,
            edgeIsGhost = edgeIsGhost,
            validEdgeCount = validCount
        )
    }

    val drawOrder = remember(
        graphNodes,
        selectedId,
        draggedNodeId,
        selectedConnectedIds,
        searchMatchIds,
        searchNeighborIds
    ) {
        val indices = IntArray(graphNodes.size) { it }
        indices.sortedBy { i ->
            val id = graphNodes[i].id
            when {
                id == selectedId || id == draggedNodeId -> 3
                id in searchMatchIds -> 2
                id in selectedConnectedIds || id in searchNeighborIds -> 1
                else -> 0
            }
        }.toIntArray()
    }

    val renderSnapshotState = simController.renderFlow.collectAsStateWithLifecycle()

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showSearchField) {
        if (showSearchField) {
            delay(60L)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        val boxWidth = constraints.maxWidth.toFloat().coerceAtLeast(100f)
        val boxHeight = constraints.maxHeight.toFloat().coerceAtLeast(100f)

        fun centerPanOnMedian(fitZoom: Boolean) {
            val snap = renderSnapshotState.value
            val count = snap.xs.size
            if (count == 0) {
                scale = 1f
                pan = Offset.Zero
                return
            }
            val sortedX = snap.xs.copyOf().apply { sort() }
            val sortedY = snap.ys.copyOf().apply { sort() }
            val medianX = sortedX[count / 2]
            val medianY = sortedY[count / 2]
            if (fitZoom && count > 1) {
                val spanW = (sortedX.last() - sortedX.first()).coerceAtLeast(160f)
                val spanH = (sortedY.last() - sortedY.first()).coerceAtLeast(160f)
                scale = min(
                    (boxWidth * 0.76f) / spanW,
                    (boxHeight * 0.72f) / spanH
                ).coerceIn(0.35f, 1.25f)
            }
            pan = Offset(-medianX * scale, -medianY * scale)
        }

        fun centerPanOnNode(nodeId: Long) {
            val snap = renderSnapshotState.value
            val idx = snap.idToIndex[nodeId] ?: return
            if (idx !in snap.xs.indices) return
            pan = Offset(-snap.xs[idx] * scale, -snap.ys[idx] * scale)
        }

        val currentLayoutVersion = renderSnapshotState.value.layoutVersion
        LaunchedEffect(currentLayoutVersion, pendingAutoFit, isIndexReady) {
            if (isIndexReady && pendingAutoFit && renderSnapshotState.value.xs.isNotEmpty()) {
                centerPanOnMedian(fitZoom = graphNodes.size > 2)
                pendingAutoFit = false
            }
        }

        // LAYER 1: Full-Bleed Zero-Allocation Physics Graph Canvas
        GraphCanvasViewport(
            graphNodes = graphNodes,
            renderMeta = renderMeta,
            drawOrder = drawOrder,
            drawCache = drawCache,
            renderSnapshotState = renderSnapshotState,
            simController = simController,
            scaleProvider = { scale },
            panProvider = { pan },
            onScalePanChange = { newScale, newPan ->
                scale = newScale
                pan = newPan
            },
            selectedId = selectedId,
            draggedNodeId = draggedNodeId,
            dragVisualPos = dragVisualPos,
            localCenterId = localCenterId,
            selectedConnectedIds = selectedConnectedIds,
            searchMatchIds = searchMatchIds,
            searchNeighborIds = searchNeighborIds,
            isSearching = searchMatchIds.isNotEmpty() || query.isNotBlank(),
            showArrows = showArrows,
            alwaysShowLabels = alwaysShowLabels,
            nodeSizeMultiplier = nodeSizeMultiplier,
            labelZoomThreshold = labelZoomThreshold,
            graphTextColor = graphTextColor,
            graphMutedColor = graphMutedColor,
            graphAccentColor = graphAccentColor,
            graphSurfaceColor = graphSurfaceColor,
            onDragStart = { nodeId, worldPos ->
                draggedNodeId = nodeId
                dragVisualPos = worldPos
                simController.setDrag(nodeId, worldPos.x, worldPos.y)
            },
            onDragMove = { nodeId, worldPos ->
                dragVisualPos = worldPos
                simController.setDrag(nodeId, worldPos.x, worldPos.y)
            },
            onDragEnd = { nodeId, worldPos ->
                if (nodeId != null && worldPos != null) {
                    simController.clearDrag(nodeId, worldPos.x, worldPos.y)
                }
                draggedNodeId = null
                dragVisualPos = null
            },
            onNodeTap = { hitNode ->
                when {
                    hitNode.isTagNode -> {
                        val tag = hitNode.primaryTag
                        selectedTag = if (selectedTag == tag) null else tag
                        selectedId = hitNode.id
                        simController.reheat(0.85f)
                    }
                    hitNode.isGhost -> {
                        if (selectedId == hitNode.id) {
                            onCreateMissingNote?.invoke(hitNode.ghostTitle ?: hitNode.title)
                        } else {
                            selectedId = hitNode.id
                        }
                    }
                    else -> {
                        if (selectedId == hitNode.id) {
                            onOpen(hitNode.id)
                        } else {
                            selectedId = hitNode.id
                        }
                    }
                }
            },
            onEmptyTap = { selectedId = null },
            modifier = Modifier.fillMaxSize()
        )

        // Indexing / Empty state overlay
        if (!isIndexReady) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (graphNodes.isEmpty()) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Hub, null, tint = scheme.primary, modifier = Modifier.size(40.dp))
                Text(
                    "Nenhuma nota neste grafo",
                    color = scheme.onBackground,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Conecte notas com [[nome]] ou ajuste os filtros em Configurações.",
                    color = graphMutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                if (hideOrphans || selectedTag != null || selectedFolder != null) {
                    FilledTonalButton(
                        onClick = {
                            hideOrphans = false
                            selectedTag = null
                            selectedFolder = null
                            pendingAutoFit = true
                            simController.reheat(0.9f)
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Limpar filtros")
                    }
                }
            }
        }

        // LAYER 2: Minimal Top Overlay Bar
        if (showHeader) {
            val resolvedTitle = when {
                headerTitle != null && localCenterId != null && !headerTitle.contains("·") ->
                    "$headerTitle · ${graphNodes.size}"
                headerTitle != null -> headerTitle
                localCenterId != null -> "Grafo Local · ${graphNodes.size}"
                else -> "Grafo"
            }

            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    GraphFloatingCircleButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        onClick = onBack
                    )
                } else if (localCenterId != null && focusedNoteId == null) {
                    GraphFloatingCircleButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar ao grafo global",
                        onClick = {
                            localCenterId = null
                            pendingAutoFit = true
                            simController.reheat(0.9f)
                        }
                    )
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                Text(
                    text = resolvedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )

                Box {
                    GraphFloatingCircleButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = "Opções do grafo",
                        onClick = { showOverflowMenu = true }
                    )
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Enquadrar grafo") },
                            leadingIcon = { Icon(Icons.Default.CenterFocusStrong, null) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            onClick = {
                                showOverflowMenu = false
                                centerPanOnMedian(fitZoom = true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Resetar física") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            onClick = {
                                showOverflowMenu = false
                                pendingAutoFit = true
                                simController.reheat(1.0f)
                            }
                        )
                        val activeFocusId = selectedNote?.id ?: localCenterId
                        if (activeFocusId != null) {
                            DropdownMenuItem(
                                text = { Text("Centralizar nota ativa") },
                                leadingIcon = { Icon(Icons.Default.MyLocation, null) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                onClick = {
                                    showOverflowMenu = false
                                    centerPanOnNode(activeFocusId)
                                }
                            )
                        }
                        if (localCenterId != null && focusedNoteId == null) {
                            DropdownMenuItem(
                                text = { Text("Ver grafo global") },
                                leadingIcon = { Icon(Icons.Default.Public, null) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                onClick = {
                                    showOverflowMenu = false
                                    localCenterId = null
                                    pendingAutoFit = true
                                    simController.reheat(0.9f)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (hideOrphans) "Mostrar notas órfãs" else "Ocultar notas órfãs") },
                            leadingIcon = {
                                Icon(
                                    if (hideOrphans) Icons.Default.Check else Icons.Default.VisibilityOff,
                                    null
                                )
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            onClick = {
                                showOverflowMenu = false
                                hideOrphans = !hideOrphans
                                simController.reheat(0.85f)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copiar resumo do grafo") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            onClick = {
                                showOverflowMenu = false
                                val clusterLine = UniversalDomainGraph.buildTopicClusterSummary(
                                    topic = selectedNode?.title ?: resolvedTitle,
                                    noteCount = filteredNotes.size,
                                    cardCount = 0,
                                    bookCount = 0,
                                    eloRating = com.studydock.app.data.MathEloEngine.DEFAULT_ELO
                                )
                                val summary = buildString {
                                    appendLine("# $resolvedTitle")
                                    appendLine("- Domínio: $clusterLine")
                                    appendLine("- Notas: ${filteredNotes.size}")
                                    appendLine("- Conexões: ${filteredEdges.size}")
                                    if (selectedNode != null) {
                                        appendLine("- Selecionada: [[${selectedNode.title}]] (${selectedNode.degree} conexões)")
                                    }
                                }
                                clipboard.setText(AnnotatedString(summary.trim()))
                                Toast.makeText(context, "Resumo copiado", Toast.LENGTH_SHORT).show()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Configurações do grafo") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            onClick = {
                                showOverflowMenu = false
                                showSettingsSheet = true
                            }
                        )
                    }
                }
            }
        }

        // LAYER 3: Unified Local Hop Depth Pill (TopStart below header, ONLY when local mode is active)
        val topControlsOffset = if (showHeader) 68.dp else 12.dp
        if (localCenterId != null) {
            Surface(
                shape = CircleShape,
                color = scheme.surfaceContainerHigh.copy(alpha = 0.90f),
                tonalElevation = 4.dp,
                border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.42f)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = topControlsOffset, start = 12.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (1..3).forEach { hop ->
                        val active = localDepth == hop
                        Box(
                            Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clip(CircleShape)
                                .background(if (active) scheme.primaryContainer else Color.Transparent)
                                .clickable {
                                    localDepth = hop
                                    pendingAutoFit = true
                                    simController.reheat(0.85f)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${hop}x",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // LAYER 4: Only 2 Quiet Top-Right Canvas Icons (below ⋮): Settings & Reheat/Fit
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = topControlsOffset, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            GraphFloatingCircleButton(
                icon = Icons.Default.Settings,
                contentDescription = "Configurações do grafo",
                onClick = { showSettingsSheet = true },
                active = showSettingsSheet || selectedTag != null || selectedFolder != null || hideOrphans
            )
            GraphFloatingCircleButton(
                icon = Icons.Default.AutoAwesome,
                contentDescription = "Reorganizar e enquadrar",
                onClick = {
                    simController.reheat(1.0f)
                    centerPanOnMedian(fitZoom = true)
                }
            )
        }

        // LAYER 5: Bottom Floating Stack (Search Capsule + Selected Node Pill + Obsidian Bottom Pill Bar)
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
            // Optional On-Demand Floating Search Capsule
            if (showSearchField) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = scheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    "Buscar nota, #tag ou path:pasta…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                                cursorBrush = SolidColor(scheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    val matches = searchMatchIds.toList()
                                    if (matches.isNotEmpty()) {
                                        val targetId = matches[searchMatchCycleIndex % matches.size]
                                        searchMatchCycleIndex++
                                        selectedId = targetId
                                        centerPanOnNode(targetId)
                                    }
                                }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester)
                            )
                        }
                        if (query.isNotBlank()) {
                            val matches = searchMatchIds.toList()
                            Surface(
                                onClick = {
                                    if (matches.isNotEmpty()) {
                                        val targetId = matches[searchMatchCycleIndex % matches.size]
                                        searchMatchCycleIndex++
                                        selectedId = targetId
                                        centerPanOnNode(targetId)
                                    }
                                },
                                shape = CircleShape,
                                color = if (matches.isEmpty()) scheme.errorContainer else scheme.primaryContainer,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Box(
                                    Modifier.padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (matches.isEmpty()) "0" else "${matches.size} ↗",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (matches.isEmpty()) scheme.onErrorContainer else scheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                if (query.isNotEmpty()) query = "" else showSearchField = false
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Close, "Fechar busca", tint = scheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Selected Node Floating Action Pill
            if (selectedNode != null) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = scheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val dotColor = Color(resolveNodeColor(selectedNode))
                        if (selectedNode.isGhost) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, dotColor, CircleShape)
                                    .background(dotColor.copy(alpha = 0.25f))
                            )
                        } else {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
                        }

                        Column(Modifier.weight(1f)) {
                            Text(
                                selectedNode.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val deg = degrees[selectedNode.id] ?: selectedNode.degree
                            val subtitle = when {
                                selectedNode.isGhost -> "Não criada · $deg ref(s)"
                                selectedNode.isTagNode -> "Tag · $deg nota(s)"
                                else -> if (deg == 1) "1 conexão" else "$deg conexões"
                            }
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        when {
                            selectedNode.isGhost -> {
                                val createAction: (() -> Unit)? = when {
                                    onCreateMissingNote != null -> {
                                        { onCreateMissingNote(selectedNode.ghostTitle ?: selectedNode.title) }
                                    }
                                    onCreateNote != null -> onCreateNote
                                    else -> null
                                }
                                if (createAction != null) {
                                    Button(
                                        onClick = createAction,
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .widthIn(max = 190.dp)
                                    ) {
                                        Text(
                                            "Criar nota \"${selectedNode.title}\"",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            selectedNode.isTagNode -> {
                                Button(
                                    onClick = {
                                        val tag = selectedNode.primaryTag
                                        selectedTag = if (selectedTag == tag) null else tag
                                    },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        if (selectedTag == selectedNode.primaryTag) "Limpar" else "Filtrar #tag",
                                        maxLines = 1
                                    )
                                }
                            }
                            selectedNote != null -> {
                                Button(
                                    onClick = { onOpen(selectedNote.id) },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Abrir nota", maxLines = 1)
                                }
                                if (focusedNoteId == null) {
                                    val isLocalHere = localCenterId == selectedNote.id
                                    IconButton(
                                        onClick = {
                                            localCenterId = if (isLocalHere) null else selectedNote.id
                                            pendingAutoFit = true
                                            simController.reheat(0.9f)
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Hub,
                                            contentDescription = if (isLocalHere) "Sair do grafo local" else "Grafo local",
                                            tint = if (isLocalHere) scheme.primary else scheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        IconButton(
                            onClick = { selectedId = null },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Close, "Fechar seleção", tint = scheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Floating Obsidian Bottom Pill Bar (<, 🔍, +, [N], ≡)
            Surface(
                shape = CircleShape,
                color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.45f))
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // < (Back / Step out)
                    IconButton(
                        onClick = {
                            when {
                                onBack != null -> onBack()
                                localCenterId != null && focusedNoteId == null -> {
                                    localCenterId = null
                                    pendingAutoFit = true
                                    simController.reheat(0.9f)
                                }
                                selectedId != null -> selectedId = null
                                else -> centerPanOnMedian(fitZoom = true)
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Voltar",
                            tint = scheme.onSurface
                        )
                    }

                    // 🔍 (Toggle floating search capsule)
                    IconButton(
                        onClick = { showSearchField = !showSearchField },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Buscar no grafo",
                            tint = if (showSearchField || query.isNotBlank()) scheme.primary else scheme.onSurface
                        )
                    }

                    // + (Create note / missing ghost note)
                    if (onCreateNote != null || onCreateMissingNote != null) {
                        IconButton(
                            onClick = {
                                when {
                                    selectedNode?.isGhost == true && onCreateMissingNote != null ->
                                        onCreateMissingNote(selectedNode.ghostTitle ?: selectedNode.title)
                                    onCreateNote != null -> onCreateNote()
                                    onCreateMissingNote != null -> showQuickCreateNoteSheet = true
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Criar nota",
                                tint = scheme.onSurface
                            )
                        }
                    }

                    // [N] (Node/Edge count badge + Quick Presets)
                    Box {
                        Box(
                            Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clip(CircleShape)
                                .clickable { showPresetsMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.5.dp, scheme.onSurfaceVariant)
                            ) {
                                Text(
                                    text = "${graphNodes.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showPresetsMenu,
                            onDismissRequest = { showPresetsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${filteredNotes.size} notas · ${filteredEdges.size} links",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                enabled = false,
                                onClick = {},
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Enquadrar tudo") },
                                leadingIcon = { Icon(Icons.Default.CenterFocusStrong, null) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                onClick = {
                                    showPresetsMenu = false
                                    centerPanOnMedian(fitZoom = true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reanimar física") },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                onClick = {
                                    showPresetsMenu = false
                                    simController.reheat(1.0f)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (hideOrphans) "Mostrar órfãs" else "Ocultar órfãs") },
                                leadingIcon = {
                                    Icon(if (hideOrphans) Icons.Default.Check else Icons.Default.VisibilityOff, null)
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                onClick = {
                                    showPresetsMenu = false
                                    hideOrphans = !hideOrphans
                                    simController.reheat(0.85f)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (showGhostNodes) "Ocultar links fantasmas" else "Mostrar links fantasmas") },
                                leadingIcon = {
                                    Icon(if (showGhostNodes) Icons.Default.Check else Icons.Default.LinkOff, null)
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                onClick = {
                                    showPresetsMenu = false
                                    showGhostNodes = !showGhostNodes
                                    simController.reheat(0.85f)
                                }
                            )
                        }
                    }

                    // ≡ (Open Settings ModalBottomSheet directly)
                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Configurações do grafo",
                            tint = scheme.onSurface
                        )
                    }
                }
            }
        }
    }

    // Unified Settings ModalBottomSheet (Filtros | Grupos | Física)
    if (showSettingsSheet) {
        GraphSettingsModalSheet(
            activeTab = settingsTab,
            onTabChange = { settingsTab = it },
            query = query,
            onQueryChange = { query = it },
            localCenterId = localCenterId,
            focusedNoteId = focusedNoteId,
            selectedNote = selectedNote,
            localDepth = localDepth,
            onLocalDepthChange = { d ->
                localDepth = d
                pendingAutoFit = true
                simController.reheat(0.85f)
            },
            onToggleLocalMode = { targetId ->
                localCenterId = targetId
                pendingAutoFit = true
                simController.reheat(0.9f)
            },
            hideOrphans = hideOrphans,
            onHideOrphansChange = { hideOrphans = it; simController.reheat(0.85f) },
            showGhostNodes = showGhostNodes,
            totalGhostCount = totalGhostCount,
            onShowGhostNodesChange = { showGhostNodes = it; simController.reheat(0.85f) },
            showTagNodes = showTagNodes,
            onShowTagNodesChange = { showTagNodes = it; simController.reheat(0.9f) },
            showArrows = showArrows,
            onShowArrowsChange = { showArrows = it },
            alwaysShowLabels = alwaysShowLabels,
            onAlwaysShowLabelsChange = { alwaysShowLabels = it },
            allTags = allTags,
            selectedTag = selectedTag,
            onSelectTag = { tag ->
                selectedTag = if (selectedTag == tag) null else tag
                pendingAutoFit = true
                simController.reheat(0.85f)
            },
            allFolders = allFolders,
            selectedFolder = selectedFolder,
            onSelectFolder = { folder ->
                selectedFolder = if (selectedFolder == folder) null else folder
                pendingAutoFit = true
                simController.reheat(0.85f)
            },
            onClearFilters = {
                query = ""
                selectedTag = null
                selectedFolder = null
                hideOrphans = false
                pendingAutoFit = true
                simController.reheat(0.85f)
            },
            groupColors = groupColors,
            customRules = customRules,
            colorMode = colorMode,
            onColorModeChange = { colorMode = it },
            onUpdateGroupColor = ::updateGroupColor,
            onUpdateCustomRules = ::updateCustomRules,
            repulsionMultiplier = repulsionMultiplier,
            onRepulsionChange = { repulsionMultiplier = it; simController.reheat(0.9f) },
            springLengthMultiplier = springLengthMultiplier,
            onSpringLengthChange = { springLengthMultiplier = it; simController.reheat(0.9f) },
            gravityMultiplier = gravityMultiplier,
            onGravityChange = { gravityMultiplier = it; simController.reheat(0.9f) },
            nodeSizeMultiplier = nodeSizeMultiplier,
            onNodeSizeChange = { nodeSizeMultiplier = it },
            labelZoomThreshold = labelZoomThreshold,
            onLabelZoomThresholdChange = { labelZoomThreshold = it },
            onReheat = { simController.reheat(1.0f) },
            onResetPhysics = {
                repulsionMultiplier = 1.0f
                springLengthMultiplier = 1.0f
                gravityMultiplier = 1.0f
                nodeSizeMultiplier = 1.0f
                labelZoomThreshold = 1.05f
                simController.reheat(1.0f)
            },
            onDismiss = { showSettingsSheet = false }
        )
    }

    // Fallback quick create note sheet when only onCreateMissingNote was provided
    if (showQuickCreateNoteSheet && onCreateMissingNote != null) {
        QuickCreateGraphNoteSheet(
            onCreate = { newTitle ->
                showQuickCreateNoteSheet = false
                onCreateMissingNote(newTitle)
            },
            onDismiss = { showQuickCreateNoteSheet = false }
        )
    }
}

@Composable
private fun GraphFloatingCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) scheme.primaryContainer.copy(alpha = 0.94f)
        else scheme.surfaceContainerHigh.copy(alpha = 0.88f),
        contentColor = if (active) scheme.onPrimaryContainer else scheme.onSurface,
        tonalElevation = 4.dp,
        border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.42f)),
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun GraphCanvasViewport(
    graphNodes: List<GraphNode>,
    renderMeta: GraphRenderMeta,
    drawOrder: IntArray,
    drawCache: GraphDrawCache,
    renderSnapshotState: State<GraphRenderSnapshot>,
    simController: GraphSimController,
    scaleProvider: () -> Float,
    panProvider: () -> Offset,
    onScalePanChange: (Float, Offset) -> Unit,
    selectedId: Long?,
    draggedNodeId: Long?,
    dragVisualPos: Offset?,
    localCenterId: Long?,
    selectedConnectedIds: Set<Long>,
    searchMatchIds: Set<Long>,
    searchNeighborIds: Set<Long>,
    isSearching: Boolean,
    showArrows: Boolean,
    alwaysShowLabels: Boolean,
    nodeSizeMultiplier: Float,
    labelZoomThreshold: Float,
    graphTextColor: Color,
    graphMutedColor: Color,
    graphAccentColor: Color,
    graphSurfaceColor: Color,
    onDragStart: (Long, Offset) -> Unit,
    onDragMove: (Long, Offset) -> Unit,
    onDragEnd: (Long?, Offset?) -> Unit,
    onNodeTap: (GraphNode) -> Unit,
    onEmptyTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentNodes by rememberUpdatedState(graphNodes)
    val currentScaleProvider by rememberUpdatedState(scaleProvider)
    val currentPanProvider by rememberUpdatedState(panProvider)
    val currentOnScalePanChange by rememberUpdatedState(onScalePanChange)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnNodeTap by rememberUpdatedState(onNodeTap)
    val currentOnEmptyTap by rememberUpdatedState(onEmptyTap)
    val density = LocalDensity.current
    val latestTouchSlop by rememberUpdatedState(LocalViewConfiguration.current.touchSlop)

    val starArgb = remember(graphMutedColor) { withAlphaArgb(graphMutedColor.toArgb(), 0.11f) }
    val mutedArgb = remember(graphMutedColor) { graphMutedColor.toArgb() }
    val accentArgb = remember(graphAccentColor) { graphAccentColor.toArgb() }
    val textArgb = remember(graphTextColor) { graphTextColor.toArgb() }
    val pillBgArgb = remember(graphSurfaceColor) { withAlphaArgb(graphSurfaceColor.toArgb(), 0.92f) }

    val ringStrokePx = with(density) { 2.2.dp.toPx() }
    val ghostStrokePx = with(density) { 1.7.dp.toPx() }
    val labelGapPx = with(density) { 4.dp.toPx() }
    val pillPadHPx = with(density) { 6.dp.toPx() }
    val pillPadVPx = with(density) { 2.5.dp.toPx() }
    val pillRadiusPx = with(density) { 6.dp.toPx() }
    val cornerRadiusPx = with(density) { 3.dp.toPx() }

    Canvas(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val canvasW = size.width.toFloat()
                    val canvasH = size.height.toFloat()
                    val vCenter = Offset(canvasW / 2f, canvasH / 2f)
                    val curScale = currentScaleProvider()
                    val curPan = currentPanProvider()
                    val worldDown = screenToWorld(down.position, curScale, curPan, vCenter)

                    val snap = renderSnapshotState.value
                    val hitRadiusWorld = (42f / curScale.coerceAtLeast(0.35f)).coerceIn(22f, 95f)
                    var bestNode: GraphNode? = null
                    var bestDist = Float.MAX_VALUE
                    val nodesSnapshot = currentNodes
                    for (n in nodesSnapshot) {
                        val idx = snap.idToIndex[n.id] ?: continue
                        if (idx !in snap.xs.indices) continue
                        val dx = snap.xs[idx] - worldDown.x
                        val dy = snap.ys[idx] - worldDown.y
                        val d = hypot(dx, dy)
                        if (d <= hitRadiusWorld && d < bestDist) {
                            bestDist = d
                            bestNode = n
                        }
                    }

                    var totalMoved = 0f
                    var wasMultiTouch = false
                    var activeDragId: Long? = bestNode?.id
                    var lastDragWorld: Offset? = if (activeDragId != null) {
                        val idx = snap.idToIndex[activeDragId]
                        if (idx != null && idx in snap.xs.indices) Offset(snap.xs[idx], snap.ys[idx]) else worldDown
                    } else null

                    if (activeDragId != null && lastDragWorld != null) {
                        currentOnDragStart(activeDragId, lastDragWorld)
                    }

                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }

                            if (pressedCount >= 2) {
                                wasMultiTouch = true
                                if (activeDragId != null) {
                                    currentOnDragEnd(activeDragId, lastDragWorld)
                                    activeDragId = null
                                }
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val oldScale = currentScaleProvider()
                                    val oldPan = currentPanProvider()
                                    val newScale = (oldScale * zoomChange).coerceIn(0.22f, 4.0f)
                                    val newPan = if (centroid != Offset.Unspecified) {
                                        val worldAtCentroid = screenToWorld(centroid, oldScale, oldPan, vCenter)
                                        centroid + panChange - vCenter - (worldAtCentroid * newScale)
                                    } else {
                                        oldPan + panChange
                                    }
                                    currentOnScalePanChange(newScale, newPan)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (pressedCount == 1 && !wasMultiTouch) {
                                val change = event.changes.firstOrNull { it.pressed } ?: continue
                                val delta = change.position - change.previousPosition
                                totalMoved += delta.getDistance()

                                if (activeDragId != null) {
                                    val worldPos = screenToWorld(change.position, currentScaleProvider(), currentPanProvider(), vCenter)
                                    lastDragWorld = worldPos
                                    currentOnDragMove(activeDragId, worldPos)
                                    if (change.positionChanged()) change.consume()
                                } else if (delta != Offset.Zero) {
                                    val s = currentScaleProvider()
                                    val p = currentPanProvider()
                                    val maxPan = max(4800f, WORLD_NOMINAL_HALF * s * 2f)
                                    val nextPan = Offset(
                                        (p.x + delta.x).coerceIn(-maxPan, maxPan),
                                        (p.y + delta.y).coerceIn(-maxPan, maxPan)
                                    )
                                    currentOnScalePanChange(s, nextPan)
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        if (activeDragId != null) {
                            currentOnDragEnd(activeDragId, lastDragWorld)
                        } else {
                            currentOnDragEnd(null, null)
                        }
                    }

                    if (!wasMultiTouch && totalMoved < latestTouchSlop) {
                        if (bestNode != null) {
                            currentOnNodeTap(bestNode)
                        } else {
                            currentOnEmptyTap()
                        }
                    }
                }
            }
    ) {
        val snap = renderSnapshotState.value
        val curScale = scaleProvider()
        val curPan = panProvider()
        val vCenterX = size.width / 2f
        val vCenterY = size.height / 2f
        val zoomRadiusScale = curScale.coerceIn(0.42f, 2.2f)

        val nCount = graphNodes.size
        drawCache.ensureCapacity(nCount)

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas

            // 1. Subtle screen-space constellation dots (zero allocations)
            drawCache.starPaint.color = starArgb
            val gridStep = (84f * curScale.coerceIn(0.6f, 1.4f)).coerceAtLeast(42f)
            val startGX = (curPan.x * 0.5f) % gridStep
            val startGY = (curPan.y * 0.5f) % gridStep
            var gx = startGX - gridStep
            while (gx < size.width + gridStep) {
                var gy = startGY - gridStep
                while (gy < size.height + gridStep) {
                    if (gx >= 0f && gy >= 0f) {
                        native.drawCircle(gx, gy, 1.5f, drawCache.starPaint)
                    }
                    gy += gridStep
                }
                gx += gridStep
            }

            if (nCount == 0) return@drawIntoCanvas

            // 2. Project world -> screen into cached FloatArrays
            val screenXs = drawCache.screenXs
            val screenYs = drawCache.screenYs
            val screenRadii = drawCache.screenRadii

            for (i in 0 until nCount) {
                val node = graphNodes[i]
                val wx: Float
                val wy: Float
                if (node.id == draggedNodeId && dragVisualPos != null) {
                    wx = dragVisualPos.x
                    wy = dragVisualPos.y
                } else {
                    val snapIdx = snap.idToIndex[node.id]
                    if (snapIdx != null && snapIdx in snap.xs.indices) {
                        wx = snap.xs[snapIdx]
                        wy = snap.ys[snapIdx]
                    } else {
                        screenXs[i] = Float.NaN
                        screenYs[i] = Float.NaN
                        screenRadii[i] = 0f
                        continue
                    }
                }
                screenXs[i] = wx * curScale + curPan.x + vCenterX
                screenYs[i] = wy * curScale + curPan.y + vCenterY
                val baseR = (10f + min(node.degree, 10) * 1.5f) * nodeSizeMultiplier
                val chosenMult = if (node.id == selectedId || node.id == draggedNodeId) 1.28f else 1f
                screenRadii[i] = (baseR * zoomRadiusScale * chosenMult).coerceIn(5.5f, 44f)
            }

            // 3. Draw Edges (zero allocations)
            val hasSelection = selectedId != null || draggedNodeId != null
            val focusId = draggedNodeId ?: selectedId
            val activeEdgePaint = drawCache.activeEdgePaint
            activeEdgePaint.color = accentArgb
            val normalEdgePaint = drawCache.normalEdgePaint
            val ghostEdgePaint = drawCache.ghostEdgePaint
            val arrowPaint = drawCache.arrowPaint
            val arrowPath = drawCache.arrowPath

            val edgeCount = renderMeta.validEdgeCount
            for (e in 0 until edgeCount) {
                val sIdx = renderMeta.edgeSrcIdx[e]
                val tIdx = renderMeta.edgeDstIdx[e]
                if (sIdx >= nCount || tIdx >= nCount) continue
                val ax = screenXs[sIdx]
                val ay = screenYs[sIdx]
                val bx = screenXs[tIdx]
                val by = screenYs[tIdx]
                if (ax.isNaN() || bx.isNaN()) continue

                if ((ax < -80f && bx < -80f) ||
                    (ax > size.width + 80f && bx > size.width + 80f) ||
                    (ay < -80f && by < -80f) ||
                    (ay > size.height + 80f && by > size.height + 80f)
                ) continue

                val srcId = graphNodes[sIdx].id
                val dstId = graphNodes[tIdx].id
                val isFocusEdge = focusId != null && (srcId == focusId || dstId == focusId)
                val isSearchEdge = isSearching && (srcId in searchMatchIds || dstId in searchMatchIds)
                val isDimmed = (isSearching && !isSearchEdge && !isFocusEdge) ||
                    (hasSelection && !isFocusEdge && !isSearchEdge)
                val isGhostEdge = renderMeta.edgeIsGhost[e]

                val paint = when {
                    isFocusEdge || isSearchEdge -> activeEdgePaint
                    isGhostEdge -> {
                        ghostEdgePaint.color = withAlphaArgb(mutedArgb, if (isDimmed) 0.12f else 0.34f)
                        ghostEdgePaint
                    }
                    else -> {
                        normalEdgePaint.color = withAlphaArgb(mutedArgb, if (isDimmed) 0.12f else 0.36f)
                        normalEdgePaint
                    }
                }
                native.drawLine(ax, ay, bx, by, paint)

                if (showArrows) {
                    val dx = bx - ax
                    val dy = by - ay
                    val dist = hypot(dx, dy)
                    val targetR = screenRadii[tIdx]
                    if (dist > targetR + 20f) {
                        val ux = dx / dist
                        val uy = dy / dist
                        val perpX = -uy
                        val perpY = ux
                        val tipX = bx - ux * (targetR + 3f)
                        val tipY = by - uy * (targetR + 3f)
                        val arrowLen = min(10f, dist * 0.25f)
                        val arrowHalfW = 4.6f
                        val baseLeftX = tipX - ux * arrowLen + perpX * arrowHalfW
                        val baseLeftY = tipY - uy * arrowLen + perpY * arrowHalfW
                        val baseRightX = tipX - ux * arrowLen - perpX * arrowHalfW
                        val baseRightY = tipY - uy * arrowLen - perpY * arrowHalfW

                        arrowPaint.color = when {
                            isFocusEdge || isSearchEdge -> accentArgb
                            isDimmed -> withAlphaArgb(mutedArgb, 0.14f)
                            else -> withAlphaArgb(mutedArgb, 0.48f)
                        }
                        arrowPath.rewind()
                        arrowPath.moveTo(tipX, tipY)
                        arrowPath.lineTo(baseLeftX, baseLeftY)
                        arrowPath.lineTo(baseRightX, baseRightY)
                        arrowPath.close()
                        native.drawPath(arrowPath, arrowPaint)
                    }
                }
            }

            // 4. Draw Nodes & Labels (zero allocations)
            val glowPaint = drawCache.glowPaint
            val nodePaint = drawCache.nodePaint
            val ghostFillPaint = drawCache.ghostFillPaint
            val ghostStrokePaint = drawCache.ghostStrokePaint
            ghostStrokePaint.strokeWidth = ghostStrokePx
            val ringPaint = drawCache.ringPaint
            ringPaint.strokeWidth = ringStrokePx
            val labelPaint = drawCache.labelPaint
            val labelMetrics = drawCache.labelMetrics
            val pillPaint = drawCache.pillPaint
            pillPaint.color = pillBgArgb

            for (idx in drawOrder) {
                if (idx >= nCount) continue
                val sx = screenXs[idx]
                val sy = screenYs[idx]
                if (sx.isNaN()) continue
                val drawRadius = screenRadii[idx]
                if (sx < -160f || sx > size.width + 160f || sy < -160f || sy > size.height + 160f) {
                    continue
                }

                val node = graphNodes[idx]
                val nodeId = node.id
                val isChosen = nodeId == selectedId || nodeId == draggedNodeId
                val isConnected = nodeId in selectedConnectedIds
                val isSearchMatch = nodeId in searchMatchIds
                val isSearchNeighbor = nodeId in searchNeighborIds
                val isDimmed = isSearching && !isSearchMatch && !isSearchNeighbor && !isChosen

                val nodeColorArgb = renderMeta.nodeColorsArgb[idx]
                val nodeAlpha = if (isDimmed) 0.18f else 1f

                if (!isDimmed && (isChosen || isConnected || isSearchMatch || nodeId == localCenterId)) {
                    val glowBase = if (isSearchMatch) accentArgb else nodeColorArgb
                    val glowAlpha = if (isChosen || isSearchMatch) 0.36f else 0.18f
                    glowPaint.color = withAlphaArgb(glowBase, glowAlpha)
                    val glowMult = if (isChosen || isSearchMatch) 2.2f else 1.75f
                    native.drawCircle(sx, sy, drawRadius * glowMult, glowPaint)
                }

                when {
                    node.isGhost -> {
                        ghostFillPaint.color = withAlphaArgb(nodeColorArgb, 0.22f * nodeAlpha)
                        ghostStrokePaint.color = withAlphaArgb(nodeColorArgb, 0.85f * nodeAlpha)
                        native.drawCircle(sx, sy, drawRadius, ghostFillPaint)
                        native.drawCircle(sx, sy, drawRadius, ghostStrokePaint)
                        if (isChosen || isSearchMatch) {
                            ringPaint.color = accentArgb
                            native.drawCircle(sx, sy, drawRadius + ringStrokePx, ringPaint)
                        }
                    }
                    node.isTagNode -> {
                        nodePaint.color = withAlphaArgb(nodeColorArgb, nodeAlpha)
                        native.save()
                        native.rotate(45f, sx, sy)
                        val half = drawRadius * 0.85f
                        native.drawRoundRect(
                            sx - half, sy - half, sx + half, sy + half,
                            cornerRadiusPx, cornerRadiusPx, nodePaint
                        )
                        if (isChosen || isSearchMatch) {
                            ringPaint.color = textArgb
                            native.drawRoundRect(
                                sx - half, sy - half, sx + half, sy + half,
                                cornerRadiusPx, cornerRadiusPx, ringPaint
                            )
                        }
                        native.restore()
                    }
                    else -> {
                        nodePaint.color = withAlphaArgb(nodeColorArgb, nodeAlpha)
                        native.drawCircle(sx, sy, drawRadius, nodePaint)
                        if (isChosen || isSearchMatch || nodeId == localCenterId) {
                            ringPaint.color = if (isSearchMatch && !isChosen) accentArgb else renderMeta.nodeRingColorsArgb[idx]
                            native.drawCircle(sx, sy, drawRadius, ringPaint)
                        }
                    }
                }

                val showLabel = alwaysShowLabels || isChosen || isConnected || isSearchMatch || isSearchNeighbor ||
                    nodeId == localCenterId || curScale >= labelZoomThreshold || node.degree >= 2 || node.isTagNode ||
                    (node.isGhost && curScale >= (labelZoomThreshold * 0.7f))

                if (showLabel && !isDimmed) {
                    val label = renderMeta.nodeLabels[idx]
                    val textW = renderMeta.nodeLabelWidths[idx]
                    labelPaint.color = if (node.isGhost) {
                        withAlphaArgb(mutedArgb, 0.88f)
                    } else {
                        withAlphaArgb(textArgb, if (isChosen || isSearchMatch) 1f else 0.90f)
                    }
                    val labelX = sx - textW / 2f
                    val baseline = sy + drawRadius + labelGapPx - labelMetrics.ascent

                    if (isChosen || isSearchMatch) {
                        native.drawRoundRect(
                            labelX - pillPadHPx,
                            baseline + labelMetrics.ascent - pillPadVPx,
                            labelX + textW + pillPadHPx,
                            baseline + labelMetrics.descent + pillPadVPx,
                            pillRadiusPx,
                            pillRadiusPx,
                            pillPaint
                        )
                    }
                    native.drawText(label, labelX, baseline, labelPaint)
                }
            }
        }
    }
}

@Composable
private fun GraphSettingsModalSheet(
    activeTab: GraphSettingsTab,
    onTabChange: (GraphSettingsTab) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    localCenterId: Long?,
    focusedNoteId: Long?,
    selectedNote: Note?,
    localDepth: Int,
    onLocalDepthChange: (Int) -> Unit,
    onToggleLocalMode: (Long?) -> Unit,
    hideOrphans: Boolean,
    onHideOrphansChange: (Boolean) -> Unit,
    showGhostNodes: Boolean,
    totalGhostCount: Int,
    onShowGhostNodesChange: (Boolean) -> Unit,
    showTagNodes: Boolean,
    onShowTagNodesChange: (Boolean) -> Unit,
    showArrows: Boolean,
    onShowArrowsChange: (Boolean) -> Unit,
    alwaysShowLabels: Boolean,
    onAlwaysShowLabelsChange: (Boolean) -> Unit,
    allTags: List<String>,
    selectedTag: String?,
    onSelectTag: (String) -> Unit,
    allFolders: List<String>,
    selectedFolder: String?,
    onSelectFolder: (String) -> Unit,
    onClearFilters: () -> Unit,
    groupColors: Map<String, Int>,
    customRules: List<GraphColorRule>,
    colorMode: Int,
    onColorModeChange: (Int) -> Unit,
    onUpdateGroupColor: (String, Int) -> Unit,
    onUpdateCustomRules: (List<GraphColorRule>) -> Unit,
    repulsionMultiplier: Float,
    onRepulsionChange: (Float) -> Unit,
    springLengthMultiplier: Float,
    onSpringLengthChange: (Float) -> Unit,
    gravityMultiplier: Float,
    onGravityChange: (Float) -> Unit,
    nodeSizeMultiplier: Float,
    onNodeSizeChange: (Float) -> Unit,
    labelZoomThreshold: Float,
    onLabelZoomThresholdChange: (Float) -> Unit,
    onReheat: () -> Unit,
    onResetPhysics: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Configurações do Grafo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, "Fechar configurações")
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val tabs = GraphSettingsTab.entries
                tabs.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = activeTab == tab,
                        onClick = { onTabChange(tab) },
                        shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (activeTab) {
                    GraphSettingsTab.FILTERS -> {
                        StudyLocalSearchField(
                            query = query,
                            onQueryChange = onQueryChange,
                            placeholder = "Buscar nota, #tag, path:pasta…",
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (localCenterId != null || selectedNote != null) {
                            Surface(
                                shape = studyControlShape(),
                                color = scheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        if (localCenterId != null) "Profundidade do Grafo Local" else "Grafo Local",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (localCenterId != null) {
                                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                            (1..3).forEachIndexed { i, hop ->
                                                SegmentedButton(
                                                    selected = localDepth == hop,
                                                    onClick = { onLocalDepthChange(hop) },
                                                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                                ) {
                                                    Text("$hop ${if (hop == 1) "salto" else "saltos"}", maxLines = 1)
                                                }
                                            }
                                        }
                                        if (focusedNoteId == null) {
                                            OutlinedButton(
                                                onClick = { onToggleLocalMode(null) },
                                                shape = studyControlShape(),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                            ) {
                                                Icon(Icons.Default.Public, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Voltar para o grafo global")
                                            }
                                        }
                                    } else if (selectedNote != null) {
                                        FilledTonalButton(
                                            onClick = { onToggleLocalMode(selectedNote.id) },
                                            shape = studyControlShape(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Icon(Icons.Default.Hub, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Focar em \"${selectedNote.title.take(20)}\"")
                                        }
                                    }
                                }
                            }
                        }

                        GraphToggleRow("Ocultar notas isoladas (órfãs)", hideOrphans, onHideOrphansChange)
                        GraphToggleRow(
                            if (totalGhostCount > 0) "Links não criados ($totalGhostCount)" else "Mostrar links não criados",
                            showGhostNodes,
                            onShowGhostNodesChange
                        )
                        GraphToggleRow("Mostrar #tags como nós no grafo", showTagNodes, onShowTagNodesChange)
                        GraphToggleRow("Mostrar setas de direção (A → B)", showArrows, onShowArrowsChange)
                        GraphToggleRow("Exibir todos os títulos sempre", alwaysShowLabels, onAlwaysShowLabelsChange)

                        if (allTags.isNotEmpty()) {
                            Text("Filtrar por #tag", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                allTags.forEach { tag ->
                                    val tagColor = Color(
                                        lookupHierarchicalColor(groupColors, "tag", tag)
                                            ?: defaultColorForKey("tag:${tag.lowercase(Locale.ROOT)}")
                                    )
                                    FilterChip(
                                        selected = selectedTag == tag,
                                        onClick = { onSelectTag(tag) },
                                        leadingIcon = {
                                            Box(Modifier.size(10.dp).clip(CircleShape).background(tagColor))
                                        },
                                        label = { Text("#$tag") },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                    )
                                }
                            }
                        }

                        if (allFolders.isNotEmpty()) {
                            Text("Filtrar por pasta", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                allFolders.forEach { folder ->
                                    val folderColor = Color(
                                        lookupHierarchicalColor(groupColors, "folder", folder)
                                            ?: defaultColorForKey("folder:${folder.lowercase(Locale.ROOT)}")
                                    )
                                    FilterChip(
                                        selected = selectedFolder == folder,
                                        onClick = { onSelectFolder(folder) },
                                        leadingIcon = {
                                            Box(Modifier.size(10.dp).clip(CircleShape).background(folderColor))
                                        },
                                        label = { Text("📁 $folder") },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                    )
                                }
                            }
                        }

                        if (query.isNotBlank() || selectedTag != null || selectedFolder != null || hideOrphans) {
                            OutlinedButton(
                                onClick = onClearFilters,
                                shape = studyControlShape(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Limpar filtros ativos")
                            }
                        }
                    }

                    GraphSettingsTab.GROUPS -> {
                        GraphGroupsTabContent(
                            allTags = allTags,
                            allFolders = allFolders,
                            groupColors = groupColors,
                            customRules = customRules,
                            colorMode = colorMode,
                            onColorModeChange = onColorModeChange,
                            onUpdateGroupColor = onUpdateGroupColor,
                            onUpdateCustomRules = onUpdateCustomRules
                        )
                    }

                    GraphSettingsTab.PHYSICS -> {
                        GraphPhysicsSlider(
                            label = "Força de repulsão (${String.format(Locale.getDefault(), "%.1fx", repulsionMultiplier)})",
                            value = repulsionMultiplier,
                            range = 0.4f..2.4f,
                            onValueChange = onRepulsionChange
                        )
                        GraphPhysicsSlider(
                            label = "Distância das conexões (${String.format(Locale.getDefault(), "%.1fx", springLengthMultiplier)})",
                            value = springLengthMultiplier,
                            range = 0.5f..2.2f,
                            onValueChange = onSpringLengthChange
                        )
                        GraphPhysicsSlider(
                            label = "Gravidade central (${String.format(Locale.getDefault(), "%.1fx", gravityMultiplier)})",
                            value = gravityMultiplier,
                            range = 0.2f..2.5f,
                            onValueChange = onGravityChange
                        )
                        GraphPhysicsSlider(
                            label = "Tamanho dos nós (${String.format(Locale.getDefault(), "%.1fx", nodeSizeMultiplier)})",
                            value = nodeSizeMultiplier,
                            range = 0.6f..1.8f,
                            onValueChange = onNodeSizeChange
                        )
                        GraphPhysicsSlider(
                            label = "Limiar de rótulos (${String.format(Locale.getDefault(), "%.2fx zoom", labelZoomThreshold)})",
                            value = labelZoomThreshold,
                            range = 0.4f..1.8f,
                            onValueChange = onLabelZoomThresholdChange
                        )

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onResetPhysics,
                                shape = studyControlShape(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Restaurar padrão", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Button(
                                onClick = onReheat,
                                shape = studyControlShape(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Reanimar", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphPhysicsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

@Composable
private fun GraphToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(studyControlShape())
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GraphGroupsTabContent(
    allTags: List<String>,
    allFolders: List<String>,
    groupColors: Map<String, Int>,
    customRules: List<GraphColorRule>,
    colorMode: Int,
    onColorModeChange: (Int) -> Unit,
    onUpdateGroupColor: (String, Int) -> Unit,
    onUpdateCustomRules: (List<GraphColorRule>) -> Unit
) {
    var editingGroupKey by remember { mutableStateOf<String?>(null) }
    var newRuleQuery by remember { mutableStateOf("") }
    var newRuleColor by remember { mutableIntStateOf(ObsidianPalette[0]) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Agrupar cores automaticamente por:", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = colorMode == 0,
                onClick = { onColorModeChange(0) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) { Text("Por #Tag", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            SegmentedButton(
                selected = colorMode == 1,
                onClick = { onColorModeChange(1) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) { Text("Por Pasta", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }

        editingGroupKey?.let { key ->
            Surface(
                shape = studyControlShape(),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Escolha a cor para ${key.removePrefix("tag:").removePrefix("folder:")}:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    PaletteSwatchesFlow(
                        selectedArgb = groupColors[key] ?: defaultColorForKey(key),
                        onSelect = { argb ->
                            onUpdateGroupColor(key, argb)
                            editingGroupKey = null
                        }
                    )
                }
            }
        }

        if (allTags.isNotEmpty()) {
            Text("Cores das #Tags", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            allTags.forEach { tag ->
                val key = "tag:${tag.lowercase(Locale.ROOT)}"
                val currentArgb = lookupHierarchicalColor(groupColors, "tag", tag) ?: defaultColorForKey(key)
                ColorGroupItemRow(
                    label = "#$tag",
                    colorArgb = currentArgb,
                    onClick = { editingGroupKey = if (editingGroupKey == key) null else key }
                )
            }
        }

        if (allFolders.isNotEmpty()) {
            Text("Cores das Pastas", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            allFolders.forEach { folder ->
                val key = "folder:${folder.lowercase(Locale.ROOT)}"
                val currentArgb = lookupHierarchicalColor(groupColors, "folder", folder) ?: defaultColorForKey(key)
                ColorGroupItemRow(
                    label = "📁 $folder",
                    colorArgb = currentArgb,
                    onClick = { editingGroupKey = if (editingGroupKey == key) null else key }
                )
            }
        }

        HorizontalDivider()

        Text(
            "Regras hierárquicas (#subtag, path:Pasta ou texto)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        customRules.forEach { rule ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(Color(rule.colorArgb)))
                    Text(
                        rule.query,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { onUpdateCustomRules(customRules.filterNot { it.id == rule.id }) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, "Remover regra", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        OutlinedTextField(
            value = newRuleQuery,
            onValueChange = { newRuleQuery = it },
            label = { Text("Filtro (#subtag, path:Pasta ou texto)") },
            singleLine = true,
            shape = studyControlShape(),
            modifier = Modifier.fillMaxWidth()
        )
        PaletteSwatchesFlow(
            selectedArgb = newRuleColor,
            onSelect = { newRuleColor = it }
        )
        Button(
            onClick = {
                val q = newRuleQuery.trim()
                if (q.isNotEmpty()) {
                    val added = GraphColorRule(
                        id = System.currentTimeMillis().toString(),
                        query = q,
                        label = q,
                        colorArgb = newRuleColor
                    )
                    onUpdateCustomRules(customRules + added)
                    newRuleQuery = ""
                }
            },
            enabled = newRuleQuery.isNotBlank(),
            shape = studyControlShape(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Adicionar Regra de Cor")
        }
    }
}

@Composable
internal fun PaletteSwatchesFlow(
    selectedArgb: Int?,
    onSelect: (Int) -> Unit,
    colors: List<Int> = ObsidianPalette
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        colors.forEach { argb ->
            val selected = selectedArgb == argb
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(argb) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (selected) 3.dp else 0.dp,
                            color = onSurface,
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun ColorGroupItemRow(label: String, colorArgb: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(studyControlShape())
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(Color(colorArgb)))
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Trocar cor", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun QuickCreateGraphNoteSheet(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var title by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { focusRequester.requestFocus() }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Nova Nota", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Título da nota") },
                singleLine = true,
                shape = studyControlShape(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
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
                    onClick = { onCreate(title.trim()) },
                    enabled = title.isNotBlank(),
                    shape = studyControlShape(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Criar") }
            }
        }
    }
}

private fun defaultColorForKey(key: String): Int {
    val idx = (key.hashCode() and Int.MAX_VALUE) % ObsidianPalette.size
    return ObsidianPalette[idx]
}

private fun loadGroupColors(prefs: SharedPreferences): Map<String, Int> {
    val raw = prefs.getString("group_colors_json", null)
        ?: prefs.getString("color_groups_json", null)
        ?: return emptyMap()
    return try {
        val obj = JSONObject(raw)
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, obj.getInt(k))
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun saveGroupColors(prefs: SharedPreferences, map: Map<String, Int>) {
    val obj = JSONObject()
    map.forEach { (k, v) -> obj.put(k, v) }
    prefs.edit()
        .putString("group_colors_json", obj.toString())
        .remove("color_groups_json")
        .apply()
}

private fun loadCustomRules(prefs: SharedPreferences): List<GraphColorRule> {
    val raw = prefs.getString("custom_rules_json", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                add(
                    GraphColorRule(
                        id = item.optString("id", i.toString()),
                        query = item.optString("query", ""),
                        label = item.optString("label", ""),
                        colorArgb = item.optInt("colorArgb", ObsidianPalette[0])
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveCustomRules(prefs: SharedPreferences, rules: List<GraphColorRule>) {
    val arr = JSONArray()
    rules.forEach { r ->
        arr.put(
            JSONObject().apply {
                put("id", r.id)
                put("query", r.query)
                put("label", r.label)
                put("colorArgb", r.colorArgb)
            }
        )
    }
    prefs.edit().putString("custom_rules_json", arr.toString()).apply()
}

/**
 * Full-bleed modal dialog showing a focused Local Graph around a specific note (1..3 hops)
 * using the single unified KnowledgeGraph header and hop selector.
 */
@Composable
fun LocalGraphDialog(
    note: Note,
    allNotes: List<Note>,
    links: List<NoteLink>,
    onOpen: (Long) -> Unit,
    onDismiss: () -> Unit,
    onCreateMissingNote: ((String) -> Unit)? = null,
    onCreateNote: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            KnowledgeGraph(
                notes = allNotes,
                bindings = links,
                onOpen = { targetId ->
                    onDismiss()
                    onOpen(targetId)
                },
                modifier = Modifier.fillMaxSize(),
                initialSelectedId = note.id,
                focusedNoteId = note.id,
                onCreateMissingNote = onCreateMissingNote?.let { creator ->
                    { missingTitle ->
                        onDismiss()
                        creator(missingTitle)
                    }
                },
                onBack = onDismiss,
                onCreateNote = onCreateNote?.let { creator ->
                    {
                        onDismiss()
                        creator()
                    }
                },
                showHeader = true,
                headerTitle = "Grafo Local"
            )
        }
    }
}

// ============================================================================
// OBSIDIAN VISUAL CANVAS WORKSPACE (.canvas style infinite whiteboard)
// ============================================================================

enum class CanvasNodeType(val wire: String) {
    TEXT("text"),
    NOTE("note"),
    GROUP("group");

    companion object {
        fun fromWire(raw: String): CanvasNodeType = when (raw.lowercase(Locale.ROOT)) {
            "note", "file" -> NOTE
            "group" -> GROUP
            else -> TEXT
        }
    }
}

object UniversalDomainGraph {
    fun buildTopicClusterSummary(
        topic: String,
        noteCount: Int,
        cardCount: Int,
        bookCount: Int,
        eloRating: Int
    ): String = "$topic · $noteCount notas · $cardCount cards · $bookCount livros · ELO $eloRating"
}
