package com.eyreland.shield.command

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.InvalidConfigurationException
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShieldCommandTest {
    private val command = object : Command("shield") {
        override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true
    }

    @Test
    fun `only administrators can reload and complete the subcommand`() {
        var allowed = false
        var reloads = 0
        val messages = mutableListOf<String>()
        val sender = sender(messages) { allowed }
        val handler = ShieldCommand({ null }, { null }) { reloads++ }
        assertTrue(handler.onCommand(sender, command, "shield", arrayOf("reload")))
        assertEquals(0, reloads)
        assertEquals(listOf(ShieldCommandView.NO_PERMISSION), messages)
        assertEquals(emptyList(), handler.onTabComplete(sender, command, "shield", arrayOf("")))

        allowed = true
        handler.onCommand(sender, command, "shield", arrayOf("RELOAD"))
        assertEquals(1, reloads)
        assertEquals(ShieldCommandView.RELOADED, messages.last())
        handler.onCommand(sender, command, "shield", arrayOf("reload", "extra"))
        assertEquals(1, reloads)
        assertEquals(ShieldCommandView.USAGE, messages.last())
        assertEquals(listOf("reload"), handler.onTabComplete(sender, command, "shield", arrayOf("RE")))
        assertEquals(emptyList(), handler.onTabComplete(sender, command, "shield", arrayOf("other")))
        assertEquals(emptyList(), handler.onTabComplete(sender, command, "shield", arrayOf("reload", "")))
    }

    @Test
    fun `reload failures report retained configuration without exposing YAML contents`() {
        for (failure in listOf(
            InvalidConfigurationException("secret YAML contents"),
            IOException("secret file contents"),
            IllegalArgumentException("server-key 变更需要重启服务器"),
        )) {
            val messages = mutableListOf<String>()
            val handler = ShieldCommand({ null }, { null }) { throw failure }
            assertTrue(handler.onCommand(sender(messages) { true }, command, "shield", arrayOf("reload")))
            assertTrue(messages.single().contains("保留原配置"))
            assertFalse(messages.single().contains("secret"))
            assertFalse(messages.single().contains("已重载"))
        }
    }

    private fun sender(messages: MutableList<String>, allowed: () -> Boolean): CommandSender =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CommandSender::class.java)) { _, method, args ->
            when (method.name) {
                "hasPermission" -> allowed()
                "sendMessage" -> { messages += args[0] as String; null }
                else -> error("Unexpected sender call: ${method.name}")
            }
        } as CommandSender
}
