/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Small dedicated atomic record. Never rewrites the preferences XML on fade steps. */
class RecoveryJournal(context: Context) : RecoveryStore {
    private val file = AtomicFile(File(context.filesDir, "backlight-recovery-v2.json"))
    @Synchronized override fun load(): RecoveryRecord? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val data = JSONObject(file.openRead().use { String(it.readBytes(), Charsets.UTF_8) })
        check(data.getInt("schema") == 2) { "Unknown recovery journal; writes remain paused" }
        return RecoveryRecord(data.getInt("original"), data.getInt("confirmed"),
            if (data.isNull("pending")) null else data.getInt("pending"), data.getString("context"),
            data.getString("kind"), data.getString("session")).also {
            check(it.original in 0..100 && it.confirmed in 0..100 && (it.pending == null || it.pending in 0..100)) { "Invalid recovery levels" }
        }
    }
    @Synchronized override fun save(record: RecoveryRecord) {
        val data = JSONObject().put("schema", 2).put("original", record.original).put("confirmed", record.confirmed)
            .put("pending", record.pending ?: JSONObject.NULL).put("context", record.context)
            .put("kind", record.kind).put("session", record.session)
        val out = file.startWrite()
        try { out.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    @Synchronized override fun clear() { file.delete() }
    companion object {
        fun exists(context: Context): Boolean {
            val base = File(context.filesDir, "backlight-recovery-v2.json")
            return base.exists() || File(base.path + ".bak").exists()
        }
    }
}
