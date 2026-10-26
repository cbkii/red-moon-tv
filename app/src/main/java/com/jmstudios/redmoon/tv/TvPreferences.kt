/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context

class TvPreferences(private val context: Context) {
    val store = context.getSharedPreferences("tv-display-v1", Context.MODE_PRIVATE)
    var backlightEnabled: Boolean
        get() = store.getBoolean("backlight", false)
        set(value) { store.edit().putBoolean("backlight", value).apply() }
    val signature: String
        get() = android.os.Build.FINGERPRINT + ":" + context.packageManager.getPackageInfo("com.android.tv.settings", 0).lastUpdateTime
    val probeComplete: Boolean
        get() = store.getBoolean("probe-restored", false) && store.getString("probe-signature", "") == signature
    var nativeQualified: Boolean
        get() = store.getBoolean("native-qualified", false) && store.getString("qualified-signature", "") == signature
        set(value) { store.edit().putBoolean("native-qualified", value).putString("qualified-signature", signature).apply() }
    var fadesQualified: Boolean
        get() = nativeQualified && store.getBoolean("fades-qualified", false)
        set(value) { store.edit().putBoolean("fades-qualified", value).apply() }
    var warmthEnabled: Boolean
        get() = store.getBoolean("warmth", false)
        set(value) { store.edit().putBoolean("warmth", value).apply() }
    var linked: Boolean
        get() = store.getBoolean("linked", true)
        set(value) { store.edit().putBoolean("linked", value).apply() }
    var status: String
        get() = store.getString("status", "Native control has not been tested on this TV.")!!
        set(value) { store.edit().putString("status", value).apply() }
    var warmthStatus: String
        get() = store.getString("warmth-status", "Warmth off")!!
        set(value) { store.edit().putString("warmth-status", value).apply() }
    fun policy(): BrightnessPolicy = BrightnessPolicy(BrightnessPolicy.defaults().settings.map {
        it.copy(level = store.getInt("${it.phase}-level", it.level),
            startMinute = store.getInt("${it.phase}-start", it.startMinute),
            fadeSeconds = store.getInt("${it.phase}-fade", it.fadeSeconds))
    })
    fun save(policy: BrightnessPolicy) { store.edit().apply {
        policy.settings.forEach { putInt("${it.phase}-level", it.level); putInt("${it.phase}-start", it.startMinute); putInt("${it.phase}-fade", it.fadeSeconds) }
    }.apply() }
    fun warmth(phase: Phase): Int = store.getInt("warmth-$phase", when (phase) { Phase.DAY -> 0; Phase.DUSK -> 20; Phase.DIM -> 40 })
    var warmthStart: Int
        get() = store.getInt("warmth-start", 18 * 60 + 30)
        set(value) { require(value in 0..1439); store.edit().putInt("warmth-start", value).apply() }
    var warmthEnd: Int
        get() = store.getInt("warmth-end", 7 * 60)
        set(value) { require(value in 0..1439); store.edit().putInt("warmth-end", value).apply() }
    var warmthFade: Int
        get() = store.getInt("warmth-fade", 30)
        set(value) { require(value in 0..888); store.edit().putInt("warmth-fade", value).apply() }
}
