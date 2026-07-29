package com.eyreland.shield.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InferenceConfigTest {
    @Test
    fun `legacy config uses HTTPS defaults when secret environment exists`() {
        val config = InferenceConfig.from(null) { "x".repeat(32) }

        assertTrue(config.enabled)
        assertEquals(
            "https://shield.i5res.com/v1/events",
            config.endpoint.toString(),
        )
        assertEquals(10.0, config.rolloutPercent)
        assertEquals(500, config.batchSize)
    }

    @Test
    fun `enabled transport requires external secret`() {
        assertFailsWith<IllegalArgumentException> {
            InferenceConfig.from(null) { null }
        }
    }
}
