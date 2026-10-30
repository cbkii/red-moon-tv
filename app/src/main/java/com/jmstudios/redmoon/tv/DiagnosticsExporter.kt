/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.os.Build
import com.jmstudios.redmoon.BuildConfig
import org.json.JSONObject

object DiagnosticsExporter {
    fun report(context: Context): String {
        val prefs = TvPreferences(context)
        val runtime = Runtime.getRuntime()
        val journal = try { RecoveryJournal(context).load()?.let {
            JSONObject().put("original", it.original).put("confirmed", it.confirmed)
                .put("pending", it.pending ?: JSONObject.NULL).put("context", it.context).put("kind", it.kind)
        } ?: JSONObject.NULL } catch (error: Exception) { "unreadable: ${error.message}" }
        return JSONObject().put("schema", 3).put("package", context.packageName)
            .put("source_sha", BuildConfig.SOURCE_SHA).put("build_id", BuildConfig.BUILD_ID)
            .put("backend_protocol", BuildConfig.BACKEND_PROTOCOL).put("firmware", Build.FINGERPRINT)
            .put("selected_backend", if (prefs.rootBackend) "root-pipe-v1" else "apk-jni-v4")
            .put("native_state", prefs.readState).put("native_level", prefs.nativeLevel ?: JSONObject.NULL)
            .put("write_context_ready", prefs.writeContextReady).put("probe_restored", prefs.probeComplete)
            .put("probe_active", TvDisplayService.active && prefs.store.getBoolean("probe-active", false))
            .put("service_running", TvDisplayService.active)
            .put("backlight_enabled", prefs.backlightEnabled).put("warmth_enabled", prefs.warmthEnabled)
            .put("native_read_at", prefs.store.getLong("native-read-at", 0))
            .put("native_call_stats", NativeCallStats.snapshot())
            .put("app_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("app_heap_limit_bytes", runtime.maxMemory()).put("recovery", journal)
            .put("uptime_ms", android.os.SystemClock.elapsedRealtime())
            .put("abi", Build.SUPPORTED_ABIS.joinToString(",")).put("status", prefs.status)
            .put("identity_ready", prefs.identityReady)
            .put("first_initialisation_error", prefs.store.getString("first-initialisation-error", "none"))
            .put("last_root_error", prefs.store.getString("root-last-error", "none"))
            .put("native", prefs.store.getString("native-diagnostics", "not read"))
            .put("write_blocked", prefs.store.getBoolean("native-blocked", false))
            .put("last_native_error", prefs.store.getString("last-native-error", "none"))
            .put("warmth", prefs.warmthStatus).put("recovery_pending", RecoveryJournal.exists(context))
            .put("native_acknowledged", prefs.nativeQualified).put("fades_acknowledged", prefs.fadesQualified)
            .put("physical_validation", "User acknowledgement; independent APK-hash acceptance required for release")
            .toString(2)
    }
}
