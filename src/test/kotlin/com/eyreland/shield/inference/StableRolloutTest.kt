package com.eyreland.shield.inference

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StableRolloutTest {
    @Test
    fun `zero and full rollout are exact`() {
        val uuid = UUID.randomUUID()
        assertFalse(StableRollout(0.0, "v1").includes(uuid))
        assertTrue(StableRollout(100.0, "v1").includes(uuid))
    }

    @Test
    fun `selection is stable and approximately follows percentage`() {
        val rollout = StableRollout(10.0, "shield-gray-v1")
        val selected = (0 until 10_000).count { index ->
            val uuid = UUID.nameUUIDFromBytes(
                "player-$index".toByteArray(StandardCharsets.UTF_8),
            )
            assertEquals(rollout.includes(uuid), rollout.includes(uuid))
            rollout.includes(uuid)
        }

        assertTrue(selected in 900..1_100, "selected=$selected")
    }
}
