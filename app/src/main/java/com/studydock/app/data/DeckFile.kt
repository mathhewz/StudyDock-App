package com.studydock.app.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.util.Base64
import java.util.zip.ZipInputStream

/** Zero-dependency pure-Kotlin RFC 8259 JSON parser and serializer for JVM & Android. */
internal object MiniJson {
    private const val MAX_DEPTH = 128

    fun parse(raw: String): Any? = Parser(raw).parseDocument()

    fun parseObject(raw: String): LinkedHashMap<String, Any?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return LinkedHashMap()
        val parsed = parse(trimmed)
        @Suppress("UNCHECKED_CAST")
        return (parsed as? LinkedHashMap<String, Any?>)
            ?: throw IllegalArgumentException("JSON raiz deve ser um objeto.")
    }

    fun parseArray(raw: String): List<Any?> {
        val parsed = parse(raw)
        @Suppress("UNCHECKED_CAST")
        return (parsed as? List<Any?>)
            ?: throw IllegalArgumentException("JSON raiz deve ser um array.")
    }

    fun optString(obj: Map<String, Any?>, key: String, default: String = ""): String =
        obj[key]?.toString() ?: default

    fun optStringOrNull(obj: Map<String, Any?>, key: String): String? =
        obj[key]?.toString()

    fun getString(obj: Map<String, Any?>, key: String): String =
        obj[key]?.toString() ?: throw IllegalArgumentException("Campo JSON obrigatório ausente: '$key'.")

    @Suppress("UNCHECKED_CAST")
    fun optObject(obj: Map<String, Any?>, key: String): Map<String, Any?>? =
        obj[key] as? Map<String, Any?>

    fun getObject(obj: Map<String, Any?>, key: String): Map<String, Any?> =
        optObject(obj, key) ?: throw IllegalArgumentException("Objeto JSON obrigatório ausente: '$key'.")

    @Suppress("UNCHECKED_CAST")
    fun optArray(obj: Map<String, Any?>, key: String): List<Any?>? =
        obj[key] as? List<Any?>

    fun getArray(obj: Map<String, Any?>, key: String): List<Any?> =
        optArray(obj, key) ?: throw IllegalArgumentException("Lista JSON obrigatória ausente: '$key'.")

    fun optInt(obj: Map<String, Any?>, key: String, default: Int = 0): Int =
        optIntOrNull(obj, key) ?: default

    fun optIntOrNull(obj: Map<String, Any?>, key: String): Int? = when (val v = obj[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    fun optLong(obj: Map<String, Any?>, key: String, default: Long = 0L): Long = when (val v = obj[key]) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: default
        else -> default
    }

    fun optDouble(obj: Map<String, Any?>, key: String, default: Double = 0.0): Double = when (val v = obj[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: default
        else -> default
    }

    fun optBoolean(obj: Map<String, Any?>, key: String, default: Boolean = false): Boolean = when (val v = obj[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: default
        else -> default
    }

    fun stringify(value: Any?): String = buildString {
        writeValue(value, this)
    }

    private fun writeValue(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> {
                when (value) {
                    is Double -> sb.append(if (value.isFinite()) value.toString() else "null")
                    is Float -> sb.append(if (value.isFinite()) value.toString() else "null")
                    else -> sb.append(value.toString())
                }
            }
            is String -> writeEscapedString(value, sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (k == null) continue
                    if (!first) sb.append(',')
                    first = false
                    writeEscapedString(k.toString(), sb)
                    sb.append(':')
                    writeValue(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(item, sb)
                }
                sb.append(']')
            }
            is Array<*> -> {
                sb.append('[')
                value.forEachIndexed { idx, item ->
                    if (idx > 0) sb.append(',')
                    writeValue(item, sb)
                }
                sb.append(']')
            }
            else -> writeEscapedString(value.toString(), sb)
        }
    }

    private fun writeEscapedString(text: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            if (src.isNotEmpty() && src[0] == '\uFEFF') pos = 1
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (pos != src.length) {
                throw IllegalArgumentException("Conteúdo extra após fim do JSON na posição $pos.")
            }
            return value
        }

        private fun skipWhitespace() {
            while (pos < src.length) {
                val c = src[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("Profundidade máxima de JSON excedida.")
            skipWhitespace()
            if (pos >= src.length) throw IllegalArgumentException("JSON incompleto.")
            return when (val ch = src[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Caractere JSON inesperado '$ch' na posição $pos.")
            }
        }

        private fun parseObject(depth: Int): LinkedHashMap<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (pos >= src.length || src[pos] != '"') {
                    throw IllegalArgumentException("Chave string esperada no objeto JSON na posição $pos.")
                }
                val key = parseString()
                skipWhitespace()
                if (pos >= src.length || src[pos] != ':') {
                    throw IllegalArgumentException("':' esperado após chave JSON na posição $pos.")
                }
                pos++
                val value = parseValue(depth + 1)
                map[key] = value
                skipWhitespace()
                if (pos >= src.length) throw IllegalArgumentException("Objeto JSON não fechado.")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("',' ou '}' esperado na posição $pos.")
                }
            }
        }

        private fun parseArray(depth: Int): ArrayList<Any?> {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue(depth + 1))
                skipWhitespace()
                if (pos >= src.length) throw IllegalArgumentException("Array JSON não fechado.")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("',' ou ']' esperado na posição $pos.")
                }
            }
        }

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Escape JSON incompleto.")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("Escape unicode incompleto.")
                                val code = src.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Escape unicode inválido.")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Escape JSON inválido '\\$esc'.")
                        }
                    }
                    ch.code < 0x20 -> throw IllegalArgumentException("Caractere de controle inválido em string JSON.")
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("String JSON não fechada.")
        }

        private fun parseLiteral(literal: String, result: Any?): Any? {
            if (!src.startsWith(literal, pos)) {
                throw IllegalArgumentException("Literal JSON inválido na posição $pos.")
            }
            pos += literal.length
            return result
        }

        private fun parseNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            if (pos >= src.length || src[pos] !in '0'..'9') {
                throw IllegalArgumentException("Número JSON inválido na posição $start.")
            }
            if (src[pos] == '0') {
                pos++
                if (pos < src.length && src[pos] in '0'..'9') {
                    throw IllegalArgumentException("Zero à esquerda inválido em JSON na posição $start.")
                }
            } else {
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                if (pos >= src.length || src[pos] !in '0'..'9') {
                    throw IllegalArgumentException("Fração decimal JSON inválida na posição $start.")
                }
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos >= src.length || src[pos] !in '0'..'9') {
                    throw IllegalArgumentException("Expoente JSON inválido na posição $start.")
                }
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            if (!isFloat) {
                token.toLongOrNull()?.let { return it }
            }
            return token.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: throw IllegalArgumentException("Número JSON fora dos limites: $token")
        }
    }
}

