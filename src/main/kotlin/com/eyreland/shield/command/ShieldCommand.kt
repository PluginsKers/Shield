package com.eyreland.shield.command

import com.eyreland.shield.capture.PacketCaptureService
import com.eyreland.shield.inference.EventSink
import com.eyreland.shield.inference.EventSinkStats
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.Locale

internal enum class ShieldCommandAction {
    STATUS,
    RELOAD,
    USAGE,
}

internal object ShieldCommandView {
    const val USAGE = "§7用法：/shield [reload]"
    const val NO_PERMISSION = "§c你没有权限管理 Shield。"
    const val RELOAD_SUCCESS = "§aShield 已重载采集策略§7。传输配置变更仍需重启服务器。"

    fun reloadFailure(reason: String?): String = "§cShield 重载失败§7：${reason ?: "请查看控制台"}"

    fun action(args: List<String>): ShieldCommandAction = when {
        args.isEmpty() -> ShieldCommandAction.STATUS
        args.size == 1 && args[0].equals("status", ignoreCase = true) -> ShieldCommandAction.STATUS
        args.size == 1 && args[0].equals("reload", ignoreCase = true) -> ShieldCommandAction.RELOAD
        else -> ShieldCommandAction.USAGE
    }

    fun suggestions(args: List<String>): List<String> = if (args.size == 1) {
        listOf("reload").filter { it.startsWith(args[0].lowercase()) }
    } else {
        emptyList()
    }

    fun status(
        captureEnabled: Boolean,
        decodeFailures: Long,
        sinkStats: EventSinkStats?,
        queuedRecords: Int,
    ): List<String> {
        if (sinkStats == null) {
            return listOf("§cShield 未运行§7，事件传输尚未启用。")
        }
        if (!captureEnabled) {
            return listOf("§eShield 待命§7，采集当前没有运行。")
        }

        val permanentFailures = decodeFailures > 0 ||
            sinkStats.queueDropped > 0 ||
            sinkStats.spoolDroppedBatches > 0 ||
            sinkStats.spoolDroppedEvents > 0 ||
            sinkStats.quarantinedBatches > 0 ||
            sinkStats.authenticationFailed > 0 ||
            sinkStats.schemaRejected > 0 ||
            sinkStats.otherRejected > 0
        val lastHttpFailed = sinkStats.lastHttpStatus?.let { it !in 200..299 } == true
        val state = when {
            permanentFailures -> "§cShield 异常"
            lastHttpFailed -> "§eShield 同步中"
            else -> "§aShield 正常"
        }
        val lines = mutableListOf(
            "$state§7。已发送 §f${number(sinkStats.sentBatches)}§7 批、" +
                "§f${number(sinkStats.sentEvents)}§7 个事件，待发送 " +
                "§f${number(sinkStats.pendingBatches)}§7 批；内存中还有 " +
                "§f${number(queuedRecords)}§7 条记录。",
        )
        val problems = buildList {
            if (decodeFailures > 0) {
                add("解码失败 §f${number(decodeFailures)}§7 条")
            }
            if (sinkStats.queueDropped > 0) {
                add("内存丢弃 §f${number(sinkStats.queueDropped)}§7 条")
            }
            if (sinkStats.spoolDroppedBatches > 0) {
                add(
                    "磁盘丢弃 §f${number(sinkStats.spoolDroppedBatches)}§7 批、" +
                        "§f${number(sinkStats.spoolDroppedEvents)}§7 个事件",
                )
            }
            if (sinkStats.quarantinedBatches > 0) {
                add("隔离 §f${number(sinkStats.quarantinedBatches)}§7 批")
            }
            val rejected = sinkStats.authenticationFailed +
                sinkStats.schemaRejected +
                sinkStats.otherRejected
            if (rejected > 0) add("服务拒绝 §f${number(rejected)}§7 次")
            if (lastHttpFailed) add("最近一次 HTTP 状态为 §f${sinkStats.lastHttpStatus}§7")
        }
        if (problems.isNotEmpty()) {
            lines += "§7检测到：${problems.joinToString("；")}。"
        }
        return lines
    }

    private fun number(value: Number): String = String.format(Locale.ROOT, "%,d", value.toLong())
}

/** `/shield` 只展示请求链路状态，不在游戏服内执行裁决。 */
class ShieldCommand(
    private val capture: () -> PacketCaptureService?,
    private val sink: () -> EventSink?,
    private val reload: () -> Unit,
) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (!sender.hasPermission("shield.admin")) {
            sender.sendMessage(ShieldCommandView.NO_PERMISSION)
            return true
        }
        when (ShieldCommandView.action(args.toList())) {
            ShieldCommandAction.STATUS -> status(sender)
            ShieldCommandAction.RELOAD -> {
                runCatching(reload)
                    .onSuccess {
                        sender.sendMessage(ShieldCommandView.RELOAD_SUCCESS)
                    }
                    .onFailure {
                        sender.sendMessage(ShieldCommandView.reloadFailure(it.message))
                    }
            }
            ShieldCommandAction.USAGE -> sender.sendMessage(ShieldCommandView.USAGE)
        }
        return true
    }

    private fun status(sender: CommandSender) {
        val captureService = capture()
        val captureStats = captureService?.stats()
        val eventSink = sink()
        ShieldCommandView.status(
            captureEnabled = captureService?.enabled == true,
            decodeFailures = captureStats?.decodeFailed ?: 0,
            sinkStats = eventSink?.stats(),
            queuedRecords = eventSink?.queued() ?: 0,
        ).forEach(sender::sendMessage)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (!sender.hasPermission("shield.admin")) return emptyList()
        return ShieldCommandView.suggestions(args.toList())
    }
}
