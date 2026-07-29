package com.eyreland.shield.capture

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 维护后端服务器实际确认的玩家上下文。
 *
 * Velocity modern forwarding 正确配置时，Bukkit 暴露的 UUID/名称就是转发后的玩家身份；
 * 世界、模式和初始位置均在对应玩家事件线程读取，网络线程不触碰这些 Bukkit 状态。
 */
class PlayerContextStore(private val plugin: Plugin) : Listener {
    private val contexts = ConcurrentHashMap<UUID, CaptureContext>()

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        for (player in plugin.server.onlinePlayers) beginSession(player)
    }

    fun stop() {
        HandlerList.unregisterAll(this)
        contexts.clear()
    }

    fun get(uuid: UUID): CaptureContext? = contexts[uuid]

    /**
     * 捕获监听器重建时切换会话 ID，防止新序列从 1 开始却复用旧 session_id。
     * 这里只改不可变快照，不跨线程读取 Bukkit Player。
     */
    fun rotateSessions() {
        val now = System.currentTimeMillis()
        contexts.replaceAll { _, current ->
            current.copy(
                sessionId = UUID.randomUUID(),
                joinedAtMs = now,
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = beginSession(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        contexts.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = refresh(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        val current = contexts[event.player.uniqueId] ?: return
        contexts[event.player.uniqueId] = current.copy(gameMode = event.newGameMode.name)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val current = contexts[player.uniqueId] ?: return
        contexts[player.uniqueId] = fromLocation(player, current.sessionId, current.joinedAtMs, event.respawnLocation)
    }

    private fun beginSession(player: Player) {
        val now = System.currentTimeMillis()
        contexts[player.uniqueId] = fromLocation(player, UUID.randomUUID(), now, player.location)
    }

    private fun refresh(player: Player) {
        val current = contexts[player.uniqueId]
        val sessionId = current?.sessionId ?: UUID.randomUUID()
        val joinedAtMs = current?.joinedAtMs ?: System.currentTimeMillis()
        contexts[player.uniqueId] = fromLocation(player, sessionId, joinedAtMs, player.location)
    }

    private fun fromLocation(player: Player, sessionId: UUID, joinedAtMs: Long, location: Location): CaptureContext =
        CaptureContext(
            sessionId = sessionId,
            playerUuid = player.uniqueId,
            playerName = player.name,
            world = location.world?.name,
            gameMode = player.gameMode.name,
            joinedAtMs = joinedAtMs,
            initialX = location.x,
            initialY = location.y,
            initialZ = location.z,
            initialYaw = location.yaw,
            initialPitch = location.pitch,
        )
}
