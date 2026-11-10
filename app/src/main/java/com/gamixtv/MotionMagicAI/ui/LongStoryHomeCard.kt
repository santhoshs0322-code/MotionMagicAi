package com.gamixtv.MotionMagicAI.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LongStoryHomeCard(modifier: Modifier = Modifier, open: () -> Unit) {
    Surface(onClick = open, modifier = modifier.fillMaxWidth().testTag("home-long-story"),
        color = Color(0xFF100728), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0xFFA45BEE).copy(alpha = .65f))) {
        Box(Modifier.fillMaxWidth().heightIn(min = 220.dp)) {
            StudioArtwork("long_story_home.webp", Modifier.matchParentSize(), ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xFF100728), Color(0xFF100728).copy(alpha = .8f), Color.Transparent))))
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(13.dp), tint = Color(0xFFE7AAFF))
                        Text("LONG STORY", color = Color(0xFFE7AAFF), fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("1\u20135 MIN", Modifier.background(Color(0xFF3E2369).copy(alpha = .9f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color(0xFFF2DCFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.fillMaxWidth(.57f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("One prompt.\nA whole story.", color = Color.White, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp)
                    Text("Photos or video references.\nSpeaking characters with lip sync.", color = Color(0xFFDCD0F0), fontSize = 11.sp, lineHeight = 17.sp)
                }
                Button(onClick = open, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), shape = CircleShape,
                    modifier = Modifier.heightIn(min = 48.dp).background(Brush.horizontalGradient(listOf(Color(0xFFA525EE), Color(0xFF5D35E7))), CircleShape).testTag("home-long-story-create")) {
                    Icon(Icons.Rounded.MovieCreation, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Create a story", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(7.dp))
                    Icon(Icons.Rounded.ArrowForward, null, Modifier.size(15.dp))
                }
                Text("Half-minute lengths \u00b7 1.5, 2.5, 3.5 and more", color = Color(0xFFB7A1D3), fontSize = 9.sp)
            }
        }
    }
}
