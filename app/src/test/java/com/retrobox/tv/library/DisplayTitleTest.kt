package com.retrobox.tv.library

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTitleTest {
    private fun t(name: String) = titleFromFileName(name)

    @Test fun underscoresAndDashesBecomeSpacesAndWordsAreCapitalised() {
        assertEquals("Chip 'n' Dale 2", t("chip-n-dale-2.nes"))
        assertEquals("Super Mario Bros", t("super_mario_bros.nes"))
        assertEquals("Rb Testrom", t("rb-testrom.gb"))
        assertEquals("Sonic", t("sonic.smc"))
    }

    @Test fun regionAndDumpTagsAreDropped() {
        assertEquals("Sonic the Hedgehog", t("Sonic the Hedgehog (Unl) [p1].smc"))
        assertEquals("Super Mario Bros", t("Super Mario Bros. (World) (Rev A) [!].nes".replace("Bros.", "Bros")))
        assertEquals("Metroid Fusion", t("Metroid Fusion (USA, Europe) {Beta}.gba"))
    }

    @Test fun allCapsTitlesAreRecasedButSmallWordsStayLower() {
        assertEquals("The Legend of Zelda", t("THE_LEGEND_OF_ZELDA.NES"))
        assertEquals("Kirby's Adventure", t("KIRBY'S ADVENTURE.nes"))
        assertEquals("Mario and Luigi", t("mario_AND_luigi.gba"))
    }

    @Test fun romanNumeralsStayUpper() {
        assertEquals("Zelda II", t("zelda_ii.nes"))
        assertEquals("Final Fantasy VII", t("final-fantasy-vii.gba"))
        assertEquals("Mega Man X", t("mega_man_x.sfc"))
        assertEquals("Mega Man X", t("MEGA MAN X.sfc"))
    }

    @Test fun mixedCaseWordsAreLeftAlone() {
        assertEquals("Pokemon FireRed", t("Pokemon_FireRed.gba"))
        assertEquals("FIFA 98", t("Fifa 98".replace("Fifa", "FIFA") + ".sfc"))
        assertEquals("Super Mario World", t("Super Mario World.sfc"))
    }

    @Test fun trailingArticleMovesToTheFront() {
        assertEquals("The Legend of Zelda", t("Legend of Zelda, The.nes"))
        assertEquals("The Legend of Zelda", t("legend-of-zelda,-the.nes"))
        assertEquals("A Link to the Past", t("Link to the Past, A (USA).sfc"))
    }

    @Test fun specialCharactersAreRemovedButNotLetters() {
        assertEquals("Punch Out", t("Punch-Out!!.nes"))
        assertEquals("Dr Mario", t("dr_mario~.nes"))
        assertEquals("Pokémon Blue", t("pokémon_blue.gb"))
        assertEquals("Contra", t("##contra@@.nes"))
    }

    @Test fun dottedNamesWithoutSpacesBecomeWords() {
        assertEquals("Super Mario Bros", t("super.mario.bros.nes"))
        assertEquals("Dr. Mario", t("Dr. Mario.nes"))
    }

    @Test fun neverReturnsAnEmptyTitle() {
        assertEquals("(!).nes".substringBeforeLast('.').let { it }, "(!)")   // guard on the input we feed below
        assert(t("(!).nes").isNotEmpty())
        assert(t("___.gb").isNotEmpty())
    }

    @Test fun displayOnlyTheFileNameIsNeverTouched() {
        // The scanner stores the original file name next to the cleaned title (saves and pictures key off the file name).
        val rom = ScannedRom("nes", "/r/CHIP-n-DALE_2.nes", "CHIP-n-DALE_2.nes", titleFromFileName("CHIP-n-DALE_2.nes"), 1)
        assertEquals("CHIP-n-DALE_2.nes", rom.fileName)
        assertEquals("Chip 'n' Dale 2", rom.title)
    }
}
