package com.retrobox.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.retrobox.tv.emulator.SystemStatus
import com.retrobox.tv.emulator.Systems
import com.retrobox.tv.game.AppRomRepository
import com.retrobox.tv.game.GameActivity
import com.retrobox.tv.game.PrefsCorePreferences
import com.retrobox.tv.game.SessionInfo
import com.retrobox.tv.library.FilePlayHistory
import com.retrobox.tv.save.SaveStore
import com.retrobox.tv.library.Game
import com.retrobox.tv.library.titleFromFileName
import com.retrobox.tv.library.SafWalker
import com.retrobox.tv.library.GameDeleter
import com.retrobox.tv.library.ThumbnailStore
import com.retrobox.tv.profile.ProfileScope
import com.retrobox.tv.profile.ProfileStore
import com.retrobox.tv.ui.AboutScreen
import com.retrobox.tv.ui.ControllersScreen
import com.retrobox.tv.ui.DiagnosticsScreen
import com.retrobox.tv.ui.FavoriteGame
import com.retrobox.tv.ui.fullBackdrop
import com.retrobox.tv.ui.FoldersScreen
import com.retrobox.tv.ui.HomeScreen
import com.retrobox.tv.ui.LibraryScreen
import com.retrobox.tv.ui.MappingScreen
import com.retrobox.tv.ui.RB
import com.retrobox.tv.ui.RecentGame
import com.retrobox.tv.ui.RetroBoxTheme
import com.retrobox.tv.ui.SettingsScreen
import com.retrobox.tv.settings.AppSettings
import com.retrobox.tv.settings.SettingsStore
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import com.retrobox.tv.ui.Screen
import com.retrobox.tv.util.ErrorLog
import java.io.File

class MainActivity : ComponentActivity() {
    private val monitor get() = (application as RetroBoxApp).controllerMonitor
    private val library get() = (application as RetroBoxApp).library

    private val romRepo by lazy(LazyThreadSafetyMode.NONE) { AppRomRepository(this) }
    private val corePrefs by lazy(LazyThreadSafetyMode.NONE) { PrefsCorePreferences(this) }
    private val history by lazy(LazyThreadSafetyMode.NONE) { FilePlayHistory(File(filesDir, "history.tsv")) }
    // Cover art is read from the app's shared folder (/sdcard/Android/data/<pkg>/files/artwork), where adb or USB can put it.
    private val thumbs by lazy(LazyThreadSafetyMode.NONE) { ThumbnailStore(File(filesDir, "thumbs"), File(getExternalFilesDir(null) ?: filesDir, "artwork")) }
    private val profiles by lazy(LazyThreadSafetyMode.NONE) { ProfileStore(File(filesDir, "profiles.json")) }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { SettingsStore(File(filesDir, "settings.json")) }
    private val exportFile get() = File(getExternalFilesDir(null) ?: filesDir, "profiles/retrobox-profiles.json")
    private var historyVersion by mutableIntStateOf(0)   // bumped on resume so Home re-reads what was just played

