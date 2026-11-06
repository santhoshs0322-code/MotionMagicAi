package com.gamixtv.MotionMagicAI.reviews

/** Local engagement only; reviews never affect credits, purchases or access. */
internal object ReviewPolicy {
    const val REQUIRED_CREATIONS = 3
    const val COOLDOWN_MILLIS = 90L * 24 * 60 * 60 * 1000

    // A downloaded cloud result and its server thumbnail are the same creation.
    fun creationId(id: String): String = id.removePrefix("cloud_")

    fun recordViewed(previous: Set<String>, id: String): Set<String> {
        val bounded = previous.filter(String::isNotBlank).take(REQUIRED_CREATIONS).toSet()
        val normalized = creationId(id)
        return if(normalized.isBlank() || bounded.size >= REQUIRED_CREATIONS) bounded
            else bounded + normalized
    }

    fun eligible(viewed: Set<String>, lastAttempt: Long, now: Long): Boolean =
        viewed.size >= REQUIRED_CREATIONS && now > 0 &&
            (lastAttempt == 0L || (lastAttempt > 0 && now >= lastAttempt && now - lastAttempt >= COOLDOWN_MILLIS))
}
