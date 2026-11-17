@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.studydock.app.ui

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.OcclusionBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_PICTURE_DECODE_DIM_PX = 2048

private val OCCLUSION_TARGET_FILL = Color(0xFFE65100)
private val OCCLUSION_TARGET_BORDER = Color(0xFFFFD54F)
private val OCCLUSION_NEUTRAL_FILL = Color(0xFF334155)
private val OCCLUSION_NEUTRAL_BORDER = Color(0xFF64748B)
private val OCCLUSION_REVEALED_BORDER = Color(0xFF10B981)
private val OCCLUSION_DASH_EFFECT = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
private val OCCLUSION_BOLD_TYPEFACE: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

private val occlusionBadgePaintLocal = object : ThreadLocal<Paint>() {
    override fun initialValue(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = OCCLUSION_BOLD_TYPEFACE
    }
}

private val answerPictureCacheKb: Int =
    ((Runtime.getRuntime().maxMemory() / 1024L) / 8L).toInt().coerceIn(8 * 1024, 32 * 1024)

private val answerPictureCache = object : LruCache<String, ImageBitmap>(answerPictureCacheKb) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        ((value.width.toLong() * value.height.toLong() * 4L) / 1024L).coerceAtLeast(1L).toInt()
}

private fun answerPictureCacheKey(identity: Any): String = when (identity) {
    is List<*> -> identity.joinToString(separator = "|", prefix = "list:") { it?.toString() ?: "<null>" }
    else -> "${identity::class.java.name}:$identity"
}

