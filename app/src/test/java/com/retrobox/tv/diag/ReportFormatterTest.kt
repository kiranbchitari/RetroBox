package com.retrobox.tv.diag

import com.retrobox.tv.settings.AppSettings
import com.retrobox.tv.settings.AudioSettings
import com.retrobox.tv.settings.GraphicsOverride
import com.retrobox.tv.settings.ShaderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFormatterTest {
    @Test fun reportListsEverySectionAndTheErrors() {
        val text = ReportFormatter.format(
            "Version 1.2.3", "2026-10-17 12:00:00",
            listOf("Device" to listOf(ReportLine("Android", "16")), "Graphics" to emptyList(), "Emulation" to listOf(ReportLine("", "free text"))),
            listOf("E1", "E2"),
        )
        assertTrue(text.startsWith("RetroBox TV diagnostic report\nVersion 1.2.3\nGenerated: 2026-10-17 12:00:00\n"))
        assertTrue("== Device ==\nAndroid: 16" in text)
        assertTrue("== Graphics ==\n(nothing)" in text)
        assertTrue("== Emulation ==\nfree text" in text)       // a line without a label is printed bare
        assertTrue("== Recent errors (2) ==\nE1\nE2" in text)
    }

    @Test fun noErrorsIsStatedExplicitly() {
        assertTrue("none recorded" in ReportFormatter.format("v", "t", emptyList(), emptyList()))
    }

    @Test fun settingsLinesDescribeWhatTheUserWouldSee() {
        val s = AppSettings(safeAreaPct = 3, overlay = true, audio = AudioSettings(false, 70, 150), perSystem = mapOf("gba" to GraphicsOverride(shader = ShaderMode.LCD)))
        val m = ReportFormatter.settingsLines(s).associate { it.label to it.value }
        assertEquals("3% per side", m["Safe area"])
        assertEquals("on", m["FPS overlay"])
        assertEquals("off, volume 70%, buffer 150 ms", m["Audio"])
        assertEquals("Game Boy Advance", m["Per-system exceptions"])
        assertEquals("none", ReportFormatter.settingsLines(AppSettings()).first { it.label == "Per-system exceptions" }.value)
    }
}
