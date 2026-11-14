package ai.comicine.dshmobile.engine

import ai.comicine.dshmobile.data.HarnessSettings
import ai.comicine.dshmobile.data.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AssistantTurn(
    val text: String,
    val thinking: String,
    val toolCalls: List<ToolCall>,
    val stopReason: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

class ProviderException(
    message: String,
    val statusCode: Int? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Streams the upstream DeepSeek Messages protocol. */
class DeepSeekMessagesClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun generate(
        settings: HarnessSettings,
        apiKey: String,
        history: List<MessageEntity>,
        tools: List<ToolDefinition>,
        onDelta: suspend (kind: String, text: String) -> Unit,
    ): AssistantTurn {
        val request = buildRequest(settings, history, tools)
        var lastFailure: ProviderException? = null
        for (attempt in 0..settings.retryCount) {
            currentCoroutineContext().ensureActive()
            try {
                return requestOnce(settings, apiKey, request, onDelta)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProviderException) {
                lastFailure = failure
                if (!failure.retryable || attempt == settings.retryCount) throw failure
                delay((500L * (1L shl attempt.coerceAtMost(5))).coerceAtMost(15_000L))
            }
        }
        throw lastFailure ?: ProviderException("DeepSeek request failed")
    }

    private suspend fun requestOnce(
        settings: HarnessSettings,
        apiKey: String,
        body: JSONObject,
        onDelta: suspend (kind: String, text: String) -> Unit,
    ): AssistantTurn {
        val url = endpoint(settings.baseUrl)
        val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("user-agent", "DeepSeek-Harness-Android/0.1.0")
            .post(requestBody)
            .build()
        val call = client.newCall(request)
        val job = currentCoroutineContext().job
        val cancellationHook = job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw providerFailure(response)
                    val source = response.body?.source() ?: throw ProviderException("DeepSeek returned an empty stream")
                    readEvents(source, onDelta)
                }
            }
        } catch (cancelled: CancellationException) {
            call.cancel()
            throw cancelled
        } catch (error: ProviderException) {
            throw error
        } catch (error: IOException) {
            if (job.isCancelled) throw CancellationException("DeepSeek request cancelled", error)
            throw ProviderException("DeepSeek network stream failed: ${error.message ?: "I/O error"}", retryable = true, cause = error)
        } catch (error: Exception) {
            if (job.isCancelled) throw CancellationException("DeepSeek request cancelled", error)
            throw ProviderException("DeepSeek response could not be decoded: ${error.message ?: error.javaClass.simpleName}", cause = error)
        } finally {
            cancellationHook.dispose()
        }
    }

    private suspend fun readEvents(source: BufferedSource, onDelta: suspend (kind: String, text: String) -> Unit): AssistantTurn {
        var eventName: String? = null
        val data = StringBuilder()
        val blocks = sortedMapOf<Int, MutableBlock>()
        var stopReason: String? = null
        var inputTokens = 0
        var outputTokens = 0
        var sawMessageStart = false
        var sawMessageStop = false
        var contentEvents = 0

        suspend fun dispatch() {
            if (data.isEmpty()) {
                eventName = null
                return
            }
            contentEvents++
            if (contentEvents > MAX_SSE_EVENTS) throw ProviderException("DeepSeek stream exceeded the event limit")
            val event = try {
                JSONObject(data.toString())
            } catch (error: JSONException) {
                throw ProviderException("DeepSeek Messages SSE contained invalid JSON", cause = error)
            }
            val type = event.optString("type", eventName ?: "")
            if (eventName != null && eventName != type) throw ProviderException("DeepSeek stream event name did not match its payload")
            when (type) {
                "error" -> {
                    val detail = event.optJSONObject("error")?.optString("message")
                    throw ProviderException(detail?.takeIf { it.isNotBlank() } ?: "DeepSeek returned a stream error")
                }
                "message_start" -> {
                    if (sawMessageStart) throw ProviderException("DeepSeek stream repeated message_start")
                    sawMessageStart = true
                    val usage = event.optJSONObject("message")?.optJSONObject("usage")
                    inputTokens = usage?.optInt("input_tokens", inputTokens) ?: inputTokens
                    outputTokens = usage?.optInt("output_tokens", outputTokens) ?: outputTokens
                }
                "content_block_start" -> {
                    val index = event.optInt("index", -1)
                    if (index < 0 || blocks.containsKey(index)) throw ProviderException("DeepSeek stream had an invalid content block index")
                    val native = event.optJSONObject("content_block") ?: throw ProviderException("DeepSeek stream omitted a content block")
                    blocks[index] = when (native.optString("type")) {
                        "text" -> MutableBlock("text", text = StringBuilder(native.optString("text")))
                        "thinking" -> MutableBlock("thinking", text = StringBuilder(native.optString("thinking")))
                        "tool_use" -> MutableBlock(
                            "tool_use",
                            id = native.optString("id"),
                            name = native.optString("name"),
                            initialInput = native.optJSONObject("input")?.toString() ?: "{}",
                        )
                        else -> throw ProviderException("DeepSeek returned an unsupported content block")
                    }
                }
                "content_block_delta" -> {
                    val index = event.optInt("index", -1)
                    val block = blocks[index] ?: throw ProviderException("DeepSeek stream delta referred to an unknown block")
                    val delta = event.optJSONObject("delta") ?: throw ProviderException("DeepSeek stream omitted a delta")
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            if (block.type != "text") throw ProviderException("DeepSeek stream text delta did not match its block")
                            val chunk = delta.optString("text")
                            block.text.append(chunk)
                            onDelta("text", chunk)
                        }
                        "thinking_delta" -> {
                            if (block.type != "thinking") throw ProviderException("DeepSeek reasoning delta did not match its block")
                            val chunk = delta.optString("thinking")
                            block.text.append(chunk)
                            onDelta("thinking", chunk)
                        }
                        "input_json_delta" -> {
                            if (block.type != "tool_use") throw ProviderException("DeepSeek tool delta did not match its block")
                            block.partialJson.append(delta.optString("partial_json"))
                            if (block.partialJson.length > MAX_TOOL_ARGUMENTS) throw ProviderException("DeepSeek tool arguments exceeded 1 MiB")
                        }
                        "signature_delta" -> Unit
                        else -> throw ProviderException("DeepSeek stream contained an unsupported delta")
                    }
                }
                "content_block_stop" -> {
                    val index = event.optInt("index", -1)
                    if (blocks[index] == null) throw ProviderException("DeepSeek stream stopped an unknown block")
                }
                "message_delta" -> {
                    val delta = event.optJSONObject("delta")
                    stopReason = delta?.optString("stop_reason")?.takeIf { it != "null" && it.isNotBlank() }
                    val usage = event.optJSONObject("usage")
                    outputTokens = usage?.optInt("output_tokens", outputTokens) ?: outputTokens
                    inputTokens = usage?.optInt("input_tokens", inputTokens) ?: inputTokens
                }
                "message_stop" -> sawMessageStop = true
                "ping" -> Unit
                else -> Unit
            }
            data.setLength(0)
            eventName = null
        }

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    dispatch()
                } else if (line.startsWith(": ") || line.startsWith(":")) {
                    continue
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim()
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    val value = line.substring(5)
                    data.append(if (value.startsWith(' ')) value.drop(1) else value)
                    if (data.length > MAX_SSE_EVENT_BYTES) throw ProviderException("DeepSeek SSE event exceeded 2 MiB")
                }
            }
            dispatch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ProviderException) {
            throw error
        } catch (error: IOException) {
            throw ProviderException("DeepSeek stream was interrupted: ${error.message ?: "I/O error"}", retryable = contentEvents == 0, cause = error)
        }

        val finalStopReason = stopReason
        if (!sawMessageStart || !sawMessageStop || finalStopReason == null) {
            throw ProviderException("DeepSeek stream ended before message_stop", retryable = contentEvents == 0)
        }
        val text = StringBuilder()
        val thinking = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (block in blocks.values) {
            when (block.type) {
                "text" -> text.append(block.text)
                "thinking" -> thinking.append(block.text)
                "tool_use" -> {
                    if (block.id.isBlank() || block.name.isBlank()) throw ProviderException("DeepSeek returned a tool call without an id or name")
                    val args = block.partialJson.toString().takeIf { it.isNotBlank() } ?: block.initialInput
                    try {
                        JSONObject(args)
                    } catch (error: JSONException) {
                        throw ProviderException("DeepSeek returned malformed tool arguments", cause = error)
                    }
                    calls += ToolCall(block.id, block.name, args)
                }
            }
        }
        if (finalStopReason == "tool_use" && calls.isEmpty()) throw ProviderException("DeepSeek requested tool use without a tool call")
        return AssistantTurn(text.toString(), thinking.toString(), calls, finalStopReason, inputTokens, outputTokens)
    }

    private fun buildRequest(settings: HarnessSettings, history: List<MessageEntity>, tools: List<ToolDefinition>): JSONObject {
        val selected = trimHistory(history, settings.maxContextCharacters)
        val messages = JSONArray()
        for (entry in selected) {
            val role = if (entry.role == "tool") "user" else entry.role
            val content = JSONArray()
            when (entry.role) {
                "user", "context" -> content.put(JSONObject().put("type", "text").put("text", entry.content))
                "assistant" -> {
                    if (entry.thinking.isNotEmpty()) content.put(JSONObject().put("type", "thinking").put("thinking", entry.thinking))
                    if (entry.content.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", entry.content))
                    val calls = JSONArray(entry.toolCallsJson)
                    for (index in 0 until calls.length()) {
                        val call = calls.getJSONObject(index)
                        content.put(JSONObject()
                            .put("type", "tool_use")
                            .put("id", call.getString("id"))
                            .put("name", call.getString("name"))
                            .put("input", JSONObject(call.optString("arguments", "{}"))))
                    }
                }
                "tool" -> content.put(JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", entry.toolCallId ?: "")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", entry.content)))
                    .put("is_error", entry.isError))
                else -> continue
            }
            if (content.length() == 0) continue
            if (messages.length() > 0 && messages.getJSONObject(messages.length() - 1).optString("role") == role) {
                val previous = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
                for (index in 0 until content.length()) previous.put(content.get(index))
            } else {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }
        val body = JSONObject()
            .put("model", settings.model)
            .put("stream", true)
            .put("max_tokens", settings.maxTokens)
            .put("thinking", JSONObject().put("type", if (settings.reasoningEffort == "off") "disabled" else "enabled"))
            .put("messages", messages)
        if (settings.reasoningEffort != "off") body.put("output_config", JSONObject().put("effort", settings.reasoningEffort))
        if (settings.systemPrompt.isNotBlank()) body.put("system", settings.systemPrompt)
        if (tools.isNotEmpty()) {
            val toolArray = JSONArray()
            for (tool in tools) toolArray.put(JSONObject()
                .put("name", tool.name)
                .put("description", tool.description)
                .put("input_schema", JSONObject(tool.parametersJson)))
            body.put("tools", toolArray)
        }
        return body
    }

    private fun trimHistory(history: List<MessageEntity>, maxCharacters: Int): List<MessageEntity> {
        val groups = mutableListOf<MutableList<MessageEntity>>()
        for (message in history.filter { it.status == "complete" || it.status == "error" }) {
            if (message.role == "user") groups.add(mutableListOf())
            if (groups.isEmpty()) groups.add(mutableListOf())
            groups.last().add(message)
        }
        fun size() = groups.sumOf { group -> group.sumOf { it.content.length + it.thinking.length + it.toolCallsJson.length + 48 } }
        while (groups.size > 1 && size() > maxCharacters) groups.removeAt(0)
        if (size() > maxCharacters) throw ProviderException("当前对话最后一轮超过本机请求内存上限；请开始新会话或缩短输入")
        return groups.flatten()
    }

    private fun endpoint(baseUrl: String): HttpUrl {
        val parsed = baseUrl.toHttpUrlOrNull() ?: throw ProviderException("模型 API 地址无效")
        val loopback = parsed.host in setOf("localhost", "127.0.0.1", "::1")
        if (parsed.scheme != "https" && !(parsed.scheme == "http" && loopback)) {
            throw ProviderException("模型 API 必须使用 HTTPS")
        }
        if (parsed.query != null || parsed.fragment != null) throw ProviderException("模型 API 地址不能包含 query 或 fragment")
        val currentPath = parsed.encodedPath.trimEnd('/')
        val apiPath = if (currentPath.endsWith("/v1")) "$currentPath/messages" else "$currentPath/v1/messages"
        return parsed.newBuilder().encodedPath(apiPath).build()
    }

    private fun providerFailure(response: Response): ProviderException {
        val raw = response.body?.string()?.take(64 * 1024).orEmpty()
        val message = try {
            JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (_: JSONException) { null }
        val code = response.code
        return ProviderException(
            message ?: "DeepSeek returned HTTP $code${response.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
            statusCode = code,
            retryable = code == 408 || code == 429 || code in 500..599,
        )
    }

    private data class MutableBlock(
        val type: String,
        val text: StringBuilder = StringBuilder(),
        val id: String = "",
        val name: String = "",
        val initialInput: String = "{}",
        val partialJson: StringBuilder = StringBuilder(),
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_SSE_EVENT_BYTES = 2 * 1024 * 1024
        private const val MAX_TOOL_ARGUMENTS = 1024 * 1024
        private const val MAX_SSE_EVENTS = 1_000_000
    }
}
