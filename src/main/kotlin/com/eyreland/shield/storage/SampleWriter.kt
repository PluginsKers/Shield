package com.eyreland.shield.storage

import com.eyreland.shield.capture.PacketSample
import com.eyreland.shield.config.UploadConfig
import java.sql.PreparedStatement
import java.sql.Types
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 样本落库器。
 *
 * 捕获线程调 [enqueue] 非阻塞入队；[executor] 上的周期任务 drain 队列并按 [UploadConfig.batchSize]
 * 分批 INSERT。队列满时丢弃新样本并计入 [Stats.dropped]，绝不阻塞捕获线程。
 */
class SampleWriter(
    private val db: Database,
    private val config: UploadConfig,
    private val executor: ScheduledExecutorService,
    private val logger: Logger,
) {
    data class Stats(val inserted: Long, val dropped: Long, val failed: Long)

    private val queue = ArrayBlockingQueue<PacketSample>(config.queueCapacity)
    private val inserted = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val failed = AtomicLong(0)
    private var task: ScheduledFuture<*>? = null

    fun start() {
        task = executor.scheduleWithFixedDelay(
            { runCatching { flush() }.onFailure { logger.log(Level.WARNING, "flush 异常", it) } },
            config.flushIntervalMs,
            config.flushIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    fun enqueue(sample: PacketSample) {
        if (!queue.offer(sample)) dropped.incrementAndGet()
    }

    fun stats(): Stats = Stats(inserted.get(), dropped.get(), failed.get())
    fun queued(): Int = queue.size

    /** drain 当前队列并落库。executor 单线程串行调用，无并发。 */
    private fun flush() {
        if (queue.isEmpty()) return
        val batch = ArrayList<PacketSample>(queue.size)
        queue.drainTo(batch)
        if (batch.isEmpty()) return
        try {
            db.withConnection { conn ->
                db.inTransaction(conn) { c ->
                    c.prepareStatement(INSERT).use { ps ->
                        var n = 0
                        for (s in batch) {
                            bind(ps, s)
                            ps.addBatch()
                            if (++n >= config.batchSize) {
                                ps.executeBatch()
                                n = 0
                            }
                        }
                        if (n > 0) ps.executeBatch()
                    }
                }
            }
            inserted.addAndGet(batch.size.toLong())
        } catch (t: Throwable) {
            failed.addAndGet(batch.size.toLong())
            logger.log(Level.WARNING, "批量落库失败，丢弃 ${batch.size} 条样本", t)
        }
    }

    /** 关闭前取消周期任务并做最后一次同步 flush，尽量不丢内存里的样本。 */
    fun stop() {
        task?.cancel(false)
        task = null
        runCatching { flush() }.onFailure { logger.log(Level.WARNING, "关闭前 flush 异常", it) }
    }

    private fun bind(ps: PreparedStatement, s: PacketSample) {
        ps.setLong(1, s.recvAtMs)
        ps.setObject(2, s.playerUuid)
        ps.setString(3, s.playerName)
        ps.setString(4, s.label)
        ps.setString(5, s.category.key)
        ps.setString(6, s.packetType)
        setNullableString(ps, 7, s.world)
        setNullableDouble(ps, 8, s.x)
        setNullableDouble(ps, 9, s.y)
        setNullableDouble(ps, 10, s.z)
        setNullableFloat(ps, 11, s.yaw)
        setNullableFloat(ps, 12, s.pitch)
        setNullableBoolean(ps, 13, s.onGround)
        setNullableInt(ps, 14, s.targetId)
        setNullableString(ps, 15, s.action)
        setNullableString(ps, 16, s.hand)
    }

    private fun setNullableString(ps: PreparedStatement, i: Int, v: String?) =
        if (v == null) ps.setNull(i, Types.VARCHAR) else ps.setString(i, v)

    private fun setNullableDouble(ps: PreparedStatement, i: Int, v: Double?) =
        if (v == null) ps.setNull(i, Types.DOUBLE) else ps.setDouble(i, v)

    private fun setNullableFloat(ps: PreparedStatement, i: Int, v: Float?) =
        if (v == null) ps.setNull(i, Types.REAL) else ps.setFloat(i, v)

    private fun setNullableBoolean(ps: PreparedStatement, i: Int, v: Boolean?) =
        if (v == null) ps.setNull(i, Types.BOOLEAN) else ps.setBoolean(i, v)

    private fun setNullableInt(ps: PreparedStatement, i: Int, v: Int?) =
        if (v == null) ps.setNull(i, Types.INTEGER) else ps.setInt(i, v)

    companion object {
        private val INSERT = """
            INSERT INTO shield_packet_samples
                (recv_at_ms, player_uuid, player_name, label, category, packet_type,
                 world, x, y, z, yaw, pitch, on_ground, target_id, action, hand)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    }
}