data class DeckFile(
    val name: String,
    val notes: List<SharedCardNote>,
    val images: Map<String, ByteArray> = emptyMap(),
    val deckConfig: CardDeck? = null
) {
    fun encode(): String {
        val rows = ArrayList<Map<String, Any?>>(notes.size)
        notes.forEach { shared ->
            val note = shared.source
            val mergedTags = normalizeNoteTags(shared.tags.ifBlank { note.tags })
            val mergedTypeAnswer = shared.typeAnswer || note.typeAnswer
            val fieldsObj = runCatching { MiniJson.parseObject(note.fields) }.getOrDefault(LinkedHashMap())
            val row = LinkedHashMap<String, Any?>().apply {
                put("type", note.format)
                put("prompt", note.prompt)
                put("answer", note.answer)
                put("template", note.template)
                put("fields", fieldsObj)
                put("variants", shared.keys.toList())
                note.imageKey?.let { put("imageKey", it) }
                note.occlusion?.takeIf { it.isNotBlank() }?.let { put("occlusion", it) }
                if (mergedTags.isNotEmpty()) put("tags", mergedTags)
                if (mergedTypeAnswer) put("typeAnswer", true)
                if (shared.flag != 0) put("flag", shared.flag)
            }
            rows.add(row)
        }
        require(notes.mapNotNull { it.source.imageKey }.toSet() == images.keys) { "Imagem ausente no baralho." }
        val root = LinkedHashMap<String, Any?>().apply {
            put("format", V4_FORMAT)
            put("name", name)
            put("notes", rows)
        }
        if (images.isNotEmpty()) {
            require(images.values.sumOf { it.size.toLong() } <= MAX_IMAGE_BYTES) { "Baralho grande demais para compartilhar." }
            val assets = LinkedHashMap<String, Any?>(images.size)
            images.forEach { (key, bytes) -> assets[key] = Base64.getEncoder().encodeToString(bytes) }
            root["images"] = assets
        }
        deckConfig?.let { cfg ->
            val cfgMap = LinkedHashMap<String, Any?>().apply {
                put("schedulerMode", cfg.schedulerMode)
                put("learningSteps", cfg.learningSteps)
                put("relearningSteps", cfg.relearningSteps)
                put("graduatingInterval", cfg.graduatingInterval)
                put("easyInterval", cfg.easyInterval)
                put("desiredRetention", cfg.desiredRetention)
                put("burySiblings", cfg.burySiblings)
                put("leechThreshold", cfg.leechThreshold)
                put("leechSuspend", cfg.leechSuspend)
                put("dayStartHour", cfg.dayStartHour)
                put("isFiltered", cfg.isFiltered)
                put("filterQuery", cfg.filterQuery)
                cfg.newLimit?.let { put("newLimit", it) }
                cfg.reviewLimit?.let { put("reviewLimit", it) }
            }
            root["deckConfig"] = cfgMap
        }
        return MiniJson.stringify(root).also {
            require(it.length <= MAX_CHARS) { "Baralho grande demais para compartilhar." }
        }
    }

    companion object {
        private const val LEGACY_V1_FORMAT = "StudyDock.deck.v1"
        private const val FORMAT = "StudyDock.deck.v2"
        private const val MEDIA_FORMAT = "StudyDock.deck.v3"
        internal const val V4_FORMAT = "StudyDock.deck.v4"
        internal const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private const val MAX_CHARS = 16 * 1024 * 1024

        fun read(reader: Reader): DeckFile {
            val text = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(text.length + count <= MAX_CHARS) { "Arquivo grande demais." }
                text.append(buffer, 0, count)
            }
            val json = MiniJson.parseObject(text.toString())
            val version = MiniJson.optString(json, "format")
            require(
                version == V4_FORMAT ||
                    version == MEDIA_FORMAT ||
                    version == FORMAT ||
                    version == LEGACY_V1_FORMAT
            ) { "Escolha um baralho JSON do StudyDock. Para arquivos .apkg, use o importador de pacotes Anki." }
            val title = MiniJson.getString(json, "name").trim()
            require(title.isNotEmpty()) { "Baralho sem nome." }
            val legacy = version == LEGACY_V1_FORMAT
            val supportsMedia = version == MEDIA_FORMAT || version == V4_FORMAT
            val rows = MiniJson.getArray(json, if (legacy) "cards" else "notes")
            val notes = rows.map { rawRow ->
                @Suppress("UNCHECKED_CAST")
                val row = rawRow as? Map<String, Any?>
                    ?: throw IllegalArgumentException("Nota JSON inválida no baralho.")
                if (legacy) {
                    SharedCardNote(
                        source = CardAuthorNote(
                            format = "BASIC",
                            prompt = MiniJson.getString(row, "front"),
                            answer = MiniJson.getString(row, "back")
                        ),
                        keys = setOf("front")
                    )
                } else {
                    val keys = MiniJson.getArray(row, "variants")
                    val rowTags = normalizeNoteTags(MiniJson.optString(row, "tags", ""))
                    val rowTypeAnswer = MiniJson.optBoolean(row, "typeAnswer", false)
                    val rowFlag = MiniJson.optInt(row, "flag", 0).coerceIn(0, 7)
                    val rowOcclusion = MiniJson.optStringOrNull(row, "occlusion")?.takeIf { it.isNotBlank() }
                    val rowImageKey = if (supportsMedia) {
                        MiniJson.optStringOrNull(row, "imageKey")?.takeIf { it.isNotBlank() }
                    } else null
                    val fieldsJson = MiniJson.optObject(row, "fields")?.let { MiniJson.stringify(it) } ?: "{}"
                    val note = CardAuthorNote(
                        format = MiniJson.getString(row, "type"),
                        prompt = MiniJson.getString(row, "prompt"),
                        answer = MiniJson.getString(row, "answer"),
                        template = MiniJson.optString(row, "template"),
                        fields = fieldsJson,
                        imageKey = rowImageKey,
                        occlusion = rowOcclusion,
                        tags = rowTags,
                        typeAnswer = rowTypeAnswer
                    )
                    SharedCardNote(
                        source = note,
                        keys = keys.map { it?.toString().orEmpty() }.filter { it.isNotEmpty() }.toSet(),
                        tags = rowTags,
                        typeAnswer = rowTypeAnswer,
                        flag = rowFlag
                    )
                }
            }
            val expectedImageKeys = notes.mapNotNull { it.source.imageKey }.toSet()
            val images = if (version == MEDIA_FORMAT || (version == V4_FORMAT && (json.containsKey("images") || expectedImageKeys.isNotEmpty()))) {
                val assets = MiniJson.getObject(json, "images")
                require(expectedImageKeys == assets.keys) { "Lista de imagens inválida." }
                var size = 0L
                expectedImageKeys.associateWith { key ->
                    val encoded = MiniJson.getString(assets, key)
                    require(encoded.length <= (CardPictures.MAX_BYTES + 2) / 3 * 4) { "Imagem grande demais." }
                    Base64.getDecoder().decode(encoded).also {
                        size += it.size
                        require(size <= MAX_IMAGE_BYTES) { "Baralho grande demais para importar." }
                    }
                }
            } else {
                emptyMap()
            }
            val cfgObj = if (version == V4_FORMAT) MiniJson.optObject(json, "deckConfig") else null
            val deckConfig = cfgObj?.let { cfg ->
                CardDeck(
                    name = title,
                    newLimit = MiniJson.optIntOrNull(cfg, "newLimit"),
                    reviewLimit = MiniJson.optIntOrNull(cfg, "reviewLimit"),
                    schedulerMode = MiniJson.optString(cfg, "schedulerMode", "SM2").ifBlank { "SM2" },
                    learningSteps = MiniJson.optString(cfg, "learningSteps", "1 10"),
                    relearningSteps = MiniJson.optString(cfg, "relearningSteps", "10"),
                    graduatingInterval = MiniJson.optInt(cfg, "graduatingInterval", 1).coerceAtLeast(1),
                    easyInterval = MiniJson.optInt(cfg, "easyInterval", 4).coerceAtLeast(1),
                    desiredRetention = MiniJson.optDouble(cfg, "desiredRetention", 0.90).coerceIn(0.70, 0.99),
                    burySiblings = MiniJson.optBoolean(cfg, "burySiblings", true),
                    leechThreshold = MiniJson.optInt(cfg, "leechThreshold", 8).coerceAtLeast(1),
                    leechSuspend = MiniJson.optBoolean(cfg, "leechSuspend", false),
                    dayStartHour = MiniJson.optInt(cfg, "dayStartHour", 4).coerceIn(0, 23),
                    isFiltered = MiniJson.optBoolean(cfg, "isFiltered", false),
                    filterQuery = MiniJson.optString(cfg, "filterQuery", "")
                )
            }
            return DeckFile(title, notes, images, deckConfig)
        }
    }
}

