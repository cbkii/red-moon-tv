/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import com.jmstudios.redmoon.BuildConfig
import java.io.File
import java.security.MessageDigest

class TvPreferences(private val context: Context) {
    val store = context.getSharedPreferences("tv-display-v1", Context.MODE_PRIVATE)
    var backlightEnabled: Boolean
        get() = store.getBoolean("backlight", false)
        set(value) { store.edit().putBoolean("backlight", value).apply() }
    val identityReady: Boolean get() = identity != null
    val signature: String get() = identity ?: ""
    fun prepareIdentity(ready: () -> Unit = {}) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        synchronized(TvPreferences::class.java) {
            if (identity != null) { main.post { ready() }; return }
            listeners.add(ready)
            if (checking) return
            checking = true
        }
        kotlin.concurrent.thread(name = "a4-vendor-identity", isDaemon = true) {
            val binding = try {
                val info = app.packageManager.getApplicationInfo("com.android.tv.settings", android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES)
                (listOf(info.sourceDir) + (info.sharedLibraryFiles?.toList() ?: emptyList())).joinToString(":") { digest(File(it)) }
            } catch (_: Exception) { "binding-unavailable" }
            val result = android.os.Build.FINGERPRINT + ":" + BuildConfig.BACKEND_PROTOCOL + ":" + BuildConfig.SOURCE_SHA +
                ":" + BuildConfig.BUILD_ID + ":" + binding + ":" + digest(File("/vendor/lib/libcom_mediatek_twoworlds_tv_jni.so"))
            val callbacks = synchronized(TvPreferences::class.java) {
                identity = result; checking = false
                listeners.toList().also { listeners.clear() }
            }
            main.post { callbacks.forEach { it() } }
        }
    }
    companion object {
        @Volatile private var identity: String? = null
        private var checking = false
        private val listeners = mutableListOf<() -> Unit>()
        private fun digest(file: File): String = try {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val bytes = ByteArray(32768); while (true) {
                val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count)
            } }
            hash.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "unreadable:${file.name}" }
    }
    val probeComplete: Boolean
        get() = identityReady && store.getBoolean("probe-restored", false) && store.getString("probe-signature", "") == signature
    var nativeQualified: Boolean
        get() = identityReady && store.getBoolean("native-qualified", false) && store.getString("qualified-signature", "") == signature
        set(value) {
            require(!value || identityReady) { "Vendor identity is still being checked" }
            val changed = store.getString("qualified-signature", "") != signature
            store.edit().apply {
                putBoolean("native-qualified", value); putString("qualified-signature", signature)
                if (!value || changed) putBoolean("fades-qualified", false)
            }.apply()
        }
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
