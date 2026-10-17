package com.retrobox.tv

import android.util.Log
import com.retrobox.tv.emulator.CoreResult
import com.retrobox.tv.emulator.CoreSpec
import com.retrobox.tv.emulator.EmulatorCore
import com.retrobox.tv.emulator.ErrorKind
import com.retrobox.tv.emulator.NativeHost
import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.emulator.Systems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every packaged core plays its test ROM on this device: it loads, produces frames and sound, reacts to buttons,
 * restores a save state to the exact same picture, and (where the cartridge has a battery) keeps its save RAM
 * across a reload. Real native cores, no fakes — run it on the TV to learn whether each core works there.
 */
@RunWith(Parameterized::class)
class CoreDeviceTest(private val systemId: String, private val coreId: String, private val rom: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} / {1}")
        fun cases() = listOf(
            arrayOf("nes", "fceumm", "rb-testrom.nes"),
            arrayOf("snes", "snes9x2005", "rb-testrom.sfc"),
            arrayOf("gb", "gambatte", "rb-testrom.gb"),
            arrayOf("gbc", "gambatte", "rb-testrom.gbc"),
            arrayOf("gba", "gpsp", "rb-testrom.gba"),
            arrayOf("gba", "mgba", "rb-testrom.gba"),
        )

        private val A = TestRoms.bit(PadButton.A.retroId)
        private val B = TestRoms.bit(PadButton.B.retroId)
        private val START = TestRoms.bit(PadButton.START.retroId)
    }

    private val system = Systems.byId(systemId)!!
    private val spec = system.cores.first { it.id == coreId }
    private val audio = ShortArray(8192)
    private var core: EmulatorCore? = null
    private var audioFrames = 0L

    @After fun tearDown() { core?.close(); TestRoms.clean() }

    private fun open(): EmulatorCore {
        val result = TestRoms.factory().create(system, spec, TestRoms.copy(rom))
        if (result is CoreResult.Err) fail("$coreId could not start: ${result.error.kind} ${result.error.message}")
        return (result as CoreResult.Ok).value.also { core = it }
    }

    /** Runs [n] frames with [mask] held on port 0, draining audio like the app does; returns how many produced video. */
    private fun EmulatorCore.run(n: Int, mask: Int = 0): Int {
        setInput(0, mask)
        var video = 0
        repeat(n) {
            if (runFrame()) video++
            while (true) { val got = readAudio(audio); if (got <= 0) break; audioFrames += got }
        }
        return video
    }

    private fun picture(): IntArray = NativeHost.snapshot() ?: error("no video frame")

    @Test fun playsTestRom() {
        val c = open()
        Log.i("RetroBoxDeviceTest", "$systemId/$coreId: ${c.description.name} ${c.description.version}, ${c.av}")

        // Video: frames at roughly every emulated frame, a sensible size and more than one colour (checkerboard).
        val video = c.run(120)
        assertTrue("only $video of 120 frames produced video", video >= 100)
        val idle = picture()
        val (w, h) = idle[0] to idle[1]
        val sizes = mapOf("nes" to setOf(256 to 224, 256 to 240), "snes" to setOf(256 to 224, 256 to 239, 512 to 224, 512 to 448),
            "gb" to setOf(160 to 144), "gbc" to setOf(160 to 144), "gba" to setOf(240 to 160))
        assertTrue("unexpected frame size ${w}x$h", (w to h) in sizes.getValue(systemId))
        assertTrue("picture has a single colour", idle.drop(2).distinct().size > 1)

        // Audio: the core delivered samples at about its own rate (no hearing involved).
        val expected = c.av.sampleRate * 120 / c.av.fps
        assertTrue("audio: $audioFrames frames for 120 video frames, expected about ${expected.toInt()}", audioFrames > expected * 0.5)

        // Input: A and B give the test ROM different background colours.
        c.run(20, A); val withA = picture()
        c.run(20, B); val withB = picture()
        assertFalse("A and B produced the same picture: input does not reach the core", withA.contentEquals(withB))

        // Save state: load restores the exact same next frame, even after other things happened in between.
        c.run(5)
        val state = c.saveState()
        assertNotNull("saveState returned null", state)
        c.run(1); val after = picture()
        c.run(30, A)
        assertTrue("loadState failed", c.loadState(state!!))
        c.run(1); val again = picture()
        assertTrue("picture after loading the state differs from the one after saving it", after.contentEquals(again))
    }

    @Test fun keepsBatterySaveAcrossReload() {
        if (systemId == "nes") return   // the NES test ROM is NROM without battery RAM
        val c = open()
        c.run(60)
        val before = c.saveRam()?.copyOf()
        c.run(10, START); c.run(30)   // Start = counter + 1, written to cartridge RAM
        val written = c.saveRam()
        assertNotNull("core exposes no save RAM", written)
        assertFalse("Start did not change save RAM", before.contentEquals(written))
        val saved = written!!.copyOf()
        c.close(); core = null

        val reopened = open()
        assertTrue("loadSaveRam failed", reopened.loadSaveRam(saved))
        reopened.run(60)
        assertTrue("save RAM did not survive a reload", saved.contentEquals(reopened.saveRam()))
    }
}

/** Failure paths that only the real factory and real files exercise. */
class CoreErrorDeviceTest {
    @After fun tearDown() = TestRoms.clean()

    @Test fun missingCoreIsReportedNotCrashed() {
        val nes = Systems.byId("nes")!!
        val r = TestRoms.factory().create(nes, CoreSpec("not_a_core", "Nothing", "-"), TestRoms.copy("rb-testrom.nes"))
        assertEquals(ErrorKind.CORE_MISSING, (r as CoreResult.Err).error.kind)
    }

    @Test fun garbageRomIsRejectedBeforeTheCoreSeesIt() {
        val gba = Systems.byId("gba")!!
        val junk = java.io.File(TestRoms.scratch, "roms/junk.gba").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        val r = TestRoms.factory().create(gba, gba.defaultCore!!, junk)
        assertEquals(ErrorKind.ROM_INVALID, (r as CoreResult.Err).error.kind)
    }

    @Test fun everyPackagedCoreFileIsPresent() {
        val dir = java.io.File(TestRoms.app.applicationInfo.nativeLibraryDir)
        val missing = Systems.all.flatMap { it.cores }.map { "lib${it.id}_libretro_android.so" }.distinct().filterNot { java.io.File(dir, it).exists() }
        assertTrue("missing from this install: $missing", missing.isEmpty())
    }
}
