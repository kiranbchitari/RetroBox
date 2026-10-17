@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.retrobox.tv.save.SlotInfo
import com.retrobox.tv.settings.AudioSettings
import com.retrobox.tv.settings.GraphicsPreset
import com.retrobox.tv.settings.GraphicsSettings
import com.retrobox.tv.settings.ScaleMode
import com.retrobox.tv.settings.ShaderMode
import com.retrobox.tv.settings.cycled
import com.retrobox.tv.settings.stepped
import com.retrobox.tv.ui.MenuDivider
import com.retrobox.tv.ui.MenuGlyph
import com.retrobox.tv.ui.MenuHeader
import com.retrobox.tv.ui.MenuNote
import com.retrobox.tv.ui.MenuPanel
import com.retrobox.tv.ui.MenuRow
import com.retrobox.tv.ui.MenuValueRow
import com.retrobox.tv.ui.RB
import com.retrobox.tv.emulator.FastForward
import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.emulator.Turbo
import java.text.DateFormat
import java.util.Date

sealed interface MenuPage {
    data object Main : MenuPage
    data object SaveSlots : MenuPage
    data object LoadSlots : MenuPage
    data class ConfirmOverwrite(val slot: Int) : MenuPage
    data object ConfirmReset : MenuPage
    data object ConfirmQuit : MenuPage
    data object Graphics : MenuPage
    data object Audio : MenuPage
    data object Players : MenuPage
    data object Speed : MenuPage
    data class Error(val message: String) : MenuPage
}

data class MenuActions(
    val resume: () -> Unit,
    val open: (MenuPage) -> Unit,
    val saveTo: (Int) -> Unit,
    val loadFrom: (Int) -> Unit,
    val reset: () -> Unit,
    val quit: () -> Unit,
    val setGraphics: (GraphicsSettings) -> Unit,   // saved for this game's system
    val resetGraphics: () -> Unit,                 // drop this system's exceptions, follow the defaults again
    val setSafeArea: (Int) -> Unit,                // whole percent per side, applies everywhere
    val setOverlay: (Boolean) -> Unit,
    val setAudio: (AudioSettings) -> Unit,
    val swapPlayers: () -> Unit,
    val setFastForward: (Boolean) -> Unit,    // switched on until switched off
    val setFastForwardSpeed: (Int) -> Unit,   // used both when switched on and while R2 / Space is held
    val setTurbo: (Set<PadButton>) -> Unit,   // saved for this game's system
)

/** What the Graphics / Audio pages show. [shaderProblem] is the renderer's own message when a look could not be built on this GPU. */
data class PauseSettings(
    val graphics: GraphicsSettings = GraphicsSettings(),
    val usesSystemOverride: Boolean = false,
    val safeAreaPct: Int = 0,
    val overlay: Boolean = false,
    val audio: AudioSettings = AudioSettings(),
    val shaderProblem: String? = null,
    val audioNote: String = "",
    /** 1 for one-player systems; the Players row and page only exist above that. */
    val players: Int = 1,
    /** What drives each player right now ("Xbox Wireless Controller", "no controller yet", …). */
    val playerNames: List<String> = emptyList(),
    /** Player 2 has its own button layout at the all-systems, system or game level. */
    val player2Custom: Boolean = false,
    /** The status line is a problem (e.g. a controller was unplugged), not a confirmation. */
    val statusWarning: Boolean = false,
    /** Fast-forward switched on from this menu (stays on until switched off). */
    val fastForwardOn: Boolean = false,
    val fastForwardSpeed: Int = FastForward.DEFAULT,
    val turbo: Set<PadButton> = emptySet(),
    val turboChoices: List<Set<PadButton>> = listOf(emptySet()),
    /** For button names in the Turbo row (a system may call a RetroPad button something else). */
    val systemId: String = "",
)

private val Blue = Color(0xFF6D8CFF)
private val Pinkish = Color(0xFFFF4D6D)

/**
 * The in-game pause menu and its sub-pages (save / load slots, confirmations, errors), all in the same format:
 * header with the game's picture, title, system and a "Game Paused" badge, then icon rows with chevrons and ‹ value › settings.
 */
