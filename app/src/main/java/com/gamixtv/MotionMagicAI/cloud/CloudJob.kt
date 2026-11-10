package com.gamixtv.MotionMagicAI.cloud

import org.json.JSONObject
import java.time.OffsetDateTime

/** A server-owned generation shown in My Projects before its media is downloaded. */
data class CloudJob(
    val id: String,
    val title: String,
    val kind: String,
    val state: String,
    val duration: Int,
    val aspect: String,
    val outputAssetId: String?,
    val chargedPaise: Long,
    val errorCode: String,
    val progressPercent: Int = stagePercent(state),
    val previewPath: String? = null,
    val previewVideo: Boolean = false,
    val createdAt: Long = 0,
    val thumbnailUrl: String? = null,
    val thumbnailState: String = "none",
    val storyScenesDone: Int = 0,
    val storyScenesTotal: Int = 0,
) {
    val isGenerating: Boolean get() = state in setOf("queued", "submitting", "running", "uncertain")
    val isActive: Boolean get() = state in setOf("queued", "submitting", "running", "uncertain", "downloading")
    val isReady: Boolean get() = state == "succeeded" && !outputAssetId.isNullOrBlank()
    val isContentBlocked: Boolean get() = state == "failed" && errorCode == "content_policy_violation"
    val statusLabel: String get() = when(state) {
        "queued" -> "Waiting for generation"
        "submitting" -> "Sending to AI model"
        "running" -> if(storyScenesTotal > 0) {
            if(storyScenesDone >= storyScenesTotal) "Assembling your story" else "Creating scene ${storyScenesDone + 1} of $storyScenesTotal"
        } else "AI model is generating"
        "uncertain" -> "Checking provider status"
        "downloading" -> "Completed · downloading to this device"
        "succeeded" -> "Completed · saved to your account"
        "cancelled" -> "Cancelled"
        "failed" -> when {
            isContentBlocked -> "Content blocked by safety check · credits released"
            errorCode == "invalid_reference" -> "Check reference format, length and size · credits released"
            else -> "Generation failed · credits released"
        }
        else -> "Checking generation"
    }

    companion object {
        fun stagePercent(state: String): Int = when(state) {
            "queued" -> 10
            "submitting" -> 20
            "running", "uncertain" -> 50
            "downloading" -> 0
            "succeeded" -> 100
            "failed", "cancelled" -> 0
            else -> 0
        }

        fun from(json: JSONObject): CloudJob = CloudJob(
            id = json.getString("id"),
            title = json.optString("title", "AI creation").ifBlank { "AI creation" },
            kind = json.optString("kind", "video"),
            state = json.getString("state"),
            duration = json.optInt("duration"),
            aspect = json.optString("aspect_ratio", "9:16"),
            outputAssetId = json.optString("output_asset_id").takeUnless { it.isBlank() || it == "null" },
            chargedPaise = json.optLong("charged_paise"),
            errorCode = json.optString("error_code"),
            createdAt = runCatching { OffsetDateTime.parse(json.optString("created_at")).toInstant().toEpochMilli() }.getOrDefault(0),
            thumbnailUrl = json.optString("thumbnail_url").takeUnless { it.isBlank() || it == "null" },
            thumbnailState = json.optString("thumbnail_state", "none"),
            storyScenesDone = json.optInt("story_scenes_done"),
            storyScenesTotal = json.optInt("story_scenes_total"),
            progressPercent = if(json.optInt("story_scenes_total") > 0 && json.optString("state") in listOf("running", "uncertain"))
                (5 + json.optInt("story_scenes_done") * 80 / json.optInt("story_scenes_total")).coerceIn(5, 85) else stagePercent(json.getString("state")),
        )
    }
}

/** Merge DB pages without duplicating saved files or resetting an ongoing device download. */
internal fun mergeCloudJobs(existing: List<CloudJob>, incoming: List<CloudJob>, downloadedIds: Set<String>, retainMissingActive: Boolean = false): List<CloudJob> {
    val previous = existing.associateBy { it.id }
    val merged = existing.filter { retainMissingActive || !it.isGenerating }.associateByTo(linkedMapOf()) { it.id }
    for(job in incoming) {
        val old = previous[job.id]
        merged[job.id] = if(old?.state == "downloading" && job.isReady) old
            else job.copy(previewPath = job.previewPath ?: old?.previewPath, previewVideo = if(job.previewPath != null) job.previewVideo else old?.previewVideo ?: false)
    }
    return merged.values.filterNot { it.id in downloadedIds }
        .sortedWith(compareByDescending<CloudJob> { it.createdAt }.thenByDescending { it.id })
}
