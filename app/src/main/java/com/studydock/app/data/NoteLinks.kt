package com.studydock.app.data

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/** Offsets refer to the original Markdown, including surrounding `[[...]]` or `![[...]]`. */
data class WikiReference(
    val start: Int,
    val end: Int,
    val raw: String,
    val target: String,
    val label: String?,
    val isEmbed: Boolean = false
) {
    val display: String get() = label ?: target
    val alias: String? get() = label
    val noteTarget: String get() = target.substringBefore('#').trim()
    val heading: String?
        get() = target.substringAfter('#', "")
            .trim()
            .takeIf { it.isNotEmpty() && !it.startsWith("^") }
    val blockId: String?
        get() = target.substringAfter('#', "")
            .trim()
            .takeIf { it.startsWith("^") }
            ?.removePrefix("^")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

typealias NoteReference = WikiReference

/** Projeção leve de nota para resolução de [[links]] sem carregar o markdown inteiro da tabela. */
data class NoteLinkHeader(
    val id: Long,
    val title: String,
    val trashedAt: Long?,
    val headMarkdown: String
)

data class MarkdownHeading(
    val level: Int,
    val title: String,
    val anchor: String,
    val lineIndex: Int,
    val charOffset: Int
)

data class FolderTreeNode(
    val name: String,
    val fullPath: String,
    val noteIds: List<Long>,
    val totalNotes: Int,
    val children: List<FolderTreeNode>
)

data class TagTreeNode(
    val segment: String,
    val fullTag: String,
    val noteIds: List<Long>,
    val totalNotes: Int,
    val children: List<TagTreeNode>
)

data class DataviewResult(
    val mode: String,
    val notes: List<Note> = emptyList(),
    val tasks: List<StudyTask> = emptyList(),
    val error: String? = null
)

internal fun mayHaveFrontmatter(markdown: String): Boolean {
    var i = 0
    val len = markdown.length
    while (i < len && markdown[i].isWhitespace()) i++
    return i + 2 < len && markdown[i] == '-' && markdown[i + 1] == '-' && markdown[i + 2] == '-'
}

internal fun fastPropertiesAndBody(markdown: String): Pair<NoteProperties, String> =
    if (!mayHaveFrontmatter(markdown)) NoteProperties() to markdown
    else NoteFrontmatter.parse(markdown)

private fun mergeSortedRanges(sorted: List<IntRange>): List<IntRange> {
    if (sorted.size <= 1) return sorted
    val merged = ArrayList<IntRange>(sorted.size)
    var current = sorted[0]
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        if (next.first <= current.last + 1) {
            if (next.last > current.last) current = current.first..next.last
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
}

internal object MarkdownCodeScanner {
    private val fenceRegex = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val frontmatterRegex = Regex("""(?s)^\s*---\r?\n.*?\r?\n---\r?\n?""")
    private val emptyFrontmatterRegex = Regex("""(?s)^\s*---\r?\n---\r?\n?""")

    data class LineSlice(
        val index: Int,
        val startOffset: Int,
        val endOffset: Int,
        val content: String,
        val inFence: Boolean,
        val isFenceDelimiter: Boolean
    )

    fun frontmatterEndOffset(markdown: String): Int {
        if (!mayHaveFrontmatter(markdown)) return 0
        val m = frontmatterRegex.find(markdown) ?: emptyFrontmatterRegex.find(markdown)
        return if (m != null) m.range.last + 1 else 0
    }

    fun scanLines(markdown: String): List<LineSlice> {
        if (markdown.isEmpty()) return emptyList()
        val lines = mutableListOf<LineSlice>()
        var lineIndex = 0
        var start = 0
        var fenceChar: Char? = null
        var fenceLen = 0

        while (start <= markdown.length) {
            val nl = markdown.indexOf('\n', start)
            val rawEnd = if (nl < 0) markdown.length else nl
            val contentEnd = if (rawEnd > start && markdown[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
            val content = markdown.substring(start, contentEnd)

            var prefix = 0
            while (prefix < content.length && prefix < 3 && content[prefix] == ' ') prefix++
            val maybeFence = prefix + 2 < content.length && (content[prefix] == '`' || content[prefix] == '~')
            val marker = if (maybeFence) fenceRegex.matchEntire(content) else null
            val inFenceNow: Boolean
            val isDelimiter: Boolean
            if (fenceChar != null) {
                val closes = marker != null &&
                    marker.groupValues[1].first() == fenceChar &&
                    marker.groupValues[1].length >= fenceLen &&
                    marker.groupValues[2].isBlank()
                if (closes) {
                    fenceChar = null
                    fenceLen = 0
                }
                inFenceNow = true
                isDelimiter = closes
            } else if (marker != null) {
                fenceChar = marker.groupValues[1].first()
                fenceLen = marker.groupValues[1].length
                inFenceNow = true
                isDelimiter = true
            } else {
                inFenceNow = false
                isDelimiter = false
            }

            lines.add(LineSlice(lineIndex, start, contentEnd, content, inFenceNow, isDelimiter))
            lineIndex++
            if (nl < 0) break
            start = nl + 1
            if (start == markdown.length) break
        }
        return lines
    }

    /**
     * Returns sorted, non-overlapping `[start, end)` intervals in [markdown] covered by fenced
     * code blocks and balanced inline code spans (per CommonMark paragraph boundaries).
     */
    fun excludedCodeRanges(markdown: String, lines: List<LineSlice> = scanLines(markdown)): List<IntRange> {
        val hasBackticks = markdown.indexOf('`') >= 0
        var anyFence = false
        for (i in lines.indices) {
            if (lines[i].inFence) {
                anyFence = true
                break
            }
        }
        if (!hasBackticks && !anyFence) return emptyList()

        val ranges = mutableListOf<IntRange>()

        if (anyFence) {
            var fenceStart: Int? = null
            for (line in lines) {
                if (line.inFence) {
                    if (fenceStart == null) fenceStart = line.startOffset
                    if (line.isFenceDelimiter && fenceStart != line.startOffset) {
                        ranges.add(fenceStart..line.endOffset)
                        fenceStart = null
                    } else if (line === lines.last()) {
                        ranges.add(fenceStart..line.endOffset)
                        fenceStart = null
                    }
                } else if (fenceStart != null) {
                    ranges.add(fenceStart..line.startOffset)
                    fenceStart = null
                }
            }
            if (fenceStart != null) {
                ranges.add(fenceStart until markdown.length)
            }
        }

        if (hasBackticks) {
            // Inline code spans within contiguous non-blank, non-fence paragraphs
            var idx = 0
            while (idx < lines.size) {
                val line = lines[idx]
                if (line.inFence || line.content.isBlank()) {
                    idx++
                    continue
                }
                val paraStart = line.startOffset
                var paraEnd = line.endOffset
                var endIdx = idx + 1
                while (endIdx < lines.size && !lines[endIdx].inFence && lines[endIdx].content.isNotBlank()) {
                    paraEnd = lines[endIdx].endOffset
                    endIdx++
                }
                val tickIdx = markdown.indexOf('`', paraStart)
                if (tickIdx in 0 until paraEnd) {
                    collectInlineCodeSpans(markdown, paraStart, paraEnd, ranges)
                }
                idx = endIdx
            }
        }

        return ranges.sortedBy { it.first }
    }

    private fun collectInlineCodeSpans(
        text: String,
        start: Int,
        end: Int,
        out: MutableList<IntRange>
    ) {
        var i = start
        while (i < end) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < end) {
                i += 2
                continue
            }
            if (ch != '`') {
                i++
                continue
            }
            var runEnd = i + 1
            while (runEnd < end && text[runEnd] == '`') runEnd++
            val tickLen = runEnd - i
            val closeStart = findMatchingBacktickRun(text, runEnd, end, tickLen)
            if (closeStart >= 0) {
                out.add(i until (closeStart + tickLen))
                i = closeStart + tickLen
            } else {
                i = runEnd
            }
        }
    }

    private fun findMatchingBacktickRun(text: String, from: Int, limit: Int, wantedLen: Int): Int {
        var i = from
        while (i < limit) {
            if (text[i] != '`') {
                i++
                continue
            }
            var runEnd = i + 1
            while (runEnd < limit && text[runEnd] == '`') runEnd++
            if (runEnd - i == wantedLen) return i
            i = runEnd
        }
        return -1
    }

    fun containsOffset(sortedRanges: List<IntRange>, offset: Int): Boolean {
        var lo = 0
        var hi = sortedRanges.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = sortedRanges[mid]
            when {
                offset < r.first -> hi = mid - 1
                offset > r.last -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}

object NoteReferences {
    fun key(title: String) = title.trim().lowercase(Locale.ROOT)
    fun escape(text: String) = text.replace("\\", "\\\\").replace("|", "\\|").replace("]", "\\]").replace("[", "\\[")

    private fun unescape(text: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\' && index + 1 < text.length) index++
            result.append(text[index++])
        }
        return result.toString()
    }

    fun parse(markdown: String): List<WikiReference> {
        if (markdown.length < 4 || markdown.indexOf("[[") < 0 || markdown.indexOf("]]") < 0) return emptyList()
        val lines = MarkdownCodeScanner.scanLines(markdown)
        val excluded = MarkdownCodeScanner.excludedCodeRanges(markdown, lines)
        return parse(markdown, lines, excluded)
    }

    internal fun parse(
        markdown: String,
        lines: List<MarkdownCodeScanner.LineSlice>,
        excluded: List<IntRange>
    ): List<WikiReference> {
        if (markdown.length < 4 || markdown.indexOf("[[") < 0 || markdown.indexOf("]]") < 0) return emptyList()
        val result = mutableListOf<WikiReference>()

        for (line in lines) {
            if (line.inFence) continue
            val content = line.content
            if (content.indexOf("[[") < 0 || content.indexOf("]]") < 0) continue
            var index = 0
            while (index < content.length) {
                val absOffset = line.startOffset + index
                if (MarkdownCodeScanner.containsOffset(excluded, absOffset)) {
                    index++
                    continue
                }
                if (content[index] == '\\') {
                    index += 2
                    continue
                }
                val isEmbed = content.startsWith("![[", index)
                val openLen = when {
                    isEmbed -> 3
                    content.startsWith("[[", index) -> 2
                    else -> 0
                }
                if (openLen == 0) {
                    index++
                    continue
                }
                var end = index + openLen
                var pipe = -1
                while (end < content.length && !content.startsWith("]]", end)) {
                    if (content[end] == '\\') {
                        end += 2
                        continue
                    }
                    if (content[end] == '|' && pipe < 0) pipe = end
                    end++
                }
                if (end >= content.length - 1) {
                    index += openLen
                    continue
                }
                val raw = content.substring(index + openLen, end).trim()
                val target = unescape(content.substring(index + openLen, if (pipe < 0) end else pipe).trim())
                val label = if (pipe < 0) null else unescape(content.substring(pipe + 1, end).trim()).ifBlank { target }
                if (target.isNotBlank()) {
                    result.add(
                        WikiReference(
                            start = absOffset,
                            end = line.startOffset + end + 2,
                            raw = raw,
                            target = target,
                            label = label,
                            isEmbed = isEmbed
                        )
                    )
                }
                index = end + 2
            }
        }
        return result
    }

    fun bindings(json: String): Map<String, Long> {
        val data = JSONObject(json)
        return data.keys().asSequence().associateWith { data.getLong(it) }
    }

    fun encode(bindings: Map<String, Long>) = JSONObject(bindings).toString()

    fun boundReferences(markdown: String, notes: List<Note>, known: Map<String, Long>): Map<String, Long> {
        val headers = notes.map { n ->
            NoteLinkHeader(
                id = n.id,
                title = n.title,
                trashedAt = n.trashedAt,
                headMarkdown = if (n.markdown.length > 2048) n.markdown.substring(0, 2048) else n.markdown
            )
        }
        return boundReferencesFromHeaders(markdown, headers, known)
    }

    fun boundReferencesFromHeaders(
        markdown: String,
        headers: List<NoteLinkHeader>,
        known: Map<String, Long>
    ): Map<String, Long> {
        val available = headers.associateBy { it.id }
        val activeHeaders = headers.filter { it.trashedAt == null }
        val titles = activeHeaders.groupBy { key(it.title) }
        val aliases = activeHeaders.flatMap { h ->
            if (!mayHaveFrontmatter(h.headMarkdown)) emptyList()
            else NoteFrontmatter.properties(h.headMarkdown).aliases.map { key(it) to h }
        }.groupBy({ it.first }, { it.second })

        return parse(markdown).mapNotNull { ref ->
            val fullKey = key(ref.target)
            val baseKey = key(ref.noteTarget)
            val id = known[ref.raw]?.takeIf { it in available }
                ?: titles[fullKey]?.singleOrNull()?.id
                ?: aliases[fullKey]?.singleOrNull()?.id
                ?: if (baseKey.isNotBlank() && baseKey != fullKey) {
                    titles[baseKey]?.singleOrNull()?.id ?: aliases[baseKey]?.singleOrNull()?.id
                } else null
            id?.let { ref.raw to it }
        }.toMap()
    }

    /** A label disambiguates two different targets with the same visible title. */
    fun reference(
        title: String,
        targetId: Long,
        occupied: Map<String, Long>,
        preferredLabel: String? = null,
        fragment: String? = null
    ): String {
        val suffix = fragment?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("#")) it else "#$it"
        }.orEmpty()
        val destination = escape(title) + suffix
        var label = preferredLabel
        var number = 2
        while (true) {
            val raw = destination + (label?.let { "|${escape(it)}" } ?: "")
            if (occupied[raw] == null || occupied[raw] == targetId) return raw
            label = "$title (${number++})"
        }
    }
}

class NoteLinkIndex(notes: List<Note>, links: List<NoteLink>) {
    private val byId = notes.associateBy { it.id }
    private val byTitle = notes.groupBy { NoteReferences.key(it.title) }
    private val byAlias = notes.flatMap { note ->
        if (!mayHaveFrontmatter(note.markdown)) emptyList()
        else NoteFrontmatter.properties(note.markdown).aliases.map { NoteReferences.key(it) to note }
    }.groupBy({ it.first }, { it.second })
    private val bindings = links.groupBy { it.sourceId }.mapValues { (_, refs) -> refs.associate { it.reference to it.targetId } }

    fun bound(sourceId: Long?): Map<String, Long> = bindings[sourceId].orEmpty()

    fun candidates(sourceId: Long?, ref: WikiReference, choices: Map<String, Long> = emptyMap()): List<Note> {
        val known = choices[ref.raw] ?: bindings[sourceId]?.get(ref.raw)
        if (known != null) return listOfNotNull(byId[known])
        val fullKey = NoteReferences.key(ref.target)
        val baseKey = NoteReferences.key(ref.noteTarget)
        val matches = byTitle[fullKey]
            ?: byAlias[fullKey]
            ?: (if (baseKey.isNotBlank() && baseKey != fullKey) byTitle[baseKey] ?: byAlias[baseKey] else null)
            ?: emptyList()
        return matches.filter { it.trashedAt == null }.ifEmpty { matches.filter { it.trashedAt != null } }
    }

    fun target(sourceId: Long?, ref: WikiReference, choices: Map<String, Long> = emptyMap()): Note? =
        candidates(sourceId, ref, choices).singleOrNull()
}

data class BacklinkOccurrence(
    val source: Note,
    val reference: String,
    val snippet: String,
    val offset: Int
)

data class UnlinkedMention(
    val source: Note,
    val matchedTerm: String,
    val snippet: String,
    val start: Int,
    val end: Int
)

object NoteConnections {
    /**
     * Finds all incoming links to [targetNoteId] across [notes], including the surrounding snippet
     * of context where the link was authored.
     */
    fun backlinksWithContext(
        targetNoteId: Long,
        notes: List<Note>,
        links: List<NoteLink>
    ): List<BacklinkOccurrence> {
        val incomingSources = links.filter { it.targetId == targetNoteId }.map { it.sourceId }.toSet()
        val index = NoteLinkIndex(notes, links)
        val result = mutableListOf<BacklinkOccurrence>()

        for (source in notes) {
            if (source.id == targetNoteId || source.trashedAt != null || source.id !in incomingSources) continue
            val refs = NoteReferences.parse(source.markdown)
            for (ref in refs) {
                val target = index.target(source.id, ref)
                if (target?.id == targetNoteId) {
                    val snippet = extractSnippet(source.markdown, ref.start, ref.end)
                    result.add(BacklinkOccurrence(source, ref.raw, snippet, ref.start))
                }
            }
        }
        return result.sortedBy { it.source.title.lowercase(Locale.ROOT) }
    }

    /**
     * Discovers mentions of the [targetNote]'s title or aliases in other notes that have not yet
     * been linked with [[...]].
     */
    fun unlinkedMentions(
        targetNote: Note,
        allNotes: List<Note>,
        links: List<NoteLink>
    ): List<UnlinkedMention> {
        if (targetNote.trashedAt != null) return emptyList()
        val props = if (mayHaveFrontmatter(targetNote.markdown)) NoteFrontmatter.properties(targetNote.markdown) else NoteProperties()
        val terms = (listOf(targetNote.title) + props.aliases)
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
        if (terms.isEmpty()) return emptyList()

        val termPatterns = terms.map { term ->
            term to Regex("""(?i)(?<![\p{L}\p{N}_])${Regex.escape(term)}(?![\p{L}\p{N}_])""")
        }
        val result = mutableListOf<UnlinkedMention>()

        for (source in allNotes) {
            if (source.id == targetNote.id || source.trashedAt != null) continue
            val body = source.markdown
            if (body.length < 3) continue
            var anyTermPresent = false
            for (i in termPatterns.indices) {
                if (body.contains(termPatterns[i].first, ignoreCase = true)) {
                    anyTermPresent = true
                    break
                }
            }
            if (!anyTermPresent) continue

            val lines = MarkdownCodeScanner.scanLines(body)
            val frontmatterEnd = MarkdownCodeScanner.frontmatterEndOffset(body)
            val codeRanges = MarkdownCodeScanner.excludedCodeRanges(body, lines)
            val existingRefs = NoteReferences.parse(body, lines, codeRanges)
            val rawExcluded = ArrayList<IntRange>(existingRefs.size + codeRanges.size)
            for (r in existingRefs) if (r.start < r.end) rawExcluded.add(r.start until r.end)
            for (r in codeRanges) if (!r.isEmpty()) rawExcluded.add(r)
            rawExcluded.sortBy { it.first }
            val excludedRanges = mergeSortedRanges(rawExcluded)

            for ((term, pattern) in termPatterns) {
                if (!body.contains(term, ignoreCase = true)) continue
                for (match in pattern.findAll(body)) {
                    val range = match.range
                    if (range.first < frontmatterEnd) continue
                    val isInsideExcluded = MarkdownCodeScanner.containsOffset(excludedRanges, range.first) ||
                        MarkdownCodeScanner.containsOffset(excludedRanges, range.last)
                    if (!isInsideExcluded) {
                        val snippet = extractSnippet(body, range.first, range.last + 1)
                        result.add(
                            UnlinkedMention(
                                source = source,
                                matchedTerm = match.value,
                                snippet = snippet,
                                start = range.first,
                                end = range.last + 1
                            )
                        )
                    }
                }
            }
        }
        return result.distinctBy { "${it.source.id}:${it.start}" }
            .sortedBy { it.source.title.lowercase(Locale.ROOT) }
    }

    private fun extractSnippet(text: String, start: Int, end: Int, contextRadius: Int = 45): String {
        val snippetStart = (start - contextRadius).coerceAtLeast(0)
        val snippetEnd = (end + contextRadius).coerceAtMost(text.length)
        val prefix = if (snippetStart > 0) "…" else ""
        val suffix = if (snippetEnd < text.length) "…" else ""
        val raw = text.substring(snippetStart, snippetEnd).replace('\n', ' ').trim()
        return "$prefix$raw$suffix"
    }
}

private val inlineTagRegex = Regex(
    """(?<![\p{L}\p{N}_/&#])#([\p{L}\p{N}_][\p{L}\p{N}_-]*(?:/[\p{L}\p{N}_][\p{L}\p{N}_-]*)*)"""
)
private val hexColorTokenRegex = Regex("""^(?:[0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$""")
private val digitsOnlyTokenRegex = Regex("""^\d+$""")
private val markdownLinkUrlRegex = Regex("""\]\(([^)\r\n]+)\)""")

/**
 * Extracts normalized lowercase tags from YAML frontmatter and Markdown body,
 * ignoring `#tags` inside code fences, inline code spans, wikilinks, and hex color codes.
 * Preserves full hierarchical paths such as `matematica/algebra/linear`.
 */
internal fun extractNoteTagsFromParts(frontmatterTags: List<String>, body: String): List<String> {
    if (body.isBlank() || body.indexOf('#') < 0) {
        return frontmatterTags.filter { it.isNotBlank() }.distinct().sorted()
    }

    val codeRanges = MarkdownCodeScanner.excludedCodeRanges(body)
    val linkRanges = NoteReferences.parse(body).map { it.start until it.end }
    val urlRanges = if (body.indexOf("](") >= 0) {
        markdownLinkUrlRegex.findAll(body).map { it.groups[1]!!.range }.toList()
    } else emptyList()
    val rawExcluded = ArrayList<IntRange>(codeRanges.size + linkRanges.size + urlRanges.size)
    for (r in codeRanges) if (!r.isEmpty()) rawExcluded.add(r)
    for (r in linkRanges) if (!r.isEmpty()) rawExcluded.add(r)
    for (r in urlRanges) if (!r.isEmpty()) rawExcluded.add(r)
    rawExcluded.sortBy { it.first }
    val excluded = mergeSortedRanges(rawExcluded)

    val inlineTags = mutableListOf<String>()
    for (match in inlineTagRegex.findAll(body)) {
        val start = match.range.first
        if (MarkdownCodeScanner.containsOffset(excluded, start)) continue
        val tag = match.groupValues[1].lowercase(Locale.ROOT)
        if (hexColorTokenRegex.matches(tag) || digitsOnlyTokenRegex.matches(tag)) continue
        inlineTags.add(tag)
    }

    return (frontmatterTags + inlineTags)
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
}

/**
 * Extracts normalized lowercase tags from YAML frontmatter and Markdown body,
 * ignoring `#tags` inside code fences, inline code spans, wikilinks, and hex color codes.
 * Preserves full hierarchical paths such as `matematica/algebra/linear`.
 */
fun extractNoteTags(markdown: String): List<String> {
    val (props, body) = fastPropertiesAndBody(markdown)
    return extractNoteTagsFromParts(props.tags, body)
}

private val headingRegex = Regex("""^ {0,3}(#{1,6})[ \t]+(.+?)\s*$""")
private val trailingHeadingHashesRegex = Regex("""[ \t]+#+[ \t]*$""")
private val blockIdLineRegex = Regex("""^(.*?)(?:\s|^)\^([A-Za-z0-9_-]+)\s*$""")
private val listItemStartRegex = Regex("""^ {0,3}(?:[-*+]|\d+[.)])\s+.*""")
private val headingEmbedAliasedRegex = Regex("""!\[\[([^\]|]+)\|([^\]]+)\]\]""")
private val headingEmbedRegex = Regex("""!\[\[([^\]]+)\]\]""")
private val headingWikiAliasedRegex = Regex("""\[\[([^\]|]+)\|([^\]]+)\]\]""")
private val headingWikiRegex = Regex("""\[\[([^\]]+)\]\]""")
private val headingMdLinkRegex = Regex("""\[([^\]]+)\]\([^)]*\)""")
private val combiningMarksRegex = Regex("""\p{InCombiningDiacriticalMarks}+""")
private val headingAnchorStripRegex = Regex("""[^\p{L}\p{N}\s_-]""")
private val whitespaceRunRegex = Regex("""\s+""")
private val dashRunRegex = Regex("""-+""")

private fun stripInlineMarkdownForHeading(text: String): String {
    var result = text
    if (result.indexOf("[[") >= 0) {
        result = result
            .replace(headingEmbedAliasedRegex, "$2")
            .replace(headingEmbedRegex, "$1")
            .replace(headingWikiAliasedRegex, "$2")
            .replace(headingWikiRegex, "$1")
    }
    if (result.indexOf("](") >= 0) {
        result = result.replace(headingMdLinkRegex, "$1")
    }
    return result
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace("*", "")
        .trim()
}

private fun foldAccents(text: String): String {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
    return normalized.replace(combiningMarksRegex, "")
}

private fun headingAnchor(title: String): String {
    val plain = stripInlineMarkdownForHeading(title).lowercase(Locale.ROOT)
    return plain
        .replace(headingAnchorStripRegex, "")
        .trim()
        .replace(whitespaceRunRegex, "-")
        .replace(dashRunRegex, "-")
        .trim('-')
}

/**
 * Extracts all ATX Markdown headings (`H1`..`H6`) with their 0-based [MarkdownHeading.lineIndex]
 * and [MarkdownHeading.charOffset] in the original [markdown], ignoring YAML frontmatter and code fences.
 */
fun extractOutline(markdown: String): List<MarkdownHeading> {
    if (markdown.isEmpty() || markdown.indexOf('#') < 0) return emptyList()
    val frontmatterEnd = MarkdownCodeScanner.frontmatterEndOffset(markdown)
    val lines = MarkdownCodeScanner.scanLines(markdown)
    return extractOutlineFromScanned(lines, frontmatterEnd)
}

private fun extractOutlineFromScanned(
    lines: List<MarkdownCodeScanner.LineSlice>,
    frontmatterEnd: Int
): List<MarkdownHeading> {
    val headings = mutableListOf<MarkdownHeading>()

    for (line in lines) {
        if (line.startOffset < frontmatterEnd || line.inFence) continue
        val content = line.content
        var prefix = 0
        while (prefix < content.length && prefix < 3 && content[prefix] == ' ') prefix++
        if (prefix >= content.length || content[prefix] != '#') continue
        val match = headingRegex.matchEntire(content) ?: continue
        val level = match.groupValues[1].length
        val rawTitle = match.groupValues[2].replace(trailingHeadingHashesRegex, "").trim()
        if (rawTitle.isEmpty()) continue
        headings.add(
            MarkdownHeading(
                level = level,
                title = rawTitle,
                anchor = headingAnchor(rawTitle),
                lineIndex = line.index,
                charOffset = line.startOffset
            )
        )
    }
    return headings
}

/**
 * Extracts either the Markdown section under a matching heading (stopping before the next heading
 * of the same or higher level) or the paragraph/list item/block tagged with `^blockId`.
 */
fun extractSectionMarkdown(markdown: String, headingOrBlock: String): String? {
    val target = headingOrBlock.trim()
    if (target.isEmpty() || markdown.isEmpty()) return null

    val lines = MarkdownCodeScanner.scanLines(markdown)
    val frontmatterEnd = MarkdownCodeScanner.frontmatterEndOffset(markdown)

    if (target.startsWith("^")) {
        val wantedId = target.removePrefix("^").trim()
        return if (wantedId.isEmpty()) null else extractBlockById(lines, frontmatterEnd, wantedId)
    }

    val headings = if (markdown.indexOf('#') < 0) emptyList() else extractOutlineFromScanned(lines, frontmatterEnd)
    val targetAnchor = headingAnchor(target)
    val targetFolded = foldAccents(target).lowercase(Locale.ROOT)

    val matchedHeading = headings.firstOrNull { h ->
        h.title.equals(target, ignoreCase = true) ||
            stripInlineMarkdownForHeading(h.title).equals(target, ignoreCase = true)
    } ?: headings.firstOrNull { h ->
        targetAnchor.isNotEmpty() && (
            h.anchor == targetAnchor ||
                foldAccents(h.anchor) == foldAccents(targetAnchor) ||
                foldAccents(stripInlineMarkdownForHeading(h.title)).lowercase(Locale.ROOT) == targetFolded
            )
    }

    if (matchedHeading != null) {
        val nextHeading = headings.firstOrNull {
            it.lineIndex > matchedHeading.lineIndex && it.level <= matchedHeading.level
        }
        val endLineIdx = nextHeading?.lineIndex ?: lines.size
        val sectionLines = lines.subList(matchedHeading.lineIndex, endLineIdx).map { it.content }
        return sectionLines.joinToString("\n").trimEnd()
    }

    return extractBlockById(lines, frontmatterEnd, target)
}

private fun extractBlockById(
    lines: List<MarkdownCodeScanner.LineSlice>,
    frontmatterEnd: Int,
    wantedId: String
): String? {
    for (i in lines.indices) {
        val line = lines[i]
        if (line.startOffset < frontmatterEnd || line.inFence) continue
        val match = blockIdLineRegex.matchEntire(line.content) ?: continue
        if (!match.groupValues[2].equals(wantedId, ignoreCase = true)) continue

        val beforeId = match.groupValues[1].trimEnd()
        if (beforeId.isBlank()) {
            // Standalone ^blockId line referring to the preceding block
            var endIdx = i - 1
            while (endIdx >= 0 && lines[endIdx].startOffset >= frontmatterEnd && lines[endIdx].content.isBlank()) {
                endIdx--
            }
            if (endIdx < 0 || lines[endIdx].startOffset < frontmatterEnd) return null
            if (lines[endIdx].inFence) {
                var startIdx = endIdx
                while (startIdx > 0 && lines[startIdx - 1].inFence) startIdx--
                return lines.subList(startIdx, endIdx + 1).joinToString("\n") { it.content }.trim()
            }
            var startIdx = endIdx
            while (startIdx > 0 && lines[startIdx - 1].startOffset >= frontmatterEnd) {
                val prev = lines[startIdx - 1]
                if (prev.inFence || prev.content.isBlank() || headingRegex.matches(prev.content)) break
                startIdx--
            }
            return lines.subList(startIdx, endIdx + 1).joinToString("\n") { it.content }.trim()
        }

        // Inline ^blockId at the end of a list item (first line or indented continuation), blockquote, or paragraph
        val ownsListItem = listItemStartRegex.matches(beforeId) || (
            beforeId.firstOrNull()?.isWhitespace() == true && run {
                var probe = i - 1
                while (probe >= 0 && lines[probe].startOffset >= frontmatterEnd) {
                    val prev = lines[probe]
                    if (prev.inFence || prev.content.isBlank() || headingRegex.matches(prev.content) || prev.content.trimStart().startsWith(">")) {
                        probe = -1
                        break
                    }
                    if (listItemStartRegex.matches(prev.content)) break
                    if (!prev.content.first().isWhitespace()) {
                        probe = -1
                        break
                    }
                    probe--
                }
                probe >= 0 && lines[probe].startOffset >= frontmatterEnd && listItemStartRegex.matches(lines[probe].content)
            }
        )
        if (ownsListItem) {
            var startIdx = i
            while (startIdx > 0 && !listItemStartRegex.matches(lines[startIdx].content)) {
                startIdx--
            }
            var endIdx = i
            while (endIdx + 1 < lines.size) {
                val next = lines[endIdx + 1]
                if (next.inFence || next.content.isBlank() || listItemStartRegex.matches(next.content) || headingRegex.matches(next.content)) {
                    break
                }
                if (!next.content.first().isWhitespace()) break
                endIdx++
            }
            return (startIdx..endIdx).joinToString("\n") { idx ->
                if (idx == i) beforeId else lines[idx].content
            }.trim()
        }

        if (beforeId.trimStart().startsWith(">")) {
            var startIdx = i
            while (startIdx > 0 && lines[startIdx - 1].startOffset >= frontmatterEnd &&
                !lines[startIdx - 1].inFence && lines[startIdx - 1].content.trimStart().startsWith(">")
            ) {
                startIdx--
            }
            var endIdx = i
            while (endIdx + 1 < lines.size && !lines[endIdx + 1].inFence &&
                lines[endIdx + 1].content.trimStart().startsWith(">")
            ) {
                endIdx++
            }
            return (startIdx..endIdx).joinToString("\n") { idx ->
                if (idx == i) beforeId else lines[idx].content
            }.trim()
        }

        var startIdx = i
        while (startIdx > 0 && lines[startIdx - 1].startOffset >= frontmatterEnd) {
            val prev = lines[startIdx - 1]
            if (prev.inFence || prev.content.isBlank() || headingRegex.matches(prev.content) ||
                listItemStartRegex.matches(prev.content) || prev.content.trimStart().startsWith(">")
            ) break
            startIdx--
        }
        var endIdx = i
        while (endIdx + 1 < lines.size) {
            val next = lines[endIdx + 1]
            if (next.inFence || next.content.isBlank() || headingRegex.matches(next.content) ||
                listItemStartRegex.matches(next.content) || next.content.trimStart().startsWith(">")
            ) break
            endIdx++
        }
        return (startIdx..endIdx).joinToString("\n") { idx ->
            if (idx == i) beforeId else lines[idx].content
        }.trim()
    }
    return null
}

private class MutableFolderNode(
    val name: String,
    val fullPath: String
) {
    val directNoteIds = mutableListOf<Long>()
    val children = linkedMapOf<String, MutableFolderNode>()

    fun allIds(): Set<Long> {
        val set = LinkedHashSet<Long>(directNoteIds)
        children.values.forEach { set.addAll(it.allIds()) }
        return set
    }

    fun toImmutable(): FolderTreeNode {
        val sortedChildren = children.values
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { it.toImmutable() }
        return FolderTreeNode(
            name = name,
            fullPath = fullPath,
            noteIds = directNoteIds.distinct(),
            totalNotes = allIds().size,
            children = sortedChildren
        )
    }
}

/**
 * Builds a hierarchical folder tree from each active note's `folder` frontmatter property
 * (supporting nested slash-separated paths like `"Direito/Constitucional"`).
 */
fun buildFolderTree(notes: List<Note>): List<FolderTreeNode> {
    val roots = linkedMapOf<String, MutableFolderNode>()
    for (note in notes) {
        if (note.trashedAt != null) continue
        if (!mayHaveFrontmatter(note.markdown)) continue
        val folder = NoteFrontmatter.properties(note.markdown).folder?.trim().orEmpty()
        if (folder.isEmpty()) continue
        val segments = folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) continue

        var currentMap = roots
        var currentNode: MutableFolderNode? = null
        val pathSoFar = mutableListOf<String>()
        for (segment in segments) {
            pathSoFar.add(segment)
            val key = segment.lowercase(Locale.ROOT)
            val node = currentMap.getOrPut(key) {
                MutableFolderNode(name = segment, fullPath = pathSoFar.joinToString("/"))
            }
            currentNode = node
            currentMap = node.children
        }
        currentNode?.directNoteIds?.add(note.id)
    }

    return roots.values
        .sortedBy { it.name.lowercase(Locale.ROOT) }
        .map { it.toImmutable() }
}

