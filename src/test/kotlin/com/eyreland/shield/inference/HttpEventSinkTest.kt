package com.eyreland.shield.inference

import com.eyreland.shield.config.InferenceConfig
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
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
    fun `receipt is acknowledged only after durable spool write`() {
        val persisted = CountDownLatch(1)
        val receiptId = "intervention-durable"
        val unavailablePort = ServerSocket(0).use { it.localPort }
        val executor = Executors.newSingleThreadScheduledExecutor()
        val sink = HttpEventSink(
            config = testConfig(
                URI("http://127.0.0.1:$unavailablePort/v1/events"),
            ).copy(retryMaxMs = 5_000),
            serverId = "survival-test",
            proxyMode = "velocity",
            spoolRoot = temporaryDirectory,
            executor = executor,
            logger = Logger.getAnonymousLogger(),
            persistedReceiptHandler = { ids ->
                assertEquals(listOf(receiptId), ids)
                Files.list(temporaryDirectory.resolve("pending")).use { files ->
                    assertTrue(files.findAny().isPresent)
                }
                persisted.countDown()
            },
        )
        val receipt = InterventionReceipt(
            directiveId = receiptId,
            playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000010"),
            kind = InterventionKind.WARN,
            outcome = InterventionOutcome.APPLIED,
            handledAtMs = 1_900_000_000_000,
        )
        try {
            sink.start()
            assertTrue(sink.enqueueInterventionReceipt(receipt))
            assertTrue(persisted.await(5, TimeUnit.SECONDS))
        } finally {
            sink.stop()
            executor.shutdownNow()
        }
    }

    @Test
    fun `successful ack is handled before pending batch is deleted`() {
        val handled = CountDownLatch(1)
        val response = """
            {"schema_version":"shield.events.ack.v2","player_interventions":[]}
        """.trimIndent().encodeToByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/events") { exchange ->
            exchange.requestBody.close()
            exchange.sendResponseHeaders(202, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val sink = HttpEventSink(
            config = testConfig(URI("http://127.0.0.1:${server.address.port}/v1/events")),
            serverId = "survival-test",
            proxyMode = "velocity",
            spoolRoot = temporaryDirectory,
            executor = executor,
            logger = Logger.getAnonymousLogger(),
            responseHandler = { _, body ->
                assertTrue(body.contentEquals(response))
                handled.countDown()
            },
        )
        try {
            sink.start()
            assertTrue(sink.enqueue(sample()))
            assertTrue(handled.await(5, TimeUnit.SECONDS))
            await(Duration.ofSeconds(5)) { sink.stats().sentBatches == 1L }
            assertEquals(0, sink.stats().pendingBatches)
            assertEquals(1, sink.stats().sentBatches)
        } finally {
            sink.stop()
            executor.shutdownNow()
            server.stop(0)
        }
    }

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
            assertTrue(json.contains("\"schema_version\":\"shield.events.v2\""))
            assertTrue(json.contains("\"session_monotonic_us\":0"))

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
    fun `network outage logs once without stack trace and reports recovery`() {
        val port = ServerSocket(0).use { it.localPort }
        val records = CopyOnWriteArrayList<LogRecord>()
        val logger = Logger.getLogger("HttpEventSinkTest-${UUID.randomUUID()}").apply {
            useParentHandlers = false
            level = Level.ALL
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        val executor = Executors.newSingleThreadScheduledExecutor()
        val sink = HttpEventSink(
            testConfig(URI("http://127.0.0.1:$port/v1/events")).copy(
                retryInitialMs = 20,
                retryMaxMs = 50,
            ),
            "survival-test",
            "velocity",
            temporaryDirectory,
            executor,
            logger,
        )
        var server: HttpServer? = null
        try {
            sink.start()
            assertTrue(sink.enqueue(sample()))
            await(Duration.ofSeconds(5)) { sink.stats().networkFailed >= 3 }

            val failures = records.filter { it.message.startsWith("事件接口网络失败") }
            assertEquals(1, failures.size)
            assertEquals(null, failures.single().thrown)

            server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/v1/events") { exchange ->
                    exchange.requestBody.close()
                    exchange.sendResponseHeaders(202, -1)
                    exchange.close()
                }
                start()
            }
            await(Duration.ofSeconds(5)) { sink.stats().sentBatches == 1L }

            assertEquals(1, records.count { it.message.startsWith("事件接口已恢复") })
        } finally {
            sink.stop()
            executor.shutdownNow()
            server?.stop(0)
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

    @Test
    fun `capture offer remains bounded under sustained load`() {
        val unavailablePort = ServerSocket(0).use { it.localPort }
        val executor = Executors.newSingleThreadScheduledExecutor()
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        executor.submit {
            workerStarted.countDown()
            releaseWorker.await()
        }
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))
        val sink = HttpEventSink(
            testConfig(
                URI("http://127.0.0.1:$unavailablePort/v1/events"),
            ).copy(queueCapacity = 8),
            "survival-test",
            "velocity",
            temporaryDirectory,
            executor,
            Logger.getAnonymousLogger(),
        )
        try {
            sink.start()
            val accepted = (1..20_000).count { index ->
                sink.enqueue(sample().copy(sessionSequence = index.toLong()))
            }

            assertEquals(8, accepted)
            assertEquals(8, sink.queued())
            assertEquals(19_992, sink.stats().queueDropped)
        } finally {
            releaseWorker.countDown()
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
        schemaVersion = "shield.events.v2",
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
