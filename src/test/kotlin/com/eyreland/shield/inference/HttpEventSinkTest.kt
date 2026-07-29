package com.eyreland.shield.inference

import com.eyreland.shield.config.InferenceConfig
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HttpEventSinkTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `retries same batch body with a fresh nonce`() {
        val requests = CopyOnWriteArrayList<Received>()
        val attempts = AtomicInteger()
        val accepted = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/events") { exchange ->
            val body = exchange.requestBody.use { it.readAllBytes() }
            requests += Received(
                body,
                exchange.requestHeaders.getFirst("X-Shield-Timestamp").toLong(),
                exchange.requestHeaders.getFirst("X-Shield-Nonce"),
                exchange.requestHeaders.getFirst("X-Shield-Signature"),
            )
            val status = if (attempts.getAndIncrement() == 0) 500 else 202
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            if (status == 202) accepted.countDown()
        }
        server.start()

        val executor = Executors.newSingleThreadScheduledExecutor()
        val config = testConfig(
            URI("http://127.0.0.1:${server.address.port}/v1/events"),
        )
        val sink = HttpEventSink(
            config,
            "survival-test",
            "velocity",
            temporaryDirectory,
            executor,
            Logger.getAnonymousLogger(),
        )
        try {
            sink.start()
            assertTrue(sink.enqueue(sample()))
            assertTrue(accepted.await(10, TimeUnit.SECONDS))

            assertEquals(2, requests.size)
            assertTrue(requests[0].body.contentEquals(requests[1].body))
            assertNotEquals(requests[0].nonce, requests[1].nonce)
            requests.forEach { received ->
                assertEquals(
                    HmacSigner.sign(
                        config.keyId,
                        config.hmacSecret,
                        received.timestamp,
                        received.nonce,
                        received.body,
                    ).signature,
                    received.signature,
                )
            }
            val json = GZIPInputStream(
                ByteArrayInputStream(requests[0].body),
            ).use { it.readAllBytes().decodeToString() }
            assertTrue(json.contains("\"schema_version\":\"shield.events.v1\""))

            await(Duration.ofSeconds(5)) { sink.stats().pendingBatches == 0 }
            assertEquals(1, sink.stats().sentBatches)
            assertEquals(1, sink.stats().sentEvents)
            assertEquals(1, sink.stats().retried)
        } finally {
            sink.stop()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun `schema rejection is quarantined and not retried`() {
        val received = CountDownLatch(1)
        val attempts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/events") { exchange ->
            exchange.requestBody.close()
            attempts.incrementAndGet()
            exchange.sendResponseHeaders(422, -1)
            exchange.close()
            received.countDown()
        }
        server.start()

        val executor = Executors.newSingleThreadScheduledExecutor()
        val sink = HttpEventSink(
            testConfig(URI("http://127.0.0.1:${server.address.port}/v1/events")),
            "survival-test",
            "velocity",
            temporaryDirectory,
            executor,
            Logger.getAnonymousLogger(),
        )
        try {
            sink.start()
            assertTrue(sink.enqueue(sample()))
            assertTrue(received.await(5, TimeUnit.SECONDS))
            await(Duration.ofSeconds(5)) {
                sink.stats().quarantinedBatches == 1
            }
            Thread.sleep(300)

            assertEquals(1, attempts.get())
            assertEquals(1, sink.stats().schemaRejected)
            assertEquals(0, sink.stats().pendingBatches)
        } finally {
            sink.stop()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun `authentication failure is quarantined and not retried`() {
        val received = CountDownLatch(1)
        val attempts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/events") { exchange ->
            exchange.requestBody.close()
            attempts.incrementAndGet()
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            received.countDown()
        }
        server.start()

        val executor = Executors.newSingleThreadScheduledExecutor()
        val sink = HttpEventSink(
            testConfig(URI("http://127.0.0.1:${server.address.port}/v1/events")),
            "survival-test",
            "velocity",
            temporaryDirectory,
            executor,
            Logger.getAnonymousLogger(),
        )
        try {
            sink.start()
            assertTrue(sink.enqueue(sample()))
            assertTrue(received.await(5, TimeUnit.SECONDS))
            await(Duration.ofSeconds(5)) {
                sink.stats().quarantinedBatches == 1
            }
            Thread.sleep(300)

            assertEquals(1, attempts.get())
            assertEquals(1, sink.stats().authenticationFailed)
            assertEquals(0, sink.stats().pendingBatches)
        } finally {
            sink.stop()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun `rate limit response is retried`() {
        val attempts = AtomicInteger()
        val accepted = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/events") { exchange ->
            exchange.requestBody.close()
            val status = if (attempts.getAndIncrement() == 0) 429 else 202
            if (status == 429) exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            if (status == 202) accepted.countDown()
        }
        server.start()

        val executor = Executors.newSingleThreadScheduledExecutor()
        val sink = HttpEventSink(
            testConfig(URI("http://127.0.0.1:${server.address.port}/v1/events")),
            "survival-test",
            "velocity",
            temporaryDirectory,
            executor,
            Logger.getAnonymousLogger(),
        )
        try {
            sink.start()
            assertTrue(sink.enqueue(sample()))
            assertTrue(accepted.await(10, TimeUnit.SECONDS))
            await(Duration.ofSeconds(5)) { sink.stats().sentBatches == 1L }

            assertEquals(2, attempts.get())
            assertEquals(1, sink.stats().rateLimited)
            assertEquals(1, sink.stats().retried)
            assertEquals(1, sink.stats().sentBatches)
        } finally {
            sink.stop()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun `pending spool evicts oldest batches at configured bound`() {
        val unavailablePort = ServerSocket(0).use { it.localPort }
        val executor = Executors.newSingleThreadScheduledExecutor()
        val sink = HttpEventSink(
            testConfig(
                URI("http://127.0.0.1:$unavailablePort/v1/events"),
            ).copy(
                batchSize = 1,
                spoolMaxBatches = 2,
                retryMaxMs = 5_000,
            ),
            "survival-test",
            "velocity",
            temporaryDirectory,
            executor,
            Logger.getAnonymousLogger(),
        )
        try {
            sink.start()
            repeat(5) { index ->
                assertTrue(
                    sink.enqueue(
                        sample().copy(sessionSequence = index.toLong() + 1),
                    ),
                )
            }
            await(Duration.ofSeconds(5)) {
                val stats = sink.stats()
                stats.pendingBatches == 2 &&
                    stats.spoolDroppedBatches >= 3
            }

            assertEquals(2, sink.stats().pendingBatches)
            assertTrue(sink.stats().spoolBytes <= 4 * 1024 * 1024)
        } finally {
            sink.stop()
            executor.shutdownNow()
        }
    }

    private fun testConfig(endpoint: URI) = InferenceConfig(
        enabled = true,
        endpoint = endpoint,
        keyId = "test-key",
        hmacSecret = "0123456789abcdef0123456789abcdef",
        batchSize = 10,
        flushIntervalMs = 20,
        queueCapacity = 100,
        connectTimeoutMs = 500,
        requestTimeoutMs = 2_000,
        retryInitialMs = 50,
        retryMaxMs = 2_000,
        maxSendBatchesPerCycle = 4,
        spoolDirectory = "spool",
        spoolMaxBytes = 4 * 1024 * 1024,
        spoolMaxBatches = 100,
        spoolTtlMs = 60_000,
        quarantineMaxBatches = 10,
        maxCompressedBatchBytes = 1_500_000,
        maxUncompressedBatchBytes = 10 * 1024 * 1024,
        rolloutPercent = 100.0,
        rolloutSalt = "test",
    )

    private fun await(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition was not met in $timeout")
    }

    private data class Received(
        val body: ByteArray,
        val timestamp: Long,
        val nonce: String,
        val signature: String,
    )
}
