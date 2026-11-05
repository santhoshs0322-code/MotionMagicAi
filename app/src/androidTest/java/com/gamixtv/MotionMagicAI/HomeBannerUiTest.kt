package com.gamixtv.MotionMagicAI

import androidx.compose.runtime.*
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

@RunWith(AndroidJUnit4::class)
class HomeBannerUiTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val banners get() = HomeBannerCatalogue.bundled(context)

    private fun waitForPosition(value: String) {
        try {
            ui.waitUntil(6500) {
                ui.onNodeWithTag("home-banner-position").fetchSemanticsNode().config[SemanticsProperties.Text].first().text == value
            }
        } catch(error: ComposeTimeoutException) {
            throw AssertionError("Expected $value; " + ui.onNodeWithTag("home-banner-position").printToString() +
                "\n" + ui.onNodeWithTag("home-banner-carousel").printToString(), error)
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
        ui.onNodeWithTag("home-banner-position").assertTextEquals("1 / 5")
        listOf("2 / 5", "3 / 5", "4 / 5", "5 / 5", "1 / 5", "2 / 5").forEach(::waitForPosition)
    }

    @Test fun manualSwipeAndDotsWorkWhilePausedAndBannerTapUsesItsDestination() {
        var destination: Screen? = null
        ui.setContent { MotionTheme(ThemeMode.LIGHT) { HomeBannerCarousel(banners, active = false, navigate = { destination = it }) } }
        ui.onNodeWithTag("home-banner-position").assertTextEquals("1 / 5")
        ui.onNodeWithTag("home-banner-page-1").performTouchInput { swipeLeft(durationMillis = 350) }
        waitForPosition("2 / 5")
        ui.onNodeWithTag("home-banner-page-2").performClick()
        assertEquals(Screen.TEXT, destination)
        ui.onNodeWithContentDescription("Show banner 5").performClick()
        waitForPosition("5 / 5")
        ui.onNodeWithTag("home-banner-page-5").performTouchInput { swipeLeft(durationMillis = 350) }
        waitForPosition("1 / 5")
    }

    @Test fun singleBannerAndUnpublishedFeedDoNotShowPhantomSlides() {
        var rows by mutableStateOf(banners.take(1))
        ui.setContent { MotionTheme(ThemeMode.DARK) { HomeBannerCarousel(rows, navigate = {}) } }
        ui.onNodeWithTag("home-banner-position").assertTextEquals("1 / 1")
        ui.onNodeWithContentDescription("Show banner 2").assertDoesNotExist()
        ui.runOnIdle { rows = HomeBannerCatalogue.parse(JSONArray()) }
        ui.onNodeWithTag("home-banner-carousel").assertDoesNotExist()
    }
}
