package com.eyreland.shield.capture

import com.eyreland.shield.config.PacketRule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 按玩家 + 规则组共享节流，POSITION/LOOK 等可合并到同一个 movement 时间窗。 */
class CaptureRateLimiter {
    private data class Key(val uuid: UUID, val group: String)

    private val lastAcceptedNanos = ConcurrentHashMap<Key, Long>()

    fun allow(uuid: UUID, rule: PacketRule, nowNanos: Long): Boolean {
        if (rule.minIntervalMs <= 0) return true
        val intervalNanos = rule.minIntervalMs
            .coerceAtMost(Long.MAX_VALUE / NANOS_PER_MILLISECOND) * NANOS_PER_MILLISECOND
        val key = Key(uuid, rule.rateLimitGroup)
        var accepted = false
        lastAcceptedNanos.compute(key) { _, previous ->
            if (previous == null || nowNanos - previous >= intervalNanos) {
                accepted = true
                nowNanos
            } else {
                previous
            }
        }
        return accepted
    }

    fun remove(uuid: UUID) {
        lastAcceptedNanos.keys.removeIf { it.uuid == uuid }
    }

    fun clear() = lastAcceptedNanos.clear()

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
