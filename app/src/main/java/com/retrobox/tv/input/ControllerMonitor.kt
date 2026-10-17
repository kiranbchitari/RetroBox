package com.retrobox.tv.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

data class PadInfo(
    val id: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val descriptor: String,
    val sources: String,
    val axes: List<String>,
)

/**
 * Tracks connected game controllers and (when [capturing]) the raw button/axis events arriving at the
 * activity. Purely passive: it never consumes events, so menu navigation keeps working.
 */
class ControllerMonitor(context: Context) : InputManager.InputDeviceListener {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

    val pads = mutableStateListOf<PadInfo>()
    val pressed = mutableStateMapOf<Int, String>()          // keyCode -> device name
    val axes = mutableStateMapOf<Int, Float>()               // axis -> value
    val recent = mutableStateListOf<String>()                // newest first
    @Volatile var capturing = false

    fun start() {
        inputManager.registerInputDeviceListener(this, null)
        refresh()
    }

    fun stop() = inputManager.unregisterInputDeviceListener(this)

    fun refresh() {
        pads.clear()
        val found = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { isGamepad(it) }.map { describe(it) }
        pads.addAll(found)
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    fun onKey(e: KeyEvent) {
        if (!capturing) return
        val name = e.device?.name ?: "device ${e.deviceId}"
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            pressed[e.keyCode] = name
            log("${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")} down  ← $name")
        } else if (e.action == KeyEvent.ACTION_UP) {
            pressed.remove(e.keyCode)
            log("${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")} up    ← $name")
        }
    }

    fun onMotion(e: MotionEvent) {
        if (!capturing || (e.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return
        val dev = e.device ?: return
        for (range in dev.motionRanges) {
            if ((range.source and InputDevice.SOURCE_JOYSTICK) == 0) continue
            val v = e.getAxisValue(range.axis)
            val shown = if (kotlin.math.abs(v) < 0.02f) 0f else v
            if (axes[range.axis] != shown) axes[range.axis] = shown
        }
    }

    private fun log(line: String) {
        recent.add(0, line)
        while (recent.size > 10) recent.removeAt(recent.lastIndex)
    }

    companion object {
        fun isGamepad(d: InputDevice): Boolean {
            if (d.isVirtual) return false
            val s = d.sources
            val joystick = s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            val pad = s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            if (!joystick && !pad) return false
            // Seen on the Mi TV 4A: its voice-search helper ("virtual-search") is a uinput keyboard that claims the
            // gamepad class, but has vendor/product 0 and no analog axes. It must not take the Player 1 slot.
            if (!joystick && d.vendorId == 0 && d.productId == 0) return false
            return true
        }

        private fun describe(d: InputDevice): PadInfo {
            val src = buildList {
                if (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) add("gamepad")
                if (d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) add("joystick")
                if (d.sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD) add("dpad")
            }.joinToString("+")
            val axes = d.motionRanges.filter { (it.source and InputDevice.SOURCE_JOYSTICK) != 0 }
                .map { MotionEvent.axisToString(it.axis).removePrefix("AXIS_") }
            return PadInfo(d.id, d.name, d.vendorId, d.productId, d.descriptor, src, axes)
        }

        fun axisName(axis: Int) = MotionEvent.axisToString(axis).removePrefix("AXIS_")
    }
}
