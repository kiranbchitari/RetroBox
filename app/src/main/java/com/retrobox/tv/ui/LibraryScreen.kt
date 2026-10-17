@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import com.retrobox.tv.game.CorePreferences
import com.retrobox.tv.library.Game
import com.retrobox.tv.library.GameDeleter
import com.retrobox.tv.library.LibraryFilter
import com.retrobox.tv.library.LibraryQuery
import com.retrobox.tv.library.LibraryService
import com.retrobox.tv.library.PlayHistory
import com.retrobox.tv.library.SortMode
import com.retrobox.tv.library.ThumbnailStore
import com.retrobox.tv.library.formatAgo
import com.retrobox.tv.library.formatPlayTime

/** The three ways the library can be shown; the header has one button per entry, in this order. */
enum class LibraryView(val icon: UiIcon) { GRID(UiIcon.GRID), LIST(UiIcon.LIST), BIG(UiIcon.BIG) }

/**
 * Games as a grid of pictures, a list, or big cards: every game, or one system's games ([system] != null).
 * OK plays, Y (or the heart on touch) toggles favourite, hold OK for options; Search, Sort and the three view buttons sit in the header.
 */
@Composable
fun LibraryScreen(
    system: SystemAdapter?,
    startWithFavorites: Boolean,
    library: LibraryService,
    history: PlayHistory,
    thumbs: ThumbnailStore,
    deleter: GameDeleter,
    corePrefs: CorePreferences,
    historyVersion: Int,
    /** The view saved last time (a [LibraryView] ordinal) and where to save a new choice, so it stays until the user picks another. */
    initialView: Int,
    onViewChange: (Int) -> Unit,
    onBack: () -> Unit,
    onPlay: (Game, String) -> Unit,
    onControls: (Game) -> Unit,
    onOpenFolders: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var sortOrdinal by rememberSaveable { mutableStateOf(SortMode.TITLE.ordinal) }
    var favoritesOnly by rememberSaveable { mutableStateOf(startWithFavorites) }
    var systemFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf(initialView.coerceIn(0, LibraryView.entries.lastIndex)) }
    val view = LibraryView.entries[viewMode]
    var optionsFor by remember { mutableStateOf<Game?>(null) }
    var deleting by remember { mutableStateOf<Game?>(null) }
    // The tile that should take focus once a delete is done (the neighbour of the removed game, or the game itself if it stayed).
    var focusAfterDelete by remember { mutableStateOf<String?>(null) }
    val afterDelete = remember { FocusRequester() }
    var coreId by remember { mutableStateOf(system?.coreFor(corePrefs.chosenCoreId(system.id))?.id) }

    val lastPlayed = remember(historyVersion) { history.recent(PlayHistory.MAX_ENTRIES).associate { it.romPath to it.lastPlayedMs } }
    val q = LibraryQuery(text, system?.id ?: systemFilter, favoritesOnly, SortMode.entries[sortOrdinal])
    val shown = remember(library.games, q, lastPlayed) { LibraryFilter.apply(library.games, q) { lastPlayed[it.path] ?: 0L } }
    val playable = remember { Systems.all.filter { it.status != SystemStatus.PLANNED } }
    val inScope = library.games.count { system == null || it.systemId == system.id }

    BackHandler(enabled = optionsFor != null) { optionsFor = null }
    BackHandler(enabled = deleting != null) { deleting = null }
    LaunchedEffect(focusAfterDelete, shown) {
        if (focusAfterDelete != null && shown.any { it.path == focusAfterDelete }) { runCatching { afterDelete.requestFocus() }; focusAfterDelete = null }
    }
    fun coreFor(g: Game) = (system ?: Systems.byId(g.systemId))?.coreFor(corePrefs.chosenCoreId(g.systemId))?.id ?: ""

    Box(Modifier.fillMaxSize().dashboardBackground()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 14.dp)) {
            // ---- header: back · icon · title · count · search · sort · view toggle ----
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Spacer(Modifier.width(10.dp))
                if (system != null) ConsoleIcon(system.id, Modifier.height(34.dp).aspectRatio(consoleIconAspect(system.id)))
                else LogoMark(Modifier.size(width = 50.dp, height = 34.dp))
                Spacer(Modifier.width(12.dp))
                // The title takes whatever room the right-hand controls leave and is cut with "…" before they would be pushed off
                // screen (seen on the Mi TV: "Game Boy Advance" + search + sort + three view buttons is wider than the screen).
                val title = system?.displayName ?: "Library"
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = if (title.length > 12) 22.sp else 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("${if (shown.size == inScope) "$inScope" else "${shown.size} of $inScope"} ${if (inScope == 1) "Game" else "Games"}", fontSize = 13.sp, color = RB.Muted, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 10.dp))
                }
                SearchBox(text, searching, onToggle = { searching = !searching }, onText = { text = it }, onDone = { searching = false })
                Spacer(Modifier.width(8.dp))
                HeaderChip(onClick = { sortOrdinal = (sortOrdinal + 1) % SortMode.entries.size }, wide = true) {
                    Text("Sort: ${SortMode.entries[sortOrdinal].label}", fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.width(6.dp)); UiIconView(UiIcon.CHEVRON_DOWN, RB.Muted, 12.dp)
                }
                Spacer(Modifier.width(8.dp))
                LibraryView.entries.forEachIndexed { i, v ->
                    if (i > 0) Spacer(Modifier.width(4.dp))
                    HeaderChip(onClick = { viewMode = v.ordinal; if (view != v) onViewChange(v.ordinal) }, selected = view == v) { UiIconView(v.icon, if (view == v) Color.White else RB.Muted, 18.dp) }
                }
            }

            // ---- filter chips (not in the mockup, needed: favourites, system filter, core, folders) ----
            val scan = library.scan
            LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                item { HeaderChip(onClick = { favoritesOnly = !favoritesOnly }, selected = favoritesOnly, wide = true) { Text(if (favoritesOnly) "♥ Favorites only" else "♡ Favorites", fontSize = 12.sp) } }
                if (system == null) item {
                    val name = playable.firstOrNull { it.id == systemFilter }?.displayName ?: "all systems"
                    HeaderChip(onClick = {
                        val ids = listOf<String?>(null) + playable.map { it.id }
                        systemFilter = ids[(ids.indexOf(systemFilter) + 1) % ids.size]
                    }, wide = true) { Text("System: $name", fontSize = 12.sp) }
                }
                if (system != null && system.cores.size > 1) item {
                    val next = system.nextCore(coreId)
                    HeaderChip(onClick = { next?.let { corePrefs.choose(system.id, it.id); coreId = it.id } }, wide = true) {
                        Text("Core: ${system.coreFor(coreId)?.displayName} → ${next?.displayName}", fontSize = 12.sp)
                    }
                }
                item { HeaderChip(onClick = onOpenFolders, wide = true) { Text("Folders", fontSize = 12.sp) } }
                item {
                    Text(
                        if (scan.running) "Scanning ${scan.folderLabel}: ${scan.romsFound} games…" else scan.message ?: "",
                        fontSize = 11.sp, color = RB.Muted, maxLines = 1, modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                    )
                }
            }

            when {
                library.games.isEmpty() && scan.running -> SectionTitle("Looking for games…")
                library.games.isEmpty() -> EmptyLibrary(library, onOpenFolders)
                shown.isEmpty() -> SectionTitle("No games match. Clear the search or filters.")
                else -> {
                    val first = remember { FocusRequester() }
                    LaunchedEffect(viewMode, shown.isEmpty()) { if (!searching) runCatching { first.requestFocus() } }
                    if (view == LibraryView.GRID || view == LibraryView.BIG) {
                        val big = view == LibraryView.BIG
                        LazyVerticalGrid(
                            // Big cards: about 4 per row on the 960x540 dp layout, each with a full picture and a larger title.
                            columns = GridCells.Adaptive(minSize = if (big) 200.dp else 100.dp),
                            modifier = Modifier.padding(top = 8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (big) 16.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(if (big) 16.dp else 10.dp),
                        ) {
                            itemsIndexed(shown, key = { _, g -> g.path }) { i, g ->
                                GameTile(
                                    title = g.title, favorite = g.favorite, missing = g.missing, thumb = rememberThumb(thumbs.displayFile(g.systemId, g.fileName)),
                                    modifier = tileFocus(i == 0, first, g.path == focusAfterDelete, afterDelete),
                                    pictureAspect = if (big) 1.0f else 1.1f, big = big,
                                    onToggleFavorite = { library.setFavorite(g, !g.favorite) }, onOptions = { optionsFor = g },
                                ) { onPlay(g, coreFor(g)) }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                            itemsIndexed(shown, key = { _, g -> g.path }) { i, g ->
                                val played = remember(g.path, historyVersion) { history.entryFor(g.path) }
                                val caption = buildString {
                                    append(if (system == null) Systems.byId(g.systemId)?.displayName ?: g.systemId else "${g.sizeBytes / 1024} KB")
                                    if (g.favorite) append(" · ♥")
                                    if (g.missing) append(" · storage not connected")
                                    else append(played?.let { " · played ${it.playCount}x · ${formatPlayTime(it.totalPlayMs)} · ${formatAgo(it.lastPlayedMs)}" } ?: " · not played yet")
                                }
                                GameCard(
                                    title = (if (g.favorite) "♥ " else "") + g.title, caption = caption,
                                    thumb = rememberThumb(thumbs.displayFile(g.systemId, g.fileName)), wide = true, width = 700.dp, height = 70.dp,
                                    modifier = tileFocus(i == 0, first, g.path == focusAfterDelete, afterDelete), onLongClick = { optionsFor = g },
                                ) { onPlay(g, coreFor(g)) }
                            }
                        }
                    }
                    Text("OK play   ·   Y favorite   ·   hold OK for options, controls and delete", fontSize = 11.sp, color = RB.Muted.copy(alpha = 0.8f), modifier = Modifier.padding(start = 6.dp, top = 2.dp))
                }
            }
        }

        optionsFor?.let { g ->
            GameOptions(
                game = g, thumb = rememberThumb(thumbs.displayFile(g.systemId, g.fileName)),
                onPlay = { optionsFor = null; onPlay(g, coreFor(g)) },
                onFavorite = { library.setFavorite(g, !g.favorite); optionsFor = null },
                onControls = { optionsFor = null; onControls(g) },
                onDelete = { optionsFor = null; deleting = g },
                onClose = { optionsFor = null },
            )
        }

        deleting?.let { g ->
            DeleteGamePanel(
                game = g, thumb = rememberThumb(thumbs.displayFile(g.systemId, g.fileName)),
                blocked = deleter.blockedReason(g), zipped = deleter.isZipped(g),
                onCancel = { deleting = null },
                onConfirm = { alsoSaves ->
                    deleting = null
                    // Focus goes to the tile that slides into the removed game's place (or the one before it if it was last).
                    val i = shown.indexOfFirst { it.path == g.path }
                    val neighbour = shown.getOrNull(i + 1) ?: shown.getOrNull(i - 1)
                    library.deleteGame(g, deleter, alsoSaves) { r -> focusAfterDelete = if (r.romRemoved) neighbour?.path else g.path }
                },
            )
        }
    }
}

