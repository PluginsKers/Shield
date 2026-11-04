package com.eyreland.shield.command

import com.eyreland.shield.capture.PacketCaptureService
import com.eyreland.shield.config.ReloadableConfig
import com.eyreland.shield.config.ShieldControl
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
    ENABLE,
    DISABLE,
    ENABLE_INTERVENTIONS,
    DISABLE_INTERVENTIONS,
    USAGE,
}

internal object ShieldCommandView {
    const val USAGE = "§e用法: §f/shield [reload|enable|disable] 或 /shield interventions <enable|disable>"
    const val NO_PERMISSION = "§c你没有权限使用此命令"
    const val RELOADED = "§f配置已重载"
    const val INVALID_YAML = "§c配置更新失败, 保留原配置: config.yml 的 YAML 格式错误"
    const val UNREADABLE_CONFIG = "§c配置更新失败, 保留原配置: 无法读写 config.yml"
    fun action(args: List<String>): ShieldCommandAction = when (args.map { it.lowercase(Locale.ROOT) }) {
        emptyList<String>() -> ShieldCommandAction.STATUS
        listOf("reload") -> ShieldCommandAction.RELOAD
        listOf("enable") -> ShieldCommandAction.ENABLE
        listOf("disable") -> ShieldCommandAction.DISABLE
        listOf("interventions", "enable") -> ShieldCommandAction.ENABLE_INTERVENTIONS
        listOf("interventions", "disable") -> ShieldCommandAction.DISABLE_INTERVENTIONS
        else -> ShieldCommandAction.USAGE
    }

    fun controls(shieldEnabled: Boolean, interventionsEnabled: Boolean): List<String> = listOf(
        "§7Shield: ${if (shieldEnabled) "§f已开启" else "§e已关闭 (采集与上报已暂停)"}",
        "§7玩家处置: ${when {
            !interventionsEnabled -> "§e已关闭"
            !shieldEnabled -> "§e已暂停 (配置已开启)"
            else -> "§f已开启"
        }}",
    )

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

/** 管理整体开关、玩家处置与配置，所有修改在全局区域线程串行执行。 */
class ShieldCommand(
    private val capture: () -> PacketCaptureService?,
    private val sink: () -> EventSink?,
    private val configuration: ReloadableConfig,
    private val onConfigChanged: () -> Unit,
    private val execute: (() -> Unit) -> Unit,
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
        val action = ShieldCommandView.action(args.toList())
        execute {
            when (action) {
                ShieldCommandAction.STATUS -> status(sender)
                ShieldCommandAction.RELOAD -> update(sender, ShieldCommandView.RELOADED, configuration::reload)
                ShieldCommandAction.ENABLE -> setEnabled(sender, ShieldControl.SHIELD, true)
                ShieldCommandAction.DISABLE -> setEnabled(sender, ShieldControl.SHIELD, false)
                ShieldCommandAction.ENABLE_INTERVENTIONS -> setEnabled(sender, ShieldControl.INTERVENTIONS, true)
                ShieldCommandAction.DISABLE_INTERVENTIONS -> setEnabled(sender, ShieldControl.INTERVENTIONS, false)
                ShieldCommandAction.USAGE -> sender.sendMessage(ShieldCommandView.USAGE)
            }
        }
        return true
    }

    private fun setEnabled(sender: CommandSender, control: ShieldControl, enabled: Boolean) {
        val name = if (control == ShieldControl.SHIELD) "Shield" else "玩家处置"
        val state = if (enabled) "开启" else "关闭"
        update(sender, "§f$name 已$state") { configuration.setEnabled(control, enabled) }
    }

    private fun update(sender: CommandSender, success: String, change: () -> Unit) {
        try {
            change()
        } catch (_: InvalidConfigurationException) {
            sender.sendMessage(ShieldCommandView.INVALID_YAML)
            return
        } catch (_: IOException) {
            sender.sendMessage(ShieldCommandView.UNREADABLE_CONFIG)
            return
        } catch (invalid: IllegalArgumentException) {
            sender.sendMessage("§c配置更新失败, 保留原配置: ${invalid.message}")
            return
        }
        onConfigChanged()
        sender.sendMessage(success)
    }

    private fun status(sender: CommandSender) {
        val captureService = capture()
        val eventSink = sink()
        val config = configuration.current
        ShieldCommandView.controls(config.enabled, config.interventions.enabled).forEach(sender::sendMessage)
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
        val candidates = when {
            args.size == 1 -> listOf("reload", "enable", "disable", "interventions")
            args.size == 2 && args[0].equals("interventions", ignoreCase = true) -> listOf("enable", "disable")
            else -> return emptyList()
        }
        return candidates.filter { it.startsWith(args.last(), ignoreCase = true) }
    }
}
