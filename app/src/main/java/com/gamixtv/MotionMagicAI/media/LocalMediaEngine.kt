package com.gamixtv.MotionMagicAI.media

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Offline image-animation renderer. It makes a real, silent H.264 MP4 with a gentle
 * camera move; it does not generate new imagery or claim to be an AI model.
 *
 * All EGL calls run on one IO thread without suspension between setup and release.
 * The caller owns [Bitmap] inputs and must not recycle them during rendering.
 */
object LocalMediaEngine {
    private const val MIME = "video/avc"
    private const val FPS = 24
    private const val BIT_RATE = 2_000_000
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val DRAIN_TIMEOUT_MS = 15_000L

    /**
     * Writes a new MP4. [outputFile] must not already exist. On failure or coroutine
     * cancellation, partial output is deleted and all codec/EGL resources are released.
     * [onProgress] runs on the worker thread and reports values from 0 to 1.
     */
    suspend fun createVideo(
        context: Context,
        bitmap: Bitmap,
        durationSeconds: Int,
        aspectRatio: String,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(durationSeconds in setOf(5, 10, 15)) { "Choose a duration of 5, 10, or 15 seconds." }
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "The selected image is no longer available." }
        val (width, height) = dimensions(aspectRatio)
        require(!outputFile.exists()) { "The output file already exists. Choose a new filename." }
        val parent = outputFile.absoluteFile.parentFile ?: context.cacheDir
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create the video folder.")
        // Allow room for the encoded stream, MP4 metadata, and temporary filesystem overhead.
        val requiredSpace = BIT_RATE.toLong() / 8 * durationSeconds + 5L * 1024 * 1024
        if (StatFs(parent.absolutePath).availableBytes < requiredSpace) {
            throw IOException("Not enough storage to create this video. Free some space and try again.")
        }

        val cancellation = currentCoroutineContext()
        cancellation.ensureActive()
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var renderer: FrameRenderer? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var completed = false
        var track = -1
        var sampleCount = 0
        var lastTimestampUs = -1L
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            onProgress(0f)
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Baseline prevents B-frame reordering, keeping presentation timestamps monotonic.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            val codec = MediaCodec.createEncoderByType(MIME)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            inputSurface = surface
            val frames = FrameRenderer(surface, bitmap, width, height)
            renderer = frames
            codec.start()
            codecStarted = true
            val outputMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = outputMuxer

