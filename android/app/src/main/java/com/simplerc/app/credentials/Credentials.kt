package com.simplerc.app.credentials

import com.simplerc.app.config.AppConfig
import java.security.SecureRandom

data class PairingCredentials(val id: String, val code: String, val expiresAtEpochSeconds: Long?)

object CredentialRules {
    private val random = SecureRandom()
    private val allowedId = Regex("^[a-z0-9][a-z0-9_-]{4,31}$")
    private val reserved = setOf("admin", "root", "support", "system", "simplerc")

    fun normalizeId(raw: String): String = raw.trim().lowercase()
    fun validId(raw: String): Boolean = normalizeId(raw).let { allowedId.matches(it) && it !in reserved }
    fun validCustomCode(value: String): Boolean = value.length >= AppConfig.CUSTOM_CODE_MIN_LENGTH
    fun randomCode(): String = buildString(AppConfig.RANDOM_CODE_DIGITS) {
        repeat(AppConfig.RANDOM_CODE_DIGITS) { append(random.nextInt(10)) }
    }
}
