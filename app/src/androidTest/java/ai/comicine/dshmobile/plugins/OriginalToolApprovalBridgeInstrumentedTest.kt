package ai.comicine.dshmobile.plugins

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.comicine.dshmobile.data.HarnessDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs the unmodified upstream filesystem tool through its real sandbox approval path on Android. */
@RunWith(AndroidJUnit4::class)
class OriginalToolApprovalBridgeInstrumentedTest {
    @Test
    fun upstreamFilesystemToolWritesOnlyAfterApprovalAndDeniesWithoutWriting() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val sandbox = File(baseContext.cacheDir, "upstream-tool-approval-${System.nanoTime()}").apply { mkdirs() }
        val isolatedFiles = File(sandbox, "files").apply { mkdirs() }
        val isolatedDatabase = File(sandbox, "database").apply { mkdirs() }
        val isolated = IsolatedContext(baseContext, isolatedFiles, isolatedDatabase)
        val workspace = File(isolatedFiles, "plugins/approval-workspace").apply { mkdirs() }
        val database = HarnessDatabase.open(isolated)
        val manager = PluginManager(isolated, database.plugins())
        val allowedPath = File(workspace, "allowed-write.txt")
        val deniedPath = File(workspace, "denied-write.txt")
        val server = LoopbackMessagesServer(allowedPath, deniedPath)
        try {
            val sandboxPolicy = manager.install("@deepseek-ai/dsh-sandbox-policy@0.2.2-rc.3")
            manager.setConfiguration(
                sandboxPolicy.id,
                JSONObject()
                    .put("mode", "read-only")
                    .put("workspaceRoot", workspace.absolutePath)
                    .toString(),
            )
            manager.enable(sandboxPolicy.id, true)

            val filesystem = manager.install("@deepseek-ai/dsh-fs-sandbox@0.2.2-rc.3")
            manager.setConfiguration(
                filesystem.id,
                JSONObject().put("cwd", workspace.absolutePath).toString(),
            )
            manager.enable(filesystem.id, true)

            val workingDirectory = manager.install("@deepseek-ai/dsh-working-directory@0.2.2-rc.3")
            manager.setConfiguration(
                workingDirectory.id,
                JSONObject().put("defaultDirectory", workspace.absolutePath).toString(),
            )
            manager.enable(workingDirectory.id, true)

            val filesystemTools = manager.install("@deepseek-ai/dsh-tool-fs@0.2.2-rc.3")
            manager.enable(filesystemTools.id, true)

            runApprovedWrite(
                manager = manager,
                server = server,
                workspace = workspace,
                sessionId = "upstream-fs-approval-allow-${System.nanoTime()}",
                filePath = allowedPath,
                allow = true,
            )
            assertEquals("Original upstream filesystem tool content", allowedPath.readText(StandardCharsets.UTF_8))

            val deniedEvents = runApprovedWrite(
                manager = manager,
                server = server,
                workspace = workspace,
                sessionId = "upstream-fs-approval-deny-${System.nanoTime()}",
                filePath = deniedPath,
                allow = false,
            )
            assertFalse("a rejected original approval must not perform the local write", deniedPath.exists())

            val deniedResult = deniedEvents.findEvent("tool/result")
            assertTrue("the original tool pipeline should return the rejected write as an error", deniedResult.getJSONObject("data").getJSONObject("message").getBoolean("isError"))
            assertTrue("the original Session must retain the approval audit", deniedEvents.hasEvent("approval/asked"))
            assertEquals(
                "rejected",
                deniedEvents.findEvent("approval/decided").getJSONObject("data").getString("outcome"),
            )

            val requests = server.awaitRequests()
            assertEquals("each Agent turn must send one tool request and one post-result request", 4, requests.size)
            assertTrue("the actual original tool schema must be sent to DeepSeek", requests.first().body.toString().contains("\"name\":\"write\""))
            assertTrue("the follow-up request must contain an original tool result", requests.any { it.body.toString().contains("tool_result") })
        } finally {
            server.close()
            manager.close()
            database.close()
            sandbox.deleteRecursively()
        }
    }

    private suspend fun runApprovedWrite(
        manager: PluginManager,
        server: LoopbackMessagesServer,
        workspace: File,
        sessionId: String,
        filePath: File,
        allow: Boolean,
    ): JSONArray {
        val info = manager.createOrRestoreOriginalAgent(
            sessionId = sessionId,
            provider = "deepseek-official",
            model = "deepseek-flash",
            baseURL = server.baseUrl,
        )
        assertFalse("each approval case starts with a new upstream Session", info.restored)
        val running = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async(start = CoroutineStart.UNDISPATCHED) {
            JSONArray(
                manager.submitUserMessageAndAwaitIdle(
                    sessionId = sessionId,
                    text = "Use the original write tool to create the requested fixture file.",
                    provider = "deepseek-official",
                    model = "deepseek-flash",
                    baseURL = server.baseUrl,
                    apiKey = "local-tool-approval-loopback-key",
                ),
            )
        }
        val approval = withTimeout(90_000) {
            manager.originalAgentEvents
                .filterIsInstance<OriginalAgentEvent.ApprovalRequested>()
                .first { it.sessionId == sessionId }
        }
        assertEquals("write", approval.toolName)
        assertEquals(
            "the approval payload must retain the upstream file path",
            filePath.absolutePath,
            JSONObject(approval.argumentsJson).optString("file_path"),
        )
        assertTrue("the approval request must be correlated with a real upstream tool-call", !approval.callId.isNullOrBlank())
        manager.resolveOriginalApproval(approval.requestId, allow)
        val events = running.await()

        assertTrue("the Agent turn must complete through the original loop", events.hasEvent("turn/end"))
        assertEquals(
            if (allow) "allowed-once" else "rejected",
            events.findEvent("approval/decided").getJSONObject("data").getString("outcome"),
        )
        assertTrue("the original tool call must be persisted in Session events", events.hasEvent("tool/call"))
        if (allow) {
            assertTrue("the original local write must be persisted in the Session", events.hasEvent("tool/result"))
            val result = events.findEvent("tool/result").getJSONObject("data").getJSONObject("message")
            assertFalse("the allowed upstream tool call must succeed", result.getBoolean("isError"))
            assertTrue("the local workspace must remain under the test sandbox", filePath.canonicalPath.startsWith(workspace.canonicalPath + File.separator))
        }
        manager.releaseOriginalAgent(sessionId)
        return events
    }

    private class IsolatedContext(
        base: Context,
        private val isolatedFiles: File,
        private val isolatedDatabase: File,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
        override fun getDatabasePath(name: String): File = File(isolatedDatabase, name)
    }

    private data class CapturedRequest(val path: String, val body: JSONObject)

    /** Process-local Messages endpoint returning a real tool-use stream, then a final stream. */
    private inner class LoopbackMessagesServer(
        private val allowedPath: File,
        private val deniedPath: File,
    ) : Closeable {
        private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val expectedCount = CountDownLatch(4)
        private val requests = CopyOnWriteArrayList<CapturedRequest>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread(::serve, "original-tool-approval-loopback").apply { isDaemon = true; start() }

        val baseUrl: String = "http://127.0.0.1:${listener.localPort}/approval-messages"

        fun awaitRequests(): List<CapturedRequest> {
            assertTrue("the upstream Agent did not submit all tool and follow-up requests", expectedCount.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("loopback Messages endpoint failed", it) }
            return requests.toList()
        }

        private fun serve() {
            try {
                repeat(4) { index ->
                    listener.accept().use { socket ->
                        socket.soTimeout = 30_000
                        val input = BufferedInputStream(socket.getInputStream())
                        val requestLine = readLine(input)
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = readLine(input)
                            if (line.isEmpty()) break
                            val separator = line.indexOf(':')
                            if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                        }
                        val body = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                            readChunkedBody(input)
                        } else {
                            readExactly(input, headers["content-length"]?.toIntOrNull() ?: 0)
                        }
                        val parts = requestLine.split(' ', limit = 3)
                        check(parts.size >= 2) { "Malformed HTTP request line" }
                        val request = CapturedRequest(parts[1], JSONObject(body.toString(StandardCharsets.UTF_8)))
                        requests += request
                        val isFollowUp = request.body.toString().contains("tool_result")
                        val events = if (isFollowUp) {
                            finalAnswerEvents("Tool turn completed after the local write.")
                        } else {
                            val target = if (requests.size == 1) allowedPath else deniedPath
                            toolUseEvents("toolu_fs_$index", target.absolutePath)
                        }
                        writeSse(socket.getOutputStream(), events)
                        expectedCount.countDown()
                    }
                }
            } catch (error: Throwable) {
                if (!listener.isClosed) failure.set(error)
            }
        }

        override fun close() {
            runCatching { listener.close() }
            worker.join(2_000)
        }

        private fun readLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            var previous = -1
            while (true) {
                val next = input.read()
                if (next < 0) break
                if (previous == '\r'.code && next == '\n'.code) {
                    val line = bytes.toByteArray()
                    return String(line, 0, (line.size - 1).coerceAtLeast(0), StandardCharsets.US_ASCII)
                }
                bytes.write(next)
                previous = next
            }
            return bytes.toString(StandardCharsets.US_ASCII.name())
        }

        private fun readExactly(input: InputStream, size: Int): ByteArray {
            require(size >= 0) { "Negative HTTP request body size" }
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                if (count < 0) throw IllegalStateException("Unexpected end of HTTP request body")
                offset += count
            }
            return bytes
        }

        private fun readChunkedBody(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input).substringBefore(';').trim().toInt(16)
                if (size == 0) {
                    while (readLine(input).isNotEmpty()) Unit
                    return output.toByteArray()
                }
                output.write(readExactly(input, size))
                readExactly(input, 2)
            }
        }

        private fun writeSse(output: java.io.OutputStream, events: List<String>) {
            val response = events.joinToString(separator = "").toByteArray(StandardCharsets.UTF_8)
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream; charset=utf-8\r\n" +
                    "Content-Length: ${response.size}\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
            )
            output.write(response)
            output.flush()
        }
    }

    private fun toolUseEvents(id: String, filePath: String): List<String> {
        val arguments = JSONObject()
            .put("file_path", filePath)
            .put("content", "Original upstream filesystem tool content")
            .put("sandbox_permissions", "workspace-write")
            .put("justification", "Write a harmless approval integration fixture in the local app workspace.")
        return listOf(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_$id\",\"model\":\"deepseek-flash\",\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}\n\n",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"$id\",\"name\":\"write\",\"input\":{}}}\n\n",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":${JSONObject.quote(arguments.toString())}}}\n\n",
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":4}}\n\n",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
        )
    }

    private fun finalAnswerEvents(text: String): List<String> = listOf(
        "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_tool_final\",\"model\":\"deepseek-flash\",\"usage\":{\"input_tokens\":20,\"output_tokens\":1}}}\n\n",
        "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":${JSONObject.quote(text)}}}\n\n",
        "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
        "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}\n\n",
        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
    )

    private fun JSONArray.hasEvent(type: String): Boolean = (0 until length()).any { getJSONObject(it).optString("type") == type }

    private fun JSONArray.findEvent(type: String): JSONObject = (0 until length())
        .asSequence()
        .map { getJSONObject(it) }
        .lastOrNull { it.optString("type") == type }
        ?: throw AssertionError("missing upstream event $type: $this")
}
