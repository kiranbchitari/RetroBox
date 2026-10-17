@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.retrobox.tv.library.FolderKind
import com.retrobox.tv.library.LibraryFolder
import com.retrobox.tv.library.LibraryService
import com.retrobox.tv.library.formatAgo

/** Where ROMs come from: the app folder (always) plus folders granted through the system picker, with scan control. */
@Composable
fun FoldersScreen(library: LibraryService, pickerAvailable: Boolean, appFolderHelp: String, onAddFolder: () -> Unit, onBack: () -> Unit) {
    var confirmRemove by remember { mutableStateOf<LibraryFolder?>(null) }
    val scan = library.scan
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().dashboardBackground()) {
    Column(Modifier.fillMaxSize()) {
    ScreenHeader("FOLDERS", "Games are found by scanning these folders (recursively, up to 8 levels). Files are never changed or deleted.", onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 8.dp)) {
        SectionTitle(if (scan.running) "Scanning ${scan.folderLabel}… ${scan.filesSeen} files, ${scan.romsFound} games" else scan.message ?: "Idle")
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { TvCard("Add folder…", width = 190.dp, height = 46.dp, compact = true, enabled = pickerAvailable, onClick = onAddFolder) }
            item {
                if (scan.running) TvCard("Cancel scan", width = 190.dp, height = 46.dp, compact = true) { library.cancelScan() }
                else TvCard("Rescan all", width = 190.dp, height = 46.dp, compact = true) { library.rescan() }
            }
        }
        if (!pickerAvailable) {
            FocusRow {
                Text("This device has no system folder picker (common on Android TV). Put ROMs into the app folder below instead; they are found automatically on the next scan.", fontSize = 13.sp, color = RB.Warn)
            }
        }
        SectionTitle("Folders (${library.folders.size})")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            library.folders.forEach { f ->
                TvCard(
                    title = (if (f.available) "" else "⚠ ") + f.label,
                    subtitle = buildString {
                        append(f.gameCount).append(" games")
                        append(if (f.available) "" else " · not available (drive unplugged or access revoked)")
                        if (f.lastScanMs > 0) append(" · scanned ").append(formatAgo(f.lastScanMs))
                        if (f.kind == FolderKind.APP) append(" · built in")
                    },
                    width = 760.dp, height = 70.dp,
                ) { if (f.kind == FolderKind.SAF) confirmRemove = f }
            }
        }
        SectionTitle("App folder (always scanned)")
        FocusRow { Text(appFolderHelp, fontSize = 13.sp, color = RB.Muted) }
    }
    }
    confirmRemove?.let { f ->
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        androidx.activity.compose.BackHandler { confirmRemove = null }
        MenuPanel(width = 400.dp) {
            MenuHeader("Remove folder?", null, null, null, subtitle = f.label)
            MenuNote("Remove \"${f.label}\" from the library? The files stay where they are.", RB.Text)
            MenuRow(MenuGlyph.CLOSE, Color(0xFFB8C4FF), "No, keep it", Modifier.focusRequester(first), chevron = false) { confirmRemove = null }
            MenuRow(MenuGlyph.EXIT, Color(0xFFFF4D6D), "Yes, remove", chevron = false) { library.removeFolder(f); confirmRemove = null }
        }
    }
    }
}
