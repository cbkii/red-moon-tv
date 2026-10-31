/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import java.util.concurrent.ScheduledThreadPoolExecutor

/** Bounded optional work, one pending item per key. Safety work always has admission. */
class ControllerQueue(private val executor: ScheduledThreadPoolExecutor,
                      private val failure: (Exception) -> Unit, private val settled: () -> Unit) {
    private val keys = mutableSetOf<String>()
    private val latest = mutableMapOf<String, () -> Unit>()
    @Volatile var count = 0
        private set
    @Synchronized fun submit(key: String, critical: Boolean = false, work: () -> Unit): Boolean {
        if (key in keys) {
            if (key == "transition") { latest[key] = work; return true }
            return false
        }
        if (!critical && count >= 3) return false
        keys.add(key); count++
        executor.execute {
            try { work() } catch (error: Exception) { if (error !is java.util.concurrent.CancellationException) failure(error) }
            finally {
                synchronized(this) {
                    keys.remove(key); count--
                    latest.remove(key)?.let { submit(key, true, it) }
                }
                settled()
            }
        }
        return true
    }
}
