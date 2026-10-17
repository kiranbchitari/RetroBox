package com.retrobox.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.retrobox.tv.emulator.PadButton
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * All artwork on the dashboard is drawn here in code: original, generic gamepad / handheld silhouettes and line icons.
 * Nothing is downloaded or bundled as an image, so there is no licensing question about console photos and no APK weight.
 */

// ---- helpers: fractions of the canvas, so every icon scales to any size ----
private fun DrawScope.fx(v: Float) = v * size.width
private fun DrawScope.fy(v: Float) = v * size.height
private fun DrawScope.box(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color) =
    drawRoundRect(c, Offset(fx(x), fy(y)), Size(fx(w), fy(h)), CornerRadius(r * size.minDimension))
private fun DrawScope.dot(x: Float, y: Float, r: Float, c: Color) = drawCircle(c, r * size.minDimension, Offset(fx(x), fy(y)))
private fun DrawScope.dpad(cx: Float, cy: Float, arm: Float, thick: Float, c: Color) {
    // [arm] and [thick] are fractions of the shorter side.
    val m = size.minDimension
    drawRoundRect(c, Offset(fx(cx) - arm * m, fy(cy) - thick * m / 2), Size(arm * 2 * m, thick * m), CornerRadius(thick * m * 0.25f))
    drawRoundRect(c, Offset(fx(cx) - thick * m / 2, fy(cy) - arm * m), Size(thick * m, arm * 2 * m), CornerRadius(thick * m * 0.25f))
}

@Composable
fun ConsoleIcon(systemId: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (systemId) {
            "nes" -> drawNes()
            "snes" -> drawSnes()
            "gb" -> drawGameBoy(body = Color(0xFFC4C7BE), screen = Color(0xFF9BBC0F), bezel = Color(0xFF50545E), buttons = Color(0xFFA8305A))
            "gbc" -> drawGameBoy(body = Color(0xFF7B52D8), screen = Color(0xFF38C6B4), bezel = Color(0xFF1B1F2E), buttons = Color(0xFF2C2F3D))
            "gba" -> drawGba()
            "genesis" -> drawGenesis()
            else -> drawNes()
        }
    }
}

/** Aspect ratio (width / height) the icon of [systemId] is drawn for. */
fun consoleIconAspect(systemId: String) = when (systemId) { "gb", "gbc" -> 0.62f; "nes" -> 1.9f; "snes" -> 1.75f; "gba" -> 1.7f; "genesis" -> 1.75f; else -> 1.5f }

private fun DrawScope.drawNes() {
    box(0.01f, 0.08f, 0.98f, 0.84f, 0.06f, Color(0xFFC9CCD3))
    box(0.05f, 0.18f, 0.9f, 0.64f, 0.04f, Color(0xFF1F2026))
    dpad(0.2f, 0.5f, 0.2f, 0.17f, Color(0xFF08080A))
    box(0.4f, 0.55f, 0.1f, 0.1f, 0.04f, Color(0xFF9BA1AC)); box(0.53f, 0.55f, 0.1f, 0.1f, 0.04f, Color(0xFF9BA1AC))
    dot(0.74f, 0.58f, 0.1f, Color(0xFFD8304A)); dot(0.89f, 0.58f, 0.1f, Color(0xFFD8304A))
}

private fun DrawScope.drawSnes() {
    val body = Color(0xFFD9DBE1)
    box(0.03f, 0.2f, 0.94f, 0.6f, 0.3f, body)
    dot(0.17f, 0.5f, 0.3f, body); dot(0.83f, 0.5f, 0.3f, body)
    dpad(0.2f, 0.5f, 0.2f, 0.15f, Color(0xFF454954))
    box(0.4f, 0.56f, 0.09f, 0.08f, 0.04f, Color(0xFF8A8F9B)); box(0.52f, 0.56f, 0.09f, 0.08f, 0.04f, Color(0xFF8A8F9B))
    val r = 0.085f
    dot(0.88f, 0.5f, r, Color(0xFFE0344B)); dot(0.76f, 0.5f, r, Color(0xFF3DDC84))
    dot(0.82f, 0.32f, r, Color(0xFF3B82F6)); dot(0.82f, 0.68f, r, Color(0xFFF2C230))
}

