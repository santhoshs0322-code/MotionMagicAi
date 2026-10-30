package com.motionmagic.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionmagic.ai.MotionViewModel
import com.motionmagic.ai.Screen
import com.motionmagic.ai.data.CreationMode

private data class CatalogTool(val title: String, val icon: ImageVector, val destination: Screen)
private val mainCatalogTools = listOf(
    CatalogTool("Create Image", Icons.Rounded.Image, Screen.CREATE_IMAGE),
    CatalogTool("Image to Video", Icons.Rounded.SmartDisplay, Screen.IMAGE),
    CatalogTool("Text to Video", Icons.Rounded.Title, Screen.TEXT),
    CatalogTool("Device Voice", Icons.Rounded.GraphicEq, Screen.VOICE),
)

@Composable
fun ReferenceCatalogScreen(vm: MotionViewModel, templatePage: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<Template?>(null) }
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { searching = !searching }) { Icon(Icons.Rounded.Search, "Search templates", Modifier.size(28.dp)) }
            Spacer(Modifier.weight(1f))
            Text("MotionMagic AI", color = Color(0xFFF4E9FF), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp, modifier = Modifier.padding(end = 10.dp))
            Surface(onClick = { vm.go(Screen.PLANS) }, color = Color(0xFF202126), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(start = 9.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = Color(0xFFFFCB52), modifier = Modifier.size(17.dp))
                    Text(vm.currentPlan.title, color = Color(0xFFFFCB52), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 5.dp))
                    Text("Upgrade", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(ReferenceGradient, RoundedCornerShape(17.dp)).padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }
        if(searching) OutlinedTextField(query, { query = it }, label = { Text("Search templates") }, singleLine = true,
            trailingIcon = { IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Rounded.Close, "Close search") } },
            shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp))
        if(templatePage) CompactCatalogTools(vm)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            if(!templatePage && query.isBlank()) {
                item {
                    Box(Modifier.padding(horizontal = 5.dp).fillMaxWidth().aspectRatio(3.1f).clip(RoundedCornerShape(15.dp)).clickable { vm.go(Screen.IMAGE) }) {
                        Art(Artwork.Banner, Modifier.fillMaxSize(), "MotionMagic creation studio")
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        mainCatalogTools.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                pair.forEach { tool ->
                                    CatalogCreationCard(tool, Modifier.weight(1f)) { vm.go(tool.destination) }
                                }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        RoundCatalogTool("Voice", Icons.Rounded.GraphicEq) { vm.go(Screen.VOICE) }
                        RoundCatalogTool("Video Editor", Icons.Rounded.ContentCut) { vm.go(Screen.EDITOR) }
                        RoundCatalogTool("Audio Editor", Icons.Rounded.Headphones) { vm.go(Screen.AUDIO_EDITOR) }
                        RoundCatalogTool("Templates", Icons.Rounded.AutoAwesome) { vm.tab(Screen.TEMPLATES) }
                        RoundCatalogTool(if(expanded) "Collapse" else "More", if(expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore) { expanded = !expanded }
                    }
                }
                if(expanded) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(25.dp)) {
                        RoundCatalogTool("My projects", Icons.Rounded.FolderOpen) { vm.tab(Screen.PROJECTS) }
                        RoundCatalogTool("Settings", Icons.Rounded.Settings) { vm.go(Screen.SETTINGS) }
                    }
                }
            }
            val groups = if(query.isNotBlank()) listOf("Search results" to templates.filter { it.name.contains(query, true) || it.category.contains(query, true) })
            else listOf(
                "Trending" to templates,
                "Little Heroes" to templates.filter { it.name in listOf("Anime", "Birthday", "3D Cartoon") },
                "Happy Birthday" to templates.filter { it.name in listOf("Birthday", "Travel", "Love Story") },
                "Star Style" to templates.filter { it.name in listOf("Cinematic", "Love Story", "Anime") },
                "Travel Stories" to templates.filter { it.name in listOf("Travel", "Cinematic", "3D Cartoon") },
            )
            groups.forEach { (title, options) ->
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
                        IconButton(onClick = { if(options.isNotEmpty()) preview = options.first() }) { Icon(Icons.Rounded.ChevronRight, "Browse $title", tint = Color(0xFF8F929C)) }
                    }
                    if(options.isEmpty()) Text("No templates match your search.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    else LazyRow(contentPadding = PaddingValues(horizontal = 15.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(options, key = { it.name }) { item -> TemplatePosterCard(item, Modifier.width(118.dp).height(160.dp)) { preview = item } }
                    }
                }
            }
        }
    }
    preview?.let { item -> TemplatePreviewSheet(item, CreationMode.IMAGE_TO_VIDEO, { preview = null }) {
        vm.useTemplate(item.art, item.name, item.promptFor(CreationMode.IMAGE_TO_VIDEO), CreationMode.IMAGE_TO_VIDEO, item.aspect, item.duration)
        preview = null
    } }
}

@Composable
private fun CompactCatalogTools(vm: MotionViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        mainCatalogTools.forEach { tool ->
            Surface(onClick = { vm.go(tool.destination) }, color = Color.Transparent, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, studioTone(tool.title).accent.copy(alpha = .25f)), modifier = Modifier.weight(1f).height(91.dp)) {
                Column(Modifier.background(studioTone(tool.title).gradient).padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(tool.icon, null, Modifier.size(26.dp), tint = Color.White)
                    Text(tool.title, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RoundCatalogTool(title: String, icon: ImageVector, action: () -> Unit) {
    val tone = studioTone(title)
    Column(Modifier.width(66.dp).clickable(onClick = action), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(49.dp).background(tone.gradient, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(23.dp), tint = Color.White) }
        Text(title, color = Color(0xFFDED6ED), fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun CatalogCreationCard(tool: CatalogTool, modifier: Modifier, action: () -> Unit) {
    val tone = studioTone(tool.title)
    Surface(onClick = action, color = Color.Transparent, shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, tone.accent.copy(alpha = .3f)), modifier = modifier.height(108.dp)) {
        Box(Modifier.fillMaxSize().background(tone.gradient)) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 16.dp, y = (-20).dp).size(94.dp).background(Color.White.copy(alpha = .07f), CircleShape))
            Icon(tool.icon, null, Modifier.align(Alignment.TopEnd).padding(13.dp).size(29.dp), tint = Color.White)
            Column(Modifier.align(Alignment.BottomStart).padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tool.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
                Text(when(tool.destination) { Screen.CREATE_IMAGE -> "Imagine it. Create it."; Screen.IMAGE -> "Bring photos to life"; Screen.TEXT -> "Your words, in motion"; else -> "Give your story a voice" }, color = Color.White.copy(alpha = .82f), fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
fun TemplatePosterCard(template: Template, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(13.dp)).clickable(onClick = action)) {
        Art(template.art, Modifier.fillMaxSize(), "${template.name} template")
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f)))))
        Text(template.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 12.dp))
    }
}
