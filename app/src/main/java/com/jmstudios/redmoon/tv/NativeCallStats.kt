/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** In-memory only: no hot-path preferences, logcat, sampling thread or background polling. */
object NativeCallStats {
    private var calls = 0L
    private var writes = 0L
    private var errors = 0L
    private var totalMs = 0L
    private var maximumMs = 0L
    private var last = "none"
    @Synchronized fun record(backend: String, operation: String, elapsed: Long, failed: Boolean) {
        calls++; if (operation == "write") writes++; if (failed) errors++
        totalMs += elapsed; maximumMs = maxOf(maximumMs, elapsed); last = "$backend/$operation: ${elapsed}ms"
    }
    @Synchronized fun values(): Map<String, Any> = mapOf("calls" to calls, "writes" to writes, "errors" to errors,
        "total_ms" to totalMs, "maximum_ms" to maximumMs, "last" to last)
    @Synchronized fun snapshot() = "calls=$calls; writes=$writes; errors=$errors; totalMs=$totalMs; maxMs=$maximumMs; last=$last"
}
