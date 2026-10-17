package com.retrobox.tv.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemsTest {
    @Test fun detectsSystemsByExtensionCaseInsensitively() {
        assertEquals("nes", Systems.detect("Super Game.NES")?.id)
        assertEquals("snes", Systems.detect("game.sfc")?.id)
        assertEquals("snes", Systems.detect("game.smc")?.id)
        assertEquals("gb", Systems.detect("tetris.gb")?.id)
        assertEquals("gbc", Systems.detect("pokemon.gbc")?.id)
        assertEquals("gba", Systems.detect("advance.gba")?.id)
    }

    @Test fun unknownOrMissingExtensionIsNotDetected() {
        assertNull(Systems.detect("readme.txt"))
        assertNull(Systems.detect("game.zip"))     // compressed ROMs are not accepted until a core's support is verified
        assertNull(Systems.detect("noextension"))
        assertNull(Systems.detect(""))
    }

    @Test fun extensionsAreUniqueAcrossSystems() {
        val all = Systems.all.flatMap { it.extensions }
        assertEquals("an extension must map to exactly one system", all.size, all.toSet().size)
    }

    @Test fun everySystemHasACoreAndButtons() {
        Systems.all.forEach { s ->
            assertNotNull("${s.id} has no core", s.defaultCore)
            assertTrue("${s.id} has no buttons", s.buttons.isNotEmpty())
        }
    }

    @Test fun everyExtensionMapsToExactlyItsOwnSystem() {
        val expected = mapOf(
            "snes" to listOf("sfc", "smc", "swc", "fig"), "gb" to listOf("gb"), "gbc" to listOf("gbc"),
            "gba" to listOf("gba"), "nes" to listOf("nes", "fds", "unf", "unif"),
        )
        expected.forEach { (id, exts) ->
            assertEquals("extensions of $id", exts.toSet(), Systems.byId(id)!!.extensions)
            exts.forEach { ext -> assertEquals(id, Systems.detect("Some Game.${ext.uppercase()}")?.id) }
        }
    }

    @Test fun gbaAndSnesExtensionsAreNotConfusedWithGameBoy() {
        assertEquals("gba", Systems.detect("x.gba")?.id)   // 'gb' is a prefix of 'gba'/'gbc': detection must not be prefix based
        assertEquals("gb", Systems.detect("x.gb")?.id)
        assertEquals("gbc", Systems.detect("x.gbc")?.id)
        assertNull(Systems.detect("x.gbx"))
        assertNull(Systems.detect("x.sf"))
        assertNull(Systems.detect("x.7z"))
    }

    @Test fun gbaOffersGpspFirstAndMgbaAsAlternative() {
        val gba = Systems.byId("gba")!!
        assertEquals(listOf("gpsp", "mgba"), gba.cores.map { it.id })
        assertEquals("gpsp", gba.defaultCore!!.id)
    }

    @Test fun gameBoyAndColorShareTheGambatteCore() {
        assertEquals("gambatte", Systems.byId("gb")!!.defaultCore!!.id)
        assertEquals("gambatte", Systems.byId("gbc")!!.defaultCore!!.id)
    }

    @Test fun buttonSubsetsMatchTheRealControllers() {
        fun b(id: String) = Systems.byId(id)!!.buttons.toSet()
        val dpadAndMain = setOf(PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT, PadButton.A, PadButton.B, PadButton.START, PadButton.SELECT)
        assertEquals(dpadAndMain, b("nes"))
        assertEquals(dpadAndMain, b("gb"))
        assertEquals(dpadAndMain, b("gbc"))
        assertEquals(dpadAndMain + setOf(PadButton.L, PadButton.R), b("gba"))
        assertEquals(dpadAndMain + setOf(PadButton.L, PadButton.R, PadButton.X, PadButton.Y), b("snes"))
    }

    @Test fun coreChoiceFallsBackToDefaultAndCyclesThroughAlternatives() {
        val gba = Systems.byId("gba")!!
        assertEquals("gpsp", gba.coreFor(null)!!.id)
        assertEquals("gpsp", gba.coreFor("does-not-exist")!!.id)
        assertEquals("mgba", gba.coreFor("mgba")!!.id)
        assertEquals("mgba", gba.nextCore(null)!!.id)
        assertEquals("gpsp", gba.nextCore("mgba")!!.id)         // wraps around
        val nes = Systems.byId("nes")!!
        assertEquals("fceumm", nes.nextCore("fceumm")!!.id)      // single-core systems stay put
        assertEquals("fceumm", nes.coreFor("mgba")!!.id)         // a core id from another system is ignored
    }

    @Test fun verifiedSystemsNeverClaimRealTvTesting() {
        // TEST_MATRIX.md: nothing has run on the Xiaomi Mi TV 4A yet; the note shown on the home card must say so.
        Systems.all.filter { it.status == SystemStatus.VERIFIED }.forEach {
            assertTrue("${it.id}: ${it.statusNote}", it.statusNote.contains("not yet on a real TV"))
        }
    }
}
