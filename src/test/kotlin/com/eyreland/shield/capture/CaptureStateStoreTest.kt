package com.eyreland.shield.capture

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureStateStoreTest {
    private val playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val sessionId = UUID.fromString("10000000-0000-0000-0000-000000000001")

    @Test
    fun `sample derives distance speed timing and wrapped angles from last emitted sample`() {
        val store = CaptureStateStore()
        val context = context()

        store.observe(context, PartialMovement(onGround = true))
        val first = store.sample(context, nowMs = 1_000, nowNanos = 1_000_000_000)
        assertEquals(1, first.sessionSequence)
        assertNull(first.distance)
        assertFalse(first.movementChanged)

        store.observe(
            context,
            PartialMovement(x = 3.0, z = 4.0, yaw = -179f, onGround = false),
        )
        val second = store.sample(context, nowMs = 2_000, nowNanos = 2_000_000_000)

        assertEquals(2, second.sessionSequence)
        assertEquals(1_000_000, second.sincePreviousUs)
        assertEquals(5.0, second.horizontalDistance)
        assertEquals(5.0, second.distance)
        assertEquals(5.0, second.speedBps)
        assertEquals(2f, second.deltaYaw)
        assertTrue(second.movementChanged)
    }

    @Test
    fun `world change resets movement baseline without resetting session sequence`() {
        val store = CaptureStateStore()
        val firstContext = context(world = "world", x = 0.0)
        store.sample(firstContext, nowMs = 1_000, nowNanos = 1_000_000)

        val netherContext = context(world = "world_nether", x = 100.0)
        val afterChange = store.sample(netherContext, nowMs = 2_000, nowNanos = 2_000_000)

        assertEquals(2, afterChange.sessionSequence)
        assertEquals(100.0, afterChange.x)
        assertNull(afterChange.deltaX)
        assertFalse(afterChange.movementChanged)
    }

    @Test
    fun `new backend connection session starts a fresh sequence`() {
        val store = CaptureStateStore()
        val first = context()
        assertEquals(1, store.sample(first, 1_000, 1_000_000).sessionSequence)

        val next = first.copy(sessionId = UUID.fromString("20000000-0000-0000-0000-000000000001"))
        assertEquals(1, store.sample(next, 2_000, 2_000_000).sessionSequence)
    }

    @Test
    fun `session clock stays strictly monotonic when nano clock repeats or moves back`() {
        val store = CaptureStateStore()
        val context = context()

        val first = store.sample(context, 1_000, 2_000_000)
        val repeated = store.sample(context, 1_000, 2_000_000)
        val backwards = store.sample(context, 1_000, 1_000_000)

        assertEquals(0, first.sessionMonotonicUs)
        assertEquals(1, repeated.sessionMonotonicUs)
        assertEquals(2, backwards.sessionMonotonicUs)
    }

    private fun context(
        world: String = "world",
        x: Double = 0.0,
    ) = CaptureContext(
        sessionId = sessionId,
        playerUuid = playerUuid,
        playerName = "Alice",
        world = world,
        gameMode = "SURVIVAL",
        joinedAtMs = 0,
        initialX = x,
        initialY = 64.0,
        initialZ = 0.0,
        initialYaw = 179f,
        initialPitch = 0f,
    )
}
