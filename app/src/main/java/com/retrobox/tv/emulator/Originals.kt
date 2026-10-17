package com.retrobox.tv.emulator

import java.io.File

/**
 * RetroBox Originals: games written for RetroBox itself (cores/originals). They run in one libretro core,
 * `retrobox_originals`; a tiny `.rbo` "cartridge" file says which game to start:
 * ```
 * RBO1
 * game=snake
 * ```
 * so the library, saves, save states, controllers and pause menu work exactly as for any other system.
 */
object Originals {
    const val SYSTEM_ID = "originals"
    const val CORE_ID = "retrobox_originals"
    const val EXTENSION = "rbo"
    const val RESOLUTION_OPTION = "retrobox_originals_resolution"
    const val FOLDER = "RetroBox Originals"
    val MAGIC = "RBO1".toByteArray(Charsets.US_ASCII)

    /** The games that ship inside the core: cartridge file name and the `game=` id the core knows. */
    data class Cartridge(val fileName: String, val gameId: String)
    val cartridges = listOf(Cartridge("Snake.$EXTENSION", "snake"))

    fun isCartridge(head: ByteArray?): Boolean = head != null && head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    fun cartridgeBytes(gameId: String): ByteArray = "RBO1\ngame=$gameId\n".toByteArray(Charsets.US_ASCII)

    /**
     * The picture size the core is asked for on a screen [heightPx] pixels tall: 720p for the common 1280x720 TVs,
     * 1080p from 1000 px up (also for 4K screens: the GPU scales 1080p up, a 4K software frame would be too slow), 540p below 650.
     */
    fun resolutionFor(heightPx: Int): String = when {
        heightPx >= 1000 -> "1080p"
        heightPx in 1 until 650 -> "540p"
        else -> "720p"
    }

    /**
     * Puts the bundled games into `<romRoot>/RetroBox Originals/`. A game is written once: a marker next to it records that,
     * so a game the user deleted does not come back at the next start. Returns the cartridges written now.
     */
    fun install(romRoot: File): List<Cartridge> {
        val dir = File(romRoot, FOLDER)
        val written = mutableListOf<Cartridge>()
        for (c in cartridges) {
            val marker = File(dir, ".installed-${c.gameId}")
            if (marker.exists()) continue
            dir.mkdirs()
            val target = File(dir, c.fileName)
            if (!target.exists()) target.writeBytes(cartridgeBytes(c.gameId))
            marker.writeText("1")
            written += c
        }
        return written
    }
}
