package com.gamixtv.MotionMagicAI.ui

import android.os.Build

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.BuildConfig
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.data.MediaKind
import com.gamixtv.MotionMagicAI.data.Project
import com.gamixtv.MotionMagicAI.data.CreationMode
import com.gamixtv.MotionMagicAI.data.ImageModelCatalog
import com.gamixtv.MotionMagicAI.data.VideoModelCatalog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Template(val name: String, val art: Artwork, val category: String, val aspect: String = "9:16", val duration: Int = 5) {
    fun promptFor(mode: CreationMode): String {
        val scene = when(name) {
            "Cinematic" -> "A sunlit portrait with warm golden light, shallow depth of field and a soft cinematic atmosphere"
            "Anime" -> "An expressive anime character in a vivid illustrated world, soft light, detailed hand-painted scenery"
            "Love Story" -> "Two people sharing a quiet moment at sunset, romantic warm tones and a dreamy atmosphere"
            "Travel" -> "A traveler by a turquoise coastline, bright summer light, ocean views and a relaxed holiday mood"
            "Birthday" -> "A joyful birthday portrait with a colorful cake, soft glowing lights and festive decorations"
            else -> "A playful 3D character with expressive eyes, colorful surroundings and soft studio lighting"
        }
        return if(mode == CreationMode.CREATE_IMAGE) "$scene. Carefully composed, detailed illustration or photography."
        else if(mode == CreationMode.IMAGE_TO_VIDEO) "Preserve the subject from the photo. ${when(name) {
            "Travel" -> "A slow panoramic camera movement with a gentle sea breeze."
            "Birthday" -> "A gentle camera push-in as festive lights shimmer softly."
            else -> "Slow cinematic push-in, natural subtle movement and soft shifting light."
        }}"
        else "$scene. A slow camera push-in with gentle, natural motion."
    }
}
val templates = listOf(Template("Cinematic", Artwork.Cinematic, "Reels"), Template("Anime", Artwork.Anime, "Reels"),
    Template("Love Story", Artwork.Love, "Love"), Template("Travel", Artwork.Travel, "Travel"),
    Template("Birthday", Artwork.Birthday, "Birthday"), Template("3D Cartoon", Artwork.Cartoon, "Reels"))

@Composable
fun HomeScreen(vm: MotionViewModel) = ReferenceCatalogScreen(vm, templatePage = false)

@Composable
fun TemplatesScreen(vm: MotionViewModel) = ReferenceCatalogScreen(vm, templatePage = true)

@Composable
fun ProjectsScreen(vm: MotionViewModel, onImport: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var deletion by remember { mutableStateOf<Project?>(null) }
    val items = vm.projects.sortedByDescending { it.createdAt }.filter { it.title.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Made by you", fontSize = 23.sp, fontWeight = FontWeight.Bold); Text("${vm.projects.size} saved creations", color = Muted, fontSize = 13.sp) }
            FilledTonalIconButton(onClick = onImport) { Icon(Icons.Rounded.FileUpload, "Import video") }
        }
        OutlinedButton(onClick = vm::resumeCloudGeneration, modifier = Modifier.fillMaxWidth()) { Text("Check cloud generation") }
        if(vm.projects.isEmpty()) {
            EmptyState(Icons.Rounded.VideoLibrary, "Your ideas live here", "Create your first video or import one to start editing.", "Create a video") { vm.go(Screen.IMAGE) }
            OutlinedButton(onClick = onImport, Modifier.fillMaxWidth()) { Text("Import a video") }
        } else {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search projects") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = PanelShape)
            if(items.isEmpty()) Text("No projects match “$query”.", color = Muted, modifier = Modifier.padding(vertical = 30.dp))
            items.forEach { project ->
                Surface(onClick = { vm.open(project) }, color = Panel, shape = PanelShape) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF29203F)), contentAlignment = Alignment.Center) {
                            Icon(when(project.kind) { MediaKind.AUDIO -> Icons.Rounded.GraphicEq; MediaKind.IMAGE -> Icons.Rounded.Image; else -> Icons.Rounded.PlayCircle },
                                null, Modifier.size(30.dp), tint = Violet)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(project.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(if(!com.gamixtv.MotionMagicAI.media.MediaAccess.external(project.path) && !File(project.path).isFile) "File missing" else "${when(project.kind) { MediaKind.VIDEO -> "${project.duration}s · ${project.aspect}"; MediaKind.IMAGE -> "Image"; else -> "Voice sample" }} · ${SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(project.createdAt))}",
                                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                        IconButton(onClick = { deletion = project }) { Icon(Icons.Rounded.DeleteOutline, "Delete ${project.title}", tint = Muted, modifier = Modifier.size(21.dp)) }
                    }
                }
            }
        }
    }
    deletion?.let { project -> DeleteDialog(project.title, { deletion = null }) { deletion = null; vm.delete(project) } }
}

