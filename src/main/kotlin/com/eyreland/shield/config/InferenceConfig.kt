package com.eyreland.shield.config

import org.bukkit.configuration.ConfigurationSection
import java.net.URI
import java.nio.file.Path

/** Shield 到治理服务的有界异步传输配置。 */
data class InferenceConfig(
    val enabled: Boolean,
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
    val rolloutPercent: Double,
    val rolloutSalt: String,
) {
    companion object {
        private const val DEFAULT_ENDPOINT = "https://api.i5res.com/shield/v1/events"
        private const val DEFAULT_SECRET_ENV = "SHIELD_INFERENCE_HMAC_SECRET"

        fun from(
            section: ConfigurationSection?,
            environment: (String) -> String? = System::getenv,
        ): InferenceConfig {
            // 旧配置没有 inference 段时也进入新链路；缺少密钥会让 Shield 自身停用，
            // 但绝不影响 Minecraft 服务。
            val enabled = section?.getBoolean("enabled", true) ?: true
            val endpoint = parseEndpoint(
                section?.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT,
                section?.getBoolean("allow-insecure-http", false) ?: false,
            )
            val keyId = (section?.getString("key-id", "gray-local-20260729")
                ?: "gray-local-20260729").trim()
            require(keyId.isNotEmpty() && keyId.length <= 128) {
                "inference.key-id 必须为 1..128 字符"
            }
            val inlineSecret = section?.getString("hmac-secret")?.trim().orEmpty()
            val secretEnv = (section?.getString("hmac-secret-env", DEFAULT_SECRET_ENV)
                ?: DEFAULT_SECRET_ENV).trim()
            val secret = if (inlineSecret.isNotEmpty()) {
                inlineSecret
            } else {
                require(secretEnv.isNotEmpty()) { "inference.hmac-secret-env 不能为空" }
                environment(secretEnv)?.trim().orEmpty()
            }
            if (enabled) {
                require(secret.length >= 32) {
                    "inference.hmac-secret 或环境变量 $secretEnv 未配置，或少于 32 字符"
                }
            }

            val retryInitialMs = longIn(section, "retry-initial-ms", 1_000, 100, 60_000)
            val retryMaxMs = longIn(section, "retry-max-ms", 60_000, retryInitialMs, 900_000)
            val spoolDirectory = (section?.getString("spool-directory", "inference-spool")
                ?: "inference-spool").trim()
            validateRelativeDirectory(spoolDirectory)
            val rolloutPercent = section?.getDouble("rollout-percent", 10.0) ?: 10.0
            require(rolloutPercent in 0.0..100.0) {
                "inference.rollout-percent 必须在 0..100"
            }
            val rolloutSalt = (section?.getString("rollout-salt", "shield-gray-v1")
                ?: "shield-gray-v1").trim()
            require(rolloutSalt.isNotEmpty()) { "inference.rollout-salt 不能为空" }

            return InferenceConfig(
                enabled = enabled,
                endpoint = endpoint,
                keyId = keyId,
                hmacSecret = secret,
                batchSize = intIn(section, "batch-size", 500, 1, 1_000),
                flushIntervalMs = longIn(section, "flush-interval-ms", 1_000, 100, 60_000),
                queueCapacity = intIn(section, "queue-capacity", 20_000, 100, 1_000_000),
                connectTimeoutMs = longIn(section, "connect-timeout-ms", 3_000, 100, 60_000),
                requestTimeoutMs = longIn(section, "request-timeout-ms", 10_000, 500, 120_000),
                retryInitialMs = retryInitialMs,
                retryMaxMs = retryMaxMs,
                maxSendBatchesPerCycle = intIn(section, "max-send-batches-per-cycle", 4, 1, 100),
                spoolDirectory = spoolDirectory,
                spoolMaxBytes = longIn(
                    section,
                    "spool-max-bytes",
                    256L * 1024 * 1024,
                    1024L * 1024,
                    10L * 1024 * 1024 * 1024,
                ),
                spoolMaxBatches = intIn(section, "spool-max-batches", 4_096, 1, 100_000),
                spoolTtlMs = longIn(
                    section,
                    "spool-ttl-ms",
                    6L * 60 * 60 * 1_000,
                    60_000,
                    30L * 24 * 60 * 60 * 1_000,
                ),
                quarantineMaxBatches = intIn(section, "quarantine-max-batches", 32, 1, 1_000),
                maxCompressedBatchBytes = intIn(
                    section,
                    "max-compressed-batch-bytes",
                    1_500_000,
                    64 * 1024,
                    2 * 1024 * 1024,
                ),
                maxUncompressedBatchBytes = intIn(
                    section,
                    "max-uncompressed-batch-bytes",
                    10 * 1024 * 1024,
                    256 * 1024,
                    12 * 1024 * 1024,
                ),
                rolloutPercent = rolloutPercent,
                rolloutSalt = rolloutSalt,
            )
        }

        private fun parseEndpoint(raw: String, allowInsecureHttp: Boolean): URI {
            val endpoint = runCatching { URI(raw.trim()) }
                .getOrElse { throw IllegalArgumentException("inference.endpoint 不是合法 URI", it) }
            require(endpoint.host != null && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) {
                "inference.endpoint 必须是无账号、query 和 fragment 的绝对地址"
            }
            require(endpoint.path == "/v1/events" || endpoint.path == "/shield/v1/events") {
                "inference.endpoint 路径必须为 /v1/events 或 /shield/v1/events"
            }
            val loopback = endpoint.host == "127.0.0.1" || endpoint.host == "localhost"
            require(endpoint.scheme == "https" || (endpoint.scheme == "http" && (allowInsecureHttp || loopback))) {
                "inference.endpoint 必须使用 HTTPS"
            }
            return endpoint
        }

        private fun validateRelativeDirectory(value: String) {
            require(value.isNotEmpty()) { "inference.spool-directory 不能为空" }
            val path = Path.of(value).normalize()
            require(!path.isAbsolute && !path.startsWith("..")) {
                "inference.spool-directory 必须位于插件目录内"
            }
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
                "inference.$path 必须在 $minimum..$maximum"
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
                "inference.$path 必须在 $minimum..$maximum"
            }
            return value
        }
    }
}
