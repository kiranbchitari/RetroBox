package com.retrobox.tv.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun history() = FilePlayHistory(File(tmp.root, "sub/history.tsv"))

    @Test fun emptyHistoryHasNoGames() {
        assertTrue(history().recent().isEmpty())
        assertNull(history().entryFor("/x.nes"))
    }

    @Test fun mostRecentlyPlayedComesFirstAndCountsAccumulate() {
        val h = history()
        h.recordStart("nes", "/roms/a.nes", "/roms/a.nes".substringAfterLast('/'), "A", "fceumm", nowMs = 1_000)
        h.recordStart("snes", "/roms/b.sfc", "/roms/b.sfc".substringAfterLast('/'), "B", "snes9x2005", nowMs = 2_000)
        h.recordStart("nes", "/roms/a.nes", "/roms/a.nes".substringAfterLast('/'), "A", "fceumm", nowMs = 3_000)
        val r = h.recent()
        assertEquals(listOf("/roms/a.nes", "/roms/b.sfc"), r.map { it.romPath })
        assertEquals(2, r[0].playCount)
        assertEquals(1, r[1].playCount)
        assertEquals(3_000L, r[0].lastPlayedMs)
    }

    @Test fun playTimeIsAddedToTheRightGame() {
        val h = history()
        h.recordStart("gb", "/roms/g.gb", "/roms/g.gb".substringAfterLast('/'), "G", "gambatte", nowMs = 1)
        h.addPlayTime("/roms/g.gb", 60_000)
        h.addPlayTime("/roms/g.gb", 30_000)
        h.addPlayTime("/roms/unknown.gb", 99_000)   // not in history: ignored
        h.addPlayTime("/roms/g.gb", -5)             // nonsense: ignored
        assertEquals(90_000L, h.entryFor("/roms/g.gb")!!.totalPlayMs)
        assertEquals(1, h.recent().size)
    }

    @Test fun replayKeepsEarlierPlayTime() {
        val h = history()
        h.recordStart("gb", "/g.gb", "/g.gb".substringAfterLast('/'), "G", "gambatte", 1)
        h.addPlayTime("/g.gb", 5_000)
        h.recordStart("gb", "/g.gb", "/g.gb".substringAfterLast('/'), "G", "gambatte", 2)
        assertEquals(5_000L, h.entryFor("/g.gb")!!.totalPlayMs)
    }

    @Test fun survivesNewInstanceAndAwkwardTitles() {
        val h = history()
        h.recordStart("nes", "/roms/we\tird\\name.nes", "/roms/we\tird\\name.nes".substringAfterLast('/'), "Tab\there\nand newline \\ backslash", "fceumm", 7)
        val e = history().entryFor("/roms/we\tird\\name.nes")!!
        assertEquals("Tab\there\nand newline \\ backslash", e.title)
    }

    @Test fun listIsCappedAtTheMaximum() {
        val h = history()
        for (i in 1..(PlayHistory.MAX_ENTRIES + 10)) h.recordStart("nes", "/r/$i.nes", "/r/$i.nes".substringAfterLast('/'), "G$i", "fceumm", i.toLong())
        val r = h.recent(1000)
        assertEquals(PlayHistory.MAX_ENTRIES, r.size)
        assertEquals("/r/${PlayHistory.MAX_ENTRIES + 10}.nes", r.first().romPath)   // newest kept
        assertFalse(r.any { it.romPath == "/r/1.nes" })                               // oldest dropped
    }

    @Test fun damagedLinesAreSkippedNotFatal() {
        val f = File(tmp.root, "history.tsv")
        f.writeText("garbage line\nnes\t/a.nes\tA\tfceumm\tnot-a-number\t1\t0\nsnes\t/b.sfc\tB\tsnes9x2005\t5\t2\t7000\n")
        val r = FilePlayHistory(f).recent()
        assertEquals(1, r.size)
        assertEquals("/b.sfc", r[0].romPath)
        assertEquals(7000L, r[0].totalPlayMs)
    }

    @Test fun durationAndAgoWording() {
        assertEquals("under a minute", formatPlayTime(30_000))
        assertEquals("5 min", formatPlayTime(5 * 60_000L))
        assertEquals("2 h 10 min", formatPlayTime(130 * 60_000L))
        val now = 10_000_000_000L
        assertEquals("just now", formatAgo(now - 10_000, now))
        assertEquals("3 h ago", formatAgo(now - 3 * 3_600_000L, now))
        assertEquals("yesterday", formatAgo(now - 30 * 3_600_000L, now))
        assertEquals("5 days ago", formatAgo(now - 5 * 86_400_000L, now))
        assertEquals("just now", formatAgo(now + 5_000, now))   // clock went backwards
    }
}
