@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.emulator.Systems
import com.retrobox.tv.game.DefaultMappings
import com.retrobox.tv.input.ControllerMonitor
import com.retrobox.tv.profile.ProfileLayer
import com.retrobox.tv.profile.ProfileResolver
import com.retrobox.tv.profile.ProfileScope
import com.retrobox.tv.profile.ProfileSet
import com.retrobox.tv.profile.ProfileStore

/** Buttons shown for a scope, in a controller-like order; global shows everything any system can use. */
internal fun buttonsFor(scope: ProfileScope): List<PadButton> {
    val systemId = when (scope) { ProfileScope.Global -> "snes"; is ProfileScope.System -> scope.systemId; is ProfileScope.Game -> scope.systemId }
    val sys = Systems.byId(systemId)!!
    val has = sys.buttons.toSet()
    return sys.editorOrder.filter { it in has }
}

/** A button's name at this level: the system's own name ("Confirm" for RetroPad A in RetroBox Originals) for a system or game, the RetroPad name for all systems. */
internal fun buttonName(scope: ProfileScope, b: PadButton): String = when (scope) {
    ProfileScope.Global -> b.label
    is ProfileScope.System -> Systems.byId(scope.systemId)?.nameOf(b) ?: b.label
    is ProfileScope.Game -> Systems.byId(scope.systemId)?.nameOf(b) ?: b.label
}

/** Most players any system covered by [scope] supports; the Player 2 switch is only offered when this is 2. */
internal fun playersFor(scope: ProfileScope): Int = when (scope) {
    ProfileScope.Global -> Systems.all.maxOf { it.players }
    is ProfileScope.System -> Systems.byId(scope.systemId)?.players ?: 1
    is ProfileScope.Game -> Systems.byId(scope.systemId)?.players ?: 1
}

internal fun scopeTitle(scope: ProfileScope) = when (scope) {
    ProfileScope.Global -> "All systems"
    is ProfileScope.System -> Systems.byId(scope.systemId)?.displayName ?: scope.systemId
    is ProfileScope.Game -> scope.fileName.substringBeforeLast('.')
}

/** True when the connected pad is a Sony one (or none is connected): its buttons are then named like the PlayStation pad. */
internal fun usePlayStationNames(monitor: ControllerMonitor) = monitor.pads.isEmpty() || monitor.pads.any { it.vendorId == 0x054C }

/** Readable key name: "Z", "Enter", "Right Shift", "↑ Up", "✕ Cross" (PlayStation names) or "Pad A" (generic). */
internal fun keyName(code: Int, ps: Boolean = true): String {
    val n = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    if (ps) when (n) {
        "BUTTON_A" -> return "✕ Cross"; "BUTTON_B" -> return "○ Circle"; "BUTTON_X" -> return "□ Square"; "BUTTON_Y" -> return "△ Triangle"
        "BUTTON_START" -> return "Options"; "BUTTON_SELECT" -> return "Share"; "BUTTON_MODE" -> return "PS button"
        "BUTTON_THUMBL" -> return "L3"; "BUTTON_THUMBR" -> return "R3"
        "BUTTON_L1", "BUTTON_R1", "BUTTON_L2", "BUTTON_R2" -> return n.removePrefix("BUTTON_")
    }
    return when {
        n.startsWith("BUTTON_") -> "Pad " + n.removePrefix("BUTTON_").let { if (it.length <= 2) it else it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        n == "DPAD_UP" -> "↑ Up"; n == "DPAD_DOWN" -> "↓ Down"; n == "DPAD_LEFT" -> "← Left"; n == "DPAD_RIGHT" -> "→ Right"
        n == "DPAD_CENTER" -> "D-pad Center"
        n == "SHIFT_RIGHT" -> "Right Shift"; n == "SHIFT_LEFT" -> "Left Shift"; n == "CTRL_RIGHT" -> "Right Ctrl"; n == "CTRL_LEFT" -> "Left Ctrl"
        n == "ALT_RIGHT" -> "Right Alt"; n == "ALT_LEFT" -> "Left Alt"
        else -> n.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }.let { if (it.length == 1) it.uppercase() else it }
    }
}

private fun actionColor(b: PadButton): Color? = when (b) {
    PadButton.A -> Color(0xFF3DDC84); PadButton.B -> Color(0xFFFF4D6D); PadButton.X -> Color(0xFF3B82F6); PadButton.Y -> Color(0xFFFFC857); else -> null
}

/**
 * Rebind buttons for one level (global, system or game). Select a row, then press the key on the controller, keyboard or remote.
 * A key another button already uses asks for a second press ("move it"); Back cancels; holding OK on a row clears it.
 * If the controller disconnects while waiting, the wait is cancelled with a message instead of hanging.
 */
