package com.retrobox.tv.settings

/** How the picture is fitted to the screen. [code] is what the native renderer understands (do not renumber). */
enum class ScaleMode(val code: Int, val label: String) {
    ORIGINAL(0, "Original"), FOUR_THREE(1, "4:3"), SIXTEEN_NINE(4, "16:9"), STRETCH(2, "Stretch"), INTEGER(3, "Integer");
    companion object { fun of(code: Int?) = entries.firstOrNull { it.code == code } ?: ORIGINAL }
}

/** Single-pass GLES2 look. [code] is the native shader id (do not renumber). All are off by default. */
enum class ShaderMode(val code: Int, val label: String) {
    NONE(0, "Off"), SHARP(1, "Sharp bilinear"), SCANLINES(2, "Scanlines"), CRT(3, "CRT"), LCD(4, "LCD grid");
    companion object { fun of(code: Int?) = entries.firstOrNull { it.code == code } ?: NONE }
}

data class GraphicsSettings(
    val scale: ScaleMode = ScaleMode.ORIGINAL,
    val bilinear: Boolean = false,
    val shader: ShaderMode = ShaderMode.NONE,
) {
    /** Sharp bilinear only works on linear sampling, so the renderer forces it; show that truthfully. */
    val effectiveBilinear: Boolean get() = bilinear || shader == ShaderMode.SHARP
}

/** Per-system exceptions to the global graphics defaults; null = follow the default. */
data class GraphicsOverride(val scale: ScaleMode? = null, val bilinear: Boolean? = null, val shader: ShaderMode? = null) {
    val isEmpty get() = scale == null && bilinear == null && shader == null
    fun applyTo(base: GraphicsSettings) = GraphicsSettings(scale ?: base.scale, bilinear ?: base.bilinear, shader ?: base.shader)
}

/** Ready-made graphics combinations. They describe a look, not a speed: any speed claim must come from TEST_MATRIX.md measurements. */
enum class GraphicsPreset(val label: String, val blurb: String, val graphics: GraphicsSettings) {
    PLAIN("Plain", "Original aspect, sharp pixels, no effects. The cheapest path.", GraphicsSettings()),
    SMOOTH("Smooth pixels", "Original aspect with the sharp-bilinear shader.", GraphicsSettings(ScaleMode.ORIGINAL, true, ShaderMode.SHARP)),
    CRT("CRT", "4:3, smoothing, scanlines + RGB mask.", GraphicsSettings(ScaleMode.FOUR_THREE, true, ShaderMode.CRT)),
    HANDHELD("Handheld LCD", "Whole-number scale with a pixel grid.", GraphicsSettings(ScaleMode.INTEGER, false, ShaderMode.LCD));

    companion object { fun matching(g: GraphicsSettings): GraphicsPreset? = entries.firstOrNull { it.graphics == g } }
}

data class AudioSettings(
    val enabled: Boolean = true,
    val volumePct: Int = 100,
    val bufferMs: Int = 100,
) {
    /** 0 when sound is off. Squared so the slider feels even to the ear (50% ≈ -12 dB instead of -6 dB). */
    val gain: Float get() = if (!enabled) 0f else (volumePct.coerceIn(0, 100) / 100f).let { it * it }

    companion object {
        val bufferChoices = listOf(50, 100, 150, 200, 300)
        val volumeChoices = (0..100 step 10).toList()
    }
}

data class AppSettings(
    val graphics: GraphicsSettings = GraphicsSettings(),
    val perSystem: Map<String, GraphicsOverride> = emptyMap(),
    val audio: AudioSettings = AudioSettings(),
    /** TV overscan margin, whole percent of the screen kept free on each side. */
    val safeAreaPct: Int = 0,
    /** FPS / frame-time readout during play. Off by default; measuring GPU time costs a little speed. */
    val overlay: Boolean = false,
    /** Speed while fast-forward is held (R2 / Space) or switched on from the pause menu: 2, 3, 4 or [FastForward.MAX]. */
    val fastForwardSpeed: Int = com.retrobox.tv.emulator.FastForward.DEFAULT,
    /** Auto-fire buttons per system id (absent = off). */
    val turbo: Map<String, Set<com.retrobox.tv.emulator.PadButton>> = emptyMap(),
    /** How the Library shows games, kept until the user picks another: 0 small grid, 1 list, 2 big cards (matches `LibraryView.ordinal`, never renumbered). */
    val libraryView: Int = 0,
) {
    fun turboFor(systemId: String): Set<com.retrobox.tv.emulator.PadButton> = turbo[systemId].orEmpty()
    fun withTurbo(systemId: String, set: Set<com.retrobox.tv.emulator.PadButton>) = copy(turbo = if (set.isEmpty()) turbo - systemId else turbo + (systemId to set))

    /**
     * The look for [systemId]: its own exception if one is set, else the global default. RetroBox Originals are drawn for the
     * screen (crisp UI text), so unless the user sets an exception they get plain original-aspect output, smoothed when scaled,
     * and not the global CRT / scanline look meant for old consoles.
     */
    fun graphicsFor(systemId: String): GraphicsSettings =
        perSystem[systemId]?.applyTo(graphicsDefaultFor(systemId)) ?: graphicsDefaultFor(systemId)

    private fun graphicsDefaultFor(systemId: String) =
        if (systemId == com.retrobox.tv.emulator.Originals.SYSTEM_ID) GraphicsSettings(ScaleMode.ORIGINAL, bilinear = true, shader = ShaderMode.NONE) else graphics
    fun hasOverride(systemId: String) = perSystem[systemId]?.isEmpty == false
    val safePermille: Int get() = safeAreaPct.coerceIn(0, MAX_SAFE_AREA_PCT) * 10

    /** Stores [g] as the choice for [systemId]: only the parts that differ from the global default become an override. */
    fun withSystemGraphics(systemId: String, g: GraphicsSettings): AppSettings {
        val base = graphicsDefaultFor(systemId)
        val o = GraphicsOverride(
            scale = g.scale.takeIf { it != base.scale }, bilinear = g.bilinear.takeIf { it != base.bilinear }, shader = g.shader.takeIf { it != base.shader },
        )
        return copy(perSystem = if (o.isEmpty) perSystem - systemId else perSystem + (systemId to o))
    }

    fun withoutSystemOverride(systemId: String) = copy(perSystem = perSystem - systemId)

    companion object { const val MAX_SAFE_AREA_PCT = 10; const val MAX_LIBRARY_VIEW = 2 }
}

/** Next/previous element of this list after [current], wrapping; [current] not in the list starts from the first. */
fun <T> List<T>.cycled(current: T, step: Int): T {
    if (isEmpty()) throw NoSuchElementException("empty list")
    val i = indexOf(current)
    return if (i < 0) first() else this[((i + step) % size + size) % size]
}

/** Like [cycled] but stops at both ends (a volume slider must not jump from 100% to 0%). A value between steps snaps to the nearest step in that direction. */
fun List<Int>.stepped(current: Int, step: Int): Int {
    if (isEmpty()) throw NoSuchElementException("empty list")
    val sorted = sorted()
    return if (step >= 0) sorted.firstOrNull { it > current } ?: sorted.last() else sorted.lastOrNull { it < current } ?: sorted.first()
}
