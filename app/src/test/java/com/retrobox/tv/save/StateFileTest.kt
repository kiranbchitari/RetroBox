package com.retrobox.tv.save

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateFileTest {
    private val meta = StateMeta("fceumm", "ac0d00a", "game.nes", 1_700_000_000_000L, 5)
    private val payload = byteArrayOf(1, 2, 3, 4, 5)

    @Test fun roundTripPreservesMetadataAndPayload() {
        val d = StateFile.decode(StateFile.encode(meta, payload)) as StateDecode.Ok
        assertEquals(meta, d.meta)
        assertArrayEquals(payload, d.payload)
    }

    @Test fun flippedPayloadByteIsDetected() {
        val b = StateFile.encode(meta, payload); b[b.size - 1] = (b[b.size - 1] + 1).toByte()
        assertTrue(StateFile.decode(b) is StateDecode.Corrupt)
    }

    @Test fun truncationIsDetected() {
        val b = StateFile.encode(meta, payload)
        assertTrue(StateFile.decode(b.copyOf(b.size - 2)) is StateDecode.Corrupt)
    }

    @Test fun foreignFilesAreRejected() {
        assertTrue(StateFile.decode(ByteArray(100)) is StateDecode.Corrupt)
        assertTrue(StateFile.decode(ByteArray(3)) is StateDecode.Corrupt)
    }

    @Test fun unicodeRomNamesSurvive() {
        val m = meta.copy(romName = "ゲーム ★.nes")
        assertEquals(m, (StateFile.decode(StateFile.encode(m, payload)) as StateDecode.Ok).meta)
    }
}
