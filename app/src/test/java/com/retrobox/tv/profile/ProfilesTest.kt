package com.retrobox.tv.profile

import android.view.KeyEvent
import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.emulator.Systems
import com.retrobox.tv.game.DefaultMappings
import com.retrobox.tv.game.GameInput
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val defaults = DefaultMappings.gamepad
    private fun resolve(set: ProfileSet, system: String = "snes", file: String? = "game.sfc") = ProfileResolver.resolve(defaults, set, system, file)

    @Test fun noOverridesGivesTheDefaults() = assertEquals(defaults, resolve(ProfileSet()))

    @Test fun globalOverrideReplacesTheKeysOfThatButtonOnly() {
        val set = ProfileSet(global = ProfileLayer(mapOf(PadButton.A to setOf(KeyEvent.KEYCODE_BUTTON_L2))))
        val r = resolve(set)
        assertEquals(PadButton.A, r[KeyEvent.KEYCODE_BUTTON_L2])
        assertNull("old A keys are gone", r[KeyEvent.KEYCODE_BUTTON_B])
        assertNull(r[KeyEvent.KEYCODE_DPAD_CENTER])
        assertEquals(PadButton.B, r[KeyEvent.KEYCODE_BUTTON_A])          // untouched
    }

    @Test fun moreSpecificLevelsWin() {
        val g = ProfileLayer(mapOf(PadButton.A to setOf(KeyEvent.KEYCODE_BUTTON_L2)))
        val s = ProfileLayer(mapOf(PadButton.A to setOf(KeyEvent.KEYCODE_BUTTON_R2)))
        val gm = ProfileLayer(mapOf(PadButton.A to setOf(KeyEvent.KEYCODE_BUTTON_THUMBL)))
        val set = ProfileSet(g, mapOf("snes" to s), mapOf(gameKey("snes", "game.sfc") to gm))
        assertEquals(PadButton.A, resolve(set)[KeyEvent.KEYCODE_BUTTON_THUMBL])
        assertEquals(PadButton.A, resolve(set, file = "other.sfc")[KeyEvent.KEYCODE_BUTTON_R2])        // game override only for its own ROM
        assertEquals(PadButton.A, resolve(set, system = "gba", file = "x.gba")[KeyEvent.KEYCODE_BUTTON_L2])   // system override only for snes
    }

    @Test fun aKeyIsNeverBoundToTwoButtons() {
        val set = ProfileSet(global = ProfileLayer(mapOf(PadButton.X to setOf(KeyEvent.KEYCODE_BUTTON_A))))   // steals the key from B
        val r = resolve(set)
        assertEquals(PadButton.X, r[KeyEvent.KEYCODE_BUTTON_A])
        assertEquals(1, r.keys.count { it == KeyEvent.KEYCODE_BUTTON_A })
    }

    @Test fun bindMovesTheKeyAndKeepsTheLosersOtherKeys() {
        // B has BUTTON_A and keyboard Z by default. Binding BUTTON_A to X takes it from B; B keeps Z.
        val layer = ProfileResolver.bind(ProfileLayer(), defaults, PadButton.X, KeyEvent.KEYCODE_BUTTON_A)
        val r = ProfileResolver.resolve(defaults, ProfileSet(global = layer), "snes", null)
        assertEquals(PadButton.X, r[KeyEvent.KEYCODE_BUTTON_A])
        assertEquals(PadButton.B, r[KeyEvent.KEYCODE_Z])
        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_A), layer.bindings[PadButton.X])
    }

    @Test fun conflictIsReportedOnlyForOtherButtons() {
        assertEquals(PadButton.B, ProfileResolver.conflict(defaults, KeyEvent.KEYCODE_BUTTON_A, PadButton.Y))
        assertNull(ProfileResolver.conflict(defaults, KeyEvent.KEYCODE_BUTTON_A, PadButton.B))
        assertNull(ProfileResolver.conflict(defaults, KeyEvent.KEYCODE_BUTTON_C, PadButton.Y))   // free key
    }

    @Test fun menuKeysCannotBeBound() {
        assertTrue(ProfileResolver.isReserved(KeyEvent.KEYCODE_BACK))
        assertTrue(ProfileResolver.isReserved(KeyEvent.KEYCODE_BUTTON_MODE))
        assertTrue(ProfileResolver.isReserved(KeyEvent.KEYCODE_MENU))
        assertFalse(ProfileResolver.isReserved(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test fun unboundButtonsAreListed() {
        val set = ProfileSet(global = ProfileLayer(mapOf(PadButton.START to emptySet())))
        val r = resolve(set)
        assertEquals(listOf(PadButton.START), ProfileResolver.unboundButtons(r, Systems.byId("snes")!!.buttons))
    }

    @Test fun resolvedMapDrivesGameInput() {
        val set = ProfileSet(global = ProfileLayer(mapOf(PadButton.A to setOf(KeyEvent.KEYCODE_BUTTON_L2))))
        val input = GameInput(resolve(set), Systems.byId("snes")!!.buttons.toSet())
        input.onKeyCode(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.ACTION_DOWN)
        assertEquals(1 shl PadButton.A.retroId, input.mask)
        input.clear()
        input.onKeyCode(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN)     // the old A key does nothing now
        assertEquals(0, input.mask)
    }

    @Test fun layersEditAndClearByScope() {
        var set = ProfileSet()
        val sys = ProfileScope.System("gb")
        set = set.with(sys, ProfileLayer(mapOf(PadButton.A to setOf(97))))
        assertFalse(set.layer(sys).isEmpty)
        assertTrue(set.layer(ProfileScope.System("snes")).isEmpty)
        set = set.with(sys, ProfileLayer())
        assertTrue(set.systems.isEmpty())
    }

    @Test fun jsonRoundTripKeepsEveryLevel() {
        val set = ProfileSet(
            ProfileLayer(mapOf(PadButton.A to setOf(97, 96), PadButton.START to setOf(108))),
            mapOf("gba" to ProfileLayer(mapOf(PadButton.L to setOf(102)))),
            mapOf(gameKey("snes", "Sonic (U) [!].smc") to ProfileLayer(mapOf(PadButton.X to setOf(99)))),
        )
        val f = File(tmp.root, "p/profiles.json")
        val store = ProfileStore(f)
        store.save(set)
        assertEquals(set, ProfileStore(f).load())
        store.export(set, File(tmp.root, "export.json"))
        val imported = store.import(File(tmp.root, "export.json"))
        assertEquals(set, imported.set)
        assertEquals(0, imported.skippedEntries)
    }

    @Test fun importSkipsBadEntriesAndRejectsForeignFiles() {
        val f = File(tmp.root, "in.json")
        f.writeText("""{"app":"retrobox-tv","version":1,"global":{"A":[97,4,-3],"NOT_A_BUTTON":[1],"B":"oops"},"systems":{"snes":{}},"games":{}}""")
        val r = ProfileStore(File(tmp.root, "x.json")).import(f)
        assertEquals(setOf(97), r.set.global.bindings[PadButton.A])      // KEYCODE_BACK (4) is reserved, -3 invalid
        assertEquals(3, r.skippedEntries)
        assertTrue(r.set.systems.isEmpty())
        f.writeText("""{"hello":"world"}""")
        try { ProfileStore(File(tmp.root, "x.json")).import(f); org.junit.Assert.fail("foreign file accepted") } catch (_: JSONException) {}
        f.writeText("not json")
        try { ProfileStore(File(tmp.root, "x.json")).import(f); org.junit.Assert.fail("garbage accepted") } catch (_: JSONException) {}
    }

    @Test fun corruptStoreFileFallsBackToDefaults() {
        val f = File(tmp.root, "profiles.json").also { it.writeText("{{{") }
        assertEquals(ProfileSet(), ProfileStore(f).load())
    }

    @Test fun clearingAButtonRemovesAllItsKeysButNothingElse() {
        val layer = ProfileResolver.clear(ProfileLayer(), PadButton.B)
        val r = ProfileResolver.resolve(defaults, ProfileSet(global = layer), "snes", null)
        assertTrue(r.none { it.value == PadButton.B })
        assertEquals(PadButton.A, r[KeyEvent.KEYCODE_BUTTON_B])          // other buttons untouched
        assertEquals(listOf(PadButton.B), ProfileResolver.unboundButtons(r, Systems.byId("snes")!!.buttons))
    }
}

class ProfilesPlayerTwoStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun playerTwoLevelsRoundTripIncludingAnEmptyCustomLevel() {
        val set = ProfileSet(global = ProfileLayer(mapOf(PadButton.A to setOf(KeyEvent.KEYCODE_BUTTON_L2))))
            .withPlayer2Custom(ProfileScope.Global, true).with(ProfileScope.System("nes"), 1, ProfileLayer())
            .with(ProfileScope.Game("snes", "a b.sfc"), 1, ProfileLayer(mapOf(PadButton.Y to setOf(KeyEvent.KEYCODE_BUTTON_R2))))
        val f = File(tmp.root, "profiles.json")
        ProfileStore(f).save(set)
        val back = ProfileStore(f).load()
        assertEquals(set.player2, back.player2)
        assertTrue(back.isPlayer2Custom(ProfileScope.System("nes")))            // empty but still custom
        assertFalse(back.isPlayer2Custom(ProfileScope.System("snes")))
    }

    @Test fun filesWithoutPlayerTwoStillLoadAndPlayerTwoFollowsPlayerOne() {
        val old = """{"app":"retrobox-tv","version":1,"global":{"A":[96]},"systems":{},"games":{}}"""
        val r = ProfileStore.parse(old)
        assertTrue(r.set.player2.isEmpty()); assertEquals(0, r.skippedEntries)
        assertEquals(ProfileResolver.resolve(DefaultMappings.gamepad, r.set, "nes", null, 0), ProfileResolver.resolve(DefaultMappings.gamepad, r.set, "nes", null, 1))
    }

    @Test fun unknownPlayerTwoKeysAreSkippedAndCounted() {
        val text = """{"app":"retrobox-tv","version":1,"global":{},"systems":{},"games":{},"player2":{"global":{"A":[96]},"bogus":{"A":[96]}}}"""
        val r = ProfileStore.parse(text)
        assertEquals(setOf("global"), r.set.player2.keys)
        assertEquals(1, r.skippedEntries)
    }

    @Test fun playerTwoReservedKeysAreDroppedOnImportLikeForPlayerOne() {
        val text = """{"app":"retrobox-tv","version":1,"global":{},"systems":{},"games":{},"player2":{"global":{"A":[4,96]}}}"""   // 4 = BACK
        val r = ProfileStore.parse(text)
        assertEquals(setOf(96), r.set.player2.getValue("global").bindings[PadButton.A])
        assertEquals(1, r.skippedEntries)
    }
}
