package com.retrobox.tv.emulator

import com.retrobox.tv.library.FileWalker
import com.retrobox.tv.library.FolderKind
import com.retrobox.tv.library.LibraryFolder
import com.retrobox.tv.library.LibraryScanner
import com.retrobox.tv.library.ScanOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GenesisTest {
    @get:Rule val tmp = TemporaryFolder()
    private val genesis = Systems.byId("genesis")!!

    /** A minimal cartridge image with the "SEGA" system name and an I/O field. */
    private fun rom(name: String, sega: Boolean = true, io: String = "J", size: Int = 0x8000): File = File(tmp.root, name).apply {
        val b = ByteArray(size)
        if (sega) "SEGA GENESIS    ".toByteArray().copyInto(b, 0x100)
        io.toByteArray().copyInto(b, 0x190)
        writeBytes(b)
    }

    @Test fun extensionsAndCores() {
        listOf("x.md", "x.GEN", "x.smd", "x.bin").forEach { assertEquals(it, "genesis", Systems.detect(it)?.id) }
        assertEquals(listOf("genesis_plus_gx", "picodrive"), genesis.cores.map { it.id })
        assertEquals(2, genesis.players)
    }

    @Test fun headerCheck() {
        assertNull(RomValidator.validate(genesis, rom("ok.md")))
        assertNull(RomValidator.validate(genesis, rom("ok.bin")))
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(genesis, rom("atari.bin", sega = false))?.kind)
        // An interleaved .smd copier dump has no plain "SEGA" but a 512-byte header with AA BB at bytes 8-9.
        val smd = File(tmp.root, "copier.smd").apply { writeBytes(ByteArray(0x4000 + 0x200).also { it[8] = 0xAA.toByte(); it[9] = 0xBB.toByte() }) }
        assertNull(RomValidator.validate(genesis, smd))
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(genesis, File(tmp.root, "tiny.gen").apply { writeBytes(ByteArray(64)) })?.kind)
    }

    @Test fun scannerListsOnlyGenesisBins() {
        val dir = tmp.newFolder("roms")
        rom("roms/Sonic.bin"); rom("roms/pitfall.bin", sega = false); rom("roms/Streets.md", sega = false)
        File(dir, "short.bin").writeBytes(ByteArray(16))
        val out = (LibraryScanner { FileWalker() }.scan(LibraryFolder(1, FolderKind.APP, dir.path, "roms", true, 0, 0)) as ScanOutcome.Found).roms
        assertEquals(setOf("Sonic.bin", "Streets.md"), out.map { it.fileName }.toSet())   // .md is trusted by extension; checked again at launch
    }

    @Test fun picoDriveGetsASixButtonPadOnlyWhenTheGameAsksForIt() {
        val pico = genesis.cores[1]; val gpgx = genesis.cores[0]
        assertEquals(mapOf("picodrive_input1" to "6 button pad", "picodrive_input2" to "6 button pad"), CoreOptions.forGame(pico, rom("six.md", io = "J6")))
        assertTrue(CoreOptions.forGame(pico, rom("three.md", io = "J")).isEmpty())
        assertTrue(CoreOptions.forGame(gpgx, rom("six2.md", io = "J6")).isEmpty())   // GPGX detects it itself
        assertFalse(CoreOptions.genesisWantsSixButtons(File(tmp.root, "missing.md")))
    }

    @Test fun buttonNamesAndTurbo() {
        assertEquals("A", genesis.nameOf(PadButton.Y)); assertEquals("C", genesis.nameOf(PadButton.A)); assertEquals("Mode", genesis.nameOf(PadButton.SELECT))
        assertEquals("Start", genesis.nameOf(PadButton.START))
        assertEquals(listOf("Off", "A", "B", "C", "A + B", "A + B + C"), Turbo.choices(genesis).map { Turbo.label(it, genesis) })
        assertEquals("B", Systems.byId("nes")!!.nameOf(PadButton.B))
    }
}
