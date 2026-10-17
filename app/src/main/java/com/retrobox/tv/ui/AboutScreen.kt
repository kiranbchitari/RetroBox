@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.retrobox.tv.BuildConfig
import com.retrobox.tv.emulator.Systems
import kotlinx.coroutines.launch

/** Readable name for a license file in `assets/licenses/` (see the copyLicenses task in app/build.gradle.kts). */
internal fun licenseTitle(file: String): String {
    val core = Systems.all.flatMap { it.cores }.firstOrNull { "core-${it.id}.txt" == file }
    if (core != null) {
        val systems = Systems.all.filter { core in it.cores }.joinToString(", ") { it.displayName }
        return "${core.displayName} ($systems) — ${core.license}"
    }
    return when (file) {
        "retrobox-GPL-2.0.txt" -> "RetroBox TV — GPL-2.0"
        "THIRD_PARTY_NOTICES.txt" -> "Third-party notices (summary)"
        "Apache-2.0.txt" -> "AndroidX, Jetpack Compose, TV Material — Apache-2.0"
        "libretro-h-MIT.txt" -> "libretro API header — MIT"
        else -> file.removeSuffix(".txt")
    }
}

/** Order: the app, the summary, the cores, then the libraries. */
private fun licenseOrder(file: String) = when {
    file.startsWith("retrobox") -> 0
    file.startsWith("THIRD_PARTY") -> 1
    file.startsWith("core-") -> 2
    else -> 3
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val files = remember { (assets.list("licenses") ?: emptyArray()).sortedWith(compareBy({ licenseOrder(it) }, { it })) }
    var open by remember { mutableStateOf<String?>(null) }
    var lastOpened by remember { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun req(f: String) = requesters.getOrPut(f) { FocusRequester() }

    Box(Modifier.fillMaxSize().dashboardBackground()) {
        val shown = open
        if (shown == null) {
            LaunchedEffect(Unit) { (lastOpened ?: files.firstOrNull())?.let { runCatching { req(it).requestFocus() } } }
            Column(Modifier.fillMaxSize()) {
                ScreenHeader("ABOUT & LICENSES", "RetroBox TV ${BuildConfig.VERSION_NAME}", onBack)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.width(640.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        MenuNote("RetroBox TV is free software under the GNU GPL, version 2. Each emulator core keeps its own license. No games, BIOS files or artwork are included: play only games you own.")
                        MenuNote("Snes9x 2005 may only be shared non-commercially. Do not sell or publish a build that contains it.", RB.Warn)
                        if (files.isEmpty()) MenuNote("No license files were packaged in this build.", RB.Warn)
                        files.forEach { f ->
                            MenuRow(MenuGlyph.LOAD, Color(0xFF8E9BFF), licenseTitle(f), Modifier.focusRequester(req(f))) { lastOpened = f; open = f }
                        }
                    }
                }
            }
        } else {
            LicenseText(licenseTitle(shown), remember(shown) { runCatching { assets.open("licenses/$shown").bufferedReader().use { it.readText() } }.getOrElse { "Could not read $shown: ${it.message}" } }) { open = null }
        }
    }
}

/** A long text the D-pad scrolls: Up/Down move by a few lines, Left/Right by a page; Back closes it. */
@Composable
private fun LicenseText(title: String, text: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, "Up / Down scroll · Left / Right page · Back closes", onClose)
        Box(
            Modifier.padding(horizontal = 40.dp).padding(bottom = 20.dp).fillMaxSize()
                .background(Color(0xCC0B1026), RoundedCornerShape(12.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                .focusRequester(focus)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = when (e.key) {
                        Key.DirectionDown -> 160f; Key.DirectionUp -> -160f
                        Key.DirectionRight, Key.PageDown -> 900f; Key.DirectionLeft, Key.PageUp -> -900f
                        else -> return@onKeyEvent false
                    }
                    scope.launch { scroll.animateScrollBy(step) }
                    true
                }
                .focusable()
                .verticalScroll(scroll)
                .padding(18.dp),
        ) {
            Text(text, fontSize = 13.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, color = RB.Text)
        }
    }
}
