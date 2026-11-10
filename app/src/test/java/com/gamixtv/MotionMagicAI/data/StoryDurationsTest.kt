package com.gamixtv.MotionMagicAI.data

import org.junit.Assert.*
import org.junit.Test

class StoryDurationsTest {
    @Test fun halfMinutesUseExactSceneCountsAndReadableLabels() {
        assertEquals(listOf(60,90,120,150,180,210,240,270,300), StoryDurations.seconds)
        assertEquals(listOf(6,10,14,18), listOf(90,150,210,270).map { it / 15 })
        assertEquals("1.5 min", StoryDurations.label(90))
        assertEquals("2.5 min", StoryDurations.label(150))
        assertEquals("3.5 min", StoryDurations.label(210))
        assertEquals("5 min", StoryDurations.label(300))
    }
}
