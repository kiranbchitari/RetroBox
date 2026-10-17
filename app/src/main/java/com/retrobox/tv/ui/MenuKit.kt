@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/*
 * Building blocks of every popup menu (pause menu, save/load slots, confirmations, a game's options):
 * a rounded dark panel with a header (picture, title, system chip, badge), icon rows with a chevron, and value rows
 * (‹ value ›) for settings. Icons are drawn in code like the rest of the artwork.
 */

enum class MenuGlyph { PLAY, SAVE, LOAD, RESET, SCREEN, SPARKLE, SPEAKER, SPEAKER_OFF, EXIT, HEART, GAMEPAD, BACK, PAUSE, CHECK, CLOSE, TRASH }

private fun DrawScope.line(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, w: Float) =
    drawLine(c, Offset(x1 * size.width, y1 * size.height), Offset(x2 * size.width, y2 * size.height), w, StrokeCap.Round)

@Composable
fun MenuGlyphView(glyph: MenuGlyph, tint: Color, iconSize: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(iconSize)) {
        val w = size.minDimension; val sw = w * 0.09f
        val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun x(v: Float) = v * size.width
        fun y(v: Float) = v * size.height
        when (glyph) {
            MenuGlyph.PLAY -> drawPath(Path().apply { moveTo(x(0.22f), y(0.12f)); lineTo(x(0.88f), y(0.5f)); lineTo(x(0.22f), y(0.88f)); close() }, tint)
            MenuGlyph.PAUSE -> { drawRoundRect(tint, Offset(x(0.2f), y(0.12f)), Size(x(0.2f), y(0.76f)), CornerRadius(w * 0.05f)); drawRoundRect(tint, Offset(x(0.6f), y(0.12f)), Size(x(0.2f), y(0.76f)), CornerRadius(w * 0.05f)) }
            MenuGlyph.SAVE -> {
                drawRoundRect(tint, Offset(x(0.1f), y(0.1f)), Size(x(0.8f), y(0.8f)), CornerRadius(w * 0.1f))
                drawRoundRect(Color(0xFF121A3C), Offset(x(0.27f), y(0.1f)), Size(x(0.46f), y(0.3f)), CornerRadius(w * 0.04f))
                drawCircle(Color(0xFF121A3C), w * 0.14f, Offset(x(0.5f), y(0.64f)))
            }
            MenuGlyph.LOAD -> {
                drawPath(Path().apply { moveTo(x(0.08f), y(0.22f)); lineTo(x(0.38f), y(0.22f)); lineTo(x(0.48f), y(0.34f)); lineTo(x(0.92f), y(0.34f)); lineTo(x(0.92f), y(0.84f)); lineTo(x(0.08f), y(0.84f)); close() }, tint)
            }
            MenuGlyph.RESET -> {
                drawArc(tint, 40f, 290f, false, Offset(x(0.15f), y(0.15f)), Size(x(0.7f), y(0.7f)), style = st)
                drawPath(Path().apply { moveTo(x(0.02f), y(0.04f)); lineTo(x(0.5f), y(0.08f)); lineTo(x(0.22f), y(0.5f)); close() }, tint)
            }
            MenuGlyph.SCREEN -> { drawRoundRect(tint, Offset(x(0.08f), y(0.2f)), Size(x(0.84f), y(0.6f)), CornerRadius(w * 0.08f), style = st); drawRoundRect(tint.copy(alpha = 0.35f), Offset(x(0.16f), y(0.28f)), Size(x(0.68f), y(0.44f)), CornerRadius(w * 0.04f)) }
            MenuGlyph.SPARKLE -> {
                fun star(cx: Float, cy: Float, r: Float) = drawPath(Path().apply {
                    moveTo(x(cx), y(cy - r)); quadraticTo(x(cx), y(cy), x(cx + r), y(cy)); quadraticTo(x(cx), y(cy), x(cx), y(cy + r))
                    quadraticTo(x(cx), y(cy), x(cx - r), y(cy)); quadraticTo(x(cx), y(cy), x(cx), y(cy - r)); close()
                }, tint)
                star(0.45f, 0.5f, 0.4f); star(0.8f, 0.2f, 0.16f); star(0.78f, 0.82f, 0.12f)
            }
            MenuGlyph.SPEAKER, MenuGlyph.SPEAKER_OFF -> {
                drawPath(Path().apply { moveTo(x(0.1f), y(0.38f)); lineTo(x(0.32f), y(0.38f)); lineTo(x(0.56f), y(0.16f)); lineTo(x(0.56f), y(0.84f)); lineTo(x(0.32f), y(0.62f)); lineTo(x(0.1f), y(0.62f)); close() }, tint)
                if (glyph == MenuGlyph.SPEAKER) { drawArc(tint, -45f, 90f, false, Offset(x(0.46f), y(0.3f)), Size(x(0.3f), y(0.4f)), style = st); drawArc(tint, -45f, 90f, false, Offset(x(0.42f), y(0.14f)), Size(x(0.5f), y(0.72f)), style = st) }
                else { line(tint, 0.68f, 0.36f, 0.92f, 0.64f, sw); line(tint, 0.92f, 0.36f, 0.68f, 0.64f, sw) }
            }
            MenuGlyph.EXIT -> {
                drawPath(Path().apply { moveTo(x(0.5f), y(0.12f)); lineTo(x(0.14f), y(0.12f)); lineTo(x(0.14f), y(0.88f)); lineTo(x(0.5f), y(0.88f)) }, tint, style = st)
                line(tint, 0.4f, 0.5f, 0.9f, 0.5f, sw); line(tint, 0.9f, 0.5f, 0.68f, 0.3f, sw); line(tint, 0.9f, 0.5f, 0.68f, 0.7f, sw)
            }
            MenuGlyph.HEART -> drawPath(Path().apply {
                moveTo(x(0.5f), y(0.88f)); cubicTo(x(0.05f), y(0.55f), x(0.0f), y(0.2f), x(0.25f), y(0.14f)); cubicTo(x(0.4f), y(0.1f), x(0.5f), y(0.25f), x(0.5f), y(0.3f))
                cubicTo(x(0.5f), y(0.25f), x(0.6f), y(0.1f), x(0.75f), y(0.14f)); cubicTo(x(1.0f), y(0.2f), x(0.95f), y(0.55f), x(0.5f), y(0.88f)); close()
            }, tint)
            MenuGlyph.GAMEPAD -> drawControllerOutline(tint, st, tint)
            MenuGlyph.BACK -> { line(tint, 0.9f, 0.5f, 0.14f, 0.5f, sw); line(tint, 0.14f, 0.5f, 0.46f, 0.18f, sw); line(tint, 0.14f, 0.5f, 0.46f, 0.82f, sw) }
            MenuGlyph.CHECK -> { line(tint, 0.14f, 0.54f, 0.4f, 0.8f, sw * 1.2f); line(tint, 0.4f, 0.8f, 0.88f, 0.24f, sw * 1.2f) }
            MenuGlyph.TRASH -> {
                line(tint, 0.14f, 0.24f, 0.86f, 0.24f, sw * 1.2f); line(tint, 0.38f, 0.12f, 0.62f, 0.12f, sw * 1.2f)
                drawPath(Path().apply { moveTo(x(0.24f), y(0.36f)); lineTo(x(0.3f), y(0.88f)); lineTo(x(0.7f), y(0.88f)); lineTo(x(0.76f), y(0.36f)) }, tint, style = st)
            }
            MenuGlyph.CLOSE -> { line(tint, 0.2f, 0.2f, 0.8f, 0.8f, sw * 1.2f); line(tint, 0.8f, 0.2f, 0.2f, 0.8f, sw * 1.2f) }
        }
    }
}

