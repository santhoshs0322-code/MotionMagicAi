package com.motionmagic.ai.data

object CreationValidation {
    const val MAX_PROMPT_LENGTH = 500
    val supportedDurations: Set<Int> = setOf(5, 10, 15)
    val supportedAspects: Set<String> = setOf("9:16", "16:9", "1:1")

    /**
     * These are editor/export choices, not a claim that a provider accepts every choice.
     * A live provider must separately validate its capabilities before a paid submission.
     */
    fun validate(
        prompt: String,
        duration: Int,
        aspect: String,
        requirePrompt: Boolean = true,
    ): ValidationResult = ValidationResult(buildList {
        if (requirePrompt && prompt.isBlank()) add("Enter a description before generating.")
        if (prompt.length > MAX_PROMPT_LENGTH) add("Keep your description to 500 characters or fewer.")
        if (duration !in supportedDurations) add("Choose a duration of 5, 10, or 15 seconds.")
        if (aspect !in supportedAspects) add("Choose 9:16, 16:9, or 1:1 as the aspect ratio.")
    })

    fun validateVoice(text: String): ValidationResult = ValidationResult(buildList {
        if (text.isBlank()) add("Enter the words you want the voice to speak.")
        if (text.length > MAX_PROMPT_LENGTH) add("Keep your script to 500 characters or fewer.")
    })
}
