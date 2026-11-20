package com.example.myapplication.loopbox

import androidx.compose.ui.graphics.Color

/** Design tokens from the LoopBox web prototype (.ph CSS variables). */
object LoopBoxColors {
    val Background = Color(0xFF0A0A0A)
    val Card = Color(0xFF1C1C1E)
    val Foreground = Color(0xFFF3EAD8)
    val Muted = Color(0xFFA39D90)
    val Accent = Color(0xFFE85002)
    val PageLight = Color(0xFFE9E5DC)
    val PageDark = Color(0xFF050505)
    val BannerBg = Color(0xFF3A2A10)
    val BannerFg = Color(0xFFFFD9A0)
}

/** Fonts used by the prototype: headings Archivo Black, reader Atkinson Hyperlegible / Lexend. */
object LoopBoxFonts {
    const val Heading = "Archivo Black"
    const val ReaderAccessible = "Atkinson Hyperlegible"
    const val ReaderComfort = "Lexend"
}