private object AnkiHtmlCleaner {
    private val styleOrScriptRegex = Regex("""(?is)<(script|style)\b[^>]*>.*?</\1>""")
    private val blockBreakRegex = Regex("""(?i)<\s*(?:br\s*/?|/?(?:p|div|li|tr|h[1-6]|blockquote|hr)\b[^>]*)>""")
    private val imgSrcRegex = Regex("""(?i)<img\b[^>]*\bsrc\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))[^>]*>""")
    private val anyTagRegex = Regex("""<[^>]+>""")
    private val soundRegex = Regex("""\[sound:[^\]]+]""")
    private val multiNewlines = Regex("""\n{3,}""")

    fun firstImageName(html: String): String? {
        val m = imgSrcRegex.find(html) ?: return null
        val raw = m.groupValues[1].ifBlank { m.groupValues[2] }.ifBlank { m.groupValues[3] }.trim()
        return raw.substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    fun clean(raw: String, stripHtml: Boolean = true): String {
        val withoutSound = raw.replace(soundRegex, "")
        val plain = if (stripHtml) {
            withoutSound
                .replace(styleOrScriptRegex, "")
                .replace(blockBreakRegex, "\n")
                .replace(imgSrcRegex, "")
                .replace(anyTagRegex, "")
        } else {
            withoutSound
        }
        return decodeHtmlEntities(plain)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(multiNewlines, "\n\n")
            .trim()
    }

    private fun decodeHtmlEntities(input: String): String =
        input.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
}

/**
 * Pure-Kotlin RFC4180 CSV/TSV codec supporting Anki text export directives
 * (`#separator:`, `#html:`, `#tags column:`), automatic delimiter detection, and cloze notes.
 */
object CsvDeckCodec {
    private const val MAX_CSV_CHARS = 16 * 1024 * 1024

    fun importCsv(reader: Reader, deckName: String, delimiter: Char? = null): DeckFile {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        while (true) {
            val read = reader.read(buf)
            if (read < 0) break
            require(sb.length + read <= MAX_CSV_CHARS) { "Arquivo CSV grande demais." }
            sb.append(buf, 0, read)
        }
        var rawText = sb.toString()
        if (rawText.startsWith("\uFEFF")) rawText = rawText.substring(1)
        rawText = rawText.replace("\r\n", "\n").replace('\r', '\n')

        var detectedDelim: Char? = delimiter
        var htmlMode = true
        var tagsColumnOneBased: Int? = null

        val lines = rawText.lines()
        var bodyStartLine = 0
        while (bodyStartLine < lines.size) {
            val line = lines[bodyStartLine]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                bodyStartLine++
                continue
            }
            if (!trimmed.startsWith("#")) break
            val lower = trimmed.lowercase()
            when {
                lower.startsWith("#separator:") -> {
                    val spec = trimmed.substringAfter(":").trim().lowercase()
                    if (detectedDelim == null) {
                        detectedDelim = when (spec) {
                            "tab", "\\t" -> '\t'
                            "comma", "," -> ','
                            "semicolon", ";" -> ';'
                            "pipe", "|" -> '|'
                            "space" -> ' '
                            else -> spec.firstOrNull()
                        }
                    }
                }
                lower.startsWith("#html:") -> {
                    htmlMode = trimmed.substringAfter(":").trim().equals("true", ignoreCase = true)
                }
                lower.startsWith("#tags column:") -> {
                    tagsColumnOneBased = trimmed.substringAfter(":").trim().toIntOrNull()?.takeIf { it >= 1 }
                }
            }
            bodyStartLine++
        }

        val bodyText = lines.drop(bodyStartLine).joinToString("\n")
        if (bodyText.isBlank()) {
            return DeckFile(deckName.trim().ifBlank { "Importado CSV" }, emptyList())
        }

        val effectiveDelimiter = detectedDelim ?: detectDelimiter(lines.drop(bodyStartLine))
        val parsedRows = parseRfc4180(bodyText, effectiveDelimiter)
            .filter { row -> row.any { it.isNotBlank() } }
        if (parsedRows.isEmpty()) {
            return DeckFile(deckName.trim().ifBlank { "Importado CSV" }, emptyList())
        }

        val dataRows = if (parsedRows.size >= 2 && looksLikeHeaderRow(parsedRows[0])) {
            parsedRows.drop(1)
        } else {
            parsedRows
        }

        val tagColIdx = tagsColumnOneBased?.minus(1) ?: 2
        val notes = dataRows.mapNotNull { row ->
            val rawFront = row.getOrNull(0).orEmpty()
            val rawBack = row.getOrNull(1).orEmpty()
            val front = if (htmlMode) AnkiHtmlCleaner.clean(rawFront, stripHtml = true) else rawFront.trim()
            val back = if (htmlMode) AnkiHtmlCleaner.clean(rawBack, stripHtml = true) else rawBack.trim()
            if (front.isBlank()) return@mapNotNull null

            val rawTags = row.getOrNull(tagColIdx).orEmpty()
            val tags = normalizeNoteTags(rawTags)
            val clozes = findClozeMatches(front)
            if (clozes.isNotEmpty()) {
                val keys = clozes.map { "c${it.number}" }.toSet()
                val note = CardAuthorNote(format = "CLOZE", prompt = front, answer = back, tags = tags)
                if (note.variants().isEmpty()) return@mapNotNull null
                SharedCardNote(source = note, keys = keys, tags = tags)
            } else {
                if (back.isBlank()) return@mapNotNull null
                val note = CardAuthorNote(format = "BASIC", prompt = front, answer = back, tags = tags)
                SharedCardNote(source = note, keys = setOf("front"), tags = tags)
            }
        }
        return DeckFile(deckName.trim().ifBlank { "Importado CSV" }, notes)
    }

