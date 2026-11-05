package com.gamixtv.MotionMagicAI.data

import java.util.UUID

enum class MediaKind { VIDEO, AUDIO, IMAGE }

enum class CreationMode(val title: String) {
    IMAGE_TO_VIDEO("Image to Video"), TEXT_TO_VIDEO("Text to Video"), CREATE_IMAGE("Create Image")
}

enum class PlanTier(val title: String, val level: Int) {
    FREE("Free", 0), STARTER("Starter", 1), PRO("Pro", 2), PREMIUM("Premium", 3);

    fun includes(required: PlanTier): Boolean = level >= required.level
}

data class VideoModelOption(
    val id: String,
    val title: String,
    val description: String,
    val requiredPlan: PlanTier,
    val durations: Set<Int>,
    val resolutions: Set<String>,
    val supportsGeneratedAudio: Boolean,
    val locallyAvailable: Boolean,
    val creditsPerSecond: Int,
    val workflow: String,
    val capabilityLabels: List<String>,
) {
    fun credits(duration: Int, generatedAudio: Boolean): Int =
        creditsPerSecond * duration + if (generatedAudio) duration * 2 else 0
}

data class PremiumWorkflowOption(
    val id: String,
    val title: String,
    val description: String,
    val workflow: String,
    val capabilityLabels: List<String>,
    val requiredPlan: PlanTier = PlanTier.PRO,
    val resolutions: List<String> = listOf("1K", "2K"),
)

object ImageModelCatalog {
    val models = listOf(
        PremiumWorkflowOption("qwen-image", "Qwen Image", "Image generation", "Text + photo → image", listOf("Text", "Photo"), requiredPlan = PlanTier.FREE, resolutions = listOf("720p", "1080p")),
        PremiumWorkflowOption("gpt-image-2-5-s", "GPT Image 2.5 Sunburst", "Image generation and editing", "Text + photo → image", listOf("Text", "Photo")),
        PremiumWorkflowOption("gpt-image-2-5-f", "GPT Image 2.5 Flare", "Image generation and editing", "Text + photo → image", listOf("Text", "Photo")),
        PremiumWorkflowOption("gpt-image-2", "GPT Image 2", "Image generation and editing", "Text + photo → image", listOf("Text", "Photo")),
        PremiumWorkflowOption("seedream-5-pro", "Seedream 5.0 Pro", "Image model catalog preview", "Text + photo → image", listOf("Text", "Photo")),
        PremiumWorkflowOption("seedream-5-lite", "Seedream 5.0 Lite", "Image generation and editing", "Text + photo → image", listOf("Text", "Photo"), resolutions = listOf("2K")),
        PremiumWorkflowOption("seedream-4-5", "Seedream 4.5", "Image creation", "Text + photo → image", listOf("Text", "Photo"), resolutions = listOf("2K")),
    )
    val aspects = listOf("16:9", "1:1", "9:16", "4:3", "3:4", "5:4", "4:5", "3:2", "2:3", "21:9")
    fun find(id: String): PremiumWorkflowOption = models.firstOrNull { it.id == id } ?: models.first()
}

data class VideoResolutionOption(val label: String, val requiredPlan: PlanTier)

object VideoResolutionCatalog {
    val options = listOf(
        VideoResolutionOption("720p", PlanTier.FREE),
        VideoResolutionOption("1080p", PlanTier.PRO),
    )
}

/**
 * Safe fallback catalog used before the authenticated server catalog is available.
 * Cloud entries intentionally stay locked: displaying a model is not a claim that a
 * provider or entitlement has been configured.
 */
