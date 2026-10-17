package com.retrobox.tv.game

import android.content.Context
import com.retrobox.tv.emulator.SystemAdapter
import com.retrobox.tv.util.ErrorLog
import java.io.File

data class GameMetadata(val title: String, val systemId: String, val path: String, val sizeBytes: Long, val lastModified: Long)

interface RomRepository {
    /** Directories searched, for display in the UI/help text. */
    val roots: List<File>
    fun list(system: SystemAdapter): List<GameMetadata>
}

/**
 * Milestone-3 repository: scans the app's own ROM folders (reachable over ADB/USB-file-transfer).
 * Replaced in Milestone 5 by SAF-based folders + Room.
 */
class AppRomRepository(context: Context) : RomRepository {
    // Created by the app itself: a folder first created by `adb push` is owned by the shell user and not listable by the app.
    override val roots: List<File> = listOfNotNull(context.getExternalFilesDir(null)?.let { File(it, "roms") }, File(context.filesDir, "roms"))
        .onEach { it.mkdirs() }

    override fun list(system: SystemAdapter): List<GameMetadata> {
        roots.forEach { ErrorLog.i("roms", "root ${it.path} dir=${it.isDirectory} files=${it.list()?.toList()}") }
        return roots.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().maxDepth(4).filter { it.isFile && system.supports(it.name) }.toList() }
            .map { GameMetadata(titleOf(it), system.id, it.absolutePath, it.length(), it.lastModified()) }
            .sortedBy { it.title.lowercase() }
    }

    companion object {
        fun titleOf(f: File) = f.nameWithoutExtension.replace('_', ' ').trim()
    }
}
