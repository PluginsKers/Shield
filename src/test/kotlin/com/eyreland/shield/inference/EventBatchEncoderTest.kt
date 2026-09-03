package com.eyreland.shield.inference

import com.eyreland.shield.capture.CaptureCategory
import com.eyreland.shield.capture.PacketSample
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EventBatchEncoderTest {
    @Test
    fun `encodes strict event contract and session closure`() {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000020")
        val batchId = UUID.fromString("00000000-0000-0000-0000-000000000030")
        val encoded = EventBatchEncoder.encode(
            records = listOf(
                OutboundRecord.Event(sample(sessionId)),
                OutboundRecord.SessionClosed(sessionId),
            ),
            serverId = "survival-test",
            proxyMode = "velocity",
            serverVersion = "test",
            nowMs = 1_785_300_000_000,
            batchId = batchId,
        )
        val json = GZIPInputStream(
            ByteArrayInputStream(encoded.transmittedBody),
        ).use { it.readAllBytes().decodeToString() }

        assertEquals(1, encoded.eventCount)
        assertEquals(1, encoded.closedSessionCount)
        assertTrue(json.contains("\"schema_version\":\"shield.events.v2\""))
        assertTrue(json.contains("\"batch_id\":\"$batchId\""))
        assertTrue(json.contains("\"packet_type\":\"POSITION_LOOK\""))
        assertTrue(json.contains("\"label\":\"unlabeled\""))
        assertTrue(json.contains("\"closed_sessions\":[\"$sessionId\"]"))
        assertTrue(json.contains("\"session_monotonic_us\":0"))
    }

    @Test
    fun `encodes semantics freshness collection proof and intervention receipts`() {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val encoded = EventBatchEncoder.encode(
            records = listOf(
                OutboundRecord.Event(sample()),
                OutboundRecord.Telemetry(
                    CollectionTelemetry(
                        capturedAtMs = 1_785_300_000_000,
                        eligiblePlayerCount = 1,
                        missingPlayerUuids = emptyList(),
                        deliveryLossObserved = false,
                    ),
                ),
                OutboundRecord.InterventionHandled(
                    InterventionReceipt(
                        directiveId = "intervention-test",
                        playerUuid = playerId,
                        kind = InterventionKind.WARN,
                        outcome = InterventionOutcome.APPLIED,
                        handledAtMs = 1_785_300_000_500,
                    ),
                ),
            ),
            serverId = "survival-test",
            proxyMode = "velocity",
            serverVersion = "test",
            nowMs = 1_785_300_001_000,
        )
        val json = GZIPInputStream(
            ByteArrayInputStream(encoded.transmittedBody),
        ).use { it.readAllBytes().decodeToString() }

        assertEquals(1, encoded.telemetryCount)
        assertEquals(1, encoded.interventionReceiptCount)
        assertEquals(listOf("intervention-test"), encoded.interventionReceiptIds)
        assertTrue(json.contains("\"schema_version\":\"shield.events.v2\""))
        assertTrue(json.contains("\"session_monotonic_us\":0"))
        assertTrue(json.contains("\"event_type\":\"movement\""))
        assertTrue(json.contains("\"position_updated\":true"))
        assertTrue(json.contains("\"eligible_player_count\":1"))
        assertTrue(json.contains("\"delivery_loss_observed\":false"))
        assertTrue(json.contains("\"intervention_receipts\":[{"))
        assertTrue(json.contains("\"outcome\":\"applied\""))
        assertFalse(json.contains("raw_fields"))
    }
}

internal fun sample(
    sessionId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000020"),
): PacketSample = PacketSample(
    capturedAtMs = 1_785_300_000_000,
    sessionId = sessionId,
    sessionSequence = 1,
    sessionAgeMs = 50,
    sincePreviousUs = null,
    sessionMonotonicUs = 0,
    serverId = "survival-test",
    proxyMode = "velocity",
    playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000010"),
    playerName = "ReplayPlayer",
    label = PacketSample.UNLABELED,
    world = "world",
    gameMode = "SURVIVAL",
    observedProtocol = 774,
    serverVersion = "test",
    category = CaptureCategory.MOVEMENT,
    packetType = "POSITION_LOOK",
    source = "client_packet",
    eventType = "movement",
    action = null,
    hand = null,
    targetEntityId = null,
    targetEntityUuid = null,
    targetEntityType = null,
    x = 1.0,
    y = 64.0,
    z = 2.0,
    yaw = 3.0f,
    pitch = 4.0f,
    onGround = true,
    horizontalCollision = false,
    deltaX = null,
    deltaY = null,
    deltaZ = null,
    deltaYaw = null,
    deltaPitch = null,
    distance = null,
    horizontalDistance = null,
    speedBps = null,
    horizontalSpeedBps = null,
    movementChanged = true,
    positionUpdated = true,
    rotationUpdated = true,
    groundUpdated = true,
    payload = mapOf("protocol_event_async" to true),
)
