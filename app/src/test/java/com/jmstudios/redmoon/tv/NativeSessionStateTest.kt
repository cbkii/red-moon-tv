package com.jmstudios.redmoon.tv
import org.junit.Assert.*
import org.junit.Test
class NativeSessionStateTest {
    private val record = RecoveryRecord(66, 60, null, "boot:input:hdr", "schedule", "live")
    @Test fun temporaryAutomationOwnsTheWorkerWithoutEnablingSavedScheduling() {
        assertFalse(nativeWorkerBusy(false, false, false, false))
        assertTrue(nativeWorkerBusy(false, false, false, true))
        assertTrue(nativeWorkerBusy(false, false, true, false))
        assertTrue(nativeWorkerBusy(false, true, false, false))
        assertTrue(nativeWorkerBusy(true, false, false, false))
        assertEquals(NativeSessionState.LIVE_SCHEDULE, nativeSessionState(record, "live", true, false, false))
    }
    @Test fun liveAndPausedSchedulesAreNotInterruptedRecovery() {
        assertEquals(NativeSessionState.LIVE_SCHEDULE, nativeSessionState(record, "live", true, false, false))
        assertEquals(NativeSessionState.PAUSED, nativeSessionState(record, "live", false, false, false))
        assertEquals(NativeSessionState.PAUSED, nativeSessionState(record, null, false, false, false))
    }
    @Test fun pendingWritesNeedRecoveryUnlessActuallyInFlightWithCurrentOwner() {
        val pending = record.copy(pending = 59)
        assertEquals(NativeSessionState.WRITING, nativeSessionState(pending, "live", true, false, false, true))
        assertEquals(NativeSessionState.RECOVERY, nativeSessionState(pending, "live", true, false, false))
        assertEquals(NativeSessionState.RECOVERY, nativeSessionState(pending, null, true, true, false))
    }
    @Test fun manualHoldSurvivesProcessLossButCannotMaskAnInterruptedProbe() {
        assertEquals(NativeSessionState.MANUAL_HOLD, nativeSessionState(record, null, true, true, false))
        assertEquals(NativeSessionState.RECOVERY, nativeSessionState(record.copy(kind = "probe"), null, true, true, false))
    }
}
