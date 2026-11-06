package com.gamixtv.MotionMagicAI

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamixtv.MotionMagicAI.data.HomeBanner
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.ui.BannerImageLoader
import com.gamixtv.MotionMagicAI.ui.HomeBannerCarousel
import com.gamixtv.MotionMagicAI.ui.MotionTheme
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeBannerLoadingUiTest {
    @get:Rule val ui = createComposeRule()
    private fun bitmap(color: Int) = Bitmap.createBitmap(96, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun waitForTitle(title: String) = ui.waitUntil(6_000) {
        ui.onAllNodesWithContentDescription(title).fetchSemanticsNodes().isNotEmpty()
    }
    private fun assertColor(color: Int) {
        val captured = ui.onNodeWithTag("home-banner-carousel").captureToImage().asAndroidBitmap()
        try { assertEquals(color, captured.getPixel(captured.width / 2, captured.height / 2)) }
        finally { captured.recycle() }
    }

    @Test fun firstLoadDoesNotShowStockArtworkAndReplacementKeepsTheLoadedImage() {
        val first = HomeBanner("one", "First upload", "templates", revision = "1")
        val replacement = first.copy(title = "Replacement", revision = "2")
        val pending = replacement.copy(title = "Pending upload", revision = "3")
        var rows by mutableStateOf(listOf(first))
        val loader = ControlledLoader()
        ui.setContent { MotionTheme(ThemeMode.DARK) { HomeBannerCarousel(rows, navigate = {}, imageLoader = loader) } }
        ui.onNodeWithTag("home-banner-carousel").assertDoesNotExist()
        loader.provide(first, bitmap(Color.BLUE))
        waitForTitle(first.title)
        assertColor(Color.BLUE)
        ui.runOnIdle { rows = listOf(replacement) }
        ui.onNodeWithContentDescription(first.title).assertIsDisplayed()
        assertColor(Color.BLUE)
        loader.provide(replacement, bitmap(Color.GREEN))
        waitForTitle(replacement.title)
        assertColor(Color.GREEN)
        ui.runOnIdle { rows = listOf(pending) }
        ui.onNodeWithContentDescription(replacement.title).assertIsDisplayed()
        ui.runOnIdle { rows = emptyList() }
        loader.provide(pending, bitmap(Color.RED))
        ui.onNodeWithTag("home-banner-carousel").assertDoesNotExist()
    }

    @Test fun automaticRotationWaitsForTheNextImageInsteadOfShowingABlankSlide() {
        val first = HomeBanner("one", "First", "templates", displaySeconds = 3, revision = "1")
        val next = HomeBanner("two", "Next", "templates", displaySeconds = 3, revision = "1")
        val loader = ControlledLoader().apply { provide(first, bitmap(Color.BLUE)) }
        ui.setContent { MotionTheme(ThemeMode.LIGHT) { HomeBannerCarousel(listOf(first, next), navigate = {}, imageLoader = loader) } }
        waitForTitle(first.title)
        ui.mainClock.advanceTimeBy(3_400)
        ui.onNodeWithTag("home-banner-carousel").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Banner 1 of 2"))
        assertColor(Color.BLUE)
        loader.provide(next, bitmap(Color.GREEN))
        ui.waitUntil(6_000) {
            ui.onNodeWithTag("home-banner-carousel").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "Banner 2 of 2"
        }
        ui.onNodeWithContentDescription(next.title).assertIsDisplayed()
        assertColor(Color.GREEN)
    }

    private class ControlledLoader : BannerImageLoader {
        private val images = ConcurrentHashMap<String, Bitmap>()
        private val waiting = ConcurrentHashMap<String, CompletableDeferred<Bitmap>>()
        private fun key(banner: HomeBanner) = "${banner.id}:${banner.revision}"
        fun provide(banner: HomeBanner, bitmap: Bitmap) {
            images[key(banner)] = bitmap
            waiting.computeIfAbsent(key(banner)) { CompletableDeferred() }.complete(bitmap)
        }
        override fun peek(banner: HomeBanner) = images[key(banner)]
        override suspend fun load(banner: HomeBanner): Bitmap = images[key(banner)] ?:
            waiting.computeIfAbsent(key(banner)) { CompletableDeferred() }.await()
    }
}
