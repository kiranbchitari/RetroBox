package com.retrobox.tv.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Initialization-failure handling, driven through a fake JNI layer (no native code needed). */
class LibretroCoreFactoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val nes = Systems.byId("nes")!!
    private val fceumm = nes.defaultCore!!
    private lateinit var libDir: File
    private lateinit var rom: File
    private lateinit var api: FakeApi

    @Before fun setUp() {
        libDir = tmp.newFolder("lib")
        rom = File(tmp.root, "game.nes").also { it.writeBytes(byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(1036)) }
        api = FakeApi()
    }

    private fun factory() = LibretroCore.Factory(api, libDir, tmp.newFolder("system"), tmp.newFolder("saves"))
    private fun installCore() = File(libDir, "lib${fceumm.id}_libretro_android.so").writeText("x")

    @Test fun missingCoreLibraryGivesClearError() {
        val r = factory().create(nes, fceumm, rom) as CoreResult.Err
        assertEquals(ErrorKind.CORE_MISSING, r.error.kind)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun coreInitFailureIsReportedNotThrown() {
        installCore(); api.loadCoreError = "dlopen failed: bad ELF"
        val r = factory().create(nes, fceumm, rom) as CoreResult.Err
        assertEquals(ErrorKind.CORE_LOAD_FAILED, r.error.kind)
        assertTrue(r.error.message.contains("bad ELF"))
    }

    @Test fun coreRejectingRomUnloadsCoreAndReports() {
        installCore(); api.loadGameError = "unsupported mapper"
        val r = factory().create(nes, fceumm, rom) as CoreResult.Err
        assertEquals(ErrorKind.ROM_REJECTED, r.error.kind)
        assertTrue("core must be released after a failed load", "unloadCore" in api.calls)
    }

    @Test fun invalidRomNeverReachesTheCore() {
        installCore()
        val bad = File(tmp.root, "bad.nes").also { it.writeBytes(ByteArray(2048)) }
        val r = factory().create(nes, fceumm, bad) as CoreResult.Err
        assertEquals(ErrorKind.ROM_INVALID, r.error.kind)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun successReturnsCoreWithAvInfoAndCloseReleasesOnce() {
        installCore()
        val core = (factory().create(nes, fceumm, rom) as CoreResult.Ok).value
        assertEquals(256, core.av.width)
        assertEquals(48000, core.av.sampleRate)
        core.close(); core.close()
        assertEquals(1, api.calls.count { it == "unloadCore" })
    }

    @Test fun anAbandonedCoreNeverUnloadsANewerOne() {
        installCore()
        val f = factory()
        val old = (f.create(nes, fceumm, rom) as CoreResult.Ok).value   // a screen closed while this was loading
        val newer = (f.create(nes, fceumm, rom) as CoreResult.Ok).value
        old.close()
        assertEquals("closing the abandoned core must not unload the one now in use", 0, api.calls.count { it == "unloadCore" })
        newer.close()
        assertEquals(1, api.calls.count { it == "unloadCore" })
    }

    @Test fun aClosedScreenNeverLoadsACore() {
        installCore()
        val r = factory().create(nes, fceumm, rom) { true } as CoreResult.Err
        assertEquals(ErrorKind.CORE_LOAD_FAILED, r.error.kind)
        assertTrue(api.calls.isEmpty())
    }

    private class FakeApi : NativeApi {
        val calls = mutableListOf<String>()
        var loadCoreError: String? = null
        var loadGameError: String? = null
        override fun loadCore(path: String, systemDir: String, saveDir: String): String? { calls += "loadCore"; return loadCoreError }
        override fun loadGame(path: String): String? { calls += "loadGame"; return loadGameError }
        override fun unloadGame() { calls += "unloadGame" }
        override fun unloadCore() { calls += "unloadCore" }
        override fun coreInfo() = arrayOf("Fake", "1.0", "nes", "1")
        override fun avInfo() = doubleArrayOf(256.0, 224.0, 1.306, 60.1, 48000.0, 256.0, 240.0)
        override fun runFrame() = true
        override fun reset() {}
        override fun readAudio(out: ShortArray) = 0
        override fun setInput(port: Int, mask: Int, lx: Int, ly: Int, rx: Int, ry: Int) {}
        override fun setOption(key: String, value: String) {}
        override fun saveState(): ByteArray? = null
        override fun loadState(data: ByteArray) = false
        override fun saveRam(): ByteArray? = null
        override fun loadSaveRam(data: ByteArray) = false
        override fun drainMessages(): String? = null
        override fun stats() = LongArray(7)
        override fun snapshot(): IntArray? = null
        override fun resetStats() {}
        override fun glInit() {}
        override fun glResize(w: Int, h: Int) {}
        override fun glDraw() {}
        override fun glRelease() {}
        override fun setDisplay(mode: Int, filter: Int, shader: Int, safePermille: Int) {}
        override fun setRenderTiming(on: Boolean) {}
        override fun renderStats() = LongArray(4)
        override fun shaderStatus() = ""
        override fun glInfo() = ""
    }
}
