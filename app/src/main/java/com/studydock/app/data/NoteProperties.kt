package com.studydock.app.data

import java.util.Locale

enum class NotePropertyType {
    TEXT,
    NUMBER,
    DATE,
    BOOL,
    LIST
}

/**
 * Structured metadata for notes, stored as YAML frontmatter (between --- delimiters)
 * at the top of the markdown text. 100% compatible with Obsidian and standard Markdown tools.
 */
data class NoteProperties(
    val aliases: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val folder: String? = null,
    val custom: Map<String, String> = emptyMap()
) {
    val isEmpty: Boolean
        get() = aliases.isEmpty() && tags.isEmpty() && folder.isNullOrBlank() && custom.isEmpty()
}

object NoteFrontmatter {
    private val frontmatterRegex = Regex("""(?s)^\s*---\r?\n(.*?)\r?\n---\r?\n?""")
    private val emptyFrontmatterRegex = Regex("""(?s)^\s*---\r?\n---\r?\n?""")
    private val tagSanitizeRegex = Regex("""[#\s,]""")
    private val numberRegex = Regex("""^-?\d+(?:\.\d+)?$""")
    private val isoDateRegex = Regex("""^\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?)?$""")
    private val brDateRegex = Regex("""^\d{2}/\d{2}/\d{4}$""")

    private val aliasKeys = setOf("aliases", "alias")
    private val tagKeys = setOf("tags", "tag")
    private val folderKeys = setOf("folder", "pasta", "category", "categoria")

    private sealed interface YamlNode {
        data class Trivia(val rawLine: String) : YamlNode
        data class Opaque(val rawLines: List<String>) : YamlNode
        data class Field(
            val rawKey: String,
            val canonicalKey: String,
            val isMultilineList: Boolean,
            val inlineComment: String = ""
        ) : YamlNode
    }

    private data class ParsedYaml(
        val properties: NoteProperties,
        val nodes: List<YamlNode>
    )

    /**
     * Splits a markdown document into its parsed [NoteProperties] and the remaining body text.
     */
    fun parse(markdown: String): Pair<NoteProperties, String> {
        val match = frontmatterRegex.find(markdown)
        if (match != null) {
            val rawYaml = match.groupValues[1]
            val body = markdown.substring(match.range.last + 1)
            return parseYamlWithStructure(rawYaml).properties to body
        }
        val emptyMatch = emptyFrontmatterRegex.find(markdown)
        if (emptyMatch != null) {
            val body = markdown.substring(emptyMatch.range.last + 1)
            return NoteProperties() to body
        }
        return NoteProperties() to markdown
    }

    /**
     * Extracts properties directly from a markdown document.
     */
    fun properties(markdown: String): NoteProperties = parse(markdown).first

    /**
     * Extracts the body text without the frontmatter block.
     */
    fun body(markdown: String): String = parse(markdown).second

    /**
     * Infers a UI property type from a serialized YAML property value.
     */
    fun inferPropertyType(value: String): NotePropertyType {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return NotePropertyType.TEXT
        if (trimmed.equals("true", ignoreCase = true) || trimmed.equals("false", ignoreCase = true)) {
            return NotePropertyType.BOOL
        }
        if (numberRegex.matches(trimmed)) return NotePropertyType.NUMBER
        if (isoDateRegex.matches(trimmed) || brDateRegex.matches(trimmed)) return NotePropertyType.DATE
        if ((trimmed.startsWith("[") && trimmed.endsWith("]")) ||
            trimmed.startsWith("- ") ||
            trimmed.contains("\n- ")
        ) {
            return NotePropertyType.LIST
        }
        return NotePropertyType.TEXT
    }

