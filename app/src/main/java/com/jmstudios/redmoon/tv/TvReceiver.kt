/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.*
import android.os.SystemClock

class TvReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, TvDisplayService.BOUNDARY)) return
        val prefs = TvPreferences(context)
        if (prefs.backlightEnabled || prefs.warmthEnabled || RecoveryJournal.exists(context))
            TvDisplayService.command(context, if (intent.action == TvDisplayService.BOUNDARY) "boundary" else "reconcile")
    }
}

/** Explicit opt-in, bounded tvQuickActions commands; no arbitrary key/root command API. */
class TvControlReceiver : BroadcastReceiver() {
    companion object { private var lastCommandAt = -1000L }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.cbkii.redmoontv.CONTROL" || !TvPreferences(context).store.getBoolean("external-control", false)) return
        val command = intent.getStringExtra("command") ?: return
        if (command !in listOf("pause", "resume", "phase", "warmth_off", "read")) return
        val phase = Phase.values().firstOrNull { it.name == intent.getStringExtra("phase") }
        if (command == "phase" && phase == null) return
        val now = SystemClock.elapsedRealtime()
        synchronized(TvControlReceiver::class.java) {
            if (now - lastCommandAt < 750L) return
            lastCommandAt = now
        }
        TvDisplayService.command(context, command, phase)
    }
}
