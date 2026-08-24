package com.eyreland.shield.capture

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.ProtocolManager
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import com.eyreland.shield.config.CaptureConfig
import com.eyreland.shield.config.CaptureSubject
import com.eyreland.shield.config.PacketRule
import com.eyreland.shield.inference.EventSink
import com.eyreland.shield.inference.CollectionTelemetry
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ProtocolLib 入站采集管线：运行时类型解析 -> 稳定语义解码 -> 上下文/过滤 -> 节流 -> 派生特征 -> 非阻塞入队。
 *
 * 网络线程不会读取 Player.world/location/gameMode，也不会做数据库 I/O。PacketType 通过当前运行时的
 * Play.Client 枚举按名称解析，不直接引用可能弃用或仅存在于某代 Minecraft 的静态常量。
 */
class PacketCaptureService(
    private val plugin: Plugin,
    private val config: CaptureConfig,
    private val contexts: PlayerContextStore,
    private val sink: EventSink,
    private val logger: Logger,
) : Listener {
    data class Stats(
        val received: Long,
        val selected: Long,
        val enqueued: Long,
        val playerFiltered: Long,
        val actionFiltered: Long,
        val afkFiltered: Long,
        val throttled: Long,
        val decodeFailed: Long,
        val missingContext: Long,
        val onlinePlayers: Int,
        val eligiblePlayers: Int,
        val capturedPlayers: Int,
        val unsupportedPackets: Int,
    )

    private var protocolManager: ProtocolManager? = null
    private var adapter: PacketAdapter? = null
    private var telemetryTask: ScheduledTask? = null
    private var serverVersion: String = "unknown"
    private val processStartedNanos = System.nanoTime()

    private val definitionByType = HashMap<PacketType, PacketDefinition>()
    private val ruleByType = HashMap<PacketType, PacketRule>()
    private val selectedTypes = HashSet<PacketType>()
    private val fallbackContexts = ConcurrentHashMap<UUID, CaptureContext>()
    private val observedProtocols = ConcurrentHashMap<UUID, Int>()
    private val activeSessions = ConcurrentHashMap<UUID, UUID>()
    private val capturedPlayers = CapturedPlayerIntervals()
    private val state = CaptureStateStore()
    private val rateLimiter = CaptureRateLimiter()
    private val afk = AfkTracker(config.afkTimeoutMs)

    private val received = AtomicLong()
    private val selected = AtomicLong()
    private val enqueued = AtomicLong()
    private val playerFiltered = AtomicLong()
    private val actionFiltered = AtomicLong()
    private val afkFiltered = AtomicLong()
    private val throttled = AtomicLong()
    private val decodeFailed = AtomicLong()
    private val missingContext = AtomicLong()
    @Volatile var enabled: Boolean = false
        private set

    @Volatile var activePacketNames: Set<String> = emptySet()
        private set

    @Volatile var unsupportedPacketNames: Set<String> = emptySet()
        private set

    fun start() {
        if (!config.enabled) {
            logger.info("采集由 capture.enabled 显式关闭")
            return
        }

        val pm = ProtocolLibrary.getProtocolManager()
        protocolManager = pm
        serverVersion = runCatching { pm.minecraftVersion.version }.getOrDefault("unknown")

        val runtimeTypes = PacketType.Play.Client.getInstance().values()
            .filter { runCatching { it.isSupported }.getOrDefault(false) }
            .associateBy { it.name().uppercase() }

        val unsupported = linkedSetOf<String>()
        val active = linkedSetOf<String>()
        for ((name, rule) in config.packets) {
            val definition = PacketCatalog.definitions[name]
            val type = runtimeTypes[name]
            if (definition == null || type == null) {
                unsupported += name
                continue
            }
            definitionByType[type] = definition
            ruleByType[type] = rule
            selectedTypes += type
            active += name
        }

        val observerNames = linkedSetOf<String>().apply {
            addAll(PacketCatalog.movementObserverNames)
            if (afk.enabled) addAll(PacketCatalog.activityObserverNames)
        }
        for (name in observerNames) {
            val definition = PacketCatalog.definitions[name] ?: continue
            val type = runtimeTypes[name] ?: continue
            definitionByType.putIfAbsent(type, definition)
        }

        activePacketNames = active
        unsupportedPacketNames = unsupported
        if (unsupported.isNotEmpty()) {
            logger.warning("当前 ProtocolLib/Minecraft 不支持或 Shield 未识别的显式数据包: ${unsupported.joinToString()}")
        }
        if (selectedTypes.isEmpty()) {
            logger.warning("显式数据包白名单为空或均不受支持，采集未启动")
            return
        }

        val listenedTypes = definitionByType.keys.toTypedArray()
        val packetAdapter = object : PacketAdapter(plugin, ListenerPriority.MONITOR, *listenedTypes) {
            override fun onPacketReceiving(event: PacketEvent) = handle(event)
        }
        adapter = packetAdapter
        pm.addPacketListener(packetAdapter)
        plugin.server.pluginManager.registerEvents(this, plugin)
        enabled = true
        telemetryTask = plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { emitCollectionTelemetry() },
            config.telemetryIntervalTicks,
            config.telemetryIntervalTicks,
        )

        val categories = active.mapNotNull { PacketCatalog.definitions[it]?.category?.key }.toSortedSet()
        logger.info(
            "已启用 ${active.size} 个显式采集包、${listenedTypes.size - selectedTypes.size} 个仅观察包" +
                "（${categories.joinToString()}，server=$serverVersion，proxy=${config.proxyMode}）",
        )
    }

    fun stop() {
        enabled = false
        telemetryTask?.cancel()
        telemetryTask = null
        val pm = protocolManager
        val currentAdapter = adapter
        if (pm != null && currentAdapter != null) pm.removePacketListener(currentAdapter)
        HandlerList.unregisterAll(this)
        adapter = null
        protocolManager = null
        definitionByType.clear()
        ruleByType.clear()
        selectedTypes.clear()
        fallbackContexts.clear()
        observedProtocols.clear()
        activeSessions.values.toSet().forEach(sink::closeSession)
        activeSessions.clear()
        capturedPlayers.clear()
        state.clear()
        rateLimiter.clear()
        afk.clear()
    }

    fun stats(): Stats {
        val online = contexts.snapshot()
        val eligible = online.filter(::isEligible)
        return Stats(
            received = received.get(),
            selected = selected.get(),
            enqueued = enqueued.get(),
            playerFiltered = playerFiltered.get(),
            actionFiltered = actionFiltered.get(),
            afkFiltered = afkFiltered.get(),
            throttled = throttled.get(),
            decodeFailed = decodeFailed.get(),
            missingContext = missingContext.get(),
            onlinePlayers = online.size,
            eligiblePlayers = eligible.size,
            capturedPlayers = eligible.count { capturedPlayers.contains(it.playerUuid) },
            unsupportedPackets = unsupportedPacketNames.size,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        fallbackContexts.remove(uuid)
        observedProtocols.remove(uuid)
        activeSessions.remove(uuid)?.let(sink::closeSession)
        state.remove(uuid)
        rateLimiter.remove(uuid)
        afk.remove(uuid)
    }

    private fun handle(event: PacketEvent) {
        if (!enabled) return
        received.incrementAndGet()
        val definition = definitionByType[event.packetType] ?: return
        val player = event.player ?: return
        val uuid = runCatching { player.uniqueId }.getOrNull() ?: return
        val name = runCatching { player.name }.getOrNull()?.takeIf(String::isNotEmpty) ?: return
        val context = contexts.get(uuid) ?: fallbackContext(uuid, name)
        activeSessions[uuid] = context.sessionId
        val rule = ruleByType[event.packetType]
        val isSelected = event.packetType in selectedTypes && rule != null
        val nowMs = System.currentTimeMillis()
        val nowNanos = System.nanoTime()

        // 未选中的动作包只承担挂机活动信号，不为此执行完整语义解码或原始字段反射。
        if (!isSelected && definition.activity == ActivitySignal.ACTION) {
            afk.onAction(uuid, nowMs)
            return
        }

        val decoded = try {
            PacketCatalog.decode(definition, event.packet)
        } catch (t: Throwable) {
            val failures = decodeFailed.incrementAndGet()
            if (failures <= 3 || failures % 1_000L == 0L) {
                logger.log(Level.WARNING, "解码 ${definition.name} 失败（累计 $failures）", t)
            }
            return
        }

        state.observe(context, decoded.movement)
        updateActivity(definition, decoded, uuid, nowMs)

        if (!isSelected) return
        val selectedRule = checkNotNull(rule)
        selected.incrementAndGet()

        val observedProtocol = observedProtocols[uuid] ?: protocolManager
            ?.let { runCatching { it.getProtocolVersion(player) }.getOrNull() }
            ?.takeIf { it >= 0 }
            ?.also { observedProtocols.putIfAbsent(uuid, it) }
        val subject = CaptureSubject(uuid, context.playerName, context.world, context.gameMode, observedProtocol)
        if (!config.filter.allows(subject)) {
            playerFiltered.incrementAndGet()
            return
        }
        if (afk.isAfk(uuid, nowMs)) {
            afkFiltered.incrementAndGet()
            return
        }
        if (!selectedRule.allowsAction(decoded.action)) {
            actionFiltered.incrementAndGet()
            return
        }
        if (!rateLimiter.allow(uuid, selectedRule, nowNanos)) {
            throttled.incrementAndGet()
            return
        }

        val derived = state.sample(context, nowMs, nowNanos)
        val payload = LinkedHashMap<String, Any?>(decoded.payload).apply {
            if (event.isCancelled) put("cancelled", true)
            if (event.isAsync) put("protocol_event_async", true)
        }
        val sample = PacketSample(
            capturedAtMs = nowMs,
            sessionId = context.sessionId,
            sessionSequence = derived.sessionSequence,
            sessionAgeMs = derived.sessionAgeMs,
            sincePreviousUs = derived.sincePreviousUs,
            sessionMonotonicUs = derived.sessionMonotonicUs,
            serverId = config.serverId,
            proxyMode = config.proxyMode,
            playerUuid = uuid,
            playerName = context.playerName,
            label = PacketSample.UNLABELED,
            world = context.world,
            gameMode = context.gameMode,
            observedProtocol = observedProtocol,
            serverVersion = serverVersion,
            category = definition.category,
            packetType = definition.name,
            source = "client_packet",
            eventType = clientEventType(definition, decoded),
            action = decoded.action,
            hand = decoded.hand,
            targetEntityId = decoded.targetEntityId,
            targetEntityUuid = null,
            targetEntityType = null,
            x = derived.x,
            y = derived.y,
            z = derived.z,
            yaw = derived.yaw,
            pitch = derived.pitch,
            onGround = derived.onGround,
            horizontalCollision = derived.horizontalCollision,
            deltaX = derived.deltaX,
            deltaY = derived.deltaY,
            deltaZ = derived.deltaZ,
            deltaYaw = derived.deltaYaw,
            deltaPitch = derived.deltaPitch,
            distance = derived.distance,
            horizontalDistance = derived.horizontalDistance,
            speedBps = derived.speedBps,
            horizontalSpeedBps = derived.horizontalSpeedBps,
            movementChanged = derived.movementChanged,
            positionUpdated = decoded.movement?.let {
                it.x != null || it.y != null || it.z != null
            } == true,
            rotationUpdated = decoded.movement?.let {
                it.yaw != null || it.pitch != null
            } == true,
            groundUpdated = decoded.movement?.let {
                it.onGround != null || it.horizontalCollision != null
            } == true,
            payload = payload,
        )
        if (sink.enqueue(sample)) {
            enqueued.incrementAndGet()
            capturedPlayers.mark(uuid)
        }
    }

    fun emitSemantic(
        playerUuid: UUID,
        source: String,
        eventType: String,
        category: CaptureCategory,
        action: String? = null,
        targetEntityId: Int? = null,
        targetEntityUuid: UUID? = null,
        targetEntityType: String? = null,
        observedProtocol: Int? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): Boolean {
        if (!enabled) return false
        val context = contexts.get(playerUuid) ?: run {
            missingContext.incrementAndGet()
            return false
        }
        if (observedProtocol != null) observedProtocols.putIfAbsent(playerUuid, observedProtocol)
        val protocol = observedProtocol ?: observedProtocols[playerUuid]
        val subject = CaptureSubject(
            playerUuid,
            context.playerName,
            context.world,
            context.gameMode,
            protocol,
        )
        if (!config.filter.allows(subject)) {
            playerFiltered.incrementAndGet()
            return false
        }

        val nowMs = System.currentTimeMillis()
        val derived = state.sample(context, nowMs, System.nanoTime())
        activeSessions[playerUuid] = context.sessionId
        val sample = PacketSample(
            capturedAtMs = nowMs,
            sessionId = context.sessionId,
            sessionSequence = derived.sessionSequence,
            sessionAgeMs = derived.sessionAgeMs,
            sincePreviousUs = derived.sincePreviousUs,
            sessionMonotonicUs = derived.sessionMonotonicUs,
            serverId = config.serverId,
            proxyMode = config.proxyMode,
            playerUuid = playerUuid,
            playerName = context.playerName,
            label = PacketSample.UNLABELED,
            world = context.world,
            gameMode = context.gameMode,
            observedProtocol = protocol,
            serverVersion = serverVersion,
            category = category,
            packetType = eventType.uppercase(),
            source = source,
            eventType = eventType,
            action = action,
            hand = null,
            targetEntityId = targetEntityId,
            targetEntityUuid = targetEntityUuid,
            targetEntityType = targetEntityType,
            x = derived.x,
            y = derived.y,
            z = derived.z,
            yaw = derived.yaw,
            pitch = derived.pitch,
            onGround = derived.onGround,
            horizontalCollision = derived.horizontalCollision,
            deltaX = derived.deltaX,
            deltaY = derived.deltaY,
            deltaZ = derived.deltaZ,
            deltaYaw = derived.deltaYaw,
            deltaPitch = derived.deltaPitch,
            distance = derived.distance,
            horizontalDistance = derived.horizontalDistance,
            speedBps = derived.speedBps,
            horizontalSpeedBps = derived.horizontalSpeedBps,
            movementChanged = derived.movementChanged,
            positionUpdated = false,
            rotationUpdated = false,
            groundUpdated = false,
            payload = LinkedHashMap(payload),
        )
        val accepted = sink.enqueue(sample)
        if (accepted) {
            enqueued.incrementAndGet()
            capturedPlayers.mark(playerUuid)
        }
        return accepted
    }

    private fun updateActivity(
        definition: PacketDefinition,
        decoded: DecodedPacket,
        uuid: UUID,
        nowMs: Long,
    ) {
        when (definition.activity) {
            ActivitySignal.MOVEMENT -> decoded.movement?.let {
                afk.onMovement(uuid, it.x, it.y, it.z, it.yaw, it.pitch, nowMs)
            }
            ActivitySignal.ACTION -> afk.onAction(uuid, nowMs)
            ActivitySignal.NONE -> Unit
        }
    }

    private fun isEligible(context: CaptureContext): Boolean {
        return config.filter.allows(
            CaptureSubject(
                context.playerUuid,
                context.playerName,
                context.world,
                context.gameMode,
                observedProtocols[context.playerUuid],
            ),
        )
    }

    private fun emitCollectionTelemetry() {
        if (!enabled) return
        val processUptimeNanos = (System.nanoTime() - processStartedNanos).coerceAtLeast(0L)
        val online = contexts.snapshot().sortedBy { it.playerUuid.toString() }
        val eligible = online.filter(::isEligible)
        val capturedInInterval = capturedPlayers.drain()
        val sinkStats = sink.stats()
        sink.enqueueTelemetry(
            CollectionTelemetry(
                capturedAtMs = System.currentTimeMillis(),
                processUptimeMs = processUptimeNanos / 1_000_000L,
                serverTick = logicalTicksSinceStart(processUptimeNanos),
                tps1m = plugin.server.tps.firstOrNull() ?: 0.0,
                mspt = plugin.server.averageTickTime.coerceAtLeast(0.0),
                onlinePlayerUuids = online.map(CaptureContext::playerUuid),
                eligiblePlayerUuids = eligible.map(CaptureContext::playerUuid),
                capturedPlayerUuids = eligible
                    .map(CaptureContext::playerUuid)
                    .filter { it in capturedInInterval },
                playerFilteredEvents = playerFiltered.get(),
                actionFilteredEvents = actionFiltered.get(),
                afkFilteredEvents = afkFiltered.get(),
                throttledEvents = throttled.get(),
                decodeFailedEvents = decodeFailed.get(),
                queueDroppedRecords = sinkStats.queueDropped,
                spoolDroppedBatches = sinkStats.spoolDroppedBatches,
                spoolDroppedEvents = sinkStats.spoolDroppedEvents,
                unsupportedPacketNames = unsupportedPacketNames.sorted(),
            ),
        )
    }

    private fun clientEventType(
        definition: PacketDefinition,
        decoded: DecodedPacket,
    ): String = when {
        decoded.action == "ATTACK" -> "client_attack_packet"
        definition.category == CaptureCategory.MOVEMENT -> "movement"
        definition.category == CaptureCategory.VEHICLE && decoded.movement != null -> "vehicle_movement"
        decoded.action == "SWING" -> "swing"
        definition.name == "USE_ENTITY" -> "entity_interaction"
        definition.category == CaptureCategory.COMBAT -> "combat_action"
        definition.category == CaptureCategory.INTERACTION -> "interaction"
        definition.category == CaptureCategory.INVENTORY -> "inventory_action"
        definition.category == CaptureCategory.PLAYER_STATE -> "player_state_action"
        definition.category == CaptureCategory.VEHICLE -> "vehicle_input"
        else -> "network_signal"
    }

    private fun fallbackContext(uuid: UUID, name: String): CaptureContext =
        fallbackContexts.computeIfAbsent(uuid) {
            missingContext.incrementAndGet()
            val now = System.currentTimeMillis()
            CaptureContext(
                sessionId = UUID.randomUUID(),
                playerUuid = uuid,
                playerName = name,
                world = null,
                gameMode = null,
                joinedAtMs = now,
                initialX = null,
                initialY = null,
                initialZ = null,
                initialYaw = null,
                initialPitch = null,
            )
        }
}

/** Folia 全局调度器不处于 ticking region；采集证明使用进程内单调逻辑 tick。 */
internal fun logicalTicksSinceStart(processUptimeNanos: Long): Long =
    processUptimeNanos / 50_000_000L

internal class CapturedPlayerIntervals {
    private val active = AtomicReference(ConcurrentHashMap.newKeySet<UUID>())

    fun mark(playerUuid: UUID) {
        while (true) {
            val interval = active.get()
            interval += playerUuid
            if (active.get() === interval) return
        }
    }

    fun contains(playerUuid: UUID): Boolean = playerUuid in active.get()

    fun drain(): Set<UUID> = active.getAndSet(ConcurrentHashMap.newKeySet()).toSet()

    fun clear() = active.set(ConcurrentHashMap.newKeySet())
}
