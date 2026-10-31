/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.*
import android.util.Log
import org.json.JSONObject

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

/** Fixed legacy aliases, deliberately separated from paired v1 automation. */
class TvControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AutomationContract.ACTION) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!admit(now)) {
                if (isOrderedBroadcast) { resultCode = 0; resultData = "{\"state\":\"rejected\",\"error_code\":\"RATE_LIMITED\"}" }
                return
            }
            try {
                val result = AutomationState.submit(context, intent.extras)
                if (isOrderedBroadcast) { resultCode = -1; resultData = result.toString() }
            } catch (error: Exception) {
                if (isOrderedBroadcast) { resultCode = 0; resultData = JSONObject().put("state", "rejected")
                    .put("error_code", "CONTRACT_OR_PAIRING_REJECTED").put("error", error.message ?: "Invalid request").toString() }
                if (now - lastRejected >= 10000) {
                    lastRejected = now
                    Log.i("RedMoonAutomation", "Request rejected; invalid contract, disabled integration or revoked token")
                }
            }
            return
        }
        val prefs = TvPreferences(context)
        if (intent.action != "com.cbkii.redmoontv.CONTROL" || !prefs.store.getBoolean("external-control", false) ||
            !prefs.store.getBoolean("legacy-control", false)) return
        val command = intent.getStringExtra("command") ?: return
        if (command !in listOf("pause", "resume", "phase", "warmth_off", "read", "ensure_running")) return
        val phase = Phase.values().firstOrNull { it.name == intent.getStringExtra("phase") }
        if (command == "phase" && phase == null) return
        if (command == "ensure_running") TvContinuity.ensureRunning(context, "external_wake")
        else TvDisplayService.command(context, command, phase)
    }
    companion object {
        private var window = 0L
        private var count = 0
        private var lastRejected = -10000L
        private fun admit(now: Long): Boolean {
            if (now - window >= 1000) { window = now; count = 0 }
            return ++count <= 32
        }
    }
}
