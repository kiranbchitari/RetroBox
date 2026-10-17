package com.retrobox.tv.emulator

import java.io.File

/** Core options the app sets before a game loads (the core's own defaults apply to everything else). */
object CoreOptions {
    fun forGame(core: CoreSpec, rom: File, screenHeightPx: Int = 0): Map<String, String> = when (core.id) {
        // RetroBox Originals draws at the size of the screen it will be shown on (it is vector-style, so any size looks sharp).
        Originals.CORE_ID -> mapOf(Originals.RESOLUTION_OPTION to Originals.resolutionFor(screenHeightPx))
        else -> emptyMap()
    }
}
