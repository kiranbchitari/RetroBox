package com.retrobox.tv.diag

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

data class ReportLine(val label: String, val value: String)

/** Collects the facts the diagnostic checklist in ARCHITECTURE.md asks for, from inside the app. */
object DeviceReport {

    fun collect(context: Context): List<ReportLine> {
        val out = mutableListOf<ReportLine>()
        fun add(l: String, v: String) { out += ReportLine(l, v) }

        add("Device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        add("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("ABIs", Build.SUPPORTED_ABIS.joinToString())
        add("Process 64-bit", if (Build.VERSION.SDK_INT >= 23 && android.os.Process.is64Bit()) "yes" else "no")
        add("Board / hardware", "${Build.BOARD} / ${Build.HARDWARE}")
        add("CPU", cpuSummary())

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        add("RAM total / available", "${mi.totalMem.mb()} MB / ${mi.availMem.mb()} MB" + if (mi.lowMemory) " (LOW)" else "")
        add("App memory class", "${am.memoryClass} MB" + if (am.isLowRamDevice) ", low-RAM device" else "")
        val rt = Runtime.getRuntime()
        add("App heap used / max", "${(rt.totalMemory() - rt.freeMemory()).mb()} MB / ${rt.maxMemory().mb()} MB")

        add("Internal storage free", statFree(Environment.getDataDirectory()))
        context.getExternalFilesDir(null)?.let { add("App external dir", "${it.path}  (${statFree(it)} free)") }

        val dm = context.resources.displayMetrics
        add("Display", "${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi} dpi")
        val pm = context.packageManager
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        add("Android TV UI mode", (ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION).toString())
        add("Leanback feature", pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK).toString())
        add("Gamepad feature", pm.hasSystemFeature("android.hardware.gamepad").toString())
        add("Touchscreen", pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN).toString())
        add("Document picker (SAF)", safPickerAvailable(context))
        return out
    }

    /** Libretro cores packaged for this device's ABI (file names in nativeLibraryDir). */
    fun installedCores(context: Context): List<String> =
        File(context.applicationInfo.nativeLibraryDir).listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith("_libretro_android.so") }
            ?.map { it.removePrefix("lib").removeSuffix("_libretro_android.so") }
            ?.sorted() ?: emptyList()

    /** GL vendor/renderer/version via a throw-away 1x1 pbuffer context. Call off the main thread. */
    fun queryGl(): List<ReportLine> {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return listOf(ReportLine("OpenGL ES", "no EGL display"))
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return listOf(ReportLine("OpenGL ES", "eglInitialize failed"))
        var ctx = EGL14.EGL_NO_CONTEXT
        var surf = EGL14.EGL_NO_SURFACE
        try {
            val cfgAttr = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttr, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return listOf(ReportLine("OpenGL ES", "no ES2 config"))
            ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surf = EGL14.eglCreatePbufferSurface(display, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) return listOf(ReportLine("OpenGL ES", "eglMakeCurrent failed"))
            return listOf(
                ReportLine("GL vendor", GLES20.glGetString(GLES20.GL_VENDOR) ?: "?"),
                ReportLine("GL renderer", GLES20.glGetString(GLES20.GL_RENDERER) ?: "?"),
                ReportLine("GL version", GLES20.glGetString(GLES20.GL_VERSION) ?: "?"),
                ReportLine("Max texture size", IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0].toString()),
            )
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
            EGL14.eglTerminate(display)
        }
    }

    private fun safPickerAvailable(context: Context): String {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        return if (i.resolveActivity(context.packageManager) != null) "available" else "MISSING (use app ROM folder fallback)"
    }

    private fun cpuSummary(): String = runCatching {
        val lines = File("/proc/cpuinfo").readLines()
        val cores = lines.count { it.startsWith("processor") }
        val feat = lines.firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()
            ?.split(' ')?.filter { it in setOf("neon", "asimd", "vfpv4", "vfpv3", "aes") }?.joinToString(",") ?: ""
        val hw = lines.firstOrNull { it.startsWith("Hardware") }?.substringAfter(':')?.trim() ?: ""
        "$cores cores ${if (hw.isNotEmpty()) "· $hw " else ""}${if (feat.isNotEmpty()) "· $feat" else ""}".trim()
    }.getOrDefault("unreadable")

    private fun statFree(f: File): String = runCatching {
        val s = StatFs(f.path); "${(s.availableBytes).mb()} MB"
    }.getOrDefault("?")

    private fun Long.mb() = this / (1024 * 1024)
}