@Composable
fun DeleteDialog(title: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete project?") },
        text = { Text("“$title” will be removed from this app. Copies already saved to your gallery will remain.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFFF8190)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep project") } }, containerColor = Panel)
}

@Composable
fun ProfileScreen(vm: MotionViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(70.dp).clip(CircleShape).background(MagicGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, null, Modifier.size(38.dp))
            }
            Column(Modifier.padding(start = 17.dp)) {
                Text("MotionMagic Studio", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Your studio, on this device", color = Muted, fontSize = 12.sp)
            }
        }
        Column(Modifier.fillMaxWidth().clip(PanelShape).background(Brush.horizontalGradient(listOf(Color(0xFF641B88), Color(0xFF2723A6)))).padding(18.dp)) {
            Text("CURRENT WORKSPACE", fontSize = 10.sp, letterSpacing = 1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Local Studio", fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("Only available tools are listed", fontSize = 11.sp, color = Color(0xFFDBCEEF)) }
                TextButton(onClick = { vm.go(Screen.PLANS) }) { Text("Services", color = Color.White) }
            }
        }
        com.gamixtv.MotionMagicAI.auth.AuthAccountCard()
        Surface(color = Panel, shape = PanelShape) {
            Column {
                SettingRow(Icons.Rounded.FolderOpen, "My Projects", "${vm.projects.size} saved locally", { vm.tab(Screen.PROJECTS) })
                SettingRow(Icons.Rounded.Settings, "Settings", onClick = { vm.go(Screen.SETTINGS) })
            }
        }
        Surface(color = Panel, shape = PanelShape) {
            Column {
                SettingRow(Icons.Rounded.HelpOutline, "Help & Support", onClick = { showHelp(vm) })
                SettingRow(Icons.Rounded.StarOutline, "Rate Us", onClick = { vm.notice("Thanks for trying MotionMagic", "This is a local test build and has no Google Play listing yet.") }, tint = Color(0xFFFFBF46))
                SettingRow(Icons.Rounded.FolderOpen, "Local workspace", onClick = { vm.notice("Local workspace", "Projects stay on this device, regardless of the signed-in account. Google sign-in does not upload or sync projects.") })
            }
        }
        TestNotice("Sign in for AI creation. Local editing and Device Voice work without an account.")
    }
}

