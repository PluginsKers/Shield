package com.eyreland.shield.command

import com.eyreland.shield.inference.EventSinkStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShieldCommandViewTest {
    @Test
    fun `root command is status and reload is the only subcommand`() {
        assertEquals(ShieldCommandAction.STATUS, ShieldCommandView.action(emptyList()))
        assertEquals(ShieldCommandAction.USAGE, ShieldCommandView.action(listOf("STATUS")))
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
    fun `command copy avoids decorative separators`() {
        val messages = buildList {
            add(ShieldCommandView.USAGE)
            add(ShieldCommandView.NO_PERMISSION)
            add(ShieldCommandView.RELOAD_SUCCESS)
            add(ShieldCommandView.reloadFailure(null))
            addAll(ShieldCommandView.status(false, null, 0))
            addAll(ShieldCommandView.status(true, stats(), 0))
            addAll(
                ShieldCommandView.status(
                    captureEnabled = true,
                    sinkStats = stats(lastHttpStatus = 500),
                    queuedRecords = 1,
                ),
            )
        }
        val separators = listOf("\u00B7", " ${124.toChar()} ", " ${47.toChar()} ")

        messages.forEach { message ->
            assertTrue(separators.none(message::contains), message)
        }
    }

    @Test
    fun `running status reports only transport facts`() {
        val lines = ShieldCommandView.status(
            captureEnabled = true,
            sinkStats = stats(),
            queuedRecords = 3,
        )

        assertEquals(
            listOf(
                "§fShield 运行中§7。已发送 §f1,234§7 批、§f56,789§7 个事件，" +
                    "磁盘待发送 §f2§7 批；内存待发送 §f3§7 条记录。",
            ),
            lines,
        )
        assertFalse(lines.joinToString().contains("HTTP"))
        assertFalse(lines.joinToString().contains("spool"))
        assertFalse(lines.joinToString().contains("TCN"))
    }

    @Test
    fun `disabled and idle states stay one line`() {
        assertEquals(
            listOf("§eShield 事件传输未启用§7。"),
            ShieldCommandView.status(false, null, 0),
        )
        assertEquals(
            listOf(
                "§eShield 采集未运行§7。已发送 §f1,234§7 批、§f56,789§7 个事件，" +
                    "磁盘待发送 §f2§7 批；内存待发送 §f0§7 条记录。",
            ),
            ShieldCommandView.status(false, stats(), 0),
        )
    }

    @Test
    fun `temporary transport failure adds one reason line`() {
        val lines = ShieldCommandView.status(
            captureEnabled = true,
            sinkStats = stats(lastHttpStatus = 429),
            queuedRecords = 0,
        )

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("§fShield 运行中"))
        assertEquals("§7最近一次 HTTP 状态为 §f429§7。", lines[1])
    }

    @Test
    fun `historical failures do not redefine the current running state`() {
        val lines = ShieldCommandView.status(
            captureEnabled = true,
            sinkStats = stats(
                queueDropped = 3,
                spoolDroppedBatches = 2,
                spoolDroppedEvents = 20,
                quarantinedBatches = 1,
                rateLimited = 1,
                authenticationFailed = 1,
                schemaRejected = 2,
                otherRejected = 3,
                networkFailed = 2,
            ),
            queuedRecords = 0,
        )

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("§fShield 运行中"))
        assertEquals(
            "§7本次运行累计：内存丢弃 §f3§7 条记录；" +
                "磁盘丢弃 §f2§7 批、§f20§7 个事件；传输失败 §f9§7 次；" +
                "当前隔离 §f1§7 批。",
            lines[1],
        )
    }

    private fun stats(
        queueDropped: Long = 0,
        spoolDroppedBatches: Long = 0,
        spoolDroppedEvents: Long = 0,
        quarantinedBatches: Int = 0,
        rateLimited: Long = 0,
        authenticationFailed: Long = 0,
        schemaRejected: Long = 0,
        otherRejected: Long = 0,
        networkFailed: Long = 0,
        lastHttpStatus: Int? = 202,
    ): EventSinkStats = EventSinkStats(
        sentBatches = 1_234,
        sentEvents = 56_789,
        retried = 0,
        rateLimited = rateLimited,
        authenticationFailed = authenticationFailed,
        schemaRejected = schemaRejected,
        otherRejected = otherRejected,
        networkFailed = networkFailed,
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
