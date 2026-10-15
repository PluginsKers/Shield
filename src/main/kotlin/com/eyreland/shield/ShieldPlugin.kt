package com.eyreland.shield

import com.eyreland.shield.capture.PacketCaptureService
import com.eyreland.shield.capture.AuthorityCaptureService
import com.eyreland.shield.capture.PlayerContextStore
import com.eyreland.shield.command.ShieldCommand
import com.eyreland.shield.command.ShieldCommandView
import com.eyreland.shield.config.CaptureConfig
import com.eyreland.shield.config.ReloadableConfig
import com.eyreland.shield.inference.EventSink
import com.eyreland.shield.inference.HttpEventSink
import com.eyreland.shield.inference.PlayerInterventionService
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.InvalidConfigurationException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 捕获线程只做过滤、稳定语义解码和非阻塞 offer。gzip、spool、HMAC 与 HTTP 全部
 * 由独立单线程执行器完成。接口故障只影响观测数据，不影响游戏线程或玩家处置。
 */
class ShieldPlugin : JavaPlugin() {

    private var sink: EventSink? = null
    private var capture: PacketCaptureService? = null
    private var authority: AuthorityCaptureService? = null
    private var contexts: PlayerContextStore? = null
    private var ioExecutor: ScheduledExecutorService? = null
    private var interventions: PlayerInterventionService? = null

    override fun onEnable() {
        saveDefaultConfig()

        val configuration = try {
            ReloadableConfig(dataFolder.resolve("config.yml"))
        } catch (_: InvalidConfigurationException) {
            logger.severe("config.yml 的 YAML 格式错误, 停用插件")
            server.pluginManager.disablePlugin(this)
            return
        } catch (_: IOException) {
            logger.severe("无法读取 config.yml, 停用插件")
            server.pluginManager.disablePlugin(this)
            return
        } catch (invalid: IllegalArgumentException) {
            logger.severe("配置无效, 停用插件: ${invalid.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        val inferenceConfig = configuration.current.inference
        val interventionConfig = configuration.current.interventions
        val captureConfig = CaptureConfig(
            inferenceConfig.keyId,
            if (server.serverConfig.isProxyEnabled) "proxy" else "standalone",
        )
        registerCommand(configuration)

        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "shield-inference").apply { isDaemon = true }
        }
        ioExecutor = executor

        val pluginRoot = dataFolder.toPath().toAbsolutePath().normalize()
        val spoolRoot = pluginRoot.resolve(inferenceConfig.spoolDirectory).normalize()
        lateinit var interventionService: PlayerInterventionService
        val eventSink = HttpEventSink(
            config = inferenceConfig,
            serverId = captureConfig.serverId,
            proxyMode = captureConfig.proxyMode,
            spoolRoot = spoolRoot,
            executor = executor,
            logger = logger,
            responseHandler = { batchId, body -> interventionService.acceptResponse(batchId, body) },
            persistedReceiptHandler = { directiveIds ->
                interventionService.acknowledgePersistedReceipts(directiveIds)
            },
        )
        try {
            interventionService = PlayerInterventionService(
                plugin = this,
                config = { configuration.current.interventions },
                ledgerPath = pluginRoot.resolve("intervention-ledger.properties"),
                executor = executor,
                receiptSink = eventSink::enqueueInterventionReceipt,
                logger = logger,
            )
            eventSink.start()
            interventionService.start()
        } catch (failure: Throwable) {
            logger.severe("事件传输启动失败, 停用插件: ${failure.message}")
            eventSink.stop()
            executor.shutdownNow()
            server.pluginManager.disablePlugin(this)
            return
        }
        sink = eventSink
        interventions = interventionService

        val contextStore = PlayerContextStore(this)
        contextStore.start()
        contexts = contextStore
        capture = newCapture(contextStore, eventSink, captureConfig)

        logger.info(
            "插件已启用 (玩家警告=${interventionConfig.enabled && interventionConfig.warning.enabled}, " +
                "踢出=${interventionConfig.enabled && interventionConfig.kick.enabled})",
        )
    }

    private fun registerCommand(configuration: ReloadableConfig) {
        val cmd = getCommand("shield")
        if (cmd == null) {
            logger.warning("plugin.yml 未声明 shield 命令")
            return
        }
        val handler = ShieldCommand(
            capture = { capture },
            sink = { sink },
            reloadConfig = configuration::reload,
        )
        cmd.setExecutor(handler)
        cmd.tabCompleter = handler
        cmd.permissionMessage(LegacyComponentSerializer.legacySection().deserialize(ShieldCommandView.NO_PERMISSION))
    }

    private fun newCapture(
        contextStore: PlayerContextStore,
        eventSink: EventSink,
        captureConfig: CaptureConfig,
    ): PacketCaptureService {

        val packetCapture = PacketCaptureService(
            this,
            captureConfig,
            contextStore,
            eventSink,
            logger,
        ).also { it.start() }
        if (packetCapture.enabled) {
            authority = AuthorityCaptureService(
                this,
                captureConfig,
                packetCapture,
            ).also { it.start() }
        }
        return packetCapture
    }

    override fun onDisable() {
        authority?.stop()
        capture?.stop()
        contexts?.stop()
        interventions?.stop()
        sink?.stop()
        ioExecutor?.let { executor ->
            executor.shutdownNow()
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.warning("事件 I/O 线程未在 2 秒内退出")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        capture = null
        authority = null
        contexts = null
        sink = null
        ioExecutor = null
        interventions = null
        logger.info("插件已禁用")
    }
}