/** A generic black six-button pad (rounded body with two grips, round D-pad, A B C below X Y Z). */
private fun DrawScope.drawGenesis() {
    val body = Color(0xFF3A3D48)
    box(0.06f, 0.18f, 0.88f, 0.56f, 0.28f, body)
    dot(0.2f, 0.56f, 0.4f, body); dot(0.8f, 0.56f, 0.4f, body)
    dot(0.22f, 0.5f, 0.27f, Color(0xFF4B4F5C))
    dpad(0.22f, 0.5f, 0.17f, 0.11f, Color(0xFF101115))
    box(0.45f, 0.4f, 0.1f, 0.06f, 0.03f, Color(0xFF6B6F7A))   // Start
    val small = 0.065f; val big = 0.095f
    dot(0.64f, 0.38f, small, Color(0xFF8A8F9B)); dot(0.75f, 0.33f, small, Color(0xFF8A8F9B)); dot(0.86f, 0.3f, small, Color(0xFF8A8F9B))
    dot(0.62f, 0.6f, big, Color(0xFF55586A)); dot(0.75f, 0.54f, big, Color(0xFF55586A)); dot(0.88f, 0.5f, big, Color(0xFF55586A))
}

private fun DrawScope.drawGameBoy(body: Color, screen: Color, bezel: Color, buttons: Color) {
    box(0.04f, 0.02f, 0.92f, 0.96f, 0.1f, body)
    box(0.12f, 0.08f, 0.76f, 0.44f, 0.05f, bezel)
    box(0.2f, 0.14f, 0.6f, 0.32f, 0.02f, screen)
    dpad(0.27f, 0.7f, 0.17f, 0.12f, Color(0xFF2A2C34))
    dot(0.8f, 0.66f, 0.08f, buttons); dot(0.62f, 0.75f, 0.08f, buttons)
    box(0.27f, 0.9f, 0.18f, 0.03f, 0.02f, Color(0xFF8D919C)); box(0.5f, 0.9f, 0.18f, 0.03f, 0.02f, Color(0xFF8D919C))
}

private fun DrawScope.drawGba() {
    val body = Color(0xFF4B3FB8)
    box(0.0f, 0.04f, 0.1f, 0.12f, 0.05f, body); box(0.9f, 0.04f, 0.1f, 0.12f, 0.05f, body)   // shoulder buttons
    box(0.01f, 0.12f, 0.98f, 0.8f, 0.24f, body)
    box(0.26f, 0.22f, 0.48f, 0.6f, 0.05f, Color(0xFF1B1D2B))
    box(0.31f, 0.29f, 0.38f, 0.46f, 0.02f, Color(0xFF8FB0D9))
    dpad(0.13f, 0.5f, 0.15f, 0.11f, Color(0xFF1E1B3A))
    dot(0.89f, 0.42f, 0.07f, Color(0xFFC9C3F5)); dot(0.79f, 0.58f, 0.07f, Color(0xFFC9C3F5))
}

enum class ToolIcon { CONTROLLER, LIBRARY, PERFORMANCE, SETTINGS }

