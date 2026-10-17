package com.retrobox.tv.game

import android.content.Context

/** Which libretro core the user picked per system (only matters where a system has more than one, e.g. GBA: gpSP / mGBA). */
interface CorePreferences {
    fun chosenCoreId(systemId: String): String?
    fun choose(systemId: String, coreId: String)
}

class PrefsCorePreferences(context: Context) : CorePreferences {
    private val prefs = context.getSharedPreferences("cores", Context.MODE_PRIVATE)
    override fun chosenCoreId(systemId: String): String? = prefs.getString("$systemId.core", null)
    override fun choose(systemId: String, coreId: String) { prefs.edit().putString("$systemId.core", coreId).apply() }
}