    /** While the mapping screen waits for a key it takes every key event before normal handling. */
    private var keyInterceptor: ((KeyEvent) -> Boolean)? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) library.addSafFolder(uri)
    }

    override fun onResume() { super.onResume(); historyVersion++ }

    private fun launch(g: Game) = launch(g.systemId, g.path, g.fileName, corePrefs.chosenCoreId(g.systemId) ?: "")

    private fun launch(systemId: String, path: String, fileName: String, coreId: String, resume: Boolean = false) {
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ROM, path).putExtra(GameActivity.EXTRA_NAME, fileName)
            .putExtra(GameActivity.EXTRA_SYSTEM, systemId).putExtra(GameActivity.EXTRA_CORE, coreId)
            .putExtra(GameActivity.EXTRA_RESUME, resume))
    }
    private val saveStore by lazy(LazyThreadSafetyMode.NONE) { SaveStore(File(filesDir, "saves")) }
    private val gameDeleter by lazy(LazyThreadSafetyMode.NONE) { GameDeleter(romRepo.roots, thumbs, saveStore, history) }

    private fun exportProfiles(): String = runCatching {
        profiles.export(profiles.load(), exportFile); "Saved to ${exportFile.absolutePath}  (copy it with adb pull / USB)"
    }.getOrElse { "Export failed: ${it.message}" }

    private fun importProfiles(): String {
        if (!exportFile.exists()) return "Nothing to import: put a file at ${exportFile.absolutePath}"
        return runCatching {
            val r = profiles.import(exportFile)
            profiles.save(r.set)
            "Imported controls from that file" + if (r.skippedEntries > 0) " (${r.skippedEntries} unusable entries skipped)" else ""
        }.getOrElse { "Import failed: ${it.message ?: "not a RetroBox controls file"}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        romRepo.roots.size   // creates the app ROM folders up front so they can be filled over ADB/USB
        library.games        // creating the service starts the first scan in the background
        // A leftover lock means the previous game session did not exit cleanly (crash / killed).
        File(filesDir, GameActivity.LOCK_FILE).takeIf { it.exists() }?.let {
            ErrorLog.w("session", "Previous session ended unexpectedly: ${it.readText()}")
            it.delete()
        }
        setContent {
            RetroBoxTheme {
                val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
                var lastFocus by rememberSaveable { mutableStateOf("hero") }
                // Re-read when the app comes back to the front: the game screen can change settings too.
                var settings by remember(historyVersion) { mutableStateOf(settingsStore.load()) }
                val settingsProblem = remember(historyVersion) { settingsStore.lastProblem }
                BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
                // The safe area keeps every screen away from the edges a TV may crop (Settings > Safe Area).
                // The backdrop is painted once across the whole screen so the margin is not a visible frame.
                BoxWithConstraints(Modifier.fillMaxSize().background(RB.Bg).fullBackdrop()) {
                    androidx.compose.runtime.CompositionLocalProvider(com.retrobox.tv.ui.LocalBackdropProvided provides true) {
                    Box(Modifier.fillMaxSize().padding(horizontal = maxWidth * settings.safeAreaPct / 100, vertical = maxHeight * settings.safeAreaPct / 100)) {
                    when (val top = stack.last()) {
                        Screen.Home -> HomeScreen(
                            recent = remember(historyVersion, library.games) {
                                // Only games that can still be started: the system must be playable, and a plain-file ROM must still exist.
                                history.recent(10).mapNotNull { e ->
                                    val sys = Systems.byId(e.systemId)?.takeIf { it.status != SystemStatus.PLANNED } ?: return@mapNotNull null
                                    val file = com.retrobox.tv.library.ZipRom.split(e.romPath)?.first ?: e.romPath
                                    if (!file.startsWith("content://") && !File(file).isFile) return@mapNotNull null
                                    RecentGame(e.copy(title = titleFromFileName(e.fileName)), sys.displayName, thumbs.displayFile(e.systemId, e.fileName),
                                        coreName = sys.cores.firstOrNull { it.id == e.coreId }?.displayName ?: sys.defaultCore?.displayName ?: "",
                                        canResume = saveStore.stateFile(e.systemId, e.fileName, 0).exists(), game = library.gameFor(e.romPath))
                                }.take(8)
                            },
                            onPlayRecent = { g, resume -> launch(g.entry.systemId, g.entry.romPath, g.entry.fileName, g.entry.coreId, resume) },
                            controllerCount = monitor.pads.size,
                            libraryCount = library.games.size,
                            gamesPerSystem = remember(library.games) { library.games.groupingBy { it.systemId }.eachCount() },
                            favorites = remember(library.games) {
                                library.games.filter { it.favorite && !it.missing }.sortedBy { it.title.lowercase() }.take(12)
                                    .map { FavoriteGame(it, Systems.byId(it.systemId)?.displayName ?: it.systemId, thumbs.displayFile(it.systemId, it.fileName)) }
                            },
                            onPlayFavorite = { launch(it.game) },
                            onToggleFavorite = { library.setFavorite(it, !it.favorite) },
                            lastFocusId = lastFocus,
                            onFocused = { lastFocus = it },
                            onOpen = { stack.add(it) },
                            onSystem = { stack.add(Screen.Library(it)) },
                        )
                        Screen.Controllers -> ControllersScreen(
                            monitor, profiles, onEditControls = { stack.add(Screen.Mapping(it)) }, onExport = ::exportProfiles, onImport = ::importProfiles,
                            onDone = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                        )
                        Screen.Settings -> SettingsScreen(
                            settings, settingsProblem,
                            onChange = { f ->
                                settings = f(settings)
                                runCatching { settingsStore.save(settings) }.onFailure { ErrorLog.e("settings", "Settings not saved", it) }
                            },
                            onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                            onAbout = { stack.add(Screen.About) },
                        )
                        Screen.About -> AboutScreen(onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) })
                        Screen.Diagnostics -> DiagnosticsScreen(sessionInfo = SessionInfo.lines, settings = settings, onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) })
                        Screen.Folders -> FoldersScreen(
                            library, pickerAvailable = SafWalker.pickerAvailable(this@MainActivity),
                            appFolderHelp = romRepo.roots.joinToString("\n") { it.path } + "\nadb push game.sfc <folder>/   (launch the app once first so the folder exists)",
                            onAddFolder = { runCatching { pickFolder.launch(null) }.onFailure { ErrorLog.e("library", "Folder picker failed", it) } },
                            onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                        )
                        is Screen.Library -> LibraryScreen(
                            system = top.system, startWithFavorites = top.favorites, library = library, history = history, thumbs = thumbs, deleter = gameDeleter,
                            corePrefs = corePrefs, historyVersion = historyVersion,
                            initialView = settings.libraryView,
                            onViewChange = { v ->
                                settings = settings.copy(libraryView = v)
                                runCatching { settingsStore.save(settings) }.onFailure { ErrorLog.e("settings", "Library view not saved", it) }
                            },
                            onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                            onPlay = { g, coreId -> launch(g.systemId, g.path, g.fileName, coreId) },
                            onControls = { stack.add(Screen.Mapping(ProfileScope.Game(it.systemId, it.fileName))) },
                            onOpenFolders = { stack.add(Screen.Folders) },
                        )
                        is Screen.Mapping -> MappingScreen(top.scope, profiles, monitor, onDone = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }, installInterceptor = { keyInterceptor = it })
                    }
                    }
                    }
                }
            }
        }
    }

    // Passive tap on raw input for the controller monitor; never consumes events (except while the mapping screen waits for a key).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keyInterceptor?.let { if (it(event)) return true }
        monitor.onKey(event)
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        monitor.onMotion(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    // Gamepad B acts as Back in menus (menu navigation is configured separately from gameplay mapping).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B && event.repeatCount == 0) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
