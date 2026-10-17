package com.retrobox.tv.library

import java.io.File

/** One game that has been played. [romPath] is the identity; [title] is what the UI shows. */
data class HistoryEntry(
    val systemId: String,
    val romPath: String,
    /** File name with extension; used for save files and pictures (the path may be an opaque content:// URI). */
    val fileName: String,
    val title: String,
    val coreId: String,
    val lastPlayedMs: Long,
    val playCount: Int,
    val totalPlayMs: Long,
)

interface PlayHistory {
    /** Most recently played first. */
    fun recent(limit: Int = MAX_ENTRIES): List<HistoryEntry>
    fun entryFor(romPath: String): HistoryEntry?
    fun recordStart(systemId: String, romPath: String, fileName: String, title: String, coreId: String, nowMs: Long = System.currentTimeMillis())
    fun addPlayTime(romPath: String, ms: Long)
    /** Forgets a game (it was deleted). */
    fun remove(romPath: String)

    companion object { const val MAX_ENTRIES = 30 }
}

/**
 * History in one small tab-separated file (`history.tsv`), rewritten atomically on every change.
 * A damaged line is skipped instead of discarding the whole history.
 */
class FilePlayHistory(private val file: File) : PlayHistory {
    private val lock = Any()

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun load(): MutableList<HistoryEntry> {
        if (!file.exists()) return mutableListOf()
        val out = mutableListOf<HistoryEntry>()
        runCatching {
            file.forEachLine { line ->
                val f = line.split('\t')
                // 8 fields now; 7-field lines are from before file names were stored.
                if (f.size == 8) runCatching {
                    out += HistoryEntry(unesc(f[0]), unesc(f[1]), unesc(f[2]), unesc(f[3]), unesc(f[4]), f[5].toLong(), f[6].toInt(), f[7].toLong())
                } else if (f.size == 7) runCatching {
                    val path = unesc(f[1])
                    out += HistoryEntry(unesc(f[0]), path, path.substringAfterLast('/'), unesc(f[2]), unesc(f[3]), f[4].toLong(), f[5].toInt(), f[6].toLong())
                }
            }
        }
        return out
    }

    private fun store(list: List<HistoryEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(list.joinToString("\n") { listOf(esc(it.systemId), esc(it.romPath), esc(it.fileName), esc(it.title), esc(it.coreId), it.lastPlayedMs, it.playCount, it.totalPlayMs).joinToString("\t") })
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    // The file is kept newest-first (move to front on every start), not sorted by the clock: a TV whose clock jumps back
    // (no network at boot) would otherwise show an older game as the last one played, or drop the new entry at the cap.
    override fun recent(limit: Int): List<HistoryEntry> = synchronized(lock) { load().take(limit) }

    override fun entryFor(romPath: String): HistoryEntry? = synchronized(lock) { load().firstOrNull { it.romPath == romPath } }

    override fun recordStart(systemId: String, romPath: String, fileName: String, title: String, coreId: String, nowMs: Long) = synchronized(lock) {
        val all = load()
        val old = all.firstOrNull { it.romPath == romPath }
        all.removeAll { it.romPath == romPath }
        all.add(0, HistoryEntry(systemId, romPath, fileName, title, coreId, nowMs, (old?.playCount ?: 0) + 1, old?.totalPlayMs ?: 0L))
        store(all.take(PlayHistory.MAX_ENTRIES))
    }

    override fun remove(romPath: String) = synchronized(lock) {
        val all = load()
        if (all.removeAll { it.romPath == romPath }) store(all)
    }

    override fun addPlayTime(romPath: String, ms: Long) = synchronized(lock) {
        if (ms <= 0) return@synchronized
        val all = load()
        val i = all.indexOfFirst { it.romPath == romPath }
        if (i < 0) return@synchronized
        all[i] = all[i].copy(totalPlayMs = all[i].totalPlayMs + ms)
        store(all)
    }
}

/** "5 min", "2 h 10 min" style duration for the UI. */
fun formatPlayTime(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "under a minute"
        min < 60 -> "$min min"
        else -> "${min / 60} h ${min % 60} min"
    }
}

/** "just now", "3 h ago", "yesterday", "5 days ago". */
fun formatAgo(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val m = (nowMs - thenMs).coerceAtLeast(0) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 48 * 60 -> "yesterday"
        else -> "${m / (24 * 60)} days ago"
    }
}
