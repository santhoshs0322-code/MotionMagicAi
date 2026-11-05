package com.gamixtv.MotionMagicAI.data

import org.junit.Assert.*
import org.junit.Test

class BannerLoopTest {
    @Test fun fiveBannersAdvanceInOrderAndWrapWithoutAReverseSweep() {
        var page = 1
        val displayed = mutableListOf<Int>()
        repeat(12) {
            displayed += bannerIndex(page, 5) + 1
            page += 1
            page = bannerRecenter(page, 5) ?: page
        }
        assertEquals(listOf(1,2,3,4,5,1,2,3,4,5,1,2), displayed)
    }
    @Test fun reverseSwipesAndSingleBannersStayWithinBounds() {
        assertEquals(4, bannerIndex(0, 5))
        assertEquals(5, bannerRecenter(0, 5))
        assertEquals(1, bannerRecenter(6, 5))
        assertNull(bannerRecenter(3, 5))
        assertNull(bannerRecenter(0, 1))
        assertEquals(0, bannerIndex(0, 1))
    }
}
