/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** ADB shell has DUMP; ordinary apps do not. This endpoint never starts JNI or a setter. */
class TvDiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(uri.path == "/report" && selection == null && selectionArgs == null) { "Only /report is supported" }
        return MatrixCursor(arrayOf("report"), 1).apply {
            addRow(arrayOf(DiagnosticsExporter.report(requireNotNull(context))))
        }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.redmoontv.diagnostic"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
}
