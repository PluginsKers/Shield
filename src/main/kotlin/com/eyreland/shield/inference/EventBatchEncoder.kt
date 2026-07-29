package com.eyreland.shield.inference

import com.eyreland.shield.capture.PacketSample
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPOutputStream

internal sealed interface OutboundRecord {
    data class Event(val sample: PacketSample) : OutboundRecord
    data class SessionClosed(val sessionId: UUID) : OutboundRecord
}

internal data class EncodedBatch(
    val batchId: UUID,
    val transmittedBody: ByteArray,
    val uncompressedBytes: Int,
    val eventCount: Int,
    val closedSessionCount: Int,
)

internal object EventBatchEncoder {
    fun encode(
        records: List<OutboundRecord>,
        serverId: String,
        proxyMode: String,
        serverVersion: String,
        nowMs: Long,
        batchId: UUID = UUID.randomUUID(),
    ): EncodedBatch {
        require(records.isNotEmpty())
        val events = ArrayList<Map<String, Any?>>()
        val closedSessions = linkedSetOf<String>()
        for (record in records) {
            when (record) {
                is OutboundRecord.Event -> events += eventMap(record.sample)
                is OutboundRecord.SessionClosed -> closedSessions += record.sessionId.toString()
            }
        }
        val body = JsonEncoder.encode(
            linkedMapOf(
                "schema_version" to "shield.events.v1",
                "batch_id" to batchId.toString(),
                "sent_at_ms" to nowMs,
                "server_id" to serverId,
                "proxy_mode" to proxyMode,
                "server_version" to serverVersion,
                "events" to events,
                "closed_sessions" to closedSessions,
            ),
        ).toByteArray(StandardCharsets.UTF_8)
        return EncodedBatch(
            batchId = batchId,
            transmittedBody = gzip(body),
            uncompressedBytes = body.size,
            eventCount = events.size,
            closedSessionCount = closedSessions.size,
        )
    }

    private fun eventMap(sample: PacketSample): Map<String, Any?> = linkedMapOf(
        "captured_at_ms" to sample.capturedAtMs,
        "session_id" to sample.sessionId.toString(),
        "session_sequence" to sample.sessionSequence,
        "session_age_ms" to sample.sessionAgeMs,
        "since_previous_us" to sample.sincePreviousUs,
        "player_uuid" to sample.playerUuid.toString(),
        "player_name" to sample.playerName,
        "label" to sample.label,
        "world" to sample.world,
        "game_mode" to sample.gameMode,
        "observed_protocol" to sample.observedProtocol,
        "category" to sample.category.key,
        "packet_type" to sample.packetType,
        "action" to sample.action,
        "hand" to sample.hand,
        "target_entity_id" to sample.targetEntityId,
        "x" to sample.x,
        "y" to sample.y,
        "z" to sample.z,
        "yaw" to sample.yaw,
        "pitch" to sample.pitch,
        "on_ground" to sample.onGround,
        "horizontal_collision" to sample.horizontalCollision,
        "delta_x" to sample.deltaX,
        "delta_y" to sample.deltaY,
        "delta_z" to sample.deltaZ,
        "delta_yaw" to sample.deltaYaw,
        "delta_pitch" to sample.deltaPitch,
        "distance" to sample.distance,
        "horizontal_distance" to sample.horizontalDistance,
        "speed_bps" to sample.speedBps,
        "horizontal_speed_bps" to sample.horizontalSpeedBps,
        "movement_changed" to sample.movementChanged,
        "payload" to sample.payload,
    )

    private fun gzip(body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(body) }
        return output.toByteArray()
    }
}
