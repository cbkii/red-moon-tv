/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

enum class WritePermission { ALLOW, BLOCK, RELEASE_MANUAL_HOLD }

/** Recalculation/wake is never authorisation to clear a native failure or recovery. */
object NativeWriteGate {
    fun decide(blocked: Boolean, manualUntil: Long?, now: Long, recovery: Boolean): WritePermission = when {
        recovery -> WritePermission.BLOCK
        manualUntil != null && now < manualUntil -> WritePermission.BLOCK
        manualUntil != null -> WritePermission.RELEASE_MANUAL_HOLD
        blocked -> WritePermission.BLOCK
        else -> WritePermission.ALLOW
    }
}
