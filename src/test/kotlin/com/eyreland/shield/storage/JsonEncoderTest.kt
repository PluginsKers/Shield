package com.eyreland.shield.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonEncoderTest {
    @Test
    fun `encodes nested values and escapes control characters`() {
        val value = linkedMapOf<String, Any?>(
            "text" to "a\n\"b\\c",
            "values" to listOf(1, true, null),
            "nested" to linkedMapOf("中文" to 2.5),
        )

        assertEquals(
            "{\"text\":\"a\\n\\\"b\\\\c\",\"values\":[1,true,null],\"nested\":{\"中文\":2.5}}",
            JsonEncoder.encode(value),
        )
    }

    @Test
    fun `non-finite floating point values become json null`() {
        assertEquals("[null,null]", JsonEncoder.encode(listOf(Double.NaN, Float.POSITIVE_INFINITY)))
    }
}
