/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.AtomicFile
import java.io.File

/** One device-protected store. Migration commits only after its recovery record is durable. */
object TvStorage {
    const val NAME = "tv-display-v1"
    const val JOURNAL = "backlight-recovery-v2.json"
    private const val MIGRATED = "storage-migrated-v1"
    @Synchronized fun context(context: Context): Context {
        val app = context.applicationContext
        val device = app.createDeviceProtectedStorageContext()
        val target = device.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (!target.getBoolean(MIGRATED, false) && app.getSystemService(UserManager::class.java).isUserUnlocked) {
            val old = app.createCredentialProtectedStorageContext()
            val source = AtomicFile(File(old.filesDir, JOURNAL))
            val destination = AtomicFile(File(device.filesDir, JOURNAL))
            if ((source.baseFile.exists() || File(source.baseFile.path + ".bak").exists()) &&
                !destination.baseFile.exists() && !File(destination.baseFile.path + ".bak").exists()) {
                val bytes = source.openRead().use { it.readBytes() }
                val out = destination.startWrite()
                try { out.write(bytes); destination.finishWrite(out) }
                catch (error: Exception) { destination.failWrite(out); throw error }
            }
            val edit = target.edit()
            old.getSharedPreferences(NAME, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                // Any explicit setting made in Direct Boot takes precedence over old CE data.
                if (!target.contains(key)) when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                }
            }
            check(edit.putBoolean(MIGRATED, true).commit()) { "TV settings migration failed" }
        }
        return device
    }
    fun preferences(context: Context): SharedPreferences = this.context(context).getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun ready(context: Context) = preferences(context).getBoolean(MIGRATED, false)
}
