package com.eyreland.shield.inference

import com.eyreland.shield.capture.PacketSample
import java.net.URI
import java.util.UUID

data class EventSinkStats(
    val sentBatches: Long,
    val sentEvents: Long,
    val retried: Long,
    val rateLimited: Long,
    val authenticationFailed: Long,
    val schemaRejected: Long,
    val otherRejected: Long,
    val networkFailed: Long,
    val queueDropped: Long,
    val spoolDroppedBatches: Long,
    val spoolDroppedEvents: Long,
    val pendingBatches: Int,
    val quarantinedBatches: Int,
    val spoolBytes: Long,
    val lastHttpStatus: Int?,
    val lastSuccessAtMs: Long?,
)

interface EventSink {
    val endpoint: URI

    fun start()

    fun accepts(uuid: UUID): Boolean

    /** 捕获线程只调用非阻塞 offer。 */
    fun enqueue(sample: PacketSample): Boolean

    /** 与事件进入同一有界队列，保证会话关闭不会主动越过已入队事件。 */
    fun closeSession(sessionId: UUID): Boolean

    fun queued(): Int

    fun stats(): EventSinkStats

    fun stop()
}
