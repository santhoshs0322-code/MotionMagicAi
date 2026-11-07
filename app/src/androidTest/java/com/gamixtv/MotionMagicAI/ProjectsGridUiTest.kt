package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.runBlocking
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

    @Test fun moderationShowsCenteredBlockAndKeepsOnlyAnAccountScopedBlurredPreview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "moderation-source-fixture.jpg")
        context.assets.open("image_templates/31_orbital_explorer.jpg").use { input -> source.outputStream().use { input.copyTo(it) } }
        val account = "moderation-ui-test-account"
        val id = "moderation-ui-test-job"
        val retained = checkNotNull(BlockedPreviewStore.save(context, account, id, source.path, false))
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(retained, bounds)
            assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 12)
            assertNotEquals(retained, BlockedPreviewStore.path(context, "different-account", id))
            assertFalse(File(BlockedPreviewStore.path(context, "different-account", id)).exists())
            assertTrue(source.delete())
            assertTrue(File(retained).isFile)
            val blocked = CloudJob(id, "Blocked portrait", "image", "failed", 0, "3:4", null, 0,
                "content_policy_violation", previewPath = retained)
            val ordinary = blocked.copy(id = "ordinary-failure", title = "Network failure", errorCode = "provider_failed")
            val theme = mutableStateOf(ThemeMode.DARK)
            ui.setContent { MotionTheme(theme.value) {
                Box(Modifier.fillMaxSize().background(Ink).testTag("capture-root")) {
                    ProjectLibraryGrid(listOf(LibraryItem.Cloud(blocked), LibraryItem.Cloud(ordinary)), "", {}, ProjectFilter.ALL, {},
                        account, 0, false, null, false, {}, {}, {}, {}, {})
                }
            } }
            ui.onNodeWithTag("blocked-preview-$id", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithTag("blocked-preview-ordinary-failure", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithText("Content blocked · credits released", useUnmergedTree = true).assertIsDisplayed()
            val preview = ui.onNodeWithTag("blocked-preview-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val icon = ui.onNodeWithContentDescription("Content blocked", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(preview.center.x, icon.center.x, 2f)
            assertEquals(preview.center.y, icon.center.y, 2f)
            ui.onNodeWithText("Download to device").assertDoesNotExist()
            screenshot("projects-blocked-dark.png")
            ui.runOnIdle { theme.value = ThemeMode.LIGHT }
            ui.onNodeWithContentDescription("Content blocked", useUnmergedTree = true).assertIsDisplayed()
            screenshot("projects-blocked-light.png")
        } finally {
            source.delete()
            File(retained).delete()
        }
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
        val loading = mutableStateOf(false)
        var refreshes = 0
        ui.setContent {
            var filter by remember { mutableStateOf(ProjectFilter.ALL) }
            var query by remember { mutableStateOf("") }
            MotionTheme(theme.value) {
                Box(Modifier.fillMaxSize().background(Ink).testTag("capture-root")) {
                    ProjectLibraryGrid(entries.filter { it.matches(filter, query) }, query, { query = it }, filter, { filter = it },
                        null, 0, loading.value, null, false, { refreshes++; loading.value = true }, {}, { selected = it }, {}, {})
                }
            }
        }
        ui.onNodeWithTag("projects-refresh").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, refreshes)
        ui.onNodeWithTag("projects-refresh").assertIsNotEnabled()
        ui.onNodeWithText("Refreshing…").assertIsDisplayed()
        ui.runOnIdle { loading.value = false }
        ui.onNodeWithText("Refresh").assertIsDisplayed()
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
