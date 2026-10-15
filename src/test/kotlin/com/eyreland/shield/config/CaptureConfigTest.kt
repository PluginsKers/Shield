package com.eyreland.shield.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureConfigTest {
    @Test
    fun `fixed collection excludes spectators and shares the existing movement sampling budget`() {
        val config = CaptureConfig("server", "standalone")
        assertFalse(config.allowsGameMode("spectator"))
        assertTrue(config.allowsGameMode("SURVIVAL"))
        assertFalse("CHAT" in config.packets)
        assertFalse("CUSTOM_PAYLOAD" in config.packets)
        assertEquals(17, config.packets.size)
        for (packet in listOf("POSITION", "POSITION_LOOK", "LOOK", "GROUND")) {
            assertEquals(PacketRule(50, "movement"), config.packets[packet])
        }
        assertEquals(180_000, config.afkTimeoutMs)
    }
}
