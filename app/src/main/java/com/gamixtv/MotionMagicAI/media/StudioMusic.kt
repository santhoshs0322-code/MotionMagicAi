package com.gamixtv.MotionMagicAI.media

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlin.math.*

/** Original synthesized demo loops, produced lazily with a 4 KB buffer; no third-party music rights implied. */
object StudioMusic {
    val names = listOf("Sunset Vibes", "Upbeat Pop", "Cinematic Rise", "Chill Lo-Fi", "Acoustic Dreams", "Action Beat", "Happy Day")
    suspend fun file(context: Context, index: Int): File = withContext(Dispatchers.IO) {
        require(index in names.indices)
        val folder = File(context.filesDir, "studio-music").apply { mkdirs() }
        val file = File(folder, "loop-$index.wav")
        if (file.isFile && file.length() == 44L + 22050 * 30 * 2) return@withContext file
        val temp = File(folder, "loop-$index.tmp")
        val rate = 22050
        val samples = rate * 30
        try {
            temp.outputStream().buffered().use { out ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
                out.write(header.array())
                val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
                val notes = doubleArrayOf(261.63, 329.63, 392.0, 440.0, 392.0, 329.63, 293.66, 329.63)
                for (n in 0 until samples) {
                    if (n % 2048 == 0) ensureActive()
                    val t = n.toDouble() / rate
                    val beat = t * (1.4 + index * .12)
                    val note = notes[(beat.toInt() + index) % notes.size] * if (index == 2) .5 else 1.0
                    val envelope = exp(-3.0 * (beat % 1))
                    val fade = min(1.0, min(t * 3, (30 - t) * 3))
                    val sound = (.16 * sin(2 * PI * note * t) + .07 * sin(4 * PI * note * t)) * envelope + .07 * sin(2 * PI * note / 2 * t)
                    buffer.putShort((sound * fade * 32767).toInt().toShort())
                    if (!buffer.hasRemaining()) { out.write(buffer.array()); buffer.clear() }
                }
                if (buffer.position() > 0) out.write(buffer.array(), 0, buffer.position())
            }
            check(temp.renameTo(file)) { "Could not save the music loop." }
            file
        } finally { temp.delete() }
    }
}
