package com.retrobox.tv.emulator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainStageTest {
    private fun stereo(frames: Int, value: Short) = ShortArray(frames * 2) { value }

    @Test fun fullVolumeWithNoFadeLeavesSamplesUntouched() {
        val s = GainStage(0)
        val b = shortArrayOf(100, -100, 32767, -32768)
        s.process(b, 2)
        assertArrayEquals(shortArrayOf(100, -100, 32767, -32768), b)
    }

    @Test fun volumeScalesBothChannelsAndClamps() {
        val s = GainStage(0).also { it.gain = 0.5f }
        val b = shortArrayOf(1000, -1000, 32767, -32768)
        s.process(b, 2)
        assertArrayEquals(shortArrayOf(500, -500, 16383, -16384), b)
        val loud = GainStage(0).also { it.gain = 2f }
        val c = shortArrayOf(30000, -30000)
        loud.process(c, 1)
        assertArrayEquals(shortArrayOf(32767, -32768), c)
    }

    @Test fun zeroGainIsSilence() {
        val s = GainStage(0).also { it.gain = 0f }
        val b = stereo(10, 1234)
        s.process(b, 10)
        assertTrue(b.all { it == 0.toShort() })
    }

    @Test fun onlyTheFramesGivenAreTouched() {
        val s = GainStage(0).also { it.gain = 0f }
        val b = stereo(4, 77)
        s.process(b, 2)
        assertEquals(listOf<Short>(0, 0, 0, 0, 77, 77, 77, 77), b.toList())
    }

    @Test fun fadeInStartsSilentAndRisesMonotonicallyToFullLevel() {
        val s = GainStage(100)
        s.restartFade()
        val b = stereo(200, 10000)
        s.process(b, 200)
        assertEquals(0, b[0].toInt())                       // first frame silent: no click
        for (i in 1 until 100) assertTrue("frame $i not rising", b[2 * i] >= b[2 * (i - 1)])
        assertEquals(10000, b[2 * 150].toInt())              // after the fade, full level and untouched
        assertTrue(b[2 * 50] in 4000..6000)                  // roughly halfway at the middle of the ramp
    }

    @Test fun fadeContinuesAcrossBlocksAndCanBeRestarted() {
        val s = GainStage(100)
        s.restartFade()
        val a = stereo(50, 10000); val b = stereo(50, 10000)
        s.process(a, 50); s.process(b, 50)
        assertTrue(b[0] > a[2 * 49] - 1)                     // second block continues where the first stopped
        val c = stereo(10, 10000)
        s.process(c, 10)
        assertEquals(10000, c[0].toInt())                    // fade finished
        s.restartFade()
        val d = stereo(10, 10000)
        s.process(d, 10)
        assertEquals(0, d[0].toInt())                        // and starts again after a resume
    }

    @Test fun fadeCombinesWithVolume() {
        val s = GainStage(10).also { it.gain = 0.5f }
        s.restartFade()
        val b = stereo(20, 10000)
        s.process(b, 20)
        assertEquals(5000, b[2 * 15].toInt())
    }
}
