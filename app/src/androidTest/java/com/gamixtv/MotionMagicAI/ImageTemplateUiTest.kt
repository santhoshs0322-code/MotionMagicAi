package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.data.CreationMode
import com.gamixtv.MotionMagicAI.data.MotionStore
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ImageTemplateUiTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entries get() = ImageTemplateCatalogue.bundled(context)

    private fun screenshot(name: String) {
        ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
        // Capture the composed window too: the template sheet lives above the activity root.
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun sharedBundleContainsSixtySixTemplatesAndFivePerCategory() {
        val templates = entries
        assertEquals(66, templates.size)
        assertEquals("Mars Astronaut", templates.first().name)
        assertEquals("Fantasy Warrior", templates[9].name)
        assertEquals("Forest Archer — Woman", templates[29].name)
        assertEquals("Orbital Explorer", templates[30].name)
        assertEquals("Kid Musician", templates.last().name)
        assertEquals(66, templates.map { it.remoteId }.distinct().size)
        assertEquals(13, templates.groupBy { it.category }.size)
        templates.groupBy { it.category }.values.forEach { assertTrue(it.size >= 5) }
        templates.forEachIndexed { index, template ->
            assertTrue(template.requiresReference)
            assertEquals(CreationMode.CREATE_IMAGE, template.mode)
            assertEquals(if(index < 10) "9:16" else "3:4", template.aspect)
            assertTrue(template.prompt!!.length <= 1000)
            if(index >= 10) assertTrue(template.prompt!!.startsWith("Use the uploaded photo as the identity reference."))
            val path = checkNotNull(ImageTemplateCatalogue.bundledPath(context, template.bundledPoster, template.bundledPosterHash))
            val bytes = context.assets.open(path).use { it.readBytes() }
            assertEquals(template.bundledPosterHash, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
    }

    @Test fun ownerChangesAndEmptyFeedReplaceTheBundledCatalogue() {
        var rows by mutableStateOf(entries)
        ui.setContent { MotionTheme(ThemeMode.LIGHT) {
            ReferenceCatalogContent(200, ThemeMode.LIGHT, rows, true, {}, {}, {})
        } }
        ui.onNodeWithText("Mars Astronaut").assertExists()
        screenshot("image-templates-light.png")
        val row = JSONObject().put("id", entries.first().remoteId).put("title", "Owner edited portrait").put("category", "Custom")
            .put("prompt", "Owner's latest prompt").put("mode", "create_image").put("poster_url", "")
            .put("video_url", "").put("requires_reference", true)
        val edited = ImageTemplateCatalogue.parse(JSONArray().put(row))
        assertNull(edited.single().videoUrl)
        assertNull(edited.single().bundledPoster)
        assertEquals("Owner's latest prompt", edited.single().prompt)
        ui.runOnIdle { rows = edited }
        ui.onNodeWithText("Owner edited portrait").assertExists()
        ui.onNodeWithText("Mars Astronaut").assertDoesNotExist()
        ui.runOnIdle { rows = ImageTemplateCatalogue.parse(JSONArray()) }
        ui.onNodeWithText("Owner edited portrait").assertDoesNotExist()
        ui.onNodeWithText("Templates are currently unavailable. Try again shortly.").assertIsDisplayed()
    }

    @Test fun previewExplainsUserPhotoAndCopiesExactPrompt() {
        var applied = 0
        val template = entries.last()
        assertTrue(template.prompt!!.length > 500)
        ui.setContent { MotionTheme(ThemeMode.DARK) {
            TemplatePreviewSheet(template, CreationMode.CREATE_IMAGE, {}, { applied++ })
        } }
        ui.onNodeWithText("Copy prompt").performScrollTo().performClick()
        ui.onNodeWithText("Copied").assertExists()
        ui.onNodeWithText(template.prompt!!).assertExists()
        ui.runOnIdle {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(template.prompt, clipboard.primaryClip!!.getItemAt(0).coerceToText(context).toString())
        }
        screenshot("image-template-preview.png")
        ui.onNodeWithText("Use template").performScrollTo().performClick()
        assertEquals(1, applied)
    }

    @Test fun referenceRequirementPersistsOnlyInTheCorrectAccountsDraft() {
        val store = MotionStore(context)
        val account = "image-template-instrumented-test"
        val template = entries.last()
        try {
            store.saveDraft(CreationDraft(prompt = template.prompt!!, aspect = template.aspect, templateId = template.remoteId, requiresReference = true), "image-generation", account)
            val restored = store.draft("image-generation", account)
            assertTrue(restored.requiresReference)
            assertEquals(template.remoteId, restored.templateId)
            assertEquals(template.prompt, restored.prompt)
            assertEquals("3:4", restored.aspect)
            assertFalse(store.draft("image-generation", "different-template-test-user").requiresReference)
        } finally { store.clearDraft("image-generation", account) }
    }
}
