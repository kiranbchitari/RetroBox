package com.retrobox.tv.emulator

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.retrobox.tv.util.ErrorLog

/** Blocking PCM sink. A blocking write doubles as frame pacing/drift correction for the emulation thread. */
class AudioOut private constructor(private val track: AudioTrack, val sampleRate: Int, val bufferFrames: Int) {
    @Volatile private var playing = false
    private val gainStage = GainStage(sampleRate * FADE_IN_MS / 1000)

    /** Output level 0..1 (volume, or 0 for sound off). Takes effect from the next write. */
    var gain: Float
        get() = gainStage.gain
        set(v) { gainStage.gain = v }

    /** Audio frames discarded because the output stopped draining, and how many times that happened. */
    @Volatile var droppedFrames = 0L
        private set
    @Volatile var stallEvents = 0
        private set
    private var stalledUntilNs = 0L

    /**
     * Writes interleaved stereo PCM. Waits (up to [MAX_WAIT_MS]) for buffer space, which paces emulation to the
     * audio clock. If the sink stops draining (disconnected HDMI/ARC, stuck HAL) we drop samples instead of freezing the game.
     */
    fun write(buf: ShortArray, frames: Int) {
        if (!playing) return
        gainStage.process(buf, frames)
        val total = frames * 2
        var off = 0
        if (Build.VERSION.SDK_INT < 23) {
            while (off < total) { val n = track.write(buf, off, total - off); if (n <= 0) break; off += n }
            return
        }
        var deadline = System.nanoTime() + MAX_WAIT_MS * 1_000_000L
        val stalled = System.nanoTime() < stalledUntilNs
        if (stalled) deadline = 0L   // already known-stuck: don't wait, just try once
        while (off < total) {
            val n = track.write(buf, off, total - off, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) break
            off += n
            if (off >= total) break
            if (System.nanoTime() >= deadline) {
                droppedFrames += (total - off) / 2
                if (!stalled) { stallEvents++; ErrorLog.w("audio", "Audio output is not draining; dropping samples (stall #$stallEvents)") }
                stalledUntilNs = System.nanoTime() + STALL_RETRY_MS * 1_000_000L
                return
            }
            try { Thread.sleep(2) } catch (_: InterruptedException) { return }
        }
        if (off >= total && stalled) stalledUntilNs = 0L   // sink recovered
    }

    fun play() { if (!playing) { gainStage.restartFade(); runCatching { track.play(); playing = true } } }
    fun pause() { if (playing) { playing = false; runCatching { track.pause(); track.flush() } } }

    val underruns: Int get() = if (Build.VERSION.SDK_INT >= 24) runCatching { track.underrunCount }.getOrDefault(0) else 0

    fun release() { playing = false; runCatching { track.stop() }; runCatching { track.release() } }

    companion object {
        private const val MAX_WAIT_MS = 100L
        private const val STALL_RETRY_MS = 1000L
        private const val FADE_IN_MS = 30

        /** @param bufferMs total buffering; larger = safer on slow CPUs, smaller = lower latency. */
        fun create(sampleRate: Int, bufferMs: Int): AudioOut? = try {
            val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) throw IllegalStateException("getMinBufferSize=$minBytes")
            val wantBytes = sampleRate * bufferMs / 1000 * 4
            val bytes = maxOf(minBytes, wantBytes)
            val track = if (Build.VERSION.SDK_INT >= 23) {
                val b = AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_GAME)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bytes)
                if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                b.build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, bytes, AudioTrack.MODE_STREAM)
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); throw IllegalStateException("AudioTrack not initialized") }
            AudioOut(track, sampleRate, bytes / 4)
        } catch (t: Throwable) {
            ErrorLog.e("audio", "Audio initialization failed (rate=$sampleRate)", t)
            null
        }
    }
}
