package com.eyreland.shield.command

import com.eyreland.shield.capture.PacketCaptureService
import com.eyreland.shield.inference.EventSink
import com.eyreland.shield.inference.EventSinkStats
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.InvalidConfigurationException
import java.io.IOException
import java.util.Locale

internal enum class ShieldCommandAction {
    STATUS,
    RELOAD,
    USAGE,
}

internal object ShieldCommandView {
    const val USAGE = "§e用法: §f/shield [reload]"
    const val NO_PERMISSION = "§c你没有权限使用此命令"
    const val RELOADED = "§f行动配置已重载"
    const val INVALID_YAML = "§c配置重载失败, 保留原配置: config.yml 的 YAML 格式错误"
    const val UNREADABLE_CONFIG = "§c配置重载失败, 保留原配置: 无法读取 config.yml"
    fun action(args: List<String>): ShieldCommandAction = when {
        args.isEmpty() -> ShieldCommandAction.STATUS
        args.size == 1 && args[0].equals("reload", ignoreCase = true) -> ShieldCommandAction.RELOAD
        else -> ShieldCommandAction.USAGE
    }

    fun status(
        captureEnabled: Boolean,
        sinkStats: EventSinkStats?,
        queuedRecords: Int,
    ): List<String> {
        if (sinkStats == null) {
            return listOf("§e事件传输未启用")
        }

        val lastHttpFailed = sinkStats.lastHttpStatus?.let { it !in 200..299 } == true
        val state = if (captureEnabled) "§f运行中" else "§e未运行"
        val lines = mutableListOf(
            "§7采集: $state",
            "§7已发送: §f${number(sinkStats.sentBatches)}§7 批, " +
                "§f${number(sinkStats.sentEvents)}§7 个事件",
            "§7待发送: §f${number(sinkStats.pendingBatches)}§7 批 (磁盘), " +
                "§f${number(queuedRecords)}§7 条记录 (内存)",
        )
        val history = buildList {
            if (sinkStats.queueDropped > 0) {
                add("内存丢弃: §f${number(sinkStats.queueDropped)}§7 条记录")
            }
            if (sinkStats.spoolDroppedBatches > 0) {
                add(
                    "磁盘丢弃: §f${number(sinkStats.spoolDroppedBatches)}§7 批, " +
                        "§f${number(sinkStats.spoolDroppedEvents)}§7 个事件",
                )
            }
            val transportFailures = sinkStats.rateLimited +
                sinkStats.authenticationFailed +
                sinkStats.schemaRejected +
                sinkStats.otherRejected +
                sinkStats.networkFailed
            if (transportFailures > 0) {
                add("传输失败: §f${number(transportFailures)}§7 次")
            }
        }
        if (history.isNotEmpty()) {
            lines += "§7本次运行累计:"
            lines += history.map { "§7  $it" }
        }
        if (sinkStats.quarantinedBatches > 0) {
            lines += "§7当前隔离: §f${number(sinkStats.quarantinedBatches)}§7 批"
        }
        if (lastHttpFailed) {
            lines += "§7最近 HTTP 状态: §e${sinkStats.lastHttpStatus}"
        }
        return lines
    }

    private fun number(value: Number): String = String.format(Locale.ROOT, "%,d", value.toLong())
}

/** `/shield` 查看状态，`/shield reload` 重载行动配置。 */
class ShieldCommand(
    private val capture: () -> PacketCaptureService?,
    private val sink: () -> EventSink?,
    private val reloadConfig: () -> Unit,
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
            ShieldCommandAction.RELOAD -> reload(sender)
            ShieldCommandAction.USAGE -> sender.sendMessage(ShieldCommandView.USAGE)
        }
        return true
    }

    private fun reload(sender: CommandSender) {
        try {
            reloadConfig()
            sender.sendMessage(ShieldCommandView.RELOADED)
        } catch (_: InvalidConfigurationException) {
            sender.sendMessage(ShieldCommandView.INVALID_YAML)
        } catch (_: IOException) {
            sender.sendMessage(ShieldCommandView.UNREADABLE_CONFIG)
        } catch (invalid: IllegalArgumentException) {
            sender.sendMessage("§c配置重载失败, 保留原配置: ${invalid.message}")
        }
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
    ): List<String> = if (sender.hasPermission("shield.admin") && args.size == 1) {
        listOf("reload").filter { it.startsWith(args[0], ignoreCase = true) }
    } else {
        emptyList()
    }
}