@Composable
fun PauseMenu(
    page: MenuPage,
    title: String,
    systemName: String,
    thumb: ImageBitmap?,
    infoLine: String,
    status: String?,
    slots: List<SlotInfo>,
    settings: PauseSettings,
    a: MenuActions,
) {
    val first = remember(page) { FocusRequester() }
    LaunchedEffect(page) { runCatching { first.requestFocus() } }
    val headerBadge = if (page == MenuPage.Main) "Game Paused" else null
    MenuPanel {
        MenuHeader(title, systemName, headerBadge, thumb, subtitle = if (page == MenuPage.Main) infoLine.substringBefore(" · run avg") else null)
        if (status != null) MenuNote(status, if (settings.statusWarning) RB.Warn else RB.Good)
        when (page) {
            MenuPage.Main -> {
                MenuRow(MenuGlyph.PLAY, Color.White, "Resume Game", Modifier.focusRequester(first), onClick = a.resume)
                MenuRow(MenuGlyph.SAVE, Blue, "Save State", onClick = { a.open(MenuPage.SaveSlots) })
                MenuRow(MenuGlyph.LOAD, Color(0xFF8FB4FF), "Load State", onClick = { a.open(MenuPage.LoadSlots) })
                MenuRow(MenuGlyph.RESET, Pinkish, "Reset Game", onClick = { a.open(MenuPage.ConfirmReset) })
                MenuDivider()
                MenuRow(MenuGlyph.SCREEN, Color(0xFF8E9BFF), "Graphics", onClick = { a.open(MenuPage.Graphics) })
                MenuRow(MenuGlyph.SPEAKER, Color(0xFF3DDC84), "Audio", onClick = { a.open(MenuPage.Audio) })
                MenuRow(MenuGlyph.PLAY, Color(0xFFFFC857), "Speed & Turbo", onClick = { a.open(MenuPage.Speed) })
                if (settings.players > 1) MenuRow(MenuGlyph.GAMEPAD, Color(0xFFFFC857), "Players", onClick = { a.open(MenuPage.Players) })
                MenuDivider()
                MenuRow(MenuGlyph.EXIT, Color(0xFFB8C4FF), "Quit to Menu", onClick = { a.open(MenuPage.ConfirmQuit) })
            }
            MenuPage.Graphics -> {
                val g = settings.graphics
                val set = a.setGraphics
                MenuValueRow(MenuGlyph.SPARKLE, Color(0xFFFFC857), "Preset", GraphicsPreset.matching(g)?.label ?: "Custom", Modifier.focusRequester(first),
                    onPrev = { set(GraphicsPreset.entries.map { it.graphics }.cycled(g, -1)) }, onNext = { set(GraphicsPreset.entries.map { it.graphics }.cycled(g, 1)) })
                MenuValueRow(MenuGlyph.SCREEN, Color(0xFF8E9BFF), "Screen Aspect", g.scale.label,
                    onPrev = { set(g.copy(scale = ScaleMode.entries.cycled(g.scale, -1))) }, onNext = { set(g.copy(scale = ScaleMode.entries.cycled(g.scale, 1))) })
                MenuValueRow(MenuGlyph.SPARKLE, Color(0xFFA78BFA), "Pixels", if (g.effectiveBilinear) "Smooth" else "Sharp",
                    onPrev = { set(g.copy(bilinear = !g.bilinear)) }, onNext = { set(g.copy(bilinear = !g.bilinear)) })
                MenuValueRow(MenuGlyph.SPARKLE, Color(0xFFFF8AD8), "Look", g.shader.label,
                    onPrev = { set(g.copy(shader = ShaderMode.entries.cycled(g.shader, -1))) }, onNext = { set(g.copy(shader = ShaderMode.entries.cycled(g.shader, 1))) })
                MenuValueRow(MenuGlyph.SCREEN, Color(0xFF6DD3C0), "Safe Area", if (settings.safeAreaPct == 0) "Off" else "${settings.safeAreaPct}%",
                    onPrev = { a.setSafeArea((settings.safeAreaPct - 1).coerceAtLeast(0)) }, onNext = { a.setSafeArea((settings.safeAreaPct + 1).coerceAtMost(com.retrobox.tv.settings.AppSettings.MAX_SAFE_AREA_PCT)) })
                MenuValueRow(MenuGlyph.PAUSE, Color(0xFFB8C4FF), "FPS Overlay", if (settings.overlay) "On" else "Off",
                    onPrev = { a.setOverlay(!settings.overlay) }, onNext = { a.setOverlay(!settings.overlay) })
                settings.shaderProblem?.let { MenuNote("This GPU could not build that look, so the plain picture is shown: $it", RB.Warn) }
                MenuNote("Aspect, pixels and look are saved for $systemName. Safe area and the overlay apply to every system.")
                if (settings.usesSystemOverride) MenuRow(MenuGlyph.RESET, Pinkish, "Use defaults for $systemName", chevron = false, onClick = a.resetGraphics)
                MenuDivider()
                MenuRow(MenuGlyph.BACK, Color(0xFFB8C4FF), "Back", chevron = false, onClick = { a.open(MenuPage.Main) })
            }
            MenuPage.Speed -> {
                MenuValueRow(MenuGlyph.PLAY, Color(0xFFFFC857), "Fast-forward", if (settings.fastForwardOn) "On" else "Off", Modifier.focusRequester(first),
                    onPrev = { a.setFastForward(!settings.fastForwardOn) }, onNext = { a.setFastForward(!settings.fastForwardOn) })
                MenuValueRow(MenuGlyph.PAUSE, Color(0xFFB8C4FF), "Speed", FastForward.label(settings.fastForwardSpeed),
                    onPrev = { a.setFastForwardSpeed(FastForward.choices.cycled(settings.fastForwardSpeed, -1)) },
                    onNext = { a.setFastForwardSpeed(FastForward.choices.cycled(settings.fastForwardSpeed, 1)) })
                MenuNote("Hold R2 on the controller (or Space on a keyboard) to fast-forward while it is held, or switch it on here: it then stays on until you switch it off. Sound is muted while fast. Max runs as fast as this TV can.")
                if (settings.turboChoices.size > 1) {
                    MenuValueRow(MenuGlyph.SPARKLE, Color(0xFFFF8AD8), "Turbo", Turbo.label(settings.turbo, com.retrobox.tv.emulator.Systems.byId(settings.systemId)),
                        onPrev = { a.setTurbo(settings.turboChoices.cycled(settings.turbo, -1)) }, onNext = { a.setTurbo(settings.turboChoices.cycled(settings.turbo, 1)) })
                    MenuNote("Turbo buttons fire about 10 times a second while held. Saved for $systemName.")
                }
                MenuDivider()
                MenuRow(MenuGlyph.BACK, Color(0xFFB8C4FF), "Back", chevron = false, onClick = { a.open(MenuPage.Main) })
            }
            MenuPage.Players -> {
                settings.playerNames.forEachIndexed { i, n -> MenuNote("Player ${i + 1}: $n", RB.Text) }
                MenuRow(MenuGlyph.RESET, Color(0xFFFFC857), "Swap Player 1 and 2", Modifier.focusRequester(first), chevron = false, onClick = a.swapPlayers)
                MenuNote("The first controller connected is Player 1, the second is Player 2; a controller that connects later takes the next free player. The keyboard and TV remote always play as Player 1.")
                MenuNote("Player 2's buttons: " + if (settings.player2Custom) "custom (set in Controllers → Edit → Player 2)." else "the same as Player 1 (change in Controllers → Edit → Player 2).")
                MenuDivider()
                MenuRow(MenuGlyph.BACK, Color(0xFFB8C4FF), "Back", chevron = false, onClick = { a.open(MenuPage.Main) })
            }
            MenuPage.Audio -> {
                val au = settings.audio
                MenuValueRow(MenuGlyph.SPEAKER, Color(0xFF3DDC84), "Sound", if (au.enabled) "On" else "Off", Modifier.focusRequester(first),
                    onPrev = { a.setAudio(au.copy(enabled = !au.enabled)) }, onNext = { a.setAudio(au.copy(enabled = !au.enabled)) })
                MenuValueRow(MenuGlyph.SPEAKER, Color(0xFF6DD3C0), "Volume", "${au.volumePct}%",
                    onPrev = { a.setAudio(au.copy(volumePct = AudioSettings.volumeChoices.stepped(au.volumePct, -1))) },
                    onNext = { a.setAudio(au.copy(volumePct = AudioSettings.volumeChoices.stepped(au.volumePct, 1))) })
                MenuValueRow(MenuGlyph.PAUSE, Color(0xFFB8C4FF), "Audio Buffer", bufferLabel(au.bufferMs),
                    onPrev = { a.setAudio(au.copy(bufferMs = AudioSettings.bufferChoices.cycled(au.bufferMs, -1))) },
                    onNext = { a.setAudio(au.copy(bufferMs = AudioSettings.bufferChoices.cycled(au.bufferMs, 1))) })
                MenuNote("A smaller buffer (50 ms) means less delay between a button press and its sound; a larger one (300 ms) avoids crackling on a slow TV. 100 ms is the default. Sound stops while the menu is open and fades in on resume.")
                if (settings.audioNote.isNotEmpty()) MenuNote(settings.audioNote)
                MenuDivider()
                MenuRow(MenuGlyph.BACK, Color(0xFFB8C4FF), "Back", chevron = false, onClick = { a.open(MenuPage.Main) })
            }
            MenuPage.SaveSlots, MenuPage.LoadSlots -> {
                val saving = page == MenuPage.SaveSlots
                MenuNote(if (saving) "Save State: choose a slot" else "Load State: choose a slot")
                slots.forEachIndexed { i, s ->
                    val label = "Slot ${s.slot}: " + when {
                        s.corrupt -> "damaged file"
                        s.meta == null -> "empty"
                        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.meta.timestampMs))
                    }
                    val enabled = saving || (s.meta != null)
                    MenuRow(if (saving) MenuGlyph.SAVE else MenuGlyph.LOAD, if (saving) Blue else Color(0xFF8FB4FF), label, if (i == 0) Modifier.focusRequester(first) else Modifier, enabled = enabled || i == 0, onClick = {
                        if (enabled) {
                            if (saving) { if (s.meta != null || s.corrupt) a.open(MenuPage.ConfirmOverwrite(s.slot)) else a.saveTo(s.slot) }
                            else a.loadFrom(s.slot)
                        }
                    })
                }
                MenuDivider()
                MenuRow(MenuGlyph.BACK, Color(0xFFB8C4FF), "Back", chevron = false, onClick = { a.open(MenuPage.Main) })
            }
            is MenuPage.ConfirmOverwrite -> {
                MenuNote("Slot ${page.slot} already has data. Replace it?", RB.Text)
                MenuRow(MenuGlyph.CLOSE, Color(0xFFB8C4FF), "No, keep it", Modifier.focusRequester(first), chevron = false, onClick = { a.open(MenuPage.SaveSlots) })
                MenuRow(MenuGlyph.SAVE, Blue, "Yes, overwrite slot ${page.slot}", chevron = false, onClick = { a.saveTo(page.slot) })
            }
            MenuPage.ConfirmReset -> {
                MenuNote("Reset the game? Unsaved progress is lost.", RB.Text)
                MenuRow(MenuGlyph.CLOSE, Color(0xFFB8C4FF), "No, go back", Modifier.focusRequester(first), chevron = false, onClick = { a.open(MenuPage.Main) })
                MenuRow(MenuGlyph.RESET, Pinkish, "Yes, reset", chevron = false, onClick = a.reset)
            }
            MenuPage.ConfirmQuit -> {
                MenuNote("Quit? Cartridge saves and your session (Quick Resume) are saved automatically; save states in slots are not changed.", RB.Text)
                MenuRow(MenuGlyph.CLOSE, Color(0xFFB8C4FF), "No, go back", Modifier.focusRequester(first), chevron = false, onClick = { a.open(MenuPage.Main) })
                MenuRow(MenuGlyph.EXIT, Pinkish, "Yes, quit", chevron = false, onClick = a.quit)
            }
            is MenuPage.Error -> {
                MenuNote(page.message, RB.Warn)
                MenuRow(MenuGlyph.CHECK, Color(0xFF3DDC84), "OK", Modifier.focusRequester(first), chevron = false, onClick = a.quit)
            }
        }
    }
}

internal fun bufferLabel(ms: Int) = "$ms ms"
