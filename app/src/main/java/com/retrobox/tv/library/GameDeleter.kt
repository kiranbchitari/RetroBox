package com.retrobox.tv.library

import com.retrobox.tv.save.SaveStore
import com.retrobox.tv.util.ErrorLog
import com.retrobox.tv.util.SafeName
import java.io.File

/** What a delete did (or why it did not happen). [error] is a sentence for the screen. */
data class DeleteResult(
    val romRemoved: Boolean,
    val artRemoved: Int = 0,
    val savesRemoved: Int = 0,
    /** Another game in the library uses the same picture / save names, so those were left alone. */
    val keptSharedFiles: Boolean = false,
    val error: String? = null,
    /** Names of picture / save files that exist but could not be removed (the game itself is gone). */
    val notRemoved: List<String> = emptyList(),
) {
    fun summary(title: String): String = when {
        error != null -> error
        notRemoved.isNotEmpty() -> "Deleted $title, but could not remove: ${notRemoved.joinToString(", ")}"
        keptSharedFiles -> "Deleted $title (its pictures and saves are shared with another game and were kept)"
        else -> "Deleted $title"
    }
}

/**
 * Deletes a game from the app's own ROM folders. Only files that really live inside one of [romRoots] are ever removed;
 * games read from a folder the user added through the system picker (`content://` documents) are refused: the app only has
 * read access there, and a file outside the app's folder is not the app's to delete.
 */
class GameDeleter(private val romRoots: List<File>, private val thumbs: ThumbnailStore, private val saves: SaveStore, private val history: PlayHistory) {

    /** The file that would be removed for [game], or null when it is not deletable from here (see [blockedReason]). */
    fun deletableFile(game: Game): File? = romFile(game.path)?.takeIf { isInside(it) }

    /** Null when [game] can be deleted; otherwise a sentence for the user. */
    fun blockedReason(game: Game): String? = when {
        deletableFile(game) != null -> null
        game.path.startsWith("content:") || ZipRom.split(game.path)?.first?.startsWith("content:") == true ->
            "This game is in a folder you added under Folders. RetroBox can only read it there, so it can't be deleted from here. Remove the file with the file manager or from the computer."
        else -> "This game is outside RetroBox's own game folder, so it can't be deleted from here."
    }

    /** True for a `.zip` game: the zip file itself is removed, which may hold more than the one game. */
    fun isZipped(game: Game) = ZipRom.split(game.path) != null

    /**
     * Removes the ROM file, its picture and saved frame (not when another game of [allGames] shares their names) and, when
     * [alsoSaves], its save states and cartridge save. Also forgets it in the play history. Returns what happened; the caller
     * removes the database row when [DeleteResult.romRemoved].
     */
    fun delete(game: Game, alsoSaves: Boolean, allGames: List<Game>): DeleteResult {
        blockedReason(game)?.let { return DeleteResult(false, error = it) }
        val file = deletableFile(game)!!
        if (file.exists() && !file.delete()) return DeleteResult(false, error = "Could not delete ${file.name}: the file is write-protected or in use.")
        history.remove(game.path)
        // "Game.nes" in two sub-folders (or inside two zips) shares one picture and one save name: keep them for the other copy.
        val shared = allGames.any { it.path != game.path && it.systemId == game.systemId && sameName(it.fileName, game.fileName) }
        var art = 0; var sv = 0
        val failed = mutableListOf<File>()
        if (!shared) {
            runCatching { thumbs.deleteAllFor(game.systemId, game.fileName) }.onSuccess { art = it.first.size; failed += it.second }
                .onFailure { ErrorLog.w("library", "Picture delete failed: ${it.message}") }
            if (alsoSaves) runCatching { saves.deleteAllFor(game.systemId, game.fileName) }.onSuccess { sv = it.first.size; failed += it.second }
                .onFailure { ErrorLog.w("library", "Save delete failed: ${it.message}") }
        }
        ErrorLog.i("library", "Deleted ${file.name}: art=$art saves=$sv shared=$shared alsoSaves=$alsoSaves notRemoved=${failed.map { it.path }}")
        return DeleteResult(true, art, sv, keptSharedFiles = shared, notRemoved = failed.map { it.name })
    }

    /** The plain file for a library path (`zip:<container>!/<entry>` resolves to the container); null for `content://` URIs. */
    private fun romFile(path: String): File? {
        val p = ZipRom.split(path)?.first ?: path
        if (p.startsWith("content:") || p.isEmpty()) return null
        return File(p)
    }

    private fun isInside(f: File) = romRoots.any { isInside(it, f) }

    companion object {
        /** True when [file] is below [root] (not [root] itself), after resolving `..` and symlinks. */
        fun isInside(root: File, file: File): Boolean {
            val r = runCatching { root.canonicalFile }.getOrNull() ?: return false
            val f = runCatching { file.canonicalFile }.getOrNull() ?: return false
            return f != r && f.path.startsWith(r.path.trimEnd(File.separatorChar) + File.separator)
        }

        /** Two ROM file names share picture and save names when their safe base names match (the extension is ignored). */
        fun sameName(a: String, b: String): Boolean = safeBase(a) == safeBase(b)

        private fun safeBase(fileName: String) = SafeName.of(fileName.substringAfterLast('/').substringBeforeLast('.'))
    }
}
