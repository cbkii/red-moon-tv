/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Small dedicated atomic record. Never rewrites the preferences XML on fade steps. */
class RecoveryJournal(context: Context) : RecoveryStore {
    private val file = AtomicFile(File(TvStorage.context(context).filesDir, "backlight-recovery-v2.json"))
    override fun load(): RecoveryRecord? = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
        val data = JSONObject(file.openRead().use { String(it.readBytes(), Charsets.UTF_8) })
        check(data.getInt("schema") in 2..3) { "Unknown recovery journal; writes remain paused" }
        RecoveryRecord(data.getInt("original"), data.getInt("confirmed"),
            if (data.isNull("pending")) null else data.getInt("pending"), data.getString("context"),
            data.getString("kind"), data.getString("session"), data.optString("identity", "")).also {
            check(it.original in 0..100 && it.confirmed in 0..100 && (it.pending == null || it.pending in 0..100)) { "Invalid recovery levels" }
        }
    }
    override fun save(record: RecoveryRecord) = synchronized(lock) {
        val data = JSONObject().put("schema", 3).put("original", record.original).put("confirmed", record.confirmed)
            .put("pending", record.pending ?: JSONObject.NULL).put("context", record.context)
            .put("kind", record.kind).put("session", record.session).put("identity", record.identity)
        val out = file.startWrite()
        try { out.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    override fun archive(record: RecoveryRecord) = synchronized(lock) {
        // One bounded previous-boot/context record; never an automatic restore instruction.
        val previous = AtomicFile(File(file.baseFile.parentFile, "backlight-previous-epoch.json"))
        val out = previous.startWrite()
        try {
            out.write(JSONObject().put("original", record.original).put("confirmed", record.confirmed)
                .put("context", record.context).put("identity", record.identity).toString().toByteArray(Charsets.UTF_8))
            previous.finishWrite(out)
        } catch (error: Exception) { previous.failWrite(out); throw error }
        file.delete()
    }
    override fun clear() = synchronized(lock) { file.delete() }
    companion object {
        // Every UI/provider/controller instance touches the same AtomicFile.
        private val lock = Any()
        fun exists(context: Context): Boolean = synchronized(lock) {
            val base = File(TvStorage.context(context).filesDir, "backlight-recovery-v2.json")
            base.exists() || File(base.path + ".bak").exists()
        }
    }
}
