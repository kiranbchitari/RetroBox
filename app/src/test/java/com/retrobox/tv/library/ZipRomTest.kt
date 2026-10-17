package com.retrobox.tv.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipRomTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipBytes(vararg entries: Pair<String, ByteArray?>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); data?.let { z.write(it) }; z.closeEntry() } }
    }.toByteArray()

    private fun zipFile(name: String, vararg entries: Pair<String, ByteArray?>) = File(tmp.root, name).apply { parentFile.mkdirs(); writeBytes(zipBytes(*entries)) }

    @Test fun keyRoundTripForFilesAndContentUris() {
        assertEquals("/r/Zoop (USA).zip" to "Zoop (USA).sfc", ZipRom.split(ZipRom.key("/r/Zoop (USA).zip", "Zoop (USA).sfc")))
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AROMS/document/primary%3AROMS%2Fsnake.ZIP"
        assertEquals(uri to "sub/snake.sfc", ZipRom.split(ZipRom.key(uri, "sub/snake.sfc")))
        assertNull(ZipRom.split("/r/plain.sfc"))
        assertNull(ZipRom.split("/r/odd!/name.sfc"))                 // no zip: prefix = an ordinary path, even with "!/"
        assertEquals("/r/a!/b.zip" to "c.nes", ZipRom.split(ZipRom.key("/r/a!/b.zip", "c.nes")))
        // A provider whose document IDs carry no file name (e.g. ".../document/123") still round-trips.
        assertEquals("content://downloads/document/123" to "game.nes", ZipRom.split(ZipRom.key("content://downloads/document/123", "game.nes")))
    }

    @Test fun firstRomSkipsFoldersJunkAndUnsupportedFiles() {
        val z = zipBytes("__MACOSX/._game.sfc" to byteArrayOf(1), "docs/" to null, "readme.txt" to byteArrayOf(1),
            ".hidden.nes" to byteArrayOf(1), "roms/Game (USA).sfc" to ByteArray(512), "other.gb" to byteArrayOf(1))
        val e = ZipRom.firstRom(ByteArrayInputStream(z))!!
        assertEquals("roms/Game (USA).sfc", e.path)
        assertEquals("Game (USA).sfc", e.fileName)
        assertNull(ZipRom.firstRom(ByteArrayInputStream(zipBytes("readme.txt" to byteArrayOf(1), "game.7z" to byteArrayOf(1)))))
    }

    @Test fun extractWritesTheEntryAndNothingElse() {
        val rom = ByteArray(70_000) { (it % 251).toByte() }
        val out = File(tmp.root, "out.sfc")
        ZipRom.extract(ByteArrayInputStream(zipBytes("a.txt" to byteArrayOf(9), "dir/game.sfc" to rom)), "dir/game.sfc", out, 1 shl 20)
        assertArrayEquals(rom, out.readBytes())
        assertFalse(File(out.path + ".part").exists())
    }

    @Test fun extractRefusesOversizeAndMissingEntriesWithoutLeavingFiles() {
        val out = File(tmp.root, "big.sfc")
        try { ZipRom.extract(ByteArrayInputStream(zipBytes("big.sfc" to ByteArray(5000))), "big.sfc", out, 4096); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("larger")) }
        assertFalse(out.exists()); assertFalse(File(out.path + ".part").exists())
        try { ZipRom.extract(ByteArrayInputStream(zipBytes("x.sfc" to byteArrayOf(1))), "y.sfc", out, 4096); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("no longer")) }
    }

    @Test fun twoZipsWithTheSameInnerNameGetTheirOwnNames() {
        val root = tmp.newFolder("hacks")
        File(root, "Hack One.zip").writeBytes(zipBytes("rom.nes" to ByteArray(64)))
        File(root, "Hack Two.zip").writeBytes(zipBytes("rom.nes" to ByteArray(64)))
        File(root, "Solo.zip").writeBytes(zipBytes("solo-game.gb" to ByteArray(64)))
        val out = (LibraryScanner { FileWalker() }.scan(LibraryFolder(1, FolderKind.APP, root.path, "hacks", true, 0, 0)) as ScanOutcome.Found).roms
        assertEquals(setOf("Hack One.nes", "Hack Two.nes", "solo-game.gb"), out.map { it.fileName }.toSet())   // saves/pictures are keyed by these
        assertEquals(setOf("Hack One", "Hack Two", "Solo Game"), out.map { it.title }.toSet())
    }

    @Test fun scannerListsZippedGamesByTheirInnerRom() {
        val root = tmp.newFolder("roms")
        File(root, "Zoop (USA).zip").writeBytes(zipBytes("Zoop (USA).sfc" to ByteArray(1024)))
        File(root, "pack/Wild_Snake.ZIP").apply { parentFile.mkdirs(); writeBytes(zipBytes("readme.txt" to byteArrayOf(1), "Wild_Snake.sfc" to ByteArray(2048))) }
        File(root, "docs.zip").writeBytes(zipBytes("readme.txt" to byteArrayOf(1)))
        File(root, "broken.zip").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        // The same game as a plain file and zipped: listed once, as the plain file.
        File(root, "tetris.gb").writeBytes(ByteArray(64))
        File(root, "tetris.zip").writeBytes(zipBytes("tetris.gb" to ByteArray(64)))

        val folder = LibraryFolder(1, FolderKind.APP, root.path, "roms", true, 0, 0)
        val out = LibraryScanner { FileWalker() }.scan(folder) as ScanOutcome.Found
        val byName = out.roms.associateBy { it.fileName }
        assertEquals(setOf("Zoop (USA).sfc", "Wild_Snake.sfc", "tetris.gb"), byName.keys)
        val zoop = byName.getValue("Zoop (USA).sfc")
        assertEquals("snes", zoop.systemId)
        assertEquals("Zoop", zoop.title)
        assertEquals(File(root, "Zoop (USA).zip").path to "Zoop (USA).sfc", ZipRom.split(zoop.path))
        assertTrue(byName.getValue("Wild_Snake.sfc").sizeBytes > 0)   // unpacked size when the zip header has it, else the zip size
        assertEquals(File(root, "tetris.gb").path, byName.getValue("tetris.gb").path)
    }
}
