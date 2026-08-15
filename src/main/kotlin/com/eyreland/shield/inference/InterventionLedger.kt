package com.eyreland.shield.inference

import com.eyreland.shield.config.InterventionConfig
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

internal sealed interface LedgerRegistration {
    data class Ready(val directive: PlayerInterventionDirective) : LedgerRegistration
    data class ReceiptOnly(val receipt: InterventionReceipt) : LedgerRegistration
    data object AlreadyPending : LedgerRegistration
}

/** 持久化指令状态；重试复用已有结果，重启时未完成的已调度动作记为 invalid。 */
internal class InterventionLedger(
    private val path: Path,
) {
    private val values = Properties()

    init {
        Files.createDirectories(path.toAbsolutePath().normalize().parent)
        if (Files.isRegularFile(path)) {
            Files.newInputStream(path).use(values::load)
        }
    }

    @Synchronized
    fun register(
        directive: PlayerInterventionDirective,
        config: InterventionConfig,
        nowMs: Long,
    ): LedgerRegistration {
        cleanup(config.ledgerRetentionMs, nowMs)
        val existingState = values.getProperty(directiveKey(directive.directiveId, "state"))
        if (existingState != null) {
            require(storedDirective(directive.directiveId) == directive) {
                "directive_id 被复用于不同内容：${directive.directiveId}"
            }
        }
        when (existingState) {
            "handled" -> return LedgerRegistration.ReceiptOnly(receipt(directive))
            "pending", "dispatched" -> return LedgerRegistration.AlreadyPending
        }

        val rejection = rejection(directive, config, nowMs)
        if (rejection != null) {
            return LedgerRegistration.ReceiptOnly(completeInternal(directive, rejection, nowMs))
        }

        putDirective(directive, "pending")
        persist()
        return LedgerRegistration.Ready(directive)
    }

    @Synchronized
    fun pending(): List<PlayerInterventionDirective> = directiveIds()
        .filter { values.getProperty(directiveKey(it, "state")) == "pending" }
        .mapNotNull(::storedDirective)

    @Synchronized
    fun pendingReceipts(): List<InterventionReceipt> = directiveIds()
        .filter {
            values.getProperty(directiveKey(it, "state")) == "handled" &&
                values.getProperty(directiveKey(it, "receipt-pending")) == "true"
        }
        .mapNotNull { id -> storedDirective(id)?.let(::receipt) }

    @Synchronized
    fun recoverInterrupted(nowMs: Long) {
        for (id in directiveIds()) {
            if (values.getProperty(directiveKey(id, "state")) != "dispatched") continue
            storedDirective(id)?.let {
                completeInternal(it, InterventionOutcome.INVALID, nowMs)
            }
        }
    }

    @Synchronized
    fun markDispatched(directiveId: String): Boolean {
        val key = directiveKey(directiveId, "state")
        if (values.getProperty(key) != "pending") return false
        values.setProperty(key, "dispatched")
        persist()
        return true
    }

    @Synchronized
    fun pendingRejection(
        directive: PlayerInterventionDirective,
        config: InterventionConfig,
        nowMs: Long,
    ): InterventionOutcome? = rejection(directive, config, nowMs)

    @Synchronized
    fun markReceiptPersisted(directiveId: String) {
        val key = directiveKey(directiveId, "receipt-pending")
        if (values.getProperty(key) != "true") return
        values.setProperty(key, "false")
        persist()
    }

    @Synchronized
    fun complete(
        directive: PlayerInterventionDirective,
        outcome: InterventionOutcome,
        nowMs: Long,
    ): InterventionReceipt {
        if (values.getProperty(directiveKey(directive.directiveId, "state")) == "handled") {
            return receipt(directive)
        }
        return completeInternal(directive, outcome, nowMs)
    }

    private fun completeInternal(
        directive: PlayerInterventionDirective,
        outcome: InterventionOutcome,
        nowMs: Long,
    ): InterventionReceipt {
        putDirective(directive, "handled")
        values.setProperty(directiveKey(directive.directiveId, "outcome"), outcome.wireName)
        values.setProperty(directiveKey(directive.directiveId, "handled-at"), nowMs.toString())
        values.setProperty(directiveKey(directive.directiveId, "receipt-pending"), "true")
        if (outcome == InterventionOutcome.APPLIED && directive.kind == InterventionKind.WARN) {
            val count = playerLong(directive.playerUuid, "warning-count") + 1
            putPlayerLong(directive.playerUuid, "warning-count", count)
            putPlayerLong(directive.playerUuid, "last-warning-at", nowMs)
        }
        if (outcome == InterventionOutcome.APPLIED && directive.kind == InterventionKind.KICK) {
            putPlayerLong(directive.playerUuid, "last-kick-at", nowMs)
        }
        persist()
        return InterventionReceipt(
            directive.directiveId,
            directive.playerUuid,
            directive.kind,
            outcome,
            nowMs,
        )
    }

    private fun receipt(directive: PlayerInterventionDirective): InterventionReceipt {
        val outcome = InterventionOutcome.entries.firstOrNull {
            it.wireName == values.getProperty(directiveKey(directive.directiveId, "outcome"))
        } ?: InterventionOutcome.INVALID
        return InterventionReceipt(
            directive.directiveId,
            directive.playerUuid,
            directive.kind,
            outcome,
            values.getProperty(directiveKey(directive.directiveId, "handled-at"))?.toLongOrNull() ?: 0,
        )
    }

    private fun putDirective(directive: PlayerInterventionDirective, state: String) {
        val key = { field: String -> directiveKey(directive.directiveId, field) }
        values.setProperty(key("state"), state)
        values.setProperty(key("player"), directive.playerUuid.toString())
        values.setProperty(key("kind"), directive.kind.wireName)
        values.setProperty(key("reason"), directive.reason)
        values.setProperty(key("issued-at"), directive.issuedAtMs.toString())
        values.setProperty(key("expires-at"), directive.expiresAtMs.toString())
    }

    private fun rejection(
        directive: PlayerInterventionDirective,
        config: InterventionConfig,
        nowMs: Long,
    ): InterventionOutcome? = when {
        nowMs > directive.expiresAtMs -> InterventionOutcome.EXPIRED
        !config.enabled -> InterventionOutcome.IGNORED_DISABLED
        directive.kind == InterventionKind.WARN && !config.warning.enabled -> {
            InterventionOutcome.IGNORED_DISABLED
        }
        directive.kind == InterventionKind.KICK && !config.kick.enabled -> {
            InterventionOutcome.IGNORED_DISABLED
        }
        hasOtherActiveDirective(directive) -> InterventionOutcome.IGNORED_COOLDOWN
        directive.kind == InterventionKind.WARN &&
            nowMs - playerLong(directive.playerUuid, "last-warning-at") < config.warning.cooldownMs -> {
            InterventionOutcome.IGNORED_COOLDOWN
        }
        directive.kind == InterventionKind.KICK &&
            playerLong(directive.playerUuid, "warning-count") < config.kick.minimumPriorWarnings -> {
            InterventionOutcome.IGNORED_COOLDOWN
        }
        directive.kind == InterventionKind.KICK &&
            nowMs - playerLong(directive.playerUuid, "last-kick-at") < config.kick.cooldownMs -> {
            InterventionOutcome.IGNORED_COOLDOWN
        }
        else -> null
    }

    private fun hasOtherActiveDirective(directive: PlayerInterventionDirective): Boolean =
        directiveIds().any { id ->
            if (id == directive.directiveId) return@any false
            val state = values.getProperty(directiveKey(id, "state"))
            if (state != "pending" && state != "dispatched") return@any false
            storedDirective(id)?.playerUuid == directive.playerUuid
        }

    private fun storedDirective(id: String): PlayerInterventionDirective? = runCatching {
        PlayerInterventionDirective(
            directiveId = id,
            playerUuid = UUID.fromString(values.getProperty(directiveKey(id, "player"))),
            kind = InterventionKind.fromWire(values.getProperty(directiveKey(id, "kind"))),
            reason = values.getProperty(directiveKey(id, "reason")),
            issuedAtMs = values.getProperty(directiveKey(id, "issued-at")).toLong(),
            expiresAtMs = values.getProperty(directiveKey(id, "expires-at")).toLong(),
        )
    }.getOrNull()

    private fun directiveIds(): Set<String> = values.stringPropertyNames()
        .asSequence()
        .filter { it.startsWith("directive.") && it.endsWith(".state") }
        .map { it.removePrefix("directive.").removeSuffix(".state") }
        .toSet()

    private fun cleanup(retentionMs: Long, nowMs: Long) {
        var changed = false
        for (id in directiveIds()) {
            if (values.getProperty(directiveKey(id, "state")) != "handled") continue
            val handledAt = values.getProperty(directiveKey(id, "handled-at"))?.toLongOrNull() ?: 0
            if (nowMs - handledAt <= retentionMs) continue
            removeDirective(id)
            changed = true
        }
        val players = values.stringPropertyNames()
            .asSequence()
            .filter { it.startsWith("player.") && it.endsWith(".last-warning-at") }
            .map { it.removePrefix("player.").removeSuffix(".last-warning-at") }
            .toSet()
        for (player in players) {
            val uuid = runCatching { UUID.fromString(player) }.getOrNull() ?: continue
            if (nowMs - playerLong(uuid, "last-warning-at") <= retentionMs) continue
            values.remove(playerKey(uuid, "last-warning-at"))
            values.remove(playerKey(uuid, "warning-count"))
            changed = true
        }
        if (changed) persist()
    }

    private fun removeDirective(id: String) {
        val prefix = "directive.$id."
        values.stringPropertyNames().filter { it.startsWith(prefix) }.forEach(values::remove)
    }

    private fun playerLong(uuid: UUID, field: String): Long =
        values.getProperty(playerKey(uuid, field))?.toLongOrNull() ?: 0

    private fun putPlayerLong(uuid: UUID, field: String, value: Long) {
        values.setProperty(playerKey(uuid, field), value.toString())
    }

    private fun persist() {
        val target = path.toAbsolutePath().normalize()
        val temporary = target.resolveSibling(".${target.fileName}.tmp")
        Files.newOutputStream(temporary).use { values.store(it, "Shield intervention ledger") }
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun directiveKey(id: String, field: String): String = "directive.$id.$field"

    private fun playerKey(uuid: UUID, field: String): String = "player.$uuid.$field"
}
