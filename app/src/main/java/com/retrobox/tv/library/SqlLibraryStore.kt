package com.retrobox.tv.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Library tables in the platform SQLite (no Room/KSP: no extra APK weight or start-up cost on a 1 GB TV).
 * Browsing, search and sorting run in memory over [games] (see [LibraryFilter]); SQL is only persistence.
 */
class SqlLibraryStore(context: Context, name: String = "library.db") : SQLiteOpenHelper(context, name, null, 1), LibraryStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, location TEXT NOT NULL UNIQUE, label TEXT NOT NULL, available INTEGER NOT NULL DEFAULT 1, last_scan INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE games(id INTEGER PRIMARY KEY AUTOINCREMENT, system TEXT NOT NULL, path TEXT NOT NULL UNIQUE, file_name TEXT NOT NULL, title TEXT NOT NULL, size INTEGER NOT NULL, folder_id INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, missing INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX games_system ON games(system)")
        db.execSQL("CREATE INDEX games_folder ON games(folder_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun folders(): List<LibraryFolder> = readableDatabase.rawQuery(
        "SELECT f.id, f.kind, f.location, f.label, f.available, f.last_scan, (SELECT COUNT(*) FROM games g WHERE g.folder_id = f.id) FROM folders f ORDER BY f.id", null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(LibraryFolder(c.getLong(0), FolderKind.valueOf(c.getString(1)), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getLong(5), c.getInt(6))) }
    }

    override fun games(): List<Game> = readableDatabase.rawQuery("SELECT id, system, path, file_name, title, size, folder_id, favorite, missing FROM games", null).use { c ->
        buildList { while (c.moveToNext()) add(Game(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getLong(6), c.getInt(7) == 1, c.getInt(8) == 1)) }
    }

    override fun addFolder(kind: FolderKind, location: String, label: String): Long {
        val db = writableDatabase
        db.rawQuery("SELECT id FROM folders WHERE location = ?", arrayOf(location)).use { if (it.moveToFirst()) return it.getLong(0) }
        return db.insertOrThrow("folders", null, ContentValues().apply { put("kind", kind.name); put("location", location); put("label", label) })
    }

    override fun removeFolder(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("games", "folder_id = ?", arrayOf(id.toString()))
            db.delete("folders", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun applyScan(folderId: Long, roms: List<ScannedRom>, nowMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val favorites = HashSet<String>()
            db.rawQuery("SELECT path FROM games WHERE folder_id = ? AND favorite = 1", arrayOf(folderId.toString())).use { while (it.moveToNext()) favorites += it.getString(0) }
            db.delete("games", "folder_id = ?", arrayOf(folderId.toString()))
            for (r in roms) {
                // A ROM reachable from two folders keeps the first row (path is unique).
                db.insertWithOnConflict("games", null, ContentValues().apply {
                    put("system", r.systemId); put("path", r.path); put("file_name", r.fileName); put("title", r.title); put("size", r.sizeBytes)
                    put("folder_id", folderId); put("favorite", if (r.path in favorites) 1 else 0); put("missing", 0)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.update("folders", ContentValues().apply { put("available", 1); put("last_scan", nowMs) }, "id = ?", arrayOf(folderId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun markUnavailable(folderId: Long, nowMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("games", ContentValues().apply { put("missing", 1) }, "folder_id = ?", arrayOf(folderId.toString()))
            db.update("folders", ContentValues().apply { put("available", 0); put("last_scan", nowMs) }, "id = ?", arrayOf(folderId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun setFavorite(path: String, favorite: Boolean) {
        writableDatabase.update("games", ContentValues().apply { put("favorite", if (favorite) 1 else 0) }, "path = ?", arrayOf(path))
    }

    override fun deleteGame(path: String) {
        writableDatabase.delete("games", "path = ?", arrayOf(path))
    }
}
