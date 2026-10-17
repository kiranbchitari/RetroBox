package com.retrobox.tv.emulator

import com.retrobox.tv.util.ErrorLog
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the emulation thread. UI code never calls the core directly: it posts commands, so opening
 * menus or changing UI state cannot stall or corrupt emulation.
 */
class EmulationSession(
    private val core: EmulatorCore,
    audioOut: AudioOut?,
    private val onFrame: () -> Unit,
) {
    @Volatile private var audio: AudioOut? = audioOut
    private var audioSwapped = false   // emulation thread only
    private val commands = ConcurrentLinkedQueue<(EmulatorCore) -> Unit>()
    @Volatile private var running = false
    @Volatile var paused = true
        private set
    private var thread: Thread? = null
    private val stopped = CountDownLatch(1)

    @Volatile var lastFps = 0.0
        private set

    /** [FastForward.NORMAL], a multiple (2, 3, 4) or [FastForward.MAX]. Sound is cut while faster than normal. */
    @Volatile var speed: Int = FastForward.NORMAL

    /** Auto-fire buttons (RetroPad bit mask) for every port; see [Turbo]. */
    @Volatile var turboMask: Int = 0

    private val held = java.util.concurrent.atomic.AtomicIntegerArray(MAX_PORTS)
    private val sent = IntArray(MAX_PORTS)              // emulation thread only: what the core last got with turbo applied
    private val turboHeldFrames = LongArray(MAX_PORTS)  // emulation thread only

    /**
     * The buttons [port] holds now. Any thread. Goes to the core at once (no added latency); while turbo buttons are
     * held the emulation thread re-sends the pulsed mask every frame.
     */
    fun setInput(port: Int, mask: Int) {
        if (port !in 0 until MAX_PORTS) return
        val prev = held.getAndSet(port, mask)
        if (prev == mask) return   // stick jitter / key repeat: the host latch would otherwise re-press a pulsing turbo button
        // Turbo buttons that were already held are pulsed by the emulation thread; only new presses go through now.
        core.setInput(port, mask and (turboMask and prev).inv())
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "emulation").also { it.priority = Thread.MAX_PRIORITY - 1; it.start() }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    /** Runs [block] on the emulation thread (e.g. save state, reset). Result delivered via the lambda's own callback. */
    fun post(block: (EmulatorCore) -> Unit) { commands.add(block) }

    /** Switches to a new audio output (e.g. another buffer size) on the emulation thread; the old one is released there. */
    fun replaceAudio(next: AudioOut?) {
        post { val old = audio; audio = next; old?.release(); audioSwapped = true }
    }

    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var abandoned = false

    private fun releaseOnce() {
        if (released.compareAndSet(false, true)) { audio?.release(); core.close() }
    }

    /**
     * Stops the thread, then closes the core on the caller's thread. Safe to call more than once. If the thread is still
     * busy after [timeoutMs] (a slow final save, a core stuck in a frame) the core is left to that thread to close when it
     * ends: closing it underneath would crash it and lose the save it is writing.
     */
    fun stop(timeoutMs: Long = 3000) {
        running = false
        if (stopped.await(timeoutMs, TimeUnit.MILLISECONDS)) { releaseOnce(); return }
        ErrorLog.w("emu", "Emulation thread did not stop within ${timeoutMs}ms; it will close the core when it finishes")
        abandoned = true
        if (stopped.count == 0L) releaseOnce()
    }

    /** Emulation thread: sends each port's turbo-pulsed mask when it differs from what the core has. */
    private fun applyTurbo() {
        val t = turboMask
        for (p in 0 until MAX_PORTS) {
            val h = held.get(p)
            if (h and t == 0) { turboHeldFrames[p] = 0; if (sent[p] != h) { sent[p] = h; if (t != 0) core.setInput(p, h) }; continue }
            val m = Turbo.apply(h, t, turboHeldFrames[p]++)
            if (m != sent[p]) { sent[p] = m; core.setInput(p, m) }
        }
    }

    private fun loop() {
        val frameNs = (1_000_000_000.0 / core.av.fps.coerceIn(20.0, 120.0)).toLong()
        val buf = ShortArray(8192 * 2)
        var next = System.nanoTime()
        var fpsWindowStart = next
        var fpsFrames = 0
        var wasPaused = true
        var wasFast = false
        try {
            while (running) {
                while (true) { (commands.poll() ?: break).invoke(core) }
                if (audioSwapped) { audioSwapped = false; if (!wasPaused) audio?.play() }
                if (paused) {
                    if (!wasPaused) { audio?.pause(); wasPaused = true }
                    Thread.sleep(10)
                    next = System.nanoTime()
                    continue
                }
                if (wasPaused) { if (!wasFast) audio?.play(); wasPaused = false; next = System.nanoTime(); fpsWindowStart = next; fpsFrames = 0 }

                applyTurbo()
                if (core.runFrame()) onFrame()
                val n = core.readAudio(buf)
                val fast = speed != FastForward.NORMAL
                if (fast != wasFast) { if (fast) audio?.pause() else audio?.play(); wasFast = fast }   // play() fades back in
                if (n > 0 && !fast) audio?.write(buf, n)

                // Pace to the core's frame rate (or a multiple of it); audio blocking handles slow drift, this stops us running ahead.
                next += if (fast) FastForward.frameNs(core.av.fps, speed) else frameNs
                val now = System.nanoTime()
                val ahead = next - now
                if (ahead > 1_000_000) Thread.sleep(ahead / 1_000_000, (ahead % 1_000_000).toInt())
                else if (ahead < -100_000_000) next = now   // fell far behind (slow device / paused by OS): resync, don't fast-forward

                fpsFrames++
                if (now - fpsWindowStart >= 1_000_000_000L) {
                    lastFps = fpsFrames * 1e9 / (now - fpsWindowStart)
                    fpsFrames = 0; fpsWindowStart = now
                }
            }
        } catch (t: Throwable) {
            ErrorLog.e("emu", "Emulation loop failed", t)
        } finally {
            // Run commands queued just before stop() (e.g. the final cartridge save) while we still own the core.
            runCatching { while (true) { (commands.poll() ?: break).invoke(core) } }
            stopped.countDown()
            if (abandoned) releaseOnce()
        }
    }

    companion object { const val MAX_PORTS = 2 }
}
