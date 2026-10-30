/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import java.util.concurrent.ScheduledThreadPoolExecutor

internal fun controllerExecutor() = ScheduledThreadPoolExecutor(1).apply {
    removeOnCancelPolicy = true
    setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
}

/** Android can cancel even zero-delay queued tasks at shutdown; close on the worker first. */
internal fun closeControllerExecutor(executor: ScheduledThreadPoolExecutor, close: () -> Unit) {
    executor.execute {
        try { close() } finally { executor.shutdown() }
    }
}
