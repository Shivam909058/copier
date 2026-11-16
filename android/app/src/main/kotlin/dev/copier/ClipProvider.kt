package dev.copier

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/**
 * Serves images received for the clipboard. Not exported: apps that paste get a temporary grant from
 * the clipboard service (grantUriPermissions), and only for the clip they read.
 */
class ClipProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no file in $uri")
        if (!SAFE_NAME.matches(name)) throw FileNotFoundException("invalid clip name")
        val context = context ?: throw FileNotFoundException("provider not attached")
        return File(File(context.cacheDir, DIR), name).takeIf { it.isFile } ?: throw FileNotFoundException("clip expired")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("clips are read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.', ""))

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    companion object {
        private const val AUTHORITY = "dev.copier.clips"
        private const val DIR = "clips"
        private const val KEEP = 5
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

        /** A fresh file for an incoming clipboard image; older ones are pruned (only the latest matters). */
        fun newFile(context: Context, mime: String): File {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP - 1)?.forEach { it.delete() }
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            return File(dir, "${UUID.randomUUID()}.$ext")
        }

        fun uriFor(file: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.name).build()
    }
}
