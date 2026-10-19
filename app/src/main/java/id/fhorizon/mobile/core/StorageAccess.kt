package id.fhorizon.mobile.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

object StorageAccess {
    /** Whether native code may open files under /storage/emulated/0 by path. */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** The settings page where the player grants "All files access". */
    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        }

    /**
     * A file-system path for a Storage Access Framework tree URI from the
     * system's external storage provider: "primary:FHorizon/Game" is
     * /storage/emulated/0/FHorizon/Game, "1234-ABCD:Games" is /storage/1234-ABCD/Games.
     * Null for other providers (cloud drives), which native code cannot read.
     */
    fun treeUriToPath(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return documentIdToPath(documentId)
    }

    fun documentIdToPath(documentId: String): File? {
        val colon = documentId.indexOf(':')
        if (colon < 0) return null
        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1).trimStart('/')
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage", volume)
        }
        return if (relative.isEmpty()) base else File(base, relative)
    }

    fun freeBytes(dir: File): Long {
        var probe: File? = dir
        while (probe != null && !probe.exists()) probe = probe.parentFile
        return try {
            StatFs((probe ?: dir).absolutePath).availableBytes
        } catch (e: Exception) {
            -1L
        }
    }
}
