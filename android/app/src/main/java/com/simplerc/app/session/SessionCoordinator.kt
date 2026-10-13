package com.simplerc.app.session

import android.app.Application
import com.simplerc.app.config.AppConfig
import com.simplerc.app.credentials.CredentialRules
import com.simplerc.app.model.SessionState
import com.simplerc.app.security.DeviceIdentity
import com.simplerc.app.security.SecretStore
import com.simplerc.app.signaling.DeviceApi
import com.simplerc.app.signaling.SignalClient
import com.simplerc.app.state.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

class SessionCoordinator(private val app: Application) : SignalClient.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity = DeviceIdentity()
    private val secrets = SecretStore(app)
    private val api = DeviceApi(identity)
    private val sequence = AtomicLong()
    private var signal: SignalClient? = null
    private var deviceId: String? = null
    private var pendingAction: (() -> Unit)? = null
    private var credentialRefresh: Job? = null
    private var heartbeat: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var connected = false

    fun startControlled() {
        AppStateStore.update { it.copy(sessionState = SessionState.REGISTERING, statusDetail = "正在注册设备") }
        connectSignal {
            val fixed = secrets.get("fixed_mode") == "1"
            val id = secrets.get("fixed_id")
            val code = secrets.get("fixed_code")
            if (fixed && id != null && code != null) registerFixed(id, code) else registerRandomCredentials()
        }
    }

    fun loadSavedCredentials() {
        val fixed = secrets.get("fixed_mode") == "1"
        AppStateStore.update { it.copy(fixedCredentialMode = fixed, localId = if (fixed) secrets.get("fixed_id").orEmpty() else "", localCode = if (fixed) secrets.get("fixed_code").orEmpty() else "") }
    }

    fun useRandomCredentials() {
        secrets.put("fixed_mode", "0")
        AppStateStore.update { it.copy(fixedCredentialMode = false, localId = "", localCode = "") }
        if (connected && AppStateStore.state.value.standbyServiceRunning) registerRandomCredentials()
    }

    fun connectTo(targetId: String, code: String) {
        val normalized = CredentialRules.normalizeId(targetId)
        if (!CredentialRules.validId(normalized) || code.length < 8) {
            AppStateStore.update { it.copy(sessionState = SessionState.ERROR, statusDetail = "ID 或验证码格式不正确") }
            return
        }
        AppStateStore.update { it.copy(sessionState = SessionState.CONNECTING, statusDetail = "正在连接", errorMessage = null) }
        connectSignal { send("CONNECT", null, JSONObject().put("targetId", normalized).put("code", code)) }
    }

    fun registerFixed(id: String, code: String) {
        val normalized = CredentialRules.normalizeId(id)
        if (!CredentialRules.validId(normalized) || !CredentialRules.validCustomCode(code)) {
            AppStateStore.update { it.copy(statusDetail = "自定义 ID 格式错误或验证码少于 12 位") }
            return
        }
        val action = {
            secrets.put("fixed_id", normalized); secrets.put("fixed_code", code); secrets.put("fixed_mode", "1")
            send("REGISTER_FIXED", null, JSONObject().put("id", normalized).put("code", code))
            AppStateStore.update { it.copy(localId = normalized, localCode = code, fixedCredentialMode = true, credentialExpiresAt = null, statusDetail = "正在保存自定义凭据") }
        }
        if (connected) action() else connectSignal(action)
    }

    fun confirmIncoming(accepted: Boolean) {
        val sessionId = AppStateStore.state.value.incomingSessionId ?: return
        send("CONFIRM", sessionId, JSONObject().put("accepted", accepted))
        AppStateStore.update {
            it.copy(sessionState = if (accepted) SessionState.CONNECTING else SessionState.STANDBY, statusDetail = if (accepted) "等待屏幕共享授权" else "已拒绝连接", incomingSessionId = null, activeSessionId = if (accepted) sessionId else null)
        }
    }

    fun disconnect() {
        AppStateStore.state.value.activeSessionId?.let { send("REVOKE", it, JSONObject()) }
        heartbeat?.cancel()
        AppStateStore.update { it.copy(sessionState = SessionState.IDLE, statusDetail = "未连接", activeSessionId = null, incomingSessionId = null, controlEnabled = false, remoteVideoAvailable = false) }
    }

    fun stop() {
        disconnect()
        credentialRefresh?.cancel()
        signal?.close()
        signal = null
    }

    private fun connectSignal(afterOpen: () -> Unit) {
        pendingAction = afterOpen
        scope.launch {
            val id = deviceId ?: secrets.get("device_id") ?: api.register().getOrElse {
                AppStateStore.update { state -> state.copy(sessionState = SessionState.ERROR, statusDetail = it.message ?: "设备注册失败") }
                return@launch
            }.also { secrets.put("device_id", it) }
            deviceId = id
            val timestamp = System.currentTimeMillis()
            val signature = identity.sign("ws\n$id\n$timestamp".toByteArray())
            signal?.close()
            signal = SignalClient(this@SessionCoordinator).also { it.connect(AppConfig.SIGNALING_WS_URL, id, timestamp, signature) }
        }
    }

    private fun registerRandomCredentials() {
        val bytes = ByteArray(5).also(SecureRandom()::nextBytes)
        val id = "r" + bytes.joinToString("") { "%02x".format(it) }
        val code = CredentialRules.randomCode()
        val expiresAt = System.currentTimeMillis() / 1000 + AppConfig.RANDOM_CREDENTIAL_REFRESH_SECONDS
        send("REGISTER_RANDOM", null, JSONObject().put("id", id).put("code", code).put("expiresAtEpochSeconds", expiresAt))
        secrets.put("active_code", code)
        AppStateStore.update { it.copy(localId = id, localCode = code, credentialExpiresAt = expiresAt, statusDetail = "正在发布配对凭据") }
        credentialRefresh?.cancel()
        credentialRefresh = scope.launch {
            delay(AppConfig.RANDOM_CREDENTIAL_REFRESH_SECONDS * 1000)
            if (AppStateStore.state.value.sessionState == SessionState.STANDBY) registerRandomCredentials()
        }
    }

    private fun send(type: String, sessionId: String?, payload: JSONObject): Boolean {
        val envelope = JSONObject().put("version", 1).put("type", type).put("sequence", sequence.incrementAndGet())
            .put("sentAtEpochMs", System.currentTimeMillis()).put("payload", payload)
        if (sessionId != null) envelope.put("sessionId", sessionId)
        return signal?.send(envelope.toString()) == true
    }

    override fun onOpen() { connected = true; pendingAction?.invoke(); pendingAction = null }

    override fun onMessage(text: String) {
        runCatching {
            val envelope = JSONObject(text)
            val type = envelope.getString("type")
            val sessionId = envelope.optString("sessionId").takeIf(String::isNotBlank)
            val payload = envelope.optJSONObject("payload") ?: JSONObject()
            when (type) {
                "REGISTERED" -> AppStateStore.update { it.copy(sessionState = SessionState.STANDBY, statusDetail = "被控待机中") }
                "INCOMING" -> AppStateStore.update { it.copy(sessionState = SessionState.WAITING_CONFIRMATION, statusDetail = "收到控制请求，请在本机确认", incomingSessionId = sessionId) }
                "CONFIRMED" -> {
                    AppStateStore.update { it.copy(sessionState = SessionState.CONNECTING, statusDetail = "正在建立媒体连接", activeSessionId = sessionId) }
                    startHeartbeat(sessionId ?: return)
                    SessionMedia.instance(app).onConfirmed(sessionId, payload, ::sendRaw)
                }
                "OFFER", "ANSWER", "ICE", "GEOMETRY", "AUDIO_STATE" -> SessionMedia.instance(app).onSignal(type, sessionId, payload, ::sendRaw)
                "REJECTED" -> AppStateStore.update { it.copy(sessionState = SessionState.IDLE, statusDetail = "连接被拒绝") }
                "REVOKED" -> disconnect()
                "ERROR" -> AppStateStore.update { it.copy(sessionState = SessionState.ERROR, statusDetail = errorText(payload.optString("code")), errorMessage = payload.optString("code")) }
            }
        }.onFailure { AppStateStore.update { state -> state.copy(statusDetail = "收到无效信令") } }
    }

    private fun sendRaw(type: String, sessionId: String, payload: JSONObject) = send(type, sessionId, payload)
    private fun startHeartbeat(sessionId: String) { heartbeat?.cancel(); heartbeat = scope.launch { while (true) { delay(AppConfig.LEASE_RENEW_SECONDS * 1000); send("LEASE_RENEW", sessionId, JSONObject()) } } }
    override fun onClosed(reason: String) { scheduleReconnect(reason) }
    override fun onFailure(message: String) { scheduleReconnect(message) }
    private fun scheduleReconnect(reason: String) {
        connected = false
        if (AppStateStore.state.value.sessionState in listOf(SessionState.IDLE, SessionState.STOPPED)) return
        if (reconnectJob?.isActive == true) return
        AppStateStore.update { it.copy(sessionState = SessionState.RECONNECTING, statusDetail = "网络中断，正在重连", controlEnabled = false) }
        reconnectJob = scope.launch {
            val deadline = System.currentTimeMillis() + AppConfig.RECONNECT_GRACE_SECONDS * 1000
            var delayMs = 1_000L
            while (System.currentTimeMillis() < deadline) {
                delay(delayMs + SecureRandom().nextInt(500))
                val active = AppStateStore.state.value.activeSessionId
                connectSignal {
                    if (active != null) { send("LEASE_RENEW", active, JSONObject()); startHeartbeat(active) }
                    else if (AppStateStore.state.value.standbyServiceRunning) registerRandomCredentials()
                }
                delay(3_000)
                if (connected) return@launch
                delayMs = (delayMs * 2).coerceAtMost(15_000)
            }
            AppStateStore.update { it.copy(sessionState = SessionState.ERROR, statusDetail = "重连超时：$reason", activeSessionId = null, controlEnabled = false) }
        }
    }
    private fun errorText(code: String) = when (code) { "AUTH_FAILED" -> "此 ID 或验证码有误"; "RATE_LIMITED" -> "尝试过多，请稍后再试"; "LEASE_LOST" -> "会话租约已失效"; else -> "连接失败" }
}