    /**
     * Parses a YAML inline/multiline list string into individual trimmed items.
     */
    fun parseListProperty(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.contains('\n')) {
            return trimmed.lines()
                .map { it.trim().removePrefix("-").trim() }
                .map { unquoteYaml(it) }
                .filter { it.isNotEmpty() }
        }
        val items = mutableListOf<String>()
        parseListItems(trimmed, items)
        return items
    }

    /**
     * Formats a list of string values as a YAML inline list.
     */
    fun formatListProperty(items: List<String>): String {
        val clean = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return "[]"
        return "[${clean.joinToString(", ") { formatListItemScalar(it) }}]"
    }

    /**
     * Formats properties into a YAML frontmatter string (with --- delimiters and trailing newline).
     */
    fun format(properties: NoteProperties): String {
        if (properties.isEmpty) return ""
        val builder = StringBuilder()
        builder.append("---\n")
        if (properties.aliases.isNotEmpty()) {
            builder.append("aliases: ").append(formatListProperty(properties.aliases)).append("\n")
        }
        if (properties.tags.isNotEmpty()) {
            builder.append("tags: ").append(formatListProperty(properties.tags)).append("\n")
        }
        if (!properties.folder.isNullOrBlank()) {
            builder.append("folder: ").append(formatScalarForYaml(properties.folder.trim())).append("\n")
        }
        properties.custom.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.append(key.trim()).append(": ").append(formatCustomValue(value)).append("\n")
            }
        }
        builder.append("---\n\n")
        return builder.toString()
    }

    /**
     * Replaces, updates in-place (preserving comments, key order, and unmodeled YAML blocks),
     * or prepends frontmatter to the given document text.
     */
    fun apply(body: String, properties: NoteProperties): String {
        val cleanBody = body(body).trimStart('\r', '\n')
        if (properties.isEmpty) return cleanBody

        val match = frontmatterRegex.find(body) ?: return format(properties) + cleanBody
        val parsed = parseYamlWithStructure(match.groupValues[1])

        val emittedLines = mutableListOf<String>()
        var aliasesEmitted = false
        var tagsEmitted = false
        var folderEmitted = false
        val emittedCustomKeys = mutableSetOf<String>()

        val customByCanonical = LinkedHashMap<String, Pair<String, String>>()
        properties.custom.forEach { (k, v) ->
            val cleanKey = k.trim()
            if (cleanKey.isNotEmpty() && v.isNotBlank()) {
                customByCanonical[cleanKey.lowercase(Locale.ROOT)] = cleanKey to v
            }
        }

        for (node in parsed.nodes) {
            when (node) {
                is YamlNode.Trivia -> emittedLines.add(node.rawLine)
                is YamlNode.Opaque -> emittedLines.addAll(node.rawLines)
                is YamlNode.Field -> {
                    when (node.canonicalKey) {
                        in aliasKeys -> if (!aliasesEmitted && properties.aliases.isNotEmpty()) {
                            emitListField(emittedLines, node.rawKey, properties.aliases, node.isMultilineList, node.inlineComment)
                            aliasesEmitted = true
                        }
                        in tagKeys -> if (!tagsEmitted && properties.tags.isNotEmpty()) {
                            emitListField(emittedLines, node.rawKey, properties.tags, node.isMultilineList, node.inlineComment)
                            tagsEmitted = true
                        }
                        in folderKeys -> if (!folderEmitted && !properties.folder.isNullOrBlank()) {
                            emittedLines.add("${node.rawKey}: ${formatScalarForYaml(properties.folder.trim())}${node.inlineComment}")
                            folderEmitted = true
                        }
                        else -> {
                            val entry = customByCanonical[node.canonicalKey]
                            if (entry != null && node.canonicalKey !in emittedCustomKeys) {
                                val (keyName, value) = entry
                                val resolvedKey = if (keyName == node.canonicalKey) node.rawKey else keyName
                                if (node.isMultilineList && inferPropertyType(value) == NotePropertyType.LIST) {
                                    emitListField(emittedLines, resolvedKey, parseListProperty(value), multiline = true, node.inlineComment)
                                } else {
                                    emittedLines.add("$resolvedKey: ${formatCustomValue(value)}${node.inlineComment}")
                                }
                                emittedCustomKeys.add(node.canonicalKey)
                            }
                        }
                    }
                }
            }
        }

        if (!aliasesEmitted && properties.aliases.isNotEmpty()) {
            emittedLines.add("aliases: ${formatListProperty(properties.aliases)}")
        }
        if (!tagsEmitted && properties.tags.isNotEmpty()) {
            emittedLines.add("tags: ${formatListProperty(properties.tags)}")
        }
        if (!folderEmitted && !properties.folder.isNullOrBlank()) {
            emittedLines.add("folder: ${formatScalarForYaml(properties.folder.trim())}")
        }
        customByCanonical.forEach { (canonicalKey, pair) ->
            if (canonicalKey !in emittedCustomKeys) {
                emittedLines.add("${pair.first}: ${formatCustomValue(pair.second)}")
                emittedCustomKeys.add(canonicalKey)
            }
        }

        val hasAnyField = emittedLines.any { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("#")
        }
        if (!hasAnyField) return cleanBody

        return "---\n${emittedLines.joinToString("\n")}\n---\n\n$cleanBody"
    }

    /**
     * Applies [props] to [markdown] and shifts [cursorStart]..[cursorEnd] by the exact delta
     * of the frontmatter prefix so the editor cursor never jumps while editing properties.
     */
    fun applyPreservingCursor(
        markdown: String,
        props: NoteProperties,
        cursorStart: Int,
        cursorEnd: Int
    ): Triple<String, Int, Int> {
        val oldCleanBodyStart = cleanBodyStartIndex(markdown)
        val updated = apply(markdown, props)
        val newCleanBodyStart = cleanBodyStartIndex(updated)

        fun mapOffset(pos: Int): Int {
            val clamped = pos.coerceIn(0, markdown.length)
            return if (clamped >= oldCleanBodyStart) {
                (newCleanBodyStart + (clamped - oldCleanBodyStart)).coerceIn(0, updated.length)
            } else {
                clamped.coerceIn(0, newCleanBodyStart)
            }
        }

        val nextStart = mapOffset(cursorStart)
        val nextEnd = mapOffset(cursorEnd)
        return Triple(updated, nextStart, nextEnd)
    }

    private fun cleanBodyStartIndex(markdown: String): Int {
        val match = frontmatterRegex.find(markdown) ?: emptyFrontmatterRegex.find(markdown)
        val rawBodyStart = if (match != null) match.range.last + 1 else 0
        var idx = rawBodyStart
        while (idx < markdown.length && (markdown[idx] == '\r' || markdown[idx] == '\n')) {
            idx++
        }
        return idx
    }

    private fun emitListField(
        out: MutableList<String>,
        key: String,
        items: List<String>,
        multiline: Boolean,
        inlineComment: String
    ) {
        if (multiline) {
            out.add("$key:$inlineComment")
            items.forEach { item ->
                out.add("  - ${formatListItemScalar(item)}")
            }
        } else {
            out.add("$key: ${formatListProperty(items)}$inlineComment")
        }
    }

    private fun parseYamlWithStructure(yaml: String): ParsedYaml {
        val aliases = mutableListOf<String>()
        val tags = mutableListOf<String>()
        var folder: String? = null
        val custom = LinkedHashMap<String, String>()
        val nodes = mutableListOf<YamlNode>()

        val lines = yaml.lines().map { it.trimEnd('\r') }
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                nodes.add(YamlNode.Trivia(rawLine))
                index++
                continue
            }

            // Indented orphan line or non-key line -> preserve verbatim
            if (rawLine.first().isWhitespace()) {
                nodes.add(YamlNode.Opaque(listOf(rawLine)))
                index++
                continue
            }

            val colonIndex = rawLine.indexOf(':')
            if (colonIndex <= 0) {
                nodes.add(YamlNode.Opaque(listOf(rawLine)))
                index++
                continue
            }

            val rawKey = rawLine.substring(0, colonIndex).trim()
            val canonicalKey = rawKey.lowercase(Locale.ROOT)
            val afterColon = rawLine.substring(colonIndex + 1)
            val (rawValuePart, inlineComment) = splitInlineComment(afterColon)
            val value = rawValuePart.trim()

            // Collect any indented continuation lines belonging to this key
            val childLines = mutableListOf<String>()
            var nextIdx = index + 1
            while (nextIdx < lines.size) {
                val candidate = lines[nextIdx]
                val candidateTrimmed = candidate.trim()
                if (candidateTrimmed.isEmpty()) {
                    // Only include blank lines if followed by more indented lines
                    var peek = nextIdx + 1
                    while (peek < lines.size && lines[peek].trim().isEmpty()) peek++
                    if (peek < lines.size && (lines[peek].firstOrNull()?.isWhitespace() == true || lines[peek].trimStart().startsWith("-"))) {
                        childLines.add(candidate)
                        nextIdx++
                        continue
                    } else break
                }
                if (candidate.first().isWhitespace() || candidateTrimmed.startsWith("-")) {
                    childLines.add(candidate)
                    nextIdx++
                } else break
            }

            val isBlockScalar = value == "|" || value == ">" || value == "|-" || value == ">-"
            val nonBlankChildren = childLines.filter { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
            val hasNestedMapping = value.isEmpty() && nonBlankChildren.isNotEmpty() &&
                nonBlankChildren.any { !it.trimStart().startsWith("-") }
            val isMultilineList = value.isEmpty() && nonBlankChildren.isNotEmpty() &&
                nonBlankChildren.all { it.trimStart().startsWith("-") }

            if (isBlockScalar || hasNestedMapping || (value.isEmpty() && nonBlankChildren.isEmpty() && canonicalKey !in aliasKeys && canonicalKey !in tagKeys && canonicalKey !in folderKeys)) {
                nodes.add(YamlNode.Opaque(listOf(rawLine) + childLines))
                index = nextIdx
                continue
            }

            nodes.add(YamlNode.Field(rawKey, canonicalKey, isMultilineList, inlineComment))

            when (canonicalKey) {
                in aliasKeys -> {
                    if (isMultilineList) {
                        nonBlankChildren.forEach { child ->
                            val item = unquoteYaml(child.trim().removePrefix("-").trim())
                            if (item.isNotEmpty()) aliases.add(item)
                        }
                    } else if (value.isNotEmpty()) {
                        parseListItems(value, aliases)
                    }
                }
                in tagKeys -> {
                    if (isMultilineList) {
                        nonBlankChildren.forEach { child ->
                            val item = unquoteYaml(child.trim().removePrefix("-").trim())
                            if (item.isNotEmpty()) tags.add(item)
                        }
                    } else if (value.isNotEmpty()) {
                        parseListItems(value, tags)
                    }
                }
                in folderKeys -> {
                    folder = unquoteYaml(value).trim().ifBlank { null }
                }
                else -> {
                    if (isMultilineList) {
                        val listValues = nonBlankChildren.mapNotNull { child ->
                            unquoteYaml(child.trim().removePrefix("-").trim()).takeIf { it.isNotEmpty() }
                        }
                        if (listValues.isNotEmpty()) {
                            custom[rawKey] = formatListProperty(listValues)
                        }
                    } else {
                        val cleanValue = unquoteYaml(value).trim()
                        if (cleanValue.isNotEmpty()) {
                            custom[rawKey] = cleanValue
                        }
                    }
                }
            }

            index = nextIdx
        }

        val cleanTags = tags
            .map { it.replace(tagSanitizeRegex, "").lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
        val cleanAliases = aliases
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return ParsedYaml(
            properties = NoteProperties(cleanAliases, cleanTags, folder, custom),
            nodes = nodes
        )
    }

    private fun splitInlineComment(rawAfterColon: String): Pair<String, String> {
        var inSingle = false
        var inDouble = false
        var bracketDepth = 0
        var i = 0
        while (i < rawAfterColon.length) {
            val ch = rawAfterColon[i]
            when {
                ch == '\\' && inDouble && i + 1 < rawAfterColon.length -> i++
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch == '\'' && !inDouble -> inSingle = !inSingle
                !inSingle && !inDouble && ch == '[' -> bracketDepth++
                !inSingle && !inDouble && ch == ']' && bracketDepth > 0 -> bracketDepth--
                !inSingle && !inDouble && bracketDepth == 0 && ch == '#' -> {
                    if (i > 0 && rawAfterColon[i - 1].isWhitespace()) {
                        val commentStart = i - 1
                        var start = commentStart
                        while (start > 0 && rawAfterColon[start - 1].isWhitespace()) start--
                        return rawAfterColon.substring(0, start) to rawAfterColon.substring(start)
                    }
                }
            }
            i++
        }
        return rawAfterColon to ""
    }

    private fun unquoteYaml(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length >= 2) {
            if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
                return trimmed.substring(1, trimmed.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
            if (trimmed.startsWith('\'') && trimmed.endsWith('\'')) {
                return trimmed.substring(1, trimmed.length - 1).replace("''", "'")
            }
        }
        return trimmed
    }

    private fun formatCustomValue(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return formatListProperty(parseListProperty(trimmed))
        }
        return formatScalarForYaml(trimmed)
    }

    private fun formatScalarForYaml(scalar: String): String {
        if (scalar.isEmpty()) return "\"\""
        val needsQuotes = scalar.startsWith("#") ||
            scalar.startsWith("-") ||
            scalar.startsWith("[") ||
            scalar.startsWith("{") ||
            scalar.startsWith("*") ||
            scalar.startsWith("&") ||
            scalar.startsWith("!") ||
            scalar.startsWith("%") ||
            scalar.startsWith("@") ||
            scalar.startsWith("`") ||
            scalar.startsWith(">") ||
            scalar.startsWith("|") ||
            scalar.startsWith("\"") ||
            scalar.startsWith("'") ||
            scalar.contains(": ") ||
            scalar.contains(" #") ||
            scalar.endsWith(":") ||
            scalar.first().isWhitespace() ||
            scalar.last().isWhitespace()
        return if (needsQuotes) {
            "\"${scalar.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            scalar
        }
    }

    private fun formatListItemScalar(scalar: String): String {
        val trimmed = scalar.trim()
        val needsQuotes = trimmed.contains(",") || trimmed.contains("]") || trimmed.contains("[") ||
            trimmed.startsWith("#") || trimmed.contains(": ") || trimmed.contains(" #")
        return if (needsQuotes) {
            "\"${trimmed.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            trimmed
        }
    }

    private fun parseListItems(value: String, target: MutableList<String>) {
        val trimmed = value.trim()
        val content = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else trimmed

        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            when {
                ch == '\\' && inDouble && i + 1 < content.length -> {
                    current.append(ch).append(content[++i])
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    current.append(ch)
                }
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(ch)
                }
                ch == ',' && !inSingle && !inDouble -> {
                    parts.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        parts.add(current.toString())

        parts.forEach { part ->
            val clean = unquoteYaml(part).trim()
            if (clean.isNotEmpty()) target.add(clean)
        }
    }
}
