package com.example.myapplication.loopbox

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Pulsing placeholder shown while a feed loads: same layout as the real page, so nothing jumps. */
@Composable
fun BrowseSkeleton(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val a by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    val block = Color(0xFF2A2A2D)
    Column(modifier.fillMaxWidth().alpha(a), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(300.dp).clip(RoundedCornerShape(22.dp)).background(block))
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(horizontal = 16.dp).width(140.dp).height(18.dp).clip(RoundedCornerShape(6.dp)).background(block))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) {
                        Box(Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(block))
                    }
                }
            }
        }
    }
}
