/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import org.junit.Assert.*
import org.junit.Test

class BacklightControllerTest {
    private class Memory : RecoveryStore {
        var record: RecoveryRecord? = null
        override fun load() = record
        override fun save(record: RecoveryRecord) { this.record = record }
        override fun clear() { record = null }
    }
    private class Fake : BacklightBackend {
        var value = 66
        var picture = "boot:picture"
        var lostResponse = false
        var held = false
        var beforeWrite: (() -> Unit)? = null
        val writes = mutableListOf<Int>()
        override fun context() = picture
        override fun read() = value
        override fun claim() { check(!held); held = true }
        override fun write(value: Int) {
            check(held); beforeWrite?.invoke(); writes.add(value); this.value = value
            if (lostResponse) throw IllegalStateException("Response lost after setter applied")
        }
        override fun release() { held = false }
    }
    @Test fun prepared_record_is_durable_before_setter_and_confirmed_only_after_readback() {
        val native = Fake(); val store = Memory(); val controller = BacklightController(native, store, "session")
        controller.begin("probe")
        native.beforeWrite = { assertEquals(61, store.record!!.pending); assertEquals(66, store.record!!.confirmed) }
        controller.write(61)
        assertNull(store.record!!.pending); assertEquals(61, store.record!!.confirmed)
        native.beforeWrite = null
        assertTrue(controller.restore()); assertEquals(66, native.value); assertNull(store.record); assertFalse(native.held)
    }
    @Test fun applied_setter_lost_response_is_not_retried_and_can_be_reconciled() {
        val native = Fake(); val store = Memory(); val controller = BacklightController(native, store, "session")
        controller.begin("probe"); native.lostResponse = true
        try { controller.write(61); fail() } catch (_: IllegalStateException) {}
        assertEquals(61, store.record!!.pending); assertEquals(66, store.record!!.confirmed)
        try { controller.write(60); fail() } catch (_: IllegalStateException) {}
        assertEquals(listOf(61), native.writes)
        native.lostResponse = false
        assertTrue(controller.restore()); assertEquals(66, native.value)
    }
    @Test fun external_adjustment_yields_and_is_never_restored_over() {
        val native = Fake(); val store = Memory(); val controller = BacklightController(native, store, "session")
        controller.begin("schedule"); controller.write(60); native.value = 50
        try { controller.write(59); fail() } catch (_: ExternalBacklightChange) {}
        try { controller.restore(); fail() } catch (_: ExternalBacklightChange) {}
        assertEquals(50, native.value); assertFalse(native.held)
    }
    @Test fun changed_context_prevents_restore_even_when_numeric_value_matches() {
        val native = Fake(); val store = Memory(); val controller = BacklightController(native, store, "session")
        controller.begin("probe"); controller.write(61); native.picture = "different"
        try { controller.restore(); fail() } catch (_: IllegalStateException) {}
        assertEquals(61, native.value); assertFalse(native.held)
    }
    @Test fun restart_preserves_record_and_requires_explicit_recovery() {
        val native = Fake(); val store = Memory(); val first = BacklightController(native, store, "first")
        first.begin("probe"); first.write(61); first.release()
        val restarted = BacklightController(native, store, "second")
        try { restarted.begin("schedule"); fail() } catch (_: IllegalStateException) {}
        assertEquals(61, store.record!!.confirmed); assertFalse(native.held)
        assertTrue(restarted.restore()); assertEquals(66, native.value)
    }
    @Test fun next_quantised_fade_change_does_not_poll_every_half_second() {
        val policy = BrightnessPolicy.defaults()
        val fade = BrightnessFade(policy, policy.settings[1], 80, 888000)
        assertTrue(fade.nextDelay(0) > 10000)
        assertEquals(500L, BrightnessFade(policy, policy.settings[1], 80, 1000).nextDelay(0))
    }    @Test fun unchanged_phase_does_not_create_a_recovery_record() {
        val native = Fake(); val store = Memory(); val controller = BacklightController(native, store, "session")
        controller.begin("schedule"); controller.write(66)
        assertNull(store.record); assertTrue(native.writes.isEmpty())
        controller.release()
    }

}
