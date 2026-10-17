package com.retrobox.tv.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Game pictures are the game's own last frame, saved when the pause menu opens or the game quits
 * (`thumbs/<system>/<rom>.png`, max [MAX_WIDTH] px wide). Nothing is downloaded and nothing ships with the app.
 */
class ThumbnailStore(private val root: File, private val artworkRoot: File = File(root.parentFile ?: root, "artwork")) {
    /**
     * The picture to show: user-supplied artwork (`artwork/<system>/<rom name>.png|jpg|jpeg`, copied in over adb/USB)
     * wins over the game's own saved frame. May not exist yet (the UI then shows a lettered tile).
     */
    fun displayFile(systemId: String, fileName: String): File {
        val base = fileFor(systemId, fileName).nameWithoutExtension
        val dir = File(artworkRoot, systemId.replace(Regex("[^a-z0-9]"), "_"))
        for (ext in ARTWORK_EXTENSIONS) File(dir, "$base.$ext").takeIf { it.isFile }?.let { return it }
        return fileFor(systemId, fileName)
    }

    /** Every user-supplied artwork file [displayFile] would pick for this game (`png`, `jpg`, `jpeg`), whether or not it exists. */
    fun artworkCandidates(systemId: String, fileName: String): List<File> {
        val base = fileFor(systemId, fileName).nameWithoutExtension
        val dir = File(artworkRoot, systemId.replace(Regex("[^a-z0-9]"), "_"))
        return ARTWORK_EXTENSIONS.map { File(dir, "$base.$it") }
    }

    /** Deletes the game's artwork and its saved frame; returns the files that were removed and the ones that exist but could not be. */
    fun deleteAllFor(systemId: String, fileName: String): Pair<List<File>, List<File>> =
        (artworkCandidates(systemId, fileName) + fileFor(systemId, fileName)).filter { it.exists() }.partition { it.delete() }

    fun fileFor(systemId: String, fileName: String): File {
        val base = com.retrobox.tv.util.SafeName.of(fileName.substringAfterLast('/').substringBeforeLast('.'))
        return File(root, "${systemId.replace(Regex("[^a-z0-9]"), "_")}/$base.png")
    }

    /** [argb] is [w]x[h] pixels row by row. Skips (returns false) pictures that are a single flat colour: a boot/blank screen. */
    fun save(systemId: String, fileName: String, argb: IntArray, w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0 || argb.size < w * h || isFlat(argb, w * h)) return false
        val f = fileFor(systemId, fileName)
        f.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        val scaled = if (w > MAX_WIDTH) Bitmap.createScaledBitmap(bmp, MAX_WIDTH, h * MAX_WIDTH / w, false) else bmp
        val tmp = File(f.path + ".tmp")
        return runCatching {
            tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f.delete(); tmp.renameTo(f)
        }.getOrDefault(false)
    }

    fun load(systemId: String, fileName: String): Bitmap? =
        fileFor(systemId, fileName).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    companion object {
        const val MAX_WIDTH = 320
        private val ARTWORK_EXTENSIONS = listOf("png", "jpg", "jpeg")
        internal fun isFlat(argb: IntArray, n: Int): Boolean {
            val first = argb[0]
            var i = 1
            val step = maxOf(1, n / 4000)
            while (i < n) { if (argb[i] != first) return false; i += step }
            return true
        }
    }
}
