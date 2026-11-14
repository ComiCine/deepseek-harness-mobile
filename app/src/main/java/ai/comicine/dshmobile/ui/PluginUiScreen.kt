package ai.comicine.dshmobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.R
import ai.comicine.dshmobile.data.PluginEntity
import ai.comicine.dshmobile.plugins.PluginUiAssetResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val LOCAL_PLUGIN_UI_HOST = "appassets.androidplatform.net"
private const val LOCAL_PLUGIN_UI_URL = "https://appassets.androidplatform.net/plugin-ui/index.html"
internal const val PLUGIN_UI_TEST_TAG = "plugin-ui-host"
internal const val PLUGIN_CHAT_INPUT_TEST_TAG = "plugin-chat-input-host"

@Composable
fun PluginUiScreen(viewModel: HarnessViewModel, focusedPackageName: String? = null) {
    val plugins by viewModel.plugins.collectAsState(initial = emptyList())
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]?.language ?: Locale.getDefault().language
    val runtimePackages = clientPackageClosure(plugins)
    val allowedPackages = runtimePackages.mapTo(linkedSetOf()) { it.packageName }
    val payload = pluginPayload(runtimePackages, language, focusedPackageName)

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.plugin_ui_description), modifier = Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        if (runtimePackages.none { it.enabled }) {
            Text(stringResource(R.string.plugin_ui_empty), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(PLUGIN_UI_TEST_TAG),
            factory = { context -> HarnessPluginWebView(context, viewModel.pluginManager.pluginsRoot).apply {
                tag = PLUGIN_UI_TEST_TAG
                loadUrl(LOCAL_PLUGIN_UI_URL)
            } },
            update = { view -> view.updatePlugins(payload, allowedPackages) },
            onRelease = HarnessPluginWebView::disposeAndDestroy,
        )
    }
}

private fun clientPackageClosure(plugins: List<PluginEntity>): List<PluginEntity> {
    val byName = plugins.associateBy(PluginEntity::packageName)
    val selected = linkedSetOf<String>()
    val pending = ArrayDeque(plugins.filter(PluginEntity::enabled).map(PluginEntity::packageName))
    while (pending.isNotEmpty()) {
        val packageName = pending.removeFirst()
        if (!selected.add(packageName)) continue
        val plugin = byName[packageName] ?: continue
        val manifest = runCatching { JSONObject(plugin.manifestJson) }.getOrNull() ?: continue
        val client = manifest.optJSONObject("dsh")?.optJSONObject("client") ?: continue
        if (client.optString("platform") != "web") continue
        val dependencies = JSONArray().apply {
            client.optJSONArray("inject")?.let { array -> for (index in 0 until array.length()) put(array.optString(index)) }
            client.optJSONArray("external")?.let { array -> for (index in 0 until array.length()) put(array.optString(index)) }
        }
        for (index in 0 until dependencies.length()) {
            val id = packageId(dependencies.optString(index)) ?: continue
            if (id in byName) pending.addLast(id)
        }
    }
    return plugins.filter { it.packageName in selected }.map { plugin ->
        if (plugin.enabled) plugin else plugin.copy(enabled = true)
    }
}

private fun packageId(specifier: String): String? {
    if (specifier.startsWith('@')) {
        val parts = specifier.split('/')
        return parts.takeIf { it.size >= 2 && it[0].length > 1 && it[1].isNotBlank() }?.take(2)?.joinToString("/")
    }
    return specifier.substringBefore('/').takeIf { it.isNotBlank() && ':' !in it }
}

private fun pluginPayload(
    plugins: List<PluginEntity>,
    language: String,
    focusedPackageName: String?,
    surface: String = "plugin-ui",
    activeSessionId: String? = null,
    inputText: String = "",
    selectionStart: Int = inputText.length,
    selectionEnd: Int = selectionStart,
    inputRevision: Int = 0,
    inputLocked: Boolean = false,
): String {
    val rows = JSONArray()
    plugins.forEach { plugin ->
        val manifest = JSONObject(plugin.manifestJson)
        if (manifest.optJSONObject("dsh")?.optJSONObject("client")?.optString("platform") != "web") return@forEach
        rows.put(JSONObject()
            .put("id", plugin.id)
            .put("packageName", plugin.packageName)
            .put("version", plugin.version)
            .put("sourceSha256", plugin.sourceSha256)
            .put("enabled", plugin.enabled)
            .put("manifestJson", plugin.manifestJson))
    }
    return JSONObject()
        .put("packages", rows)
        .put("language", if (language.startsWith("zh", true)) "zh" else "en")
        .put("focusedPackageName", focusedPackageName)
        .put("surface", surface)
        .put("activeSessionId", activeSessionId)
        .put("inputText", inputText)
        .put("selectionStart", selectionStart)
        .put("selectionEnd", selectionEnd)
        .put("inputRevision", inputRevision)
        .put("inputLocked", inputLocked)
        .toString()
}

