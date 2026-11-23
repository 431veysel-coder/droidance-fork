// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.browser

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.tools.DeviceToolNames
import com.droidance.core.device.tools.UnifiedDeviceToolRegistrar
import com.droidance.core.device.tools.UnifiedDeviceToolProtocol
import com.droidance.core.agent.tools.ToolDefinition
import com.droidance.core.agent.tools.ToolParameter
import com.droidance.core.agent.tools.ToolTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserToolRegistrar @Inject constructor(
    private val browserManager: BrowserManager,
    private val registerTool: (UnifiedDeviceToolRegistrar.Tool) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun registerAll() {
        registerTool(BrowserActTool())
    }

    private inner class BrowserActTool : UnifiedDeviceToolRegistrar.Tool() {
        override val name = DeviceToolNames.BROWSER_ACT
        override val tier = ToolTier.DEVICE

        override val definition = ToolDefinition(
            name = name,
            description = "Control embedded browser via CDP: navigate, click, type, screenshot, eval_js, get_dom, wait_for, create_profile, launch, close",
            parameters = mapOf(
                "op" to ToolParameter("string", "Operation to perform", true, listOf(
                    "create_profile", "launch", "navigate", "click", "type_text",
                    "screenshot", "get_dom", "eval_js", "wait_for", "close", "close_all"
                )),
                "profile_id" to ToolParameter("string", "Browser profile ID (for create_profile/launch)", false),
                "profile_name" to ToolParameter("string", "Profile name (for create_profile)", false),
                "user_agent" to ToolParameter("string", "Custom user agent", false),
                "viewport_width" to ToolParameter("integer", "Viewport width", false),
                "viewport_height" to ToolParameter("integer", "Viewport height", false),
                "webview_id" to ToolParameter("integer", "WebView instance ID (returned by launch)", false),
                "url" to ToolParameter("string", "URL to navigate to", false),
                "selector" to ToolParameter("string", "CSS selector for click/wait_for", false),
                "x" to ToolParameter("integer", "X coordinate for click", false),
                "y" to ToolParameter("integer", "Y coordinate for click", false),
                "text" to ToolParameter("string", "Text to type", false),
                "expression" to ToolParameter("string", "JavaScript expression for eval_js", false),
                "format" to ToolParameter("string", "Screenshot format (png/jpeg)", false),
                "quality" to ToolParameter("integer", "Screenshot quality 1-100", false),
                "timeout_ms" to ToolParameter("integer", "Timeout in milliseconds", false)
            )
        )

        override suspend fun execute(arguments: String): ToolExecutionResult = withContext(Dispatchers.IO) {
            val input = json.decodeFromString<JsonObject>(arguments)
            val op = input["op"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("op")

            return when (op) {
                "create_profile" -> createProfile(input)
                "launch" -> launchWebView(input)
                "navigate" -> navigate(input)
                "click" -> click(input)
                "type_text" -> typeText(input)
                "screenshot" -> screenshot(input)
                "get_dom" -> getDom(input)
                "eval_js" -> evalJs(input)
                "wait_for" -> waitFor(input)
                "close" -> close(input)
                "close_all" -> closeAll(input)
                else -> ToolProtocolResult.notExecuted("UNKNOWN_BROWSER_OP: $op", true)
            }
        }

        private fun createProfile(input: JsonObject): ToolExecutionResult {
            val name = input["profile_name"]?.jsonPrimitive?.content ?: "default"
            val userAgent = input["user_agent"]?.jsonPrimitive?.content
            val viewportWidth = input["viewport_width"]?.jsonPrimitive?.int ?: 1080
            val viewportHeight = input["viewport_height"]?.jsonPrimitive?.int ?: 1920

            val profile = browserManager.createProfile(
                name = name,
                userAgent = userAgent ?: BrowserManager.DEFAULT_USER_AGENT,
                viewport = BrowserManager.BrowserProfile.Viewport(viewportWidth, viewportHeight)
            )
            return ToolProtocolResult.success(buildJsonObject {
                put("profile_id", profile.id)
                put("profile_name", profile.name)
            })
        }

        private fun launchWebView(input: JsonObject): ToolExecutionResult {
            val profileId = input["profile_id"]?.jsonPrimitive?.content
                ?: return ToolProtocolResult.missingArgument("profile_id")
            val webViewId = browserManager.launchWebView(profileId).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("webview_id", webViewId)
                put("profile_id", profileId)
            })
        }

        private fun navigate(input: JsonObject): ToolExecutionResult {
            val webViewId = input["webview_id"]?.jsonPrimitive?.int ?: return ToolProtocolResult.missingArgument("webview_id")
            val url = input["url"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("url")
            val waitUntil = input["wait_until"]?.jsonPrimitive?.content ?: "networkidle"
            browserManager.navigate(webViewId, url, waitUntil).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("url", url)
                put("webview_id", webViewId)
            })
        }

        private fun click(input: JsonObject): ToolExecutionResult {
            val webViewId = input["webview_id"]?.jsonPrimitive?.int ?: return ToolProtocolResult.missingArgument("webview_id")
            val selector = input["selector"]?.jsonPrimitive?.content
            val x = input["x"]?.jsonPrimitive?.int
            val y = input["y"]?.jsonPrimitive?.int

            if (selector != null) {
                browserManager.click(webViewId, selector = selector).getOrElse { throw it }
            } else if (x != null && y != null) {
                browserManager.click(webViewId, x = x, y = y).getOrElse { throw it }
            } else {
                return ToolProtocolResult.missingArgument("selector or x,y")
            }
            return ToolProtocolResult.success(buildJsonObject { put("clicked", true) })
        }

        private fun typeText(input: JsonObject): ToolExecutionResult {
            val webViewId = input["webview_id"]?.jsonPrimitive?.int ?: return ToolProtocolResult.missingArgument("webview_id")
            val text = input["text"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("text")
            browserManager.typeText(webViewId, text).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject { put("typed", true) })
        }

        private fun screenshot(input: JsonObject): ToolExecutionResult {
            val webViewId = input["webview_id"]?.jsonPrimitive?.int ?: return ToolProtocolResult.missingArgument("webview_id")
            val format = input["format"]?.jsonPrimitive?.content ?: "png"
            val quality = input["quality"]?.jsonPrimitive?.int ?: 80
            val bytes = browserManager.screenshot(webViewId, format, quality).getOrElse { throw it }
            // Store as blob, return reference
            val blobRef = "sha256:${java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"
            return ToolProtocolResult.success(buildJsonObject {
                put("screenshot_ref", blobRef)
                put("format", format)
                put("size", bytes.size)
                put("webview_id", webViewId)
            })
        }

        private fun getDom(input: JsonObject): ToolExecutionResult {
            val webViewId = input["webview_id"]?.jsonPrimitive?.int ?: return ToolProtocolResult.missingArgument("webview_id")
            val selector = input["selector"]?.jsonPrimitive?.content
            val dom = browserManager.getDom(webViewId, selector).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("dom", dom)
                put("selector", selector ?: "document")
                put("webview_id", webViewId)
            })
        }

        private fun evalJs(input: JsonObject): ToolExecutionResult {
            val webViewId = input["webview_id"]?.jsonPrimitive?.int ?: return ToolProtocolResult.missingArgument("webview_id")
            val expression = input["expression"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("expression")
            val result = browserManager.evalJs(webViewId, expression).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("result", result)
                put("expression", expression)
                put("webview_id", webViewId)
            })
        }

        private fun waitFor(input: JsonObject): ToolExecutionResult {
            val webViewId = input["webview_id"]?.jsonPrimitive?.int ?: return ToolProtocolResult.missingArgument("webview_id")
            val selector = input["selector"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("selector")
            val timeoutMs = (input["timeout_ms"]?.jsonPrimitive?.long ?: 30_000L)
            browserManager.waitForSelector(webViewId, selector, timeoutMs).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("selector", selector)
                put("found", true)
            })
        }

        private fun close(input: JsonObject): ToolExecutionResult {
            val webViewId = input["webview_id"]?.jsonPrimitive?.int
            if (webViewId != null) {
                browserManager.closeWebView(webViewId)
            }
            return ToolProtocolResult.success(buildJsonObject { put("closed", webViewId != null) })
        }

        private fun closeAll(input: JsonObject): ToolExecutionResult {
            browserManager.closeAll()
            return ToolProtocolResult.success(buildJsonObject { put("closed_all", true) })
        }
    }
}