object VideoModelCatalog {
    fun isReference(id: String) = id == "kling-o3-reference" || id == "kling-o3-edit"
    val models = listOf(
        VideoModelOption(
            id = "kling-o3-edit", title = "Kling O3 Video Edit",
            description = "Edit your existing clip using up to four reference photos",
            requiredPlan = PlanTier.FREE, durations = (3..15).toSet(), resolutions = setOf("1080p"),
            supportsGeneratedAudio = true, locallyAvailable = false, creditsPerSecond = 0,
            workflow = "Video + up to 4 photos + prompt → edited clip",
            capabilityLabels = listOf("Edit this video", "4 photos", "Existing audio"),
        ),
        VideoModelOption(
            id = "kling-o3-reference", title = "Kling O3 Story Reference",
            description = "Create a new scene guided by a video and up to four photos",
            requiredPlan = PlanTier.FREE, durations = (3..15).toSet(),
            resolutions = setOf("1080p"), supportsGeneratedAudio = true,
            locallyAvailable = false, creditsPerSecond = 0,
            workflow = "Video + up to 4 photos + prompt → new scene",
            capabilityLabels = listOf("Video reference", "Prompt", "Story", "Existing audio"),
        ),
        VideoModelOption(
            id = "kling-3-omni",
            title = "Kling O3",
            description = "Cinematic motion with native audio workflows",
            requiredPlan = PlanTier.PRO,
            durations = (3..15).toSet(),
            resolutions = setOf("720p", "1080p"),
            supportsGeneratedAudio = true,
            locallyAvailable = false,
            creditsPerSecond = 0,
            workflow = "Photo + prompt → video + audio",
            capabilityLabels = listOf("Photo", "Prompt", "Video", "Audio"),
        ),
        VideoModelOption(
            id = "pixverse-v6",
            title = "PixVerse V6",
            description = "Prompt and image-led premium video",
            requiredPlan = PlanTier.FREE,
            durations = (1..15).toSet(),
            resolutions = setOf("720p", "1080p"),
            supportsGeneratedAudio = true,
            locallyAvailable = false,
            creditsPerSecond = 0,
            workflow = "Text or photo + prompt → video + audio",
            capabilityLabels = listOf("Text", "Photo", "Prompt", "Audio"),
        ),
    )

    fun find(id: String): VideoModelOption = models.firstOrNull { it.id == if(id == "pixverse-v6-max") "pixverse-v6" else id } ?: models.first()
}

object PremiumWorkflowCatalog {
    val models = listOf(
        PremiumWorkflowOption(
            id = "qwen-image",
            title = "Qwen Image",
            description = "Create or edit a reference image before video generation",
            workflow = "Photo + prompt → reference image → video model",
            capabilityLabels = listOf("Photo", "Prompt", "Reference image"),
        ),
    )
}

/** Metadata only. A missing media file does not remove a project from the library. */
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val path: String,
    val kind: MediaKind,
    val createdAt: Long = System.currentTimeMillis(),
    val duration: Int = 0,
    val aspect: String = "9:16",
    val style: String = "Realistic",
    val durationMs: Long = duration * 1000L,
    val mimeType: String = when(kind) { MediaKind.AUDIO -> "audio/wav"; MediaKind.IMAGE -> "image/png"; else -> "video/mp4" },
    val storyRoot: String? = null,
    val storyPart: Int = 0,
)

enum class ThemeMode { DARK, LIGHT }

data class AppSettings(
    val defaultAspect: String = "9:16",
    val quality: String = "Preview (540p)",
    val notifications: Boolean = false,
    val autoSave: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
)

data class CreationDraft(
    val prompt: String = "",
    val selectedStyle: String = "Realistic",
    val duration: Int = 10,
    val aspect: String = "9:16",
    val modelId: String = "pixverse-v6",
    val resolution: String = "720p",
    val generatedAudio: Boolean = false,
    val imageQuality: String = "720p",
    val audioPrompt: String = "",
    val imageModelId: String = "qwen-image",
    val outputCount: Int = 1,
    val templateId: String? = null,
    val requiresReference: Boolean = false,
)

data class ValidationResult(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
    val message: String get() = errors.joinToString("\n")
}

/** A commit failure is actionable: callers must not report that an unsaved change was saved. */
class StoreWriteException(message: String) : IllegalStateException(message)
