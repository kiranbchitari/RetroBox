package com.retrobox.tv.profile

import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.game.Hotkeys
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Overrides for one level. For every RetroPad button it mentions, the listed Android key codes REPLACE the keys that
 * level below it gave that button; buttons it does not mention are left alone.
 */
data class ProfileLayer(val bindings: Map<PadButton, Set<Int>> = emptyMap()) {
    val isEmpty get() = bindings.isEmpty()
}

sealed interface ProfileScope {
    data object Global : ProfileScope
    data class System(val systemId: String) : ProfileScope
    data class Game(val systemId: String, val fileName: String) : ProfileScope {
        val key get() = gameKey(systemId, fileName)
    }
}

fun gameKey(systemId: String, fileName: String) = "$systemId|$fileName"

/** Where a layer is stored in [ProfileSet.player2]; also the order levels are applied in is global → system → game. */
fun scopeKey(scope: ProfileScope): String = when (scope) {
    ProfileScope.Global -> "global"
    is ProfileScope.System -> "system:${scope.systemId}"
    is ProfileScope.Game -> "game:${scope.key}"
}

/**
 * All saved overrides: global, per system, per game (most specific wins), for Player 1.
 *
 * Player 2 follows Player 1 level by level: a level (all systems / one system / one game) is either **copied** from Player 1
 * (the default, and it keeps following Player 1's changes) or **custom**, meaning [player2] holds its own layer for it
 * (possibly empty = "the defaults"). Presence of the key is what makes a level custom.
 */
data class ProfileSet(
    val global: ProfileLayer = ProfileLayer(),
    val systems: Map<String, ProfileLayer> = emptyMap(),
    val games: Map<String, ProfileLayer> = emptyMap(),
    val player2: Map<String, ProfileLayer> = emptyMap(),
) {
    fun isPlayer2Custom(scope: ProfileScope) = scopeKey(scope) in player2

    /** The layer that applies at [scope] for [player] (0 or 1): Player 2's own when custom, otherwise Player 1's. */
    fun layer(scope: ProfileScope, player: Int): ProfileLayer =
        if (player == 1 && isPlayer2Custom(scope)) player2.getValue(scopeKey(scope)) else layer(scope)

    /** Stores [layer] for [player]; Player 2's layer makes that level custom even when it is empty. */
    fun with(scope: ProfileScope, player: Int, layer: ProfileLayer): ProfileSet =
        if (player == 1) copy(player2 = player2 + (scopeKey(scope) to layer)) else with(scope, layer)

    /** Custom: Player 2 gets a copy of what Player 1 has at this level to start from. Copy: Player 2 follows Player 1 again. */
    fun withPlayer2Custom(scope: ProfileScope, custom: Boolean): ProfileSet =
        if (custom) copy(player2 = player2 + (scopeKey(scope) to (if (isPlayer2Custom(scope)) player2.getValue(scopeKey(scope)) else layer(scope))))
        else copy(player2 = player2 - scopeKey(scope))

    fun layer(scope: ProfileScope): ProfileLayer = when (scope) {
        ProfileScope.Global -> global
        is ProfileScope.System -> systems[scope.systemId] ?: ProfileLayer()
        is ProfileScope.Game -> games[scope.key] ?: ProfileLayer()
    }

    fun with(scope: ProfileScope, layer: ProfileLayer): ProfileSet = when (scope) {
        ProfileScope.Global -> copy(global = layer)
        is ProfileScope.System -> copy(systems = if (layer.isEmpty) systems - scope.systemId else systems + (scope.systemId to layer))
        is ProfileScope.Game -> copy(games = if (layer.isEmpty) games - scope.key else games + (scope.key to layer))
    }
}

object ProfileResolver {
    /** Android key code -> RetroPad button as it applies to this game: defaults, then global, system and game overrides. */
    fun resolve(defaults: Map<Int, PadButton>, set: ProfileSet, systemId: String, fileName: String?, player: Int = 0): Map<Int, PadButton> {
        val map = LinkedHashMap(defaults)
        val scopes = listOfNotNull(ProfileScope.Global, ProfileScope.System(systemId), fileName?.let { ProfileScope.Game(systemId, it) })
        for (scope in scopes) applyLayer(map, set.layer(scope, player))   // an absent layer is empty: a no-op
        return map
    }

    private fun applyLayer(map: MutableMap<Int, PadButton>, layer: ProfileLayer) {
        val claimed = layer.bindings.values.flatten().toSet()
        map.entries.removeAll { it.value in layer.bindings.keys || it.key in claimed }
        for ((button, keys) in layer.bindings) for (k in keys) map[k] = button
    }

    /** Resolution for one scope level, i.e. what the editor shows when editing [scope]. */
    fun resolveForScope(defaults: Map<Int, PadButton>, set: ProfileSet, scope: ProfileScope, player: Int = 0): Map<Int, PadButton> = when (scope) {
        ProfileScope.Global -> resolve(defaults, set, "", null, player)
        is ProfileScope.System -> resolve(defaults, set, scope.systemId, null, player)
        is ProfileScope.Game -> resolve(defaults, set, scope.systemId, scope.fileName, player)
    }

    /** Keys that open the pause menu (or mean "nothing") cannot be gameplay buttons. */
    fun isReserved(keyCode: Int) = keyCode in Hotkeys.menuKeys || keyCode == android.view.KeyEvent.KEYCODE_UNKNOWN

    /** The button that currently uses [keyCode] if it is not [target] (binding it would take it away from that button). */
    fun conflict(resolved: Map<Int, PadButton>, keyCode: Int, target: PadButton): PadButton? = resolved[keyCode]?.takeIf { it != target }

