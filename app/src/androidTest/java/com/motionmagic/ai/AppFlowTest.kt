package com.motionmagic.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    private fun await(text: String, timeout: Long = 15_000) {
        app.waitUntil(timeout) { app.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun createEditAndDownloadVideo() {
        await("Image to Video")
        app.onNodeWithText("Image to Video").performClick()
        app.onNodeWithText("5s").performScrollTo().performClick()
        app.onNodeWithText("Generate Test Video").performScrollTo().performClick()
        await("Edit Further", 90_000)
        app.onNodeWithText("Edit Further").performScrollTo().performClick()
        app.waitUntil(20_000) {
            app.onAllNodes(hasText("Export") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        app.onNodeWithText("Text", substring = false).performScrollTo().performClick()
        app.onNode(hasSetTextAction()).performTextInput("Made with MotionMagic")
        app.onNodeWithText("Apply").performClick()
        app.onNodeWithText("Export", substring = false).performClick()
        await("Edit Further", 90_000)
        app.onNodeWithText("Download").performScrollTo().performClick()
        await("Saved to Movies / MotionMagic AI", 30_000)
        app.waitUntil(10_000) { app.onAllNodesWithText("Saved to Movies / MotionMagic AI").fetchSemanticsNodes().isEmpty() }
        app.onNodeWithText("Delete", substring = false).performScrollTo().performClick()
        app.onNodeWithText("Keep project").performClick()
        app.onNodeWithText("My Projects").performScrollTo().performClick()
        await("Made by you")
        app.onNodeWithText("Made by you").assertIsDisplayed()
    }

    @Test fun textValidationAndServicesShowOnlyAvailableTools() {
        await("Prompt Preview")
        app.onNodeWithText("Prompt Preview").performClick()
        app.onNode(hasSetTextAction()).performTextClearance()
        app.onNodeWithText("Generate Test Video").performScrollTo().performClick()
        await("Couldn’t finish that")
        app.onNodeWithText("OK").performClick()
        app.onNodeWithContentDescription("Back").performClick()
        app.onNodeWithText("SERVICES", substring = true).performClick()
        await("Available now")
        app.onNodeWithText("Photo animation").assertExists()
        app.onNodeWithText("Video and audio editing").performScrollTo().assertExists()
        app.onNodeWithText("Back to studio").performScrollTo().assertExists()
    }
}
