package com.eyreland.shield.config

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CaptureConfigTest {
    @Test
    fun `heartbeat intervals are bounded and legacy raw fields are ignored`() {
        val section = YamlConfiguration().apply {
            set("enabled", true)
            set("heartbeat-interval-ticks", 1)
            set("telemetry-interval-ticks", 5_000)
            set("packets.USE_ENTITY.enabled", true)
            set("packets.USE_ENTITY.raw-fields", true)
        }

        val config = CaptureConfig.from(section)

        assertEquals(20, config.heartbeatIntervalTicks)
        assertEquals(1_200, config.telemetryIntervalTicks)
        assertFalse(config.usedLegacyPacketDefaults)
        assertEquals(setOf("USE_ENTITY"), config.packets.keys)
    }
}
