package ai.comicine.dshmobile.plugins

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.comicine.dshmobile.HarnessApplication
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.MainActivity
import ai.comicine.dshmobile.R
import ai.comicine.dshmobile.ui.CHAT_PROMPT_TEST_TAG
import ai.comicine.dshmobile.ui.PLUGIN_CHAT_INPUT_TEST_TAG
import ai.comicine.dshmobile.ui.PLUGIN_UI_TEST_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the original, unmodified upstream browser UI fixture in the Android WebView host. */
@RunWith(AndroidJUnit4::class)
class OriginalUiPluginInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private var installedPluginId: String? = null

    @After
    fun removeFixturePlugin(): Unit = runBlocking {
        installedPluginId?.let { id ->
            val application = compose.activity.application as HarnessApplication
            runCatching { application.pluginManager.enable(id, false) }
            runCatching { application.pluginManager.uninstall(id) }
        }
    }

    @Test
    fun installsActivatesRendersInteractsDeactivatesAndUninstallsOriginalUiPlugin(): Unit = runBlocking {
        val application = compose.activity.application as HarnessApplication
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val archive = File(application.cacheDir, "fixture-layout-bottom-0.0.1.tgz")
        testContext.assets.open("fixture-layout-bottom-0.0.1.tgz").use { input ->
            archive.outputStream().use(input::copyTo)
        }

        val installed = application.pluginManager.install(Uri.fromFile(archive))
        installedPluginId = installed.id
        assertEquals("@fixture/layout-bottom", installed.packageName)
        assertTrue("the test package must retain its upstream Web client contract", installed.manifestJson.contains("\"platform\":\"web\""))
        application.pluginManager.enable(installed.id, true)

        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.nav_plugin_ui),
            useUnmergedTree = true,
        ).performClick()
        var runtimeFailure: String? = null
        compose.waitUntil(timeoutMillis = 30_000) {
            val host = webView()
            if (host == null) return@waitUntil false
            if (evaluate(host, "Boolean(document.querySelector('[data-bottom-fixture]'))") == "true") return@waitUntil true
            runtimeFailure = evaluateString(host, "document.body.dataset.runtimeError || document.body.dataset.pluginSyncError || ''")
                ?.takeIf(String::isNotBlank)
            runtimeFailure != null
        }
        assertTrue("the original client bundle did not render: ${runtimeFailure.orEmpty()}", runtimeFailure == null)

        val webView = webView()
        assertNotNull("the native Plugin UI screen must own a local WebView", webView)
        val host = requireNotNull(webView)
        assertEquals("true", evaluate(host, "Boolean(document.querySelector('[data-bottom-fixture] input[aria-label=\\\"Bottom input\\\"]'))"))
        assertEquals("true", evaluate(host, "[...document.querySelectorAll('[data-bottom-fixture] button')].some(button => button.textContent.includes('Grow bottom'))"))

        evaluate(host, "[...document.querySelectorAll('[data-bottom-fixture] button')].find(button => button.textContent.includes('Grow bottom'))?.click()")
        compose.waitUntil(timeoutMillis = 10_000) {
            evaluate(host, "document.querySelector('[data-bottom-fixture]')?.style.height === '360px'") == "true"
        }

        evaluate(host, "[...document.querySelectorAll('[data-bottom-fixture] button')].find(button => button.textContent.includes('Collapse bottom'))?.click()")
        compose.waitUntil(timeoutMillis = 10_000) {
            evaluate(host, "!document.querySelector('[data-bottom-fixture]')") == "true"
        }

        application.pluginManager.enable(installed.id, false)
        compose.waitUntil(timeoutMillis = 30_000) {
            evaluate(host, "!document.querySelector('[data-bottom-fixture]')") == "true"
        }
        application.pluginManager.uninstall(installed.id)
        installedPluginId = null
        assertEquals("the original plugin must be removed from local metadata", null, application.database.plugins().get(installed.id))
        assertTrue("the original npm package directory must be removed", !File(application.pluginManager.pluginsRoot, "node_modules/@fixture/layout-bottom").exists())
        archive.delete()
    }

    @Test
    fun opensAnOriginalPluginConfigurationUiFromTheNativePluginManager(): Unit = runBlocking {
        val application = compose.activity.application as HarnessApplication
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val archive = File(application.cacheDir, "fixture-input-extension-0.0.1.tgz")
        testContext.assets.open("fixture-input-extension-0.0.1.tgz").use { input ->
            archive.outputStream().use(input::copyTo)
        }

        val installed = application.pluginManager.install(Uri.fromFile(archive))
        installedPluginId = installed.id
        assertEquals("@fixture/input-extension", installed.packageName)
        assertTrue("the test package must retain its upstream Web client contract", installed.manifestJson.contains("\"platform\":\"web\""))
        application.pluginManager.enable(installed.id, true)

        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.nav_plugins),
            useUnmergedTree = true,
        ).performClick()
        val openUiLabel = compose.activity.getString(R.string.action_open_plugin_ui)
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(openUiLabel, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(openUiLabel, useUnmergedTree = true).performClick()

        var runtimeFailure: String? = null
        compose.waitUntil(timeoutMillis = 30_000) {
            val host = webView()
            if (host == null) return@waitUntil false
            if (evaluate(host, "Boolean(document.querySelector('[data-package-ui]')?.getAttribute('data-package-ui') === '@fixture/input-extension' && document.querySelector('[data-package-ui]')?.textContent.includes('Input plugin configuration'))") == "true") return@waitUntil true
            runtimeFailure = evaluateString(host, "document.body.dataset.runtimeError || document.body.dataset.pluginSyncError || ''")
                ?.takeIf(String::isNotBlank)
            runtimeFailure != null
        }
        assertTrue("the original plugin configuration UI did not render: ${runtimeFailure.orEmpty()}", runtimeFailure == null)

        val host = requireNotNull(webView())
        assertEquals(
            "true",
            evaluate(host, "document.querySelector('[data-package-ui]')?.getAttribute('data-package-ui') === '@fixture/input-extension' && document.querySelector('[data-package-ui]')?.textContent.includes('Input plugin configuration')"),
        )

        application.pluginManager.enable(installed.id, false)
        compose.waitUntil(timeoutMillis = 30_000) {
            evaluate(host, "document.querySelector('[data-package-ui]')?.getAttribute('data-package-ui') !== '@fixture/input-extension'") == "true"
        }
        application.pluginManager.uninstall(installed.id)
        installedPluginId = null
        assertEquals("the original plugin must be removed from local metadata", null, application.database.plugins().get(installed.id))
        assertTrue("the original npm package directory must be removed", !File(application.pluginManager.pluginsRoot, "node_modules/@fixture/input-extension").exists())
        archive.delete()
    }

    @Test
    fun originalInputActivityPluginInsertsTextIntoTheNativeChatComposer(): Unit = runBlocking {
        val application = compose.activity.application as HarnessApplication
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val archive = File(application.cacheDir, "fixture-input-extension-chat-0.0.1.tgz")
        testContext.assets.open("fixture-input-extension-0.0.1.tgz").use { input ->
            archive.outputStream().use(input::copyTo)
        }
        val installed = application.pluginManager.install(Uri.fromFile(archive))
        installedPluginId = installed.id
        application.pluginManager.enable(installed.id, true)

        val viewModel = ViewModelProvider(compose.activity)[HarnessViewModel::class.java]
        viewModel.newSession()
        compose.waitUntil(timeoutMillis = 10_000) { viewModel.selectedSessionId.value != null }
        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.nav_chat),
            useUnmergedTree = true,
        ).performClick()

        var host: WebView? = null
        compose.waitUntil(timeoutMillis = 45_000) {
            host = compose.activity
                .findViewById<ViewGroup>(android.R.id.content)
                .findViewWithTag(PLUGIN_CHAT_INPUT_TEST_TAG)
            host != null && evaluate(host!!, "document.querySelector('button[aria-label]')?.getAttribute('aria-label') === 'Input activity'") == "true"
        }
        val pluginHost = requireNotNull(host)
        evaluate(pluginHost, "[...document.querySelectorAll('button')].find(button => button.getAttribute('aria-label') === 'Input activity')?.click()")
        compose.waitUntil(timeoutMillis = 10_000) {
            evaluate(pluginHost, "[...document.querySelectorAll('button')].some(button => button.textContent.includes('Insert result'))") == "true"
        }
        evaluate(pluginHost, "[...document.querySelectorAll('button')].find(button => button.textContent.includes('Insert result'))?.click()")
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onNodeWithTag(CHAT_PROMPT_TEST_TAG).fetchSemanticsNode().config[SemanticsProperties.EditableText].text == " from plugin"
        }
        assertEquals(
            "the original plugin result must be inserted into the editable native composer",
            " from plugin",
            compose.onNodeWithTag(CHAT_PROMPT_TEST_TAG).fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )

        application.pluginManager.enable(installed.id, false)
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.activity.findViewById<ViewGroup>(android.R.id.content)
                .findViewWithTag<View>(PLUGIN_CHAT_INPUT_TEST_TAG) == null
        }
        application.pluginManager.uninstall(installed.id)
        installedPluginId = null
        assertEquals("the original plugin must be removed from local metadata", null, application.database.plugins().get(installed.id))
        assertTrue("the original npm package directory must be removed", !File(application.pluginManager.pluginsRoot, "node_modules/@fixture/input-extension").exists())
        archive.delete()
    }

    private fun evaluate(webView: WebView, script: String): String? {
        val result = AtomicReference<String?>()
        val completed = CountDownLatch(1)
        compose.activity.runOnUiThread {
            webView.evaluateJavascript(script) { value ->
                result.set(value)
                completed.countDown()
            }
        }
        assertTrue("WebView JavaScript evaluation did not return", completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun evaluateString(webView: WebView, script: String): String? =
        evaluate(webView, script)?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }

    private fun webView(): WebView? = compose.activity
        .findViewById<ViewGroup>(android.R.id.content)
        .findViewWithTag<WebView>(PLUGIN_UI_TEST_TAG)
}
