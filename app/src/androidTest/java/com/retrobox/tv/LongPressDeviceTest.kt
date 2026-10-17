package com.retrobox.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.retrobox.tv.ui.GameTile
import com.retrobox.tv.ui.RetroBoxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Holding OK on a game tile opens its options instead of playing it. The key events are built the way a TV remote's
 * auto-repeat arrives: repeat counts 0, 1, 2 ... with NO long-press flag (adb's --longpress sets that flag, a remote does not).
 */
class LongPressDeviceTest {
    @get:Rule val rule = createComposeRule()
    private var plays = 0
    private var options = 0

    private fun show() {
        rule.setContent {
            RetroBoxTheme {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                GameTile(title = "Snake", favorite = false, thumb = null, modifier = Modifier.focusRequester(focus), onOptions = { options++ }, onClick = { plays++ })
            }
        }
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        rule.waitForIdle()
    }

    private fun hold(holdMs: Long) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        var repeat = 0
        inst.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat++))
        while (SystemClock.uptimeMillis() - downTime < holdMs) {
            Thread.sleep(80)
            inst.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat++))
        }
        inst.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        rule.waitForIdle()
    }

    @Test fun holdingOkOpensOptionsAndDoesNotPlay() {
        show()
        hold(900)
        assertEquals("options", 1, options)
        assertEquals("a hold must not also play the game", 0, plays)
    }

    @Test fun aTapStillPlaysAndAHoldDoesNotSpoilTheNextTap() {
        show()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); rule.waitForIdle()
        assertEquals(1, plays); assertEquals(0, options)
        hold(900)
        assertEquals(1, options); assertEquals(1, plays)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); rule.waitForIdle()
        assertEquals("the tap after a hold must play", 2, plays)
        assertEquals(1, options)
    }
}
