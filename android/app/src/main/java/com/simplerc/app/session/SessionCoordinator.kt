package com.simplerc.app.session

import android.app.Application
import android.os.SystemClock
import com.simplerc.app.config.AppConfig
import com.simplerc.app.credentials.CredentialRules
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import com.simplerc.app.security.DeviceIdentity
import com.simplerc.app.security.SecretStore
import com.simplerc.app.signaling.ConnectionFailure
import com.simplerc.app.signaling.DeviceApi
import com.simplerc.app.signaling.SignalClient
import com.simplerc.app.signaling.connectionFailure
import com.simplerc.app.state.AppStateStore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** All lifecycle decisions and transport callbacks run on the main dispatcher. */
class SessionCoordinator(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val identity = DeviceIdentity()
    private val secrets = SecretStore(app)
    private val api = DeviceApi(identity)
    private val sequence = AtomicLong()
    private val random = SecureRandom()
    private var signal: SignalClient? = null
    private var deviceId: String? = null
    private var generation = 0L
    private var connected = false
    private var enabled = false
    private var localSaving = false
    private var opening: CompletableDeferred<Unit>? = null
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var credentialRefresh: Job? = null
    private var operationTimeout: Job? = null
    private var heartbeat: Job? = null
    private var rateLimitCountdown: Job? = null
    private var pendingRegistrationId: String? = null
    private var resumeAction: (() -> Unit)? = null

    fun loadSavedCredentials() {
        val fixed = secrets.get("fixed_mode") == "1"
        val id = secrets.get("fixed_id").orEmpty()
        val code = secrets.get("fixed_code").orEmpty()
        AppStateStore.update {
            it.copy(fixedCredentialMode=fixed,savedFixedId=id,savedFixedCode=code,
                localId=if (it.credentialsRegistered) it.localId else if(fixed) id else "",
                localCode=if (it.credentialsRegistered) it.localCode else if(fixed) code else "")
        }
    }

    fun startControlled() {
        if (localSaving || connectionJob?.isActive == true || rateLimited()) return
        enabled = true
        loadSavedCredentials()
        AppStateStore.update { it.copy(sessionState=SessionState.REGISTERING,statusDetail="正在连接信令服务",errorMessage=null) }
        resumeAction = ::publishCurrentCredentials
        connectSignal(::publishCurrentCredentials)
    }

    fun registerFixed(rawId: String, code: String) {
        if (localSaving || AppStateStore.state.value.credentialPublishing || sessionBusy()) return
        val id = CredentialRules.normalizeId(rawId)
        if (!CredentialRules.validId(id) || !CredentialRules.validCustomCode(code)) {
            fail("INVALID_REQUEST", CredentialRules.idValidationText(rawId) ?: CredentialRules.codeValidationText(code) ?: "自定义凭据格式错误")
            return
        }
        localSaving = true
        credentialRefresh?.cancel()
        scope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) {
                secrets.putAll(mapOf("fixed_id" to id,"fixed_code" to code,"fixed_mode" to "1"))
            } }
            localSaving = false
            if (saved.isFailure) { fail("LOCAL_SAVE_FAILED","本机凭据保存失败"); return@launch }
            // Desired mode survives a network failure. Local save does not claim an online registration.
            AppStateStore.update { it.copy(fixedCredentialMode=true,savedFixedId=id,savedFixedCode=code,
                localId=id,localCode=code,credentialExpiresAt=null,credentialsRegistered=false,
                sessionState=if(enabled) SessionState.REGISTERING else SessionState.IDLE,
                statusDetail=if(enabled) "本机已保存，正在注册自定义凭据" else "本机已保存自定义凭据；启用被控后检查 ID 占用并注册",errorMessage=null) }
            if (enabled) { resumeAction=::publishCurrentCredentials; connectSignal(::publishCurrentCredentials) }
        }
    }

    fun useRandomCredentials() {
        if (localSaving || AppStateStore.state.value.credentialPublishing || sessionBusy()) return
        localSaving = true
        credentialRefresh?.cancel()
        scope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { secrets.putAll(mapOf("fixed_mode" to "0")) } }
            localSaving = false
            if (saved.isFailure) { fail("LOCAL_SAVE_FAILED","模式保存失败"); return@launch }
            AppStateStore.update { it.copy(fixedCredentialMode=false,localId="",localCode="",credentialExpiresAt=null,
                credentialsRegistered=false,statusDetail=if(enabled) "正在切换随机凭据" else "已选择随机模式；启用被控后生成凭据") }
            if (enabled) { resumeAction=::publishCurrentCredentials; connectSignal(::publishCurrentCredentials) }
        }
    }

    fun connectTo(targetId: String, code: String) {
        if (rateLimited() || connectionJob?.isActive == true) return
        val id = CredentialRules.normalizeId(targetId)
        if (!CredentialRules.validId(id) || code.length !in 8..AppConfig.CUSTOM_CODE_MAX_LENGTH || code.any(Char::isWhitespace)) {
            fail("INVALID_REQUEST","ID 或验证码格式不正确"); return
        }
        AppStateStore.update { it.copy(sessionState=SessionState.CONNECTING,statusDetail="正在连接被控设备",errorMessage=null) }
        val action = {
            if (send("CONNECT",null,JSONObject().put("targetId",id).put("code",code))) {
                armTimeout(AppConfig.CONFIRM_TIMEOUT_SECONDS + 5,"等待被控端确认超时（CONFIRMATION_TIMEOUT）")
            } else fail("SIGNAL_SEND_FAILED","信令发送失败")
        }
        resumeAction = action
        connectSignal(action)
    }

    private fun publishCurrentCredentials() {
        if (!enabled || AppStateStore.state.value.role != DeviceRole.CONTROLLED) return
        credentialRefresh?.cancel()
        operationTimeout?.cancel()
        val fixed = secrets.get("fixed_mode") == "1"
        val id: String
        val code: String
        val payload: JSONObject
        if (fixed) {
            id = secrets.get("fixed_id").orEmpty(); code = secrets.get("fixed_code").orEmpty()
            if (!CredentialRules.validId(id) || !CredentialRules.validCustomCode(code)) {
                fail("INVALID_SAVED_CREDENTIALS","已保存的自定义凭据不符合规则，请修改并保存"); return
            }
            payload=JSONObject().put("id",id).put("code",code)
        } else {
            val bytes=ByteArray(5).also(random::nextBytes)
            id="r"+bytes.joinToString("") { "%02x".format(it) }
            code=CredentialRules.randomCode()
            payload=JSONObject().put("id",id).put("code",code).put("expiresAtEpochSeconds",System.currentTimeMillis()/1000+AppConfig.RANDOM_CREDENTIAL_REFRESH_SECONDS)
        }
        pendingRegistrationId=id
        AppStateStore.update { it.copy(fixedCredentialMode=fixed,localId=id,localCode=code,credentialsRegistered=false,
            credentialPublishing=true,credentialExpiresAt=if(fixed) null else payload.getLong("expiresAtEpochSeconds"),
            sessionState=SessionState.REGISTERING,statusDetail=if(fixed) "正在注册自定义凭据" else "正在注册随机凭据") }
        if (!send(if(fixed) "REGISTER_FIXED" else "REGISTER_RANDOM",null,payload)) {
            fail("SIGNAL_SEND_FAILED","凭据信令发送失败"); return
        }
        armTimeout(AppConfig.HANDSHAKE_TIMEOUT_SECONDS,"凭据注册超时（REGISTRATION_TIMEOUT）")
    }

    private fun armTimeout(seconds:Long, message:String) {
        operationTimeout?.cancel()
        operationTimeout=scope.launch {
            delay(seconds*1000)
            if(!enabled) resumeAction=null
            fail("OPERATION_TIMEOUT",message)
        }
    }

    private fun connectSignal(afterOpen:()->Unit) {
        if(connected) { afterOpen(); return }
        connectionJob?.cancel()
        connectionJob=scope.launch {
            try {
                withTimeout(AppConfig.SIGNAL_CONNECT_TIMEOUT_SECONDS*1000) {
                    val id=deviceId ?: secrets.get("device_id") ?: withContext(Dispatchers.IO) {
                        api.register().getOrThrow().also { secrets.put("device_id",it) }
                    }
                    deviceId=id
                    val timestamp=System.currentTimeMillis()
                    val signature=withContext(Dispatchers.IO) { identity.sign("ws\n$id\n$timestamp".toByteArray()) }
                    signal?.close()
                    val token=++generation
                    val ready=CompletableDeferred<Unit>()
                    opening=ready
                    signal=SignalClient(object:SignalClient.Listener {
                        override fun onOpen() { scope.launch { if(token==generation) { connected=true;ready.complete(Unit) } } }
                        override fun onMessage(text:String) { scope.launch { if(token==generation) handleMessage(text) } }
                        override fun onClosed(reason:String) { scope.launch { if(token==generation) transportLost(ConnectionFailure("SERVER_UNREACHABLE")) } }
                        override fun onFailure(failure:ConnectionFailure) { scope.launch { if(token==generation) transportLost(failure) } }
                    }).also { it.connect(AppConfig.SIGNALING_WS_URL,id,timestamp,signature) }
                    ready.await()
                    opening=null
                    afterOpen()
                }
            } catch (e:TimeoutCancellationException) {
                closeTransport()
                fail("SERVER_UNREACHABLE","服务器连接超时（SERVER_UNREACHABLE）")
                if(reconnectJob?.isActive != true) scheduleReconnect()
            } catch (e:CancellationException) { throw e
            } catch (e:Exception) {
                closeTransport()
                val failure=connectionFailure(e)
                if(failure.code=="RATE_LIMITED") startRateLimitCountdown(failure.retryAfterSeconds.coerceAtLeast(1))
                else { fail(failure.code,errorText(failure.code)); if(failure.code!="TLS_FAILED" && failure.code!="DEVICE_AUTH_FAILED" && reconnectJob?.isActive != true) scheduleReconnect() }
            }
        }
    }

    private fun transportLost(failure:ConnectionFailure) {
        connected=false
        opening?.completeExceptionally(failure)
        if(opening!=null) return
        operationTimeout?.cancel()
        credentialRefresh?.cancel()
        AppStateStore.update { it.copy(credentialsRegistered=false,credentialPublishing=false) }
        if(failure.code=="RATE_LIMITED") startRateLimitCountdown(failure.retryAfterSeconds.coerceAtLeast(1))
        else if(failure.code=="TLS_FAILED" || failure.code=="DEVICE_AUTH_FAILED") fail(failure.code,errorText(failure.code))
        else scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (resumeAction==null || reconnectJob?.isActive==true || rateLimited()) return
        reconnectJob=scope.launch {
            val deadline=SystemClock.elapsedRealtime()+AppConfig.RECONNECT_GRACE_SECONDS*1000
            var pause=1000L
            while(SystemClock.elapsedRealtime()<deadline) {
                val left=((deadline-SystemClock.elapsedRealtime())/1000).coerceAtLeast(0)
                AppStateStore.update { it.copy(sessionState=SessionState.RECONNECTING,controlEnabled=false,
                    statusDetail=if(networkAvailable()) "服务器不可达，正在重连（剩余 ${left} 秒）" else "网络不可用，等待恢复（剩余 ${left} 秒）") }
                delay(pause+random.nextInt(AppConfig.RECONNECT_JITTER_MILLIS))
                if(!networkAvailable()) { pause=(pause*2).coerceAtMost(AppConfig.RECONNECT_MAX_DELAY_SECONDS*1000);continue }
                connectSignal {
                    val active=AppStateStore.state.value.activeSessionId
                    if(active!=null) { send("LEASE_RENEW",active,JSONObject());startHeartbeat(active) }
                    else resumeAction?.invoke()
                }
                val remaining=deadline-SystemClock.elapsedRealtime()
                if(remaining<=0 || withTimeoutOrNull(remaining) { connectionJob?.join();true }==null) break
                if(connected || rateLimited() || AppStateStore.state.value.errorMessage in listOf("TLS_FAILED","DEVICE_AUTH_FAILED")) return@launch
                pause=(pause*2).coerceAtMost(AppConfig.RECONNECT_MAX_DELAY_SECONDS*1000)
            }
            closeTransport()
            connectionJob?.cancel()
            fail("RECONNECT_TIMEOUT","重连超时，请重试或停止")
        }
    }

    private fun handleMessage(text:String) {
        runCatching {
            val envelope=JSONObject(text)
            val type=envelope.getString("type")
            val id=envelope.optString("sessionId").takeIf(String::isNotBlank)
            val payload=envelope.optJSONObject("payload") ?: JSONObject()
            when(type) {
                "REGISTERED" -> {
                    if(!enabled || payload.optString("id")!=pendingRegistrationId) return
                    operationTimeout?.cancel()
                    pendingRegistrationId=null
                    AppStateStore.update { it.copy(sessionState=SessionState.STANDBY,statusDetail="被控待机中",
                        credentialsRegistered=true,credentialPublishing=false,errorMessage=null,retryAfterSeconds=null) }
                    if(secrets.get("fixed_mode")!="1") credentialRefresh=scope.launch {
                        delay(AppConfig.RANDOM_CREDENTIAL_REFRESH_SECONDS*1000)
                        if(enabled && secrets.get("fixed_mode")!="1" && AppStateStore.state.value.sessionState==SessionState.STANDBY) publishCurrentCredentials()
                    }
                }
                "INCOMING" -> {
                    if (!enabled || !AppStateStore.state.value.credentialsRegistered) {
                        if (id != null) send("CONFIRM",id,JSONObject().put("accepted",false))
                        return
                    }
                    credentialRefresh?.cancel()
                    AppStateStore.update { it.copy(sessionState=SessionState.WAITING_CONFIRMATION,statusDetail="收到控制请求，请在本机确认",incomingSessionId=id) }
                    armTimeout(AppConfig.CONFIRM_TIMEOUT_SECONDS,"本机确认超时")
                }
                "CONFIRMED" -> {
                    operationTimeout?.cancel()
                    AppStateStore.update { it.copy(sessionState=SessionState.CONNECTING,statusDetail="正在建立媒体连接",activeSessionId=id) }
                    startHeartbeat(id ?: return)
                    SessionMedia.instance(app).onConfirmed(id,payload,::sendRaw)
                    operationTimeout=scope.launch {
                        delay(AppConfig.HANDSHAKE_TIMEOUT_SECONDS*1000)
                        if(AppStateStore.state.value.sessionState!=SessionState.CONNECTED) {
                            send("REVOKE",id,JSONObject())
                            SessionMedia.instance(app).close()
                            heartbeat?.cancel()
                            resumeAction=if(enabled) ::publishCurrentCredentials else null
                            AppStateStore.update { it.copy(activeSessionId=null) }
                            fail("MEDIA_TIMEOUT","媒体连接超时（MEDIA_TIMEOUT）")
                        }
                    }
                }
                "OFFER","ANSWER","ICE","GEOMETRY","AUDIO_STATE" -> if (id != null && id == AppStateStore.state.value.activeSessionId)
                    SessionMedia.instance(app).onSignal(type,id,payload,::sendRaw)
                "REJECTED" -> { operationTimeout?.cancel();resumeAction=null;fail("REJECTED","被控端拒绝连接") }
                "REVOKED" -> {
                    disconnect()
                    AppStateStore.update { it.copy(statusDetail=if(payload.optString("reason")=="CONFIRMATION_TIMEOUT") "被控端确认超时" else "会话已结束") }
                }
                "ERROR" -> {
                    val code=payload.optString("code")
                    operationTimeout?.cancel()
                    if(!enabled) resumeAction=null
                    if(code=="RATE_LIMITED") startRateLimitCountdown(payload.optInt("retryAfterSeconds",60).coerceAtLeast(1))
                    else fail(code,errorText(code))
                }
            }
        }.onFailure { fail("SIGNAL_PROCESSING_FAILED","信令处理失败（SIGNAL_PROCESSING_FAILED）") }
    }

    private fun send(type:String,id:String?,payload:JSONObject):Boolean {
        if(!connected) return false
        val envelope=JSONObject().put("version",1).put("type",type).put("sequence",sequence.incrementAndGet())
            .put("sentAtEpochMs",System.currentTimeMillis()).put("payload",payload)
        if(id!=null) envelope.put("sessionId",id)
        return signal?.send(envelope.toString())==true
    }
    private fun sendRaw(type:String,id:String,payload:JSONObject)=send(type,id,payload)
    private fun startHeartbeat(id:String) { heartbeat?.cancel();heartbeat=scope.launch { while(isActive) { delay(AppConfig.LEASE_RENEW_SECONDS*1000);send("LEASE_RENEW",id,JSONObject()) } } }

    fun confirmIncoming(accepted:Boolean) {
        val id=AppStateStore.state.value.incomingSessionId ?: return
        if(!send("CONFIRM",id,JSONObject().put("accepted",accepted))) { fail("SIGNAL_SEND_FAILED","确认发送失败");return }
        operationTimeout?.cancel()
        AppStateStore.update { it.copy(sessionState=if(accepted) SessionState.CONNECTING else SessionState.STANDBY,
            statusDetail=if(accepted) "正在等待共享服务启动" else "已拒绝连接",incomingSessionId=null,activeSessionId=if(accepted) id else null) }
    }
    fun disconnect() {
        AppStateStore.state.value.activeSessionId?.let { send("REVOKE",it,JSONObject()) }
        operationTimeout?.cancel();heartbeat?.cancel();credentialRefresh?.cancel();reconnectJob?.cancel();connectionJob?.cancel()
        SessionMedia.closeIfCreated()
        app.stopService(android.content.Intent(app,com.simplerc.app.capture.ScreenShareService::class.java))
        if(!enabled) { resumeAction=null;closeTransport() }
        AppStateStore.update { it.copy(sessionState=if(enabled) SessionState.STANDBY else SessionState.IDLE,
            statusDetail=if(enabled) "被控待机中" else "未连接",activeSessionId=null,incomingSessionId=null,controlEnabled=false,remoteVideoAvailable=false,credentialPublishing=false,remoteRotation=null,remoteCaptureWidth=0,remoteCaptureHeight=0) }
        if(enabled && connected && secrets.get("fixed_mode")!="1") publishCurrentCredentials()
    }
    fun stop() {
        enabled=false;resumeAction=null
        disconnect()
        rateLimitCountdown?.cancel();pendingRegistrationId=null;closeTransport()
        AppStateStore.update { it.copy(credentialsRegistered=false,credentialPublishing=false,retryAfterSeconds=null) }
    }
    private fun closeTransport() { generation++;connected=false;opening=null;signal?.close();signal=null }
    private fun fail(code:String,message:String) {
        pendingRegistrationId=null
        AppStateStore.update { it.copy(sessionState=SessionState.ERROR,statusDetail=message,errorMessage=code,credentialPublishing=false,incomingSessionId=null) }
    }
    private fun startRateLimitCountdown(seconds:Int) {
        reconnectJob?.cancel();rateLimitCountdown?.cancel()
        rateLimitCountdown=scope.launch {
            for(left in seconds downTo 1) {
                AppStateStore.update { it.copy(sessionState=SessionState.ERROR,statusDetail="被限流，${left} 秒后可重试（RATE_LIMITED）",
                    errorMessage="RATE_LIMITED",retryAfterSeconds=left,credentialPublishing=false) }
                delay(1000)
            }
            AppStateStore.update { it.copy(statusDetail="可以重试",retryAfterSeconds=0) }
        }
    }
    private fun rateLimited()=(AppStateStore.state.value.retryAfterSeconds ?: 0)>0
    private fun sessionBusy()=AppStateStore.state.value.sessionState in listOf(SessionState.WAITING_CONFIRMATION,SessionState.CONNECTING,SessionState.CONNECTED,SessionState.RECONNECTING)
    fun retryControlled() { if(enabled && !rateLimited()) startControlled() }
    private fun networkAvailable()=app.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork!=null
    private fun errorText(code:String)=when(code) {
        "AUTH_FAILED" -> "此 ID 或验证码有误（AUTH_FAILED）"
        "ID_UNAVAILABLE" -> "该 ID 已被使用，无法使用（ID_UNAVAILABLE）；本机配置仍保留"
        "INVALID_REQUEST" -> "服务器拒绝凭据格式（INVALID_REQUEST）"
        "DEVICE_AUTH_FAILED" -> "设备认证失败，请核对手机日期和时间（DEVICE_AUTH_FAILED）"
        "STALE_OR_INVALID" -> "信令已过期或顺序错误，请核对手机日期和时间（STALE_OR_INVALID）"
        "TLS_FAILED" -> "服务器安全连接失败（TLS_FAILED）"
        "LEASE_LOST" -> "会话租约已失效（LEASE_LOST）"
        "SERVER_UNREACHABLE" -> if(networkAvailable()) "服务器不可达（SERVER_UNREACHABLE）" else "网络不可用（NETWORK_UNAVAILABLE）"
        else -> "连接失败（$code）"
    }
}
