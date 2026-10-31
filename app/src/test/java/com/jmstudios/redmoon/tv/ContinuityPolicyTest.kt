/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import org.junit.Assert.*
import org.junit.Test

class ContinuityPolicyTest {
    private val context = "root:device:boot:id=one:picture=1:input=HDMI1:hdr=0"
    private val record = RecoveryRecord(66, 60, null, context, "schedule", "old", "qualified")
    @Test fun confirmedSameEpochIsAdoptable() {
        assertEquals(ScheduleRecovery.ADOPT, ContinuityPolicy.recovery(record, 60, context, "qualified"))
        assertEquals(ScheduleRecovery.MANUAL_CHANGE, ContinuityPolicy.recovery(record, 55, context, "qualified"))
    }
    @Test fun newBootIsFreshBaselineAndSameBootInputIsSeparate() {
        assertEquals(ScheduleRecovery.NEW_BOOT, ContinuityPolicy.recovery(record, 90, context.replace("id=one", "id=two"), "qualified"))
        assertEquals(ScheduleRecovery.CONTEXT_CHANGED, ContinuityPolicy.recovery(record, 60, context.replace("HDMI1", "HDMI2"), "qualified"))
    }
    @Test fun unknownWritesProbesAndChangedBackendCannotBeAdopted() {
        for (unsafe in listOf(record.copy(pending = 55), record.copy(kind = "probe"), record.copy(identity = "")))
            assertEquals(ScheduleRecovery.BLOCK, ContinuityPolicy.recovery(unsafe, 60, context, "qualified"))
        assertEquals(ScheduleRecovery.BLOCK, ContinuityPolicy.recovery(record, 60, context, "different"))
        assertEquals(ScheduleRecovery.BLOCK, ContinuityPolicy.recovery(record, 60, null, "qualified"))
    }
    @Test fun retryBudgetNeverReplaysUnknownWriteOrPermissionFailure() {
        val retry = ReadinessRetry()
        assertNull(retry.next(true, false, true)); assertNull(retry.next(false, true, true))
        assertNull(retry.next(false, false, false)); assertEquals(0, retry.attempts)
        assertEquals(listOf(1000L, 2000L, 5000L, 10000L, 30000L), (1..5).map { retry.next(false, false, true) })
        assertNull(retry.next(false, false, true)); retry.reset()
        assertEquals(1000L, retry.next(false, false, true))
    }
}
