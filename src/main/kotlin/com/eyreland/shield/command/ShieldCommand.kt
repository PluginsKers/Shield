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
            return listOf("§cShield 未运行 §7· 事件传输未启用")
        }
        if (!captureEnabled) {
            return listOf("§eShield 待命 §7· 采集未运行")
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
            "$state §7· 已发送 §f${number(sinkStats.sentBatches)} 批 / " +
                "${number(sinkStats.sentEvents)} 事件 §7· 待发 §f" +
                "${number(sinkStats.pendingBatches)} 批 / ${number(queuedRecords)} 条",
        )
        val problems = buildList {
            if (decodeFailures > 0) {
                add("解码失败 §f${number(decodeFailures)} 条")
            }
            if (sinkStats.queueDropped > 0) {
                add("内存丢弃 §f${number(sinkStats.queueDropped)} 条")
            }
            if (sinkStats.spoolDroppedBatches > 0) {
                add(
                    "磁盘丢弃 §f${number(sinkStats.spoolDroppedBatches)} 批 / " +
                        "${number(sinkStats.spoolDroppedEvents)} 事件",
                )
            }
            if (sinkStats.quarantinedBatches > 0) {
                add("隔离 §f${number(sinkStats.quarantinedBatches)} 批")
            }
            val rejected = sinkStats.authenticationFailed +
                sinkStats.schemaRejected +
                sinkStats.otherRejected
            if (rejected > 0) add("服务拒绝 §f${number(rejected)} 次")
            if (lastHttpFailed) add("最近 HTTP §f${sinkStats.lastHttpStatus}")
        }
        if (problems.isNotEmpty()) {
            lines += "§7原因 · ${problems.joinToString(" §7· ")}"
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
            sender.sendMessage("§c你没有权限使用该命令")
            return true
        }
        when (ShieldCommandView.action(args.toList())) {
            ShieldCommandAction.STATUS -> status(sender)
            ShieldCommandAction.RELOAD -> {
                runCatching(reload)
                    .onSuccess {
                        sender.sendMessage(
                            "§aShield 采集策略已重载 §7· 传输配置变更仍需重启",
                        )
                    }
                    .onFailure {
                        sender.sendMessage("§cShield 重载失败 §7· ${it.message ?: "请查看控制台"}")
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
