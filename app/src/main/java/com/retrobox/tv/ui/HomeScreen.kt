@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
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
import com.retrobox.tv.emulator.SystemAdapter
import com.retrobox.tv.emulator.SystemStatus
import com.retrobox.tv.emulator.Systems
import com.retrobox.tv.library.HistoryEntry
import com.retrobox.tv.library.formatAgo
import com.retrobox.tv.library.formatPlayTime
import java.io.File

/** A history entry prepared for display: the system name, core, where its picture would be, and whether a saved session exists. */
data class RecentGame(val entry: HistoryEntry, val systemName: String, val thumbFile: File, val coreName: String = "", val canResume: Boolean = false, val game: com.retrobox.tv.library.Game? = null)

/** A favourite game prepared for display. */
data class FavoriteGame(val game: com.retrobox.tv.library.Game, val systemName: String, val thumbFile: File)

@Composable
fun rememberThumb(file: File): androidx.compose.ui.graphics.ImageBitmap? {
    val state = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.path, file.lastModified()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            file.takeIf { it.exists() }?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    return state.value
}

sealed interface Screen {
    data object Home : Screen
    data object Controllers : Screen
    data object Diagnostics : Screen
    data object Settings : Screen
    data object About : Screen
    data object Folders : Screen
    data class Library(val system: SystemAdapter?, val favorites: Boolean = false) : Screen
    data class Mapping(val scope: com.retrobox.tv.profile.ProfileScope) : Screen
}

