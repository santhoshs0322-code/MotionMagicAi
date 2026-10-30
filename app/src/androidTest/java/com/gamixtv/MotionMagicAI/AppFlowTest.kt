package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.data.CreationDraft
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    private val vm: MotionViewModel get() = ViewModelProvider(app.activity)[MotionViewModel::class.java]

    private fun await(text: String, timeout: Long = 15_000) {
        app.waitUntil(timeout) { runCatching { app.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }

    private fun screenshot(name: String) {
        app.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.activity.filesDir, name.replace("reference-", "font-").replace("premium-", "font-")).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun closeSettings() {
        app.onNodeWithContentDescription("Close settings").performScrollTo().performClick()
    }

    private fun chooseMode(name: String) {
        app.onNodeWithTag("creation-mode-selector").performClick()
        await("Creation Mode")
        screenshot("reference-modes.png")
        app.onNodeWithTag("mode-$name").performClick()
    }

    @Test fun referenceSettingsRetainSeparateImageAndVideoDrafts() {
        await("Text to Video")
        screenshot("reference-home.png")
        app.runOnIdle {
            vm.updateDraft(CreationDraft())
            vm.updateImageDraft(CreationDraft())
        }
        app.onNodeWithText("Text to Video").performClick()
        app.onNodeWithTag("creation-prompt").performTextClearance()
        app.onNodeWithTag("creation-prompt").performTextInput("Ocean at sunrise with cinematic light")
        app.onNodeWithTag("settings-toggle").performClick()
        await("Model")
        app.onNodeWithTag("model-option-PixVerse V6").performClick()
        app.onNodeWithText("Create with Premium").assertDoesNotExist()
        app.onNodeWithTag("ratio-16:9").performScrollTo().performClick()
        app.onNodeWithTag("duration-5").performScrollTo().performClick()
        screenshot("reference-video-settings.png")
        closeSettings()
        app.runOnIdle { assertEquals("16:9", vm.draft.aspect); assertEquals(5, vm.draft.duration) }
        screenshot("reference-text.png")
        app.onNodeWithTag("create-button").assertIsEnabled()
        app.onNodeWithText("Check cloud generation").assertExists()
        chooseMode("Create Image")
        app.onNodeWithTag("creation-prompt").performTextClearance()
        app.onNodeWithTag("creation-prompt").performTextInput("Watercolor mountain landscape")
        app.onNodeWithText("Couldn’t finish that").assertDoesNotExist()
        app.onNodeWithTag("settings-toggle").performClick()
        await("Model")
        app.onNodeWithTag("model-option-Qwen-image").performClick()
        app.onNodeWithTag("resolution-1080p").performClick()
        app.onNodeWithTag("ratio-4:3").performClick()
        screenshot("reference-image-settings.png")
        closeSettings()
        app.runOnIdle {
            assertEquals("qwen-image", vm.imageDraft.imageModelId)
            assertEquals("4:3", vm.imageDraft.aspect)
            assertEquals(1, vm.imageDraft.outputCount)
        }
        screenshot("reference-create-image.png")
        app.onNodeWithTag("create-button").assertIsEnabled()
        chooseMode("Text to Video")
        app.onNodeWithTag("creation-prompt").assertTextContains("Ocean at sunrise", substring = true)
        app.runOnIdle { assertEquals(5, vm.draft.duration) }
        android.os.SystemClock.sleep(400)
        app.activityRule.scenario.recreate()
        app.onNodeWithTag("creation-mode-selector").assertExists()
        chooseMode("Create Image")
        app.runOnIdle {
            assertEquals("Watercolor mountain landscape", vm.imageDraft.prompt)
            assertEquals("4:3", vm.imageDraft.aspect)
            assertEquals(1, vm.imageDraft.outputCount)
        }
    }

    @Test fun referenceUploadAndTemplateKeepTheUserPhoto() {
        await("Image to Video")
        app.onNodeWithText("Image to Video").performClick()
        app.runOnIdle { vm.removePhoto() }
        app.onNodeWithTag("upload-photo").assertExists()
        app.onNodeWithTag("create-button").assertIsNotEnabled()
        screenshot("reference-image-to-video.png")
        app.onNodeWithTag("upload-photo").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        app.waitUntil(15_000) {
            val pkg = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
            pkg.contains("photopicker") || pkg.contains("providers.media") || pkg.contains("documentsui")
        }
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        app.waitUntil(15_000) { automation.rootInActiveWindow?.packageName?.toString() == "com.gamixtv.MotionMagicAI" }
        val source = File(app.activity.cacheDir, "reference-input.png")
        Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.rgb(111, 67, 174))
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        app.runOnIdle { vm.importPhoto(Uri.fromFile(source)) }
        app.waitUntil(15_000) { vm.photo != null }
        app.onNodeWithContentDescription("Your selected photo").assertExists()
        app.onNodeWithContentDescription("Templates").performClick()
        await("Choose a look to start with")
        app.onNodeWithContentDescription("Cinematic template").performClick()
        await("The starting prompt")
        screenshot("reference-template-preview.png")
        app.onNodeWithText("Use template").performClick()
        app.runOnIdle { assertNotNull(vm.photo); assertEquals(5, vm.draft.duration) }
        app.onNodeWithText("Use template").assertDoesNotExist()
        screenshot("reference-template-applied.png")
        app.onNodeWithTag("create-button").assertIsEnabled()
        app.runOnIdle { vm.go(Screen.PLANS) }
        await("Create your way")
        app.onNodeWithContentDescription("Free plan").assertIsDisplayed()
        app.onNodeWithContentDescription("AI credits plan").assertIsDisplayed()
        screenshot("premium-plans.png")
    }
}
