package com.gamixtv.MotionMagicAI.cloud

import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.data.ImageModelCatalog
import com.gamixtv.MotionMagicAI.data.VideoModelCatalog
import org.junit.Assert.*
import org.junit.Test

class CloudRateTest {
    @Test fun silentAndAudioPricesCannotBeInterchanged() {
        val silent = CloudRate("kling-720p-silent", "Kling", "kling-3-omni", "video", "720p", listOf("9:16"), listOf(5, 10), false, true)
        assertTrue(silent.matches(CreationDraft(), false))
        assertFalse(silent.matches(CreationDraft(generatedAudio = true), false))
        assertFalse(silent.matches(CreationDraft(resolution = "1080p"), false))
        assertFalse(silent.matches(CreationDraft(modelId = "pixverse-v6"), false))
        assertFalse(silent.matches(CreationDraft(), true))
    }
    @Test fun providerSpecificImageSizesAndSavedPixverseIdsRemainUsable() {
        assertEquals(listOf("1K"), ImageModelCatalog.find("nano-banana").resolutions)
        assertEquals(listOf("2K"), ImageModelCatalog.find("seedream-5-lite").resolutions)
        assertEquals("pixverse-v6", VideoModelCatalog.find("pixverse-v6-max").id)
        assertEquals("PixVerse V6", VideoModelCatalog.find("pixverse-v6-max").title)
    }
}
