package com.retrobox.tv.emulator

import com.retrobox.tv.util.ErrorLog
import java.io.File

/** [EmulatorCore] backed by the native libretro host. The native host holds a single core at a time. */
class LibretroCore private constructor(
    private val api: NativeApi,
    override val system: SystemAdapter,
    override val romFile: File,
    override val description: CoreDescription,
    override val av: AvInfo,
    private val generation: Long,
) : EmulatorCore {

    private var closed = false

    override fun runFrame() = api.runFrame()
    override fun reset() = api.reset()
    override fun readAudio(out: ShortArray) = api.readAudio(out)
    override fun setInput(port: Int, mask: Int, lx: Int, ly: Int, rx: Int, ry: Int) = api.setInput(port, mask, lx, ly, rx, ry)
    override fun saveState() = api.saveState()
    override fun loadState(data: ByteArray) = api.loadState(data)
    override fun saveRam() = api.saveRam()
    override fun loadSaveRam(data: ByteArray) = api.loadSaveRam(data)
    override fun drainMessages() = api.drainMessages()

    override fun close() {
        if (closed) return
        closed = true
        // The native host holds one core for the whole process. If another game has loaded since (this one was abandoned
        // while loading), unloading now would pull the newer game's core out from under it.
        synchronized(LOCK) { if (generation == current) api.unloadCore() }   // unloads the game, deinits and dlcloses the core
    }

    companion object {
        private val LOCK = Any()
        private var current = 0L   // generation of the core loaded last; guarded by LOCK
    }

    class Factory(
        private val api: NativeApi,
        private val nativeLibDir: File,
        private val systemDir: File,
        private val saveDir: File,
        /** Height of the screen the game is shown on; lets screen-size-aware cores (RetroBox Originals) pick their resolution. 0 = unknown. */
        private val screenHeightPx: Int = 0,
    ) : EmulatorCoreFactory {

        override fun create(system: SystemAdapter, core: CoreSpec, rom: File): CoreResult<EmulatorCore> = create(system, core, rom) { false }

        /** As [create]; [abandoned] is checked under the load lock, so a screen closed while its ROM was copied never loads a core. */
        fun create(system: SystemAdapter, core: CoreSpec, rom: File, abandoned: () -> Boolean): CoreResult<EmulatorCore> = synchronized(LOCK) {
            if (abandoned()) return CoreResult.Err(EmulatorError(ErrorKind.CORE_LOAD_FAILED, "Loading was cancelled."))
            load(system, core, rom)
        }

        private fun load(system: SystemAdapter, core: CoreSpec, rom: File): CoreResult<EmulatorCore> {
            RomValidator.validate(system, rom)?.let { return CoreResult.Err(it) }

            val so = File(nativeLibDir, "lib${core.id}_libretro_android.so")
            if (!so.exists()) {
                return CoreResult.Err(EmulatorError(ErrorKind.CORE_MISSING, "Emulator core '${core.displayName}' is not installed in this build (${so.name})."))
            }
            systemDir.mkdirs(); saveDir.mkdirs()
            api.loadCore(so.absolutePath, systemDir.absolutePath, saveDir.absolutePath)?.let {
                ErrorLog.e("core", "loadCore(${core.id}) failed: $it")
                return CoreResult.Err(EmulatorError(ErrorKind.CORE_LOAD_FAILED, "Could not start ${core.displayName}: $it"))
            }
            val ci = api.coreInfo()
            CoreOptions.forGame(core, rom, screenHeightPx).forEach { (k, v) -> api.setOption(k, v) }
            api.loadGame(rom.absolutePath)?.let {
                api.unloadCore()
                ErrorLog.e("core", "loadGame(${rom.name}) failed on ${core.id}: $it")
                return CoreResult.Err(EmulatorError(ErrorKind.ROM_REJECTED, "${core.displayName} could not load ${rom.name}. $it"))
            }
            val a = api.avInfo()
            val av = AvInfo(a[0].toInt(), a[1].toInt(), a[2], a[3], a[4].toInt())
            return CoreResult.Ok(LibretroCore(api, system, rom, CoreDescription(ci[0], ci[1], ci[2]), av, ++current))
        }
    }
}
