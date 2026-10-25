package com.simplerc.app.signaling

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class SignalClient(
    private val listener: Listener,
    private val webSockets: WebSocket.Factory = sharedClient,
) {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(reason: String)
        fun onFailure(failure: ConnectionFailure)
    }

    private class Connection {
        var socket: WebSocket? = null
        var terminal = false
    }
    private val lock = Any()
    private var current: Connection? = null

    // Check ownership and deliver together: close/reconnect cannot invalidate a
    // socket between its callback check and a notification to the coordinator.
    private fun withCurrent(connection: Connection, action: () -> Unit) = synchronized(lock) {
        if (current === connection && !connection.terminal) action()
    }
    private fun terminate(connection: Connection, action: () -> Unit) = withCurrent(connection) {
        connection.terminal = true
        action()
    }

    fun connect(wssUrl: String, deviceId: String, timestamp: Long, signature: String) {
        require(wssUrl.startsWith("wss://")) { "正式信令只允许 WSS" }
        val request = Request.Builder().url(wssUrl)
            .header("X-Device-Id", deviceId).header("X-Timestamp", timestamp.toString())
            .header("X-Signature", signature).build()
        val connection = Connection()
        val previous = synchronized(lock) { current.also { current = connection } }
        previous?.socket?.cancel()
        val socket = try {
            webSockets.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = withCurrent(connection) {
                    // onOpen may win the race with newWebSocket returning.
                    connection.socket = webSocket
                    listener.onOpen()
                }
                override fun onMessage(webSocket: WebSocket, text: String) = withCurrent(connection) {
                    if (text.toByteArray(Charsets.UTF_8).size <= SignalValidator.MAX_MESSAGE_BYTES) listener.onMessage(text)
                    else terminate(connection) {
                        webSocket.close(1009, "message too large")
                        listener.onClosed("message too large")
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    terminate(connection) { listener.onClosed(reason) }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    terminate(connection) { listener.onClosed(reason) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = terminate(connection) {
                    listener.onFailure(connectionFailure(t,response?.code,response?.header("Retry-After")?.toIntOrNull() ?: 0))
                }
            })
        } catch (error: Exception) {
            synchronized(lock) { if (current === connection) current = null }
            throw error
        }
        val abandoned = synchronized(lock) {
            if (current === connection && !connection.terminal) { connection.socket = socket; false }
            else true
        }
        if (abandoned) socket.cancel()
    }

    fun send(text: String): Boolean = synchronized(lock) {
        current?.takeUnless { it.terminal }?.socket?.send(text) == true
    }
    fun close() {
        val previous = synchronized(lock) { current.also { current = null } }
        previous?.socket?.cancel()
    }
    companion object { private val sharedClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build() }
}
