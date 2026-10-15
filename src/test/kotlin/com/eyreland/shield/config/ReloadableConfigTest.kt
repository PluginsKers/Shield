package com.eyreland.shield.config

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReloadableConfigTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val key = "shd_00000000-0000-0000-0000-000000000001.${"a".repeat(64)}"

    @Test
    fun `reload publishes all action settings together and supports removing overrides`() {
        val file = temporaryDirectory.resolve("config.yml").toFile()
        file.writeText("server-key: $key\n")
        val config = ReloadableConfig(file)
        val original = config.current
        file.writeText("""
            server-key: $key
            interventions:
              warning:
                enabled: false
                cooldown-seconds: 600
                message: "请检查行为"
              kick:
                enabled: true
                minimum-prior-warnings: 2
                cooldown-seconds: 120
                message: "请联系管理员"
        """.trimIndent())
        config.reload()
        val updated = config.current
        assertEquals(original.inference, updated.inference)
        assertEquals(WarningConfig(false, 600_000, "请检查行为"), updated.interventions.warning)
        assertEquals(KickConfig(true, 2, 120_000, "请联系管理员"), updated.interventions.kick)
        file.writeText("server-key: $key\n")
        config.reload()
        assertEquals(original, config.current)
    }

    @Test
    fun `unreadable malformed invalid or changed identity configuration preserves the active snapshot`() {
        val file = temporaryDirectory.resolve("config.yml").toFile()
        file.writeText("server-key: $key\ninterventions:\n  enabled: false\n")
        val config = ReloadableConfig(file)
        val original = config.current
        for (yaml in listOf(
            "server-key: [$key",
            "server-key: $key\ninterventions:\n  kick:\n    enabled: 'true'",
            "server-key: $key\ninterventions:\n  warning:\n    cooldown-seconds: 0",
            "server-key: $key\ninterventions:\n  unknown: true",
            "server-key: invalid",
            "server-key: ${key.replace("a".repeat(64), "b".repeat(64))}",
            "server-key: ${key.replace("000000000001", "000000000002")}",
        )) {
            file.writeText(yaml)
            assertFails { config.reload() }
            assertSame(original, config.current)
        }
        assertTrue(file.delete())
        assertFailsWith<IOException> { config.reload() }
        assertSame(original, config.current)
    }
}
