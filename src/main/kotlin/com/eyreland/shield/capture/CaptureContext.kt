package com.eyreland.shield.capture

import java.util.UUID

/**
 * 在 Bukkit/Folia 允许访问 Player 的事件线程上生成的不可变快照。
 * ProtocolLib 的网络线程只读这份快照，避免跨线程读取 world、gameMode 或 location。
 */
data class CaptureContext(
    val sessionId: UUID,
    val playerUuid: UUID,
    val playerName: String,
    val world: String?,
    val gameMode: String?,
    val joinedAtMs: Long,
    val initialX: Double?,
    val initialY: Double?,
    val initialZ: Double?,
    val initialYaw: Float?,
    val initialPitch: Float?,
)
