package com.gamixtv.MotionMagicAI

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.reviews.*
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.testing.FakeReviewManager
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayReviewTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun milestonePersistsAndDownloadedCopiesCannotCountTwice() {
        val name = "reviews_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            var store = ReviewStore(prefs) { 1_800_000_000_000L }
            assertFalse(store.recordViewed("account-a", "first"))
            store = ReviewStore(prefs) { 1_800_000_000_000L }
            assertFalse(store.recordViewed("account-a", "cloud_first"))
            assertFalse(store.recordViewed("account-a", "second"))
            assertTrue(store.recordViewed("account-a", "third"))
            assertFalse(store.claimAttempt("account-b"))
            assertTrue(store.claimAttempt("account-a"))
            assertFalse(ReviewStore(prefs) { 1_800_000_000_000L }.claimAttempt("account-a"))
            assertFalse(prefs.all.keys.any { it.contains("account-a") })
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun switchingAccountsAndRestartingCannotBypassDeviceCooldown() {
        val name = "reviews_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        var now = 1_800_000_000_000L
        try {
            val store = ReviewStore(prefs) { now }
            listOf("one", "two", "three").forEach { store.recordViewed("a", it) }
            assertTrue(store.claimAttempt("a"))
            listOf("one", "two", "three").forEach { assertFalse(store.recordViewed("b", it)) }
            assertFalse(ReviewStore(prefs) { now }.claimAttempt("b"))
            now += ReviewPolicy.COOLDOWN_MILLIS
            assertTrue(ReviewStore(prefs) { now }.claimAttempt("b"))
            assertFalse(store.claimAttempt("a"))
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun manualListingVisitAlsoSuppressesAutomaticPrompts() {
        val name = "reviews_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val store = ReviewStore(prefs) { 1_800_000_000_000L }
            listOf("one", "two", "three").forEach { store.recordViewed("a", it) }
            store.recordStoreVisit()
            assertFalse(store.claimAttempt("a"))
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun nativeReviewFlowCompletesUsingGoogleFakeWithoutOpeningRealReviewUi() = runBlocking {
        val manager = RecordingManager(context)
        requestPlayReview(ui.activity, { true }, manager)
        assertEquals(1, manager.requests)
        assertEquals(1, manager.launches)
    }

    @Test fun navigatingAwayWhilePlayRespondsDoesNotLaunchReview() = runBlocking {
        var eligible = true
        val pending = TaskCompletionSource<ReviewInfo>()
        val requested = CompletableDeferred<Unit>()
        val delegate = RecordingManager(context)
        val manager = object : ReviewManager by delegate {
            override fun requestReviewFlow(): Task<ReviewInfo> {
                requested.complete(Unit)
                return pending.task
            }
        }
        val job = launch { requestPlayReview(ui.activity, { eligible }, manager) }
        requested.await()
        eligible = false
        pending.setResult(FakeReviewManager(context).requestReviewFlow().result)
        job.join()
        assertEquals(0, delegate.launches)
    }

    @Test fun cancelledRequestAndGoogleErrorsDoNotLaunchOrBlockTheApp() = runBlocking {
        val pending = TaskCompletionSource<ReviewInfo>()
        val requested = CompletableDeferred<Unit>()
        val delegate = RecordingManager(context)
        val manager = object : ReviewManager by delegate {
            override fun requestReviewFlow(): Task<ReviewInfo> {
                requested.complete(Unit)
                return pending.task
            }
        }
        val job = launch { requestPlayReview(ui.activity, { true }, manager) }
        requested.await()
        job.cancel(); job.join()
        pending.setException(IllegalStateException("Simulated Play failure"))
        assertEquals(0, delegate.launches)
        // An already failed Task is also optional: no exception reaches normal navigation.
        requestPlayReview(ui.activity, { true }, manager)
        assertEquals(0, delegate.launches)
    }

    @Test fun manualRateUsUsesCurrentPackageAndFallsBackToBrowser() {
        val captured = mutableListOf<Intent>()
        val intercepted = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                captured += intent
                if(intent.`package` == "com.android.vending") throw ActivityNotFoundException()
            }
        }
        assertTrue(openPlayStoreReview(intercepted))
        assertEquals(2, captured.size)
        assertEquals("com.android.vending", captured.first().`package`)
        assertEquals("https://play.google.com/store/apps/details?id=com.gamixtv.MotionMagicAI", captured.last().dataString)
        val unavailable = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent): Unit = throw ActivityNotFoundException()
        }
        assertFalse(openPlayStoreReview(unavailable))
    }

    private class RecordingManager(context: Context) : ReviewManager {
        private val fake = FakeReviewManager(context)
        var requests = 0
        var launches = 0
        override fun requestReviewFlow(): Task<ReviewInfo> { requests++; return fake.requestReviewFlow() }
        override fun launchReviewFlow(activity: Activity, info: ReviewInfo): Task<Void> {
            launches++
            return fake.launchReviewFlow(activity, info)
        }
    }
}
