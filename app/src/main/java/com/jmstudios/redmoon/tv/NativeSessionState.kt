/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

enum class NativeSessionState { IDLE, LIVE_SCHEDULE, PAUSED, WRITING, MANUAL_HOLD, PROBE, RECOVERY }
fun nativeSessionState(record: RecoveryRecord?, liveSession: String?, enabled: Boolean,
                       manualHold: Boolean, probe: Boolean, inFlight: Boolean = false): NativeSessionState = when {
    probe && liveSession != null -> NativeSessionState.PROBE
    record?.pending != null && record.session == liveSession && inFlight -> NativeSessionState.WRITING
    record?.pending != null -> NativeSessionState.RECOVERY
    manualHold && (record == null || record.kind == "schedule") -> NativeSessionState.MANUAL_HOLD
    record == null -> if (enabled && liveSession != null) NativeSessionState.LIVE_SCHEDULE else NativeSessionState.IDLE
    record.session != liveSession -> if (record.kind == "schedule") NativeSessionState.PAUSED else NativeSessionState.RECOVERY
    record.kind == "probe" -> NativeSessionState.RECOVERY
    enabled -> NativeSessionState.LIVE_SCHEDULE
    else -> NativeSessionState.PAUSED
}
