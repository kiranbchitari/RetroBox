package com.retrobox.tv.library

interface LibraryStore {
    fun folders(): List<LibraryFolder>
    fun games(): List<Game>
    /** Returns the existing folder's id when [location] is already known. */
    fun addFolder(kind: FolderKind, location: String, label: String): Long
    fun removeFolder(id: Long)
    /** Replaces the folder's games with [roms]; favourites are kept for paths that are still present. */
    fun applyScan(folderId: Long, roms: List<ScannedRom>, nowMs: Long)
    /** The folder could not be read: keep its games, flagged missing, and remember that. */
    fun markUnavailable(folderId: Long, nowMs: Long)
    fun setFavorite(path: String, favorite: Boolean)
    /** Drops one game row (its file was deleted). A rescan would not bring it back since the file is gone. */
    fun deleteGame(path: String)
}