@Composable
fun HomeScreen(
    recent: List<RecentGame>,
    favorites: List<FavoriteGame>,
    controllerCount: Int,
    libraryCount: Int,
    gamesPerSystem: Map<String, Int>,
    lastFocusId: String,
    onFocused: (String) -> Unit,
    onOpen: (Screen) -> Unit,
    onSystem: (SystemAdapter) -> Unit,
    onPlayRecent: (RecentGame, Boolean) -> Unit,   // (game, resumeSavedSession)
    onPlayFavorite: (FavoriteGame) -> Unit,
    onToggleFavorite: (com.retrobox.tv.library.Game) -> Unit,
) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun req(id: String) = requesters.getOrPut(id) { FocusRequester() }
    // Restore focus to what opened the previous screen; otherwise the hero card, otherwise the first system.
    LaunchedEffect(Unit) {
        // requestFocus() reports false (no longer throws) for a requester that is not on screen, e.g. a removed favourite.
        for (id in listOf(lastFocusId, "hero", "sys:${Systems.all.first().id}")) if (runCatching { req(id).requestFocus() }.getOrDefault(false)) break
    }

    Box(Modifier.fillMaxSize().dashboardBackground()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 14.dp)) {
            Header(controllerCount, libraryCount)

            SectionLabel("PICK UP WHERE YOU LEFT OFF", RB.Pink)
            val hero = recent.firstOrNull()
            if (hero == null) {
                DashCard(onClick = { onOpen(Screen.Library(null)) }, modifier = Modifier.fillMaxWidth().height(110.dp).focusRequester(req("hero")), onFocus = { onFocused("hero") }) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.Center) {
                        Text("No games played yet", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Pick a system below or open the Library. Games you play show up here with a picture and a Resume button.", fontSize = 13.sp, color = RB.Muted)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(156.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HeroCard(hero, Modifier.weight(1.75f).fillMaxHeight().focusRequester(req("hero")), onFocus = { onFocused("hero") }) { onPlayRecent(hero, false) }
                    ResumeCard(hero, Modifier.weight(1f).fillMaxHeight().focusRequester(req("resume")), onFocus = { onFocused("resume") }) { onPlayRecent(hero, true) }
                }
            }

            SectionLabel("YOUR SYSTEMS", RB.Cyan)
            Row(Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Systems.all.forEach { s ->
                    SystemTile(s, gamesPerSystem[s.id] ?: 0, Modifier.weight(1f).fillMaxHeight().focusRequester(req("sys:${s.id}")), onFocus = { onFocused("sys:${s.id}") }) { onSystem(s) }
                }
            }

            SectionLabel("TOOLS & SETTINGS", RB.Pink)
            Row(Modifier.fillMaxWidth().height(78.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolTile(ToolIcon.CONTROLLER, RB.Pink, "Controllers", "Detect pads, live button + axis monitor", true, Modifier.weight(1f).fillMaxHeight().focusRequester(req("tool:controllers")), { onFocused("tool:controllers") }) { onOpen(Screen.Controllers) }
                ToolTile(ToolIcon.LIBRARY, RB.Cyan, "Library", "Browse, search, favorites, folders", true, Modifier.weight(1f).fillMaxHeight().focusRequester(req("tool:library")), { onFocused("tool:library") }) { onOpen(Screen.Library(null)) }
                ToolTile(ToolIcon.PERFORMANCE, RB.Cyan, "Performance", "Device, GPU, memory, cores, recent errors", true, Modifier.weight(1f).fillMaxHeight().focusRequester(req("tool:diagnostics")), { onFocused("tool:diagnostics") }) { onOpen(Screen.Diagnostics) }
                ToolTile(ToolIcon.SETTINGS, RB.Pink, "Settings", "Graphics, audio, TV safe area", true, Modifier.weight(1f).fillMaxHeight().focusRequester(req("tool:settings")), { onFocused("tool:settings") }) { onOpen(Screen.Settings) }
            }

            if (recent.isNotEmpty()) {
                SectionLabel("RECENTLY PLAYED", RB.Cyan)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                    itemsIndexed(recent, key = { _, g -> g.entry.romPath }) { i, g ->
                        GameTile(
                            title = g.entry.title, favorite = g.game?.favorite == true, thumb = rememberThumb(g.thumbFile),
                            caption = formatAgo(g.entry.lastPlayedMs), pictureAspect = 0.95f,
                            modifier = Modifier.width(112.dp).focusRequester(req("recent:$i")), onFocus = { onFocused("recent:$i") },
                            onToggleFavorite = { g.game?.let(onToggleFavorite) },
                        ) { onPlayRecent(g, false) }
                    }
                }
            }

            if (favorites.isNotEmpty()) {
                SectionLabel("FAVORITES", RB.Pink)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                    itemsIndexed(favorites, key = { _, g -> g.game.path }) { i, g ->
                        GameTile(
                            title = g.game.title, favorite = true, thumb = rememberThumb(g.thumbFile), caption = g.systemName, pictureAspect = 0.95f,
                            modifier = Modifier.width(112.dp).focusRequester(req("fav:$i")), onFocus = { onFocused("fav:$i") },
                            onToggleFavorite = { onToggleFavorite(g.game) },
                        ) { onPlayFavorite(g) }
                    }
                }
            }

            Text(
                "Systems marked Playable were tested on an Android TV emulator with test programs; none has been tested on a real TV yet.",
                fontSize = 11.sp, color = RB.Muted.copy(alpha = 0.8f), modifier = Modifier.padding(top = 14.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun Header(controllerCount: Int, libraryCount: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LogoMark(Modifier.size(width = 58.dp, height = 40.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("RetroBox", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(" TV", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = RB.Cyan)
            }
            Text("Your games. Your couch. Your nostalgia.", fontSize = 12.sp, color = RB.Muted)
        }
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (controllerCount > 0) RB.Good else RB.Muted))
        Text(
            if (controllerCount > 0) "  CONTROLLER CONNECTED" else "  NO CONTROLLER", fontSize = 11.sp, letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold, color = if (controllerCount > 0) RB.Text else RB.Muted,
        )
        Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(24.dp).background(RB.Muted.copy(alpha = 0.35f)))
        Text("LIBRARY ", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold, color = RB.Muted)
        Text("$libraryCount", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RB.Cyan)
        Text(" GAMES", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold, color = RB.Muted)
    }
}

@Composable
private fun SectionLabel(text: String, accent: Color) {
    Row(Modifier.padding(top = 11.dp, bottom = 7.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(16.dp).background(accent))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD9DEEA))
    }
}

