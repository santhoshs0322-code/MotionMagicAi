package com.gamixtv.MotionMagicAI.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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

private data class CatalogTool(val title: String, val subtitle: String, val icon: ImageVector, val destination: Screen, val artwork: Artwork)
private val mainCatalogTools = listOf(
    CatalogTool("Create Image", "Imagine it. Create it.", Icons.Rounded.Image, Screen.CREATE_IMAGE, Artwork.Beach),
    CatalogTool("Image to Video", "Animate your photo", Icons.Rounded.SmartDisplay, Screen.IMAGE, Artwork.Cinematic),
    CatalogTool("Text to Video", "Your words, in motion", Icons.Rounded.Title, Screen.TEXT, Artwork.Anime),
    CatalogTool("Continue Story", "Make the next scene", Icons.Rounded.VideoLibrary, Screen.PROJECTS, Artwork.Love),
)

@Composable
fun ReferenceCatalogScreen(vm: MotionViewModel, templatePage: Boolean) {
    LaunchedEffect(Unit) { vm.refreshShowcase() }
    var preview by remember { mutableStateOf<Template?>(null) }
    ReferenceCatalogContent(vm.walletCredits, vm.settings.themeMode, vm.showcaseTemplates.ifEmpty { templates },
        templatePage, vm::toggleTheme, { destination -> if(destination == Screen.TEMPLATES) vm.tab(destination) else vm.go(destination) }, { preview = it })
    preview?.let { item -> TemplatePreviewSheet(item, item.mode, { preview = null }) {
        vm.useTemplate(item.art, item.name, item.promptFor(item.mode), item.mode, item.aspect, item.duration,
            item.modelId, item.resolution, item.generatedAudio)
        preview = null
    } }
}

@Composable
internal fun ReferenceCatalogContent(credits: Long?, theme: ThemeMode, entries: List<Template>, templatePage: Boolean,
    toggleTheme: () -> Unit, navigate: (Screen) -> Unit, preview: (Template) -> Unit) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val colors = StudioColors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        CatalogHeader(credits, theme, toggleTheme, { navigate(Screen.PLANS) }) { searching = !searching }
        if(searching) OutlinedTextField(query, { query = it }, label = { Text("Search templates") }, singleLine = true,
            trailingIcon = { IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Rounded.Close, "Close search") } },
            shape = PanelShape, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if(!templatePage && query.isBlank()) {
                item { CatalogHero(entries.sortedByDescending { it.featured }.take(3), preview) }
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
private fun CatalogHero(entries: List<Template>, preview: (Template) -> Unit) {
    if(entries.isEmpty()) return
    val pager = rememberPagerState(pageCount = { entries.size })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 12.dp) { index ->
            val item = entries[index]
            Box(Modifier.fillMaxWidth().aspectRatio(2.35f).clip(RoundedCornerShape(18.dp)).clickable { preview(item) }) {
                if(item.remoteId != null) ShowcasePoster(item, Modifier.fillMaxSize(), item.name)
                else if(index == 0) Art(Artwork.Banner, Modifier.fillMaxSize(), "Turn photos into stunning videos")
                else Art(item.art, Modifier.fillMaxSize(), item.name)
                if(item.remoteId != null || index != 0) {
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent))))
                    Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.76f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if(item.remoteId != null) item.name else "Turn Photos Into\nStunning Videos", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                        Text("Create, Edit, Animate with AI", color = Color.White, fontSize = 10.sp)
                    }
                }
                if(item.remoteId != null || index != 0) Surface(color = Color.Black.copy(alpha = .35f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = .75f)), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Preview ${item.name}", tint = Color.White, modifier = Modifier.size(30.dp)) }
                }
            }
        }
        if(entries.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            repeat(entries.size) { index -> Box(Modifier.size(6.dp).background(if(pager.currentPage == index) StudioColors.primary else StudioColors.muted.copy(alpha = .4f), CircleShape)) }
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
        Text(template.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 10.dp))
    }
}
