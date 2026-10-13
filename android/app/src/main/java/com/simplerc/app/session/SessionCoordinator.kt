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
    private var connectTimeout: Job? = null
    private var rateLimitCountdown: Job? = null
    private var registrationTimeout: Job? = null
    private var pendingFixedSave: Pair<String,String>? = null
    @Volatile private var connected = false

    fun startControlled() {
        AppStateStore.update { it.copy(sessionState = SessionState.REGISTERING, statusDetail = "正在注册设备") }
        connectSignal {
            val fixed = secrets.get("fixed_mode") == "1"
            val id = secrets.get("fixed_id")
            val code = secrets.get("fixed_code")
            if (fixed && id != null && code != null) publishFixed(id, code, persistOnSuccess = false) else registerRandomCredentials()
        }
    }

    fun loadSavedCredentials() {
        val fixed = secrets.get("fixed_mode") == "1"
        AppStateStore.update { it.copy(fixedCredentialMode = fixed, localId = if (fixed) secrets.get("fixed_id").orEmpty() else "", localCode = if (fixed) secrets.get("fixed_code").orEmpty() else "") }
    }

    fun useRandomCredentials() {
        credentialRefresh?.cancel()
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
            AppStateStore.update { it.copy(statusDetail = CredentialRules.idValidationText(id) ?: CredentialRules.codeValidationText(code) ?: "自定义凭据格式错误") }
            return
        }
        val action = {
            publishFixed(normalized, code, persistOnSuccess = true)
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
        connectTimeout?.cancel(); reconnectJob?.cancel(); rateLimitCountdown?.cancel(); registrationTimeout?.cancel()
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
            connected = false
            signal?.close()
            signal = SignalClient(this@SessionCoordinator).also { it.connect(AppConfig.SIGNALING_WS_URL, id, timestamp, signature) }
            connectTimeout?.cancel()
            connectTimeout = scope.launch {
                delay(AppConfig.SIGNAL_CONNECT_TIMEOUT_SECONDS * 1000)
                if (!connected) {
                    signal?.close()
                    AppStateStore.update { it.copy(sessionState=SessionState.ERROR,statusDetail="服务器连接超时",errorMessage="SERVER_UNREACHABLE") }
                }
            }
        }
    }

    private fun registerRandomCredentials() {
        if (secrets.get("fixed_mode") == "1") return
        val bytes = ByteArray(5).also(SecureRandom()::nextBytes)
        val id = "r" + bytes.joinToString("") { "%02x".format(it) }
        val code = CredentialRules.randomCode()
        val expiresAt = System.currentTimeMillis() / 1000 + AppConfig.RANDOM_CREDENTIAL_REFRESH_SECONDS
        send("REGISTER_RANDOM", null, JSONObject().put("id", id).put("code", code).put("expiresAtEpochSeconds", expiresAt))
        startRegistrationTimeout()
        secrets.put("active_code", code)
        AppStateStore.update { it.copy(localId = id, localCode = code, credentialExpiresAt = expiresAt, statusDetail = "正在发布配对凭据") }
        credentialRefresh?.cancel()
        credentialRefresh = scope.launch {
            delay(AppConfig.RANDOM_CREDENTIAL_REFRESH_SECONDS * 1000)
            if (secrets.get("fixed_mode") != "1" && AppStateStore.state.value.sessionState == SessionState.STANDBY) registerRandomCredentials()
        }
    }

    private fun publishFixed(id:String, code:String, persistOnSuccess:Boolean) {
        credentialRefresh?.cancel()
        if (persistOnSuccess) {
            pendingFixedSave = id to code
        }
        send("REGISTER_FIXED", null, JSONObject().put("id", id).put("code", code))
        startRegistrationTimeout()
        AppStateStore.update { it.copy(localId=id,localCode=code,fixedCredentialMode=it.fixedCredentialMode||!persistOnSuccess,credentialExpiresAt=null,statusDetail=if(persistOnSuccess)"正在保存自定义凭据" else "正在发布自定义凭据") }
    }

    private fun publishCurrentCredentials() {
        val fixed = secrets.get("fixed_mode") == "1"
        val id = secrets.get("fixed_id"); val code = secrets.get("fixed_code")
        if (fixed && id != null && code != null) publishFixed(id, code, false) else registerRandomCredentials()
    }
    private fun startRegistrationTimeout(){registrationTimeout?.cancel();registrationTimeout=scope.launch{delay(AppConfig.HANDSHAKE_TIMEOUT_SECONDS*1000);if(pendingFixedSave!=null){pendingFixedSave=null;loadSavedCredentials()};AppStateStore.update{it.copy(sessionState=SessionState.ERROR,statusDetail="凭据注册超时",errorMessage="REGISTRATION_TIMEOUT")}}}

    private fun send(type: String, sessionId: String?, payload: JSONObject): Boolean {
        val envelope = JSONObject().put("version", 1).put("type", type).put("sequence", sequence.incrementAndGet())
            .put("sentAtEpochMs", System.currentTimeMillis()).put("payload", payload)
        if (sessionId != null) envelope.put("sessionId", sessionId)
        return signal?.send(envelope.toString()) == true
    }

    override fun onOpen() { connected = true; connectTimeout?.cancel(); reconnectJob?.cancel(); pendingAction?.invoke(); pendingAction = null }

    override fun onMessage(text: String) {
        runCatching {
            val envelope = JSONObject(text)
            val type = envelope.getString("type")
            val sessionId = envelope.optString("sessionId").takeIf(String::isNotBlank)
            val payload = envelope.optJSONObject("payload") ?: JSONObject()
            when (type) {
                "REGISTERED" -> { registrationTimeout?.cancel();pendingFixedSave?.let{(id,code)->secrets.put("fixed_id",id);secrets.put("fixed_code",code);secrets.put("fixed_mode","1")};val savedFixed=pendingFixedSave!=null||secrets.get("fixed_mode")=="1";pendingFixedSave=null; AppStateStore.update { it.copy(sessionState=SessionState.STANDBY,statusDetail="被控待机中",errorMessage=null,retryAfterSeconds=null,fixedCredentialMode=savedFixed) } }
                "INCOMING" -> AppStateStore.update { it.copy(sessionState = SessionState.WAITING_CONFIRMATION, statusDetail = "收到控制请求，请在本机确认", incomingSessionId = sessionId) }
                "CONFIRMED" -> {
                    AppStateStore.update { it.copy(sessionState = SessionState.CONNECTING, statusDetail = "正在建立媒体连接", activeSessionId = sessionId) }
                    startHeartbeat(sessionId ?: return)
                    SessionMedia.instance(app).onConfirmed(sessionId, payload, ::sendRaw)
                }
                "OFFER", "ANSWER", "ICE", "GEOMETRY", "AUDIO_STATE" -> SessionMedia.instance(app).onSignal(type, sessionId, payload, ::sendRaw)
                "REJECTED" -> AppStateStore.update { it.copy(sessionState = SessionState.IDLE, statusDetail = "连接被拒绝") }
                "REVOKED" -> disconnect()
                "ERROR" -> {
                    registrationTimeout?.cancel()
                    val code=payload.optString("code")
                    if(pendingFixedSave!=null){pendingFixedSave=null;loadSavedCredentials()}
                    if(code=="RATE_LIMITED") startRateLimitCountdown(payload.optInt("retryAfterSeconds",60).coerceAtLeast(1))
                    else AppStateStore.update { it.copy(sessionState=SessionState.ERROR,statusDetail=errorText(code),errorMessage=code,retryAfterSeconds=null) }
                }
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
        val unavailable = !networkAvailable()
        AppStateStore.update { it.copy(sessionState=SessionState.RECONNECTING,statusDetail=if(unavailable)"网络不可用，等待恢复" else "服务器不可达，正在重连",controlEnabled=false,errorMessage=if(unavailable)"NETWORK_UNAVAILABLE" else "SERVER_UNREACHABLE") }
        reconnectJob = scope.launch {
            val deadline = System.currentTimeMillis() + AppConfig.RECONNECT_GRACE_SECONDS * 1000
            var delayMs = 1_000L
            while (System.currentTimeMillis() < deadline) {
                delay(delayMs + SecureRandom().nextInt(AppConfig.RECONNECT_JITTER_MILLIS))
                val active = AppStateStore.state.value.activeSessionId
                connectSignal {
                    if (active != null) { send("LEASE_RENEW", active, JSONObject()); startHeartbeat(active) }
                    else if (AppStateStore.state.value.standbyServiceRunning) publishCurrentCredentials()
                }
                delay(3_000)
                if (connected) return@launch
                delayMs = (delayMs * 2).coerceAtMost(AppConfig.RECONNECT_MAX_DELAY_SECONDS * 1000)
            }
            AppStateStore.update { it.copy(sessionState = SessionState.ERROR, statusDetail = "重连超时：$reason", activeSessionId = null, controlEnabled = false) }
        }
    }
    private fun startRateLimitCountdown(seconds:Int){
        reconnectJob?.cancel(); rateLimitCountdown?.cancel()
        rateLimitCountdown=scope.launch { for(left in seconds downTo 1){AppStateStore.update{it.copy(sessionState=SessionState.ERROR,statusDetail="验证失败次数过多，${left} 秒后可重试",errorMessage="RATE_LIMITED",retryAfterSeconds=left)};delay(1000)};AppStateStore.update{it.copy(statusDetail="可以重试",retryAfterSeconds=0)} }
    }
    fun retryControlled(){ if(AppStateStore.state.value.standbyServiceRunning && AppStateStore.state.value.retryAfterSeconds.let{it==null||it==0}) startControlled() }
    private fun networkAvailable():Boolean { val cm=app.getSystemService(android.net.ConnectivityManager::class.java);return cm.activeNetwork!=null }
    private fun errorText(code: String) = when (code) { "AUTH_FAILED" -> "此 ID 或验证码有误"; "RATE_LIMITED" -> "验证失败次数过多"; "ID_UNAVAILABLE" -> "该 ID 已被使用，无法使用"; "LEASE_LOST" -> "会话租约已失效"; else -> "连接失败" }
}
