package com.eyreland.shield.config

/** 固定采集协议；升级插件时更新，用户配置不参与采样。 */
class CaptureConfig(val serverId: String, val proxyMode: String) {
    val afkTimeoutMs = 180_000L
    val heartbeatIntervalTicks = 100L
    val telemetryIntervalTicks = 100L
    val packets = setOf(
        "POSITION",
        "POSITION_LOOK",
        "LOOK",
        "GROUND",
        "ATTACK",
        "USE_ENTITY",
        "ARM_ANIMATION",
        "PUNCH",
        "USE_ITEM",
        "USE_ITEM_ON",
        "BLOCK_DIG",
        "ENTITY_ACTION",
        "ABILITIES",
        "STEER_VEHICLE",
        "VEHICLE_MOVE",
        "BOAT_MOVE",
        "HELD_ITEM_SLOT",
        "WINDOW_CLICK",
    )

    fun allowsGameMode(gameMode: String?): Boolean = !gameMode.equals("SPECTATOR", ignoreCase = true)
}
