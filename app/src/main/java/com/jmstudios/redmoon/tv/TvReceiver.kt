/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.*
import android.os.SystemClock

class TvReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                TvDisplayService.BOUNDARY, TvContinuity.HEALTH)) return
        val reason = when (action) {
            TvContinuity.HEALTH -> "health"
            TvDisplayService.BOUNDARY -> "boundary"
            else -> action!!.substringAfterLast('.')
        }
        if (action != TvContinuity.HEALTH && action != TvDisplayService.BOUNDARY) StartupEvents.record(context, "receiver", reason)
        TvContinuity.ensureRunning(context, reason)
    }
}

/** Explicit opt-in, bounded tvQuickActions commands; no arbitrary key/root command API. */
class TvControlReceiver : BroadcastReceiver() {
    companion object { private var lastCommandAt = -1000L; private var lastCommand = "" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.cbkii.redmoontv.CONTROL" || !TvPreferences(context).store.getBoolean("external-control", false)) return
        val command = intent.getStringExtra("command") ?: return
        if (command !in listOf("pause", "resume", "phase", "warmth_off", "read", "ensure_running")) return
        val phase = Phase.values().firstOrNull { it.name == intent.getStringExtra("phase") }
        if (command == "phase" && phase == null) return
        val now = SystemClock.elapsedRealtime()
        synchronized(TvControlReceiver::class.java) {
            val key = "$command:${phase?.name}"
            if (key == lastCommand && now - lastCommandAt < 750L) return
            lastCommandAt = now; lastCommand = key
        }
        if (command == "ensure_running") TvContinuity.ensureRunning(context, "external_wake")
        else TvDisplayService.command(context, command, phase)
    }
}
