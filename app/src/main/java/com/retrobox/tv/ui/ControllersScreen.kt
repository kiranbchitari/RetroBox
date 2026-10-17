@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import android.view.MotionEvent
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.retrobox.tv.emulator.SystemStatus
import com.retrobox.tv.emulator.Systems
import com.retrobox.tv.input.ControllerMonitor
import com.retrobox.tv.profile.ProfileScope
import com.retrobox.tv.profile.ProfileSet
import com.retrobox.tv.profile.ProfileStore

/**
 * Controllers: a live picture of the connected pad (pressed buttons light up, sticks and triggers move), the list of
 * connected devices, and the controls levels to edit (all systems, or one system) with export / import.
 */
@Composable
fun ControllersScreen(
    monitor: ControllerMonitor,
    profiles: ProfileStore,
    onEditControls: (ProfileScope) -> Unit,
    onExport: () -> String,
    onImport: () -> String,
    onDone: () -> Unit,
) {
    DisposableEffect(monitor) {
        monitor.capturing = true
        monitor.refresh()
        onDispose { monitor.capturing = false; monitor.pressed.clear(); monitor.axes.clear() }
    }
    var message by remember { mutableStateOf<String?>(null) }
    val set: ProfileSet = remember { profiles.load() }
    var currentSet by remember { mutableStateOf(set) }
    val first = remember { FocusRequester() }
    val playable = remember { Systems.all.filter { it.status != SystemStatus.PLANNED } }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    val lit = monitor.pressed.keys.mapNotNull { padPartOfKey(it) }.toSet()
    fun axis(a: Int) = monitor.axes[a] ?: 0f
    val trigL = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)).coerceIn(0f, 1f)
    val trigR = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().dashboardBackground()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onDone)
                Spacer(Modifier.width(14.dp))
                LogoMark(Modifier.size(width = 58.dp, height = 40.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("CONTROLLERS", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Press buttons and move sticks to test them. Assign controls for every system or for one system.", fontSize = 12.sp, color = RB.Muted)
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---- left: live pad + devices ----
                Column(Modifier.width(330.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xB30F1530)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pads = monitor.pads
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (pads.isEmpty()) Color(0x44FF3D5E) else Color(0x333DDC84)).padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolIconView(ToolIcon.CONTROLLER, if (pads.isEmpty()) Color(0xFFFF5C7A) else RB.Good, 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (pads.isEmpty()) "No controller connected" else "${pads.size} controller${if (pads.size > 1) "s" else ""} connected", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (pads.isEmpty()) Color(0xFFFF9AAE) else RB.Good)
                    }
                    if (pads.isEmpty()) Text("Pair one in Android TV settings (Remotes & Accessories) and it appears here. The keyboard and TV remote still work.", fontSize = 11.sp, lineHeight = 14.sp, color = RB.Muted)
                    ControllerIllustration(
                        lit, Modifier.fillMaxWidth().aspectRatio(1.9f),
                        stickL = Offset(axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y)).clampLen(),
                        stickR = Offset(axis(MotionEvent.AXIS_Z), axis(MotionEvent.AXIS_RZ)).clampLen(),
                        triggerL = trigL, triggerR = trigR,
                    )
                    Text(
                        monitor.recent.firstOrNull() ?: "Press a button on your controller to see it light up.",
                        fontSize = 11.sp, color = if (monitor.recent.isEmpty()) RB.Muted else RB.Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (pads.size >= 2) Text("Two-player games (NES, SNES): the first controller connected is Player 1, the second is Player 2. Swap them from the pause menu → Players.", fontSize = 10.sp, lineHeight = 13.sp, color = RB.Muted)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(pads, key = { it.id }) { p ->
                            // Same rule the game uses at start: the oldest connected pad (lowest device id) is Player 1, the next Player 2.
                            val order = pads.sortedBy { it.id }.indexOfFirst { it.id == p.id }
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x4D2B3566)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text((if (order in 0..1) "Player ${order + 1} · " else "Spare · ") + p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("id ${p.id} · ${p.vendorId.toString(16)}:${p.productId.toString(16)} · ${p.sources}", fontSize = 10.sp, color = RB.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("axes: ${p.axes.joinToString().ifEmpty { "none" }}", fontSize = 10.sp, color = RB.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // ---- right: controls levels ----
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xB30F1530)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("CONTROLS FOR", Modifier.weight(1.2f), fontSize = 11.sp, letterSpacing = 1.sp, color = RB.Muted, fontWeight = FontWeight.Bold)
                        Text("STATUS", Modifier.weight(1f), fontSize = 11.sp, letterSpacing = 1.sp, color = RB.Muted, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(86.dp))
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                        item {
                            ScopeRow("All systems", "applies to every system" + if (currentSet.isPlayer2Custom(ProfileScope.Global)) " · Player 2 custom" else "", null, !currentSet.global.isEmpty || currentSet.isPlayer2Custom(ProfileScope.Global), Modifier.focusRequester(first)) { onEditControls(ProfileScope.Global) }
                        }
                        items(playable, key = { it.id }) { s ->
                            val customGames = currentSet.games.keys.count { it.startsWith(s.id + "|") }
                            ScopeRow(
                                s.displayName,
                                buildString {
                                    append(if (currentSet.systems.containsKey(s.id)) "custom controls" else "uses all-systems controls")
                                    if (s.players > 1 && currentSet.isPlayer2Custom(ProfileScope.System(s.id))) append(" · Player 2 custom")
                                    if (customGames > 0) append(" · $customGames game${if (customGames > 1) "s" else ""} with their own")
                                },
                                s.id, currentSet.systems.containsKey(s.id), Modifier,
                            ) { onEditControls(ProfileScope.System(s.id)) }
                        }
                    }
                    message?.let { Text(it, fontSize = 11.sp, color = RB.Good, modifier = Modifier.padding(start = 12.dp, top = 4.dp), maxLines = 2) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Hint("A", Color(0xFF3DDC84), "Select"); Spacer(Modifier.width(22.dp))
                Hint("B", Color(0xFFFF4D6D), "Back"); Spacer(Modifier.width(22.dp))
                Text("Hold OK on a game in the Library for its own controls", fontSize = 12.sp, color = RB.Muted)
                Spacer(Modifier.weight(1f))
                FooterButton("⇪  Export", enabled = true, accent = RB.Muted) { message = onExport() }
                Spacer(Modifier.width(10.dp))
                FooterButton("⇩  Import", enabled = true, accent = RB.Muted) { message = onImport(); currentSet = profiles.load() }
                Spacer(Modifier.width(12.dp))
                FooterButton("✓  Done", enabled = true, accent = RB.Cyan, onClick = onDone)
            }
        }
    }
}

