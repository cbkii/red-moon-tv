/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/** One OS-owned non-wakeup heartbeat survives app death. No service or UI is started by queries. */
object TvContinuity {
    const val HEALTH = "com.cbkii.redmoontv.HEALTH"
    const val INTERVAL = 300000L
    @Volatile var nextHealth = 0L; private set
    fun arm(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val event = PendingIntent.getBroadcast(context, 72, Intent(context, TvReceiver::class.java).setAction(HEALTH), PendingIntent.FLAG_IMMUTABLE)
        val prefs = TvPreferences(context)
        if (!prefs.backlightEnabled && !prefs.warmthEnabled) { alarm.cancel(event); nextHealth = 0; return }
        val now = SystemClock.elapsedRealtime()
        if (nextHealth > now && nextHealth - now <= INTERVAL) return
        nextHealth = now + INTERVAL
        alarm.set(AlarmManager.ELAPSED_REALTIME, nextHealth, event)
    }
    fun ensureRunning(context: Context, reason: String): Boolean {
        val prefs = TvPreferences(context)
        // Rearm before launching: a crash during reconciliation does not lose the next attempt.
        arm(context)
        if (!prefs.backlightEnabled && !prefs.warmthEnabled) return false
        return try {
            context.startForegroundService(Intent(context, TvDisplayService::class.java)
                .putExtra("command", "ensure_running").putExtra("reason", reason.take(80)))
            true
        } catch (error: RuntimeException) {
            StartupEvents.record(context, "start_rejected", error.toString()); false
        }
    }
}
