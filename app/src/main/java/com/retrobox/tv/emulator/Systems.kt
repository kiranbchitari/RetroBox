package com.retrobox.tv.emulator

/** Registry of all systems. Add a system by adding an entry here + packaging its core `.so`. */
object Systems {
    private val nesButtons = listOf(PadButton.B, PadButton.A, PadButton.SELECT, PadButton.START, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT)
    private val snesButtons = nesButtons + listOf(PadButton.Y, PadButton.X, PadButton.L, PadButton.R)
    private val gbaButtons = nesButtons + listOf(PadButton.L, PadButton.R)
    // Both Genesis cores read the RetroPad like this (cores/GENESIS_HANDOVER.md): B=B, Y=A, A=C, L=X, X=Y, R=Z, Select=Mode.
    private val originalsButtons = listOf(PadButton.A, PadButton.B, PadButton.START, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT)
    private val genesisNames = mapOf(
        PadButton.Y to "A", PadButton.B to "B", PadButton.A to "C", PadButton.L to "X", PadButton.X to "Y", PadButton.R to "Z", PadButton.SELECT to "Mode",
    )
    private val genesisOrder = listOf(PadButton.Y, PadButton.B, PadButton.A, PadButton.L, PadButton.X, PadButton.R, PadButton.START, PadButton.SELECT, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT)

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
        // .bin is shared with other systems: the scanner and validator only accept one with the "SEGA" header.
        SystemAdapter("genesis", "Genesis", setOf("md", "gen", "smd", "bin"),
            listOf(CoreSpec("genesis_plus_gx", "Genesis Plus GX", "non-commercial (MAME-style)"), CoreSpec("picodrive", "PicoDrive", "non-commercial (MAME-style)")),
            genesisOrder, SystemStatus.VERIFIED, "Verified on an Android TV emulator with a test ROM; not yet on a real TV", players = 2,
            buttonNames = genesisNames, editorOrder = genesisOrder, turboButtons = listOf(PadButton.Y, PadButton.B, PadButton.A)),
        // Games made for RetroBox itself (cores/originals): a tiny .rbo "cartridge" picks the game inside one core.
        SystemAdapter(Originals.SYSTEM_ID, "RetroBox Originals", setOf(Originals.EXTENSION), listOf(CoreSpec(Originals.CORE_ID, "RetroBox Originals", "GPL-2.0")),
            originalsButtons, SystemStatus.EXPERIMENTAL, "Built for RetroBox; not yet checked on a real TV",
            buttonNames = mapOf(PadButton.A to "Confirm", PadButton.B to "Back", PadButton.START to "Pause"),
            editorOrder = listOf(PadButton.A, PadButton.B, PadButton.START, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT), turboButtons = emptyList()),
    )

    fun byId(id: String): SystemAdapter? = all.firstOrNull { it.id == id }

    /** First system whose extension list matches. GB and GBC are distinguished by extension only. */
    fun detect(fileName: String): SystemAdapter? = all.firstOrNull { it.supports(fileName) }
}