private class MutableTagNode(
    val segment: String,
    val fullTag: String
) {
    val directNoteIds = mutableListOf<Long>()
    val children = linkedMapOf<String, MutableTagNode>()

    fun allIds(): Set<Long> {
        val set = LinkedHashSet<Long>(directNoteIds)
        children.values.forEach { set.addAll(it.allIds()) }
        return set
    }

    fun toImmutable(): TagTreeNode {
        val sortedChildren = children.values
            .sortedBy { it.segment.lowercase(Locale.ROOT) }
            .map { it.toImmutable() }
        return TagTreeNode(
            segment = segment,
            fullTag = fullTag,
            noteIds = directNoteIds.distinct(),
            totalNotes = allIds().size,
            children = sortedChildren
        )
    }
}

/**
 * Builds a hierarchical `#tag` tree from active notes, indexing nested tags like
 * `"matematica/algebra/linear"` under each parent segment and computing deduplicated descendant counts.
 */
fun buildTagTree(notes: List<Note>): List<TagTreeNode> {
    val roots = linkedMapOf<String, MutableTagNode>()
    for (note in notes) {
        if (note.trashedAt != null) continue
        val tags = extractNoteTags(note.markdown)
        for (tag in tags) {
            val segments = tag.split('/').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue

            var currentMap = roots
            var currentNode: MutableTagNode? = null
            val pathSoFar = mutableListOf<String>()
            for (segment in segments) {
                pathSoFar.add(segment)
                val full = pathSoFar.joinToString("/")
                val node = currentMap.getOrPut(segment) {
                    MutableTagNode(segment = segment, fullTag = full)
                }
                currentNode = node
                currentMap = node.children
            }
            currentNode?.directNoteIds?.add(note.id)
        }
    }

    return roots.values
        .sortedBy { it.segment.lowercase(Locale.ROOT) }
        .map { it.toImmutable() }
}

