package com.gamixtv.MotionMagicAI.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudJobTest {
    @Test fun longStoryDistinguishesSceneGenerationFromAssembly() {
        val story = job("story", "running").copy(storyScenesDone = 3, storyScenesTotal = 20)
        assertEquals("Creating scene 4 of 20", story.statusLabel)
        assertEquals("Assembling your story", story.copy(storyScenesDone = 20).statusLabel)
        assertEquals("Completed · saved to your account", story.copy(state = "succeeded").statusLabel)
    }
    @Test fun moderatedFailuresAreBlockedTerminalAndCannotBeDownloaded() {
        val blocked = job("blocked", "failed").copy(errorCode = "content_policy_violation", chargedPaise = 0)
        assertTrue(blocked.isContentBlocked)
        assertFalse(blocked.isReady)
        assertFalse(blocked.isActive)
        assertTrue(blocked.statusLabel.contains("Content blocked"))
        assertFalse(blocked.copy(errorCode = "provider_failed").isContentBlocked)
        assertFalse(blocked.copy(state = "running").isContentBlocked)
        val merged = mergeCloudJobs(listOf(job("blocked", "running")), listOf(blocked), emptySet())
        assertEquals(blocked, merged.single())
    }
    private fun job(id: String, state: String = "succeeded", time: Long = 1) =
        CloudJob(id, "Scene", "video", state, 10, "9:16", "asset", 25, "", createdAt = time)

    @Test fun loadingOlderPagesRetainsHistoryAndActiveWorkWithoutDuplicateResults() {
        val existing = (31..70).map { job(it.toString(), time = it.toLong()) } + job("running", "running", 80)
        val incoming = (1..35).map { job(it.toString(), time = it.toLong()) }
        val merged = mergeCloudJobs(existing, incoming, setOf("3"), retainMissingActive = true)
        assertEquals(70, merged.size)
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
        assertEquals("running", merged.first().id)
        assertFalse(merged.any { it.id == "3" })
    }

    @Test fun pollingRetainsLoadedHistoryAndDownloadProgressAndUpdatesCompletedWork() {
        val downloading = job("download", "downloading").copy(progressPercent = 63, previewPath = "private-preview", previewVideo = true)
        val existing = listOf(job("older"), job("done", "running"), job("gone", "running"), downloading)
        val merged = mergeCloudJobs(existing, listOf(job("done"), job("download")), emptySet())
        assertEquals(setOf("older", "done", "download"), merged.map { it.id }.toSet())
        assertEquals(downloading, merged.first { it.id == "download" })
        assertTrue(merged.first { it.id == "done" }.isReady)
    }
}
