package com.retrobox.tv.library

import com.retrobox.tv.emulator.Systems
import java.io.File

class StorageUnavailableException(message: String) : Exception(message)

/** Lists the files below a folder. Implementations: [FileWalker] (app folder) and `SafWalker` (system-picker folders). */
interface RomWalker {
    /**
     * Calls [onFile] for every regular file under [root], recursively. Must poll [shouldStop] regularly.
     * @throws StorageUnavailableException when [root] itself cannot be read.
     */
    fun walk(root: String, onFile: (path: String, fileName: String, size: Long) -> Unit, shouldStop: () -> Boolean)

    /** Opens a file this walker reported (used to look inside `.zip` files). */
    fun open(path: String): java.io.InputStream = File(path).inputStream()

    /** The file itself when [path] is on the local file system (a zip can then be read by its directory), else null. */
    fun localFile(path: String): File? = File(path).takeIf { it.isFile }
}

class FileWalker(private val maxDepth: Int = MAX_DEPTH) : RomWalker {
    override fun walk(root: String, onFile: (String, String, Long) -> Unit, shouldStop: () -> Boolean) {
        val dir = File(root)
        if (!dir.isDirectory || dir.list() == null) throw StorageUnavailableException("Cannot read folder $root")
        fun rec(d: File, depth: Int) {
            if (shouldStop() || depth > maxDepth) return
            val children = d.listFiles() ?: return
            for (f in children) {
                if (shouldStop()) return
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) rec(f, depth + 1) else if (f.isFile) onFile(f.absolutePath, f.name, f.length())
            }
        }
        rec(dir, 0)
    }

    companion object { const val MAX_DEPTH = 8 }
}

sealed interface ScanOutcome {
    data class Found(val roms: List<ScannedRom>, val filesSeen: Int) : ScanOutcome
    data class Unavailable(val reason: String) : ScanOutcome
    data object Cancelled : ScanOutcome
}

/** Walks one folder and keeps the files whose extension belongs to a system. Cancellable, reports progress. */
class LibraryScanner(private val walkerFor: (LibraryFolder) -> RomWalker) {
    fun scan(folder: LibraryFolder, shouldStop: () -> Boolean = { false }, onProgress: (filesSeen: Int, romsFound: Int) -> Unit = { _, _ -> }): ScanOutcome {
        val roms = mutableListOf<ScannedRom>()
        val zipNames = HashMap<String, String>()   // library key of a zipped game -> its zip's file name
        var seen = 0
        try {
            val walker = walkerFor(folder)
            walker.walk(folder.location, { path, name, size ->
                seen++
                val system = Systems.detect(name)
                if (system != null && size > 0) {
                    if (!needsHeaderPeek(name) || headerSaysGenesis(walker, path)) roms += ScannedRom(system.id, path, name, titleFromFileName(name), size)
                } else if (ZipRom.isZip(name) && size > 0) {
                    zipRom(walker, path, size)?.let { roms += it; zipNames[it.path] = name }
                }
                if (seen % 25 == 0) onProgress(seen, roms.size)
            }, shouldStop)
        } catch (e: StorageUnavailableException) {
            return ScanOutcome.Unavailable(e.message ?: "Storage not available")
        } catch (e: SecurityException) {
            return ScanOutcome.Unavailable("Access to this folder was revoked")
        } catch (e: java.io.IOException) {
            return ScanOutcome.Unavailable(e.message ?: "Storage error")
        }
        if (shouldStop()) return ScanOutcome.Cancelled
        fun zipped(r: ScannedRom) = ZipRom.split(r.path) != null
        // A ROM next to its own zip (unzipped copy) would show twice; keep the plain file.
        val plain = roms.filterNot(::zipped).map { it.systemId to it.fileName.lowercase() }.toSet()
        roms.removeAll { zipped(it) && (it.systemId to it.fileName.lowercase()) in plain }
        // Two zips holding the same inner name (two hacks, each with "rom.nes") would share saves, states and pictures:
        // those are named after their zips instead.
        val clashing = roms.filter(::zipped).groupBy { it.systemId to it.fileName.lowercase() }.filterValues { it.size > 1 }.values.flatten().map { it.path }.toSet()
        val result = roms.map { r ->
            val zipName = zipNames[r.path]
            if (r.path !in clashing || zipName == null) r
            else ZipRom.nameFromZip(zipName, r.fileName).let { n -> r.copy(fileName = n, title = titleFromFileName(n)) }
        }
        onProgress(seen, result.size)
        return ScanOutcome.Found(result.sortedBy { it.path }, seen)
    }

    /** `.bin` is used by many systems; only a file with the Genesis header is listed as a Genesis game. */
    private fun needsHeaderPeek(name: String) = name.endsWith(".bin", ignoreCase = true)

    private fun headerSaysGenesis(walker: RomWalker, path: String): Boolean = try {
        walker.open(path).use { input ->
            val head = ByteArray(0x104); var n = 0
            while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r }
            n == head.size && com.retrobox.tv.emulator.RomValidator.looksLikeGenesis(head)
        }
    } catch (e: java.io.IOException) { false } catch (e: SecurityException) { false }

    /** The game inside a zip, or null (no supported ROM, or the file is not a readable zip: skipped, not fatal). */
    private fun zipRom(walker: RomWalker, path: String, zipSize: Long): ScannedRom? {
        val entry = try {
            val local = walker.localFile(path)
            if (local != null) ZipRom.firstRom(local) else walker.open(path).use { ZipRom.firstRom(it) }
        } catch (e: java.io.IOException) { null } catch (e: IllegalArgumentException) { null } ?: return null
        val system = Systems.detect(entry.fileName) ?: return null
        return ScannedRom(system.id, ZipRom.key(path, entry.path), entry.fileName, titleFromFileName(entry.fileName), if (entry.size > 0) entry.size else zipSize)
    }
}
