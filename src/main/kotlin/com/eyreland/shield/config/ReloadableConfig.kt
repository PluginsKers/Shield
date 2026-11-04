package com.eyreland.shield.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class ShieldControl(val configPath: String) {
    SHIELD("enabled"),
    INTERVENTIONS("interventions.enabled"),
}

class ReloadableConfig(private val file: File) {
    data class Snapshot(
        val enabled: Boolean,
        val inference: InferenceConfig,
        val interventions: InterventionConfig,
    ) {
        val activeInterventions: InterventionConfig
            get() = if (enabled) interventions else interventions.copy(enabled = false)
    }

    @Volatile
    var current: Snapshot = snapshot(read())
        private set

    @Synchronized
    fun reload() {
        current = validatedReplacement(read())
    }

    @Synchronized
    fun setEnabled(control: ShieldControl, enabled: Boolean) {
        val root = read()
        validatedReplacement(root)
        root.set(control.configPath, enabled)
        val replacement = snapshot(root)
        val target = file.toPath().toAbsolutePath()
        val temporary = Files.createTempFile(target.parent, ".shield-config-", ".yml")
        try {
            Files.writeString(temporary, root.saveToString())
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        current = replacement
    }

    private fun validatedReplacement(root: YamlConfiguration): Snapshot {
        val replacement = snapshot(root)
        // shortcut: 密钥与上报地址变更需重启；支持热切换时须处理在途请求与隔离批次。
        require(replacement.inference == current.inference) {
            "server-key 或 endpoint 变更需要重启服务器"
        }
        return replacement
    }

    private fun read(): YamlConfiguration = YamlConfiguration().apply { load(file) }

    private fun snapshot(root: YamlConfiguration): Snapshot {
        val enabled = root.get("enabled") ?: true
        require(enabled is Boolean) { "enabled 必须是布尔值" }
        return Snapshot(enabled, InferenceConfig.from(root), InterventionConfig.from(root))
    }
}
