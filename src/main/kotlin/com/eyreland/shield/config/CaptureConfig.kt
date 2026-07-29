package com.eyreland.shield.config

import org.bukkit.configuration.ConfigurationSection
import java.util.UUID

/** 单个显式白名单数据包的采集规则。 */
data class PacketRule(
    val minIntervalMs: Long = 0,
    val rateLimitGroup: String,
    val includeActions: Set<String> = emptySet(),
    val excludeActions: Set<String> = emptySet(),
    val includeRawFields: Boolean = false,
) {
    fun allowsAction(action: String?): Boolean {
        val normalized = action?.uppercase()
        if (includeActions.isNotEmpty() && (normalized == null || normalized !in includeActions)) return false
        return normalized == null || normalized !in excludeActions
    }
}

/** 网络线程执行过滤所需的纯数据，不持有 Bukkit Player。 */
data class CaptureSubject(
    val uuid: UUID,
    val name: String,
    val world: String?,
    val gameMode: String?,
    val observedProtocol: Int?,
)

/**
 * 玩家维度过滤器。include 中每个非空维度都必须命中；exclude 中任一维度命中即拒绝。
 * 空集合表示该维度不限制，避免用隐式通配字符串表达规则。
 */
data class CaptureFilter(
    val includeUuids: Set<UUID> = emptySet(),
    val includeNames: Set<String> = emptySet(),
    val includeWorlds: Set<String> = emptySet(),
    val includeGameModes: Set<String> = emptySet(),
    val includeProtocols: Set<Int> = emptySet(),
    val excludeUuids: Set<UUID> = emptySet(),
    val excludeNames: Set<String> = emptySet(),
    val excludeWorlds: Set<String> = emptySet(),
    val excludeGameModes: Set<String> = emptySet(),
    val excludeProtocols: Set<Int> = emptySet(),
) {
    fun allows(subject: CaptureSubject): Boolean {
        val name = subject.name.lowercase()
        val world = subject.world?.lowercase()
        val gameMode = subject.gameMode?.uppercase()
        if (includeUuids.isNotEmpty() && subject.uuid !in includeUuids) return false
        if (includeNames.isNotEmpty() && name !in includeNames) return false
        if (includeWorlds.isNotEmpty() && (world == null || world !in includeWorlds)) return false
        if (includeGameModes.isNotEmpty() && (gameMode == null || gameMode !in includeGameModes)) return false
        if (includeProtocols.isNotEmpty() && (subject.observedProtocol == null || subject.observedProtocol !in includeProtocols)) return false

        if (subject.uuid in excludeUuids) return false
        if (name in excludeNames) return false
        if (world != null && world in excludeWorlds) return false
        if (gameMode != null && gameMode in excludeGameModes) return false
        if (subject.observedProtocol != null && subject.observedProtocol in excludeProtocols) return false
        return true
    }
}

/**
 * 采集策略。数据包采用显式白名单：只有 [packets] 中出现且值为 true/规则段的名称才会发送。
 * 新增到代码目录的数据包不会在升级后自动开启。
 */
