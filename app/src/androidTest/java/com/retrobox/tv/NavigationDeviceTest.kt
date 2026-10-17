package com.retrobox.tv

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.retrobox.tv.settings.AppSettings
import com.retrobox.tv.ui.HomeScreen
import com.retrobox.tv.ui.RetroBoxTheme
import com.retrobox.tv.ui.Screen
import com.retrobox.tv.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real key events (as a remote or pad sends them) through the window, checking where focus goes. */
private fun ComposeTestRule.key(code: Int) {
    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
    waitForIdle()
}

/** A TV is never in touch mode, but instrumentation starts activities in it; leave it the way a remote's first key press would. */
private fun ComposeTestRule.keyMode() {
    InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    waitForIdle()
}

private fun containing(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))

/** Every text inside the focused element (a card merges its texts). */
private fun ComposeTestRule.focusedTexts(): List<String> = onAllNodes(isFocused()).fetchSemanticsNodes()
    .flatMap { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }.orEmpty().map { it.text } }

private fun ComposeTestRule.focusedHas(text: String) = onAllNodes(isFocused() and containing(text)).fetchSemanticsNodes().isNotEmpty()

/** Presses [code] until the focused element shows [text]; fails after [max] presses. */
private fun ComposeTestRule.moveTo(code: Int, text: String, max: Int = 6) {
    repeat(max + 1) { if (focusedHas(text)) return; key(code) }
    val focused = onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString("|").toString() }.ifEmpty { "nothing" }
    throw AssertionError("focus never reached \"$text\" with ${KeyEvent.keyCodeToString(code)}; focused: $focused")
}

class HomeNavigationDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun dpadReachesEveryHomeSectionAndOpensSettings() {
        var opened: Screen? = null
        rule.setContent {
            RetroBoxTheme {
                HomeScreen(
                    recent = emptyList(), favorites = emptyList(), controllerCount = 0, libraryCount = 0, gamesPerSystem = emptyMap(),
                    lastFocusId = "fav:3", onFocused = {}, onOpen = { opened = it }, onSystem = {}, onPlayRecent = { _, _ -> },
                    onPlayFavorite = {}, onToggleFavorite = {},
                )
            }
        }
        rule.keyMode()
        rule.moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "No games played yet", max = 0)   // the remembered tile is gone (no favourites), so focus falls back to the empty "pick up" card
        rule.key(KeyEvent.KEYCODE_DPAD_DOWN)
        val systems = com.retrobox.tv.emulator.Systems.all.map { it.displayName }
        assertTrue("Down from the top card should land on a system, got ${rule.focusedTexts()}", rule.focusedTexts().any { it in systems })
        repeat(systems.size) { rule.key(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue("leftmost system should be NES, got ${rule.focusedTexts()}", "NES" in rule.focusedTexts())
        rule.moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, systems.last())                   // the rightmost system tile
        rule.key(KeyEvent.KEYCODE_DPAD_DOWN)                                       // into the Tools row
        rule.moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, "TV safe area")                  // Settings is the rightmost tool
        rule.moveTo(KeyEvent.KEYCODE_DPAD_LEFT, "Detect pads")                    // Controllers, first tool
        rule.moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, "TV safe area")
        rule.key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(Screen.Settings, opened)
    }
}

class SettingsNavigationDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun leftRightChangeTheFocusedSetting() {
        var settings by mutableStateOf(AppSettings())
        rule.setContent { RetroBoxTheme { SettingsScreen(settings, null, onChange = { settings = it(settings) }, onBack = {}) } }
        rule.keyMode()
        rule.moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Plain", max = 0)                 // focus starts on Preset
        rule.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Smooth pixels", max = 0)
        assertNotEquals(AppSettings().graphics, settings.graphics)
        rule.moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "FPS Overlay", max = 8)
        rule.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(true, settings.overlay)
    }
}

/**
 * The whole app: Home → Settings → Back returns to Home with focus on the tile that opened Settings.
 * Runs against the installed app's real data (it only moves focus and changes nothing).
 */
class AppBackNavigationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun backReturnsToTheTileThatOpenedTheScreen() {
        rule.keyMode()
        // Down from wherever Home starts (it depends on what was played) until a Tools tile, then right to Settings.
        val tools = listOf("Detect pads", "Browse, search", "Device, GPU", "TV safe area")
        repeat(5) { if (tools.none { rule.focusedHas(it) }) rule.key(KeyEvent.KEYCODE_DPAD_DOWN) }
        rule.moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, "TV safe area")
        rule.key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNode(hasText("SETTINGS")).assertExists()
        rule.key(KeyEvent.KEYCODE_BACK)
        rule.moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "TV safe area", max = 0)
    }
}

/** A pop-up (MenuPanel) keeps the D-pad inside: the screen behind it must never get focus. */
class PopupFocusDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun dpadNeverLeavesAPopup() {
        var behindClicked = false
        rule.setContent {
            RetroBoxTheme {
                androidx.compose.foundation.layout.Box {
                    androidx.compose.foundation.layout.Column {
                        com.retrobox.tv.ui.MenuRow(com.retrobox.tv.ui.MenuGlyph.PLAY, androidx.compose.ui.graphics.Color.White, "Behind top") { behindClicked = true }
                    }
                    com.retrobox.tv.ui.MenuPanel {
                        val first = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                        androidx.compose.runtime.LaunchedEffect(Unit) { first.requestFocus() }
                        com.retrobox.tv.ui.MenuRow(com.retrobox.tv.ui.MenuGlyph.PLAY, androidx.compose.ui.graphics.Color.White, "Popup first", androidx.compose.ui.Modifier.focusRequester(first)) {}
                        com.retrobox.tv.ui.MenuRow(com.retrobox.tv.ui.MenuGlyph.PLAY, androidx.compose.ui.graphics.Color.White, "Popup last") {}
                    }
                }
            }
        }
        rule.keyMode()
        rule.moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Popup first", max = 0)
        repeat(3) { rule.key(KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue("Up left the pop-up: ${rule.focusedTexts()}", rule.focusedHas("Popup"))
        repeat(4) { rule.key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("Down left the pop-up: ${rule.focusedTexts()}", rule.focusedHas("Popup last"))
        rule.key(KeyEvent.KEYCODE_DPAD_LEFT); rule.key(KeyEvent.KEYCODE_DPAD_RIGHT); rule.key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("the item behind the pop-up was activated", !behindClicked)
    }
}
