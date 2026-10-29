package com.motionmagic.ai.data

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
}
