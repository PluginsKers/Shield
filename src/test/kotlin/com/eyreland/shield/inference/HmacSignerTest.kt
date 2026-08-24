package com.eyreland.shield.inference

import kotlin.test.Test
import kotlin.test.assertEquals

class HmacSignerTest {
    @Test
    fun `matches Python service canonical signature`() {
        val headers = HmacSigner.sign(
            keyId = "shield-key",
            secret = "0123456789abcdef0123456789abcdef",
            timestamp = 1_785_300_000,
            nonce = "0123456789abcdef0123456789abcdef",
            transmittedBody = "hello-shield".encodeToByteArray(),
        )

        assertEquals(
            "5bb835de5c6bf7aca1a0b50e60ace845620497f404844719b6c6eb3150bf6d1a",
            headers.signature,
        )
    }
}
