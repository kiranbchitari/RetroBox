package com.retrobox.tv.game

import android.opengl.GLSurfaceView
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.retrobox.tv.diag.ReportLine
import com.retrobox.tv.emulator.AudioOut
import com.retrobox.tv.emulator.CoreResult
import com.retrobox.tv.emulator.EmulationSession
import com.retrobox.tv.emulator.FastForward
import com.retrobox.tv.emulator.Turbo
import com.retrobox.tv.emulator.EmulatorCore
import com.retrobox.tv.emulator.LibretroCore
import com.retrobox.tv.emulator.NativeHost
import com.retrobox.tv.emulator.Systems
import android.net.Uri
import com.retrobox.tv.library.FilePlayHistory
import com.retrobox.tv.library.RomAccess
import com.retrobox.tv.library.RomUnavailableException
import com.retrobox.tv.library.titleFromFileName
import com.retrobox.tv.input.ControllerMonitor
import com.retrobox.tv.profile.ProfileResolver
import com.retrobox.tv.profile.ProfileScope
import com.retrobox.tv.profile.ProfileSet
import com.retrobox.tv.profile.ProfileStore
import com.retrobox.tv.library.PlayHistory
import com.retrobox.tv.library.ThumbnailStore
import com.retrobox.tv.save.SaveStore
import com.retrobox.tv.save.SlotInfo
import com.retrobox.tv.save.StateDecode
import com.retrobox.tv.save.StateMeta
import com.retrobox.tv.settings.AppSettings
import com.retrobox.tv.settings.AudioSettings
import com.retrobox.tv.settings.GraphicsSettings
import com.retrobox.tv.settings.SettingsStore
import com.retrobox.tv.ui.RetroBoxTheme
import com.retrobox.tv.util.ErrorLog
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Full-screen gameplay. Emulation runs on its own thread; this activity only handles input, lifecycle and the pause menu. */
class GameActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var glView: GLSurfaceView
    private lateinit var store: SaveStore
    private lateinit var settingsStore: SettingsStore
    private var core: EmulatorCore? = null
    private var session: EmulationSession? = null
    private var audio: AudioOut? = null
    private var players: PlayerInputs? = null
    private var profileSet = ProfileSet()
    private var inputManager: InputManager? = null
    private var playerNames by mutableStateOf<List<String>>(emptyList())
    private var player2Custom by mutableStateOf(false)
    private var statusWarning by mutableStateOf(false)
    private var lostPlayers = setOf<Int>()   // players whose pad was unplugged and has not been replaced
    private var systemId = ""
    private var romName = ""
    private var coreId = ""
    private var lastSram: ByteArray? = null
    private lateinit var history: PlayHistory
    private lateinit var thumbs: ThumbnailStore
    private var romPath = ""
    private var playedMs = 0L        // time the game actually ran (menu time excluded)
    private var segmentStartMs = 0L  // 0 = not running
    private var finishing = false

    private var menu by mutableStateOf<MenuPage?>(null)
    private var status by mutableStateOf<String?>(null)
    private var slots by mutableStateOf<List<SlotInfo>>(emptyList())
    private var settings by mutableStateOf(AppSettings())
    private var shaderProblem by mutableStateOf<String?>(null)
    private var audioNote by mutableStateOf("")
    private var overlayText by mutableStateOf("")
    private var infoLine by mutableStateOf("")
    private var title by mutableStateOf("Paused")
    private var systemName by mutableStateOf("")

    // Fast-forward: held (R2 / Space / analog trigger) or switched on from the pause menu.
    private var ffHeld = false
    private val ffTriggers = HashSet<Int>()   // devices whose analog R2 is pulled past half
    private val ffTrigger get() = ffTriggers.isNotEmpty()
    private var ffLatched by mutableStateOf(false)
    private var ffActive by mutableStateOf(false)

    private fun updateSpeed() {
        ffActive = ffLatched || ffHeld || ffTrigger
        session?.speed = if (ffActive) settings.fastForwardSpeed else FastForward.NORMAL
    }

    /** Buttons for [port]: through the session (which adds turbo) while a game runs, straight to the host otherwise. */
    private fun sendInput(port: Int, mask: Int) {
        session?.setInput(port, mask) ?: NativeHost.setInput(port, mask, 0, 0, 0, 0)
    }

    private val playedPads = HashSet<Int>()   // gamepads that sent gameplay input this session

    private val chordRunnable = Runnable { if (players?.selectAndStartDown == true) openMenu() }

    /** A controller was unplugged: free its player slot, release its buttons and stop the game on the menu so nobody plays on without noticing. */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            val ps = players ?: return
            if (ffTriggers.remove(deviceId)) updateSpeed()
            if (ps.players == 1) {
                // One player: every pad drives Player 1, so a pad that played and vanished (flat battery) may have left a
                // direction or R2 held with no release event ever coming. Release everything and pause.
                if (!playedPads.remove(deviceId)) return
                ps.clear(); sendInput(0, 0); ffHeld = false; updateSpeed()
                if (finishing) return
                if (menu == null) openMenu()
                status = "Controller disconnected. Reconnect it, or continue with another controller, the keyboard or the remote."
                statusWarning = true
                return
            }
            val p = ps.assigner.release(deviceId) ?: return
            ps.clear(p); sendInput(p, 0)
            if (finishing) return
            if (menu == null) openMenu()
            lostPlayers = lostPlayers + p
            status = if (lostPlayers.size > 1) "Both controllers disconnected. Reconnect them, or continue with the keyboard / remote (Player 1)."
            else "Player ${p + 1}'s controller disconnected. Reconnect it (it takes the free player), or continue without."
            statusWarning = true
            refreshPlayers()
        }
    }
    private val sramTick = object : Runnable {
        override fun run() { persistSram(); ui.postDelayed(this, SRAM_AUTOSAVE_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        store = SaveStore(File(filesDir, "saves"))
        settingsStore = SettingsStore(File(filesDir, "settings.json"))
        settings = settingsStore.load()
        settingsStore.lastProblem?.let { ErrorLog.w("settings", it) }
        history = FilePlayHistory(File(filesDir, "history.tsv"))
        thumbs = ThumbnailStore(File(filesDir, "thumbs"), File(getExternalFilesDir(null) ?: filesDir, "artwork"))

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = NativeHost.glInit()
                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = NativeHost.glResize(width, height)
                override fun onDrawFrame(gl: GL10?) = NativeHost.glDraw()
            })
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        val menuView = ComposeView(this).apply {
            setContent {
                RetroBoxTheme {
                    val m = menu
                    if (m != null) PauseMenu(m, title, systemName, com.retrobox.tv.ui.rememberThumb(thumbs.displayFile(systemId, romName)), infoLine, status, slots, pauseSettings(), actions())
                    else {
                        if (settings.overlay && overlayText.isNotEmpty()) FpsOverlay(overlayText, settings.safeAreaPct)
                        if (ffActive) FastForwardBadge(FastForward.label(settings.fastForwardSpeed), settings.safeAreaPct)
                    }
                }
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(-1, -1))
            addView(menuView, FrameLayout.LayoutParams(-1, -1))
        })

        val romKey = intent.getStringExtra(EXTRA_ROM)
        val sysId = intent.getStringExtra(EXTRA_SYSTEM)
        val system = sysId?.let { Systems.byId(it) }
        if (romKey == null || system == null) { showError("No game was specified."); return }
        systemId = system.id
        systemName = system.displayName
        applyDisplay()
        val fileName = intent.getStringExtra(EXTRA_NAME) ?: Uri.decode(romKey.substringAfterLast('/')).substringAfterLast(':').substringAfterLast('/')
        startGame(system, romKey, fileName, intent.getStringExtra(EXTRA_CORE))
    }

    /** [romKey] is a file path or a content:// document URI; [fileName] is its display name with extension. */
    private fun startGame(system: com.retrobox.tv.emulator.SystemAdapter, romKey: String, fileName: String, chosenCore: String?) {
        val coreSpec = system.coreFor(chosenCore) ?: return showError("No emulator core is configured for ${system.displayName}.")
        coreId = coreSpec.id
        romName = fileName
        romPath = romKey
        title = titleFromFileName(fileName)
        File(filesDir, LOCK_FILE).writeText("${system.id}|$romKey|${System.currentTimeMillis()}")
        SessionInfo.set("Selected core" to coreSpec.displayName, "Current ROM" to fileName, "Status" to "loading")
        val screenHeightPx = resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
        Thread({
            val rom = try { RomAccess.materialize(this, romKey, fileName) } catch (e: RomUnavailableException) {
                runOnUiThread { if (!isDestroyed && !finishing) showError(e.message ?: "The ROM is not available.") }
                return@Thread
            }
            val factory = LibretroCore.Factory(NativeHost, File(applicationInfo.nativeLibraryDir), File(getExternalFilesDir(null) ?: filesDir, "system"), File(filesDir, "saves/core"), screenHeightPx)
            val result = factory.create(system, coreSpec, rom) { isDestroyed || finishing }
            runOnUiThread {
                if (isDestroyed || finishing) { (result as? CoreResult.Ok)?.value?.close(); return@runOnUiThread }
                when (result) {
                    is CoreResult.Err -> showError(result.error.message)
                    is CoreResult.Ok -> onCoreReady(result.value)
                }
            }
        }, "core-load").start()
    }

    private fun onCoreReady(c: EmulatorCore) {
        core = c
        profileSet = ProfileStore(File(filesDir, "profiles.json")).load()
        val buttons = c.system.buttons.toSet()
        val inputs = (0 until c.system.players).map { p -> GameInput(ProfileResolver.resolve(DefaultMappings.gamepad, profileSet, systemId, romName, p), buttons, fourWayStick = c.system.stickFourWay) }
        val assigner = PlayerAssigner(c.system.players)
        assigner.assignInitial(connectedGamepadIds())
        players = PlayerInputs(inputs, assigner)
        inputManager = (getSystemService(INPUT_SERVICE) as InputManager).also { it.registerInputDeviceListener(deviceListener, ui) }
        if (c.system.players > 1) {
            ErrorLog.i("input", "${c.system.players}-player game: pads ${(0 until c.system.players).map { assigner.deviceOf(it)?.let { id -> InputDevice.getDevice(id)?.name ?: "device $id" } ?: "-" }}")
        }
        refreshPlayers()
        store.readSram(systemId, romName)?.let { if (!c.loadSaveRam(it)) ErrorLog.w("save", "Cartridge save for $romName was not accepted by the core") }
        // Baseline = what the core holds now (loaded save, or its blank/0xFF default): only genuine changes are written,
        // so games without battery RAM (and GBA cores that always expose 128 KiB) never produce empty .srm files.
        lastSram = runCatching { c.saveRam() }.getOrNull()

        audio = AudioOut.create(c.av.sampleRate, settings.audio.bufferMs)?.also { it.gain = settings.audio.gain }
        if (audio == null) status = "Audio could not be initialized; continuing without sound."
        val s = EmulationSession(c, audio, onFrame = { glView.requestRender() })
        s.turboMask = Turbo.mask(settings.turboFor(systemId))
        session = s
        updateSpeed()
        refreshInfo()
        s.start()
        if (intent.getBooleanExtra(EXTRA_RESUME, false)) queueResume(s)
        // Loading can finish after the player pressed Home: then start on the pause menu instead of playing in the background.
        val inForeground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        if (inForeground) s.resume() else openMenu()
        ui.postDelayed(sramTick, SRAM_AUTOSAVE_MS)
        ui.postDelayed(infoTick, 1000)
        history.recordStart(systemId, romPath, romName, titleFromFileName(romName), coreId)
        segmentStartMs = if (inForeground) System.currentTimeMillis() else 0
        applyDisplay()
    }

    /** Quick Resume: load the session saved automatically when this game was last quit (slot 0), if it belongs to this core. */
    private fun queueResume(s: EmulationSession) {
        val d = store.read(systemId, romName, 0)
        if (d !is StateDecode.Ok || d.meta.coreId != coreId) { ErrorLog.i("save", "Quick Resume: no usable saved session for $romName; starting normally"); return }
        // The session is only saved on a clean quit. If the game saved to its cartridge later (a session that ended in a
        // crash or was killed), restoring the old session would bring back the old cartridge save and the autosave would
        // overwrite the newer one. Start normally instead.
        val sram = store.sramFile(systemId, romName)
        if (sram.exists() && sram.lastModified() > d.meta.timestampMs + 2000) {
            ErrorLog.w("save", "Quick Resume skipped for $romName: its cartridge save is newer than the saved session")
            status = "Quick Resume skipped: the game's own save is newer than the saved session, so the game started normally."
            return
        }
        s.post { c ->
            c.runFrame()   // let the core finish its first-frame setup before restoring
            val ok = runCatching { c.loadState(d.payload) }.getOrDefault(false)
            if (!ok) ErrorLog.w("save", "Quick Resume: the core rejected the saved session for $romName")
        }
    }

    private val infoTick = object : Runnable {
        override fun run() { refreshInfo(); ui.postDelayed(this, 1000) }
    }

    private fun refreshInfo() {
        val c = core ?: return
        val st = NativeHost.stats()
        val fps = session?.lastFps ?: 0.0
        infoLine = "${c.description.name} ${c.description.version} · ${st[5]}x${st[6]} @ ${c.av.sampleRate} Hz · ${"%.1f".format(fps)} fps · run avg ${st[1] / 1000.0}ms max ${st[2] / 1000.0}ms · audio underruns ${audio?.underruns ?: -1} dropped ${audio?.droppedFrames ?: 0}"
        val rs = NativeHost.renderStats()   // window since the previous tick; only meaningful while the overlay (timing) is on
        val g = settings.graphicsFor(systemId)
        shaderProblem = NativeHost.shaderStatus().trim().ifEmpty { null }
        audioNote = audio?.let { "This session: ${it.underruns} underruns, ${it.droppedFrames} dropped frames." } ?: "Audio is not running."
        overlayText = "%.0f fps · emu %.1f ms · draw %.1f ms".format(fps, st[1] / 1000.0, rs[1] / 1000.0)
        // One line per second while the overlay is on, so presets can be compared from `adb logcat -s RetroBoxPerf`.
        if (settings.overlay && rs[0] > 0) android.util.Log.i("RetroBoxPerf", "system=$systemId look=${g.shader.label} aspect=${g.scale.label} smooth=${g.effectiveBilinear} fps=${"%.1f".format(fps)} emuAvgMs=${"%.2f".format(st[1] / 1000.0)} draws=${rs[0]} drawAvgMs=${"%.2f".format(rs[1] / 1000.0)} drawMaxMs=${"%.2f".format(rs[2] / 1000.0)} drawIntervalMs=${"%.1f".format(rs[3] / 1000.0)}")
        SessionInfo.set(
            "Selected core" to "${c.description.name} ${c.description.version}",
            "Current ROM" to c.romFile.name,
            "Video" to "core ${c.av.width}x${c.av.height} aspect ${"%.3f".format(c.av.aspect)}, last frame ${st[5]}x${st[6]} (pixel format ${st[4]})",
            "Frame rate (measured / core)" to "${"%.1f".format(fps)} / ${"%.2f".format(c.av.fps)}",
            "retro_run avg / max" to "${st[1] / 1000.0} ms / ${st[2] / 1000.0} ms",
            "Renderer" to NativeHost.glInfo().ifEmpty { "not started" },
            "Graphics in use" to "${g.scale.label}, ${if (g.effectiveBilinear) "smooth" else "sharp"}, look: ${g.shader.label}, safe area ${settings.safeAreaPct}%" + if (settings.hasOverride(systemId)) " (this system differs from the defaults)" else "",
            "Render timing" to if (settings.overlay && rs[0] > 0) "${rs[0]} draws in the last window, avg ${"%.2f".format(rs[1] / 1000.0)} ms (incl. GPU via glFinish), max ${"%.2f".format(rs[2] / 1000.0)} ms, ${"%.1f".format(rs[3] / 1000.0)} ms between draws" else "off (turn on Graphics > FPS Overlay)",
            "Shader problems" to (shaderProblem ?: "none"),
            "Audio" to (audio?.let { "${it.sampleRate} Hz, buffer ${it.bufferFrames * 1000 / it.sampleRate} ms, underruns ${it.underruns}, dropped frames ${it.droppedFrames}, stalls ${it.stallEvents}" } ?: "NOT INITIALIZED"),
        )
    }

    // ---- players ----

    private fun connectedGamepadIds(): List<Int> =
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { ControllerMonitor.isGamepad(it) }.map { it.id }

    /** Names of the pads behind each player slot for the Players page; pads connected but not yet touched take the free slots first. */
    private fun refreshPlayers() {
        val ps = players ?: return
        if (ps.players > 1) connectedGamepadIds().sorted().forEach { ps.assigner.claim(it) }
        lostPlayers = lostPlayers.filter { ps.assigner.deviceOf(it) == null }.toSet()
        playerNames = (0 until ps.players).map { p ->
            val id = ps.assigner.deviceOf(p)
            if (id != null) InputDevice.getDevice(id)?.name ?: "controller $id"
            else if (p == 0) "keyboard / remote (no controller)" else "no controller yet"
        }
        player2Custom = listOf(ProfileScope.Global, ProfileScope.System(systemId), ProfileScope.Game(systemId, romName)).any { profileSet.isPlayer2Custom(it) }
    }

    private fun swapPlayers() {
        val ps = players ?: return
        ps.assigner.swap(); ps.clear()
        for (p in 0 until ps.players) sendInput(p, 0)
        refreshPlayers()
        status = "Players swapped."
    }

    // ---- menu ----

    private fun actions() = MenuActions(
        resume = ::closeMenu,
        open = { menu = it; if (it == MenuPage.SaveSlots || it == MenuPage.LoadSlots) slots = store.slots(systemId, romName) },
        saveTo = ::saveState,
        loadFrom = ::loadState,
        reset = { session?.post { it.reset() }; closeMenu() },
        quit = ::quitGame,
        setGraphics = { g -> update { it.withSystemGraphics(systemId, g) } },
        resetGraphics = { update { it.withoutSystemOverride(systemId) } },
        setSafeArea = { p -> update { it.copy(safeAreaPct = p) } },
        setOverlay = { on -> update { it.copy(overlay = on) } },
        setAudio = ::changeAudio,
        swapPlayers = ::swapPlayers,
        setFastForward = { on -> ffLatched = on; updateSpeed() },
        setFastForwardSpeed = { speed -> update { it.copy(fastForwardSpeed = speed) }; updateSpeed() },
        setTurbo = { set -> update { it.withTurbo(systemId, set) }; session?.turboMask = Turbo.mask(set) },
    )

    private fun pauseSettings() = PauseSettings(
        settings.graphicsFor(systemId), settings.hasOverride(systemId), settings.safeAreaPct, settings.overlay, settings.audio, shaderProblem, audioNote,
        players = players?.players ?: 1, playerNames = playerNames, player2Custom = player2Custom, statusWarning = statusWarning,
        fastForwardOn = ffLatched, fastForwardSpeed = settings.fastForwardSpeed,
        turbo = settings.turboFor(systemId), turboChoices = Systems.byId(systemId)?.let { Turbo.choices(it) } ?: listOf(emptySet()), systemId = systemId,
    )

    /** Applies a settings change at once and writes it down. */
    private fun update(change: (AppSettings) -> AppSettings) {
        settings = change(settings)
        runCatching { settingsStore.save(settings) }.onFailure { ErrorLog.e("settings", "Settings not saved", it); status = "Could not save settings: ${it.message}" }
        applyDisplay()
    }

    private fun applyDisplay() {
        val g = settings.graphicsFor(systemId)
        NativeHost.setDisplay(g.scale.code, if (g.bilinear) 1 else 0, g.shader.code, settings.safePermille)
        NativeHost.setRenderTiming(settings.overlay)
        if (::glView.isInitialized) glView.requestRender()
    }

    private fun changeAudio(next: AudioSettings) {
        val bufferChanged = next.bufferMs != settings.audio.bufferMs
        update { it.copy(audio = next) }
        audio?.gain = next.gain
        val c = core
        if (bufferChanged && c != null && session != null) {
            // A new buffer size means a new AudioTrack; it is swapped in on the emulation thread.
            val fresh = AudioOut.create(c.av.sampleRate, next.bufferMs)?.also { it.gain = next.gain }
            if (fresh == null) status = "Audio could not be restarted with that buffer size; keeping the old one."
            else { session?.replaceAudio(fresh); audio = fresh }
        }
    }

    private fun openMenu() {
        if (menu != null || session == null) return
        endPlaySegment()
        saveThumbnail(async = true)
        session?.pause()
        ffHeld = false; ffTriggers.clear(); updateSpeed()   // a held trigger is released by opening the menu
        players?.clear(); for (p in 0..1) sendInput(p, 0)
        status = null; statusWarning = false
        refreshPlayers()
        refreshInfo()
        session?.post { c -> c.drainMessages()?.let { ErrorLog.w("core", it.trim()) } }
        menu = MenuPage.Main
    }

    private fun closeMenu() { menu = null; status = null; statusWarning = false; session?.resume(); if (session != null) segmentStartMs = System.currentTimeMillis() }

    private fun endPlaySegment() {
        if (segmentStartMs > 0) { playedMs += System.currentTimeMillis() - segmentStartMs; segmentStartMs = 0 }
    }

    /** Keeps the game's current frame as its picture in the library (the frame must be read now: the core is freed on quit). */
    private fun saveThumbnail(async: Boolean) {
        if (romPath.isEmpty()) return
        val snap = runCatching { NativeHost.snapshot() }.getOrNull() ?: return
        if (snap.size < 3) return
        val w = snap[0]; val h = snap[1]; val px = snap.copyOfRange(2, snap.size)
        val job = { runCatching { thumbs.save(systemId, romName, px, w, h) }.onFailure { ErrorLog.w("library", "Thumbnail not saved: ${it.message}") }; Unit }
        if (async) Thread(job, "thumb").start() else job()
    }

    private fun saveState(slot: Int) {
        val s = session ?: return
        s.post { c ->
            val bytes = runCatching { c.saveState() }.getOrNull()
            val msg = if (bytes == null || bytes.isEmpty()) "This core does not support save states."
            else try {
                store.write(systemId, romName, slot, StateMeta(coreId, c.description.version, romName, System.currentTimeMillis(), bytes.size), bytes)
                "Saved to slot $slot"
            } catch (e: Exception) { ErrorLog.e("save", "State write failed", e); "Could not write the save state: ${e.message}" }
            // Only touch the menu if it is still open: the player may have resumed or quit while the state was written.
            runOnUiThread { if (menu != null && !finishing) { slots = store.slots(systemId, romName); status = msg; menu = MenuPage.Main } }
        }
    }

    private fun loadState(slot: Int) {
        val s = session ?: return
        val decoded = store.read(systemId, romName, slot)
        if (decoded == null) { status = "Slot $slot is empty."; menu = MenuPage.Main; return }
        if (decoded is StateDecode.Corrupt) { status = "Slot $slot is damaged (${decoded.reason}); nothing was changed."; menu = MenuPage.Main; return }
        decoded as StateDecode.Ok
        if (decoded.meta.coreId != coreId) { status = "Slot $slot was saved with another emulator core; nothing was changed."; menu = MenuPage.Main; return }
        s.post { c ->
            val ok = runCatching { c.loadState(decoded.payload) }.getOrDefault(false)
            runOnUiThread {
                if (menu == null || finishing) return@runOnUiThread
                if (ok) closeMenu() else { status = "The core rejected slot $slot; the game continues unchanged."; menu = MenuPage.Main }
            }
        }
    }

    private fun persistSram() {
        val s = session ?: return
        s.post { c ->
            val ram = runCatching { c.saveRam() }.getOrNull() ?: return@post
            if (ram.isNotEmpty() && !ram.contentEquals(lastSram)) {
                runCatching { store.writeSram(systemId, romName, ram); lastSram = ram }.onFailure { ErrorLog.e("save", "SRAM write failed", it) }
            }
        }
    }

    private fun quitGame() { shutdown(); finish() }

    private fun showError(message: String) {
        ErrorLog.e("game", message)
        SessionInfo.set("Selected core" to coreId.ifEmpty { "none" }, "Current ROM" to romName.ifEmpty { "none" }, "Last error" to message)
        title = "Cannot start game"
        menu = MenuPage.Error(message)
    }

    // ---- input ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menu != null || session == null) {
            if (menu is MenuPage.Error || menu == null) return super.dispatchKeyEvent(event)
            // Back inside the menu goes up a level / resumes.
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (menu == MenuPage.Main) closeMenu() else menu = MenuPage.Main
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_UP) { if (menu == MenuPage.Main) closeMenu() else menu = MenuPage.Main; return true }
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode in Hotkeys.menuKeys) {
            if (event.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        val ps = players ?: return super.dispatchKeyEvent(event)
        val gamepad = event.device?.let { ControllerMonitor.isGamepad(it) } ?: isGamepadSource(event.source)
        if (gamepad) playedPads += event.deviceId
        if (event.keyCode in Hotkeys.fastForwardKeys && !ps.anyPlays(event.keyCode)) {
            if (event.repeatCount == 0) { ffHeld = event.action == KeyEvent.ACTION_DOWN; updateSpeed() }
            return true
        }
        val route = ps.onKey(event.deviceId, gamepad, event.keyCode, event.action)
        if (route.handled) {
            if (route.player >= 0) sendInput(route.player, ps.mask(route.player))
            ui.removeCallbacks(chordRunnable)
            if (ps.selectAndStartDown) ui.postDelayed(chordRunnable, Hotkeys.CHORD_HOLD_MS)
            return true
        }
        // A gamepad button the player left unbound must do nothing: Android would otherwise turn BUTTON_B into "Back" and open the menu.
        if (KeyEvent.isGamepadButton(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val ps = players
        if (menu == null && ps != null && (ev.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && ev.action == MotionEvent.ACTION_MOVE) {
            // Analog right trigger = fast-forward while pressed past half (pads that report R2 only as an axis).
            if (!ps.anyPlays(KeyEvent.KEYCODE_BUTTON_R2)) {
                val pulled = maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
                // Per device: another pad's stick movement (trigger at 0) must not cancel this pad's held trigger.
                val changed = if (pulled) ffTriggers.add(ev.deviceId) else ffTriggers.remove(ev.deviceId)
                if (changed) updateSpeed()
            }
            playedPads += ev.deviceId
            val p = ps.onAxes(ev.deviceId, true, ev.getAxisValue(MotionEvent.AXIS_HAT_X), ev.getAxisValue(MotionEvent.AXIS_HAT_Y), ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y))
            if (p != null) sendInput(p, ps.mask(p))
            return true   // consume joystick motion while playing so it can't move UI focus
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun isGamepadSource(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    // ---- lifecycle ----

    override fun onPause() {
        super.onPause()
        glView.onPause()
        if (session != null && menu == null) openMenu()   // never keep running behind another app/screen saver
        persistSram()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        glView.requestRender()
    }

    /**
     * The game screen is singleTask (one native core per process), so starting another game while this one is still
     * alive in the background lands here instead of in a new screen. The same game just comes back; a different one
     * saves and closes this game first, then opens a fresh screen for the new one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val sameGame = intent.getStringExtra(EXTRA_ROM) == romPath && intent.getStringExtra(EXTRA_SYSTEM) == systemId &&
            (intent.getStringExtra(EXTRA_CORE) ?: coreId) == coreId && !intent.getBooleanExtra(EXTRA_RESUME, false)
        if (sameGame) return
        ErrorLog.i("game", "Switching from $romName to another game")
        shutdown()
        finish()
        startActivity(Intent(intent).setClass(this, GameActivity::class.java))
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Idempotent: flush saves, stop the thread, free the core and audio, and clear the crash marker. */
    private fun shutdown() {
        if (finishing) return
        finishing = true
        ui.removeCallbacks(sramTick); ui.removeCallbacks(infoTick); ui.removeCallbacks(chordRunnable)
        val s = session
        val c = core
        if (s != null && c != null) {
            endPlaySegment()
            saveThumbnail(async = false)
            history.addPlayTime(romPath, playedMs)
            // Final cartridge save, executed on the emulation thread before it stops.
            s.post { core ->
                val ram = runCatching { core.saveRam() }.getOrNull()
                if (ram != null && ram.isNotEmpty() && !ram.contentEquals(lastSram)) runCatching { store.writeSram(systemId, romName, ram) }
                // Quick Resume session (slot 0): written on every clean quit, replaced next time.
                val st = runCatching { core.saveState() }.getOrNull()
                if (st != null && st.isNotEmpty()) runCatching {
                    store.write(systemId, romName, 0, StateMeta(coreId, core.description.version, romName, System.currentTimeMillis(), st.size), st)
                }.onFailure { ErrorLog.w("save", "Quick Resume session not saved: ${it.message}") }
                core.drainMessages()?.let { ErrorLog.w("core", it.trim()) }
            }
            refreshInfo()
            s.stop()
            session = null; core = null
        }
        inputManager?.unregisterInputDeviceListener(deviceListener); inputManager = null
        for (p in 0..1) sendInput(p, 0)
        File(filesDir, LOCK_FILE).delete()
        SessionInfo.set(*(SessionInfo.lines.map { it.label to it.value } + ("Status" to "closed")).toTypedArray())
    }

    companion object {
        const val EXTRA_ROM = "rom"
        const val EXTRA_NAME = "name"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_CORE = "core"
        const val LOCK_FILE = "session.lock"
        private const val SRAM_AUTOSAVE_MS = 20_000L
    }
}
