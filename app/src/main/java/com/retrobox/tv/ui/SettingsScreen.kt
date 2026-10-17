@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.retrobox.tv.emulator.Systems
import com.retrobox.tv.game.bufferLabel
import com.retrobox.tv.settings.AppSettings
import com.retrobox.tv.settings.AudioSettings
import com.retrobox.tv.settings.GraphicsOverride
import com.retrobox.tv.settings.GraphicsPreset
import com.retrobox.tv.settings.ScaleMode
import com.retrobox.tv.settings.ShaderMode
import com.retrobox.tv.settings.cycled
import com.retrobox.tv.settings.stepped

private fun describe(o: GraphicsOverride): String = listOfNotNull(
    o.scale?.let { "aspect ${it.label}" }, o.bilinear?.let { if (it) "smooth pixels" else "sharp pixels" }, o.shader?.let { "look ${it.label}" },
).joinToString(", ")

/**
 * Global defaults for graphics, audio and the TV safe area. A game's own tweaks (made in its pause menu) are listed at the
 * bottom as per-system exceptions. [onChange] receives a function so callers apply it to the newest settings and save them.
 */
@Composable
fun SettingsScreen(settings: AppSettings, loadProblem: String?, onChange: ((AppSettings) -> AppSettings) -> Unit, onBack: () -> Unit, onAbout: () -> Unit = {}) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    var confirmReset by remember { mutableStateOf(false) }
    val g = settings.graphics
    val au = settings.audio

    Box(Modifier.fillMaxSize().dashboardBackground()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("SETTINGS", "Defaults for every system. Changes apply and save as you make them.", onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.width(640.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (loadProblem != null) MenuNote(loadProblem, RB.Warn)

                    Heading("GRAPHICS DEFAULTS", RB.Pink)
                    MenuValueRow(MenuGlyph.SPARKLE, Color(0xFFFFC857), "Preset", GraphicsPreset.matching(g)?.label ?: "Custom", Modifier.focusRequester(first),
                        onPrev = { onChange { it.copy(graphics = GraphicsPreset.entries.map { p -> p.graphics }.cycled(it.graphics, -1)) } },
                        onNext = { onChange { it.copy(graphics = GraphicsPreset.entries.map { p -> p.graphics }.cycled(it.graphics, 1)) } })
                    MenuValueRow(MenuGlyph.SCREEN, Color(0xFF8E9BFF), "Screen Aspect", g.scale.label,
                        onPrev = { onChange { it.copy(graphics = it.graphics.copy(scale = ScaleMode.entries.cycled(it.graphics.scale, -1))) } },
                        onNext = { onChange { it.copy(graphics = it.graphics.copy(scale = ScaleMode.entries.cycled(it.graphics.scale, 1))) } })
                    MenuValueRow(MenuGlyph.SPARKLE, Color(0xFFA78BFA), "Pixels", if (g.effectiveBilinear) "Smooth" else "Sharp",
                        onPrev = { onChange { it.copy(graphics = it.graphics.copy(bilinear = !it.graphics.bilinear)) } },
                        onNext = { onChange { it.copy(graphics = it.graphics.copy(bilinear = !it.graphics.bilinear)) } })
                    MenuValueRow(MenuGlyph.SPARKLE, Color(0xFFFF8AD8), "Look", g.shader.label,
                        onPrev = { onChange { it.copy(graphics = it.graphics.copy(shader = ShaderMode.entries.cycled(it.graphics.shader, -1))) } },
                        onNext = { onChange { it.copy(graphics = it.graphics.copy(shader = ShaderMode.entries.cycled(it.graphics.shader, 1))) } })
                    MenuNote(GraphicsPreset.matching(g)?.blurb ?: "Your own mix of the options above.")
                    MenuNote("Looks are small GPU programs; if one cannot be built on this TV the plain picture is shown and Performance explains why. Scanlines, CRT and LCD grid look best with Integer or a screen at least twice the game's height.")

                    Heading("TV SCREEN", RB.Cyan)
                    MenuValueRow(MenuGlyph.SCREEN, Color(0xFF6DD3C0), "Safe Area", if (settings.safeAreaPct == 0) "Off" else "${settings.safeAreaPct}%",
                        onPrev = { onChange { it.copy(safeAreaPct = (it.safeAreaPct - 1).coerceAtLeast(0)) } },
                        onNext = { onChange { it.copy(safeAreaPct = (it.safeAreaPct + 1).coerceAtMost(AppSettings.MAX_SAFE_AREA_PCT)) } })
                    MenuNote("Raise this if your TV crops the edges (overscan): this page and the game picture shrink away from the edges as you change it.")
                    MenuValueRow(MenuGlyph.PAUSE, Color(0xFFB8C4FF), "FPS Overlay", if (settings.overlay) "On" else "Off",
                        onPrev = { onChange { it.copy(overlay = !it.overlay) } }, onNext = { onChange { it.copy(overlay = !it.overlay) } })
                    MenuNote("Shows frame rate and timings during play. Measuring GPU time makes each frame slightly slower, so leave it off for normal play.")

                    Heading("PLAY", RB.Pink)
                    MenuValueRow(MenuGlyph.PLAY, Color(0xFFFFC857), "Fast-forward speed", com.retrobox.tv.emulator.FastForward.label(settings.fastForwardSpeed),
                        onPrev = { onChange { it.copy(fastForwardSpeed = com.retrobox.tv.emulator.FastForward.choices.cycled(it.fastForwardSpeed, -1)) } },
                        onNext = { onChange { it.copy(fastForwardSpeed = com.retrobox.tv.emulator.FastForward.choices.cycled(it.fastForwardSpeed, 1)) } })
                    MenuNote("Hold R2 (or Space on a keyboard) during a game to fast-forward; the pause menu's Speed & Turbo page can also switch it on and set auto-fire buttons per system.")

                    Heading("AUDIO", RB.Good)
                    MenuValueRow(MenuGlyph.SPEAKER, Color(0xFF3DDC84), "Sound", if (au.enabled) "On" else "Off",
                        onPrev = { onChange { it.copy(audio = it.audio.copy(enabled = !it.audio.enabled)) } }, onNext = { onChange { it.copy(audio = it.audio.copy(enabled = !it.audio.enabled)) } })
                    MenuValueRow(MenuGlyph.SPEAKER, Color(0xFF6DD3C0), "Volume", "${au.volumePct}%",
                        onPrev = { onChange { it.copy(audio = it.audio.copy(volumePct = AudioSettings.volumeChoices.stepped(it.audio.volumePct, -1))) } },
                        onNext = { onChange { it.copy(audio = it.audio.copy(volumePct = AudioSettings.volumeChoices.stepped(it.audio.volumePct, 1))) } })
                    MenuValueRow(MenuGlyph.PAUSE, Color(0xFFB8C4FF), "Audio Buffer", bufferLabel(au.bufferMs),
                        onPrev = { onChange { it.copy(audio = it.audio.copy(bufferMs = AudioSettings.bufferChoices.cycled(it.audio.bufferMs, -1))) } },
                        onNext = { onChange { it.copy(audio = it.audio.copy(bufferMs = AudioSettings.bufferChoices.cycled(it.audio.bufferMs, 1))) } })
                    MenuNote("A smaller buffer (50 ms) means less delay between a button press and its sound; a larger one (300 ms) avoids crackling on a slow TV. 100 ms is the default. Sound is cut while a game is paused and fades in when it resumes.")

                    Heading("PER-SYSTEM EXCEPTIONS", RB.Pink)
                    val overrides = settings.perSystem.filterValues { !it.isEmpty }
                    if (overrides.isEmpty()) MenuNote("None. Change Graphics from a game's pause menu to give that system its own look; it will be listed here.")
                    overrides.forEach { (id, o) ->
                        val name = Systems.byId(id)?.displayName ?: id
                        MenuRow(MenuGlyph.RESET, Color(0xFFFF4D6D), "$name: ${describe(o)}", chevron = false, onClick = { onChange { it.withoutSystemOverride(id) } })
                    }
                    if (overrides.isNotEmpty()) MenuNote("Select one to remove its exception and follow the defaults again.")

                    Heading("ABOUT", RB.Cyan)
                    MenuRow(MenuGlyph.LOAD, Color(0xFF8E9BFF), "About & licenses", onClick = onAbout)

                    Heading("RESET", RB.Muted)
                    MenuRow(MenuGlyph.RESET, Color(0xFFFF4D6D), if (confirmReset) "Press again to reset every setting" else "Reset all settings", chevron = false, onClick = {
                        if (confirmReset) { onChange { AppSettings() }; confirmReset = false } else confirmReset = true
                    })
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String, color: Color) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = color, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 12.dp, bottom = 2.dp))
}
