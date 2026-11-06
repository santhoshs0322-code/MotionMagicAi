package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.auth.AuthStatus
import com.gamixtv.MotionMagicAI.auth.AuthUiState
import com.gamixtv.MotionMagicAI.auth.LoginScreen
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.ui.MotionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class LoginLayoutUiTest {
    @get:Rule val ui = createComposeRule()

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun assertCentered(tag: String) {
        val screen = ui.onNodeWithTag("login-screen").fetchSemanticsNode().boundsInRoot
        val bounds = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertEquals(screen.center.x, bounds.center.x, 2f)
    }

    @Test fun headingsAndButtonAreCenteredInBothThemes() {
        val theme = mutableStateOf(ThemeMode.DARK)
        var clicks = 0
        ui.setContent { MotionTheme(theme.value) {
            LoginScreen(AuthUiState(configured = true), { clicks++ }, {}, {})
        } }
        for(mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }
            ui.onNodeWithTag("login-brand").performScrollTo().assertIsDisplayed()
            assertCentered("login-brand")
            assertCentered("login-heading")
            ui.onNodeWithTag("google-sign-in").performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertCentered("google-sign-in")
            ui.onNodeWithText("Welcome to your studio").assertIsDisplayed()
            screenshot("login-${mode.name.lowercase()}.png")
        }
        ui.onNodeWithTag("google-sign-in").performClick()
        assertEquals(1, clicks)
    }

    @Test fun longErrorAndDismissRemainAccessibleOnCompactScreenWithLargeText() {
        val state = mutableStateOf(AuthUiState(message = "Google sign-in could not finish. Check your internet connection and try again. Your existing projects and credits remain linked to your account.", configured = false))
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                MotionTheme(ThemeMode.DARK) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(320.dp, 520.dp)) {
                            LoginScreen(state.value, {}, {}, { state.value = state.value.copy(message = null, configured = true) })
                        }
                    }
                }
            }
        }
        ui.onNodeWithTag("login-heading").performScrollTo().assertIsDisplayed()
        assertCentered("login-heading")
        ui.onNodeWithTag("login-message").performScrollTo().assertIsDisplayed()
        assertCentered("login-notice")
        ui.onNodeWithText("Sign-in is being configured. Please try again shortly.").assertDoesNotExist()
        ui.onNodeWithText("Dismiss").performScrollTo().assertIsDisplayed()
        screenshot("login-compact-error.png")
        ui.onNodeWithText("Dismiss").performClick()
        ui.onNodeWithTag("login-message").assertDoesNotExist()
        ui.onNodeWithTag("google-sign-in").performScrollTo().assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithTag("login-footer").performScrollTo().assertIsDisplayed()
    }

    @Test fun signedOutNoticeCanBeDismissedAndBusySignInCannotBeTappedAgain() {
        val state = mutableStateOf(AuthUiState(configured = true, message = "Signed out. Projects remain on this device.", messageIsError = false))
        var clicks = 0
        ui.setContent { MotionTheme(ThemeMode.LIGHT) {
            LoginScreen(state.value, { clicks++ }, {}, { state.value = state.value.copy(message = null) })
        } }
        ui.onNodeWithTag("login-message").performScrollTo().assertIsDisplayed()
        assertCentered("login-notice")
        screenshot("login-signed-out.png")
        ui.onNodeWithText("Dismiss").performScrollTo().performClick()
        ui.onNodeWithTag("login-message").assertDoesNotExist()
        ui.runOnIdle { state.value = state.value.copy(status = AuthStatus.SIGNING_IN, busy = true) }
        ui.onNodeWithTag("google-sign-in").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("Opening Google sign-in…").assertIsDisplayed()
        assertEquals(0, clicks)
    }
}
