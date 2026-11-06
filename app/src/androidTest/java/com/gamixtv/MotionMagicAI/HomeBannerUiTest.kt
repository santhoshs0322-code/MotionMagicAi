package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.ui.HomeBannerCatalogue
import com.gamixtv.MotionMagicAI.ui.HomeBannerCarousel
import com.gamixtv.MotionMagicAI.ui.ImageTemplateCatalogue
import com.gamixtv.MotionMagicAI.ui.MotionTheme
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeBannerUiTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val banners get() = HomeBannerCatalogue.bundled(context)

    private fun waitForBanner(number: Int, count: Int = 5) {
        val value = "Banner $number of $count"
        try {
            ui.waitUntil(6500) {
                runCatching { ui.onNodeWithTag("home-banner-carousel").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == value }.getOrDefault(false)
            }
        } catch(error: ComposeTimeoutException) {
            throw AssertionError("Expected $value; " + ui.onNodeWithTag("home-banner-carousel").printToString(), error)
        }
    }

    @Test fun bundledBannersAdvance123451InFourSecondIntervals() {
        val rows = banners
        assertEquals(5, rows.size)
        rows.forEach {
            assertEquals(4, it.displaySeconds)
            val path = checkNotNull(ImageTemplateCatalogue.bundledPath(context, it.bundledFile, it.bundledHash))
            val bytes = context.assets.open(path).use { input -> input.readBytes() }
            assertEquals(it.bundledHash, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) })
        }
        ui.setContent { MotionTheme(ThemeMode.DARK) { HomeBannerCarousel(rows, navigate = {}) } }
        waitForBanner(1)
        ui.onNodeWithTag("home-banner-position").assertDoesNotExist()
        listOf(2, 3, 4, 5, 1, 2).forEach {
            ui.mainClock.advanceTimeBy(4_100)
            waitForBanner(it)
        }
        val screenshot = ui.onNodeWithTag("home-banner-carousel").captureToImage().asAndroidBitmap()
        try {
            File(context.filesDir, "home-banners-full-frame.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { screenshot.recycle() }
    }

    @Test fun fullFrameHasNoIndicatorsAndSwipeAndBannerDestinationStillWork() {
        var destination: Screen? = null
        ui.setContent { MotionTheme(ThemeMode.LIGHT) { HomeBannerCarousel(banners, active = false, navigate = { destination = it }) } }
        waitForBanner(1)
        ui.onNodeWithTag("home-banner-position").assertDoesNotExist()
        ui.onNodeWithContentDescription("Show banner 5").assertDoesNotExist()
        val frame = ui.onNodeWithTag("home-banner-carousel").fetchSemanticsNode().boundsInRoot
        val image = ui.onNodeWithTag("home-banner-page-1").fetchSemanticsNode().boundsInRoot
        assertEquals(frame.width, image.width, 1f)
        assertEquals(frame.height, image.height, 1f)
        ui.onNodeWithTag("home-banner-page-1").performTouchInput { swipeLeft(durationMillis = 350) }
        waitForBanner(2)
        ui.onNodeWithTag("home-banner-page-2").performClick()
        assertEquals(Screen.TEXT, destination)
        for(number in 3..5) {
            ui.onNodeWithTag("home-banner-page-${number - 1}").performTouchInput { swipeLeft(durationMillis = 350) }
            waitForBanner(number)
        }
        ui.onNodeWithTag("home-banner-page-5").performTouchInput { swipeLeft(durationMillis = 350) }
        waitForBanner(1)
    }

    @Test fun singleBannerAndUnpublishedFeedDoNotShowPhantomSlides() {
        var rows by mutableStateOf(banners.take(1))
        ui.setContent { MotionTheme(ThemeMode.DARK) { HomeBannerCarousel(rows, navigate = {}) } }
        waitForBanner(1, 1)
        ui.onNodeWithTag("home-banner-position").assertDoesNotExist()
        ui.onNodeWithContentDescription("Show banner 2").assertDoesNotExist()
        ui.runOnIdle { rows = HomeBannerCatalogue.parse(JSONArray()) }
        ui.onNodeWithTag("home-banner-carousel").assertDoesNotExist()
    }
}
