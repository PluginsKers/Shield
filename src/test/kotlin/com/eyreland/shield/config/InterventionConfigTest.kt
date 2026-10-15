package com.eyreland.shield.config

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterventionConfigTest {
    @Test
    fun `collection defaults do not authorize kicking`() {
        val config = InterventionConfig.defaults()
        assertTrue(config.enabled && config.warning.enabled)
        assertFalse(config.kick.enabled)
        assertEquals(config, InterventionConfig.from(YamlConfiguration()))
        val bundled = YamlConfiguration().apply {
            loadFromString(javaClass.getResource("/config.yml")!!.readText())
        }
        assertEquals(config, InterventionConfig.from(bundled))
    }

    @Test
    fun `explicit local policy loads without changing internal defaults`() {
        val root = YamlConfiguration().apply {
            loadFromString("""
                interventions:
                  enabled: false
                  warning:
                    enabled: false
                    cooldown-seconds: 300
                    message: "请停止异常行为"
                  kick:
                    enabled: true
                    minimum-prior-warnings: 2
                    cooldown-seconds: 60
                    message: "异常行为持续发生，请联系管理员"
            """.trimIndent())
        }
        val config = InterventionConfig.from(root)
        assertFalse(config.enabled)
        assertFalse(config.warning.enabled)
        assertEquals(300_000, config.warning.cooldownMs)
        assertEquals("请停止异常行为", config.warning.message)
        assertTrue(config.kick.enabled)
        assertEquals(2, config.kick.minimumPriorWarnings)
        assertEquals(60_000, config.kick.cooldownMs)
        assertEquals("异常行为持续发生，请联系管理员", config.kick.message)
        assertEquals(InterventionConfig.defaults().ledgerRetentionMs, config.ledgerRetentionMs)
    }

    @Test
    fun `malformed policy cannot silently fall back to enabled defaults`() {
        for ((path, value) in listOf(
            "interventions" to false,
            "interventions.enabled" to "false",
            "interventions.warning" to listOf("enabled"),
            "interventions.warning.enabled" to 1,
            "interventions.warning.cooldown-seconds" to 299,
            "interventions.warning.cooldown-seconds" to 86_401,
            "interventions.kick.enabled" to "true",
            "interventions.kick.minimum-prior-warnings" to 0,
            "interventions.kick.minimum-prior-warnings" to 101,
            "interventions.kick.cooldown-seconds" to 59,
            "interventions.kick.cooldown-seconds" to 604_801,
            "interventions.kick.cooldown-seconds" to 60.5,
            "interventions.kick.cooldown-seconds" to "60",
            "interventions.kick.message" to 123,
            "interventions.kick.message" to " ",
            "interventions.kick.message" to "line\nline",
            "interventions.kick.message" to "a".repeat(257),
            "interventions.kick.enable" to true,
            "interventions.ban.enabled" to true,
        )) {
            assertFailsWith<IllegalArgumentException>("$path=$value") {
                InterventionConfig.from(YamlConfiguration().apply { set(path, value) })
            }
        }
    }
}
