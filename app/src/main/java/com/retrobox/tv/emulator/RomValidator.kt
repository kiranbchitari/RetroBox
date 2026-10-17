package com.retrobox.tv.emulator

import java.io.File

/** Cheap pre-flight checks so obviously wrong files fail with a clear message instead of inside a core. */
object RomValidator {
    const val MAX_ROM_BYTES = 64L * 1024 * 1024

    fun validate(system: SystemAdapter, rom: File): EmulatorError? {
        if (!rom.exists()) return EmulatorError(ErrorKind.ROM_UNREADABLE, "ROM file not found: ${rom.name}. Is the storage still connected?")
        if (!rom.isFile || !rom.canRead()) return EmulatorError(ErrorKind.ROM_UNREADABLE, "Cannot read ROM file: ${rom.name}")
        if (!system.supports(rom.name)) {
            return EmulatorError(ErrorKind.ROM_UNSUPPORTED, "'.${rom.extension}' is not a ${system.displayName} ROM (supported: ${system.extensions.joinToString { ".$it" }})")
        }
        val size = rom.length()
        if (size == 0L) return EmulatorError(ErrorKind.ROM_INVALID, "ROM file is empty: ${rom.name}")
        if (size > MAX_ROM_BYTES) return EmulatorError(ErrorKind.ROM_INVALID, "ROM is larger than ${MAX_ROM_BYTES / (1024 * 1024)} MB: ${rom.name}")
        return headerCheck(system, rom)
    }

    private fun headerCheck(system: SystemAdapter, rom: File): EmulatorError? {
        val ok = try {
            when (system.id) {
                "nes" -> rom.extension.lowercase() != "nes" || nesHeaderOk(rom)
                "snes" -> snesHeaderOk(rom)
                "gb", "gbc" -> gbHeaderOk(rom)
                "gba" -> gbaHeaderOk(rom)
                "genesis" -> genesisHeaderOk(rom)
                else -> true
            }
        } catch (_: java.io.IOException) {
            return EmulatorError(ErrorKind.ROM_UNREADABLE, "Cannot read ROM file: ${rom.name}")
        }
        if (ok) return null
        val what = when (system.id) {
            "nes" -> "a valid iNES ROM (bad header)"
            "snes" -> "a recognisable SNES ROM (no valid cartridge header found)"
            "gb", "gbc" -> "a valid Game Boy ROM (no Nintendo logo / header checksum)"
            "gba" -> "a valid GBA ROM (bad header)"
            "genesis" -> "a Genesis / Mega Drive ROM (no \"SEGA\" header)"
            else -> "a valid ROM"
        }
        return EmulatorError(ErrorKind.ROM_INVALID, "${rom.name} is not $what. The file may be corrupt or a different format.")
    }

    /** Reads [len] bytes at [offset]; null if the file is too short. */
    private fun readAt(rom: File, offset: Long, len: Int): ByteArray? = java.io.RandomAccessFile(rom, "r").use { f ->
        if (offset < 0 || offset + len > f.length()) return null
        val b = ByteArray(len)
        f.seek(offset); f.readFully(b)
        b
    }

    private fun nesHeaderOk(rom: File): Boolean {
        val h = readAt(rom, 0, 16) ?: return false
        return h[0] == 'N'.code.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'S'.code.toByte() && h[3] == 0x1A.toByte()
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    /**
     * SNES ROMs have no magic number. A header is accepted at the LoROM / HiROM / ExHiROM location (shifted by a
     * 512-byte copier header when the size says there is one) if its checksum complement matches, or - for hacks
     * and homebrew with sloppy checksums - if the map-mode byte and the title look sane.
     */
    internal fun snesHeaderOk(rom: File): Boolean {
        val size = rom.length()
        val copier = if (size % 1024 == 512L) 512L else 0L
        if (size - copier < 0x8000) return false
        return longArrayOf(0x7FC0, 0xFFC0, 0x40FFC0).any { base ->
            val h = readAt(rom, base + copier, 0x40) ?: return@any false
            val checksumOk = (u16(h, 0x1C) xor u16(h, 0x1E)) == 0xFFFF
            val mapMode = u8(h, 0x15)
            val mapOk = (mapMode and 0xE0) == 0x20 && (mapMode and 0x0F) <= 5
            val titleOk = (0 until 21).all { val c = u8(h, it); c in 0x20..0x7E || c in 0xA0..0xDF || c == 0 }
            checksumOk || (mapOk && titleOk)
        }
    }

    private val gbLogo = intArrayOf(
        0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
        0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
        0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E,
    )

    /** Game Boy / Color: the Nintendo logo at 0x104 or the header checksum at 0x14D (either one is enough, bootlegs lack the logo). */
    internal fun gbHeaderOk(rom: File): Boolean {
        val h = readAt(rom, 0x100, 0x50) ?: return false
        val logoOk = gbLogo.indices.all { u8(h, 4 + it) == gbLogo[it] }
        var x = 0
        for (i in 0x34..0x4C) x = (x - u8(h, i) - 1) and 0xFF
        return logoOk || x == u8(h, 0x4D)
    }

    /** GBA: fixed byte 0x96 at 0xB2 or a valid header complement at 0xBD. */
    /** "SEGA" at 0x100 (or 0x101, some dumps pad the system name); an interleaved .smd copier dump is recognised by its 512-byte header. */
    internal fun genesisHeaderOk(rom: File): Boolean {
        val head = readAt(rom, 0, 0x104) ?: return false
        if (looksLikeGenesis(head)) return true
        return rom.extension.equals("smd", ignoreCase = true) && rom.length() % 0x4000 == 0x200L && u8(head, 8) == 0xAA && u8(head, 9) == 0xBB
    }

    /** True when [head] (at least 0x104 bytes from the start of a file) has the Genesis "SEGA" system name. */
    fun looksLikeGenesis(head: ByteArray): Boolean {
        fun at(o: Int) = head.size >= o + 4 && String(head, o, 4, Charsets.US_ASCII) == "SEGA"
        return at(0x100) || at(0x101)
    }

    internal fun gbaHeaderOk(rom: File): Boolean {
        val h = readAt(rom, 0, 0xC0) ?: return false
        var x = 0
        for (i in 0xA0..0xBC) x = (x - u8(h, i)) and 0xFF
        x = (x - 0x19) and 0xFF
        return u8(h, 0xB2) == 0x96 || x == u8(h, 0xBD)
    }
}
