package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LongStoryHomeUiTest {
    @get:Rule val ui = createComposeRule()
    private val theme = mutableStateOf(ThemeMode.DARK)
    private var destination: Screen? = null

    @Test fun cinematicCardOpensTheStoryDraftAndKeepsHomeToolsInBothThemes() {
        ui.setContent {
            MotionTheme(theme.value) {
                Box(Modifier.fillMaxSize().systemBarsPadding().testTag("home-capture")) {
                    ReferenceCatalogContent(8975, theme.value, templates, false, {
                        theme.value = if(theme.value == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
                    }, { destination = it }, {}, banners = emptyList())
                }
            }
        }
        ui.onNodeWithTag("home-long-story").assertIsDisplayed()
        ui.onNodeWithTag("home-long-story-create").performClick()
        assertEquals(Screen.LONG_STORY, destination)
        ui.onNodeWithTag("home-long-story").performClick()
        assertEquals(Screen.LONG_STORY, destination)
        save("home-story-dark.png")
        ui.onNodeWithTag("home-catalog").performScrollToNode(hasText("Continue Story"))
        ui.onNodeWithText("Continue Story").performClick()
        assertEquals(Screen.STORY, destination)
        ui.onNodeWithTag("home-catalog").performScrollToIndex(0)
        ui.onNodeWithTag("theme-toggle").performClick()
        ui.onNodeWithTag("home-long-story").assertIsDisplayed()
        save("home-story-light.png")
        ui.onNodeWithTag("credit-balance").performClick()
        assertEquals(Screen.PLANS, destination)
    }

    private fun save(name: String) {
        ui.waitForIdle()
        // Give the small bundled raster decode time to publish its state.
        Thread.sleep(250)
        ui.waitForIdle()
        val bitmap = ui.onNodeWithTag("home-capture").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
