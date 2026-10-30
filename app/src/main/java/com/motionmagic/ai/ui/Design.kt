package com.motionmagic.ai.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionmagic.ai.R

val Ink = Color(0xFF08090B)
val Panel = Color(0xFF1D1E22)
val Muted = Color(0xFF969AA5)
val Violet = Color(0xFFB83BFF)
val Pink = Color(0xFFFF36AF)
val Blue = Color(0xFF198DFF)
val MagicGradient = Brush.horizontalGradient(listOf(Color(0xFFCF2AF5), Color(0xFF702AFF), Blue))
val PanelShape = RoundedCornerShape(18.dp)

data class StudioTone(val start: Color, val end: Color, val accent: Color) {
    val gradient: Brush get() = Brush.linearGradient(listOf(start, end))
}

fun studioTone(title: String): StudioTone = when {
    title.contains("Image to Video") || title.contains("Kling") -> StudioTone(Color(0xFFC52F95), Color(0xFF6428C4), Color(0xFFFFB3E6))
    title.contains("Create Image") || title.contains("Nano") -> StudioTone(Color(0xFF7A37E8), Color(0xFF3332AD), Color(0xFFD8C1FF))
    title.contains("Text to Video") || title.contains("Gemini") -> StudioTone(Color(0xFF245FD5), Color(0xFF3E26A7), Color(0xFFABDFFF))
    title.contains("Voice", true) || title.contains("Audio") -> StudioTone(Color(0xFF087D91), Color(0xFF2442A0), Color(0xFF99F4EC))
    else -> StudioTone(Color(0xFF8B39C6), Color(0xFF472799), Color(0xFFE8BDFF))
}

// Bundled static weights keep the same typography offline on every supported Android version.
private val MotionFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

private val StudioTypography = Typography(
    displayLarge = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 58.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 50.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-.8).sp),
    headlineLarge = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-.8).sp),
    headlineMedium = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-.6).sp),
    headlineSmall = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-.4).sp),
    titleLarge = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-.4).sp),
    titleMedium = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = (-.2).sp),
    titleSmall = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 19.sp, letterSpacing = .1.sp),
    labelLarge = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = .1.sp),
    labelSmall = TextStyle(fontFamily = MotionFontFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = .2.sp),
)

@Composable
fun MotionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Violet, onPrimary = Color.White, secondary = Blue,
        background = Ink, surface = Ink, surfaceVariant = Panel,
        onBackground = Color.White, onSurface = Color.White,
        outline = Color(0xFF303744), error = Color(0xFFFF8190)
    ), typography = StudioTypography, content = content)
}

enum class Artwork(val x: Int, val y: Int, val width: Int, val height: Int) {
    Portrait(1280, 90, 220, 338),
    Garden(664, 89, 246, 139),
    Cinematic(337, 762, 87, 91),
    Anime(438, 762, 86, 91),
    Love(537, 762, 82, 91),
    Travel(337, 891, 85, 89),
    Birthday(438, 891, 85, 89),
    Cartoon(537, 891, 81, 89),
    Fantasy(1172, 255, 51, 66),
    Beach(962, 255, 55, 66),
    Banner(308, 88, 314, 140)
}

object ArtRepository {
    private var sheet: Bitmap? = null
    private val cache = mutableMapOf<Artwork, Bitmap>()
    @Synchronized fun bitmap(context: Context, artwork: Artwork): Bitmap = cache.getOrPut(artwork) {
        val source = sheet ?: context.assets.open("showcase.png").use { BitmapFactory.decodeStream(it) }.also { sheet = it }
        Bitmap.createBitmap(source, artwork.x, artwork.y, artwork.width, artwork.height)
    }
}

@Composable
fun Art(artwork: Artwork, modifier: Modifier = Modifier, description: String? = null) {
    val context = LocalContext.current
    val bitmap = remember(artwork) { ArtRepository.bitmap(context, artwork).asImageBitmap() }
    Image(bitmap, description, modifier.clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Crop)
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                   enabled: Boolean = true, icon: ImageVector = Icons.Rounded.AutoFixHigh) {
    Button(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) MagicGradient else Brush.horizontalGradient(listOf(Panel, Panel))),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 15.dp)) {
        Icon(icon, null, Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(action, color = Color(0xFFC8ACFF), fontSize = 12.sp)
        }
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 46.dp), shape = RoundedCornerShape(13.dp),
        color = if (selected) Color(0xFF211B36) else Panel,
        border = BorderStroke(1.dp, if (selected) Violet else Color(0xFF242C37))) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
fun TestNotice(text: String = "Free local tools · AI creation uses generation credits", modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B1530))
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Science, null, Modifier.size(18.dp), tint = Color(0xFFD393FF))
        Spacer(Modifier.width(9.dp))
        Text(text, color = Color(0xFFD6C3F1), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
fun SettingRow(icon: ImageVector, title: String, subtitle: String? = null,
               onClick: () -> Unit = {}, trailing: @Composable (() -> Unit)? = null,
               tint: Color = Color.White) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = tint)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = Muted)
        }
        if (trailing != null) trailing() else Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Box(Modifier.size(86.dp).clip(RoundedCornerShape(25.dp)).background(Color(0xFF20132F)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(39.dp), tint = Violet)
        }
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(body, color = Muted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        GradientButton(action, onAction, Modifier.padding(top = 8.dp))
    }
}
