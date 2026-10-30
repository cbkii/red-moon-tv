/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

enum class TvSetupStep { RECOVERY, CHECKING, CONNECT, READING, FAILED, READ_ONLY, PROBE, CONFIRM, READY }

/** UI setup is a view of actual read/probe state; opening a page never qualifies hardware. */
fun tvSetupStep(identityReady: Boolean, recovery: Boolean, readState: String, level: Int?,
                writeContext: Boolean, probeRestored: Boolean, qualified: Boolean): TvSetupStep = when {
    recovery -> TvSetupStep.RECOVERY
    !identityReady -> TvSetupStep.CHECKING
    readState == "reading" -> TvSetupStep.READING
    readState == "failed" -> TvSetupStep.FAILED
    readState != "ready" || level == null || level !in 0..100 -> TvSetupStep.CONNECT
    !writeContext -> TvSetupStep.READ_ONLY
    qualified -> TvSetupStep.READY
    probeRestored -> TvSetupStep.CONFIRM
    else -> TvSetupStep.PROBE
}

/** In-memory only, including during fades. No disk write or polling in the native hot path. */
object NativeSnapshot {
    data class Value(val level: Int, val writeContext: Boolean, val root: Boolean)
    @Volatile var value: Value? = null
        private set
    fun record(level: Int, context: Boolean, root: Boolean) { value = Value(level, context, root) }
    fun clear() { value = null }
}
