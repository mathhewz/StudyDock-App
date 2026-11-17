package com.studydock.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AssistantProxyClientTest {

    @Test
    fun validateEndpointUrlRejectsCredentialsAndRemoteHttp() {
        assertThrows(IllegalArgumentException::class.java) {
            AssistantProxyClient.validateEndpointUrl(URL("https://user:secret@api.openai.com/v1/chat/completions"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantProxyClient.validateEndpointUrl(URL("http://api.openai.com/v1/chat/completions"))
        }

        // Valid HTTPS and loopback development hosts
        AssistantProxyClient.validateEndpointUrl(URL("https://api.openai.com/v1/chat/completions"))
        AssistantProxyClient.validateEndpointUrl(URL("http://localhost:8080/chat"))
        AssistantProxyClient.validateEndpointUrl(URL("http://10.0.2.2:8787/chat"))
        AssistantProxyClient.validateEndpointUrl(URL("http://127.0.0.1:8787/chat"))
    }

    @Test
    fun readStreamBoundedRejectsOversizedSuccessAndTruncatesErrors() {
        val ok = AssistantProxyClient.readStreamBounded(
            ByteArrayInputStream("olá".toByteArray(Charsets.UTF_8)),
            maxBytes = 16,
            failOnOverflow = true
        )
        assertEquals("olá", ok)

        val large = ByteArray(100) { 'a'.code.toByte() }
        assertThrows(IllegalStateException::class.java) {
            AssistantProxyClient.readStreamBounded(
                ByteArrayInputStream(large),
                maxBytes = 64,
                failOnOverflow = true
            )
        }

        val truncated = AssistantProxyClient.readStreamBounded(
            ByteArrayInputStream(large),
            maxBytes = 32,
            failOnOverflow = false
        )
        assertEquals(32, truncated.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun executePostJsonRequestBlocksRedirectsAndOversizedBodies() {
        val leakedHits = AtomicInteger(0)
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                        }
                        if (contentLength > 0) {
                            val skipped = CharArray(contentLength)
                            reader.read(skipped, 0, contentLength)
                        }
                        val out = client.getOutputStream()
                        when {
                            requestLine.contains(" /redirect ") -> {
                                val resp = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$port/leaked\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                out.write(resp.toByteArray(Charsets.ISO_8859_1))
                            }
                            requestLine.contains(" /leaked ") -> {
                                leakedHits.incrementAndGet()
                                val body = """{"ok":true}""".toByteArray(Charsets.UTF_8)
                                val head = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                out.write(head.toByteArray(Charsets.ISO_8859_1))
                                out.write(body)
                            }
                            requestLine.contains(" /huge ") -> {
                                val size = MAX_HTTP_RESPONSE_BYTES + 4096
                                val head = "HTTP/1.1 200 OK\r\nContent-Length: $size\r\nConnection: close\r\n\r\n"
                                out.write(head.toByteArray(Charsets.ISO_8859_1))
                                out.write(ByteArray(size) { 'x'.code.toByte() })
                            }
                        }
                        out.flush()
                    }
                }
            }
        }
        try {
            val base = "http://127.0.0.1:$port"
            val redirectError = assertThrows(IllegalStateException::class.java) {
                AssistantProxyClient.executePostJsonRequest(
                    providerLabel = "OpenAI-compat",
                    url = URL("$base/redirect"),
                    bodyBytes = "{}".toByteArray(Charsets.UTF_8),
                    headers = mapOf("Authorization" to "Bearer secret-key"),
                    followRedirects = false
                )
            }
            assertTrue(redirectError.message.orEmpty().contains("302"))
            assertEquals(0, leakedHits.get())

            assertThrows(IllegalStateException::class.java) {
                AssistantProxyClient.executePostJsonRequest(
                    providerLabel = "OpenAI-compat",
                    url = URL("$base/huge"),
                    bodyBytes = "{}".toByteArray(Charsets.UTF_8),
                    headers = emptyMap(),
                    followRedirects = false
                )
            }
        } finally {
            runCatching { server.close() }
            worker.join(2_000L)
        }
    }

    @Test
    fun parseReplyActionsParsesCanvasNodesEdgesAndLaTeX() {
        val raw = """
            Veja a análise com **negrito** e ${'$'}E = mc^2${'$'}.
            ```text
            <<<ACTION:CANVAS
            TITLE: Ciclo de Krebs
            SUMMARY: Etapas e enzimas principais
            LAYOUT: tree
            NODE: n1 | text | violet | Piruvato | Substrato inicial\nForma Acetil-CoA
            NODE: n2 | note | blue | [[Citrato]] | Condensação com oxaloacetato
            NODE: n3 | cyan | red | ATP e NADH | Saldo energético final
            EDGE: n1 -> n2 | PDH
            EDGE: Citrato -> n3 | ciclo
            <<<END_ACTION>>>
            ```
        """.trimIndent()

        val parsed = AssistantProxyClient.parseReplyActions(raw)
        assertFalse(parsed.cleanText.contains("```"))
        assertTrue(parsed.cleanText.contains("**negrito**"))
        assertEquals(1, parsed.actions.size)

        val canvas = parsed.actions[0] as GeneratedStudyObject.CanvasObj
        assertEquals("Ciclo de Krebs", canvas.title)
        assertEquals("Etapas e enzimas principais", canvas.summary)
        assertEquals("tree", canvas.layout)
        assertEquals(3, canvas.nodes.size)
        assertEquals("Substrato inicial\nForma Acetil-CoA", canvas.nodes[0].text)
        assertEquals("Citrato", canvas.nodes[1].title)
        assertEquals("note", canvas.nodes[1].type)
        assertEquals(2, canvas.edges.size)
        assertEquals("n2", canvas.edges[1].from)
        assertEquals("n3", canvas.edges[1].to)
        assertEquals("ciclo", canvas.edges[1].label)

        val built = AssistantProxyClient.layoutCanvasBoard(
            canvas = canvas,
            noteIdsByTitle = mapOf("citrato" to 42L)
        )
        assertEquals(3, built.nodes.size)
        assertEquals("text", built.nodes[0].type)
        assertEquals("note", built.nodes[1].type)
        assertEquals(42L, built.nodes[1].noteId)
        assertEquals(2, built.edges.size)

        val jsonStr = AssistantProxyClient.buildCanvasBoardJson(
            canvas = canvas,
            noteIdsByTitle = mapOf("citrato" to 42L)
        )
        assertTrue(jsonStr.startsWith("""{"version":1,"nodes":["""))
        assertTrue(jsonStr.contains(""""noteId":42"""))
        assertTrue(jsonStr.contains("""Substrato inicial\nForma Acetil-CoA"""))

        // Verify bounding boxes across tree, grid, and radial layouts never overlap
        listOf("tree", "grid", "radial").forEach { layoutMode ->
            val variant = AssistantProxyClient.layoutCanvasBoard(canvas.copy(layout = layoutMode))
            assertEquals(3, variant.nodes.size)
            for (i in variant.nodes.indices) {
                for (j in i + 1 until variant.nodes.size) {
                    val a = variant.nodes[i]
                    val b = variant.nodes[j]
                    val overlapX = a.x < b.x + b.width && a.x + a.width > b.x
                    val overlapY = a.y < b.y + b.height && a.y + a.height > b.y
                    assertFalse("$layoutMode layout nodes $i/$j overlapped", overlapX && overlapY)
                }
            }
        }
    }

    @Test
    fun parseReplyActionsPreservesRealCodeFenceBeforeAction() {
        val raw = """
            Exemplo real:
            ```kotlin
            val ans = 42
            ```
            <<<ACTION:TASK
            TITLE: Revisar Álgebra Linear
            SUBJECT: Matemática
            DUE_DAYS: 2
            PRIORITY: 1
            DETAILS: Resolver 10 exercícios
            <<<END_ACTION>>>
        """.trimIndent()

        val parsed = AssistantProxyClient.parseReplyActions(raw)
        assertTrue(parsed.cleanText.contains("```kotlin\nval ans = 42\n```"))
        assertEquals(1, parsed.actions.size)
        assertTrue(parsed.actions[0] is GeneratedStudyObject.TaskObj)
    }

    @Test
    fun professorPromptUnlocksCanvasNotesFlashcardsTasksGoals() {
        val prompt = AssistantProxyClient.buildSystemInstructions(
            mode = StudyTutorMode.PROFESSOR_CRITIC,
            feynmanLevel = 1,
            focusTopic = "Termodinâmica"
        )
        assertTrue(prompt.contains("<<<ACTION:CANVAS"))
        assertTrue(prompt.contains("<<<ACTION:NOTE"))
        assertTrue(prompt.contains("<<<ACTION:FLASHCARD"))
        assertTrue(prompt.contains("<<<ACTION:TASK"))
        assertTrue(prompt.contains("<<<ACTION:GOAL"))
        assertTrue(prompt.contains("Markdown"))
        assertTrue(prompt.contains("LaTeX"))
    }
}