@Composable
fun MappingScreen(
    scope: ProfileScope,
    store: ProfileStore,
    monitor: ControllerMonitor,
    onDone: () -> Unit,
    installInterceptor: (((KeyEvent) -> Boolean)?) -> Unit,
) {
    var set by remember { mutableStateOf(store.load()) }
    var waitingFor by remember { mutableStateOf<PadButton?>(null) }
    var selected by remember { mutableStateOf<PadButton?>(null) }
    var pendingKey by remember { mutableStateOf<Pair<Int, PadButton>?>(null) }   // key that needs a second press to move it
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }      // text, isWarning
    var padsWhenWaiting by remember { mutableIntStateOf(0) }
    var player by remember { mutableIntStateOf(0) }   // 0 = Player 1, 1 = Player 2
    var swallowUp by remember { mutableIntStateOf(0) }   // key whose key-down ended the wait: its key-up must not reach the UI (a Back key-up would leave the screen)

    val buttons = remember(scope) { buttonsFor(scope) }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }
    val twoPlayer = remember(scope) { playersFor(scope) >= 2 }
    val resolved = remember(set, scope, player) { ProfileResolver.resolveForScope(DefaultMappings.gamepad, set, scope, player) }
    // Only buttons this system really has take part in conflicts (e.g. a Game Boy game has no L2, so a key default-bound to L2 is free here).
    val visible = remember(resolved, buttons) { resolved.filterValues { it in buttons } }
    val layer = set.layer(scope, player)
    // Player 2 can only be edited once its level is custom; until then it is a live copy of Player 1.
    val editable = player == 0 || set.isPlayer2Custom(scope)
    val ps = usePlayStationNames(monitor)

    fun commit(newSet: ProfileSet) { set = newSet; store.save(newSet) }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP && swallowUp == e.keyCode) { swallowUp = 0; return true }
        val button = waitingFor ?: return false
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return true   // swallow ups/repeats while waiting
        swallowUp = e.keyCode
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BACK) { waitingFor = null; pendingKey = null; message = "Cancelled" to false; return true }
        if (ProfileResolver.isReserved(code)) { message = "${keyName(code, ps)} opens the pause menu and can't be a game button. Press another key." to true; return true }
        val loser = ProfileResolver.conflict(visible, code, button)
        if (loser != null && pendingKey?.first != code) {
            pendingKey = code to loser
            message = "${keyName(code, ps)} is already ${buttonName(scope, loser)}. Press it again to move it to ${buttonName(scope, button)}, another key to choose differently, Back to cancel." to true
            return true
        }
        commit(set.with(scope, player, ProfileResolver.bind(layer, visible, button, code)))
        message = "${buttonName(scope, button)} ← ${keyName(code, ps)}" + (loser?.let { " (${buttonName(scope, it)} lost this key)" } ?: "") to false
        waitingFor = null; pendingKey = null
        return true
    }

    DisposableEffect(waitingFor, swallowUp) {
        installInterceptor(if (waitingFor != null || swallowUp != 0) ::onKey else null)
        onDispose { installInterceptor(null) }
    }
    // Controller unplugged / battery died while waiting for a key: stop waiting, say why.
    LaunchedEffect(monitor.pads.size) {
        if (waitingFor != null && padsWhenWaiting > 0 && monitor.pads.size < padsWhenWaiting) {
            message = "The controller disconnected. Reconnect it, then select the button again." to true
            waitingFor = null; pendingKey = null
        }
    }

    val subtitle = when (scope) {
        ProfileScope.Global -> "Applies to every system unless a system or game overrides it."
        is ProfileScope.System -> "Overrides the global controls for ${scopeTitle(scope)} games."
        is ProfileScope.Game -> "Overrides the system and global controls for this game only."
    } + if (twoPlayer && player == 1) (if (editable) " Player 2 has its own layout here." else " Player 2 uses Player 1's buttons here.") else ""
    val resetLabel = when (scope) { is ProfileScope.Game -> "Reset to system defaults"; is ProfileScope.System -> "Reset to global defaults"; ProfileScope.Global -> "Reset to defaults" }

    Box(Modifier.fillMaxSize().dashboardBackground()) {
        Column(Modifier.fillMaxSize()) {
            // ---- header ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onDone)
                Spacer(Modifier.width(14.dp))
                LogoMark(Modifier.size(width = 58.dp, height = 40.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (scope is ProfileScope.Game) "GAME CONTROLS" else "CONTROLS", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Text("  ·  ", fontSize = 24.sp, color = RB.Muted)
                        Text(scopeTitle(scope), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = RB.Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(subtitle, fontSize = 12.sp, color = RB.Muted)
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---- left panel ----
                Column(Modifier.width(290.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xB30F1530)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val pad = monitor.pads.getOrNull(player)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (pad == null) Color(0x44FF3D5E) else Color(0x333DDC84)).padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolIconView(ToolIcon.CONTROLLER, if (pad == null) Color(0xFFFF5C7A) else RB.Good, 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (pad == null) (if (player == 1) "No second controller connected" else "No controller connected") else "${if (twoPlayer) "Player ${player + 1}: " else "Controller: "}${pad.name}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (pad == null) Color(0xFFFF9AAE) else RB.Good, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (player == 1) "Player 2 is the second controller connected. The keyboard and TV remote always play as Player 1." else "Keyboard and TV remote available.", fontSize = 11.sp, color = RB.Muted)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
                    if (waitingFor != null) {
                        Text("Press the key for ${buttonName(scope, waitingFor!!)} now…", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RB.Pink)
                        Text("Press a keyboard key, gamepad button or remote button. Back cancels.", fontSize = 11.sp, lineHeight = 14.sp, color = RB.Muted)
                    } else {
                        Text("Select an action, then press a button", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Press a key, gamepad button, or remote button to assign it to this action.", fontSize = 11.sp, lineHeight = 14.sp, color = RB.Muted)
                    }
                    message?.let { (text, warn) -> Text(text, fontSize = 11.sp, lineHeight = 14.sp, color = if (warn) RB.Warn else RB.Good) }
                    ControllerIllustration(setOfNotNull((waitingFor ?: selected)?.let { padPartOfAction(it) }), Modifier.fillMaxWidth().aspectRatio(1.9f))
                    val unbound = ProfileResolver.unboundButtons(resolved, buttons)
                    if (unbound.isNotEmpty()) Text("Not bound: ${unbound.joinToString { buttonName(scope, it) }}", fontSize = 11.sp, color = RB.Warn)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(0xFF2B8CFF)), contentAlignment = Alignment.Center) { Text("i", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        Text("  TIPS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4DA3FF), letterSpacing = 1.sp)
                    }
                    Text("• Use your gamepad, keyboard, or TV remote.\n• ${if (scope == ProfileScope.Global) "These controls apply to every system." else "These controls apply only to ${scopeTitle(scope)}."}\n• You can reset to defaults anytime.", fontSize = 10.sp, lineHeight = 13.sp, color = RB.Muted)
                }

                // ---- right panel: table ----
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xB30F1530)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (twoPlayer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChoiceChip("Player 1", player == 0) { player = 0; waitingFor = null; pendingKey = null; message = null }
                            ChoiceChip("Player 2", player == 1) { player = 1; waitingFor = null; pendingKey = null; message = null }
                            if (player == 1) {
                                Spacer(Modifier.width(10.dp))
                                ChoiceChip("Same as Player 1", !editable) { commit(set.withPlayer2Custom(scope, false)); message = "Player 2 now follows Player 1's buttons here." to false }
                                ChoiceChip("Custom", editable) { if (!editable) { commit(set.withPlayer2Custom(scope, true)); message = "Player 2 has its own buttons here, starting from a copy of Player 1's." to false } }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("ACTION", Modifier.weight(1.1f), fontSize = 11.sp, letterSpacing = 1.sp, color = RB.Muted, fontWeight = FontWeight.Bold)
                        Text("CURRENT INPUT", Modifier.weight(1.3f), fontSize = 11.sp, letterSpacing = 1.sp, color = RB.Muted, fontWeight = FontWeight.Bold)
                        Text("CHANGE", Modifier.width(86.dp), fontSize = 11.sp, letterSpacing = 1.sp, color = RB.Muted, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                        itemsIndexed(buttons, key = { _, b -> b.name }) { i, b ->
                            MappingRow(
                                button = b, name = buttonName(scope, b), ps = ps, custom = layer.bindings.containsKey(b), keys = ProfileResolver.keysOf(visible, b),
                                waiting = waitingFor == b,
                                modifier = if (i == 0) Modifier.focusRequester(firstRow) else Modifier,
                                onFocus = { selected = b },
                                onClear = {
                                    if (editable) { commit(set.with(scope, player, ProfileResolver.clear(layer, b))); message = "${buttonName(scope, b)} cleared. Select it and press a key to bind it again, or use Reset." to false }
                                },
                            ) {
                                if (!editable) message = "Player 2 uses Player 1's buttons here. Choose Custom above to give Player 2 their own." to true
                                else { waitingFor = b; pendingKey = null; padsWhenWaiting = monitor.pads.size; message = null }
                            }
                        }
                    }
                }
            }

            // ---- footer ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Hint("A", Color(0xFF3DDC84), "Select"); Spacer(Modifier.width(22.dp))
                Hint("B", Color(0xFFFF4D6D), "Back"); Spacer(Modifier.width(22.dp))
                Hint("≡", Color(0xFF8892B8), "Hold A to clear")
                Spacer(Modifier.weight(1f))
                FooterButton("⟲  $resetLabel", enabled = editable && !layer.isEmpty, accent = RB.Muted) { commit(set.with(scope, player, ProfileLayer())); message = "Controls reset." to false }
                Spacer(Modifier.width(12.dp))
                FooterButton("✓  Done", enabled = true, accent = RB.Cyan, onClick = onDone)
            }
        }
    }
}

/** A selectable pill (current choice is filled). */
@Composable
internal fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = Modifier.height(32.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0x66FF3D8B) else Color(0x33141B40), contentColor = RB.Text,
            focusedContainerColor = if (selected) Color(0x99FF3D8B) else RB.PanelHi, focusedContentColor = RB.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) RB.Pink else Color(0x44FFFFFF)), shape = RoundedCornerShape(16.dp)),
            focusedBorder = Border(BorderStroke(2.5.dp, RB.Pink), shape = RoundedCornerShape(16.dp)),
        ),
    ) { Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { Text(text, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1) } }
}

