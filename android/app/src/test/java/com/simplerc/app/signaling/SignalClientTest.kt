package com.simplerc.app.signaling

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SignalClientTest {
    private class Socket(private val request: Request) : WebSocket {
        val sent = mutableListOf<String>()
        val closes = mutableListOf<Pair<Int, String?>>()
        var cancellations = 0
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { closes += code to reason; return true }
        override fun cancel() { cancellations++ }
    }
    private class Attempt(val socket: Socket, val listener: WebSocketListener) {
        fun open() = listener.onOpen(socket, Response.Builder().request(socket.request())
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun message(text: String) = listener.onMessage(socket, text)
        fun closing(reason: String = "server closed") = listener.onClosing(socket, 1000, reason)
        fun closed() = listener.onClosed(socket, 1000, "closed")
        fun fail() = listener.onFailure(socket, IOException("connection lost"), null)
    }
    private class Factory : WebSocket.Factory {
        val attempts = mutableListOf<Attempt>()
        var beforeReturn: ((Attempt) -> Unit)? = null
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val attempt = Attempt(Socket(request), listener)
            attempts += attempt
            beforeReturn?.invoke(attempt)
            return attempt.socket
        }
    }
    private class Events : SignalClient.Listener {
        val values = mutableListOf<String>()
        override fun onOpen() { values += "open" }
        override fun onMessage(text: String) { values += "message:$text" }
        override fun onClosed(reason: String) { values += "closed:$reason" }
        override fun onFailure(failure: ConnectionFailure) { values += "failure:${failure.code}" }
    }
    private fun SignalClient.connect() = connect("wss://signal.example/ws", "device", 1L, "signature")

    @Test fun reconnectCancelsTheOldSocketAndRejectsAllItsLateCallbacks() {
        val factory = Factory(); val events = Events(); val client = SignalClient(events, factory)
        client.connect(); val old = factory.attempts.single(); old.open()
        client.connect(); val current = factory.attempts.last()
        assertEquals(1, old.socket.cancellations)
        old.open(); old.message("obsolete"); old.closing(); old.closed(); old.fail()
        assertEquals(listOf("open"), events.values)
        current.open(); current.message("current")
        assertEquals(listOf("open", "open", "message:current"), events.values)
        assertTrue(client.send("request"))
        assertTrue(old.socket.sent.isEmpty())
        assertEquals(listOf("request"), current.socket.sent)
    }

    @Test fun terminalCallbacksAreDeliveredOnceAndCannotReopenTheConnection() {
        val factory = Factory(); val events = Events(); val client = SignalClient(events, factory)
        client.connect(); val attempt = factory.attempts.single()
        attempt.open(); attempt.closing(); attempt.closed(); attempt.fail()
        attempt.open(); attempt.message("too late")
        assertEquals(listOf("open", "closed:server closed"), events.values)
        assertFalse(client.send("after close"))
        assertTrue(attempt.socket.sent.isEmpty())
    }

    @Test fun explicitCloseSuppressesCallbacksAndReleasesTheSocketOnlyOnce() {
        val factory = Factory(); val events = Events(); val client = SignalClient(events, factory)
        client.connect(); val attempt = factory.attempts.single()
        client.close(); client.close()
        attempt.open(); attempt.message("late"); attempt.closing(); attempt.closed(); attempt.fail()
        assertTrue(events.values.isEmpty())
        assertEquals(1, attempt.socket.cancellations)
        assertFalse(client.send("after close"))
    }

    @Test fun onOpenCanSendBeforeTheFactoryHasReturnedItsSocket() {
        val factory = Factory(); lateinit var client: SignalClient
        val sends = mutableListOf<Boolean>()
        client = SignalClient(object : SignalClient.Listener {
            override fun onOpen() { sends += client.send("first request") }
            override fun onMessage(text: String) = Unit
            override fun onClosed(reason: String) = Unit
            override fun onFailure(failure: ConnectionFailure) = Unit
        }, factory)
        factory.beforeReturn = { it.open() }
        client.connect()
        assertEquals(listOf(true), sends)
        assertEquals(listOf("first request"), factory.attempts.single().socket.sent)
    }

    @Test fun closingDuringSocketCreationCancelsTheSocketWhenItBecomesAvailable() {
        val factory = Factory(); val events = Events(); val client = SignalClient(events, factory)
        factory.beforeReturn = { client.close(); it.open() }
        client.connect()
        assertTrue(events.values.isEmpty())
        assertEquals(1, factory.attempts.single().socket.cancellations)
        assertFalse(client.send("obsolete"))
    }

    @Test fun reconnectDuringSocketCreationCannotReplaceTheNewerSocket() {
        val factory = Factory(); val events = Events(); val client = SignalClient(events, factory)
        factory.beforeReturn = {
            if (factory.attempts.size == 1) { client.connect(); it.open() }
        }
        client.connect()
        val old = factory.attempts.first(); val current = factory.attempts.last()
        assertEquals(2, factory.attempts.size)
        assertEquals(1, old.socket.cancellations)
        assertTrue(events.values.isEmpty())
        current.open()
        assertTrue(client.send("newest"))
        assertEquals(listOf("newest"), current.socket.sent)
        assertTrue(old.socket.sent.isEmpty())
    }

    @Test fun oversizedUtf8MessageClosesOnlyItsOwnConnection() {
        val factory = Factory(); val events = Events(); val client = SignalClient(events, factory)
        client.connect(); val attempt = factory.attempts.single(); attempt.open()
        attempt.message("界".repeat(SignalValidator.MAX_MESSAGE_BYTES / 3 + 1))
        attempt.message("late"); attempt.closed(); attempt.fail(); attempt.open()
        assertEquals(listOf("open", "closed:message too large"), events.values)
        assertEquals(listOf(1009 to "message too large"), attempt.socket.closes)
        assertFalse(client.send("after rejection"))
        client.connect(); factory.attempts.last().open()
        assertTrue(client.send("fresh"))
    }

    @Test fun invalidReplacementUrlLeavesTheCurrentConnectionUsable() {
        val factory = Factory(); val events = Events(); val client = SignalClient(events, factory)
        client.connect(); val attempt = factory.attempts.single(); attempt.open()
        try {
            client.connect("ws://signal.example/ws", "device", 2L, "signature")
            fail("Insecure URL accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, factory.attempts.size)
        assertEquals(0, attempt.socket.cancellations)
        assertTrue(client.send("still current"))
    }
}
