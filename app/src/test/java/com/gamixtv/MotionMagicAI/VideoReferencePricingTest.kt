package com.gamixtv.MotionMagicAI

import com.gamixtv.MotionMagicAI.cloud.CloudRate
import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.data.referenceBillableSeconds
import org.junit.Assert.*
import org.junit.Test

class VideoReferencePricingTest {
    @Test fun fractionalSourceLengthsReserveTheNextWholeSecond() {
        assertEquals(3, referenceBillableSeconds(3000))
        assertEquals(4, referenceBillableSeconds(3001))
        assertEquals(5, referenceBillableSeconds(4210))
        assertEquals(10, referenceBillableSeconds(10000))
        assertEquals(15, referenceBillableSeconds(15000))
        listOf(0L, 2999L, 15001L, Long.MAX_VALUE).forEach { duration ->
            assertThrows(IllegalArgumentException::class.java) { referenceBillableSeconds(duration) }
        }
    }

    @Test fun editEstimateUsesTheServersTotalAndSelectedAudioVariant() {
        val rate = CloudRate("edit-silent", "Video Edit", "kling-o3-edit", "video", "1080p", listOf("9:16"),
            (3..15).toList(), false, false, creditPrices = mapOf(5 to 267L, 10 to 534L), maxReferenceImages = 4, durationFromSource = true)
        val draft = CreationDraft(modelId = "kling-o3-edit", resolution = "1080p", duration = referenceBillableSeconds(4210))
        assertEquals(267L, rate.priceFor(draft, false))
        assertEquals(534L, rate.priceFor(draft.copy(duration = 10), false))
        assertNull(rate.priceFor(draft.copy(generatedAudio = true), false))
        assertNull(rate.priceFor(draft.copy(duration = 6), false))
    }
}
