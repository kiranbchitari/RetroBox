package com.retrobox.tv

import com.retrobox.tv.library.FileWalker
import com.retrobox.tv.library.FilePlayHistory
import com.retrobox.tv.library.FolderKind
import com.retrobox.tv.library.GameDeleter
import com.retrobox.tv.library.LibraryScanner
import com.retrobox.tv.library.ScanOutcome
import com.retrobox.tv.library.SqlLibraryStore
import com.retrobox.tv.library.ThumbnailStore
import com.retrobox.tv.save.SaveStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deleting a game with the real SQLite store and real files, entirely inside the test's own scratch folder and database:
 * the player's ROMs, artwork, saves and library are never touched.
 */
class GameDeleteDeviceTest {
    private val dbName = "device-test-delete.db"
    private val store = SqlLibraryStore(TestRoms.app, dbName)
    private val base = File(TestRoms.scratch, "delete").apply { mkdirs() }
    private val roms = File(base, "files/roms").apply { mkdirs() }
    private val artwork = File(base, "files/artwork")
    private val thumbs = ThumbnailStore(File(base, "thumbs"), artwork)
    private val saves = SaveStore(File(base, "saves"))
    private val history = FilePlayHistory(File(base, "history.tsv"))
    private val deleter = GameDeleter(listOf(roms), thumbs, saves, history)

    @After fun tearDown() { store.close(); TestRoms.app.deleteDatabase(dbName); TestRoms.clean() }

    private fun put(dir: File, rel: String) = File(dir, rel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(16) { 1 }) }

    @Test fun deleteRemovesFilesAndTheLibraryRowAndKeepsOtherGames() {
        val keep = put(roms, "Keep Me.nes"); val rom = put(roms, "sub/Delete Me.nes")
        val art = put(artwork, "nes/Delete Me.jpg"); val state = put(File(base, "saves"), "states/nes/Delete Me.slot1.state"); val sram = put(File(base, "saves"), "sram/nes/Delete Me.srm")
        val id = store.addFolder(FolderKind.APP, roms.absolutePath, "Test folder")
        store.applyScan(id, (LibraryScanner { FileWalker() }.scan(store.folders().single { it.id == id }) as ScanOutcome.Found).roms, 1000)
        val game = store.games().single { it.fileName == "Delete Me.nes" }
        history.recordStart(game.systemId, game.path, game.fileName, game.title, "fceumm", 1)

        val result = deleter.delete(game, alsoSaves = false, allGames = store.games())
        if (result.romRemoved) store.deleteGame(game.path)

        assertTrue(result.romRemoved)
        assertFalse(rom.exists() || art.exists())
        assertTrue(state.exists() && sram.exists() && keep.exists())          // saves are kept by default
        assertEquals(listOf("Keep Me.nes"), store.games().map { it.fileName })
        assertNull(history.entryFor(game.path))
        // A rescan does not bring the game back: its file is gone.
        store.applyScan(id, (LibraryScanner { FileWalker() }.scan(store.folders().single { it.id == id }) as ScanOutcome.Found).roms, 2000)
        assertEquals(listOf("Keep Me.nes"), store.games().map { it.fileName })
    }
}
