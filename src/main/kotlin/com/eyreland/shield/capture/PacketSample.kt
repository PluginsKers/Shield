package com.eyreland.shield.capture

import java.util.UUID

/**
 * 一条捕获到的数据包样本。字段按 [category] 取用，无关字段为 null。
 * 不可变，可安全跨线程从捕获线程交给落库线程。
 */
data class PacketSample(
    val recvAtMs: Long,
    val playerUuid: UUID,
    val playerName: String,
    val label: String,
    val category: CaptureCategory,
    val packetType: String,
    val world: String?,
    val x: Double?,
    val y: Double?,
    val z: Double?,
    val yaw: Float?,
    val pitch: Float?,
    val onGround: Boolean?,
    val targetId: Int?,
    val action: String?,
    val hand: String?,
)
