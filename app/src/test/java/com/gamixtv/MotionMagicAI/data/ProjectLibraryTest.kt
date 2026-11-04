package com.gamixtv.MotionMagicAI.data

import com.gamixtv.MotionMagicAI.cloud.CloudJob
import org.junit.Assert.*
import org.junit.Test

class ProjectLibraryTest {
    private fun job(id: String, kind: String = "video") = CloudJob(id, "Forest scene", kind, "succeeded", 10, "9:16", "asset", 25, "", createdAt = 10)

    @Test fun savedCopiesAppearOnceAndMissingLocalFilesCanBeDownloadedAgain() {
        val local = Project(id = "cloud_one", title = "Forest scene", path = "/private/result.mp4", kind = MediaKind.VIDEO)
        val existing = projectLibrary(listOf(local), listOf(job("one"), job("two", "image")), setOf(local.id))
        assertEquals(2, existing.size)
        assertTrue(existing.first { it.key == local.id } is LibraryItem.Device)
        val missing = projectLibrary(listOf(local), listOf(job("one")), emptySet())
        assertEquals(1, missing.size)
        assertTrue(missing.single() is LibraryItem.Cloud)
    }

    @Test fun imageVideoVoiceFiltersAndPromptSearchUseOnlyTheSuppliedAccountRecords() {
        val voice = Project(title = "Welcome voice", path = "/private/voice.wav", kind = MediaKind.AUDIO)
        val entries = projectLibrary(listOf(voice), listOf(job("one"), job("two", "image")), setOf(voice.id))
        assertEquals(3, entries.count { it.matches(ProjectFilter.ALL, "") })
        assertEquals(1, entries.count { it.matches(ProjectFilter.IMAGES, "forest") })
        assertEquals(1, entries.count { it.matches(ProjectFilter.VIDEOS, "FOREST") })
        assertEquals(1, entries.count { it.matches(ProjectFilter.VOICE, " welcome ") })
        assertEquals(0, entries.count { it.matches(ProjectFilter.IMAGES, "welcome") })
    }
}
