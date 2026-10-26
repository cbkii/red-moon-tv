/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** Pure transition executor; all service calls use its generation/cancellation and failure rules. */
class TransitionSession(
    private val fade: BrightnessFade,
    initial: Int,
    private val elapsed: () -> Long,
    private val cancelled: () -> Boolean,
    private val write: (Int) -> Unit
) {
    var lastApplied = initial
        private set
    private var finished = false
    fun tick(): Boolean {
        if (finished || cancelled()) { finished = true; return false }
        val time = elapsed()
        val next = fade.valueAt(time)
        try {
            if (next != lastApplied) { write(next); lastApplied = next }
        } catch (error: Exception) { finished = true; throw error }
        if (time >= fade.durationMillis) finished = true
        return !finished
    }
}
