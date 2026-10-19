package id.fhorizon.mobile.module

import android.content.Context
import id.fhorizon.mobile.core.AppLog
import id.fhorizon.mobile.core.FhPaths
import id.fhorizon.mobile.core.NativeCore
import id.fhorizon.mobile.core.objects
import id.fhorizon.mobile.core.parseJsonOrNull
import id.fhorizon.mobile.core.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/** A backend module that passed validation and is installed in files/modules/active. */
data class InstalledModule(
    val directory: File,
    val sourceName: String,
    val kind: String,
    val installedUtc: String,
    val loadOrder: List<String>,
    val sideLibraries: List<String>,
    val sdlVersion: String?,
    val pinyonCommit: String?,
    val rexglueCommit: String?,
    val buildVersion: String?,
    val buildCreatedUtc: String?,
    val titleUpdateV4: Boolean,
    val libmainSha256: String?,
    val totalBytes: Long,
    val warnings: List<String>,
    val libraries: List<String>,
) {
    val knownBackend: SdlCompat.KnownBackend? get() = SdlCompat.findKnown(rexglueCommit, pinyonCommit)
    val sdlMatches: Boolean get() = sdlVersion == SdlCompat.JAVA_SDL_VERSION
    val buildManifestFile: File get() = File(directory, "meta/pinyon_shift_build.json")

    fun libraryPath(name: String): File = File(directory, name)

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("source_name", sourceName)
        .put("kind", kind)
        .put("installed_utc", installedUtc)
        .put("load_order", JSONArray(loadOrder))
        .put("side_libraries", JSONArray(sideLibraries))
        .put("sdl_version", sdlVersion ?: JSONObject.NULL)
        .put("pinyon_commit", pinyonCommit ?: JSONObject.NULL)
        .put("rexglue_commit", rexglueCommit ?: JSONObject.NULL)
        .put("build_version", buildVersion ?: JSONObject.NULL)
        .put("build_created_utc", buildCreatedUtc ?: JSONObject.NULL)
        .put("title_update_v4", titleUpdateV4)
        .put("libmain_sha256", libmainSha256 ?: JSONObject.NULL)
        .put("total_bytes", totalBytes)
        .put("warnings", JSONArray(warnings))
        .put("libraries", JSONArray(libraries))

    companion object {
        const val FILE = "installed.json"

        fun read(directory: File): InstalledModule? {
            val json = File(directory, FILE).takeIf { it.isFile }?.readText()?.let { parseJsonOrNull(it) }
                ?: return null
            fun s(name: String) = if (json.isNull(name)) null else json.optString(name, null)
            return InstalledModule(
                directory = directory,
                sourceName = json.optString("source_name"),
                kind = json.optString("kind"),
                installedUtc = json.optString("installed_utc"),
                loadOrder = json.optJSONArray("load_order").strings(),
                sideLibraries = json.optJSONArray("side_libraries").strings(),
                sdlVersion = s("sdl_version"),
                pinyonCommit = s("pinyon_commit"),
                rexglueCommit = s("rexglue_commit"),
                buildVersion = s("build_version"),
                buildCreatedUtc = s("build_created_utc"),
                titleUpdateV4 = json.optBoolean("title_update_v4"),
                libmainSha256 = s("libmain_sha256"),
                totalBytes = json.optLong("total_bytes"),
                warnings = json.optJSONArray("warnings").strings(),
                libraries = json.optJSONArray("libraries").strings(),
            )
        }
    }
}

data class ModuleImportResult(
    val ok: Boolean,
    val module: InstalledModule?,
    val errors: List<String>,
    val warnings: List<String>,
    val inspectorJson: String?,
)

/**
 * Imports and validates backend modules.
 *
 * Accepted inputs (both are zip files):
 *  - an FHorizon module made by tools/fhorizon_module.py: module.json,
 *    lib/<name>.so, meta/pinyon_shift_build.json;
 *  - the Android package Pinyon Shift's own tools build on the player's PC
 *    (pinyon.py android build): lib/arm64-v8a/<name>.so and
 *    assets/pinyon_shift_build.json. Only its native libraries and manifest
 *    are taken; its Java code is not used (this APK has its own).
 *
 * Nothing is executed during import: libraries are read as ELF files only.
 */
class ModuleManager(private val context: Context) {

    companion object {
        val REQUIRED_LIBRARIES = listOf("libmain.so", "librexruntime.so", "libc++_shared.so")
        const val GPU_PLUGIN = "librexgpu-fh1.so"
        val ADRENOTOOLS_HOOKS = listOf("libhook_impl.so", "libmain_hook.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so")
        private const val MAX_LIBRARY_BYTES = 2L * 1024 * 1024 * 1024
    }

    fun installed(): InstalledModule? = InstalledModule.read(FhPaths.activeModule(context))

