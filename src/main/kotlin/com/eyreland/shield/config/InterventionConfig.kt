package com.eyreland.shield.config

import org.bukkit.configuration.ConfigurationSection

data class WarningConfig(
    val enabled: Boolean,
    val cooldownMs: Long,
    val prefix: String,
    val message: String,
)

data class KickConfig(
    val enabled: Boolean,
    val minimumPriorWarnings: Int,
    val cooldownMs: Long,
    val message: String,
)

/** 玩家干预只消费推理端短期指令；本地配置拥有最终执行权。 */
data class InterventionConfig(
    val enabled: Boolean,
    val ledgerRetentionMs: Long,
    val warning: WarningConfig,
    val kick: KickConfig,
) {
    companion object {
        fun from(section: ConfigurationSection?): InterventionConfig {
            val warning = section?.getConfigurationSection("warning")
            val kick = section?.getConfigurationSection("kick")
            return InterventionConfig(
                enabled = section?.getBoolean("enabled", true) ?: true,
                ledgerRetentionMs = hoursToMs(
                    longIn(section, "ledger-retention-hours", 168, 24, 720),
                ),
                warning = WarningConfig(
                    enabled = warning?.getBoolean("enabled", true) ?: true,
                    cooldownMs = secondsToMs(
                        longIn(warning, "cooldown-seconds", 1_800, 300, 86_400),
                    ),
                    prefix = textIn(warning, "prefix", "[!]", 1, 16),
                    message = textIn(
                        warning,
                        "message",
                        "你当前的行为存在异常",
                        1,
                        256,
                    ),
                ),
                kick = KickConfig(
                    enabled = kick?.getBoolean("enabled", false) ?: false,
                    minimumPriorWarnings = intIn(
                        kick,
                        "minimum-prior-warnings",
                        1,
                        1,
                        100,
                    ),
                    cooldownMs = secondsToMs(
                        longIn(kick, "cooldown-seconds", 3_600, 60, 604_800),
                    ),
                    message = textIn(
                        kick,
                        "message",
                        "你当前的行为持续异常",
                        1,
                        256,
                    ),
                ),
            )
        }

        private fun textIn(
            section: ConfigurationSection?,
            path: String,
            default: String,
            minimum: Int,
            maximum: Int,
        ): String {
            val value = (section?.getString(path, default) ?: default).trim()
            require(value.length in minimum..maximum) {
                "interventions.$path 长度必须在 $minimum..$maximum"
            }
            require(value.none { it.isISOControl() }) {
                "interventions.$path 不得包含控制字符"
            }
            return value
        }

        private fun intIn(
            section: ConfigurationSection?,
            path: String,
            default: Int,
            minimum: Int,
            maximum: Int,
        ): Int {
            val value = section?.getInt(path, default) ?: default
            require(value in minimum..maximum) {
                "interventions.$path 必须在 $minimum..$maximum"
            }
            return value
        }

        private fun longIn(
            section: ConfigurationSection?,
            path: String,
            default: Long,
            minimum: Long,
            maximum: Long,
        ): Long {
            val value = section?.getLong(path, default) ?: default
            require(value in minimum..maximum) {
                "interventions.$path 必须在 $minimum..$maximum"
            }
            return value
        }

        private fun secondsToMs(seconds: Long): Long = Math.multiplyExact(seconds, 1_000)

        private fun hoursToMs(hours: Long): Long = Math.multiplyExact(hours, 60L * 60 * 1_000)
    }
}
