/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Per-package pairing and bounded, persisted result history. Tokens never enter reports/logcat. */
class AutomationState(private val context: Context) {
    private val prefs = TvPreferences(context).store
    val enabled get() = prefs.getBoolean("external-control", false)
    fun token(): String = synchronized(lock) {
        prefs.getString("automation-token", null) ?: (UUID.randomUUID().toString() + UUID.randomUUID()).also {
            check(prefs.edit().putString("automation-token", it).commit())
        }
    }
    fun rotate() { synchronized(lock) { check(prefs.edit().remove("automation-token").remove("automation-seen").commit()); token() } }
    fun shortcutToken(request: AutomationRequest) = ShortcutCapability.sign(token(), request)
    fun authorised(candidate: String?, request: AutomationRequest? = null): Boolean {
        if (!enabled || candidate == null || candidate.length !in 1..128) return false
        val expected = if (candidate.startsWith("shortcut:")) request?.let { shortcutToken(it) } ?: return false else token()
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
    }
    fun temporary(part: String): JSONObject? = synchronized(lock) {
        prefs.getString("automation-$part", null)?.let { JSONObject(it) }
    }
    fun temporary(part: String, value: JSONObject?) = synchronized(lock) {
        check(prefs.edit().apply { if (value == null) remove("automation-$part")
            else putString("automation-$part", value.toString()) }.commit())
    }
    fun pending() = temporary("brightness") != null || temporary("warmth") != null
    fun expire(now: Long): Set<String> {
        val expired = mutableSetOf<String>()
        listOf("brightness", "warmth").forEach { part -> temporary(part)?.let {
            if (!temporaryActive(it.optLong("expires"), now)) { temporary(part, null); expired.add(part) }
        } }
        return expired
    }
    fun nextExpiry(): Long? = listOfNotNull(temporary("brightness"), temporary("warmth"))
        .map { it.optLong("expires") }.filter { it > 0 }.minOrNull()
    private fun history() = JSONArray(prefs.getString("automation-results", "[]"))
    fun find(id: String): JSONObject? = synchronized(lock) {
        val all = history(); (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("request_id") == id }
    }
    /** Returns false for a retry; the original result remains authoritative across process recreation. */
    fun accept(r: AutomationRequest, protectReplay: Boolean): Boolean = synchronized(lock) {
        if (find(r.id) != null) return false
        val all = history()
        val seen = AutomationReplayGuard(prefs.getString("automation-seen", null))
        check(!protectReplay || !seen.contains(r.id)) { "Request ID already retired; no replay performed" }
        // Never evict running requests to admit more work.
        if ((0 until all.length()).count { all.getJSONObject(it).optString("state") in active } >= 16) return false
        val next = JSONArray()
        val keep = (0 until all.length()).map { all.getJSONObject(it) }
        val terminal = keep.filter { it.optString("state") !in active }.takeLast(15)
        (keep.filter { it.optString("state") in active } + terminal).forEach { next.put(it) }
        next.put(JSONObject().put("request_id", r.id).put("command", r.command).put("state", "accepted")
            .put("at", System.currentTimeMillis()).put("process", process).put("fingerprint", r.toString()).put("feedback", r.feedback).put("parts", JSONObject()))
        if (protectReplay) seen.add(r.id)
        check(prefs.edit().putString("automation-results", next.toString())
            .putString("automation-seen", seen.encode()).commit()); true
    }
    fun result(id: String, part: String, state: String, detail: String = "") = synchronized(lock) {
        val all = history()
        for (i in 0 until all.length()) {
            val row = all.getJSONObject(i); if (row.optString("request_id") != id) continue
            if (row.optString("state") !in active) return@synchronized
            val parts = row.getJSONObject("parts")
            if (part == "dispatch") parts.keys().asSequence().toList().forEach { key ->
                val pending = parts.getJSONObject(key)
                if (pending.optString("state") in active) pending.put("state", state).put("error_code", AutomationResult.errorCode(state, key) ?: JSONObject.NULL).put("detail", detail.take(512))
            }
            else if (parts.optJSONObject(part)?.optString("state")?.let { it !in active } == true) return@synchronized
            parts.put(part, JSONObject().put("state", state).put("error_code", AutomationResult.errorCode(state, part) ?: JSONObject.NULL).put("detail", detail.take(512)))
            val states = parts.keys().asSequence().map { parts.getJSONObject(it).getString("state") }.toList()
            val aggregate = AutomationResult.aggregate(states)
            row.put("state", aggregate).put("error_code", AutomationResult.aggregateError(
                parts.keys().asSequence().associateWith { parts.getJSONObject(it).getString("state") }) ?: JSONObject.NULL).put("updated_at", System.currentTimeMillis())
            if (retireTemporary(state)) {
                val affected = if (part == "dispatch") listOf("brightness", "warmth") else listOf(part)
                affected.forEach { key -> if (temporary(key)?.optString("request_id") == id) temporary(key, null) }
            }
            save(all)
            if (aggregate !in active) {
                android.util.Log.i("RedMoonAutomation", "request=$id state=$aggregate")
                if (row.optBoolean("feedback")) android.os.Handler(context.mainLooper).post {
                    Toast.makeText(context, "Red Moon: $aggregate — ${detail.take(180)}", Toast.LENGTH_SHORT).show()
                }
            }
            return@synchronized
        }
    }
    fun start(r: AutomationRequest, parts: List<String>) = synchronized(lock) {
        val all = history()
        for (i in 0 until all.length()) if (all.getJSONObject(i).optString("request_id") == r.id) {
            val row = all.getJSONObject(i); if (row.optString("state") !in active) return@synchronized
            val p = row.getJSONObject("parts")
            parts.forEach { p.put(it, JSONObject().put("state", "running")) }; row.put("state", "running")
                .put("execution_session", TvDisplayService.liveSession); save(all)
        }
    }
    fun interrupted() = synchronized(lock) {
        val all = history()
        for (i in 0 until all.length()) {
            val row = all.getJSONObject(i)
            if (row.optString("state") in active && executionInterrupted(row.optString("process"), process,
                row.optString("execution_session"), TvDisplayService.liveSession)) {
                row.put("state", "failed").put("error", "process_restarted_outcome_unknown").put("error_code", "PROCESS_RESTARTED_OUTCOME_UNKNOWN").put("updated_at", System.currentTimeMillis())
                val parts = row.optJSONObject("parts")
                parts?.keys()?.asSequence()?.toList()?.forEach { key ->
                    val part = parts.getJSONObject(key)
                    if (part.optString("state") in active) part.put("state", "failed").put("error_code", "PROCESS_RESTARTED_OUTCOME_UNKNOWN").put("detail", "process_restarted_outcome_unknown")
                }
                listOf("brightness", "warmth").forEach { key ->
                    if (temporary(key)?.optString("request_id") == row.optString("request_id")) temporary(key, null)
                }
            }
        }; save(all)
    }
    fun stopSession(session: String) = synchronized(lock) {
        val all = history()
        (0 until all.length()).map { all.getJSONObject(it) }.filter {
            it.optString("state") in active && (it.optString("execution_session") == session ||
                (it.optString("execution_session").isEmpty() && it.optString("process") == process))
        }.forEach { result(it.getString("request_id"), "dispatch", "cancelled", "Service execution ended") }
    }
    fun runningIn(session: String): Boolean = synchronized(lock) {
        val all = history()
        (0 until all.length()).any { all.getJSONObject(it).let { row ->
            row.optString("state") in active && row.optString("execution_session") == session
        } }
    }
    fun report(): JSONObject = synchronized(lock) {
        val prefs = TvPreferences(context)
        JSONObject().put("api_version", 1).put("enabled", enabled)
            .put("legacy_enabled", this.prefs.getBoolean("legacy-control", false))
            .put("brightness", temporary("brightness") ?: JSONObject.NULL)
            .put("warmth", temporary("warmth") ?: JSONObject.NULL).put("results", history())
            .put("native_level", prefs.nativeLevel ?: JSONObject.NULL)
            .put("native_read_at", NativeSnapshot.value?.at ?: this.prefs.getLong("native-read-at", 0))
            .put("sample_current_process", NativeSnapshot.value != null)
            .put("native_sample_age_ms", NativeSnapshot.value?.let { android.os.SystemClock.elapsedRealtime() - it.elapsed } ?: JSONObject.NULL)
            .put("current_error", this.prefs.getString("current-native-error", null) ?: JSONObject.NULL)
            .put("native_qualified", prefs.nativeQualified).put("recovery", prefs.sessionState().name)
            .put("runtime", TvDisplayService.runtime()).put("status", prefs.status).put("warmth_status", prefs.warmthStatus)
            .put("manual_hold_until", this.prefs.getLong("manual-hold-until", 0))
            .put("brightness_enabled", prefs.backlightEnabled).put("warmth_enabled", prefs.warmthEnabled)
            .put("at", System.currentTimeMillis())
    }
    private fun save(all: JSONArray) { check(prefs.edit().putString("automation-results", all.toString()).commit()) }
    companion object {
        private val lock = Any()
        private val process = UUID.randomUUID().toString()
        private val active = AutomationResult.active
        fun data(extras: Bundle?): Map<String, Any?> = extras?.keySet()?.associateWith { extras.get(it) } ?: emptyMap()
        fun submit(context: Context, extras: Bundle?): JSONObject {
            val state = AutomationState(context)
            require(state.enabled && extras != null && extras.size() <= 12) { "Integration disabled or request too large" }
            val candidate = extras.getString("token")
            require(candidate != null && candidate.length in 1..128 &&
                (candidate.startsWith("shortcut:") || state.authorised(candidate))) { "Integration disabled or token revoked" }
            val request = AutomationContract.parse(data(extras), UUID.randomUUID().toString())
            require(state.authorised(candidate, request)) { "Integration disabled or token revoked" }
            synchronized(lock) {
                state.find(request.id)?.let {
                    require(it.optString("fingerprint") == request.toString()) { "request_id already used for different arguments" }
                    return it
                }
                check(state.accept(request, extras.containsKey("request_id"))) { "Automation queue busy" }
            }
            try {
                context.startForegroundService(Intent(context, TvDisplayService::class.java).putExtra("command", "automation")
                    .putExtra("automation-extras", Bundle(extras ?: Bundle())).putExtra("automation-id", request.id))
            } catch (error: RuntimeException) {
                state.result(request.id, "dispatch", "failed", "Service unavailable")
            }
            return state.find(request.id)!!
        }
    }
}
