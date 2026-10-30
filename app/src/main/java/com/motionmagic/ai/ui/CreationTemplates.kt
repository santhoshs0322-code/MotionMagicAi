package com.motionmagic.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionmagic.ai.data.CreationMode

@Composable
fun TemplateMiniCard(template: Template, onClick: () -> Unit) {
    Box(Modifier.width(116.dp).height(142.dp).clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick)) {
        Art(template.art, Modifier.fillMaxSize(), "${template.name} template")
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(template.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Try this look", color = Color(0xFFD9C4F0), fontSize = 9.sp)
        }
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(9.dp).size(14.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatePreviewSheet(template: Template, mode: CreationMode, onDismiss: () -> Unit, onApply: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Box(Modifier.fillMaxWidth().height(225.dp).clip(RoundedCornerShape(20.dp))) {
                Art(template.art, Modifier.fillMaxSize(), "${template.name} preview")
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f)))))
                Text("LOOK PREVIEW", fontSize = 9.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha = .5f), RoundedCornerShape(7.dp)).padding(7.dp))
                Text(template.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(18.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(mode.title, fontSize = 11.sp) })
                AssistChip(onClick = {}, label = { Text(template.aspect, fontSize = 11.sp) })
                if(mode != CreationMode.CREATE_IMAGE) AssistChip(onClick = {}, label = { Text("${template.duration}s", fontSize = 11.sp) })
            }
            Text("The starting prompt", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(template.promptFor(mode), color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
            Text(if(mode == CreationMode.IMAGE_TO_VIDEO) "Use your own photo as the first frame. This template applies the prompt and settings; you can change everything before generating."
                else "This template applies a prompt and aspect ratio. You can edit everything before generating.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
            GradientButton("Use template", onApply, icon = Icons.Rounded.AutoAwesome)
        }
    }
}
