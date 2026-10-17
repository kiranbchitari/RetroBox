package com.retrobox.tv.game

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.retrobox.tv.emulator.PadButton

/** Android keyCode -> RetroPad button. Positional (Xbox-style) like RetroArch: bottom face button = RetroPad B. */
object DefaultMappings {
    val gamepad: Map<Int, PadButton> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to PadButton.B, KeyEvent.KEYCODE_BUTTON_B to PadButton.A,
        KeyEvent.KEYCODE_BUTTON_X to PadButton.Y, KeyEvent.KEYCODE_BUTTON_Y to PadButton.X,
        KeyEvent.KEYCODE_BUTTON_L1 to PadButton.L, KeyEvent.KEYCODE_BUTTON_R1 to PadButton.R,
        KeyEvent.KEYCODE_BUTTON_L2 to PadButton.L2, KeyEvent.KEYCODE_BUTTON_R2 to PadButton.R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL to PadButton.L3, KeyEvent.KEYCODE_BUTTON_THUMBR to PadButton.R3,
        KeyEvent.KEYCODE_BUTTON_SELECT to PadButton.SELECT, KeyEvent.KEYCODE_BUTTON_START to PadButton.START,
        KeyEvent.KEYCODE_DPAD_UP to PadButton.UP, KeyEvent.KEYCODE_DPAD_DOWN to PadButton.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to PadButton.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to PadButton.RIGHT,
        // Remote-only play: OK acts as the main action button.
        KeyEvent.KEYCODE_DPAD_CENTER to PadButton.A,
        // USB/Bluetooth keyboard, RetroArch's defaults: Z=B X=A A=Y S=X Q=L W=R Enter=Start RightShift=Select.
        KeyEvent.KEYCODE_Z to PadButton.B, KeyEvent.KEYCODE_X to PadButton.A,
        KeyEvent.KEYCODE_A to PadButton.Y, KeyEvent.KEYCODE_S to PadButton.X,
        KeyEvent.KEYCODE_Q to PadButton.L, KeyEvent.KEYCODE_W to PadButton.R,
        KeyEvent.KEYCODE_ENTER to PadButton.START, KeyEvent.KEYCODE_SHIFT_RIGHT to PadButton.SELECT,
    )
}

/** Keys that open the pause menu instead of reaching the game, so gameplay mappings can't trap the user. */
object Hotkeys {
    val menuKeys = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU)
    const val CHORD_HOLD_MS = 600L   // Select + Start held together
    /** Held = fast-forward, unless the player's layout gives the key a button this system has. Analog R2 (trigger axis) works too. */
    val fastForwardKeys = setOf(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_SPACE)
}

/**
 * Turns key/motion events into a RetroPad bitmask. Three sources (buttons, hat, stick) are tracked
 * separately and OR-ed so releasing one never cancels another.
 */
class GameInput(
    private val keyMap: Map<Int, PadButton>,
    private val supported: Set<PadButton>,
    private val stickThreshold: Float = 0.5f,
    /** The stick gives one direction at a time (4-way games such as Snake): see [stickDirections]. */
    private val fourWayStick: Boolean = false,
) {
    private var keyMask = 0
    private var hatMask = 0
    private var stickMask = 0
    private var lockedAxis = AXIS_NONE   // 4-way sticks: the axis the current push belongs to

    val mask: Int get() = filter(keyMask or hatMask or stickMask)
    val selectAndStartDown: Boolean get() = (keyMask and bit(PadButton.SELECT)) != 0 && (keyMask and bit(PadButton.START)) != 0

    private fun bit(b: PadButton) = 1 shl b.retroId
    private fun filter(m: Int) = supported.fold(0) { acc, b -> acc or (m and bit(b)) }

    /** True when [keyCode] presses a button this system has (so it must not be used as a hotkey). */
    fun drivesGame(keyCode: Int) = keyMap[keyCode]?.let { it in supported } == true

    /** @return true if the event was a mapped gameplay key (caller should consume it). */
    fun onKey(e: KeyEvent): Boolean = onKeyCode(e.keyCode, e.action)

    fun onKeyCode(keyCode: Int, action: Int): Boolean {
        val b = keyMap[keyCode] ?: return false
        when (action) {
            KeyEvent.ACTION_DOWN -> keyMask = keyMask or bit(b)
            KeyEvent.ACTION_UP -> keyMask = keyMask and bit(b).inv()
        }
        return true
    }

    fun onMotion(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return false
        onAxes(e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y), e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y))
        return true   // consume joystick motion while playing so it can't move UI focus
    }

    /** Hat axes and the left stick both act as a digital D-pad (stick past [stickThreshold]). */
    fun onAxes(hx: Float, hy: Float, sx: Float, sy: Float) {
        hatMask = (if (hx < -0.5f) bit(PadButton.LEFT) else 0) or (if (hx > 0.5f) bit(PadButton.RIGHT) else 0) or
            (if (hy < -0.5f) bit(PadButton.UP) else 0) or (if (hy > 0.5f) bit(PadButton.DOWN) else 0)
        stickMask = if (fourWayStick) fourWayStick(sx, sy) else
            (if (sx < -stickThreshold) bit(PadButton.LEFT) else 0) or (if (sx > stickThreshold) bit(PadButton.RIGHT) else 0) or
                (if (sy < -stickThreshold) bit(PadButton.UP) else 0) or (if (sy > stickThreshold) bit(PadButton.DOWN) else 0)
    }

    /**
     * A thumb pushing a stick sideways also rolls it a little up or down, and past the threshold that read as a second direction:
     * Snake turned right and then, by itself, down. Here a push belongs to the axis that is further out when it starts and keeps it
     * until the stick comes back near the centre, so the other axis is ignored in between (the D-pad hat is not affected).
     */
    private fun fourWayStick(sx: Float, sy: Float): Int {
        val ax = kotlin.math.abs(sx); val ay = kotlin.math.abs(sy)
        val stillOut = when (lockedAxis) { AXIS_X -> ax > STICK_RELEASE; AXIS_Y -> ay > STICK_RELEASE; else -> false }
        if (!stillOut) lockedAxis = if (maxOf(ax, ay) <= stickThreshold) AXIS_NONE else if (ax >= ay) AXIS_X else AXIS_Y
        return when (lockedAxis) {
            AXIS_X -> if (sx < 0) bit(PadButton.LEFT) else bit(PadButton.RIGHT)
            AXIS_Y -> if (sy < 0) bit(PadButton.UP) else bit(PadButton.DOWN)
            else -> 0
        }
    }

    private companion object {
        const val AXIS_NONE = 0; const val AXIS_X = 1; const val AXIS_Y = 2
        const val STICK_RELEASE = 0.3f   // a locked direction lets go below this (hysteresis: no flicker around the threshold)
    }

    fun clear() { keyMask = 0; hatMask = 0; stickMask = 0; lockedAxis = AXIS_NONE }
}
