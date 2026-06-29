package com.eyreland.shield.command

import com.eyreland.shield.capture.PacketCaptureService
import com.eyreland.shield.label.LabelStore
import com.eyreland.shield.storage.SampleWriter
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.UUID

/**
 * /shield 管理命令，全部需要 shield.admin（默认 op）。
 *
 * - status:采集开关、队列、累计落库/丢弃/失败、标记人数
 * - label <玩家|UUID> <normal|abnormal|clear>:标记玩家，回填到后续样本的 label 列
 * - reload:重载采集开关与密度（数据库与上传参数变更需重启）
 */
class ShieldCommand(
    private val capture: () -> PacketCaptureService?,
    private val writer: SampleWriter,
    private val labels: LabelStore,
    private val reload: () -> Unit,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("shield.admin")) {
            sender.sendMessage("§c你没有权限使用该命令")
            return true
        }
        when (args.getOrNull(0)?.lowercase()) {
            "status" -> status(sender)
            "label" -> label(sender, args)
            "reload" -> {
                reload()
                sender.sendMessage("§f已重载 Shield 采集配置")
            }
            else -> help(sender)
        }
        return true
    }

    private fun status(sender: CommandSender) {
        val stats = writer.stats()
        val counts = labels.counts()
        val on = capture()?.enabled == true
        sender.sendMessage("§eShield §f采集=${if (on) "§a开" else "§c关"} §f队列=§e${writer.queued()}")
        sender.sendMessage("§f落库=§e${stats.inserted} §f丢弃=§e${stats.dropped} §f失败=§e${stats.failed}")
        sender.sendMessage("§f标记 normal=§e${counts["normal"] ?: 0} §fabnormal=§e${counts["abnormal"] ?: 0}")
    }

    private fun label(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage("§7用法:/shield label <玩家|UUID> <normal|abnormal|clear>")
            return
        }
        val target = resolve(args[1])
        if (target == null) {
            sender.sendMessage("§c找不到玩家:${args[1]}")
            return
        }
        val (uuid, name) = target
        when (args[2].lowercase()) {
            "normal" -> {
                labels.set(uuid, name, LabelStore.Label.NORMAL, sender.name)
                sender.sendMessage("§f已标记 §e$name §f为 §anormal")
            }
            "abnormal" -> {
                labels.set(uuid, name, LabelStore.Label.ABNORMAL, sender.name)
                sender.sendMessage("§f已标记 §e$name §f为 §cabnormal")
            }
            "clear" -> {
                labels.clear(uuid)
                sender.sendMessage("§f已清除 §e$name §f的标记")
            }
            else -> sender.sendMessage("§7标记取值:normal | abnormal | clear")
        }
    }

    private fun resolve(arg: String): Pair<UUID, String>? {
        Bukkit.getPlayerExact(arg)?.let { return it.uniqueId to it.name }
        val uuid = runCatching { UUID.fromString(arg) }.getOrNull() ?: return null
        val offline = Bukkit.getOfflinePlayer(uuid)
        return uuid to (offline.name ?: uuid.toString())
    }

    private fun help(sender: CommandSender) {
        sender.sendMessage("§eShield 命令")
        sender.sendMessage("§7/shield status")
        sender.sendMessage("§7/shield label <玩家|UUID> <normal|abnormal|clear>")
        sender.sendMessage("§7/shield reload")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (!sender.hasPermission("shield.admin")) return emptyList()
        return when (args.size) {
            1 -> listOf("status", "label", "reload").filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].equals("label", true)) {
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
            } else {
                emptyList()
            }
            3 -> if (args[0].equals("label", true)) {
                listOf("normal", "abnormal", "clear").filter { it.startsWith(args[2].lowercase()) }
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }
}
