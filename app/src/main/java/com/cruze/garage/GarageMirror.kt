package com.cruze.garage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * A copy of the garage in shared storage, so it survives the app being uninstalled.
 *
 * The rule for this app is that the garage never resets on reinstall, and the app's own
 * files directory cannot deliver that on its own: it is deleted with the app, and Android's
 * Auto Backup restores it only when the rider has Google backup switched on, only from a fresh
 * install, and never on a developer reinstall. Files an app creates in the shared Documents
 * collection are the one place that outlives an uninstall with no account and no API key, which
 * is the constraint this app is built under.
 *
 * Everything here is best-effort. A failure to mirror must never stop a bike being saved, so
 * every call swallows its errors — the real garage is still the file in filesDir.
 */
class GarageMirror(private val context: Context) {

    private companion object {
        const val DIR = "Documents/Cruze"
        const val NAME = "garage.json"
    }

    fun write(text: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) writeViaMediaStore(text) else writeLegacy(text)
        }
    }

    fun read(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) readViaMediaStore() else readLegacy()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // --- Android 10+ ---------------------------------------------------------------------
    //
    // Scoped storage: no permission is needed to create or reopen our own file in Documents.

    private fun writeViaMediaStore(text: String) {
        val existing = findMirror()
        val uri = existing ?: context.contentResolver.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            },
        ) ?: return
        // "wt" truncates first, so a shorter garage does not leave the tail of the old one.
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
    }

    private fun readViaMediaStore(): String? {
        val uri = findMirror() ?: return null
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
    }

    private fun findMirror(): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("$DIR%", NAME),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString())
        }
        return null
    }

    // --- Android 9 and below -------------------------------------------------------------
    //
    // Legacy storage. Writing here needs WRITE_EXTERNAL_STORAGE, which this app deliberately
    // does not ask for, so on these versions the mirror is skipped and Auto Backup is the only
    // safety net. Reading a mirror left by a newer Android is still worth trying.

    private fun legacyFile() =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Cruze/$NAME")

    private fun writeLegacy(text: String) {
        val f = legacyFile()
        if (f.parentFile?.exists() != true && f.parentFile?.mkdirs() != true) return
        f.writeText(text)
    }

    private fun readLegacy(): String? = legacyFile().takeIf { it.exists() }?.readText()
}
