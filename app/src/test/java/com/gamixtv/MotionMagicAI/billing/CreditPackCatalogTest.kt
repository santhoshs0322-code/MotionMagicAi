package com.gamixtv.MotionMagicAI.billing

import org.junit.Assert.*
import org.junit.Test

class CreditPackCatalogTest {
    @Test fun saleCatalogReplaces500With20000() {
        assertEquals(setOf("motion_pack_2500", "motion_pack_5000", "motion_pack_10000", "motion_pack_20000"), PlayBilling.creditPacks)
        assertFalse(PlayBilling.TOPUP_500 in PlayBilling.creditPacks)
    }

    @Test fun oldPaymentsRemainRecoverableWithoutReturningToSale() {
        assertTrue(PlayBilling.TOPUP_500 in PlayBilling.recoverableCreditPacks)
        assertTrue("motion_topup_500" in PlayBilling.recoverableCreditPacks)
        assertTrue(PlayBilling.PACK_20000 in PlayBilling.recoverableCreditPacks)
    }
}
