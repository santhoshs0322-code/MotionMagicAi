package com.gamixtv.MotionMagicAI.ui

import android.os.Build
import android.app.Activity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamixtv.MotionMagicAI.MotionViewModel
import com.gamixtv.MotionMagicAI.BuildConfig
import com.gamixtv.MotionMagicAI.Screen
import com.gamixtv.MotionMagicAI.cloud.CloudJob
import com.gamixtv.MotionMagicAI.data.MediaKind
import com.gamixtv.MotionMagicAI.data.Project
import com.gamixtv.MotionMagicAI.data.CreationMode
import com.gamixtv.MotionMagicAI.data.ImageModelCatalog
import com.gamixtv.MotionMagicAI.data.VideoModelCatalog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Template(val name: String, val art: Artwork, val category: String, val aspect: String = "9:16", val duration: Int = 5,
    val remoteId: String? = null, val posterUrl: String? = null, val videoUrl: String? = null,
    val exampleInputUrl: String? = null, val mediaRevision: String = "", val prompt: String? = null,
    val mode: CreationMode = CreationMode.IMAGE_TO_VIDEO, val modelId: String? = null,
    val resolution: String? = null, val generatedAudio: Boolean = false, val featured: Boolean = false,
    val requiresReference: Boolean = false, val bundledPoster: String? = null, val bundledPosterHash: String? = null) {
    fun promptFor(mode: CreationMode): String {
        if (!prompt.isNullOrBlank()) return prompt
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
fun DeleteDialog(title: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete project?") },
        text = { Text("“$title” will be removed from this app. Copies already saved to your gallery will remain.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = StudioColors.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep project", color = StudioColors.primary) } },
        shape = RoundedCornerShape(24.dp), containerColor = Panel)
}

@Composable
fun ProfileScreen(vm: MotionViewModel) {
    LaunchedEffect(vm.activeAccountId) { vm.refreshBilling() }
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
            Text("CURRENT WORKSPACE", color = Color.White, fontSize = 10.sp, letterSpacing = 1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Local Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("Only available tools are listed", fontSize = 11.sp, color = Color(0xFFDBCEEF)) }
                TextButton(onClick = { vm.go(Screen.PLANS) }) { Text("Services", color = Color.White) }
            }
            Text("${vm.walletCredits?.toString() ?: "…"} credits available", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Free: ${vm.freeCredits ?: "…"} · Purchased: ${vm.topupCredits ?: "…"}" +
                if((vm.monthlyCredits ?: 0) > 0) " · Previous membership: ${vm.monthlyCredits}" else "",
                color = Color(0xFFE5D4F5), fontSize = 12.sp)
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
                RateUsRow(vm)
                SettingRow(Icons.Rounded.FolderOpen, "Local workspace", onClick = { vm.notice("Local workspace", "Saved projects on this device are shown only for the signed-in account. Cloud generations belong to that account and can be recovered from My Projects. Device-only previews and voice clips are not uploaded or synced.") })
            }
        }
        TestNotice("Sign in for AI creation. Device Voice uses your installed speech engine.")
    }
}

@Composable
fun SettingsScreen(vm: MotionViewModel, onNotifications: (Boolean) -> Unit) {
    var ratioDialog by remember { mutableStateOf(false) }
    val preferenceTone = studioTone("Create Image")
    val supportTone = studioTone("Text to Video")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Preferences", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Surface(color = Color.Transparent, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, preferenceTone.accent.copy(alpha = .6f))) {
            Column(Modifier.background(Brush.linearGradient(listOf(preferenceTone.start.copy(alpha = .55f), preferenceTone.end.copy(alpha = .5f))))) {
                SettingRow(Icons.Rounded.DarkMode, "Dark appearance", if(vm.settings.themeMode == com.gamixtv.MotionMagicAI.data.ThemeMode.DARK) "Dark" else "Light",
                    onClick = vm::toggleTheme, trailing = {
                        Switch(vm.settings.themeMode == com.gamixtv.MotionMagicAI.data.ThemeMode.DARK, { vm.toggleTheme() })
                    })
                SettingRow(Icons.Rounded.Language, "Language", "English", { vm.notice("Language", "The interface is available in English in this build. Device voice availability depends on installed speech data.") })
                SettingRow(Icons.Rounded.HighQuality, "Video Quality", vm.settings.quality, { vm.notice("Video quality", "On-device photo animations use a 540-pixel short edge. AI video resolution is selected on the creation screen from the available provider options.") })
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
        Text("Help and information", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        Surface(color = Color.Transparent, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, supportTone.accent.copy(alpha = .6f))) {
            Column(Modifier.background(Brush.linearGradient(listOf(supportTone.start.copy(alpha = .55f), supportTone.end.copy(alpha = .5f))))) {
                SettingRow(Icons.Rounded.SupportAgent, "Help & Support", onClick = { showHelp(vm) })
                RateUsRow(vm)
                SettingRow(Icons.Rounded.PrivacyTip, "Privacy", onClick = { vm.notice("Your media and cloud generation", "Local projects are saved on your device. For AI creation, your chosen photo is uploaded to private cloud storage while preparing a quote. After you confirm generation, your prompt and reference photo are sent through the server to fal.ai and the selected model provider. Generated media is saved in cloud storage and downloaded to your device.\n\nGoogle sign-in shares your account ID, name, email and picture with the login server. Session tokens are encrypted with Android Keystore. Signing out clears the saved session but does not delete server records.\n\nDeleting a local project removes its local copy; cloud outputs, uploaded references and gallery copies remain. Sharing sends media to the app you choose. Device Voice uses the selected Android speech engine, whose processing depends on its installed voices and settings.") })
                SettingRow(Icons.Rounded.Description, "About this build", onClick = { vm.notice("MotionMagic AI", "Version ${BuildConfig.VERSION_NAME}\n\nChoose an enabled AI model to create images or videos. Review the server price before confirming. Device Voice does not use AI generation credits. Use media you have permission to upload and publish.") })
            }
        }
    }
    if(ratioDialog) AlertDialog(onDismissRequest = { ratioDialog = false }, title = { Text("Default aspect ratio") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("9:16", "16:9", "1:1").forEach { ratio -> ChoiceChip(ratio, vm.settings.defaultAspect == ratio, Modifier.fillMaxWidth()) {
                vm.saveSettings(vm.settings.copy(defaultAspect = ratio)); vm.updateDraft(vm.draft.copy(aspect = ratio)); ratioDialog = false
            } }
        }
    }, confirmButton = { TextButton(onClick = { ratioDialog = false }) { Text("Close", color = StudioColors.primary) } },
        shape = RoundedCornerShape(24.dp), containerColor = Panel)
}

@Composable
private fun RateUsRow(vm: MotionViewModel) {
    val context = LocalContext.current
    SettingRow(Icons.Rounded.StarOutline, "Rate Us", onClick = {
        if(com.gamixtv.MotionMagicAI.reviews.openPlayStoreReview(context)) vm.recordRatingStoreVisit()
        else vm.message("Could not open Google Play. Please install or enable a browser and try again.")
    }, tint = Color(0xFFFFBF46))
}


@Composable
private fun PlanPurchaseCard(title: String, price: String, credits: String, color: Color, current: Boolean, modifier: Modifier, buy: () -> Unit) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, color.copy(alpha = .5f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.Star, null, tint = color, modifier = Modifier.size(20.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(price, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(credits, color = Muted, fontSize = 10.sp, lineHeight = 13.sp)
            TextButton(onClick = buy, enabled = !current, contentPadding = PaddingValues(0.dp)) { Text(if(current) "Current plan" else "Choose") }
        }
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
