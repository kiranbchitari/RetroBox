package com.retrobox.tv.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun folder(path: String) = LibraryFolder(1, FolderKind.APP, path, "t", true, 0, 0)
    private fun touch(rel: String, size: Int = 100): File = File(tmp.root, rel).also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(size)) }
    private fun game(title: String, system: String = "nes", fav: Boolean = false, path: String = "/r/$title.nes") =
        Game(0, system, path, "$title.nes", title, 1, 1, fav, false)

    // ---- scanner ----

    @Test fun scanFindsRomsRecursivelyAndIgnoresOtherFiles() {
        touch("a.nes"); touch("sub/deep/b.SFC"); touch("sub/c.gba"); touch("notes.txt"); touch("sub/game.zip"); touch(".hidden/d.nes"); touch("empty.nes", 0)
        val out = LibraryScanner { FileWalker() }.scan(folder(tmp.root.path)) as ScanOutcome.Found
        assertEquals(listOf("a.nes", "b.SFC", "c.gba"), out.roms.map { it.fileName }.sorted().let { l -> l.sortedBy { it.lowercase() } })
        assertEquals(setOf("nes", "snes", "gba"), out.roms.map { it.systemId }.toSet())
        assertEquals("Deep ROM", titleFromFileName("deep_ROM.nes"))
    }

    @Test fun scanOfMissingFolderIsReportedAsUnavailable() {
        val out = LibraryScanner { FileWalker() }.scan(folder(File(tmp.root, "gone").path))
        assertTrue(out is ScanOutcome.Unavailable)
    }

    @Test fun scanCanBeCancelledAndReportsProgress() {
        repeat(60) { touch("g$it.nes") }
        var progress = 0
        val done = LibraryScanner { FileWalker() }.scan(folder(tmp.root.path), onProgress = { _, _ -> progress++ })
        assertTrue(done is ScanOutcome.Found)
        assertTrue("progress was reported", progress >= 2)
        var calls = 0
        val cancelled = LibraryScanner { FileWalker() }.scan(folder(tmp.root.path), shouldStop = { ++calls > 5 })
        assertEquals(ScanOutcome.Cancelled, cancelled)
    }

    @Test fun permissionLossDuringScanBecomesUnavailable() {
        val walker = object : RomWalker {
            override fun walk(root: String, onFile: (String, String, Long) -> Unit, shouldStop: () -> Boolean) { throw SecurityException("revoked") }
        }
        assertTrue(LibraryScanner { walker }.scan(folder("content://x")) is ScanOutcome.Unavailable)
    }

    @Test fun deepFoldersStopAtTheDepthLimit() {
        var p = ""
        repeat(FileWalker.MAX_DEPTH + 3) { p += "d$it/" }
        touch("${p}too-deep.nes")
        val out = LibraryScanner { FileWalker() }.scan(folder(tmp.root.path)) as ScanOutcome.Found
        assertTrue(out.roms.isEmpty())
    }

    // ---- search / sort / filter ----

    private val library = listOf(
        game("Super Mario Bros 10"), game("Super Mario Bros 2"), game("The Legend of Zelda"), game("Zelda II", fav = true),
        game("Tetris", system = "gb", path = "/r/Tetris.gb"), game("Metroid Fusion", system = "gba", fav = true, path = "/r/mf.gba"),
    )

    @Test fun searchMatchesAllWordsInAnyOrderCaseInsensitively() {
        assertEquals(listOf("Super Mario Bros 2", "Super Mario Bros 10"), LibraryFilter.apply(library, LibraryQuery(text = "mario SUPER")).map { it.title })
        assertEquals(listOf("The Legend of Zelda", "Zelda II"), LibraryFilter.apply(library, LibraryQuery(text = "zelda")).map { it.title })
        assertTrue(LibraryFilter.apply(library, LibraryQuery(text = "zelda tetris")).isEmpty())
        assertEquals(6, LibraryFilter.apply(library, LibraryQuery(text = "   ")).size)
    }

    @Test fun titleSortIsNaturalAndIgnoresLeadingThe() {
        val t = LibraryFilter.apply(library, LibraryQuery()).map { it.title }
        // "The Legend of Zelda" sorts under L
        assertEquals(listOf("The Legend of Zelda", "Metroid Fusion", "Super Mario Bros 2", "Super Mario Bros 10", "Tetris", "Zelda II"), t)
    }

    @Test fun filtersBySystemAndFavourites() {
        assertEquals(listOf("Tetris"), LibraryFilter.apply(library, LibraryQuery(systemId = "gb")).map { it.title })
        assertEquals(listOf("Metroid Fusion", "Zelda II"), LibraryFilter.apply(library, LibraryQuery(favoritesOnly = true)).map { it.title })
        assertTrue(LibraryFilter.apply(library, LibraryQuery(systemId = "snes")).isEmpty())
    }

    @Test fun recentSortPutsNeverPlayedLastAlphabetically() {
        val played = mapOf("/r/Tetris.gb" to 500L, "/r/Zelda II.nes" to 900L)
        val t = LibraryFilter.apply(library, LibraryQuery(sort = SortMode.RECENT)) { played[it.path] ?: 0L }.map { it.title }
        assertEquals(listOf("Zelda II", "Tetris", "The Legend of Zelda", "Metroid Fusion"), t.take(4))
    }

    @Test fun systemSortGroupsInRegistryOrder() {
        val t = LibraryFilter.apply(library, LibraryQuery(sort = SortMode.SYSTEM)).map { it.systemId }
        assertEquals(listOf("nes", "nes", "nes", "nes", "gb", "gba"), t)
    }

    @Test fun tvFrameworkStubIsNotARealFolderPicker() {
        assertTrue(SafWalker.isPlaceholder("com.android.tv.frameworkpackagestubs", "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub"))
        assertTrue(!SafWalker.isPlaceholder("com.android.documentsui", "com.android.documentsui.picker.PickActivity"))
        assertTrue(!SafWalker.isPlaceholder("com.google.android.documentsui", "com.android.documentsui.picker.PickActivity"))
    }
}
