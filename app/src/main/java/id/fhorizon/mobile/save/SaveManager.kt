package id.fhorizon.mobile.save

import id.fhorizon.mobile.core.AppLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class SaveBackup(val file: File, val label: String, val sizeBytes: Long, val modified: Long)

data class ProfileSummary(val exists: Boolean, val files: Int, val bytes: Long, val lastModified: Long)

/**
 * Backs up and restores the game's save profile (<Save>/user, the folder the
 * runtime mounts as the console's storage device). Backups are plain zip
 * files in FHorizon/Backups, so they can be copied to a PC, where Pinyon
 * Shift uses the same profile layout.
 */
class SaveManager(private val profile: File, private val backups: File) {

    fun summary(): ProfileSummary {
        if (!profile.isDirectory) return ProfileSummary(false, 0, 0, 0)
        var files = 0
        var bytes = 0L
        var last = 0L
        profile.walkTopDown().filter { it.isFile }.forEach {
            files++; bytes += it.length(); last = maxOf(last, it.lastModified())
        }
        return ProfileSummary(true, files, bytes, last)
    }

    fun list(): List<SaveBackup> =
        backups.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.map { SaveBackup(it, it.nameWithoutExtension, it.length(), it.lastModified()) }
            ?.sortedByDescending { it.modified }
            ?: emptyList()

    /** Zips the profile into a new backup. Returns null when there is nothing to back up. */
    fun backup(prefix: String = "fh1-save"): SaveBackup? {
        if (!profile.isDirectory || profile.listFiles().isNullOrEmpty()) return null
        backups.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(backups, "$prefix-$stamp.zip")
        FileOutputStream(target).use { zipFolder(profile, it) }
        AppLog.i("Save backed up to ${target.absolutePath} (${target.length()} bytes)")
        return SaveBackup(target, target.nameWithoutExtension, target.length(), target.lastModified())
    }

    /**
     * Replaces the profile with a backup. The current profile is backed up
     * first ("before-restore-..."), so a restore can always be undone.
     */
    fun restore(backup: File): Result<SaveBackup?> = runCatching {
        require(backup.isFile) { "backup tidak ditemukan" }
        validateZip(FileInputStream(backup))
        val safety = this.backup("before-restore")
        val staging = File(profile.parentFile, ".user-restoring")
        staging.deleteRecursively()
        staging.mkdirs()
        FileInputStream(backup).use { unzip(it, staging) }
        val old = File(profile.parentFile, ".user-old")
        old.deleteRecursively()
        if (profile.exists() && !profile.renameTo(old)) error("profil lama tidak bisa dipindahkan")
        if (!staging.renameTo(profile)) {
            old.renameTo(profile)
            error("profil baru tidak bisa dipasang")
        }
        old.deleteRecursively()
        AppLog.i("Save restored from ${backup.name}; previous profile kept as ${safety?.file?.name}")
        safety
    }

    fun delete(backup: File): Boolean = backup.delete()

    fun importBackup(name: String, input: InputStream): File {
        backups.mkdirs()
        val clean = name.replace(Regex("[^A-Za-z0-9._-]+"), "-").removeSuffix(".zip")
        val target = File(backups, "$clean.zip")
        FileOutputStream(target).use { out -> input.copyTo(out) }
        validateZip(FileInputStream(target))
        return target
    }

    fun export(backup: File, output: OutputStream) {
        FileInputStream(backup).use { it.copyTo(output) }
    }

    companion object {
        fun zipFolder(folder: File, output: OutputStream) {
            ZipOutputStream(output.buffered()).use { zip ->
                folder.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = file.relativeTo(folder).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

        /** Extracts a zip, refusing entries that would land outside [target]. */
        fun unzip(input: InputStream, target: File) {
            val root = target.canonicalFile
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(root, entry.name).canonicalFile
                    require(out.path.startsWith(root.path + File.separator)) { "entri zip tidak aman: ${entry.name}" }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zip.copyTo(it) }
                        if (entry.time > 0) out.setLastModified(entry.time)
                    }
                }
            }
        }

        fun validateZip(input: InputStream) {
            var count = 0
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.name.contains("..")) { "entri zip tidak aman: ${entry.name}" }
                    count++
                }
            }
            require(count > 0) { "zip kosong atau rusak" }
        }
    }
}
