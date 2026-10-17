package com.retrobox.tv.emulator

/** Buttons of the libretro "RetroPad"; [retroId] is RETRO_DEVICE_ID_JOYPAD_*. Systems expose a subset. */
enum class PadButton(val retroId: Int, val label: String) {
    B(0, "B"), Y(1, "Y"), SELECT(2, "Select"), START(3, "Start"),
    UP(4, "Up"), DOWN(5, "Down"), LEFT(6, "Left"), RIGHT(7, "Right"),
    A(8, "A"), X(9, "X"), L(10, "L"), R(11, "R"),
    L2(12, "L2"), R2(13, "R2"), L3(14, "L3"), R3(15, "R3"),
}

/**
 * How honest the UI may be about a system. A system only becomes [VERIFIED] after a ROM has actually
 * been loaded and played through the integration (see TEST_MATRIX.md); nothing is shown as playable before that.
 */
enum class SystemStatus { PLANNED, EXPERIMENTAL, VERIFIED }

/** A libretro core packaged as `lib<id>_libretro_android.so` in the APK's native libs. */
data class CoreSpec(val id: String, val displayName: String, val license: String)

data class SystemAdapter(
    val id: String,
    val displayName: String,
    val extensions: Set<String>,
    val cores: List<CoreSpec>,
    val buttons: List<PadButton>,
    val status: SystemStatus,
    val statusNote: String,
    /** How many people can play on one device (libretro ports the host feeds). Game Boy-family cores here have no link cable, so 1. */
    val players: Int = 1,
    /** What the system calls each RetroPad button, where it differs (RetroBox Originals: A is "Confirm", B is "Back", …). */
    val buttonNames: Map<PadButton, String> = emptyMap(),
    /** Order of the rows in the controls editor. */
    val editorOrder: List<PadButton> = listOf(PadButton.A, PadButton.B, PadButton.X, PadButton.Y, PadButton.L, PadButton.R, PadButton.START, PadButton.SELECT, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT),
    /** Buttons that can be set to auto-fire, in menu order; null = the face buttons A B X Y this system has. */
    val turboButtons: List<PadButton>? = null,
    /** The left stick acts as a 4-way D-pad (one direction at a time) instead of 8-way: for menus and grid games where a slip must not turn twice. */
    val stickFourWay: Boolean = false,
) {
    fun nameOf(b: PadButton): String = buttonNames[b] ?: b.label

    val defaultCore: CoreSpec? get() = cores.firstOrNull()

    /** The core the user chose for this system (by core id), or the default when nothing/an unknown id was chosen. */
    fun coreFor(chosenId: String?): CoreSpec? = cores.firstOrNull { it.id == chosenId } ?: defaultCore

    /** The next core in the list after [currentId], wrapping around; the same core when there is only one. */
    fun nextCore(currentId: String?): CoreSpec? {
        val cur = coreFor(currentId) ?: return null
        return cores[(cores.indexOf(cur) + 1) % cores.size]
    }
    fun supports(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in extensions
}
