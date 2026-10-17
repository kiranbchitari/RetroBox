package com.retrobox.tv.save

import java.io.File

/** Slot info for UI: null meta means the slot is empty, [corrupt] means a file exists but cannot be read. */
data class SlotInfo(val slot: Int, val meta: StateMeta?, val corrupt: Boolean)

interface SaveStateRepository {
    val slotCount: Int
    fun slots(systemId: String, romName: String): List<SlotInfo>
    fun write(systemId: String, romName: String, slot: Int, meta: StateMeta, payload: ByteArray)
    fun read(systemId: String, romName: String, slot: Int): StateDecode?     // null = empty
}

/** File-based saves under [root]: `states/<system>/<rom>.slotN.state` and `sram/<system>/<rom>.srm`. */
class SaveStore(private val root: File, override val slotCount: Int = 4) : SaveStateRepository {

    private fun base(romName: String) = com.retrobox.tv.util.SafeName.of(romName.substringBeforeLast('.'), Int.MAX_VALUE)
    fun stateFile(systemId: String, romName: String, slot: Int) = File(root, "states/$systemId/${base(romName)}.slot$slot.state")
    fun sramFile(systemId: String, romName: String) = File(root, "sram/$systemId/${base(romName)}.srm")

    override fun slots(systemId: String, romName: String): List<SlotInfo> = (1..slotCount).map { slot ->
        val f = stateFile(systemId, romName, slot)
        if (!f.exists()) SlotInfo(slot, null, false)
        else when (val d = runCatching { StateFile.decode(f.readBytes()) }.getOrNull()) {
            is StateDecode.Ok -> SlotInfo(slot, d.meta, false)
            else -> SlotInfo(slot, null, true)
        }
    }

    override fun write(systemId: String, romName: String, slot: Int, meta: StateMeta, payload: ByteArray) =
        writeAtomic(stateFile(systemId, romName, slot), StateFile.encode(meta, payload), keepBackup = true)

    override fun read(systemId: String, romName: String, slot: Int): StateDecode? {
        val f = stateFile(systemId, romName, slot)
        if (!f.exists()) return null
        return runCatching { StateFile.decode(f.readBytes()) }.getOrElse { StateDecode.Corrupt("cannot read file") }
    }

    fun readSram(systemId: String, romName: String): ByteArray? = sramFile(systemId, romName).takeIf { it.exists() }?.runCatching { readBytes() }?.getOrNull()

    /** Writes the cartridge save. The previous file is kept as `.bak` so a bad write never destroys the only copy. */
    fun writeSram(systemId: String, romName: String, data: ByteArray) = writeAtomic(sramFile(systemId, romName), data, keepBackup = true)

    /**
     * Deletes every save state slot (including the Quick Resume session, slot 0), the cartridge save and their `.bak` / `.tmp`
     * leftovers for this game; returns the files removed and the ones that could not be. Only files named exactly after this game are touched.
     */
    fun deleteAllFor(systemId: String, romName: String): Pair<List<File>, List<File>> {
        val b = Regex.escape(base(romName))
        val states = Regex("$b\\.slot\\d+\\.state(\\.bak|\\.tmp)?")
        val sram = Regex("$b\\.srm(\\.bak|\\.tmp)?")
        fun sweep(dir: String, pattern: Regex) = File(root, "$dir/$systemId").listFiles().orEmpty().filter { it.isFile && pattern.matches(it.name) }
        return (sweep("states", states) + sweep("sram", sram)).partition { it.delete() }
    }

    private fun writeAtomic(target: File, data: ByteArray, keepBackup: Boolean) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(data)
        if (target.exists() && keepBackup) {
            val bak = File(target.path + ".bak")
            bak.delete()
            target.renameTo(bak)
        }
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "Could not replace ${target.name}" } }
    }
}
