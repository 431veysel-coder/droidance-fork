// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.browser

import android.util.Log
import com.droidance.debug.DebugLogManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okio.ByteString
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserCdpClient @Inject constructor(
    private val debugLogManager: DebugLogManager
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private val requestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val eventListeners = mutableMapOf<String, MutableList<(JsonObject) -> Unit>>()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val connectionLock = Any()

    data class CdpResponse(
        val id: Long?,
        val result: JsonObject?,
        val error: JsonObject?,
        val method: String?,
        val params: JsonObject?
    )

    /** CDP WebSocket bağlantısını kur. */
    suspend fun connect(cdpUrl: String): Result<Unit> {
        synchronized(connectionLock) {
            if (isConnected) return Result.success(Unit)
        }

        return suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(cdpUrl)
                .build()

            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    BrowserCdpClient.this.webSocket = webSocket
                    isConnected = true
                    debugLogManager.appendEvent("browser: CDP connected to $cdpUrl")
                    cont.resume(Result.success(Unit))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    debugLogManager.appendEvent("browser: CDP connection failed: ${t.message}")
                    if (!cont.isCompleted) cont.resume(Result.failure(t))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Binary messages not expected for CDP
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    debugLogManager.appendEvent("browser: CDP closed: $code $reason")
                    // Reject all pending
                    pendingRequests.values.forEach { it.completeExceptionally(IllegalStateException("CDP closed: $reason")) }
                    pendingRequests.clear()
                }
            })
        }
    }

    private fun handleMessage(text: String) {
        val json = Json.decodeFromString<JsonObject>(text)
        val id = json["id"]?.jsonPrimitive?.longOrNull()

        if (id != null) {
            // Response to a command
            val deferred = pendingRequests.remove(id)
            deferred?.complete(json)
        } else {
            // Event notification
            val method = json["method"]?.jsonPrimitive?.content ?: return
            val params = json["params"]?.jsonObject
            eventListeners[method]?.forEach { listener ->
                try { listener(params ?: JsonObject()) } catch (e: Exception) { Log.w("BrowserCdp", "Event listener error", e) }
            }
        }
    }

    /** CDP komutu gönder ve sonucu bekle. */
    suspend fun sendCommand(method: String, params: JsonObject = JsonObject()): Result<JsonObject> {
        synchronized(connectionLock) {
            if (!isConnected || webSocket == null) {
                return Result.failure(IllegalStateException("CDP not connected"))
            }
        }

        val id = requestId.getAndIncrement()
        val requestJson = JsonObject(mapOf(
            "id" to JsonPrimitive(id),
            "method" to JsonPrimitive(method),
            "params" to params
        ))

        return suspendCancellableCoroutine { cont ->
            val deferred = CompletableDeferred<JsonObject>()
            pendingRequests[id] = deferred

            webSocket!!.send(requestJson.toString())

            deferred.await().also { response ->
                if (response["error"] != null) {
                    val err = response["error"] as JsonObject
                    cont.resume(Result.failure(IllegalStateException("CDP error ${err["code"]}: ${err["message"]}")))
                } else {
                    cont.resume(Result.success(response["result"] as? JsonObject ?: JsonObject()))
                }
            }
        }
    }

    /** Event listener kaydet. */
    fun onEvent(method: String, listener: (JsonObject) -> Unit) {
        eventListeners.computeIfAbsent(method) { mutableListOf() }.add(listener)
    }

    /** Event listener kaldır. */
    fun offEvent(method: String, listener: (JsonObject) -> Unit) {
        eventListeners[method]?.remove(listener)
    }

    /** Bağlantıyı kapat. */
    fun disconnect() {
        synchronized(connectionLock) {
            webSocket?.close(1000, "Client disconnect")
            webSocket = null
            isConnected = false
        }
    }

    val isConnected: Boolean get() = isConnected
}