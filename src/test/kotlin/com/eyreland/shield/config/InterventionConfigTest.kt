package com.eyreland.shield.config

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterventionConfigTest {
    @Test
    fun `defaults warn slowly and never kick`() {
        val config = InterventionConfig.from(null)

        assertTrue(config.enabled)
        assertTrue(config.warning.enabled)
        assertEquals(30 * 60 * 1_000L, config.warning.cooldownMs)
        assertEquals("[!]", config.warning.prefix)
        assertEquals("你当前的行为存在异常", config.warning.message)
        assertFalse(config.kick.enabled)
        assertEquals(1, config.kick.minimumPriorWarnings)
    }

    @Test
    fun `explicit kick still requires a prior warning`() {
        val section = YamlConfiguration().apply {
            set("kick.enabled", true)
            set("kick.minimum-prior-warnings", 2)
            set("warning.cooldown-seconds", 3600)
        }

        val config = InterventionConfig.from(section)

        assertTrue(config.kick.enabled)
        assertEquals(2, config.kick.minimumPriorWarnings)
        assertEquals(3_600_000L, config.warning.cooldownMs)
    }
}
