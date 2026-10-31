/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.*
import android.util.Log
import org.json.JSONObject

class TvReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, TvDisplayService.BOUNDARY)) return
        val prefs = TvPreferences(context)
        if (prefs.backlightEnabled || prefs.warmthEnabled || RecoveryJournal.exists(context) || AutomationState(context).pending())
            TvDisplayService.command(context, if (intent.action == TvDisplayService.BOUNDARY) "boundary" else "reconcile")
    }
}

/** Fixed legacy aliases, deliberately separated from paired v1 automation. */
class TvControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AutomationContract.ACTION) {
            try {
                val result = AutomationState.submit(context, intent.extras)
                if (isOrderedBroadcast) { resultCode = -1; resultData = result.toString() }
            } catch (error: Exception) {
                if (isOrderedBroadcast) { resultCode = 0; resultData = JSONObject().put("state", "rejected")
                    .put("error", error.message ?: "Invalid request").toString() }
                Log.i("RedMoonAutomation", "Request rejected; invalid contract, disabled integration or revoked token")
            }
            return
        }
        val prefs = TvPreferences(context)
        if (intent.action != "com.cbkii.redmoontv.CONTROL" || !prefs.store.getBoolean("external-control", false) ||
            !prefs.store.getBoolean("legacy-control", false)) return
        val command = intent.getStringExtra("command") ?: return
        if (command !in listOf("pause", "resume", "phase", "warmth_off", "read")) return
        val phase = Phase.values().firstOrNull { it.name == intent.getStringExtra("phase") }
        if (command == "phase" && phase == null) return
        TvDisplayService.command(context, command, phase)
    }
}
