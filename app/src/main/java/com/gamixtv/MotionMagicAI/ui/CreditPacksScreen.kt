package com.gamixtv.MotionMagicAI.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val balance: Long?, val products: List<Pair<String, Long>>, val rates: List<CloudRate>,
    val ready: Boolean = false, val loading: Boolean = false, val busy: Boolean = false,
    val openingSku: String? = null, val error: String? = null,
    val catalogStatus: String = "Loading available models\u2026",
    val prices: Map<String, String> = emptyMap(),
)

@Composable
fun PlansScreen(vm: MotionViewModel) {
    val activity = LocalActivity.current
    LaunchedEffect(vm.activeAccountId) { vm.refreshCloudCatalog(); vm.refreshBilling() }
    CreditPacksContent(
        CreditPacksState(vm.walletCredits, vm.creditProducts, vm.cloudRates, vm.billingReady, vm.billingLoading,
            vm.billingBusy, vm.buyingProductId, vm.billingError, vm.cloudCatalogStatus, vm.creditProductPrices),
        onBuy = { sku -> if(activity == null) vm.message("Open the Android app to buy credits.") else vm.buy(activity, sku) },
        onRetry = { vm.refreshBilling(force = true) }, onBack = vm::back, onAccount = { vm.tab(Screen.PROFILE) },
    )
}

private data class PackColors(val start: Color, val end: Color, val accent: Color, val buttonStart: Color, val buttonEnd: Color)
private fun packColors(sku: String, dark: Boolean): PackColors {
    val vivid = when(sku) {
        PlayBilling.PACK_2500 -> PackColors(Color(0xFF6514AB), Color(0xFF1A104C), Color(0xFFED69FF), Color(0xFFD42AFF), Color(0xFF6809F4))
        PlayBilling.PACK_5000 -> PackColors(Color(0xFF14366C), Color(0xFF10173D), Color(0xFF40D6FF), Color(0xFF08C4E9), Color(0xFF1551FF))
        PlayBilling.PACK_10000 -> PackColors(Color(0xFF06564F), Color(0xFF062734), Color(0xFF42F3CA), Color(0xFF00D994), Color(0xFF008FAD))
        else -> PackColors(Color(0xFF763E0D), Color(0xFF35102E), Color(0xFFFFCF43), Color(0xFFFFCB21), Color(0xFFF12B86))
    }
    return if(dark) vivid else vivid.copy(start = lerp(Color.White, vivid.accent, .12f), end = lerp(Color.White, vivid.accent, .025f))
}

