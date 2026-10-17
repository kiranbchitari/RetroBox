package com.retrobox.tv.library

enum class FolderKind { APP, SAF }

/** A place ROMs are read from: the app's own folder, or a folder the user granted through the system picker (SAF tree). */
data class LibraryFolder(
    val id: Long,
    val kind: FolderKind,
    /** Absolute path (APP) or tree URI string (SAF). */
    val location: String,
    val label: String,
    /** False when the last scan could not read it (USB drive unplugged, permission revoked, folder deleted). */
    val available: Boolean,
    val lastScanMs: Long,
    val gameCount: Int,
)

/** A ROM known to the library. [path] is an absolute file path or a `content://` document URI. */
data class Game(
    val id: Long,
    val systemId: String,
    val path: String,
    val fileName: String,
    val title: String,
    val sizeBytes: Long,
    val folderId: Long,
    val favorite: Boolean,
    /** Its folder was unreachable at the last scan; the entry is kept so it comes back when the drive does. */
    val missing: Boolean,
)

/** A file found by a scan, already matched to a system. */
data class ScannedRom(val systemId: String, val path: String, val fileName: String, val title: String, val sizeBytes: Long)

/** Display title for a ROM file name (see [DisplayTitle]); the file name itself is never changed. */
fun titleFromFileName(fileName: String): String = DisplayTitle.fromFileName(fileName)
