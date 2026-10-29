package com.motionmagic.ai.media

/** Edits reference source ranges. Splitting never decodes or duplicates the media. */
data class EditorClip(val source: String, val durationMs: Long, val startMs: Long = 0,
    val endMs: Long = durationMs, val label: String = "Clip") {
    val lengthMs get() = endMs - startMs
}

data class EditorDocument(
    val clips: List<EditorClip> = emptyList(), val selected: Int = 0,
    val speed: Float = 1f, val volume: Float = 1f, val pitch: Float = 1f,
    val fadeInMs: Long = 0, val fadeOutMs: Long = 0,
    val bass: Float = 1f, val treble: Float = 1f, val noiseGate: Boolean = false,
    val text: String = "", val sticker: String = "", val filter: String = "None",
    val music: String? = null, val musicName: String = "", val musicVolume: Float = .35f,
    val height: Int = 1080,
) {
    val durationMs get() = clips.sumOf { it.lengthMs }
    val exportDurationMs get() = (durationMs / speed.toDouble()).toLong()
    fun split(positionMs: Long): EditorDocument {
        require(clips.size < 32) { "This timeline supports up to 32 clips." }
        var offset = 0L
        clips.forEachIndexed { index, clip ->
            val point = positionMs - offset
            if (point in 250..(clip.lengthMs - 250)) {
                val cut = clip.startMs + point
                return copy(clips = clips.take(index) + listOf(clip.copy(endMs = cut), clip.copy(startMs = cut)) + clips.drop(index + 1), selected = index + 1)
            }
            offset += clip.lengthMs
        }
        error("Move the playhead at least 0.25 seconds from a clip edge.")
    }
    fun validate() {
        require(clips.isNotEmpty() && clips.size <= 32) { "Add between 1 and 32 clips." }
        clips.forEach {
            require(it.durationMs in 250..(Long.MAX_VALUE / 64) && it.startMs >= 0 && it.endMs <= it.durationMs && it.lengthMs >= 250) { "Keep at least 0.25 seconds inside each source clip." }
        }
        require(speed.isFinite() && speed in .25f..3f && pitch.isFinite() && pitch in .5f..2f) { "Choose a supported speed or pitch." }
        require(volume.isFinite() && volume in 0f..2f && musicVolume.isFinite() && musicVolume in 0f..1f) { "Choose a valid volume." }
        require(fadeInMs in 0..30000 && fadeOutMs in 0..30000) { "Fades must be between 0 and 30 seconds." }
        require(bass.isFinite() && bass in .25f..2f && treble.isFinite() && treble in .25f..2f) { "Choose valid equalizer levels." }
        require(height in listOf(0, 480, 720, 1080)) { "Choose Source, 480p, 720p or 1080p." }
        require(text.length <= 120 && sticker.length <= 16) { "Caption or sticker is too long." }
    }
}

/** Fixed history budget: at most 30 documents, each with at most 32 source references. */
class EditorHistory(initial: EditorDocument) {
    var current = initial; private set
    private val past = ArrayDeque<EditorDocument>()
    private val future = ArrayDeque<EditorDocument>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun update(value: EditorDocument) {
        if (value == current) return
        if (past.size == 30) past.removeFirst()
        past.addLast(current); current = value; future.clear()
    }
    fun undo(): EditorDocument { if (past.isNotEmpty()) { future.addLast(current); current = past.removeLast() }; return current }
    fun redo(): EditorDocument { if (future.isNotEmpty()) { past.addLast(current); current = future.removeLast() }; return current }
}
