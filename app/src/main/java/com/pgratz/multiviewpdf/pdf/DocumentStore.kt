package com.pgratz.multiviewpdf.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/**
 * Moves PDFs between Storage Access Framework URIs and a private working copy that MuPDF
 * opens by path (MuPDF's incremental save needs a real, seekable file it can append to).
 */
class DocumentStore(private val context: Context) {

    data class Imported(val file: File, val name: String, val fingerprint: String)

    private val workDir = File(context.cacheDir, "work").apply { mkdirs() }

    suspend fun import(uri: Uri): Imported = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val file = File(workDir, "current.pdf")
        val digest = MessageDigest.getInstance("SHA-256")
        var hashed = 0
        val input = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Can't open $uri")
        input.use { ins ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    // Fingerprint the head of the file: incremental saves only append, so
                    // annotating a document doesn't lose its remembered view state.
                    if (hashed < FINGERPRINT_BYTES) {
                        val take = minOf(n, FINGERPRINT_BYTES - hashed)
                        digest.update(buf, 0, take)
                        hashed += take
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        val fp = digest.digest().joinToString("") { "%02x".format(it) }.take(32)
        Imported(file, name, fp)
    }

    /** Copies the working file back over the original document. */
    suspend fun writeBack(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        val out = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            // Some providers don't support the "truncate" mode string.
            context.contentResolver.openOutputStream(uri, "w")
        } ?: throw IOException("Can't write to $uri")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Keeps access to [uri] across restarts (for the recent-files list and saving). */
    fun persistPermission(uri: Uri) {
        val cr = context.contentResolver
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            cr.takePersistableUriPermission(uri, rw)
        } catch (e: SecurityException) {
            try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Transient grant only (e.g. opened from an email); fine for this session.
            }
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "document.pdf"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { return it } }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    companion object {
        private const val FINGERPRINT_BYTES = 64 * 1024
    }
}
