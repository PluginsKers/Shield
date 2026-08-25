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
    const val RELOAD_SUCCESS = "§aShield 已重载采集与玩家干预策略§7。传输配置变更仍需重启服务器。"

    fun reloadFailure(reason: String?): String = "§cShield 重载失败§7：${reason ?: "请查看控制台"}"

    fun action(args: List<String>): ShieldCommandAction = when {
        args.isEmpty() -> ShieldCommandAction.STATUS
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
        sinkStats: EventSinkStats?,
        queuedRecords: Int,
    ): List<String> {
        if (sinkStats == null) {
            return listOf("§eShield 事件传输未启用§7。")
        }

        val lastHttpFailed = sinkStats.lastHttpStatus?.let { it !in 200..299 } == true
        val state = if (captureEnabled) "§fShield 运行中" else "§eShield 采集未运行"
        val lines = mutableListOf(
            "$state§7。已发送 §f${number(sinkStats.sentBatches)}§7 批、" +
                "§f${number(sinkStats.sentEvents)}§7 个事件，磁盘待发送 " +
                "§f${number(sinkStats.pendingBatches)}§7 批；内存待发送 " +
                "§f${number(queuedRecords)}§7 条记录。",
        )
        val history = buildList {
            if (sinkStats.queueDropped > 0) {
                add("内存丢弃 §f${number(sinkStats.queueDropped)}§7 条记录")
            }
            if (sinkStats.spoolDroppedBatches > 0) {
                add(
                    "磁盘丢弃 §f${number(sinkStats.spoolDroppedBatches)}§7 批、" +
                        "§f${number(sinkStats.spoolDroppedEvents)}§7 个事件",
                )
            }
            val transportFailures = sinkStats.rateLimited +
                sinkStats.authenticationFailed +
                sinkStats.schemaRejected +
                sinkStats.otherRejected +
                sinkStats.networkFailed
            if (transportFailures > 0) {
                add("传输失败 §f${number(transportFailures)}§7 次")
            }
        }
        val observations = buildList {
            if (history.isNotEmpty()) {
                add("本次运行累计：${history.joinToString("；")}")
            }
            if (sinkStats.quarantinedBatches > 0) {
                add("当前隔离 §f${number(sinkStats.quarantinedBatches)}§7 批")
            }
            if (lastHttpFailed) add("最近一次 HTTP 状态为 §f${sinkStats.lastHttpStatus}§7")
        }
        if (observations.isNotEmpty()) {
            lines += "§7${observations.joinToString("；")}。"
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
        val eventSink = sink()
        ShieldCommandView.status(
            captureEnabled = captureService?.enabled == true,
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
