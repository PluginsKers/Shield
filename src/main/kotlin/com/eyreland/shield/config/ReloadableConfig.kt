package com.eyreland.shield.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class ReloadableConfig(private val file: File) {
    data class Snapshot(
        val inference: InferenceConfig,
        val interventions: InterventionConfig,
    )

    @Volatile
    var current: Snapshot = load()
        private set

    @Synchronized
    fun reload() {
        val replacement = load()
        // shortcut: 密钥变更需重启；支持热轮换时须处理在途请求与隔离批次。
        require(replacement.inference == current.inference) {
            "server-key 变更需要重启服务器"
        }
        current = replacement
    }

    private fun load(): Snapshot {
        val root = YamlConfiguration().apply { load(file) }
        return Snapshot(InferenceConfig.from(root), InterventionConfig.from(root))
    }
}
