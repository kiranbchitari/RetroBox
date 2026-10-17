package com.retrobox.tv.library

import com.retrobox.tv.save.SaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameDeleterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val roms by lazy { File(tmp.root, "files/roms").apply { mkdirs() } }
    private val outside by lazy { File(tmp.root, "other").apply { mkdirs() } }
    private val thumbs by lazy { ThumbnailStore(File(tmp.root, "internal/thumbs"), File(tmp.root, "files/artwork")) }
    private val saves by lazy { SaveStore(File(tmp.root, "internal/saves")) }
    private val history by lazy { FilePlayHistory(File(tmp.root, "internal/history.tsv")) }
    private val deleter by lazy { GameDeleter(listOf(roms), thumbs, saves, history) }

    private fun file(dir: File, rel: String, bytes: Int = 8) = File(dir, rel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes) { 1 }) }
    private fun game(path: String, system: String = "nes", name: String = path.substringAfterLast('/'), id: Long = 1) =
        Game(id, system, path, name, titleFromFileName(name), 8, 1, false, false)

    // ---- path-inside-folder check ----

    @Test fun fileBelowTheRootIsInside() {
        assertTrue(GameDeleter.isInside(roms, File(roms, "a.nes")))
        assertTrue(GameDeleter.isInside(roms, File(roms, "sub/deeper/a.nes")))
    }

    @Test fun rootItselfAndSiblingsWithTheSamePrefixAreNotInside() {
        assertFalse(GameDeleter.isInside(roms, roms))
        assertFalse(GameDeleter.isInside(roms, File(roms.path + "-backup/a.nes")))   // "roms-backup" starts with "roms" as text
        assertFalse(GameDeleter.isInside(roms, File(outside, "a.nes")))
    }

    @Test fun dotDotTricksDoNotEscapeTheRoot() {
        assertFalse(GameDeleter.isInside(roms, File(roms, "../other/a.nes")))
        assertFalse(GameDeleter.isInside(roms, File(roms, "sub/../../other/a.nes")))
        assertTrue(GameDeleter.isInside(roms, File(roms, "sub/../a.nes")))
    }

    @Test fun symlinkOutOfTheRootIsNotInside() {
        val target = file(outside, "secret.nes")
        val link = File(roms, "link.nes")
        val made = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        if (made) assertFalse(GameDeleter.isInside(roms, link))
    }

    // ---- art file name resolution ----

    @Test fun artworkNamesFollowTheSafeRomBaseNameIncludingNonAscii() {
        val names = thumbs.artworkCandidates("snes", "/r/Sonic the Hedgehog (Unl) [p1].smc").map { it.relativeTo(File(tmp.root, "files/artwork")).path }
        assertEquals(listOf("snes/Sonic the Hedgehog _Unl_ _p1_.png", "snes/Sonic the Hedgehog _Unl_ _p1_.jpg", "snes/Sonic the Hedgehog _Unl_ _p1_.jpeg"), names)
        val jp = thumbs.artworkCandidates("nes", "ドラゴン.nes").map { it.name }
        assertTrue(jp.all { it.startsWith("_____" ) && Regex("_[0-9a-f]{8}\\.(png|jpg|jpeg)").containsMatchIn(it) })
    }

    @Test fun artworkCandidatesAreTheSameFilesTheLibraryShows() {
        val art = file(File(tmp.root, "files/artwork"), "gb/Tetris.jpeg")
        assertTrue(art in thumbs.artworkCandidates("gb", "Tetris.gb"))
        assertEquals(art, thumbs.displayFile("gb", "Tetris.gb"))
    }

    @Test fun deleteAllForRemovesArtAndFrameOnlyForThatGameAndSystem() {
        val artDir = File(tmp.root, "files/artwork")
        val a = file(artDir, "nes/Duck.png"); val b = file(artDir, "nes/Duck.jpg"); val other = file(artDir, "nes/Other.png"); val otherSys = file(artDir, "gb/Duck.png")
        val frame = thumbs.fileFor("nes", "Duck.nes").also { file(it.parentFile!!, it.name) }
        val (removed, failed) = thumbs.deleteAllFor("nes", "/x/Duck.nes")
        assertEquals(setOf(a, b, frame), removed.toSet())
        assertTrue(failed.isEmpty())
        assertTrue(other.exists() && otherSys.exists())
    }

    // ---- saves ----

    @Test fun saveDeletionTakesStatesSramAndBackupsButNotOtherGames() {
        val root = File(tmp.root, "internal/saves")
        val mine = listOf("states/nes/Duck.slot0.state", "states/nes/Duck.slot1.state", "states/nes/Duck.slot4.state.bak", "states/nes/Duck.slot2.state.tmp", "sram/nes/Duck.srm", "sram/nes/Duck.srm.bak").map { file(root, it) }
        val kept = listOf("states/nes/Duck 2.slot1.state", "states/nes/Duck.txt", "sram/nes/Duck 2.srm", "states/gb/Duck.slot1.state", "sram/gb/Duck.srm", "states/nes/Duckx.slot1.state").map { file(root, it) }
        assertEquals(mine.toSet(), saves.deleteAllFor("nes", "Duck.nes").first.toSet())
        assertTrue(mine.none { it.exists() })
        assertTrue(kept.all { it.exists() })
    }

    @Test fun saveDeletionMatchesTheNamesSaveStoreWrites() {
        saves.writeSram("snes", "Great Game (U).smc", byteArrayOf(1, 2))
        saves.writeSram("snes", "Great Game (U).smc", byteArrayOf(3))      // leaves a .bak
        assertEquals(2, saves.deleteAllFor("snes", "Great Game (U).smc").first.size)
        assertFalse(saves.sramFile("snes", "Great Game (U).smc").exists())
    }

    // ---- blocked / allowed ----

    @Test fun gamesInTheAppRomFolderAreDeletable() {
        assertNull(deleter.blockedReason(game(file(roms, "Duck.nes").path)))
    }

    @Test fun safDocumentGamesAreRefusedWithAMessageAndNothingIsTouched() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AROMS/document/primary%3AROMS%2FDuck.nes"
        val g = game(uri, name = "Duck.nes")
        assertTrue(deleter.blockedReason(g)!!.contains("can't be deleted from here"))
        val art = file(File(tmp.root, "files/artwork"), "nes/Duck.png")
        val r = deleter.delete(g, true, listOf(g))
        assertFalse(r.romRemoved)
        assertNotNull(r.error)
        assertTrue(art.exists())
        val zipInSaf = game(ZipRom.key(uri.replace("Duck.nes", "pack.zip"), "Duck.nes"), name = "Duck.nes")
        assertNotNull(deleter.blockedReason(zipInSaf))
    }

    @Test fun filesOutsideTheRomFolderAreRefusedEvenIfTheLibraryListsThem() {
        val f = file(outside, "Duck.nes")
        val r = deleter.delete(game(f.path), false, emptyList())
        assertFalse(r.romRemoved)
        assertTrue(f.exists())
        assertNotNull(deleter.blockedReason(game(File(roms, "../other/Duck.nes").path)))
    }

    // ---- delete ----

    @Test fun deleteRemovesRomArtFrameAndHistoryAndKeepsSavesByDefault() {
        val rom = file(roms, "sub/Duck.nes")
        val art = file(File(tmp.root, "files/artwork"), "nes/Duck.png")
        val frame = thumbs.fileFor("nes", "Duck.nes").also { file(it.parentFile!!, it.name) }
        val state = saves.stateFile("nes", "Duck.nes", 0).also { file(it.parentFile!!, it.name) }
        val sram = saves.sramFile("nes", "Duck.nes").also { file(it.parentFile!!, it.name) }
        val untouched = file(roms, "Other.nes")
        val g = game(rom.path)
        history.recordStart("nes", rom.path, "Duck.nes", "Duck", "fceumm", 1)

        val r = deleter.delete(g, false, listOf(g))
        assertTrue(r.romRemoved); assertNull(r.error)
        assertEquals(2, r.artRemoved); assertEquals(0, r.savesRemoved)
        assertFalse(rom.exists() || art.exists() || frame.exists())
        assertTrue(state.exists() && sram.exists() && untouched.exists())
        assertNull(history.entryFor(rom.path))
        assertTrue(roms.exists())            // never the folder itself
    }

    @Test fun deleteWithSavesAlsoRemovesStatesAndCartridgeSave() {
        val rom = file(roms, "Duck.nes")
        val state = saves.stateFile("nes", "Duck.nes", 2).also { file(it.parentFile!!, it.name) }
        val sram = saves.sramFile("nes", "Duck.nes").also { file(it.parentFile!!, it.name) }
        val g = game(rom.path)
        val r = deleter.delete(g, true, listOf(g))
        assertEquals(2, r.savesRemoved)
        assertFalse(state.exists() || sram.exists())
    }

    @Test fun zippedGameDeletesItsZipFile() {
        val zip = file(roms, "pack.zip")
        val g = game(ZipRom.key(zip.path, "inner/Duck.nes"), name = "Duck.nes")
        assertTrue(deleter.isZipped(g))
        assertTrue(deleter.delete(g, false, listOf(g)).romRemoved)
        assertFalse(zip.exists())
    }

    @Test fun anotherCopyWithTheSameNameKeepsSharedArtAndSaves() {
        val a = file(roms, "a/Duck.nes"); val b = file(roms, "b/Duck.nes")
        val art = file(File(tmp.root, "files/artwork"), "nes/Duck.png")
        val state = saves.stateFile("nes", "Duck.nes", 1).also { file(it.parentFile!!, it.name) }
        val ga = game(a.path, id = 1); val gb = game(b.path, id = 2)
        val r = deleter.delete(ga, true, listOf(ga, gb))
        assertTrue(r.romRemoved && r.keptSharedFiles)
        assertFalse(a.exists()); assertTrue(b.exists() && art.exists() && state.exists())
        // The same name on another system is a different game and does not block cleaning up.
        assertFalse(GameDeleter.sameName("Duck.nes", "Other.nes"))
        assertTrue(GameDeleter.sameName("Duck.nes", "x/Duck.sfc"))
    }

    @Test fun alreadyMissingFileStillCountsAsRemoved() {
        val g = game(File(roms, "Gone.nes").path)
        assertTrue(deleter.delete(g, false, listOf(g)).romRemoved)
    }

    @Test fun summaryTextsAreHonest() {
        assertEquals("Deleted Duck", DeleteResult(true).summary("Duck"))
        assertTrue(DeleteResult(true, keptSharedFiles = true).summary("Duck").contains("kept"))
        assertEquals("boom", DeleteResult(false, error = "boom").summary("Duck"))
        assertEquals("Deleted Duck, but could not remove: Duck.png, Duck.slot1.state", DeleteResult(true, notRemoved = listOf("Duck.png", "Duck.slot1.state")).summary("Duck"))
    }
}
