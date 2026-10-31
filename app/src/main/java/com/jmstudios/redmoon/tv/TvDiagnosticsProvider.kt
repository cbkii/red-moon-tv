/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.content.Intent
import org.json.JSONObject
import java.util.UUID

/** Read-only DUMP report; fixed mutation calls additionally require the shell/root UID. */
class TvDiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(uri.path == "/report" && selection == null && selectionArgs == null) { "Only /report is supported" }
        return MatrixCursor(arrayOf("report"), 1).apply {
            addRow(arrayOf(DiagnosticsExporter.report(requireNotNull(context))))
        }
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // ContentProvider.call does not enforce read/write permissions automatically.
        val app = requireNotNull(context)
        require(Binder.getCallingUid() in setOf(0, 2000)) { "ADB shell/root only" }
        if (Binder.getCallingUid() != 0) app.enforceCallingPermission("android.permission.DUMP", "ADB diagnostics")
        require((arg?.length ?: 0) <= 8192 && (extras == null || extras.isEmpty)) { "Bounded JSON argument only" }
        val data = JSONObject(arg ?: "{}")
        TvAdbControl.validate(method, data)
        if (method == "supervise") {
            val identity = Binder.clearCallingIdentity()
            try {
                val prefs = TvPreferences(app)
                val enabled = prefs.backlightEnabled || prefs.warmthEnabled
                if (enabled && !TvDisplayService.active) TvContinuity.ensureRunning(app, "supervisor")
                return Bundle().apply { putString("result", JSONObject().put("enabled", enabled)
                    .put("service_running", TvDisplayService.active).put("runtime", TvDisplayService.runtime()).toString()) }
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        val id = UUID.randomUUID().toString()
        val identity = Binder.clearCallingIdentity()
        try {
            app.startForegroundService(Intent(app, TvDisplayService::class.java).putExtra("command", "adb")
                .putExtra("adb-command", method).putExtra("adb-data", data.toString()).putExtra("adb-id", id))
        } finally { Binder.restoreCallingIdentity(identity) }
        return Bundle().apply { putString("result", JSONObject().put("request_id", id).put("queued", true).toString()) }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.redmoontv.diagnostic"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
}
