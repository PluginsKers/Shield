package com.eyreland.shield.storage

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaTest {
    @Test
    fun `missing older or newer schema versions require direct reset`() {
        assertTrue(Schema.requiresReset(null))
        assertTrue(Schema.requiresReset(1))
        assertFalse(Schema.requiresReset(2))
        assertTrue(Schema.requiresReset(3))
    }
}
