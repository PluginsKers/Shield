package com.eyreland.shield.capture

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AfkTrackerTest {

    private val uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val timeout = 60_000L

    @Test
    fun `disabled tracker never reports afk`() {
        val afk = AfkTracker(timeoutMs = 0)
        assertFalse(afk.enabled)
        afk.onMovement(uuid, 0.0, 64.0, 0.0, 0f, 0f, 0)
        assertFalse(afk.isAfk(uuid, 10_000_000))
    }

    @Test
    fun `unknown player is not afk`() {
        val afk = AfkTracker(timeout)
        assertFalse(afk.isAfk(uuid, 0))
    }

    @Test
    fun `standing still becomes afk after timeout`() {
        val afk = AfkTracker(timeout)
        // 首个包记为活动起点，此后坐标不变的站桩包不刷新活动
        afk.onMovement(uuid, 0.0, 64.0, 0.0, 0f, 0f, 0)
        afk.onMovement(uuid, 0.0, 64.0, 0.0, 0f, 0f, 30_000)
        assertFalse(afk.isAfk(uuid, 30_000))
        assertTrue(afk.isAfk(uuid, 60_000))
    }

    @Test
    fun `real movement keeps player active`() {
        val afk = AfkTracker(timeout)
        afk.onMovement(uuid, 0.0, 64.0, 0.0, 0f, 0f, 0)
        afk.onMovement(uuid, 5.0, 64.0, 0.0, 0f, 0f, 59_000)
        assertFalse(afk.isAfk(uuid, 59_000))
        // 从最后一次实质移动重新计时
        assertFalse(afk.isAfk(uuid, 118_000))
        assertTrue(afk.isAfk(uuid, 119_001))
    }

    @Test
    fun `sub epsilon drift does not count as activity`() {
        val afk = AfkTracker(timeout)
        afk.onMovement(uuid, 0.0, 64.0, 0.0, 0f, 0f, 0)
        // 小于位置阈值(0.03)的漂移不算活动
        afk.onMovement(uuid, 0.01, 64.0, 0.005, 0f, 0f, 30_000)
        assertTrue(afk.isAfk(uuid, 60_000))
    }

    @Test
    fun `look rotation counts as activity`() {
        val afk = AfkTracker(timeout)
        afk.onMovement(uuid, 0.0, 64.0, 0.0, 0f, 0f, 0)
        // 只转视角(LOOK 包无坐标)也算活动
        afk.onMovement(uuid, null, null, null, 90f, 20f, 40_000)
        assertFalse(afk.isAfk(uuid, 90_000))
        assertTrue(afk.isAfk(uuid, 100_001))
    }

    @Test
    fun `action resumes collection after afk`() {
        val afk = AfkTracker(timeout)
        afk.onMovement(uuid, 0.0, 64.0, 0.0, 0f, 0f, 0)
        assertTrue(afk.isAfk(uuid, 60_000))
        // 一次事件动作即刻刷新活动，恢复采集
        afk.onAction(uuid, 60_000)
        assertFalse(afk.isAfk(uuid, 60_000))
        assertTrue(afk.isAfk(uuid, 120_000))
    }

    @Test
    fun `clear resets tracking`() {
        val afk = AfkTracker(timeout)
        afk.onMovement(uuid, 0.0, 64.0, 0.0, 0f, 0f, 0)
        afk.clear()
        assertFalse(afk.isAfk(uuid, 120_000))
    }
}
