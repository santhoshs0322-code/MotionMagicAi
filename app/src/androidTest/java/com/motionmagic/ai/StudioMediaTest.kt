package com.motionmagic.ai

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.motionmagic.ai.media.*
import com.motionmagic.ai.ui.ArtRepository
import com.motionmagic.ai.ui.Artwork
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StudioMediaTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun file(ext: String) = File(context.filesDir, "projects/studio-test-${UUID.randomUUID()}.$ext").apply { parentFile!!.mkdirs() }
    private fun types(file: File): List<String> {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(file.path); return (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! } }
        finally { extractor.release() }
    }
    @Test fun opensOneGiBDocumentAndExportsShortAudioRange() = runBlocking {
        val source = file("wav"); val output = file("m4a")
        try {
            val bytes = 1024 * 1024 * 1024
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(bytes - 8).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes - 44)
            RandomAccessFile(source, "rw").use { it.write(header.array()); it.setLength(bytes.toLong()) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
            val before = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
            val started = SystemClock.elapsedRealtime()
            val info = MediaAccess.inspect(context, uri)
            val openMs = SystemClock.elapsedRealtime() - started
            assertEquals(bytes.toLong(), info.size)
            assertTrue(info.durationMs > 10_000_000)
            val from = info.durationMs - 4000
            StudioExporter.export(context, EditorDocument(clips = listOf(EditorClip(uri.toString(), info.durationMs, from, from + 2000)),
                fadeInMs = 250, fadeOutMs = 250), output, audioOnly = true)
            val result = MediaAccess.inspect(context, Uri.fromFile(output))
            assertTrue("Duration ${result.durationMs}", result.durationMs in 1900..2300)
            assertEquals(listOf("audio/mp4a-latm"), types(output))
            assertTrue(output.length() < 1024 * 1024)
            val after = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
            File(context.getExternalFilesDir(null), "large-media-measurement.txt").writeText(
                "Source bytes=$bytes\nDuration ms=${info.durationMs}\nMetadata open ms=$openMs\nPSS before KB=$before\nPSS after KB=$after\nOutput bytes=${output.length()}\nSparse PCM fixture; not a benchmark for every codec/provider.\n")
        } finally { source.delete(); output.delete() }
    }
    @Test fun concatenationSpeedFilterAndMusicProduceTimedVideo() = runBlocking {
        val source = file("mp4"); val output = file("mp4")
        try {
            LocalMediaEngine.createVideo(context, ArtRepository.bitmap(context, Artwork.Garden), 5, "16:9", source)
            val music = StudioMusic.file(context, 0)
            val document = EditorDocument(clips = listOf(EditorClip(source.path, 5000, 0, 2000), EditorClip(source.path, 5000, 2000, 5000)),
                speed = 2f, text = "Studio export", filter = "Cool", height = 480, music = music.path)
            StudioExporter.export(context, document, output)
            val info = MediaAccess.inspect(context, Uri.fromFile(output))
            assertTrue("Duration ${info.durationMs}", info.durationMs in 2400..2800)
            assertEquals(480, info.height)
            assertTrue(types(output).any { it.startsWith("video/") }); assertTrue(types(output).any { it.startsWith("audio/") })
            val previews = MediaAnalysis.thumbnails(context, output.path, info.durationMs)
            assertTrue(previews.isNotEmpty() && previews.size <= 8)
            assertTrue(previews.all { it.width <= 160 && it.height <= 90 })
        } finally { source.delete(); output.delete() }
    }
    @Test fun audioSpeedPitchAndBoundedWaveformExport() = runBlocking {
        val source = StudioMusic.file(context, 1); val output = file("m4a")
        try {
            val doc = EditorDocument(listOf(EditorClip(source.path, 30000, 1000, 7000)), speed = 1.5f, pitch = .8f,
                volume = .5f, bass = 1.2f, treble = .8f, fadeInMs = 400, fadeOutMs = 400, noiseGate = true)
            StudioExporter.export(context, doc, output, true)
            val info = MediaAccess.inspect(context, Uri.fromFile(output))
            assertTrue("Duration ${info.durationMs}", info.durationMs in 3900..4300)
            val waveform = MediaAnalysis.waveform(context, output.path, info.durationMs)
            assertEquals(64, waveform.size); assertTrue(waveform.any { it > .01f })
        } finally { output.delete() }
    }
    @Test fun streamingFadesChangeSamplesAndCancellationCleansOutput() = runBlocking {
        val processor = StudioAudioProcessor(.5f, 1000, 100, 100)
        processor.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT)); processor.flush()
        val data = ByteBuffer.allocateDirect(2000).order(ByteOrder.nativeOrder())
        repeat(1000) { data.putShort(20000) }; data.flip(); processor.queueInput(data)
        val result = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(0, result.getShort(0).toInt())
        assertTrue(result.getShort(1000).toInt() in 9900..10100)
        assertTrue(result.getShort(1998).toInt() in 0..150)
        processor.reset()
        val source = StudioMusic.file(context, 2); val output = file("m4a")
        val job = launch {
            StudioExporter.export(context, EditorDocument(listOf(EditorClip(source.path, 30000)), speed = .25f), output, true)
        }
        delay(50); job.cancelAndJoin(); assertFalse(output.exists())
    }
}
