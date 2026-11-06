package com.gamixtv.MotionMagicAI.reviews

import org.junit.Assert.*
import org.junit.Test

class ReviewPolicyTest {
    private val now = 1_800_000_000_000L
    private val three = setOf("one", "two", "three")

    @Test fun waitsForThreeDistinctViewedCreations() {
        var viewed = emptySet<String>()
        listOf("one", "one", "two", "two").forEach {
            viewed = ReviewPolicy.recordViewed(viewed, it)
            assertFalse(ReviewPolicy.eligible(viewed, 0, now))
        }
        viewed = ReviewPolicy.recordViewed(viewed, "three")
        assertTrue(ReviewPolicy.eligible(viewed, 0, now))
    }

    @Test fun downloadingThenOpeningTheSameCloudResultDoesNotCountTwice() {
        val viewed = ReviewPolicy.recordViewed(setOf("job-one"), "cloud_job-one")
        assertEquals(setOf("job-one"), viewed)
        assertFalse(ReviewPolicy.eligible(viewed, 0, now))
    }

    @Test fun historyRemainsBoundedRegardlessOfNumberOfCreations() {
        var viewed = emptySet<String>()
        repeat(10_000) { viewed = ReviewPolicy.recordViewed(viewed, "result-$it") }
        assertEquals(setOf("result-0", "result-1", "result-2"), viewed)
    }

    @Test fun allowsAnotherAttemptOnlyAtNinetyDayBoundary() {
        assertFalse(ReviewPolicy.eligible(three, now, now + ReviewPolicy.COOLDOWN_MILLIS - 1))
        assertTrue(ReviewPolicy.eligible(three, now, now + ReviewPolicy.COOLDOWN_MILLIS))
    }

    @Test fun clockMovingBackOrInvalidTimestampsCannotBypassCooldown() {
        assertFalse(ReviewPolicy.eligible(three, now, now - 1))
        assertFalse(ReviewPolicy.eligible(three, -1, now))
        assertFalse(ReviewPolicy.eligible(three, 0, 0))
    }

    @Test fun emptyCreationIdsAreNotProgress() {
        assertEquals(emptySet<String>(), ReviewPolicy.recordViewed(emptySet(), "cloud_"))
        assertEquals(emptySet<String>(), ReviewPolicy.recordViewed(emptySet(), ""))
    }
}
