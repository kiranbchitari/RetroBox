package com.retrobox.tv.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.retrobox.tv.util.ErrorLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ScanStatus(val running: Boolean = false, val folderLabel: String = "", val filesSeen: Int = 0, val romsFound: Int = 0, val message: String? = null)

/**
 * Owns the library: folders, scan jobs and the in-memory game list the UI reads (Compose state, updated on the main thread).
 * All database work runs on one background thread.
 */
class LibraryService(private val context: Context, private val store: LibraryStore, appFolders: List<File>) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "library").apply { priority = Thread.MIN_PRIORITY } }
    private val cancel = AtomicBoolean(false)
    private val scanner = LibraryScanner { f -> if (f.kind == FolderKind.SAF) SafWalker(context.contentResolver) else FileWalker() }

    var games by mutableStateOf<List<Game>>(emptyList()); private set
    var folders by mutableStateOf<List<LibraryFolder>>(emptyList()); private set
    var scan by mutableStateOf(ScanStatus()); private set

    init {
        onWorker {
            // The private internal folder (only reachable with run-as) is pointless to users: use it only when shared storage is missing.
            val wanted = appFolders.filter { "/Android/data/" in it.absolutePath }.ifEmpty { appFolders.take(1) }
            store.folders().filter { it.kind == FolderKind.APP && it.location !in wanted.map { w -> w.absolutePath } }.forEach { store.removeFolder(it.id) }
            wanted.forEach { dir -> dir.mkdirs(); store.addFolder(FolderKind.APP, dir.absolutePath, if ("/Android/data/" in dir.absolutePath) "App folder (shared, adb/USB): Android/data/…/${dir.parentFile?.name}/${dir.name}" else "App folder (private)") }
            wanted.forEach { dir -> runCatching { com.retrobox.tv.emulator.Originals.install(dir) } }   // the games that ship with the app
            publish()
            scanAllNow()
        }
    }

    private fun publish() {
        val g = store.games(); val f = store.folders()
        main.post { games = g; folders = f }
    }

    fun rescan() { onWorker { scanAllNow() } }

    fun cancelScan() { cancel.set(true) }

    private fun scanAllNow() {
        cancel.set(false)
        for (folder in store.folders()) {
            if (cancel.get()) break
            scanOne(folder)
        }
        val folders = store.folders()
        val offline = folders.count { !it.available }
        val summary = (if (cancel.get()) "Scan cancelled · " else "") + "${folders.sumOf { it.gameCount }.let { if (it == 1) "1 game" else "$it games" }} in ${folders.size} ${if (folders.size == 1) "folder" else "folders"}" + if (offline > 0) " · $offline not available" else ""
        main.post { scan = scan.copy(running = false, folderLabel = "", message = summary) }
        publish()
    }

    private fun scanOne(folder: LibraryFolder) {
        main.post { scan = ScanStatus(running = true, folderLabel = folder.label) }
        val outcome = try {
            scanner.scan(folder, shouldStop = { cancel.get() }) { seen, found -> main.post { scan = scan.copy(filesSeen = seen, romsFound = found) } }
        } catch (t: Throwable) {
            ErrorLog.e("library", "Scan of ${folder.label} failed", t)
            ScanOutcome.Unavailable(t.message ?: t.javaClass.simpleName)
        }
        val now = System.currentTimeMillis()
        when (outcome) {
            is ScanOutcome.Found -> { store.applyScan(folder.id, outcome.roms, now); main.post { scan = scan.copy(message = "${folder.label}: ${outcome.roms.size} games") } }
            is ScanOutcome.Unavailable -> { store.markUnavailable(folder.id, now); ErrorLog.w("library", "${folder.label}: ${outcome.reason}"); main.post { scan = scan.copy(message = "${folder.label}: ${outcome.reason}") } }
            ScanOutcome.Cancelled -> main.post { scan = scan.copy(message = "Scan cancelled") }
        }
        publish()
    }

    fun addSafFolder(tree: android.net.Uri) {
        SafWalker.keepAccess(context, tree)
        onWorker {
            val id = store.addFolder(FolderKind.SAF, tree.toString(), SafWalker.labelOf(tree))
            publish()
            store.folders().firstOrNull { it.id == id }?.let { cancel.set(false); scanOne(it) }
            main.post { scan = scan.copy(running = false) }
        }
    }

    fun removeFolder(folder: LibraryFolder) {
        if (folder.kind == FolderKind.APP) return
        onWorker {
            store.removeFolder(folder.id)
            if (folder.kind == FolderKind.SAF) runCatching { context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(folder.location), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            publish()
        }
    }

    fun setFavorite(game: Game, favorite: Boolean) {
        // Optimistic UI update, then persist.
        games = games.map { if (it.path == game.path) it.copy(favorite = favorite) else it }
        // Publish afterwards: a scan running meanwhile may have published the list from before this change.
        onWorker { store.setFavorite(game.path, favorite); publish() }
    }

    /**
     * Deletes [game] with [deleter] (file work and the database row run on the library thread), refreshes the list, puts the
     * outcome in the scan line and calls [onDone] on the main thread. The row only goes when the file is really gone.
     */
    fun deleteGame(game: Game, deleter: GameDeleter, alsoSaves: Boolean, onDone: (DeleteResult) -> Unit = {}) {
        onWorker {
            val result = deleter.delete(game, alsoSaves, store.games())
            if (result.romRemoved) store.deleteGame(game.path)
            publish()
            main.post { scan = scan.copy(running = false, message = result.summary(game.title)); onDone(result) }
        }
    }

    /**
     * Runs [task] on the library thread. A database or storage error (full disk, damaged file) is logged and shown in the
     * scan line instead of killing the app: an uncaught exception there would crash it on every start.
     */
    private fun onWorker(task: () -> Unit) = worker.execute {
        try { task() } catch (t: Throwable) {
            ErrorLog.e("library", "Library task failed", t)
            main.post { scan = scan.copy(running = false, message = "Library error: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    fun gameFor(path: String): Game? = games.firstOrNull { it.path == path }
}
