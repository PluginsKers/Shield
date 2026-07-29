package com.eyreland.shield.inference

import com.eyreland.shield.capture.CaptureCategory
import com.eyreland.shield.capture.PacketSample
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            serverVersion = "1.21.11",
            nowMs = 1_785_300_000_000,
            batchId = batchId,
        )
        val json = GZIPInputStream(
            ByteArrayInputStream(encoded.transmittedBody),
        ).use { it.readAllBytes().decodeToString() }

        assertEquals(1, encoded.eventCount)
        assertEquals(1, encoded.closedSessionCount)
        assertTrue(json.contains("\"schema_version\":\"shield.events.v1\""))
        assertTrue(json.contains("\"batch_id\":\"$batchId\""))
        assertTrue(json.contains("\"packet_type\":\"POSITION_LOOK\""))
        assertTrue(json.contains("\"label\":\"unlabeled\""))
        assertTrue(json.contains("\"closed_sessions\":[\"$sessionId\"]"))
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
    serverId = "survival-test",
    proxyMode = "velocity",
    playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000010"),
    playerName = "ReplayPlayer",
    label = PacketSample.UNLABELED,
    world = "world",
    gameMode = "SURVIVAL",
    observedProtocol = 774,
    serverVersion = "1.21.11",
    category = CaptureCategory.MOVEMENT,
    packetType = "POSITION_LOOK",
    action = null,
    hand = null,
    targetEntityId = null,
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
    payload = mapOf("protocol_event_async" to true),
)
