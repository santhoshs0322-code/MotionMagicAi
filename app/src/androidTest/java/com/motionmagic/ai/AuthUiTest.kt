package com.motionmagic.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AuthUiTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    @Test fun guestCanReachGoogleSignInAndReturnToStudio() {
        app.waitUntil(15000) { app.onAllNodesWithText("Profile").fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithText("Profile").performClick()
        app.waitUntil(15000) { app.onAllNodesWithText("Sign in with Google").fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithText("Sign in with Google").performScrollTo().assertIsEnabled()
        app.onNodeWithText("Home", substring = false).performClick()
        app.onNodeWithText("Image to Video").assertIsDisplayed()
    }
}
