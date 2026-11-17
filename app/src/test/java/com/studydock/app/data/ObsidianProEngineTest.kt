package com.studydock.app.data

import com.studydock.app.ui.CanvasDocument
import com.studydock.app.ui.CanvasEdgeItem
import com.studydock.app.ui.CanvasNodeItem
import com.studydock.app.ui.CanvasNodeType
import com.studydock.app.ui.GraphEdge
import com.studydock.app.ui.GraphNode
import com.studydock.app.ui.ObsidianPalette
import com.studydock.app.ui.computeForceDirectedLayout
import com.studydock.app.ui.localGraphNeighborhood
import com.studydock.app.ui.noteTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ObsidianProEngineTest {

    private val saoPaulo: ZoneId = ZoneId.of("America/Sao_Paulo")

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, saoPaulo).toInstant().toEpochMilli()

    @Test
    fun noteReferencesParseFragmentsEmbedsAliasesAndIgnoreCode() {
        val md = """
            ---
            title: Revisão
            tags: [estudo]
            ---
            Veja [[Nota#Seção]] e também [[Nota#^bloco]].
            Embeds: ![[Embed]] e ![[Embed#Seção]] com apelido [[Nota#Seção|Apelido Custom]].
            Não parsear inline `[[InlineFake#Heading]]` nem `![[InlineEmbed]]`.
            ```markdown
            [[FenceFake#Heading]]
            ![[FenceEmbed#^bloco]]
            ```
            ~~~kotlin
            val ignore = "[[TildeFake#Secao]]"
            ~~~
        """.trimIndent()

        val refs: List<NoteReference> = NoteReferences.parse(md)
        assertEquals(5, refs.size)

        val headingRef = refs[0]
        assertEquals("Nota#Seção", headingRef.target)
        assertEquals("Nota", headingRef.noteTarget)
        assertEquals("Seção", headingRef.heading)
        assertNull(headingRef.blockId)
        assertNull(headingRef.alias)
        assertFalse(headingRef.isEmbed)
        assertEquals("Nota#Seção", headingRef.display)

        val blockRef = refs[1]
        assertEquals("Nota#^bloco", blockRef.target)
        assertEquals("Nota", blockRef.noteTarget)
        assertNull(blockRef.heading)
        assertEquals("bloco", blockRef.blockId)
        assertFalse(blockRef.isEmbed)

        val embedPlain = refs[2]
        assertEquals("Embed", embedPlain.target)
        assertEquals("Embed", embedPlain.noteTarget)
        assertNull(embedPlain.heading)
        assertNull(embedPlain.blockId)
        assertTrue(embedPlain.isEmbed)

        val embedHeading = refs[3]
        assertEquals("Embed#Seção", embedHeading.target)
        assertEquals("Embed", embedHeading.noteTarget)
        assertEquals("Seção", embedHeading.heading)
        assertNull(embedHeading.blockId)
        assertTrue(embedHeading.isEmbed)

        val aliased = refs[4]
        assertEquals("Nota#Seção", aliased.target)
        assertEquals("Nota", aliased.noteTarget)
        assertEquals("Seção", aliased.heading)
        assertEquals("Apelido Custom", aliased.alias)
        assertEquals("Apelido Custom", aliased.display)
        assertFalse(aliased.isEmbed)
    }

    @Test
    fun extractNoteTagsAndUiNoteTagsIgnoreCodeHexColorsAndPreserveSubtags() {
        val md = """
            ---
            tags: [FrontTag, mat/Geometria]
            ---
            Texto com #mat/algebra, #estudo_ativo e duplicada #mat/algebra.
            Cores hex devem ser ignoradas: #fff #ffffff #a1b2c3 #00ff00ff e números #12345.
            Ignorar link [[Outra#SecaoTag]] e URL [doc](https://example.com/#ancora-url).
            Ignorar backtick `#falso-inline` e `#outro/subtag`.
            ```css
            color: #123abc;
            /* #tag-em-fence */
            ```
            ~~~
            #tag-em-tilde
            ~~~
        """.trimIndent()

        val tags = extractNoteTags(md)
        assertEquals(
            listOf("estudo_ativo", "fronttag", "mat/algebra", "mat/geometria"),
            tags
        )
        assertEquals(tags, noteTags(md))
        assertFalse(tags.contains("fff"))
        assertFalse(tags.contains("ffffff"))
        assertFalse(tags.contains("a1b2c3"))
        assertFalse(tags.contains("falso-inline"))
        assertFalse(tags.contains("tag-em-fence"))
        assertFalse(tags.contains("tag-em-tilde"))
        assertFalse(tags.contains("secaotag"))
        assertFalse(tags.contains("ancora-url"))
    }

    @Test
    fun extractOutlineAndSectionMarkdownExtractHeadingsAndBlockIds() {
        val md = """
            ---
            folder: Direito/Constitucional
            ---
            # Título Principal
            Introdução geral.

            ## Seção A ###
            Conteúdo de A.
            ### Subseção A1
            Detalhe importante sobre controle difuso. ^bloco1

            #### Nível 4
            Texto nível 4.
            ##### Nível 5
            Texto nível 5.
            ###### Nível 6
            Texto nível 6.

            ```markdown
            # Heading Falso Dentro de Code Fence
            Parágrafo falso ^bloco1
            ```

            ## Seção B
            - Item da lista principal
              continuação indentada ^lista-item

            Bloco isolado antes do marcador.
            ^bloco-solto
        """.trimIndent()

        val outline = extractOutline(md)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 2), outline.map { it.level })
        assertEquals(
            listOf(
                "Título Principal",
                "Seção A",
                "Subseção A1",
                "Nível 4",
                "Nível 5",
                "Nível 6",
                "Seção B"
            ),
            outline.map { it.title }
        )
        assertTrue(outline.zipWithNext().all { (a, b) -> a.lineIndex < b.lineIndex && a.charOffset < b.charOffset })

        val sectionA = extractSectionMarkdown(md, "Seção A")
        assertNotNull(sectionA)
        assertTrue(sectionA!!.startsWith("## Seção A"))
        assertTrue(sectionA.contains("### Subseção A1"))
        assertTrue(sectionA.contains("Texto nível 6."))
        assertFalse(sectionA.contains("## Seção B"))

        val subSection = extractSectionMarkdown(md, "subsecao-a1")
        assertNotNull(subSection)
        assertTrue(subSection!!.contains("Detalhe importante sobre controle difuso."))
        assertFalse(subSection.contains("## Seção B"))

        val blockInline = extractSectionMarkdown(md, "^bloco1")
        assertEquals("Detalhe importante sobre controle difuso.", blockInline)

        val blockStandalone = extractSectionMarkdown(md, "^bloco-solto")
        assertEquals("Bloco isolado antes do marcador.", blockStandalone)

        val listBlock = extractSectionMarkdown(md, "^lista-item")
        assertNotNull(listBlock)
        assertTrue(listBlock!!.contains("Item da lista principal"))
        assertNull(extractSectionMarkdown(md, "Heading Falso Dentro de Code Fence"))
    }

    @Test
    fun buildFolderTreeAndTagTreeComputeNestedHierarchyAndTotalNotes() {
        val notes = listOf(
            Note(
                id = 1L,
                title = "Controle",
                markdown = "---\nfolder: Direito/Constitucional/Controle\ntags: [juridico/constitucional, juridico/constitucional/controle]\n---\nConteúdo 1"
            ),
            Note(
                id = 2L,
                title = "Princípios",
                markdown = "---\nfolder: direito/constitucional\n---\nTexto #juridico/administrativo"
            ),
            Note(
                id = 3L,
                title = "Física",
                markdown = "---\nfolder: Exatas/Física\n---\nTexto #exatas/mecanica"
            ),
            Note(
                id = 4L,
                title = "Lixeira",
                markdown = "---\nfolder: Direito/Penal\n---\nTexto #juridico/penal",
                trashedAt = 12345L
            )
        )

        val folders = buildFolderTree(notes)
        assertEquals(listOf("Direito", "Exatas"), folders.map { it.name })
        val direito = folders.first { it.name == "Direito" }
        assertEquals(2, direito.totalNotes)
        assertTrue(direito.noteIds.isEmpty())
        assertEquals(listOf("Constitucional"), direito.children.map { it.name })
        val constitucional = direito.children.single()
        assertEquals("Direito/Constitucional", constitucional.fullPath)
        assertEquals(listOf(2L), constitucional.noteIds)
        assertEquals(2, constitucional.totalNotes)
        val controle = constitucional.children.single()
        assertEquals("Direito/Constitucional/Controle", controle.fullPath)
        assertEquals(listOf(1L), controle.noteIds)
        assertEquals(1, controle.totalNotes)

        val tagTree = buildTagTree(notes)
        assertEquals(listOf("exatas", "juridico"), tagTree.map { it.segment })
        val juridico = tagTree.first { it.segment == "juridico" }
        // Note 1 has two nested tags under juridico/constitucional, Note 2 has juridico/administrativo;
        // trashed Note 4 is excluded -> deduplicated totalNotes must be 2.
        assertEquals(2, juridico.totalNotes)
        val constTag = juridico.children.first { it.segment == "constitucional" }
        assertEquals("juridico/constitucional", constTag.fullTag)
        assertEquals(1, constTag.totalNotes)
        assertEquals(listOf(1L), constTag.noteIds)
        assertEquals("juridico/constitucional/controle", constTag.children.single().fullTag)
        assertEquals(listOf(1L), constTag.children.single().noteIds)
    }

    @Test
    fun obsidianSearchEngineSupportsOperatorsPhraseRegexNegationAndOr() {
        val n1 = Note(
            id = 1L,
            title = "Álgebra Linear",
            markdown = "---\nfolder: Exatas/Matemática\naliases: [Matrizes]\n---\nEstudo de espaço vetorial e #mat/algebra."
        )
        val n2 = Note(
            id = 2L,
            title = "Cálculo Vetorial",
            markdown = "---\nfolder: Exatas/Matemática/Avançado\n---\nTeorema de Stokes aula04 #mat/calculo."
        )
        val n3 = Note(
            id = 3L,
            title = "Direito Tributário",
            markdown = "---\nfolder: Direito/Tributário\n---\nObrigação principal e rascunho de #dir/trib."
        )
        val all = listOf(n1, n2, n3)

        assertEquals(listOf(1L), ObsidianSearchEngine.search(all, "tag:mat/algebra").map { it.id })
        assertEquals(listOf(1L, 2L), ObsidianSearchEngine.search(all, "tag:mat").map { it.id })
        assertEquals(listOf(1L, 2L), ObsidianSearchEngine.search(all, "path:Exatas/Matemática").map { it.id })
        assertEquals(listOf(2L), ObsidianSearchEngine.search(all, "path:Exatas/Matemática/Avançado").map { it.id })
        assertEquals(listOf(1L), ObsidianSearchEngine.search(all, "file:Matrizes").map { it.id })
        assertEquals(listOf(1L), ObsidianSearchEngine.search(all, "\"espaço vetorial\"").map { it.id })
        assertEquals(listOf(2L), ObsidianSearchEngine.search(all, """/aula\d{2}/""").map { it.id })
        assertEquals(
            listOf(2L),
            ObsidianSearchEngine.search(all, "path:Exatas -tag:mat/algebra").map { it.id }
        )
        assertEquals(
            listOf(1L, 3L),
            ObsidianSearchEngine.search(all, "tag:mat/algebra OR \"Obrigação principal\"").map { it.id }
        )
        assertTrue(ObsidianSearchEngine.search(all, "tag:dir -rascunho").isEmpty())
    }

    @Test
    fun dataviewQueryEngineExecutesFromSortLimitAndTaskQueries() {
        val t1 = epochMillis(2026, 11, 10)
        val t2 = epochMillis(2026, 11, 12)
        val t3 = epochMillis(2026, 11, 14)

        val n1 = Note(
            id = 1L,
            title = "Beta Projeto",
            markdown = "---\nfolder: Projetos/App\ntags: [projeto/mobile]\n---\n- [ ] Escrever documentação\n- [x] Configurar repositório",
            updatedAt = t2
        )
        val n2 = Note(
            id = 2L,
            title = "Alfa Projeto",
            markdown = "---\nfolder: Projetos/Web\ntags: [projeto/web]\n---\n- [ ] Publicar versão",
            updatedAt = t3
        )
        val n3 = Note(
            id = 3L,
            title = "Gama Projeto",
            markdown = "---\nfolder: Projetos/App\ntags: [projeto/mobile]\n---\nNotas gerais.",
            updatedAt = t1
        )
        val trashed = Note(
            id = 4L,
            title = "Excluída",
            markdown = "---\nfolder: Projetos/App\ntags: [projeto/mobile]\n---\n- [ ] Não deve aparecer",
            updatedAt = t3,
            trashedAt = t3
        )
        val notes = listOf(n1, n2, n3, trashed)

        val byTagSortedLimited = DataviewQueryEngine.execute(
            """
            LIST
            FROM #projeto/mobile
            SORT updated DESC
            LIMIT 1
            """.trimIndent(),
            notes
        )
        assertEquals("NOTES", byTagSortedLimited.mode)
        assertNull(byTagSortedLimited.error)
        assertEquals(listOf(1L), byTagSortedLimited.notes.map { it.id })

        val byFolderSortedTitle = DataviewQueryEngine.execute(
            """
            LIST
            FROM "Projetos"
            SORT title ASC
            LIMIT 2
            """.trimIndent(),
            notes
        )
        assertEquals(listOf("Alfa Projeto", "Beta Projeto"), byFolderSortedTitle.notes.map { it.title })

        val studyTasks = listOf(
            StudyTask(id = 10L, title = "Escrever documentação", dueAt = t2, completed = false, priority = 1, noteId = 1L, tags = "projeto/mobile"),
            StudyTask(id = 11L, title = "Revisar PR aberto", dueAt = t3, completed = false, priority = 2, noteId = null, tags = "projeto/mobile"),
            StudyTask(id = 12L, title = "Tarefa fora", dueAt = t3, completed = false, priority = 1, noteId = null, tags = "lazer")
        )
        val taskQuery = DataviewQueryEngine.execute(
            """
            TASK
            FROM #projeto
            """.trimIndent(),
            notes,
            studyTasks
        )
        assertEquals("TASKS", taskQuery.mode)
        assertNull(taskQuery.error)
        // StudyTask "Escrever documentação" must deduplicate against the matching Markdown checklist line.
        val titles = taskQuery.tasks.map { it.title }
        assertEquals(1, titles.count { it == "Escrever documentação" })
        assertTrue("Configurar repositório" in titles)
        assertTrue("Publicar versão" in titles)
        assertTrue("Revisar PR aberto" in titles)
        assertFalse("Tarefa fora" in titles)
        assertFalse("Não deve aparecer" in titles)

        val invalidLimit = DataviewQueryEngine.execute("LIST\nLIMIT abc", notes)
        assertNotNull(invalidLimit.error)
    }

    @Test
    fun noteFrontmatterRoundTripsLosslesslyAndPreservesBodyCursor() {
        val original = """
            ---
            # Cabeçalho de comentários YAML
            folder: "Estudos/Direito"
            tags: # comentário na chave tags
              - direito/constitucional
              - revisao
            status: em_andamento
            peso: 3 # mantido em ordem
            publicado: false
            revisao_em: 2026-11-14
            meta:
              autor: Ana
              versao: 2
            ---
            Corpo da nota começa aqui e termina nesta frase importante.
        """.trimIndent() + "\n"

        val (parsedProps, body) = NoteFrontmatter.parse(original)
        assertEquals("Estudos/Direito", parsedProps.folder)
        assertEquals(listOf("direito/constitucional", "revisao"), parsedProps.tags)
        assertEquals("em_andamento", parsedProps.custom["status"])
        assertEquals("3", parsedProps.custom["peso"])
        assertEquals("false", parsedProps.custom["publicado"])
        assertEquals("2026-11-14", parsedProps.custom["revisao_em"])

        assertEquals(NotePropertyType.NUMBER, NoteFrontmatter.inferPropertyType("3.5"))
        assertEquals(NotePropertyType.BOOL, NoteFrontmatter.inferPropertyType("false"))
        assertEquals(NotePropertyType.DATE, NoteFrontmatter.inferPropertyType("2026-11-14"))
        assertEquals(NotePropertyType.LIST, NoteFrontmatter.inferPropertyType("[a, b]"))
        assertEquals(NotePropertyType.TEXT, NoteFrontmatter.inferPropertyType("em_andamento"))
        assertEquals("[um, dois]", NoteFrontmatter.formatListProperty(NoteFrontmatter.parseListProperty("- um\n- dois")))

        val updatedProps = parsedProps.copy(
            tags = listOf("direito/constitucional", "revisao", "prova"),
            custom = parsedProps.custom + ("prioridade" to "alta")
        )
        val roundTripped = NoteFrontmatter.apply(original, updatedProps)
        assertTrue(roundTripped.contains("# Cabeçalho de comentários YAML"))
        assertTrue(roundTripped.contains("meta:\n  autor: Ana\n  versao: 2"))
        assertTrue(roundTripped.contains("peso: 3 # mantido em ordem"))
        assertTrue(roundTripped.contains("prioridade: alta"))
        assertTrue(roundTripped.indexOf("status:") < roundTripped.indexOf("peso:"))
        val reparsed = NoteFrontmatter.properties(roundTripped)
        assertEquals("alta", reparsed.custom["prioridade"])
        assertEquals(listOf("direito/constitucional", "revisao", "prova"), reparsed.tags)
        assertEquals(body.trimStart('\r', '\n'), NoteFrontmatter.body(roundTripped).trimStart('\r', '\n'))

        // Cursor tracking inside body text when frontmatter grows or shrinks
        val targetPhrase = "frase importante"
        val origStart = original.indexOf(targetPhrase)
        val origEnd = origStart + targetPhrase.length
        val (shiftedMd, nextStart, nextEnd) = NoteFrontmatter.applyPreservingCursor(
            markdown = original,
            props = updatedProps,
            cursorStart = origStart,
            cursorEnd = origEnd
        )
        assertEquals(targetPhrase, shiftedMd.substring(nextStart, nextEnd))

        // Removing all properties collapses frontmatter and still points to exact body phrase
        val (strippedMd, strippedStart, strippedEnd) = NoteFrontmatter.applyPreservingCursor(
            markdown = original,
            props = NoteProperties(),
            cursorStart = origStart,
            cursorEnd = origEnd
        )
        assertFalse(strippedMd.startsWith("---"))
        assertEquals(targetPhrase, strippedMd.substring(strippedStart, strippedEnd))
    }

    @Test
    fun expandTemplateUserTemplatesAndSnapshotHeuristicsWorkAsSpecified() {
        val now = epochMillis(2026, 11, 14, 9, 5) // Saturday
        val template = """
            # {{title}}
            Data ISO: {{date}} | BR: {{date:DD/MM/YYYY}} | Hora: {{time}} | Ano: {{year}} | Dia: {{weekday}} | {{unknown}}
        """.trimIndent()

        val expanded = expandTemplate(template, title = "Resumo Diário", nowMillis = now, zone = saoPaulo)
        assertTrue(expanded.contains("# Resumo Diário"))
        assertTrue(expanded.contains("Data ISO: 2026-11-14"))
        assertTrue(expanded.contains("BR: 14/11/2026"))
        assertTrue(expanded.contains("Hora: 09:05"))
        assertTrue(expanded.contains("Ano: 2026"))
        assertTrue(expanded.contains("{{unknown}}"))
        assertFalse(expanded.contains("{{date}}"))

        val userNotes = listOf(
            Note(id = 1L, title = "Template Aula", markdown = "---\ntags: [template/aula]\n---\n## Tópicos"),
            Note(id = 2L, title = "Fichamento", markdown = "---\nfolder: Templates/Leitura\n---\n## Resumo"),
            Note(id = 3L, title = "Normal", markdown = "Sem tag nem pasta de modelo"),
            Note(id = 4L, title = "Template Apagado", markdown = "#template\nDescartado", trashedAt = now)
        )
        val discovered = userTemplatesFromNotes(userNotes)
        assertEquals(listOf("Fichamento", "Template Aula"), discovered.map { it.title })
        assertTrue(discovered.all { it.isUserTemplate })

        val baseText = "Texto inicial com cinquenta caracteres suficientes para teste de snapshot."
        assertFalse(shouldCreateSnapshot("Nota", baseText, "Nota", baseText, lastSnapshotAt = now - 60_000L, now = now))
        assertTrue(shouldCreateSnapshot("Nota", baseText, "Novo Título", baseText, lastSnapshotAt = now - 1_000L, now = now))
        assertTrue(shouldCreateSnapshot("Nota", baseText, "Nota", "$baseText Acréscimo de mais de 24 chars!", lastSnapshotAt = now - 1_000L, now = now))

        // Mid-text replacement >= 24 chars with identical total length
        val left = "Início fixo "
        val right = " fim fixo."
        val midA = left + "AAAAAAAAAAAAAAAAAAAAAAAAAAAA" + right
        val midB = left + "BBBBBBBBBBBBBBBBBBBBBBBBBBBB" + right
        assertEquals(midA.length, midB.length)
        assertTrue(shouldCreateSnapshot("Nota", midA, "Nota", midB, lastSnapshotAt = now - 5_000L, now = now))

        // Small edit: creates if no snapshot yet or >= 5 minutes elapsed, skips inside cooldown window
        assertTrue(shouldCreateSnapshot("Nota", baseText, "Nota", "$baseText!", lastSnapshotAt = null, now = now))
        assertFalse(shouldCreateSnapshot("Nota", baseText, "Nota", "$baseText!", lastSnapshotAt = now - 60_000L, now = now))
        assertTrue(shouldCreateSnapshot("Nota", baseText, "Nota", "$baseText!", lastSnapshotAt = now - 300_000L, now = now))
    }

    @Test
    fun searchLinksAndDataviewScaleCleanlyAcrossHundredsOfNotes() {
        val baseTime = epochMillis(2026, 11, 1, 8, 0)
        val notes = (1..360L).map { idx ->
            val domain = when ((idx % 3L).toInt()) {
                0 -> "Exatas/Algebra"
                1 -> "Direito/Constitucional"
                else -> "Biologia/Citologia"
            }
            val nextIdx = if (idx < 360L) idx + 1L else 1L
            val tag = when ((idx % 3L).toInt()) {
                0 -> "mat/algebra"
                1 -> "dir/const"
                else -> "bio/celula"
            }
            val md = buildString {
                appendLine("---")
                appendLine("folder: $domain")
                appendLine("aliases: [Resumo $idx, Alias$idx]")
                appendLine("tags: [$tag, revisao]")
                appendLine("prioridade: ${(idx % 5L) + 1L}")
                appendLine("---")
                appendLine("# Tópico $idx")
                appendLine("Conteúdo principal sobre [[Nota $nextIdx]] e `[[Ignorado $idx]]` além de #estudo/lote${idx % 4L}.")
                if (idx % 6L == 0L) {
                    appendLine("- [ ] Exercício lote $idx")
                }
            }
            Note(
                id = idx,
                title = "Nota $idx",
                markdown = md,
                updatedAt = baseTime + idx * 60_000L,
                trashedAt = if (idx > 350L) baseTime + 999_999L else null
            )
        }

        val startNs = System.nanoTime()
        val algebraHits = ObsidianSearchEngine.search(notes, "path:Exatas/Algebra tag:mat/algebra -\"Alias999\"")
        val regexHits = ObsidianSearchEngine.search(notes, """/Exercício lote (6|12)\b/""")
        val folderTree = buildFolderTree(notes)
        val tagTree = buildTagTree(notes)
        val dvNotes = DataviewQueryEngine.execute(
            """
            LIST
            FROM #dir/const
            SORT updated DESC
            LIMIT 10
            """.trimIndent(),
            notes
        )
        val dvTasks = DataviewQueryEngine.execute(
            """
            TASK
            FROM "Exatas"
            """.trimIndent(),
            notes
        )
        val linkIndex = NoteLinkIndex(notes, emptyList())
        val firstNote = notes.first()
        val firstRefs = NoteReferences.parse(firstNote.markdown)
        val boundFirst = NoteReferences.boundReferences(firstNote.markdown, notes, emptyMap())
        val backlinksTo2 = NoteConnections.backlinksWithContext(
            targetNoteId = 2L,
            notes = notes,
            links = listOf(NoteLink(sourceId = 1L, reference = "Nota 2", targetId = 2L))
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue("Bulk Obsidian operations took too long: ${elapsedMs}ms", elapsedMs < 3_000L)
        assertEquals(120, algebraHits.size)
        assertEquals(listOf(6L, 12L), regexHits.map { it.id }.sorted())
        assertEquals(3, folderTree.size)
        assertEquals(350, folderTree.sumOf { it.totalNotes })
        assertTrue(tagTree.any { it.segment == "estudo" && it.totalNotes == 350 })
        assertEquals(10, dvNotes.notes.size)
        assertTrue(dvNotes.notes.all { it.trashedAt == null && it.id % 3L == 1L })
        assertTrue(dvTasks.tasks.isNotEmpty())
        assertEquals(1, firstRefs.size)
        assertEquals(2L, boundFirst["Nota 2"])
        assertEquals(2L, linkIndex.target(1L, firstRefs.single())?.id)
        assertEquals(1, backlinksTo2.size)
        assertEquals(1L, backlinksTo2.single().source.id)

        // Verify zero frontmatter/tag regression on active notes
        val sample = notes[5] // idx == 6L
        val (props, body) = NoteFrontmatter.parse(sample.markdown)
        assertEquals("Exatas/Algebra", props.folder)
        assertEquals("2", props.custom["prioridade"])
        assertTrue(body.contains("[[Nota 7]]"))
        assertTrue("mat/algebra" in noteTags(sample.markdown))
    }

    @Test
    fun knowledgeGraphLayoutNeighborhoodNotesPlusTagsAndCanvasBoardContractsWork() {
        // 1. KnowledgeGraph.computeForceDirectedLayout with pinned center & bounds check
        val nodes = (1..40L).map { id ->
            GraphNode(
                id = id,
                title = "Nó $id",
                folder = if (id % 2L == 0L) "Exatas" else "Humanas",
                primaryTag = if (id % 2L == 0L) "exatas" else "humanas",
                degree = if (id == 1L) 6 else 2
            )
        }
        val edges = (2..40L).map { id -> GraphEdge(sourceId = 1L, targetId = id) } +
            listOf(GraphEdge(2L, 3L), GraphEdge(4L, 5L))
        val layout = computeForceDirectedLayout(
            nodes = nodes,
            edges = edges,
            width = 800f,
            height = 600f,
            pinnedCenterId = 1L,
            iterations = 50
        )
        assertEquals(40, layout.size)
        assertEquals(400f, layout.getValue(1L).x, 1e-3f)
        assertEquals(300f, layout.getValue(1L).y, 1e-3f)
        layout.values.forEach { pt ->
            assertFalse(pt.x.isNaN() || pt.y.isNaN())
            assertTrue(pt.x in 36f..764f)
            assertTrue(pt.y in 36f..564f)
        }

        // 2. KnowledgeGraph.localGraphNeighborhood across depths 0, 1, 2
        val chainNotes = (1..7L).map { Note(id = it, title = "N$it", markdown = "") }
        val chainEdges = listOf(1L to 2L, 2L to 3L, 3L to 4L, 6L to 7L)
        val (d0Notes, d0Edges) = localGraphNeighborhood(1L, chainNotes, chainEdges, depth = 0)
        assertEquals(listOf(1L), d0Notes.map { it.id })
        assertTrue(d0Edges.isEmpty())

        val (d1Notes, d1Edges) = localGraphNeighborhood(1L, chainNotes, chainEdges, depth = 1)
        assertEquals(setOf(1L, 2L), d1Notes.map { it.id }.toSet())
        assertEquals(listOf(1L to 2L), d1Edges)

        val (d2Notes, d2Edges) = localGraphNeighborhood(1L, chainNotes, chainEdges, depth = 2)
        assertEquals(setOf(1L, 2L, 3L), d2Notes.map { it.id }.toSet())
        assertEquals(setOf(1L to 2L, 2L to 3L), d2Edges.toSet())
        assertFalse(d2Notes.any { it.id == 6L || it.id == 7L })

        // 3. NotesPlus.noteTags contract
        val mdWithNoise = "---\ntags: [Fisica/Optica]\n---\nEstudo #fisica/ondas e #fff `#fake-tag`"
        assertEquals(listOf("fisica/ondas", "fisica/optica"), noteTags(mdWithNoise))

        // 4. CanvasBoard & CanvasDocument / CanvasNodeItem / CanvasEdgeItem contracts
        assertEquals(CanvasNodeType.TEXT, CanvasNodeType.fromWire("text"))
        assertEquals(CanvasNodeType.NOTE, CanvasNodeType.fromWire("note"))
        assertEquals(CanvasNodeType.NOTE, CanvasNodeType.fromWire("file"))
        assertEquals(CanvasNodeType.GROUP, CanvasNodeType.fromWire("group"))
        assertEquals(CanvasNodeType.TEXT, CanvasNodeType.fromWire("unknown"))

        val nText = CanvasNodeItem(id = "n1", type = CanvasNodeType.TEXT, x = 10f, y = 20f, width = 240f, height = 150f, title = "Resumo", text = "Texto")
        val nNote = CanvasNodeItem(id = "n2", type = CanvasNodeType.NOTE, x = 320f, y = 40f, width = 260f, height = 160f, title = "Nota", noteId = 42L, colorArgb = ObsidianPalette[1])
        val nGroup = CanvasNodeItem(id = "g1", type = CanvasNodeType.GROUP, x = 0f, y = 0f, width = 620f, height = 340f, title = "Bloco")
        val edge = CanvasEdgeItem(id = "e1", fromNode = nText.id, toNode = nNote.id, label = "relaciona")
        val doc = CanvasDocument(nodes = listOf(nGroup, nText, nNote), edges = listOf(edge), viewportPanX = 12f, viewportPanY = -8f, viewportScale = 1.25f)
        assertEquals(3, doc.nodes.size)
        assertEquals(1, doc.edges.size)
        assertEquals(42L, doc.nodes.last().noteId)

        val board = CanvasBoard(id = 7L, title = "Mapa Conceitual", jsonData = """{"version":1,"nodes":[],"edges":[]}""", createdAt = 100L, updatedAt = 200L)
        assertEquals(7L, board.id)
        assertEquals("Mapa Conceitual", board.title)
        assertTrue(board.jsonData.contains("\"version\":1"))
    }
}
