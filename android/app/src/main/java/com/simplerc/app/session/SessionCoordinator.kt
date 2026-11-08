package com.simplerc.app.session

import android.app.Application
import android.os.SystemClock
import com.simplerc.app.config.AppConfig
import com.simplerc.app.credentials.CredentialRules
import com.simplerc.app.credentials.CredentialRefreshSchedule
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
    private var recoveryDeadlineJob: Job? = null
    private var recoveryDeadlineMs: Long? = null
    private var mediaRecoveryJob: Job? = null
    private var leaseAuthorized = false
    private var pendingConnect = false
    private var ending = false
    val sessionAuthorized: Boolean get() = leaseAuthorized && !ending && AppStateStore.state.value.activeSessionId != null

    init {
        app.getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object:android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network:android.net.Network) { scope.launch {
                    if(canWaitInStandby() && resumeAction!=null && !ending && !connected &&
                        connectionJob?.isActive!=true && rateLimitCountdown?.isActive!=true) {
                        reconnectJob?.cancel();reconnectJob=null
                        scheduleReconnect(immediate=true)
                    }
                } }
                override fun onLost(network:android.net.Network) { scope.launch {
                    if(!networkAvailable() && resumeAction!=null && !ending) {
                        beginRecovery()
                        transportLost(ConnectionFailure("NETWORK_UNAVAILABLE"))
                    }
                } }
            })
    }

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
        if (localSaving || connectionJob?.isActive == true) return
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
        if (AppStateStore.state.value.role != DeviceRole.CONTROLLER || sessionBusy() || rateLimited() || connectionJob?.isActive == true) return
        val id = CredentialRules.normalizeId(targetId)
        if (!CredentialRules.validId(id) || code.length !in 8..AppConfig.CUSTOM_CODE_MAX_LENGTH || code.any(Char::isWhitespace)) {
            fail("INVALID_REQUEST","ID 或验证码格式不正确"); return
        }
        AppStateStore.update { it.copy(sessionState=SessionState.CONNECTING,statusDetail="正在连接被控设备",errorMessage=null) }
        pendingConnect = true
        val selectedPolicy=AppStateStore.state.value.selectedVideoPolicy
        val action = {
            pendingConnect = true
            if (send("CONNECT",null,JSONObject().put("targetId",id).put("code",code).put("videoPolicy",selectedPolicy))) {
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

    private fun scheduleCredentialRefresh() {
        credentialRefresh?.cancel()
        val state = AppStateStore.state.value
        if (!enabled || !connected || state.fixedCredentialMode || !state.credentialsRegistered || state.sessionState != SessionState.STANDBY) return
        val pause = CredentialRefreshSchedule.delayMillis(state.credentialExpiresAt, System.currentTimeMillis()) ?: return
        credentialRefresh = scope.launch {
            delay(pause)
            if (enabled && connected && !AppStateStore.state.value.fixedCredentialMode && AppStateStore.state.value.sessionState == SessionState.STANDBY) publishCurrentCredentials()
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
                if(recoveryDeadlineMs==null)fail("SERVER_UNREACHABLE","服务器连接超时（SERVER_UNREACHABLE）")
                if(reconnectJob?.isActive != true) scheduleReconnect()
            } catch (e:CancellationException) { throw e
            } catch (e:Exception) {
                closeTransport()
                val failure=connectionFailure(e)
                if(failure.code=="RATE_LIMITED") startRateLimitCountdown(failure.retryAfterSeconds.coerceAtLeast(1))
                else { if(recoveryDeadlineMs==null || failure.code in listOf("TLS_FAILED","DEVICE_AUTH_FAILED"))fail(failure.code,errorText(failure.code)); if(failure.code!="TLS_FAILED" && failure.code!="DEVICE_AUTH_FAILED" && reconnectJob?.isActive != true) scheduleReconnect() }
            }
        }
    }

    private fun transportLost(failure:ConnectionFailure) {
        connected=false
        opening?.completeExceptionally(failure)
        if(opening!=null) return
        closeTransport()
        operationTimeout?.cancel()
        credentialRefresh?.cancel()
        AppStateStore.update { it.copy(credentialsRegistered=false,credentialPublishing=false) }
        if(failure.code=="RATE_LIMITED") startRateLimitCountdown(failure.retryAfterSeconds.coerceAtLeast(1))
        else if(failure.code=="TLS_FAILED" || failure.code=="DEVICE_AUTH_FAILED") fail(failure.code,errorText(failure.code))
        else if(AppStateStore.state.value.incomingSessionId!=null) {
            // An unconfirmed request has no renewable lease; a new connection
            // must ask again rather than accept a stale consent dialog.
            endFailedSession("NETWORK_UNAVAILABLE","连接请求因网络中断已取消")
        }
        else scheduleReconnect()
    }

    private fun canWaitInStandby() = SessionRecoveryPolicy.mayWaitInStandby(enabled, AppStateStore.state.value)

    private fun scheduleReconnect(immediate:Boolean=false) {
        if (resumeAction==null || reconnectJob?.isActive==true) return
        beginRecovery()
        reconnectJob=scope.launch {
            var attempt=0
            while(isActive && resumeAction!=null && !ending) {
                val standby=canWaitInStandby()
                val deadline=recoveryDeadlineMs
                if(!standby && (deadline==null || SystemClock.elapsedRealtime()>=deadline)) break
                if(standby) AppStateStore.update { it.copy(sessionState=SessionState.RECONNECTING,
                    statusDetail=if(networkAvailable()) "正在恢复被控待机" else "网络不可用，保持待机并等待恢复",
                    credentialsRegistered=false,credentialPublishing=false,errorMessage=null,controlEnabled=false) }
                val pause=if(immediate && attempt==0)0L else SessionRecoveryPolicy.retryDelayMillis(attempt,standby)+random.nextInt(AppConfig.RECONNECT_JITTER_MILLIS)
                delay(if(standby)pause else pause.coerceAtMost((deadline!!-SystemClock.elapsedRealtime()).coerceAtLeast(0)))
                if(!networkAvailable()) { attempt=(attempt+1).coerceAtMost(5);continue }
                connectSignal {
                    val active=AppStateStore.state.value.activeSessionId
                    if(active!=null) { send("LEASE_RENEW",active,JSONObject());startHeartbeat(active) }
                    else resumeAction?.invoke()
                }
                if(standby) connectionJob?.join()
                else {
                    val remaining=deadline!!-SystemClock.elapsedRealtime()
                    if(remaining<=0 || withTimeoutOrNull(remaining) { connectionJob?.join();true }==null) break
                }
                if(connected || resumeAction==null || rateLimitCountdown?.isActive==true) return@launch
                attempt=(attempt+1).coerceAtMost(5)
            }
            if(recoveryDeadlineMs!=null)endFailedSession("RECONNECT_TIMEOUT","重连超过 15 秒，请重试或停止")
        }
    }

    private fun beginRecovery() {
        if(recoveryDeadlineMs!=null || ending || canWaitInStandby())return
        val deadline=SystemClock.elapsedRealtime()+AppConfig.RECONNECT_GRACE_SECONDS*1000
        recoveryDeadlineMs=deadline
        recoveryDeadlineJob=scope.launch {
            while(isActive && SystemClock.elapsedRealtime()<deadline) {
                val left=((deadline-SystemClock.elapsedRealtime()+999)/1000).coerceAtLeast(0)
                val media=if(AppStateStore.state.value.activeSessionId!=null)SessionMedia.instance(app) else null
                AppStateStore.update { it.copy(sessionState=SessionState.RECONNECTING,
                    statusDetail=if(networkAvailable()) "正在恢复连接 · ${left}s" else "网络不可用 · ${left}s 后断开",
                    controlEnabled=media?.transportReady==true && sessionAuthorized) }
                media?.refreshControlAvailability()
                delay(250)
            }
            if(recoveryDeadlineMs==deadline)endFailedSession("RECONNECT_TIMEOUT","重连超过 15 秒，请重试或停止")
        }
    }
    private fun recoveryComplete() {
        recoveryDeadlineMs=null;recoveryDeadlineJob?.cancel();recoveryDeadlineJob=null
        mediaRecoveryJob?.cancel();reconnectJob?.cancel();reconnectJob=null
    }
    fun mediaTransportLost() {
        if(AppStateStore.state.value.activeSessionId==null || ending)return
        beginRecovery()
        mediaRecoveryJob?.cancel()
        mediaRecoveryJob=scope.launch {
            delay(1200)
            val id=AppStateStore.state.value.activeSessionId ?: return@launch
            if(connected && !SessionMedia.instance(app).transportReady) {
                if(AppStateStore.state.value.role==DeviceRole.CONTROLLED)SessionMedia.instance(app).restartIce()
                else {send("ICE_RESTART",id,JSONObject());send("CONTROL_RESTART",id,JSONObject())}
            }
        }
    }
    fun mediaTransportRestored() {
        val media=SessionMedia.instance(app)
        if(connected && sessionAuthorized && media.transportReady) {
            val recovered=recoveryDeadlineMs!=null
            operationTimeout?.cancel();operationTimeout=null
            recoveryComplete()
            AppStateStore.update { it.copy(sessionState=SessionState.CONNECTED,statusDetail="已连接",errorMessage=null) }
            media.refreshControlAvailability()
            if(recovered)media.synchronizeAfterRecovery()
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
                    recoveryComplete()
                    AppStateStore.update { it.copy(sessionState=SessionState.STANDBY,statusDetail="被控待机中",
                        credentialsRegistered=true,credentialPublishing=false,errorMessage=null,retryAfterSeconds=null) }
                    scheduleCredentialRefresh()
                }
                "INCOMING" -> {
                    val state = AppStateStore.state.value
                    if (id != null && id == state.incomingSessionId) return
                    if (id == null || !enabled || state.role != DeviceRole.CONTROLLED || !state.credentialsRegistered || state.sessionState != SessionState.STANDBY) {
                        if (id != null) send("CONFIRM",id,JSONObject().put("accepted",false))
                        return
                    }
                    credentialRefresh?.cancel()
                    AppStateStore.update { it.copy(sessionState=SessionState.WAITING_CONFIRMATION,statusDetail="收到控制请求，请在本机确认",incomingSessionId=id) }
                    operationTimeout?.cancel()
                    operationTimeout=scope.launch {
                        delay(AppConfig.CONFIRM_TIMEOUT_SECONDS*1000)
                        if (confirmIncoming(false, id)) AppStateStore.update { it.copy(statusDetail="本机确认超时，已拒绝请求") }
                    }
                }
                "CONFIRMED" -> {
                    val state = AppStateStore.state.value
                    if (id == null || (state.activeSessionId == id && leaseAuthorized)) return
                    val expected = if (state.role == DeviceRole.CONTROLLED) enabled && state.activeSessionId == id
                        else pendingConnect && state.sessionState in listOf(SessionState.CONNECTING, SessionState.RECONNECTING) && state.activeSessionId == null
                    if (!expected) { send("REVOKE",id,JSONObject()); return }
                    pendingConnect = false
                    operationTimeout?.cancel()
                    leaseAuthorized=true
                    AppStateStore.update { it.copy(sessionState=SessionState.CONNECTING,statusDetail="正在建立媒体连接",activeSessionId=id) }
                    startHeartbeat(id ?: return)
                    SessionMedia.instance(app).onConfirmed(id,payload,::sendRaw)
                    operationTimeout=scope.launch {
                        delay(AppConfig.HANDSHAKE_TIMEOUT_SECONDS*1000)
                        if(AppStateStore.state.value.activeSessionId==id && AppStateStore.state.value.sessionState!=SessionState.CONNECTED) {
                            endFailedSession("MEDIA_TIMEOUT","视频或控制连接超时：${SessionMedia.instance(app).readinessDescription()}（MEDIA_TIMEOUT）")
                        }
                    }
                }
                "OFFER","ANSWER","ICE","GEOMETRY","AUDIO_STATE","ICE_RESTART","CONTROL_OFFER","CONTROL_ANSWER","CONTROL_ICE","CONTROL_RESTART" -> if (id != null && id == AppStateStore.state.value.activeSessionId)
                    SessionMedia.instance(app).onSignal(type,id,payload,::sendRaw)
                "HEARTBEAT_ACK" -> if(id!=null && id==AppStateStore.state.value.activeSessionId) { leaseAuthorized=true;mediaTransportRestored() }
                "REJECTED" -> if (pendingConnect && AppStateStore.state.value.role == DeviceRole.CONTROLLER) {
                    operationTimeout?.cancel();resumeAction=null;fail("REJECTED","被控端拒绝连接")
                }
                "REVOKED" -> {
                    if(id==null || id!=AppStateStore.state.value.activeSessionId && id!=AppStateStore.state.value.incomingSessionId)return
                    val reason=payload.optString("reason")
                    if(reason in listOf("LEASE_LOST","RECONNECT_TIMEOUT")) {
                        endFailedSession(reason,if(reason=="LEASE_LOST")"会话租约已失效（LEASE_LOST）" else "连接已超过 15 秒未恢复，请重试或停止")
                        return
                    }
                    disconnect(false)
                    AppStateStore.update { it.copy(statusDetail=if(payload.optString("reason")=="CONFIRMATION_TIMEOUT") "被控端确认超时" else "会话已结束") }
                }
                "ERROR" -> {
                    // Responses already queued for a completed session cannot end a newer one.
                    if (id != null && id != AppStateStore.state.value.activeSessionId && id != AppStateStore.state.value.incomingSessionId) return
                    val code=payload.optString("code")
                    operationTimeout?.cancel()
                    if(!enabled) resumeAction=null
                    if(code=="RATE_LIMITED") startRateLimitCountdown(payload.optInt("retryAfterSeconds",60).coerceAtLeast(1))
                    else if (code=="STALE_OR_INVALID") {
                        val detail=when(payload.optString("reason")) {
                            "SEQUENCE" -> "信令发送顺序错误"
                            "TIME" -> "信令时间已过期，请核对手机时间"
                            "VERSION" -> "信令版本不匹配"
                            else -> "信令无效或已过期"
                        }
                        fail(code,"$detail（STALE_OR_INVALID，${payload.optString("requestType","UNKNOWN")}）")
                    } else fail(code,errorText(code))
                }
            }
        }.onFailure { fail("SIGNAL_PROCESSING_FAILED","信令处理失败（SIGNAL_PROCESSING_FAILED）") }
    }

    @Synchronized
    private fun send(type:String,id:String?,payload:JSONObject):Boolean {
        if(!connected) return false
        val envelope=JSONObject().put("version",1).put("type",type).put("sequence",sequence.incrementAndGet())
            .put("sentAtEpochMs",System.currentTimeMillis()).put("payload",payload)
        if(id!=null) envelope.put("sessionId",id)
        return signal?.send(envelope.toString())==true
    }
    private fun sendRaw(type:String,id:String,payload:JSONObject)=send(type,id,payload)
    private fun startHeartbeat(id:String) { heartbeat?.cancel();heartbeat=scope.launch { while(isActive) { delay(AppConfig.LEASE_RENEW_SECONDS*1000);send("LEASE_RENEW",id,JSONObject()) } } }

    fun confirmIncoming(accepted:Boolean, expectedSessionId:String? = AppStateStore.state.value.incomingSessionId):Boolean {
        val state = AppStateStore.state.value
        val id=state.incomingSessionId ?: return false
        if (!enabled || state.role != DeviceRole.CONTROLLED || state.sessionState != SessionState.WAITING_CONFIRMATION || id != expectedSessionId) return false
        if(!send("CONFIRM",id,JSONObject().put("accepted",accepted))) { fail("SIGNAL_SEND_FAILED","确认发送失败");return false }
        operationTimeout?.cancel()
        AppStateStore.update { it.copy(sessionState=if(accepted) SessionState.CONNECTING else SessionState.STANDBY,
            statusDetail=if(accepted) "正在等待共享服务启动" else "已拒绝连接",incomingSessionId=null,activeSessionId=if(accepted) id else null) }
        if (accepted) armTimeout(AppConfig.HANDSHAKE_TIMEOUT_SECONDS,"等待会话授权超时（CONFIRM_ACK_TIMEOUT）")
        else scheduleCredentialRefresh()
        return true
    }
    fun disconnect() {
        disconnect(true)
    }
    private fun disconnect(notifyPeer:Boolean, resumeStandby:Boolean=true) {
        if(ending)return
        ending=true
        val stateBeforeStop = AppStateStore.state.value
        (stateBeforeStop.activeSessionId ?: stateBeforeStop.incomingSessionId)?.let { if(notifyPeer)send("REVOKE",it,JSONObject()) }
        pendingConnect=false
        leaseAuthorized=false
        recoveryComplete()
        operationTimeout?.cancel();heartbeat?.cancel();credentialRefresh?.cancel();reconnectJob?.cancel();connectionJob?.cancel()
        AppStateStore.update { it.copy(activeSessionId=null,incomingSessionId=null,controlEnabled=false,remoteVideoAvailable=false) }
        SessionMedia.closeIfCreated()
        app.stopService(android.content.Intent(app,com.simplerc.app.capture.ScreenShareService::class.java))
        app.stopService(android.content.Intent(app,com.simplerc.app.service.ControllerService::class.java))
        if(!enabled) { resumeAction=null;closeTransport() }
        AppStateStore.update { it.copy(sessionState=if(enabled) SessionState.STANDBY else SessionState.IDLE,
            statusDetail=if(enabled) "被控待机中" else "未连接",activeSessionId=null,incomingSessionId=null,controlEnabled=false,remoteVideoAvailable=false,credentialPublishing=false,remoteRotation=null,remoteCaptureWidth=0,remoteCaptureHeight=0,remoteFrameWidth=0,remoteFrameHeight=0,rttMs=null,packetLossPercent=null,remoteAudioAllowed=false,mediaDiagnostics=null,videoPolicyStatus="",activeVideoPolicy=null,activeVideoEncoding=null,controlRttMs=null,controlProbeRttMs=null,inputAckMs=null,connectionPath="路径未知",controlConnectionPath="路径未知",
            controlCandidateTypes=null,controlCandidatePairId=null,controlCandidatePairSelection=null) }
        ending=false
        if(resumeStandby) {
            if(enabled && connected && (!AppStateStore.state.value.credentialsRegistered || secrets.get("fixed_mode")!="1")) publishCurrentCredentials()
            else if(enabled && !connected) scheduleReconnect()
        }
    }
    fun stop() {
        enabled=false;resumeAction=null
        disconnect()
        rateLimitCountdown?.cancel();pendingRegistrationId=null;closeTransport()
        AppStateStore.update { it.copy(credentialsRegistered=false,credentialPublishing=false,retryAfterSeconds=null) }
    }
    private fun closeTransport() { generation++;connected=false;opening=null;signal?.close();signal=null }
    private fun fail(code:String,message:String) {
        if((AppStateStore.state.value.activeSessionId!=null || AppStateStore.state.value.incomingSessionId!=null) && !ending) { endFailedSession(code,message);return }
        if(code in listOf("TLS_FAILED","DEVICE_AUTH_FAILED")) { endFailedSession(code,message);return }
        if(SessionRecoveryPolicy.mayRetryStandbyFailure(enabled,AppStateStore.state.value,code)) {
            operationTimeout?.cancel();credentialRefresh?.cancel();connectionJob?.cancel();connectionJob=null
            pendingRegistrationId=null
            closeTransport()
            AppStateStore.update { it.copy(sessionState=SessionState.RECONNECTING,
                statusDetail="$message；被控待机将自动重试",credentialsRegistered=false,credentialPublishing=false,errorMessage=null) }
            scheduleReconnect()
            return
        }
        pendingConnect=false
        pendingRegistrationId=null
        AppStateStore.update { it.copy(sessionState=SessionState.ERROR,statusDetail=message,errorMessage=code,credentialPublishing=false,incomingSessionId=null) }
    }
    fun endFailedSession(code:String,message:String) {
        if(ending)return
        val keepStandby=SessionRecoveryPolicy.mayReturnToStandby(enabled,AppStateStore.state.value,code)
        if(keepStandby) {
            // Invalidate the old lease, media objects and socket before advertising
            // availability again. A new incoming request still needs fresh consent.
            disconnect(code!="LEASE_LOST" && code!="SESSION_INVALID",resumeStandby=false)
            rateLimitCountdown?.cancel();pendingRegistrationId=null;closeTransport()
            resumeAction=::publishCurrentCredentials
            AppStateStore.update { it.copy(sessionState=SessionState.RECONNECTING,
                statusDetail="$message；正在恢复被控待机",errorMessage=null,credentialsRegistered=false,
                credentialPublishing=false,retryAfterSeconds=null,controlEnabled=false,activeSessionId=null,incomingSessionId=null) }
            scheduleReconnect(immediate=true)
            return
        }
        enabled=false;resumeAction=null
        disconnect(code!="LEASE_LOST" && code!="SESSION_INVALID")
        rateLimitCountdown?.cancel();pendingRegistrationId=null;closeTransport()
        app.stopService(android.content.Intent(app,com.simplerc.app.service.StandbyService::class.java))
        app.stopService(android.content.Intent(app,com.simplerc.app.service.ControllerService::class.java))
        AppStateStore.update { it.copy(sessionState=SessionState.ERROR,statusDetail=message,errorMessage=code,
            credentialsRegistered=false,credentialPublishing=false,retryAfterSeconds=null,standbyServiceRunning=false,
            availability=com.simplerc.app.model.AvailabilityState.ERROR,controlEnabled=false,activeSessionId=null,incomingSessionId=null) }
    }
    fun serviceDestroyed(role:DeviceRole) {
        if(AppStateStore.state.value.role!=role || AppStateStore.state.value.sessionState==SessionState.ERROR)return
        stop()
    }
    private fun startRateLimitCountdown(seconds:Int) {
        reconnectJob?.cancel();rateLimitCountdown?.cancel()
        rateLimitCountdown=scope.launch {
            for(left in seconds downTo 1) {
                AppStateStore.update { if(it.role==DeviceRole.CONTROLLER)it.copy(sessionState=SessionState.ERROR,statusDetail="被限流，${left} 秒后可重试（RATE_LIMITED）",
                    errorMessage="RATE_LIMITED",retryAfterSeconds=left,credentialPublishing=false) else it.copy(retryAfterSeconds=left) }
                delay(1000)
            }
            AppStateStore.update { it.copy(statusDetail=if(it.role==DeviceRole.CONTROLLER)"可以重试" else it.statusDetail,retryAfterSeconds=0) }
            if(canWaitInStandby() && resumeAction!=null) {
                rateLimitCountdown=null
                if(connected) publishCurrentCredentials() else scheduleReconnect(immediate=true)
            }
        }
    }
    private fun rateLimited()=AppStateStore.state.value.role==DeviceRole.CONTROLLER && (AppStateStore.state.value.retryAfterSeconds ?: 0)>0
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
