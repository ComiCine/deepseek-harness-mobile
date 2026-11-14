package ai.comicine.dshmobile.engine

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.comicine.dshmobile.data.HarnessDatabase
import ai.comicine.dshmobile.data.HarnessRepository
import ai.comicine.dshmobile.data.HarnessSettings
import ai.comicine.dshmobile.plugins.OriginalAgentEvent
import ai.comicine.dshmobile.plugins.PluginManager
import ai.comicine.dshmobile.security.CredentialStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Verifies the product chat engine sends and persists a turn through the original on-device AgentLoop. */
@RunWith(AndroidJUnit4::class)
class HarnessEngineOriginalAgentInstrumentedTest {
    @Test
    fun chatUsesOriginalAgentLoopStreamsAndPersistsLocally(): Unit = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val sandbox = File(base.cacheDir, "harness-engine-original-agent-runtime").apply { mkdirs() }
        val isolated = IsolatedContext(
            base = base,
            files = File(sandbox, "files").apply { mkdirs() },
            cache = File(sandbox, "cache").apply { mkdirs() },
            databases = File(sandbox, "databases").apply { mkdirs() },
            preferencePrefix = "engine-test-${System.nanoTime()}",
        )
        isolated.getDatabasePath("deepseek-harness.db").delete()
        val database = HarnessDatabase.open(isolated)
        val repository = HarnessRepository(database, isolated)
        val credentials = CredentialStore(isolated)
        val pluginManager = PluginManager(isolated, database.plugins())
        val engine = HarnessEngine(isolated, repository, credentials, pluginManager)
        val server = MockWebServer()
        val liveSnapshots = CopyOnWriteArrayList<LiveAssistant>()
        val originalEvents = CopyOnWriteArrayList<OriginalAgentEvent>()
        var liveCollector: kotlinx.coroutines.Job? = null
        var originalCollector: kotlinx.coroutines.Job? = null
        try {
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                    .setHeader("Cache-Control", "no-cache")
                    .setBody(MESSAGES_RESPONSE.trimIndent() + "\n\n"),
            )
            val apiKey = "harness-engine-loopback-test-key"
            val prompt = "Exercise the product chat path through the original AgentLoop."
            val answer = "Product chat integration response"
            engine.setApiKey(apiKey)
            engine.updateSettings(
                HarnessSettings(
                    baseUrl = server.url("/product-messages").toString().removeSuffix("/"),
                    model = "deepseek-flash",
                    systemPrompt = "Product integration system prompt",
                    maxAgentSteps = 8,
                ),
            )
            val session = engine.newSession()
            assertEquals("deepseek-official", session.provider)
            originalCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                pluginManager.originalAgentEvents.collect(originalEvents::add)
            }
            liveCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                engine.liveAssistants.collect { current -> current[session.id]?.let(liveSnapshots::add) }
            }

            withTimeout(10 * 60_000L) { engine.start(session.id, prompt).join() }

            val messages = repository.messagesNow(session.id)
            val sessionAfterTurn = repository.session(session.id)
            val logRows = database.logs().observeRecent(session.id).first()
            assertEquals(
                "product chat turn did not complete; messages=${messages.map { it.role to it.status }}; " +
                    "logs=${logRows.map { it.kind to it.summary }}; modelRequests=${server.requestCount}; " +
                    "upstreamEvents=${originalEvents.map { it.toString() }}",
                "idle",
                sessionAfterTurn?.executionState,
            )

            val request = server.takeRequest(20, TimeUnit.SECONDS)
            assertNotNull("the product engine did not send a model request", request)
            assertEquals("POST", request!!.method)
            assertEquals("/product-messages/v1/messages", request.path)
            assertEquals(apiKey, request.getHeader("x-api-key"))
            val requestBody = request.body.readUtf8()
            assertTrue("the configured model must reach the original provider", requestBody.contains("deepseek-flash"))
            assertTrue("the current user prompt must reach the original provider", requestBody.contains(prompt))
            assertTrue("the configured system prompt must reach the original provider", requestBody.contains("Product integration system prompt"))

            val userMessage = messages.single { it.role == "user" }
            val assistantMessage = messages.single { it.role == "assistant" }
            assertEquals(prompt, userMessage.content)
            assertEquals(answer, assistantMessage.content)
            assertEquals("complete", assistantMessage.status)
            assertEquals("idle", repository.session(session.id)?.executionState)
            assertTrue("the product UI state must receive a real streamed assistant chunk", liveSnapshots.any { it.text.contains(answer) })

            assertTrue("the original turn must produce local execution logs", logRows.any { it.kind == "turn/start" })
            assertTrue("the API key must not be written to execution logs", logRows.none { it.details.contains(apiKey) })
        } finally {
            liveCollector?.cancelAndJoin()
            originalCollector?.cancelAndJoin()
            engine.close()
            pluginManager.close()
            database.close()
            server.shutdown()
            isolated.getDatabasePath("deepseek-harness.db").delete()
        }
    }

    private class IsolatedContext(
        base: Context,
        private val files: File,
        private val cache: File,
        private val databases: File,
        private val preferencePrefix: String,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getDatabasePath(name: String): File = File(databases, name)
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("$preferencePrefix-$name", mode)
    }

    private companion object {
        private const val MESSAGES_RESPONSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_product_engine","model":"deepseek-flash","usage":{"input_tokens":12,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Product chat integration response"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}

        """
    }
}