@Composable
internal fun Hint(letter: String, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Text(letter, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1024)) }
        Text("  $label", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun FooterButton(text: String, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.height(40.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xCC131A36), contentColor = RB.Text, focusedContainerColor = RB.PanelHi, focusedContentColor = RB.Text,
            disabledContainerColor = Color(0x66131A36), disabledContentColor = RB.Muted,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.5.dp, accent.copy(alpha = if (enabled) 0.8f else 0.3f)), shape = RoundedCornerShape(12.dp)),
            focusedBorder = Border(BorderStroke(3.dp, RB.Pink), shape = RoundedCornerShape(12.dp)),
        ),
    ) { Box(Modifier.padding(horizontal = 18.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) } }
}

@Composable
private fun MappingRow(
    button: PadButton, name: String, ps: Boolean, custom: Boolean, keys: List<Int>, waiting: Boolean,
    modifier: Modifier, onFocus: () -> Unit, onClear: () -> Unit, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ok = rememberOkLongPress(onClear, onClick)
    Surface(
        onClick = ok.onClick, onLongClick = ok.onLongClick,
        modifier = modifier.then(ok.modifier).fillMaxWidth().height(40.dp).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x661B2350), contentColor = RB.Text, focusedContainerColor = Color(0x80301A4A), focusedContentColor = RB.Text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x14FFFFFF)), shape = RoundedCornerShape(10.dp)),
            focusedBorder = Border(BorderStroke(2.5.dp, RB.Pink), shape = RoundedCornerShape(10.dp)),
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1.1f), verticalAlignment = Alignment.CenterVertically) {
                ActionBadge(button, name)
                Spacer(Modifier.width(12.dp))
                Text(name + if (custom) " (custom)" else "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.weight(1.3f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (waiting) KeyChip("Press a key…", RB.Pink)
                else if (keys.isEmpty()) KeyChip("— not bound —", RB.Warn)
                else keys.take(3).forEach { KeyChip(keyName(it, ps), null) }
            }
            Box(
                Modifier.width(86.dp).height(28.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.5.dp, if (focused || waiting) RB.Pink else Color(0x66FFFFFF), RoundedCornerShape(8.dp))
                    .background(if (focused || waiting) Color(0x22FF3D8B) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { Text("Change", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (focused || waiting) RB.Pink else RB.Text) }
        }
    }
}

@Composable
private fun KeyChip(text: String, accent: Color?) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0x4D2B3566)).padding(horizontal = 9.dp, vertical = 4.dp),
    ) { Text(text, fontSize = 12.sp, color = accent ?: RB.Text, maxLines = 1) }
}

/** Round (face buttons), pill (Start/Select/L/R) or arrow (D-pad) badge in front of an action name. */
@Composable
private fun ActionBadge(b: PadButton, name: String) {
    val c = actionColor(b)
    when {
        c != null -> Box(Modifier.size(26.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) { Text(name, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B1024)) }
        b == PadButton.UP || b == PadButton.DOWN || b == PadButton.LEFT || b == PadButton.RIGHT -> Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            Text(when (b) { PadButton.UP -> "↑"; PadButton.DOWN -> "↓"; PadButton.LEFT -> "←"; else -> "→" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        else -> Box(Modifier.height(22.dp).width(46.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
            Text(name.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
        }
    }
}