@Composable
internal fun CreditPacksContent(state: CreditPacksState, onBuy: (String) -> Unit, onRetry: () -> Unit,
    onBack: () -> Unit, onAccount: () -> Unit = {}) {
    val colors = StudioColors
    val config = LocalConfiguration.current
    val columns = if(config.screenWidthDp < 360 || config.fontScale > 1.2f) 1 else 2
    val products = remember(state.products) { state.products.filter { it.first in PlayBilling.creditPacks }.sortedBy { it.second } }
    val rows = remember(products, columns) { products.chunked(columns) }
    val models = remember(state.rates) { state.rates.groupBy { it.modelId }.values.toList() }
    var showCosts by rememberSaveable { mutableStateOf(false) }
    val page = if(colors.dark) Color(0xFF03031B) else Color(0xFFF6F2FF)
    LazyColumn(Modifier.fillMaxSize().background(page).drawBehind {
        if(colors.dark) drawRect(Brush.radialGradient(listOf(Color(0xFF34105C).copy(alpha = .3f), Color.Transparent),
            center = Offset(size.width, size.height*.45f), radius = size.width))
    }.testTag("credit-packs-page"),
        contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { CreditShopHeader(onBack, onAccount) }
        item { CreditStudioHero() }
        item { CreditBalanceBanner(state.balance, Modifier.padding(horizontal = 14.dp)) }
        item {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Choose a Credit Pack", color = colors.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
                Text("More credits. More possibilities.", color = colors.muted, fontSize = 13.sp)
            }
        }
        items(rows, key = { it.first().first }) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (sku, amount) ->
                    CreditPackCard(sku, amount, state.prices[sku], state.ready && !state.busy, state.openingSku == sku,
                        Modifier.weight(1f).fillMaxHeight()) { onBuy(sku) }
                }
                if(row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
        if(state.loading && products.isEmpty()) item {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
                Text("Loading credit packs\u2026", color = colors.muted, fontSize = 12.sp)
            }
        }
        state.error?.let { error -> item {
            Surface(Modifier.padding(horizontal = 16.dp), color = colors.surface, shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, colors.error.copy(alpha = .4f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, color = colors.text, fontSize = 13.sp)
                    OutlinedButton(onRetry, enabled = !state.loading, modifier = Modifier.testTag("credit-packs-retry")) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Try again")
                    }
                }
            }
        } }
        if(state.ready && products.isEmpty() && !state.loading) item {
            Text("Credit packs are currently unavailable.", Modifier.padding(18.dp), color = colors.muted, fontSize = 13.sp)
        }
        item { CreditTrustStrip() }
        item { CreditShopFooter() }
        item {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One-time purchases. Credits never expire. Google Play confirms the final price before payment.",
                    color = colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                TextButton(onClick = { showCosts = !showCosts }, modifier = Modifier.testTag("credit-cost-toggle")) {
                    Text(if(showCosts) "Hide model credit costs" else "View model credit costs", color = colors.primary)
                    Icon(if(showCosts) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
        }
        if(showCosts) {
            item { Text("Available AI models", Modifier.padding(horizontal = 18.dp), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            if(models.isEmpty()) item { Text(state.catalogStatus, Modifier.padding(horizontal = 18.dp), color = colors.muted, fontSize = 12.sp) }
            items(models, key = { it.first().modelId }) { variants -> Box(Modifier.padding(horizontal = 16.dp)) { ModelCreditCard(variants) } }
            item { Text("Model, resolution, duration and sound determine the final quote. Review it before Create.", Modifier.padding(horizontal = 18.dp), color = colors.muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun CreditShopHeader(back: () -> Unit, account: () -> Unit) {
    val colors = StudioColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconButton(back, Modifier.size(44.dp).background(colors.primary.copy(alpha = .09f), CircleShape)) {
            Icon(Icons.Rounded.ArrowBack, "Back to studio", tint = colors.text, modifier = Modifier.size(22.dp))
        }
        MagicLogo(Modifier.size(30.dp))
        Text("MotionMagic AI", Modifier.weight(1f), color = colors.text, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, letterSpacing = (-.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(onClick = account, shape = CircleShape, color = colors.primary.copy(alpha = .12f),
            border = BorderStroke(1.dp, colors.primary.copy(alpha = .45f)), modifier = Modifier.testTag("credit-shop-account")) {
            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Rounded.AccountCircle, null, Modifier.size(18.dp), tint = colors.primary)
                Text("My Account", color = colors.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CreditStudioHero() {
    val dark = StudioColors.dark
    val config = LocalConfiguration.current
    val tall = config.fontScale > 1.2f
    Box(Modifier.fillMaxWidth().heightIn(min = if(tall) 280.dp else 210.dp).testTag("credit-shop-hero")) {
        StudioArtwork("credits_hero.webp", Modifier.matchParentSize(), ContentScale.Crop)
        if(!dark) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xFFF6F2FF), Color(0xFFF6F2FF).copy(alpha = .8f), Color.Transparent))))
        Column(Modifier.fillMaxWidth(if(tall) .57f else .49f).padding(start = 18.dp, top = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Fuel Your", color = if(dark) Color.White else Color(0xFF171027), fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text("Creativity", style = TextStyle(brush = Brush.horizontalGradient(listOf(Color(0xFFD933FF), Color(0xFF5EC7FF))),
                fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, letterSpacing = (-1.1).sp))
            Text("Get credits to create stunning AI images and videos.", color = if(dark) Color(0xFFE6E3F5) else Color(0xFF4B3A65),
                fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(9.dp))
            Text("Dream \u00b7 Create \u00b7 Explore", Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF861DC6).copy(alpha = .7f), Color.Transparent)), RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                color = if(dark) Color.White else Color(0xFF4A0D77), fontFamily = FontFamily.Cursive, fontStyle = FontStyle.Italic, fontSize = 15.sp)
        }
    }
}

@Composable
private fun CreditBalanceBanner(balance: Long?, modifier: Modifier = Modifier) {
    val count = balance?.let { "%,d".format(it) } ?: "\u2026"
    Surface(modifier.shadow(14.dp, RoundedCornerShape(20.dp), ambientColor = Color(0xFF902FFF), spotColor = Color(0xFF902FFF)),
        color = Color.Transparent, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFFB179FF).copy(alpha = .65f))) {
        Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF5420A1), Color(0xFF26247D))))
            .padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(45.dp).background(Brush.radialGradient(listOf(Color(0xFFB069F6), Color(0xFF5315AC))), CircleShape), contentAlignment = Alignment.Center) {
                Crown(Modifier.size(28.dp))
            }
            Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Your Balance", color = Color(0xFFE0CFF8), fontSize = 11.sp)
                Text(count, color = Color.White, fontSize = if(count.length > 8) 20.sp else 29.sp, lineHeight = 33.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("pricing-credit-balance"))
                Text("Credits available", color = Color(0xFFE0CFF8), fontSize = 9.sp)
            }
            Box(Modifier.width(1.dp).height(42.dp).background(Color.White.copy(alpha = .2f)))
            Icon(Icons.Rounded.CardGiftcard, null, Modifier.size(25.dp), tint = Color(0xFFED4AFF))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("200 Welcome Credits", color = Color.White, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
                Text("on your first sign-in", color = Color(0xFFDDD3FC), fontSize = 9.sp, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun Crown(modifier: Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width*.15f,size.height*.75f); lineTo(size.width*.05f,size.height*.3f)
            lineTo(size.width*.32f,size.height*.45f); lineTo(size.width*.5f,size.height*.1f)
            lineTo(size.width*.68f,size.height*.45f); lineTo(size.width*.95f,size.height*.3f)
            lineTo(size.width*.85f,size.height*.75f); close()
        }
        drawPath(path, Brush.verticalGradient(listOf(Color.White,Color(0xFFE0B2FF))))
        drawLine(Color.White, Offset(size.width*.2f,size.height*.88f), Offset(size.width*.8f,size.height*.88f), strokeWidth=size.height*.08f, cap=StrokeCap.Round)
    }
}

