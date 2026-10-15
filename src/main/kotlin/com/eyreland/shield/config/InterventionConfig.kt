package com.eyreland.shield.config

import org.bukkit.configuration.ConfigurationSection

data class WarningConfig(
    val enabled: Boolean,
    val cooldownMs: Long,
    val message: String,
)

data class KickConfig(
    val enabled: Boolean,
    val minimumPriorWarnings: Int,
    val cooldownMs: Long,
    val message: String,
)

/** 本地执行边界；开启行动不会替代推理端授权。 */
data class InterventionConfig(
    val enabled: Boolean,
    val ledgerRetentionMs: Long,
    val warning: WarningConfig,
    val kick: KickConfig,
) {
    companion object {
        fun defaults() = InterventionConfig(
            enabled = true,
            ledgerRetentionMs = 168L * 60 * 60 * 1_000,
            warning = WarningConfig(true, 1_800_000, "行为异常"),
            kick = KickConfig(false, 1, 3_600_000, "异常行为持续发生"),
        )

        fun from(root: ConfigurationSection): InterventionConfig {
            val defaults = defaults()
            val actions = section(root, "interventions", setOf("enabled", "warning", "kick"))
                ?: return defaults
            val warning = section(actions, "warning", setOf("enabled", "cooldown-seconds", "message"))
            val kick = section(actions, "kick", setOf("enabled", "minimum-prior-warnings", "cooldown-seconds", "message"))
            return defaults.copy(
                enabled = boolean(actions, "enabled", defaults.enabled),
                warning = defaults.warning.copy(
                    enabled = boolean(warning, "enabled", defaults.warning.enabled),
                    cooldownMs = integer(warning, "cooldown-seconds", defaults.warning.cooldownMs / 1_000, 300L..86_400L) * 1_000,
                    message = message(warning, defaults.warning.message),
                ),
                kick = defaults.kick.copy(
                    enabled = boolean(kick, "enabled", defaults.kick.enabled),
                    minimumPriorWarnings = integer(kick, "minimum-prior-warnings", defaults.kick.minimumPriorWarnings.toLong(), 1L..100L).toInt(),
                    cooldownMs = integer(kick, "cooldown-seconds", defaults.kick.cooldownMs / 1_000, 60L..604_800L) * 1_000,
                    message = message(kick, defaults.kick.message),
                ),
            )
        }

        private fun section(root: ConfigurationSection, key: String, keys: Set<String>): ConfigurationSection? {
            val value = root.get(key) ?: return null
            require(value is ConfigurationSection) { "${root.currentPath}.$key 必须是配置节" }
            require(value.getKeys(false).all { it in keys }) { "${value.currentPath} 包含不支持的配置项" }
            return value
        }

        private fun boolean(section: ConfigurationSection?, key: String, default: Boolean): Boolean {
            val value = section?.get(key) ?: return default
            require(value is Boolean) { "${section.currentPath}.$key 必须是布尔值" }
            return value
        }

        private fun integer(section: ConfigurationSection?, key: String, default: Long, range: LongRange): Long {
            val value = section?.get(key) ?: return default
            require(value is Int || value is Long) { "${section.currentPath}.$key 必须是整数" }
            val number = (value as Number).toLong()
            require(number in range) { "${section.currentPath}.$key 必须在 ${range.first} 至 ${range.last} 之间" }
            return number
        }

        private fun message(section: ConfigurationSection?, default: String): String {
            val value = section?.get("message") ?: return default
            require(value is String && value.trim().length in 1..256 && value.none { it.isISOControl() }) {
                "${section.currentPath}.message 必须是 1 至 256 字符的单行文字"
            }
            return value.trim()
        }
    }
}
