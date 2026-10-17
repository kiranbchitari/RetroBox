package com.retrobox.tv.emulator

/** Registry of all systems. Add a system by adding an entry here + packaging its core `.so`. */
object Systems {
    private val nesButtons = listOf(PadButton.B, PadButton.A, PadButton.SELECT, PadButton.START, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT)
    private val snesButtons = nesButtons + listOf(PadButton.Y, PadButton.X, PadButton.L, PadButton.R)
    private val gbaButtons = nesButtons + listOf(PadButton.L, PadButton.R)
    private val originalsButtons = listOf(PadButton.A, PadButton.B, PadButton.START, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT)

    val all: List<SystemAdapter> = listOf(
        SystemAdapter("nes", "NES", setOf("nes", "fds", "unf", "unif"), listOf(CoreSpec("fceumm", "FCEUmm", "GPL-2.0+")), nesButtons,
            SystemStatus.VERIFIED, "Verified on an Android TV emulator; not yet on a real TV", players = 2),
        SystemAdapter("snes", "SNES", setOf("sfc", "smc", "swc", "fig"), listOf(CoreSpec("snes9x2005", "Snes9x 2005", "Snes9x (non-commercial)")), snesButtons,
            SystemStatus.VERIFIED, "Verified on an Android TV emulator with a test ROM; not yet on a real TV", players = 2),
        SystemAdapter("gb", "Game Boy", setOf("gb"), listOf(CoreSpec("gambatte", "Gambatte", "GPL-2.0")), nesButtons,
            SystemStatus.VERIFIED, "Verified on an Android TV emulator with a test ROM; not yet on a real TV"),
        SystemAdapter("gbc", "Game Boy Color", setOf("gbc"), listOf(CoreSpec("gambatte", "Gambatte", "GPL-2.0")), nesButtons,
            SystemStatus.VERIFIED, "Verified on an Android TV emulator with a test ROM; not yet on a real TV"),
        SystemAdapter("gba", "Game Boy Advance", setOf("gba"), listOf(CoreSpec("gpsp", "gpSP", "GPL-2.0"), CoreSpec("mgba", "mGBA", "MPL-2.0")), gbaButtons,
            SystemStatus.VERIFIED, "Verified on an Android TV emulator with a test ROM; not yet on a real TV"),
        // Games made for RetroBox itself (cores/originals): a tiny .rbo "cartridge" picks the game inside one core.
        SystemAdapter(Originals.SYSTEM_ID, "RetroBox Originals", setOf(Originals.EXTENSION), listOf(CoreSpec(Originals.CORE_ID, "RetroBox Originals", "GPL-2.0")),
            originalsButtons, SystemStatus.EXPERIMENTAL, "Built for RetroBox; not yet checked on a real TV",
            players = 2,
            buttonNames = mapOf(PadButton.A to "Confirm / Shoot", PadButton.B to "Back / Jump", PadButton.START to "Pause / Join"),
            editorOrder = listOf(PadButton.A, PadButton.B, PadButton.START, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT), turboButtons = emptyList(), stickFourWay = true),
    )

    fun byId(id: String): SystemAdapter? = all.firstOrNull { it.id == id }

    /** First system whose extension list matches. GB and GBC are distinguished by extension only. */
    fun detect(fileName: String): SystemAdapter? = all.firstOrNull { it.supports(fileName) }
}
