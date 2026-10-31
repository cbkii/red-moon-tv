/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.os.Build
import com.jmstudios.redmoon.BuildConfig
import org.json.JSONObject
import org.json.JSONArray

object DiagnosticsExporter {
    @Volatile private var certificate: String? = null
    @Suppress("DEPRECATION") private fun signer(context: Context): String {
        certificate?.let { return it }
        return try {
            val bytes = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures.first().toByteArray()
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.also { certificate = it }
        } catch (_: Exception) { "unavailable" }
    }
    fun report(context: Context): String {
        val prefs = TvPreferences(context)
        val runtime = Runtime.getRuntime()
        val journal = try { RecoveryJournal(context).load()?.let {
            JSONObject().put("original", it.original).put("confirmed", it.confirmed)
                .put("pending", it.pending ?: JSONObject.NULL).put("context", it.context).put("kind", it.kind)
        } ?: JSONObject.NULL } catch (error: Exception) { "unreadable: ${error.message}" }
        val now = java.time.Instant.now()
        val schedule = try { prefs.policy().at(now, java.time.ZoneId.systemDefault()) } catch (_: IllegalArgumentException) { null }
        return JSONObject().put("schema", 5)
            .put("report_at", System.currentTimeMillis())
            .put("boot_id", BootIdentity.read()).put("native_compatibility", BuildConfig.NATIVE_COMPATIBILITY)
            .put("device_protected_storage", TvStorage.context(context).isDeviceProtectedStorage)
            .put("storage_migrated", TvStorage.ready(context))
            .put("user_unlocked", context.getSystemService(android.os.UserManager::class.java).isUserUnlocked)
            .put("background_restricted", (android.os.Build.VERSION.SDK_INT >= 28 && context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted))
            .put("battery_exempt", context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
            .put("overlay_permission", android.provider.Settings.canDrawOverlays(context))
            .put("startup_events", StartupEvents.snapshot(context))
            .put("sample_current_process", NativeSnapshot.value != null)
            .put("sample_age_ms", NativeSnapshot.value?.let { android.os.SystemClock.elapsedRealtime() - it.elapsed }
                ?: prefs.store.getLong("native-read-at", 0).let { if (it > 0 && System.currentTimeMillis() >= it) System.currentTimeMillis() - it else JSONObject.NULL })
            .put("enforce_schedule", prefs.enforceSchedule)
            .put("access_required", prefs.store.getBoolean("access-required", false))
            .put("schedule", JSONObject().put("phase", schedule?.current?.phase?.name)
                .put("level", schedule?.current?.level).put("next_at", schedule?.next?.instant?.toEpochMilli()))
            .put("scheduled_level_applied", TvDisplayService.active && TvDisplayService.runtime().optString("brightness_state") == "ACTIVE" && NativeSnapshot.value?.let { android.os.SystemClock.elapsedRealtime() - it.elapsed < 120000 } == true && prefs.backlightEnabled && !prefs.store.contains("manual-hold-until") &&
                schedule != null && prefs.nativeLevel == schedule.current.level).put("package", context.packageName)
            .put("signer_sha256", signer(context))
            .put("source_sha", BuildConfig.SOURCE_SHA).put("build_id", BuildConfig.BUILD_ID)
            .put("backend_protocol", BuildConfig.BACKEND_PROTOCOL).put("firmware", Build.FINGERPRINT)
            .put("selected_backend", if (prefs.rootBackend) "root-pipe-v1" else "apk-jni-v4")
            .put("adb_request", prefs.store.getString("adb-last-request", "none"))
            .put("adb_error", prefs.store.getString("adb-error", "none"))
            .put("test", JSONObject().put("at", prefs.store.getLong("test-start-at", 0)).put("seconds", prefs.store.getInt("test-seconds", -1))
                .put("from", prefs.store.getInt("test-from", -1)).put("target", prefs.store.getInt("test-target", -1)))
            .put("session_state", prefs.sessionState().name)
            .put("runtime", TvDisplayService.runtime())
            .put("manual_hold_until", prefs.store.getLong("manual-hold-until", 0))
            .put("current_native_error", prefs.store.getString("current-native-error", "none"))
            .put("last_native_event", prefs.store.getString("last-native-event", "none"))
            .put("last_native_event_at", prefs.store.getLong("last-native-event-at", 0))
            .put("last_native_error_at", prefs.store.getLong("last-native-error-at", 0))
            .put("sample_at", NativeSnapshot.value?.at ?: prefs.store.getLong("native-read-at", 0))
            .put("sample_context", NativeSnapshot.value?.identity ?: JSONObject.NULL)
            .put("trace", JSONArray().apply { NativeTrace.snapshot().takeLast(64).forEach {
                put(JSONObject().put("at", it.at).put("operation", it.operation).put("level", it.level).put("context", it.context))
            } })
            .put("transition_mode", if (prefs.strictTransitions) "strict" else "smooth")
            .put("settings", JSONObject().apply {
                try { prefs.policy().settings.forEach { put(it.phase.name, JSONObject().put("level", it.level).put("start", it.startMinute).put("fade", it.fadeSeconds)) } }
                catch (error: IllegalArgumentException) { put("error", error.message) }
                put("linked", prefs.linked); put("warmth_fade", prefs.warmthFade)
            })
            .put("native_state", prefs.readState).put("native_level", prefs.nativeLevel ?: JSONObject.NULL)
            .put("write_context_ready", prefs.writeContextReady).put("probe_restored", prefs.probeComplete)
            .put("probe_active", TvDisplayService.active && prefs.store.getBoolean("probe-active", false))
            .put("service_running", TvDisplayService.active)
            .put("backlight_enabled", prefs.backlightEnabled).put("warmth_enabled", prefs.warmthEnabled)
            .put("native_read_at", prefs.store.getLong("native-read-at", 0))
            .put("metrics", JSONObject(NativeCallStats.values()))
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
            .put("warmth", prefs.warmthStatus).put("recovery_pending", prefs.sessionState() == NativeSessionState.RECOVERY)
            .put("journal_present", RecoveryJournal.exists(context))
            .put("native_acknowledged", prefs.nativeQualified).put("fades_acknowledged", prefs.fadesQualified)
            .put("physical_validation", "User acknowledgement; independent APK-hash acceptance required for release")
            .toString(2)
    }
}
