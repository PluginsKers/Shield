package com.eyreland.shield.inference

import com.eyreland.shield.config.InterventionConfig
import com.eyreland.shield.config.ReloadableConfig
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlayerInterventionReloadTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `player retirement completes the directive before or after scheduling`(alreadyRetired: Boolean) {
        val callbacks = ArrayDeque<Consumer<ScheduledTask>>()
        val task = proxy<ScheduledTask> { name, _ -> error("Unexpected task call: $name") }
        var retiredCallback: Runnable? = null
        val entityScheduler = proxy<EntityScheduler> { name, args ->
            check(name == "run")
            if (alreadyRetired) null else {
                retiredCallback = args[2] as Runnable
                task
            }
        }
        val globalScheduler = proxy<GlobalRegionScheduler> { name, args ->
            check(name == "run")
            @Suppress("UNCHECKED_CAST")
            callbacks.addLast(args[1] as Consumer<ScheduledTask>)
            task
        }
        val player = proxy<Player> { name, _ ->
            when (name) {
                "isOnline" -> true
                "getScheduler" -> entityScheduler
                else -> error("Unexpected player call: $name")
            }
        }
        val server = proxy<Server> { name, _ ->
            when (name) {
                "getGlobalRegionScheduler" -> globalScheduler
                "getPlayer" -> player
                else -> error("Unexpected server call: $name")
            }
        }
        val plugin = proxy<Plugin> { name, _ ->
            check(name == "getServer")
            server
        }
        val now = 1_900_000_000_000L
        val directive = PlayerInterventionDirective(
            "retired", UUID.randomUUID(), InterventionKind.WARN, "test", now, now + 60_000,
        )
        val ledgerPath = temporaryDirectory.resolve("ledger.properties")
        val receipts = CopyOnWriteArrayList<InterventionReceipt>()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val service = PlayerInterventionService(
            plugin, InterventionConfig::defaults, ledgerPath, executor,
            { receipts += it; true }, Logger.getAnonymousLogger(),
            Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        )
        try {
            service.start()
            service.accept(listOf(directive))
            callbacks.removeFirst().accept(task)
            if (!alreadyRetired) checkNotNull(retiredCallback).run()
            executor.submit {}.get(5, TimeUnit.SECONDS)

            assertEquals(listOf(InterventionOutcome.PLAYER_OFFLINE), receipts.map { it.outcome })
            val persisted = InterventionLedger(ledgerPath)
            assertEquals(emptyList(), persisted.pending())
            assertEquals(receipts.toList(), persisted.pendingReceipts())
            service.accept(listOf(directive))
            assertEquals(0, callbacks.size)
        } finally {
            service.stop()
            executor.shutdownNow()
        }
    }

    @Test
    fun `queued actions use new policy while warnings cooldown and receipt dedupe survive reload`() {
        val file = temporaryDirectory.resolve("config.yml").toFile()
        fun writePolicy(enabled: Boolean, minimumWarnings: Int = 1) {
            file.writeText("""
                server-key: shd_00000000-0000-0000-0000-000000000001.${"a".repeat(64)}
                interventions:
                  kick:
                    enabled: $enabled
                    minimum-prior-warnings: $minimumWarnings
                    message: "重载后的踢出提示"
            """.trimIndent())
        }
        writePolicy(true)
        val config = ReloadableConfig(file)
        val now = 1_900_000_000_000L
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000010")
        fun kick(id: String) = PlayerInterventionDirective(id, playerId, InterventionKind.KICK, "test", now, now + 60_000)
        val ledgerPath = temporaryDirectory.resolve("ledger.properties")
        InterventionLedger(ledgerPath).apply {
            val warning = kick("warning").copy(kind = InterventionKind.WARN)
            complete(warning, InterventionOutcome.APPLIED, now - 1_000)
            markReceiptPersisted(warning.directiveId)
        }

        val callbacks = ArrayDeque<Consumer<ScheduledTask>>()
        val kicks = mutableListOf<Component>()
        val task = proxy<ScheduledTask> { name, _ -> error("Unexpected task call: $name") }
        fun schedule(name: String, args: Array<out Any?>): Any {
            check(name == "run")
            @Suppress("UNCHECKED_CAST")
            callbacks.addLast(args[1] as Consumer<ScheduledTask>)
            return task
        }
        val entityScheduler = proxy<EntityScheduler>(::schedule)
        val globalScheduler = proxy<GlobalRegionScheduler>(::schedule)
        val player = proxy<Player> { name, args ->
            when (name) {
                "isOnline" -> true
                "getScheduler" -> entityScheduler
                "kick" -> { kicks += args[0] as Component; null }
                else -> error("Unexpected player call: $name")
            }
        }
        val server = proxy<Server> { name, _ ->
            when (name) {
                "getGlobalRegionScheduler" -> globalScheduler
                "getPlayer" -> player
                else -> error("Unexpected server call: $name")
            }
        }
        val plugin = proxy<Plugin> { name, _ ->
            check(name == "getServer")
            server
        }
        val receipts = CopyOnWriteArrayList<InterventionReceipt>()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val service = PlayerInterventionService(
            plugin, { config.current.interventions }, ledgerPath, executor,
            { receipts += it; true }, Logger.getAnonymousLogger(),
            Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        )
        fun drainReceipts() = executor.submit {}.get(5, TimeUnit.SECONDS)
        try {
            service.start()
            service.accept(listOf(kick("disabled-after-dispatch")))
            callbacks.removeFirst().accept(task)
            writePolicy(false)
            config.reload()
            callbacks.removeFirst().accept(task)
            drainReceipts()
            assertEquals(InterventionOutcome.IGNORED_DISABLED, receipts.last().outcome)
            assertEquals(0, kicks.size)

            writePolicy(true, minimumWarnings = 2)
            config.reload()
            service.accept(listOf(kick("two-warnings-required")))
            assertEquals(InterventionOutcome.IGNORED_COOLDOWN, receipts.last().outcome)
            assertEquals(0, callbacks.size)

            writePolicy(true)
            config.reload()
            val accepted = kick("accepted")
            service.accept(listOf(accepted))
            callbacks.removeFirst().accept(task)
            callbacks.removeFirst().accept(task)
            drainReceipts()
            assertEquals(InterventionOutcome.APPLIED, receipts.last().outcome)
            assertEquals(listOf(PlayerInterventionService.kickComponent(config.current.interventions)), kicks)

            config.reload()
            service.accept(listOf(accepted, kick("cooldown")))
            assertEquals(listOf(InterventionOutcome.APPLIED, InterventionOutcome.IGNORED_COOLDOWN), receipts.takeLast(2).map { it.outcome })
            assertEquals(1, kicks.size)
            assertEquals(0, callbacks.size)
            val duplicate = assertIs<LedgerRegistration.ReceiptOnly>(
                InterventionLedger(ledgerPath).register(accepted, config.current.interventions, now),
            )
            assertEquals(InterventionOutcome.APPLIED, duplicate.receipt.outcome)
        } finally {
            service.stop()
            executor.shutdownNow()
        }
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
}
