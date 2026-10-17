package com.retrobox.tv.library

import com.retrobox.tv.profile.ProfileSet
import com.retrobox.tv.profile.ProfileStore
import com.retrobox.tv.save.SaveStore
import com.retrobox.tv.util.SafeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Regression tests for the whole-app review (2026-10-17). */
class BugFixesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun nonAsciiNamesNoLongerShareSaves() {
        val store = SaveStore(tmp.newFolder("saves"))
        val mario = store.sramFile("nes", "スーパーマリオ.nes"); val kong = store.sramFile("nes", "ドンキーコング.nes")
        assertNotEquals(mario, kong)
        // ASCII names keep exactly the names earlier versions used, so existing saves still match.
        assertEquals("Zoop _USA_.srm", store.sramFile("snes", "Zoop (USA).sfc").name)
        assertEquals("Zoop _USA_", SafeName.of("Zoop (USA)"))
        val t = ThumbnailStore(tmp.newFolder("thumbs"))
        assertNotEquals(t.fileFor("nes", "スーパーマリオ.nes"), t.fileFor("nes", "ドンキーコング.nes"))
    }

    @Test fun historyFollowsPlayOrderEvenWhenTheClockGoesBack() {
        val h = FilePlayHistory(File(tmp.root, "history.tsv"))
        h.recordStart("nes", "/r/a.nes", "a.nes", "A", "fceumm", nowMs = 2_000_000)
        h.recordStart("nes", "/r/b.nes", "b.nes", "B", "fceumm", nowMs = 1_000_000)   // TV booted with a clock in the past
        assertEquals(listOf("B", "A"), h.recent(10).map { it.title })
        // Full history: the newest entry is kept even with the oldest timestamp.
        repeat(PlayHistory.MAX_ENTRIES) { h.recordStart("gb", "/r/$it.gb", "$it.gb", "G$it", "gambatte", nowMs = 5_000_000L + it) }
        h.recordStart("nes", "/r/late.nes", "late.nes", "Late", "fceumm", nowMs = 1)
        assertEquals("Late", h.recent(1).single().title)
        assertEquals(PlayHistory.MAX_ENTRIES, h.recent(1000).size)
    }

    @Test fun aDamagedProfilesFileIsKeptAndReported() {
        val f = File(tmp.root, "profiles.json").apply { writeText("{\"app\":\"retrobox-tv\",\"glob") }
        val store = ProfileStore(f)
        assertEquals(ProfileSet(), store.load())
        assertNotNull(store.lastProblem)
        assertTrue(File(tmp.root, "profiles.json.bad").readText().startsWith("{\"app\""))
    }
}
