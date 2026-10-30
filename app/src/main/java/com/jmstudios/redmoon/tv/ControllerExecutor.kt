/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import java.util.concurrent.ScheduledThreadPoolExecutor

internal fun controllerExecutor() = ScheduledThreadPoolExecutor(1).apply {
    removeOnCancelPolicy = true
    setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
}
