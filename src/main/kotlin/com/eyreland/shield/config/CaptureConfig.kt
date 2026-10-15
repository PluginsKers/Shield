package com.eyreland.shield.config

/** 固定采集协议；升级插件时更新，用户配置不参与采样。 */
data class PacketRule(val minIntervalMs: Long = 0, val rateLimitGroup: String)

class CaptureConfig(val serverId: String, val proxyMode: String) {
    val afkTimeoutMs = 180_000L
    val heartbeatIntervalTicks = 100L
    val telemetryIntervalTicks = 100L
    val packets = defaultPacketRules()

    fun allowsGameMode(gameMode: String?): Boolean = !gameMode.equals("SPECTATOR", ignoreCase = true)

    companion object {
        private fun defaultPacketRules(): Map<String, PacketRule> = linkedMapOf(
            "POSITION" to movementRule(),
            "POSITION_LOOK" to movementRule(),
            "LOOK" to movementRule(),
            "GROUND" to movementRule(),
            "ATTACK" to PacketRule(rateLimitGroup = "attack"),
            "USE_ENTITY" to PacketRule(rateLimitGroup = "use_entity"),
            "ARM_ANIMATION" to PacketRule(rateLimitGroup = "arm_animation"),
            "USE_ITEM" to PacketRule(rateLimitGroup = "use_item"),
            "USE_ITEM_ON" to PacketRule(rateLimitGroup = "use_item_on"),
            "BLOCK_DIG" to PacketRule(rateLimitGroup = "block_dig"),
            "ENTITY_ACTION" to PacketRule(rateLimitGroup = "entity_action"),
            "ABILITIES" to PacketRule(rateLimitGroup = "abilities"),
            "STEER_VEHICLE" to PacketRule(rateLimitGroup = "vehicle_input"),
            "VEHICLE_MOVE" to PacketRule(minIntervalMs = 50, rateLimitGroup = "vehicle_move"),
            "BOAT_MOVE" to PacketRule(rateLimitGroup = "boat_input"),
            "HELD_ITEM_SLOT" to PacketRule(rateLimitGroup = "held_item_slot"),
            "WINDOW_CLICK" to PacketRule(rateLimitGroup = "window_click"),
        )

        private fun movementRule() = PacketRule(minIntervalMs = 50, rateLimitGroup = "movement")
    }
}
