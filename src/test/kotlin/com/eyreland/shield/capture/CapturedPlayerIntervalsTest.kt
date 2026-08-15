package com.eyreland.shield.capture

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapturedPlayerIntervalsTest {
    @Test
    fun `each telemetry interval has an independent captured set`() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val intervals = CapturedPlayerIntervals()

        intervals.mark(first)
        assertTrue(intervals.contains(first))
        assertEquals(setOf(first), intervals.drain())
        assertFalse(intervals.contains(first))

        intervals.mark(second)
        assertEquals(setOf(second), intervals.drain())
        assertEquals(emptySet(), intervals.drain())
    }
}
