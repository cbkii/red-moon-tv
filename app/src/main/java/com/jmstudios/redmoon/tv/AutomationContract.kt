/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** Platform-free, strict v1 wire contract shared by broadcasts, shortcuts and ADB. */
data class AutomationRequest(val id: String, val command: String, val scope: String,
    val phase: Phase?, val value: Int?, val delta: Int?, val enabled: Boolean?,
    val fade: Int?, val expiry: String, val seconds: Int?, val feedback: Boolean) {
    fun affects(part: String) = scope == "both" || scope == part
}
object AutomationContract {
    const val ACTION = "com.cbkii.redmoontv.AUTOMATION"
    val commands = setOf("brightness_pause", "brightness_resume", "brightness_phase", "brightness_set",
        "brightness_adjust", "warmth_enable", "warmth_set", "warmth_adjust", "preset_apply", "snooze",
        "resume_auto", "cancel_transition", "reconcile", "show_status", "open_controls")
    private val common = setOf("api_version", "command", "request_id", "token", "feedback")
    fun parse(data: Map<String, Any?>, generatedId: String): AutomationRequest {
        fun str(key: String, default: String? = null): String? = if (!data.containsKey(key)) default
            else (data[key] as? String ?: throw IllegalArgumentException("$key must be a string"))
        fun int(key: String, range: IntRange): Int? {
            if (!data.containsKey(key)) return null
            val v = data[key]; require(v is Int && v in range) { "$key must be an integer in $range" }; return v
        }
        fun bool(key: String): Boolean? {
            if (!data.containsKey(key)) return null
            return data[key] as? Boolean ?: throw IllegalArgumentException("$key must be boolean")
        }
        require(int("api_version", 1..1) == 1) { "api_version=1 required" }
        val command = requireNotNull(str("command")) { "command required" }; require(command in commands) { "Unknown command" }
        val temporal = setOf("expiry", "duration_seconds", "fade_seconds")
        val specific = when (command) {
            "brightness_phase" -> setOf("phase") + temporal
            "brightness_set", "warmth_set" -> setOf("value") + temporal
            "brightness_adjust", "warmth_adjust" -> setOf("delta") + temporal
            "warmth_enable" -> setOf("enabled")
            "preset_apply" -> setOf("phase", "scope") + temporal
            "snooze" -> setOf("scope", "duration_seconds")
            "resume_auto", "cancel_transition" -> setOf("scope")
            else -> emptySet()
        }
        require(data.keys.all { it in common + specific }) { "Unknown argument" }
        require(data.size <= 12 && data.values.all { it is String || it is Int || it is Boolean }) { "Primitive extras only" }
        require(data.values.filterIsInstance<String>().all { it.length <= 256 }) { "Argument too long" }
        val id = str("request_id", generatedId)!!
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid request_id" }
        val scope = str("scope", when {
            command.startsWith("brightness_") -> "brightness"
            command.startsWith("warmth_") -> "warmth"
            else -> "both"
        })!!
        require(scope in setOf("brightness", "warmth", "both")) { "Invalid scope" }
        val phase = str("phase")?.let { value -> Phase.values().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("phase must be DAY, DUSK or DIM") }
        if (command in setOf("brightness_phase", "preset_apply")) require(phase != null) { "phase required" }
        val value = int("value", if (command == "brightness_set") 1..100 else 0..100)
        if (command.endsWith("_set")) require(value != null) { "value required" }
        val delta = int("delta", -20..20)
        if (command.endsWith("_adjust")) require(delta != null && delta != 0) { "Nonzero delta required" }
        val enabled = bool("enabled")
        if (command == "warmth_enable") require(enabled != null) { "enabled required" }
        val expiry = str("expiry", if (command == "snooze") "duration" else "next_boundary")!!
        require(expiry in setOf("next_boundary", "duration", "until_resumed")) { "Invalid expiry" }
        val seconds = int("duration_seconds", 1..86400)
        require((expiry == "duration") == (seconds != null)) { "duration expiry requires duration_seconds only" }
        return AutomationRequest(id, command, scope, phase, value, delta, enabled,
            int("fade_seconds", 0..888), expiry, seconds, bool("feedback") ?: false)
    }
    fun expiry(request: AutomationRequest, now: Long, nextBoundary: Long): Long = when (request.expiry) {
        "until_resumed" -> 0L
        "duration" -> now + requireNotNull(request.seconds) * 1000L
        else -> nextBoundary
    }
    fun needsRelativeRead(command: String, hasDesiredTarget: Boolean, sampleAgeMillis: Long?): Boolean =
        command == "brightness_adjust" && !hasDesiredTarget && (sampleAgeMillis == null || sampleAgeMillis !in 0..5000)
    /** Latest desired target, rather than stale readback, accumulates held-button presses. */
    fun adjusted(current: Int, delta: Int, band: IntRange) = (current + delta).coerceIn(band)
}
