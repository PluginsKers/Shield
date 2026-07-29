package com.eyreland.shield.inference

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** 只依赖 UUID 和固定盐的稳定灰度；重启和多后端不会随机跳组。 */
class StableRollout(
    private val percent: Double,
    private val salt: String,
) {
    init {
        require(percent in 0.0..100.0)
        require(salt.isNotEmpty())
    }

    fun includes(uuid: UUID): Boolean {
        if (percent <= 0.0) return false
        if (percent >= 100.0) return true
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$salt:$uuid".toByteArray(StandardCharsets.UTF_8))
        val value = (
            ((digest[0].toLong() and 0xff) shl 24) or
                ((digest[1].toLong() and 0xff) shl 16) or
                ((digest[2].toLong() and 0xff) shl 8) or
                (digest[3].toLong() and 0xff)
            )
        val bucket = value % 1_000_000L
        return bucket < (percent * 10_000.0).toLong()
    }
}
