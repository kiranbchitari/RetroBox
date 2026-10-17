package com.retrobox.tv.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RomValidatorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val nes = Systems.byId("nes")!!

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).also { it.writeBytes(bytes) }
    private fun ines() = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12) + ByteArray(1024)

    @Test fun acceptsValidInesRom() = assertNull(RomValidator.validate(nes, file("ok.nes", ines())))

    @Test fun missingFileReportsStorageHint() {
        val e = RomValidator.validate(nes, File(tmp.root, "gone.nes"))!!
        assertEquals(ErrorKind.ROM_UNREADABLE, e.kind)
        assert(e.message.contains("storage"))
    }

    @Test fun wrongExtensionIsUnsupported() =
        assertEquals(ErrorKind.ROM_UNSUPPORTED, RomValidator.validate(nes, file("game.sfc", ines()))!!.kind)

    @Test fun emptyFileIsInvalid() =
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(nes, file("empty.nes", ByteArray(0)))!!.kind)

    @Test fun corruptHeaderIsInvalid() {
        val bad = ines().also { it[0] = 0x00 }
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(nes, file("bad.nes", bad))!!.kind)
    }

    @Test fun truncatedHeaderIsInvalid() =
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(nes, file("short.nes", byteArrayOf(0x4E, 0x45)))!!.kind)

    @Test fun directoryIsNotARom() {
        val d = File(tmp.root, "dir.nes").also { it.mkdir() }
        assertEquals(ErrorKind.ROM_UNREADABLE, RomValidator.validate(nes, d)!!.kind)
    }

    // ---- SNES ----
    private val snes = Systems.byId("snes")!!

    /** 32 KiB LoROM image with a header at 0x7FC0 (optionally preceded by a 512-byte copier header). */
    private fun snesRom(checksumOk: Boolean = true, copier: Boolean = false, mapMode: Int = 0x20, title: String = "TEST ROM"): ByteArray {
        val rom = ByteArray(0x8000 + if (copier) 512 else 0)
        val h = 0x7FC0 + if (copier) 512 else 0
        title.padEnd(21).forEachIndexed { i, c -> rom[h + i] = c.code.toByte() }
        rom[h + 0x15] = mapMode.toByte()
        val sum = if (checksumOk) 0x1234 else 0
        val comp = if (checksumOk) sum xor 0xFFFF else 0
        rom[h + 0x1C] = comp.toByte(); rom[h + 0x1D] = (comp shr 8).toByte()
        rom[h + 0x1E] = sum.toByte(); rom[h + 0x1F] = (sum shr 8).toByte()
        return rom
    }

    @Test fun snesAcceptsLoRomWithValidChecksum() = assertNull(RomValidator.validate(snes, file("a.sfc", snesRom())))

    @Test fun snesAcceptsCopierHeaderedRom() = assertNull(RomValidator.validate(snes, file("a.smc", snesRom(copier = true))))

    @Test fun snesAcceptsHackWithBadChecksumButSaneMapModeAndTitle() =
        assertNull(RomValidator.validate(snes, file("hack.sfc", snesRom(checksumOk = false))))

    @Test fun snesAcceptsHiRomHeader() {
        val rom = ByteArray(0x10000)
        val h = 0xFFC0
        "HIROM".padEnd(21).forEachIndexed { i, c -> rom[h + i] = c.code.toByte() }
        rom[h + 0x15] = 0x21
        rom[h + 0x1C] = 0xFF.toByte(); rom[h + 0x1D] = 0xFF.toByte()   // complement 0xFFFF, checksum 0x0000
        assertNull(RomValidator.validate(snes, file("hi.sfc", rom)))
    }

    @Test fun snesRejectsGarbageAndTinyFiles() {
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(snes, file("zero.sfc", ByteArray(0x8000)))!!.kind)
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(snes, file("noise.sfc", ByteArray(0x8000) { (it * 7 + 3).toByte() }))!!.kind)
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(snes, file("tiny.sfc", ByteArray(1024)))!!.kind)
    }

    @Test fun snesRejectsNesExtensionAndNesRomExtensionMismatch() =
        assertEquals(ErrorKind.ROM_UNSUPPORTED, RomValidator.validate(snes, file("game.nes", snesRom()))!!.kind)

    // ---- Game Boy / Color ----
    private val gb = Systems.byId("gb")!!
    private val gbc = Systems.byId("gbc")!!
    private val logo = intArrayOf(
        0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
        0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
        0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E,
    )

    private fun gbRom(withLogo: Boolean = true, fixChecksum: Boolean = true, size: Int = 0x8000): ByteArray {
        val rom = ByteArray(size)
        if (withLogo) logo.forEachIndexed { i, v -> rom[0x104 + i] = v.toByte() }
        "TEST".forEachIndexed { i, c -> rom[0x134 + i] = c.code.toByte() }
        if (fixChecksum) {
            var x = 0
            for (i in 0x134..0x14C) x = (x - (rom[i].toInt() and 0xFF) - 1) and 0xFF
            rom[0x14D] = x.toByte()
        }
        return rom
    }

    @Test fun gbAcceptsRomWithLogo() {
        assertNull(RomValidator.validate(gb, file("a.gb", gbRom())))
        assertNull(RomValidator.validate(gbc, file("a.gbc", gbRom())))
    }

    @Test fun gbAcceptsBootlegWithoutLogoButValidHeaderChecksum() =
        assertNull(RomValidator.validate(gb, file("boot.gb", gbRom(withLogo = false))))

    @Test fun gbAcceptsLogoEvenIfChecksumIsOff() = assertNull(RomValidator.validate(gb, file("odd.gb", gbRom(fixChecksum = false))))

    @Test fun gbRejectsNeitherLogoNorChecksumAndTruncatedFiles() {
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(gb, file("bad.gb", gbRom(withLogo = false, fixChecksum = false)))!!.kind)
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(gbc, file("bad.gbc", ByteArray(0x8000)))!!.kind)
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(gb, file("short.gb", ByteArray(0x120)))!!.kind)
    }

    @Test fun gbRomWithGbaExtensionIsRoutedNowhere() {
        assertEquals(ErrorKind.ROM_UNSUPPORTED, RomValidator.validate(gb, file("game.gba", gbRom()))!!.kind)
        assertEquals(ErrorKind.ROM_UNSUPPORTED, RomValidator.validate(gb, file("game.gbc", gbRom()))!!.kind)   // gbc belongs to the GBC system
    }

    // ---- GBA ----
    private val gba = Systems.byId("gba")!!

    private fun gbaRom(fixed96: Boolean = true, complement: Boolean = false, size: Int = 0x8000): ByteArray {
        val rom = ByteArray(size)
        "TEST".forEachIndexed { i, c -> rom[0xA0 + i] = c.code.toByte() }
        if (fixed96) rom[0xB2] = 0x96.toByte()
        if (complement) {
            var x = 0
            for (i in 0xA0..0xBC) x = (x - (rom[i].toInt() and 0xFF)) and 0xFF
            rom[0xBD] = ((x - 0x19) and 0xFF).toByte()
        }
        return rom
    }

    @Test fun gbaAcceptsFixedByteOrValidComplement() {
        assertNull(RomValidator.validate(gba, file("a.gba", gbaRom())))
        assertNull(RomValidator.validate(gba, file("b.gba", gbaRom(fixed96 = false, complement = true))))
    }

    @Test fun gbaRejectsBadHeaderAndTruncatedFiles() {
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(gba, file("bad.gba", gbaRom(fixed96 = false)))!!.kind)
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(gba, file("tiny.gba", ByteArray(0x80)))!!.kind)
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(gba, file("empty.gba", ByteArray(0)))!!.kind)
    }

    @Test fun oversizedRomIsRejectedBeforeAnyHeaderRead() {
        val f = File(tmp.root, "huge.gba")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(RomValidator.MAX_ROM_BYTES + 1) }
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(gba, f)!!.kind)
    }
}
