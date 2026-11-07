package com.gamixtv.MotionMagicAI.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.data.ThemeMode
import com.gamixtv.MotionMagicAI.data.HomeBanner
import androidx.compose.ui.platform.LocalContext

private data class CatalogTool(val title: String, val subtitle: String, val icon: ImageVector, val destination: Screen, val artwork: Artwork)
private val mainCatalogTools = listOf(
    CatalogTool("Create Image", "Imagine it. Create it.", Icons.Rounded.Image, Screen.CREATE_IMAGE, Artwork.Beach),
    CatalogTool("Image to Video", "Animate your photo", Icons.Rounded.SmartDisplay, Screen.IMAGE, Artwork.Cinematic),
    CatalogTool("Text to Video", "Your words, in motion", Icons.Rounded.Title, Screen.TEXT, Artwork.Anime),
    CatalogTool("Continue Story", "Make the next scene", Icons.Rounded.VideoLibrary, Screen.STORY, Artwork.Love),
)

@Composable
fun ReferenceCatalogScreen(vm: MotionViewModel, templatePage: Boolean) {
    ObserveTemplateCatalogue(vm)
    var preview by remember { mutableStateOf<Template?>(null) }
    if(!templatePage) ReviewPromptHost(vm, enabled = preview == null)
    LaunchedEffect(vm.showcaseTemplates) {
        preview?.remoteId?.let { id -> preview = vm.showcaseTemplates.firstOrNull { it.remoteId == id } }
    }
    ReferenceCatalogContent(vm.walletCredits, vm.settings.themeMode, vm.showcaseTemplates,
        templatePage, vm::toggleTheme, { destination -> if(destination == Screen.TEMPLATES) vm.tab(destination) else vm.go(destination) }, { preview = it },
        banners = vm.homeBanners, bannersActive = preview == null)
    preview?.let { item -> TemplatePreviewSheet(item, item.mode, { preview = null }) {
        vm.useTemplate(item.art, item.name, item.promptFor(item.mode), item.mode, item.aspect, item.duration,
            item.modelId, item.resolution, item.generatedAudio, item.remoteId, item.requiresReference)
        preview = null
    } }
}

