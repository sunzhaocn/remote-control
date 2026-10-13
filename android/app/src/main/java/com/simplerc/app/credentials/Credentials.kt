package com.simplerc.app.credentials

import com.simplerc.app.config.AppConfig
import java.security.SecureRandom

data class PairingCredentials(val id: String, val code: String, val expiresAtEpochSeconds: Long?)

object CredentialRules {
    private val random = SecureRandom()
    private val allowedId = Regex("^[a-z0-9_-]{${AppConfig.PAIRING_ID_MIN_LENGTH},${AppConfig.PAIRING_ID_MAX_LENGTH}}$")
    private val reserved = setOf("admin", "root", "support", "system", "simplerc")

    fun normalizeId(raw: String): String = raw.lowercase()
    fun validId(raw: String): Boolean = normalizeId(raw).let { allowedId.matches(it) && it !in reserved }
    fun validCustomCode(value: String): Boolean = value.length in AppConfig.CUSTOM_CODE_MIN_LENGTH..AppConfig.CUSTOM_CODE_MAX_LENGTH && value.none(Char::isWhitespace)
    fun idValidationText(value: String): String? = when {
        value.length < AppConfig.PAIRING_ID_MIN_LENGTH -> "还需 ${AppConfig.PAIRING_ID_MIN_LENGTH - value.length} 个字符"
        value.length > AppConfig.PAIRING_ID_MAX_LENGTH -> "最多 ${AppConfig.PAIRING_ID_MAX_LENGTH} 个字符，当前多 ${value.length - AppConfig.PAIRING_ID_MAX_LENGTH} 个"
        !value.all { it.isLetterOrDigit() || it == '-' || it == '_' } -> "只能使用字母、数字、-、_"
        normalizeId(value) in reserved -> "该 ID 不可使用"
        else -> null
    }
    fun codeValidationText(value: String): String? = when {
        value.any(Char::isWhitespace) -> "验证码不能包含空白"
        value.length < AppConfig.CUSTOM_CODE_MIN_LENGTH -> "还需 ${AppConfig.CUSTOM_CODE_MIN_LENGTH - value.length} 个字符"
        value.length > AppConfig.CUSTOM_CODE_MAX_LENGTH -> "最多 ${AppConfig.CUSTOM_CODE_MAX_LENGTH} 个字符，当前多 ${value.length - AppConfig.CUSTOM_CODE_MAX_LENGTH} 个"
        else -> null
    }
    fun randomCode(): String = buildString(AppConfig.RANDOM_CODE_DIGITS) {
        repeat(AppConfig.RANDOM_CODE_DIGITS) { append(random.nextInt(10)) }
    }
}
