package ai.comicine.dshmobile.plugins

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

/** Runs the original AgentLoop and provider packages in the on-device Node runtime. */
@RunWith(AndroidJUnit4::class)
class UpstreamAgentLoopInstrumentedTest {
    @Test
    fun unmodifiedUpstreamAgentLoopCompletesSessionTurnThroughLoopbackProvider() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val sandbox = File(baseContext.cacheDir, "upstream-agent-loop-${System.nanoTime()}").apply { mkdirs() }
        val runtimeContext = IsolatedRuntimeContext(baseContext, File(sandbox, "files").apply { mkdirs() })
        val runtime = EmbeddedNodeRuntime(runtimeContext)
        val server = LoopbackMessagesServer()
        try {
            // These are the original published packages, installed into the app-private embedded npm project.
            runtime.call(
                "installPackage",
                "@deepseek-ai/dsh-agent-loop@0.2.2-rc.3",
                "",
                "",
                timeoutMs = 10 * 60 * 1000L,
            )
            runtime.call(
                "installPackage",
                "@deepseek-ai/dsh-llm-deepseek-api-key@0.2.2-rc.3",
                "",
                "",
                timeoutMs = 10 * 60 * 1000L,
            )
            runtime.call(
                "installPackage",
                "@deepseek-ai/dsh-credentials-local@0.2.2-rc.3",
                "",
                "",
                timeoutMs = 10 * 60 * 1000L,
            )

            // credentials-local validates owner-only permissions. This is a read-only test fixture,
            // not the application's Keystore-backed credential provider integration.
            val credentialFile = File(runtime.pluginsRoot, "agent-loop-test-credentials.yaml")
            credentialFile.writeText(
                "version: 1\nrefs:\n  DEEPSEEK_API_KEY: local-instrumented-agent-key\n",
                StandardCharsets.UTF_8,
            )
            Os.chmod(credentialFile.absolutePath, 0x180) // 0600

            val result = JSONObject(
                runtime.evaluateAsync(
                    upstreamAgentLoopExercise(
                        baseUrl = server.baseUrl,
                        credentialPath = credentialFile.absolutePath,
                    ),
                    timeoutMs = 120_000,
                ),
            )

            assertEquals("Original AgentLoop SSE response", result.getString("assistantText"))
            assertEquals("completed", result.getString("turnEndReason"))
            assertEquals("idle", result.getString("agentStatus"))
            assertEquals(
                "exercise original AgentLoop on Android",
                result.getString("userText"),
            )
            val eventTypes = result.getJSONArray("eventTypes")
            for (requiredEvent in listOf("turn/start", "step/start", "user/message", "assistant/message", "step/end", "turn/end")) {
                assertTrue("original Session should contain $requiredEvent; got $eventTypes", eventTypes.hasString(requiredEvent))
            }

            val request = server.awaitRequest()
            assertEquals("POST", request.method)
            assertEquals("/anthropic/v1/messages", request.path)
            assertEquals("local-instrumented-agent-key", request.headers["x-api-key"])
            assertEquals("deepseek-flash", request.body.getString("model"))
            assertTrue(
                "the real provider request should contain the AgentLoop user prompt",
                request.body.toString().contains("exercise original AgentLoop on Android"),
            )
        } finally {
            server.close()
            runtime.close()
            sandbox.deleteRecursively()
        }
    }

    private fun upstreamAgentLoopExercise(baseUrl: String, credentialPath: String): String {
        val endpointJson = JSONObject.quote(baseUrl)
        val credentialPathJson = JSONObject.quote(credentialPath)
        return """
            (async () => {
              const path = require('node:path');
              const { createRequire } = require('node:module');
              const root = globalThis.__dshPluginRoot;
              const rootRequire = createRequire(path.join(root, 'package.json'));
              const { importOriginalPlugin } = rootRequire(path.join(root, 'plugin-importer.mjs'));
              const [cordis, llm, session, sessionProjection, systemPrompt, tools, nativeExecutor,
                agentRegistry, agentLoop, deepseekProvider, localCredentials] = await Promise.all([
                importOriginalPlugin('@deepseek-ai/cordis'),
                importOriginalPlugin('@deepseek-ai/dsh-llm'),
                importOriginalPlugin('@deepseek-ai/dsh-session'),
                importOriginalPlugin('@deepseek-ai/dsh-session-projection'),
                importOriginalPlugin('@deepseek-ai/dsh-system-prompt'),
                importOriginalPlugin('@deepseek-ai/dsh-tools'),
                importOriginalPlugin('@deepseek-ai/dsh-tool-executor-native'),
                importOriginalPlugin('@deepseek-ai/dsh-agent'),
                importOriginalPlugin('@deepseek-ai/dsh-agent-loop'),
                importOriginalPlugin('@deepseek-ai/dsh-llm-deepseek-api-key'),
                importOriginalPlugin('@deepseek-ai/dsh-credentials-local'),
              ]);
              const ctx = new cordis.Context();
              let handle;
              try {
                await ctx.plugin(llm.default);
                await ctx.plugin(session.default);
                await ctx.plugin(sessionProjection.default ?? sessionProjection);
                await ctx.plugin(systemPrompt.default);
                await ctx.plugin(tools.default);
                await ctx.plugin(nativeExecutor.default ?? nativeExecutor);
                await ctx.plugin(agentRegistry.default);
                await ctx.plugin(localCredentials.default, { path: $credentialPathJson, watch: false });
                await ctx.plugin(deepseekProvider.default ?? deepseekProvider, {
                  baseURL: $endpointJson,
                  apiKeyEnv: 'DEEPSEEK_API_KEY',
                });
                await ctx.plugin(agentLoop.default, { agents: [] });
                await ctx.fiber.await();

                handle = await ctx.agents.create({
                  sessionId: 'android-upstream-agent-loop-turn',
                  agentOptions: { provider: 'deepseek-official', model: 'deepseek-flash' },
                });
                const prompt = llm.createUserMessage({
                  source: { kind: 'user' },
                  content: [{ type: 'text', text: 'exercise original AgentLoop on Android' }],
                });
                handle.agent.followup(prompt);
                await handle.agent.whenIdle();

                const events = handle.agent.session.snapshotEvents();
                const userEvent = events.findLast(event => event.type === 'user/message');
                const assistantEvent = events.findLast(event => event.type === 'assistant/message');
                const turnEnd = events.findLast(event => event.type === 'turn/end');
                const assistantText = assistantEvent?.data.message.content
                  .filter(block => block.type === 'text')
                  .map(block => block.text)
                  .join('');
                const userText = userEvent?.data.content
                  .filter(block => block.type === 'text')
                  .map(block => block.text)
                  .join('');
                return JSON.stringify({
                  eventTypes: events.map(event => event.type),
                  assistantText,
                  userText,
                  turnEndReason: turnEnd?.data.reason.kind,
                  agentStatus: handle.agent.status,
                });
              } finally {
                if (handle) await handle.dispose();
                await ctx.fiber.dispose();
              }
            })()
        """.trimIndent()
    }

    private class IsolatedRuntimeContext(base: Context, private val isolatedFiles: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: JSONObject,
    )

    /** Test-only HTTP/SSE endpoint bound to loopback; it never leaves the test process. */
    private class LoopbackMessagesServer : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val complete = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val captured = AtomicReference<CapturedRequest?>()
        private val worker = Thread(::serve, "upstream-agent-loop-loopback").apply { isDaemon = true; start() }

        val baseUrl: String = "http://127.0.0.1:${listener.localPort}/anthropic"

        fun awaitRequest(): CapturedRequest {
            assertTrue("original AgentLoop did not call the loopback Messages endpoint", complete.await(20, TimeUnit.SECONDS))
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
                        if (separator > 0) {
                            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                        }
                    }
                    val requestBody = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                        readChunkedBody(input)
                    } else {
                        readExactly(input, headers["content-length"]?.toIntOrNull() ?: 0)
                    }
                    val requestParts = requestLine.split(' ', limit = 3)
                    check(requestParts.size >= 2) { "Malformed HTTP request line" }
                    captured.set(
                        CapturedRequest(
                            method = requestParts[0],
                            path = requestParts[1],
                            headers = headers,
                            body = JSONObject(requestBody.toString(StandardCharsets.UTF_8)),
                        ),
                    )
                    complete.countDown()

                    val responseBody = SSE_EVENTS.joinToString(separator = "").toByteArray(StandardCharsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream; charset=utf-8\r\n" +
                            "Content-Length: ${responseBody.size}\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
                    )
                    output.write(responseBody)
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
            require(size >= 0) { "Negative HTTP body size" }
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
                val line = readLine(input).substringBefore(';').trim()
                val size = line.toInt(16)
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
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_agent_loop\",\"model\":\"deepseek-flash\",\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}\n\n",
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Original AgentLoop SSE response\"}}\n\n",
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}\n\n",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            )
        }
    }

    private fun JSONArray.hasString(value: String): Boolean = (0 until length()).any { getString(it) == value }
}