@Composable
internal fun ReferenceCatalogContent(credits: Long?, theme: ThemeMode, entries: List<Template>, templatePage: Boolean,
    toggleTheme: () -> Unit, navigate: (Screen) -> Unit, preview: (Template) -> Unit,
    banners: List<HomeBanner>? = null, bannersActive: Boolean = true) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val colors = StudioColors
    val context = LocalContext.current
    val bannerRows = banners ?: remember { HomeBannerCatalogue.bundled(context) }
    val listState = rememberLazyListState()
    val bannerVisible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "home-banners" } } }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        CatalogHeader(credits, theme, toggleTheme, { navigate(Screen.PLANS) }) { searching = !searching }
        if(searching) OutlinedTextField(query, { query = it }, label = { Text("Search templates") }, singleLine = true,
            trailingIcon = { IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Rounded.Close, "Close search") } },
            shape = PanelShape, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if(entries.isEmpty()) item {
                Text("Templates are currently unavailable. Try again shortly.", color = Muted, modifier = Modifier.padding(16.dp))
            }
            if(!templatePage && query.isBlank()) {
                item(key = "home-banners") { HomeBannerCarousel(bannerRows, bannersActive && bannerVisible && !searching, navigate) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        mainCatalogTools.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { tool -> CatalogCreationCard(tool, Modifier.weight(1f)) { navigate(tool.destination) } }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        RoundCatalogTool("Voice", Icons.Rounded.Mic) { navigate(Screen.VOICE) }
                        RoundCatalogTool("Templates", Icons.Rounded.Layers) { navigate(Screen.TEMPLATES) }
                        RoundCatalogTool("Projects", Icons.Rounded.AutoAwesome) { navigate(Screen.PROJECTS) }
                        RoundCatalogTool("More", Icons.Rounded.GridView) { expanded = !expanded }
                    }
                }
                if(expanded) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { navigate(Screen.SETTINGS) }, Modifier.weight(1f)) { Text("Settings") }
                        OutlinedButton(onClick = { navigate(Screen.PLANS) }, Modifier.weight(1f)) { Text("Add credits") }
                    }
                }
            }
            val groups = if(query.isNotBlank()) listOf("Search results" to entries.filter { it.name.contains(query, true) || it.category.contains(query, true) })
                else if(templatePage) entries.groupBy { it.category }.toList()
                else listOf("Trending" to entries) + entries.groupBy { it.category }.filterKeys { it != "Reels" }.toList()
            groups.forEach { (title, options) ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
                            if(!templatePage) TextButton(onClick = { navigate(Screen.TEMPLATES) }) { Text("View All", color = colors.primary, fontSize = 12.sp) }
                        }
                        if(options.isEmpty()) Text("No templates match your search.", color = Muted, modifier = Modifier.padding(16.dp))
                        else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(options, key = { it.remoteId ?: it.name }) { item -> TemplatePosterCard(item, Modifier.width(100.dp).height(134.dp)) { preview(item) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CatalogHeader(credits: Long?, theme: ThemeMode, toggleTheme: () -> Unit, addCredits: () -> Unit, search: () -> Unit) {
    val colors = StudioColors
    val wide = LocalConfiguration.current.screenWidthDp >= 412 && LocalConfiguration.current.fontScale <= 1.1f
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = search, Modifier.size(44.dp)) { Icon(Icons.Rounded.Search, "Search templates", Modifier.size(25.dp)) }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MagicLogo(Modifier.size(24.dp))
            Text("MotionMagic AI", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(onClick = addCredits, color = colors.field, shape = CircleShape, border = BorderStroke(1.dp, colors.border), modifier = Modifier.testTag("credit-balance")) {
            Row(Modifier.heightIn(min = 44.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = Color(0xFFFFBD32), modifier = Modifier.size(18.dp))
                Text((credits?.toString() ?: "…") + if(wide) " Credits" else "", color = colors.text, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 5.dp), maxLines = 1)
                Box(Modifier.size(28.dp).background(MagicGradient, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, "Add credits", Modifier.size(21.dp), tint = Color.White)
                }
            }
        }
        IconButton(onClick = toggleTheme, modifier = Modifier.size(44.dp).testTag("theme-toggle")) {
            Icon(if(theme == ThemeMode.DARK) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                if(theme == ThemeMode.DARK) "Switch to light mode" else "Switch to dark mode", Modifier.size(22.dp), tint = colors.primary)
        }
    }
}


@Composable
private fun RoundCatalogTool(title: String, icon: ImageVector, action: () -> Unit) {
    val colors = StudioColors
    Column(Modifier.width(66.dp).clickable(onClick = action), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(color = colors.surface, shape = CircleShape, border = BorderStroke(1.dp, colors.border), shadowElevation = if(colors.dark) 0.dp else 2.dp, modifier = Modifier.size(46.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(23.dp), tint = if(title == "Projects") colors.primary else colors.text) }
        }
        Text(title, color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CatalogCreationCard(tool: CatalogTool, modifier: Modifier, action: () -> Unit) {
    val colors = StudioColors
    val selected = tool.destination == Screen.IMAGE
    Surface(onClick = action, color = colors.surface, shape = RoundedCornerShape(18.dp), shadowElevation = if(colors.dark) 0.dp else 2.dp,
        border = BorderStroke(1.dp, if(selected) colors.primary else colors.border), modifier = modifier.heightIn(min = 104.dp)) {
        Box(Modifier.heightIn(min = 104.dp)) {
            Art(tool.artwork, Modifier.matchParentSize(), null)
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(colors.surface.copy(alpha = .98f), colors.surface.copy(alpha = .82f), colors.surface.copy(alpha = .12f)))))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(tool.icon, null, Modifier.size(23.dp), tint = if(selected) colors.primary else colors.text)
                Spacer(Modifier.height(3.dp))
                Text(tool.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, letterSpacing = (-.4).sp)
                Text(tool.subtitle, fontSize = 10.sp, lineHeight = 14.sp, color = colors.text)
            }
        }
    }
}

@Composable
fun TemplatePosterCard(template: Template, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(13.dp)).clickable(onClick = action)) {
        ShowcasePoster(template, Modifier.fillMaxSize(), "${template.name} template")
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f)))))
        Text(template.name, color = Color.White, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 10.dp))
    }
}
