/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import org.junit.Assert.*
import org.junit.Test

class AutomationContractTest {
    private fun parse(command: String, vararg extras: Pair<String, Any?>) = AutomationContract.parse(
        mapOf("api_version" to 1, "command" to command) + extras, "new-press")
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid request accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun everyCommandHasAValidStrictContract() {
        AutomationContract.commands.forEach { command ->
            val extra: Array<Pair<String, Any?>> = when (command) {
                "brightness_phase", "preset_apply" -> arrayOf("phase" to "DIM")
                "brightness_set", "warmth_set" -> arrayOf("value" to 10)
                "brightness_adjust", "warmth_adjust" -> arrayOf("delta" to -5)
                "warmth_enable" -> arrayOf("enabled" to true)
                "snooze" -> arrayOf("duration_seconds" to 1800)
                else -> emptyArray()
            }
            assertEquals(command, parse(command, *extra).command)
        }
    }
    @Test fun parserRejectsUnknownCommandsVersionsAndExtraneousPrivilegeArguments() {
        rejects { parse("native_write") }
        rejects { parse("brightness_pause", "api_version" to 2) }
        rejects { parse("brightness_pause", "backend" to "root") }
        rejects { parse("brightness_pause", "scope" to "both") }
        rejects { parse("brightness_pause", "request_id" to "id\ncommand") }
        rejects { parse("warmth_set", "value" to 10, "token" to "x".repeat(257)) }
    }
    @Test fun typedExtrasNeverCoerceStringsFloatsOrBooleanNumbers() {
        rejects { parse("brightness_set", "value" to "10") }
        rejects { parse("brightness_set", "value" to 10L) }
        rejects { parse("brightness_set", "value" to 10.0) }
        rejects { parse("brightness_set", "value" to true) }
        rejects { parse("warmth_enable", "enabled" to "true") }
        rejects { parse("warmth_enable", "enabled" to 1) }
        rejects { parse("warmth_set", "value" to null) }
    }
    @Test fun defaultExpiryUsesTheRelevantBoundaryAndNewPressHasNewId() {
        val r = parse("brightness_phase", "phase" to "DUSK")
        assertEquals("new-press", r.id); assertEquals("brightness", r.scope)
        assertEquals(12345L, AutomationContract.expiry(r, 1000, 12345))
        assertEquals("warmth", parse("warmth_set", "value" to 0).scope)
        assertEquals("both", parse("preset_apply", "phase" to "DIM").scope)
    }
    @Test fun expiryRequiresBoundedDurationOnlyWhenSelected() {
        rejects { parse("warmth_set", "value" to 10, "expiry" to "duration") }
        rejects { parse("warmth_set", "value" to 10, "duration_seconds" to 5) }
        rejects { parse("snooze", "duration_seconds" to 0) }
        rejects { parse("snooze", "duration_seconds" to 86401) }
        val r = parse("snooze", "duration_seconds" to 86400)
        assertEquals(86401000L, AutomationContract.expiry(r, 1000, 2000))
        assertEquals(0L, AutomationContract.expiry(parse("warmth_set", "value" to 10, "expiry" to "until_resumed"), 1, 2))
    }
    @Test fun allMandatoryArgumentsAndBoundsAreEnforced() {
        listOf("brightness_phase", "brightness_set", "brightness_adjust", "warmth_enable", "preset_apply", "snooze").forEach {
            rejects { parse(it) }
        }
        rejects { parse("brightness_phase", "phase" to "dim") }
        rejects { parse("warmth_adjust", "delta" to 0) }
        rejects { parse("brightness_adjust", "delta" to 21) }
        rejects { parse("brightness_set", "value" to 0) }
        rejects { parse("warmth_set", "value" to 101) }
        rejects { parse("warmth_set", "value" to 10, "fade_seconds" to 889) }
        rejects { parse("preset_apply", "phase" to "DAY", "scope" to "native") }
        assertEquals(888, parse("brightness_set", "value" to 80, "fade_seconds" to 888).fade)
    }
    @Test fun rapidRelativePressesAccumulateFromDesiredTargetWithinSavedBand() {
        var desired = 70
        repeat(10) { desired = AutomationContract.adjusted(desired, 2, 70..80) }
        assertEquals(80, desired)
        repeat(10) { desired = AutomationContract.adjusted(desired, -2, 70..80) }
        assertEquals(70, desired)
        assertEquals(0, AutomationContract.adjusted(2, -5, 0..100))
    }
    @Test fun temporaryDayTargetDoesNotBecomeANewSavedDayCap() {
        val p = BrightnessPolicy.defaults(); val day = p.settings[0]
        val temporary = day.copy(level = 75)
        val fade = BrightnessFade(p, temporary, 100, 1000, true, p.band(day))
        assertEquals(80, fade.valueAt(0))
        assertEquals(75, fade.valueAt(1000))
        assertEquals(80, p.settings[0].level)
    }
    @Test fun scopeAggregationWaitsForBothAndNeverHidesPartialFailure() {
        assertEquals("running", AutomationResult.aggregate(listOf("applied", "running")))
        assertEquals("partial", AutomationResult.aggregate(listOf("applied", "failed")))
        assertEquals("partial", AutomationResult.aggregate(listOf("rejected", "applied")))
        assertEquals("failed", AutomationResult.aggregate(listOf("cancelled", "failed")))
        assertEquals("applied", AutomationResult.aggregate(listOf("applied", "applied")))
        assertEquals("cancelled", AutomationResult.aggregate(listOf("cancelled")))
        assertEquals("rejected", AutomationResult.aggregate(listOf("rejected", "cancelled")))
    }
    @Test fun protocolContainsNoArbitraryRootBackendQualificationOrProfileMutation() {
        listOf("use_root", "acknowledge", "configure", "restore", "keep", "test_fade", "probe").forEach {
            assertFalse(it in AutomationContract.commands)
        }
    }
}
