/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import kotlin.math.roundToInt

data class WarmthState(val colour: Int, val strength: Int) {
    init { require(colour in 0..100 && strength in 0..100) }
}
/** Quantised elapsed-time transition, at most five updates/second and no idle ticker. */
class WarmthTransition(private val from: WarmthState, private val to: WarmthState, requestedMillis: Long) {
    init { require(requestedMillis in 0..888000) }
    val duration = if (from == to) 0L else requestedMillis
    fun valueAt(elapsed: Long): WarmthState {
        val fraction = if (duration == 0L) 1.0 else (elapsed.toDouble() / duration).coerceIn(0.0, 1.0)
        fun value(a: Int, b: Int) = (a + (b - a) * fraction).roundToInt().coerceIn(0, 100)
        return WarmthState(value(from.colour, to.colour), value(from.strength, to.strength))
    }
    fun nextDelay(elapsed: Long): Long {
        if (elapsed >= duration) return 200L
        val current = valueAt(elapsed)
        var low = elapsed + 1
        var high = duration
        while (low < high) {
            val middle = (low + high) / 2
            if (valueAt(middle) == current) low = middle + 1 else high = middle
        }
        return maxOf(200L, low - elapsed)
    }
}
