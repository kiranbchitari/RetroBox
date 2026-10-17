package com.retrobox.tv.emulator

import java.io.File

enum class ErrorKind { CORE_MISSING, CORE_LOAD_FAILED, ROM_UNREADABLE, ROM_UNSUPPORTED, ROM_INVALID, ROM_REJECTED }

data class EmulatorError(val kind: ErrorKind, val message: String)

sealed interface CoreResult<out T> {
    data class Ok<T>(val value: T) : CoreResult<T>
    data class Err(val error: EmulatorError) : CoreResult<Nothing>
}

data class AvInfo(
    val width: Int, val height: Int, val aspect: Double, val fps: Double, val sampleRate: Int,
)

data class CoreDescription(val name: String, val version: String, val extensions: String)

/** A loaded core with a loaded game. All methods except [setInput] must be called from one (emulation) thread. */
interface EmulatorCore : AutoCloseable {
    val description: CoreDescription
    val av: AvInfo
    val system: SystemAdapter
    val romFile: File

    fun runFrame(): Boolean
    fun reset()
    fun readAudio(out: ShortArray): Int
    fun setInput(port: Int, mask: Int, lx: Int = 0, ly: Int = 0, rx: Int = 0, ry: Int = 0)
    fun saveState(): ByteArray?
    fun loadState(data: ByteArray): Boolean
    fun saveRam(): ByteArray?
    fun loadSaveRam(data: ByteArray): Boolean
    fun drainMessages(): String?
}

interface EmulatorCoreFactory {
    /** Validates the ROM, loads [core] and the game; never throws for expected failures. */
    fun create(system: SystemAdapter, core: CoreSpec, rom: File): CoreResult<EmulatorCore>
}