@Composable
fun ToolIconView(icon: ToolIcon, tint: Color, iconSize: Dp = 40.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(iconSize)) {
        val s = Stroke(width = this.size.minDimension * 0.07f, cap = StrokeCap.Round)
        when (icon) {
            ToolIcon.CONTROLLER -> drawControllerOutline(tint, s, accent = tint)
            ToolIcon.LIBRARY -> {
                for (i in 0..2) {
                    val y = 0.62f - i * 0.2f
                    val p = Path().apply {
                        moveTo(fx(0.5f), fy(y - 0.2f)); lineTo(fx(0.92f), fy(y)); lineTo(fx(0.5f), fy(y + 0.2f)); lineTo(fx(0.08f), fy(y)); close()
                    }
                    if (i == 2) drawPath(p, tint.copy(alpha = 0.22f))
                    drawPath(p, tint, style = s)
                }
            }
            ToolIcon.PERFORMANCE -> {
                val w = 0.17f
                box(0.1f, 0.62f, w, 0.28f, 0.03f, tint); box(0.4f, 0.46f, w, 0.44f, 0.03f, tint); box(0.7f, 0.3f, w, 0.6f, 0.03f, tint)
                drawLine(tint, Offset(fx(0.08f), fy(0.4f)), Offset(fx(0.5f), fy(0.2f)), s.width, StrokeCap.Round)
                drawLine(tint, Offset(fx(0.5f), fy(0.2f)), Offset(fx(0.9f), fy(0.08f)), s.width, StrokeCap.Round)
            }
            ToolIcon.SETTINGS -> {
                val c = Offset(fx(0.5f), fy(0.5f))
                drawCircle(tint, size.minDimension * 0.2f, c, style = s)
                drawCircle(tint, size.minDimension * 0.33f, c, style = s)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val r1 = size.minDimension * 0.33f; val r2 = size.minDimension * 0.44f
                    drawLine(tint, Offset(c.x + (r1 * cos(a)).toFloat(), c.y + (r1 * sin(a)).toFloat()), Offset(c.x + (r2 * cos(a)).toFloat(), c.y + (r2 * sin(a)).toFloat()), s.width * 1.6f, StrokeCap.Round)
                }
            }
        }
    }
}

/** The logo / Controllers icon: a gamepad outline with coloured buttons. */
fun DrawScope.drawControllerOutline(line: Color, stroke: Stroke, accent: Color, colourful: Boolean = false) {
    val p = Path().apply {
        moveTo(fx(0.22f), fy(0.22f)); lineTo(fx(0.78f), fy(0.22f))
        cubicTo(fx(0.94f), fy(0.22f), fx(1.0f), fy(0.5f), fx(0.96f), fy(0.7f))
        cubicTo(fx(0.93f), fy(0.88f), fx(0.8f), fy(0.92f), fx(0.72f), fy(0.78f))
        lineTo(fx(0.66f), fy(0.68f)); lineTo(fx(0.34f), fy(0.68f)); lineTo(fx(0.28f), fy(0.78f))
        cubicTo(fx(0.2f), fy(0.92f), fx(0.07f), fy(0.88f), fx(0.04f), fy(0.7f))
        cubicTo(fx(0.0f), fy(0.5f), fx(0.06f), fy(0.22f), fx(0.22f), fy(0.22f)); close()
    }
    drawPath(p, line, style = stroke)
    dpad(0.27f, 0.45f, 0.09f, 0.07f, if (colourful) Color(0xFFFF3D8B) else accent)
    if (colourful) {
        dot(0.76f, 0.38f, 0.045f, Color(0xFF22D3EE)); dot(0.84f, 0.47f, 0.045f, Color(0xFFFFC857))
        dot(0.68f, 0.47f, 0.045f, Color(0xFF3DDC84)); dot(0.76f, 0.56f, 0.045f, Color(0xFFFF3D8B))
    } else {
        dot(0.76f, 0.4f, 0.04f, accent); dot(0.82f, 0.5f, 0.04f, accent)
    }
}

@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    Canvas(modifier) { drawControllerOutline(Color(0xFFEDEBFF), Stroke(width = size.minDimension * 0.08f, cap = StrokeCap.Round), Color(0xFFFF3D8B), colourful = true) }
}

/** True while an outer layer already paints the backdrop across the whole screen (so it also fills the TV safe-area margin). */
val LocalBackdropProvided = androidx.compose.runtime.compositionLocalOf { false }

/** Static dusk-sky backdrop: gradient, a low sun, layered ridges and a few stars. Drawn once, no images, cheap on a Mali-450. */
@Composable
fun Modifier.dashboardBackground(): Modifier = if (LocalBackdropProvided.current) this else this.fullBackdrop()

