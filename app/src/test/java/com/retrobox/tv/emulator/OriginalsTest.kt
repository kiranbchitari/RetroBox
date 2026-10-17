package com.retrobox.tv.emulator

import com.retrobox.tv.settings.AppSettings
import com.retrobox.tv.settings.GraphicsSettings
import com.retrobox.tv.settings.ScaleMode
import com.retrobox.tv.settings.ShaderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OriginalsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val originals = Systems.byId(Originals.SYSTEM_ID)!!

    @Test fun cartridgesAreDetectedAsRetroBoxOriginals() {
        assertEquals(Originals.SYSTEM_ID, Systems.detect("Snake.rbo")?.id)
        assertEquals(Originals.SYSTEM_ID, Systems.detect("SNAKE.RBO")?.id)
        assertEquals(Originals.CORE_ID, originals.defaultCore!!.id)
    }

    @Test fun theSystemOffersNoAutoFireButtons() = assertTrue(Turbo.choices(originals) == listOf(emptySet<PadButton>()))

    @Test fun shippedCartridgesPassTheValidator() {
        Originals.cartridges.forEach { c ->
            val f = File(tmp.root, c.fileName).also { it.writeBytes(Originals.cartridgeBytes(c.gameId)) }
            assertNull(c.fileName, RomValidator.validate(originals, f))
            assertTrue(String(f.readBytes()).contains("game=${c.gameId}"))
        }
    }

    @Test fun aFileWithoutTheCartridgeHeaderIsRejected() {
        val f = File(tmp.root, "fake.rbo").also { it.writeText("hello world") }
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(originals, f)!!.kind)
        val short = File(tmp.root, "short.rbo").also { it.writeText("RB") }
        assertEquals(ErrorKind.ROM_INVALID, RomValidator.validate(originals, short)!!.kind)
    }

    @Test fun resolutionFollowsTheScreenHeight() {
        assertEquals("720p", Originals.resolutionFor(720))
        assertEquals("720p", Originals.resolutionFor(0))       // unknown
        assertEquals("1080p", Originals.resolutionFor(1080))
        assertEquals("1080p", Originals.resolutionFor(2160))   // 4K screens still get a 1080p frame
        assertEquals("1080p", Originals.resolutionFor(1000))
        assertEquals("720p", Originals.resolutionFor(800))
        assertEquals("540p", Originals.resolutionFor(540))
    }

    @Test fun onlyTheOriginalsCoreGetsTheResolutionOption() {
        val rom = File(tmp.root, "Snake.rbo")
        assertEquals(mapOf(Originals.RESOLUTION_OPTION to "1080p"), CoreOptions.forGame(originals.defaultCore!!, rom, 1080))
        assertEquals(mapOf(Originals.RESOLUTION_OPTION to "720p"), CoreOptions.forGame(originals.defaultCore!!, rom, 720))
        assertTrue(CoreOptions.forGame(Systems.byId("nes")!!.defaultCore!!, rom, 1080).isEmpty())
    }

    @Test fun installWritesEachGameOnceAndALaterDeleteSticks() {
        val written = Originals.install(tmp.root)
        assertEquals(Originals.cartridges, written)
        val snake = File(tmp.root, "${Originals.FOLDER}/Snake.rbo")
        assertTrue(snake.isFile)
        assertTrue(Originals.install(tmp.root).isEmpty())     // second start: nothing to do
        assertTrue(snake.delete())
        assertTrue(Originals.install(tmp.root).isEmpty())     // deleted on purpose: stays deleted
        assertFalse(snake.exists())
    }

    @Test fun installKeepsACartridgeTheUserAlreadyHas() {
        val dir = File(tmp.root, Originals.FOLDER).also { it.mkdirs() }
        File(dir, "Snake.rbo").writeText("RBO1\ngame=snake\n# mine\n")
        Originals.install(tmp.root)
        assertTrue(File(dir, "Snake.rbo").readText().contains("# mine"))
    }

    @Test fun originalsLookPlainUnlessTheUserSaysOtherwise() {
        val crtEverywhere = AppSettings(graphics = GraphicsSettings(ScaleMode.FOUR_THREE, true, ShaderMode.CRT))
        val g = crtEverywhere.graphicsFor(Originals.SYSTEM_ID)
        assertEquals(ShaderMode.NONE, g.shader)
        assertEquals(ScaleMode.ORIGINAL, g.scale)
        assertEquals(ShaderMode.CRT, crtEverywhere.graphicsFor("nes").shader)
        assertFalse(crtEverywhere.hasOverride(Originals.SYSTEM_ID))
        // an explicit choice in the pause menu still wins
        val chosen = crtEverywhere.withSystemGraphics(Originals.SYSTEM_ID, GraphicsSettings(ScaleMode.STRETCH, true, ShaderMode.NONE))
        assertEquals(ScaleMode.STRETCH, chosen.graphicsFor(Originals.SYSTEM_ID).scale)
        assertTrue(chosen.hasOverride(Originals.SYSTEM_ID))
        assertNotNull(chosen.withoutSystemOverride(Originals.SYSTEM_ID).graphicsFor(Originals.SYSTEM_ID))
    }
}
