package com.eyreland.shield.config

import org.bukkit.configuration.ConfigurationSection

/**
 * 批量落库参数。flush 周期 drain 内存队列，按 [batchSize] 分批 INSERT。
 * 队列满则丢弃新样本并计数，绝不阻塞捕获线程。
 */
data class UploadConfig(
    val batchSize: Int,
    val flushIntervalMs: Long,
    val queueCapacity: Int,
) {
    companion object {
        fun from(c: ConfigurationSection?): UploadConfig {
            val batch = (c?.getInt("batch-size", 500) ?: 500).coerceIn(1, 100_000)
            val flush = (c?.getLong("flush-interval-ms", 1000L) ?: 1000L).coerceAtLeast(100L)
            val cap = (c?.getInt("queue-capacity", 100_000) ?: 100_000).coerceAtLeast(1000)
            return UploadConfig(batch, flush, cap)
        }
    }
}
