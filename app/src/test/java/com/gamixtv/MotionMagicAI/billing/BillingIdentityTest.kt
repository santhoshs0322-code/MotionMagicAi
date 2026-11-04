package com.gamixtv.MotionMagicAI.billing

import org.junit.Assert.*
import org.junit.Test

class BillingIdentityTest {
    @Test fun checkoutIdentityMatchesTheServerAndSeparatesAccounts() {
        // Python hashlib.sha256(account UUID encoded as UTF-8).hexdigest().
        val first = playAccountId("00000000-0000-0000-0000-000000000001")
        assertEquals("7ac1b8d7010bb6cd3a3e84e7f90136b880bbc899e428ece49333372911ab9052", first)
        assertNotEquals(first, playAccountId("00000000-0000-0000-0000-000000000002"))
    }
}
