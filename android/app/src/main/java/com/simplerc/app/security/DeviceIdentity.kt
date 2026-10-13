package com.simplerc.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.Base64

class DeviceIdentity {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensureCreated() {
        if (keyStore.containsAlias(ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKeyPair()
    }

    fun publicKeyBase64(): String {
        ensureCreated()
        return Base64.getEncoder().encodeToString(keyStore.getCertificate(ALIAS).publicKey.encoded)
    }

    fun sign(payload: ByteArray): String {
        ensureCreated()
        val key = keyStore.getKey(ALIAS, null) as java.security.PrivateKey
        val bytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(payload)
            sign()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    companion object { private const val ALIAS = "simplerc_device_identity_v1" }
}
