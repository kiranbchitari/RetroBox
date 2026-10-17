package com.retrobox.tv

import android.util.Log
import com.retrobox.tv.emulator.CoreResult
import com.retrobox.tv.emulator.EmulationSession
import com.retrobox.tv.emulator.EmulatorCore
import com.retrobox.tv.emulator.FastForward
import com.retrobox.tv.emulator.NativeHost
import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.emulator.Systems
import com.retrobox.tv.emulator.Turbo
import com.retrobox.tv.library.FileWalker
import com.retrobox.tv.library.FolderKind
import com.retrobox.tv.library.LibraryFolder
import com.retrobox.tv.library.LibraryScanner
import com.retrobox.tv.library.RomAccess
import com.retrobox.tv.library.ScanOutcome
import com.retrobox.tv.library.ZipRom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fast-forward and turbo through the real emulation thread and core; zipped ROMs from scan to a running core. */
class SpeedZipDeviceTest {
    private var session: EmulationSession? = null
    private var core: EmulatorCore? = null

    @After fun tearDown() {
        session?.stop() ?: core?.close()
        TestRoms.clean()
    }

    private fun startNes(): EmulationSession {
        val nes = Systems.byId("nes")!!
        val c = (TestRoms.factory().create(nes, nes.defaultCore!!, TestRoms.copy("rb-testrom.nes")) as CoreResult.Ok).value
        core = c
        return EmulationSession(c, null, onFrame = {}).also { session = it; it.start(); it.resume() }
    }

    /** Frames per second the core really ran at, from its own frame counter, after the new speed settled. */
    private fun framesPerSecond(): Double {
        Thread.sleep(400)
        val f0 = NativeHost.stats()[0]; val t0 = System.nanoTime()
        Thread.sleep(1500)
        return (NativeHost.stats()[0] - f0) * 1e9 / (System.nanoTime() - t0)
    }

    @Test fun fastForwardRunsFasterAndBack() {
        val s = startNes()
        val normal = framesPerSecond()
        s.speed = 3
        val triple = framesPerSecond()
        s.speed = FastForward.MAX
        val max = framesPerSecond()
        s.speed = FastForward.NORMAL
        val back = framesPerSecond()
        Log.i("RetroBoxDeviceTest", "fps normal=$normal 3x=$triple max=$max back=$back")
        // Only coarse checks: inside `am instrument` on the TV emulator Thread.sleep oversleeps by tens of ms at random, so
        // paced rates are noisy (normal 20-60 fps, 3x 70-180). The app itself measured 59.8 fps normal and 180 fps at 3x
        // through the UI (TEST_MATRIX.md). FastForward.frameNs is unit-tested exactly.
        assertTrue("normal $normal is faster than the core's 60 fps", normal < 70.0)
        assertTrue("3x gave $triple, normal $normal", triple > normal * 1.3)
        assertTrue("max gave $max, 3x $triple", max > triple * 3)
        assertTrue("back to normal gave $back", back < 70.0)
    }

    /** The NES test ROM's background is red while A is held and black otherwise; turbo makes it alternate. */
    @Test fun turboPulsesAHeldButtonAndOnlyWithTurboOn() {
        val s = startNes()
        val a = TestRoms.bit(PadButton.A.retroId)
        // Two neighbouring checkerboard cells: one of them always shows the backdrop colour.
        fun backgrounds(): Set<Pair<Int, Int>> {
            val seen = HashSet<Pair<Int, Int>>()
            repeat(40) { NativeHost.snapshot()?.let { px -> seen += px[2 + 20 * px[0] + 4] to px[2 + 20 * px[0] + 12] }; Thread.sleep(16) }
            return seen
        }
        Thread.sleep(500)                     // past the ROM's start-up frames
        s.setInput(0, a); Thread.sleep(200)
        val plain = backgrounds()
        s.turboMask = Turbo.mask(setOf(PadButton.A)); Thread.sleep(200)
        val turbo = backgrounds()
        s.setInput(0, 0)
        Log.i("RetroBoxDeviceTest", "backdrop colours: held=$plain turbo=$turbo")
        assertEquals("holding A without turbo should keep one colour", 1, plain.size)
        assertTrue("turbo should alternate the colour, saw $turbo", turbo.size >= 2)
    }

    @Test fun zippedRomIsFoundUnpackedAndPlayed() {
        val dir = File(TestRoms.scratch, "zips").apply { mkdirs() }
        val rom = TestRoms.copy("rb-testrom.gba")
        ZipOutputStream(File(dir, "RB Zip Test.zip").outputStream()).use { z ->
            z.putNextEntry(ZipEntry("readme.txt")); z.write("CC0 test".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("game/rb-zip-test.gba")); z.write(rom.readBytes()); z.closeEntry()
        }
        val found = (LibraryScanner { FileWalker() }.scan(LibraryFolder(1, FolderKind.APP, dir.path, "zips", true, 0, 0)) as ScanOutcome.Found).roms.single()
        assertEquals("gba", found.systemId)
        assertEquals("rb-zip-test.gba", found.fileName)

        val file = RomAccess.materialize(TestRoms.app, found.path, found.fileName)
        assertTrue(file.readBytes().contentEquals(rom.readBytes()))
        val gba = Systems.byId("gba")!!
        val c = (TestRoms.factory().create(gba, gba.defaultCore!!, file) as CoreResult.Ok).value
        core = c
        var video = 0
        repeat(60) { if (c.runFrame()) video++ }
        assertTrue("only $video frames", video > 50)
        assertTrue(ZipRom.split(found.path)!!.first.endsWith("RB Zip Test.zip"))
    }
}
