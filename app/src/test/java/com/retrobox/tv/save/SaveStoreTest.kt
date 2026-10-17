package com.retrobox.tv.save

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val store by lazy { SaveStore(tmp.root) }
    private fun meta(ts: Long = 1L) = StateMeta("fceumm", "1", "game.nes", ts, 3)

    @Test fun emptySlotsReportNoMetadata() {
        val s = store.slots("nes", "game.nes")
        assertEquals(4, s.size)
        assertTrue(s.all { it.meta == null && !it.corrupt })
        assertNull(store.read("nes", "game.nes", 1))
    }

    @Test fun writtenSlotIsListedAndReadable() {
        store.write("nes", "game.nes", 2, meta(42), byteArrayOf(9, 9, 9))
        val slot = store.slots("nes", "game.nes")[1]
        assertEquals(42L, slot.meta!!.timestampMs)
        assertArrayEquals(byteArrayOf(9, 9, 9), (store.read("nes", "game.nes", 2) as StateDecode.Ok).payload)
    }

    @Test fun overwritingKeepsThePreviousStateAsBackup() {
        store.write("nes", "game.nes", 1, meta(1), byteArrayOf(1))
        store.write("nes", "game.nes", 1, meta(2), byteArrayOf(2))
        val bak = java.io.File(store.stateFile("nes", "game.nes", 1).path + ".bak")
        assertTrue(bak.exists())
        assertEquals(1L, (StateFile.decode(bak.readBytes()) as StateDecode.Ok).meta.timestampMs)
    }

    @Test fun corruptStateFileIsFlaggedNotLoaded() {
        val f = store.stateFile("nes", "game.nes", 3); f.parentFile!!.mkdirs(); f.writeBytes(ByteArray(64) { 7 })
        assertTrue(store.slots("nes", "game.nes")[2].corrupt)
        assertTrue(store.read("nes", "game.nes", 3) is StateDecode.Corrupt)
    }

    @Test fun statesAreSeparatedPerSystemAndRom() {
        store.write("nes", "a.nes", 1, meta(), byteArrayOf(1))
        assertNull(store.read("nes", "b.nes", 1))
        assertNull(store.read("gb", "a.gb", 1))
    }

    @Test fun sramRoundTripAndBackup() {
        assertNull(store.readSram("nes", "game.nes"))
        store.writeSram("nes", "game.nes", byteArrayOf(1, 2))
        store.writeSram("nes", "game.nes", byteArrayOf(3, 4))
        assertArrayEquals(byteArrayOf(3, 4), store.readSram("nes", "game.nes"))
        assertTrue(java.io.File(store.sramFile("nes", "game.nes").path + ".bak").exists())
        assertFalse(java.io.File(store.sramFile("nes", "game.nes").path + ".tmp").exists())
    }

    @Test fun hostileRomNamesCannotEscapeTheSaveDirectory() {
        val f = store.stateFile("nes", "../../evil.nes", 1)
        assertTrue(f.canonicalPath.startsWith(tmp.root.canonicalPath))
    }

    @Test fun quickResumeSessionIsSlotZeroAndNeverShownAsASaveSlot() {
        store.write("snes", "game.sfc", 0, meta(5), byteArrayOf(1, 2, 3))      // automatic session written on quit
        assertTrue(store.stateFile("snes", "game.sfc", 0).exists())
        assertTrue("manual slots 1..4 stay empty", store.slots("snes", "game.sfc").all { it.meta == null })
        assertEquals(listOf(1, 2, 3, 4), store.slots("snes", "game.sfc").map { it.slot })
        val d = store.read("snes", "game.sfc", 0) as StateDecode.Ok
        assertArrayEquals(byteArrayOf(1, 2, 3), d.payload)
        store.write("snes", "game.sfc", 0, meta(6), byteArrayOf(4))              // the next quit replaces it
        assertArrayEquals(byteArrayOf(4), (store.read("snes", "game.sfc", 0) as StateDecode.Ok).payload)
    }
}
