package com.retrobox.tv

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import com.retrobox.tv.emulator.CoreResult
import com.retrobox.tv.emulator.EmulatorCore
import com.retrobox.tv.emulator.NativeHost
import com.retrobox.tv.emulator.PadButton
import com.retrobox.tv.emulator.Systems
import com.retrobox.tv.settings.ScaleMode
import com.retrobox.tv.settings.ShaderMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws a real game frame through the app's GLES2 renderer with every "Look" on this device's GPU (an off-screen
 * pbuffer, so no window is needed). On the Mi TV this answers whether the Mali-450 can build each shader.
 */
class GlLooksDeviceTest {
    private val w = 320
    private val h = 240
    private var core: EmulatorCore? = null
    private val egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var ctx = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE

    @After fun tearDown() {
        if (ctx != EGL14.EGL_NO_CONTEXT) {
            NativeHost.glRelease()
            EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(egl, surface)
            EGL14.eglDestroyContext(egl, ctx)
        }
        core?.close()
        TestRoms.clean()
    }

    private fun makeContext() {
        val v = IntArray(2)
        assertTrue(EGL14.eglInitialize(egl, v, 0, v, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 5, EGL14.EGL_GREEN_SIZE, 6, EGL14.EGL_BLUE_SIZE, 5, EGL14.EGL_NONE,
        )
        assertTrue("no GLES2 pbuffer config", EGL14.eglChooseConfig(egl, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0)
        ctx = EGL14.eglCreateContext(egl, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(egl, configs[0], intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        assertTrue("eglMakeCurrent failed", EGL14.eglMakeCurrent(egl, surface, surface, ctx))
    }

    /** Share of pixels in the pbuffer that are not black. */
    private fun litShare(): Double {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        var lit = 0
        for (i in 0 until w * h) {
            val r = buf.get(i * 4).toInt() and 0xFF; val g = buf.get(i * 4 + 1).toInt() and 0xFF; val b = buf.get(i * 4 + 2).toInt() and 0xFF
            if (r + g + b > 24) lit++
        }
        return lit.toDouble() / (w * h)
    }

    @Test fun everyLookBuildsAndDrawsTheGame() {
        // A NES frame with A held: red background over the checkerboard, so a working draw is clearly not black.
        val nes = Systems.byId("nes")!!
        core = (TestRoms.factory().create(nes, nes.defaultCore!!, TestRoms.copy("rb-testrom.nes")) as CoreResult.Ok).value
        core!!.setInput(0, TestRoms.bit(PadButton.A.retroId))
        repeat(30) { core!!.runFrame() }

        makeContext()
        NativeHost.glInit()
        NativeHost.glResize(w, h)
        Log.i("RetroBoxDeviceTest", "GL: ${NativeHost.glInfo()}")

        val shares = ShaderMode.entries.associateWith { look ->
            NativeHost.setDisplay(ScaleMode.STRETCH.code, 0, look.code, 0)
            NativeHost.glDraw()
            assertEquals("GL error drawing ${look.label}", GLES20.GL_NO_ERROR, GLES20.glGetError())
            litShare()
        }
        Log.i("RetroBoxDeviceTest", "lit share per look: " + shares.entries.joinToString { "${it.key.label}=${"%.2f".format(it.value)}" })
        assertEquals("shaders this GPU could not build", "", NativeHost.shaderStatus())
        shares.forEach { (look, share) -> assertTrue("${look.label} drew a mostly black picture ($share)", share > 0.2) }
    }
}
