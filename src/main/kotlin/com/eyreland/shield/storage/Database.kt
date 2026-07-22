package com.eyreland.shield.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.logging.Level
import java.util.logging.Logger

/**
 * PostgreSQL 持久层入口，HikariCP 连接池。
 *
 * 连不上或 schema 失败时抛 [DatabaseConnectException]，由 [com.eyreland.shield.ShieldPlugin]
 * 捕获后停用插件，不在坏库上空转。
 */
class Database(private val config: DatabaseConfig, private val logger: Logger) {

    @Volatile private var dataSource: HikariDataSource? = null

    fun open(schema: Schema) {
        if (dataSource != null) return
        if (config.password.isEmpty()) {
            throw DatabaseConnectException("PostgreSQL 密码为空，请在 config.yml 的 database.password 填入")
        }

        // Paper 插件用独立 ClassLoader 加载 jar 内依赖。DriverManager 在 Java 9+ 按 caller class
        // loader 过滤注册表，看不到插件 loader 注册的 PG driver，会抛 "No suitable driver"。
        // 直接给 Hikari 一个 PGSimpleDataSource，绕开 DriverManager。
        val pg = PGSimpleDataSource().apply {
            setUrl(config.jdbcUrl())
            user = config.username
            password = config.password
        }
        val hikari = HikariConfig().apply {
            dataSource = pg
            maximumPoolSize = config.poolSize
            minimumIdle = 1
            connectionTimeout = config.connectionTimeoutMs
            poolName = "shield-pg"
            initializationFailTimeout = config.connectionTimeoutMs
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }

        val ds = try {
            HikariDataSource(hikari)
        } catch (t: Throwable) {
            throw DatabaseConnectException(
                "PostgreSQL 连接失败：${config.host}:${config.port}/${config.database}（user=${config.username}）：${t.message}",
                t,
            )
        }

        // 探活：HikariCP 拿到连接不代表能跑 SQL，做一次 SELECT 1 确认。
        try {
            ds.connection.use { c ->
                c.createStatement().use { st -> st.executeQuery("SELECT 1").use { it.next() } }
            }
        } catch (t: Throwable) {
            ds.close()
            throw DatabaseConnectException("PostgreSQL 已连接但 SELECT 1 失败：${t.message}", t)
        }
        dataSource = ds

        if (config.initSchema) {
            try {
                withConnection { c -> inTransaction(c) { conn -> schema.createAll(conn) } }
            } catch (t: Throwable) {
                logger.log(Level.SEVERE, "schema 初始化失败，已回滚", t)
                close()
                throw DatabaseConnectException("schema 初始化失败：${t.message}", t)
            }
        }

        logger.info("PostgreSQL 已连接：${config.host}:${config.port}/${config.database}（pool=${config.poolSize}）")
    }

    /** 借连接执行 [block]，归还到池。block 内异常向上抛。 */
    fun <T> withConnection(block: (Connection) -> T): T {
        val ds = dataSource ?: error("Database 未初始化")
        ds.connection.use { return block(it) }
    }

    /** 在给定连接上以事务方式执行 [block]，失败回滚并抛出。 */
    fun inTransaction(conn: Connection, block: (Connection) -> Unit) {
        val prev = conn.autoCommit
        conn.autoCommit = false
        try {
            block(conn)
            conn.commit()
        } catch (t: Throwable) {
            try { conn.rollback() } catch (_: Throwable) {}
            throw t
        } finally {
            conn.autoCommit = prev
        }
    }

    fun close() {
        val ds = dataSource ?: return
        dataSource = null
        try {
            ds.close()
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "HikariCP 关闭异常", t)
        }
    }

    class DatabaseConnectException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)
}
