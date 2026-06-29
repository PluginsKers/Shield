package com.eyreland.shield.storage

import java.sql.Connection

/**
 * Shield 的 PostgreSQL schema。
 *
 * - shield_packet_samples：每条捕获的数据包一行，公共列覆盖各类型字段，无关列留 NULL
 * - shield_player_labels：玩家正常/异常标记，捕获时回填到样本的 label 列，便于直接导出带标签数据集
 *
 * 全部 CREATE ... IF NOT EXISTS，重复执行安全。后续字段演进走 ALTER ... IF NOT EXISTS。
 */
object Schema {

    private val DDL = listOf(
        """
        CREATE TABLE IF NOT EXISTS shield_packet_samples (
            id          BIGSERIAL PRIMARY KEY,
            recv_at_ms  BIGINT NOT NULL,
            player_uuid UUID NOT NULL,
            player_name TEXT NOT NULL,
            label       TEXT NOT NULL DEFAULT 'unlabeled',
            category    TEXT NOT NULL,
            packet_type TEXT NOT NULL,
            world       TEXT,
            x           DOUBLE PRECISION,
            y           DOUBLE PRECISION,
            z           DOUBLE PRECISION,
            yaw         REAL,
            pitch       REAL,
            on_ground   BOOLEAN,
            target_id   INTEGER,
            action      TEXT,
            hand        TEXT
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_shield_samples_uuid_ts ON shield_packet_samples (player_uuid, recv_at_ms)",
        "CREATE INDEX IF NOT EXISTS idx_shield_samples_label ON shield_packet_samples (label)",
        "CREATE INDEX IF NOT EXISTS idx_shield_samples_category ON shield_packet_samples (category)",
        """
        CREATE TABLE IF NOT EXISTS shield_player_labels (
            player_uuid   UUID PRIMARY KEY,
            player_name   TEXT NOT NULL,
            label         TEXT NOT NULL,
            updated_at_ms BIGINT NOT NULL,
            updated_by    TEXT
        )
        """.trimIndent(),
    )

    fun createAll(conn: Connection) {
        conn.createStatement().use { st -> for (sql in DDL) st.execute(sql) }
    }
}
