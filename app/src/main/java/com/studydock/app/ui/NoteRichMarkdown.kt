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

internal fun inlineAnnotated(text: String, linkColor: Color, codeBg: Color, linkify: Boolean = true): AnnotatedString = buildAnnotatedString {
    val highlightBg = linkColor.copy(alpha = 0.22f)
    fun appendFormatted(piece: String) {
        var last = 0
        for (match in inlineRegex.findAll(piece)) {
            append(piece.substring(last, match.range.first))
            val boldItalic = match.groups[1]?.value
            val bold = match.groups[2]?.value ?: match.groups[3]?.value
            val italic = match.groups[4]?.value ?: match.groups[5]?.value
            val highlight = match.groups[6]?.value
            val strike = match.groups[7]?.value
            val code = match.groups[8]?.value
            val linkText = match.groups[9]?.value
            val linkUrl = match.groups[10]?.value
            val tag = match.groups[11]?.value
            when {
                boldItalic != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    append(boldItalic)
                    pop()
                }
                bold != null -> { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(bold); pop() }
                italic != null -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(italic); pop() }
                highlight != null -> { pushStyle(SpanStyle(background = highlightBg)); append(highlight); pop() }
                strike != null -> { pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)); append(strike); pop() }
                code != null -> { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)); append(code); pop() }
                linkText != null && linkUrl != null && linkify -> {
                    pushStringAnnotation("URL", linkUrl)
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    append(linkText)
                    pop()
                    pop()
                }
                tag != null && linkify -> {
                    val isHexColor = (tag.length == 3 || tag.length == 4 || tag.length == 6 || tag.length == 8) &&
                        tag.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                    if (isHexColor) append(match.value)
                    else { pushStyle(SpanStyle(color = linkColor)); append("#$tag"); pop() }
                }
                else -> append(match.value)
            }
            last = match.range.last + 1
        }
        append(piece.substring(last))
    }
    var last = 0
    if (linkify) for (ref in NoteReferences.parse(text)) {
        appendFormatted(text.substring(last, ref.start))
        pushStringAnnotation("LINK", ref.raw)
        pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        append(ref.display)
        pop()
        pop()
        last = ref.end
    }
    appendFormatted(text.substring(last))
}

