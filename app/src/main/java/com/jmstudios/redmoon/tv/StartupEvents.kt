/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Persist lifecycle/failure edges, never a steady-state disk heartbeat. */
object StartupEvents {
    @Synchronized fun record(context: Context, event: String, detail: String = "") {
        val prefs = TvStorage.context(context).getSharedPreferences("tv-startup-v1", Context.MODE_PRIVATE)
        val events = try { JSONArray(prefs.getString("events", "[]")) } catch (_: Exception) { JSONArray() }
        val bounded = JSONArray()
        for (i in maxOf(0, events.length() - 30) until events.length()) bounded.put(events.get(i))
        bounded.put(JSONObject().put("event", event).put("detail", detail.take(256))
            .put("elapsed", SystemClock.elapsedRealtime()).put("at", System.currentTimeMillis())
            .put("boot", BootIdentity.read()).put("pid", android.os.Process.myPid()))
        check(prefs.edit().putString("events", bounded.toString()).commit()) { "Cannot persist startup evidence" }
    }
    fun snapshot(context: Context): JSONArray = try { JSONArray(TvStorage.context(context)
        .getSharedPreferences("tv-startup-v1", Context.MODE_PRIVATE).getString("events", "[]")) }
        catch (_: Exception) { JSONArray() }
}
