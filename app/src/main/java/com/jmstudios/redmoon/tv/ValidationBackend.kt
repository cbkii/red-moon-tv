/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** Shell-only TESTING fault injection. Fixed failures; never substitutes fake successful hardware. */
class ValidationBackend(private val actual: BacklightBackend) : BacklightBackend by actual {
    override fun read(): Int {
        when (take("read_unavailable", "access_denied")) {
            "read_unavailable" -> throw IllegalStateException("Native service unavailable (ADB readiness experiment)")
            "access_denied" -> throw NativeAccessRequired("Root permission denied (ADB access experiment)")
        }
        return actual.read()
    }
    override fun write(value: Int) {
        actual.write(value)
        if (take("lost_write_reply") != null) error("ADB experiment: setter completed but its reply was lost; outcome unknown")
    }
    companion object {
        @Volatile private var next: String? = null
        @Synchronized fun arm(fault: String) { check(next == null); next = fault }
        @Synchronized fun clear() { next = null }
        @Synchronized private fun take(vararg options: String): String? = next?.takeIf { it in options }?.also { next = null }
    }
}
