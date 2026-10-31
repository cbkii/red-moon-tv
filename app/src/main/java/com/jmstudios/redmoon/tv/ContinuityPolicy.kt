/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

enum class DisplayRunState { DISABLED, STARTING, ACTIVE, STANDBY, MANUAL_HOLD, RETRY_WAIT, QUALIFICATION_REQUIRED, RECOVERY_REQUIRED, ACCESS_REQUIRED }
enum class ScheduleRecovery { EMPTY, ADOPT, NEW_BOOT, CONTEXT_CHANGED, MANUAL_CHANGE, BLOCK }

/** Boot is a distinct epoch; a same-boot source change must never restore the old source. */
object ContinuityPolicy {
    private val boot = Regex(":boot:([^:]+)")
    fun recovery(record: RecoveryRecord?, current: Int, context: String?, identity: String): ScheduleRecovery {
        if (record == null) return ScheduleRecovery.EMPTY
        if (record.kind != "schedule" || record.pending != null || context.isNullOrBlank() ||
            record.identity.isBlank() || record.identity != identity) return ScheduleRecovery.BLOCK
        val before = boot.find(record.context)?.groupValues?.get(1)
        val after = boot.find(context)?.groupValues?.get(1)
        if (before != null && after != null && before != after) return ScheduleRecovery.NEW_BOOT
        if (record.context != context) return ScheduleRecovery.CONTEXT_CHANGED
        if (record.confirmed != current) return ScheduleRecovery.MANUAL_CHANGE
        return ScheduleRecovery.ADOPT
    }
}

/** Retry only readiness/read failures. Unknown writes and access failures are never replayed. */
class ReadinessRetry {
    private val delays = longArrayOf(1000, 2000, 5000, 10000, 30000)
    var attempts = 0; private set
    fun next(pendingWrite: Boolean, access: Boolean, transient: Boolean): Long? {
        if (pendingWrite || access || !transient || attempts == delays.size) return null
        return delays[attempts++]
    }
    fun reset() { attempts = 0 }
}

class NativeAccessRequired(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Catch-up is a lifecycle event, independent of whether a warmth window exists. */
fun startupCatchUp(firstOrWake: Boolean, nativeNeedsStart: Boolean, brightnessEnabled: Boolean): Boolean =
    firstOrWake || (nativeNeedsStart && brightnessEnabled)
