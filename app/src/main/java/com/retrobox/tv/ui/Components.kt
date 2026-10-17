@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Focusable card with a clear 3dp accent ring + slight scale; the building block of every menu. */
@Composable
fun TvCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    badgeColor: Color = RB.Muted,
    enabled: Boolean = true,
    width: Dp = 190.dp,
    height: Dp = 92.dp,
    compact: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.width(width).height(height).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = RB.Surface, contentColor = RB.Text,
            focusedContainerColor = RB.SurfaceHi, focusedContentColor = RB.Text,
            pressedContainerColor = RB.SurfaceHi, pressedContentColor = RB.Text,
            disabledContainerColor = RB.Surface, disabledContentColor = RB.Muted,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, RB.Accent), shape = RoundedCornerShape(14.dp))),
    ) {
        if (compact) {
            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            return@Surface
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, lineHeight = 15.sp, color = RB.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(badgeColor))
                    Spacer(Modifier.width(8.dp))
                    Text(badge, fontSize = 12.sp, color = badgeColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * A game: its picture (or a plain coloured tile until one exists) with title and a short caption.
 * [wide] = picture on the left, text on the right (lists); otherwise picture on top (tiles).
 */
@Composable
fun GameCard(
    title: String,
    caption: String,
    thumb: androidx.compose.ui.graphics.ImageBitmap?,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    width: Dp,
    height: Dp,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val ok = rememberOkLongPress(onLongClick, onClick)
    Surface(
        onClick = ok.onClick,
        onLongClick = ok.onLongClick,
        modifier = modifier.then(ok.modifier).width(width).height(height).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = RB.Surface, contentColor = RB.Text,
            focusedContainerColor = RB.SurfaceHi, focusedContentColor = RB.Text,
            pressedContainerColor = RB.SurfaceHi, pressedContentColor = RB.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, RB.Accent), shape = RoundedCornerShape(14.dp))),
    ) {
        @Composable fun picture(m: Modifier) {
            Box(m.background(Color(0xFF0B0E14)), contentAlignment = Alignment.Center) {
                if (thumb != null) androidx.compose.foundation.Image(thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                else Text(title.take(1).uppercase(), fontSize = 28.sp, color = RB.Muted)
            }
        }
        @Composable fun text(m: Modifier) {
            Column(m.padding(10.dp), verticalArrangement = Arrangement.Center) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(caption, fontSize = 12.sp, color = RB.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            }
        }
        if (wide) Row(Modifier.fillMaxSize()) { picture(Modifier.width(height * 4 / 3).fillMaxHeight()); text(Modifier.weight(1f).fillMaxHeight()) }
        else Column(Modifier.fillMaxSize()) { picture(Modifier.fillMaxWidth().weight(1f)); text(Modifier.fillMaxWidth()) }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = RB.Muted, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = RB.Muted)
    }
}

/**
 * A text row that takes D-pad focus. Lists of plain text are otherwise unreachable (and therefore
 * unscrollable) on a TV, so diagnostics/log rows are focusable on purpose.
 */
@Composable
fun FocusRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) RB.SurfaceHi else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { content() }
}

@Composable
fun KeyValueRow(label: String, value: String) {
    FocusRow {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = RB.Muted, fontSize = 14.sp, modifier = Modifier.width(230.dp))
            Text(value, fontSize = 14.sp)
        }
    }
}
