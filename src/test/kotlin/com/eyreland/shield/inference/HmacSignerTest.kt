package com.eyreland.shield.inference

import kotlin.test.Test
import kotlin.test.assertEquals

class HmacSignerTest {
    @Test
    fun `matches Python service canonical signature`() {
        val headers = HmacSigner.sign(
            keyId = "gray-key",
            secret = "0123456789abcdef0123456789abcdef",
            timestamp = 1_785_300_000,
            nonce = "0123456789abcdef0123456789abcdef",
            transmittedBody = "hello-shield".encodeToByteArray(),
        )

        assertEquals(
            "3115974e2a7f6f5828ae7ce7501160e25e300ec437dd40aa8235528508d08627",
            headers.signature,
        )
    }
}
