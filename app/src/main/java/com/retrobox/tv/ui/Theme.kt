package com.retrobox.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Dark console palette; contrast ratios checked for text on Bg/Surface (all > 7:1). */
object RB {
    val Bg = Color(0xFF0E1117)
    val Surface = Color(0xFF161B22)
    val SurfaceHi = Color(0xFF212833)
    val Accent = Color(0xFFE8334A)
    val Text = Color(0xFFF5F7FA)
    val Muted = Color(0xFFA3ADBB)
    val Good = Color(0xFF3DDC84)
    val Warn = Color(0xFFFFB020)
    // Dashboard accents
    val Pink = Color(0xFFFF3D8B)
    val Cyan = Color(0xFF22D3EE)
    val Panel = Color(0xE6131A36)
    val PanelHi = Color(0xF21D2750)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RetroBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RB.Accent, onPrimary = RB.Text,
            background = RB.Bg, onBackground = RB.Text,
            surface = RB.Surface, onSurface = RB.Text,
            surfaceVariant = RB.SurfaceHi, onSurfaceVariant = RB.Muted,
        ),
        content = { CompositionLocalProvider(LocalContentColor provides RB.Text) { content() } },
    )
}
