package ai.comicine.dshmobile.plugins

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/** Serves app assets and approved, installed upstream client bundles into the local WebView. */
class PluginUiAssetResolver(context: Context, private val pluginsRoot: File) {
    private val app = context.applicationContext
    @Volatile private var allowedPackages: Set<String> = emptySet()

    fun updateAllowedPackages(packageNames: Set<String>) {
        allowedPackages = packageNames.toSet()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.host != APP_HOST) return null
        if (request.method != "GET" && request.method != "HEAD") return response(405, "Method Not Allowed", "text/plain", ByteArray(0))
        val path = uri.encodedPath.orEmpty().removePrefix("/")
        if (path.startsWith("plugin-ui/")) {
            val safe = path.split('/').none { it == ".." || it.isEmpty() }
            if (!safe) return notFound()
            return runCatching { app.assets.open(path).use { bytes -> response(200, "OK", mime(path), bytes.readBytes()) } }
                .getOrElse { notFound() }
        }
        // WebBootGraph uses `/plugins/??<package>/client.js&rev=...`; the
        // question marks start the query, so Android exposes `/plugins/` as
        // the encoded path.
        if (path.trimEnd('/') != "plugins") return notFound()
        return resolvePluginResource(uri.toString())
    }

    private fun resolvePluginResource(url: String): WebResourceResponse {
        val marker = url.indexOf("/plugins/")
        if (marker < 0) return notFound()
        val rawRoute = url.substring(marker + "/plugins/".length)
        val route = (if (rawRoute.startsWith("??")) rawRoute.substring(2) else rawRoute)
            .substringBefore("&rev=")
            .substringBefore('?')
            .let(Uri::decode)
        val segments = route.split('/').filter(String::isNotEmpty)
        if (segments.isEmpty()) return notFound()
        val packageSegmentCount = if (segments[0].startsWith('@')) 2 else 1
        if (segments.size <= packageSegmentCount) return notFound()
        val packageName = segments.take(packageSegmentCount).joinToString("/")
        if (!validPackageName(packageName) || packageName !in allowedPackages) return notFound()
        val relative = segments.drop(packageSegmentCount).joinToString("/")
        val packageDir = safePackageDirectory(packageName) ?: return notFound()
        val entry = resolveClientEntry(packageDir) ?: return notFound()
        val file = when {
            relative == "client.js" -> entry
            relative.startsWith("assets/") -> resolveExportedAsset(packageDir, relative.removePrefix("assets/"))
            CLIENT_CHUNK.matches(relative) -> safeChild(entry.parentFile ?: return notFound(), relative)
            else -> null
        } ?: return notFound()
        if (!file.isFile || file.length() > MAX_RESOURCE_BYTES) return notFound()
        val headers = linkedMapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Access-Control-Allow-Origin" to "https://$APP_HOST",
        )
        if (file.extension.lowercase(Locale.ROOT) == "svg") {
            headers["Content-Security-Policy"] = "script-src 'none'"
        }
        return runCatching {
            WebResourceResponse(mime(file.name), "utf-8", 200, "OK", headers, FileInputStream(file))
        }.getOrElse { notFound() }
    }

    private fun safePackageDirectory(packageName: String): File? {
        val modulesRoot = File(pluginsRoot, "node_modules").canonicalFile
        val candidate = File(modulesRoot, packageName).canonicalFile
        return candidate.takeIf { it.path.startsWith(modulesRoot.path + File.separator) && it.isDirectory }
    }

    private fun resolveClientEntry(packageDir: File): File? {
        val manifest = runCatching { JSONObject(File(packageDir, "package.json").readText()) }.getOrNull() ?: return null
        val exports = manifest.optJSONObject("exports") ?: return null
        val client = exports.opt("./client")
        val target = when (client) {
            is String -> client
            is JSONObject -> client.optString("browser").takeIf(String::isNotBlank)
                ?: client.optString("default").takeIf(String::isNotBlank)
                ?: client.optString("import").takeIf(String::isNotBlank)
            else -> null
        } ?: return null
        return safeChild(packageDir, target.removePrefix("./"))?.takeIf(File::isFile)
    }

    private fun resolveExportedAsset(packageDir: File, assetName: String): File? {
        if (assetName.isBlank() || assetName.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        val extension = assetName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in SAFE_ASSET_EXTENSIONS) return null
        val manifest = runCatching { JSONObject(File(packageDir, "package.json").readText()) }.getOrNull() ?: return null
        val exports = manifest.optJSONObject("exports") ?: return null
        val declaration = exports.opt("./assets/*")
        val target = when (declaration) {
            is String -> declaration
            is JSONObject -> declaration.optString("default").takeIf(String::isNotBlank)
                ?: declaration.optString("import").takeIf(String::isNotBlank)
            else -> null
        } ?: return null
        val prefix = target.substringBefore('*', missingDelimiterValue = "")
        if (prefix.isBlank()) return null
        return safeChild(packageDir, prefix.removePrefix("./") + assetName)
    }

    private fun safeChild(parent: File, relative: String): File? {
        if (relative.isBlank() || relative.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        val canonicalParent = parent.canonicalFile
        val child = File(canonicalParent, relative).canonicalFile
        return child.takeIf { it.path.startsWith(canonicalParent.path + File.separator) }
    }

    private fun validPackageName(name: String): Boolean = PACKAGE_NAME.matches(name)

    private fun notFound(): WebResourceResponse = response(404, "Not Found", "text/plain", ByteArray(0))

    private fun response(status: Int, reason: String, contentType: String, body: ByteArray): WebResourceResponse =
        WebResourceResponse(contentType, "utf-8", status, reason, emptyMap(), ByteArrayInputStream(body))

    private fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    private companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        const val MAX_RESOURCE_BYTES = 24L * 1024 * 1024
        val PACKAGE_NAME = Regex("^(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+$")
        val CLIENT_CHUNK = Regex("client\\.[A-Za-z0-9][A-Za-z0-9._-]*\\.js")
        val SAFE_ASSET_EXTENSIONS = setOf("svg", "png", "webp", "jpg", "jpeg")
    }
}