data class CaptureConfig(
    val enabled: Boolean,
    val afkTimeoutMs: Long,
    val serverId: String,
    val proxyMode: String,
    val filter: CaptureFilter,
    val packets: Map<String, PacketRule>,
    val usedLegacyPacketDefaults: Boolean,
) {
    companion object {
        fun from(c: ConfigurationSection?): CaptureConfig {
            val enabled = c?.getBoolean("enabled", true) ?: false
            val afkTimeout = (c?.getLong("afk-timeout-ms", 180_000L) ?: 180_000L).coerceAtLeast(0L)
            val serverId = (c?.getString("server-id", "default") ?: "default").trim()
            require(serverId.isNotEmpty()) { "capture.server-id 不能为空" }
            val proxyMode = (c?.getString("proxy-mode", "velocity") ?: "velocity").trim().lowercase()
            require(proxyMode.isNotEmpty()) { "capture.proxy-mode 不能为空" }

            val packetSection = c?.getConfigurationSection("packets")
            val usedLegacyDefaults = packetSection == null
            val packets = packetSection?.let(::parsePackets) ?: defaultPacketRules()
            val filter = parseFilter(c?.getConfigurationSection("filters"))
            return CaptureConfig(enabled, afkTimeout, serverId, proxyMode, filter, packets, usedLegacyDefaults)
        }

        private fun parsePackets(section: ConfigurationSection): Map<String, PacketRule> {
            val result = linkedMapOf<String, PacketRule>()
            for (rawName in section.getKeys(false)) {
                val name = rawName.trim().uppercase()
                require(name.isNotEmpty()) { "capture.packets 包含空名称" }
                require(name !in result) { "capture.packets 包含大小写重复名称: $rawName" }
                val rawRule = section.get(rawName)
                if (rawRule is Boolean) {
                    if (rawRule) result[name] = PacketRule(rateLimitGroup = name.lowercase())
                    continue
                }
                val ruleSection = rawRule as? ConfigurationSection
                    ?: throw IllegalArgumentException("capture.packets.$rawName 必须是 true、false 或规则段")

                if (!ruleSection.getBoolean("enabled", true)) continue
                val minInterval = ruleSection.getLong("min-interval-ms", 0L).coerceAtLeast(0L)
                val group = (ruleSection.getString("rate-limit-group", name.lowercase()) ?: name.lowercase()).trim().lowercase()
                require(group.isNotEmpty()) { "capture.packets.$rawName.rate-limit-group 不能为空" }
                val includeActions = ruleSection.getStringList("include-actions").map { it.uppercase() }.toSet()
                val excludeActions = ruleSection.getStringList("exclude-actions").map { it.uppercase() }.toSet()
                result[name] = PacketRule(
                    minIntervalMs = minInterval,
                    rateLimitGroup = group,
                    includeActions = includeActions,
                    excludeActions = excludeActions,
                    includeRawFields = ruleSection.getBoolean("raw-fields", false),
                )
            }
            return result
        }

        private fun parseFilter(section: ConfigurationSection?): CaptureFilter {
            val include = section?.getConfigurationSection("include")
            val exclude = section?.getConfigurationSection("exclude")
            return CaptureFilter(
                includeUuids = uuidSet(include, "uuids"),
                includeNames = stringSet(include, "names") { it.lowercase() },
                includeWorlds = stringSet(include, "worlds") { it.lowercase() },
                includeGameModes = stringSet(include, "game-modes") { it.uppercase() },
                includeProtocols = intSet(include, "protocols"),
                excludeUuids = uuidSet(exclude, "uuids"),
                excludeNames = stringSet(exclude, "names") { it.lowercase() },
                excludeWorlds = stringSet(exclude, "worlds") { it.lowercase() },
                excludeGameModes = stringSet(exclude, "game-modes") { it.uppercase() },
                excludeProtocols = intSet(exclude, "protocols"),
            )
        }

        private fun uuidSet(section: ConfigurationSection?, path: String): Set<UUID> =
            stringSet(section, path) { value ->
                runCatching { UUID.fromString(value) }
                    .getOrElse { throw IllegalArgumentException("$path 包含无效 UUID: $value") }
            }

        private fun <T> stringSet(section: ConfigurationSection?, path: String, map: (String) -> T): Set<T> =
            section?.getStringList(path)
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.map(map)
                ?.toSet()
                ?: emptySet()

        private fun intSet(section: ConfigurationSection?, path: String): Set<Int> =
            section?.getList(path)
                ?.map { value ->
                    when (value) {
                        is Number -> value.toInt()
                        else -> value.toString().toIntOrNull()
                            ?: throw IllegalArgumentException("$path 包含无效协议号: $value")
                    }
                }
                ?.toSet()
                ?: emptySet()

        /** 仅供尚未更新旧配置的安装使用；集合固定，不会随目录扩展而自动扩大。 */
        private fun defaultPacketRules(): Map<String, PacketRule> = linkedMapOf(
            "POSITION" to movementRule(),
            "POSITION_LOOK" to movementRule(),
            "LOOK" to movementRule(),
            "GROUND" to movementRule(),
            "ATTACK" to PacketRule(rateLimitGroup = "attack"),
            "USE_ENTITY" to PacketRule(rateLimitGroup = "use_entity", includeRawFields = true),
            "ARM_ANIMATION" to PacketRule(rateLimitGroup = "arm_animation"),
            "USE_ITEM" to PacketRule(rateLimitGroup = "use_item"),
            "USE_ITEM_ON" to PacketRule(rateLimitGroup = "use_item_on"),
            "BLOCK_DIG" to PacketRule(rateLimitGroup = "block_dig"),
            "ENTITY_ACTION" to PacketRule(rateLimitGroup = "entity_action"),
            "ABILITIES" to PacketRule(rateLimitGroup = "abilities"),
            "STEER_VEHICLE" to PacketRule(rateLimitGroup = "vehicle_input", includeRawFields = true),
            "VEHICLE_MOVE" to PacketRule(minIntervalMs = 50, rateLimitGroup = "vehicle_move"),
            "BOAT_MOVE" to PacketRule(rateLimitGroup = "boat_input"),
            "HELD_ITEM_SLOT" to PacketRule(rateLimitGroup = "held_item_slot"),
            "WINDOW_CLICK" to PacketRule(rateLimitGroup = "window_click", includeRawFields = true),
        )

        private fun movementRule() = PacketRule(minIntervalMs = 50, rateLimitGroup = "movement")
    }
}
