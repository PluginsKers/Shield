package com.eyreland.shield.label

import com.eyreland.shield.storage.Database
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 玩家标记的内存表加 PG 持久化。
 *
 * - 捕获线程只读 [labelOf]（ConcurrentHashMap，O(1) 无锁），把当前标记回填到每条样本
 * - 命令线程改标记：先更新内存（捕获立即生效），再把落库丢到 [dbExecutor] 异步执行
 */
class LabelStore(
    private val db: Database,
    private val dbExecutor: Executor,
    private val logger: Logger,
) {
    enum class Label(val key: String) { NORMAL("normal"), ABNORMAL("abnormal") }

    private val labels = ConcurrentHashMap<UUID, String>()

    fun labelOf(uuid: UUID): String = labels[uuid] ?: UNLABELED

    /** 各标记取值的当前人数，供 /shield status 展示。 */
    fun counts(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (v in labels.values) out[v] = (out[v] ?: 0) + 1
        return out
    }

    fun load() {
        db.withConnection { conn ->
            conn.prepareStatement("SELECT player_uuid, label FROM shield_player_labels").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val uuid = rs.getObject(1) as? UUID ?: continue
                        val label = rs.getString(2) ?: continue
                        labels[uuid] = label
                    }
                }
            }
        }
        logger.info("已加载 ${labels.size} 条玩家标记")
    }

    fun set(uuid: UUID, name: String, label: Label, updatedBy: String) {
        labels[uuid] = label.key
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                db.withConnection { conn ->
                    db.inTransaction(conn) { c ->
                        c.prepareStatement(UPSERT).use { ps ->
                            ps.setObject(1, uuid)
                            ps.setString(2, name)
                            ps.setString(3, label.key)
                            ps.setLong(4, now)
                            ps.setString(5, updatedBy)
                            ps.executeUpdate()
                        }
                    }
                }
            } catch (t: Throwable) {
                logger.log(Level.WARNING, "写入玩家标记失败：$name", t)
            }
        }
    }

    fun clear(uuid: UUID) {
        labels.remove(uuid)
        dbExecutor.execute {
            try {
                db.withConnection { conn ->
                    db.inTransaction(conn) { c ->
                        c.prepareStatement("DELETE FROM shield_player_labels WHERE player_uuid = ?").use { ps ->
                            ps.setObject(1, uuid)
                            ps.executeUpdate()
                        }
                    }
                }
            } catch (t: Throwable) {
                logger.log(Level.WARNING, "清除玩家标记失败", t)
            }
        }
    }

    companion object {
        const val UNLABELED = "unlabeled"

        private val UPSERT = """
            INSERT INTO shield_player_labels (player_uuid, player_name, label, updated_at_ms, updated_by)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid) DO UPDATE SET
                player_name = EXCLUDED.player_name,
                label = EXCLUDED.label,
                updated_at_ms = EXCLUDED.updated_at_ms,
                updated_by = EXCLUDED.updated_by
        """.trimIndent()
    }
}
