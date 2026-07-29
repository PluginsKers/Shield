package com.eyreland.shield.inference

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class HmacHeaders(
    val keyId: String,
    val timestamp: Long,
    val nonce: String,
    val signature: String,
)

/** 与 Shield-Inference 完全一致的传输体 HMAC-SHA256。 */
object HmacSigner {
    fun sign(
        keyId: String,
        secret: String,
        timestamp: Long,
        nonce: String,
        transmittedBody: ByteArray,
    ): HmacHeaders {
        val bodyHash = MessageDigest.getInstance("SHA-256")
            .digest(transmittedBody)
            .toHex()
        val canonical = "$keyId\n$timestamp\n$nonce\n$bodyHash"
            .toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return HmacHeaders(keyId, timestamp, nonce, mac.doFinal(canonical).toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
