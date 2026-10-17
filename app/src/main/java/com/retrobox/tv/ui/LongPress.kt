package com.retrobox.tv.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** The modifier and the two callbacks to give a clickable surface so that holding OK works with a real TV remote. */
class OkLongPress(val modifier: Modifier, val onClick: () -> Unit, val onLongClick: (() -> Unit)?)

private val okKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)
private const val HOLD_MS = 450L

/**
 * Hold OK for [onLong], tap OK for [onClick]. The TV material surface only sees a long press when the system marks the key
 * event as one, which `adb input --longpress` does but a real remote's auto-repeat does not: holding OK on the TV then
 * played the game on release. This watches the repeats itself (a hold is OK still down after [HOLD_MS], or a second repeat)
 * and, once it fired, swallows the click that the release would otherwise cause. [onLong] = null disables the long press.
 */
@Composable
fun rememberOkLongPress(onLong: (() -> Unit)?, onClick: () -> Unit): OkLongPress {
    val state = remember { object { var downAt = 0L; var fired = false } }
    val long = rememberUpdatedState(onLong)
    val click = rememberUpdatedState(onClick)
    val watcher = Modifier.onPreviewKeyEvent { e ->
        if (e.key in okKeys && e.type == KeyEventType.KeyDown && long.value != null) {
            val repeats = e.nativeKeyEvent.repeatCount
            val now = SystemClock.uptimeMillis()
            if (repeats == 0) { state.downAt = now; state.fired = false }
            else if (!state.fired && (now - state.downAt >= HOLD_MS || repeats >= 2)) { state.fired = true; long.value?.invoke() }
        }
        false   // never consume: the surface still has to see the press for a normal click
    }
    val clickGuard: () -> Unit = { if (state.fired) state.fired = false else click.value() }
    val longGuard: (() -> Unit)? = if (onLong == null) null else ({ if (!state.fired) { state.fired = true; long.value?.invoke() } })
    return OkLongPress(watcher, clickGuard, longGuard)
}
