package com.retrobox.tv.game

import android.view.KeyEvent
import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.emulator.Systems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInputTest {
    private fun bit(b: PadButton) = 1 shl b.retroId
    private fun nesInput() = GameInput(DefaultMappings.gamepad, Systems.byId("nes")!!.buttons.toSet())

    @Test fun xboxStyleBottomButtonIsRetroPadB() {
        val i = nesInput()
        i.onKeyCode(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)
        assertEquals(bit(PadButton.B), i.mask)
        i.onKeyCode(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP)
        assertEquals(0, i.mask)
    }

    @Test fun buttonsTheSystemLacksAreFilteredOut() {
        val i = nesInput()
        i.onKeyCode(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.ACTION_DOWN)   // NES has no shoulder buttons
        i.onKeyCode(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.ACTION_DOWN)
        assertEquals(0, i.mask)
        val snes = GameInput(DefaultMappings.gamepad, Systems.byId("snes")!!.buttons.toSet())
        snes.onKeyCode(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.ACTION_DOWN)
        assertEquals(bit(PadButton.L), snes.mask)
    }

    @Test fun unmappedKeysAreNotConsumed() = assertFalse(nesInput().onKeyCode(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))

    @Test fun hatAndStickAreOredAndReleaseIndependently() {
        val i = nesInput()
        i.onKeyCode(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN)
        i.onAxes(hx = 1f, hy = 0f, sx = -0.9f, sy = 0f)
        assertEquals(bit(PadButton.UP) or bit(PadButton.RIGHT) or bit(PadButton.LEFT), i.mask)
        i.onAxes(0f, 0f, 0f, 0f)
        assertEquals(bit(PadButton.UP), i.mask)
    }

    @Test fun stickInsideThresholdIsIgnored() {
        val i = nesInput(); i.onAxes(0f, 0f, 0.3f, -0.4f)
        assertEquals(0, i.mask)
    }

    @Test fun selectPlusStartChordIsDetectedAndClearResets() {
        val i = nesInput()
        i.onKeyCode(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.ACTION_DOWN)
        assertFalse(i.selectAndStartDown)
        i.onKeyCode(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN)
        assertTrue(i.selectAndStartDown)
        i.clear()
        assertEquals(0, i.mask)
    }

    @Test fun menuHotkeysAreNotGameplayMappings() {
        Hotkeys.menuKeys.forEach { assertFalse("$it must not be mapped to gameplay", DefaultMappings.gamepad.containsKey(it)) }
    }

    // ---- per-system default mapping (RetroPad ids: B=0 Y=1 Select=2 Start=3 Up=4 Down=5 Left=6 Right=7 A=8 X=9 L=10 R=11) ----

    private fun maskFor(systemId: String, vararg keys: Int): Int {
        val i = GameInput(DefaultMappings.gamepad, Systems.byId(systemId)!!.buttons.toSet())
        keys.forEach { i.onKeyCode(it, KeyEvent.ACTION_DOWN) }
        return i.mask
    }
    private val allFaceAndShoulder = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
    )

    @Test fun snesGetsAllTwelveButtonsPositionally() {
        // Xbox A(bottom)=SNES B, Xbox B(right)=SNES A, Xbox X(left)=SNES Y, Xbox Y(top)=SNES X, plus L/R/Select/Start/D-pad.
        val m = maskFor("snes", *allFaceAndShoulder, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals((0..11).fold(0) { a, id -> a or (1 shl id) }, m)       // exactly ids 0..11; L2/R2/L3/R3 are filtered
        assertEquals(bit(PadButton.Y), maskFor("snes", KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(bit(PadButton.X), maskFor("snes", KeyEvent.KEYCODE_BUTTON_Y))
    }

    @Test fun gbaHasShouldersButNoXY() {
        assertEquals(bit(PadButton.L), maskFor("gba", KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(bit(PadButton.R), maskFor("gba", KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(0, maskFor("gba", KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2))
        val full = maskFor("gba", *allFaceAndShoulder, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)
        assertEquals(bit(PadButton.A) or bit(PadButton.B) or bit(PadButton.L) or bit(PadButton.R) or bit(PadButton.START) or bit(PadButton.SELECT), full)
    }

    @Test fun gameBoyAndColorOnlyHaveAbSelectStartAndDpad() {
        val expected = bit(PadButton.A) or bit(PadButton.B) or bit(PadButton.START) or bit(PadButton.SELECT)
        for (sys in listOf("gb", "gbc")) {
            assertEquals(sys, expected, maskFor(sys, *allFaceAndShoulder, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT))
            assertEquals(sys, bit(PadButton.UP) or bit(PadButton.LEFT), maskFor(sys, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        }
    }

    @Test fun remoteOkButtonActsAsAOnEverySystem() {
        for (sys in listOf("nes", "snes", "gb", "gbc", "gba")) assertEquals(sys, bit(PadButton.A), maskFor(sys, KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test fun stickAndHatStillWorkOnShoulderSystems() {
        val i = GameInput(DefaultMappings.gamepad, Systems.byId("gba")!!.buttons.toSet())
        i.onAxes(0f, -1f, 0f, 0f)
        i.onKeyCode(KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.ACTION_DOWN)
        assertEquals(bit(PadButton.UP) or bit(PadButton.R), i.mask)
    }

    @Test fun keyboardDefaultsFollowRetroArch() {
        assertEquals(bit(PadButton.B), maskFor("snes", KeyEvent.KEYCODE_Z))
        assertEquals(bit(PadButton.A), maskFor("snes", KeyEvent.KEYCODE_X))
        assertEquals(bit(PadButton.Y), maskFor("snes", KeyEvent.KEYCODE_A))
        assertEquals(bit(PadButton.X), maskFor("snes", KeyEvent.KEYCODE_S))
        assertEquals(bit(PadButton.L), maskFor("gba", KeyEvent.KEYCODE_Q))
        assertEquals(bit(PadButton.R), maskFor("gba", KeyEvent.KEYCODE_W))
        assertEquals(bit(PadButton.START), maskFor("gb", KeyEvent.KEYCODE_ENTER))
        assertEquals(bit(PadButton.SELECT), maskFor("gb", KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(0, maskFor("gb", KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W))   // no Y/X/L/R on Game Boy
    }
}
