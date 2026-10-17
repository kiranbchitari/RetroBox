package com.retrobox.tv.emulator

/**
 * Volume, mute and the short fade-in after every (re)start of playback, applied in place to interleaved stereo PCM.
 * The fade hides the click that a flushed, restarted AudioTrack otherwise makes when the game resumes from the pause menu.
 * Used from the emulation thread only; [gain] may be set from any thread.
 */
class GainStage(private val fadeFrames: Int) {
    @Volatile var gain = 1f
    private var fadePos = fadeFrames

    /** Next [process] call starts from silence and ramps up. */
    fun restartFade() { fadePos = 0 }

    fun process(buf: ShortArray, frames: Int) {
        val target = gain
        if (Math.abs(target - 1f) < 0.001f && fadePos >= fadeFrames) return   // nothing to do: leave the samples untouched
        if (target <= 0f) { java.util.Arrays.fill(buf, 0, frames * 2, 0); fadePos = minOf(fadeFrames, fadePos + frames); return }
        var i = 0
        while (i < frames) {
            val g = if (fadePos < fadeFrames) target * (fadePos.toFloat() / fadeFrames).also { fadePos++ } else target
            val l = (buf[2 * i] * g).toInt().coerceIn(-32768, 32767)
            val r = (buf[2 * i + 1] * g).toInt().coerceIn(-32768, 32767)
            buf[2 * i] = l.toShort(); buf[2 * i + 1] = r.toShort()
            i++
        }
    }
}