@Composable
private fun Chevron(right: Boolean, tint: Color = Color(0xFF9FB0FF)) {
    Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
        val a = if (right) 0.15f else 0.85f; val b = if (right) 0.85f else 0.15f
        val sw = size.width * 0.2f
        drawLine(tint, Offset(size.width * a, size.height * 0.1f), Offset(size.width * b, size.height * 0.5f), sw, StrokeCap.Round)
        drawLine(tint, Offset(size.width * b, size.height * 0.5f), Offset(size.width * a, size.height * 0.9f), sw, StrokeCap.Round)
    }
}

/** Full-screen dim layer with the menu panel centred; the panel scrolls if the screen is too short (phones). */
@Composable
fun MenuPanel(width: Dp = 400.dp, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xB30A0E22)), contentAlignment = Alignment.Center) {
        Column(
            // A pop-up keeps the D-pad inside: without this, Up/Down past its first/last row moved focus to the
            // (dimmed) screen behind it, and OK then acted on that hidden item.
            Modifier.focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
                .width(width).heightIn(max = maxHeight * 0.96f).clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(Color(0xF2141B40), Color(0xF20C1230))))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            content = content,
        )
    }
}

/** Picture (or lettered tile), title, system chip and an optional badge such as "Game Paused". */
@Composable
fun MenuHeader(title: String, chip: String?, badge: String?, thumb: ImageBitmap?, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 74.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF080B18)), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            else Text(title.take(1).uppercase(), fontSize = 24.sp, color = RB.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
            if (chip != null) Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3B2F8F)).padding(horizontal = 9.dp, vertical = 2.dp)) {
                Text(chip.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = RB.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp), lineHeight = 12.sp)
        }
        if (badge != null) Row(
            Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(10.dp)).background(Color(0x33141B40)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { MenuGlyphView(MenuGlyph.PAUSE, RB.Text, 12.dp); Text("  $badge", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun MenuDivider() { Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(Color(0x22FFFFFF))) }

@Composable
fun MenuNote(text: String, color: Color = RB.Muted) {
    Text(text, fontSize = 12.sp, lineHeight = 15.sp, color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun RowSurface(modifier: Modifier, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().height(42.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xCC111838), contentColor = RB.Text, focusedContainerColor = Color(0xE63A1236), focusedContentColor = RB.Text,
            pressedContainerColor = Color(0xE63A1236), pressedContentColor = RB.Text, disabledContainerColor = Color(0x80111838), disabledContentColor = RB.Muted,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x14FFFFFF)), shape = RoundedCornerShape(12.dp)),
            focusedBorder = Border(BorderStroke(2.5.dp, RB.Pink), shape = RoundedCornerShape(12.dp)),
        ),
    ) { content() }
}

