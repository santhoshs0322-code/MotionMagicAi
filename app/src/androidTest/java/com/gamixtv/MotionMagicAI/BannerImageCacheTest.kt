package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.data.HomeBanner
import com.gamixtv.MotionMagicAI.ui.BannerImageRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BannerImageCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val origin = "https://api.example.test"
    private val source = HomeBanner("test", "Uploaded", "templates", imageUrl = "https://test-bucket.s3.amazonaws.com/catalog/templates/test/banner/one.jpg?signature=old", revision = "one")
    private fun bytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 32, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    @Test fun restartAndSignedUrlRenewalUseDiskWithoutRedownloading() = runBlocking {
        val folder = File(context.cacheDir, "banner-test-${UUID.randomUUID()}")
        try {
            val calls = AtomicInteger()
            val image = bytes()
            val repository = BannerImageRepository(context, origin, folder) { calls.incrementAndGet(); image }
            assertNotNull(repository.load(source))
            assertNotNull(repository.load(source.copy(imageUrl = source.imageUrl!!.replace("old", "new"), revision = "renamed")))
            assertEquals(1, calls.get())
            val reopened = BannerImageRepository(context, origin, folder) { error("A cached banner must not use the network") }
            assertNotNull(reopened.load(source.copy(imageUrl = source.imageUrl!!.replace("old", "renewed"))))
            assertNotNull(repository.load(source.copy(imageUrl = source.imageUrl!!.replace("one.jpg", "two.jpg"))))
            assertEquals(2, calls.get())
        } finally { folder.deleteRecursively() }
    }

    @Test fun duplicateLoadsShareOneDownloadAndCancelledLoadsCanRetry() = runBlocking {
        val folder = File(context.cacheDir, "banner-test-${UUID.randomUUID()}")
        try {
            val calls = AtomicInteger()
            val image = bytes()
            val repository = BannerImageRepository(context, origin, folder) { calls.incrementAndGet(); delay(60); image }
            val results = coroutineScope { (1..8).map { async { repository.load(source) } }.awaitAll() }
            assertTrue(results.all { it != null })
            assertEquals(1, calls.get())
            val started = CompletableDeferred<Unit>()
            val another = source.copy(imageUrl = source.imageUrl!!.replace("one.jpg", "cancel.jpg"))
            val cancelRepo = BannerImageRepository(context, origin, folder) {
                if(calls.incrementAndGet() == 2) { started.complete(Unit); awaitCancellation() }
                image
            }
            val job = launch { cancelRepo.load(another) }
            started.await(); job.cancelAndJoin()
            assertNotNull(cancelRepo.load(another))
            assertFalse(folder.listFiles().orEmpty().any { it.extension == "tmp" })
        } finally { folder.deleteRecursively() }
    }

    @Test fun corruptCacheRecoversAndUntrustedOrOversizedDownloadsAreNotSaved() = runBlocking {
        val folder = File(context.cacheDir, "banner-test-${UUID.randomUUID()}")
        try {
            val image = bytes()
            assertNotNull(BannerImageRepository(context, origin, folder) { image }.load(source))
            folder.listFiles()!!.single { it.extension == "img" }.writeText("broken")
            val recovered = BannerImageRepository(context, origin, folder) { image }
            assertNotNull(recovered.load(source))
            var called = false
            val denied = BannerImageRepository(context, origin, folder) { called = true; image }
            for(url in listOf("http://test-bucket.s3.amazonaws.com/catalog/templates/a", "https://evil.test/a", "https://key@test-bucket.s3.amazonaws.com/catalog/templates/a")) {
                assertNull(denied.load(source.copy(imageUrl = url)))
            }
            assertFalse(called)
            val oversized = BannerImageRepository(context, origin, folder) { ByteArray(5 * 1024 * 1024 + 1) }
            assertNull(oversized.load(source.copy(imageUrl = source.imageUrl!!.replace("one.jpg", "large.jpg"))))
        } finally { folder.deleteRecursively() }
    }

    @Test fun persistentCacheIsBoundedEvenForLargeOriginalUploads() = runBlocking {
        val folder = File(context.cacheDir, "banner-test-${UUID.randomUUID()}")
        try {
            val image = bytes().copyOf(5 * 1024 * 1024)
            val repository = BannerImageRepository(context, origin, folder) { image }
            for(index in 0..7) assertNotNull(repository.load(source.copy(imageUrl = source.imageUrl!!.replace("one.jpg", "$index.jpg"))))
            assertTrue(folder.listFiles().orEmpty().sumOf { it.length() } <= 32L * 1024 * 1024)
        } finally { folder.deleteRecursively() }
    }
}
