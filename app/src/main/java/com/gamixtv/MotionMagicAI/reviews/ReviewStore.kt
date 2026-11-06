package com.gamixtv.MotionMagicAI.reviews

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/** Account milestones, bounded at three IDs; a device-wide cooldown prevents account-switch nagging. */
internal class ReviewStore(
    private val preferences: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("motion_reviews_v1", Context.MODE_PRIVATE))

    private fun key(account: String): String = "viewed_" + MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun viewed(account: String): Set<String> = preferences.getStringSet(key(account), emptySet()).orEmpty().toSet()

    /** Call on IO after a result was opened. Reopening it cannot inflate the milestone. */
    fun recordViewed(account: String, creationId: String): Boolean = synchronized(lock) {
        require(account.isNotBlank())
        val previous = viewed(account)
        val updated = ReviewPolicy.recordViewed(previous, creationId)
        if(updated != previous && !preferences.edit().putStringSet(key(account), updated).commit()) return@synchronized false
        ReviewPolicy.eligible(updated, preferences.getLong(LAST_ATTEMPT, 0L), now())
    }

    /** Persist before contacting Play. A crash, rotation or another account cannot retry immediately. */
    fun claimAttempt(account: String): Boolean = synchronized(lock) {
        val timestamp = now()
        if(!ReviewPolicy.eligible(viewed(account), preferences.getLong(LAST_ATTEMPT, 0L), timestamp)) return@synchronized false
        preferences.edit().putLong(LAST_ATTEMPT, timestamp).commit()
    }

    fun recordStoreVisit() = synchronized(lock) {
        preferences.edit().putLong(LAST_ATTEMPT, now()).commit()
    }

    private companion object {
        const val LAST_ATTEMPT = "last_attempt"
        val lock = Any()
    }
}
