package com.eyreland.shield.capture

/** 事件协议中的采集类别。 */
enum class CaptureCategory(val key: String) {
    MOVEMENT("movement"),
    COMBAT("combat"),
    INTERACTION("interaction"),
    INVENTORY("inventory"),
    PLAYER_STATE("player_state"),
    VEHICLE("vehicle"),
    NETWORK("network"),
}
