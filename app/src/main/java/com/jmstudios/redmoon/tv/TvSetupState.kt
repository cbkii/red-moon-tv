/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

enum class TvSetupStep { PROBING, RECOVERY, CHECKING, CONNECT, READING, FAILED, READ_ONLY, PROBE, CONFIRM, READY }

/** A killed read cannot leave the next launch showing an operation that no longer exists. */
fun nativeReadState(currentSource: Boolean, stored: String, serviceActive: Boolean): String =
    if (!currentSource || (stored == "reading" && !serviceActive)) "not_checked" else stored

/** UI setup is a view of actual read/probe state; opening a page never qualifies hardware. */
fun tvSetupStep(identityReady: Boolean, recovery: Boolean, readState: String, level: Int?,
                writeContext: Boolean, probeRestored: Boolean, qualified: Boolean, probeActive: Boolean = false): TvSetupStep = when {
    probeActive -> TvSetupStep.PROBING
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
    data class Value(val level: Int, val writeContext: Boolean, val root: Boolean, val at: Long, val identity: String?)
    @Volatile var value: Value? = null
        private set
    fun record(level: Int, context: Boolean, root: Boolean, identity: String? = null) { value = Value(level, context, root, System.currentTimeMillis(), identity) }
    fun clear() { value = null }
}
