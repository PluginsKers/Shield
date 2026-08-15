package com.eyreland.shield.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class PacketCaptureTelemetryTest {
    @Test
    fun `logical tick follows monotonic process time`() {
        assertEquals(0, logicalTicksSinceStart(49_999_999L))
        assertEquals(1, logicalTicksSinceStart(50_000_000L))
        assertEquals(100, logicalTicksSinceStart(5_000_000_000L))
    }
}
