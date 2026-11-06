package com.gamixtv.MotionMagicAI.reviews

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gamixtv.MotionMagicAI.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The explicit Rate Us action always opens a listing, independently of Google's popup quota. */
internal fun openPlayStoreReview(context: Context): Boolean {
    val suffix = "id=${BuildConfig.APPLICATION_ID}"
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?$suffix")).setPackage("com.android.vending"),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?$suffix")),
    )
    for(intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch(_: ActivityNotFoundException) { /* Try the browser when Play Store is absent. */ }
        catch(_: SecurityException) { /* A restricted device may still allow a browser. */ }
    }
    return false
}

/** No custom rating gate, reward or automatic browser fallback. Failure must not interrupt Home. */
internal suspend fun requestPlayReview(
    activity: Activity,
    canLaunch: () -> Boolean,
    manager: ReviewManager? = null,
) {
    fun available() = !activity.isFinishing && !activity.isDestroyed && canLaunch()
    try {
        if(!available()) return
        val client = manager ?: ReviewManagerFactory.create(activity)
        val info = client.requestReviewFlow().awaitReviewTask()
        // Navigation, backgrounding or account changes while Play replies cancel the opportunity.
        if(available()) client.launchReviewFlow(activity, info).awaitReviewTask()
        // Play intentionally does not report whether the card appeared or a review was submitted.
    } catch(e: CancellationException) { throw e }
    catch(_: Exception) { /* Optional review failures never block creation or navigation. */ }
}

private suspend fun <T> Task<T>.awaitReviewTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if(continuation.isActive) {
            if(task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(task.exception ?: IllegalStateException("Review request did not finish."))
        }
    }
}
