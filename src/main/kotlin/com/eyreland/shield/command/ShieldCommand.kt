package com.eyreland.shield.command

import com.eyreland.shield.capture.PacketCaptureService
import com.eyreland.shield.inference.EventSink
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/** `/shield` 只展示捕获与治理传输状态，不在游戏服内执行裁决。 */
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
        when (args.getOrNull(0)?.lowercase()) {
            "status" -> status(sender)
            "reload" -> {
                runCatching(reload)
                    .onSuccess { sender.sendMessage("§f已重载 Shield 捕获策略") }
                    .onFailure { sender.sendMessage("§c配置重载失败：${it.message}") }
            }
            else -> help(sender)
        }
        return true
    }

    private fun status(sender: CommandSender) {
        val captureService = capture()
        val captureStats = captureService?.stats()
        val eventSink = sink()
        val sinkStats = eventSink?.stats()
        sender.sendMessage(
            "§eShield §f采集=${if (captureService?.enabled == true) "§a开" else "§c关"}" +
                " §fHTTP=${if (eventSink != null) "§a开" else "§c关"}" +
                " §f内存队列=§e${eventSink?.queued() ?: 0}",
        )
        if (eventSink != null) {
            sender.sendMessage(
                "§f接口=§e${eventSink.endpoint.host} §f发送批次/事件=§e" +
                    "${sinkStats?.sentBatches ?: 0}/${sinkStats?.sentEvents ?: 0}" +
                    " §f重试/429=§e${sinkStats?.retried ?: 0}/${sinkStats?.rateLimited ?: 0}",
            )
            sender.sendMessage(
                "§fspool 批次/字节=§e${sinkStats?.pendingBatches ?: 0}/${sinkStats?.spoolBytes ?: 0}" +
                    " §f隔离=§e${sinkStats?.quarantinedBatches ?: 0}" +
                    " §f内存丢弃=§e${sinkStats?.queueDropped ?: 0}",
            )
            sender.sendMessage(
                "§fspool 丢弃批次/事件=§e${sinkStats?.spoolDroppedBatches ?: 0}/" +
                    "${sinkStats?.spoolDroppedEvents ?: 0}",
            )
            sender.sendMessage(
                "§f认证/schema/其他拒绝=§e${sinkStats?.authenticationFailed ?: 0}/" +
                    "${sinkStats?.schemaRejected ?: 0}/${sinkStats?.otherRejected ?: 0}" +
                    " §f网络失败=§e${sinkStats?.networkFailed ?: 0}" +
                    " §f最后 HTTP=§e${sinkStats?.lastHttpStatus ?: "-"}",
            )
        }
        sender.sendMessage(
            "§f显式包=§e${captureService?.activePacketNames?.size ?: 0}" +
                " §f接收/入队=§e${captureStats?.received ?: 0}/${captureStats?.enqueued ?: 0}" +
                " §f灰度过滤=§e${captureStats?.grayFiltered ?: 0}",
        )
        sender.sendMessage(
            "§f过滤 玩家/动作/挂机/节流=§e${captureStats?.playerFiltered ?: 0}/" +
                "${captureStats?.actionFiltered ?: 0}/${captureStats?.afkFiltered ?: 0}/" +
                "${captureStats?.throttled ?: 0} §f解码失败=§e${captureStats?.decodeFailed ?: 0}",
        )
        sender.sendMessage("§7当前仅 shadow 观测；Shield 不执行踢出或封禁")
    }

    private fun help(sender: CommandSender) {
        sender.sendMessage("§eShield 命令")
        sender.sendMessage("§7/shield status")
        sender.sendMessage("§7/shield reload")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (!sender.hasPermission("shield.admin")) return emptyList()
        return if (args.size == 1) {
            listOf("status", "reload").filter { it.startsWith(args[0].lowercase()) }
        } else {
            emptyList()
        }
    }
}
