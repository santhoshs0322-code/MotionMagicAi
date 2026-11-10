package com.gamixtv.MotionMagicAI.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.billing.PlayBilling
import com.gamixtv.MotionMagicAI.cloud.CloudRate
import com.gamixtv.MotionMagicAI.cloud.credits
import com.gamixtv.MotionMagicAI.data.ImageModelCatalog
import com.gamixtv.MotionMagicAI.data.VideoModelCatalog

internal data class CreditPacksState(
    val balance: Long?,
    val products: List<Pair<String, Long>>,
    val rates: List<CloudRate>,
    val ready: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val openingSku: String? = null,
    val error: String? = null,
    val catalogStatus: String = "Loading available models…",
)

@Composable
fun PlansScreen(vm: MotionViewModel) {
    val activity = LocalActivity.current
    LaunchedEffect(vm.activeAccountId) { vm.refreshCloudCatalog(); vm.refreshBilling() }
    CreditPacksContent(
        CreditPacksState(vm.walletCredits, vm.creditProducts, vm.cloudRates, vm.billingReady, vm.billingLoading,
            vm.billingBusy, vm.buyingProductId, vm.billingError, vm.cloudCatalogStatus),
        onBuy = { sku -> if(activity == null) vm.message("Open the Android app to buy credits.") else vm.buy(activity, sku) },
        onRetry = { vm.refreshBilling(force = true) },
        onBack = { vm.tab(Screen.HOME) },
    )
}

private data class PackColors(val start: Color, val end: Color, val accent: Color, val buttonStart: Color, val buttonEnd: Color)

private fun packColors(sku: String, dark: Boolean): PackColors = when(sku) {
    PlayBilling.PACK_2500 -> PackColors(if(dark) Color(0xFF1C304D) else Color(0xFFF0F7FF), if(dark) Color(0xFF20253B) else Color(0xFFE3EDFF),
        if(dark) Color(0xFF91C7FF) else Color(0xFF2254A8), Color(0xFF2360CB), Color(0xFF3343B4))
    PlayBilling.PACK_5000 -> PackColors(if(dark) Color(0xFF173B36) else Color(0xFFEDFFF8), if(dark) Color(0xFF1D2B38) else Color(0xFFDDF5EF),
        if(dark) Color(0xFF89E6D0) else Color(0xFF006B60), Color(0xFF00756F), Color(0xFF17509B))
    PlayBilling.PACK_10000 -> PackColors(if(dark) Color(0xFF402E21) else Color(0xFFFFF9EC), if(dark) Color(0xFF30243A) else Color(0xFFFFECD6),
        if(dark) Color(0xFFFFD78A) else Color(0xFF8D5000), Color(0xFF9C5300), Color(0xFF9E3B59))
    else -> PackColors(if(dark) Color(0xFF3D2557) else Color(0xFFFCF5FF), if(dark) Color(0xFF28254C) else Color(0xFFEDE1FF),
        if(dark) Color(0xFFD9ADFF) else Color(0xFF7E22CE), Color(0xFF8B2BCD), Color(0xFF5135C7))
}

@Composable
internal fun CreditPacksContent(state: CreditPacksState, onBuy: (String) -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    val colors = StudioColors
    val configuration = LocalConfiguration.current
    val columns = if(configuration.screenWidthDp < 360 || configuration.fontScale > 1.2f) 1 else 2
    val products = remember(state.products) { state.products.filter { it.first in PlayBilling.creditPacks } }
    val rows = remember(products, columns) { products.chunked(columns) }
    val models = remember(state.rates) { state.rates.groupBy { it.modelId }.values.toList() }
    val background = Brush.verticalGradient(listOf(if(colors.dark) Color(0xFF251A38) else Color(0xFFF0E4FF), colors.background, colors.background))
    LazyColumn(Modifier.fillMaxSize().background(background).testTag("credit-packs-page"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(17.dp), tint = colors.primary)
                    Text("MOTIONMAGIC STUDIO", color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                }
                Text("Fuel your next idea", fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, color = colors.text)
                Text("One-time credit packs. More room to create.", color = colors.muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        item { CreditBalanceBanner(state.balance) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Choose your credits", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Purchased credits never expire. Google Play confirms the price before you pay.", color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        items(rows, key = { row -> row.first().first }) { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (sku, amount) ->
                    CreditPackCard(sku, amount, state.ready && !state.busy, state.openingSku == sku,
                        Modifier.weight(1f).fillMaxHeight()) { onBuy(sku) }
                }
                if(row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
        if(state.loading && products.isEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
                    Text("Loading credit packs…", color = colors.muted, fontSize = 12.sp)
                }
                repeat(2) { index ->
                    val palette = packColors(if(index == 0) PlayBilling.PACK_20000 else PlayBilling.PACK_2500, colors.dark)
                    Surface(color = palette.start, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, palette.accent.copy(alpha = .25f))) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.width(110.dp).height(16.dp).background(palette.accent.copy(alpha = .2f), RoundedCornerShape(5.dp)))
                            Box(Modifier.fillMaxWidth(.7f).height(12.dp).background(palette.accent.copy(alpha = .12f), RoundedCornerShape(5.dp)))
                        }
                    }
                }
            }
        }
        state.error?.let { error -> item {
            Surface(color = colors.surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, colors.error.copy(alpha = .4f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(error, color = colors.text, fontSize = 13.sp, lineHeight = 20.sp)
                    OutlinedButton(onClick = onRetry, enabled = !state.loading, modifier = Modifier.testTag("credit-packs-retry")) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Try again")
                    }
                }
            }
        } }
        if(state.ready && products.isEmpty() && !state.loading) item {
            Text("Credit packs are currently unavailable.", color = colors.muted, fontSize = 13.sp)
        }
        if(products.isNotEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(17.dp), tint = colors.primary)
                Text("Payments processed by Google Play", color = colors.muted, fontSize = 11.sp)
            }
        }
        item {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Available AI models", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Compare starting credit costs", color = colors.muted, fontSize = 12.sp)
            }
        }
        if(models.isEmpty()) item { Text(state.catalogStatus, color = colors.muted, fontSize = 12.sp) }
        items(models, key = { variants -> variants.first().modelId }) { variants -> ModelCreditCard(variants) }
        item {
            Text("The final credit quote depends on the model, resolution, duration and sound. Review it before confirming Create.",
                color = colors.muted, fontSize = 12.sp, lineHeight = 19.sp)
        }
        item { GradientButton("Back to studio", onBack, Modifier.padding(bottom = 8.dp), icon = Icons.Rounded.Home) }
    }
}

