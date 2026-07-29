package com.eyreland.shield.config

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InferenceConfigTest {
    @Test
    fun `legacy config uses HTTPS defaults when secret environment exists`() {
        val config = InferenceConfig.from(null) { "x".repeat(32) }

        assertTrue(config.enabled)
        assertEquals(
            "https://api.i5res.com/shield/v1/events",
            config.endpoint.toString(),
        )
        assertEquals(10.0, config.rolloutPercent)
        assertEquals(500, config.batchSize)
    }

    @Test
    fun `enabled transport requires configured secret`() {
        assertFailsWith<IllegalArgumentException> {
            InferenceConfig.from(null) { null }
        }
    }

    @Test
    fun `inline secret makes config self contained and takes precedence`() {
        val section = YamlConfiguration().apply {
            set("hmac-secret", "inline-" + "x".repeat(32))
            set("hmac-secret-env", "SHIELD_TEST_SECRET")
        }

        val config = InferenceConfig.from(section) { "environment-" + "y".repeat(32) }

        assertEquals("inline-" + "x".repeat(32), config.hmacSecret)
    }
}
