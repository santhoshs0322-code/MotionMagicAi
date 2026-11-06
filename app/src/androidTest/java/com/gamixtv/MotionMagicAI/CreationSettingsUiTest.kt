package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.cloud.CloudRate
import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.data.ThemeMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.content.res.Configuration
import com.gamixtv.MotionMagicAI.ui.CreationSettingsPanel
import com.gamixtv.MotionMagicAI.ui.MotionTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class CreationSettingsUiTest {
    @get:Rule val ui = createComposeRule()
    private var latest = CreationDraft()
    private val rates = listOf(false, true).map { sound ->
        CloudRate("kling-$sound", "Kling O3", "kling-3-omni", "video", "720p", listOf("16:9", "9:16", "1:1"),
            (3..15).toList(), sound, true, creditPrices = (3..15).associateWith { it * if(sound) 35L else 28L })
    } + listOf("1K", "2K").map { size ->
        CloudRate("image-$size", "GPT Image", "gpt-image-2-5-s", "image", size,
            listOf("1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"), emptyList(), null, false,
            creditPrices = mapOf(1 to 25L))
    }

    private fun show(still: Boolean, reference: Boolean = false, referenceHasAudio: Boolean? = null, theme: ThemeMode = ThemeMode.DARK, fontScale: Float = 1f) {
        ui.setContent {
            var draft by remember { mutableStateOf(CreationDraft(modelId = if(reference) "kling-o3-reference" else "kling-3-omni",
                resolution = if(reference) "1080p" else "720p", generatedAudio = reference, imageModelId = "gpt-image-2-5-s", imageQuality = "1K")) }
            latest = draft
            val configuration = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalConfiguration provides configuration) {
            MotionTheme(theme) {
                Box(Modifier.fillMaxSize().testTag("capture-root")) {
                    run {
                        val choices = if(reference) listOf(false, true).map { sound ->
                            CloudRate("reference-$sound", "Kling O3 Story Reference", "kling-o3-reference", "video", "1080p",
                                listOf("16:9", "9:16", "1:1"), (3..15).toList(), sound, false, creditPrices = (3..15).associateWith { 53L * it })
                        } else rates
                        CreationSettingsPanel(draft, still, choices, false, { draft = it }, { draft = draft.copy(modelId = it.id) }, {}, referenceHasAudio)
                    }
                }
            }
            }
        }
    }

    @Test fun largeTextKeepsBackAndCostVisibleWhileSettingsScroll() {
        show(false, theme = ThemeMode.LIGHT, fontScale = 1.5f)
        ui.onNodeWithText("Video Settings").assertIsDisplayed()
        ui.onNodeWithTag("audio-generate").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithText("Estimated cost").assertIsDisplayed()
        ui.onNodeWithTag("settings-done").assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithContentDescription("Close settings").assertIsDisplayed()
        screenshot("settings-large-text.png")
    }

    @Test fun silentReferenceCannotEnableAudioAndExplainsWhy() {
        show(false, reference = true, referenceHasAudio = false)
        ui.onNodeWithTag("audio-keep").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        ui.onNodeWithTag("audio-generate").assertDoesNotExist()
        ui.onNodeWithText("Your reference has no audio track. This model cannot generate new sound.").performScrollTo().assertIsDisplayed()
        screenshot("settings-silent-reference.png")
    }

    @Test fun audibleReferenceCanKeepItsExistingAudioOrTurnItOff() {
        show(false, reference = true, referenceHasAudio = true)
        ui.onNodeWithText("Keeps existing sound only. Silent video stays silent.").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("audio-mute").performScrollTo().assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(false, latest.generatedAudio) }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        ui.onNodeWithTag("capture-root").captureToImage().asAndroidBitmap().let { bitmap ->
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun videoControlsKeepHeaderVisibleAndValidateTypedDuration() {
        show(false)
        ui.onNodeWithText("Video Settings").assertIsDisplayed()
        ui.onNodeWithTag("audio-generate").performScrollTo().assertIsDisplayed().performClick()
        ui.runOnIdle { assertEquals(true, latest.generatedAudio) }
        ui.onNodeWithContentDescription("Type duration").performScrollTo().performClick()
        ui.onNodeWithTag("duration-input").performTextClearance()
        ui.onNodeWithTag("duration-input").performTextInput("16")
        ui.onNodeWithText("Apply").assertIsNotEnabled()
        ui.onNodeWithTag("duration-input").performTextClearance()
        ui.onNodeWithTag("duration-input").performTextInput("7")
        ui.onNodeWithText("Apply").performClick()
        ui.runOnIdle { assertEquals(7, latest.duration) }
        ui.onNodeWithTag("ratio-1:1").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("Close settings").assertIsDisplayed()
        ui.onNodeWithTag("model-picker").performScrollTo()
        screenshot("settings-video.png")
    }

    @Test fun longModelNameAndImageRatiosFitTheSettingsPanel() {
        show(true, theme = ThemeMode.LIGHT)
        ui.onNodeWithTag("model-picker").assertIsDisplayed().performClick()
        ui.onNodeWithTag("model-option-Seedream 5.0 Lite").assertDoesNotExist()
        screenshot("settings-models.png")
        ui.onNodeWithTag("model-option-GPT Image 2.5 Sunburst").performClick()
        ui.onNodeWithTag("resolution-1K").assertIsDisplayed()
        ui.onNodeWithTag("resolution-2K").assertIsDisplayed()
        ui.onNodeWithTag("ratio-21:9").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("Close settings").assertIsDisplayed()
        assertEquals(ui.onNodeWithText("1:1").fetchSemanticsNode().boundsInRoot.top,
            ui.onNodeWithText("3:2").fetchSemanticsNode().boundsInRoot.top, 1f)
        screenshot("settings-image.png")
    }

    @Test fun referenceAudioAndCreditEstimateRemainCorrectInLightMode() {
        show(false, reference = true, referenceHasAudio = true, theme = ThemeMode.LIGHT)
        ui.onNodeWithTag("audio-generate").assertDoesNotExist()
        ui.onNodeWithTag("audio-mute").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(false, latest.generatedAudio) }
        ui.onNodeWithText("530 Credits").assertIsDisplayed()
        ui.onNodeWithTag("settings-done").assertIsDisplayed().assertIsEnabled()
        screenshot("settings-reference-light.png")
    }
}
