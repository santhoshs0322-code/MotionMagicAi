package com.gamixtv.MotionMagicAI

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.media.DeviceVoiceEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceVoiceTest {
    @Test fun deviceSpeechCreatesWaveAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "voice_test_${UUID.randomUUID()}.wav")
        try {
            DeviceVoiceEngine.synthesize(context, "Welcome to MotionMagic. Your creative studio.", "Natural", file)
            assertTrue(file.length() > 44)
            val header = file.inputStream().use { input -> ByteArray(12).also { assertEquals(12, input.read(it)) } }
            assertEquals("RIFF", String(header.copyOfRange(0, 4), Charsets.US_ASCII))
            assertEquals("WAVE", String(header.copyOfRange(8, 12), Charsets.US_ASCII))
        } finally { file.delete() }
    }
}
