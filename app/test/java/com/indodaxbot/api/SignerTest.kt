package com.indodaxbot.api

import org.junit.Assert.assertEquals
import org.junit.Test

class SignerTest {
    @Test
    fun hmacSha256_matchesRfc4231Vector() {
        // RFC 4231 test case 2 (independent of Indodax docs).
        val sig = Signer.hmacSha256Hex("Jefe", "what do ya want for nothing?")
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", sig)
    }

    @Test
    fun hmacSha256_isLowercaseHex64Chars() {
        val sig = Signer.hmacSha256Hex("secret", "symbol=btcidr&timestamp=1")
        assertEquals(64, sig.length)
        assertEquals(sig.lowercase(), sig)
    }

    @Test
    fun buildQuery_keepsOrderAndEncodes() {
        val q = Signer.buildQuery(linkedMapOf("symbol" to "BTCIDR", "x" to "a b&c", "timestamp" to "5"))
        assertEquals("symbol=BTCIDR&x=a+b%26c&timestamp=5", q)
    }

    @Test
    fun credentials_toStringIsRedacted() {
        val c = Credentials("KEY123", "SECRET456")
        assertEquals("Credentials(***)", c.toString())
    }
}
