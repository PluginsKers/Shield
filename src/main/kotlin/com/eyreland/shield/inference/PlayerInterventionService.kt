package com.eyreland.shield.inference

import com.eyreland.shield.config.InterventionConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

class PlayerInterventionService(
    private val plugin: JavaPlugin,
    initialConfig: InterventionConfig,
    ledgerPath: Path,
    private val executor: ScheduledExecutorService,
    private val receiptSink: (InterventionReceipt) -> Boolean,
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val config = AtomicReference(initialConfig)
    private val ledger = InterventionLedger(ledgerPath)

    @Volatile private var started = false

    fun start() {
        started = true
        ledger.recoverInterrupted(clock.millis())
        ledger.pendingReceipts().forEach(::enqueueReceipt)
        ledger.pending().forEach(::dispatch)
    }

    fun stop() {
        started = false
    }

    fun updateConfig(value: InterventionConfig) {
        config.set(value)
    }

    fun acceptResponse(batchId: UUID, body: ByteArray) {
        accept(InferenceAckDecoder.decode(body, batchId))
    }

    fun acknowledgePersistedReceipts(directiveIds: List<String>) {
        directiveIds.forEach(ledger::markReceiptPersisted)
    }

    internal fun accept(directives: List<PlayerInterventionDirective>) {
        for (directive in directives) {
            when (val registration = ledger.register(directive, config.get(), clock.millis())) {
                LedgerRegistration.AlreadyPending -> Unit
                is LedgerRegistration.Ready -> dispatch(registration.directive)
                is LedgerRegistration.ReceiptOnly -> enqueueReceipt(registration.receipt)
            }
        }
    }

    private fun dispatch(directive: PlayerInterventionDirective) {
        if (!started) return
        if (clock.millis() > directive.expiresAtMs) {
            finish(directive, InterventionOutcome.EXPIRED)
            return
        }
        if (!ledger.markDispatched(directive.directiveId)) return
        runCatching {
            plugin.server.globalRegionScheduler.run(plugin) {
                if (!started) return@run
                val player = plugin.server.getPlayer(directive.playerUuid)
                if (player == null || !player.isOnline) {
                    finish(directive, InterventionOutcome.PLAYER_OFFLINE)
                    return@run
                }
                player.scheduler.run(
                    plugin,
                    { apply(player, directive) },
                    { finish(directive, InterventionOutcome.PLAYER_OFFLINE) },
                )
            }
        }.onFailure {
            logger.log(Level.WARNING, "无法调度玩家干预：${directive.directiveId}", it)
            finish(directive, InterventionOutcome.INVALID)
        }
    }

    private fun apply(player: Player, directive: PlayerInterventionDirective) {
        if (!started) return
        val current = config.get()
        val outcome = try {
            val rejection = ledger.pendingRejection(directive, current, clock.millis())
            if (rejection != null) {
                rejection
            } else {
                when (directive.kind) {
                    InterventionKind.WARN -> {
                        player.sendMessage(warningComponent(current))
                        InterventionOutcome.APPLIED
                    }
                    InterventionKind.KICK -> {
                        player.kick(kickComponent(current))
                        InterventionOutcome.APPLIED
                    }
                }
            }
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "执行玩家干预失败：${directive.directiveId}", failure)
            InterventionOutcome.INVALID
        }
        finish(directive, outcome)
    }

    private fun finish(
        directive: PlayerInterventionDirective,
        outcome: InterventionOutcome,
    ) {
        if (!started) return
        runCatching {
            executor.execute {
                if (!started) return@execute
                enqueueReceipt(ledger.complete(directive, outcome, clock.millis()))
            }
        }.onFailure {
            logger.log(Level.WARNING, "无法提交玩家干预回执：${directive.directiveId}", it)
        }
    }

    private fun enqueueReceipt(receipt: InterventionReceipt) {
        if (!receiptSink(receipt)) {
            logger.warning("玩家干预回执队列已满：${receipt.directiveId}")
        }
    }

    internal companion object {
        fun warningComponent(config: InterventionConfig): Component = Component
            .text(config.warning.prefix, NamedTextColor.RED)
            .append(Component.text(" ${config.warning.message}", NamedTextColor.WHITE))

        fun kickComponent(config: InterventionConfig): Component = Component
            .text(config.warning.prefix, NamedTextColor.RED)
            .append(Component.text(" ${config.kick.message}", NamedTextColor.WHITE))
    }
}
