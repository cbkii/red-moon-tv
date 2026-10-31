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
        var picture: String? = "boot:picture"
        var lostResponse = false
        var held = false
        var beforeWrite: (() -> Unit)? = null
        val writes = mutableListOf<Int>()
        override fun context() = picture
        var onRead: (() -> Unit)? = null
        override fun read(): Int { onRead?.invoke(); return value }
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

    @Test fun missing_context_does_not_block_a_read_but_never_authorises_a_setter() {
        val native = Fake(); native.picture = null
        val store = Memory(); val controller = BacklightController(native, store, "session")
        assertEquals(66, controller.read())
        try { controller.begin("probe"); fail() } catch (_: IllegalStateException) {}
        assertFalse(native.held); assertNull(store.record); assertTrue(native.writes.isEmpty())
    }
    @Test fun backend_context_identity_prevents_cross_backend_restoration() {
        val native = Fake(); native.picture = "apk:boot:picture"
        val store = Memory(); val controller = BacklightController(native, store, "first")
        controller.begin("probe"); controller.write(61); controller.release()
        native.picture = "root:boot:picture"
        val replacement = BacklightController(native, store, "second")
        try { replacement.restore(); fail() } catch (_: IllegalStateException) {}
        assertEquals(listOf(61), native.writes); assertNotNull(store.record); assertFalse(native.held)
    }

    @Test fun confirmedScheduleAdoptsAfterRestartAndPreservesOriginal() {
        val native = Fake(); val store = Memory(); val first = BacklightController(native, store, "first")
        first.begin("schedule"); first.write(60); first.release()
        val next = BacklightController(native, store, "second")
        assertTrue(next.adoptSchedule()); assertEquals("second", store.record!!.session)
        next.begin("schedule"); next.write(59); assertTrue(next.restore()); assertEquals(66, native.value)
    }
    @Test fun steadyObservationFindsManualChangeWithoutAnySetter() {
        val native = Fake(); val store = Memory(); val controller = BacklightController(native, store, "live")
        controller.begin("schedule"); controller.write(60); native.value = 55
        try { controller.observe(); fail() } catch (_: ExternalBacklightChange) {}
        controller.release(); controller.discard()
        assertFalse(controller.restore()); assertEquals(55, native.value); assertEquals(listOf(60), native.writes)
    }
    @Test fun inputHdrChangeIsDifferentFromManualBrightnessAndCannotRestoreOldContext() {
        val native = Fake(); val store = Memory(); val controller = BacklightController(native, store, "live")
        controller.begin("schedule"); controller.write(60); native.picture = "other-input:hdr"
        try { controller.observe(); fail() } catch (_: BacklightContextChange) {}
        try { controller.restore(); fail() } catch (_: IllegalStateException) {}
        assertEquals(listOf(60), native.writes)
    }

    @Test fun cancellationDuringReadCannotStartAStaleSetter() {
        val native = Fake(); val store = Memory(); val controller = BacklightController(native, store, "live")
        controller.begin("schedule"); var allowed = true
        native.onRead = { allowed = false }
        try { controller.write(60) { allowed }; fail() } catch (_: java.util.concurrent.CancellationException) {}
        assertTrue(native.writes.isEmpty()); assertNull(store.record)
    }

    @Test fun adoptionHoldsLeaseDuringJournalTransfer() {
        val native = Fake(); val store = Memory(); val first = BacklightController(native, store, "one")
        first.begin("schedule"); first.write(60); first.release()
        native.onRead = { assertTrue(native.held) }
        val next = BacklightController(native, store, "two")
        next.begin("schedule"); assertEquals("two", store.record!!.session)
        assertEquals(66, store.record!!.original); next.release()
    }
    @Test fun newBootNeverRestoresPreviousBootOriginal() {
        val native = Fake(); val store = Memory()
        native.picture = "device:boot:id=one:picture=1"
        val first = BacklightController(native, store, "one")
        first.begin("schedule"); first.write(60); first.release()
        native.picture = "device:boot:id=two:picture=1"; native.value = 85
        val next = BacklightController(native, store, "two")
        next.begin("schedule"); next.write(70); next.restore()
        assertEquals(85, native.value)
    }

}