            fun drain(endOfStream: Boolean) {
                val deadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
                while (true) {
                    cancellation.ensureActive()
                    val status = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) CODEC_TIMEOUT_US else 0L)
                    when {
                        status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (SystemClock.elapsedRealtime() > deadline) {
                                throw IOException("The video encoder did not finish. Please try again.")
                            }
                        }
                        status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "The encoder changed its format unexpectedly." }
                            track = outputMuxer.addTrack(codec.outputFormat)
                            outputMuxer.start()
                            muxerStarted = true
                        }
                        status >= 0 -> {
                            try {
                                val buffer = codec.getOutputBuffer(status)
                                    ?: throw IOException("The encoder returned an empty buffer.")
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    bufferInfo.size = 0
                                }
                                if (bufferInfo.size > 0) {
                                    check(muxerStarted && track >= 0) { "The video format is unavailable." }
                                    check(bufferInfo.presentationTimeUs >= lastTimestampUs) {
                                        "The encoder returned frames out of order."
                                    }
                                    buffer.position(bufferInfo.offset)
                                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                                    outputMuxer.writeSampleData(track, buffer, bufferInfo)
                                    lastTimestampUs = bufferInfo.presentationTimeUs
                                    sampleCount++
                                }
                            } finally {
                                codec.releaseOutputBuffer(status, false)
                            }
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            val frameCount = durationSeconds * FPS
            for (frame in 0 until frameCount) {
                cancellation.ensureActive()
                // Drain first: filling an input Surface without consuming output can deadlock.
                drain(endOfStream = false)
                frames.draw(frame.toFloat() / max(1, frameCount - 1))
                // Timestamp is media time, independent of how quickly this device renders.
                frames.submit(frame.toLong() * 1_000_000_000L / FPS)
                if (frame % 4 == 0) onProgress((frame + 1).toFloat() / frameCount * .95f)
            }
            codec.signalEndOfInputStream()
            drain(endOfStream = true)
            check(sampleCount > 0 && muxerStarted) { "No video frames were created." }
            cancellation.ensureActive()
            outputMuxer.stop()
            muxerStarted = false
            outputMuxer.release()
            muxer = null
            check(outputFile.length() > 0) { "The video file is empty." }
            onProgress(1f)
            completed = true
            outputFile
        } finally {
            // Each release is isolated so one vendor codec failure cannot skip the others.
            runCatching { renderer?.close() }
            if (codecStarted) runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!completed) outputFile.delete()
        }
    }

    /** Saves to Movies/MotionMagic on Android 10+, with no broad storage permission. */
    suspend fun exportToGallery(
        context: Context,
        source: File,
        displayName: String = source.name,
    ): Uri = exportToGallery(context, Uri.fromFile(source), displayName)

    suspend fun exportToGallery(context: Context, source: Uri, displayName: String, mime: String = "video/mp4"): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Use the system Save As picker on this Android version.")
        }
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "MotionMagic-${System.currentTimeMillis()}.mp4" }
            .let { val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp4"; "${it.substringBeforeLast('.', it)}.$extension" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, safeName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MotionMagic")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create a video in the gallery.")
        var committed = false
        try {
            val cancellation = currentCoroutineContext()
            MediaAccess.open(context, source).use { input ->
                (resolver.openOutputStream(uri) ?: throw IOException("Cannot write the gallery video.")).use { output ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        cancellation.ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        output.write(bytes, 0, count)
                    }
                    output.flush()
                }
            }
            cancellation.ensureActive()
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            if (published == 0) throw IOException("The gallery could not finish saving this video.")
            committed = true
            uri
        } finally {
            if (!committed) runCatching { resolver.delete(uri, null, null) }
        }
    }

    /** Provide a FileProvider or MediaStore content URI; raw file:// URIs are rejected. */
    fun shareIntent(contentUri: Uri): Intent {
        require(contentUri.scheme == "content") { "Sharing requires a content URI." }
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newRawUri("MotionMagic video", contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun dimensions(aspectRatio: String): Pair<Int, Int> = when (aspectRatio) {
        "9:16" -> 540 to 960
        "16:9" -> 960 to 540
        "1:1" -> 540 to 540
        else -> throw IllegalArgumentException("Choose 9:16, 16:9, or 1:1.")
    }
}

/** Minimal GLES2 renderer targeting the hardware encoder's Surface. */
private class FrameRenderer(
    target: Surface,
    image: Bitmap,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var positionLocation = 0
    private var uvLocation = 0
    private var samplerLocation = 0
    private val imageAspect = image.width.toFloat() / image.height
    private val positions = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv = floatBuffer(FloatArray(8))

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "OpenGL display is unavailable." }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Cannot initialize OpenGL." }
            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "This device does not support recording an OpenGL surface."
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "Cannot create the video renderer." }
            surface = EGL14.eglCreateWindowSurface(display, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "Cannot connect the video renderer." }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Cannot activate the video renderer." }

            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER,
                "attribute vec4 aPosition; attribute vec2 aUv; varying vec2 vUv; void main() { gl_Position = aPosition; vUv = aUv; }")
            var fragmentShader = 0
            try {
                fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float; uniform sampler2D uImage; varying vec2 vUv; void main() { vec4 p = texture2D(uImage, vUv); gl_FragColor = vec4(p.rgb, 1.0); }")
                program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, vertexShader)
                GLES20.glAttachShader(program, fragmentShader)
                GLES20.glLinkProgram(program)
                val linked = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
                check(linked[0] == GLES20.GL_TRUE) { "Cannot link the video shader: ${GLES20.glGetProgramInfoLog(program)}" }
            } finally {
                GLES20.glDeleteShader(vertexShader)
                if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
            }
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
            uvLocation = GLES20.glGetAttribLocation(program, "aUv")
            samplerLocation = GLES20.glGetUniformLocation(program, "uImage")
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            // Bound texture memory, and copy hardware Bitmaps into CPU-readable storage.
            val maxTexture = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            val limit = minOf(2048, maxTexture[0])
            check(limit > 0) { "The device cannot load image textures." }
            val scale = minOf(1f, limit.toFloat() / max(image.width, image.height))
            var upload: Bitmap = image
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && image.config == Bitmap.Config.HARDWARE) {
                    upload = image.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IOException("Cannot read the selected image.")
                }
                if (scale < 1f) {
                    val resized = Bitmap.createScaledBitmap(upload,
                        max(1, (image.width * scale).roundToInt()),
                        max(1, (image.height * scale).roundToInt()), true)
                    if (upload !== image && upload !== resized) upload.recycle()
                    upload = resized
                }
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, upload, 0)
                checkGl("loading the image")
            } finally {
                if (upload !== image) upload.recycle()
            }
        } catch (failure: Throwable) {
            runCatching { close() }
            throw failure
        }
    }

    fun draw(progress: Float) {
        val outputAspect = width.toFloat() / height
        val zoom = 1f + .12f * progress
        val visibleWidth = minOf(1f, outputAspect / imageAspect) / zoom
        val visibleHeight = minOf(1f, imageAspect / outputAspect) / zoom
        val horizontalMove = sin(progress * PI).toFloat() * .025f
        val centerX = (.5f + horizontalMove).coerceIn(visibleWidth / 2, 1f - visibleWidth / 2)
        val centerY = (.5f - progress * .015f).coerceIn(visibleHeight / 2, 1f - visibleHeight / 2)
        val left = centerX - visibleWidth / 2
        val right = centerX + visibleWidth / 2
        val top = centerY - visibleHeight / 2
        val bottom = centerY + visibleHeight / 2
        uv.clear()
        uv.put(floatArrayOf(left, bottom, right, bottom, left, top, right, top))
        uv.position(0)
        positions.position(0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(.02f, .02f, .04f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(uvLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(uvLocation)
        checkGl("drawing a video frame")
    }

    fun submit(timestampNs: Long) {
        check(EGLExt.eglPresentationTimeANDROID(display, surface, timestampNs)) { "Cannot timestamp the video frame." }
        check(EGL14.eglSwapBuffers(display, surface)) { "Cannot send the video frame to the encoder." }
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, surface, surface, context)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        texture = 0
        program = 0
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Cannot create the video shader." }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val reason = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IOException("Cannot compile the video shader: $reason")
        }
        return shader
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) throw IOException("OpenGL error $error while $operation.")
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}