@Composable
private fun InlineLine(
    text: String,
    linkColor: Color,
    codeBg: Color,
    onOpenLinked: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    strike: Boolean = false,
    contentColor: Color = LocalContentColor.current
) {
    val uriHandler = LocalUriHandler.current
    val segments = remember(text) { parseMathSegments(text) }
    val hasMath = remember(segments) {
        segments.any { it is MathSegment.InlineMath || it is MathSegment.BlockMath }
    }
    val resolved = style.copy(
        color = if (strike) contentColor.copy(alpha = 0.65f) else contentColor,
        textDecoration = if (strike) TextDecoration.LineThrough else null
    )

    if (!hasMath) {
        val annotated = remember(text, linkColor, codeBg) { inlineAnnotated(text, linkColor, codeBg) }
        @Suppress("DEPRECATION")
        ClickableText(
            text = annotated,
            style = resolved,
            modifier = modifier,
            onClick = { offset ->
                annotated.getStringAnnotations("LINK", offset, offset).firstOrNull()?.let { onOpenLinked(it.item) }
                    ?: annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { urlAnn ->
                        runCatching { uriHandler.openUri(urlAnn.item) }
                    }
            }
        )
        return
    }

    val baseFontSp = if (resolved.fontSize != TextUnit.Unspecified) resolved.fontSize else MaterialTheme.typography.bodyMedium.fontSize
    val paragraphs = remember(segments) {
        val out = mutableListOf<Any>() // List<MathSegment> para corrida inline, ou MathSegment.BlockMath
        val currentInline = mutableListOf<MathSegment>()
        segments.forEach { seg ->
            if (seg is MathSegment.BlockMath) {
                if (currentInline.isNotEmpty()) {
                    out.add(currentInline.toList())
                    currentInline.clear()
                }
                out.add(seg)
            } else {
                currentInline.add(seg)
            }
        }
        if (currentInline.isNotEmpty()) out.add(currentInline.toList())
        out
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        paragraphs.forEach { entry ->
            when (entry) {
                is MathSegment.BlockMath -> {
                    LatexFormulaBlock(latex = entry.latex, textStyle = resolved, color = resolved.color)
                }
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    InlineMathParagraphRow(
                        segments = entry as List<MathSegment>,
                        linkColor = linkColor,
                        codeBg = codeBg,
                        resolved = resolved,
                        baseFontSp = baseFontSp,
                        onOpenLinked = onOpenLinked
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineMathParagraphRow(
    segments: List<MathSegment>,
    linkColor: Color,
    codeBg: Color,
    resolved: TextStyle,
    baseFontSp: TextUnit,
    onOpenLinked: (String) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        segments.forEach { seg ->
            when (seg) {
                is MathSegment.Text -> {
                    val fullAnnotated = remember(seg.value, linkColor, codeBg) { inlineAnnotated(seg.value, linkColor, codeBg) }
                    val chunks = remember(fullAnnotated) { splitAnnotatedKeepingLinksAndSpaces(fullAnnotated) }
                    chunks.forEach { chunkAnn ->
                        @Suppress("DEPRECATION")
                        ClickableText(
                            text = chunkAnn,
                            style = resolved,
                            modifier = Modifier.align(Alignment.CenterVertically),
                            onClick = { offset ->
                                chunkAnn.getStringAnnotations("LINK", offset, offset).firstOrNull()?.let { onOpenLinked(it.item) }
                                    ?: chunkAnn.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { urlAnn ->
                                        runCatching { uriHandler.openUri(urlAnn.item) }
                                    }
                            }
                        )
                    }
                }
                is MathSegment.InlineMath -> {
                    LatexInlineFormula(
                        latex = seg.latex,
                        textStyle = resolved.copy(fontSize = baseFontSp),
                        color = resolved.color,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(horizontal = 2.dp)
                    )
                }
                is MathSegment.BlockMath -> Unit
            }
        }
    }
}

private fun splitAnnotatedKeepingLinksAndSpaces(annotated: AnnotatedString): List<AnnotatedString> {
    val text = annotated.text
    if (text.isEmpty()) return emptyList()
    val refRanges = (
        annotated.getStringAnnotations("LINK", 0, annotated.length) +
            annotated.getStringAnnotations("URL", 0, annotated.length)
        ).sortedBy { it.start }
    val tokens = mutableListOf<AnnotatedString>()
    var i = 0
    while (i < text.length) {
        val refAtPos = refRanges.firstOrNull { i in it.start until it.end }
        if (refAtPos != null) {
            var end = refAtPos.end.coerceAtMost(text.length)
            while (end < text.length && text[end].isWhitespace() && text[end] != '\n') end++
            tokens.add(annotated.subSequence(i, end))
            i = end
            continue
        }
        val nextRefStart = refRanges.firstOrNull { it.start > i }?.start ?: text.length
        val start = i
        while (i < nextRefStart && !text[i].isWhitespace()) i++
        while (i < nextRefStart && text[i].isWhitespace()) i++
        if (i > start) tokens.add(annotated.subSequence(start, i))
        else i++
    }
    return tokens
}

@Composable
internal fun RichMarkdown(
    markdown: String,
    onToggleLine: (Int) -> Unit,
    onOpenLinked: (String) -> Unit,
    notes: List<Note> = emptyList(),
    links: List<NoteLink> = emptyList(),
    tasks: List<StudyTask> = emptyList(),
    transclusionStack: Set<Long> = emptySet(),
    highlightedHeading: String? = null,
    jumpVersion: Int = 0,
    onOpenNoteById: ((Long) -> Unit)? = null,
    onToggleTask: ((StudyTask) -> Unit)? = null,
    contentColor: Color = LocalContentColor.current,
    baseTextStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val cleanBody = remember(markdown) { NoteFrontmatter.body(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkIndex = remember(notes, links) {
        if (notes.isEmpty() && links.isEmpty()) null else NoteLinkIndex(notes, links)
    }

    val lines = remember(cleanBody) { cleanBody.lines() }
    val firstMatchingLineIdx = remember(lines, highlightedHeading) {
        if (highlightedHeading.isNullOrBlank()) -1
        else {
            val exactIdx = lines.indexOfFirst { raw ->
                val m = headingLineRegex.matchEntire(raw.trimEnd())
                m != null && m.groupValues[2].trim().equals(highlightedHeading, ignoreCase = true)
            }
            if (exactIdx >= 0) exactIdx else lines.indexOfFirst { raw ->
                val m = headingLineRegex.matchEntire(raw.trimEnd())
                m != null && m.groupValues[2].trim().contains(highlightedHeading, ignoreCase = true)
            }
        }
    }
    var lineIdx = 0
    while (lineIdx < lines.size) {
        val rawLine = lines[lineIdx]
        val line = rawLine.trimEnd()
        val trimmed = line.trim()
        val index = lineIdx

        // Blind Proof interactive block (:::proof Título ... :::)
        if (trimmed.startsWith(":::proof")) {
            val proofTitle = trimmed.removePrefix(":::proof").trim().ifBlank { "Demonstração (Prova Cega)" }
            val proofSteps = mutableListOf<String>()
            var nextIdx = lineIdx + 1
            while (nextIdx < lines.size) {
                val cand = lines[nextIdx].trim()
                if (cand == ":::") {
                    nextIdx++
                    break
                }
                if (cand.isNotEmpty()) {
                    proofSteps.add(cand)
                }
                nextIdx++
            }
            if (proofSteps.isNotEmpty()) {
                BlindProofInteractiveCard(
                    block = BlindProofBlock(title = proofTitle, steps = proofSteps)
                )
            }
            lineIdx = nextIdx
            continue
        }

        // Multi-line or single-line LaTeX block ($$...$$)
        if (trimmed.startsWith("$$")) {
            val singleLineClose = if (trimmed.length > 4 && trimmed.endsWith("$$")) trimmed.length - 2 else -1
            if (singleLineClose > 2) {
                val latex = trimmed.substring(2, singleLineClose).trim()
                if (latex.isNotEmpty()) LatexFormulaBlock(latex = latex, textStyle = baseTextStyle, color = contentColor)
                lineIdx++
                continue
            } else {
                val blockLines = mutableListOf<String>()
                val afterOpen = trimmed.removePrefix("$$").trim()
                if (afterOpen.isNotEmpty()) blockLines.add(afterOpen)
                var nextIdx = lineIdx + 1
                var foundClose = false
                while (nextIdx < lines.size) {
                    val cand = lines[nextIdx].trim()
                    if (cand.contains("$$")) {
                        val beforeClose = cand.substringBefore("$$").trim()
                        if (beforeClose.isNotEmpty()) blockLines.add(beforeClose)
                        foundClose = true
                        nextIdx++
                        break
                    }
                    blockLines.add(lines[nextIdx])
                    nextIdx++
                }
                if (foundClose) {
                    val latex = blockLines.joinToString("\n").trim()
                    if (latex.isNotEmpty()) LatexFormulaBlock(latex = latex, textStyle = baseTextStyle, color = contentColor)
                    lineIdx = nextIdx
                    continue
                }
            }
        }

        // Multi-line or single-line LaTeX block (\[...\])
        if (trimmed.startsWith("\\[") && !trimmed.startsWith("\\[[")) {
            val singleLineClose = if (trimmed.length > 4 && trimmed.endsWith("\\]")) trimmed.length - 2 else -1
            if (singleLineClose > 2) {
                val latex = trimmed.substring(2, singleLineClose).trim()
                if (latex.isNotEmpty()) LatexFormulaBlock(latex = latex, textStyle = baseTextStyle, color = contentColor)
                lineIdx++
                continue
            } else {
                val blockLines = mutableListOf<String>()
                val afterOpen = trimmed.removePrefix("\\[").trim()
                if (afterOpen.isNotEmpty()) blockLines.add(afterOpen)
                var nextIdx = lineIdx + 1
                var foundClose = false
                while (nextIdx < lines.size) {
                    val cand = lines[nextIdx].trim()
                    if (cand.contains("\\]")) {
                        val beforeClose = cand.substringBefore("\\]").trim()
                        if (beforeClose.isNotEmpty()) blockLines.add(beforeClose)
                        foundClose = true
                        nextIdx++
                        break
                    }
                    blockLines.add(lines[nextIdx])
                    nextIdx++
                }
                if (foundClose) {
                    val latex = blockLines.joinToString("\n").trim()
                    if (latex.isNotEmpty()) LatexFormulaBlock(latex = latex, textStyle = baseTextStyle, color = contentColor)
                    lineIdx = nextIdx
                    continue
                }
            }
        }

        // Fenced code blocks & live query/dataview blocks
        val marker = fenceMarkerRegex.matchEntire(line)
        if (marker != null) {
            val fenceChar = marker.groupValues[1].first()
            val fenceLength = marker.groupValues[1].length
            val fenceLang = marker.groupValues[2].trim()
            val codeLines = mutableListOf<String>()
            var nextIdx = lineIdx + 1
            while (nextIdx < lines.size) {
                val candLine = lines[nextIdx].trimEnd()
                val closeMarker = fenceMarkerRegex.matchEntire(candLine)
                if (closeMarker != null &&
                    closeMarker.groupValues[1].first() == fenceChar &&
                    closeMarker.groupValues[1].length >= fenceLength &&
                    closeMarker.groupValues[2].isBlank()
                ) {
                    nextIdx++
                    break
                }
                codeLines.add(lines[nextIdx])
                nextIdx++
            }
            val codeText = codeLines.joinToString("\n")
            if (fenceLang.equals("query", ignoreCase = true) || fenceLang.equals("dataview", ignoreCase = true)) {
                DataviewQueryCard(
                    queryBlock = codeText,
                    notes = notes,
                    tasks = tasks,
                    onOpenNoteById = onOpenNoteById,
                    onToggleTask = onToggleTask
                )
            } else {
                CodeBlockCard(code = codeText, language = fenceLang)
            }
            lineIdx = nextIdx
            continue
        }

        // Embed / Transclusion line: ![[Note]] or ![[Note#Section]]
        val lineRefs = NoteReferences.parse(line)
        val standaloneEmbed = lineRefs.singleOrNull()?.takeIf {
            it.isEmbed && line.trim() == line.substring(it.start, it.end).trim()
        }
        if (standaloneEmbed != null) {
            TransclusionCard(
                ref = standaloneEmbed,
                notes = notes,
                links = links,
                tasks = tasks,
                linkIndex = linkIndex,
                transclusionStack = transclusionStack,
                onOpenLinked = onOpenLinked,
                onOpenNoteById = onOpenNoteById,
                onToggleTask = onToggleTask
            )
            lineIdx++
            continue
        }

        // Collapsible Multi-block Callout check (> [!info]+ Título / > [!warning]-)
        val calloutMatch = calloutStartRegex.matchEntire(line)
        if (calloutMatch != null) {
            val type = calloutMatch.groupValues[1].uppercase(Locale.ROOT)
            val foldMod = calloutMatch.groupValues[2]
            val customTitle = calloutMatch.groupValues[3].trim()
            val contentStartLine = lineIdx + 1
            val calloutLines = mutableListOf<String>()
            var nextIdx = lineIdx + 1
            while (nextIdx < lines.size && lines[nextIdx].trimStart().startsWith(">")) {
                calloutLines.add(lines[nextIdx].trimStart().removePrefix(">").removePrefix(" "))
                nextIdx++
            }
            lineIdx = nextIdx
            CalloutBlock(
                type = type,
                foldModifier = foldMod,
                customTitle = customTitle,
                content = calloutLines.joinToString("\n"),
                onToggleInnerLine = { relIdx -> onToggleLine(contentStartLine + relIdx) },
                onOpenLinked = onOpenLinked,
                notes = notes,
                links = links,
                tasks = tasks,
                transclusionStack = transclusionStack,
                onOpenNoteById = onOpenNoteById,
                onToggleTask = onToggleTask
            )
            continue
        }

        // Table check (| col 1 | col 2 |)
        if (line.contains('|') && lineIdx + 1 < lines.size && tableSeparatorRegex.matches(lines[lineIdx + 1].trim())) {
            val tableLines = mutableListOf<String>()
            var nextIdx = lineIdx
            while (nextIdx < lines.size && lines[nextIdx].trim().contains('|') && lines[nextIdx].trim().isNotEmpty()) {
                tableLines.add(lines[nextIdx].trim())
                nextIdx++
            }
            lineIdx = nextIdx
            MarkdownTable(tableLines, linkColor, codeBg, onOpenLinked)
            continue
        }

        // Standard Markdown lines
        val check = checklistRegex.matchEntire(line)
        val headingMatch = headingLineRegex.matchEntire(line)
        val bulletMatch = bulletLineRegex.matchEntire(line)
        val orderedMatch = orderedLineRegex.matchEntire(line)
        when {
            check != null -> {
                val done = check.groupValues[2].equals("x", ignoreCase = true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = done, onCheckedChange = { onToggleLine(index) })
                    InlineLine(
                        text = check.groupValues[3],
                        linkColor = linkColor,
                        codeBg = codeBg,
                        onOpenLinked = onOpenLinked,
                        modifier = Modifier.weight(1f),
                        style = baseTextStyle,
                        strike = done,
                        contentColor = contentColor
                    )
                }
            }
            headingMatch != null -> {
                val level = headingMatch.groupValues[1].length
                val headingText = headingMatch.groupValues[2].trim()
                val headingStyle = when (level) {
                    1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                }
                val isTargetHeading = index == firstMatchingLineIdx
                val bringRequester = remember(index, headingText) { BringIntoViewRequester() }
                LaunchedEffect(isTargetHeading, jumpVersion) {
                    if (isTargetHeading) bringRequester.bringIntoView()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringRequester)
                        .then(
                            if (isTargetHeading) {
                                Modifier
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            } else {
                                Modifier.padding(top = if (level <= 2) 10.dp else 8.dp)
                            }
                        )
                ) {
                    InlineLine(headingText, linkColor, codeBg, onOpenLinked, style = headingStyle, contentColor = contentColor)
                }
            }
            line.startsWith("> ") || line == ">" -> Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(linkColor))
                InlineLine(
                    text = line.removePrefix(">").trimStart(),
                    linkColor = linkColor,
                    codeBg = codeBg,
                    onOpenLinked = onOpenLinked,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    style = baseTextStyle,
                    contentColor = contentColor
                )
            }
            bulletMatch != null -> {
                val indentDp = ((bulletMatch.groupValues[1].length / 2).coerceIn(0, 4) * 12).dp
                InlineLine(
                    text = "• " + bulletMatch.groupValues[2],
                    linkColor = linkColor,
                    codeBg = codeBg,
                    onOpenLinked = onOpenLinked,
                    modifier = Modifier.padding(start = 6.dp + indentDp, top = 2.dp),
                    style = baseTextStyle,
                    contentColor = contentColor
                )
            }
            orderedMatch != null -> {
                val indentDp = ((orderedMatch.groupValues[1].length / 2).coerceIn(0, 4) * 12).dp
                InlineLine(
                    text = "${orderedMatch.groupValues[2]} ${orderedMatch.groupValues[3]}",
                    linkColor = linkColor,
                    codeBg = codeBg,
                    onOpenLinked = onOpenLinked,
                    modifier = Modifier.padding(start = 6.dp + indentDp, top = 2.dp),
                    style = baseTextStyle,
                    contentColor = contentColor
                )
            }
            line == "---" || line == "***" -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
            line.isBlank() -> Spacer(Modifier.height(6.dp))
            else -> InlineLine(line, linkColor, codeBg, onOpenLinked, Modifier.padding(top = 2.dp), style = baseTextStyle, contentColor = contentColor)
        }
        lineIdx++
    }
}

@Composable
private fun TransclusionCard(
    ref: WikiReference,
    notes: List<Note>,
    links: List<NoteLink>,
    tasks: List<StudyTask>,
    linkIndex: NoteLinkIndex?,
    transclusionStack: Set<Long>,
    onOpenLinked: (String) -> Unit,
    onOpenNoteById: ((Long) -> Unit)?,
    onToggleTask: ((StudyTask) -> Unit)?
) {
    val target = remember(ref, notes, links) {
        linkIndex?.target(null, ref) ?: notes.firstOrNull {
            it.trashedAt == null && (
                it.title.equals(ref.noteTarget, true) ||
                    NoteFrontmatter.properties(it.markdown).aliases.any { a -> a.equals(ref.noteTarget, true) }
                )
        }
    }

    if (target == null) {
        Surface(
            onClick = { onOpenLinked(ref.raw) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = studyControlShape(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Description, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Transclusão não encontrada: ${ref.target}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    if (target.id in transclusionStack || transclusionStack.size >= 4) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = studyControlShape(),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        ) {
            Text(
                "Ciclo de transclusão interrompido para [[${target.title}]].",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(10.dp)
            )
        }
        return
    }

    val embeddedMarkdown = remember(target.markdown, ref.heading, ref.blockId) {
        val sectionKey = ref.heading ?: ref.blockId?.let { "^$it" }
        if (!sectionKey.isNullOrBlank()) {
            extractSectionMarkdown(target.markdown, sectionKey) ?: "(Seção \"$sectionKey\" não encontrada em ${target.title})"
        } else {
            NoteFrontmatter.body(target.markdown).ifBlank { "(Nota vazia)" }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Description, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    val subtitle = ref.heading?.let { " › $it" } ?: ref.blockId?.let { " › ^$it" } ?: ""
                    Text(
                        "${target.title}$subtitle",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = { onOpenNoteById?.invoke(target.id) ?: onOpenLinked(ref.raw) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Abrir", style = MaterialTheme.typography.labelSmall)
                    }
                }
                RichMarkdown(
                    markdown = embeddedMarkdown,
                    onToggleLine = {},
                    onOpenLinked = onOpenLinked,
                    notes = notes,
                    links = links,
                    tasks = tasks,
                    transclusionStack = transclusionStack + target.id,
                    onOpenNoteById = onOpenNoteById,
                    onToggleTask = onToggleTask
                )
            }
        }
    }
}

@Composable
private fun CodeBlockCard(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val highlighted = remember(code, language, scheme) { highlightCodeAnnotated(code, language, scheme) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().background(scheme.surfaceContainerHighest.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(shape = RoundedCornerShape(6.dp), color = scheme.primaryContainer.copy(alpha = 0.75f)) {
                    Text(
                        language.ifBlank { "código" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, "Código copiado", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, "Copiar código", Modifier.size(16.dp))
                }
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)) {
                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

private val codeKeywords = setOf(
    "fun", "val", "var", "class", "data", "object", "interface", "sealed", "enum", "override",
    "suspend", "return", "if", "else", "for", "while", "when", "in", "is", "as", "import",
    "package", "private", "public", "internal", "protected", "try", "catch", "throw", "finally",
    "true", "false", "null", "def", "from", "with", "yield", "async", "await", "None", "True",
    "False", "const", "let", "function", "export", "SELECT", "FROM", "WHERE", "ORDER", "BY",
    "GROUP", "LIMIT", "INSERT", "UPDATE", "DELETE", "JOIN", "AND", "OR", "NOT", "AS"
)
private val codeTokenRegex = Regex(
    """(//.*$|#.*$|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)""",
    RegexOption.MULTILINE
)

private fun highlightCodeAnnotated(code: String, language: String, scheme: ColorScheme): AnnotatedString = buildAnnotatedString {
    val hashCommentLangs = setOf("py", "python", "sh", "bash", "yaml", "yml", "rb", "r", "toml", "")
    val allowHashComment = language.lowercase(Locale.ROOT) in hashCommentLangs
    var last = 0
    for (m in codeTokenRegex.findAll(code)) {
        append(code.substring(last, m.range.first))
        val tok = m.value
        when {
            tok.startsWith("//") || (allowHashComment && tok.startsWith("#")) -> {
                pushStyle(SpanStyle(color = scheme.onSurfaceVariant, fontStyle = FontStyle.Italic))
                append(tok)
                pop()
            }
            tok.startsWith("\"") || tok.startsWith("'") -> {
                pushStyle(SpanStyle(color = scheme.tertiary))
                append(tok)
                pop()
            }
            tok.first().isDigit() -> {
                pushStyle(SpanStyle(color = scheme.secondary, fontWeight = FontWeight.Medium))
                append(tok)
                pop()
            }
            tok in codeKeywords -> {
                pushStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.SemiBold))
                append(tok)
                pop()
            }
            else -> append(tok)
        }
        last = m.range.last + 1
    }
    append(code.substring(last))
}

private data class DataviewRenderState(
    val result: DataviewResult,
    val noteMetaById: Map<Long, String>
)

@Composable
private fun DataviewQueryCard(
    queryBlock: String,
    notes: List<Note>,
    tasks: List<StudyTask>,
    onOpenNoteById: ((Long) -> Unit)?,
    onToggleTask: ((StudyTask) -> Unit)?
) {
    var renderState by remember { mutableStateOf<DataviewRenderState?>(null) }
    LaunchedEffect(queryBlock, notes, tasks) {
        renderState = withContext(Dispatchers.Default) {
            val result = DataviewQueryEngine.execute(queryBlock, notes, tasks)
            val metaById = if (result.mode != "TASKS" && result.error == null) {
                result.notes.associate { note ->
                    val props = NoteFrontmatter.properties(note.markdown)
                    val meta = listOfNotNull(
                        props.folder?.let { "📁 $it" },
                        noteTags(note.markdown).take(2).joinToString(" ") { "#$it" }.ifBlank { null }
                    ).joinToString(" · ")
                    note.id to meta
                }
            } else {
                emptyMap()
            }
            DataviewRenderState(result = result, noteMetaById = metaById)
        }
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.secondary.copy(alpha = 0.4f))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val loaded = renderState
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = scheme.secondary)
                val headerLabel = if (loaded == null) {
                    "Query · Executando..."
                } else {
                    val result = loaded.result
                    val count = if (result.mode == "TASKS") result.tasks.size else result.notes.size
                    if (result.mode == "TASKS") "Query · Tarefas ($count)" else "Query · Notas ($count)"
                }
                Text(
                    headerLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
            if (loaded == null) {
                Text("Processando consulta...", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else {
                val result = loaded.result
                val errorText = result.error
                if (errorText != null) {
                    Text(errorText, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                } else if (result.mode == "TASKS") {
                    if (result.tasks.isEmpty()) {
                        Text("Nenhuma tarefa encontrada pela consulta.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    } else {
                        result.tasks.forEach { task ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { task.noteId?.let { onOpenNoteById?.invoke(it) } }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Checkbox(
                                    checked = task.completed,
                                    onCheckedChange = { onToggleTask?.invoke(task) }
                                )
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                } else {
                    if (result.notes.isEmpty()) {
                        Text("Nenhuma nota encontrada pela consulta.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    } else {
                        result.notes.forEach { note ->
                            val meta = loaded.noteMetaById[note.id].orEmpty()
                            Surface(
                                onClick = { onOpenNoteById?.invoke(note.id) },
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Description, null, Modifier.size(16.dp), tint = scheme.primary)
                                    Column(Modifier.weight(1f)) {
                                        Text(note.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (meta.isNotEmpty()) {
                                            Text(meta, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                        }
                                    }
                                    Icon(Icons.Default.ArrowOutward, null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class CalloutVisuals(
    val icon: ImageVector,
    val defaultTitle: String,
    val container: Color,
    val content: Color,
    val accent: Color
)

@Composable
private fun CalloutBlock(
    type: String,
    foldModifier: String,
    customTitle: String,
    content: String,
    onToggleInnerLine: (Int) -> Unit = {},
    onOpenLinked: (String) -> Unit,
    notes: List<Note>,
    links: List<NoteLink>,
    tasks: List<StudyTask>,
    transclusionStack: Set<Long>,
    onOpenNoteById: ((Long) -> Unit)?,
    onToggleTask: ((StudyTask) -> Unit)?
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalStudySemanticColors.current
    val visuals = when (type) {
        "TIP", "SUCCESS", "CHECK", "DONE", "HINT" -> CalloutVisuals(
            Icons.Default.Lightbulb,
            "Dica",
            semantic.successContainer,
            semantic.onSuccessContainer,
            semantic.success
        )
        "IMPORTANT", "WARNING", "ATTENTION" -> CalloutVisuals(
            Icons.Default.Warning,
            "Importante",
            semantic.warningContainer,
            semantic.onWarningContainer,
            semantic.warning
        )
        "CAUTION", "DANGER", "ERROR", "FAIL" -> CalloutVisuals(
            Icons.Default.ReportProblem,
            "Atenção",
            scheme.errorContainer,
            scheme.onErrorContainer,
            scheme.error
        )
        "QUESTION", "FAQ", "HELP" -> CalloutVisuals(
            Icons.AutoMirrored.Filled.HelpOutline,
            "Pergunta",
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
            scheme.secondary
        )
        "EXAMPLE" -> CalloutVisuals(
            Icons.Default.AutoStories,
            "Exemplo",
            scheme.tertiaryContainer,
            scheme.onTertiaryContainer,
            scheme.tertiary
        )
        else -> CalloutVisuals(
            Icons.Default.Info,
            "Nota",
            semantic.infoContainer,
            semantic.onInfoContainer,
            scheme.primary
        )
    }
    val borderTint = visuals.accent.copy(alpha = 0.45f)
    val collapsible = foldModifier == "+" || foldModifier == "-"
    var expanded by remember(type, customTitle, foldModifier) { mutableStateOf(foldModifier != "-") }

    Surface(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = studyControlShape(),
        color = visuals.container,
        contentColor = visuals.content,
        border = BorderStroke(1.dp, borderTint)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(visuals.icon, null, tint = visuals.content, modifier = Modifier.size(18.dp))
                Text(
                    customTitle.ifBlank { visuals.defaultTitle },
                    fontWeight = FontWeight.Bold,
                    color = visuals.content,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (collapsible) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Recolher callout" else "Expandir callout",
                        tint = visuals.content
                    )
                }
            }
            if (expanded && content.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                CompositionLocalProvider(LocalContentColor provides visuals.content) {
                    RichMarkdown(
                        markdown = content,
                        onToggleLine = onToggleInnerLine,
                        onOpenLinked = onOpenLinked,
                        notes = notes,
                        links = links,
                        tasks = tasks,
                        transclusionStack = transclusionStack,
                        onOpenNoteById = onOpenNoteById,
                        onToggleTask = onToggleTask
                    )
                }
            }
        }
    }
}

private fun parseMarkdownTableRow(row: String): List<String> {
    var trimmed = row.trim()
    if (trimmed.startsWith("|")) trimmed = trimmed.substring(1)
    if (trimmed.endsWith("|") && (trimmed.length < 2 || trimmed[trimmed.length - 2] != '\\')) {
        trimmed = trimmed.substring(0, trimmed.length - 1)
    }
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < trimmed.length) {
        val ch = trimmed[i]
        if (ch == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '|') {
            current.append('|')
            i += 2
        } else if (ch == '|') {
            cells.add(current.toString().trim())
            current.clear()
            i++
        } else {
            current.append(ch)
            i++
        }
    }
    cells.add(current.toString().trim())
    return cells
}

@Composable
private fun MarkdownTable(lines: List<String>, linkColor: Color, codeBg: Color, onOpenLinked: (String) -> Unit) {
    if (lines.size < 2) return
    val headerCells = parseMarkdownTableRow(lines[0])
    if (headerCells.isEmpty()) return
    val colCount = headerCells.size
    val dataRows = lines.drop(2).map { row ->
        val parsed = parseMarkdownTableRow(row)
        List(colCount) { idx -> parsed.getOrElse(idx) { "" } }
    }

    Surface(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                // Header with inline formatting support
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 8.dp)) {
                    headerCells.forEach { cell ->
                        Box(Modifier.widthIn(min = 100.dp, max = 240.dp).padding(horizontal = 12.dp)) {
                            InlineLine(
                                text = cell,
                                linkColor = linkColor,
                                codeBg = codeBg,
                                onOpenLinked = onOpenLinked,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
                HorizontalDivider()
                // Data rows preserving empty cells
                dataRows.forEach { row ->
                    Row(Modifier.padding(vertical = 8.dp)) {
                        row.forEach { cell ->
                            Box(Modifier.widthIn(min = 100.dp, max = 240.dp).padding(horizontal = 12.dp)) {
                                if (cell.isEmpty()) {
                                    Text("", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    InlineLine(cell, linkColor, codeBg, onOpenLinked)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

data class BlindProofBlock(val title: String, val steps: List<String>)

fun parseBlindProofBlocks(markdown: String): List<BlindProofBlock> {
    val regex = Regex(":::proof(?:\\s+([^\\n]*))?\\n([\\s\\S]*?)\\n:::")
    return regex.findAll(markdown).map { m ->
        val title = m.groupValues[1].trim().ifBlank { "Demonstração (Prova Cega)" }
        val steps = m.groupValues[2].lines().map { it.trim() }.filter { it.isNotEmpty() }
        BlindProofBlock(title, steps)
    }.toList()
}

@Composable
fun BlindProofInteractiveCard(block: BlindProofBlock, modifier: Modifier = Modifier) {
    var revealedSteps by remember(block.title, block.steps.size) { mutableStateOf(0) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🛡️ ${block.title}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("${revealedSteps}/${block.steps.size} passos", style = MaterialTheme.typography.labelSmall)
            }
            block.steps.forEachIndexed { idx, step ->
                val visible = idx < revealedSteps
                Text(
                    text = if (visible) "${idx + 1}. $step" else "${idx + 1}. ••••••••••••••••••••••••",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (visible) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.45f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (revealedSteps < block.steps.size) {
                    TextButton(onClick = { revealedSteps++ }) { Text("Revelar próximo passo") }
                }
                if (revealedSteps > 0) {
                    TextButton(onClick = { revealedSteps = 0 }) { Text("Ocultar tudo") }
                }
            }
        }
    }
}
