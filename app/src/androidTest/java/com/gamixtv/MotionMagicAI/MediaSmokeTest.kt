package com.gamixtv.MotionMagicAI

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.data.*
import com.gamixtv.MotionMagicAI.media.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun image(): Bitmap = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.rgb(35, 15, 65))
        val paint = Paint().apply { color = Color.MAGENTA }
        canvas.drawRect(10f, 10f, 100f, 100f, paint)
        paint.color = Color.CYAN
        canvas.drawCircle(230f, 320f, 100f, paint)
    }
    private fun output(ext: String = "mp4") = File(context.cacheDir, "smoke_${UUID.randomUUID()}.$ext")

    @Test fun createsPlayableClipsForEveryAspectRatio() = runBlocking {
        val bitmap = image()
        try {
            for((aspect, expected) in listOf("9:16" to (540 to 960), "16:9" to (960 to 540), "1:1" to (540 to 540))) {
                val file = output()
                try {
                    LocalMediaEngine.createVideo(context, bitmap, 5, aspect, file)
                    assertTrue(file.length() > 1000)
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.path)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                        assertTrue("Duration $duration", duration in 4900L..5100L)
                        assertEquals(expected.first.toString(), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                        assertEquals(expected.second.toString(), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                        assertNotNull(retriever.getFrameAtTime(1_000_000))
                    } finally { retriever.release() }
                } finally { file.delete() }
            }
        } finally { bitmap.recycle() }
    }

    @Test fun cancellationRemovesPartialVideo() = runBlocking {
        val bitmap = image()
        val file = output()
        try {
            try {
                LocalMediaEngine.createVideo(context, bitmap, 15, "9:16", file) { if(it > .12f) throw CancellationException("Test cancellation") }
                fail("Expected cancellation")
            } catch(_: CancellationException) { assertFalse("Partial output must be removed", file.exists()) }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun trimCaptionFilterAndMusicProduceVideoAndAudio() = runBlocking {
        val bitmap = image()
        val source = output()
        val edited = output()
        val music = output("wav")
        try {
            LocalMediaEngine.createVideo(context, bitmap, 5, "9:16", source)
            writeTone(music)
            Media3Editor.export(context, source, edited, EditOptions(startMs = 1000, endMs = 4000,
                text = "MotionMagic test", filter = "Warm", musicUri = Uri.fromFile(music)))
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(edited.path)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Edited duration $duration", duration in 2900L..3200L)
                assertNotNull(retriever.getFrameAtTime(1_000_000))
            } finally { retriever.release() }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(edited.path)
                val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! }
                assertTrue(types.any { it.startsWith("video/") })
                assertTrue(types.any { it.startsWith("audio/") })
            } finally { extractor.release() }
        } finally { bitmap.recycle(); source.delete(); edited.delete(); music.delete() }
    }

    @Test fun storePersistsDraftSettingsAndMissingFileMetadata() {
        val name = "smoke_${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = context.getSharedPreferences(name, mode)
        }
        try {
            val store = MotionStore(isolated)
            val project = Project(title = "Missing file retained", path = File(context.cacheDir, "missing.mp4").path, kind = MediaKind.VIDEO)
            val draft = CreationDraft("My prompt", "Anime", 15, "1:1")
            store.upsertProject(project)
            store.saveDraft(draft)
            store.saveSettings(AppSettings(defaultAspect = "16:9", autoSave = true))
            val reopened = MotionStore(isolated)
            assertEquals(project, reopened.projects().single())
            assertEquals(draft, reopened.draft())
            assertEquals("16:9", reopened.settings().defaultAspect)
            assertTrue(reopened.settings().autoSave)
            reopened.deleteProject(project.id)
            assertTrue(MotionStore(isolated).projects().isEmpty())
        } finally { context.deleteSharedPreferences(name) }
    }

    private fun writeTone(file: File) {
        val rate = 24000
        val bytes = rate * 2
        val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes)
        for(i in 0 until rate) buffer.putShort((sin(2 * Math.PI * 440 * i / rate) * 2000).toInt().toShort())
        file.writeBytes(buffer.array())
    }
}
