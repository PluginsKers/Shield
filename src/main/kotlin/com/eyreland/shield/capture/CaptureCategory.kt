package com.eyreland.shield.capture

/** 稳定的业务语义类目。具体数据包由配置白名单选择，类目不再承担开关职责。 */
enum class CaptureCategory(val key: String) {
    MOVEMENT("movement"),
    COMBAT("combat"),
    INTERACTION("interaction"),
    INVENTORY("inventory"),
    PLAYER_STATE("player_state"),
    VEHICLE("vehicle"),
    NETWORK("network"),
}