    fun keysOf(resolved: Map<Int, PadButton>, button: PadButton): List<Int> = resolved.filterValues { it == button }.keys.toList()

    /**
     * New [scope]-level layer after pressing [keyCode] for [button]: that key becomes the button's only key at this level,
     * and a button that loses the key keeps its other keys (written down explicitly so the override is self-contained).
     */
    fun bind(current: ProfileLayer, resolvedBefore: Map<Int, PadButton>, button: PadButton, keyCode: Int): ProfileLayer {
        val b = LinkedHashMap(current.bindings)
        val loser = resolvedBefore[keyCode]
        if (loser != null && loser != button) b[loser] = (b[loser] ?: keysOf(resolvedBefore, loser).toSet()) - keyCode
        b[button] = setOf(keyCode)
        return ProfileLayer(b)
    }

    /** [button] ends up with no key at this level (its default and lower-level keys are removed too). */
    fun clear(current: ProfileLayer, button: PadButton): ProfileLayer = ProfileLayer(current.bindings + (button to emptySet()))

    /** Duplicate keys inside one resolved map cannot exist, but two buttons of the SAME system may end up with no key at all. */
    fun unboundButtons(resolved: Map<Int, PadButton>, buttons: List<PadButton>): List<PadButton> = buttons.filter { b -> resolved.none { it.value == b } }
}

class ImportResult(val set: ProfileSet, val skippedEntries: Int)

/** `profiles.json`; also the import/export format (version 1). Unknown or malformed entries are skipped, not fatal. */
class ProfileStore(private val file: File) {
    /** Why the last [load] fell back to defaults, or null. */
    @Volatile var lastProblem: String? = null
        private set

    /**
     * A damaged file falls back to defaults, but is first copied to `profiles.json.bad` and reported (error log and
     * [lastProblem]): the next change would otherwise overwrite every saved layout without a trace.
     */
    fun load(): ProfileSet {
        lastProblem = null
        if (!file.exists()) return ProfileSet()
        return try { parse(file.readText()).set } catch (e: Exception) {
            val bad = File(file.path + ".bad")
            runCatching { file.copyTo(bad, overwrite = true) }
            lastProblem = "${file.name} is damaged (${e.message}); default controls are used. The old file was kept as ${bad.name}."
            com.retrobox.tv.util.ErrorLog.w("profiles", lastProblem!!)
            ProfileSet()
        }
    }

    fun save(set: ProfileSet) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(toJson(set))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun export(set: ProfileSet, to: File) { to.parentFile?.mkdirs(); to.writeText(toJson(set)) }

    /** @throws JSONException / IOException when the file is not a RetroBox profile file at all. */
    fun import(from: File): ImportResult = parse(from.readText())

    companion object {
        const val VERSION = 1

        fun toJson(set: ProfileSet): String = JSONObject().apply {
            put("app", "retrobox-tv"); put("version", VERSION)
            put("global", layerJson(set.global))
            put("systems", JSONObject().apply { set.systems.toSortedMap().forEach { (k, v) -> put(k, layerJson(v)) } })
            put("games", JSONObject().apply { set.games.toSortedMap().forEach { (k, v) -> put(k, layerJson(v)) } })
            // Present key = that level has its own Player 2 controls (an empty object means "defaults"); absent = copy of Player 1.
            if (set.player2.isNotEmpty()) put("player2", JSONObject().apply { set.player2.toSortedMap().forEach { (k, v) -> put(k, layerJson(v)) } })
        }.toString(2)

        private fun layerJson(l: ProfileLayer) = JSONObject().apply {
            l.bindings.toSortedMap(compareBy { it.retroId }).forEach { (b, keys) -> put(b.name, JSONArray(keys.sorted())) }
        }

        fun parse(text: String): ImportResult {
            val root = JSONObject(text)
            if (root.optString("app") != "retrobox-tv") throw JSONException("Not a RetroBox profile file")
            if (root.optInt("version", 0) != VERSION) throw JSONException("Unsupported profile file version ${root.optInt("version", 0)}")
            var skipped = 0
            fun layer(o: JSONObject?): ProfileLayer {
                if (o == null) return ProfileLayer()
                val m = LinkedHashMap<PadButton, Set<Int>>()
                for (name in o.keys()) {
                    val button = PadButton.entries.firstOrNull { it.name == name }
                    val arr = o.optJSONArray(name)
                    if (button == null || arr == null) { skipped++; continue }
                    val keys = (0 until arr.length()).mapNotNull { i -> arr.optInt(i, -1).takeIf { it > 0 && !ProfileResolver.isReserved(it) } }
                    if (keys.size != arr.length()) skipped++
                    m[button] = keys.toSet()
                }
                return ProfileLayer(m)
            }
            fun map(o: JSONObject?): Map<String, ProfileLayer> =
                o?.keys()?.asSequence()?.associateWith { layer(o.optJSONObject(it)) }?.filterValues { !it.isEmpty } ?: emptyMap()
            val p2o = root.optJSONObject("player2")
            val p2 = p2o?.keys()?.asSequence()?.associateWith { layer(p2o.optJSONObject(it)) }?.filterKeys { it == "global" || it.startsWith("system:") || it.startsWith("game:") } ?: emptyMap()
            if (p2o != null && p2.size != p2o.length()) skipped++
            return ImportResult(ProfileSet(layer(root.optJSONObject("global")), map(root.optJSONObject("systems")), map(root.optJSONObject("games")), p2), skipped)
        }
    }
}
