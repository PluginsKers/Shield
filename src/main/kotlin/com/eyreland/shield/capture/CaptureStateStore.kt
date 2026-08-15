package com.eyreland.shield.capture

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** 解码器给出的增量移动字段；null 表示该数据包没有携带该字段。 */
data class PartialMovement(
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val yaw: Float? = null,
    val pitch: Float? = null,
    val onGround: Boolean? = null,
    val horizontalCollision: Boolean? = null,
)

/** 写样本前生成的会话序列、完整姿态与稳定派生特征。 */
data class CaptureEnrichment(
    val sessionSequence: Long,
    val sessionAgeMs: Long,
    val sincePreviousUs: Long?,
    val sessionMonotonicUs: Long,
    val x: Double?,
    val y: Double?,
    val z: Double?,
    val yaw: Float?,
    val pitch: Float?,
    val onGround: Boolean?,
    val horizontalCollision: Boolean?,
    val deltaX: Double?,
    val deltaY: Double?,
    val deltaZ: Double?,
    val deltaYaw: Float?,
    val deltaPitch: Float?,
    val distance: Double?,
    val horizontalDistance: Double?,
    val speedBps: Double?,
    val horizontalSpeedBps: Double?,
    val movementChanged: Boolean,
)

/**
 * 按后端连接会话维护完整姿态，并在真正发送时计算相邻样本特征。
 * 观察态与已选中态分离：节流掉的移动仍会更新当前姿态，但 delta 始终相对上一条已选样本。
 */
class CaptureStateStore {
    private class State(context: CaptureContext) {
        val sessionId: UUID = context.sessionId
        var world: String? = context.world
        var sequence: Long = 0
        var x: Double? = context.initialX
        var y: Double? = context.initialY
        var z: Double? = context.initialZ
        var yaw: Float? = context.initialYaw
        var pitch: Float? = context.initialPitch
        var onGround: Boolean? = null
        var horizontalCollision: Boolean? = null
        var emittedX: Double? = null
        var emittedY: Double? = null
        var emittedZ: Double? = null
        var emittedYaw: Float? = null
        var emittedPitch: Float? = null
        var emittedOnGround: Boolean? = null
        var emittedHorizontalCollision: Boolean? = null
        var lastSampleNanos: Long? = null
        var firstSampleNanos: Long? = null
        var lastMonotonicUs: Long = -1
    }

    private val states = ConcurrentHashMap<UUID, State>()

    fun observe(context: CaptureContext, movement: PartialMovement?) {
        if (movement == null) return
        val state = stateFor(context)
        synchronized(state) {
            resetForWorldChange(state, context)
            movement.x?.let { state.x = it }
            movement.y?.let { state.y = it }
            movement.z?.let { state.z = it }
            movement.yaw?.let { state.yaw = it }
            movement.pitch?.let { state.pitch = it }
            movement.onGround?.let { state.onGround = it }
            movement.horizontalCollision?.let { state.horizontalCollision = it }
        }
    }

    fun sample(context: CaptureContext, nowMs: Long, nowNanos: Long): CaptureEnrichment {
        val state = stateFor(context)
        synchronized(state) {
            resetForWorldChange(state, context)
            state.sequence += 1

            val sincePreviousUs = state.lastSampleNanos?.let { ((nowNanos - it).coerceAtLeast(0L)) / 1_000L }
            val firstNanos = state.firstSampleNanos ?: nowNanos.also { state.firstSampleNanos = it }
            val elapsedUs = ((nowNanos - firstNanos).coerceAtLeast(0L)) / 1_000L
            val sessionMonotonicUs = maxOf(elapsedUs, state.lastMonotonicUs + 1L)
            val dx = delta(state.x, state.emittedX)
            val dy = delta(state.y, state.emittedY)
            val dz = delta(state.z, state.emittedZ)
            val deltaYaw = angleDelta(state.yaw, state.emittedYaw)
            val deltaPitch = angleDelta(state.pitch, state.emittedPitch)
            val horizontalDistance = if (dx != null && dz != null) sqrt(dx * dx + dz * dz) else null
            val distance = if (dx != null && dy != null && dz != null) sqrt(dx * dx + dy * dy + dz * dz) else null
            val seconds = sincePreviousUs?.takeIf { it > 0 }?.div(1_000_000.0)
            val speed = if (distance != null && seconds != null) distance / seconds else null
            val horizontalSpeed = if (horizontalDistance != null && seconds != null) horizontalDistance / seconds else null
            val movementChanged = listOfNotNull(dx, dy, dz).any { it != 0.0 } ||
                listOfNotNull(deltaYaw, deltaPitch).any { it != 0f } ||
                (state.emittedOnGround != null && state.onGround != state.emittedOnGround) ||
                (state.emittedHorizontalCollision != null && state.horizontalCollision != state.emittedHorizontalCollision)

            val result = CaptureEnrichment(
                sessionSequence = state.sequence,
                sessionAgeMs = (nowMs - context.joinedAtMs).coerceAtLeast(0L),
                sincePreviousUs = sincePreviousUs,
                sessionMonotonicUs = sessionMonotonicUs,
                x = state.x,
                y = state.y,
                z = state.z,
                yaw = state.yaw,
                pitch = state.pitch,
                onGround = state.onGround,
                horizontalCollision = state.horizontalCollision,
                deltaX = dx,
                deltaY = dy,
                deltaZ = dz,
                deltaYaw = deltaYaw,
                deltaPitch = deltaPitch,
                distance = distance,
                horizontalDistance = horizontalDistance,
                speedBps = speed,
                horizontalSpeedBps = horizontalSpeed,
                movementChanged = movementChanged,
            )

            state.emittedX = state.x
            state.emittedY = state.y
            state.emittedZ = state.z
            state.emittedYaw = state.yaw
            state.emittedPitch = state.pitch
            state.emittedOnGround = state.onGround
            state.emittedHorizontalCollision = state.horizontalCollision
            state.lastSampleNanos = nowNanos
            state.lastMonotonicUs = sessionMonotonicUs
            return result
        }
    }

    fun remove(uuid: UUID) {
        states.remove(uuid)
    }

    fun clear() = states.clear()

    private fun stateFor(context: CaptureContext): State =
        states.compute(context.playerUuid) { _, current ->
            if (current == null || current.sessionId != context.sessionId) State(context) else current
        }!!

    private fun resetForWorldChange(state: State, context: CaptureContext) {
        if (state.world == context.world) return
        state.world = context.world
        state.x = context.initialX
        state.y = context.initialY
        state.z = context.initialZ
        state.yaw = context.initialYaw
        state.pitch = context.initialPitch
        state.onGround = null
        state.horizontalCollision = null
        state.emittedX = null
        state.emittedY = null
        state.emittedZ = null
        state.emittedYaw = null
        state.emittedPitch = null
        state.emittedOnGround = null
        state.emittedHorizontalCollision = null
    }

    private fun delta(current: Double?, previous: Double?): Double? =
        if (current == null || previous == null) null else current - previous

    private fun angleDelta(current: Float?, previous: Float?): Float? {
        if (current == null || previous == null) return null
        var value = (current - previous) % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }
}
