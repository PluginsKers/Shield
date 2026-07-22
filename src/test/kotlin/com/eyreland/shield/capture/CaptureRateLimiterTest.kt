package com.eyreland.shield.capture

import com.eyreland.shield.config.PacketRule
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureRateLimiterTest {
    @Test
    fun `different packet names can share one rate limit group`() {
        val limiter = CaptureRateLimiter()
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val position = PacketRule(minIntervalMs = 50, rateLimitGroup = "movement")
        val look = PacketRule(minIntervalMs = 50, rateLimitGroup = "movement")

        assertTrue(limiter.allow(uuid, position, 1_000_000_000))
        assertFalse(limiter.allow(uuid, look, 1_049_999_999))
        assertTrue(limiter.allow(uuid, look, 1_050_000_000))
    }

    @Test
    fun `extreme configured interval is saturated instead of overflowing`() {
        val limiter = CaptureRateLimiter()
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val rule = PacketRule(minIntervalMs = Long.MAX_VALUE, rateLimitGroup = "movement")

        assertTrue(limiter.allow(uuid, rule, 1_000))
        assertFalse(limiter.allow(uuid, rule, 2_000))
    }
}
