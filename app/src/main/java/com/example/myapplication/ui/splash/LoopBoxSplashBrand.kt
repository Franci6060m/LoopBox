package com.example.myapplication.ui.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.SnProFamily

/** Minimal, deliberate LoopBox identity: a media frame, a continuous loop and a play mark. */
@Composable
internal fun LoopBoxSplashBrand(
    timeSec: () -> Float,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val ink = if (dark) Color(0xFFF4F0EA) else Color(0xFF171717)
    val orange = Color(0xFFFF6A32)
    val gold = Color(0xFFFFB45B)
    val t = timeSec().coerceIn(0f, 4.3f)
    val reveal = ((t - 0.12f) / 0.9f).coerceIn(0f, 1f)
    val scale = 0.88f + 0.12f * (1f - kotlin.math.exp(-5f * reveal))
    Column(
        modifier = modifier.scale(scale).alpha(reveal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Canvas(Modifier.size(154.dp)) {
            val side = size.minDimension * 0.78f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val corner = side * 0.25f
            val stroke = side * 0.055f
            val center = Offset(size.width / 2f, size.height / 2f)
            val glow = side * (0.72f + 0.08f * kotlin.math.sin(t * 2f))
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(orange.copy(alpha = 0.19f * reveal), orange.copy(alpha = 0f)),
                    center = center,
                    radius = glow,
                ),
                radius = glow,
                center = center,
            )
            drawRoundRect(
                brush = Brush.linearGradient(listOf(orange, gold)),
                topLeft = Offset(left, top),
                size = Size(side, side),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner),
                style = Stroke(width = stroke),
            )
            val loopRadius = side * 0.265f
            drawArc(
                brush = Brush.sweepGradient(listOf(orange, gold, orange), center),
                startAngle = 42f,
                sweepAngle = 282f,
                useCenter = false,
                topLeft = Offset(center.x - loopRadius, center.y - loopRadius),
                size = Size(loopRadius * 2f, loopRadius * 2f),
                style = Stroke(width = stroke * 1.05f, cap = StrokeCap.Round),
            )
            val play = Path().apply {
                moveTo(center.x - side * 0.075f, center.y - side * 0.13f)
                lineTo(center.x + side * 0.15f, center.y)
                lineTo(center.x - side * 0.075f, center.y + side * 0.13f)
                close()
            }
            drawPath(play, Brush.linearGradient(listOf(orange, gold)))
        }
        Text(
            text = "LoopBox",
            color = ink,
            fontFamily = SnProFamily,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1.2).sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "ALL YOUR STORIES. ONE PLACE.",
            color = orange,
            fontFamily = SnProFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.8.sp,
            textAlign = TextAlign.Center,
        )
    }
}