@Composable
fun SettingsScreen(vm: MotionViewModel, onNotifications: (Boolean) -> Unit) {
    var ratioDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(color = Panel, shape = PanelShape) {
            Column {
                SettingRow(Icons.Rounded.Language, "Language", "English", { vm.notice("Language", "The interface is available in English in this build. Device voice availability depends on installed speech data.") })
                SettingRow(Icons.Rounded.HighQuality, "Video Quality", vm.settings.quality, { vm.notice("Test video quality", "On-device previews use a 540-pixel short edge: 540×960, 960×540 or 540×540. Imported videos retain their source dimensions during editing. HD AI generation will require a live provider.") })
                SettingRow(Icons.Rounded.AspectRatio, "Default Aspect Ratio", vm.settings.defaultAspect, { ratioDialog = true })
                SettingRow(Icons.Rounded.NotificationsNone, "Notifications", onClick = { onNotifications(!vm.settings.notifications) }, trailing = {
                    Switch(vm.settings.notifications, onNotifications)
                })
                SettingRow(Icons.Rounded.SaveAlt, "Auto Save to Gallery", if(Build.VERSION.SDK_INT < 29) "Use Download on Android 8–9" else null,
                    onClick = { if(Build.VERSION.SDK_INT >= 29) vm.saveSettings(vm.settings.copy(autoSave = !vm.settings.autoSave))
                    else vm.notice("Save using Download", "On this Android version, use Download to choose where each video is saved.") }, trailing = {
                    Switch(vm.settings.autoSave && Build.VERSION.SDK_INT >= 29, { vm.saveSettings(vm.settings.copy(autoSave = it)) }, enabled = Build.VERSION.SDK_INT >= 29)
                })
            }
        }
        Surface(color = Panel, shape = PanelShape) {
            Column {
                SettingRow(Icons.Rounded.SupportAgent, "Help & Support", onClick = { showHelp(vm) })
                SettingRow(Icons.Rounded.PrivacyTip, "Privacy", onClick = { vm.notice("Your media and cloud generation", "Editing projects are saved on your device. For AI creation, your chosen photo is uploaded to private cloud storage while preparing a quote. After you confirm generation, your prompt and reference photo are sent through the server to fal.ai and the selected model provider. Generated media is saved in cloud storage and downloaded to your device.\n\nGoogle sign-in shares your account ID, name, email and picture with the login server. Session tokens are encrypted with Android Keystore. Signing out clears the saved session but does not delete server records.\n\nDeleting a local project removes its local copy; cloud outputs, uploaded references and gallery copies remain. Sharing sends media to the app you choose. Device Voice uses the selected Android speech engine, whose processing depends on its installed voices and settings.") })
                SettingRow(Icons.Rounded.Description, "About this build", onClick = { vm.notice("MotionMagic AI", "Version ${BuildConfig.VERSION_NAME}\n\nChoose an enabled AI model to create images or videos. Review the server price before confirming. Local editing and Device Voice do not use AI generation credits. Use media you have permission to upload and publish.") })
            }
        }
        TestNotice("AI creation uses generation credits after you confirm the price. Local editing and Device Voice remain free.")
    }
    if(ratioDialog) AlertDialog(onDismissRequest = { ratioDialog = false }, title = { Text("Default aspect ratio") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("9:16", "16:9", "1:1").forEach { ratio -> ChoiceChip(ratio, vm.settings.defaultAspect == ratio, Modifier.fillMaxWidth()) {
                vm.saveSettings(vm.settings.copy(defaultAspect = ratio)); vm.updateDraft(vm.draft.copy(aspect = ratio)); ratioDialog = false
            } }
        }
    }, confirmButton = { TextButton(onClick = { ratioDialog = false }) { Text("Close") } }, containerColor = Panel)
}

