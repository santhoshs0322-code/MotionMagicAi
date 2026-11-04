package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.cloud.CloudJob
import com.gamixtv.MotionMagicAI.data.*
import com.gamixtv.MotionMagicAI.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectsGridUiTest {
    @get:Rule val ui = createComposeRule()

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = ui.onNodeWithTag("capture-root").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun mixedGridFiltersByMediaTypeSearchesAndOpensTheSelectedCreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.cacheDir, "grid-fixture.jpg")
        val fixture = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        image.outputStream().use { fixture.compress(Bitmap.CompressFormat.JPEG, 80, it) }; fixture.recycle()
        val first = Project(id = "image-one", title = "Forest portrait", path = image.path, kind = MediaKind.IMAGE, createdAt = 30)
        val second = first.copy(id = "image-two", title = "Sunset", createdAt = 20)
        val video = CloudJob("video-one", "Forest scene", "video", "succeeded", 10, "9:16", "output", 30, "", createdAt = 10)
        val entries = projectLibrary(listOf(first, second), listOf(video), setOf(first.id, second.id))
        var selected: LibraryItem? = null
        val theme = mutableStateOf(ThemeMode.DARK)
        ui.setContent {
            var filter by remember { mutableStateOf(ProjectFilter.ALL) }
            var query by remember { mutableStateOf("") }
            MotionTheme(theme.value) {
                Box(Modifier.fillMaxSize().background(Ink).testTag("capture-root")) {
                    ProjectLibraryGrid(entries.filter { it.matches(filter, query) }, query, { query = it }, filter, { filter = it },
                        null, 0, false, null, false, {}, {}, { selected = it }, {}, {})
                }
            }
        }
        ui.onNodeWithTag("project-image-one").assertIsDisplayed()
        ui.onNodeWithTag("project-image-two").assertIsDisplayed()
        ui.onNodeWithTag("project-cloud_video-one").assertIsDisplayed()
        assertEquals(ui.onNodeWithTag("project-image-one").fetchSemanticsNode().boundsInRoot.top,
            ui.onNodeWithTag("project-cloud_video-one").fetchSemanticsNode().boundsInRoot.top, 1f)
        screenshot("projects-grid-dark.png")
        ui.onNodeWithTag("filter-IMAGES").performClick()
        ui.onNodeWithTag("project-cloud_video-one").assertDoesNotExist()
        ui.onNode(hasSetTextAction()).performTextInput("Forest")
        ui.onNodeWithTag("project-image-two").assertDoesNotExist()
        ui.onNodeWithTag("project-image-one").performClick()
        assertEquals("image-one", selected?.key)
        ui.onNodeWithContentDescription("Clear search").performClick()
        ui.onNodeWithTag("filter-VIDEOS").performClick()
        ui.onNodeWithTag("project-cloud_video-one").assertIsDisplayed().performClick()
        assertEquals("cloud_video-one", selected?.key)
        ui.onNodeWithTag("filter-ALL").performClick()
        ui.runOnIdle { theme.value = ThemeMode.LIGHT }
        screenshot("projects-grid-light.png")
        image.delete()
    }
}
