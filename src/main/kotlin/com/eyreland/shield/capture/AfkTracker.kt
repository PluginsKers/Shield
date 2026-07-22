package com.eyreland.shield.capture

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 挂机判定:按玩家实质活动时间决定是否停止采集。
 *
 * 活动来自两类信号:移动包里位移或转角超过阈值(滤掉站桩时的漂移/抖动包)，
 * 以及战斗/物品/操控这类必然出于人为的事件包。距上次活动超过 [timeoutMs] 即视为挂机，
 * 期间捕获侧只跳过入队，活动跟踪持续进行，玩家一恢复移动或动作就立刻续采。
 *
 * 同一连接的包在其 Netty 事件循环线程串行到达，故按 UUID 天然无并发；跨玩家的并发由
 * [ConcurrentHashMap] 兜底。[timeoutMs] 为 0 时整体关闭，[isAfk] 恒为 false。
 */
class AfkTracker(
    private val timeoutMs: Long,
    private val positionEpsilon: Double = DEFAULT_POSITION_EPSILON,
    private val angleEpsilon: Float = DEFAULT_ANGLE_EPSILON,
) {
    val enabled: Boolean get() = timeoutMs > 0

    private class Snapshot(var x: Double?, var y: Double?, var z: Double?, var yaw: Float?, var pitch: Float?)

    private val snapshots = ConcurrentHashMap<UUID, Snapshot>()
    private val lastActivityMs = ConcurrentHashMap<UUID, Long>()

    /** 移动/载具移动包:与上次快照比对，位移或转角超阈值才算活动。首次出现记为活动起点。 */
    fun onMovement(uuid: UUID, x: Double?, y: Double?, z: Double?, yaw: Float?, pitch: Float?, now: Long) {
        if (!enabled) return
        val snap = snapshots[uuid]
        if (snap == null) {
            snapshots[uuid] = Snapshot(x, y, z, yaw, pitch)
            lastActivityMs[uuid] = now
            return
        }
        val moved = changed(snap.x, x, positionEpsilon) ||
            changed(snap.y, y, positionEpsilon) ||
            changed(snap.z, z, positionEpsilon) ||
            changed(snap.yaw, yaw, angleEpsilon) ||
            changed(snap.pitch, pitch, angleEpsilon)
        if (x != null) snap.x = x
        if (y != null) snap.y = y
        if (z != null) snap.z = z
        if (yaw != null) snap.yaw = yaw
        if (pitch != null) snap.pitch = pitch
        if (moved) lastActivityMs[uuid] = now
    }

    /** 事件包(战斗/物品/操控输入)出于人为，直接刷新活动时间。 */
    fun onAction(uuid: UUID, now: Long) {
        if (enabled) lastActivityMs[uuid] = now
    }

    fun isAfk(uuid: UUID, now: Long): Boolean {
        if (!enabled) return false
        val last = lastActivityMs[uuid] ?: return false
        return now - last >= timeoutMs
    }

    fun remove(uuid: UUID) {
        snapshots.remove(uuid)
        lastActivityMs.remove(uuid)
    }

    fun clear() {
        snapshots.clear()
        lastActivityMs.clear()
    }

    private fun changed(old: Double?, new: Double?, epsilon: Double): Boolean =
        old != null && new != null && abs(new - old) > epsilon

    private fun changed(old: Float?, new: Float?, epsilon: Float): Boolean =
        old != null && new != null && abs(new - old) > epsilon

    companion object {
        const val DEFAULT_POSITION_EPSILON = 0.03
        const val DEFAULT_ANGLE_EPSILON = 1.0f
    }
}
