package com.retrobox.tv

import com.retrobox.tv.emulator.CoreResult
import com.retrobox.tv.emulator.EmulatorCore
import com.retrobox.tv.emulator.LibretroCore
import com.retrobox.tv.emulator.NativeHost
import com.retrobox.tv.emulator.Originals
import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.emulator.Systems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Blizzard Buddies (the snowball platformer in RetroBox Originals) on this device: size, drawing, two pads, save states, speed. */
class BlizzardDeviceTest {
    private val system = Systems.byId(Originals.SYSTEM_ID)!!
    private val audio = ShortArray(8192)
    private var core: EmulatorCore? = null
    private var audioFrames = 0L

    @After fun tearDown() { core?.close(); TestRoms.clean() }

    private fun open(screenHeightPx: Int): EmulatorCore {
        val cart = File(TestRoms.scratch, "roms/Blizzard Buddies.rbo").also { it.parentFile!!.mkdirs(); it.writeBytes(Originals.cartridgeBytes("blizzard")) }
        val factory = LibretroCore.Factory(NativeHost, File(TestRoms.app.applicationInfo.nativeLibraryDir), File(TestRoms.scratch, "system"), File(TestRoms.scratch, "saves"), screenHeightPx)
        val result = factory.create(system, system.defaultCore!!, cart)
        if (result is CoreResult.Err) fail("Blizzard Buddies could not start: ${result.error.kind} ${result.error.message}")
        return (result as CoreResult.Ok).value.also { core = it }
    }

    private fun EmulatorCore.run(n: Int, mask: Int = 0, port: Int = 0): Int {
        setInput(port, mask)
        var video = 0
        repeat(n) {
            if (runFrame()) video++
            while (true) { val got = readAudio(audio); if (got <= 0) break; audioFrames += got }
        }
        return video
    }

    private fun EmulatorCore.tap(button: PadButton, port: Int = 0) { run(3, TestRoms.bit(button.retroId), port); run(3, 0, port) }
    private fun picture(): IntArray = NativeHost.snapshot() ?: error("no video frame")

    @Test fun fillsTheScreenAtEachResolution() {
        for ((height, size) in listOf(540 to (960 to 540), 720 to (1280 to 720), 1080 to (1920 to 1080))) {
            val c = open(height)
            assertEquals(size, c.av.width to c.av.height)
            assertTrue("no video", c.run(60) >= 50)
            val p = picture()
            assertEquals(size, p[0] to p[1])
            assertTrue("picture has too few colours", p.drop(2).distinct().size > 100)
            c.close(); core = null
        }
    }

    @Test fun startsAGameMovesAndMakesSound() {
        val c = open(720)
        c.run(30); val title = picture()
        c.tap(PadButton.A); c.run(150)
        val level = picture()
        assertFalse("A did not start the game", title.contentEquals(level))
        assertTrue("no sound at all", audioFrames > 48000)
        val before = picture()
        c.run(40, TestRoms.bit(PadButton.RIGHT.retroId) or TestRoms.bit(PadButton.B.retroId))
        assertFalse("running and jumping changed nothing", before.contentEquals(picture()))
    }

    @Test fun aSecondPadStartsACoOpGame() {
        val c = open(720)
        c.run(30); c.tap(PadButton.A); c.run(150)
        val solo = picture()
        c.close(); core = null
        val c2 = open(720)
        c2.run(30); c2.tap(PadButton.A, port = 1); c2.run(150)
        assertFalse("pad 2 did not add a second player", solo.contentEquals(picture()))
    }

    @Test fun saveStateRestoresTheExactNextFrame() {
        val c = open(720)
        c.run(10); c.tap(PadButton.A); c.run(200, TestRoms.bit(PadButton.A.retroId))      // throwing snow while the monsters drop in
        val state = c.saveState()
        assertNotNull(state)
        c.run(1); val after = picture()
        c.run(120, TestRoms.bit(PadButton.LEFT.retroId))
        assertTrue(c.loadState(state!!))
        c.run(1)
        assertTrue("the picture after loading the state differs", after.contentEquals(picture()))
        assertFalse("a corrupt state must be refused", c.loadState(ByteArray(state.size)))
        assertEquals(64, c.saveRam()!!.size)
    }

    @Test fun difficultyAndBuddyChoicesAreKeptInSaveRam() {
        val c = open(720)
        c.run(30)
        val before = c.saveRam()!!.copyOf()
        c.tap(PadButton.RIGHT)                                     // difficulty: Normal -> Hard
        c.tap(PadButton.DOWN); c.tap(PadButton.RIGHT)              // buddy: Blue -> Pink
        val after = c.saveRam()!!.copyOf()
        assertFalse("menu choices did not reach save RAM", before.contentEquals(after))
        c.close(); core = null
        val again = open(720)
        assertTrue(again.loadSaveRam(after))
        again.run(30)
        assertTrue("choices did not survive a reload", after.contentEquals(again.saveRam()))
    }

    @Test fun framesAreFastEnoughAtEveryResolution() {
        for (height in listOf(720, 1080)) {
            val c = open(height)
            c.run(20); c.tap(PadButton.A); c.run(200)
            val n = 300
            val t0 = System.nanoTime()
            c.run(n, TestRoms.bit(PadButton.RIGHT.retroId) or TestRoms.bit(PadButton.A.retroId))
            val ms = (System.nanoTime() - t0) / 1e6 / n
            android.util.Log.i("RetroBoxDeviceTest", "blizzard ${c.av.width}x${c.av.height}: %.2f ms per frame (budget 16.7)".format(ms))
            assertTrue("$height px: $ms ms per frame is over the 16.7 ms budget", ms < 16.7)
            c.close(); core = null
        }
    }
}
