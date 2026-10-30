package com.gamixtv.MotionMagicAI.data

import com.gamixtv.MotionMagicAI.media.*
import org.junit.Assert.*
import org.junit.Test

class EditorDocumentTest {
    private val doc = EditorDocument(listOf(EditorClip("content://source", 7_200_000)))
    @Test fun splitLongRecordingPreservesRangesAndDurationWithoutCopyingSource() {
        val split = doc.split(3_600_000)
        assertEquals(2, split.clips.size)
        assertEquals(doc.durationMs, split.durationMs)
        assertEquals(split.clips[0].endMs, split.clips[1].startMs)
        assertEquals(split.clips[0].source, split.clips[1].source)
        assertEquals(3_600_000L, split.copy(speed = 2f).exportDurationMs)
    }
    @Test fun splittingAtAnEdgeOrBeyondClipBudgetFails() {
        for (point in listOf(0L, 249L, doc.durationMs - 249, doc.durationMs)) {
            try { doc.split(point); fail("Invalid split") } catch (_: IllegalStateException) { }
        }
        try { doc.copy(clips = List(32) { doc.clips.first() }).split(1000); fail("Too many clips") } catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsInvalidRangesAndNonFiniteControls() {
        for (bad in listOf(doc.copy(speed = Float.NaN), doc.copy(volume = Float.POSITIVE_INFINITY),
            doc.copy(clips = listOf(doc.clips.first().copy(startMs = 1000, endMs = 800))), doc.copy(height = 999))) {
            try { bad.validate(); fail("Invalid document") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun undoRedoIsBoundedAndNewChangeDiscardsRedo() {
        val history = EditorHistory(doc)
        repeat(50) { history.update(doc.copy(text = "Edit $it")) }
        repeat(30) { history.undo() }
        assertFalse(history.canUndo); assertTrue(history.canRedo)
        history.redo(); history.update(doc.copy(text = "New edit")); assertFalse(history.canRedo)
    }
}
