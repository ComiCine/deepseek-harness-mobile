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
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the published upstream LLM and DeepSeek provider packages in the embedded Node runtime. */
@RunWith(AndroidJUnit4::class)
class UpstreamProviderRuntimeInstrumentedTest {
    @Test
    fun unmodifiedUpstreamProviderRegistersRoutesModelAndStreamsFromLoopback() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val sandbox = File(baseContext.cacheDir, "upstream-provider-${System.nanoTime()}").apply { mkdirs() }
        val runtimeContext = IsolatedRuntimeContext(baseContext, File(sandbox, "files").apply { mkdirs() })
        val runtime = EmbeddedNodeRuntime(runtimeContext)
        val server = LoopbackMessagesServer()
        try {
            // This is the upstream published package. npm resolves its original transport and Cordis peers.
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

            val credentialFile = File(runtime.pluginsRoot, "provider-test-credentials.yaml")
            credentialFile.writeText(
                "version: 1\nrefs:\n  DEEPSEEK_API_KEY: local-instrumented-provider-key\n",
                StandardCharsets.UTF_8,
            )
            Os.chmod(credentialFile.absolutePath, 0x180) // 0600, required by upstream credentials-local.

            val result = JSONObject(
                runtime.evaluateAsync(
                    upstreamProviderExercise(
                        baseUrl = server.baseUrl,
                        credentialPath = credentialFile.absolutePath,
                    ),
                    timeoutMs = 90_000,
                ),
            )

            val providers = result.getJSONArray("providers")
            assertTrue("original provider should register the official route", providers.hasProvider("deepseek-official"))
            assertEquals("deepseek-official", result.getJSONObject("resolvedModel").getString("provider"))
            assertEquals("deepseek-flash", result.getJSONObject("resolvedModel").getString("id"))
            assertTrue("the original route should expose its configured catalog", result.getJSONArray("models").hasModel("deepseek-flash"))
            assertTrue("the unmodified provider should yield streamed text deltas", result.getJSONArray("chunks").hasType("text-delta"))
            assertEquals("Original upstream provider stream", result.getString("text"))
            assertEquals("stop", result.getJSONObject("finish").getString("kind"))

            val request = server.awaitRequest()
            assertEquals("POST", request.method)
            assertEquals("/anthropic/v1/messages", request.path)
            assertEquals("local-instrumented-provider-key", request.headers["x-api-key"])
            assertEquals("deepseek-flash", request.body.getString("model"))
            assertTrue(
                "the upstream adapter should serialize the original user prompt",
                request.body.getJSONArray("messages").getJSONObject(0)
                    .getJSONArray("content").getJSONObject(0).getString("text") == "exercise original provider",
            )
        } finally {
            server.close()
            runtime.close()
            sandbox.deleteRecursively()
        }
    }

    private fun upstreamProviderExercise(baseUrl: String, credentialPath: String): String {
        val endpointJson = JSONObject.quote(baseUrl)
        val credentialPathJson = JSONObject.quote(credentialPath)
        return """
            (async () => {
              const path = require('node:path');
              const { createRequire } = require('node:module');
              const root = globalThis.__dshPluginRoot;
              const rootRequire = createRequire(path.join(root, 'package.json'));
              const { importOriginalPlugin } = rootRequire(path.join(root, 'plugin-importer.mjs'));
              const [cordisModule, llmModule, credentialModule, providerModule] = await Promise.all([
                importOriginalPlugin('@deepseek-ai/cordis'),
                importOriginalPlugin('@deepseek-ai/dsh-llm'),
                importOriginalPlugin('@deepseek-ai/dsh-credentials-local'),
                importOriginalPlugin('@deepseek-ai/dsh-llm-deepseek-api-key'),
              ]);
              const Context = cordisModule.Context;
              const LlmRuntime = llmModule.default;
              const LocalCredentialProvider = credentialModule.default;
              const DeepSeekApiKeyProvider = providerModule.default ?? providerModule;
              const ctx = new Context();
              try {
                await ctx.plugin(LlmRuntime);
                await ctx.plugin(LocalCredentialProvider, {
                  path: $credentialPathJson,
                  watch: false,
                });
                await ctx.plugin(DeepSeekApiKeyProvider, {
                  baseURL: $endpointJson,
                  apiKeyEnv: 'DEEPSEEK_API_KEY',
                });
                await ctx.fiber.await();

                const providers = ctx.llm.listProviders();
                const models = await ctx.llm.listModels('deepseek-official');
                const resolvedModel = await ctx.llm.resolveModelInfo('deepseek-official', 'deepseek-flash');
                const message = llmModule.createUserMessage({
                  source: { kind: 'user' },
                  content: [{ type: 'text', text: 'exercise original provider' }],
                });
                const chunks = [];
                for await (const chunk of ctx.llm.stream({
                  provider: 'deepseek-official',
                  model: 'deepseek-flash',
                  messages: [message],
                })) chunks.push(chunk);
                const text = chunks
                  .filter(chunk => chunk.type === 'text-delta')
                  .map(chunk => chunk.text)
                  .join('');
                const finish = chunks.findLast(chunk => chunk.type === 'finish');
                return JSON.stringify({
                  providers,
                  models: models.map(model => model.id),
                  resolvedModel,
                  chunks: chunks.map(chunk => ({ type: chunk.type, text: chunk.text })),
                  text,
                  finish: finish?.reason,
                });
              } finally {
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

    /** A test-only HTTP/SSE endpoint. The product runtime never starts or depends on this server. */
    private class LoopbackMessagesServer : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val complete = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val captured = AtomicReference<CapturedRequest?>()
        private val worker = Thread(::serve, "upstream-provider-loopback").apply { isDaemon = true; start() }

        val baseUrl: String = "http://127.0.0.1:${listener.localPort}/anthropic"

        fun awaitRequest(): CapturedRequest {
            assertTrue("original provider did not reach the loopback Messages endpoint", complete.await(20, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("loopback Messages endpoint failed", it) }
            return captured.get() ?: throw AssertionError("loopback endpoint captured no request")
        }

        private fun serve() {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 20_000
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
                    val requestParts = requestLine.split(' ', limit = 3)
                    check(requestParts.size >= 2) { "Malformed HTTP request line" }
                    captured.set(
                        CapturedRequest(
                            method = requestParts[0],
                            path = requestParts[1],
                            headers = headers,
                            body = JSONObject(body.toString(StandardCharsets.UTF_8)),
                        ),
                    )
                    complete.countDown()

                    val output = socket.getOutputStream()
                    output.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream; charset=utf-8\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
                    )
                    for (event in SSE_EVENTS) {
                        val bytes = event.toByteArray(StandardCharsets.UTF_8)
                        output.write((bytes.size.toString(16) + "\r\n").toByteArray(StandardCharsets.US_ASCII))
                        output.write(bytes)
                        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                        output.flush()
                        Thread.sleep(10)
                    }
                    output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
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
                check(readLine(input).isEmpty()) { "Malformed chunk delimiter" }
            }
        }

        companion object {
            private val SSE_EVENTS = listOf(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_local_1\",\"model\":\"deepseek-flash\",\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}\n\n",
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Original upstream provider stream\"}}\n\n",
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}\n\n",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            )
        }
    }

    private fun JSONArray.hasProvider(id: String): Boolean = (0 until length()).any { getJSONObject(it).optString("id") == id }
    private fun JSONArray.hasModel(id: String): Boolean = (0 until length()).any { getString(it) == id }
    private fun JSONArray.hasType(type: String): Boolean = (0 until length()).any { getJSONObject(it).optString("type") == type }
}
