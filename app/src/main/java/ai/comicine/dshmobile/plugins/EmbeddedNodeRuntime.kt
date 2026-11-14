package ai.comicine.dshmobile.plugins

import android.content.Context
import android.os.SystemClock
import ai.comicine.dshmobile.R
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.enums.V8RuntimeTerminationMode
import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.options.NodeRuntimeOptions
import com.caoccao.javet.node.modules.NodeModuleModule
import com.caoccao.javet.values.reference.V8ValuePromise
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException

class PluginPackageException(val messageResourceId: Int) : IllegalArgumentException()

/** Persistent on-device Node.js runtime used by the original Cordis plugin ecosystem. */
class EmbeddedNodeRuntime(context: Context) : Closeable {
    private val app = context.applicationContext
    val pluginsRoot = File(app.filesDir, "plugins").apply { mkdirs() }
    private val npmRoot = File(app.filesDir, "embedded-npm")
    private val runtimeThread = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dsh-embedded-node").apply { isDaemon = true }
    }
    private val dispatcher = runtimeThread.asCoroutineDispatcher()
    private val timeoutThread = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "dsh-node-watchdog").apply { isDaemon = true }
    }
    private var runtime: NodeRuntime? = null
    private var closed = false

    suspend fun call(operation: String, vararg arguments: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String = withContext(dispatcher) {
        val call = hostCall(operation, arguments.toList())
        runAsync("($call)", timeoutMs, currentCoroutineContext()[Job])
    }

    /** Calls a Cordis host operation while keeping one argument out of generated JavaScript source text. */
    suspend fun callWithSensitiveArgument(
        operation: String,
        arguments: List<String>,
        sensitiveArgumentIndex: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String = withContext(NonCancellable + dispatcher) {
        require(sensitiveArgumentIndex in arguments.indices) { "Sensitive argument index is out of range" }
        val active = ensureRuntime()
        val global = active.getGlobalObject()
        val property = "__dshSensitiveArgument"
        try {
            global.set(property, arguments[sensitiveArgumentIndex])
            val call = hostCall(operation, arguments, property, sensitiveArgumentIndex)
            runAsync("($call)", timeoutMs, null)
        } finally {
            runCatching { global.delete(property) }
            global.close()
        }
    }

    /** Runs a short control call to completion even if its caller is cancelled. */
    suspend fun callNonCancellable(
        operation: String,
        vararg arguments: String,
        timeoutMs: Long = 15_000L,
    ): String = withContext(NonCancellable + dispatcher) {
        val call = hostCall(operation, arguments.toList())
        runAsync("($call)", timeoutMs, null)
    }

    suspend fun evaluate(expression: String): String = withContext(dispatcher) {
        ensureRuntime().getExecutor(expression).executeString()
    }

    suspend fun evaluateAsync(expression: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String = withContext(dispatcher) {
        runAsync(expression, timeoutMs, currentCoroutineContext()[Job])
    }

    private fun hostCall(
        operation: String,
        arguments: List<String>,
        sensitiveProperty: String? = null,
        sensitiveArgumentIndex: Int = -1,
    ): String {
        val safeOperation = operation.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9]*")) }
            ?: throw IllegalArgumentException("Invalid Cordis bridge operation")
        val encoded = arguments.mapIndexed { index, value ->
            if (index == sensitiveArgumentIndex) "globalThis.$sensitiveProperty" else JSONObject.quote(value)
        }
        return "globalThis.__dshCordisHost.$safeOperation(${encoded.joinToString(",")})"
    }

    private fun ensureRuntime(): NodeRuntime {
        check(!closed) { "The embedded Node runtime is closed" }
        runtime?.let { return it }
        ensureNpmExtracted()
        val options = NodeRuntimeOptions().setBuiltInModuleResolution(true)
        val created = V8Host.getNodeI18nInstance().createV8Runtime<NodeRuntime>(options)
        // Upstream Cordis Loader uses Function to evaluate its supported !!js config values.
        created.allowEval(true)
        created.getNodeModule(NodeModuleModule::class.java).setRequireRootDirectory(pluginsRoot)
        created.getExecutor(
            "process.chdir(${JSONObject.quote(pluginsRoot.absolutePath)});" +
                "globalThis.__dshPluginRoot=${JSONObject.quote(pluginsRoot.absolutePath)};" +
                "globalThis.__dshNpmRoot=${JSONObject.quote(npmRoot.absolutePath)};" +
                "globalThis.__dshMigrationRoot=${JSONObject.quote(app.cacheDir.absolutePath)};",
        ).executeVoid()
        app.assets.open("plugin-importer.mjs").use { input ->
            FileOutputStream(File(pluginsRoot, "plugin-importer.mjs")).use { output -> input.copyTo(output) }
        }
        val bootstrap = app.assets.open("cordis-host.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        created.getExecutor(bootstrap).executeVoid()
        runtime = created
        return created
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun runAsync(expression: String, timeoutMs: Long, job: Job?): String {
        val active = ensureRuntime()
        val timedOut = AtomicBoolean(false)
        val operationFinished = AtomicBoolean(false)
        val terminationSent = AtomicBoolean(false)
        fun terminate() {
            if (!operationFinished.get() && terminationSent.compareAndSet(false, true)) {
                runCatching { active.terminateExecution(V8RuntimeTerminationMode.Asynchronous) }
            }
        }
        val cancellationHandle = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) terminate()
        }
        val timeoutTask: ScheduledFuture<*> = timeoutThread.schedule({
            timedOut.set(true)
            terminate()
        }, timeoutMs, TimeUnit.MILLISECONDS)
        val wrapped = """
            (async () => {
              try { return JSON.stringify({ ok: true, value: await ($expression) }); }
              catch (error) { return JSON.stringify({ ok: false, error: String(error && (error.stack || error.message) || error) }); }
            })()
        """.trimIndent()
        try {
            job?.ensureActive()
            val value = active.getExecutor(wrapped).execute<com.caoccao.javet.values.V8Value>()
            if (value !is V8ValuePromise) {
                value.close()
                throw IllegalStateException("Embedded Node bridge returned a non-Promise result")
            }
            val promise = value
            try {
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (promise.isPending && SystemClock.elapsedRealtime() < deadline) {
                    if (timedOut.get()) throw IllegalStateException("Embedded Node operation exceeded ${timeoutMs}ms")
                    job?.ensureActive()
                    active.await(V8AwaitMode.RunOnce)
                    if (promise.isPending) Thread.sleep(4)
                }
                if (timedOut.get() || promise.isPending) throw IllegalStateException("Embedded Node operation exceeded ${timeoutMs}ms")
                if (promise.isRejected) {
                    val rejected = promise.getResult<com.caoccao.javet.values.V8Value>()
                    val reason = try {
                        active.toObject<Any?, com.caoccao.javet.values.V8Value>(rejected)?.toString()
                    } finally {
                        rejected.close()
                    }
                    throw IllegalStateException(reason?.takeIf { it.isNotBlank() } ?: "Embedded Node operation failed")
                }
                val response = JSONObject(promise.getResultString())
                if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Embedded Node operation failed"))
                val result = response.opt("value")
                return if (result == null || result === JSONObject.NULL) "" else result.toString()
            } finally {
                promise.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            job?.ensureActive()
            if (timedOut.get()) throw IllegalStateException("Embedded Node operation exceeded ${timeoutMs}ms", error)
            throw error
        } finally {
            operationFinished.set(true)
            timeoutTask.cancel(false)
            cancellationHandle?.dispose()
            if (terminationSent.get()) runCatching { active.cancelTerminateExecution() }
        }
    }

    private fun ensureNpmExtracted() {
        if (File(npmRoot, "package/lib/npm.js").isFile) return
        val stage = File(app.filesDir, ".npm-runtime-${System.nanoTime()}").apply { mkdirs() }
        try {
            val maxExtractedBytes = 80L * 1024 * 1024
            var extracted = 0L
            app.assets.open("npm-runtime/npm-11.20.0.tgz").use { compressed ->
                TarArchiveInputStream(GzipCompressorInputStream(compressed)).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (!name.startsWith("package/") || name.split('/').any { it == ".." }) {
                            throw IllegalStateException("Bundled npm runtime contains an unsafe archive path")
                        }
                        val relative = name.removePrefix("package/")
                        if (relative.isBlank()) continue
                        val output = File(stage, "package/$relative").canonicalFile
                        val canonicalStage = stage.canonicalFile
                        check(output.path.startsWith(canonicalStage.path + File.separator)) {
                            "Bundled npm runtime escaped the application directory"
                        }
                        if (entry.isDirectory) output.mkdirs()
                        else if (entry.isFile) {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { target ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = tar.read(buffer)
                                    if (count < 0) break
                                    extracted += count
                                    check(extracted <= maxExtractedBytes) { "Bundled npm runtime exceeded its extraction limit" }
                                    target.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                }
            }
            check(File(stage, "package/lib/npm.js").isFile) { "Bundled npm runtime is incomplete" }
            npmRoot.deleteRecursively()
            check(stage.renameTo(npmRoot)) { "Could not publish the embedded npm runtime" }
        } finally {
            stage.deleteRecursively()
        }
    }

    override fun close() {
        if (closed) return
        runCatching {
            runtimeThread.submit {
                runtime?.close()
                runtime = null
            }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        closed = true
        timeoutThread.shutdownNow()
        dispatcher.close()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L

        fun packageNameFromTarball(input: java.io.InputStream): String {
            TarArchiveInputStream(GzipCompressorInputStream(input)).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.name != "package/package.json" || !entry.isFile) continue
                    val json = JSONObject(tar.readBytes().toString(Charsets.UTF_8))
                    return json.getString("name").also {
                        require(it.matches(Regex("(@[a-z0-9._-]+/)?[a-z0-9._-]+", RegexOption.IGNORE_CASE))) {
                            throw PluginPackageException(R.string.plugin_error_invalid_package_name)
                        }
                    }
                }
            }
            throw PluginPackageException(R.string.plugin_error_invalid_archive)
        }
    }
}
