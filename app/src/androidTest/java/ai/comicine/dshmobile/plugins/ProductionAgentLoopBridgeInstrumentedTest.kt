package ai.comicine.dshmobile.plugins

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.comicine.dshmobile.data.HarnessDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the production Cordis host and PluginManager AgentLoop APIs against a loopback Messages endpoint. */
@RunWith(AndroidJUnit4::class)
class ProductionAgentLoopBridgeInstrumentedTest {
    @Test
    fun productionBridgeRunsAndRestoresAnOriginalAgentWithConfiguredEndpoint(): Unit = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val sandbox = File(baseContext.cacheDir, "production-agent-bridge-${System.nanoTime()}").apply { mkdirs() }
        val isolated = IsolatedContext(
            baseContext,
            File(sandbox, "files").apply { mkdirs() },
            File(sandbox, "database").apply { mkdirs() },
        )
        val database = HarnessDatabase.open(isolated)
        val manager = PluginManager(isolated, database.plugins())
        val server = LoopbackMessagesServer()
        val sessionId = "production-agent-${System.nanoTime()}"
        val deliveredEvents = CopyOnWriteArrayList<OriginalAgentEvent>()
        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.originalAgentEvents.collect(deliveredEvents::add)
        }
        try {
            val created = manager.createOrRestoreOriginalAgent(
                sessionId = sessionId,
                provider = "deepseek-official",
                model = "deepseek-flash",
                baseURL = server.baseUrl,
            )
            assertFalse("a new session must not be reported as restored", created.restored)

            val events = JSONArray(
                manager.submitUserMessageAndAwaitIdle(
                    sessionId = sessionId,
                    text = "exercise the production AgentLoop bridge",
                    provider = "deepseek-official",
                    model = "deepseek-flash",
                    baseURL = server.baseUrl,
                    apiKey = "production-bridge-loopback-key",
                ),
            )
            assertTrue("Session event array must contain the original turn start", events.hasEvent("turn/start"))
            assertTrue("Session event array must contain the original user message", events.hasEvent("user/message"))
            assertTrue("Session event array must contain the original assistant message", events.hasEvent("assistant/message"))
            assertTrue("Session event array must contain the original turn end", events.hasEvent("turn/end"))
            assertTrue(
                "the Android SharedFlow must receive original Session events before the turn completes",
                deliveredEvents.any { it is OriginalAgentEvent.SessionEvent && it.eventType == "assistant/message" },
            )
            assertTrue(
                "the Android SharedFlow must receive original assistant stream chunks before the turn completes",
                deliveredEvents.filterIsInstance<OriginalAgentEvent.AssistantFrame>().any { frame ->
                    val json = JSONObject(frame.frameJson)
                    json.optString("type") == "chunk" && json.optJSONObject("chunk")?.optString("type") == "text-delta"
                },
            )

            val assistant = events.findEvent("assistant/message")
            val content = assistant.getJSONObject("data").getJSONObject("message").getJSONArray("content")
            assertEquals("Production host AgentLoop response", content.getJSONObject(0).getString("text"))
            val turnEnd = events.findEvent("turn/end").getJSONObject("data")
            assertEquals("completed", turnEnd.getJSONObject("reason").getString("kind"))

            val request = server.awaitRequest()
            assertEquals("POST", request.method)
            assertEquals("/custom-messages/v1/messages", request.path)
            assertEquals("production-bridge-loopback-key", request.headers["x-api-key"])
            assertTrue(request.body.toString().contains("exercise the production AgentLoop bridge"))

            manager.releaseOriginalAgent(sessionId)
            val restored = manager.createOrRestoreOriginalAgent(
                sessionId = sessionId,
                provider = "deepseek-official",
                model = "deepseek-flash",
                baseURL = server.baseUrl,
            )
            assertTrue("the persisted original Session event array must be replayed", restored.restored)
            val replayed = JSONArray(manager.readOriginalSessionEvents(sessionId))
            assertTrue("replayed events retain the committed assistant message", replayed.hasEvent("assistant/message"))
            manager.releaseOriginalAgent(sessionId)
        } finally {
            eventCollector.cancelAndJoin()
            server.close()
            manager.close()
            database.close()
            sandbox.deleteRecursively()
        }
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

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: JSONObject,
    )

    /** The production provider can contact only this in-process test server during this test. */
    private class LoopbackMessagesServer : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val complete = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val captured = AtomicReference<CapturedRequest?>()
        private val worker = Thread(::serve, "production-agent-loopback").apply { isDaemon = true; start() }

        val baseUrl: String = "http://127.0.0.1:${listener.localPort}/custom-messages"

        fun awaitRequest(): CapturedRequest {
            assertTrue("original provider did not reach its configured loopback endpoint", complete.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("loopback Messages endpoint failed", it) }
            return captured.get() ?: throw AssertionError("loopback endpoint captured no request")
        }

        private fun serve() {
            try {
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
                    captured.set(
                        CapturedRequest(
                            method = parts[0],
                            path = parts[1],
                            headers = headers,
                            body = JSONObject(body.toString(StandardCharsets.UTF_8)),
                        ),
                    )
                    complete.countDown()

                    val response = SSE_EVENTS.joinToString(separator = "").toByteArray(StandardCharsets.UTF_8)
                    val output = socket.getOutputStream()
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
            } catch (error: Throwable) {
                failure.set(error)
                complete.countDown()
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
                check(readLine(input).isEmpty()) { "Malformed HTTP chunk delimiter" }
            }
        }

        companion object {
            private val SSE_EVENTS = listOf(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_production_bridge\",\"model\":\"deepseek-flash\",\"usage\":{\"input_tokens\":8,\"output_tokens\":1}}}\n\n",
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Production host AgentLoop response\"}}\n\n",
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}\n\n",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            )
        }
    }

    private fun JSONArray.hasEvent(type: String): Boolean = (0 until length()).any { getJSONObject(it).optString("type") == type }

    private fun JSONArray.findEvent(type: String): JSONObject = (0 until length())
        .map { getJSONObject(it) }
        .first { it.optString("type") == type }
}
