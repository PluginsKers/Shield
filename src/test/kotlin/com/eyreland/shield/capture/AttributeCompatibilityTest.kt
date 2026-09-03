package com.eyreland.shield.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class AttributeCompatibilityTest {
    @Test
    fun `tries current and legacy attribute keys`() {
        assertEquals(
            listOf("movement_speed", "generic.movement_speed"),
            attributeKeys("movement_speed"),
        )
    }
}