/** Focusable translucent navy panel with the pink focus ring used across the dashboard. */
@Composable
private fun DashCard(onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true, onFocus: () -> Unit = {}, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = RB.Panel, contentColor = RB.Text, focusedContainerColor = RB.PanelHi, focusedContentColor = RB.Text,
            pressedContainerColor = RB.PanelHi, pressedContentColor = RB.Text, disabledContainerColor = RB.Panel, disabledContentColor = RB.Muted,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x33FFFFFF)), shape = RoundedCornerShape(16.dp)),
            focusedBorder = Border(BorderStroke(3.dp, RB.Pink), shape = RoundedCornerShape(16.dp)),
        ),
    ) { content() }
}

@Composable
private fun HeroCard(g: RecentGame, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    DashCard(onClick, modifier, onFocus = onFocus) {
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxHeight().aspectRatio(4f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF080B18)), contentAlignment = Alignment.Center) {
                val thumb = rememberThumb(g.thumbFile)
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                else Text(g.entry.title.take(1).uppercase(), fontSize = 40.sp, color = RB.Muted)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(g.entry.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3B2F8F)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(g.systemName.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Text("Last played ${formatAgo(g.entry.lastPlayedMs)}  ·  ${formatPlayTime(g.entry.totalPlayMs)} played", fontSize = 12.sp, color = RB.Muted)
                Box(Modifier.padding(vertical = 7.dp).fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Text("${g.coreName.ifEmpty { "Core" }} · played ${g.entry.playCount}×", fontSize = 12.sp, color = RB.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Press OK to start from the beginning", fontSize = 11.sp, color = RB.Muted.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun ResumeCard(g: RecentGame, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    DashCard(onClick, modifier, onFocus = onFocus) {
        Box(Modifier.fillMaxSize()) {
            val thumb = rememberThumb(g.thumbFile)
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize().alpha(0.18f), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("QUICK RESUME", fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD9DEEA))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Canvas(Modifier.size(62.dp)) {
                        drawCircle(RB.Cyan.copy(alpha = 0.22f))
                        drawCircle(RB.Cyan, style = Stroke(width = 4f))
                        val p = Path().apply {
                            moveTo(size.width * 0.41f, size.height * 0.3f); lineTo(size.width * 0.72f, size.height * 0.5f); lineTo(size.width * 0.41f, size.height * 0.7f); close()
                        }
                        drawPath(p, Color.White)
                    }
                    Column {
                        Text("Resume game", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (g.canResume) "Continue where you left off" else "No saved session yet: starts the game",
                            fontSize = 12.sp, color = RB.Muted, maxLines = 2,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun SystemTile(s: SystemAdapter, games: Int, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val (label, color) = when (s.status) {
        SystemStatus.VERIFIED -> "PLAYABLE" to RB.Good
        SystemStatus.EXPERIMENTAL -> "EXPERIMENTAL" to RB.Warn
        SystemStatus.PLANNED -> "PLANNED" to RB.Muted
    }
    DashCard(onClick, modifier, enabled = s.status != SystemStatus.PLANNED, onFocus = onFocus) {
        // Six systems share the row (about 138 dp each at 960 dp): a narrow icon column leaves room for "Game Boy" and "PLAYABLE".
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val aspect = consoleIconAspect(s.id)
            Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                ConsoleIcon(s.id, if (aspect >= 1f) Modifier.fillMaxWidth().aspectRatio(aspect) else Modifier.fillMaxHeight(0.95f).aspectRatio(aspect))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(s.displayName, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (games == 1) "1 GAME" else "$games GAMES", fontSize = 10.sp, letterSpacing = 1.sp, color = RB.Muted, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                    // No wrapping: with a TV safe area the tile narrows, and a wrapped status label would be clipped away.
                    Text("  $label", fontSize = 10.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ToolTile(icon: ToolIcon, tint: Color, title: String, subtitle: String, enabled: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    DashCard(onClick, modifier, enabled = enabled, onFocus = onFocus) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolIconView(icon, if (enabled) tint else RB.Muted.copy(alpha = 0.6f), 34.dp)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(subtitle, fontSize = 10.sp, lineHeight = 12.sp, color = RB.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (enabled) Text("›", fontSize = 22.sp, color = RB.Muted)
        }
    }
}
