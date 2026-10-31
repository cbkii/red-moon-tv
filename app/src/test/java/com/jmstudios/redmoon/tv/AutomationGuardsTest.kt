package com.jmstudios.redmoon.tv

import org.junit.Assert.*
import org.junit.Test

class AutomationGuardsTest {
    private fun request() = AutomationContract.parse(mapOf("api_version" to 1, "command" to "warmth_set", "value" to 20), "press")
    @Test fun shortcutCapabilityCannotChangeCommandArgumentsOrScopeAndRevokesWithMaster() {
        val r = request(); val token = ShortcutCapability.sign("secret", r)
        assertEquals(token, ShortcutCapability.sign("secret", r.copy(id = "new-press")))
        listOf(r.copy(value = 99), r.copy(command = "brightness_set"), r.copy(scope = "both"),
            r.copy(fade = 10), r.copy(expiry = "until_resumed")).forEach {
            assertNotEquals(token, ShortcutCapability.sign("secret", it))
        }
        assertNotEquals(token, ShortcutCapability.sign("rotated", r))
        assertFalse(token.contains("secret"))
    }
    @Test fun retiredIdsRemainRejectedAfterResultEvictionAndSerialization() {
        val seen = AutomationReplayGuard()
        repeat(500) { seen.add("request-$it") }
        val restored = AutomationReplayGuard(seen.encode())
        repeat(500) { assertTrue(restored.contains("request-$it")) }
        assertTrue(seen.encode().length <= 43692)
        assertFalse(AutomationReplayGuard().contains("request-0"))
    }
    @Test fun delayedBrightnessCannotUndoANewerPauseOrAdjustment() {
        val order = AutomationOrder(); val old = order.admit("brightness")
        order.invalidate("brightness")
        assertFalse(order.current(old))
        val next = order.admit("brightness")
        order.snapshot("both") // Read-only status must not supersede a pending button press.
        assertTrue(order.current(next)); assertFalse(order.current(old))
    }
    @Test fun warmthDoesNotCancelAnIndependentDeferredBrightnessRequest() {
        val order = AutomationOrder(); val brightness = order.admit("brightness")
        order.admit("warmth"); assertTrue(order.current(brightness))
        val both = order.admit("both"); order.admit("warmth"); assertFalse(order.current(both))
    }
    @Test fun expiryIsCheckedAtAdmissionEvenBeforeTheBoundaryAlarmRuns() {
        assertTrue(temporaryActive(0, 100)); assertTrue(temporaryActive(101, 100))
        assertFalse(temporaryActive(100, 100)); assertFalse(temporaryActive(99, 100))
    }
    @Test fun aggregateFailureNamesTheFailedScopeRegardlessOfCompletionOrder() {
        assertEquals("NATIVE_FAILED", AutomationResult.aggregateError(linkedMapOf("brightness" to "failed", "warmth" to "cancelled")))
        assertEquals("NATIVE_FAILED", AutomationResult.aggregateError(linkedMapOf("warmth" to "cancelled", "brightness" to "failed")))
        assertEquals("OVERLAY_FAILED", AutomationResult.aggregateError(linkedMapOf("warmth" to "failed", "brightness" to "cancelled")))
        assertEquals("PARTIAL_APPLICATION", AutomationResult.aggregateError(mapOf("brightness" to "failed", "warmth" to "applied")))
    }
    @Test fun failureCannotBeReportedAsExecutionOfANewerRequest() {
        assertEquals("failed", automationFailureState(2, 2))
        assertEquals("rejected", automationFailureState(2, 3))
        assertEquals("failed", automationFailureState(null, 3))
    }
    @Test fun temporaryTargetsRetireOnFailureButNotSuccessfulApplicationOrStandby() {
        assertTrue(retireTemporary("failed")); assertTrue(retireTemporary("cancelled")); assertTrue(retireTemporary("rejected"))
        assertFalse(retireTemporary("applied")); assertFalse(retireTemporary("running"))
    }
    @Test fun serviceEpochDetectsInterruptedWorkEvenInTheSameProcess() {
        assertFalse(executionInterrupted("p", "p", "", "new")) // Accepted; not dispatched yet.
        assertFalse(executionInterrupted("p", "p", "new", "new"))
        assertTrue(executionInterrupted("p", "p", "old", "new"))
        assertTrue(executionInterrupted("old-process", "new-process", "", "new"))
    }
}
