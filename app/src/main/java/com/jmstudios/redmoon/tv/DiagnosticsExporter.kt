/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.os.Build
import com.jmstudios.redmoon.BuildConfig
import org.json.JSONObject

object DiagnosticsExporter {
    fun report(context: Context): String {
        val prefs = TvPreferences(context)
        return JSONObject().put("schema", 2).put("package", context.packageName)
            .put("source_sha", BuildConfig.SOURCE_SHA).put("build_id", BuildConfig.BUILD_ID)
            .put("backend_protocol", BuildConfig.BACKEND_PROTOCOL).put("firmware", Build.FINGERPRINT)
            .put("abi", Build.SUPPORTED_ABIS.joinToString(",")).put("status", prefs.status)
            .put("identity_ready", prefs.identityReady)
            .put("first_initialisation_error", prefs.store.getString("first-initialisation-error", "none"))
            .put("native", prefs.store.getString("native-diagnostics", "not read"))
            .put("write_blocked", prefs.store.getBoolean("native-blocked", false))
            .put("last_native_error", prefs.store.getString("last-native-error", "none"))
            .put("warmth", prefs.warmthStatus).put("recovery_pending", RecoveryJournal.exists(context))
            .put("native_acknowledged", prefs.nativeQualified).put("fades_acknowledged", prefs.fadesQualified)
            .put("physical_validation", "User acknowledgement; independent APK-hash acceptance required for release")
            .toString(2)
    }
}
