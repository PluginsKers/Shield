package com.eyreland.shield.capture

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.ProtocolManager
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.events.PacketEvent
import com.eyreland.shield.config.CaptureConfig
import com.eyreland.shield.label.LabelStore
import com.eyreland.shield.storage.SampleWriter
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * ProtocolLib 客户端数据包捕获。
 *
 * 监听器在 Netty 线程被调用，因此只做三件确定线程安全的事:读包内字段、查内存标记、非阻塞入队，
 * 绝不调用需要主线程的 Bukkit API（玩家世界名 best-effort 读取，失败留 null）。
 * 移动类按 [CaptureConfig.movementMinIntervalMs] 节流；战斗/物品/坐骑事件全量捕获。
 *
 * 字段读取按类型走 StructureModifier，索引越界或缺字段时 runCatching 兜底为 null，
 * 跨版本不因单个字段缺失而中断。
 */
class PacketCaptureService(
    private val plugin: Plugin,
    private val config: CaptureConfig,
    private val labels: LabelStore,
    private val writer: SampleWriter,
    private val logger: Logger,
) {
    private var protocolManager: ProtocolManager? = null
    private var adapter: PacketAdapter? = null

    private val categoryByType = HashMap<PacketType, CaptureCategory>()
    private val nameByType = HashMap<PacketType, String>()
    private val lastMovementMs = ConcurrentHashMap<UUID, Long>()
    private val afk = AfkTracker(config.afkTimeoutMs)

    @Volatile var enabled: Boolean = false
        private set

    fun start() {
        val pm = ProtocolLibrary.getProtocolManager()
        protocolManager = pm

        for ((type, name, category) in TRACKED) {
            if (type == null || !type.isSupported) continue
            if (!config.categoryEnabled(category)) continue
            categoryByType[type] = category
            nameByType[type] = name
        }

        val types = categoryByType.keys.toTypedArray()
        if (types.isEmpty()) {
            logger.warning("没有可用的数据包类型，捕获未启动")
            return
        }

        val ad = object : PacketAdapter(plugin, ListenerPriority.MONITOR, *types) {
            override fun onPacketReceiving(event: PacketEvent) = handle(event)
        }
        adapter = ad
        pm.addPacketListener(ad)
        enabled = config.enabled
        logger.info(
            "已注册 ${types.size} 种数据包监听（${categoryByType.values.toSortedSet().joinToString { it.key }}）" +
                "，采集${if (enabled) "已开启" else "已关闭"}",
        )
    }

    fun stop() {
        val pm = protocolManager
        val ad = adapter
        if (pm != null && ad != null) pm.removePacketListener(ad)
        adapter = null
        lastMovementMs.clear()
        afk.clear()
    }

    private fun handle(event: PacketEvent) {
        runCatching {
            if (!enabled) return
            val category = categoryByType[event.packetType] ?: return
            val player = event.player ?: return
            val name = player.name
            if (name.isNullOrEmpty()) return
            val uuid = player.uniqueId

            val now = System.currentTimeMillis()
            updateActivity(event.packet, category, event.packetType, uuid, now)
            if (afk.isAfk(uuid, now)) return

            if (category == CaptureCategory.MOVEMENT) {
                val last = lastMovementMs[uuid]
                if (last != null && now - last < config.movementMinIntervalMs) return
                lastMovementMs[uuid] = now
            }

            writer.enqueue(buildSample(event, category, uuid, name, now))
        }
    }

    /** 刷新挂机活动时间:移动类比对位移/转角，事件类直接计为人为动作。STEER_VEHICLE 是操控输入，非坐标。 */
    private fun updateActivity(packet: PacketContainer, category: CaptureCategory, type: PacketType, uuid: UUID, now: Long) {
        when (category) {
            CaptureCategory.MOVEMENT ->
                afk.onMovement(uuid, readDouble(packet, 0), readDouble(packet, 1), readDouble(packet, 2), readFloat(packet, 0), readFloat(packet, 1), now)
            CaptureCategory.VEHICLE ->
                if (type == PacketType.Play.Client.VEHICLE_MOVE) {
                    afk.onMovement(uuid, readDouble(packet, 0), readDouble(packet, 1), readDouble(packet, 2), readFloat(packet, 0), readFloat(packet, 1), now)
                } else {
                    afk.onAction(uuid, now)
                }
            CaptureCategory.COMBAT, CaptureCategory.ITEM -> afk.onAction(uuid, now)
        }
    }

    private fun buildSample(
        event: PacketEvent,
        category: CaptureCategory,
        uuid: UUID,
        name: String,
        now: Long,
    ): PacketSample {
        val packet = event.packet
        val type = event.packetType
        val typeName = nameByType[type] ?: type.toString()
        val world = runCatching { event.player?.world?.name }.getOrNull()

        var x: Double? = null
        var y: Double? = null
        var z: Double? = null
        var yaw: Float? = null
        var pitch: Float? = null
        var onGround: Boolean? = null
        var targetId: Int? = null
        var action: String? = null
        var hand: String? = null

        when (category) {
            CaptureCategory.MOVEMENT -> {
                x = readDouble(packet, 0); y = readDouble(packet, 1); z = readDouble(packet, 2)
                yaw = readFloat(packet, 0); pitch = readFloat(packet, 1)
                onGround = readBoolean(packet, 0)
            }
            CaptureCategory.COMBAT -> {
                // USE_ENTITY 的 integers[0] 是目标实体 id；ARM_ANIMATION 无目标，仅记挥手手别。
                targetId = readInt(packet, 0)
                hand = readHand(packet)
            }
            CaptureCategory.ITEM -> {
                hand = readHand(packet)
            }
            CaptureCategory.VEHICLE -> {
                if (type == PacketType.Play.Client.STEER_VEHICLE) {
                    // STEER_VEHICLE 的 floats 是侧向/前向输入，booleans 是跳跃/下马，不是坐标。
                    action = "side=${readFloat(packet, 0)};fwd=${readFloat(packet, 1)};" +
                        "jump=${readBoolean(packet, 0)};dismount=${readBoolean(packet, 1)}"
                } else {
                    x = readDouble(packet, 0); y = readDouble(packet, 1); z = readDouble(packet, 2)
                    yaw = readFloat(packet, 0); pitch = readFloat(packet, 1)
                }
            }
        }

        return PacketSample(
            recvAtMs = now,
            playerUuid = uuid,
            playerName = name,
            label = labels.labelOf(uuid),
            category = category,
            packetType = typeName,
            world = world,
            x = x, y = y, z = z,
            yaw = yaw, pitch = pitch,
            onGround = onGround,
            targetId = targetId,
            action = action,
            hand = hand,
        )
    }

    private fun readDouble(p: PacketContainer, i: Int): Double? =
        runCatching { if (p.doubles.size() > i) p.doubles.read(i) else null }.getOrNull()

    private fun readFloat(p: PacketContainer, i: Int): Float? =
        runCatching { if (p.float.size() > i) p.float.read(i) else null }.getOrNull()

    private fun readBoolean(p: PacketContainer, i: Int): Boolean? =
        runCatching { if (p.booleans.size() > i) p.booleans.read(i) else null }.getOrNull()

    private fun readInt(p: PacketContainer, i: Int): Int? =
        runCatching { if (p.integers.size() > i) p.integers.read(i) else null }.getOrNull()

    private fun readHand(p: PacketContainer): String? =
        runCatching { if (p.hands.size() > 0) p.hands.read(0)?.name else null }.getOrNull()

    private data class Tracked(val type: PacketType?, val name: String, val category: CaptureCategory)

    companion object {
        private val TRACKED: List<Tracked> = listOf(
            Tracked(PacketType.Play.Client.POSITION, "POSITION", CaptureCategory.MOVEMENT),
            Tracked(PacketType.Play.Client.POSITION_LOOK, "POSITION_LOOK", CaptureCategory.MOVEMENT),
            Tracked(PacketType.Play.Client.LOOK, "LOOK", CaptureCategory.MOVEMENT),
            Tracked(PacketType.Play.Client.USE_ENTITY, "USE_ENTITY", CaptureCategory.COMBAT),
            Tracked(PacketType.Play.Client.ARM_ANIMATION, "ARM_ANIMATION", CaptureCategory.COMBAT),
            Tracked(PacketType.Play.Client.USE_ITEM, "USE_ITEM", CaptureCategory.ITEM),
            Tracked(PacketType.Play.Client.BLOCK_PLACE, "BLOCK_PLACE", CaptureCategory.ITEM),
            Tracked(PacketType.Play.Client.BLOCK_DIG, "BLOCK_DIG", CaptureCategory.ITEM),
            Tracked(PacketType.Play.Client.STEER_VEHICLE, "STEER_VEHICLE", CaptureCategory.VEHICLE),
            Tracked(PacketType.Play.Client.VEHICLE_MOVE, "VEHICLE_MOVE", CaptureCategory.VEHICLE),
        )
    }
}
