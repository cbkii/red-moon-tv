/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** Shared terminal aggregation: a successful scope never hides another scope's failure. */
object AutomationResult {
    val active = setOf("accepted", "running")
    fun errorCode(state: String, part: String): String? = when (state) {
        "applied", "accepted", "running" -> null
        "partial" -> "PARTIAL_APPLICATION"
        "cancelled" -> "CANCELLED"
        "rejected" -> when (part) { "brightness" -> "BRIGHTNESS_REJECTED"; "warmth" -> "WARMTH_REJECTED"; else -> "REQUEST_REJECTED" }
        else -> when (part) { "brightness" -> "NATIVE_FAILED"; "warmth" -> "OVERLAY_FAILED"; else -> "DISPATCH_FAILED" }
    }
    fun aggregate(states: List<String>): String = when {
        states.isEmpty() || states.any { it in active } -> "running"
        states.all { it == "applied" } -> "applied"
        states.any { it == "applied" } -> "partial"
        states.any { it == "failed" } -> "failed"
        states.any { it == "rejected" } -> "rejected"
        else -> "cancelled"
    }
}
