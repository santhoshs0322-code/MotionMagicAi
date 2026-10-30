package com.gamixtv.MotionMagicAI

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AuthUiTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    @Test fun guestSeesOnlyLoginUntilGoogleSignInSucceeds() {
        app.waitUntil(15000) { app.onAllNodesWithTag("login-screen").fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithText("Continue with Google").assertExists()
        app.onNodeWithText("Image to Video").assertDoesNotExist()
    }
}
