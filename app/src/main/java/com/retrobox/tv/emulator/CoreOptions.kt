package com.retrobox.tv.emulator

import java.io.File

/** Core options the app sets before a game loads (the core's own defaults apply to everything else). */
object CoreOptions {
    fun forGame(core: CoreSpec, rom: File): Map<String, String> = when (core.id) {
        // Genesis Plus GX picks a 6-button pad from the ROM header by itself ("joypad auto"); PicoDrive always assumes
        // 3 buttons, which hides X/Y/Z/Mode from games that use them. Same rule as GPGX: a '6' in the header's I/O field.
        "picodrive" -> if (genesisWantsSixButtons(rom)) mapOf("picodrive_input1" to "6 button pad", "picodrive_input2" to "6 button pad") else emptyMap()
        else -> emptyMap()
    }

    /** The Genesis header's I/O support field (0x190-0x19F) lists '6' for games that know the 6-button pad. */
    fun genesisWantsSixButtons(rom: File): Boolean = runCatching {
        java.io.RandomAccessFile(rom, "r").use { f ->
            if (f.length() < 0x1A0) return false
            val io = ByteArray(16); f.seek(0x190); f.readFully(io)
            io.any { it == '6'.code.toByte() }
        }
    }.getOrDefault(false)
}
