/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import org.json.JSONObject

/** Fixed ADB-only operations. No reflection names, shell fragments, files or raw vendor settings. */
object TvAdbControl {
    val commands = setOf("read", "read_restart", "use_root_read", "use_apk_read", "probe", "restore", "keep",
        "pause", "resume", "phase", "warmth", "warmth_off", "boundary", "test_fade", "configure",
        "save_config", "restore_config", "acknowledge", "close_worker", "ensure_running", "fault", "supervise")
    fun validate(command: String, data: JSONObject) {
        require(command in commands) { "Unknown ADB command" }
        val keys = when (command) {
            "fault" -> setOf("fault")
            "phase" -> setOf("phase")
            "test_fade" -> setOf("seconds", "delta")
            "configure" -> setOf("phases", "warmth", "linked", "warmth_start", "warmth_end", "warmth_fade", "strength", "colour", "transition_mode", "enforce_schedule", "external_control")
            "acknowledge" -> setOf("native", "fades", "persistence_checked")
            else -> emptySet()
        }
        require(data.keys().asSequence().all { it in keys }) { "Unknown ADB argument" }
        when (command) {
            "fault" -> require(data.getString("fault") in setOf("detach_overlay", "stop_service", "read_unavailable", "access_denied", "lost_write_reply", "clear"))
            "phase" -> Phase.valueOf(data.getString("phase"))
            "test_fade" -> { require(data.getInt("seconds") in 0..888); require(data.getInt("delta") in -20..20 && data.getInt("delta") != 0) }
            "configure" -> policy(data) // Also validates warmth, before changing anything.
            "acknowledge" -> {
                require(data.optBoolean("native", false)) { "Explicit visible native acknowledgement required" }
                require(!data.optBoolean("fades", false) || data.optBoolean("persistence_checked", false)) { "Persistence evidence required" }
            }
        }
    }
    fun policy(data: JSONObject): BrightnessPolicy? {
        if (data.has("transition_mode")) require(data.getString("transition_mode") in setOf("strict", "smooth"))
        for (key in listOf("warmth", "linked", "enforce_schedule", "external_control")) if (data.has(key)) require(data.get(key) is Boolean)
        for (key in listOf("strength", "colour")) if (data.has(key)) require(data.getInt(key) in 0..100)
        for (key in listOf("warmth_start", "warmth_end")) if (data.has(key)) require(data.getInt(key) in 0..1439)
        if (data.has("warmth_fade")) require(data.getInt("warmth_fade") in 0..888)
        if (!data.has("phases")) return null
        val list = data.getJSONArray("phases"); require(list.length() == 3)
        return BrightnessPolicy(Phase.values().mapIndexed { i, phase ->
            val item = list.getJSONObject(i)
            require(item.keys().asSequence().toSet() == setOf("phase", "level", "start", "fade"))
            require(item.getString("phase") == phase.name)
            PhaseSetting(phase, item.getInt("level"), item.getInt("start"), item.getInt("fade"))
        })
    }
}
