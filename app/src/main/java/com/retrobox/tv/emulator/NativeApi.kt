package com.retrobox.tv.emulator

/** JNI surface of `libretrobox_host.so`. An interface so the core wrapper can be unit-tested with a fake. */
interface NativeApi {
    fun loadCore(path: String, systemDir: String, saveDir: String): String?   // null = ok, else error text
    fun loadGame(path: String): String?
    fun unloadGame()
    fun unloadCore()
    fun coreInfo(): Array<String>                  // name, version, extensions, needFullpath("1"/"0")
    fun avInfo(): DoubleArray                      // baseW, baseH, aspect, fps, sampleRate, maxW, maxH
    fun runFrame(): Boolean                        // true when a new video frame was produced
    fun reset()
    fun readAudio(out: ShortArray): Int            // stereo frames written into out
    fun setInput(port: Int, mask: Int, lx: Int, ly: Int, rx: Int, ry: Int)
    fun setOption(key: String, value: String)
    fun saveState(): ByteArray?
    fun loadState(data: ByteArray): Boolean
    fun saveRam(): ByteArray?
    fun loadSaveRam(data: ByteArray): Boolean
    fun drainMessages(): String?
    fun stats(): LongArray                         // frames, avgRunUs, maxRunUs, audioFrames, pixelFormat, lastFrameW, lastFrameH
    fun resetStats()
    fun snapshot(): IntArray?                      // [w, h, ARGB pixels...] of the newest video frame, or null

    // Renderer (GL thread only)
    fun glInit()
    fun glResize(w: Int, h: Int)
    fun glDraw()
    fun glRelease()
    fun setDisplay(mode: Int, filter: Int, shader: Int, safePermille: Int)
    fun setRenderTiming(on: Boolean)
    fun renderStats(): LongArray                   // draws, avg us, max us, avg us between draws in the window since the last call (then reset)
    fun shaderStatus(): String                     // "" or one line per shader this GPU could not build
    fun glInfo(): String                           // renderer | version | fragment highp yes/no (of the game's own GL context)
}

object NativeHost : NativeApi {
    init { System.loadLibrary("retrobox_host") }

    external override fun loadCore(path: String, systemDir: String, saveDir: String): String?
    external override fun loadGame(path: String): String?
    external override fun unloadGame()
    external override fun unloadCore()
    external override fun coreInfo(): Array<String>
    external override fun avInfo(): DoubleArray
    external override fun runFrame(): Boolean
    external override fun reset()
    external override fun readAudio(out: ShortArray): Int
    external override fun setInput(port: Int, mask: Int, lx: Int, ly: Int, rx: Int, ry: Int)
    external override fun setOption(key: String, value: String)
    external override fun saveState(): ByteArray?
    external override fun loadState(data: ByteArray): Boolean
    external override fun saveRam(): ByteArray?
    external override fun loadSaveRam(data: ByteArray): Boolean
    external override fun drainMessages(): String?
    external override fun stats(): LongArray
    external override fun resetStats()
    external override fun snapshot(): IntArray?
    external override fun glInit()
    external override fun glResize(w: Int, h: Int)
    external override fun glDraw()
    external override fun glRelease()
    external override fun setDisplay(mode: Int, filter: Int, shader: Int, safePermille: Int)
    external override fun setRenderTiming(on: Boolean)
    external override fun renderStats(): LongArray
    external override fun shaderStatus(): String
    external override fun glInfo(): String
}