@Composable
private fun CreditPackCard(sku: String, amount: Long, price: String?, enabled: Boolean, opening: Boolean, modifier: Modifier, buy: () -> Unit) {
    val dark = StudioColors.dark
    val palette = packColors(sku, dark)
    val popular = sku == PlayBilling.PACK_10000
    val count = "%,d".format(amount)
    val text = if(dark) Color.White else Color(0xFF211634)
    val muted = if(dark) Color(0xFFDED6EE) else Color(0xFF594967)
    val title = when(sku) { PlayBilling.PACK_2500 -> "Creator Pack"; PlayBilling.PACK_5000 -> "Pro Pack"; PlayBilling.PACK_10000 -> "Studio Pack"; else -> "Ultimate Pack" }
    Box(modifier.padding(top = 5.dp).testTag("credit-pack-$sku")) {
        Surface(Modifier.fillMaxSize().padding(top = 5.dp).shadow(if(popular) 12.dp else 5.dp, RoundedCornerShape(20.dp), ambientColor = palette.accent, spotColor = palette.accent),
            color = Color.Transparent, shape = RoundedCornerShape(20.dp), border = BorderStroke(if(popular) 1.5.dp else 1.dp, palette.accent.copy(alpha = .7f))) {
            Column(Modifier.background(Brush.linearGradient(listOf(palette.start, palette.end))).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.weight(.43f).height(78.dp).drawBehind {
                        drawCircle(Brush.radialGradient(listOf(palette.accent.copy(alpha=.3f), Color.Transparent)), radius = size.minDimension*.75f)
                    }) {
                        StudioArtwork("credit_coins.webp", Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(palette.accent, BlendMode.Modulate))
                    }
                    Column(Modifier.weight(.57f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, color = text, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
                        BasicText(count, style = MaterialTheme.typography.headlineSmall.copy(color = text, lineHeight = 29.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp), maxLines = 1, softWrap = false,
                            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 24.sp, stepSize = .5.sp))
                        Text("Credits", color = text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(10.dp), tint = if(dark) palette.accent else palette.buttonEnd)
                            Text("Never expires", color = muted, fontSize = 8.sp, lineHeight = 12.sp)
                        }
                    }
                }
                val button = Brush.horizontalGradient(listOf(palette.buttonStart, palette.buttonEnd))
                Button(buy, enabled = enabled, shape = CircleShape, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                        contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .7f)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(button, CircleShape)
                        .testTag("buy-$sku").semantics { contentDescription = "Buy $count credits" }) {
                    if(opening) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.Rounded.ShoppingCart, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if(opening) "Opening Play\u2026" else price ?: "Buy credits", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if(popular) Surface(Modifier.align(Alignment.TopStart).padding(start = 10.dp).testTag("popular-$sku"), color = Color(0xFFF1C7FF), shape = CircleShape,
            border = BorderStroke(1.dp, Color.White.copy(alpha = .8f)), shadowElevation = 5.dp) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Star, null, Modifier.size(13.dp), tint = Color(0xFF401455))
                Text("Most popular", color = Color(0xFF321042), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CreditTrustStrip() {
    val dark = StudioColors.dark
    Surface(Modifier.padding(horizontal = 14.dp), color = if(dark) Color(0xFF15102D) else Color.White,
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, StudioColors.primary.copy(alpha=.2f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Triple(Icons.Rounded.Bolt, "Instant\naccess", Color(0xFFFFD03C)),
                Triple(Icons.Rounded.VerifiedUser, "Verified\npurchases", Color(0xFFDA65FF)),
                Triple(Icons.Rounded.PlayArrow, "Payments via\nGoogle Play", Color(0xFF41D7B1)),
                Triple(Icons.Rounded.AllInclusive, "Never\nexpires", Color(0xFFC75CFF))).forEachIndexed { index, (icon, label, accent) ->
                if(index > 0) Box(Modifier.width(1.dp).height(26.dp).background(StudioColors.primary.copy(alpha=.23f)))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(icon, null, Modifier.size(21.dp), tint = accent)
                    Text(label, color = StudioColors.text, fontSize = 8.sp, lineHeight = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun CreditShopFooter() {
    val colors = StudioColors
    Box(Modifier.fillMaxWidth().height(85.dp).drawBehind {
        for(side in 0..1) for(i in 0..2) {
            val path=Path().apply {
                moveTo(if(side==0) 0f else size.width, size.height*(.12f+i*.12f))
                cubicTo(size.width*(if(side==0) .22f else .78f), size.height*.4f,
                    size.width*(if(side==0) .1f else .9f), size.height*.95f, size.width*.5f, size.height*1.1f)
            }
            drawPath(path, if(side==0) Color(0xFF942DFF).copy(alpha=.45f-i*.1f) else Color(0xFF2765FF).copy(alpha=.45f-i*.1f),
                style=androidx.compose.ui.graphics.drawscope.Stroke(width=6.dp.toPx()-i*1.dp.toPx()))
        }
    }, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MagicLogo(Modifier.size(39.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MotionMagic AI", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Create more. Imagine more.", color = colors.muted, fontSize = 10.sp)
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
    val accent = if(colors.dark) palette.accent else colors.primary
    Surface(color = palette.start, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, palette.accent.copy(alpha = .3f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = palette.accent.copy(alpha = .12f), shape = RoundedCornerShape(11.dp)) {
                    Icon(if(image) Icons.Rounded.Image else Icons.Rounded.SmartDisplay, null, Modifier.padding(9.dp).size(19.dp), tint = accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title ?: cheapest.title,
                        color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(variants.map { it.size.uppercase() }.distinct().joinToString(" · "), color = colors.muted, fontSize = 11.sp)
                }
            }
            Text("From ${credits(cheapest.minimumPricePaise)} ${if(image) "per image" else "per ${cheapest.minimumUnits}s video"}",
                color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
