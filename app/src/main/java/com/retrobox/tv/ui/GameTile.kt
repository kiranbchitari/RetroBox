@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Colour for a game without a picture, stable per title so a grid looks varied but consistent. */
fun fallbackBrush(title: String): Brush {
    val hue = (title.lowercase().fold(7) { h, c -> h * 31 + c.code } and 0x7fffffff) % 360
    return Brush.linearGradient(listOf(Color.hsv(hue.toFloat(), 0.55f, 0.42f), Color.hsv(((hue + 40) % 360).toFloat(), 0.6f, 0.22f)))
}

/**
 * A game as a poster card: picture (filling the frame) with a title and a heart underneath. [pictureAspect] is the picture's
 * width / height: 1.1 gives the library grid, about 0.95 the taller poster cards of the home rows.
 * OK plays, Y toggles the heart, hold OK for options; tap the heart on touch screens.
 */
@Composable
fun GameTile(
    title: String,
    favorite: Boolean,
    thumb: ImageBitmap?,
    modifier: Modifier = Modifier,
    missing: Boolean = false,
    caption: String? = null,
    pictureAspect: Float = 1.1f,
    big: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onOptions: () -> Unit = {},
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val ok = rememberOkLongPress(onOptions, onClick)
    Surface(
        onClick = ok.onClick, onLongClick = ok.onLongClick,
        modifier = modifier.then(ok.modifier).onKeyEvent { e ->
            if (e.type == KeyEventType.KeyUp && (e.key == Key.ButtonY || e.key == Key.Menu)) { onToggleFavorite(); true } else false
        }.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = RB.Panel, contentColor = RB.Text, focusedContainerColor = RB.PanelHi, focusedContentColor = RB.Text, pressedContainerColor = RB.PanelHi, pressedContentColor = RB.Text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x33FFFFFF)), shape = RoundedCornerShape(12.dp)),
            focusedBorder = Border(BorderStroke(3.dp, RB.Pink), shape = RoundedCornerShape(12.dp)),
        ),
    ) {
        Column(Modifier.padding(6.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(pictureAspect).clip(RoundedCornerShape(8.dp)).background(if (thumb == null) fallbackBrush(title) else Brush.linearGradient(listOf(Color(0xFF080B18), Color(0xFF080B18)))),
                contentAlignment = Alignment.Center,
            ) {
                // Big cards show the whole picture (Fit, smooth) so a cover is never cropped; small tiles keep the pixel-sharp look.
                if (thumb != null) Image(
                    thumb, null, Modifier.fillMaxSize(),
                    contentScale = if (big || pictureAspect >= 1.05f) ContentScale.Fit else ContentScale.Crop,
                    filterQuality = if (big) FilterQuality.Medium else FilterQuality.None,
                )
                else Text(title.removePrefix("The ").take(2).uppercase(), fontSize = if (big) 44.sp else 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White.copy(alpha = 0.85f))
                if (missing) Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) { Text("not connected", fontSize = if (big) 13.sp else 10.sp, color = RB.Warn) }
            }
            Row(Modifier.padding(top = 5.dp).fillMaxWidth().height(if (big) 52.dp else if (caption != null) 44.dp else 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = if (big) 15.sp else 11.sp, lineHeight = if (big) 18.sp else 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (caption != null) Text(caption, fontSize = if (big) 12.sp else 9.sp, lineHeight = if (big) 14.sp else 11.sp, color = RB.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Touch: tap the heart. (Remote/pad: press Y while the tile is focused.)
                Box(Modifier.padding(start = 4.dp).pointerInput(Unit) { detectTapGestures { onToggleFavorite() } }) {
                    HeartIcon(favorite, if (favorite) RB.Pink else RB.Muted, if (big) 22.dp else 16.dp)
                }
            }
        }
    }
}
