package com.eyreland.shield.capture

import com.comphenix.protocol.ProtocolLibrary
import com.eyreland.shield.config.CaptureConfig
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.entity.EntityToggleSwimEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.event.player.PlayerVelocityEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 采集 Paper 已经解释过的权威事实。监听器只构造稳定标量并非阻塞入队，
 * 不做特征工程、裁决或任何玩家处置。
 */
class AuthorityCaptureService(
    private val plugin: Plugin,
    private val config: CaptureConfig,
    private val capture: PacketCaptureService,
) : Listener {
    private val heartbeatTasks = ConcurrentHashMap<UUID, ScheduledTask>()

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.onlinePlayers.forEach(::scheduleHeartbeat)
    }

    fun stop() {
        HandlerList.unregisterAll(this)
        heartbeatTasks.values.forEach(ScheduledTask::cancel)
        heartbeatTasks.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = scheduleHeartbeat(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        heartbeatTasks.remove(event.player.uniqueId)?.cancel()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClientAttack(event: PrePlayerAttackEntityEvent) {
        val target = event.attacked
        emit(
            player = event.player,
            source = "server_event",
            eventType = "attack_intent",
            category = CaptureCategory.COMBAT,
            action = "ATTACK",
            target = target,
            payload = linkedMapOf(
                "will_attack" to event.willAttack(),
                "cancelled" to event.isCancelled,
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAttackDamage(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        emit(
            player = attacker,
            source = "server_event",
            eventType = "attack_result",
            category = CaptureCategory.COMBAT,
            action = "DAMAGE",
            target = event.entity,
            payload = linkedMapOf(
                "accepted" to (!event.isCancelled && event.finalDamage > 0.0),
                "cancelled" to event.isCancelled,
                "damage" to event.damage,
                "final_damage" to event.finalDamage,
                "critical" to event.isCritical,
                "cause" to event.cause.name,
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAttackKnockback(event: EntityPushedByEntityAttackEvent) {
        val attacker = event.pushedBy as? Player ?: return
        emit(
            player = attacker,
            source = "server_event",
            eventType = "attack_knockback",
            category = CaptureCategory.COMBAT,
            action = "KNOCKBACK",
            target = event.entity,
            payload = linkedMapOf(
                "cancelled" to event.isCancelled,
                "cause" to event.cause.name,
                "final_knockback" to vectorMap(event.knockback),
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTeleport(event: PlayerTeleportEvent) {
        val correctionCandidate = event.cause == PlayerTeleportEvent.TeleportCause.UNKNOWN
        emit(
            player = event.player,
            source = "server_event",
            eventType = if (correctionCandidate) "position_correction" else "teleport",
            category = CaptureCategory.PLAYER_STATE,
            action = event.cause.name,
            payload = linkedMapOf(
                "cancelled" to event.isCancelled,
                "correction_candidate" to correctionCandidate,
                "cause" to event.cause.name,
                "from" to locationMap(event.from),
                "to" to locationMap(event.to),
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onVelocity(event: PlayerVelocityEvent) {
        emit(
            player = event.player,
            source = "server_event",
            eventType = "velocity",
            category = CaptureCategory.PLAYER_STATE,
            action = "SET_VELOCITY",
            payload = linkedMapOf(
                "cancelled" to event.isCancelled,
                "velocity" to vectorMap(event.velocity),
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEffect(event: EntityPotionEffectEvent) {
        val player = event.entity as? Player ?: return
        val effect = event.newEffect ?: event.oldEffect
        emit(
            player = player,
            source = "server_event",
            eventType = "effect_change",
            category = CaptureCategory.PLAYER_STATE,
            action = event.action.name,
            payload = linkedMapOf(
                "cancelled" to event.isCancelled,
                "cause" to event.cause.name,
                "override" to event.isOverride,
                "effect" to effect?.let {
                    linkedMapOf(
                        "type" to it.type.key.toString(),
                        "amplifier" to it.amplifier,
                        "duration_ticks" to it.duration,
                        "ambient" to it.isAmbient,
                    )
                },
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onFlight(event: PlayerToggleFlightEvent) = emitToggle(
        event.player,
        "flight_change",
        event.isFlying,
        event.isCancelled,
    )

    @EventHandler(priority = EventPriority.MONITOR)
    fun onGlide(event: EntityToggleGlideEvent) {
        val player = event.entity as? Player ?: return
        emitToggle(player, "glide_change", event.isGliding, event.isCancelled)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSwim(event: EntityToggleSwimEvent) {
        val player = event.entity as? Player ?: return
        emitToggle(player, "swim_change", event.isSwimming, event.isCancelled)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? Player ?: return
        emit(
            player,
            "server_event",
            "vehicle_change",
            CaptureCategory.VEHICLE,
            "ENTER",
            event.vehicle,
            linkedMapOf("enabled" to !event.isCancelled, "cancelled" to event.isCancelled),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onVehicleExit(event: VehicleExitEvent) {
        val player = event.exited as? Player ?: return
        emit(
            player,
            "server_event",
            "vehicle_change",
            CaptureCategory.VEHICLE,
            "EXIT",
            event.vehicle,
            linkedMapOf("enabled" to false, "cancelled" to event.isCancelled),
        )
    }

    private fun scheduleHeartbeat(player: Player) {
        val uuid = player.uniqueId
        if (heartbeatTasks.containsKey(uuid)) return
        val task = player.scheduler.runAtFixedRate(
            plugin,
            { emitHeartbeat(player) },
            { heartbeatTasks.remove(uuid) },
            1L,
            config.heartbeatIntervalTicks,
        )
        if (task != null) heartbeatTasks[uuid] = task
    }

    private fun emitHeartbeat(player: Player) {
        if (!player.isOnline) return
        val location = player.location
        val server = plugin.server
        val effects = player.activePotionEffects
            .sortedBy { it.type.key.toString() }
            .map {
                linkedMapOf(
                    "type" to it.type.key.toString(),
                    "amplifier" to it.amplifier,
                    "duration_ticks" to it.duration,
                    "ambient" to it.isAmbient,
                )
            }
        val payload = linkedMapOf<String, Any?>(
            "position" to locationMap(location),
            "velocity" to vectorMap(player.velocity),
            "state" to linkedMapOf(
                "on_ground" to (player as Entity).isOnGround,
                "allow_flight" to player.allowFlight,
                "flying" to player.isFlying,
                "gliding" to player.isGliding,
                "swimming" to player.isSwimming,
                "climbing" to player.isClimbing,
                "riptiding" to player.isRiptiding,
                "inside_vehicle" to player.isInsideVehicle,
                "vehicle_type" to player.vehicle?.type?.name,
            ),
            "environment" to linkedMapOf(
                "in_water" to player.isInWater,
                "under_water" to player.isUnderWater,
                "in_lava" to player.isInLava,
                "in_powder_snow" to player.isInPowderedSnow,
                "collides_at_position" to player.collidesAt(location),
            ),
            "network" to linkedMapOf("ping_ms" to player.ping),
            "server" to linkedMapOf(
                "server_tick" to server.currentTick,
                "tps_1m" to (server.tps.firstOrNull() ?: 0.0),
                "mspt" to server.averageTickTime.coerceAtLeast(0.0),
            ),
            "attributes" to linkedMapOf(
                "movement_speed" to player.attribute("movement_speed"),
                "flying_speed" to player.attribute("flying_speed"),
                "attack_speed" to player.attribute("attack_speed"),
                "jump_strength" to player.attribute("jump_strength"),
                "gravity" to player.attribute("gravity"),
            ),
            "effects" to effects,
        )
        emit(
            player = player,
            source = "server_heartbeat",
            eventType = "player_heartbeat",
            category = CaptureCategory.PLAYER_STATE,
            action = "SNAPSHOT",
            payload = payload,
        )
    }

    private fun Player.attribute(key: String): Double? = attributeKeys(key)
        .firstNotNullOfOrNull { candidate ->
            Registry.ATTRIBUTE.get(NamespacedKey.minecraft(candidate))
                ?.let { getAttribute(it)?.value }
        }

    private fun emitToggle(
        player: Player,
        eventType: String,
        enabled: Boolean,
        cancelled: Boolean,
    ) {
        emit(
            player = player,
            source = "server_event",
            eventType = eventType,
            category = CaptureCategory.PLAYER_STATE,
            action = if (enabled) "ENABLE" else "DISABLE",
            payload = linkedMapOf("enabled" to enabled, "cancelled" to cancelled),
        )
    }

    private fun emit(
        player: Player,
        source: String,
        eventType: String,
        category: CaptureCategory,
        action: String? = null,
        target: Entity? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        capture.emitSemantic(
            playerUuid = player.uniqueId,
            source = source,
            eventType = eventType,
            category = category,
            action = action,
            targetEntityId = target?.entityId,
            targetEntityUuid = target?.uniqueId,
            targetEntityType = target?.type?.name,
            observedProtocol = protocolVersion(player),
            payload = payload,
        )
    }

    private fun protocolVersion(player: Player): Int? = runCatching {
        ProtocolLibrary.getProtocolManager().getProtocolVersion(player)
    }.getOrNull()?.takeIf { it >= 0 }

    private fun vectorMap(vector: Vector): Map<String, Double> = linkedMapOf(
        "x" to vector.x,
        "y" to vector.y,
        "z" to vector.z,
    )

    private fun locationMap(location: Location): Map<String, Any?> = linkedMapOf(
        "world" to location.world?.name,
        "x" to location.x,
        "y" to location.y,
        "z" to location.z,
        "yaw" to location.yaw,
        "pitch" to location.pitch,
    )
}

internal fun attributeKeys(key: String): List<String> = listOf(key, "generic.$key")
