package ru.lumastack.lumawork.data.simpleone

import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import ru.lumastack.lumawork.LumaWorkApplication

/** Recheck grants when opened: a URI issued before SO logout must not bypass the gate. */
class ProtectedFileProvider : FileProvider() {
    private fun checkAccess(uri: Uri) {
        val protectedRoots = setOf("ftp_downloads", "ftp_saved_downloads", "wiki_pdfs", "ftp-downloads", "wiki-pdfs")
        if (uri.pathSegments.any { it in protectedRoots }) {
            (context!!.applicationContext as LumaWorkApplication).container.simpleOneAccess.requireCurrent()
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        checkAccess(uri)
        return super.openFile(uri, mode)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkAccess(uri)
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }
    override fun getType(uri: Uri): String? { checkAccess(uri); return super.getType(uri) }
}
