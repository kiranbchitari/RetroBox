package com.retrobox.tv.game

import android.view.KeyEvent
import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.emulator.Systems
import com.retrobox.tv.profile.ProfileLayer
import com.retrobox.tv.profile.ProfileResolver
import com.retrobox.tv.profile.ProfileScope
import com.retrobox.tv.profile.ProfileSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayersTest {
    private fun bit(b: PadButton) = 1 shl b.retroId
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    private fun inputs(set: ProfileSet = ProfileSet(), system: String = "snes", n: Int = 2): PlayerInputs {
        val buttons = Systems.byId(system)!!.buttons.toSet()
        val list = (0 until n).map { GameInput(ProfileResolver.resolve(DefaultMappings.gamepad, set, system, "g.sfc", it), buttons) }
        return PlayerInputs(list, PlayerAssigner(n))
    }

    // ---- assigner ----

    @Test fun oldestPadIsPlayerOneAtStart() {
        val a = PlayerAssigner(2)
        a.assignInitial(listOf(9, 4))
        assertEquals(4, a.deviceOf(0)); assertEquals(9, a.deviceOf(1))
        assertEquals(0, a.playerOf(4)); assertEquals(1, a.playerOf(9))
    }

    @Test fun aThirdPadGetsNoSlotUntilOneIsFree() {
        val a = PlayerAssigner(2)
        a.assignInitial(listOf(1, 2, 3))
        assertNull(a.playerOf(3)); assertNull(a.claim(3))
        assertEquals(1, a.release(2))
        assertEquals(1, a.claim(3))          // takes the slot that just freed
    }

    @Test fun releasingAnUnassignedPadChangesNothing() {
        val a = PlayerAssigner(2)
        a.assignInitial(listOf(1))
        assertNull(a.release(77))
        assertEquals(1, a.deviceOf(0))
    }

    @Test fun aPadThatReconnectsToAnEmptyStartTakesTheFirstFreeSlot() {
        val a = PlayerAssigner(2)
        a.assignInitial(listOf(5, 6))
        a.release(5)
        assertEquals(0, a.claim(8))
        assertEquals(6, a.deviceOf(1))
    }

    @Test fun swapExchangesPlayersEvenWithOneEmptySlot() {
        val a = PlayerAssigner(2)
        a.assignInitial(listOf(5, 6)); a.swap()
        assertEquals(6, a.deviceOf(0)); assertEquals(5, a.deviceOf(1))
        val lone = PlayerAssigner(2); lone.assignInitial(listOf(5)); lone.swap()
        assertNull(lone.deviceOf(0)); assertEquals(5, lone.deviceOf(1))
    }

    @Test fun singlePlayerAssignerNeverSwapsOrOverflows() {
        val a = PlayerAssigner(1)
        a.assignInitial(listOf(1, 2)); a.swap()
        assertEquals(1, a.deviceOf(0)); assertNull(a.deviceOf(1))
    }

    // ---- routing ----

    @Test fun eachPadDrivesItsOwnPlayer() {
        val p = inputs(); p.assigner.assignInitial(listOf(10, 11))
        p.onKey(10, true, KeyEvent.KEYCODE_BUTTON_A, down)      // Player 1: positional A = RetroPad B
        p.onKey(11, true, KeyEvent.KEYCODE_DPAD_UP, down)       // Player 2: up
        assertEquals(bit(PadButton.B), p.mask(0))
        assertEquals(bit(PadButton.UP), p.mask(1))
        p.onKey(10, true, KeyEvent.KEYCODE_BUTTON_A, up)
        assertEquals(0, p.mask(0)); assertEquals(bit(PadButton.UP), p.mask(1))   // releasing one never touches the other
    }

    @Test fun opposingDirectionsOnTwoPadsDoNotCancel() {
        val p = inputs(); p.assigner.assignInitial(listOf(1, 2))
        p.onKey(1, true, KeyEvent.KEYCODE_DPAD_LEFT, down); p.onKey(2, true, KeyEvent.KEYCODE_DPAD_RIGHT, down)
        assertEquals(bit(PadButton.LEFT), p.mask(0)); assertEquals(bit(PadButton.RIGHT), p.mask(1))
    }

    @Test fun keyboardAndRemoteAlwaysPlayAsPlayerOne() {
        val p = inputs(); p.assigner.assignInitial(listOf(1, 2))
        val r = p.onKey(-1, false, KeyEvent.KEYCODE_Z, down)
        assertEquals(Route(0, true), r)
        assertEquals(bit(PadButton.B), p.mask(0)); assertEquals(0, p.mask(1))
    }

    @Test fun aPadNobodyAssignedClaimsTheNextSlotOnFirstPress() {
        val p = inputs()
        val r = p.onKey(30, true, KeyEvent.KEYCODE_BUTTON_START, down)
        assertEquals(0, r.player)
        assertEquals(1, p.onKey(31, true, KeyEvent.KEYCODE_BUTTON_START, down).player)
    }

    @Test fun aThirdPadIsSwallowedAndDrivesNobody() {
        val p = inputs(); p.assigner.assignInitial(listOf(1, 2))
        val r = p.onKey(3, true, KeyEvent.KEYCODE_BUTTON_A, down)
        assertEquals(Route(-1, true), r)
        assertEquals(0, p.mask(0)); assertEquals(0, p.mask(1))
        assertNull(p.onAxes(3, true, 1f, 0f, 0f, 0f))
    }

    @Test fun unmappedKeyIsNotHandledButStillReportsThePlayer() {
        val p = inputs(); p.assigner.assignInitial(listOf(1))
        assertEquals(Route(0, false), p.onKey(1, true, KeyEvent.KEYCODE_VOLUME_UP, down))
    }

    @Test fun onePlayerSystemsSendEveryPadToPlayerOne() {
        val p = inputs(system = "gb", n = 1)
        p.onKey(7, true, KeyEvent.KEYCODE_DPAD_LEFT, down); p.onKey(8, true, KeyEvent.KEYCODE_BUTTON_B, down)
        assertEquals(bit(PadButton.LEFT) or bit(PadButton.A), p.mask(0))
    }

    @Test fun sticksAndHatsRouteByDevice() {
        val p = inputs(); p.assigner.assignInitial(listOf(1, 2))
        assertEquals(1, p.onAxes(2, true, 0f, -1f, 0f, 0f))
        assertEquals(bit(PadButton.UP), p.mask(1)); assertEquals(0, p.mask(0))
        p.onAxes(1, true, 0f, 0f, 0.9f, 0f)
        assertEquals(bit(PadButton.RIGHT), p.mask(0)); assertEquals(bit(PadButton.UP), p.mask(1))
    }

    @Test fun releasingAPlayerClearsOnlyTheirButtons() {
        val p = inputs(); p.assigner.assignInitial(listOf(1, 2))
        p.onKey(1, true, KeyEvent.KEYCODE_BUTTON_A, down); p.onKey(2, true, KeyEvent.KEYCODE_BUTTON_A, down)
        p.clear(1)
        assertEquals(bit(PadButton.B), p.mask(0)); assertEquals(0, p.mask(1))
    }

    @Test fun selectAndStartOnEitherPadCountsForTheMenuChord() {
        val p = inputs(); p.assigner.assignInitial(listOf(1, 2))
        p.onKey(2, true, KeyEvent.KEYCODE_BUTTON_SELECT, down); assertFalse(p.selectAndStartDown)
        p.onKey(2, true, KeyEvent.KEYCODE_BUTTON_START, down); assertTrue(p.selectAndStartDown)
    }

    // ---- Player 2 controls: same as Player 1 or custom ----

    private val rebound = ProfileLayer(mapOf(PadButton.B to setOf(KeyEvent.KEYCODE_BUTTON_L2)))

    @Test fun playerTwoCopiesPlayerOneByDefault() {
        val set = ProfileSet(global = rebound)
        assertEquals(ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", "g.sfc", 0), ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", "g.sfc", 1))
        val p = inputs(set); p.assigner.assignInitial(listOf(1, 2))
        p.onKey(2, true, KeyEvent.KEYCODE_BUTTON_L2, down)
        assertEquals(bit(PadButton.B), p.mask(1))                  // Player 2 got Player 1's rebinding
    }

    @Test fun customPlayerTwoLeavesPlayerOneAlone() {
        val custom = ProfileLayer(mapOf(PadButton.B to setOf(KeyEvent.KEYCODE_BUTTON_R2)))
        val set = ProfileSet(global = rebound).withPlayer2Custom(ProfileScope.Global, true).with(ProfileScope.Global, 1, custom)
        val p = inputs(set); p.assigner.assignInitial(listOf(1, 2))
        p.onKey(1, true, KeyEvent.KEYCODE_BUTTON_L2, down); p.onKey(2, true, KeyEvent.KEYCODE_BUTTON_R2, down)
        assertEquals(bit(PadButton.B), p.mask(0)); assertEquals(bit(PadButton.B), p.mask(1))
        p.onKey(2, true, KeyEvent.KEYCODE_BUTTON_L2, down)           // L2 is not Player 2's B any more
        assertEquals(bit(PadButton.B), p.mask(1))
        assertEquals(PadButton.B, ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", "g.sfc", 0)[KeyEvent.KEYCODE_BUTTON_L2])
    }

    @Test fun switchingToCustomStartsFromACopyOfPlayerOne() {
        val set = ProfileSet(global = rebound).withPlayer2Custom(ProfileScope.Global, true)
        assertTrue(set.isPlayer2Custom(ProfileScope.Global))
        assertEquals(rebound, set.layer(ProfileScope.Global, 1))
        assertEquals(ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", null, 0), ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", null, 1))
    }

    @Test fun backToCopyFollowsPlayerOneAgainAndForgetsTheCustomLayer() {
        var set = ProfileSet().withPlayer2Custom(ProfileScope.Global, true).with(ProfileScope.Global, 1, rebound)
        assertEquals(PadButton.B, ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", null, 1)[KeyEvent.KEYCODE_BUTTON_L2])
        set = set.withPlayer2Custom(ProfileScope.Global, false)
        assertFalse(set.isPlayer2Custom(ProfileScope.Global))
        assertEquals(PadButton.L2, ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", null, 1)[KeyEvent.KEYCODE_BUTTON_L2])   // back to the default binding
    }

    @Test fun levelsAreCopiedOrCustomIndependently() {
        // Global stays a copy; the SNES level is custom for Player 2; a game level is Player 1's own override.
        val sys = ProfileScope.System("snes")
        val p2sys = ProfileLayer(mapOf(PadButton.A to setOf(KeyEvent.KEYCODE_BUTTON_R2)))
        val gameP1 = ProfileLayer(mapOf(PadButton.X to setOf(KeyEvent.KEYCODE_BUTTON_THUMBL)))
        val set = ProfileSet(global = rebound).withPlayer2Custom(sys, true).with(sys, 1, p2sys)
            .with(ProfileScope.Game("snes", "g.sfc"), gameP1)
        val r2 = ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", "g.sfc", 1)
        assertEquals(PadButton.B, r2[KeyEvent.KEYCODE_BUTTON_L2])      // global copied from Player 1
        assertEquals(PadButton.A, r2[KeyEvent.KEYCODE_BUTTON_R2])      // system level is Player 2's own
        assertEquals(PadButton.X, r2[KeyEvent.KEYCODE_BUTTON_THUMBL])  // game level not custom: follows Player 1
        val r1 = ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", "g.sfc", 0)
        assertTrue("Player 1 never sees Player 2's layer", r1[KeyEvent.KEYCODE_BUTTON_R2] != PadButton.A)
    }

    @Test fun anEmptyCustomLayerMeansDefaultsNotCopy() {
        val set = ProfileSet(global = rebound).withPlayer2Custom(ProfileScope.Global, true).with(ProfileScope.Global, 1, ProfileLayer())
        assertTrue(set.isPlayer2Custom(ProfileScope.Global))
        assertEquals(PadButton.L2, ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", null, 1)[KeyEvent.KEYCODE_BUTTON_L2])   // defaults, not Player 1's rebinding
        assertEquals(PadButton.B, ProfileResolver.resolve(DefaultMappings.gamepad, set, "snes", null, 0)[KeyEvent.KEYCODE_BUTTON_L2])
    }

    @Test fun onlyNesSnesAndOriginalsAreTwoPlayer() {
        assertEquals(listOf("nes", "snes", "originals"), Systems.all.filter { it.players > 1 }.map { it.id })
        assertTrue(Systems.all.all { it.players in 1..2 })
    }
}
