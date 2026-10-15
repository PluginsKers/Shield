package com.eyreland.shield.config

import org.bukkit.configuration.ConfigurationSection
import java.net.URI
import java.util.UUID

/** shield 到治理服务的有界异步传输配置。 */
data class InferenceConfig(
    val endpoint: URI,
    val keyId: String,
    val hmacSecret: String,
    val batchSize: Int,
    val flushIntervalMs: Long,
    val queueCapacity: Int,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long,
    val retryInitialMs: Long,
    val retryMaxMs: Long,
    val maxSendBatchesPerCycle: Int,
    val spoolDirectory: String,
    val spoolMaxBytes: Long,
    val spoolMaxBatches: Int,
    val spoolTtlMs: Long,
    val quarantineMaxBatches: Int,
    val maxCompressedBatchBytes: Int,
    val maxUncompressedBatchBytes: Int,
) {
    companion object {
        fun from(root: ConfigurationSection): InferenceConfig {
            require(root.getKeys(false).all { it in setOf("server-key", "interventions") }) {
                "配置仅支持 server-key 与 interventions"
            }
            val key = root.getString("server-key").orEmpty().trim()
            val parts = key.removePrefix("shd_").split('.')
            require(key.startsWith("shd_") &&
                parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}"))) {
                "server-key 无效, 请从账户后台复制完整接入密钥"
            }
            val serverId = runCatching { UUID.fromString(parts[0]).toString() }.getOrNull()
            require(serverId == parts[0]) { "server-key 中的服务器标识无效" }
            return InferenceConfig(
                endpoint = URI("https://api.i5res.com/shield/v1/events"),
                keyId = parts[0],
                hmacSecret = parts[1],
                batchSize = 500,
                flushIntervalMs = 1_000,
                queueCapacity = 20_000,
                connectTimeoutMs = 3_000,
                requestTimeoutMs = 60_000,
                retryInitialMs = 1_000,
                retryMaxMs = 60_000,
                maxSendBatchesPerCycle = 4,
                spoolDirectory = "inference-spool",
                spoolMaxBytes = 256L * 1024 * 1024,
                spoolMaxBatches = 4_096,
                spoolTtlMs = 6L * 60 * 60 * 1_000,
                quarantineMaxBatches = 32,
                maxCompressedBatchBytes = 1_500_000,
                maxUncompressedBatchBytes = 10 * 1024 * 1024,
            )
        }
    }
}
