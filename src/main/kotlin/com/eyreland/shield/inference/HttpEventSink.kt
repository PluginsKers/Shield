package com.eyreland.shield.inference

import com.eyreland.shield.capture.PacketSample
import com.eyreland.shield.config.InferenceConfig
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.withLock
import kotlin.io.path.name
import kotlin.math.min

/**
 * HTTP-only 事件 sink。
 *
 * 捕获线程只执行 [ArrayBlockingQueue.offer]。单独 I/O 线程负责批处理、gzip、落入
 * 有界 spool、HMAC 和 HTTP。稳定 batch id 存在 gzip body 内，响应丢失时可安全重试。
 */
class HttpEventSink(
    private val config: InferenceConfig,
    private val serverId: String,
    private val proxyMode: String,
    spoolRoot: Path,
    private val executor: ScheduledExecutorService,
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
    private val httpClient: HttpClient = defaultHttpClient(config),
) : EventSink {
    override val endpoint: URI = config.endpoint

    private val queue = ArrayBlockingQueue<OutboundRecord>(config.queueCapacity)
    private val rollout = StableRollout(config.rolloutPercent, config.rolloutSalt)
    private val root = spoolRoot.toAbsolutePath().normalize()
    private val pendingDirectory = root.resolve("pending")
    private val quarantineDirectory = root.resolve("quarantine")
    private val ioLock = ReentrantLock()
    private val fileSequence = AtomicLong(System.nanoTime().coerceAtLeast(0))
    private val serverVersion = AtomicReference("unknown")

    private val sentBatches = AtomicLong()
    private val sentEvents = AtomicLong()
    private val retried = AtomicLong()
    private val rateLimited = AtomicLong()
    private val authenticationFailed = AtomicLong()
    private val schemaRejected = AtomicLong()
    private val otherRejected = AtomicLong()
    private val networkFailed = AtomicLong()
    private val queueDropped = AtomicLong()
    private val spoolDroppedBatches = AtomicLong()
    private val spoolDroppedEvents = AtomicLong()
    private val pendingBatches = AtomicInteger()
    private val quarantinedBatches = AtomicInteger()
    private val spoolBytes = AtomicLong()
    private val lastHttpStatus = AtomicInteger(-1)
    private val lastSuccessAtMs = AtomicLong(-1)

    @Volatile private var started = false
    @Volatile private var nextAttemptAtMs = 0L
    private var consecutiveFailures = 0
    private var task: ScheduledFuture<*>? = null

    override fun start() {
        ioLock.withLock {
            if (started) return
            ensureDirectories()
            removeTemporaryFiles()
            enforceBounds(clock.millis())
            refreshSpoolStats()
            started = true
            task = executor.scheduleWithFixedDelay(
                ::safeCycle,
                0,
                config.flushIntervalMs,
                TimeUnit.MILLISECONDS,
            )
        }
        logger.info(
            "Shield 事件 sink 已启动：endpoint=$endpoint，schema=${config.schemaVersion}," +
                " gray=${config.rolloutPercent}%," +
                " batch=${config.batchSize}，spool<=${config.spoolMaxBytes} bytes/${config.spoolTtlMs} ms",
        )
    }

    override fun accepts(uuid: UUID): Boolean = config.enabled && rollout.includes(uuid)

    override fun enqueue(sample: PacketSample): Boolean {
        if (!started) {
            queueDropped.incrementAndGet()
            return false
        }
        serverVersion.set(sample.serverVersion)
        val accepted = queue.offer(OutboundRecord.Event(sample))
        if (!accepted) queueDropped.incrementAndGet()
        return accepted
    }

    override fun enqueueTelemetry(telemetry: CollectionTelemetry): Boolean {
        if (config.schemaVersion != "shield.events.v2") return false
        if (!started) {
            queueDropped.incrementAndGet()
            return false
        }
        val accepted = queue.offer(OutboundRecord.Telemetry(telemetry))
        if (!accepted) queueDropped.incrementAndGet()
        return accepted
    }

    override fun closeSession(sessionId: UUID): Boolean {
        if (!started) {
            queueDropped.incrementAndGet()
            return false
        }
        val accepted = queue.offer(OutboundRecord.SessionClosed(sessionId))
        if (!accepted) queueDropped.incrementAndGet()
        return accepted
    }

    override fun queued(): Int = queue.size

    override fun stats(): EventSinkStats = EventSinkStats(
        sentBatches = sentBatches.get(),
        sentEvents = sentEvents.get(),
        retried = retried.get(),
        rateLimited = rateLimited.get(),
        authenticationFailed = authenticationFailed.get(),
        schemaRejected = schemaRejected.get(),
        otherRejected = otherRejected.get(),
        networkFailed = networkFailed.get(),
        queueDropped = queueDropped.get(),
        spoolDroppedBatches = spoolDroppedBatches.get(),
        spoolDroppedEvents = spoolDroppedEvents.get(),
        pendingBatches = pendingBatches.get(),
        quarantinedBatches = quarantinedBatches.get(),
        spoolBytes = spoolBytes.get(),
        lastHttpStatus = lastHttpStatus.get().takeIf { it >= 0 },
        lastSuccessAtMs = lastSuccessAtMs.get().takeIf { it >= 0 },
    )

    override fun stop() {
        started = false
        task?.cancel(true)
        task = null
        val acquired = try {
            ioLock.tryLock(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            logger.warning("事件 I/O 未在 2 秒内停止；跳过关闭前内存持久化")
            return
        }
        try {
            persistQueued()
            enforceBounds(clock.millis())
            refreshSpoolStats()
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "关闭前持久化事件队列失败", t)
        } finally {
            ioLock.unlock()
        }
    }

    private fun safeCycle() {
        try {
            ioLock.withLock {
                persistQueued()
                enforceBounds(clock.millis())
                if (clock.millis() >= nextAttemptAtMs) sendPending()
                refreshSpoolStats()
            }
        } catch (t: Throwable) {
            networkFailed.incrementAndGet()
            scheduleRetry(clock.millis())
            logFailure("事件上传周期异常", t)
        }
    }

    private fun persistQueued() {
        while (true) {
            val records = ArrayList<OutboundRecord>(config.batchSize)
            queue.drainTo(records, config.batchSize)
            if (records.isEmpty()) return
            try {
                persistRecords(records)
            } catch (t: Throwable) {
                val events = records.count { it is OutboundRecord.Event }
                spoolDroppedBatches.incrementAndGet()
                spoolDroppedEvents.addAndGet(events.toLong())
                logger.log(Level.SEVERE, "事件 spool 写入失败，丢弃 ${records.size} 条队列记录", t)
            }
        }
    }

    private fun persistRecords(records: List<OutboundRecord>) {
        val encoded = EventBatchEncoder.encode(
            records = records,
            serverId = serverId,
            proxyMode = proxyMode,
            serverVersion = serverVersion.get(),
            schemaVersion = config.schemaVersion,
            nowMs = clock.millis(),
        )
        val oversized = encoded.transmittedBody.size > config.maxCompressedBatchBytes ||
            encoded.uncompressedBytes > config.maxUncompressedBatchBytes
        if (oversized && records.size > 1) {
            val middle = records.size / 2
            persistRecords(records.subList(0, middle))
            persistRecords(records.subList(middle, records.size))
            return
        }
        if (oversized) {
            spoolDroppedBatches.incrementAndGet()
            spoolDroppedEvents.addAndGet(encoded.eventCount.toLong())
            logger.severe(
                "单条事件超过请求体上限，已丢弃：compressed=${encoded.transmittedBody.size}," +
                    " uncompressed=${encoded.uncompressedBytes}",
            )
            return
        }

        val filename = buildFilename(encoded)
        val target = pendingDirectory.resolve(filename)
        val temporary = pendingDirectory.resolve(".$filename.tmp")
        Files.write(temporary, encoded.transmittedBody)
        setOwnerOnlyFilePermissions(temporary)
        atomicMove(temporary, target)
    }

    private fun buildFilename(batch: EncodedBatch): String = "%013d_%020d_%04d_%04d_%s.json.gz".format(
        clock.millis(),
        fileSequence.incrementAndGet(),
        batch.eventCount,
        batch.closedSessionCount,
        batch.batchId,
    )

    private fun sendPending() {
        val entries = pendingEntries()
        var attempted = 0
        for (entry in entries) {
            if (attempted++ >= config.maxSendBatchesPerCycle) return
            when (send(entry)) {
                SendResult.SUCCESS -> Unit
                SendResult.REJECTED, SendResult.RETRY_LATER -> return
            }
        }
    }

    private fun send(entry: SpoolEntry): SendResult {
        val body = try {
            Files.readAllBytes(entry.path)
        } catch (t: IOException) {
            dropPending(entry, "无法读取")
            return SendResult.SUCCESS
        }
        if (body.size > config.maxCompressedBatchBytes) {
            quarantine(entry, 413)
            otherRejected.incrementAndGet()
            return SendResult.REJECTED
        }

        val timestamp = clock.instant().epochSecond
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val signed = HmacSigner.sign(
            config.keyId,
            config.hmacSecret,
            timestamp,
            nonce,
            body,
        )
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(config.requestTimeoutMs))
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "gzip")
            .header("User-Agent", "Shield/${javaClass.`package`.implementationVersion ?: "dev"}")
            .header("X-Shield-Key-Id", signed.keyId)
            .header("X-Shield-Timestamp", signed.timestamp.toString())
            .header("X-Shield-Nonce", signed.nonce)
            .header("X-Shield-Signature", signed.signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            networkFailed.incrementAndGet()
            retried.incrementAndGet()
            scheduleRetry(clock.millis())
            return SendResult.RETRY_LATER
        } catch (failure: IOException) {
            networkFailed.incrementAndGet()
            retried.incrementAndGet()
            scheduleRetry(clock.millis())
            logFailure("事件接口网络失败", failure)
            return SendResult.RETRY_LATER
        }

        val status = response.statusCode()
        lastHttpStatus.set(status)
        if (status in 200..299) {
            Files.deleteIfExists(entry.path)
            sentBatches.incrementAndGet()
            sentEvents.addAndGet(entry.eventCount.toLong())
            lastSuccessAtMs.set(clock.millis())
            consecutiveFailures = 0
            nextAttemptAtMs = 0
            return SendResult.SUCCESS
        }

        if (status == 429 || status == 408 || status == 425 || status >= 500) {
            if (status == 429) rateLimited.incrementAndGet() else networkFailed.incrementAndGet()
            retried.incrementAndGet()
            val retryAfterMs = response.headers().firstValue("Retry-After")
                .orElse("")
                .toLongOrNull()
                ?.times(1_000)
            scheduleRetry(clock.millis(), retryAfterMs)
            logFailure("事件接口暂时失败：HTTP $status")
            return SendResult.RETRY_LATER
        }

        when (status) {
            401, 403 -> authenticationFailed.incrementAndGet()
            422 -> schemaRejected.incrementAndGet()
            else -> otherRejected.incrementAndGet()
        }
        quarantine(entry, status)
        // 认证或 schema 错误通常影响后续所有批次。隔离当前批次后慢速探测，
        // 避免在配置修复前快速清空整个 pending。
        consecutiveFailures += 1
        nextAttemptAtMs = clock.millis() + config.retryMaxMs
        logFailure("事件批次被隔离：HTTP $status")
        return SendResult.REJECTED
    }

    private fun scheduleRetry(nowMs: Long, requestedDelayMs: Long? = null) {
        consecutiveFailures += 1
        var exponential = config.retryInitialMs
        repeat(min(consecutiveFailures - 1, 20)) {
            exponential = if (exponential >= config.retryMaxMs / 2) {
                config.retryMaxMs
            } else {
                exponential * 2
            }
        }
        val requested = requestedDelayMs?.coerceIn(config.retryInitialMs, config.retryMaxMs)
        val base = maxOf(exponential, requested ?: 0)
        val jitter = ThreadLocalRandom.current().nextLong((base / 4).coerceAtLeast(1))
        nextAttemptAtMs = nowMs + base + jitter
    }

    private fun quarantine(entry: SpoolEntry, status: Int) {
        val target = quarantineDirectory.resolve("${entry.path.name}.http-$status")
        atomicMove(entry.path, target)
        setOwnerOnlyFilePermissions(target)
        enforceBounds(clock.millis())
    }

    private fun dropPending(entry: SpoolEntry, reason: String) {
        Files.deleteIfExists(entry.path)
        spoolDroppedBatches.incrementAndGet()
        spoolDroppedEvents.addAndGet(entry.eventCount.toLong())
        logger.severe("丢弃 pending 批次（$reason）：${entry.path.name}")
    }

    private fun enforceBounds(nowMs: Long) {
        expireOldFiles(pendingDirectory, nowMs, countAsDropped = true)
        expireOldFiles(quarantineDirectory, nowMs, countAsDropped = false)

        var pending = pendingEntries()
        while (pending.size > config.spoolMaxBatches) {
            dropPending(pending.first(), "超过 spool-max-batches")
            pending = pending.drop(1)
        }

        var quarantine = regularFiles(quarantineDirectory)
        while (quarantine.size > config.quarantineMaxBatches) {
            Files.deleteIfExists(quarantine.first())
            quarantine = quarantine.drop(1)
        }

        var all = (regularFiles(pendingDirectory) + regularFiles(quarantineDirectory))
            .sortedBy { fileTimestamp(it) }
        var bytes = all.sumOf(::safeSize)
        while (bytes > config.spoolMaxBytes && all.isNotEmpty()) {
            val oldest = all.first()
            val size = safeSize(oldest)
            if (oldest.parent == pendingDirectory) {
                metadata(oldest)?.let { entry ->
                    spoolDroppedBatches.incrementAndGet()
                    spoolDroppedEvents.addAndGet(entry.eventCount.toLong())
                }
            }
            Files.deleteIfExists(oldest)
            bytes -= size
            all = all.drop(1)
        }
    }

    private fun expireOldFiles(directory: Path, nowMs: Long, countAsDropped: Boolean) {
        for (path in regularFiles(directory)) {
            if (nowMs - fileTimestamp(path) <= config.spoolTtlMs) continue
            if (countAsDropped) {
                metadata(path)?.let { entry ->
                    spoolDroppedBatches.incrementAndGet()
                    spoolDroppedEvents.addAndGet(entry.eventCount.toLong())
                }
            }
            Files.deleteIfExists(path)
        }
    }

    private fun pendingEntries(): List<SpoolEntry> = regularFiles(pendingDirectory)
        .mapNotNull(::metadata)
        .sortedBy { it.path.name }

    private fun metadata(path: Path): SpoolEntry? {
        val match = PENDING_NAME.matchEntire(path.name) ?: return null
        return SpoolEntry(
            path = path,
            eventCount = match.groupValues[3].toInt(),
            closedSessionCount = match.groupValues[4].toInt(),
        )
    }

    private fun refreshSpoolStats() {
        pendingBatches.set(pendingEntries().size)
        quarantinedBatches.set(regularFiles(quarantineDirectory).size)
        spoolBytes.set(
            (regularFiles(pendingDirectory) + regularFiles(quarantineDirectory))
                .sumOf(::safeSize),
        )
    }

    private fun ensureDirectories() {
        Files.createDirectories(pendingDirectory)
        Files.createDirectories(quarantineDirectory)
        setOwnerOnlyDirectoryPermissions(root)
        setOwnerOnlyDirectoryPermissions(pendingDirectory)
        setOwnerOnlyDirectoryPermissions(quarantineDirectory)
    }

    private fun removeTemporaryFiles() {
        Files.list(pendingDirectory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun regularFiles(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.filter(Files::isRegularFile)
                .sorted(compareBy<Path> { fileTimestamp(it) }.thenBy { it.name })
                .toList()
        }
    }

    private fun fileTimestamp(path: Path): Long = path.name
        .substringBefore('_')
        .toLongOrNull()
        ?: runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0)

    private fun safeSize(path: Path): Long = runCatching { Files.size(path) }.getOrDefault(0)

    private fun atomicMove(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun setOwnerOnlyDirectoryPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
    }

    private fun setOwnerOnlyFilePermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    private fun logFailure(message: String, failure: Throwable? = null) {
        val failures = consecutiveFailures.coerceAtLeast(1)
        if (failures <= 3 || failures % 10 == 0) {
            if (failure == null) logger.warning("$message（连续失败 $failures）")
            else logger.log(Level.WARNING, "$message（连续失败 $failures）", failure)
        }
    }

    private data class SpoolEntry(
        val path: Path,
        val eventCount: Int,
        val closedSessionCount: Int,
    )

    private enum class SendResult { SUCCESS, RETRY_LATER, REJECTED }

    companion object {
        private val PENDING_NAME = Regex(
            """^(\d{13})_(\d{20})_(\d{4})_(\d{4})_([0-9a-f-]{36})\.json\.gz$""",
        )

        private fun defaultHttpClient(config: InferenceConfig): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.connectTimeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build()
    }
}
