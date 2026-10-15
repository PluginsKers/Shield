package com.eyreland.shield.inference

import com.eyreland.shield.config.InterventionConfig
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

class PlayerInterventionService(
    private val plugin: Plugin,
    private val config: () -> InterventionConfig,
    ledgerPath: Path,
    private val executor: ScheduledExecutorService,
    private val receiptSink: (InterventionReceipt) -> Boolean,
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val ledger = InterventionLedger(ledgerPath)
    private val receiptRetryScheduled = AtomicBoolean()

    @Volatile private var started = false

    fun start() {
        started = true
        ledger.recoverInterrupted(clock.millis())
        retryPendingReceipts()
        ledger.pending().forEach(::dispatch)
    }

    fun stop() {
        started = false
    }

    fun acceptResponse(batchId: UUID, body: ByteArray) {
        accept(InferenceAckDecoder.decode(body, batchId))
    }

    fun acknowledgePersistedReceipts(directiveIds: List<String>) {
        directiveIds.forEach(ledger::markReceiptPersisted)
    }

    internal fun accept(directives: List<PlayerInterventionDirective>) {
        for (directive in directives) {
            when (val registration = ledger.register(directive, config(), clock.millis())) {
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
            logger.log(Level.WARNING, "无法调度玩家干预: ${directive.directiveId}", it)
            finish(directive, InterventionOutcome.INVALID)
        }
    }

    private fun apply(player: Player, directive: PlayerInterventionDirective) {
        if (!started) return
        val current = config()
        try {
            val rejection = ledger.pendingRejection(directive, current, clock.millis())
            if (rejection != null) {
                finish(directive, rejection)
                return
            }
            when (directive.kind) {
                InterventionKind.WARN -> showWarningDialog(player, directive, current)
                InterventionKind.KICK -> {
                    player.kick(kickComponent(current))
                    finish(directive, InterventionOutcome.APPLIED)
                }
            }
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "执行玩家干预失败: ${directive.directiveId}", failure)
            finish(directive, InterventionOutcome.INVALID)
        }
    }

    private fun showWarningDialog(
        player: Player,
        directive: PlayerInterventionDirective,
        current: InterventionConfig,
    ) {
        val nowMs = clock.millis()
        val remainingMs = directive.expiresAtMs - nowMs
        if (remainingMs <= 0) {
            finish(directive, InterventionOutcome.EXPIRED, nowMs)
            return
        }
        val closeAction = DialogAction.customClick(
            { _, _ ->
                val handledAtMs = clock.millis()
                finish(
                    directive,
                    if (handledAtMs <= directive.expiresAtMs) {
                        InterventionOutcome.APPLIED
                    } else {
                        InterventionOutcome.EXPIRED
                    },
                    handledAtMs,
                )
            },
            ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMillis(remainingMs))
                .build(),
        )
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(warningTitle())
                        .canCloseWithEscape(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    Component.text(current.warning.message),
                                ),
                            ),
                        )
                        .build(),
                )
                .type(
                    DialogType.notice(
                        ActionButton.builder(Component.text("关闭"))
                            .action(closeAction)
                            .build(),
                    ),
                )
        }
        executor.schedule(
            { finish(directive, InterventionOutcome.EXPIRED, clock.millis()) },
            remainingMs,
            TimeUnit.MILLISECONDS,
        )
        player.showDialog(dialog)
    }

    private fun finish(
        directive: PlayerInterventionDirective,
        outcome: InterventionOutcome,
        handledAtMs: Long = clock.millis(),
    ) {
        if (!started) return
        runCatching {
            executor.execute {
                if (!started) return@execute
                ledger.complete(directive, outcome, handledAtMs)?.let(::enqueueReceipt)
            }
        }.onFailure {
            logger.log(Level.WARNING, "无法提交玩家干预回执: ${directive.directiveId}", it)
        }
    }

    private fun enqueueReceipt(receipt: InterventionReceipt) {
        if (receiptSink(receipt)) return
        logger.warning("玩家干预回执队列已满, 稍后重试: ${receipt.directiveId}")
        scheduleReceiptRetry()
    }

    private fun retryPendingReceipts() {
        for (receipt in ledger.pendingReceipts()) {
            if (receiptSink(receipt)) continue
            logger.warning("玩家干预回执队列仍满, 稍后重试: ${receipt.directiveId}")
            scheduleReceiptRetry()
            return
        }
    }

    private fun scheduleReceiptRetry() {
        if (!started || !receiptRetryScheduled.compareAndSet(false, true)) return
        try {
            executor.schedule(
                {
                    receiptRetryScheduled.set(false)
                    if (started) retryPendingReceipts()
                },
                1,
                TimeUnit.SECONDS,
            )
        } catch (rejected: RejectedExecutionException) {
            receiptRetryScheduled.set(false)
            if (started) logger.log(Level.WARNING, "无法调度玩家干预回执重试", rejected)
        }
    }

    internal companion object {
        fun warningTitle(): Component = Component.text("行为提示")

        fun kickComponent(config: InterventionConfig): Component = Component.text(config.kick.message)
    }
}
