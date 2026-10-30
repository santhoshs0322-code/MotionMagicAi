package com.gamixtv.MotionMagicAI.cloud

import com.gamixtv.MotionMagicAI.auth.AuthSession
import com.gamixtv.MotionMagicAI.data.CreationDraft

data class CloudRate(
    val id: String, val title: String, val modelId: String, val kind: String, val size: String,
    val ratios: List<String>, val durations: List<Int>, val audio: Boolean?, val sourceRatio: Boolean,
    val minimumPricePaise: Long = 0, val minimumUnits: Int = 1,
) {
    fun matches(draft: CreationDraft, image: Boolean): Boolean =
        kind == (if(image) "image" else "video") && modelId == (if(image) draft.imageModelId else draft.modelId) &&
            size == (if(image) draft.imageQuality else draft.resolution) && (image || audio == null || audio == draft.generatedAudio)
}

data class CloudQuote(val id: String, val chargePaise: Long, val availablePaise: Long, val title: String,
    val session: AuthSession, val draft: CreationDraft, val image: Boolean)

class CloudException(val status: Int, message: String) : Exception(message)

fun rupees(paise: Long): String = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("en-IN"))
    .format(java.math.BigDecimal.valueOf(paise, 2))
