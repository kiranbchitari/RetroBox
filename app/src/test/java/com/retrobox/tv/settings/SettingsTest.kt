package com.retrobox.tv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun store() = SettingsStore(File(tmp.root, "settings.json"))

    @Test fun defaultsAreTheCheapestPath() {
        val s = AppSettings()
        assertEquals(GraphicsSettings(ScaleMode.ORIGINAL, false, ShaderMode.NONE), s.graphics)
        assertFalse("overlay is off by default", s.overlay)
        assertEquals(0, s.safeAreaPct)
        assertEquals(1f, s.audio.gain, 0f)
        assertEquals(GraphicsPreset.PLAIN, GraphicsPreset.matching(s.graphics))
    }

    @Test fun nativeCodesAreStable() {
        // These numbers are shared with renderer.cpp / layout.h; renumbering would silently change the picture.
        assertEquals(listOf(0, 1, 4, 2, 3), ScaleMode.entries.map { it.code })
        assertEquals(listOf(0, 1, 2, 3, 4), ShaderMode.entries.map { it.code })
        assertEquals(ScaleMode.SIXTEEN_NINE, ScaleMode.of(4))
        assertEquals(ScaleMode.ORIGINAL, ScaleMode.of(99))
        assertEquals(ShaderMode.NONE, ShaderMode.of(null))
    }

    @Test fun everyPresetIsDistinctAndFindable() {
        assertEquals(GraphicsPreset.entries.size, GraphicsPreset.entries.map { it.graphics }.toSet().size)
        GraphicsPreset.entries.forEach { assertEquals(it, GraphicsPreset.matching(it.graphics)) }
        assertNull(GraphicsPreset.matching(GraphicsSettings(ScaleMode.STRETCH, true, ShaderMode.LCD)))
    }

    @Test fun sharpBilinearForcesSmoothSampling() {
        assertTrue(GraphicsSettings(shader = ShaderMode.SHARP, bilinear = false).effectiveBilinear)
        assertFalse(GraphicsSettings(shader = ShaderMode.CRT, bilinear = false).effectiveBilinear)
    }

    @Test fun perSystemOverrideOnlyStoresDifferences() {
        val base = AppSettings(graphics = GraphicsSettings(ScaleMode.FOUR_THREE, true, ShaderMode.NONE))
        val s = base.withSystemGraphics("gba", GraphicsSettings(ScaleMode.FOUR_THREE, true, ShaderMode.LCD))
        assertEquals(GraphicsOverride(shader = ShaderMode.LCD), s.perSystem["gba"])
        assertEquals(ShaderMode.LCD, s.graphicsFor("gba").shader)
        assertEquals(ShaderMode.NONE, s.graphicsFor("nes").shader)
        assertTrue(s.hasOverride("gba")); assertFalse(s.hasOverride("nes"))
        // Choosing the default again removes the exception instead of storing a no-op.
        val back = s.withSystemGraphics("gba", base.graphics)
        assertFalse(back.hasOverride("gba")); assertTrue(back.perSystem.isEmpty())
    }

    @Test fun changingTheDefaultMovesSystemsWithoutExceptions() {
        val s = AppSettings().withSystemGraphics("snes", GraphicsSettings(ScaleMode.INTEGER)).copy(graphics = GraphicsSettings(shader = ShaderMode.SCANLINES))
        assertEquals(ScaleMode.INTEGER, s.graphicsFor("snes").scale)
        assertEquals(ShaderMode.SCANLINES, s.graphicsFor("snes").shader)   // exception only covered the aspect
        assertEquals(ShaderMode.SCANLINES, s.graphicsFor("nes").shader)
    }

    @Test fun removingAnOverrideRestoresTheDefault() {
        val s = AppSettings().withSystemGraphics("nes", GraphicsSettings(ScaleMode.STRETCH)).withoutSystemOverride("nes")
        assertEquals(AppSettings().graphics, s.graphicsFor("nes"))
    }

    @Test fun safeAreaIsClampedAndConvertedToPermille() {
        assertEquals(50, AppSettings(safeAreaPct = 5).safePermille)
        assertEquals(100, AppSettings(safeAreaPct = 99).safePermille)
        assertEquals(0, AppSettings(safeAreaPct = -3).safePermille)
    }

    @Test fun audioGainIsSquaredAndZeroWhenOff() {
        assertEquals(0.25f, AudioSettings(volumePct = 50).gain, 1e-6f)
        assertEquals(0f, AudioSettings(enabled = false, volumePct = 100).gain, 0f)
        assertEquals(0f, AudioSettings(volumePct = 0).gain, 0f)
        assertEquals(1f, AudioSettings(volumePct = 150).gain, 0f)   // out-of-range input cannot amplify
    }

    @Test fun cycledWrapsBothWays() {
        val l = listOf("a", "b", "c")
        assertEquals("b", l.cycled("a", 1)); assertEquals("a", l.cycled("c", 1)); assertEquals("c", l.cycled("a", -1))
        assertEquals("a", l.cycled("zzz", 1))   // unknown current value starts at the first entry
    }

    @Test fun steppedStopsAtTheEnds() {
        val v = AudioSettings.volumeChoices
        assertEquals(100, v.stepped(100, 1)); assertEquals(0, v.stepped(0, -1))
        assertEquals(60, v.stepped(50, 1)); assertEquals(40, v.stepped(50, -1))
        assertEquals(40, v.stepped(35, 1)); assertEquals(30, v.stepped(35, -1))   // between steps: nearest step in that direction
    }

    @Test fun storeRoundTripKeepsEverything() {
        val s = AppSettings(
            graphics = GraphicsSettings(ScaleMode.INTEGER, true, ShaderMode.CRT),
            perSystem = mapOf("gb" to GraphicsOverride(shader = ShaderMode.LCD), "snes" to GraphicsOverride(ScaleMode.FOUR_THREE, false, null)),
            audio = AudioSettings(false, 40, 200), safeAreaPct = 4, overlay = true,
        )
        store().save(s)
        assertEquals(s, store().load())
    }

    @Test fun libraryViewIsRememberedUntilChanged() {
        assertEquals("small grid by default", 0, AppSettings().libraryView)
        store().save(AppSettings(libraryView = 2))
        assertEquals(2, store().load().libraryView)
        store().save(store().load().copy(libraryView = 1))      // the user picks another view: that one is kept now
        assertEquals(1, store().load().libraryView)
    }

    @Test fun libraryViewFromAnOldOrOddFileIsSafe() {
        assertEquals("a file without the field", 0, SettingsStore.parse("{\"version\":1}").libraryView)
        assertEquals("a value from the future", AppSettings.MAX_LIBRARY_VIEW, SettingsStore.parse("{\"libraryView\":99}").libraryView)
        assertEquals("a negative value", 0, SettingsStore.parse("{\"libraryView\":-3}").libraryView)
    }

    @Test fun missingFileMeansDefaultsWithoutAComplaint() {
        val st = store()
        assertEquals(AppSettings(), st.load()); assertNull(st.lastProblem)
    }

    @Test fun damagedFileFallsBackAndSaysSo() {
        File(tmp.root, "settings.json").writeText("{ not json")
        val st = store()
        assertEquals(AppSettings(), st.load())
        assertNotNull(st.lastProblem)
        st.save(AppSettings(overlay = true))            // and the next change repairs it
        assertTrue(store().load().overlay)
        assertNull(store().also { it.load() }.lastProblem)
    }

    @Test fun unknownAndOutOfRangeValuesAreSanitised() {
        val s = SettingsStore.parse("""{"graphics":{"scale":42,"shader":-1,"bilinear":"x"},"audio":{"volumePct":900,"bufferMs":5},"safeAreaPct":77,
            "perSystem":{"nes":{"scale":3},"gba":{},"snes":"nope"}}""")
        assertEquals(ScaleMode.ORIGINAL, s.graphics.scale); assertEquals(ShaderMode.NONE, s.graphics.shader)
        assertEquals(100, s.audio.volumePct)
        assertEquals(AudioSettings.bufferChoices.first(), s.audio.bufferMs)
        assertEquals(AppSettings.MAX_SAFE_AREA_PCT, s.safeAreaPct)
        assertEquals(setOf("nes"), s.perSystem.keys)        // empty / malformed entries dropped
        assertEquals(ScaleMode.INTEGER, s.perSystem["nes"]?.scale)
    }

    @Test fun saveLeavesNoTempFileBehind() {
        store().save(AppSettings())
        assertEquals(listOf("settings.json"), tmp.root.list()!!.toList())
    }
}
