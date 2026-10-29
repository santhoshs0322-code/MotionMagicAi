package com.motionmagic.ai.data

import java.util.UUID

enum class MediaKind { VIDEO, AUDIO, IMAGE }

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
    val mimeType: String = if (kind == MediaKind.AUDIO) "audio/wav" else "video/mp4",
)

data class AppSettings(
    val defaultAspect: String = "9:16",
    val quality: String = "Preview (540p)",
    val notifications: Boolean = false,
    val autoSave: Boolean = false,
)

data class CreationDraft(
    val prompt: String = "",
    val selectedStyle: String = "Realistic",
    val duration: Int = 10,
    val aspect: String = "9:16",
)

data class ValidationResult(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
    val message: String get() = errors.joinToString("\n")
}

/** A commit failure is actionable: callers must not report that an unsaved change was saved. */
class StoreWriteException(message: String) : IllegalStateException(message)
