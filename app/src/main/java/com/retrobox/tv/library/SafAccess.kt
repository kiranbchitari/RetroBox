package com.retrobox.tv.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Walks a folder the user granted through the system picker (Storage Access Framework tree URI). */
class SafWalker(private val resolver: ContentResolver) : RomWalker {
    override fun open(path: String): java.io.InputStream =
        resolver.openInputStream(Uri.parse(path)) ?: throw java.io.IOException("Could not open $path")

    override fun localFile(path: String): File? = null

    override fun walk(root: String, onFile: (String, String, Long) -> Unit, shouldStop: () -> Boolean) {
        val tree = Uri.parse(root)
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { throw StorageUnavailableException("Folder reference is invalid") }
        fun rec(docId: String, depth: Int) {
            if (shouldStop() || depth > FileWalker.MAX_DEPTH) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cursor = try {
                resolver.query(children, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                ), null, null, null)
            } catch (e: Exception) {
                // A subfolder that cannot be listed must fail the whole scan (the folder is then marked unavailable and its
                // games kept): treating it as empty would delete those games and their favourites from the library.
                throw StorageUnavailableException("Folder is not reachable (${e.javaClass.simpleName}); is the drive connected?")
            }
            if (cursor == null) throw StorageUnavailableException("Folder is not reachable; is the drive connected?")
            val dirs = mutableListOf<String>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    if (shouldStop()) return
                    val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2); val size = if (c.isNull(3)) 0L else c.getLong(3)
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) dirs += id
                    else onFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, size)
                }
            }
            dirs.forEach { rec(it, depth + 1) }
        }
        rec(rootId, 0)
    }

    companion object {
        /** A readable name for a picked tree, e.g. "primary:ROMS/SNES" -> "ROMS/SNES". */
        fun labelOf(tree: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Storage" } }.getOrDefault("Folder")

        fun keepAccess(context: Context, tree: Uri) {
            runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        /** False on TV firmwares without a system document picker (common): the UI then explains the app folder instead. */
        fun pickerAvailable(context: Context): Boolean {
            val info = context.packageManager.resolveActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0)?.activityInfo ?: return false
            return !isPlaceholder(info.packageName, info.name)
        }

        /**
         * Android TV images answer the picker intent with a stub activity (`com.android.tv.frameworkpackagestubs`,
         * seen on the TV emulator) that only tells the user the action is unsupported, so "an activity exists" is not enough.
         */
        fun isPlaceholder(packageName: String, className: String) =
            packageName.contains("frameworkpackagestubs", ignoreCase = true) || className.contains("Stub", ignoreCase = true)
    }
}

class RomUnavailableException(message: String) : Exception(message)

/** Makes a library ROM readable by a core: file paths are used in place, SAF documents are copied into the cache first. */
object RomAccess {
    fun materialize(context: Context, key: String, fileName: String): File {
        ZipRom.split(key)?.let { (container, entry) -> return unzip(context, container, entry, fileName) }
        if (!key.startsWith("content://")) {
            val f = File(key)
            if (!f.isFile) throw RomUnavailableException("ROM file not found: $fileName. Is the storage still connected?")
            return f
        }
        val dir = File(context.cacheDir, "rom-cache").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != fileName) it.delete() }   // keep the cache at one ROM
        val out = File(dir, fileName)
        try {
            context.contentResolver.openInputStream(Uri.parse(key))?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: throw RomUnavailableException("Could not open $fileName. Is the drive connected?")
        } catch (e: RomUnavailableException) { throw e
        } catch (e: SecurityException) { throw RomUnavailableException("Access to $fileName was revoked. Add the folder again under Folders.")
        } catch (e: java.io.IOException) { out.delete(); throw RomUnavailableException("Could not read $fileName (${e.message}). Is the drive connected?")
        } catch (e: Exception) { out.delete(); throw RomUnavailableException("Could not open $fileName: ${e.javaClass.simpleName}") }
        return out
    }

    /** Unpacks a zipped ROM into the one-ROM cache (cores need a plain file). */
    private fun unzip(context: Context, container: String, entry: String, fileName: String): File {
        val dir = File(context.cacheDir, "rom-cache").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != fileName) it.delete() }
        val out = File(dir, fileName)
        val zipName = Uri.decode(container.substringAfterLast('/')).substringAfterLast('/')
        try {
            val input = if (container.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(container))
                ?: throw RomUnavailableException("Could not open $zipName. Is the drive connected?")
            else File(container).takeIf { it.isFile }?.inputStream()
                ?: throw RomUnavailableException("$zipName was not found. Is the storage still connected?")
            ZipRom.extract(input, entry, out, com.retrobox.tv.emulator.RomValidator.MAX_ROM_BYTES)
        } catch (e: RomUnavailableException) { throw e
        } catch (e: SecurityException) { throw RomUnavailableException("Access to $zipName was revoked. Add the folder again under Folders.")
        } catch (e: java.io.IOException) { out.delete(); throw RomUnavailableException("Could not unpack $fileName from $zipName (${e.message}).")
        } catch (e: IllegalArgumentException) { out.delete(); throw RomUnavailableException("$zipName is not a readable zip file.") }
        return out
    }
}
