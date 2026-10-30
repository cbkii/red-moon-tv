/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvSetupStateTest {
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