    fun exportCsv(cards: List<Flashcard>, separator: Char = '\t', includeTags: Boolean = true): String =
        buildString {
            cards.forEachIndexed { idx, card ->
                if (idx > 0) append('\n')
                append(escapeRfc4180(card.front, separator))
                append(separator)
                append(escapeRfc4180(card.back, separator))
                if (includeTags) {
                    append(separator)
                    append(escapeRfc4180(normalizeNoteTags(card.tags), separator))
                }
            }
        }

    private fun looksLikeHeaderRow(row: List<String>): Boolean {
        val c0 = row.getOrNull(0)?.trim()?.lowercase().orEmpty()
        val c1 = row.getOrNull(1)?.trim()?.lowercase().orEmpty()
        val frontNames = setOf("front", "frente", "pergunta", "question", "term", "termo")
        val backNames = setOf("back", "verso", "resposta", "answer", "definition", "definicao", "definição")
        return c0 in frontNames && c1 in backNames
    }

    private fun detectDelimiter(sampleLines: List<String>): Char {
        val candidates = listOf('\t', ';', ',')
        val nonEmpty = sampleLines.filter { it.isNotBlank() }.take(10)
        if (nonEmpty.isEmpty()) return '\t'
        return candidates.maxByOrNull { delim ->
            val counts = nonEmpty.map { countUnquoted(it, delim) }
            val linesWithHit = counts.count { it > 0 }
            val totalHits = counts.sum()
            linesWithHit * 1000 + totalHits
        }?.takeIf { delim -> nonEmpty.any { countUnquoted(it, delim) > 0 } } ?: '\t'
    }

    private fun countUnquoted(line: String, delimiter: Char): Int {
        var inQuotes = false
        var count = 0
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') i++
                else inQuotes = !inQuotes
            } else if (!inQuotes && ch == delimiter) {
                count++
            }
            i++
        }
        return count
    }

    private fun parseRfc4180(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var atFieldStart = true
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                        i++
                        continue
                    }
                } else {
                    field.append(ch)
                    i++
                }
            } else {
                when {
                    ch == '"' && atFieldStart -> {
                        inQuotes = true
                        atFieldStart = false
                        i++
                    }
                    ch == delimiter -> {
                        row.add(field.toString())
                        field.setLength(0)
                        atFieldStart = true
                        i++
                    }
                    ch == '\n' -> {
                        row.add(field.toString())
                        rows.add(row)
                        row = ArrayList()
                        field.setLength(0)
                        atFieldStart = true
                        i++
                    }
                    else -> {
                        field.append(ch)
                        atFieldStart = false
                        i++
                    }
                }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private fun escapeRfc4180(value: String, delimiter: Char): String {
        val needsQuote = value.contains(delimiter) || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuote) return value
        return buildString(value.length + 4) {
            append('"')
            for (ch in value) {
                if (ch == '"') append("\"\"") else append(ch)
            }
            append('"')
        }
    }
}

data class ImportedApkgCard(
    val front: String,
    val back: String,
    val tags: String,
    val flag: Int,
    val dueAt: Long,
    val intervalDays: Int,
    val easeFactor: Double,
    val repetitions: Int,
    val lapses: Int,
    val learningState: String?,
    val noteTypeKey: String,
    val occlusion: String = "",
    val typeAnswer: Boolean = false,
    val imageBytes: ByteArray? = null,
    val imageName: String? = null,
    val imageFile: File? = null
)

data class ImportedApkgDeck(val name: String, val cards: List<ImportedApkgCard>)

data class ApkgImportResult(
    val decks: List<ImportedApkgDeck>,
    val warnings: List<String> = emptyList()
)

data class ApkgNoteRow(
    val id: Long,
    val mid: Long,
    val flds: String,
    val tags: String,
    val guid: String = ""
)

data class ApkgCardRow(
    val id: Long,
    val nid: Long,
    val did: Long,
    val ord: Int,
    val type: Int,
    val queue: Int,
    val due: Long,
    val ivl: Int,
    val factor: Int,
    val reps: Int,
    val lapses: Int,
    val flags: Int
)

data class ApkgRawTables(
    val decksJson: String,
    val modelsJson: String,
    val notes: List<ApkgNoteRow>,
    val cards: List<ApkgCardRow>,
    val crtSeconds: Long = 0L
)

/**
 * Pure-Kotlin Anki `.apkg` (Zip + SQLite + JSON models + numbered media) reader and converter.
 */
