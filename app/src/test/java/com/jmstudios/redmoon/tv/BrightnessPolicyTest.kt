package com.jmstudios.redmoon.tv

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class BrightnessPolicyTest {
    private val policy = BrightnessPolicy.defaults()
    private val zone = ZoneId.of("Australia/Sydney")
    private fun local(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant()
    @Test fun midnightIsDimAndNextBoundaryIsTomorrowMorning() {
        val state = policy.at(local("2026-10-26T23:59"), zone)
        assertEquals(Phase.DIM, state.current.phase)
        assertEquals(local("2026-10-27T07:00"), state.next.instant)
        assertEquals(Phase.DIM, policy.at(local("2026-10-27T00:00"), zone).current.phase)
    }
    @Test fun boundariesApplyIncomingPhaseExactly() {
        assertEquals(Phase.DUSK, policy.at(local("2026-10-26T18:30"), zone).current.phase)
        assertEquals(Phase.DAY, policy.at(local("2026-10-26T07:00"), zone).current.phase)
    }
    @Test fun dayMayStartAfterMidnightAcrossCyclicSchedule() {
        val p = BrightnessPolicy(listOf(PhaseSetting(Phase.DAY, 80, 1200, 0), PhaseSetting(Phase.DUSK, 40, 60, 0), PhaseSetting(Phase.DIM, 5, 300, 0)))
        assertEquals(Phase.DAY, p.at(local("2026-10-26T00:00"), zone).current.phase)
    }
    @Test fun rejectsOverlapsBadOrderingAndDuplicateBoundaries() {
        listOf(listOf(420, 1320, 1110), listOf(420, 420, 1320)).forEach { times ->
            try { BrightnessPolicy(policy.settings.mapIndexed { i, s -> s.copy(startMinute = times[i]) }); fail("Invalid cycle accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsInvalidCapsAndPreferenceOrdering() {
        listOf(69 to 40, 101 to 40, 70 to 80).forEach { (day, dusk) ->
            try { BrightnessPolicy(policy.settings.map { when(it.phase) { Phase.DAY -> it.copy(level = day); Phase.DUSK -> it.copy(level = dusk); else -> it } }); fail("Invalid cap accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun fadesStayInIncomingBandForEveryPossibleStartingLevel() {
        policy.settings.forEach { setting -> (0..100).forEach { current ->
            listOf(0L, 888000L).forEach { duration ->
                val fade = BrightnessFade(policy, setting, current, duration)
                for (elapsed in listOf(-1L, 0L, 1L, 500L, 444000L, 887999L, 888000L, 999999L))
                    assertTrue(fade.valueAt(elapsed) in policy.band(setting))
                assertEquals(setting.level, fade.valueAt(duration))
            }
        }}
    }
    @Test fun hardBoundaryClampPrecedesFade() {
        val dusk = policy.settings[1]
        val fade = BrightnessFade(policy, dusk, 90, 1000)
        assertEquals(80, fade.valueAt(0))
        assertEquals(60, fade.valueAt(500))
        assertEquals(40, fade.valueAt(1000))
    }
    @Test fun errorValuesCannotBecomeBrightness() {
        try { BrightnessFade(policy, policy.settings[0], -1, 0); fail("Error became brightness") } catch (_: IllegalArgumentException) { }
    }
    @Test fun fadeMustFitWindowAndSupportExactMaximum() {
        BrightnessPolicy(policy.settings.map { it.copy(fadeSeconds = 888) })
        try { BrightnessPolicy(policy.settings.map { it.copy(fadeSeconds = 889) }); fail() } catch (_: IllegalArgumentException) { }
        try { BrightnessPolicy(listOf(PhaseSetting(Phase.DAY, 80, 0, 61), PhaseSetting(Phase.DUSK, 40, 1, 0), PhaseSetting(Phase.DIM, 5, 2, 0))); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun skippedDstBoundaryResolvesToFirstValidInstant() {
        val p = BrightnessPolicy(listOf(PhaseSetting(Phase.DAY, 80, 150, 0), PhaseSetting(Phase.DUSK, 40, 1080, 0), PhaseSetting(Phase.DIM, 5, 1320, 0)))
        val before = p.at(local("2026-10-04T01:59"), zone)
        assertEquals(local("2026-10-04T03:00"), before.next.instant)
        assertEquals(Phase.DAY, p.at(local("2026-10-04T03:00"), zone).current.phase)
    }
    @Test fun repeatedDstBoundaryRunsOnceAtEarlierOffset() {
        val p = BrightnessPolicy(listOf(PhaseSetting(Phase.DAY, 80, 150, 0), PhaseSetting(Phase.DUSK, 40, 1080, 0), PhaseSetting(Phase.DIM, 5, 1320, 0)))
        val repeatedLocal = LocalDateTime.parse("2026-04-05T02:40")
        val later = repeatedLocal.toInstant(zone.rules.getValidOffsets(repeatedLocal).last())
        val state = p.at(later, zone)
        assertEquals(Phase.DAY, state.current.phase)
        assertEquals(Phase.DUSK, state.next.setting.phase)
    }
}