/** Runs original session-scoped composer extensions against the live native draft. */
@Composable
fun ChatPluginInputActivities(
    viewModel: HarnessViewModel,
    sessionId: String,
    draft: String,
    revision: Int,
    locked: Boolean,
    onInsert: (String, Int, Int, Int, String) -> Boolean,
    onSetDraft: (String, String) -> Boolean,
    onSubmit: () -> Unit,
) {
    val plugins by viewModel.plugins.collectAsState(initial = emptyList())
    val runtimePackages = clientPackageClosure(plugins)
    if (runtimePackages.none(PluginEntity::enabled)) return

    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]?.language ?: Locale.getDefault().language
    val allowedPackages = runtimePackages.mapTo(linkedSetOf()) { it.packageName }
    val payload = pluginPayload(
        runtimePackages,
        language,
        focusedPackageName = null,
        surface = "chat-input",
        activeSessionId = sessionId,
        inputText = draft,
        selectionStart = draft.length,
        selectionEnd = draft.length,
        inputRevision = revision,
        inputLocked = locked,
    )
    var hasActivity by remember(sessionId) { mutableStateOf(false) }
    val callbacks = remember(sessionId) { PluginUiJavascriptCallbacks() }
    callbacks.update(
        onInsert = onInsert,
        onSetDraft = onSetDraft,
        onSubmit = onSubmit,
        onAvailability = { hasActivity = it },
    )
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (hasActivity) 72.dp else 0.dp)
            .testTag(PLUGIN_CHAT_INPUT_TEST_TAG),
        factory = { context -> HarnessPluginWebView(context, viewModel.pluginManager.pluginsRoot, callbacks).apply {
            tag = PLUGIN_CHAT_INPUT_TEST_TAG
            loadUrl(LOCAL_PLUGIN_UI_URL)
        } },
        update = { view -> view.updatePlugins(payload, allowedPackages) },
        onRelease = HarnessPluginWebView::disposeAndDestroy,
    )
}

private class PluginUiJavascriptCallbacks {
    @Volatile private var insertAction: (String, Int, Int, Int, String) -> Boolean = { _, _, _, _, _ -> false }
    @Volatile private var setDraftAction: (String, String) -> Boolean = { _, _ -> false }
    @Volatile private var submit: () -> Unit = {}
    @Volatile private var availability: (Boolean) -> Unit = {}

    fun update(
        onInsert: (String, Int, Int, Int, String) -> Boolean,
        onSetDraft: (String, String) -> Boolean,
        onSubmit: () -> Unit,
        onAvailability: (Boolean) -> Unit,
    ) {
        insertAction = onInsert
        setDraftAction = onSetDraft
        submit = onSubmit
        availability = onAvailability
    }

    fun insert(text: String, start: Int, end: Int, revision: Int, sessionId: String): Boolean =
        onMainAndWait { insertAction(text, start, end, revision, sessionId) }

    fun setDraft(text: String, sessionId: String): Boolean = onMainAndWait { setDraftAction(text, sessionId) }

    fun submitInput(sessionId: String) {
        Handler(Looper.getMainLooper()).post(submit)
    }

    fun setAvailable(value: Boolean) {
        Handler(Looper.getMainLooper()).post { availability(value) }
    }

    private fun <T> onMainAndWait(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val result = AtomicReference<Result<T>?>(null)
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching(action))
            done.countDown()
        }
        if (!done.await(2, TimeUnit.SECONDS)) throw IllegalStateException("The Android composer did not respond")
        return (result.get() ?: throw IllegalStateException("The Android composer returned no result"))
            .onFailure { Log.e("HarnessPluginUI", "A plugin input action failed", it) }
            .getOrThrow()
    }
}

private class PluginUiJavascriptBridge(private val callbacks: PluginUiJavascriptCallbacks) {
    @JavascriptInterface fun onRuntimeReady() = Unit
    @JavascriptInterface fun onRuntimeError(message: String) { Log.e("HarnessPluginUI", message) }
    @JavascriptInterface fun onInputUiAvailability(available: Boolean) = callbacks.setAvailable(available)
    @JavascriptInterface fun insertInputText(text: String, start: Int, end: Int, revision: Int, sessionId: String) =
        callbacks.insert(text, start, end, revision, sessionId)
    @JavascriptInterface fun setInputDraft(text: String, sessionId: String) = callbacks.setDraft(text, sessionId)
    @JavascriptInterface fun submitInput(sessionId: String) = callbacks.submitInput(sessionId)
}

@SuppressLint("SetJavaScriptEnabled")
private class HarnessPluginWebView(
    context: Context,
    pluginsRoot: java.io.File,
    callbacks: PluginUiJavascriptCallbacks = PluginUiJavascriptCallbacks(),
) : WebView(context) {
    private val resolver = PluginUiAssetResolver(context, pluginsRoot)
    private var currentPayload = ""
    private var sentPayload = ""
    private var pageReady = false

    init {
        addJavascriptInterface(PluginUiJavascriptBridge(callbacks), "AndroidPluginUiBridge")
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = resolver.intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != LOCAL_PLUGIN_UI_HOST

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(LOCAL_PLUGIN_UI_URL)) {
                    pageReady = true
                    sendPendingPayload()
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val log = "${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, log)
                else Log.d(TAG, log)
                return true
            }
        }
    }

    fun updatePlugins(payload: String, allowedPackages: Set<String>) {
        resolver.updateAllowedPackages(allowedPackages)
        currentPayload = payload
        sendPendingPayload()
    }

    fun disposeAndDestroy() {
        resolver.updateAllowedPackages(emptySet())
        pageReady = false
        stopLoading()
        evaluateJavascript("window.__dshAndroidPluginUi?.dispose?.().finally(() => true)") {
            post { runCatching { destroy() } }
        }
        postDelayed({ runCatching { destroy() } }, 1500L)
    }

    private fun sendPendingPayload() {
        if (!pageReady || currentPayload.isBlank() || currentPayload == sentPayload) return
        sentPayload = currentPayload
        val encoded = JSONObject.quote(currentPayload)
        evaluateJavascript("window.__dshAndroidPluginUi?.syncPlugins($encoded)?.catch(error => console.error(error))") { }
    }

    private companion object {
        const val TAG = "HarnessPluginUI"
    }
}