private fun decodeSampledImageBitmap(bytes: ByteArray, maxDimPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sampleSize = 1
    val rawW = bounds.outWidth
    val rawH = bounds.outHeight
    if (rawW > maxDimPx || rawH > maxDimPx) {
        val halfW = rawW / 2
        val halfH = rawH / 2
        while ((halfW / sampleSize) >= maxDimPx || (halfH / sampleSize) >= maxDimPx) {
            sampleSize *= 2
        }
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/** Extracts numeric box ID from keys such as `c1`, `occ2`, or `3`. */
internal fun parseTargetOcclusionBoxId(variantKey: String?): Int? {
    val clean = variantKey?.trim()?.lowercase()?.ifEmpty { null } ?: return null
    val digits = when {
        clean.startsWith("occ") -> clean.removePrefix("occ")
        clean.startsWith("c") -> clean.removePrefix("c")
        else -> clean
    }
    return digits.toIntOrNull()?.takeIf { it > 0 }
}

/**
 * Draws Anki Image Occlusion overlays over [drawRegionOffset]..[drawRegionSize]:
 * - Before reveal: masks all boxes (when [hideAll] is true) or only [targetBoxId] (when false),
 *   highlighting the target box in warm Anki amber/red without exposing any answer label.
 * - After reveal: clears [targetBoxId] with a crisp dashed emerald outline while keeping sibling
 *   boxes opaquely masked whenever [hideAll] is true.
 */
internal fun DrawScope.showAllMaskedOverlay(
    boxes: List<OcclusionBox>,
    targetBoxId: Int?,
    revealed: Boolean,
    hideAll: Boolean = boxes.firstOrNull()?.hideAll ?: true,
    drawRegionOffset: Offset = Offset.Zero,
    drawRegionSize: Size = size
) {
    if (boxes.isEmpty() || drawRegionSize.width <= 0f || drawRegionSize.height <= 0f) return
    val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
    val badgePaint = requireNotNull(occlusionBadgePaintLocal.get())

    boxes.forEachIndexed { index, box ->
        val resolvedId = box.id.takeIf { it > 0 } ?: (index + 1)
        val isTarget = targetBoxId != null && targetBoxId == resolvedId
        val l = drawRegionOffset.x + box.left.coerceIn(0f, 1f) * drawRegionSize.width
        val t = drawRegionOffset.y + box.top.coerceIn(0f, 1f) * drawRegionSize.height
        val r = drawRegionOffset.x + box.right.coerceIn(box.left.coerceIn(0f, 1f), 1f) * drawRegionSize.width
        val b = drawRegionOffset.y + box.bottom.coerceIn(box.top.coerceIn(0f, 1f), 1f) * drawRegionSize.height
        val boxSize = Size((r - l).coerceAtLeast(4f), (b - t).coerceAtLeast(4f))
        val topLeft = Offset(l, t)

        if (!revealed) {
            val shouldMask = isTarget || hideAll || targetBoxId == null
            if (!shouldMask) return@forEachIndexed
            drawRoundRect(
                color = if (isTarget) OCCLUSION_TARGET_FILL else OCCLUSION_NEUTRAL_FILL,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = corner
            )
            drawRoundRect(
                color = if (isTarget) OCCLUSION_TARGET_BORDER else OCCLUSION_NEUTRAL_BORDER,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = corner,
                style = Stroke(width = (if (isTarget) 2.2f else 1.2f).dp.toPx())
            )
            if (isTarget) {
                val badgeText = "#$resolvedId"
                badgePaint.textSize = (minOf(boxSize.width * 0.42f, boxSize.height * 0.56f))
                    .coerceIn(11.dp.toPx(), 18.dp.toPx())
                val baseline = topLeft.y + boxSize.height / 2f - (badgePaint.ascent() + badgePaint.descent()) / 2f
                drawContext.canvas.nativeCanvas.drawText(
                    badgeText,
                    topLeft.x + boxSize.width / 2f,
                    baseline,
                    badgePaint
                )
            }
        } else {
            if (isTarget) {
                drawRoundRect(
                    color = OCCLUSION_REVEALED_BORDER.copy(alpha = 0.08f),
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = OCCLUSION_REVEALED_BORDER,
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = corner,
                    style = Stroke(width = 2.4.dp.toPx(), pathEffect = OCCLUSION_DASH_EFFECT)
                )
            } else if (hideAll) {
                drawRoundRect(
                    color = OCCLUSION_NEUTRAL_FILL,
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = OCCLUSION_NEUTRAL_BORDER,
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = corner,
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }
        }
    }
}

@Composable
internal fun AnswerPicture(
    identity: Any,
    occlusionBoxes: List<OcclusionBox> = emptyList(),
    activeVariantKey: String? = null,
    targetBoxId: Int? = null,
    revealed: Boolean = true,
    hideAll: Boolean? = null,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 220.dp,
    load: suspend () -> ByteArray?
) {
    val cacheKey = remember(identity) { answerPictureCacheKey(identity) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    var attempt by remember(cacheKey) { mutableIntStateOf(0) }
    var picture by remember(cacheKey, attempt) {
        mutableStateOf(if (attempt == 0) answerPictureCache.get(cacheKey) else null)
    }
    LaunchedEffect(cacheKey, attempt) {
        if (attempt == 0) {
            val cached = picture ?: answerPictureCache.get(cacheKey)
            if (cached != null) {
                picture = cached
                failed = false
                return@LaunchedEffect
            }
        }
        failed = false
        try {
            val decoded = withContext(Dispatchers.IO) {
                load()?.let { bytes ->
                    requireNotNull(decodeSampledImageBitmap(bytes, MAX_PICTURE_DECODE_DIM_PX))
                }
            }
            if (decoded != null) {
                answerPictureCache.put(cacheKey, decoded)
            }
            picture = decoded
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    if (failed) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Não foi possível carregar a imagem.", color = MaterialTheme.colorScheme.error)
            TextButton({ attempt++ }) { Text("Tentar novamente") }
        }
    }
    val bitmap = picture ?: return
    val resolvedTargetId = remember(targetBoxId, activeVariantKey) {
        targetBoxId ?: parseTargetOcclusionBoxId(activeVariantKey)
    }
    val effectiveHideAll = hideAll ?: occlusionBoxes.firstOrNull()?.hideAll ?: true
    var expanded by remember(identity) { mutableStateOf(false) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        contentAlignment = Alignment.Center
    ) {
        val maxWpx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val maxHpx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val fitScale = minOf(maxWpx / bitmap.width.coerceAtLeast(1), maxHpx / bitmap.height.coerceAtLeast(1))
        val drawWidthDp = with(density) { (bitmap.width * fitScale).toDp() }
        val drawHeightDp = with(density) { (bitmap.height * fitScale).toDp() }

        Box(
            modifier = Modifier
                .size(drawWidthDp, drawHeightDp)
                .clip(studyControlShape())
                .clickable(onClickLabel = "Ampliar imagem") { expanded = true }
                .semantics {
                    contentDescription = if (occlusionBoxes.isNotEmpty()) {
                        if (revealed) "Imagem com oclusão revelada" else "Imagem com regiões ocultas"
                    } else {
                        "Imagem do cartão"
                    }
                }
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds
            )
            if (occlusionBoxes.isNotEmpty()) {
                Canvas(Modifier.matchParentSize()) {
                    showAllMaskedOverlay(
                        boxes = occlusionBoxes,
                        targetBoxId = resolvedTargetId,
                        revealed = revealed,
                        hideAll = effectiveHideAll
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                shape = RoundedCornerShape(999.dp),
                color = Color.Black.copy(alpha = 0.56f),
                contentColor = Color.White
            ) {
                Text(
                    text = if (occlusionBoxes.isNotEmpty() && resolvedTargetId != null) {
                        "#$resolvedTargetId · Ampliar"
                    } else {
                        "Ampliar"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var targetScale by remember { mutableFloatStateOf(1f) }
            val scale by animateFloatAsState(targetValue = targetScale, label = "answer_picture_zoom")
            var offset by remember { mutableStateOf(Offset.Zero) }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier
                        .safeDrawingPadding()
                        .padding(StudyTokens.cardPadding()),
                    verticalArrangement = Arrangement.spacedBy(StudyTokens.itemSpacing())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (occlusionBoxes.isNotEmpty()) "Imagem · Oclusão" else "Imagem do cartão",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (targetScale > 1.02f) {
                            TextButton({ targetScale = 1f; offset = Offset.Zero }) { Text("1x") }
                        }
                        TextButton({ expanded = false }) { Text("Fechar") }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
                    ) {
                        TextButton(
                            onClick = {
                                targetScale = (targetScale / 1.5f).coerceAtLeast(1f)
                                if (targetScale <= 1.01f) offset = Offset.Zero
                            },
                            enabled = targetScale > 1f
                        ) { Text("Reduzir zoom", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Text(
                            "${(scale * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        TextButton(
                            onClick = { targetScale = (targetScale * 1.5f).coerceAtMost(5f) },
                            enabled = targetScale < 5f
                        ) { Text("Aumentar zoom", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest, studyControlShape())
                            .clip(studyControlShape())
                            .clipToBounds(),
                        contentAlignment = Alignment.Center
                    ) {
                        val boxW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                        val boxH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                        val fit = minOf(boxW / bitmap.width.coerceAtLeast(1), boxH / bitmap.height.coerceAtLeast(1))
                        val imageWidth = bitmap.width * fit
                        val imageHeight = bitmap.height * fit
                        val baseWDp = with(density) { imageWidth.toDp() }
                        val baseHDp = with(density) { imageHeight.toDp() }
                        val transform = rememberTransformableState { zoom, pan, _ ->
                            val nextScale = (targetScale * zoom).coerceIn(1f, 5f)
                            targetScale = nextScale
                            val limitX = ((imageWidth * nextScale - boxW) / 2f).coerceAtLeast(0f)
                            val limitY = ((imageHeight * nextScale - boxH) / 2f).coerceAtLeast(0f)
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-limitX, limitX),
                                (offset.y + pan.y).coerceIn(-limitY, limitY)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .transformable(transform)
                                .pointerInput(imageWidth, imageHeight) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            if (targetScale > 1.25f) {
                                                targetScale = 1f
                                                offset = Offset.Zero
                                            } else {
                                                targetScale = 2.5f
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(baseWDp, baseHDp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    }
                            ) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Imagem ampliada",
                                    modifier = Modifier.matchParentSize(),
                                    contentScale = ContentScale.FillBounds
                                )
                                if (occlusionBoxes.isNotEmpty()) {
                                    Canvas(Modifier.matchParentSize()) {
                                        showAllMaskedOverlay(
                                            boxes = occlusionBoxes,
                                            targetBoxId = resolvedTargetId,
                                            revealed = revealed,
                                            hideAll = effectiveHideAll
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