/** Icon, label, chevron. */
@Composable
fun MenuRow(glyph: MenuGlyph, tint: Color, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, chevron: Boolean = true, onClick: () -> Unit) {
    RowSurface(modifier, enabled, onClick) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuGlyphView(glyph, if (enabled) tint else tint.copy(alpha = 0.35f), 22.dp)
            Spacer(Modifier.width(16.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (chevron) Chevron(true)
        }
    }
}

/** A setting: icon, label and "‹ value ›". OK / Right = next, Left = previous. */
@Composable
fun MenuValueRow(glyph: MenuGlyph, tint: Color, label: String, value: String, modifier: Modifier = Modifier, onPrev: () -> Unit, onNext: () -> Unit) {
    RowSurface(
        modifier.onKeyEvent { e ->
            when (e.key) {
                Key.DirectionLeft -> { if (e.type == KeyEventType.KeyDown) onPrev(); true }
                Key.DirectionRight -> { if (e.type == KeyEventType.KeyDown) onNext(); true }
                else -> false
            }
        },
        true, onNext,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuGlyphView(glyph, tint, 22.dp)
            Spacer(Modifier.width(16.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Chevron(false); Text(value, Modifier.width(112.dp), fontSize = 13.sp, color = Color(0xFFB4C0FF), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis); Chevron(true)
        }
    }
}
