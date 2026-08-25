package com.eyreland.shield.capture

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CapturedPlayerIntervalsTest {
    @Test
    fun `each telemetry interval has an independent captured set`() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val intervals = CapturedPlayerIntervals()

        intervals.mark(first)
        assertEquals(setOf(first), intervals.drain())
        assertEquals(emptySet(), intervals.drain())

        intervals.mark(second)
        assertEquals(setOf(second), intervals.drain())
        assertEquals(emptySet(), intervals.drain())
    }
}
