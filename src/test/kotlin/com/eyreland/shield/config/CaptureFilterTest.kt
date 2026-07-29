package com.eyreland.shield.config

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureFilterTest {
    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `non-empty include dimensions are combined with and`() {
        val filter = CaptureFilter(
            includeNames = setOf("alice"),
            includeWorlds = setOf("world"),
            includeProtocols = setOf(774),
        )

        assertTrue(filter.allows(subject(name = "Alice", world = "WORLD", protocol = 774)))
        assertFalse(filter.allows(subject(name = "Alice", world = "nether", protocol = 774)))
        assertFalse(filter.allows(subject(name = "Alice", world = "world", protocol = 773)))
    }

    @Test
    fun `any exclude dimension rejects subject`() {
        val filter = CaptureFilter(
            excludeGameModes = setOf("SPECTATOR"),
            excludeNames = setOf("blocked"),
        )

        assertFalse(filter.allows(subject(gameMode = "spectator")))
        assertFalse(filter.allows(subject(name = "Blocked")))
        assertTrue(filter.allows(subject(gameMode = "survival")))
    }

    @Test
    fun `packet action rules fail closed when include list cannot be evaluated`() {
        val rule = PacketRule(
            rateLimitGroup = "combat",
            includeActions = setOf("ATTACK"),
            excludeActions = setOf("INTERACT"),
        )

        assertTrue(rule.allowsAction("attack"))
        assertFalse(rule.allowsAction("INTERACT"))
        assertFalse(rule.allowsAction(null))
    }

    private fun subject(
        name: String = "Alice",
        world: String? = "world",
        gameMode: String? = "survival",
        protocol: Int? = 774,
    ) = CaptureSubject(uuid, name, world, gameMode, protocol)
}
