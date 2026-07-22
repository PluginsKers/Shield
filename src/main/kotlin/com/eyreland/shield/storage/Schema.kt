package com.eyreland.shield.storage

import java.sql.Connection

/**
 * Schema v2 使用稳定公共列 + JSONB 扩展载荷。
 *
 * 旧版没有版本表。首次升级时会在同一事务中删除旧样本表并重建；玩家人工标记表保持不变。
 * 后续启动读取版本号，不会重复清空。用户已明确允许本次样本库重置。
 */
object Schema {
    private const val CURRENT_VERSION = 2
    private const val COMPONENT = "capture"

    fun createAll(conn: Connection) {
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

        require(version == null || version == CURRENT_VERSION) {
            "不支持的 Shield schema 版本: $version，当前仅支持 $CURRENT_VERSION"
        }
        if (version == null) {
            conn.createStatement().use { it.execute("DROP TABLE IF EXISTS shield_packet_samples") }
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
    }

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
