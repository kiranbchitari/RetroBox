@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.retrobox.tv.diag.DeviceReport
import com.retrobox.tv.diag.ReportFormatter
import com.retrobox.tv.diag.ReportLine
import com.retrobox.tv.settings.AppSettings
import java.io.File
import com.retrobox.tv.util.ErrorLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticsScreen(sessionInfo: List<ReportLine>, settings: AppSettings, onBack: () -> Unit) {
    val ctx: Context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var device by remember { mutableStateOf(emptyList<ReportLine>()) }
    var gl by remember { mutableStateOf<List<ReportLine>?>(null) }
    var errors by remember { mutableStateOf(ErrorLog.recent()) }
    val cores = remember(refresh) { DeviceReport.installedCores(ctx) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    val settingsLines = remember(settings) { ReportFormatter.settingsLines(settings) }

    LaunchedEffect(refresh) {
        device = withContext(Dispatchers.IO) { DeviceReport.collect(ctx) }
        errors = ErrorLog.recent()
        gl = null
        gl = withContext(Dispatchers.IO) { runCatching { DeviceReport.queryGl() }.getOrElse { listOf(ReportLine("OpenGL ES", "query failed: ${it.message}")) } }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().dashboardBackground()) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("PERFORMANCE", "Device, GPU, memory, cores and recent errors. Share this screen when reporting a problem.", onBack)
        Row(Modifier.padding(horizontal = 48.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvCard("Refresh", width = 160.dp, height = 56.dp) { refresh++ }
            TvCard("Clear error log", width = 220.dp, height = 56.dp) { ErrorLog.clear(); errors = ErrorLog.recent() }
            TvCard("Save report to file", width = 240.dp, height = 56.dp) {
                savedMessage = runCatching {
                    val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "diagnostics/report.txt")
                    file.parentFile?.mkdirs()
                    val coreText = listOf(ReportLine("Cores in this APK (${android.os.Build.SUPPORTED_ABIS.firstOrNull()})", cores.joinToString().ifEmpty { "none" }))
                    file.writeText(ReportFormatter.format(
                        "Version ${com.retrobox.tv.BuildConfig.VERSION_NAME}",
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
                        listOf("Device" to device, "Graphics" to (gl ?: emptyList()), "Settings" to settingsLines, "Emulation" to (sessionInfo + coreText)),
                        errors,
                    ))
                    "Saved to ${file.absolutePath}  (copy it with adb pull or USB)"
                }.getOrElse { "Could not save the report: ${it.message}" }
            }
        }
        savedMessage?.let { Text(it, fontSize = 12.sp, color = RB.Good, modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp)) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
            item { SectionTitle("Device") }
            items(device) { KeyValueRow(it.label, it.value) }
            item { SectionTitle("Graphics") }
            val g = gl
            if (g == null) item { KeyValueRow("OpenGL ES", "querying…") } else items(g) { KeyValueRow(it.label, it.value) }
            item { SectionTitle("Settings in effect") }
            items(settingsLines) { KeyValueRow(it.label, it.value) }
            item { SectionTitle("Emulation (last game played)") }
            items(sessionInfo) { KeyValueRow(it.label, it.value) }
            item { KeyValueRow("Cores in this APK (${android.os.Build.SUPPORTED_ABIS.firstOrNull()})", cores.joinToString().ifEmpty { "none" }) }
            item { SectionTitle("Recent errors (${errors.size})") }
            if (errors.isEmpty()) item { KeyValueRow("", "No errors recorded") }
            items(errors) { line -> FocusRow { Text(line, fontSize = 14.sp, color = RB.Warn) } }
        }
    }
    }
}
