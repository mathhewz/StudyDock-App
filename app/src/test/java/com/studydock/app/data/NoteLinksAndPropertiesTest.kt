package com.studydock.app.data

import com.studydock.app.ui.noteTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLinksAndPropertiesTest {

    @Test
    fun noteReferencesParsesWikilinksAliasesHeadingsEscapesAndIgnoresCode() {
        val escapedTitle = NoteReferences.escape("Matriz [2x2] | Determinante")
        val md = """
Veja [[Álgebra Linear]] e [[Cálculo|Cálculo Diferencial]] além de [[Física#Cinemática]] e [[Química#Estequiometria|Estequiometria]].
Com rótulo vazio [[Direito|   ]] e escapado [[$escapedTitle|Rótulo \| Especial]].
Ignorados: `[[InlineCode]]`, `` `[[DoubleTick]]` ``, \[[Escapado]], [[]], [[   |Só label]] e [[SemFim.

```kotlin
val fake = "[[InsideTripleBacktick]]"
```

~~~
[[InsideTildeFence]]
~~~
Após fence: [[Nota Final]].
""".trimStart()

        val refs = NoteReferences.parse(md)
        assertEquals(7, refs.size)

        assertEquals("Álgebra Linear", refs[0].target)
        assertNull(refs[0].label)
        assertEquals("Álgebra Linear", refs[0].display)
        assertEquals("[[Álgebra Linear]]", md.substring(refs[0].start, refs[0].end))

        assertEquals("Cálculo", refs[1].target)
        assertEquals("Cálculo Diferencial", refs[1].label)
        assertEquals("Cálculo Diferencial", refs[1].display)

        assertEquals("Física#Cinemática", refs[2].target)
        assertNull(refs[2].label)

        assertEquals("Química#Estequiometria", refs[3].target)
        assertEquals("Estequiometria", refs[3].label)

        assertEquals("Direito", refs[4].target)
        assertEquals("Direito", refs[4].label)

        assertEquals("Matriz [2x2] | Determinante", refs[5].target)
        assertEquals("Rótulo | Especial", refs[5].label)

        assertEquals("Nota Final", refs[6].target)
        assertEquals("[[Nota Final]]", md.substring(refs[6].start, refs[6].end))
    }

    @Test
    fun noteLinkIndexAndBoundReferencesResolveByTitleAliasBindingsAndTrashState() {
        val algebra = Note(
            id = 1L,
            title = "Álgebra Linear",
            markdown = """
---
aliases: [Vetores, Espaços Vetoriais]
---
Conteúdo de álgebra.
""".trimStart()
        )
        val calculusA = Note(id = 2L, title = "Cálculo", markdown = "Cálculo I")
        val calculusB = Note(id = 3L, title = "Cálculo", markdown = "Cálculo II")
        val trashedOnly = Note(id = 4L, title = "Arquivada", markdown = "Antiga", trashedAt = 12345L)
        val activeDuplicate = Note(id = 5L, title = "Duplicada", markdown = "Ativa")
        val trashedDuplicate = Note(id = 6L, title = "Duplicada", markdown = "Lixo", trashedAt = 999L)

        val notes = listOf(algebra, calculusA, calculusB, trashedOnly, activeDuplicate, trashedDuplicate)

        val md = "Estudo [[vetores]], [[Cálculo]], [[Arquivada]] e [[Duplicada]]."
        // Ambiguous [[Cálculo]] has no known binding yet -> omitted from boundReferences; trashed Arquivada also omitted
        val bound = NoteReferences.boundReferences(md, notes, known = emptyMap())
        assertEquals(1L, bound["vetores"])
        assertEquals(5L, bound["Duplicada"])
        assertFalse("Cálculo" in bound)
        assertFalse("Arquivada" in bound)

        // When disambiguated by known bindings, [[Cálculo]] binds to the chosen target
        val disambiguated = NoteReferences.boundReferences(md, notes, known = mapOf("Cálculo" to 3L))
        assertEquals(3L, disambiguated["Cálculo"])

        // NoteLinkIndex resolution
        val savedLinks = listOf(NoteLink(sourceId = 10L, reference = "Cálculo", targetId = 2L))
        val index = NoteLinkIndex(notes, savedLinks)
        val refs = NoteReferences.parse(md)

        assertEquals(mapOf("Cálculo" to 2L), index.bound(10L))
        assertEquals(1L, index.target(10L, refs[0])?.id) // alias match
        assertEquals(2L, index.target(10L, refs[1])?.id) // binding wins over ambiguous titles
        assertNull(index.target(99L, refs[1])) // without binding, ambiguous title yields null
        assertEquals(3L, index.target(99L, refs[1], choices = mapOf("Cálculo" to 3L))?.id) // explicit choice overrides
        assertEquals(4L, index.target(99L, refs[2])?.id) // falls back to trashed if no active exists
        assertEquals(5L, index.target(99L, refs[3])?.id) // prefers active over trashed when both exist

        // Disambiguated reference helper
        val occupied = mapOf("Cálculo" to 2L, "Cálculo|Cálculo (2)" to 99L)
        assertEquals("Cálculo", NoteReferences.reference("Cálculo", 2L, occupied))
        assertEquals("Cálculo|Cálculo (3)", NoteReferences.reference("Cálculo", 3L, occupied))
    }

    @Test
    fun noteConnectionsFindsBacklinksWithContextAndUnlinkedMentions() {
        val target = Note(
            id = 1L,
            title = "Mitocôndria",
            markdown = """
---
aliases: [Usina Celular, A]
---
Organela responsável pela respiração celular.
""".trimStart()
        )
        val sourceLinked = Note(
            id = 2L,
            title = "Bioenergética",
            markdown = "Durante a respiração aeróbia, a [[Mitocôndria]] produz ATP."
        )
        val sourceUnlinked = Note(
            id = 3L,
            title = "Citologia",
            markdown = "A usina celular gera energia, mas mitocôndrias não devem casar parcial e [[Mitocôndria]] já está linkada."
        )
        val trashedSource = Note(
            id = 4L,
            title = "Lixo",
            markdown = "A [[Mitocôndria]] e usina celular no lixo.",
            trashedAt = 1000L
        )
        val notes = listOf(target, sourceLinked, sourceUnlinked, trashedSource)
        val links = listOf(
            NoteLink(sourceId = 2L, reference = "Mitocôndria", targetId = 1L),
            NoteLink(sourceId = 3L, reference = "Mitocôndria", targetId = 1L),
            NoteLink(sourceId = 4L, reference = "Mitocôndria", targetId = 1L),
            NoteLink(sourceId = 1L, reference = "Mitocôndria", targetId = 1L) // self-link
        )

        val backlinks = NoteConnections.backlinksWithContext(1L, notes, links)
        assertEquals(2, backlinks.size)
        assertEquals("Bioenergética", backlinks[0].source.title)
        assertTrue(backlinks[0].snippet.contains("[[Mitocôndria]]"))
        assertEquals("Citologia", backlinks[1].source.title)

        // Unlinked mentions: matches "usina celular" (alias >= 3 chars), skips short alias "A",
        // skips partial word "mitocôndrias", skips already-linked "[[Mitocôndria]]", skips trashed & self.
        val mentions = NoteConnections.unlinkedMentions(target, notes, links)
        assertEquals(1, mentions.size)
        assertEquals(3L, mentions.single().source.id)
        assertEquals("usina celular", mentions.single().matchedTerm)
        assertEquals("usina celular", sourceUnlinked.markdown.substring(mentions.single().start, mentions.single().end))
    }

    @Test
    fun noteFrontmatterParsesFormatsAppliesAndHandlesEdgeCases() {
        val crlfMarkdown = "---\r\n" +
            "# comentário YAML\r\n" +
            "aliases:\r\n" +
            "  - \"Lei de Ohm\"\r\n" +
            "  - 'V=IR'\r\n" +
            "  - V=IR\r\n" +
            "tags: [#Física, Eletricidade 1, fisica]\r\n" +
            "pasta: \"Exatas\"\r\n" +
            "autor: Georg Ohm\r\n" +
            "---\r\n" +
            "# Título\r\n\r\nCorpo com #circuito e --- divisor."

        val (props, body) = NoteFrontmatter.parse(crlfMarkdown)
        assertEquals(listOf("Lei de Ohm", "V=IR"), props.aliases)
        assertEquals(listOf("física", "eletricidade1", "fisica"), props.tags)
        assertEquals("Exatas", props.folder)
        assertEquals("Georg Ohm", props.custom["autor"])
        assertTrue(body.startsWith("# Título"))
        assertTrue(body.contains("--- divisor."))

        // Plain markdown with horizontal rule later must NOT be mistaken for frontmatter
        val noFrontmatter = "# Introdução\n\nTexto inicial\n---\nOutro bloco"
        val (emptyProps, unchangedBody) = NoteFrontmatter.parse(noFrontmatter)
        assertTrue(emptyProps.isEmpty)
        assertEquals(noFrontmatter, unchangedBody)

        // Applying empty properties strips frontmatter cleanly
        val stripped = NoteFrontmatter.apply(crlfMarkdown, NoteProperties())
        assertFalse(stripped.startsWith("---"))
        assertTrue(stripped.startsWith("# Título"))

        // Applying updated properties replaces old frontmatter without stacking --- blocks
        val updated = NoteFrontmatter.apply(
            crlfMarkdown,
            NoteProperties(
                aliases = listOf("Ohm"),
                tags = listOf("eletrica"),
                folder = "Física",
                custom = mapOf("nivel" to "avancado")
            )
        )
        val (reparsed, reparsedBody) = NoteFrontmatter.parse(updated)
        assertEquals(listOf("Ohm"), reparsed.aliases)
        assertEquals(listOf("eletrica"), reparsed.tags)
        assertEquals("Física", reparsed.folder)
        assertEquals("avancado", reparsed.custom["nivel"])
        assertEquals("\n# Título\r\n\r\nCorpo com #circuito e --- divisor.", reparsedBody)
        val reapplied = NoteFrontmatter.apply(updated, NoteProperties(folder = "Física2"))
        val (reappliedProps, reappliedBody) = NoteFrontmatter.parse(reapplied)
        assertEquals("Física2", reappliedProps.folder)
        assertTrue(reappliedBody.trimStart().startsWith("# Título"))

        // noteTags combines frontmatter and inline body tags without matching "# Título"
        val extractedTags = noteTags(crlfMarkdown)
        assertTrue("circuito" in extractedTags)
        assertTrue("física" in extractedTags)
        assertFalse("título" in extractedTags)

        // Built-in standard templates parse cleanly
        assertEquals(4, StandardNoteTemplates.all.size)
        for (template in StandardNoteTemplates.all) {
            val (tProps, tBody) = NoteFrontmatter.parse(template.initialMarkdown)
            assertFalse(tProps.isEmpty)
            assertNotNull(tProps.folder)
            assertTrue(tBody.isNotBlank())
        }
    }
}
