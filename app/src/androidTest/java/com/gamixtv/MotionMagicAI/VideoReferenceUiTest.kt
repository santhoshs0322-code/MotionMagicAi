package com.gamixtv.MotionMagicAI

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.gamixtv.MotionMagicAI.cloud.CloudRate
import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.ui.CreationSettingsPanel
import com.gamixtv.MotionMagicAI.ui.MotionTheme
import com.gamixtv.MotionMagicAI.ui.VideoReferenceInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoReferenceUiTest {
    @get:Rule val ui = createComposeRule()

    private fun screenshot(name: String) {
        ui.mainClock.advanceTimeBy(300)
        ui.waitForIdle()
        val rendered = ui.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name.replace(".png", "-root.png")).outputStream().use {
            rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        rendered.recycle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun videoAndFourPhotosCoexistAndRemovalReenablesAdding() {
        var picked: Int? = -1
        ui.setContent {
            var photos by remember { mutableStateOf(listOf("one.png", "two.png", "three.png", "four.png")) }
            MotionTheme(ThemeMode.DARK) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    VideoReferenceInput("kling-o3-edit", listOf("kling-o3-edit", "kling-o3-reference"), "base.mp4", 4210,
                        photos, 4, {}, {}, {}, { picked = it }, { index -> photos = photos.filterIndexed { i, _ -> i != index } })
                }
            }
        }
        ui.onNodeWithTag("pick-base-video").assertExists()
        ui.onNodeWithText("Reference photos · 4/4 (optional)").assertExists()
        ui.onNodeWithTag("add-reference-photos").assertDoesNotExist()
        screenshot("video-reference-dark.png")
        ui.onNodeWithTag("reference-photo-0").performClick()
        assertEquals(0, picked)
        ui.onNodeWithTag("remove-reference-photo-0").performScrollTo().performClick()
        ui.onNodeWithText("Reference photos · 3/4 (optional)").assertExists()
        ui.onNodeWithTag("add-reference-photos").performScrollTo().assertIsEnabled().performClick()
        assertEquals(null, picked)
    }

    @Test fun newSceneAndEditAreDistinctAndPhotosRequireABaseClipInLightMode() {
        var selected = "kling-o3-reference"
        ui.setContent {
            var model by remember { mutableStateOf(selected) }
            MotionTheme(ThemeMode.LIGHT) {
                VideoReferenceInput(model, listOf("kling-o3-edit", "kling-o3-reference"), null, null,
                    emptyList(), 4, { model = it; selected = it }, {}, {}, {}, {})
            }
        }
        ui.onNodeWithTag("add-reference-photos").assertIsNotEnabled()
        screenshot("video-reference-light.png")
        ui.onNodeWithTag("workflow-kling-o3-edit").performClick()
        assertEquals("kling-o3-edit", selected)
        ui.onNodeWithText("Change the subject, look or setting of your existing clip. Length and frame follow the base video.").assertExists()
        ui.onNodeWithTag("workflow-kling-o3-reference").performClick()
        ui.onNodeWithText("Create a new scene guided by the base video's motion and camera. Choose the output length in settings.").assertExists()
    }

    @Test fun editSettingsLockLengthAndRatioAndExplainSilentReference() {
        val draft = CreationDraft(modelId = "kling-o3-edit", resolution = "1080p", duration = 5)
        val rates = listOf(false, true).map { sound -> CloudRate("edit-$sound", "Video Edit", draft.modelId, "video", "1080p",
            listOf("9:16", "16:9", "1:1"), (3..15).toList(), sound, false, creditPrices = mapOf(5 to 267L), maxReferenceImages = 4, durationFromSource = true) }
        ui.setContent {
            MotionTheme(ThemeMode.DARK) {
                CreationSettingsPanel(draft, false, rates, false, {}, {}, {}, referenceHasAudio = false, referenceDurationMs = 4210)
            }
        }
        ui.onNodeWithText("267 Credits").assertExists()
        screenshot("video-edit-settings.png")
        ui.onNodeWithTag("source-duration").performScrollTo().assertTextEquals("4.21s source · billed as 5s (rounded up)")
        ui.onNodeWithTag("ratio-9:16").assertDoesNotExist()
        ui.onNodeWithTag("audio-keep").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("audio-generate").assertDoesNotExist()
    }
}
