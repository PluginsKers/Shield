package com.eyreland.shield.inference

import com.eyreland.shield.config.InferenceConfig
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 可选的跨仓真实接口测试。普通 CI 自动跳过；本地通过环境变量连接
 * shield-inference，验证 Kotlin gzip/HMAC/JSON 到 Python 在线处理的完整链路。
 */
class HttpEventSinkE2eTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `sends events to real shield-inference`() {
        val endpoint = System.getenv("SHIELD_E2E_ENDPOINT")
        val serverKey = System.getenv("SHIELD_E2E_SERVER_KEY")
        assumeTrue(
            !endpoint.isNullOrBlank() &&
                !serverKey.isNullOrBlank(),
            "未配置 shield-inference E2E 环境变量",
        )

        val config = InferenceConfig.from(
            YamlConfiguration().apply { set("server-key", serverKey) },
        ).copy(endpoint = URI(endpoint))
        val executor = Executors.newSingleThreadScheduledExecutor()
        val sink = HttpEventSink(
            config,
            config.keyId,
            "standalone",
            temporaryDirectory,
            executor,
            Logger.getAnonymousLogger(),
        )
        val base = System.currentTimeMillis()
        val template = sample(UUID.randomUUID()).copy(playerUuid = UUID.randomUUID())
        try {
            sink.start()
            repeat(100) { index ->
                assertTrue(
                    sink.enqueue(
                        template.copy(
                            capturedAtMs = base + index * 50,
                            sessionSequence = index.toLong() + 1,
                            sessionAgeMs = index * 50L,
                            sessionMonotonicUs = index * 50_000L,
                            sincePreviousUs = if (index == 0) null else 50_000,
                            x = index * 0.08,
                            z = index * 0.02,
                            yaw = index * 0.7f,
                        ),
                    ),
                )
            }
            assertTrue(sink.closeSession(template.sessionId))
            await(Duration.ofSeconds(15)) {
                val stats = sink.stats()
                stats.sentEvents == 100L &&
                    stats.pendingBatches == 0 &&
                    sink.queued() == 0
            }

            assertEquals(100, sink.stats().sentEvents)
            assertTrue(sink.stats().sentBatches >= 1)
            assertEquals(0, sink.stats().pendingBatches)
            assertEquals(0, sink.stats().authenticationFailed)
            assertEquals(0, sink.stats().schemaRejected)
        } finally {
            sink.stop()
            executor.shutdownNow()
        }
    }

    private fun await(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(condition(), "condition was not met in $timeout")
    }
}