/** Focus targets of a game tile: the first one (focused when the list opens) and the one to focus after a delete. */
private fun tileFocus(isFirst: Boolean, first: FocusRequester, isAfterDelete: Boolean, afterDelete: FocusRequester): Modifier {
    var m: Modifier = Modifier
    if (isFirst) m = m.focusRequester(first)
    if (isAfterDelete) m = m.focusRequester(afterDelete)
    return m
}

/** Small focusable pill used in the header and the filter row. */
@Composable
private fun HeaderChip(onClick: () -> Unit, selected: Boolean = false, wide: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) RB.Pink else Color(0xCC131A36), contentColor = RB.Text,
            focusedContainerColor = if (selected) RB.Pink else RB.PanelHi, focusedContentColor = RB.Text,
            pressedContainerColor = RB.PanelHi, pressedContentColor = RB.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x33FFFFFF)), shape = RoundedCornerShape(12.dp)),
            focusedBorder = Border(BorderStroke(2.dp, if (selected) Color.White else RB.Pink), shape = RoundedCornerShape(12.dp)),
        ),
    ) {
        Row(Modifier.padding(horizontal = if (wide) 14.dp else 10.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { content() }
    }
}

@Composable
private fun SearchBox(text: String, active: Boolean, onToggle: () -> Unit, onText: (String) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    LaunchedEffect(active) { if (active) runCatching { focus.requestFocus() } }
    Surface(
        onClick = onToggle,
        modifier = Modifier.width(if (active || text.isNotEmpty()) 250.dp else 170.dp).height(36.dp),   // narrow when idle so the header's buttons always fit
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xCC131A36), contentColor = RB.Text, focusedContainerColor = RB.PanelHi, focusedContentColor = RB.Text),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x33FFFFFF)), shape = RoundedCornerShape(12.dp)),
            focusedBorder = Border(BorderStroke(2.dp, RB.Pink), shape = RoundedCornerShape(12.dp)),
        ),
    ) {
        Row(Modifier.padding(horizontal = 12.dp).fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            UiIconView(UiIcon.SEARCH, RB.Muted, 16.dp)
            Spacer(Modifier.width(10.dp))
            if (active) {
                BasicTextField(
                    value = text, onValueChange = onText, singleLine = true,
                    textStyle = TextStyle(color = RB.Text, fontSize = 14.sp), cursorBrush = SolidColor(RB.Pink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onDone(); fm.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            } else {
                Text(text.ifEmpty { "Search games…" }, fontSize = 13.sp, color = if (text.isEmpty()) RB.Muted else RB.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(library: LibraryService, onOpenFolders: () -> Unit) {
    SectionTitle("No games found")
    FocusRow {
        Column {
            Text("The library looks in these folders:", fontSize = 14.sp)
            library.folders.forEach { Text("${if (it.available) "" else "⚠ not available · "}${it.label}", fontSize = 13.sp, color = if (it.available) RB.Cyan else RB.Warn) }
            Text("Copy ROMs you own into the app folder (USB transfer or adb push), or add a folder under Folders. Supported: .nes .sfc .smc .gb .gbc .gba (no .zip yet).", fontSize = 13.sp, color = RB.Muted)
        }
    }
    HeaderChip(onClick = onOpenFolders, wide = true) { Text("Open Folders", fontSize = 13.sp) }
}

@Composable
private fun GameOptions(game: Game, thumb: androidx.compose.ui.graphics.ImageBitmap?, onPlay: () -> Unit, onFavorite: () -> Unit, onControls: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    MenuPanel(width = 380.dp) {
        MenuHeader(game.title, Systems.byId(game.systemId)?.displayName, null, thumb, subtitle = game.fileName)
        MenuRow(MenuGlyph.PLAY, Color.White, "Play", Modifier.focusRequester(first), onClick = onPlay)
        MenuRow(MenuGlyph.HEART, RB.Pink, if (game.favorite) "Remove from Favorites" else "Add to Favorites", onClick = onFavorite)
        MenuRow(MenuGlyph.GAMEPAD, Color(0xFF8FB4FF), "Controls for this Game", onClick = onControls)
        MenuRow(MenuGlyph.TRASH, DeleteRed, "Delete game…", onClick = onDelete)
        MenuDivider()
        MenuRow(MenuGlyph.BACK, Color(0xFFB8C4FF), "Close", chevron = false, onClick = onClose)
    }
}

private val DeleteRed = Color(0xFFFF6B6B)

/**
 * Asks before deleting a game. Focus starts on "No, keep it" (Back also cancels); save states and cartridge saves are kept
 * unless the second row is switched to "Delete too". For a game RetroBox may not delete ([blocked]) it only explains why.
 */
@Composable
private fun DeleteGamePanel(game: Game, thumb: androidx.compose.ui.graphics.ImageBitmap?, blocked: String?, zipped: Boolean, onCancel: () -> Unit, onConfirm: (alsoSaves: Boolean) -> Unit) {
    val first = remember { FocusRequester() }
    var alsoSaves by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    MenuPanel(width = 420.dp) {
        MenuHeader(game.title, Systems.byId(game.systemId)?.displayName, null, thumb, subtitle = game.fileName)
        if (blocked != null) {
            MenuNote(blocked, RB.Warn)
            MenuRow(MenuGlyph.BACK, Color(0xFFB8C4FF), "OK", Modifier.focusRequester(first), chevron = false, onClick = onCancel)
        } else {
            MenuNote(
                "Delete this game from the TV? The game file${if (zipped) " (the whole .zip)" else ""}, its cover art and its picture are removed. This can't be undone.",
                RB.Text,
            )
            MenuRow(MenuGlyph.CLOSE, Color(0xFFB8C4FF), "No, keep it", Modifier.focusRequester(first), chevron = false, onClick = onCancel)
            MenuValueRow(MenuGlyph.SAVE, Color(0xFF8FB4FF), "Save states + saves", if (alsoSaves) "Delete too" else "Keep", onPrev = { alsoSaves = !alsoSaves }, onNext = { alsoSaves = !alsoSaves })
            MenuNote(if (alsoSaves) "Save slots, Quick Resume and the cartridge save will be deleted as well." else "Save slots, Quick Resume and the cartridge save stay (they are used again if you copy the game back).")
            MenuRow(MenuGlyph.TRASH, DeleteRed, "Yes, delete game", chevron = false, onClick = { onConfirm(alsoSaves) })
        }
    }
}