@Composable
private fun CreditBalanceBanner(balance: Long?) {
    val count = balance?.let { "%,d".format(it) } ?: "…"
    Surface(color = Color.Transparent, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF722BC5), Color(0xFF4641B7), Color(0xFF215EA6))))
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Rounded.Bolt, null, Modifier.size(22.dp), tint = Color(0xFFFFE296))
                Text("YOUR BALANCE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            }
            Text(count, color = Color.White, fontSize = if(count.length > 15) 23.sp else 34.sp,
                fontWeight = FontWeight.Bold, lineHeight = if(count.length > 15) 31.sp else 42.sp,
                modifier = Modifier.fillMaxWidth().testTag("pricing-credit-balance"))
            Text("credits available", color = Color.White, fontSize = 13.sp)
            HorizontalDivider(color = Color.White.copy(alpha = .2f), modifier = Modifier.padding(vertical = 2.dp))
            Text("200 welcome credits on your first sign-in", color = Color.White, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun CreditPackCard(sku: String, amount: Long, enabled: Boolean, opening: Boolean, modifier: Modifier, buy: () -> Unit) {
    val colors = StudioColors
    val palette = packColors(sku, colors.dark)
    val popular = sku == PlayBilling.PACK_10000
    val count = "%,d".format(amount)
    Surface(modifier.testTag("credit-pack-$sku"), color = Color.Transparent, shape = RoundedCornerShape(22.dp),
        border = BorderStroke(if(popular) 2.dp else 1.dp, palette.accent.copy(alpha = if(popular) .8f else .35f))) {
        Column(Modifier.fillMaxHeight().background(Brush.linearGradient(listOf(palette.start, palette.end))).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(popular) Surface(color = Color(0xFFFFE3A0), shape = RoundedCornerShape(8.dp), modifier = Modifier.testTag("popular-$sku")) {
                Row(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(12.dp), tint = Color(0xFF613A00))
                    Text("Most popular", color = Color(0xFF513000), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
                }
            } else Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp), tint = palette.accent)
                Text("CREDIT PACK", color = palette.accent, fontSize = 9.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            }
            Text("Credit top-up", color = palette.accent, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(count, color = colors.text, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Text("AI credits", color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(13.dp), tint = palette.accent)
                Text("Never expires", color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.weight(1f))
            val buttonBrush = Brush.horizontalGradient(if(enabled) listOf(palette.buttonStart, palette.buttonEnd) else listOf(colors.field, colors.field))
            Button(onClick = buy, enabled = enabled, shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White,
                    disabledContainerColor = Color.Transparent, disabledContentColor = colors.muted),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(buttonBrush, RoundedCornerShape(13.dp))
                    .testTag("buy-$sku").semantics { contentDescription = "Buy $count credits" }) {
                if(opening) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = colors.muted)
                else Icon(Icons.Rounded.Add, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(if(opening) "Opening Play…" else "Buy credits", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ModelCreditCard(variants: List<CloudRate>) {
    val colors = StudioColors
    val cheapest = variants.minBy { it.minimumPricePaise }
    val image = cheapest.kind == "image"
    val title = if(image) ImageModelCatalog.models.firstOrNull { it.id == cheapest.modelId }?.title
        else VideoModelCatalog.models.firstOrNull { it.id == cheapest.modelId }?.title
    val palette = packColors(if(image) PlayBilling.PACK_20000 else PlayBilling.PACK_2500, colors.dark)
    Surface(color = palette.start, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, palette.accent.copy(alpha = .3f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = palette.accent.copy(alpha = .12f), shape = RoundedCornerShape(11.dp)) {
                    Icon(if(image) Icons.Rounded.Image else Icons.Rounded.SmartDisplay, null, Modifier.padding(9.dp).size(19.dp), tint = palette.accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title ?: cheapest.title,
                        color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(variants.map { it.size.uppercase() }.distinct().joinToString(" · "), color = colors.muted, fontSize = 11.sp)
                }
            }
            Text("From ${credits(cheapest.minimumPricePaise)} ${if(image) "per image" else "per ${cheapest.minimumUnits}s video"}",
                color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
