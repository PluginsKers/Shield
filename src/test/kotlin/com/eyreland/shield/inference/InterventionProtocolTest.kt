package com.eyreland.shield.inference

import com.eyreland.shield.config.InterventionConfig
import net.kyori.adventure.text.TextComponent
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InterventionProtocolTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `ack decoder accepts bounded v2 directives`() {
        val directives = InferenceAckDecoder.decode(
            """
            {
              "schema_version":"shield.events.ack.v2",
              "player_interventions":[{
                "directive_id":"intervention-test",
                "player_uuid":"00000000-0000-0000-0000-000000000010",
                "kind":"warn",
                "reason":"persistent_priority_anomaly",
                "issued_at_ms":1900000000000,
                "expires_at_ms":1900001800000
              }]
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(1, directives.size)
        assertEquals(InterventionKind.WARN, directives.single().kind)
        assertEquals(
            UUID.fromString("00000000-0000-0000-0000-000000000010"),
            directives.single().playerUuid,
        )
    }

    @Test
    fun `ack decoder rejects directives outside v2 contract`() {
        assertFailsWith<IllegalArgumentException> {
            InferenceAckDecoder.decode(
                """
                {"schema_version":"shield.events.ack.v1","player_interventions":[{
                  "directive_id":"intervention-test",
                  "player_uuid":"00000000-0000-0000-0000-000000000010",
                  "kind":"warn","reason":"test",
                  "issued_at_ms":2,"expires_at_ms":1
                }]}
                """.trimIndent().encodeToByteArray(),
            )
        }
    }

    @Test
    fun `ack directive must belong to current request batch`() {
        val expected = UUID.fromString("00000000-0000-0000-0000-000000000030")
        val body = """
            {
              "schema_version":"shield.events.ack.v2",
              "batch_id":"00000000-0000-0000-0000-000000000031",
              "player_interventions":[{
                "directive_id":"intervention-test",
                "player_uuid":"00000000-0000-0000-0000-000000000010",
                "kind":"warn","reason":"test",
                "issued_at_ms":1,"expires_at_ms":2
              }]
            }
        """.trimIndent().encodeToByteArray()

        assertFailsWith<IllegalArgumentException> {
            InferenceAckDecoder.decode(body, expected)
        }
    }

    @Test
    fun `ledger persists dedupe and enforces long warning cooldown`() {
        val path = temporaryDirectory.resolve("ledger.properties")
        val config = InterventionConfig.defaults()
        val now = 1_900_000_000_000
        val first = directive("first", now)
        val ledger = InterventionLedger(path)

        assertIs<LedgerRegistration.Ready>(ledger.register(first, config, now))
        val receipt = assertNotNull(
            ledger.complete(first, InterventionOutcome.APPLIED, now),
        )
        assertEquals(InterventionOutcome.APPLIED, receipt.outcome)
        assertEquals(now, receipt.handledAtMs)
        assertNull(ledger.complete(first, InterventionOutcome.EXPIRED, now + 1))
        assertEquals(1, InterventionLedger(path).pendingReceipts().size)
        ledger.markReceiptPersisted(first.directiveId)
        assertEquals(0, InterventionLedger(path).pendingReceipts().size)
        val duplicate = assertIs<LedgerRegistration.ReceiptOnly>(
            InterventionLedger(path).register(first, config, now + 1),
        )
        assertEquals(InterventionOutcome.APPLIED, duplicate.receipt.outcome)

        val second = directive("second", now + 60_000)
        val cooledDown = assertIs<LedgerRegistration.ReceiptOnly>(
            InterventionLedger(path).register(second, config, now + 60_000),
        )
        assertEquals(InterventionOutcome.IGNORED_COOLDOWN, cooledDown.receipt.outcome)
    }

    @Test
    fun `directive id cannot be reused with different content`() {
        val now = 1_900_000_000_000
        val first = directive("collision", now)
        val ledger = InterventionLedger(temporaryDirectory.resolve("collision.properties"))
        assertIs<LedgerRegistration.Ready>(
            ledger.register(first, InterventionConfig.defaults(), now),
        )

        assertFailsWith<IllegalArgumentException> {
            ledger.register(
                first.copy(reason = "different"),
                InterventionConfig.defaults(),
                now,
            )
        }
    }

    @Test
    fun `player notices use plain text without a prefix`() {
        val title = PlayerInterventionService.warningTitle()

        val text = assertIs<TextComponent>(title)
        assertEquals("行为提示", text.content())
        assertNull(text.color())

        val config = InterventionConfig.defaults()
        val kick = assertIs<TextComponent>(PlayerInterventionService.kickComponent(config))
        assertEquals(config.kick.message, kick.content())
        assertNull(kick.color())
        assertEquals(emptyList(), kick.children())
    }

    @Test
    fun `local gate rejects kick without successful warning history`() {
        val config = InterventionConfig.defaults().let { it.copy(kick = it.kick.copy(enabled = true)) }
        val now = 1_900_000_000_000
        val kick = directive("kick-first", now, InterventionKind.KICK)

        val result = assertIs<LedgerRegistration.ReceiptOnly>(
            InterventionLedger(temporaryDirectory.resolve("kick-ledger.properties"))
                .register(kick, config, now),
        )

        assertEquals(InterventionOutcome.IGNORED_COOLDOWN, result.receipt.outcome)
    }

    @Test
    fun `local gate admits configured kick after successful warning`() {
        val config = InterventionConfig.from(YamlConfiguration().apply {
            set("interventions.kick.enabled", true)
        })
        val now = 1_900_000_000_000
        val ledger = InterventionLedger(temporaryDirectory.resolve("kick-ready.properties"))
        val warning = directive("warning", now)
        assertIs<LedgerRegistration.Ready>(ledger.register(warning, config, now))
        ledger.complete(warning, InterventionOutcome.APPLIED, now)

        val kick = directive("kick", now + 1, InterventionKind.KICK)
        assertIs<LedgerRegistration.Ready>(ledger.register(kick, config, now + 1))
        ledger.complete(kick, InterventionOutcome.APPLIED, now + 2)
        val receipt = InterventionLedger(temporaryDirectory.resolve("kick-ready.properties"))
            .pendingReceipts().single { it.kind == InterventionKind.KICK }
        assertEquals(kick.directiveId, receipt.directiveId)
        assertEquals(InterventionOutcome.APPLIED, receipt.outcome)
        assertEquals(now + 2, receipt.handledAtMs)
    }

    @Test
    fun `restart never repeats an action whose dispatch outcome is unknown`() {
        val path = temporaryDirectory.resolve("recovery-ledger.properties")
        val now = 1_900_000_000_000
        val warning = directive("recovery", now)
        val ledger = InterventionLedger(path)
        assertIs<LedgerRegistration.Ready>(
            ledger.register(warning, InterventionConfig.defaults(), now),
        )
        assertEquals(true, ledger.markDispatched(warning.directiveId))

        val reopened = InterventionLedger(path)
        reopened.recoverInterrupted(now + 1)

        assertEquals(0, reopened.pending().size)
        assertEquals(
            InterventionOutcome.INVALID,
            reopened.pendingReceipts().single().outcome,
        )
    }

    private fun directive(
        id: String,
        issuedAtMs: Long,
        kind: InterventionKind = InterventionKind.WARN,
    ) = PlayerInterventionDirective(
        directiveId = "intervention-$id",
        playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000010"),
        kind = kind,
        reason = "persistent_priority_anomaly",
        issuedAtMs = issuedAtMs,
        expiresAtMs = issuedAtMs + 30 * 60 * 1_000,
    )
}
