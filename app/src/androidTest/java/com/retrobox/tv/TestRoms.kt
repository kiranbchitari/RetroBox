package com.retrobox.tv

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.retrobox.tv.emulator.LibretroCore
import com.retrobox.tv.emulator.NativeHost
import java.io.File

/**
 * The original CC0 test ROMs (tools/make_test_rom*.py, tools/gba-testrom) shipped in the test APK's assets, and a scratch
 * area under the app's cache so the tests never touch the player's ROMs, saves, settings or library.
 */
object TestRoms {
    val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testApk: Context get() = InstrumentationRegistry.getInstrumentation().context

    val scratch: File get() = File(app.cacheDir, "device-tests")

    fun copy(name: String): File {
        val out = File(scratch, "roms/$name")
        out.parentFile!!.mkdirs()
        testApk.assets.open("testroms/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    fun factory() = LibretroCore.Factory(NativeHost, File(app.applicationInfo.nativeLibraryDir), File(scratch, "system"), File(scratch, "saves"))

    fun clean() { scratch.deleteRecursively() }

    /** RETRO_DEVICE_ID_JOYPAD_* bit for [setInput]. */
    fun bit(retroId: Int) = 1 shl retroId
}
