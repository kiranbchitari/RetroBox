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

/**
 * RetroBox Originals (our own Snake core) on this device: it starts at the picture size the screen height asks for,
 * draws, makes sound, reacts to the D-pad, and its save states and best scores round-trip. Real native core, real GPU-free
 * software frames; run it on the TV to learn how fast the frames really are there (see the logged average).
 */
class OriginalsDeviceTest {
    private val system = Systems.byId(Originals.SYSTEM_ID)!!
    private val audio = ShortArray(8192)
    private var core: EmulatorCore? = null
    private var audioFrames = 0L

    @After fun tearDown() { core?.close(); TestRoms.clean() }

    private fun open(screenHeightPx: Int): EmulatorCore {
        val cart = File(TestRoms.scratch, "roms/Snake.rbo").also { it.parentFile!!.mkdirs(); it.writeBytes(Originals.cartridgeBytes("snake")) }
        val factory = LibretroCore.Factory(NativeHost, File(TestRoms.app.applicationInfo.nativeLibraryDir), File(TestRoms.scratch, "system"), File(TestRoms.scratch, "saves"), screenHeightPx)
        val result = factory.create(system, system.defaultCore!!, cart)
        if (result is CoreResult.Err) fail("Originals could not start: ${result.error.kind} ${result.error.message}")
        return (result as CoreResult.Ok).value.also { core = it }
    }

    private fun EmulatorCore.run(n: Int, mask: Int = 0): Int {
        setInput(0, mask)
        var video = 0
        repeat(n) {
            if (runFrame()) video++
            while (true) { val got = readAudio(audio); if (got <= 0) break; audioFrames += got }
        }
        return video
    }

    /** A short press: the button is down for a few frames, then up (menus and turns act on the press itself). */
    private fun EmulatorCore.tap(button: PadButton) { run(3, TestRoms.bit(button.retroId)); run(3) }

    private fun picture(): IntArray = NativeHost.snapshot() ?: error("no video frame")

    @Test fun picksTheResolutionForTheScreenAndDraws() {
        for ((height, size) in listOf(540 to (960 to 540), 720 to (1280 to 720), 1080 to (1920 to 1080), 2160 to (1920 to 1080))) {
            val c = open(height)
            assertEquals("screen $height px", size, c.av.width to c.av.height)
            assertEquals(60.0, c.av.fps, 0.01)
            assertEquals(48000, c.av.sampleRate)
            assertTrue("no video", c.run(60) >= 50)
            val p = picture()
            assertEquals("frame size at $height px", size, p[0] to p[1])
            assertTrue("picture has too few colours", p.drop(2).distinct().size > 50)
            c.close(); core = null
        }
    }

    @Test fun playsReactsToInputAndMakesSound() {
        val c = open(720)
        c.run(30)
        val title = picture()
        c.tap(PadButton.A)                                   // PLAY
        c.run(45)
        val playing = picture()
        assertFalse("A did not start the game", title.contentEquals(playing))
        assertTrue("no sound for the start jingle", audioFrames > 48000 * 0.2)

        // Turning changes where the snake goes: two runs from the same state, one with UP, one without.
        val state = c.saveState()!!
        c.run(30); val straight = picture()
        assertTrue(c.loadState(state))
        c.tap(PadButton.UP); c.run(24); val turned = picture()
        assertFalse("UP made no difference", straight.contentEquals(turned))
    }

    @Test fun saveStateRestoresTheExactNextFrame() {
        val c = open(720)
        c.run(10); c.tap(PadButton.A); c.run(40)
        val state = c.saveState()
        assertNotNull("saveState returned null", state)
        c.run(1); val after = picture()
        c.run(60)
        assertTrue("loadState failed", c.loadState(state!!))
        c.run(1)
        assertTrue("the picture after loading the state differs", after.contentEquals(picture()))
        assertFalse("a corrupt state must be refused", c.loadState(ByteArray(state.size)))
    }

    @Test fun keepsTheMenuChoiceInSaveRamAcrossReload() {
        val c = open(720)
        c.run(10)
        val before = c.saveRam()!!.copyOf()
        c.tap(PadButton.UP); c.tap(PadButton.RIGHT)            // SPEED row, one step: Normal -> Hard
        val after = c.saveRam()!!.copyOf()
        assertFalse("changing the speed did not reach save RAM", before.contentEquals(after))
        c.close(); core = null

        val reopened = open(720)
        assertTrue(reopened.loadSaveRam(after))
        reopened.run(10)
        assertTrue("save RAM did not survive a reload", after.contentEquals(reopened.saveRam()))
    }

    @Test fun framesAreFastEnoughAtEveryResolution() {
        for (height in listOf(720, 1080)) {
            val c = open(height)
            c.run(20); c.tap(PadButton.A); c.run(60)
            val n = 240
            val t0 = System.nanoTime()
            c.run(n)
            val msPerFrame = (System.nanoTime() - t0) / 1e6 / n
            android.util.Log.i("RetroBoxDeviceTest", "originals ${c.av.width}x${c.av.height}: %.2f ms per frame (budget 16.7)".format(msPerFrame))
            assertTrue("$height px: $msPerFrame ms per frame is over the 16.7 ms budget", msPerFrame < 16.7)
            c.close(); core = null
        }
    }
}
