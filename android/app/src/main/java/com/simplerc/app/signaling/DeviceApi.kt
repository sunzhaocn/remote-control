package com.simplerc.app.signaling

import com.simplerc.app.config.AppConfig
import com.simplerc.app.security.DeviceIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeviceApi(private val identity: DeviceIdentity) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun register(): Result<String> = runCatching {
        val timestamp = System.currentTimeMillis()
        val publicKey = identity.publicKeyBase64()
        val signature = identity.sign("register\n$timestamp\n$publicKey".toByteArray())
        val body = JSONObject().put("publicKey", publicKey).put("timestamp", timestamp).put("signature", signature).toString()
        val request = Request.Builder().url("${AppConfig.SIGNALING_BASE_URL}/v1/devices/register")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("设备注册失败（${response.code}）")
            JSONObject(response.body?.string().orEmpty()).getString("deviceId")
        }
    }
}
