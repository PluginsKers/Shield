package com.eyreland.shield.config

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InferenceConfigTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val secret = "a".repeat(64)

    @Test
    fun `one issued key binds identity and signing material to fixed transport defaults`() {
        val root = YamlConfiguration().apply { set("server-key", "shd_$id.$secret") }
        val config = InferenceConfig.from(root)
        assertEquals(id, config.keyId)
        assertEquals(secret, config.hmacSecret)
        assertEquals("https://api.i5res.com/shield/v1/events", config.endpoint.toString())
        assertEquals(500, config.batchSize)
        assertEquals(60_000, config.requestTimeoutMs)
    }

    @Test
    fun `missing malformed and legacy configuration cannot silently select another identity or policy`() {
        for (key in listOf("", id, "shd_1-1-1-1-1.$secret", "shd_$id.short", "shd_$id.${"z".repeat(64)}")) {
            assertFailsWith<IllegalArgumentException> {
                InferenceConfig.from(YamlConfiguration().apply { set("server-key", key) })
            }
        }
        for (path in listOf("capture.server-id", "capture.enabled", "inference.batch-size", "actions.kick.enabled")) {
            assertFailsWith<IllegalArgumentException> {
                InferenceConfig.from(YamlConfiguration().apply {
                    set("server-key", "shd_$id.$secret")
                    set(path, "override")
                })
            }
        }
    }

    @Test
    fun `local actions do not alter server identity or transport`() {
        val root = YamlConfiguration().apply { set("server-key", "shd_$id.$secret") }
        val before = InferenceConfig.from(root)
        root.set("interventions.kick.enabled", true)
        assertEquals(before, InferenceConfig.from(root))
    }
}
