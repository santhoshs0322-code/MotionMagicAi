package com.gamixtv.MotionMagicAI

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamixtv.MotionMagicAI.billing.PlayBilling
import com.gamixtv.MotionMagicAI.cloud.CloudRate
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.ui.CreditPacksContent
import com.gamixtv.MotionMagicAI.ui.CreditPacksState
import com.gamixtv.MotionMagicAI.ui.MotionTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fixtures render the production UI without a session, provider request, or Play purchase. */
@RunWith(AndroidJUnit4::class)
class CreditPacksUiTest {
    @get:Rule val ui = createComposeRule()
    private val products = listOf(PlayBilling.PACK_2500 to 2500L, PlayBilling.PACK_5000 to 5000L,
        PlayBilling.PACK_10000 to 10000L, PlayBilling.PACK_20000 to 20000L)
    private val rates = listOf(
        CloudRate("qwen", "Qwen Image", "qwen-image", "image", "720p", listOf("1:1"), emptyList(), null, false, minimumPricePaise = 35),
        CloudRate("pixverse", "PixVerse V6", "pixverse-v6", "video", "720p", listOf("9:16"), listOf(5), false, false,
            minimumPricePaise = 250, minimumUnits = 5),
    )
    private val state = mutableStateOf(CreditPacksState(2050, products, rates, ready = true))
    private val theme = mutableStateOf(ThemeMode.DARK)
    private val fontScale = mutableFloatStateOf(1f)
    private var purchases = emptyList<String>()
    private var retries = 0

    private fun show() {
        ui.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { fontScale = this@CreditPacksUiTest.fontScale.floatValue }
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)) {
                MotionTheme(theme.value) {
                    Box(Modifier.fillMaxSize().testTag("pricing-capture")) {
                        CreditPacksContent(state.value, { sku ->
                            purchases = purchases + sku
                            state.value = state.value.copy(busy = true, openingSku = sku)
                        }, { retries++ }, {})
                    }
                }
            }
        }
    }

    private fun scrollTo(tag: String) {
        ui.onNodeWithTag("credit-packs-page").performScrollToNode(hasTestTag(tag))
    }

    private fun screenshot(name: String) {
        ui.mainClock.advanceTimeBy(250)
        ui.waitForIdle()
        val bitmap = ui.onNodeWithTag("pricing-capture").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        // Also inspect the actual display: render-node captures can omit cached layers after a theme switch.
        val window = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name.replace(".png", "-window.png")).outputStream().use {
            window.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        window.recycle()
    }

    @Test fun bothThemesShowPacksAndOpeningPlayDisablesDuplicatePurchases() {
        show()
        screenshot("pricing-hero-dark.png")
        ui.onNodeWithTag("pricing-credit-balance").assertTextEquals("2,050")
        scrollTo("popular-${PlayBilling.PACK_10000}")
        ui.onNodeWithTag("popular-${PlayBilling.PACK_10000}").assertIsDisplayed()
        ui.onAllNodesWithText("Most popular").assertCountEquals(1)
        ui.onNodeWithTag("credit-pack-${PlayBilling.TOPUP_500}").assertDoesNotExist()
        screenshot("pricing-dark.png")
        scrollTo("buy-${PlayBilling.PACK_20000}")
        ui.onNodeWithTag("buy-${PlayBilling.PACK_20000}").assertIsEnabled().performClick()
        assertEquals(listOf(PlayBilling.PACK_20000), purchases)
        ui.onNodeWithTag("buy-${PlayBilling.PACK_20000}").assertIsNotEnabled()
        ui.onNodeWithText("Opening Play…").assertIsDisplayed()
        scrollTo("buy-${PlayBilling.PACK_10000}")
        ui.onNodeWithTag("buy-${PlayBilling.PACK_10000}").assertIsNotEnabled()
        ui.runOnIdle { state.value = state.value.copy(busy = false, openingSku = null); theme.value = ThemeMode.LIGHT }
        ui.onNodeWithTag("credit-packs-page").performScrollToIndex(0)
        screenshot("pricing-light.png")
        ui.onNodeWithTag("credit-packs-page").performScrollToNode(hasText("Qwen Image"))
        ui.onNodeWithText("Qwen Image").assertIsDisplayed()
        ui.onNodeWithTag("credit-packs-page").performScrollToNode(hasText("From 35 credits per image"))
        ui.onNodeWithText("From 35 credits per image").assertIsDisplayed()
        screenshot("pricing-models-light.png")
    }

    @Test fun largeTextKeepsPacksReadableAndLoadingErrorsCanRetry() {
        theme.value = ThemeMode.LIGHT
        fontScale.floatValue = 1.5f
        state.value = state.value.copy(balance = 999999999L, products = emptyList(), ready = false, loading = true)
        show()
        ui.onNodeWithTag("pricing-credit-balance").assertTextEquals("999,999,999")
        ui.onNodeWithTag("credit-packs-page").performScrollToNode(hasText("Loading credit packs…"))
        ui.onNodeWithText("Loading credit packs…").assertIsDisplayed()
        ui.runOnIdle { state.value = state.value.copy(loading = false, error = "Could not load credit packs.") }
        scrollTo("credit-packs-retry")
        ui.onNodeWithTag("credit-packs-retry").assertIsEnabled().performClick()
        assertEquals(1, retries)
        ui.runOnIdle { state.value = state.value.copy(products = products, error = null) }
        scrollTo("buy-${PlayBilling.PACK_10000}")
        ui.onNodeWithTag("buy-${PlayBilling.PACK_10000}").assertIsNotEnabled()
        ui.runOnIdle { state.value = state.value.copy(ready = true) }
        ui.onNodeWithTag("buy-${PlayBilling.PACK_10000}").assertIsEnabled().assertIsDisplayed()
        val card = ui.onNodeWithTag("credit-pack-${PlayBilling.PACK_10000}").fetchSemanticsNode().boundsInRoot
        val page = ui.onNodeWithTag("credit-packs-page").fetchSemanticsNode().boundsInRoot
        assertTrue("Large text needs a full-width credit card", card.width > page.width * .8f)
        screenshot("pricing-large-text.png")
        ui.runOnIdle { state.value = state.value.copy(products = emptyList()) }
        ui.onNodeWithTag("credit-packs-page").performScrollToNode(hasText("Credit packs are currently unavailable."))
        ui.onNodeWithText("Credit packs are currently unavailable.").assertIsDisplayed()
    }
}
