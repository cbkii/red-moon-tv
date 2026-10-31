package com.jmstudios.redmoon.tv
import org.junit.Assert.*
import org.junit.Test

class TransitionSessionTest {
    private val p = BrightnessPolicy.defaults()
    @Test fun suppressesDuplicateIntegerWritesAndEndsExactly() {
        var time = 0L; val writes = mutableListOf<Int>()
        val session = TransitionSession(BrightnessFade(p, p.settings[1], 80, 888000), 80, { time }, { false }, { writes.add(it) })
        session.tick(); session.tick(); assertTrue(writes.isEmpty())
        time = 444000; assertTrue(session.tick()); assertEquals(listOf(60), writes)
        time = 888000; assertFalse(session.tick()); assertEquals(listOf(60, 40), writes)
        assertFalse(session.tick()); assertEquals(2, writes.size)
    }
    @Test fun cancellationPreventsFutureWrites() {
        var cancelled = false; var time = 0L; val writes = mutableListOf<Int>()
        val session = TransitionSession(BrightnessFade(p, p.settings[1], 90, 1000), 90, { time }, { cancelled }, { writes.add(it) })
        session.tick(); cancelled = true; time = 1000
        assertFalse(session.tick()); assertEquals(listOf(80), writes)
    }
    @Test fun uncertainFailedWriteNeverAutomaticallyRetriesOrClaimsSuccess() {
        var calls = 0
        val session = TransitionSession(BrightnessFade(p, p.settings[2], 40, 0), 40, { 0 }, { false }, { calls++; throw IllegalStateException("readback mismatch") })
        try { session.tick(); fail() } catch (_: IllegalStateException) { }
        assertEquals(40, session.lastApplied)
        assertFalse(session.tick()); assertEquals(1, calls)
    }
    @Test fun cancellation_during_setter_preserves_late_confirmation_but_stops_future_steps() {
        var cancelled = false
        var time = 0L
        val writes = mutableListOf<Int>()
        val transition = TransitionSession(BrightnessFade(p, p.settings[2], 40, 1000), 40, { time }, { cancelled }) {
            writes.add(it); cancelled = true
        }
        transition.tick()
        assertEquals(20, transition.lastApplied)
        time = 1000
        assertFalse(transition.tick())
        assertEquals(listOf(20), writes)
    }

    @Test fun replacementGenerationStopsOldTransition() {
        var generation = 1; val writes = mutableListOf<Int>()
        val old = TransitionSession(BrightnessFade(p, p.settings[2], 40, 1000), 40, { 0 }, { generation != 1 }, { writes.add(it) })
        generation = 2
        assertFalse(old.tick()); assertTrue(writes.isEmpty())
    }
}

class DailyWindowTest {
    private val zone = java.time.ZoneId.of("Australia/Sydney")
    @Test fun overnightWarmthIsIndependentOfBrightnessPhases() {
        val window = DailyWindow(19 * 60, 6 * 60)
        assertTrue(window.at(java.time.Instant.parse("2026-10-26T12:00:00Z"), zone).first)
        assertFalse(window.at(java.time.Instant.parse("2026-10-26T00:00:00Z"), zone).first)
    }
    @Test fun repeatedHourDoesNotReenterExpiredWarmthWindow() {
        val window = DailyWindow(125, 135)
        val local = java.time.LocalDateTime.parse("2026-04-05T02:10")
        val later = local.toInstant(zone.rules.getValidOffsets(local).last())
        assertFalse(window.at(later, zone).first)
    }
}

class NoOpFadeTest {
    @Test fun unchangedTargetFinishesWithoutAnIdleTicker() {
        val p = BrightnessPolicy.defaults()
        val session = TransitionSession(BrightnessFade(p, p.settings[2], 5, 888000), 5, { 0 }, { false }, { fail("No write expected") })
        assertFalse(session.tick())
    }
}

class CollapsedDstTest {
    @Test fun multipleSkippedBoundariesReconcileDirectlyToTheFinalPhase() {
        val zone = java.time.ZoneId.of("Australia/Sydney")
        val p = BrightnessPolicy(listOf(PhaseSetting(Phase.DAY, 80, 130, 0), PhaseSetting(Phase.DUSK, 40, 140, 0), PhaseSetting(Phase.DIM, 5, 1320, 0)))
        val after = java.time.LocalDateTime.parse("2026-10-04T03:00").atZone(zone).toInstant()
        assertEquals(Phase.DUSK, p.at(after, zone).current.phase)
    }
}
