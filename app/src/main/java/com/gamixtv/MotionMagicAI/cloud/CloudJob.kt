package com.gamixtv.MotionMagicAI.cloud

import org.json.JSONObject

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
) {
    val isGenerating: Boolean get() = state in setOf("queued", "submitting", "running", "uncertain")
    val isActive: Boolean get() = state in setOf("queued", "submitting", "running", "uncertain", "downloading")
    val isReady: Boolean get() = state == "succeeded" && !outputAssetId.isNullOrBlank()
    val statusLabel: String get() = when(state) {
        "queued" -> "Waiting for generation"
        "submitting" -> "Sending to AI model"
        "running" -> "AI model is generating"
        "uncertain" -> "Checking provider status"
        "downloading" -> "Saving creation to this device"
        "succeeded" -> "Ready to download"
        "cancelled" -> "Cancelled"
        "failed" -> "Generation failed · credits released"
        else -> "Checking generation"
    }

    companion object {
        fun stagePercent(state: String): Int = when(state) {
            "queued" -> 10
            "submitting" -> 20
            "running", "uncertain" -> 50
            "downloading", "succeeded" -> 90
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
        )
    }
}
