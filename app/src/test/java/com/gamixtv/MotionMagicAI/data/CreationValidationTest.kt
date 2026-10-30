package com.gamixtv.MotionMagicAI.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreationValidationTest {
    @Test fun whitespaceOnlyDescriptionsCannotStartGeneration() {
        assertFalse(CreationValidation.validate(" \n\t", 10, "9:16").isValid)
    }

    @Test fun exactPromptLimitIsAcceptedButAnExtraCharacterIsRejected() {
        assertTrue(CreationValidation.validate("a".repeat(500), 5, "1:1").isValid)
        assertFalse(CreationValidation.validate("a".repeat(501), 5, "1:1").isValid)
    }

    @Test fun photoAnimationCanOmitPromptButStillValidatesOtherOptions() {
        assertTrue(CreationValidation.validate("", 15, "16:9", requirePrompt = false).isValid)
        assertFalse(CreationValidation.validate("", 0, "16:9", requirePrompt = false).isValid)
        assertFalse(CreationValidation.validate("x".repeat(501), 10, "9:16", requirePrompt = false).isValid)
    }

    @Test fun invalidFieldsAreReportedTogetherSoUsersCanFixThemAtOnce() {
        val result = CreationValidation.validate("", -1, "4:3")
        assertEquals(3, result.errors.size)
        assertFalse(result.isValid)
        assertTrue(result.message.contains("description"))
        assertTrue(result.message.contains("duration"))
        assertTrue(result.message.contains("aspect ratio"))
    }

    @Test fun supportedEditorExportChoicesValidate() {
        for (duration in listOf(5, 10, 15)) {
            for (aspect in listOf("9:16", "16:9", "1:1")) {
                assertTrue(CreationValidation.validate("Sunlight over a quiet lake", duration, aspect).isValid)
            }
        }
    }

    @Test fun voiceRequiresTextAndSharesTheFiveHundredCharacterLimit() {
        assertFalse(CreationValidation.validateVoice("\n ").isValid)
        assertTrue(CreationValidation.validateVoice("नमस्ते, आपका स्वागत है।").isValid)
        assertTrue(CreationValidation.validateVoice("a".repeat(500)).isValid)
        assertFalse(CreationValidation.validateVoice("a".repeat(501)).isValid)
    }

    @Test fun videoModelValidationAcceptsTheCompleteLocalPreviewConfiguration() {
        val model = VideoModelOption("test-local", "Test Local", "Test fixture", PlanTier.FREE, setOf(10), setOf("540p"), false, true, 0, "Photo → video", listOf("Photo", "Video"))
        val draft = CreationDraft(prompt = "Ocean at sunset", modelId = model.id, duration = 10, aspect = "9:16", resolution = "540p")
        assertTrue(CreationValidation.validateVideo(draft, model, PlanTier.FREE, requirePrompt = true).isValid)
    }

    @Test fun videoModelValidationRejectsLockedUnavailableAndUnsupportedSettingsTogether() {
        val model = VideoModelCatalog.find("kling-3-omni")
        val draft = CreationDraft(prompt = "Ocean at sunset", modelId = model.id, duration = 10, aspect = "9:16", resolution = "540p", generatedAudio = true)
        val result = CreationValidation.validateVideo(draft, model, PlanTier.FREE, requirePrompt = true)
        assertFalse(result.isValid)
        assertTrue(result.message.contains("requires the Premium plan"))
        assertTrue(result.message.contains("unavailable"))
        assertTrue(result.message.contains("does not support 540p"))
    }

    @Test fun generatedAudioMustBeSupportedByTheSelectedModel() {
        val model = VideoModelOption("test-no-audio", "Test No Audio", "Test fixture", PlanTier.FREE, setOf(10), setOf("540p"), false, true, 0, "Photo → video", listOf("Photo", "Video"))
        val draft = CreationDraft(prompt = "Ocean", modelId = model.id, resolution = "540p", generatedAudio = true)
        assertTrue(CreationValidation.validateVideo(draft, model, PlanTier.FREE, true).message.contains("does not support generated audio"))
    }
}
