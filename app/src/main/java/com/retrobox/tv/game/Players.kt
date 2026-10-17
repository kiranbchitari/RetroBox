package com.retrobox.tv.game

/**
 * Which connected gamepad is which player. Pure (device ids are plain ints) so the rules can be unit-tested.
 * Keyboards and TV remotes are not gamepads: they are not tracked here and always belong to Player 1.
 */
class PlayerAssigner(val maxPlayers: Int = 2) {
    private val slots = IntArray(maxPlayers) { NONE }

    fun deviceOf(player: Int): Int? = slots.getOrNull(player)?.takeIf { it != NONE }
    fun playerOf(deviceId: Int): Int? = slots.indexOf(deviceId).takeIf { it >= 0 }

    /** Pads present when a game starts, oldest (lowest id) first, fill Player 1, Player 2, … Extra pads wait for a free slot. */
    fun assignInitial(connectedGamepadIds: List<Int>) {
        slots.fill(NONE)
        connectedGamepadIds.sorted().forEach { claim(it) }
    }

    /** The player [deviceId] is, taking the lowest free slot the first time it is seen; null when every slot is taken by other pads. */
    fun claim(deviceId: Int): Int? {
        playerOf(deviceId)?.let { return it }
        val free = slots.indexOf(NONE)
        if (free < 0) return null
        slots[free] = deviceId
        return free
    }

    /** Frees the slot of a pad that went away; returns the player that lost it (null if it was not assigned). */
    fun release(deviceId: Int): Int? {
        val p = playerOf(deviceId) ?: return null
        slots[p] = NONE
        return p
    }

    /** Exchanges Player 1 and Player 2 (an empty slot moves too, so a lone pad can become Player 2). */
    fun swap() {
        if (maxPlayers < 2) return
        val t = slots[0]; slots[0] = slots[1]; slots[1] = t
    }

    private companion object { const val NONE = Int.MIN_VALUE }
}

/** What happened to one input event: which player it belongs to, and whether the game took it (so the caller must not also use it for the UI). */
data class Route(val player: Int, val handled: Boolean)

/**
 * One [GameInput] per player, fed by device. With one player every pad drives Player 1 (as before multiplayer existed);
 * with two, a gamepad's events go to the player it is assigned to and a third pad is ignored.
 */
class PlayerInputs(private val inputs: List<GameInput>, val assigner: PlayerAssigner) {
    val players get() = inputs.size

    fun mask(player: Int) = inputs[player].mask

    /** Select + Start held on any player's pad (opens the pause menu). */
    val selectAndStartDown get() = inputs.any { it.selectAndStartDown }

    private fun playerFor(deviceId: Int, gamepad: Boolean): Int? = when {
        !gamepad || inputs.size == 1 -> 0
        else -> assigner.claim(deviceId)
    }

    fun onKey(deviceId: Int, gamepad: Boolean, keyCode: Int, action: Int): Route {
        val p = playerFor(deviceId, gamepad) ?: return Route(-1, handled = gamepad)   // third pad: swallow its buttons, drive nobody
        return Route(p, inputs[p].onKeyCode(keyCode, action))
    }

    /** Hat/stick state of a pad; returns the player it drives, or null when nobody. */
    fun onAxes(deviceId: Int, gamepad: Boolean, hx: Float, hy: Float, sx: Float, sy: Float): Int? {
        val p = playerFor(deviceId, gamepad) ?: return null
        inputs[p].onAxes(hx, hy, sx, sy)
        return p
    }

    fun clear() = inputs.forEach { it.clear() }

    /** Some player's layout uses [keyCode] for a button of this system. */
    fun anyPlays(keyCode: Int) = inputs.any { it.drivesGame(keyCode) }

    /** Releases everything Player [player] was holding (their pad disconnected, so no button may stay stuck down). */
    fun clear(player: Int) { inputs.getOrNull(player)?.clear() }
}
