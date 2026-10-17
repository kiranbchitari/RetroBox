package com.retrobox.tv.diag

import com.retrobox.tv.settings.AppSettings
import com.retrobox.tv.emulator.Systems

/** Plain-text form of the Performance screen, for "Save report" (copy it off the TV with adb pull or a USB stick). */
object ReportFormatter {
    fun format(header: String, generatedAt: String, sections: List<Pair<String, List<ReportLine>>>, errors: List<String>): String = buildString {
        appendLine("RetroBox TV diagnostic report")
        appendLine(header)
        appendLine("Generated: $generatedAt")
        for ((title, lines) in sections) {
            appendLine()
            appendLine("== $title ==")
            if (lines.isEmpty()) appendLine("(nothing)")
            for (l in lines) appendLine(if (l.label.isEmpty()) l.value else "${l.label}: ${l.value}")
        }
        appendLine()
        appendLine("== Recent errors (${errors.size}) ==")
        if (errors.isEmpty()) appendLine("none recorded") else errors.forEach { appendLine(it) }
    }

    /** The settings that change what the player sees and hears, as report lines. */
    fun settingsLines(s: AppSettings): List<ReportLine> = buildList {
        val g = s.graphics
        add(ReportLine("Graphics default", "${g.scale.label}, ${if (g.effectiveBilinear) "smooth" else "sharp"}, look: ${g.shader.label}"))
        add(ReportLine("Safe area", if (s.safeAreaPct == 0) "off" else "${s.safeAreaPct}% per side"))
        add(ReportLine("FPS overlay", if (s.overlay) "on" else "off"))
        add(ReportLine("Audio", "${if (s.audio.enabled) "on" else "off"}, volume ${s.audio.volumePct}%, buffer ${s.audio.bufferMs} ms"))
        val ov = s.perSystem.filterValues { !it.isEmpty }
        add(ReportLine("Per-system exceptions", if (ov.isEmpty()) "none" else ov.keys.joinToString { Systems.byId(it)?.displayName ?: it }))
    }
}
