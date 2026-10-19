package id.fhorizon.mobile.drivers

import id.fhorizon.mobile.core.AppLog
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

data class GpuDriver(val name: String, val folder: File, val libraryName: String?, val description: String?, val vendor: String?, val version: String?)

/**
 * Custom Vulkan drivers (Mesa Turnip for Adreno) in <Save>/drivers, the
 * folder the backend's android_gpu_driver cvar names a driver from
 * (REX_ANDROID_DRIVERS_DIR). The runtime copies the chosen one into internal
 * storage and loads it through libadrenotools at start.
 *
 * Packages are adrenotools zips (meta.json + the driver .so), as published
 * for Android emulators. None are bundled here: Turnip builds are MIT
 * licensed, but which build suits a given Adreno 650 firmware is for the
 * player to choose.
 */
class DriverManager(private val root: File) {

    fun list(): List<GpuDriver> = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
        ?.sortedBy { it.name }
        ?.map { folder ->
            val meta = File(folder, "meta.json").takeIf { it.isFile }?.let {
                try { JSONObject(it.readText()) } catch (e: Exception) { null }
            }
            GpuDriver(
                name = folder.name,
                folder = folder,
                libraryName = meta?.optString("libraryName")?.takeIf { it.isNotEmpty() }
                    ?: folder.listFiles { f -> f.name.endsWith(".so") }?.singleOrNull()?.name,
                description = meta?.optString("description")?.takeIf { it.isNotEmpty() },
                vendor = meta?.optString("vendor")?.takeIf { it.isNotEmpty() },
                version = meta?.optString("driverVersion")?.takeIf { it.isNotEmpty() },
            )
        } ?: emptyList()

    /** Unpacks an adrenotools zip into <root>/<name>; returns the folder name. */
    fun import(displayName: String, input: InputStream): String {
        val name = displayName.replace(Regex("(?i)\\.zip$"), "")
            .replace(Regex("[^A-Za-z0-9._-]+"), "-").lowercase(Locale.ROOT)
            .trim('-', '.').ifEmpty { "driver" }
        root.mkdirs()
        val staging = File(root, ".$name.importing")
        staging.deleteRecursively()
        staging.mkdirs()
        var library = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val file = entry.name.substringAfterLast('/')
                if (file.isEmpty() || file.startsWith(".")) continue
                library = library || file.endsWith(".so")
                FileOutputStream(File(staging, file)).use { zip.copyTo(it) }
            }
        }
        if (!library) {
            staging.deleteRecursively()
            throw IllegalArgumentException("zip ini tidak berisi pustaka driver (.so)")
        }
        val target = File(root, name)
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw IllegalStateException("tidak bisa membuat $target")
        AppLog.i("GPU driver imported: $name")
        return name
    }

    fun delete(name: String): Boolean = File(root, name).deleteRecursively()
}
