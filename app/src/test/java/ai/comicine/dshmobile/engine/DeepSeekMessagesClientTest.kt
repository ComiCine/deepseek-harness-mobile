package ai.comicine.dshmobile.engine

import android.app.Application
import ai.comicine.dshmobile.data.HarnessSettings
import ai.comicine.dshmobile.data.MessageEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class DeepSeekMessagesClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamsMessagesAndBuildsTheUpstreamRequest() = runBlocking {
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(textStream))
        val deltas = mutableListOf<Pair<String, String>>()
        val result = DeepSeekMessagesClient().generate(
            settings = settings(),
            apiKey = "unit-test-secret",
            history = listOf(userMessage("Explain this")),
            tools = emptyList(),
        ) { kind, text -> deltas += kind to text }

        assertEquals("Hello from the stream.", result.text)
        assertEquals("reasoning", result.thinking)
        assertEquals("end_turn", result.stopReason)
        assertEquals(4, result.inputTokens)
        assertEquals(8, result.outputTokens)
        assertEquals(listOf("Hello ", "from the stream."), deltas.filter { it.first == "text" }.map { it.second })

        val request = server.takeRequest()
        assertEquals("/anthropic/v1/messages", request.path)
        assertEquals("unit-test-secret", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("deepseek-flash", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("Explain this", body.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun assemblesPartialToolArgumentsWithoutExecutingThem() = runBlocking {
        server.enqueue(MockResponse().setBody(toolStream))
        val result = DeepSeekMessagesClient().generate(
            settings = settings(),
            apiKey = "unit-test-secret",
            history = listOf(userMessage("Read notes.txt")),
            tools = listOf(ToolDefinition("read_file", "Read a workspace file", "{\"type\":\"object\"}", false)),
        ) { _, _ -> }

        assertEquals("tool_use", result.stopReason)
        assertEquals(1, result.toolCalls.size)
        assertEquals("read_file", result.toolCalls.single().name)
        assertEquals("call-1", result.toolCalls.single().id)
        assertEquals("notes.txt", JSONObject(result.toolCalls.single().argumentsJson).getString("path"))
        val request = server.takeRequest()
        assertTrue(JSONObject(request.body.readUtf8()).getJSONArray("tools").length() == 1)
        assertFalse(request.bodySize == 0L)
    }

    private fun settings() = HarnessSettings(
        baseUrl = server.url("/anthropic").toString().trimEnd('/'),
        model = "deepseek-flash",
        retryCount = 0,
    )

    private fun userMessage(content: String) = MessageEntity(
        id = "user-1",
        sessionId = "session-1",
        role = "user",
        content = content,
        createdAt = 1L,
    )

    private val textStream = """
        event: message_start
        data: {"type":"message_start","message":{"usage":{"input_tokens":4}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"reasoning"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello "}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"from the stream."}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":1}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":8}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent().replace("\n\n", "\n\n")

    private val toolStream = """
        event: message_start
        data: {"type":"message_start","message":{"usage":{"input_tokens":2}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call-1","name":"read_file","input":{}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"notes.txt\"}"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent()
}
