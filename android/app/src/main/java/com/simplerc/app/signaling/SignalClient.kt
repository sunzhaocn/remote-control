package com.simplerc.app.signaling

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class SignalClient(private val listener: Listener) {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(reason: String)
        fun onFailure(message: String)
    }

    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    @Volatile private var closedByClient = false

    fun connect(wssUrl: String, deviceId: String, timestamp: Long, signature: String) {
        closedByClient = false
        require(wssUrl.startsWith("wss://")) { "正式信令只允许 WSS" }
        val request = Request.Builder().url(wssUrl)
            .header("X-Device-Id", deviceId).header("X-Timestamp", timestamp.toString())
            .header("X-Signature", signature).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray().size <= SignalValidator.MAX_MESSAGE_BYTES) listener.onMessage(text)
                else webSocket.close(1009, "message too large")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (!closedByClient) listener.onClosed(reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (!closedByClient) listener.onFailure(t.message ?: "网络错误") }
        })
    }

    fun send(text: String): Boolean = socket?.send(text) == true
    fun close() { closedByClient = true; socket?.close(1000, "client stop"); socket = null }
}
