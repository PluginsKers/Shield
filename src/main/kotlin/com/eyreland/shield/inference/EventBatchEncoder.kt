package com.eyreland.shield.inference

import com.eyreland.shield.capture.PacketSample
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPOutputStream

internal sealed interface OutboundRecord {
    data class Event(val sample: PacketSample) : OutboundRecord
    data class SessionClosed(val sessionId: UUID) : OutboundRecord
    data class Telemetry(val value: CollectionTelemetry) : OutboundRecord
    data class InterventionHandled(val receipt: InterventionReceipt) : OutboundRecord
}

internal data class EncodedBatch(
    val batchId: UUID,
    val transmittedBody: ByteArray,
    val uncompressedBytes: Int,
    val eventCount: Int,
    val closedSessionCount: Int,
    val telemetryCount: Int,
    val interventionReceiptCount: Int,
    val interventionReceiptIds: List<String>,
)

internal object EventBatchEncoder {
    fun encode(
        records: List<OutboundRecord>,
        serverId: String,
        proxyMode: String,
        serverVersion: String,
        schemaVersion: String,
        nowMs: Long,
        batchId: UUID = UUID.randomUUID(),
    ): EncodedBatch {
        require(records.isNotEmpty())
        val events = ArrayList<Map<String, Any?>>()
        val closedSessions = linkedSetOf<String>()
        val telemetry = ArrayList<Map<String, Any?>>()
        val interventionReceipts = ArrayList<Map<String, Any?>>()
        for (record in records) {
            when (record) {
                is OutboundRecord.Event -> events += eventMap(record.sample, schemaVersion)
                is OutboundRecord.SessionClosed -> closedSessions += record.sessionId.toString()
                is OutboundRecord.Telemetry -> if (schemaVersion == "shield.events.v2") {
                    telemetry += telemetryMap(record.value)
                }
                is OutboundRecord.InterventionHandled -> if (schemaVersion == "shield.events.v2") {
                    interventionReceipts += receiptMap(record.receipt)
                }
            }
        }
        val body = JsonEncoder.encode(
            linkedMapOf(
                "schema_version" to schemaVersion,
                "batch_id" to batchId.toString(),
                "sent_at_ms" to nowMs,
                "server_id" to serverId,
                "proxy_mode" to proxyMode,
                "server_version" to serverVersion,
                "events" to events,
                "closed_sessions" to closedSessions,
            ).apply {
                if (schemaVersion == "shield.events.v2") {
                    put("collection_telemetry", telemetry)
                    put("intervention_receipts", interventionReceipts)
                }
            },
        ).toByteArray(StandardCharsets.UTF_8)
        return EncodedBatch(
            batchId = batchId,
            transmittedBody = gzip(body),
            uncompressedBytes = body.size,
            eventCount = events.size,
            closedSessionCount = closedSessions.size,
            telemetryCount = telemetry.size,
            interventionReceiptCount = interventionReceipts.size,
            interventionReceiptIds = interventionReceipts.map {
                requireNotNull(it["directive_id"] as? String)
            },
        )
    }

    private fun eventMap(sample: PacketSample, schemaVersion: String): Map<String, Any?> = linkedMapOf(
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
    ).apply {
        if (schemaVersion == "shield.events.v2") {
            put("session_monotonic_us", sample.sessionMonotonicUs)
            put("source", sample.source)
            put("event_type", sample.eventType)
            put("target_entity_uuid", sample.targetEntityUuid?.toString())
            put("target_entity_type", sample.targetEntityType)
            put("position_updated", sample.positionUpdated)
            put("rotation_updated", sample.rotationUpdated)
            put("ground_updated", sample.groundUpdated)
        }
    }

    private fun telemetryMap(value: CollectionTelemetry): Map<String, Any?> = linkedMapOf(
        "captured_at_ms" to value.capturedAtMs,
        "process_uptime_ms" to value.processUptimeMs,
        "server_tick" to value.serverTick,
        "tps_1m" to value.tps1m,
        "mspt" to value.mspt,
        "online_player_uuids" to value.onlinePlayerUuids.map(UUID::toString),
        "eligible_player_uuids" to value.eligiblePlayerUuids.map(UUID::toString),
        "captured_player_uuids" to value.capturedPlayerUuids.map(UUID::toString),
        "player_filtered_events" to value.playerFilteredEvents,
        "action_filtered_events" to value.actionFilteredEvents,
        "afk_filtered_events" to value.afkFilteredEvents,
        "throttled_events" to value.throttledEvents,
        "decode_failed_events" to value.decodeFailedEvents,
        "queue_dropped_records" to value.queueDroppedRecords,
        "spool_dropped_batches" to value.spoolDroppedBatches,
        "spool_dropped_events" to value.spoolDroppedEvents,
        "unsupported_packet_names" to value.unsupportedPacketNames,
    )

    private fun receiptMap(value: InterventionReceipt): Map<String, Any?> = linkedMapOf(
        "directive_id" to value.directiveId,
        "player_uuid" to value.playerUuid.toString(),
        "kind" to value.kind.wireName,
        "outcome" to value.outcome.wireName,
        "handled_at_ms" to value.handledAtMs,
    )

    private fun gzip(body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(body) }
        return output.toByteArray()
    }
}