object ObsidianSearchEngine {

    private sealed interface SearchPredicate {
        val negated: Boolean

        data class Tag(val tagPrefix: String, override val negated: Boolean) : SearchPredicate
        data class Path(val pathQuery: String, override val negated: Boolean) : SearchPredicate
        data class File(val titleQuery: String, override val negated: Boolean) : SearchPredicate
        data class RegexPattern(val regex: Regex?, val fallbackLiteral: String, override val negated: Boolean) : SearchPredicate
        data class Phrase(val phrase: String, override val negated: Boolean) : SearchPredicate
        data class Word(val word: String, override val negated: Boolean) : SearchPredicate
    }

    private data class NoteSearchIndex(
        val note: Note,
        val props: NoteProperties,
        val tags: List<String>,
        val folderLower: String,
        val titleLower: String,
        val aliasesLower: List<String>
    )

    fun search(notes: List<Note>, rawQuery: String): List<Note> {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return notes

        val branches = parseBranches(trimmed)
        if (branches.isEmpty()) return notes

        val indexed = notes.map { note ->
            val (props, body) = fastPropertiesAndBody(note.markdown)
            NoteSearchIndex(
                note = note,
                props = props,
                tags = extractNoteTagsFromParts(props.tags, body),
                folderLower = props.folder?.trim()?.trim('/')?.lowercase(Locale.ROOT).orEmpty(),
                titleLower = note.title.lowercase(Locale.ROOT),
                aliasesLower = props.aliases.map { it.lowercase(Locale.ROOT) }
            )
        }

        val scored = mutableListOf<Pair<NoteSearchIndex, Int>>()
        for (entry in indexed) {
            var bestScore = -1
            for (branch in branches) {
                if (branch.isEmpty()) continue
                var matchesAll = true
                var score = 0
                for (pred in branch) {
                    val matchScore = evaluatePredicate(entry, pred)
                    val matched = matchScore > 0
                    if (pred.negated) {
                        if (matched) {
                            matchesAll = false
                            break
                        }
                    } else {
                        if (!matched) {
                            matchesAll = false
                            break
                        }
                        score += matchScore
                    }
                }
                if (matchesAll && score > bestScore) {
                    bestScore = score
                }
            }
            if (bestScore >= 0) {
                scored.add(entry to bestScore)
            }
        }

        // Preserve input order when all scores tie (e.g. operator-only queries), otherwise rank by score.
        return scored
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<NoteSearchIndex, Int>>> { it.value.second }.thenBy { it.index })
            .map { it.value.first.note }
    }

    private fun evaluatePredicate(entry: NoteSearchIndex, pred: SearchPredicate): Int {
        return when (pred) {
            is SearchPredicate.Tag -> {
                val q = pred.tagPrefix
                if (q.isEmpty()) {
                    if (entry.tags.isNotEmpty()) 1 else 0
                } else {
                    val exact = entry.tags.any { it == q }
                    val descendant = entry.tags.any { it.startsWith("$q/") }
                    when {
                        exact -> 20
                        descendant -> 15
                        else -> 0
                    }
                }
            }
            is SearchPredicate.Path -> {
                val q = pred.pathQuery
                if (q.isEmpty()) {
                    if (entry.folderLower.isNotEmpty()) 1 else 0
                } else {
                    when {
                        entry.folderLower == q -> 25
                        entry.folderLower.startsWith("$q/") -> 20
                        entry.folderLower.contains(q) -> 12
                        else -> 0
                    }
                }
            }
            is SearchPredicate.File -> {
                val q = pred.titleQuery
                if (q.isEmpty()) 1
                else when {
                    entry.titleLower == q -> 40
                    entry.titleLower.contains(q) -> 30
                    entry.aliasesLower.any { it.contains(q) } -> 20
                    else -> 0
                }
            }
            is SearchPredicate.RegexPattern -> {
                val r = pred.regex
                if (r != null) {
                    when {
                        safeContainsMatch(r, entry.note.title) -> 30
                        entry.props.aliases.any { safeContainsMatch(r, it) } -> 20
                        safeContainsMatch(r, entry.note.markdown) -> 10
                        else -> 0
                    }
                } else {
                    matchTextScore(entry, pred.fallbackLiteral)
                }
            }
            is SearchPredicate.Phrase -> matchTextScore(entry, pred.phrase)
            is SearchPredicate.Word -> matchTextScore(entry, pred.word)
        }
    }

    private fun matchTextScore(entry: NoteSearchIndex, needleLower: String): Int {
        if (needleLower.isEmpty()) return 1
        return when {
            entry.titleLower == needleLower -> 50
            entry.titleLower.contains(needleLower) -> 35
            entry.aliasesLower.any { it.contains(needleLower) } -> 25
            entry.folderLower.contains(needleLower) || entry.tags.any { it.contains(needleLower) } -> 15
            entry.note.markdown.contains(needleLower, ignoreCase = true) -> 10
            else -> 0
        }
    }

    private fun parseBranches(query: String): List<List<SearchPredicate>> {
        val rawTokens = tokenize(query)
        val branches = mutableListOf<MutableList<SearchPredicate>>(mutableListOf())
        for (token in rawTokens) {
            if (token == "OR" && branches.last().isNotEmpty()) {
                branches.add(mutableListOf())
            } else {
                parseToken(token)?.let { branches.last().add(it) }
            }
        }
        return branches.filter { it.isNotEmpty() }
    }

    private fun tokenize(query: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < query.length) {
            while (i < query.length && query[i].isWhitespace()) i++
            if (i >= query.length) break

            val sb = StringBuilder()
            if (query[i] == '-') {
                sb.append('-')
                i++
                if (i >= query.length || query[i].isWhitespace()) {
                    tokens.add("-")
                    continue
                }
            }

            if (i < query.length && query[i] == '/') {
                // Scan regex token /pattern/flags
                sb.append('/')
                var j = i + 1
                var closed = false
                while (j < query.length) {
                    val ch = query[j]
                    sb.append(ch)
                    if (ch == '\\' && j + 1 < query.length) {
                        sb.append(query[j + 1])
                        j += 2
                        continue
                    }
                    if (ch == '/') {
                        closed = true
                        j++
                        while (j < query.length && query[j].isLetter()) {
                            sb.append(query[j])
                            j++
                        }
                        break
                    }
                    j++
                }
                if (closed) {
                    tokens.add(sb.toString())
                    i = j
                    continue
                }
                // Unclosed '/' -> fall through as normal token
                sb.setLength(if (sb.startsWith("-")) 1 else 0)
            }

            var inQuotes = false
            while (i < query.length) {
                val ch = query[i]
                if (ch == '\\' && i + 1 < query.length && inQuotes) {
                    sb.append(ch).append(query[i + 1])
                    i += 2
                    continue
                }
                if (ch == '"') {
                    inQuotes = !inQuotes
                    sb.append(ch)
                    i++
                    continue
                }
                if (!inQuotes && ch.isWhitespace()) break
                sb.append(ch)
                i++
            }
            tokens.add(sb.toString())
        }
        return tokens
    }

    private fun parseToken(rawToken: String): SearchPredicate? {
        if (rawToken.isEmpty()) return null
        val negated = rawToken.startsWith("-") && rawToken.length > 1
        val token = if (negated) rawToken.substring(1) else rawToken

        // Quoted exact phrase: "..."
        if (token.startsWith("\"") && token.endsWith("\"") && token.length >= 2) {
            val phrase = unescapeQuotes(token.substring(1, token.length - 1)).lowercase(Locale.ROOT)
            return if (phrase.isEmpty()) null else SearchPredicate.Phrase(phrase, negated)
        }

        // Regex literal: /pattern/flags (with safety checks against catastrophic backtracking)
        if (token.startsWith("/")) {
            val lastSlash = token.lastIndexOf('/')
            if (lastSlash > 0) {
                val pattern = token.substring(1, lastSlash)
                val flags = token.substring(lastSlash + 1).lowercase(Locale.ROOT)
                val options = mutableSetOf(RegexOption.MULTILINE)
                if ('c' !in flags) options.add(RegexOption.IGNORE_CASE)
                if ('s' in flags) options.add(RegexOption.DOT_MATCHES_ALL)
                val isUnsafePattern = pattern.length > 160 || isCatastrophicRegex(pattern)
                val compiled = if (isUnsafePattern) null else runCatching { Regex(pattern, options) }.getOrNull()
                return SearchPredicate.RegexPattern(compiled, pattern.lowercase(Locale.ROOT), negated)
            }
        }

        val colonIdx = token.indexOf(':')
        if (colonIdx > 0) {
            val op = token.substring(0, colonIdx).lowercase(Locale.ROOT)
            val rawArg = token.substring(colonIdx + 1)
            val cleanArg = rawArg.removeSurrounding("\"").trim()
            when (op) {
                "tag" -> {
                    val tagVal = cleanArg.removePrefix("#").trim().lowercase(Locale.ROOT)
                    return SearchPredicate.Tag(tagVal, negated)
                }
                "path", "folder" -> {
                    val pathVal = cleanArg.trim('/').lowercase(Locale.ROOT)
                    return SearchPredicate.Path(pathVal, negated)
                }
                "file", "title" -> {
                    return SearchPredicate.File(cleanArg.lowercase(Locale.ROOT), negated)
                }
            }
        }

        return SearchPredicate.Word(token.lowercase(Locale.ROOT), negated)
    }

    private fun unescapeQuotes(text: String): String = text.replace("\\\"", "\"").replace("\\\\", "\\")

    private val nestedQuantifierRegex = Regex("""\([^()]*[+*][^()]*\)\s*[+*?]""")

    private fun isCatastrophicRegex(pattern: String): Boolean =
        nestedQuantifierRegex.containsMatchIn(pattern)

    private class BoundedCharSequence(
        private val base: CharSequence,
        private val maxOps: Int = 50_000
    ) : CharSequence {
        private var ops = 0
        override val length: Int get() = base.length
        override fun get(index: Int): Char {
            if (++ops > maxOps) throw IllegalStateException("Regex step budget exceeded")
            return base[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            BoundedCharSequence(base.subSequence(startIndex, endIndex), maxOps)
        override fun toString(): String = base.toString()
    }

    private fun safeContainsMatch(regex: Regex, input: String): Boolean {
        val capped = if (input.length > 32_768) input.substring(0, 32_768) else input
        return runCatching { regex.containsMatchIn(BoundedCharSequence(capped)) }.getOrDefault(false)
    }
}

object DataviewQueryEngine {

    private val dqlKeywordRegex = Regex(
        """^(?:LIST|TABLE|TASK|CALENDAR|FROM|WHERE|SORT|LIMIT)\b""",
        RegexOption.IGNORE_CASE
    )
    private val whereComparisonRegex = Regex(
        """^(.+?)\s*(!=|>=|<=|=|>|<|\bcontains\b)\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val containsFuncRegex = Regex(
        """^(!?)\s*contains\s*\(\s*([^,]+?)\s*,\s*(.+?)\s*\)$""",
        RegexOption.IGNORE_CASE
    )
    private val inOpRegex = Regex(
        """^(.+?)\s+in\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val inlineSubClauseRegex = Regex("""\b(FROM|WHERE|SORT|LIMIT)\b""", RegexOption.IGNORE_CASE)
    private val sortWhitespaceRegex = Regex("""\s+""")

    private sealed interface FromAtom {
        val negated: Boolean

        data class Tag(val tag: String, override val negated: Boolean) : FromAtom
        data class Folder(val folder: String, override val negated: Boolean) : FromAtom
        data class LinkTo(val noteTitle: String, override val negated: Boolean) : FromAtom
    }

    private data class SortSpec(val field: String, val descending: Boolean)

    fun execute(
        queryBlock: String,
        notes: List<Note>,
        tasks: List<StudyTask> = emptyList()
    ): DataviewResult {
        val activeNotes = notes.filter { it.trashedAt == null }
        val nonEmptyLines = queryBlock.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }

        if (nonEmptyLines.isEmpty()) {
            return DataviewResult(mode = "NOTES", notes = emptyList())
        }

        val isDql = nonEmptyLines.any { dqlKeywordRegex.containsMatchIn(it) }
        if (!isDql) {
            val matched = ObsidianSearchEngine.search(activeNotes, nonEmptyLines.joinToString(" "))
            return DataviewResult(mode = "NOTES", notes = matched)
        }

        var mode = "NOTES"
        val fromClauses = mutableListOf<String>()
        val whereClauses = mutableListOf<String>()
        var sortSpec: SortSpec? = null
        var limit: Int? = null

        for (line in nonEmptyLines) {
            val upper = line.uppercase(Locale.ROOT)
            when {
                upper == "TASK" || upper.startsWith("TASK ") -> {
                    mode = "TASKS"
                    val rest = line.drop(4).trim()
                    if (rest.isNotEmpty()) {
                        dispatchInlineSubClauses(rest, fromClauses, whereClauses, { sortSpec = it }, { limit = it })
                    }
                }
                upper == "LIST" || upper.startsWith("LIST ") -> {
                    mode = "NOTES"
                    val rest = line.drop(4).trim()
                    if (rest.isNotEmpty() && dqlKeywordRegex.containsMatchIn(rest)) {
                        dispatchInlineSubClauses(rest, fromClauses, whereClauses, { sortSpec = it }, { limit = it })
                    }
                }
                upper.startsWith("TABLE ") || upper == "TABLE" -> {
                    mode = "NOTES"
                    val rest = line.drop(5).trim()
                    if (rest.isNotEmpty() && inlineSubClauseRegex.containsMatchIn(rest)) {
                        dispatchInlineSubClauses(rest, fromClauses, whereClauses, { sortSpec = it }, { limit = it })
                    }
                }
                upper.startsWith("FROM ") -> {
                    fromClauses.add(line.drop(5).trim())
                }
                upper.startsWith("WHERE ") -> {
                    whereClauses.add(line.drop(6).trim())
                }
                upper.startsWith("SORT ") -> {
                    sortSpec = parseSortSpec(line.drop(5).trim())
                }
                upper.startsWith("LIMIT ") -> {
                    val parsedLimit = line.drop(6).trim().toIntOrNull()
                    if (parsedLimit == null) {
                        return DataviewResult(mode = mode, error = "LIMIT inválido: '${line.drop(6).trim()}'")
                    }
                    limit = parsedLimit.coerceAtLeast(0)
                }
            }
        }

        val parsedCache = HashMap<Long, Triple<NoteProperties, List<String>, List<WikiReference>>>(activeNotes.size * 2 + 1)
        fun parsedForNote(note: Note): Triple<NoteProperties, List<String>, List<WikiReference>> =
            parsedCache.getOrPut(note.id) {
                val (props, body) = fastPropertiesAndBody(note.markdown)
                Triple(props, extractNoteTagsFromParts(props.tags, body), NoteReferences.parse(note.markdown))
            }

        val filteredNotes = if (fromClauses.isEmpty()) {
            activeNotes
        } else {
            val parsedFrom = fromClauses.map { parseFromBranches(it) }
            activeNotes.filter { note ->
                val (props, tags, refs) = parsedForNote(note)
                parsedFrom.all { branches ->
                    matchesFromBranches(note, props, tags, refs, branches)
                }
            }
        }

        if (mode == "TASKS") {
            val candidateTasks = collectTasksForQuery(
                filteredNotes = filteredNotes,
                tasks = tasks,
                hasFrom = fromClauses.isNotEmpty(),
                fromClauses = fromClauses,
                tagsForNote = { n -> parsedForNote(n).second }
            )
            val notesById = activeNotes.associateBy { it.id }
            val filteredTasks = if (whereClauses.isEmpty()) {
                candidateTasks
            } else {
                candidateTasks.filter { task ->
                    val linkedNote = task.noteId?.let { notesById[it] }
                    whereClauses.all { clause -> evaluateTaskWhere(task, linkedNote, clause) }
                }
            }
            val sortedTasks = applyTaskSort(filteredTasks, notesById, sortSpec)
            val finalTasks = limit?.let { sortedTasks.take(it) } ?: sortedTasks
            return DataviewResult(mode = "TASKS", notes = filteredNotes, tasks = finalTasks)
        }

        val whereNotes = if (whereClauses.isEmpty()) {
            filteredNotes
        } else {
            filteredNotes.filter { note ->
                val (props, tags, _) = parsedForNote(note)
                whereClauses.all { clause -> evaluateNoteWhere(note, props, tags, clause) }
            }
        }

        val sortedNotes = applyNoteSort(whereNotes, sortSpec)
        val finalNotes = limit?.let { sortedNotes.take(it) } ?: sortedNotes
        return DataviewResult(mode = "NOTES", notes = finalNotes)
    }

    private fun dispatchInlineSubClauses(
        rest: String,
        fromClauses: MutableList<String>,
        whereClauses: MutableList<String>,
        onSort: (SortSpec?) -> Unit,
        onLimit: (Int) -> Unit
    ) {
        val tokens = inlineSubClauseRegex.findAll(rest).toList()
        for (i in tokens.indices) {
            val kw = tokens[i].value.uppercase(Locale.ROOT)
            val contentStart = tokens[i].range.last + 1
            val contentEnd = if (i + 1 < tokens.size) tokens[i + 1].range.first else rest.length
            val arg = rest.substring(contentStart, contentEnd).trim()
            if (arg.isEmpty()) continue
            when (kw) {
                "FROM" -> fromClauses.add(arg)
                "WHERE" -> whereClauses.add(arg)
                "SORT" -> onSort(parseSortSpec(arg))
                "LIMIT" -> arg.toIntOrNull()?.let { onLimit(it.coerceAtLeast(0)) }
            }
        }
    }

    private fun parseSortSpec(raw: String): SortSpec? {
        if (raw.isBlank()) return null
        val firstClause = raw.split(',').first().trim()
        val parts = firstClause.split(sortWhitespaceRegex).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val field = parts[0].trim()
        val desc = parts.getOrNull(1)?.equals("DESC", ignoreCase = true) == true
        return SortSpec(field, desc)
    }

    private fun parseFromBranches(clause: String): List<List<FromAtom>> {
        val orParts = splitLogicalOperator(clause, "OR")
        return orParts.mapNotNull { branch ->
            val rawTokens = tokenizeFromClause(branch)
                .filter { !it.equals("AND", ignoreCase = true) }
            val atoms = rawTokens.mapNotNull { parseFromAtom(it) }
            atoms.ifEmpty { null }
        }
    }

    private fun tokenizeFromClause(branch: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var quoteChar = '\u0000'
        var bracketDepth = 0
        var i = 0
        while (i < branch.length) {
            val ch = branch[i]
            if (inQuotes) {
                current.append(ch)
                if (ch == quoteChar && (i == 0 || branch[i - 1] != '\\')) inQuotes = false
                i++
                continue
            }
            if (ch == '"' || ch == '\'') {
                inQuotes = true
                quoteChar = ch
                current.append(ch)
                i++
                continue
            }
            if (ch == '[') {
                bracketDepth++
                current.append(ch)
                i++
                continue
            }
            if (ch == ']' && bracketDepth > 0) {
                bracketDepth--
                current.append(ch)
                i++
                continue
            }
            if (bracketDepth == 0 && ch.isWhitespace()) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
                i++
                continue
            }
            current.append(ch)
            i++
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun parseFromAtom(rawToken: String): FromAtom? {
        var token = rawToken.trim()
        if (token.isEmpty() || token == "\"\"") return null
        val negated = token.startsWith("-") || token.startsWith("!")
        if (negated) token = token.drop(1).trim()
        if (token.isEmpty()) return null

        return when {
            token.startsWith("#") -> {
                FromAtom.Tag(token.removePrefix("#").trim().lowercase(Locale.ROOT), negated)
            }
            token.startsWith("[[") && token.endsWith("]]") -> {
                val target = token.substring(2, token.length - 2).substringBefore('|').substringBefore('#').trim()
                if (target.isEmpty()) null else FromAtom.LinkTo(target.lowercase(Locale.ROOT), negated)
            }
            token.startsWith("path:", ignoreCase = true) || token.startsWith("folder:", ignoreCase = true) -> {
                val path = token.substringAfter(':').trim().removeSurrounding("\"").trim('/').lowercase(Locale.ROOT)
                FromAtom.Folder(path, negated)
            }
            else -> {
                val folder = token.removeSurrounding("\"").removeSurrounding("'").trim('/').lowercase(Locale.ROOT)
                if (folder.isEmpty()) null else FromAtom.Folder(folder, negated)
            }
        }
    }

    private fun matchesFromBranches(
        note: Note,
        props: NoteProperties,
        tags: List<String>,
        refs: List<WikiReference>,
        branches: List<List<FromAtom>>
    ): Boolean {
        if (branches.isEmpty()) return true
        return branches.any { andAtoms ->
            andAtoms.all { atom ->
                val rawMatch = when (atom) {
                    is FromAtom.Tag -> tags.any { it == atom.tag || it.startsWith("${atom.tag}/") }
                    is FromAtom.Folder -> {
                        val folder = props.folder?.trim()?.trim('/')?.lowercase(Locale.ROOT).orEmpty()
                        folder.isNotEmpty() && (folder == atom.folder || folder.startsWith("${atom.folder}/"))
                    }
                    is FromAtom.LinkTo -> refs.any { it.noteTarget.lowercase(Locale.ROOT) == atom.noteTitle }
                }
                if (atom.negated) !rawMatch else rawMatch
            }
        }
    }

    private fun evaluateNoteWhere(
        note: Note,
        props: NoteProperties,
        tags: List<String>,
        clause: String
    ): Boolean {
        return evaluateBooleanClause(clause) { atom ->
            evaluateNoteWhereAtom(note, props, tags, atom)
        }
    }

    private fun evaluateTaskWhere(
        task: StudyTask,
        linkedNote: Note?,
        clause: String
    ): Boolean {
        return evaluateBooleanClause(clause) { atom ->
            evaluateTaskWhereAtom(task, linkedNote, atom)
        }
    }

    private fun evaluateBooleanClause(clause: String, evalAtom: (String) -> Boolean): Boolean {
        val orParts = splitLogicalOperator(clause, "OR")
        return orParts.any { branch ->
            val andParts = splitLogicalOperator(branch, "AND")
            andParts.all { atom -> evalAtom(atom.trim()) }
        }
    }

    private fun splitLogicalOperator(expr: String, operator: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var quoteChar = '\u0000'
        var parenDepth = 0
        var i = 0
        while (i < expr.length) {
            val ch = expr[i]
            if (inQuotes) {
                current.append(ch)
                if (ch == quoteChar && (i == 0 || expr[i - 1] != '\\')) inQuotes = false
                i++
                continue
            }
            if (ch == '"' || ch == '\'') {
                inQuotes = true
                quoteChar = ch
                current.append(ch)
                i++
                continue
            }
            if (ch == '(') parenDepth++
            if (ch == ')' && parenDepth > 0) parenDepth--

            if (parenDepth == 0 && expr.regionMatches(i, operator, 0, operator.length, ignoreCase = true)) {
                val beforeOk = i == 0 || expr[i - 1].isWhitespace()
                val afterIdx = i + operator.length
                val afterOk = afterIdx >= expr.length || expr[afterIdx].isWhitespace()
                if (beforeOk && afterOk) {
                    result.add(current.toString())
                    current.clear()
                    i = afterIdx
                    continue
                }
            }
            current.append(ch)
            i++
        }
        result.add(current.toString())
        return result.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun evaluateNoteWhereAtom(
        note: Note,
        props: NoteProperties,
        tags: List<String>,
        atom: String
    ): Boolean {
        val trimmed = atom.trim().removeSurrounding("(", ")").trim()
        if (trimmed.isEmpty()) return true

        containsFuncRegex.matchEntire(trimmed)?.let { m ->
            val negated = m.groupValues[1] == "!"
            val field = m.groupValues[2].trim()
            val needle = cleanWhereLiteral(m.groupValues[3], isTagField(field))
            val fieldValues = resolveNoteFieldValues(note, props, tags, field)
            val hit = fieldValues.any { it.contains(needle, ignoreCase = true) }
            return if (negated) !hit else hit
        }

        inOpRegex.matchEntire(trimmed)?.let { m ->
            val field = m.groupValues[2].trim()
            val needle = cleanWhereLiteral(m.groupValues[1], isTagField(field))
            val fieldValues = resolveNoteFieldValues(note, props, tags, field)
            return fieldValues.any { it.equals(needle, ignoreCase = true) }
        }

        whereComparisonRegex.matchEntire(trimmed)?.let { m ->
            val field = m.groupValues[1].trim()
            val op = m.groupValues[2].trim().lowercase(Locale.ROOT)
            val rhsRaw = cleanWhereLiteral(m.groupValues[3], isTagField(field))
            val fieldValues = resolveNoteFieldValues(note, props, tags, field)
            return compareValues(fieldValues, op, rhsRaw)
        }

        val negated = trimmed.startsWith("!")
        val flagField = trimmed.removePrefix("!").trim()
        val values = resolveNoteFieldValues(note, props, tags, flagField)
        val truthy = values.any {
            it.isNotBlank() && !it.equals("false", ignoreCase = true) && it != "0" && it != "[]"
        }
        return if (negated) !truthy else truthy
    }

    private fun evaluateTaskWhereAtom(
        task: StudyTask,
        linkedNote: Note?,
        atom: String
    ): Boolean {
        val trimmed = atom.trim().removeSurrounding("(", ")").trim()
        if (trimmed.isEmpty()) return true

        containsFuncRegex.matchEntire(trimmed)?.let { m ->
            val negated = m.groupValues[1] == "!"
            val field = m.groupValues[2].trim()
            val needle = cleanWhereLiteral(m.groupValues[3], isTagField(field))
            val values = resolveTaskFieldValues(task, linkedNote, field)
            val hit = values.any { it.contains(needle, ignoreCase = true) }
            return if (negated) !hit else hit
        }

        whereComparisonRegex.matchEntire(trimmed)?.let { m ->
            val field = m.groupValues[1].trim()
            val op = m.groupValues[2].trim().lowercase(Locale.ROOT)
            val rhs = cleanWhereLiteral(m.groupValues[3], isTagField(field))
            val values = resolveTaskFieldValues(task, linkedNote, field)
            return compareValues(values, op, rhs)
        }

        val negated = trimmed.startsWith("!")
        val flagField = trimmed.removePrefix("!").trim()
        val values = resolveTaskFieldValues(task, linkedNote, flagField)
        val truthy = values.any {
            it.isNotBlank() && !it.equals("false", ignoreCase = true) && it != "0"
        }
        return if (negated) !truthy else truthy
    }

    private fun resolveNoteFieldValues(
        note: Note,
        props: NoteProperties,
        tags: List<String>,
        rawField: String
    ): List<String> {
        return when (rawField.lowercase(Locale.ROOT)) {
            "title", "name", "file.name" -> listOf(note.title)
            "folder", "pasta", "file.folder" -> listOfNotNull(props.folder?.takeIf { it.isNotBlank() })
            "tags", "tag", "file.tags", "file.etags" -> tags
            "aliases", "alias", "file.aliases" -> props.aliases
            "pinned" -> listOf(note.pinned.toString())
            "updated", "updatedat", "file.mtime", "mtime" -> listOf(note.updatedAt.toString())
            else -> {
                val customVal = props.custom.entries
                    .firstOrNull { it.key.equals(rawField, ignoreCase = true) }
                    ?.value
                    ?: return emptyList()
                if (NoteFrontmatter.inferPropertyType(customVal) == NotePropertyType.LIST) {
                    NoteFrontmatter.parseListProperty(customVal)
                } else {
                    listOf(customVal)
                }
            }
        }
    }

    private fun resolveTaskFieldValues(
        task: StudyTask,
        linkedNote: Note?,
        rawField: String
    ): List<String> {
        return when (rawField.lowercase(Locale.ROOT)) {
            "completed", "checked", "done" -> listOf(task.completed.toString())
            "text", "title" -> listOf(task.title)
            "priority" -> listOf(task.priority.toString())
            "tags", "tag" -> task.tags.split(',').map { it.trim().removePrefix("#").lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
            "due", "dueat" -> listOf(task.dueAt.toString())
            "file.name", "note" -> listOfNotNull(linkedNote?.title)
            else -> if (linkedNote != null) {
                val (props, body) = fastPropertiesAndBody(linkedNote.markdown)
                resolveNoteFieldValues(linkedNote, props, extractNoteTagsFromParts(props.tags, body), rawField)
            } else emptyList()
        }
    }

    private fun compareValues(fieldValues: List<String>, op: String, rhs: String): Boolean {
        if (op == "!=") {
            return fieldValues.none { it.equals(rhs, ignoreCase = true) }
        }
        if (fieldValues.isEmpty()) return false
        return when (op) {
            "=" -> fieldValues.any { it.equals(rhs, ignoreCase = true) }
            "contains" -> fieldValues.any { it.contains(rhs, ignoreCase = true) }
            ">", ">=", "<", "<=" -> {
                val rhsNum = rhs.toDoubleOrNull()
                fieldValues.any { lhs ->
                    val lhsNum = lhs.toDoubleOrNull()
                    val cmp = if (lhsNum != null && rhsNum != null) {
                        lhsNum.compareTo(rhsNum)
                    } else {
                        lhs.lowercase(Locale.ROOT).compareTo(rhs.lowercase(Locale.ROOT))
                    }
                    when (op) {
                        ">" -> cmp > 0
                        ">=" -> cmp >= 0
                        "<" -> cmp < 0
                        "<=" -> cmp <= 0
                        else -> false
                    }
                }
            }
            else -> false
        }
    }

    private fun isTagField(field: String): Boolean =
        field.lowercase(Locale.ROOT) in setOf("tags", "tag", "file.tags", "file.etags")

    private fun cleanWhereLiteral(raw: String, stripHashPrefix: Boolean = false): String {
        val trimmed = raw.trim()
        val unquoted = if (trimmed.length >= 2 &&
            ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith('\'') && trimmed.endsWith('\'')))
        ) {
            trimmed.substring(1, trimmed.length - 1)
        } else trimmed
        return if (stripHashPrefix) unquoted.removePrefix("#").trim() else unquoted.trim()
    }

    private fun collectTasksForQuery(
        filteredNotes: List<Note>,
        tasks: List<StudyTask>,
        hasFrom: Boolean,
        fromClauses: List<String>,
        tagsForNote: (Note) -> List<String> = { extractNoteTags(it.markdown) }
    ): List<StudyTask> {
        val noteIds = filteredNotes.map { it.id }.toSet()
        val fromTagFilter = if (hasFrom) {
            fromClauses.flatMap { parseFromBranches(it).flatten() }
                .filterIsInstance<FromAtom.Tag>()
                .filterNot { it.negated }
                .map { it.tag }
        } else emptyList()

        val selectedStudyTasks = tasks.filter { task ->
            when {
                !hasFrom -> true
                task.noteId != null -> task.noteId in noteIds
                fromTagFilter.isNotEmpty() -> {
                    val tTags = task.tags.split(',').map { it.trim().removePrefix("#").lowercase(Locale.ROOT) }
                    fromTagFilter.any { wanted -> tTags.any { it == wanted || it.startsWith("$wanted/") } }
                }
                else -> false
            }
        }

        val existingKeysByNote = selectedStudyTasks
            .filter { it.noteId != null }
            .groupBy { it.noteId!! }
            .mapValues { (_, list) -> list.map { it.title.trim().lowercase(Locale.ROOT) }.toSet() }

        val checklistTasks = mutableListOf<StudyTask>()
        for (note in filteredNotes) {
            val extracted = NoteChecklists.extract(note.markdown)
            if (extracted.isEmpty()) continue
            val covered = existingKeysByNote[note.id].orEmpty()
            val noteTagCsv = tagsForNote(note).joinToString(",")
            for (line in extracted) {
                if (line.text.trim().lowercase(Locale.ROOT) in covered) continue
                checklistTasks.add(
                    StudyTask(
                        id = 0L,
                        title = line.text,
                        dueAt = note.updatedAt,
                        completed = line.checked,
                        noteId = note.id,
                        details = "line:${line.lineIndex}",
                        hasDueDate = false,
                        tags = noteTagCsv
                    )
                )
            }
        }

        return selectedStudyTasks + checklistTasks
    }

    private fun applyNoteSort(notes: List<Note>, spec: SortSpec?): List<Note> {
        if (spec == null || notes.size <= 1) return notes
        val fieldLower = spec.field.lowercase(Locale.ROOT)
        if (fieldLower == "folder" || fieldLower == "pasta" || fieldLower == "file.folder") {
            val keys = notes.associate { note ->
                val folder = if (mayHaveFrontmatter(note.markdown)) {
                    NoteFrontmatter.properties(note.markdown).folder.orEmpty().lowercase(Locale.ROOT)
                } else ""
                note to folder
            }
            return notes.sortedWith(Comparator { a, b ->
                val cmp = keys.getValue(a).compareTo(keys.getValue(b))
                if (spec.descending) -cmp else cmp
            })
        }
        val customKeys = if (fieldLower != "updated" && fieldLower != "updatedat" &&
            fieldLower != "file.mtime" && fieldLower != "mtime" &&
            fieldLower != "title" && fieldLower != "name" && fieldLower != "file.name" &&
            fieldLower != "pinned"
        ) {
            notes.associateWith { note ->
                if (!mayHaveFrontmatter(note.markdown)) ""
                else NoteFrontmatter.properties(note.markdown).custom.entries
                    .firstOrNull { it.key.equals(spec.field, ignoreCase = true) }?.value.orEmpty()
            }
        } else null
        val comparator = Comparator<Note> { a, b ->
            val cmp = when (fieldLower) {
                "updated", "updatedat", "file.mtime", "mtime" -> a.updatedAt.compareTo(b.updatedAt)
                "title", "name", "file.name" -> a.title.lowercase(Locale.ROOT).compareTo(b.title.lowercase(Locale.ROOT))
                "pinned" -> a.pinned.compareTo(b.pinned)
                else -> compareSortValues(customKeys!!.getValue(a), customKeys.getValue(b))
            }
            if (spec.descending) -cmp else cmp
        }
        return notes.sortedWith(comparator)
    }

    private fun applyTaskSort(tasks: List<StudyTask>, notesById: Map<Long, Note>, spec: SortSpec?): List<StudyTask> {
        if (spec == null) return tasks
        val fieldLower = spec.field.lowercase(Locale.ROOT)
        val comparator = Comparator<StudyTask> { a, b ->
            val cmp = when (fieldLower) {
                "completed", "checked", "done" -> a.completed.compareTo(b.completed)
                "priority" -> a.priority.compareTo(b.priority)
                "due", "dueat" -> a.dueAt.compareTo(b.dueAt)
                "title", "text" -> a.title.lowercase(Locale.ROOT).compareTo(b.title.lowercase(Locale.ROOT))
                "updated", "updatedat", "file.mtime" -> {
                    val ua = a.noteId?.let { notesById[it]?.updatedAt } ?: a.dueAt
                    val ub = b.noteId?.let { notesById[it]?.updatedAt } ?: b.dueAt
                    ua.compareTo(ub)
                }
                else -> a.title.lowercase(Locale.ROOT).compareTo(b.title.lowercase(Locale.ROOT))
            }
            if (spec.descending) -cmp else cmp
        }
        return tasks.sortedWith(comparator)
    }

    private fun compareSortValues(a: String, b: String): Int {
        val na = a.toDoubleOrNull()
        val nb = b.toDoubleOrNull()
        return if (na != null && nb != null) na.compareTo(nb)
        else a.lowercase(Locale.ROOT).compareTo(b.lowercase(Locale.ROOT))
    }
}

object NoteEvergreenScheduler {
    data class EvergreenItem(
        val note: Note,
        val maturityScore: Int,
        val dueForRefinement: Boolean
    )

    fun queueForRefinement(
        notes: List<Note>,
        links: List<NoteLink>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<EvergreenItem> {
        val linkCountByNote = links.groupingBy { it.sourceId }.eachCount()
        return notes.asSequence()
            .filter { it.trashedAt == null }
            .map { note ->
                val words = note.markdown.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                val outLinks = linkCountByNote[note.id] ?: 0
                val maturity = ((words / 30).coerceAtMost(60) + outLinks * 8).coerceIn(0, 100)
                val intervalDays = when {
                    maturity >= 80 -> 21L
                    maturity >= 50 -> 7L
                    else -> 2L
                }
                val daysSinceUpdate = ((nowMillis - note.updatedAt) / 86_400_000L).coerceAtLeast(0L)
                EvergreenItem(note = note, maturityScore = maturity, dueForRefinement = daysSinceUpdate >= intervalDays)
            }
            .sortedWith(compareByDescending<EvergreenItem> { it.dueForRefinement }.thenBy { it.maturityScore })
            .toList()
    }
}

object PeriodicNotesEngine {
    fun dailyNoteTitle(date: java.time.LocalDate = java.time.LocalDate.now()): String =
        "Diário · %04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)

    fun weeklyNoteTitle(date: java.time.LocalDate = java.time.LocalDate.now()): String {
        val week = date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val year = date.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)
        return "Semana · %04d-W%02d".format(year, week)
    }

    fun monthlyNoteTitle(date: java.time.LocalDate = java.time.LocalDate.now()): String =
        "Mês · %04d-%02d".format(date.year, date.monthValue)

    /** Extrai tarefas pendentes `- [ ] ...` de uma nota anterior e monta o template da nova nota periódica. */
    fun buildDailyNoteWithRollover(
        date: java.time.LocalDate,
        previousNoteMarkdown: String?
    ): Pair<String, String> {
        val title = dailyNoteTitle(date)
        val pendingTasks = previousNoteMarkdown
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.startsWith("- [ ] ") }
            .orEmpty()
        val rolloverBlock = if (pendingTasks.isEmpty()) {
            "- [ ] Definir prioridade #1 do dia"
        } else {
            pendingTasks.joinToString("\n")
        }
        val markdown = buildString {
            appendLine("# $title")
            appendLine()
            appendLine("## Rollover & Prioridades")
            appendLine(rolloverBlock)
            appendLine()
            appendLine("## Notas Rápidas & Demonstrações")
            appendLine()
        }
        return title to markdown
    }
}
