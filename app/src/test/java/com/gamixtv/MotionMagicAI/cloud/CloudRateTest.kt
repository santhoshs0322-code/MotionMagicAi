package com.gamixtv.MotionMagicAI.cloud

import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.data.ImageModelCatalog
import com.gamixtv.MotionMagicAI.data.VideoModelCatalog
import org.junit.Assert.*
import org.junit.Test

class CloudRateTest {
    @Test fun silentAndAudioPricesCannotBeInterchanged() {
        val silent = CloudRate("kling-720p-silent", "Kling", "kling-3-omni", "video", "720p", listOf("9:16"), listOf(5, 10), false, true)
        val klingDraft = CreationDraft(modelId = "kling-3-omni")
        assertTrue(silent.matches(klingDraft, false))
        assertFalse(silent.matches(klingDraft.copy(generatedAudio = true), false))
        assertFalse(silent.matches(klingDraft.copy(resolution = "1080p"), false))
        assertFalse(silent.matches(CreationDraft(), false))
        assertFalse(silent.matches(klingDraft, true))
    }
    @Test fun providerSpecificImageSizesAndSavedPixverseIdsRemainUsable() {
        assertEquals("qwen-image", ImageModelCatalog.find("nano-banana").id)
        assertEquals(listOf("2K"), ImageModelCatalog.find("seedream-5-lite").resolutions)
        assertEquals("pixverse-v6", VideoModelCatalog.find("pixverse-v6-max").id)
        assertEquals("PixVerse V6", VideoModelCatalog.find("pixverse-v6-max").title)
    }
    @Test fun creditEstimateFollowsDurationAudioAndAspect() {
        val silent = CloudRate("kling-silent", "Kling", "kling-3-omni", "video", "720p",
            listOf("9:16"), listOf(5, 10), false, false, creditPrices = mapOf(5 to 150, 10 to 300))
        val draft = CreationDraft(modelId = "kling-3-omni", generatedAudio = false, duration = 10)
        assertEquals(300L, silent.priceFor(draft, false))
        assertEquals(150L, silent.priceFor(draft.copy(duration = 5), false))
        assertNull(silent.priceFor(draft.copy(generatedAudio = true), false))
        assertNull(silent.priceFor(draft.copy(aspect = "16:9"), false))
    }
}
