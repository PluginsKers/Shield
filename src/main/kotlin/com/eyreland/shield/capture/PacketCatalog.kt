package com.eyreland.shield.capture

import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.reflect.StructureModifier
import com.comphenix.protocol.wrappers.BlockPosition
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.lang.reflect.Array as ReflectArray
import java.util.Optional

enum class ActivitySignal { MOVEMENT, ACTION, NONE }

data class PacketDefinition(
    val name: String,
    val category: CaptureCategory,
    val activity: ActivitySignal,
)

data class DecodedPacket(
    val action: String? = null,
    val hand: String? = null,
    val targetEntityId: Int? = null,
    val movement: PartialMovement? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

/**
 * shield 支持的稳定语义目录。
 *
 * 目录只保存字符串名称，启动时再从当前 ProtocolLib 的 Play.Client 枚举解析 PacketType。
 * 因此 1.21.11 的 USE_ENTITY 合并包和 26.1 的 ATTACK/USE_ENTITY 拆分包可以同时声明，
 * 当前运行时不存在的名称只会被报告为 unsupported，不会触发类加载或弃用常量。
 */
object PacketCatalog {
    val definitions: Map<String, PacketDefinition> = listOf(
        definition("POSITION", CaptureCategory.MOVEMENT, ActivitySignal.MOVEMENT),
        definition("POSITION_LOOK", CaptureCategory.MOVEMENT, ActivitySignal.MOVEMENT),
        definition("LOOK", CaptureCategory.MOVEMENT, ActivitySignal.MOVEMENT),
        definition("GROUND", CaptureCategory.MOVEMENT, ActivitySignal.MOVEMENT),
        definition("VEHICLE_MOVE", CaptureCategory.VEHICLE, ActivitySignal.MOVEMENT),
        definition("STEER_VEHICLE", CaptureCategory.VEHICLE, ActivitySignal.ACTION),
        definition("BOAT_MOVE", CaptureCategory.VEHICLE, ActivitySignal.ACTION),
        definition("ATTACK", CaptureCategory.COMBAT, ActivitySignal.ACTION),
        definition("USE_ENTITY", CaptureCategory.COMBAT, ActivitySignal.ACTION),
        definition("ARM_ANIMATION", CaptureCategory.COMBAT, ActivitySignal.ACTION),
        definition("USE_ITEM", CaptureCategory.INTERACTION, ActivitySignal.ACTION),
        definition("USE_ITEM_ON", CaptureCategory.INTERACTION, ActivitySignal.ACTION),
        definition("BLOCK_DIG", CaptureCategory.INTERACTION, ActivitySignal.ACTION),
        definition("HELD_ITEM_SLOT", CaptureCategory.INVENTORY, ActivitySignal.ACTION),
        definition("WINDOW_CLICK", CaptureCategory.INVENTORY, ActivitySignal.ACTION),
        definition("CLOSE_WINDOW", CaptureCategory.INVENTORY, ActivitySignal.ACTION),
        definition("SET_CREATIVE_SLOT", CaptureCategory.INVENTORY, ActivitySignal.ACTION),
        definition("ENCHANT_ITEM", CaptureCategory.INVENTORY, ActivitySignal.ACTION),
        definition("PICK_ITEM", CaptureCategory.INVENTORY, ActivitySignal.ACTION),
        definition("PICK_ITEM_FROM_BLOCK", CaptureCategory.INVENTORY, ActivitySignal.ACTION),
        definition("ENTITY_ACTION", CaptureCategory.PLAYER_STATE, ActivitySignal.ACTION),
        definition("ABILITIES", CaptureCategory.PLAYER_STATE, ActivitySignal.ACTION),
        definition("CLIENT_COMMAND", CaptureCategory.PLAYER_STATE, ActivitySignal.ACTION),
        definition("TELEPORT_ACCEPT", CaptureCategory.PLAYER_STATE, ActivitySignal.NONE),
        definition("PLAYER_LOADED", CaptureCategory.PLAYER_STATE, ActivitySignal.ACTION),
        definition("CLIENT_TICK_END", CaptureCategory.PLAYER_STATE, ActivitySignal.NONE),
        definition("KEEP_ALIVE", CaptureCategory.NETWORK, ActivitySignal.NONE),
        definition("PONG", CaptureCategory.NETWORK, ActivitySignal.NONE),
    ).associateBy(PacketDefinition::name)

    val movementObserverNames: Set<String> = definitions.values
        .filter { it.activity == ActivitySignal.MOVEMENT }
        .mapTo(linkedSetOf(), PacketDefinition::name)

    val activityObserverNames: Set<String> = definitions.values
        .filter { it.activity != ActivitySignal.NONE }
        .mapTo(linkedSetOf(), PacketDefinition::name)

    fun decode(definition: PacketDefinition, packet: PacketContainer): DecodedPacket {
        val reader = PacketReader(packet)
        val decoded = when (definition.name) {
            "POSITION" -> movement(reader, position = true, rotation = false)
            "POSITION_LOOK" -> movement(reader, position = true, rotation = true)
            "LOOK" -> movement(reader, position = false, rotation = true)
            "GROUND" -> movement(reader, position = false, rotation = false)
            "VEHICLE_MOVE" -> vehicleMove(reader)
            "STEER_VEHICLE" -> vehicleInput(reader)
            "BOAT_MOVE" -> DecodedPacket(
                action = "PADDLE",
                payload = payloadOf("left" to reader.boolean(0), "right" to reader.boolean(1)),
            )
            "ATTACK" -> DecodedPacket(action = "ATTACK", targetEntityId = reader.int(0))
            "USE_ENTITY" -> useEntity(reader)
            "ARM_ANIMATION" -> DecodedPacket(action = "SWING", hand = reader.hand())
            "USE_ITEM" -> DecodedPacket(
                action = "USE_ITEM",
                hand = reader.hand(),
                payload = payloadOf(
                    "sequence" to reader.int(0),
                    "yaw" to reader.float(0),
                    "pitch" to reader.float(1),
                ),
            )
            "USE_ITEM_ON" -> useItemOn(reader)
            "BLOCK_DIG" -> blockDig(reader)
            "HELD_ITEM_SLOT" -> DecodedPacket(
                action = "SELECT_SLOT",
                payload = payloadOf("slot" to (reader.int(0) ?: reader.short(0)?.toInt())),
            )
            "WINDOW_CLICK" -> windowClick(reader)
            "CLOSE_WINDOW" -> DecodedPacket(
                action = "CLOSE_WINDOW",
                payload = payloadOf("container_id" to (reader.int(0) ?: reader.byte(0)?.toInt())),
            )
            "SET_CREATIVE_SLOT" -> DecodedPacket(
                action = "SET_CREATIVE_SLOT",
                payload = payloadOf(
                    "slot" to (reader.int(0) ?: reader.short(0)?.toInt()),
                    "item" to reader.itemSummary(),
                ),
            )
            "ENCHANT_ITEM" -> DecodedPacket(
                action = "ENCHANT_ITEM",
                payload = payloadOf(
                    "container_id" to (reader.int(0) ?: reader.byte(0)?.toInt()),
                    "enchantment" to (reader.int(1) ?: reader.byte(1)?.toInt()),
                ),
            )
            "PICK_ITEM", "PICK_ITEM_FROM_BLOCK" -> DecodedPacket(
                action = definition.name,
                payload = reader.semanticScalars(),
            )
            "ENTITY_ACTION" -> {
                val action = reader.playerAction() ?: reader.firstEnumName() ?: "ENTITY_ACTION"
                DecodedPacket(
                    action = action,
                    payload = payloadOf("entity_id" to reader.int(0), "data" to reader.int(1)),
                )
            }
            "ABILITIES" -> DecodedPacket(
                action = "ABILITIES",
                payload = payloadOf("flying" to reader.boolean(0)),
            )
            "CLIENT_COMMAND" -> DecodedPacket(
                action = reader.clientCommand() ?: reader.firstEnumName() ?: "CLIENT_COMMAND",
            )
            "TELEPORT_ACCEPT" -> DecodedPacket(
                action = "TELEPORT_ACCEPT",
                payload = payloadOf("teleport_id" to reader.int(0)),
            )
            "PLAYER_LOADED" -> DecodedPacket(action = "PLAYER_LOADED")
            "CLIENT_TICK_END" -> DecodedPacket(action = "CLIENT_TICK_END")
            "KEEP_ALIVE" -> DecodedPacket(
                action = "KEEP_ALIVE",
                payload = payloadOf("id" to reader.long(0)),
            )
            "PONG" -> DecodedPacket(
                action = "PONG",
                payload = payloadOf("id" to reader.int(0)),
            )
            else -> DecodedPacket(action = definition.name, payload = reader.semanticScalars())
        }

        return decoded
    }

    private fun movement(reader: PacketReader, position: Boolean, rotation: Boolean): DecodedPacket =
        DecodedPacket(
            movement = PartialMovement(
                x = if (position) reader.double(0) else null,
                y = if (position) reader.double(1) else null,
                z = if (position) reader.double(2) else null,
                yaw = if (rotation) reader.float(0) else null,
                pitch = if (rotation) reader.float(1) else null,
                onGround = reader.boolean(0),
                horizontalCollision = reader.boolean(1),
            ),
        )

    /** 现代 VEHICLE_MOVE 把坐标放在 Vec3 中；旧实现只读 doubles 会得到空值。 */
    private fun vehicleMove(reader: PacketReader): DecodedPacket {
        val vector = reader.vector(0)
        return DecodedPacket(
            movement = PartialMovement(
                x = vector?.x ?: reader.double(0),
                y = vector?.y ?: reader.double(1),
                z = vector?.z ?: reader.double(2),
                yaw = reader.float(0),
                pitch = reader.float(1),
                onGround = reader.boolean(0),
            ),
        )
    }

    /** 旧版是 float/boolean 扁平字段，1.21.11+ 是嵌套 Input record；两种形态同时保留稳定键。 */
    private fun vehicleInput(reader: PacketReader): DecodedPacket {
        val record = reader.firstRecordMap()
        val input = if (record.isNotEmpty()) {
            record
        } else {
            payloadOf(
                "sideways" to reader.float(0),
                "forward" to reader.float(1),
                "jump" to reader.boolean(0),
                "dismount" to reader.boolean(1),
            )
        }
        return DecodedPacket(action = "INPUT", payload = payloadOf("input" to input))
    }

    /** 1.21.11 通过 WrappedEnumEntityUseAction 解包；26.1 已把 ATTACK 拆包，USE_ENTITY 直接携带手和位置。 */
    private fun useEntity(reader: PacketReader): DecodedPacket {
        val targetId = reader.int(0)
        val wrapped = reader.entityUseAction()
        if (wrapped != null) {
            val action = runCatching { wrapped.action.name }.getOrNull()
            val hand = if (action == "ATTACK") null else runCatching { wrapped.hand?.name }.getOrNull()
            val position = if (action == "INTERACT_AT") runCatching { wrapped.position }.getOrNull() else null
            return DecodedPacket(
                action = action,
                hand = hand,
                targetEntityId = targetId,
                payload = payloadOf(
                    "interaction_position" to position?.let(::vectorMap),
                    "using_secondary_action" to reader.boolean(0),
                ),
            )
        }

        val position = reader.vector(0)
        return DecodedPacket(
            action = if (position == null) "INTERACT" else "INTERACT_AT",
            hand = reader.hand(),
            targetEntityId = targetId,
            payload = payloadOf(
                "interaction_position" to position?.let(::vectorMap),
                "using_secondary_action" to reader.boolean(0),
            ),
        )
    }

    private fun useItemOn(reader: PacketReader): DecodedPacket {
        val hit = reader.movingBlockPosition()
        return DecodedPacket(
            action = "USE_ITEM_ON",
            hand = reader.hand(),
            payload = payloadOf(
                "sequence" to reader.int(0),
                "block" to hit?.blockPosition?.let(::blockMap),
                "face" to hit?.direction?.name,
                "hit_position" to hit?.posVector?.let(::vectorMap),
                "inside_block" to hit?.isInsideBlock,
            ),
        )
    }

    private fun blockDig(reader: PacketReader): DecodedPacket = DecodedPacket(
        action = reader.playerDigType() ?: reader.firstEnumName() ?: "BLOCK_DIG",
        payload = payloadOf(
            "sequence" to reader.int(0),
            "block" to reader.blockPosition()?.let(::blockMap),
            "face" to reader.direction(),
        ),
    )

    private fun windowClick(reader: PacketReader): DecodedPacket {
        val clickType = reader.firstEnumName() ?: "CLICK"
        return DecodedPacket(
            action = clickType,
            payload = payloadOf(
                "container_id" to reader.int(0),
                "state_id" to reader.int(1),
                "slot" to reader.short(0)?.toInt(),
                "button" to reader.byte(0)?.toInt(),
                "click_type" to clickType,
                "changed_slots" to reader.firstMapSize(),
            ),
        )
    }

    private fun definition(name: String, category: CaptureCategory, activity: ActivitySignal) =
        PacketDefinition(name, category, activity)

    private fun payloadOf(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            for ((key, value) in pairs) if (value != null) put(key, value)
        }

    private fun blockMap(position: BlockPosition): Map<String, Any?> =
        linkedMapOf("x" to position.x, "y" to position.y, "z" to position.z)

    private fun vectorMap(vector: Vector): Map<String, Any?> =
        linkedMapOf("x" to vector.x, "y" to vector.y, "z" to vector.z)
}

/** 所有 ProtocolLib 读取都通过 readSafely + runCatching，单字段缺失不会中断整条包。 */
private class PacketReader(private val packet: PacketContainer) {
    fun byte(index: Int): Byte? = read(packet.bytes, index)
    fun short(index: Int): Short? = read(packet.shorts, index)
    fun int(index: Int): Int? = read(packet.integers, index)
    fun long(index: Int): Long? = read(packet.longs, index)
    fun float(index: Int): Float? = read(packet.float, index)
    fun double(index: Int): Double? = read(packet.doubles, index)
    fun boolean(index: Int): Boolean? = read(packet.booleans, index)
    fun vector(index: Int): Vector? = read(packet.vectors, index)
    fun hand(): String? = read(packet.hands, 0)?.name
    fun direction(): String? = read(packet.directions, 0)?.name
    fun blockPosition(): BlockPosition? = read(packet.blockPositionModifier, 0)
    fun movingBlockPosition(): MovingObjectPositionBlock? = read(packet.movingBlockPositions, 0)
    fun playerDigType(): String? = read(packet.playerDigTypes, 0)?.name
    fun playerAction(): String? = read(packet.playerActions, 0)?.name
    fun clientCommand(): String? = read(packet.clientCommands, 0)?.name
    fun entityUseAction() = runCatching { packet.enumEntityUseActions.readSafely(0) }.getOrNull()

    fun itemSummary(): Map<String, Any?>? {
        val item = read(packet.itemModifier, 0) ?: return null
        return linkedMapOf("type" to item.type.name, "amount" to item.amount)
    }

    fun firstEnumName(): String? = rawValues().firstNotNullOfOrNull { (it as? Enum<*>)?.name }

    fun firstRecordMap(): Map<String, Any?> = rawValues()
        .asSequence()
        .mapNotNull { value ->
            val normalized = normalize(value, 0) as? Map<*, *> ?: return@mapNotNull null
            normalized.entries
                .filter { it.key is String && it.key != "_type" }
                .associateTo(linkedMapOf()) { it.key as String to it.value }
                .takeIf(Map<*, *>::isNotEmpty)
        }
        .firstOrNull()
        ?: emptyMap()

    fun firstMapSize(): Int? = rawValues().firstNotNullOfOrNull { (it as? Map<*, *>)?.size }

    fun semanticScalars(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        readAll(packet.bytes).takeIf { it.isNotEmpty() }?.let { result["bytes"] = it }
        readAll(packet.shorts).takeIf { it.isNotEmpty() }?.let { result["shorts"] = it }
        readAll(packet.integers).takeIf { it.isNotEmpty() }?.let { result["integers"] = it }
        readAll(packet.longs).takeIf { it.isNotEmpty() }?.let { result["longs"] = it }
        readAll(packet.float).takeIf { it.isNotEmpty() }?.let { result["floats"] = it }
        readAll(packet.doubles).takeIf { it.isNotEmpty() }?.let { result["doubles"] = it }
        readAll(packet.booleans).takeIf { it.isNotEmpty() }?.let { result["booleans"] = it }
        blockPosition()?.let { result["block"] = linkedMapOf("x" to it.x, "y" to it.y, "z" to it.z) }
        direction()?.let { result["direction"] = it }
        hand()?.let { result["hand"] = it }
        firstEnumName()?.let { result["enum"] = it }
        return result
    }

    private fun rawValues(): List<Any?> {
        val modifier = packet.modifier
        val size = runCatching { modifier.size() }.getOrDefault(0)
        return (0 until size).mapNotNull { read(modifier, it) }
    }

    private fun <T> readAll(modifier: StructureModifier<T>): List<T> {
        val size = runCatching { modifier.size() }.getOrDefault(0)
        return (0 until size).mapNotNull { read(modifier, it) }
    }

    private fun <T> read(modifier: StructureModifier<T>, index: Int): T? =
        runCatching { modifier.readSafely(index) }.getOrNull()
}

/** 将 NMS 的简单字段/record 限深转成 JSON 可写值；未知复杂对象只记录类型，不调用不稳定的 toString。 */
private fun normalize(value: Any?, depth: Int): Any? {
    if (value == null || depth > 3) return null
    return when (value) {
        is String, is Boolean, is Number -> value
        is Char -> value.toString()
        is Enum<*> -> value.name
        is java.util.UUID -> value.toString()
        is Vector -> linkedMapOf("x" to value.x, "y" to value.y, "z" to value.z)
        is BlockPosition -> linkedMapOf("x" to value.x, "y" to value.y, "z" to value.z)
        is ItemStack -> linkedMapOf("type" to value.type.name, "amount" to value.amount)
        is Optional<*> -> normalize(value.orElse(null), depth + 1)
        is Map<*, *> -> {
            if (value.size > 64) return linkedMapOf("size" to value.size, "truncated" to true)
            val out = linkedMapOf<String, Any?>()
            for ((key, item) in value) {
                val normalized = normalize(item, depth + 1) ?: continue
                out[key.toString()] = normalized
            }
            if (out.isEmpty()) linkedMapOf("size" to value.size) else out
        }
        is Iterable<*> -> value.take(64).mapNotNull { normalize(it, depth + 1) }
        else -> normalizeObject(value, depth)
    }
}

private fun normalizeObject(value: Any, depth: Int): Any? {
    val type = value.javaClass
    if (type.isArray) {
        val size = ReflectArray.getLength(value).coerceAtMost(64)
        return (0 until size).mapNotNull { normalize(ReflectArray.get(value, it), depth + 1) }
    }
    if (type.isRecord) {
        val result = linkedMapOf<String, Any?>()
        for (component in type.recordComponents.take(32)) {
            val componentValue = runCatching { component.accessor.invoke(value) }.getOrNull()
            normalize(componentValue, depth + 1)?.let { result[component.name] = it }
        }
        if (result.isNotEmpty()) return result
    }
    return linkedMapOf("_type" to type.name)
}
