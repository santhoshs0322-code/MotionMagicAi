package com.gamixtv.MotionMagicAI.media

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gamixtv.MotionMagicAI.data.CreationValidation
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Test-mode speech using the device's installed TTS engine, not a cloud AI voice model. */
object DeviceVoiceEngine {
    suspend fun synthesize(
        context: Context,
        text: String,
        voiceStyle: String,
        outputFile: File,
    ): File = withContext(Dispatchers.Main.immediate) {
        val validation = CreationValidation.validateVoice(text)
        if (!validation.isValid) throw VoiceGenerationException(validation.message)
        if (!outputFile.extension.equals("wav", ignoreCase = true)) {
            throw VoiceGenerationException("Choose a WAV file for the device voice recording.")
        }
        val style = styles[voiceStyle.lowercase(Locale.ROOT)]
            ?: throw VoiceGenerationException("Choose Natural, Warm, Bright, Narrator, or Calm.")
        var engine: TextToSpeech? = null
        var createdOutput = false
        var completed = false
        try {
            withContext(Dispatchers.IO) {
                try {
                    val directory = outputFile.absoluteFile.parentFile
                        ?: throw IOException("Missing output directory")
                    if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create directory")
                    if (!outputFile.createNewFile()) {
                        throw VoiceGenerationException("This audio file already exists. Generate a new recording to keep the original.")
                    }
                    createdOutput = true
                } catch (error: IOException) {
                    throw VoiceGenerationException("Could not create the audio file. Free some device storage and try again.", error)
                } catch (error: SecurityException) {
                    throw VoiceGenerationException("The audio folder is unavailable. Choose an app-accessible folder and try again.", error)
                }
            }

            val initialized = CompletableDeferred<Int>()
            val speech = TextToSpeech(context.applicationContext) { status -> initialized.complete(status) }
            engine = speech
            val initializationStatus = withTimeoutOrNull(INIT_TIMEOUT_MS) { initialized.await() }
                ?: throw VoiceGenerationException("The device speech engine did not start. Check Android text-to-speech settings and try again.")
            if (initializationStatus != TextToSpeech.SUCCESS) {
                throw VoiceGenerationException("No working speech engine is available. Install or enable a text-to-speech engine in Android settings.")
            }

            val language = listOf(Locale.forLanguageTag("en-IN"), Locale.ENGLISH)
                .firstOrNull { speech.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
                ?: throw VoiceGenerationException("An English voice is unavailable. Download English voice data in Android text-to-speech settings, then retry.")

            // Prefer an installed offline voice. Availability varies by device; no gender is promised.
            speech.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == language.language }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == language.country }
                    .thenByDescending { it.quality })
                ?.firstOrNull()?.let { speech.voice = it }
            if (speech.setPitch(style.pitch) == TextToSpeech.ERROR ||
                speech.setSpeechRate(style.rate) == TextToSpeech.ERROR) {
                throw VoiceGenerationException("This speech engine could not apply the voice style. Try Natural or change the engine in Android settings.")
            }

            val utteranceId = UUID.randomUUID().toString()
            val synthesisResult = CompletableDeferred<Int>()
            val listenerStatus = speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    if (id == utteranceId) synthesisResult.complete(TextToSpeech.SUCCESS)
                }

                @Deprecated("Required by older TTS engines")
                override fun onError(id: String?) {
                    if (id == utteranceId) synthesisResult.complete(TextToSpeech.ERROR)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) synthesisResult.complete(errorCode)
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    if (id == utteranceId) synthesisResult.complete(STOPPED)
                }
            })
            if (listenerStatus == TextToSpeech.ERROR) {
                throw VoiceGenerationException("The speech engine could not report recording status. Restart the app or select another speech engine.")
            }
            if (speech.synthesizeToFile(text.trim(), Bundle(), outputFile, utteranceId) == TextToSpeech.ERROR) {
                throw VoiceGenerationException("The speech engine could not start this recording. Check voice data and available device storage.")
            }
            val result = withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) { synthesisResult.await() }
                ?: throw VoiceGenerationException("Voice recording timed out. Check the speech engine and retry with a shorter script.")
            if (result != TextToSpeech.SUCCESS) throw VoiceGenerationException(errorMessage(result))
            val hasAudio = withContext(Dispatchers.IO) { outputFile.isFile && outputFile.length() > 44L }
            if (!hasAudio) throw VoiceGenerationException("The speech engine returned an empty recording. Check voice data and try again.")
            completed = true
            outputFile
        } catch (error: SecurityException) {
            throw VoiceGenerationException("Android blocked the speech engine or audio file. Check the device's text-to-speech settings and try again.", error)
        } finally {
            // Both timeout and caller cancellation pass here. Never leak the service connection.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                engine?.let { speech ->
                    runCatching { speech.stop() }
                    runCatching { speech.shutdown() }
                }
            }
            if (createdOutput && !completed) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { outputFile.delete() }
            }
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT ->
            "This device voice needs a connection. Connect to the internet or install an offline English voice, then retry."
        TextToSpeech.ERROR_NOT_INSTALLED_YET ->
            "The selected voice data is not installed. Download it in Android text-to-speech settings, then retry."
        TextToSpeech.ERROR_OUTPUT ->
            "The speech engine could not write the recording. Free some device storage and try again."
        TextToSpeech.ERROR_INVALID_REQUEST ->
            "The speech engine could not read this script. Shorten or simplify the text and try again."
        STOPPED -> "Voice recording was interrupted. Try generating it again."
        else -> "The speech engine could not finish this recording. Check its voice data or try another engine."
    }

    private data class Style(val pitch: Float, val rate: Float)
    private val styles = mapOf(
        "natural" to Style(1f, 1f),
        "warm" to Style(0.9f, 0.95f),
        "bright" to Style(1.15f, 1.05f),
        "narrator" to Style(0.85f, 0.9f),
        "calm" to Style(0.95f, 0.85f),
    )
    private const val INIT_TIMEOUT_MS = 12_000L
    private const val SYNTHESIS_TIMEOUT_MS = 90_000L
    private const val STOPPED = -100
}

class VoiceGenerationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
