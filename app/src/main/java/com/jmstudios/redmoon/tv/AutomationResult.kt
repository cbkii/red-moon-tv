/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** Shared terminal aggregation: a successful scope never hides another scope's failure. */
object AutomationResult {
    val active = setOf("accepted", "running")
    fun aggregate(states: List<String>): String = when {
        states.isEmpty() || states.any { it in active } -> "running"
        states.all { it == "applied" } -> "applied"
        states.any { it == "applied" } -> "partial"
        states.any { it == "failed" } -> "failed"
        states.any { it == "rejected" } -> "rejected"
        else -> "cancelled"
    }
}
