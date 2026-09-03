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

data class CollectionTelemetry(
    val capturedAtMs: Long,
    val eligiblePlayerCount: Int,
    val missingPlayerUuids: List<UUID>,
    val deliveryLossObserved: Boolean,
)

interface EventSink {
    val endpoint: URI

    fun start()

    /** 捕获线程只调用非阻塞 offer。 */
    fun enqueue(sample: PacketSample): Boolean

    /** 周期采集证明与事件共用有界传输链路。 */
    fun enqueueTelemetry(telemetry: CollectionTelemetry): Boolean

    /** 玩家干预回执与事件共用有界持久化链路。 */
    fun enqueueInterventionReceipt(receipt: InterventionReceipt): Boolean

    /** 与事件进入同一有界队列，保证会话关闭不会主动越过已入队事件。 */
    fun closeSession(sessionId: UUID): Boolean

    fun queued(): Int

    fun stats(): EventSinkStats

    fun stop()
}
