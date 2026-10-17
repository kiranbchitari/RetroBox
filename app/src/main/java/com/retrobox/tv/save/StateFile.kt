package com.retrobox.tv.save

import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Metadata stored in front of every save-state payload so corrupt/foreign files are detected, never loaded blindly. */
data class StateMeta(
    val coreId: String,
    val coreVersion: String,
    val romName: String,
    val timestampMs: Long,
    val payloadSize: Int,
)

sealed interface StateDecode {
    data class Ok(val meta: StateMeta, val payload: ByteArray) : StateDecode
    data class Corrupt(val reason: String) : StateDecode
}

object StateFile {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte())
    private const val VERSION = 1

    fun encode(meta: StateMeta, payload: ByteArray): ByteArray {
        val strings = listOf(meta.coreId, meta.coreVersion, meta.romName).map { it.toByteArray(Charsets.UTF_8) }
        val size = 4 + 4 + 8 + 4 + 4 + strings.sumOf { 2 + it.size } + payload.size
        val b = ByteBuffer.allocate(size)
        b.put(MAGIC).putInt(VERSION).putLong(meta.timestampMs).putInt(payload.size)
        val crc = CRC32().also { it.update(payload) }.value.toInt()
        b.putInt(crc)
        strings.forEach { b.putShort(it.size.toShort()).put(it) }
        b.put(payload)
        return b.array()
    }

    fun decode(bytes: ByteArray): StateDecode {
        try {
            if (bytes.size < 24) return StateDecode.Corrupt("file too small")
            val b = ByteBuffer.wrap(bytes)
            val magic = ByteArray(4).also { b.get(it) }
            if (!magic.contentEquals(MAGIC)) return StateDecode.Corrupt("not a RetroBox save state")
            val version = b.int
            if (version != VERSION) return StateDecode.Corrupt("unsupported state version $version")
            val ts = b.long
            val payloadSize = b.int
            val crc = b.int
            fun str(): String { val n = b.short.toInt() and 0xFFFF; if (n > b.remaining()) throw IllegalArgumentException("bad string"); return ByteArray(n).also { b.get(it) }.toString(Charsets.UTF_8) }
            val coreId = str(); val coreVer = str(); val rom = str()
            if (payloadSize < 0 || payloadSize != b.remaining()) return StateDecode.Corrupt("truncated or extended payload")
            val payload = ByteArray(payloadSize).also { b.get(it) }
            if (CRC32().also { it.update(payload) }.value.toInt() != crc) return StateDecode.Corrupt("checksum mismatch")
            return StateDecode.Ok(StateMeta(coreId, coreVer, rom, ts, payloadSize), payload)
        } catch (e: Exception) {
            return StateDecode.Corrupt("unreadable (${e.javaClass.simpleName})")
        }
    }
}
