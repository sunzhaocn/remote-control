package com.simplerc.app.webrtc

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.hardware.display.DisplayManager
import android.view.Surface
import android.view.SurfaceHolder
import org.webrtc.SurfaceViewRenderer

/** A source-rate hint, not a forced display-mode switch; drawing is paced on the EGL looper. */
class RendererFrameRateHint(private val renderer: SurfaceViewRenderer, private var fps: Int) : SurfaceHolder.Callback {
    private val displays=renderer.context.getSystemService(DisplayManager::class.java)
    private val listener=object:DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId:Int)=Unit
        override fun onDisplayRemoved(displayId:Int)=Unit
        override fun onDisplayChanged(displayId:Int) {
            if(renderer.display?.displayId==displayId)updateRefreshRate()
        }
    }
    private fun updateRefreshRate() {
        org.webrtc.SimpleRcVideoTuning.current().displayRefreshRateHz=
            renderer.display?.refreshRate?.toDouble()?.takeIf {it.isFinite() && it in 20.0..360.0} ?: 0.0
    }
    fun refresh() = updateRefreshRate()
    fun requestFps(value: Int) {
        if (fps == value) return
        fps = value
        apply(value.toFloat())
    }
    init {
        renderer.holder.addCallback(this)
        displays.registerDisplayListener(listener,Handler(Looper.getMainLooper()))
        apply(fps.toFloat())
    }
    private fun apply(rate: Float) {
        updateRefreshRate()
        if (Build.VERSION.SDK_INT >= 30) {
            val surface=renderer.holder.surface
            if(surface.isValid)runCatching { surface.setFrameRate(rate,Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
        }
    }
    override fun surfaceCreated(holder: SurfaceHolder) = apply(fps.toFloat())
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    fun close() {
        displays.unregisterDisplayListener(listener)
        renderer.holder.removeCallback(this)
        apply(0f)
    }
}
