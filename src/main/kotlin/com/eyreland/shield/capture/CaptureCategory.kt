package com.eyreland.shield.capture

/** 数据包归类，写入 shield_packet_samples.category，并作为 config 采集开关的键。 */
enum class CaptureCategory(val key: String) {
    MOVEMENT("movement"),
    COMBAT("combat"),
    ITEM("item"),
    VEHICLE("vehicle"),
}
