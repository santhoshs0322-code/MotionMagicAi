package com.gamixtv.MotionMagicAI.media

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.*

object MediaAccess {
    fun uri(path: String): Uri = if (path.startsWith("content://") || path.startsWith("file://")) Uri.parse(path) else Uri.fromFile(File(path))
    fun external(path: String) = path.startsWith("content://")
    fun open(context: Context, source: Uri): InputStream = context.contentResolver.openInputStream(source)
        ?: error("This file is unavailable. Select it again.")

    suspend fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read); count += read
        }
        require(count > 0) { "The selected file is empty." }
        output.flush()
    }
}
