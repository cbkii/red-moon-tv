/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ControllerTeardownTest {
    @Test fun inFlightCallSettlesAndWorkerClosesBeforeShutdownCancelsTheQueue() {
        val executor = controllerExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        try {
            executor.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val longFade = executor.schedule(Runnable {}, 888, TimeUnit.SECONDS)
            closeControllerExecutor(executor) { closed.countDown() }
            assertFalse("Do not shut down while cleanup is still queued", executor.isShutdown)
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(longFade.isCancelled)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
