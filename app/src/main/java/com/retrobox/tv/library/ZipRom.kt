package com.retrobox.tv.library

import com.retrobox.tv.emulator.Systems
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * ROMs inside `.zip` files. A zip counts as a game when one of its entries has a supported extension; the first such
 * entry (archive order, ignoring folders, hidden files and `__MACOSX`) is the
 * game, so a zip holding one ROM plus a readme works. The library stores it as `zip:<zip path or URI>!/<entry path>` and
 * uses the entry's own file name for the title, saves and pictures, so unzipping the game later keeps its saves.
 */
object ZipRom {
    private const val PREFIX = "zip:"     // explicit: some document providers' URIs do not end in ".zip"
    private const val SEPARATOR = "!/"

    fun isZip(fileName: String) = fileName.endsWith(".zip", ignoreCase = true)

    data class Entry(val path: String, val size: Long) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    fun key(container: String, entryPath: String) = "$PREFIX$container$SEPARATOR$entryPath"

    /** (container, entry path) for a key made by [key]; null for an ordinary path or URI. */
    fun split(key: String): Pair<String, String>? {
        if (!key.startsWith(PREFIX)) return null
        val i = key.lastIndexOf(SEPARATOR)
        if (i <= PREFIX.length) return null
        return key.substring(PREFIX.length, i) to key.substring(i + SEPARATOR.length)
    }

    private fun usable(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return !path.startsWith("__MACOSX/") && !name.startsWith(".") && Systems.detect(name) != null
    }

    /** The game entry of a zip, or null when it holds no supported ROM. Reads entry headers only as far as needed. */
    fun firstRom(input: InputStream): Entry? = ZipInputStream(input.buffered()).use { zip ->
        generateSequence { zip.nextEntry }.firstOrNull { !it.isDirectory && usable(it.name) }?.let { Entry(it.name, it.size) }
    }

    /** Same, for a file on disk: reads the zip's directory instead of inflating every entry before the ROM. */
    fun firstRom(file: File): Entry? = java.util.zip.ZipFile(file).use { zip ->
        zip.entries().asSequence().firstOrNull { e ->
            !e.isDirectory && usable(e.name)
        }?.let { Entry(it.name, it.size) }
    }

    /** "HackA.zip" holding "rom.nes" → "HackA.nes": the name used when two zips hold the same inner file name. */
    fun nameFromZip(zipFileName: String, entryFileName: String) =
        zipFileName.substringBeforeLast('.') + "." + entryFileName.substringAfterLast('.', "rom")

    /**
     * Writes entry [entryPath] of the zip in [input] to [out]. Refuses entries larger than [maxBytes] (a zip bomb or a
     * file no supported system has) without leaving a partial file behind.
     */
    fun extract(input: InputStream, entryPath: String, out: File, maxBytes: Long) {
        ZipInputStream(input.buffered()).use { zip ->
            val e = generateSequence { zip.nextEntry }.firstOrNull { it.name == entryPath } ?: throw IOException("$entryPath is no longer in the zip")
            val tmp = File(out.path + ".part")
            try {
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("${e.name.substringAfterLast('/')} is larger than ${maxBytes / (1024 * 1024)} MB unpacked")
                        o.write(buf, 0, n)
                    }
                }
                if (!tmp.renameTo(out)) throw IOException("could not write ${out.name}")
            } finally { tmp.delete() }
        }
    }
}
