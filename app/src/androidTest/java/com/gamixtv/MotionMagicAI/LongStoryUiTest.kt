package com.gamixtv.MotionMagicAI

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gamixtv.MotionMagicAI.cloud.CloudRate
import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.data.StoryDurations
import androidx.compose.ui.semantics.SemanticsActions
import com.gamixtv.MotionMagicAI.ui.LongStoryContent
import com.gamixtv.MotionMagicAI.ui.MotionTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LongStoryUiTest {
    @get:Rule val ui = createComposeRule()
    private var created = false
    private var updated: CreationDraft? = null

    private fun show(enabled: Boolean, theme: ThemeMode) {
        val rates = if(enabled) listOf(CloudRate("long", "Long Story", "long-story", "video", "1080p",
            listOf("9:16", "16:9", "1:1"), StoryDurations.seconds, true, false,
            creditPrices = StoryDurations.seconds.associateWith { it * 1000L / 60 })) else emptyList()
        ui.setContent {
            var draft by remember { mutableStateOf(CreationDraft(modelId="long-story", resolution="1080p", duration=60, generatedAudio=true)) }
            MotionTheme(theme) {
                LongStoryContent(draft, emptyList(), null, rates, { draft=it; updated=it }, {}, {}, {}, {}, { created=true }, {})
            }
        }
    }

    @Test fun fiveMinuteSelectionUpdatesTheCompleteQuoteInDarkMode() {
        show(true, ThemeMode.DARK)
        ui.onNodeWithText("Character photos · optional").assertIsDisplayed()
        ui.onNodeWithText("1000 credits").assertIsDisplayed()
        ui.onNodeWithText("Story length").performScrollTo()
        ui.onNodeWithTag("story-minutes-5").performScrollTo().assertIsDisplayed().performClick()
        ui.waitForIdle()
        val bitmap = ui.onNodeWithTag("long-story-screen").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "long-story-dark.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        ui.onNodeWithText("5000 credits").assertIsDisplayed()
        assertEquals(300, updated?.duration)
        ui.onNodeWithTag("create-long-story").performClick()
        assertTrue(created)
    }

    @Test fun unavailableServerCannotStartAPaidStoryInLightMode() {
        show(false, ThemeMode.LIGHT)
        ui.onNodeWithTag("create-long-story").assertIsNotEnabled()
        ui.onNodeWithText("Add character photo").assertIsDisplayed()
        ui.onNodeWithText("Add reference video").performScrollTo().assertIsDisplayed()
        assertFalse(created)
    }

    @Test fun halfMinuteSliderUpdatesPriceAndSceneCount() {
        show(true, ThemeMode.LIGHT)
        ui.onNodeWithTag("story-duration-slider").performScrollTo()
        for(seconds in listOf(90,150,210,270)) {
            ui.onNodeWithTag("story-duration-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(seconds.toFloat()) }
            ui.onNodeWithText(StoryDurations.label(seconds)).assertIsDisplayed()
            ui.onNodeWithText("${seconds * 1000L / 60} credits").assertIsDisplayed()
            assertEquals(seconds, updated?.duration)
        }
    }
}
