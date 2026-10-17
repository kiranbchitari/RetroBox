package com.retrobox.tv

import com.retrobox.tv.library.FileWalker
import com.retrobox.tv.library.FolderKind
import com.retrobox.tv.library.LibraryScanner
import com.retrobox.tv.library.ScanOutcome
import com.retrobox.tv.library.SqlLibraryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The scanner on this device's file system feeding the real SQLite store (the JVM tests cannot run SQLite). Uses its own database file. */
class LibraryDeviceTest {
    private val dbName = "device-test-library.db"
    private val store = SqlLibraryStore(TestRoms.app, dbName)
    private val root = File(TestRoms.scratch, "library").apply { mkdirs() }
    private val scanner = LibraryScanner { FileWalker() }

    @After fun tearDown() { store.close(); TestRoms.app.deleteDatabase(dbName); TestRoms.clean() }

    private fun put(rel: String, bytes: Int = 64) = File(root, rel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes) { 1 }) }

    private fun scanInto(folderId: Long, now: Long): Int {
        val folder = store.folders().first { it.id == folderId }
        val out = scanner.scan(folder) as ScanOutcome.Found
        store.applyScan(folderId, out.roms, now)
        return out.roms.size
    }

    @Test fun scanStoreFavouriteRescanAndUnplug() {
        put("Super_Game_(U).nes"); put("sub/deeper/Puzzle.SFC"); put("handheld/a.gb"); put("handheld/b.gbc"); put("adv.gba")
        put("ignored.zip"); put("notes.txt"); put("empty.nes", bytes = 0)

        val id = store.addFolder(FolderKind.APP, root.absolutePath, "Test folder")
        assertEquals(5, scanInto(id, 1000))
        val games = store.games().associateBy { it.fileName }
        assertEquals(setOf("Super_Game_(U).nes", "Puzzle.SFC", "a.gb", "b.gbc", "adv.gba"), games.keys)
        assertEquals("snes", games.getValue("Puzzle.SFC").systemId)
        assertEquals("Super Game", games.getValue("Super_Game_(U).nes").title)

        // A favourite survives a rescan, and a ROM added later shows up.
        store.setFavorite(games.getValue("adv.gba").path, true)
        put("new.nes")
        assertEquals(6, scanInto(id, 2000))
        assertTrue(store.games().single { it.fileName == "adv.gba" }.favorite)

        // Drive unplugged: entries kept but marked missing; back again after a successful scan.
        store.markUnavailable(id, 3000)
        assertFalse(store.folders().single { it.id == id }.available)
        assertTrue(store.games().all { it.missing })
        scanInto(id, 4000)
        assertTrue(store.folders().single { it.id == id }.available)
        assertTrue(store.games().none { it.missing })

        // A deleted folder is reported unavailable instead of throwing; removing the folder drops its games.
        root.deleteRecursively()
        assertTrue(scanner.scan(store.folders().single { it.id == id }) is ScanOutcome.Unavailable)
        store.removeFolder(id)
        assertTrue(store.games().isEmpty())
    }
}
