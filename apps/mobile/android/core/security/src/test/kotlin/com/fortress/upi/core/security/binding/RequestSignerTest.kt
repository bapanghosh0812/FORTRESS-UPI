package com.fortress.upi.core.security.binding

import com.fortress.upi.core.crypto.sha256
import com.fortress.upi.core.crypto.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestSignerTest {

    /** Must equal goldenCanonicalHex in services/security-gateway/internal/devicesig/devicesig_test.go. */
    @Test
    fun canonicalRequestMatchesGatewayGoldenVector() {
        val canonical = RequestSigner.canonicalRequest(
            method = "POST",
            encodedPath = "/v1/offline/settlements",
            encodedQuery = "",
            bodySha256 = sha256(ByteArray(0)),
            timestampMillis = 1_700_000_000_000,
            nonce = ByteArray(16) { (it + 1).toByte() },
            deviceId = "dev_0123456789abcdef",
        )
        assertEquals(
            "0100000004504f535402000000172f76312f6f66666c696e652f736574746c656d656e747303000000000400000020" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85505000000080000018bcfe5680006000000100102030405060708090a0b0c0d0e0f10" +
                "07000000146465765f30313233343536373839616263646566",
            canonical.toHex(),
        )
    }
}
