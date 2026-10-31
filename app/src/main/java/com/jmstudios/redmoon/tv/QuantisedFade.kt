/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import kotlin.math.roundToInt

/** Elapsed-time interpolation: never replays missed steps or emits duplicate integer values. */
object QuantisedFade {
    fun value(start: Int, target: Int, duration: Long, elapsed: Long): Int {
        require(start in 0..100 && target in 0..100 && duration in 0..888000)
        val fraction = if (duration == 0L) 1.0 else (elapsed.toDouble() / duration).coerceIn(0.0, 1.0)
        return (start + (target - start) * fraction).roundToInt()
    }
    fun nextDelay(start: Int, target: Int, duration: Long, elapsed: Long): Long {
        var low = elapsed + 1; var high = duration
        val current = value(start, target, duration, elapsed)
        while (low < high) {
            val middle = (low + high) / 2
            if (value(start, target, duration, middle) == current) low = middle + 1 else high = middle
        }
        return maxOf(500L, low - elapsed)
    }
}
