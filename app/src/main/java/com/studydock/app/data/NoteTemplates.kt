package com.studydock.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class NoteTemplate(
    val id: String,
    val title: String,
    val description: String,
    val defaultTitle: String,
    val initialMarkdown: String,
    val isUserTemplate: Boolean = false
)

object StandardNoteTemplates {
    val all: List<NoteTemplate> = listOf(
        NoteTemplate(
            id = "atomic_concept",
            title = "Nota Conceitual (Zettelkasten)",
            description = "Uma ideia atômica com conexões para outros conceitos e exemplos práticos.",
            defaultTitle = "Conceito: ",
            initialMarkdown = """---
tags: [conceito]
aliases: []
folder: Conceitos
---
# Conceito: 

## Em poucas palavras
Explique o conceito com suas próprias palavras em 2 a 3 frases.

## Conexões
- Aprofunda a ideia de [[...]]
- Difere ou contrasta com [[...]]

## Exemplo Prático
- 
"""
        ),
        NoteTemplate(
            id = "lecture_summary",
            title = "Resumo de Aula / Leitura",
            description = "Estrutura para registrar aulas, leituras de livros ou artigos com pontos-chave.",
            defaultTitle = "Resumo: ",
            initialMarkdown = """---
tags: [resumo, leitura]
folder: Leituras
---
# Resumo: 
**Fonte / Autor:** 

## Pontos Principais
1. 
2. 
3. 

## Citações ou Trechos Importantes
> 

## Ideias para Flashcards
- Pergunta: 
  Resposta: 
"""
        ),
        NoteTemplate(
            id = "formula_definition",
            title = "Definição Científica / Fórmula",
            description = "Foco em princípios matemáticos, físicos ou definições técnicas formais.",
            defaultTitle = "Definição: ",
            initialMarkdown = """---
tags: [definicao, exatas]
folder: Fórmulas
---
# Definição: 

## Enunciado Formal
> 

## Notação e Variáveis
- **X:** 
- **Y:** 

## Quando aplicar
- 
"""
        ),
        NoteTemplate(
            id = "weekly_review",
            title = "Revisão e Reflexão de Estudos",
            description = "Consolidação periódica de aprendizados, lacunas e planejamento.",
            defaultTitle = "Revisão Semanal",
            initialMarkdown = """---
tags: [revisao, meta]
folder: Planejamento
---
# Revisão Semanal

## O que consolidei com sucesso?
- 

## O que ainda preciso reforçar?
- 

## Próximos passos e metas
- [ ] 
- [ ] 
"""
        )
    )
}

private val templatePlaceholderRegex = Regex("""\{\{\s*([^{}]+?)\s*\}\}""")
private val ptBrLocale = Locale("pt", "BR")
private val defaultTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", ptBrLocale)

/**
 * Expands Obsidian-style template variables inside [markdown]:
 * - `{{date}}` -> `yyyy-MM-dd`
 * - `{{date:PATTERN}}` -> formatted with `PATTERN` (supports both Java patterns like `dd/MM/yyyy` and Moment-style `YYYY-MM-dd`)
 * - `{{time}}` -> `HH:mm`
 * - `{{time:PATTERN}}` -> formatted with `PATTERN`
 * - `{{title}}` -> [title]
 * - `{{weekday}}` -> localized weekday name
 * - `{{year}}` -> 4-digit year
 */
fun expandTemplate(
    markdown: String,
    title: String,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): String {
    if (!markdown.contains("{{")) return markdown
    val zoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val defaultDate = zoned.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val defaultTime = zoned.format(defaultTimeFormatter)
    val weekday = zoned.dayOfWeek
        .getDisplayName(TextStyle.FULL, ptBrLocale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(ptBrLocale) else it.toString() }
    val year = zoned.year.toString()

    val result = StringBuilder(markdown.length)
    var last = 0
    for (match in templatePlaceholderRegex.findAll(markdown)) {
        result.append(markdown, last, match.range.first)
        val expr = match.groupValues[1].trim()
        val colonIdx = expr.indexOf(':')
        val key = (if (colonIdx < 0) expr else expr.substring(0, colonIdx)).trim().lowercase(Locale.ROOT)
        val arg = if (colonIdx < 0) "" else expr.substring(colonIdx + 1).trim()

        val replacement = when (key) {
            "date" -> if (arg.isEmpty()) defaultDate else formatZonedPattern(zoned, arg, defaultDate)
            "time" -> if (arg.isEmpty()) defaultTime else formatZonedPattern(zoned, arg, defaultTime)
            "title" -> title
            "weekday" -> weekday
            "year" -> year
            else -> match.value
        }
        result.append(replacement)
        last = match.range.last + 1
    }
    result.append(markdown, last, markdown.length)
    return result.toString()
}

private fun formatZonedPattern(zoned: ZonedDateTime, rawPattern: String, fallback: String): String {
    val normalized = normalizeMomentToJavaDatePattern(rawPattern)
    return runCatching {
        zoned.format(DateTimeFormatter.ofPattern(normalized, ptBrLocale))
    }.getOrElse { fallback }
}

private fun normalizeMomentToJavaDatePattern(pattern: String): String {
    val sb = StringBuilder(pattern.length)
    var inQuotes = false
    var i = 0
    while (i < pattern.length) {
        val ch = pattern[i]
        if (ch == '\'') {
            inQuotes = !inQuotes
            sb.append(ch)
            i++
            continue
        }
        if (!inQuotes && (ch == 'Y' || ch == 'D')) {
            sb.append(ch.lowercaseChar())
        } else {
            sb.append(ch)
        }
        i++
    }
    return sb.toString()
}

private val templateTagSet = setOf("template", "modelo", "templates", "modelos")
private val templateFolderSet = setOf("templates", "modelos", "template", "modelo")

/**
 * Discovers user-defined note templates among non-trashed [notes]: any note tagged with
 * `#template` / `#modelo` (including subtags) or stored inside a `Templates` / `Modelos` folder.
 */
fun userTemplatesFromNotes(notes: List<Note>): List<NoteTemplate> {
    return notes
        .asSequence()
        .filter { it.trashedAt == null }
        .filter { note ->
            val props = NoteFrontmatter.properties(note.markdown)
            val folderSegments = props.folder
                ?.trim()
                ?.split('/')
                ?.map { it.trim().lowercase(Locale.ROOT) }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val inTemplateFolder = folderSegments.firstOrNull() in templateFolderSet
            val hasTemplateTag = extractNoteTags(note.markdown).any { tag ->
                val rootSegment = tag.substringBefore('/')
                rootSegment in templateTagSet
            }
            inTemplateFolder || hasTemplateTag
        }
        .sortedBy { it.title.lowercase(Locale.ROOT) }
        .map { note ->
            val props = NoteFrontmatter.properties(note.markdown)
            val previewLine = NoteFrontmatter.body(note.markdown)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.take(80)
            val originLabel = props.folder?.trim()?.takeIf { it.isNotEmpty() } ?: "#template"
            val title = note.title.ifBlank { "Modelo #${note.id}" }
            NoteTemplate(
                id = "user_note_${note.id}",
                title = title,
                description = previewLine ?: "Modelo pessoal ($originLabel)",
                defaultTitle = title,
                initialMarkdown = note.markdown,
                isUserTemplate = true
            )
        }
        .toList()
}
