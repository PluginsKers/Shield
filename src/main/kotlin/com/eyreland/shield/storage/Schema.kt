package com.eyreland.shield.storage

import java.sql.Connection

/**
 * Schema v2 使用稳定公共列 + JSONB 扩展载荷。
 *
 * 不迁移任何旧行。版本缺失或不一致时，在同一事务中直接删除 Shield 的样本表和人工标记表，
 * 再创建当前结构；版本一致的正常重启不会清空新数据。
 */
object Schema {
    private const val CURRENT_VERSION = 2
    private const val COMPONENT = "capture"

    /** @return true 表示本次直接删除并重建了 Shield 数据表。 */
    fun createAll(conn: Connection): Boolean {
        conn.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS shield_schema_versions (
                    component     TEXT PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    updated_at_ms BIGINT NOT NULL
                )
                """.trimIndent(),
            )
        }

        val version = conn.prepareStatement(
            "SELECT schema_version FROM shield_schema_versions WHERE component = ?",
        ).use { statement ->
            statement.setString(1, COMPONENT)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else null }
        }

        val reset = requiresReset(version)
        if (reset) {
            conn.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS shield_packet_samples")
                statement.execute("DROP TABLE IF EXISTS shield_player_labels")
            }
        }

        conn.createStatement().use { statement ->
            for (sql in DDL) statement.execute(sql)
        }
        conn.prepareStatement(
            """
            INSERT INTO shield_schema_versions (component, schema_version, updated_at_ms)
            VALUES (?, ?, ?)
            ON CONFLICT (component) DO UPDATE SET
                schema_version = EXCLUDED.schema_version,
                updated_at_ms = EXCLUDED.updated_at_ms
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, COMPONENT)
            statement.setInt(2, CURRENT_VERSION)
            statement.setLong(3, System.currentTimeMillis())
            statement.executeUpdate()
        }
        return reset
    }

    internal fun requiresReset(version: Int?): Boolean = version != CURRENT_VERSION

    private val DDL = listOf(
        """
        CREATE TABLE IF NOT EXISTS shield_packet_samples (
            id                       BIGSERIAL PRIMARY KEY,
            captured_at_ms           BIGINT NOT NULL,
            session_id               UUID NOT NULL,
            session_sequence         BIGINT NOT NULL,
            session_age_ms           BIGINT NOT NULL,
            since_previous_us        BIGINT,
            server_id                TEXT NOT NULL,
            proxy_mode               TEXT NOT NULL,
            player_uuid              UUID NOT NULL,
            player_name              TEXT NOT NULL,
            label                    TEXT NOT NULL DEFAULT 'unlabeled',
            world                    TEXT,
            game_mode                TEXT,
            observed_protocol        INTEGER,
            server_version           TEXT NOT NULL,
            category                 TEXT NOT NULL,
            packet_type              TEXT NOT NULL,
            action                   TEXT,
            hand                     TEXT,
            target_entity_id         INTEGER,
            x                        DOUBLE PRECISION,
            y                        DOUBLE PRECISION,
            z                        DOUBLE PRECISION,
            yaw                      REAL,
            pitch                    REAL,
            on_ground                BOOLEAN,
            horizontal_collision     BOOLEAN,
            delta_x                  DOUBLE PRECISION,
            delta_y                  DOUBLE PRECISION,
            delta_z                  DOUBLE PRECISION,
            delta_yaw                REAL,
            delta_pitch              REAL,
            distance                 DOUBLE PRECISION,
            horizontal_distance      DOUBLE PRECISION,
            speed_bps                DOUBLE PRECISION,
            horizontal_speed_bps     DOUBLE PRECISION,
            movement_changed         BOOLEAN NOT NULL,
            payload                  JSONB NOT NULL DEFAULT '{}'::jsonb,
            UNIQUE (session_id, session_sequence)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_shield_samples_uuid_ts ON shield_packet_samples (player_uuid, captured_at_ms)",
        "CREATE INDEX IF NOT EXISTS idx_shield_samples_session_seq ON shield_packet_samples (session_id, session_sequence)",
        "CREATE INDEX IF NOT EXISTS idx_shield_samples_label ON shield_packet_samples (label)",
        "CREATE INDEX IF NOT EXISTS idx_shield_samples_packet ON shield_packet_samples (packet_type, captured_at_ms)",
        "CREATE INDEX IF NOT EXISTS idx_shield_samples_category ON shield_packet_samples (category, captured_at_ms)",
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
}
