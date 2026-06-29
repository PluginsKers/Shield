package com.eyreland.shield.storage

import org.bukkit.configuration.ConfigurationSection

/**
 * PostgreSQL 连接配置，从 `config.yml.database` 段读取。
 *
 * [password] 为空时 [Database.open] 会抛错，避免有人忘填密码就部署。
 */
data class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val poolSize: Int,
    val connectionTimeoutMs: Long,
    val initSchema: Boolean,
) {
    fun jdbcUrl(): String = "jdbc:postgresql://$host:$port/$database"

    companion object {
        fun from(c: ConfigurationSection?): DatabaseConfig {
            val s = c ?: error("config.yml 缺少 database 段；请填 host/port/database/username/password")
            val host = s.getString("host", "localhost") ?: "localhost"
            val port = s.getInt("port", 5432)
            val database = s.getString("database", "shield") ?: "shield"
            val username = s.getString("username", "shield") ?: "shield"
            val password = s.getString("password", "") ?: ""
            val poolSize = s.getInt("pool-size", 2).coerceAtLeast(1)
            val timeout = s.getLong("connection-timeout-ms", 5000L).coerceAtLeast(500L)
            val initSchema = s.getBoolean("init-schema", true)
            return DatabaseConfig(host, port, database, username, password, poolSize, timeout, initSchema)
        }
    }
}
