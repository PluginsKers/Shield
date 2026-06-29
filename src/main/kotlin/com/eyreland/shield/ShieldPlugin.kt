package com.eyreland.shield

import com.eyreland.shield.capture.PacketCaptureService
import com.eyreland.shield.command.ShieldCommand
import com.eyreland.shield.config.CaptureConfig
import com.eyreland.shield.config.UploadConfig
import com.eyreland.shield.label.LabelStore
import com.eyreland.shield.storage.Database
import com.eyreland.shield.storage.DatabaseConfig
import com.eyreland.shield.storage.SampleWriter
import com.eyreland.shield.storage.Schema
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Shield 入口。
 *
 * 全部后台 I/O 走单线程 [ScheduledExecutorService]（落库 flush 周期任务 + 标记 upsert），
 * 不用 Bukkit 调度器，因此对 Folia 安全。捕获在 ProtocolLib 的 Netty 线程，命令在主/region 线程。
 *
 * 数据库连不上时停用插件而不关服:Shield 是采集插件，不应拖垮整个服务器。
 */
class ShieldPlugin : JavaPlugin() {

    private var database: Database? = null
    private var writer: SampleWriter? = null
    private var capture: PacketCaptureService? = null
    private var labels: LabelStore? = null
    private var dbExecutor: ScheduledExecutorService? = null

    override fun onEnable() {
        saveDefaultConfig()

        val db = Database(DatabaseConfig.from(config.getConfigurationSection("database")), logger)
        try {
            db.open(Schema)
        } catch (t: Database.DatabaseConnectException) {
            logger.severe("数据库连接失败，停用 Shield:${t.message}")
            logger.severe("请检查 plugins/Shield/config.yml 的 database 段以及 PostgreSQL 是否可访问")
            server.pluginManager.disablePlugin(this)
            return
        }
        this.database = db

        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "shield-db").apply { isDaemon = true }
        }
        this.dbExecutor = executor

        val labelStore = LabelStore(db, executor, logger)
        labelStore.load()
        this.labels = labelStore

        val sampleWriter = SampleWriter(db, UploadConfig.from(config.getConfigurationSection("upload")), executor, logger)
        sampleWriter.start()
        this.writer = sampleWriter

        capture = newCapture(labelStore, sampleWriter)

        val cmd = getCommand("shield")
        if (cmd != null) {
            val handler = ShieldCommand({ capture }, sampleWriter, labelStore) { reloadShield() }
            cmd.setExecutor(handler)
            cmd.tabCompleter = handler
        } else {
            logger.warning("plugin.yml 未声明 shield 命令")
        }

        logger.info("Shield 已启用")
    }

    private fun newCapture(labelStore: LabelStore, sampleWriter: SampleWriter): PacketCaptureService =
        PacketCaptureService(this, CaptureConfig.from(config.getConfigurationSection("capture")), labelStore, sampleWriter, logger)
            .also { it.start() }

    /** 重载采集开关、密度与类目:重建捕获监听即可生效。数据库与上传参数变更需重启。 */
    private fun reloadShield() {
        reloadConfig()
        val l = labels ?: return
        val w = writer ?: return
        capture?.stop()
        capture = newCapture(l, w)
    }

    override fun onDisable() {
        capture?.stop()
        writer?.stop()
        dbExecutor?.let { ex ->
            ex.shutdown()
            try {
                if (!ex.awaitTermination(5, TimeUnit.SECONDS)) ex.shutdownNow()
            } catch (_: InterruptedException) {
                ex.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        database?.close()

        capture = null
        writer = null
        labels = null
        dbExecutor = null
        database = null
        logger.info("Shield 已禁用")
    }
}
