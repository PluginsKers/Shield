package com.eyreland.shield

import com.eyreland.shield.capture.PacketCaptureService
import com.eyreland.shield.capture.PlayerContextStore
import com.eyreland.shield.command.ShieldCommand
import com.eyreland.shield.config.CaptureConfig
import com.eyreland.shield.config.InferenceConfig
import com.eyreland.shield.inference.EventSink
import com.eyreland.shield.inference.HttpEventSink
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Shield 入口。
 *
 * 捕获线程只做过滤、稳定语义解码和非阻塞 offer。gzip、spool、HMAC 与 HTTP 全部
 * 由独立单线程执行器完成。接口故障只影响观测数据，不影响游戏线程或玩家处置。
 */
class ShieldPlugin : JavaPlugin() {

    private var sink: EventSink? = null
    private var capture: PacketCaptureService? = null
    private var contexts: PlayerContextStore? = null
    private var ioExecutor: ScheduledExecutorService? = null
    private var activeInferenceConfig: InferenceConfig? = null
    private var activeServerId: String? = null
    private var activeProxyMode: String? = null

    override fun onEnable() {
        saveDefaultConfig()

        val captureConfig: CaptureConfig
        val inferenceConfig: InferenceConfig
        try {
            captureConfig = CaptureConfig.from(config.getConfigurationSection("capture"))
            inferenceConfig = InferenceConfig.from(config.getConfigurationSection("inference"))
        } catch (invalid: IllegalArgumentException) {
            logger.severe("Shield 配置无效，停用插件：${invalid.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        registerCommand()
        if (!inferenceConfig.enabled) {
            logger.warning("inference.enabled=false；Shield 不捕获也不保存原始事件")
            logger.info("Shield 已启用（传输关闭，游戏 fail-open）")
            return
        }

        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "shield-inference").apply { isDaemon = true }
        }
        ioExecutor = executor

        val pluginRoot = dataFolder.toPath().toAbsolutePath().normalize()
        val spoolRoot = pluginRoot.resolve(inferenceConfig.spoolDirectory).normalize()
        if (!spoolRoot.startsWith(pluginRoot)) {
            logger.severe("inference.spool-directory 越出插件目录，停用 Shield")
            server.pluginManager.disablePlugin(this)
            return
        }
        val eventSink = HttpEventSink(
            config = inferenceConfig,
            serverId = captureConfig.serverId,
            proxyMode = captureConfig.proxyMode,
            spoolRoot = spoolRoot,
            executor = executor,
            logger = logger,
        )
        try {
            eventSink.start()
        } catch (failure: Throwable) {
            logger.severe("事件 sink 启动失败，停用 Shield：${failure.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        sink = eventSink
        activeInferenceConfig = inferenceConfig
        activeServerId = captureConfig.serverId
        activeProxyMode = captureConfig.proxyMode

        val contextStore = PlayerContextStore(this)
        contextStore.start()
        contexts = contextStore
        capture = newCapture(contextStore, eventSink, captureConfig)

        logger.info("Shield 已启用（HTTP-only，自动处置永久关闭）")
    }

    private fun registerCommand() {
        val cmd = getCommand("shield")
        if (cmd == null) {
            logger.warning("plugin.yml 未声明 shield 命令")
            return
        }
        val handler = ShieldCommand(
            capture = { capture },
            sink = { sink },
            reload = ::reloadShield,
        )
        cmd.setExecutor(handler)
        cmd.tabCompleter = handler
    }

    private fun newCapture(
        contextStore: PlayerContextStore,
        eventSink: EventSink,
        captureConfig: CaptureConfig,
    ): PacketCaptureService {
        if (captureConfig.usedLegacyPacketDefaults) {
            logger.warning("旧配置缺少 capture.packets，暂用固定兼容白名单；请按新版 config.yml 显式配置")
        }
        return PacketCaptureService(
            this,
            captureConfig,
            contextStore,
            eventSink,
            logger,
        ).also { it.start() }
    }

    /** 传输和 spool 参数需重启；reload 只原子替换捕获策略并切换会话。 */
    private fun reloadShield() {
        reloadConfig()
        val newCaptureConfig = CaptureConfig.from(config.getConfigurationSection("capture"))
        val newInferenceConfig = InferenceConfig.from(config.getConfigurationSection("inference"))
        val currentInference = activeInferenceConfig
            ?: throw IllegalStateException("传输当前未启用；修改 inference 后请重启")
        require(newInferenceConfig == currentInference) {
            "inference 参数变更需要重启"
        }
        require(
            newCaptureConfig.serverId == activeServerId &&
                newCaptureConfig.proxyMode == activeProxyMode,
        ) {
            "capture.server-id 或 proxy-mode 变更需要重启"
        }
        val contextStore = contexts ?: throw IllegalStateException("玩家上下文未启动")
        val eventSink = sink ?: throw IllegalStateException("事件 sink 未启动")

        capture?.stop()
        contextStore.rotateSessions()
        capture = newCapture(contextStore, eventSink, newCaptureConfig)
    }

    override fun onDisable() {
        capture?.stop()
        contexts?.stop()
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
        contexts = null
        sink = null
        ioExecutor = null
        activeInferenceConfig = null
        activeServerId = null
        activeProxyMode = null
        logger.info("Shield 已禁用")
    }
}
