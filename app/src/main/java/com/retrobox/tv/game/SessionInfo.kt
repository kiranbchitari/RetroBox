package com.retrobox.tv.game

import com.retrobox.tv.diag.ReportLine

/** Process-wide snapshot of the current/last emulation session, shown on the Diagnostics screen. */
object SessionInfo {
    @Volatile var lines: List<ReportLine> = listOf(ReportLine("Selected core", "none"), ReportLine("Current ROM", "none"))
    fun set(vararg l: Pair<String, String>) { lines = l.map { ReportLine(it.first, it.second) } }
}