@Composable
fun PlansScreen(vm: MotionViewModel) {
    val accents = listOf(Color(0xFF86EDC9), Color(0xFFD8ACFF))
    LaunchedEffect(Unit) { vm.refreshCloudCatalog() }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B102B), Ink, Ink)))
        .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(32.dp).background(MagicGradient, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = Color.White)
            }
            Text("MOTIONMAGIC STUDIO", color = Color(0xFFD5B4F7), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Create your way", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text("Free tools. Pay for each AI creation.", color = Color(0xFFCCBCD9), fontSize = 14.sp)
        }
        val rows = listOf(
            "Native voice" to listOf("✓", "✓"),
            "Simple editors" to listOf("✓", "✓"),
            "Local projects" to listOf("✓", "✓"),
            "AI creation" to listOf("—", "Per creation"),
        )
        Surface(color = Color(0xFF160E23), shape = RoundedCornerShape(23.dp), border = BorderStroke(1.dp, Color(0xFF634078))) {
            Column {
                Row(Modifier.height(116.dp).background(Brush.horizontalGradient(listOf(Color(0xFF28153F), Color(0xFF32184D))))) {
                    Box(Modifier.weight(1.4f).fillMaxHeight().padding(12.dp), contentAlignment = Alignment.BottomStart) {
                        Text("What’s\nincluded", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE4D4F3), lineHeight = 19.sp)
                    }
                    listOf("Free", "AI credits").forEachIndexed { index, name ->
                        PlanHeader(name, accents[index], index == 0, Modifier.weight(1f))
                    }
                }
                rows.forEachIndexed { rowIndex, (feature, values) ->
                    HorizontalDivider(color = Color(0xFF75558F).copy(alpha = .22f), thickness = .5.dp)
                    Row(Modifier.height(64.dp).background(if(rowIndex % 2 == 0) Color(0xFF20122F) else Color(0xFF180F25))) {
                        PricingCell(feature, Color(0xFFE9DFF2), Modifier.weight(1.4f), label = true)
                        values.forEachIndexed { index, value -> PricingCell(value, accents[index], Modifier.weight(1f)) }
                    }
                }
            }
        }
        Text("Available AI models", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if(vm.cloudRates.isEmpty()) Text(vm.cloudCatalogStatus, color = Muted, fontSize = 12.sp)
        vm.cloudRates.groupBy { it.modelId }.values.forEach { variants ->
            val cheapest = variants.minBy { it.minimumPricePaise }
            Surface(color = Color(0xFF20122F), shape = PanelShape) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(cheapest.kind == "image") ImageModelCatalog.find(cheapest.modelId).title else VideoModelCatalog.find(cheapest.modelId).title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(variants.map { it.size.uppercase() }.distinct().joinToString(" · "), color = Muted, fontSize = 11.sp)
                    }
                    Text("From ${com.gamixtv.MotionMagicAI.cloud.credits(cheapest.minimumPricePaise)}\n${if(cheapest.kind == "image") "per image" else "per ${cheapest.minimumUnits}s video"}", color = accents[1], fontSize = 12.sp, textAlign = TextAlign.End)
                }
            }
        }
        Text("The final quote depends on the model, resolution, duration and generated sound. Review it before confirming. Subscriptions are not enabled.", color = Color(0xFFBAA6CC), fontSize = 12.sp, lineHeight = 19.sp)
        GradientButton("Back to studio", { vm.tab(Screen.HOME) }, icon = Icons.Rounded.Home)
    }
}

@Composable
private fun PlanHeader(name: String, accent: Color, current: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxHeight().background(accent.copy(alpha = .055f)).padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically)) {
            Box(Modifier.size(31.dp).background(accent.copy(alpha = .16f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(if(name == "Free") Icons.Rounded.CheckCircle else Icons.Rounded.Star, "$name plan", tint = accent, modifier = Modifier.size(21.dp))
            }
            Text(name, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = (-.3).sp)
            Text(if(current) "INCLUDED" else "PAY AS YOU CREATE", color = accent.copy(alpha = .9f), fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = .3.sp)
        }
}

@Composable
private fun PricingCell(value: String, color: Color, modifier: Modifier, label: Boolean = false) {
        Box(modifier.fillMaxHeight().background(if(label) Color.Transparent else color.copy(alpha = .035f)).padding(horizontal = if(label) 12.dp else 4.dp, vertical = 8.dp), contentAlignment = if(label) Alignment.CenterStart else Alignment.Center) {
            if(value == "✓") {
                Box(Modifier.size(25.dp).background(color.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, "Included", Modifier.size(16.dp), tint = color)
                }
            } else Text(value, color = if(value == "—") Color(0xFF997DAE) else color, fontSize = if(label) 11.sp else 12.sp,
                lineHeight = 16.sp, fontWeight = if(label) FontWeight.Medium else FontWeight.SemiBold)
        }
}
