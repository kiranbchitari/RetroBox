package com.retrobox.tv.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val store get() = ThumbnailStore(tmp.root)

    @Test fun pictureLivesPerSystemUnderTheRomName() {
        assertEquals("snes/Sonic the Hedgehog _Unl_ _p1_.png", store.fileFor("snes", "/storage/roms/Sonic the Hedgehog (Unl) [p1].smc").relativeTo(tmp.root).path)
    }

    @Test fun sameRomNameOnDifferentSystemsDoesNotCollide() {
        assertFalse(store.fileFor("gb", "/a/tetris.gb") == store.fileFor("gbc", "/a/tetris.gbc"))
    }

    @Test fun pathTricksStayInsideTheThumbnailFolder() {
        val f = store.fileFor("../../evil", "/x/../../etc/pa:ss*wd.nes")
        assertTrue(f.canonicalPath.startsWith(tmp.root.canonicalPath))
    }

    @Test fun flatPicturesAreDetected() {
        assertTrue(ThumbnailStore.isFlat(IntArray(10_000) { 0xFF000000.toInt() }, 10_000))
        assertFalse(ThumbnailStore.isFlat(IntArray(10_000) { if (it == 5_000 || it % 97 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }, 10_000))
    }

    @Test fun userArtworkBeatsTheSavedFrameAndFallsBackToIt() {
        val s = ThumbnailStore(java.io.File(tmp.root, "thumbs"), java.io.File(tmp.root, "artwork"))
        assertEquals(s.fileFor("snes", "Sonic.smc"), s.displayFile("snes", "Sonic.smc"))             // nothing supplied: the saved frame (may not exist yet)
        val art = java.io.File(tmp.root, "artwork/snes/Sonic.jpg").also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1)) }
        assertEquals(art, s.displayFile("snes", "Sonic.smc"))
        assertEquals(s.fileFor("gb", "Sonic.gb"), s.displayFile("gb", "Sonic.gb"))                  // artwork is per system
        val png = java.io.File(tmp.root, "artwork/snes/Sonic.png").also { it.writeBytes(byteArrayOf(1)) }
        assertEquals(png, s.displayFile("snes", "Sonic.smc"))                                        // png preferred over jpg
    }
}
