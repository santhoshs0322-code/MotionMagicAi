package com.gamixtv.MotionMagicAI

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.data.*
import com.gamixtv.MotionMagicAI.ui.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeAndReferenceUiTest {
    @get:Rule val ui = createComposeRule()
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val store get() = MotionStore(application)
    private lateinit var original: AppSettings
    private lateinit var vm: MotionViewModel
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }

    @Before fun prepare() {
        original = store.settings()
        store.saveSettings(original.copy(themeMode = ThemeMode.DARK))
        vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(application))[MotionViewModel::class.java]
    }

    @After fun restore() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.viewModelStore.clear() }
        store.saveSettings(original)
    }

    private fun show(content: @Composable () -> Unit) {
        ui.setContent { MotionTheme(vm.settings.themeMode) { Box(Modifier.fillMaxSize().testTag("capture-root")) { content() } } }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = ui.onNodeWithTag("capture-root").captureToImage().asAndroidBitmap()
        File(application.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun homeToggleKeepsCreditsAndPersistsTheLastRapidSelection() {
        var destination: Screen? = null
        show { ReferenceCatalogContent(1728, vm.settings.themeMode, templates, false, vm::toggleTheme, { destination = it }, {}) }
        ui.onNodeWithText("Continue Story").assertIsDisplayed()
        ui.onNodeWithText("Continue Story").performClick()
        assertEquals(Screen.STORY, destination)
        ui.onNodeWithTag("theme-toggle").assertIsDisplayed().performClick()
        ui.onNodeWithContentDescription("Switch to dark mode").assertIsDisplayed()
        ui.onNodeWithText("1728", substring = true).assertIsDisplayed()
        screenshot("reference-home-light.png")
        ui.runOnIdle { repeat(4) { vm.toggleTheme() } }
        waitForSavedTheme(ThemeMode.LIGHT)
        ui.runOnIdle { assertEquals(ThemeMode.LIGHT, vm.settings.themeMode) }
        assertEquals(ThemeMode.LIGHT, MotionStore(application).settings().themeMode)
        ui.onNodeWithTag("credit-balance").performClick()
        assertEquals(Screen.PLANS, destination)
        ui.onNodeWithTag("theme-toggle").performClick()
        waitForSavedTheme(ThemeMode.DARK)
        screenshot("reference-home-dark.png")
    }

    private fun waitForSavedTheme(mode: ThemeMode) {
        // Rapid toggles can visit the expected value before the final background write.
        var matchingSince: Long? = null
        ui.waitUntil(5_000) {
            val now = android.os.SystemClock.uptimeMillis()
            if(store.settings().themeMode != mode) {
                matchingSince = null
                false
            } else {
                if(matchingSince == null) matchingSince = now
                now - checkNotNull(matchingSince) >= 300
            }
        }
    }

    @Test fun creationSettingsReturnToTheOriginatingModeAndPreserveDrafts() {
        ui.runOnIdle { vm.go(Screen.TEXT) }
        val originalVideo = vm.draft
        val originalImage = vm.imageDraft
        show {
            if(vm.screen == Screen.CREATION_SETTINGS) CreationSettingsScreen(vm)
            else CreateScreen(vm, {}, {}, refreshCatalog = {})
        }
        ui.onNodeWithTag("creation-prompt").assertIsDisplayed()
        ui.onNodeWithTag("settings-toggle").performScrollTo().performClick()
        ui.onNodeWithText("Video Settings").assertIsDisplayed()
        ui.onNodeWithText("Estimated cost").assertIsDisplayed()
        ui.onNodeWithTag("settings-done").assertIsDisplayed().performClick()
        ui.runOnIdle {
            assertEquals(Screen.TEXT, vm.screen)
            assertEquals(CreationMode.TEXT_TO_VIDEO, vm.creationSettingsMode)
            assertEquals(originalVideo, vm.draft)
            assertEquals(originalImage, vm.imageDraft)
            vm.go(Screen.IMAGE)
        }
        screenshot("reference-create-dark.png")
        ui.runOnIdle { vm.toggleTheme() }
        ui.waitUntil(5_000) { store.settings().themeMode == ThemeMode.LIGHT }
        screenshot("reference-create-light.png")
        ui.runOnIdle { vm.go(Screen.CREATE_IMAGE) }
        ui.onNodeWithTag("settings-toggle").performScrollTo().performClick()
        ui.onNodeWithText("Image Settings").assertIsDisplayed()
        ui.onNodeWithTag("settings-done").assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithContentDescription("Close settings").performClick()
        ui.runOnIdle { assertEquals(Screen.CREATE_IMAGE, vm.screen); assertEquals(originalImage, vm.imageDraft) }
    }

    @Test fun olderSettingsAndUnknownThemeValuesKeepAUsableDarkDefault() {
        val preferences = application.getSharedPreferences("motionmagic_local_v1", Application.MODE_PRIVATE)
        val saved = org.json.JSONObject(preferences.getString("settings", "{}")!!)
        saved.remove("themeMode")
        assertTrue(preferences.edit().putString("settings", saved.toString()).commit())
        assertEquals(ThemeMode.DARK, store.settings().themeMode)
        saved.put("themeMode", "UNKNOWN_THEME")
        assertTrue(preferences.edit().putString("settings", saved.toString()).commit())
        assertEquals(ThemeMode.DARK, store.settings().themeMode)
    }

    @Test fun bothPalettesKeepBodyTextAndSecondaryLabelsReadable() {
        listOf(DarkMotionColors, LightMotionColors).forEach { colors ->
            listOf(colors.background, colors.surface, colors.field).forEach { background ->
                listOf(colors.text, colors.muted).forEach { foreground ->
                    val a = foreground.luminance() + .05f
                    val b = background.luminance() + .05f
                    assertTrue("Text contrast is too low in " + if(colors.dark) "dark" else "light", maxOf(a, b) / minOf(a, b) >= 4.5f)
                }
            }
        }
    }
}
