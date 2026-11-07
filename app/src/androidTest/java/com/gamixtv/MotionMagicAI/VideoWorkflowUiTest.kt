package com.gamixtv.MotionMagicAI

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gamixtv.MotionMagicAI.cloud.CloudRate
import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.data.VideoModelCatalog
import com.gamixtv.MotionMagicAI.ui.CreationSettingsPanel
import com.gamixtv.MotionMagicAI.ui.MotionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VideoWorkflowUiTest {
    @get:Rule val ui = createComposeRule()
    private var selected = ""

    private fun show(story: Boolean) {
        // All models are enabled by the server; entry context must still filter the picker.
        val rates = VideoModelCatalog.models.map { model ->
            CloudRate(model.id, model.title, model.id, "video", "1080p", listOf("9:16", "16:9", "1:1"),
                (3..15).toList(), false, false, creditPrices = (3..15).associateWith { 25L * it })
        }
        ui.setContent {
            var draft by remember { mutableStateOf(CreationDraft(modelId = if(story) "kling-o3-reference" else "kling-3-omni", resolution = "1080p")) }
            MotionTheme(if(story) ThemeMode.LIGHT else ThemeMode.DARK) {
                CreationSettingsPanel(draft, false, rates, false, { draft = it }, {
                    selected = it.id
                    draft = draft.copy(modelId = it.id)
                }, {}, storyModelsOnly = story)
            }
        }
    }

    @Test fun imageVideoOnlyOffersImageVideoModels() {
        show(false)
        ui.onNodeWithTag("model-picker").performClick()
        ui.onNodeWithTag("model-option-Kling O3").assertIsDisplayed()
        ui.onNodeWithTag("model-option-PixVerse V6").assertIsDisplayed()
        ui.onNodeWithTag("model-option-Kling O3 Story Reference").assertDoesNotExist()
        ui.onNodeWithTag("model-option-Kling O3 Video Edit").assertDoesNotExist()
        ui.onNodeWithTag("model-option-PixVerse V6").performClick()
        assertEquals("pixverse-v6", selected)
        ui.onNodeWithTag("model-picker").performClick()
        ui.onNodeWithTag("model-option-Kling O3 Story Reference").assertDoesNotExist()
    }

    @Test fun continueStoryOnlyOffersStoryModels() {
        show(true)
        ui.onNodeWithText("Story Settings").assertIsDisplayed()
        ui.onNodeWithTag("model-picker").performClick()
        ui.onNodeWithTag("model-option-Kling O3 Story Reference").assertIsDisplayed()
        ui.onNodeWithTag("model-option-Kling O3 Video Edit").assertIsDisplayed()
        ui.onNodeWithTag("model-option-Kling O3").assertDoesNotExist()
        ui.onNodeWithTag("model-option-PixVerse V6").assertDoesNotExist()
        ui.onNodeWithTag("model-option-Kling O3 Video Edit").performClick()
        assertEquals("kling-o3-edit", selected)
        ui.onNodeWithTag("model-picker").performClick()
        ui.onNodeWithTag("model-option-PixVerse V6").assertDoesNotExist()
    }
}