    fun import(sourceName: String, open: () -> InputStream, progress: (String) -> Unit = {}): ModuleImportResult {
        val root = FhPaths.modulesRoot(context).apply { mkdirs() }
        root.listFiles()?.filter { it.name.startsWith("staging-") || it.name.startsWith("old-") }
            ?.forEach { it.deleteRecursively() }
        val staging = File(root, "staging-" + System.currentTimeMillis())
        staging.mkdirs()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        try {
            progress("Membaca $sourceName")
            val kind = extract(open, staging, errors)
            if (errors.isNotEmpty()) return fail(staging, errors, warnings, null)

            progress("Memeriksa pustaka ELF")
            val inspectorJson = NativeCore.module(staging.absolutePath, "libmain.so")
            val inspector = parseJsonOrNull(inspectorJson)
                ?: return fail(staging, listOf("inspector output unreadable"), warnings, inspectorJson)
            val plan = inspector.optJSONObject("plan") ?: JSONObject()
            errors += plan.optJSONArray("errors").strings()
            warnings += plan.optJSONArray("warnings").strings()

            val libraries = inspector.optJSONArray("libraries").objects()
            val names = libraries.map { it.optString("file") }
            for (required in REQUIRED_LIBRARIES) {
                if (required !in names) errors += "modul tidak berisi $required"
            }
            if (GPU_PLUGIN !in names) {
                errors += "modul tidak berisi $GPU_PLUGIN (plugin renderer Xenos→Vulkan)"
            }
            val missingHooks = ADRENOTOOLS_HOOKS.filter { it !in names }
            if (missingHooks.isNotEmpty()) {
                warnings += "hook adrenotools tidak lengkap (${missingHooks.joinToString()}): driver GPU custom (Turnip) tidak bisa dimuat, driver sistem tetap bisa"
            }
            val sdlVersions = libraries.mapNotNull { it.optString("sdl_version").takeIf { v -> v.isNotEmpty() } }.distinct()
            val sdlVersion = sdlVersions.firstOrNull()
            when {
                sdlVersions.size > 1 -> errors += "modul berisi beberapa versi SDL: ${sdlVersions.joinToString()}"
                sdlVersion == null -> warnings += "versi SDL di modul tidak terdeteksi; kecocokan dengan launcher diperiksa saat start"
                sdlVersion != SdlCompat.JAVA_SDL_VERSION -> errors +=
                    "modul dibangun dengan SDL $sdlVersion, launcher ini membawa SDL ${SdlCompat.JAVA_SDL_VERSION}. " +
                    "Bangun ulang modul dari commit Pinyon Shift ${SdlCompat.pinnedPinyonCommit.take(7)}."
            }

            val build = File(staging, "meta/pinyon_shift_build.json").takeIf { it.isFile }
                ?.readText()?.let { parseJsonOrNull(it) }
            if (build == null) {
                warnings += "manifest build (pinyon_shift_build.json) tidak ada; asal modul tidak bisa dicatat"
            } else if (build.optString("platform") != "android-arm64") {
                errors += "modul dibangun untuk ${build.optString("platform")}, bukan android-arm64"
            }
            val moduleJson = File(staging, "meta/module.json").takeIf { it.isFile }
                ?.readText()?.let { parseJsonOrNull(it) }
            val pinyonCommit = build?.optString("pinyon_shift_commit")?.takeIf { it.isNotEmpty() && it != "unknown" }
                ?: moduleJson?.optString("pinyon_commit")?.takeIf { it.isNotEmpty() }
            val rexglueCommit = build?.optString("rexglue_commit")?.takeIf { it.isNotEmpty() && it != "unknown" }
                ?: moduleJson?.optString("rexglue_commit")?.takeIf { it.isNotEmpty() }
            if (SdlCompat.findKnown(rexglueCommit, pinyonCommit) == null) {
                warnings += "kombinasi backend ini (Pinyon ${pinyonCommit?.take(7) ?: "?"}, ShiftGlue ${rexglueCommit?.take(7) ?: "?"}) belum diuji dengan launcher ini"
            }
            if (errors.isNotEmpty()) return fail(staging, errors, warnings, inspectorJson)

            progress("Menghitung SHA-256 libmain.so")
            val libmain = File(staging, "libmain.so")
            val module = InstalledModule(
                directory = FhPaths.activeModule(context),
                sourceName = sourceName,
                kind = kind,
                installedUtc = utcNow(),
                loadOrder = plan.optJSONArray("load_order").strings(),
                sideLibraries = plan.optJSONArray("side_libraries").strings(),
                sdlVersion = sdlVersion,
                pinyonCommit = pinyonCommit,
                rexglueCommit = rexglueCommit,
                buildVersion = build?.optString("version"),
                buildCreatedUtc = build?.optString("created_utc"),
                titleUpdateV4 = moduleJson?.optBoolean("title_update_v4") ?: false,
                libmainSha256 = sha256(libmain),
                totalBytes = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                warnings = warnings.toList(),
                libraries = names,
            )
            File(staging, InstalledModule.FILE).writeText(module.toJson().toString(2))
            installBundledDrivers(staging, warnings)

            progress("Memasang modul")
            val active = FhPaths.activeModule(context)
            val old = File(root, "old-" + System.currentTimeMillis())
            if (active.exists() && !active.renameTo(old)) {
                return fail(staging, listOf("tidak bisa mengganti modul lama"), warnings, inspectorJson)
            }
            if (!staging.renameTo(active)) {
                old.renameTo(active)
                return fail(staging, listOf("tidak bisa memasang modul"), warnings, inspectorJson)
            }
            old.deleteRecursively()
            active.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { it.setWritable(false, false) }
            AppLog.i("Backend module installed from $sourceName: ${module.loadOrder.joinToString()}")
            return ModuleImportResult(true, module, emptyList(), warnings, inspectorJson)
        } catch (e: Exception) {
            AppLog.e("module import failed", e)
            return fail(staging, listOf(e.message ?: e.javaClass.simpleName), warnings, null)
        }
    }

