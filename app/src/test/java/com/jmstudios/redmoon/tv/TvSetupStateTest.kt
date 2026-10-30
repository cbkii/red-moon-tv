/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvSetupStateTest {
    @Test fun interruptedReadCanReconnectWhileCompletedIdleReadRemainsExportable() {
        assertEquals("not_checked", nativeReadState(true, "reading", false))
        assertEquals("reading", nativeReadState(true, "reading", true))
        assertEquals("ready", nativeReadState(true, "ready", false))
        assertEquals("not_checked", nativeReadState(false, "ready", false))
    }
    @Test fun liveProbeJournalIsExpectedButBecomesRecoveryAfterServiceLoss() {
        assertEquals(TvSetupStep.PROBING, tvSetupStep(true, true, "ready", 61, true, false, false, true))
        assertEquals(TvSetupStep.RECOVERY, tvSetupStep(true, true, "ready", 61, true, false, false, false))
    }
    @Test fun recoveryAlwaysWinsOverOldSuccess() {
        assertEquals(TvSetupStep.RECOVERY, tvSetupStep(true, true, "ready", 66, true, true, true))
    }
    @Test fun unverifiedRangeCannotOfferAProbeOrSchedule() {
        assertEquals(TvSetupStep.READ_ONLY, tvSetupStep(true, false, "ready", 66, false, true, true))
    }
    @Test fun failedReadDoesNotReuseLastConfirmedValue() {
        assertEquals(TvSetupStep.FAILED, tvSetupStep(true, false, "failed", 66, true, true, true))
    }
    @Test fun successfulReadOffersProbeThenConfirmationBeforeReady() {
        assertEquals(TvSetupStep.PROBE, tvSetupStep(true, false, "ready", 66, true, false, false))
        assertEquals(TvSetupStep.CONFIRM, tvSetupStep(true, false, "ready", 66, true, true, false))
        assertEquals(TvSetupStep.READY, tvSetupStep(true, false, "ready", 66, true, true, true))
    }
    @Test fun missingReadNeverAppearsConnected() {
        assertEquals(TvSetupStep.CONNECT, tvSetupStep(true, false, "ready", null, true, true, true))
    }
}
