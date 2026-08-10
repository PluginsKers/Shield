package com.eyreland.shield.command

import com.eyreland.shield.inference.EventSinkStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShieldCommandViewTest {
    @Test
    fun `root command is status and legacy status stays compatible`() {
        assertEquals(ShieldCommandAction.STATUS, ShieldCommandView.action(emptyList()))
        assertEquals(ShieldCommandAction.STATUS, ShieldCommandView.action(listOf("STATUS")))
        assertEquals(ShieldCommandAction.RELOAD, ShieldCommandView.action(listOf("reload")))
        assertEquals(ShieldCommandAction.USAGE, ShieldCommandView.action(listOf("help")))
        assertEquals(
            ShieldCommandAction.USAGE,
            ShieldCommandView.action(listOf("status", "extra")),
        )
    }

    @Test
    fun `completion exposes only the actionable reload command`() {
        assertEquals(listOf("reload"), ShieldCommandView.suggestions(listOf("")))
        assertEquals(listOf("reload"), ShieldCommandView.suggestions(listOf("re")))
        assertEquals(emptyList(), ShieldCommandView.suggestions(listOf("s")))
        assertEquals(emptyList(), ShieldCommandView.suggestions(listOf("reload", "extra")))
    }

    @Test
    fun `healthy status is one conclusion line without implementation details`() {
        val lines = ShieldCommandView.status(
            captureEnabled = true,
            decodeFailures = 0,
            sinkStats = stats(),
            queuedRecords = 3,
        )

        assertEquals(
            listOf(
                "§aShield 正常§7。已发送 §f1,234§7 批、§f56,789§7 个事件，" +
                    "待发送 §f2§7 批；内存中还有 §f3§7 条记录。",
            ),
            lines,
        )
        assertFalse(lines.single().contains("HTTP"))
        assertFalse(lines.single().contains("spool"))
        assertFalse(lines.single().contains("TCN"))
    }

    @Test
    fun `disabled and idle states stay one line`() {
        assertEquals(
            listOf("§cShield 未运行§7，事件传输尚未启用。"),
            ShieldCommandView.status(false, 0, null, 0),
        )
        assertEquals(
            listOf("§eShield 待命§7，采集当前没有运行。"),
            ShieldCommandView.status(false, 0, stats(), 0),
        )
    }

    @Test
    fun `temporary transport failure adds one reason line`() {
        val lines = ShieldCommandView.status(
            captureEnabled = true,
            decodeFailures = 0,
            sinkStats = stats(lastHttpStatus = 429),
            queuedRecords = 0,
        )

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("§eShield 同步中"))
        assertEquals("§7检测到：最近一次 HTTP 状态为 §f429§7。", lines[1])
    }

    @Test
    fun `data loss and rejection collapse into one anomaly line`() {
        val lines = ShieldCommandView.status(
            captureEnabled = true,
            decodeFailures = 4,
            sinkStats = stats(
                queueDropped = 3,
                spoolDroppedBatches = 2,
                spoolDroppedEvents = 20,
                quarantinedBatches = 1,
                authenticationFailed = 1,
                schemaRejected = 2,
                otherRejected = 3,
            ),
            queuedRecords = 0,
        )

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("§cShield 异常"))
        assertEquals(
            "§7检测到：解码失败 §f4§7 条；内存丢弃 §f3§7 条；" +
                "磁盘丢弃 §f2§7 批、§f20§7 个事件；隔离 §f1§7 批；" +
                "服务拒绝 §f6§7 次。",
            lines[1],
        )
    }

    private fun stats(
        queueDropped: Long = 0,
        spoolDroppedBatches: Long = 0,
        spoolDroppedEvents: Long = 0,
        quarantinedBatches: Int = 0,
        authenticationFailed: Long = 0,
        schemaRejected: Long = 0,
        otherRejected: Long = 0,
        lastHttpStatus: Int? = 202,
    ): EventSinkStats = EventSinkStats(
        sentBatches = 1_234,
        sentEvents = 56_789,
        retried = 0,
        rateLimited = 0,
        authenticationFailed = authenticationFailed,
        schemaRejected = schemaRejected,
        otherRejected = otherRejected,
        networkFailed = 0,
        queueDropped = queueDropped,
        spoolDroppedBatches = spoolDroppedBatches,
        spoolDroppedEvents = spoolDroppedEvents,
        pendingBatches = 2,
        quarantinedBatches = quarantinedBatches,
        spoolBytes = 0,
        lastHttpStatus = lastHttpStatus,
        lastSuccessAtMs = 1,
    )
}