object ApkgPackageCodec {
    private const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_SINGLE_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 160L * 1024 * 1024
    private val zstdMagic = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())
    private val templateConditional = Regex("""\{\{([#^])([^{}#\^/:\n]{1,80})\}\}([\s\S]*?)\{\{/\2\}\}""")
    private val templateToken = Regex("""\{\{(#|/|\^|type:|cloze:|hint:)?([^{}#\^/:\n]{1,80})\}\}""")
    private val frontSideBreakRegex = Regex("""(?is)\{\{FrontSide\}\}\s*(?:<hr\b[^>]*>\s*)?""")

    private data class AnkiTemplateDef(
        val name: String,
        val ord: Int,
        val qfmt: String,
        val afmt: String
    )

    private data class AnkiModelDef(
        val id: Long,
        val name: String,
        val isCloze: Boolean,
        val fieldNames: List<String>,
        val templates: List<AnkiTemplateDef>
    )

    fun readApkg(
        input: InputStream,
        cacheDir: File,
        keepMediaFilesOnDisk: Boolean = false,
        readSqlite: (File) -> ApkgRawTables
    ): ApkgImportResult {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        var totalBytes = 0L
        var entryCount = 0
        var anki21bFile: File? = null
        var anki21File: File? = null
        var anki2File: File? = null
        var hasAnki21b = false
        var anki21bZstd = false
        var mediaJsonBytes: ByteArray? = null
        val numberedMediaFiles = HashMap<String, File>()
        val warnings = ArrayList<String>()

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) { "Pacote .apkg excede o limite máximo de entradas." }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val safeName = entry.name.replace('\\', '/')
                if (safeName.startsWith("/") || safeName.split('/').any { it == ".." }) {
                    throw IllegalArgumentException("Entrada inválida detectada no arquivo .apkg.")
                }
                val baseName = safeName.substringAfterLast('/')
                when {
                    baseName == "collection.anki21b" -> {
                        hasAnki21b = true
                        val raw21b = readBoundedEntry(zip, totalBytes) { added -> totalBytes += added }
                        if (startsWithMagic(raw21b, zstdMagic)) {
                            anki21bZstd = true
                            val decompressed = tryDecompressZstd(raw21b)
                            if (decompressed != null && decompressed.size >= 16) {
                                val f = File(cacheDir, "collection_21b.sqlite")
                                f.writeBytes(decompressed)
                                anki21bFile = f
                            }
                        } else if (raw21b.size >= 16) {
                            val f = File(cacheDir, "collection_21b.sqlite")
                            f.writeBytes(raw21b)
                            anki21bFile = f
                        }
                    }
                    baseName == "collection.anki21" -> {
                        val f = File(cacheDir, "collection_21.sqlite")
                        writeBoundedEntryToFile(zip, f, totalBytes) { added -> totalBytes += added }
                        anki21File = f
                    }
                    baseName == "collection.anki2" -> {
                        val f = File(cacheDir, "collection_2.sqlite")
                        writeBoundedEntryToFile(zip, f, totalBytes) { added -> totalBytes += added }
                        anki2File = f
                    }
                    baseName == "media" -> {
                        mediaJsonBytes = readBoundedEntry(zip, totalBytes) { added -> totalBytes += added }
                    }
                    baseName.isNotEmpty() && baseName.all { it.isDigit() } -> {
                        val f = File(cacheDir, "media_$baseName.bin")
                        val written = writeBoundedEntryToFile(zip, f, totalBytes) { added -> totalBytes += added }
                        if (written in 1L..DeckFile.MAX_IMAGE_BYTES.toLong()) {
                            numberedMediaFiles[baseName] = f
                        } else {
                            f.delete()
                        }
                    }
                    else -> {
                        drainBoundedEntry(zip, totalBytes) { added -> totalBytes += added }
                    }
                }
                zip.closeEntry()
            }
        }

        if (hasAnki21b && anki21bFile == null) {
            warnings.add(
                if (anki21bZstd) {
                    "Detectado collection.anki21b compactado com zstd (magic 0x28 B5 2F FD). Dados legados serão usados quando disponíveis."
                } else {
                    "Detectado collection.anki21b no pacote; utilizando banco SQLite compatível quando disponível."
                }
            )
        }

        val chosenSqliteFile = anki21bFile ?: anki21File ?: anki2File
        if (chosenSqliteFile == null || !chosenSqliteFile.exists()) {
            if (hasAnki21b) {
                return ApkgImportResult(emptyList(), warnings)
            }
            throw IllegalArgumentException("Arquivo .apkg inválido: collection.anki21/collection.anki2 não encontrado.")
        }

        val mediaFilesByName = LinkedHashMap<String, File>()
        mediaJsonBytes?.let { rawMedia ->
            val effectiveMedia = if (startsWithMagic(rawMedia, zstdMagic)) {
                tryDecompressZstd(rawMedia)
            } else {
                rawMedia
            }
            if (effectiveMedia == null) {
                warnings.add("Tabela de mídia compactada com zstd; mídias podem não ser extraídas neste modo.")
            } else if (effectiveMedia.isNotEmpty() && effectiveMedia[0] == 0x0A.toByte()) {
                val pbMap = parseAnki21bMediaProtobuf(effectiveMedia)
                for ((idx, fileName) in pbMap) {
                    val assetFile = numberedMediaFiles[idx]
                    if (fileName.isNotEmpty() && assetFile != null && assetFile.exists()) {
                        val header = ByteArray(4)
                        val readHeader = runCatching {
                            assetFile.inputStream().use { it.read(header) }
                        }.getOrDefault(0)
                        if (readHeader == 4 && startsWithMagic(header, zstdMagic)) {
                            tryDecompressZstd(assetFile.readBytes())?.let { assetFile.writeBytes(it) }
                        }
                        mediaFilesByName[fileName] = assetFile
                    }
                }
            } else {
                runCatching {
                    val mediaObj = MiniJson.parseObject(effectiveMedia.toString(Charsets.UTF_8))
                    for ((idx, rawName) in mediaObj) {
                        val fileName = rawName?.toString()?.trim().orEmpty()
                        val assetFile = numberedMediaFiles[idx]
                        if (fileName.isNotEmpty() && assetFile != null && assetFile.exists()) {
                            mediaFilesByName[fileName] = assetFile
                        }
                    }
                }.onFailure {
                    warnings.add("Não foi possível ler o índice de mídia do pacote .apkg.")
                }
            }
        }

        val parsed = try {
            val rawTables = readSqlite(chosenSqliteFile)
            val mediaFilesLower = mediaFilesByName.entries.associate { it.key.lowercase() to (it.key to it.value) }
            if (keepMediaFilesOnDisk) {
                parseRawTablesWithMediaResolver(rawTables) { candidateName ->
                    val pair = mediaFilesByName[candidateName]?.let { candidateName to it }
                        ?: mediaFilesLower[candidateName.lowercase()]
                    pair?.let { (resolvedName, file) ->
                        if (file.exists() && file.length() > 0L) Triple(resolvedName, null, file) else null
                    }
                }
            } else {
                val loadedMediaCache = HashMap<String, ByteArray>()
                parseRawTablesWithMediaResolver(rawTables) { candidateName ->
                    val pair = mediaFilesByName[candidateName]?.let { candidateName to it }
                        ?: mediaFilesLower[candidateName.lowercase()]
                    pair?.let { (resolvedName, file) ->
                        val bytes = loadedMediaCache.getOrPut(resolvedName) {
                            runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
                        }
                        if (bytes.isNotEmpty()) Triple(resolvedName, bytes, null) else null
                    }
                }
            }
        } finally {
            anki21bFile?.delete()
            anki21File?.delete()
            anki2File?.delete()
            if (!keepMediaFilesOnDisk) {
                numberedMediaFiles.values.forEach { it.delete() }
            }
        }

        return ApkgImportResult(
            decks = parsed.decks,
            warnings = warnings + parsed.warnings
        )
    }

    fun parseRawTables(
        raw: ApkgRawTables,
        mediaByName: Map<String, ByteArray>,
        nowMillis: Long = System.currentTimeMillis()
    ): ApkgImportResult {
        val mediaLower = mediaByName.entries.associate { it.key.lowercase() to (it.key to it.value) }
        return parseRawTablesWithMediaResolver(raw, nowMillis) { name ->
            val found = mediaByName[name]?.let { name to it } ?: mediaLower[name.lowercase()]
            found?.let { (resolvedName, bytes) -> Triple(resolvedName, bytes, null) }
        }
    }

    private fun parseRawTablesWithMediaResolver(
        raw: ApkgRawTables,
        nowMillis: Long = System.currentTimeMillis(),
        resolveMedia: (String) -> Triple<String, ByteArray?, File?>?
    ): ApkgImportResult {
        val warnings = ArrayList<String>()
        val deckNamesById = parseDecksJson(raw.decksJson, warnings)
        val modelsById = parseModelsJson(raw.modelsJson, warnings)
        val seenGuids = HashSet<String>()
        val dedupedNotes = raw.notes.filter { n ->
            n.guid.isBlank() || seenGuids.add(n.guid)
        }
        val notesById = dedupedNotes.associateBy { it.id }
        val cardsByDeckName = LinkedHashMap<String, MutableList<ImportedApkgCard>>()
        var orphanCards = 0
        var skippedPlaceholderCards = 0

        for (cardRow in raw.cards) {
            val note = notesById[cardRow.nid]
            if (note == null) {
                orphanCards++
                continue
            }
            val model = modelsById[note.mid]
            val fieldValues = note.flds.split('\u001F')
            val fieldMap = LinkedHashMap<String, String>()
            if (model != null && model.fieldNames.isNotEmpty()) {
                model.fieldNames.forEachIndexed { idx, fname ->
                    fieldMap[fname] = fieldValues.getOrElse(idx) { "" }
                }
            } else {
                fieldValues.forEachIndexed { idx, value ->
                    fieldMap["Field${idx + 1}"] = value
                }
            }

            val isClozeModel = model?.isCloze == true ||
                (model == null && fieldValues.firstOrNull()?.let { findClozeMatches(it).isNotEmpty() } == true)

            val rendered = if (isClozeModel) {
                renderClozeCard(model, fieldValues = fieldValues, fieldMap = fieldMap, ord = cardRow.ord)
            } else {
                renderStandardCard(model, fieldValues = fieldValues, fieldMap = fieldMap, ord = cardRow.ord)
            }

            val rawFront = rendered.first
            val rawBack = rendered.second
            val imgCandidateName = AnkiHtmlCleaner.firstImageName(rawFront)
                ?: AnkiHtmlCleaner.firstImageName(rawBack)
            val resolvedMedia = imgCandidateName?.let(resolveMedia)

            val cleanFront = AnkiHtmlCleaner.clean(rawFront, stripHtml = true)
                .ifBlank { resolvedMedia?.first?.let { "[Imagem: $it]" }.orEmpty() }
            val cleanBack = AnkiHtmlCleaner.clean(rawBack, stripHtml = true)
                .ifBlank {
                    fieldValues.getOrNull(1)?.let { AnkiHtmlCleaner.clean(it, stripHtml = true) }.orEmpty()
                }
            if (cleanFront.isBlank() && cleanBack.isBlank() && resolvedMedia == null) continue
            val isAnkiUpdatePlaceholder = cleanFront.contains("Please update to the latest Anki version", ignoreCase = true) ||
                cleanFront.contains("atualize para a versão mais recente do Anki", ignoreCase = true)
            if (isAnkiUpdatePlaceholder) {
                skippedPlaceholderCards++
                continue
            }

            val deckName = deckNamesById[cardRow.did]?.trim()?.ifBlank { "Anki" } ?: "Anki"
            val learningState = when (cardRow.type) {
                1 -> "LEARNING"
                3 -> "RELEARNING"
                else -> null
            }
            val intervalDays = if (cardRow.ivl > 0) cardRow.ivl else 0
            val easeFactor = if (cardRow.factor > 0) (cardRow.factor / 1000.0).coerceAtLeast(1.3) else 2.5
            val dueAt = convertAnkiDue(cardRow.type, cardRow.due, raw.crtSeconds, nowMillis)
            val flag = (cardRow.flags and 0x7).coerceIn(0, 7)
            val noteTypeKey = model?.name?.takeIf { it.isNotBlank() } ?: if (isClozeModel) "Cloze" else "Basic"

            cardsByDeckName.getOrPut(deckName) { ArrayList() }.add(
                ImportedApkgCard(
                    front = cleanFront.ifBlank { "[Sem frente]" },
                    back = cleanBack.ifBlank { "[Sem verso]" },
                    tags = normalizeNoteTags(note.tags),
                    flag = flag,
                    dueAt = dueAt,
                    intervalDays = intervalDays,
                    easeFactor = easeFactor,
                    repetitions = cardRow.reps.coerceAtLeast(0),
                    lapses = cardRow.lapses.coerceAtLeast(0),
                    learningState = learningState,
                    noteTypeKey = noteTypeKey,
                    typeAnswer = rendered.third,
                    imageBytes = resolvedMedia?.second,
                    imageName = resolvedMedia?.first,
                    imageFile = resolvedMedia?.third
                )
            )
        }

        if (skippedPlaceholderCards > 0 && cardsByDeckName.isEmpty()) {
            warnings.add("Este pacote .apkg contém apenas o aviso de versão do Anki em collection.anki2. Exporte no Anki com a compatibilidade para versões anteriores ativada.")
        }
        if (skippedPlaceholderCards > 0 && cardsByDeckName.isEmpty()) {
            warnings.add("Este pacote .apkg contém apenas o aviso de versão do Anki em collection.anki2. Exporte no Anki com a compatibilidade para versões anteriores ativada.")
        }
        if (orphanCards > 0) {
            warnings.add("$orphanCards cartões ignorados por ausência da nota correspondente no .apkg.")
        }

        val decks = cardsByDeckName.entries.map { (deckName, list) ->
            ImportedApkgDeck(name = deckName, cards = list)
        }
        return ApkgImportResult(decks = decks, warnings = warnings)
    }

    private fun readBoundedEntry(
        zip: ZipInputStream,
        alreadyRead: Long,
        onAdvance: (Long) -> Unit
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var entryTotal = 0L
        while (true) {
            val read = zip.read(buf)
            if (read < 0) break
            entryTotal += read
            require(entryTotal <= MAX_SINGLE_ENTRY_BYTES) { "Entrada do .apkg excede o tamanho seguro." }
            require(alreadyRead + entryTotal <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "Pacote .apkg excede o limite de descompressão (proteção ZipBomb)." }
            out.write(buf, 0, read)
        }
        onAdvance(entryTotal)
        return out.toByteArray()
    }

    private fun writeBoundedEntryToFile(
        zip: ZipInputStream,
        target: File,
        alreadyRead: Long,
        onAdvance: (Long) -> Unit
    ): Long {
        val buf = ByteArray(8192)
        var entryTotal = 0L
        target.outputStream().buffered().use { out ->
            while (true) {
                val read = zip.read(buf)
                if (read < 0) break
                entryTotal += read
                require(entryTotal <= MAX_SINGLE_ENTRY_BYTES) { "Entrada do .apkg excede o tamanho seguro." }
                require(alreadyRead + entryTotal <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "Pacote .apkg excede o limite de descompressão (proteção ZipBomb)." }
                out.write(buf, 0, read)
            }
        }
        onAdvance(entryTotal)
        return entryTotal
    }

    private fun drainBoundedEntry(
        zip: ZipInputStream,
        alreadyRead: Long,
        onAdvance: (Long) -> Unit
    ) {
        val buf = ByteArray(8192)
        var entryTotal = 0L
        while (true) {
            val read = zip.read(buf)
            if (read < 0) break
            entryTotal += read
            require(entryTotal <= MAX_SINGLE_ENTRY_BYTES) { "Entrada do .apkg excede o tamanho seguro." }
            require(alreadyRead + entryTotal <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "Pacote .apkg excede o limite de descompressão (proteção ZipBomb)." }
        }
        onAdvance(entryTotal)
    }

    private fun startsWithMagic(bytes: ByteArray, magic: ByteArray): Boolean {
        if (bytes.size < magic.size) return false
        for (i in magic.indices) if (bytes[i] != magic[i]) return false
        return true
    }

    private fun parseDecksJson(raw: String, warnings: MutableList<String>): Map<Long, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = MiniJson.parseObject(raw)
            val result = HashMap<Long, String>()
            for (key in obj.keys) {
                val deckObj = MiniJson.optObject(obj, key) ?: continue
                val fallbackId = key.toLongOrNull()
                val id = if (deckObj.containsKey("id") && deckObj["id"] != null) {
                    MiniJson.optLong(deckObj, "id", fallbackId ?: continue)
                } else {
                    fallbackId ?: continue
                }
                val name = MiniJson.optString(deckObj, "name", "").trim()
                if (name.isNotEmpty()) result[id] = name
            }
            result
        }.onFailure {
            warnings.add("Falha ao interpretar metadados de baralhos do .apkg.")
        }.getOrDefault(emptyMap())
    }

    private fun parseModelsJson(raw: String, warnings: MutableList<String>): Map<Long, AnkiModelDef> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = MiniJson.parseObject(raw)
            val result = HashMap<Long, AnkiModelDef>()
            for (key in obj.keys) {
                val m = MiniJson.optObject(obj, key) ?: continue
                val fallbackId = key.toLongOrNull()
                val id = if (m.containsKey("id") && m["id"] != null) {
                    MiniJson.optLong(m, "id", fallbackId ?: continue)
                } else {
                    fallbackId ?: continue
                }
                val name = MiniJson.optString(m, "name", "Anki")
                val isCloze = MiniJson.optInt(m, "type", 0) == 1
                val fldsArr = MiniJson.optArray(m, "flds").orEmpty()
                val fieldsWithOrd = fldsArr.mapIndexedNotNull { i, item ->
                    @Suppress("UNCHECKED_CAST")
                    val f = item as? Map<String, Any?> ?: return@mapIndexedNotNull null
                    MiniJson.optInt(f, "ord", i) to MiniJson.optString(f, "name", "Field${i + 1}")
                }.sortedBy { it.first }.map { it.second }
                val tmplsArr = MiniJson.optArray(m, "tmpls").orEmpty()
                val templates = tmplsArr.mapIndexedNotNull { i, item ->
                    @Suppress("UNCHECKED_CAST")
                    val t = item as? Map<String, Any?> ?: return@mapIndexedNotNull null
                    AnkiTemplateDef(
                        name = MiniJson.optString(t, "name", "Card ${i + 1}"),
                        ord = MiniJson.optInt(t, "ord", i),
                        qfmt = MiniJson.optString(t, "qfmt", ""),
                        afmt = MiniJson.optString(t, "afmt", "")
                    )
                }.sortedBy { it.ord }
                result[id] = AnkiModelDef(id, name, isCloze, fieldsWithOrd, templates)
            }
            result
        }.onFailure {
            warnings.add("Falha ao interpretar modelos de notas do .apkg; usando fallback simples.")
        }.getOrDefault(emptyMap())
    }

    private fun renderStandardCard(
        model: AnkiModelDef?,
        fieldValues: List<String>,
        fieldMap: Map<String, String>,
        ord: Int
    ): Triple<String, String, Boolean> {
        val tmpl = model?.templates?.firstOrNull { it.ord == ord } ?: model?.templates?.getOrNull(ord)
        if (tmpl != null && tmpl.qfmt.isNotBlank()) {
            val hasTypeAnswer = tmpl.qfmt.contains("{{type:") || tmpl.afmt.contains("{{type:")
            val front = applyAnkiTemplate(tmpl.qfmt, fieldMap, isFront = true, clozeOrd = null)
            val backPattern = frontSideBreakRegex.replace(tmpl.afmt, "").ifBlank { tmpl.afmt }
            val renderedBack = applyAnkiTemplate(backPattern, fieldMap, isFront = false, clozeOrd = null)
                .ifBlank { fieldValues.getOrElse(if (ord == 1 && fieldValues.size >= 2) 0 else 1) { "" } }
            return Triple(front, renderedBack, hasTypeAnswer)
        }
        val f0 = fieldValues.getOrElse(0) { "" }
        val f1 = fieldValues.getOrElse(1) { "" }
        return if (ord % 2 == 1 && f1.isNotBlank()) {
            Triple(f1, f0, false)
        } else {
            Triple(f0, f1, false)
        }
    }

    private fun renderClozeCard(
        model: AnkiModelDef?,
        fieldValues: List<String>,
        fieldMap: Map<String, String>,
        ord: Int
    ): Triple<String, String, Boolean> {
        val tmpl = model?.templates?.firstOrNull()
        val hasTypeAnswer = tmpl != null && (tmpl.qfmt.contains("{{type:") || tmpl.afmt.contains("{{type:"))
        val activeClozeNum = (ord + 1).toString()
        if (tmpl != null && tmpl.qfmt.contains("{{cloze:")) {
            val front = applyAnkiTemplate(tmpl.qfmt, fieldMap, isFront = true, clozeOrd = activeClozeNum)
            val backPattern = frontSideBreakRegex.replace(tmpl.afmt, "").ifBlank { tmpl.afmt }
            val back = applyAnkiTemplate(backPattern, fieldMap, isFront = false, clozeOrd = activeClozeNum)
            return Triple(front, back, hasTypeAnswer)
        }
        val clozeIdx = fieldValues.indexOfFirst { findClozeMatches(it).isNotEmpty() }.coerceAtLeast(0)
        val rawClozeText = fieldValues.getOrElse(clozeIdx) { "" }
        val matches = findClozeMatches(rawClozeText)
        val front = if (matches.isEmpty()) {
            rawClozeText
        } else {
            replaceClozeString(rawClozeText, matches) { m ->
                if (m.number == activeClozeNum) "[${m.hint.ifBlank { "…" }}]" else m.answer
            }
        }
        val extraFields = fieldValues.filterIndexed { i, v -> i != clozeIdx && v.isNotBlank() }
        val fullCloze = if (matches.isEmpty()) rawClozeText else replaceClozeString(rawClozeText, matches) { it.answer }
        val back = (listOf(fullCloze) + extraFields).filter { it.isNotBlank() }.joinToString("\n\n")
        return Triple(front, back, hasTypeAnswer)
    }

    private fun applyAnkiTemplate(
        template: String,
        fields: Map<String, String>,
        isFront: Boolean,
        clozeOrd: String?
    ): String {
        val fieldLookup = fields.entries.associate { it.key.trim().lowercase() to it.value }
        var current = template
        repeat(12) {
            var changed = false
            current = templateConditional.replace(current) { match ->
                val kind = match.groupValues[1]
                val key = match.groupValues[2].trim().lowercase()
                val body = match.groupValues[3]
                if (body.contains("{{#${match.groupValues[2]}}}") || body.contains("{{^${match.groupValues[2]}}}")) {
                    return@replace match.value
                }
                changed = true
                val nonBlank = AnkiHtmlCleaner.clean(fieldLookup[key].orEmpty(), stripHtml = true).isNotBlank()
                val keep = if (kind == "#") nonBlank else !nonBlank
                if (keep) body else ""
            }
            if (!changed) return@repeat
        }
        return templateToken.replace(current) { match ->
            val prefix = match.groupValues[1]
            val key = match.groupValues[2].trim()
            val value = fieldLookup[key.lowercase()].orEmpty()
            when (prefix) {
                "type:" -> if (isFront) "" else value
                "text:" -> AnkiHtmlCleaner.clean(value, stripHtml = true)
                "furigana:" -> value.replace(Regex("\\[[^\\]]*\\]"), "")
                "cloze:" -> {
                    val matches = findClozeMatches(value)
                    if (matches.isEmpty()) value
                    else if (isFront && clozeOrd != null) {
                        replaceClozeString(value, matches) { m ->
                            if (m.number == clozeOrd) "[${m.hint.ifBlank { "…" }}]" else m.answer
                        }
                    } else {
                        replaceClozeString(value, matches) { it.answer }
                    }
                }
                "hint:" -> if (value.isBlank()) "" else value
                "#", "^", "/" -> ""
                else -> value
            }
        }
    }

    private fun replaceClozeString(
        text: String,
        matches: List<ClozeDeletionMatch>,
        transform: (ClozeDeletionMatch) -> String
    ): String = buildString(text.length) {
        var cursor = 0
        for (m in matches) {
            append(text, cursor, m.start)
            append(transform(m))
            cursor = m.endExclusive
        }
        append(text, cursor, text.length)
    }

    private fun convertAnkiDue(type: Int, due: Long, crtSeconds: Long, nowMillis: Long): Long {
        if (due <= 0L) return nowMillis
        if (due >= 1_000_000_000_000L) return due
        if ((type == 1 || type == 3) && due in 1_000_000_000L until 10_000_000_000L) {
            return due * 1000L
        }
        if (type == 2 && crtSeconds > 0L && due in 0L..100_000L) {
            return (crtSeconds + due * 86_400L) * 1000L
        }
        return nowMillis
    }

    /**
     * Descompactador Zstandard (RFC 8878) híbrido:
     * 1) Usa `ZstdInputStream` via reflexão se disponível no runtime;
     * 2) Caso contrário, decodifica frames Zstandard RFC 8878 (blocos Raw e RLE) em Kotlin puro.
     */
    internal fun tryDecompressZstd(input: ByteArray): ByteArray? {
        if (input.size < 8 || !startsWithMagic(input, zstdMagic)) return null
        // 1. Tenta via biblioteca Zstd se disponível no classpath
        runCatching {
            val cls = Class.forName("com.github.luben.zstd.ZstdInputStream")
            val ctor = cls.getConstructor(InputStream::class.java)
            (ctor.newInstance(java.io.ByteArrayInputStream(input)) as InputStream).use { stream ->
                return stream.readBytes()
            }
        }
        // 2. Decodificador RFC 8878 em Kotlin puro (suporta frames com blocos Raw/RLE)
        return runCatching {
            var pos = 4
            val fhd = input[pos++].toInt() and 0xFF
            val singleSegment = (fhd and 0x20) != 0
            val contentChecksum = (fhd and 0x04) != 0
            val dictIdFlag = fhd and 0x03
            val fcsFlag = (fhd ushr 6) and 0x03
            if (!singleSegment) pos++ // Window_Descriptor
            pos += when (dictIdFlag) {
                0 -> 0
                1 -> 1
                2 -> 2
                else -> 4
            }
            pos += when (fcsFlag) {
                0 -> if (singleSegment) 1 else 0
                1 -> 2
                2 -> 4
                else -> 8
            }
            if (pos >= input.size) return null
            val out = ByteArrayOutputStream()
            var lastBlock = false
            while (!lastBlock) {
                if (pos + 3 > input.size) return null
                val bh = (input[pos].toInt() and 0xFF) or
                    ((input[pos + 1].toInt() and 0xFF) shl 8) or
                    ((input[pos + 2].toInt() and 0xFF) shl 16)
                pos += 3
                lastBlock = (bh and 0x01) != 0
                val blockType = (bh ushr 1) and 0x03
                val blockSize = bh ushr 3
                when (blockType) {
                    0 -> { // Raw_Block
                        if (pos + blockSize > input.size || out.size() + blockSize > MAX_SINGLE_ENTRY_BYTES) return null
                        out.write(input, pos, blockSize)
                        pos += blockSize
                    }
                    1 -> { // RLE_Block
                        if (pos + 1 > input.size || out.size() + blockSize > MAX_SINGLE_ENTRY_BYTES) return null
                        val b = input[pos++]
                        val repeated = ByteArray(blockSize) { b }
                        out.write(repeated)
                    }
                    else -> return null // Bloco FSE/Huffman sem codec nativo -> aciona fallback seguro
                }
            }
            if (contentChecksum && pos + 4 <= input.size) pos += 4
            out.toByteArray().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Parser Protobuf nativo para o arquivo `media` do formato Anki 21b (`MediaEntries`). */
    internal fun parseAnki21bMediaProtobuf(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var pos = 0
        var index = 0

        fun readVarint(buf: ByteArray, start: Int, limit: Int): Pair<Long, Int>? {
            var value = 0L
            var shift = 0
            var p = start
            while (p < limit && shift < 64) {
                val b = buf[p++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return value to p
                shift += 7
            }
            return null
        }

        while (pos < bytes.size) {
            val tagPair = readVarint(bytes, pos, bytes.size) ?: break
            val tag = tagPair.first.toInt()
            pos = tagPair.second
            val wireType = tag and 0x07
            val fieldNum = tag ushr 3
            if (wireType == 2) {
                val lenPair = readVarint(bytes, pos, bytes.size) ?: break
                val len = lenPair.first.toInt()
                pos = lenPair.second
                if (len < 0 || pos + len > bytes.size) break
                if (fieldNum == 1) {
                    val entryEnd = pos + len
                    var ePos = pos
                    var entryName = ""
                    while (ePos < entryEnd) {
                        val eTagPair = readVarint(bytes, ePos, entryEnd) ?: break
                        val eTag = eTagPair.first.toInt()
                        ePos = eTagPair.second
                        val eWire = eTag and 0x07
                        val eField = eTag ushr 3
                        when (eWire) {
                            0 -> {
                                val v = readVarint(bytes, ePos, entryEnd) ?: break
                                ePos = v.second
                            }
                            2 -> {
                                val sLenPair = readVarint(bytes, ePos, entryEnd) ?: break
                                val sLen = sLenPair.first.toInt()
                                ePos = sLenPair.second
                                if (sLen < 0 || ePos + sLen > entryEnd) break
                                if (eField == 1) {
                                    entryName = String(bytes, ePos, sLen, Charsets.UTF_8).trim()
                                }
                                ePos += sLen
                            }
                            else -> break
                        }
                    }
                    if (entryName.isNotEmpty()) {
                        result[index.toString()] = entryName
                    }
                    index++
                }
                pos += len
            } else if (wireType == 0) {
                val v = readVarint(bytes, pos, bytes.size) ?: break
                pos = v.second
            } else {
                break
            }
        }
        return result
    }
}
