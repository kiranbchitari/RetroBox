package com.retrobox.tv.emulator

/** Fast-forward speeds offered to the player. [MAX] means "as fast as this device can run the core". */
object FastForward {
    const val NORMAL = 1
    const val MAX = 0
    val choices = listOf(2, 3, 4, MAX)
    const val DEFAULT = 3

    fun label(speed: Int) = when (speed) { MAX -> "Max"; NORMAL -> "Off"; else -> "$speed×" }

    /** Nanoseconds between frames at [speed] for a core running at [fps]; 0 = do not wait at all. */
    fun frameNs(fps: Double, speed: Int): Long {
        val base = 1_000_000_000.0 / fps.coerceIn(20.0, 120.0)
        return if (speed == MAX) 0L else (base / speed.coerceAtLeast(1)).toLong()
    }
}

/**
 * Auto-fire. While a turbo button is held it is pressed for [ON_FRAMES] frames and released for as many,
 * about 10 presses a second at 60 fps. The first frame of a hold is always "pressed", so a tap still registers.
 */
object Turbo {
    const val ON_FRAMES = 3

    /** Buttons that can be set to auto-fire (face buttons only), in menu order. */
    private val faceButtons = listOf(PadButton.A, PadButton.B, PadButton.X, PadButton.Y)

    fun eligible(system: SystemAdapter) = system.turboButtons ?: faceButtons.filter { it in system.buttons }

    /** Off, each button alone, the first two together (A + B), and all of them when the system has more than two. */
    fun choices(system: SystemAdapter): List<Set<PadButton>> {
        val e = eligible(system)
        val out = mutableListOf<Set<PadButton>>(emptySet())
        e.forEach { out += setOf(it) }
        if (e.size >= 2) out += setOf(e[0], e[1])
        if (e.size > 2) out += e.toSet()
        return out
    }

    /** "Off", "A", "A + B", … in the system's own button names and order. */
    fun label(set: Set<PadButton>, system: SystemAdapter? = null): String {
        if (set.isEmpty()) return "Off"
        val order = system?.let { eligible(it) } ?: faceButtons
        return (order.filter { it in set } + set.filter { it !in order }).joinToString(" + ") { system?.nameOf(it) ?: it.label }
    }

    fun mask(set: Set<PadButton>) = set.fold(0) { m, b -> m or (1 shl b.retroId) }

    /** The mask the core sees on frame [framesHeld] (0 = first frame) of a hold, given the held mask and the turbo mask. */
    fun apply(held: Int, turboMask: Int, framesHeld: Long): Int {
        if (held and turboMask == 0) return held
        val on = (framesHeld / ON_FRAMES) % 2 == 0L
        return if (on) held else held and turboMask.inv()
    }
}
