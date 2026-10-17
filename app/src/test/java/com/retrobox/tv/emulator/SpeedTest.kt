package com.retrobox.tv.emulator

import android.view.KeyEvent
import com.retrobox.tv.game.DefaultMappings
import com.retrobox.tv.game.GameInput
import com.retrobox.tv.settings.AppSettings
import com.retrobox.tv.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTest {
    private val a = 1 shl PadButton.A.retroId
    private val b = 1 shl PadButton.B.retroId
    private val up = 1 shl PadButton.UP.retroId

    @Test fun turboPulsesOnlyTheTurboButtonsStartingPressed() {
        val held = a or up
        val seen = (0L until 12L).map { Turbo.apply(held, a, it) }
        assertEquals(held, seen[0])   // first frame of a hold is pressed: a tap still counts
        assertEquals(listOf(held, held, held, up, up, up, held, held, held, up, up, up), seen)
        assertTrue(seen.all { it and up != 0 })   // non-turbo buttons are never touched
        assertEquals(b, Turbo.apply(b, a, 4))     // nothing to pulse: unchanged
        assertEquals(0, Turbo.apply(0, a, 4))
    }

    @Test fun turboChoicesFollowTheSystemsButtons() {
        val nes = Systems.byId("nes")!!; val snes = Systems.byId("snes")!!
        assertEquals(listOf("Off", "A", "B", "A + B"), Turbo.choices(nes).map { Turbo.label(it) })   // no "all" entry with only two buttons
        val snesLabels = Turbo.choices(snes).map { Turbo.label(it) }
        assertTrue(snesLabels.containsAll(listOf("Off", "A", "B", "X", "Y", "A + B", "A + B + X + Y")))
        assertEquals(a or b, Turbo.mask(setOf(PadButton.A, PadButton.B)))
    }

    @Test fun fastForwardPacing() {
        val normal = FastForward.frameNs(60.0, 1)
        assertEquals(16_666_666L, normal)
        assertEquals(normal / 3, FastForward.frameNs(60.0, 3))
        assertEquals(0L, FastForward.frameNs(60.0, FastForward.MAX))
        assertEquals(listOf("2×", "3×", "4×", "Max"), FastForward.choices.map { FastForward.label(it) })
        assertEquals("Off", FastForward.label(FastForward.NORMAL))
    }

    @Test fun speedAndTurboSettingsRoundTripAndSanitise() {
        val s = AppSettings(fastForwardSpeed = FastForward.MAX).withTurbo("snes", setOf(PadButton.A, PadButton.Y)).withTurbo("nes", setOf(PadButton.B))
        val back = SettingsStore.parse(SettingsStore.toJson(s).toString())
        assertEquals(FastForward.MAX, back.fastForwardSpeed)
        assertEquals(setOf(PadButton.A, PadButton.Y), back.turboFor("snes"))
        assertEquals(setOf(PadButton.B), back.turboFor("nes"))
        assertEquals(emptySet<PadButton>(), back.turboFor("gb"))
        assertEquals(s.withTurbo("nes", emptySet()).turbo.keys, setOf("snes"))

        // Old files have neither field; bad values fall back; buttons a system lacks (X on NES) and unknown systems are dropped.
        val old = SettingsStore.parse("""{"version":1}""")
        assertEquals(FastForward.DEFAULT, old.fastForwardSpeed); assertTrue(old.turbo.isEmpty())
        val bad = SettingsStore.parse("""{"fastForwardSpeed":7,"turbo":{"nes":["X","A","nonsense"],"zzz":["A"],"gb":"A"}}""")
        assertEquals(FastForward.DEFAULT, bad.fastForwardSpeed)
        assertEquals(mapOf("nes" to setOf(PadButton.A)), bad.turbo)
    }

    @Test fun r2IsAHotkeyOnlyWhenNoButtonOfTheSystemUsesIt() {
        val nes = Systems.byId("nes")!!.buttons.toSet()
        assertFalse(GameInput(DefaultMappings.gamepad, nes).drivesGame(KeyEvent.KEYCODE_BUTTON_R2))   // default R2 -> RetroPad R2, not a NES button
        assertTrue(GameInput(DefaultMappings.gamepad, nes).drivesGame(KeyEvent.KEYCODE_BUTTON_A))
        val custom = DefaultMappings.gamepad + (KeyEvent.KEYCODE_BUTTON_R2 to PadButton.A)
        assertTrue(GameInput(custom, nes).drivesGame(KeyEvent.KEYCODE_BUTTON_R2))                     // the player bound R2 to A: the game gets it
        assertFalse(GameInput(DefaultMappings.gamepad, nes).drivesGame(KeyEvent.KEYCODE_SPACE))
    }
}
