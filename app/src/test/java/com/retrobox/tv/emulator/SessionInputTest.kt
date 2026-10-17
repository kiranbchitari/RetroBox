package com.retrobox.tv.emulator

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** What [EmulationSession.setInput] passes to the core (no emulation thread is started). */
class SessionInputTest {
    private class RecordingCore : EmulatorCore {
        val sent = mutableListOf<Pair<Int, Int>>()
        override val description = CoreDescription("fake", "1", "nes")
        override val av = AvInfo(256, 224, 4.0 / 3, 60.0, 48000)
        override val system = Systems.byId("nes")!!
        override val romFile = File("x.nes")
        override fun runFrame() = true
        override fun reset() {}
        override fun readAudio(out: ShortArray) = 0
        override fun setInput(port: Int, mask: Int, lx: Int, ly: Int, rx: Int, ry: Int) { sent += port to mask }
        override fun saveState(): ByteArray? = null
        override fun loadState(data: ByteArray) = false
        override fun saveRam(): ByteArray? = null
        override fun loadSaveRam(data: ByteArray) = false
        override fun drainMessages(): String? = null
        override fun close() {}
    }

    private val a = 1 shl PadButton.A.retroId
    private val up = 1 shl PadButton.UP.retroId

    @Test fun repeatsAreDroppedAndHeldTurboButtonsAreLeftToThePulse() {
        val core = RecordingCore()
        val s = EmulationSession(core, null, onFrame = {})
        s.turboMask = a
        s.setInput(0, a)              // new press: goes through at once
        s.setInput(0, a)              // key repeat / stick jitter: nothing re-sent (the host latch would re-press A)
        s.setInput(0, a or up)        // a direction while A is held: A is not re-pressed, the pulse owns it
        s.setInput(0, 0)
        assertEquals(listOf(0 to a, 0 to up, 0 to 0), core.sent)
    }

    @Test fun withoutTurboEveryChangeGoesThrough() {
        val core = RecordingCore()
        val s = EmulationSession(core, null, onFrame = {})
        s.setInput(1, a); s.setInput(1, a); s.setInput(1, a or up)
        assertEquals(listOf(1 to a, 1 to (a or up)), core.sent)
    }
}