fun Modifier.fullBackdrop(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0F24), Color(0xFF111A3C), Color(0xFF1B1240))))
    drawCircle(Color(0xFFFF3D8B).copy(alpha = 0.16f), size.height * 0.16f, Offset(size.width * 0.84f, size.height * 0.2f))
    drawCircle(Color(0xFFFF8A5B).copy(alpha = 0.10f), size.height * 0.22f, Offset(size.width * 0.84f, size.height * 0.2f))
    val rnd = java.util.Random(7)
    repeat(34) {
        val a = 0.15f + rnd.nextFloat() * 0.35f
        drawRect(Color.White.copy(alpha = a), Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height * 0.45f), Size(2f, 2f))
    }
    fun ridge(base: Float, amp: Float, seed: Long, color: Color) {
        val r = java.util.Random(seed)
        val p = Path().apply {
            moveTo(0f, size.height)
            var x = 0f
            var y = size.height * base
            lineTo(0f, y)
            while (x < size.width) { x += size.width / 14f; y = size.height * base - r.nextFloat() * amp * size.height; lineTo(x, y) }
            lineTo(size.width, size.height); close()
        }
        drawPath(p, color)
    }
    ridge(0.72f, 0.16f, 11, Color(0xFF241B54).copy(alpha = 0.45f))
    ridge(0.82f, 0.12f, 23, Color(0xFF0E1230).copy(alpha = 0.7f))
}

enum class UiIcon { BACK, SEARCH, GRID, LIST, BIG, CHEVRON_DOWN }