    fun remove(): Boolean {
        val active = FhPaths.activeModule(context)
        active.walkTopDown().forEach { it.setWritable(true, true) }
        return active.deleteRecursively().also { AppLog.i("Backend module removed: $it") }
    }

    /** Re-inspects the installed module (after an app update, or on request). */
    fun reinspect(): String? {
        val module = installed() ?: return null
        return NativeCore.module(module.directory.absolutePath, "libmain.so")
    }

    private fun extract(open: () -> InputStream, staging: File, errors: MutableList<String>): String {
        var sawApkLayout = false
        var sawModuleLayout = false
        val otherAbis = mutableSetOf<String>()
        var libraries = 0
        File(staging, "meta").mkdirs()
        ZipInputStream(open().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val base = name.substringAfterLast('/')
                if (base.isEmpty() || base.startsWith(".")) continue
                val target: File? = when {
                    name.startsWith("lib/arm64-v8a/") && base.endsWith(".so") -> {
                        sawApkLayout = true; libraries++; File(staging, base)
                    }
                    name.startsWith("lib/") && name.count { it == '/' } == 1 && base.endsWith(".so") -> {
                        sawModuleLayout = true; libraries++; File(staging, base)
                    }
                    name.startsWith("lib/") && base.endsWith(".so") -> {
                        otherAbis += name.split('/').getOrElse(1) { "?" }; null
                    }
                    name == "module.json" || name == "meta/module.json" -> File(staging, "meta/module.json")
                    name == "assets/pinyon_shift_build.json" || name == "pinyon_shift_build.json" ||
                        name == "meta/pinyon_shift_build.json" -> File(staging, "meta/pinyon_shift_build.json")
                    name.startsWith("assets/drivers/") || name.startsWith("drivers/") -> {
                        val parts = name.split('/')
                        val driver = parts.getOrNull(parts.indexOf("drivers") + 1)
                        if (driver != null && parts.size >= 3) File(staging, "drivers/$driver/$base") else null
                    }
                    else -> null
                }
                if (target == null) continue
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_LIBRARY_BYTES) throw IllegalStateException("$base melebihi 2 GB")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
        if (libraries == 0) {
            errors += if (otherAbis.isNotEmpty()) {
                "paket hanya berisi pustaka untuk ${otherAbis.joinToString()}, bukan arm64-v8a"
            } else {
                "berkas ini bukan modul backend: tidak ada pustaka .so"
            }
        }
        return when {
            sawApkLayout -> "pinyon-apk"
            sawModuleLayout -> "fhorizon-module"
            else -> "unknown"
        }
    }

    private fun installBundledDrivers(staging: File, warnings: MutableList<String>) {
        val drivers = File(staging, "drivers")
        val folders = drivers.listFiles()?.filter { it.isDirectory } ?: return
        for (folder in folders) {
            try {
                val target = File(FhPaths.driversRoot, folder.name)
                folder.copyRecursively(target, overwrite = true)
                AppLog.i("Bundled GPU driver ${folder.name} copied to ${target.absolutePath}")
            } catch (e: Exception) {
                warnings += "driver bawaan ${folder.name} tidak bisa disalin: ${e.message}"
            }
        }
        drivers.deleteRecursively()
    }

    private fun fail(staging: File, errors: List<String>, warnings: List<String>, json: String?): ModuleImportResult {
        staging.deleteRecursively()
        AppLog.w("Backend module rejected: ${errors.joinToString(" | ")}")
        return ModuleImportResult(false, null, errors, warnings, json)
    }

    private fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
