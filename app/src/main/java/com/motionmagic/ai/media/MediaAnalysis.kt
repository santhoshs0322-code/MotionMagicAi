package com.motionmagic.ai.media

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Fixed budgets independent of source file size; results are preview samples, not a sample editor. */
object MediaAnalysis {
    private val worker = Semaphore(1)
    private val pictures = object : LruCache<String, List<Bitmap>>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: List<Bitmap>) = value.sumOf { it.allocationByteCount }
    }
    private val waves = LruCache<String, FloatArray>(24)
    suspend fun thumbnails(context: Context, source: String, durationMs: Long, startMs: Long = 0, endMs: Long = durationMs): List<Bitmap> = withContext(Dispatchers.IO) {
        // API 26 only offers full-resolution frame retrieval. Keep its preview memory bounded.
        if (android.os.Build.VERSION.SDK_INT < 27) return@withContext emptyList()
        val key = "$source:$durationMs:$startMs:$endMs"
        pictures.get(key)?.let { return@withContext it }
        worker.withPermit {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(context, MediaAccess.uri(source))
                val images = (0 until 8).mapNotNull { index ->
                    ensureActive()
                    reader.getScaledFrameAtTime((startMs + (endMs - startMs) * index / 8) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                }
                pictures.put(key, images); images
            } finally { reader.release() }
        }
    }
    suspend fun waveform(context: Context, source: String, durationMs: Long, startMs: Long = 0, endMs: Long = durationMs): FloatArray = withContext(Dispatchers.IO) {
        val key = "$source:$durationMs:$startMs:$endMs"
        waves.get(key)?.let { return@withContext it }
        worker.withPermit {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(context, MediaAccess.uri(source), null)
                val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: return@withPermit FloatArray(64)
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                decoder = codec; codec.configure(format, null, null, 0); codec.start()
                val info = MediaCodec.BufferInfo()
                val peaks = FloatArray(64)
                for (index in peaks.indices) {
                    ensureActive()
                    val targetUs = (startMs + (endMs - startMs) * index / peaks.size) * 1000
                    extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    codec.flush()
                    var eos = false
                    var gotSample = false
                    var attempts = 0
                    var encoding = AudioFormat.ENCODING_PCM_16BIT
                    while (!gotSample && attempts++ < 40) {
                        ensureActive()
                        if (!eos) {
                            val input = codec.dequeueInputBuffer(5000)
                            if (input >= 0) {
                                val buffer = codec.getInputBuffer(input)!!
                                val bytes = extractor.readSampleData(buffer, 0)
                                if (bytes < 0) { codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                                else { codec.queueInputBuffer(input, 0, bytes, extractor.sampleTime, 0); extractor.advance() }
                            }
                        }
                        val output = codec.dequeueOutputBuffer(info, 5000)
                        if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            encoding = if (codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        } else if (output >= 0) {
                            try {
                                if (info.size > 0 && info.presentationTimeUs >= targetUs) {
                                    val buffer = codec.getOutputBuffer(output)!!.duplicate().order(ByteOrder.nativeOrder())
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    var peak = 0f
                                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) while (buffer.remaining() >= 4) peak = max(peak, abs(buffer.float))
                                    else while (buffer.remaining() >= 2) peak = max(peak, abs(buffer.short / 32768f))
                                    peaks[index] = peak.coerceIn(0f, 1f); gotSample = true
                                }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            } finally { codec.releaseOutputBuffer(output, false) }
                        }
                    }
                }
                waves.put(key, peaks); peaks
            } finally { runCatching { decoder?.stop() }; decoder?.release(); extractor.release() }
        }
    }
}