@Composable
fun UiIconView(icon: UiIcon, tint: Color, iconSize: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(iconSize)) {
        val sw = this.size.minDimension * 0.1f
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        when (icon) {
            UiIcon.BACK -> {
                drawLine(tint, Offset(fx(0.9f), fy(0.5f)), Offset(fx(0.12f), fy(0.5f)), sw, StrokeCap.Round)
                drawLine(tint, Offset(fx(0.12f), fy(0.5f)), Offset(fx(0.48f), fy(0.14f)), sw, StrokeCap.Round)
                drawLine(tint, Offset(fx(0.12f), fy(0.5f)), Offset(fx(0.48f), fy(0.86f)), sw, StrokeCap.Round)
            }
            UiIcon.SEARCH -> {
                drawCircle(tint, size.minDimension * 0.3f, Offset(fx(0.42f), fy(0.42f)), style = st)
                drawLine(tint, Offset(fx(0.64f), fy(0.64f)), Offset(fx(0.9f), fy(0.9f)), sw, StrokeCap.Round)
            }
            UiIcon.GRID -> for (r in 0..1) for (c in 0..1) box(0.1f + c * 0.45f, 0.1f + r * 0.45f, 0.35f, 0.35f, 0.07f, tint)
            UiIcon.LIST -> for (r in 0..2) {
                dot(0.14f, 0.2f + r * 0.3f, 0.06f, tint)
                drawLine(tint, Offset(fx(0.32f), fy(0.2f + r * 0.3f)), Offset(fx(0.92f), fy(0.2f + r * 0.3f)), sw, StrokeCap.Round)
            }
            // Big cards: one large card with a picture area and a caption line.
            UiIcon.BIG -> {
                box(0.14f, 0.08f, 0.72f, 0.58f, 0.09f, tint)
                drawLine(tint, Offset(fx(0.14f), fy(0.84f)), Offset(fx(0.62f), fy(0.84f)), sw, StrokeCap.Round)
            }
            UiIcon.CHEVRON_DOWN -> {
                drawLine(tint, Offset(fx(0.15f), fy(0.35f)), Offset(fx(0.5f), fy(0.7f)), sw, StrokeCap.Round)
                drawLine(tint, Offset(fx(0.5f), fy(0.7f)), Offset(fx(0.85f), fy(0.35f)), sw, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun HeartIcon(filled: Boolean, tint: Color, iconSize: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(iconSize)) {
        val p = Path().apply {
            moveTo(fx(0.5f), fy(0.88f))
            cubicTo(fx(0.05f), fy(0.55f), fx(0.0f), fy(0.2f), fx(0.25f), fy(0.14f))
            cubicTo(fx(0.4f), fy(0.1f), fx(0.5f), fy(0.25f), fx(0.5f), fy(0.3f))
            cubicTo(fx(0.5f), fy(0.25f), fx(0.6f), fy(0.1f), fx(0.75f), fy(0.14f))
            cubicTo(fx(1.0f), fy(0.2f), fx(0.95f), fy(0.55f), fx(0.5f), fy(0.88f)); close()
        }
        if (filled) drawPath(p, tint) else drawPath(p, tint, style = Stroke(width = size.minDimension * 0.09f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** Physical parts of the drawn PlayStation-style pad. */
enum class PadPart { UP, DOWN, LEFT, RIGHT, TRIANGLE, CIRCLE, CROSS, SQUARE, L1, R1, L2, R2, L3, R3, SHARE, OPTIONS, PS }

/** The pad part an Android key code belongs to when pressed on a PlayStation-layout controller (Cross = BUTTON_A ...). */
fun padPartOfKey(code: Int): PadPart? = when (code) {
    android.view.KeyEvent.KEYCODE_BUTTON_A -> PadPart.CROSS; android.view.KeyEvent.KEYCODE_BUTTON_B -> PadPart.CIRCLE
    android.view.KeyEvent.KEYCODE_BUTTON_X -> PadPart.SQUARE; android.view.KeyEvent.KEYCODE_BUTTON_Y -> PadPart.TRIANGLE
    android.view.KeyEvent.KEYCODE_BUTTON_L1 -> PadPart.L1; android.view.KeyEvent.KEYCODE_BUTTON_R1 -> PadPart.R1
    android.view.KeyEvent.KEYCODE_BUTTON_L2 -> PadPart.L2; android.view.KeyEvent.KEYCODE_BUTTON_R2 -> PadPart.R2
    android.view.KeyEvent.KEYCODE_BUTTON_THUMBL -> PadPart.L3; android.view.KeyEvent.KEYCODE_BUTTON_THUMBR -> PadPart.R3
    android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> PadPart.SHARE; android.view.KeyEvent.KEYCODE_BUTTON_START -> PadPart.OPTIONS
    android.view.KeyEvent.KEYCODE_BUTTON_MODE -> PadPart.PS
    android.view.KeyEvent.KEYCODE_DPAD_UP -> PadPart.UP; android.view.KeyEvent.KEYCODE_DPAD_DOWN -> PadPart.DOWN
    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> PadPart.LEFT; android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> PadPart.RIGHT
    else -> null
}

/** Which drawn part a RetroPad action is in the default PlayStation mapping (RetroPad A = the right button = Circle). */
fun padPartOfAction(b: PadButton): PadPart? = when (b) {
    PadButton.A -> PadPart.CIRCLE; PadButton.B -> PadPart.CROSS; PadButton.X -> PadPart.TRIANGLE; PadButton.Y -> PadPart.SQUARE
    PadButton.L -> PadPart.L1; PadButton.R -> PadPart.R1; PadButton.L2 -> PadPart.L2; PadButton.R2 -> PadPart.R2
    PadButton.START -> PadPart.OPTIONS; PadButton.SELECT -> PadPart.SHARE
    PadButton.UP -> PadPart.UP; PadButton.DOWN -> PadPart.DOWN; PadButton.LEFT -> PadPart.LEFT; PadButton.RIGHT -> PadPart.RIGHT
    PadButton.L3 -> PadPart.L3; PadButton.R3 -> PadPart.R3
}

/**
 * Large neon-outline gamepad (flat dark body, glowing purple-to-blue outline, ring sticks, outlined D-pad, lettered colour
 * buttons). Positions follow the pad layout (top / right / bottom / left); the letters are the emulator's action names
 * (X top, A right, B bottom, Y left), so the picture agrees with the table next to it. [lit] parts turn pink (the action
 * being edited, or keys pressed live); sticks follow [stickL]/[stickR] (-1..1) and the bumpers tint with [triggerL]/[triggerR].
 * Drawn in code, no image files. Best at an aspect ratio of about 1.9.
 */
@Composable
fun ControllerIllustration(
    lit: Set<PadPart>,
    modifier: Modifier = Modifier,
    stickL: Offset = Offset.Zero, stickR: Offset = Offset.Zero, triggerL: Float = 0f, triggerR: Float = 0f,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(modifier) {
        val w = size.width; val u = size.height
        fun px(x: Float) = x * w
        fun py(y: Float) = y * u
        val line = Color(0xFFB4B8F0); val pink = Color(0xFFFF3D8B); val dark = Color(0xFF080D26)
        fun on(p: PadPart) = p in lit
        val outline = Brush.verticalGradient(listOf(Color(0xFFA77BFF), Color(0xFF7A7CFF), Color(0xFF2F8BFF)), startY = 0f, endY = u)
        val sw = u * 0.02f

        // bumpers: outlined tabs behind the top corners
        fun bumper(x0: Float, part: PadPart, pull: Float) {
            val tl = Offset(px(x0), py(0.035f)); val sz = Size(px(0.17f), py(0.13f))
            drawRoundRect(dark, tl, sz, CornerRadius(u * 0.06f))
            if (pull > 0.05f) drawRoundRect(pink.copy(alpha = 0.6f * pull), tl, sz, CornerRadius(u * 0.06f))
            if (on(part)) drawRoundRect(pink.copy(alpha = 0.55f), tl, sz, CornerRadius(u * 0.06f))
            if (on(part)) drawRoundRect(pink, tl, sz, CornerRadius(u * 0.06f), style = Stroke(sw)) else drawRoundRect(outline, tl, sz, CornerRadius(u * 0.06f), style = Stroke(sw))
        }
        bumper(0.17f, PadPart.L1, triggerL); bumper(0.66f, PadPart.R1, triggerR)

        // body silhouette: broad flat top, long sloping grips, shallow arch between them
        val body = Path().apply {
            moveTo(px(0.22f), py(0.12f))
            quadraticTo(px(0.5f), py(0.145f), px(0.78f), py(0.12f))
            cubicTo(px(0.88f), py(0.12f), px(0.93f), py(0.22f), px(0.95f), py(0.38f))
            cubicTo(px(0.99f), py(0.55f), px(0.99f), py(0.78f), px(0.96f), py(0.9f))
            cubicTo(px(0.94f), py(0.98f), px(0.84f), py(0.99f), px(0.8f), py(0.9f))
            cubicTo(px(0.76f), py(0.82f), px(0.72f), py(0.77f), px(0.66f), py(0.77f))
            lineTo(px(0.34f), py(0.77f))
            cubicTo(px(0.28f), py(0.77f), px(0.24f), py(0.82f), px(0.2f), py(0.9f))
            cubicTo(px(0.16f), py(0.99f), px(0.06f), py(0.98f), px(0.04f), py(0.9f))
            cubicTo(px(0.01f), py(0.78f), px(0.01f), py(0.55f), px(0.05f), py(0.38f))
            cubicTo(px(0.07f), py(0.22f), px(0.12f), py(0.12f), px(0.22f), py(0.12f)); close()
        }
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFF111A44), dark), startY = 0f, endY = u))
        // neon glow, outline, faint inner line
        drawPath(body, outline, alpha = 0.07f, style = Stroke(u * 0.09f))
        drawPath(body, outline, alpha = 0.14f, style = Stroke(u * 0.05f))
        drawPath(body, outline, style = Stroke(sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        scale(0.955f, pivot = Offset(px(0.5f), py(0.5f))) {
            drawPath(body, outline, alpha = 0.22f, style = Stroke(u * 0.008f))
        }

        // D-pad: outlined cross
        val dc = Offset(px(0.255f), py(0.42f)); val arm = u * 0.15f; val th = u * 0.1f
        val cross = Path().apply {
            moveTo(dc.x - th / 2, dc.y - arm); lineTo(dc.x + th / 2, dc.y - arm); lineTo(dc.x + th / 2, dc.y - th / 2); lineTo(dc.x + arm, dc.y - th / 2)
            lineTo(dc.x + arm, dc.y + th / 2); lineTo(dc.x + th / 2, dc.y + th / 2); lineTo(dc.x + th / 2, dc.y + arm); lineTo(dc.x - th / 2, dc.y + arm)
            lineTo(dc.x - th / 2, dc.y + th / 2); lineTo(dc.x - arm, dc.y + th / 2); lineTo(dc.x - arm, dc.y - th / 2); lineTo(dc.x - th / 2, dc.y - th / 2); close()
        }
        drawPath(cross, Color(0xFF141C4D)); drawPath(cross, line, style = Stroke(sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        fun armLit(x: Float, y: Float, wd: Float, ht: Float) { drawRect(pink.copy(alpha = 0.85f), Offset(x, y), Size(wd, ht)) }
        if (on(PadPart.UP)) armLit(dc.x - th / 2 + sw, dc.y - arm + sw, th - 2 * sw, arm - th / 2 - sw)
        if (on(PadPart.DOWN)) armLit(dc.x - th / 2 + sw, dc.y + th / 2, th - 2 * sw, arm - th / 2 - sw)
        if (on(PadPart.LEFT)) armLit(dc.x - arm + sw, dc.y - th / 2 + sw, arm - th / 2 - sw, th - 2 * sw)
        if (on(PadPart.RIGHT)) armLit(dc.x + th / 2, dc.y - th / 2 + sw, arm - th / 2 - sw, th - 2 * sw)
        drawCircle(line, u * 0.008f, dc)

        // Select / Start pills
        fun pill(x: Float, part: PadPart) {
            val tl = Offset(px(x), py(0.395f)); val sz = Size(px(0.045f), py(0.04f))
            if (on(part)) drawRoundRect(pink.copy(alpha = 0.45f), Offset(tl.x - 5f, tl.y - 5f), Size(sz.width + 10f, sz.height + 10f), CornerRadius(u * 0.03f))
            drawRoundRect(dark, tl, sz, CornerRadius(u * 0.02f))
            drawRoundRect(if (on(part)) pink else line, tl, sz, CornerRadius(u * 0.02f), style = Stroke(sw * 0.8f))
        }
        pill(0.43f, PadPart.SHARE); pill(0.525f, PadPart.OPTIONS)

        // face buttons: flat colour discs with their letters
        val fc = Offset(px(0.78f), py(0.4f)); val off = u * 0.16f; val br = u * 0.072f
        fun disc(c: Offset, part: PadPart, color: Color, letter: String) {
            val l = on(part)
            if (l) { drawCircle(pink.copy(alpha = 0.22f), br * 2.0f, c); drawCircle(pink.copy(alpha = 0.4f), br * 1.5f, c) }
            drawCircle(color, br, c)
            drawCircle(Color.White.copy(alpha = 0.25f), br * 0.9f, Offset(c.x - br * 0.1f, c.y - br * 0.12f), style = Stroke(u * 0.006f))
            if (l) drawCircle(Color.White, br * 1.12f, c, style = Stroke(u * 0.016f))
            val layout = measurer.measure(letter, androidx.compose.ui.text.TextStyle(color = Color(0xFF0B1024), fontSize = (br * 1.1f).toSp(), fontWeight = FontWeight.ExtraBold))
            drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
        }
        disc(Offset(fc.x, fc.y - off), PadPart.TRIANGLE, Color(0xFF4DA3FF), "X")
        disc(Offset(fc.x + off, fc.y), PadPart.CIRCLE, Color(0xFF3DDC84), "A")
        disc(Offset(fc.x, fc.y + off), PadPart.CROSS, Color(0xFFFF4D6D), "B")
        disc(Offset(fc.x - off, fc.y), PadPart.SQUARE, Color(0xFFFFC857), "Y")

        // sticks: rings, caps follow the axes
        fun stick(c: Offset, part: PadPart, d: Offset) {
            val r = u * 0.108f
            val l = on(part)
            drawCircle(dark, r * 1.15f, c)
            drawCircle(if (l) pink else line, r * 1.15f, c, style = Stroke(sw))
            val cc = Offset(c.x + d.x * r * 0.4f, c.y + d.y * r * 0.4f)
            drawCircle(Color(0xFF141C4D), r * 0.82f, cc)
            drawCircle(if (l) pink else line.copy(alpha = 0.85f), r * 0.82f, cc, style = Stroke(sw * 0.9f))
            drawCircle(line.copy(alpha = 0.35f), r * 0.4f, cc, style = Stroke(sw * 0.5f))
        }
        stick(Offset(px(0.4f), py(0.585f)), PadPart.L3, stickL); stick(Offset(px(0.6f), py(0.585f)), PadPart.R3, stickR)
        val hc = Offset(px(0.5f), py(0.6f))
        drawRoundRect(if (on(PadPart.PS)) pink else line.copy(alpha = 0.7f), Offset(hc.x - px(0.012f), hc.y - u * 0.006f), Size(px(0.024f), u * 0.012f), CornerRadius(4f))
    }
}