private fun Offset.clampLen(): Offset {
    val l = kotlin.math.sqrt(x * x + y * y)
    return if (l > 1f) Offset(x / l, y / l) else this
}

@Composable
private fun ScopeRow(title: String, status: String, systemId: String?, custom: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(46.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x661B2350), contentColor = RB.Text, focusedContainerColor = Color(0x80301A4A), focusedContentColor = RB.Text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x14FFFFFF)), shape = RoundedCornerShape(10.dp)),
            focusedBorder = Border(BorderStroke(2.5.dp, RB.Pink), shape = RoundedCornerShape(10.dp)),
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(54.dp).height(30.dp), contentAlignment = Alignment.Center) {
                    if (systemId == null) LogoMark(Modifier.size(width = 42.dp, height = 28.dp))
                    else {
                        val a = consoleIconAspect(systemId)
                        ConsoleIcon(systemId, if (a >= 1f) Modifier.fillMaxWidth().aspectRatio(a) else Modifier.fillMaxHeight().aspectRatio(a))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(status, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 13.sp, color = if (custom) RB.Pink else RB.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(
                Modifier.width(86.dp).height(28.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.5.dp, if (focused) RB.Pink else Color(0x66FFFFFF), RoundedCornerShape(8.dp))
                    .background(if (focused) Color(0x22FF3D8B) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { Text("Edit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (focused) RB.Pink else RB.Text) }
        }
    }
}
