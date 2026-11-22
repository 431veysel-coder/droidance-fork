// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.browser

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.os.Build
import com.droidance.debug.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserManager @Inject constructor(
    @androidx.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val cdpClient: BrowserCdpClient,
    private val debugLogManager: DebugLogManager
) {
    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"
        const val CDP_DEBUG_PORT = 9222
    }

    private val webViews = ConcurrentHashMap<Int, WebView>()
    private val profiles = ConcurrentHashMap<String, BrowserProfile>()
    private val nextProfileId = AtomicInteger(1)
    private val nextWebViewId = AtomicInteger(1)

    data class BrowserProfile(
        val id: String,
        val name: String,
        val cookieStore: String, // serialized cookies
        val localStorage: String, // serialized localStorage
        val userAgent: String,
        val viewport: Viewport
    ) {
        data class Viewport(val width: Int = 1080, val height: Int = 1920, val scale: Float = 1.0f)
    }

    data class WebViewInstance(
        val id: Int,
        val webView: WebView,
        val profileId: String,
        val cdpPort: Int
    )

    /** Yeni browser profili oluştur. */
    fun createProfile(
        name: String = "default",
        userAgent: String = DEFAULT_USER_AGENT,
        viewport: BrowserProfile.Viewport = BrowserProfile.Viewport()
    ): BrowserProfile {
        val id = "profile_${nextProfileId.getAndIncrement()}"
        val profile = BrowserProfile(id, name, "{}", "{}", userAgent, viewport)
        profiles[id] = profile
        debugLogManager.appendEvent("browser: profile created $id ($name)")
        return profile
    }

    /** Profil sil. */
    fun deleteProfile(profileId: String) {
        profiles.remove(profileId)
        // Close associated WebViews
        webViews.values().filter { it.profileId == profileId }.forEach { closeWebView(it.id) }
        debugLogManager.appendEvent("browser: profile deleted $profileId")
    }

    /** WebView başlat (sanal ekranda, görünmez). */
    suspend fun launchWebView(profileId: String): Result<Int> = withContext(Dispatchers.IO) {
        val profile = profiles[profileId] ?: return Result.failure(IllegalArgumentException("Profile not found: $profileId"))

        // WebView must be created on UI thread - we'll use a workaround
        // In practice, this runs on a HandlerThread with Looper
        return@withContext try {
            val webViewId = nextWebViewId.getAndIncrement()
            val cdpPort = CDP_DEBUG_PORT + webViewId

            // Create WebView on a dedicated thread with Looper
            val webView = createWebViewOnUiThread(profile, cdpPort)
            val instance = WebViewInstance(webViewId, webView, profileId, cdpPort)
            webViews[webViewId] = instance

            // Enable CDP debugging
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }

            // Wait for CDP to be ready
            Thread.sleep(500)

            // Connect CDP client
            val cdpUrl = "ws://localhost:$cdpPort/devtools/page/1"
            cdpClient.connect(cdpUrl).getOrThrow()

            debugLogManager.appendEvent("browser: WebView $webViewId launched for profile $profileId (CDP:$cdpPort)")
            Result.success(webViewId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createWebViewOnUiThread(profile: BrowserProfile, cdpPort: Int): WebView {
        // This is a simplified version - actual implementation needs a HandlerThread
        val webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = profile.userAgent
                setViewportWidth(profile.viewport.width)
                setViewportHeight(profile.viewport.height)
                defaultTextEncodingName = "UTF-8"
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
            webViewClient = BrowserWebViewClient(profile.id)
            webChromeClient = BrowserWebChromeClient()
        }

        // Load blank page first
        webView.loadData("<html><body></body></html>", "text/html", "UTF-8")
        return webView
    }

    /** URL'ye git. */
    suspend fun navigate(webViewId: Int, url: String, waitUntil: String = "networkidle"): Result<String> {
        val instance = webViews[webViewId] ?: return Result.failure(IllegalArgumentException("WebView not found: $webViewId"))
        val result = cdpClient.sendCommand("Page.navigate", JsonObject(mapOf("url" to url))).getOrThrow()
        val frameId = result["frameId"]?.jsonPrimitive?.content ?: return Result.failure(IllegalStateException("No frameId"))

        // Wait for load event
        return suspendCancellableCoroutine { cont ->
            val listener = cdpClient.onEvent("Page.loadEventFired") { _ ->
                cont.resume(Result.success(frameId))
            }
            // Timeout
            kotlinx.coroutines.Dispatchers.IO.delay(30_000).also {
                cdpClient.offEvent("Page.loadEventFired", listener)
                if (!cont.isCompleted) cont.resume(Result.failure(TimeoutException("Navigation timeout")))
            }
        }
    }

    /** Element tıkla (selector veya koordinat). */
    suspend fun click(webViewId: Int, selector: String? = null, x: Int? = null, y: Int? = null): Result<Unit> {
        if (selector != null) {
            // Get element coordinates via CDP
            val coords = getElementCoordinates(webViewId, selector).getOrThrow()
            return clickAt(webViewId, coords.x, coords.y)
        } else if (x != null && y != null) {
            return clickAt(webViewId, x, y)
        }
        return Result.failure(IllegalArgumentException("Either selector or x,y required"))
    }

    private suspend fun clickAt(webViewId: Int, x: Int, y: Int): Result<Unit> {
        cdpClient.sendCommand("Input.dispatchMouseEvent", JsonObject(mapOf(
            "type" to "mousePressed",
            "x" to x.toDouble(),
            "y" to y.toDouble(),
            "button" to "left",
            "clickCount" to 1
        ))).getOrThrow()
        cdpClient.sendCommand("Input.dispatchMouseEvent", JsonObject(mapOf(
            "type" to "mouseReleased",
            "x" to x.toDouble(),
            "y" to y.toDouble(),
            "button" to "left",
            "clickCount" to 1
        ))).getOrThrow()
        return Result.success(Unit)
    }

    /** Metin yaz. */
    suspend fun typeText(webViewId: Int, text: String): Result<Unit> {
        cdpClient.sendCommand("Input.insertText", JsonObject(mapOf("text" to text))).getOrThrow()
        return Result.success(Unit)
    }

    /** Ekran görüntüsü al. */
    suspend fun screenshot(webViewId: Int, format: String = "png", quality: Int = 80): Result<ByteArray> {
        val result = cdpClient.sendCommand("Page.captureScreenshot", JsonObject(mapOf(
            "format" to format,
            "quality" to (quality / 100.0)
        ))).getOrThrow()
        val base64 = result["data"]?.jsonPrimitive?.content ?: return Result.failure(IllegalStateException("No screenshot data"))
        return Result.success(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
    }

    /** DOM al (outerHTML). */
    suspend fun getDom(webViewId: Int, selector: String? = null): Result<String> {
        val expression = if (selector != null) {
            "document.querySelector('$selector')?.outerHTML ?? ''"
        } else {
            "document.documentElement.outerHTML"
        }
        val result = cdpClient.sendCommand("Runtime.evaluate", JsonObject(mapOf(
            "expression" to expression,
            "returnByValue" to true
        ))).getOrThrow()
        val value = result["result"]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
        return Result.success(value)
    }

    /** JavaScript çalıştır. */
    suspend fun evalJs(webViewId: Int, expression: String): Result<String> {
        val result = cdpClient.sendCommand("Runtime.evaluate", JsonObject(mapOf(
            "expression" to expression,
            "returnByValue" to true,
            "awaitPromise" to true
        ))).getOrThrow()
        if (result["exceptionDetails"] != null) {
            val err = result["exceptionDetails"] as JsonObject
            return Result.failure(IllegalStateException("JS error: ${err["text"]}"))
        }
        val value = result["result"]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
        return Result.success(value)
    }

    /** Selector için bekle. */
    suspend fun waitForSelector(webViewId: Int, selector: String, timeoutMs: Long = 30_000L): Result<Unit> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val exists = evalJs(webViewId, "!!document.querySelector('$selector')").getOrElse { false }
            if (exists == "true") return Result.success(Unit)
            Thread.sleep(100)
        }
        Result.failure(TimeoutException("Selector '$selector' not found within ${timeoutMs}ms"))
    }

    /** Element koordinatlarını al. */
    private suspend fun getElementCoordinates(webViewId: Int, selector: String): Result<Point> {
        val script = """
            (function() {
                const el = document.querySelector('$selector');
                if (!el) return null;
                const rect = el.getBoundingClientRect();
                return {x: rect.left + rect.width/2, y: rect.top + rect.height/2};
            })()
        """.trimIndent()
        val result = evalJs(webViewId, script).getOrThrow()
        val json = kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonObject>(result)
        val x = json["x"]?.jsonPrimitive?.double?.toInt() ?: return Result.failure(IllegalStateException("No x coordinate"))
        val y = json["y"]?.jsonPrimitive?.double?.toInt() ?: return Result.failure(IllegalStateException("No y coordinate"))
        return Result.success(Point(x, y))
    }

    /** WebView kapat. */
    fun closeWebView(webViewId: Int) {
        webViews.remove(webViewId)?.webView?.destroy()
        debugLogManager.appendEvent("browser: WebView $webViewId closed")
    }

    /** Tüm WebView'ları kapat. */
    fun closeAll() {
        webViews.values.forEach { it.webView.destroy() }
        webViews.clear()
        cdpClient.disconnect()
        debugLogManager.appendEvent("browser: all WebViews closed")
    }

    private data class Point(val x: Int, val y: Int)

    private inner class BrowserWebViewClient(private val profileId: String) : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            debugLogManager.appendEvent("browser: page finished $url (profile: $profileId)")
        }
    }

    private inner class BrowserWebChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage) {
            debugLogManager.appendEvent("browser: console [${consoleMessage.messageLevel()}] ${consoleMessage.message()}")
        }
    }
}