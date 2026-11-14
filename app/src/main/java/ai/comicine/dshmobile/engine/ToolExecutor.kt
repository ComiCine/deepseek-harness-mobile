package ai.comicine.dshmobile.engine

import android.content.Context
import ai.comicine.dshmobile.data.PluginEntity
import ai.comicine.dshmobile.plugins.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String,
    val requiresApproval: Boolean,
    val pluginId: String? = null,
    val pluginToolName: String? = null,
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ToolExecution(
    val call: ToolCall,
    val output: String,
    val isError: Boolean = false,
    val additionalContexts: List<String> = emptyList(),
)

class ToolExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Local Android tools, confined to app-private workspace files and unprivileged processes. */
class ToolExecutor(
    context: Context,
    private val pluginManager: PluginManager,
) {
    private val app = context.applicationContext
    private val workspaceRoot = File(app.filesDir, "workspace").apply { mkdirs() }.canonicalFile

    suspend fun definitions(sessionId: String): List<ToolDefinition> {
        val builtins = listOf(
            ToolDefinition("list_files", "List files in the app's local workspace.", listOf("path" to "string", "recursive" to "boolean").schema(), false),
            ToolDefinition("read_file", "Read a UTF-8 text file from the app's local workspace.", listOf("path" to "string", "maxCharacters" to "integer").schema(), false),
            ToolDefinition("write_file", "Write or replace a UTF-8 text file inside the app's local workspace.", listOf("path" to "string", "content" to "string", "createParents" to "boolean").schema(required = listOf("path", "content")), true),
            ToolDefinition("edit_file", "Replace exactly one matching text region inside a workspace file.", listOf("path" to "string", "oldText" to "string", "newText" to "string").schema(required = listOf("path", "oldText", "newText")), true),
            ToolDefinition("shell", "Run one command in Android's local /system/bin/sh in the app sandbox. No root or persistent terminal is available.", shellSchema(), true),
        )
        val pluginTools = pluginManager.activeTools(sessionId).map { plugin ->
            ToolDefinition(plugin.name, plugin.description, plugin.parametersJson, true, plugin.pluginId, plugin.name)
        }
        val names = mutableSetOf<String>()
        return (builtins + pluginTools).onEach {
            if (!names.add(it.name)) throw ToolExecutionException("Tool name collision: ${it.name}")
        }
    }

    suspend fun execute(sessionId: String, definition: ToolDefinition, call: ToolCall): ToolExecution {
        return try {
            val args = JSONObject(call.argumentsJson)
            when (definition.name) {
                "list_files" -> ToolExecution(call, listFiles(args))
                "read_file" -> ToolExecution(call, readFile(args))
                "write_file" -> ToolExecution(call, writeFile(args))
                "edit_file" -> ToolExecution(call, editFile(args))
                "shell" -> ToolExecution(call, runShell(args))
                else -> {
                    definition.pluginId ?: throw ToolExecutionException("Tool is no longer registered")
                    val pluginName = definition.pluginToolName ?: throw ToolExecutionException("Plugin tool name is missing")
                    pluginManager.invoke(sessionId, call.id, pluginName, call.argumentsJson).let {
                        ToolExecution(call, it.output, it.isError, it.additionalContexts)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecution(call, error.message ?: error.javaClass.simpleName, isError = true)
        }
    }

    fun workspacePath() = workspaceRoot.absolutePath

    private fun listFiles(args: JSONObject): String {
        val root = if (args.has("path")) resolve(args.getString("path")) else workspaceRoot
        if (!root.exists()) return "Path does not exist: ${relative(root)}"
        if (!root.isDirectory) return "Not a directory: ${relative(root)}"
        val recursive = args.optBoolean("recursive", false)
        val files = mutableListOf<String>()
        if (recursive) {
            root.walkTopDown().maxDepth(12).filter { it != root }.take(5000).forEach {
                files += "${if (it.isDirectory) "d" else "f"}\t${relative(it)}"
            }
        } else {
            root.listFiles()?.sortedBy { it.name.lowercase() }?.forEach {
                files += "${if (it.isDirectory) "d" else "f"}\t${relative(it)}"
            }
        }
        return files.take(5000).joinToString("\n").ifEmpty { "(empty)" }
    }

    private fun readFile(args: JSONObject): String {
        val path = resolve(args.getString("path"))
        if (!path.isFile) throw ToolExecutionException("Not a file: ${relative(path)}")
        if (path.length() > MAX_FILE_BYTES) throw ToolExecutionException("File exceeds 2 MiB")
        val content = path.readText(Charsets.UTF_8)
        val limit = args.optInt("maxCharacters", 80_000).coerceIn(1, MAX_TOOL_OUTPUT)
        return if (content.length > limit) content.take(limit) + "\n… output truncated …" else content
    }

    private fun writeFile(args: JSONObject): String {
        val path = resolve(args.getString("path"))
        val parent = path.parentFile ?: throw ToolExecutionException("Invalid file path")
        if (!parent.exists() && !args.optBoolean("createParents", false)) {
            throw ToolExecutionException("Parent folder does not exist; set createParents=true after approval")
        }
        if (args.optBoolean("createParents", false) && !parent.mkdirs() && !parent.isDirectory) {
            throw ToolExecutionException("Could not create parent folders")
        }
        val content = args.getString("content")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) throw ToolExecutionException("File exceeds 2 MiB")
        val temp = File(parent, ".${path.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(path)) throw ToolExecutionException("Could not atomically replace ${relative(path)}")
        } finally {
            temp.delete()
        }
        return "Wrote ${content.length} characters to ${relative(path)}"
    }

    private fun editFile(args: JSONObject): String {
        val path = resolve(args.getString("path"))
        if (!path.isFile) throw ToolExecutionException("Not a file: ${relative(path)}")
        if (path.length() > MAX_FILE_BYTES) throw ToolExecutionException("File exceeds 2 MiB")
        val original = path.readText(Charsets.UTF_8)
        val oldText = args.getString("oldText")
        val first = original.indexOf(oldText)
        if (first < 0) throw ToolExecutionException("Text to replace was not found")
        if (original.indexOf(oldText, first + oldText.length) >= 0) {
            throw ToolExecutionException("Text occurs more than once; provide a more specific match")
        }
        val updated = original.replaceRange(first, first + oldText.length, args.getString("newText"))
        if (updated.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) throw ToolExecutionException("Edited file exceeds 2 MiB")
        val temp = File(path.parentFile, ".${path.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(updated, Charsets.UTF_8)
            if (!temp.renameTo(path)) throw ToolExecutionException("Could not atomically replace ${relative(path)}")
        } finally {
            temp.delete()
        }
        return "Updated ${relative(path)}"
    }

    private suspend fun runShell(args: JSONObject): String = coroutineScope {
        val command = args.getString("command")
        if (command.length > 32_000) throw ToolExecutionException("Command exceeds 32,000 characters")
        val workingDirectory = if (args.has("cwd")) resolve(args.getString("cwd")) else workspaceRoot
        if (!workingDirectory.isDirectory) throw ToolExecutionException("Working directory does not exist")
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).directory(workingDirectory).start()
        } catch (error: Exception) {
            throw ToolExecutionException("Could not start Android local shell: ${error.message}", error)
        }
        val stdout = async(Dispatchers.IO) { readCapped(process.inputStream, MAX_SHELL_OUTPUT) }
        val stderr = async(Dispatchers.IO) { readCapped(process.errorStream, MAX_SHELL_OUTPUT) }
        try {
            if (args.has("stdin")) {
                process.outputStream.write(args.getString("stdin").toByteArray(Charsets.UTF_8))
            }
            process.outputStream.close()
            val timeout = args.optLong("timeoutMs", 120_000).coerceIn(1_000, 300_000)
            val finished = withContext(Dispatchers.IO) { process.waitFor(timeout, TimeUnit.MILLISECONDS) }
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            val out = stdout.await()
            val err = stderr.await()
            buildString {
                append("exitCode=")
                append(if (finished) process.exitValue() else "timeout")
                if (out.first.isNotBlank()) append("\nstdout:\n").append(out.first)
                if (err.first.isNotBlank()) append("\nstderr:\n").append(err.first)
                if (out.second || err.second) append("\n… output truncated …")
                if (!finished) append("\nCommand terminated after the timeout.")
            }
        } catch (cancelled: CancellationException) {
            process.destroyForcibly()
            throw cancelled
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private suspend fun readCapped(input: InputStream, limit: Int): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        input.use { stream ->
            val output = java.io.ByteArrayOutputStream(minOf(limit, 16 * 1024))
            val buffer = ByteArray(8192)
            var truncated = false
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val remaining = limit - output.size()
                if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                if (count > remaining) truncated = true
            }
            output.toString(Charsets.UTF_8.name()) to truncated
        }
    }

    private fun resolve(relative: String): File {
        if (relative.isBlank() || File(relative).isAbsolute) throw ToolExecutionException("Use a non-empty relative workspace path")
        val file = File(workspaceRoot, relative).canonicalFile
        if (file.path != workspaceRoot.path && !file.path.startsWith(workspaceRoot.path + File.separator)) {
            throw ToolExecutionException("Path escapes the app's local workspace")
        }
        return file
    }

    private fun relative(file: File): String = file.relativeTo(workspaceRoot).path.ifBlank { "." }

    private fun List<Pair<String, String>>.schema(required: List<String> = emptyList()): String {
        val properties = JSONObject()
        for ((name, type) in this) properties.put(name, JSONObject().put("type", type))
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) schema.put("required", JSONArray().apply { required.forEach(::put) })
        return schema.toString()
    }

    private fun shellSchema(): String = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("command", JSONObject().put("type", "string"))
            .put("cwd", JSONObject().put("type", "string"))
            .put("stdin", JSONObject().put("type", "string"))
            .put("timeoutMs", JSONObject().put("type", "integer"))
        )
        .put("required", JSONArray().put("command"))
        .toString()

    companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_TOOL_OUTPUT = 100_000
        private const val MAX_SHELL_OUTPUT = 100_000
    }
}
