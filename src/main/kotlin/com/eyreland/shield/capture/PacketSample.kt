package com.eyreland.shield.capture

import java.util.UUID

/**
 * 一条捕获到的数据包样本。字段按 [category] 取用，无关字段为 null。
 * 不可变，可安全跨线程从捕获线程交给 HTTP sink。
 */
data class PacketSample(
    val capturedAtMs: Long,
    val sessionId: UUID,
    val sessionSequence: Long,
    val sessionAgeMs: Long,
    val sincePreviousUs: Long?,
    val serverId: String,
    val proxyMode: String,
    val playerUuid: UUID,
    val playerName: String,
    val label: String,
    val world: String?,
    val gameMode: String?,
    val observedProtocol: Int?,
    val serverVersion: String,
    val category: CaptureCategory,
    val packetType: String,
    val action: String?,
    val hand: String?,
    val targetEntityId: Int?,
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
    val payload: Map<String, Any?>,
) {
    companion object {
        const val UNLABELED = "unlabeled"
    }
}
