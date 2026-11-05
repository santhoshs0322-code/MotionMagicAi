package com.gamixtv.MotionMagicAI.cloud

import com.gamixtv.MotionMagicAI.auth.AuthSession
import com.gamixtv.MotionMagicAI.data.CreationDraft
import com.gamixtv.MotionMagicAI.data.PlanTier

data class CloudRate(
    val id: String, val title: String, val modelId: String, val kind: String, val size: String,
    val ratios: List<String>, val durations: List<Int>, val audio: Boolean?, val sourceRatio: Boolean,
    val minimumPricePaise: Long = 0, val minimumUnits: Int = 1,
    val creditPrices: Map<Int, Long> = emptyMap(),
    val requiredPlan: PlanTier = PlanTier.PRO,
    val maxReferenceImages: Int = 0,
    val durationFromSource: Boolean = false,
) {
    fun priceFor(draft: CreationDraft, image: Boolean): Long? =
        if (matches(draft, image) && draft.aspect in ratios && (image || draft.duration in durations))
            creditPrices[if (image) 1 else draft.duration]
        else null
    fun matches(draft: CreationDraft, image: Boolean): Boolean =
        kind == (if(image) "image" else "video") && modelId == (if(image) draft.imageModelId else draft.modelId) &&
            size == (if(image) draft.imageQuality else draft.resolution) && (image || audio == null || audio == draft.generatedAudio)
}

data class CloudQuote(val id: String, val chargePaise: Long, val availablePaise: Long, val title: String,
    val session: AuthSession, val draft: CreationDraft, val image: Boolean, val sourcePhoto: Boolean,
    val sourceVideoPath: String? = null, val storyRoot: String? = null, val storyPart: Int = 0,
    val deferredInput: Boolean = false, val sourcePhotoRevision: Int = 0,
    val referencePhotoPaths: List<String> = emptyList())

class CloudException(val status: Int, message: String) : Exception(message)

fun credits(value: Long): String = "$value credits"
