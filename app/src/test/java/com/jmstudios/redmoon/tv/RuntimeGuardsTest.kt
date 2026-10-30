/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import org.junit.Assert.*
import org.junit.Test

class RuntimeGuardsTest {
    @Test fun native_library_initialisation_failure_is_retained_without_another_loader_attempt() {
        val once = InitialisationOnce<Any>()
        var loads = 0
        val first = UnsatisfiedLinkError("original JNI initialiser error")
        repeat(3) {
            try { once.get { loads++; throw first }; fail() }
            catch (error: UnsatisfiedLinkError) { assertSame(first, error) }
        }
        assertEquals(1, loads)
    }
    @Test fun successful_native_binding_is_reused() {
        val once = InitialisationOnce<Any>()
        val binding = Any()
        assertSame(binding, once.get { binding })
        assertSame(binding, once.get { fail("Unexpected second loader"); Any() })
    }
    @Test fun wake_refresh_does_not_clear_manual_hold_before_its_boundary() {
        assertEquals(WritePermission.BLOCK, NativeWriteGate.decide(true, 1000, 999, false))
        assertEquals(WritePermission.RELEASE_MANUAL_HOLD, NativeWriteGate.decide(true, 1000, 1000, false))
    }
    @Test fun native_failure_stays_blocked_across_wake_restart_and_later_boundaries() {
        for (now in listOf(0L, 1000L, 10000000L))
            assertEquals(WritePermission.BLOCK, NativeWriteGate.decide(true, null, now, false))
    }
    @Test fun pending_recovery_blocks_even_an_expired_manual_hold() {
        assertEquals(WritePermission.BLOCK, NativeWriteGate.decide(true, 1000, 2000, true))
    }
    @Test fun explicit_validated_resume_can_clear_failure_without_bypassing_recovery() {
        assertEquals(WritePermission.ALLOW, NativeWriteGate.decide(false, null, 1, false))
        assertEquals(WritePermission.BLOCK, NativeWriteGate.decide(false, null, 1, true))
    }
    @Test fun warmth_finishes_exactly_and_schedules_only_changed_integer_values() {
        val fade = WarmthTransition(WarmthState(40, 0), WarmthState(40, 40), 888000)
        assertTrue(fade.nextDelay(0) > 10000)
        assertEquals(WarmthState(40, 40), fade.valueAt(888000))
        assertEquals(WarmthState(40, 20), fade.valueAt(444000))
        assertEquals(200L, WarmthTransition(WarmthState(0, 0), WarmthState(100, 100), 1000).nextDelay(0))
    }
    @Test fun zero_duration_and_no_op_warmth_have_no_timer() {
        assertEquals(0L, WarmthTransition(WarmthState(40, 5), WarmthState(40, 5), 888000).duration)
        assertEquals(WarmthState(40, 20), WarmthTransition(WarmthState(40, 0), WarmthState(40, 20), 0).valueAt(0))
    }
}

class WorkerSessionTest {
    @Test fun replacement_connection_requires_its_own_handshake() {
        val first = WorkerSession("first")
        first.hello(10000, 12, 10000, 11, "worker-a")
        first.verify("first", "worker-a")
        first.invalidate()
        val second = WorkerSession("second")
        assertFalse(second.established)
        try { second.verify("first", "worker-a"); fail() } catch (_: IllegalStateException) {}
        second.hello(10000, 13, 10000, 11, "worker-b")
        second.verify("second", "worker-b")
        try { first.verify("first", "worker-a"); fail() } catch (_: IllegalStateException) {}
    }
    @Test fun different_uid_same_process_and_changed_worker_are_rejected() {
        for ((uid, pid) in listOf(10001 to 12, 10000 to 11, 10000 to -1)) {
            try { WorkerSession("token").hello(uid, pid, 10000, 11, "worker"); fail() }
            catch (_: IllegalStateException) {}
        }
        val session = WorkerSession("token")
        session.hello(10000, 12, 10000, 11, "worker")
        try { session.verify("token", "replacement"); fail() } catch (_: IllegalStateException) {}
    }
}

class ControllerExecutorTest {
    @Test fun shutdown_does_not_retain_an_888_second_fade_timer() {
        val worker = controllerExecutor()
        try {
            worker.schedule({ fail("Obsolete fade executed") }, 888, java.util.concurrent.TimeUnit.SECONDS)
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(worker.queue.isEmpty())
        } finally { worker.shutdownNow() }
    }
    @Test fun cancelling_a_replaced_transition_removes_its_service_closure_immediately() {
        val worker = controllerExecutor()
        try {
            val task = worker.schedule({ fail("Cancelled fade executed") }, 888, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(task.cancel(false))
            assertTrue(worker.queue.isEmpty())
        } finally { worker.shutdownNow() }
    }
}
