package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.media.*
import com.gamixtv.MotionMagicAI.ui.ArtRepository
import com.gamixtv.MotionMagicAI.ui.Artwork
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Rule
import org.junit.Test

class StudioUiTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    private fun await(text: String, timeout: Long = 20000) {
        app.waitUntil(timeout) { app.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun shot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun videoEditorShowsReferenceLayoutAndExportsCaption(): Unit = runBlocking {
        await("Video Editor")
        val source = File(app.activity.cacheDir, "Studio-design-preview.mp4")
        try {
            LocalMediaEngine.createVideo(app.activity, ArtRepository.bitmap(app.activity, Artwork.Garden), 5, "16:9", source)
            app.runOnUiThread { ViewModelProvider(app.activity)[MotionViewModel::class.java].importVideo(Uri.fromFile(source)) }
            await("Export")
            app.waitUntil(20000) { app.onAllNodes(hasText("Export") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            app.onNodeWithText("Text", substring = false).performScrollTo().performClick()
            app.onNode(hasSetTextAction()).performTextInput("MotionMagic AI")
            app.onNodeWithText("Apply").performClick()
            app.onNodeWithContentDescription("Undo").assertIsEnabled()
            // Return to the preview before capturing the screen.
            app.onNodeWithContentDescription("Play video").performScrollTo()
            app.waitForIdle(); shot("studio-video.png")
            app.onNodeWithText("Export", substring = false).performClick()
            await("Edit Further", 90000)
            app.onNodeWithText("Edit Further").assertExists()
        } finally { source.delete() }
    }
    @Test fun audioEditorImportsMixesAndExportsM4a(): Unit = runBlocking {
        await("Video Editor")
        val source = StudioMusic.file(app.activity, 0)
        app.runOnUiThread { ViewModelProvider(app.activity)[MotionViewModel::class.java].importAudio(Uri.fromFile(source)) }
        await("Audio Tools")
        app.onNodeWithText("AI Voice Generator", substring = false).performScrollTo()
        app.onNodeWithTag("studio-content").performScrollToNode(hasText("Background Music", substring = false))
        shot("studio-audio-library.png")
        app.onNodeWithText("Upbeat Pop", substring = false).performScrollTo().performClick()
        app.waitUntil(15000) { app.onAllNodesWithText("Opening media…").fetchSemanticsNodes().isEmpty() }
        app.onNodeWithTag("studio-content").performScrollToNode(hasText("Audio Tools"))
        app.onNodeWithTag("studio-content").performScrollToNode(hasContentDescription("Play", substring = false))
        app.waitForIdle(); shot("studio-audio.png")
        app.onNodeWithText("Export", substring = false).performClick()
        await("Edit Audio", 90000)
        app.onNodeWithText("Edit Audio").assertExists()
    }
}
