package com.eyreland.shield.config

import com.eyreland.shield.capture.CaptureCategory
import org.bukkit.configuration.ConfigurationSection

/**
 * 采集开关与密度配置。
 *
 * [movementMinIntervalMs] 限制每名玩家移动类样本的最小间隔，把数据密度控制在目标
 * （默认 50ms，对应客户端每 tick 一个移动包）。事件型数据包（战斗/物品/坐骑）不节流，全量捕获。
 * [afkTimeoutMs] 为玩家无实质移动/动作多久后停止采集，0 关闭该功能。
 */
data class CaptureConfig(
    val enabled: Boolean,
    val movementMinIntervalMs: Long,
    val afkTimeoutMs: Long,
    private val categories: Map<CaptureCategory, Boolean>,
) {
    fun categoryEnabled(category: CaptureCategory): Boolean = categories[category] ?: true

    companion object {
        fun from(c: ConfigurationSection?): CaptureConfig {
            val enabled = c?.getBoolean("enabled", true) ?: true
            val interval = (c?.getLong("movement-min-interval-ms", 50L) ?: 50L).coerceAtLeast(0L)
            val afkTimeout = (c?.getLong("afk-timeout-ms", 180000L) ?: 180000L).coerceAtLeast(0L)
            val cats = c?.getConfigurationSection("categories")
            val map = CaptureCategory.entries.associateWith { cat -> cats?.getBoolean(cat.key, true) ?: true }
            return CaptureConfig(enabled, interval, afkTimeout, map)
        }
    }
}
