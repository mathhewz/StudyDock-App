package com.studydock.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.studydock.app.ui.GraphEdge
import com.studydock.app.ui.GraphNode
import com.studydock.app.ui.computeForceDirectedLayout
import com.studydock.app.ui.localGraphNeighborhood
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PhaseTwoAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "phase-two-${UUID.randomUUID()}.db"
    private var database: StudyDatabase? = null
    private fun open(): StudyDao = StudyDatabase.openNamed(context, name).also { database = it }.studyDao()
    private fun reopen(): StudyDao { database?.close(); return open() }
    @After fun cleanup() { database?.close(); context.deleteDatabase(name) }

    @Test
    fun frontmatterParserExtractsPropertiesAndPreservesBody() {
        val markdown = """---
aliases: [Segunda Lei, F=ma]
tags: [fisica, mecanica]
folder: Física Clássica
author: Isaac Newton
---
# Segunda Lei de Newton

A aceleração de um corpo é diretamente proporcional à força resultante.
"""
        val (props, body) = NoteFrontmatter.parse(markdown)
        assertEquals(listOf("Segunda Lei", "F=ma"), props.aliases)
        assertEquals(listOf("fisica", "mecanica"), props.tags)
        assertEquals("Física Clássica", props.folder)
        assertEquals("Isaac Newton", props.custom["author"])
        assertTrue(body.startsWith("# Segunda Lei de Newton"))

        // Roundtrip format and apply
        val formatted = NoteFrontmatter.format(props)
        assertTrue(formatted.contains("aliases: [Segunda Lei, F=ma]"))
        assertTrue(formatted.contains("folder: Física Clássica"))

        val reapplied = NoteFrontmatter.apply("# Novo Título", props)
        val (reparsedProps, reparsedBody) = NoteFrontmatter.parse(reapplied)
        assertEquals(props.aliases, reparsedProps.aliases)
        assertEquals(props.folder, reparsedProps.folder)
        assertEquals("# Novo Título", reparsedBody.trim())
    }

    @Test
    fun frontmatterHandlesIndentedYamlListsAndEmptyBlocks() {
        val indentedYaml = """---
aliases:
  - Termo Um
  - Termo Dois
tags:
  - tag1
  - tag2
folder: Testes
---
Texto simples.
"""
        val props = NoteFrontmatter.properties(indentedYaml)
        assertEquals(listOf("Termo Um", "Termo Dois"), props.aliases)
        assertEquals(listOf("tag1", "tag2"), props.tags)
        assertEquals("Testes", props.folder)

        // Plain markdown with no frontmatter
        val plain = "# Apenas texto"
        val (emptyProps, plainBody) = NoteFrontmatter.parse(plain)
        assertTrue(emptyProps.isEmpty)
        assertEquals(plain, plainBody)
    }

    @Test
    fun aliasesAllowLinkingNotesByTheirAlternateNames() = runBlocking {
        val dao = open()
        val noteContent = """---
aliases: [Física Newtoniana, Mecânica Clássica]
---
Fundamentos da física.
"""
        val physicsId = dao.saveNote(Note(title = "Mecânica", markdown = noteContent))
        val notes = dao.allNotes()

        val referencingText = "Estudamos isso em [[Física Newtoniana]] e também em [[Mecânica Clássica|outro rótulo]]."
        val bound = NoteReferences.boundReferences(referencingText, notes, emptyMap())

        assertEquals(physicsId, bound["Física Newtoniana"])
        assertEquals(physicsId, bound["Mecânica Clássica|outro rótulo"])

        val index = NoteLinkIndex(notes, emptyList())
        val refs = NoteReferences.parse(referencingText)
        val firstTarget = index.target(null, refs[0])
        val secondTarget = index.target(null, refs[1])

        assertNotNull(firstTarget)
        assertEquals(physicsId, firstTarget!!.id)
        assertNotNull(secondTarget)
        assertEquals(physicsId, secondTarget!!.id)
    }

    @Test
    fun backlinksWithContextExtractsSurroundingSentence() = runBlocking {
        val dao = open()
        val targetId = dao.saveNote(Note(title = "Mitocôndria", markdown = "Organela celular."))
        val sourceMarkdown = "Durante a aula de biologia, aprendemos que a [[Mitocôndria]] gera ATP para a célula."
        val sourceId = dao.saveNote(Note(title = "Bioenergética", markdown = sourceMarkdown))

        val bound = NoteReferences.boundReferences(sourceMarkdown, dao.allNotes(), emptyMap())
        dao.replaceNoteLinks(sourceId, bound)

        val backlinks = NoteConnections.backlinksWithContext(targetId, dao.allNotes(), dao.noteLinks().first())
        assertEquals(1, backlinks.size)
        assertEquals(sourceId, backlinks[0].source.id)
        assertTrue(backlinks[0].snippet.contains("Mitocôndria"))
        assertTrue(backlinks[0].snippet.contains("gera ATP"))
    }

    @Test
    fun unlinkedMentionsFindsPlainOccurrencesAndCanLinkThem() = runBlocking {
        val dao = open()
        val targetId = dao.saveNote(Note(title = "Gravidade", markdown = "Força fundamental."))
        val sourceText = "A aceleração causada pela gravidade na Terra é de aproximadamente 9,8 m/s²."
        val sourceId = dao.saveNote(Note(title = "Queda Livre", markdown = sourceText))

        val targetNote = dao.findNote(targetId)!!
        val mentions = NoteConnections.unlinkedMentions(targetNote, dao.allNotes(), emptyList())

        assertEquals(1, mentions.size)
        assertEquals(sourceId, mentions[0].source.id)
        assertEquals("gravidade", mentions[0].matchedTerm.lowercase())

        // Execute linking
        val mention = mentions[0]
        val success = dao.linkMention(sourceId, mention.start, mention.end, targetId, targetNote.title)
        assertTrue(success)

        val updatedSource = reopen().findNote(sourceId)!!
        assertTrue(updatedSource.markdown.contains("[[Gravidade|gravidade]]"))

        val links = reopen().linksFromNote(sourceId)
        assertEquals(1, links.size)
        assertEquals(targetId, links[0].targetId)

        // Verifying that after linking, it is no longer reported as unlinked
        val afterMentions = NoteConnections.unlinkedMentions(targetNote, reopen().allNotes(), links)
        assertTrue(afterMentions.isEmpty())
    }

    @Test
    fun forceDirectedLayoutGeneratesBoundedValidCoordinates() {
        val nodes = (1..20L).map { id ->
            GraphNode(id = id, title = "Nota $id", folder = null, primaryTag = null, degree = if (id % 2L == 0L) 2 else 1)
        }
        val edges = listOf(
            GraphEdge(1, 2), GraphEdge(2, 3), GraphEdge(3, 4),
            GraphEdge(4, 5), GraphEdge(5, 6), GraphEdge(1, 6)
        )

        val positions = computeForceDirectedLayout(nodes, edges, width = 600f, height = 400f, iterations = 50)
        assertEquals(20, positions.size)

        positions.values.forEach { pos ->
            assertFalse(pos.x.isNaN())
            assertFalse(pos.y.isNaN())
            assertTrue("Coordenada X fora dos limites: ${pos.x}", pos.x in 20f..580f)
            assertTrue("Coordenada Y fora dos limites: ${pos.y}", pos.y in 20f..380f)
        }
    }

    @Test
    fun localGraphNeighborhoodExtractsCorrectDepths() {
        val notes = (1..6L).map { Note(id = it, title = "N$it", markdown = "") }
        val edges = listOf(1L to 2L, 2L to 3L, 3L to 4L, 5L to 6L)

        // Depth 1 around node 1
        val (depth1Notes, depth1Edges) = localGraphNeighborhood(1L, notes, edges, depth = 1)
        assertEquals(setOf(1L, 2L), depth1Notes.map { it.id }.toSet())
        assertEquals(listOf(1L to 2L), depth1Edges)

        // Depth 2 around node 1
        val (depth2Notes, depth2Edges) = localGraphNeighborhood(1L, notes, edges, depth = 2)
        assertEquals(setOf(1L, 2L, 3L), depth2Notes.map { it.id }.toSet())
        assertEquals(setOf(1L to 2L, 2L to 3L), depth2Edges.toSet())
    }

    @Test
    fun noteFlashcardHubIntegratesCardCountAndState() = runBlocking {
        val dao = open()
        val noteId = dao.saveNote(Note(title = "Célula", markdown = "Biologia celular."))
        val cardId1 = dao.addFlashcard(Flashcard(front = "O que é citoplasma?", back = "Fluido celular", sourceNoteId = noteId, sourceExcerpt = "citoplasma"))
        val cardId2 = dao.addFlashcard(Flashcard(front = "O que é membrana?", back = "Camada lipídica", sourceNoteId = noteId, sourceExcerpt = "membrana", intervalDays = 25, repetitions = 4))

        val allCards = dao.listFlashcards()
        val noteCards = allCards.filter { it.sourceNoteId == noteId }

        assertEquals(2, noteCards.size)
        assertTrue(noteCards.any { it.id == cardId1 && it.intervalGroup() == IntervalGroup.NEW })
        assertTrue(noteCards.any { it.id == cardId2 && it.intervalGroup() == IntervalGroup.MATURE })
    }

    @Test
    fun standardTemplatesProvideValidInitialStructures() {
        assertEquals(4, StandardNoteTemplates.all.size)
        StandardNoteTemplates.all.forEach { template ->
            assertTrue(template.title.isNotBlank())
            assertTrue(template.initialMarkdown.contains("---"))
            val (props, body) = NoteFrontmatter.parse(template.initialMarkdown)
            assertFalse(props.isEmpty)
            assertTrue(props.tags.isNotEmpty())
            assertTrue(body.isNotBlank())
        }
    }
}
