package com.retrobox.tv.settings

import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * `settings.json` in the app's private files. Reads are forgiving (a damaged file or an unknown value never stops the app: it
 * falls back to defaults and [lastProblem] says what happened); writes go through a temp file so a crash cannot leave half a file.
 */
class SettingsStore(private val file: File) {
    @Volatile var lastProblem: String? = null
        private set

    fun load(): AppSettings {
        lastProblem = null
        if (!file.exists()) return AppSettings()
        return try { parse(file.readText()) } catch (e: JSONException) {
            lastProblem = "settings.json is damaged (${e.message}); defaults are used until you change a setting"
            AppSettings()
        } catch (e: java.io.IOException) {
            lastProblem = "settings.json could not be read (${e.message}); defaults are used"
            AppSettings()
        }
    }

    fun save(s: AppSettings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(s).toString(2))
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException("could not replace ${file.name}") }
    }

    companion object {
        const val VERSION = 1

        fun toJson(s: AppSettings): JSONObject = JSONObject().apply {
            put("version", VERSION)
            put("graphics", graphicsJson(s.graphics))
            put("perSystem", JSONObject().also { o ->
                s.perSystem.filterValues { !it.isEmpty }.toSortedMap().forEach { (id, ov) ->
                    o.put(id, JSONObject().apply {
                        ov.scale?.let { put("scale", it.code) }; ov.bilinear?.let { put("bilinear", it) }; ov.shader?.let { put("shader", it.code) }
                    })
                }
            })
            put("audio", JSONObject().put("enabled", s.audio.enabled).put("volumePct", s.audio.volumePct).put("bufferMs", s.audio.bufferMs))
            put("safeAreaPct", s.safeAreaPct)
            put("overlay", s.overlay)
            put("fastForwardSpeed", s.fastForwardSpeed)
            put("libraryView", s.libraryView)
            put("turbo", JSONObject().also { o ->
                s.turbo.filterValues { it.isNotEmpty() }.toSortedMap().forEach { (id, set) -> o.put(id, org.json.JSONArray(set.map { it.name }.sorted())) }
            })
        }

        private fun graphicsJson(g: GraphicsSettings) = JSONObject().put("scale", g.scale.code).put("bilinear", g.bilinear).put("shader", g.shader.code)

        /** Throws [JSONException] when the text is not JSON at all; wrong or missing fields inside it fall back to defaults. */
        fun parse(text: String): AppSettings {
            val root = JSONObject(text)
            val d = AppSettings()
            val g = root.optJSONObject("graphics")
            val a = root.optJSONObject("audio")
            val ps = root.optJSONObject("perSystem")
            val per = buildMap {
                ps?.keys()?.forEach { id ->
                    val o = ps.optJSONObject(id) ?: return@forEach
                    val ov = GraphicsOverride(
                        scale = if (o.has("scale")) ScaleMode.of(o.optInt("scale", -1)) else null,
                        bilinear = if (o.has("bilinear")) o.optBoolean("bilinear") else null,
                        shader = if (o.has("shader")) ShaderMode.of(o.optInt("shader", -1)) else null,
                    )
                    if (!ov.isEmpty) put(id, ov)
                }
            }
            return AppSettings(
                graphics = GraphicsSettings(
                    ScaleMode.of(g?.optInt("scale", 0)), g?.optBoolean("bilinear", false) ?: false, ShaderMode.of(g?.optInt("shader", 0)),
                ),
                perSystem = per,
                audio = AudioSettings(
                    enabled = a?.optBoolean("enabled", true) ?: true,
                    volumePct = (a?.optInt("volumePct", 100) ?: 100).coerceIn(0, 100),
                    bufferMs = (a?.optInt("bufferMs", d.audio.bufferMs) ?: d.audio.bufferMs).coerceIn(AudioSettings.bufferChoices.first(), AudioSettings.bufferChoices.last()),
                ),
                safeAreaPct = root.optInt("safeAreaPct", 0).coerceIn(0, AppSettings.MAX_SAFE_AREA_PCT),
                overlay = root.optBoolean("overlay", false),
                libraryView = root.optInt("libraryView", 0).coerceIn(0, AppSettings.MAX_LIBRARY_VIEW),
                fastForwardSpeed = root.optInt("fastForwardSpeed", d.fastForwardSpeed).takeIf { it in com.retrobox.tv.emulator.FastForward.choices } ?: d.fastForwardSpeed,
                turbo = buildMap {
                    val t = root.optJSONObject("turbo")
                    t?.keys()?.forEach { id ->
                        val arr = t.optJSONArray(id) ?: return@forEach
                        val allowed = com.retrobox.tv.emulator.Systems.byId(id)?.let { com.retrobox.tv.emulator.Turbo.eligible(it) } ?: return@forEach
                        val set = (0 until arr.length()).mapNotNull { i -> allowed.firstOrNull { it.name == arr.optString(i) } }.toSet()
                        if (set.isNotEmpty()) put(id, set)
                    }
                },
            )
        }
    }
}
