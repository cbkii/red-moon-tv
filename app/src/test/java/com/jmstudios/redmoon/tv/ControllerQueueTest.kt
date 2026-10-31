/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ControllerQueueTest {
    @Test fun saturatedOptionalWorkCannotDropRestoreAndDuplicateRestoreIsCoalesced() {
        val executor = controllerExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val restored = CountDownLatch(1)
        val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
        val queue = ControllerQueue(executor, { throw AssertionError(it) }, {})
        try {
            assertTrue(queue.submit("blocked") { entered.countDown(); release.await(3, TimeUnit.SECONDS) })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(queue.submit("read-1") {})
            assertTrue(queue.submit("read-2") {})
            assertFalse(queue.submit("probe") { writes.add("bad-probe") })
            assertTrue(queue.submit("restore", true) { writes.add("restore"); restored.countDown() })
            assertFalse(queue.submit("restore", true) { writes.add("duplicate") })
            release.countDown(); assertTrue(restored.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("restore"), writes)
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun latestTransitionReplacesStaleQueuedDestination() {
        val executor = controllerExecutor(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val writes = mutableListOf<Int>()
        val queue = ControllerQueue(executor, { throw AssertionError(it) }, {})
        try {
            queue.submit("transition", true) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queue.submit("transition", true) { writes.add(1) }
            queue.submit("transition", true) { writes.add(2); done.countDown() }
            release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(listOf(2), writes)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
