/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import com.jmstudios.redmoon.Profile

/** Direct Boot-safe TV window. No legacy Config, profiles, EventBus or brightness side effects. */
class TvWarmthOverlay(context: Context) : View(context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var added = false
    var profile = Profile(40, 0, 0, false)
        set(value) {
            require(value.color in 0..100 && value.intensity in 0..100 && value.dimLevel == 0 && !value.lowerBrightness)
            val changed = field != value
            field = value
            if (value.isOff) close() else {
                if (!added || !isAttachedToWindow) {
                    // isAttachedToWindow can lag addView until the next main-loop traversal.
                    if (!added) { windows.addView(this, params); added = true }
                }
                if (changed) invalidate()
            }
        }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT)
    fun attached() = added && isAttachedToWindow
    fun repair() {
        if (!profile.isOff && !isAttachedToWindow) { close(); profile = profile }
    }
    fun close() {
        if (added) {
            try { windows.removeViewImmediate(this) } catch (_: IllegalArgumentException) { /* Already detached. */ }
            finally { added = false }
        }
    }
    override fun onDraw(canvas: Canvas) { if (!profile.isOff) canvas.drawColor(profile.filterColor) }
}
