package id.fhorizon.mobile.compat

import id.fhorizon.mobile.core.formatBytes
import id.fhorizon.mobile.device.DeviceInfo
import id.fhorizon.mobile.game.ScanReport
import id.fhorizon.mobile.game.XexSummary
import id.fhorizon.mobile.module.InstalledModule
import id.fhorizon.mobile.module.SdlCompat

enum class Severity { OK, INFO, WARN, BLOCK }

data class Check(val group: String, val title: String, val severity: Severity, val detail: String)

enum class Verdict(val label: String) {
    READY("Siap dicoba (eksperimental, belum teruji di perangkat ini)"),
    READY_WITH_WARNINGS("Bisa dicoba, dengan peringatan"),
    BLOCKED("Belum bisa dijalankan"),
}

/** Everything the launcher knows before Play. */
data class CompatInput(
    val device: DeviceInfo?,
    val hasAllFilesAccess: Boolean,
    val gameRootOnSharedStorage: Boolean,
    val gameRoot: String?,
    val xex: XexSummary?,
    val scan: ScanReport?,
    val verified: Boolean?,
    val verifyMismatches: Int,
    val module: InstalledModule?,
    val selectedDriver: String,
    val driverInstalled: Boolean,
    val saveFreeBytes: Long,
)

/**
 * Turns the device, game files and backend module into a list of checks.
 * BLOCK means the game cannot start (or would refuse the files); WARN means
 * it may start but something is untested or known to be risky. The launcher
 * never reports a game as playable on a device where nobody has run it.
 */
object Compatibility {

    fun evaluate(input: CompatInput): Pair<Verdict, List<Check>> {
        val checks = mutableListOf<Check>()
        val device = input.device

        // --- Device ---------------------------------------------------------
        if (device != null) {
            checks += if (device.isArm64) Check("Perangkat", "CPU arm64", Severity.OK, device.abis.joinToString())
            else Check("Perangkat", "CPU arm64", Severity.BLOCK, "Kode hasil rekompilasi hanya untuk AArch64.")
            checks += if (device.hasArmv82) Check("Perangkat", "ARMv8.2 (LSE atomics, RDM)", Severity.OK, "Didukung")
            else Check("Perangkat", "ARMv8.2 (LSE atomics, RDM)", Severity.BLOCK,
                "Modul dikompilasi untuk ARMv8.2-A; CPU ini tidak melaporkan 'atomics'/'asimdrdm'.")
            checks += if (device.sdkInt >= 33) Check("Perangkat", "Android ${device.androidRelease} (API ${device.sdkInt})", Severity.OK, "Modul Pinyon Shift membutuhkan API 33+")
            else Check("Perangkat", "Android ${device.androidRelease} (API ${device.sdkInt})", Severity.BLOCK,
                "Modul backend dibangun dengan minSdk 33 (Android 13).")
            val gb = device.totalRamGb
            checks += when {
                gb >= 11.5 -> Check("Perangkat", "RAM ${"%.1f".format(gb)} GB", Severity.OK, "Sesuai target Pinyon Shift (12 GB).")
                gb >= 7.5 -> Check("Perangkat", "RAM ${"%.1f".format(gb)} GB", Severity.WARN,
                    "Di bawah 12 GB yang ditargetkan Pinyon Shift (perkiraan pemakaian ~3 GB pada skala 1x, belum diukur di 8 GB). Tutup aplikasi lain; gunakan preset Low.")
                else -> Check("Perangkat", "RAM ${"%.1f".format(gb)} GB", Severity.WARN,
                    "Sangat mungkin dihentikan sistem (lmkd) karena kehabisan memori. Gunakan preset Very Low dan batas tekstur rendah.")
            }
            if (device.adrenoModel != null && device.adrenoModel.startsWith("6")) {
                checks += Check("Perangkat", "GPU Adreno ${device.adrenoModel}", Severity.WARN,
                    "Pinyon Shift diuji di Adreno 740/830. Adreno 6xx (termasuk 650 di Xiaomi Pad 6) belum pernah diuji siapa pun.")
            } else if (device.adrenoModel != null) {
                checks += Check("Perangkat", "GPU Adreno ${device.adrenoModel}", Severity.OK, device.kgslModel ?: "")
            }
            val vk = device.vulkan
            val primary = vk.primary
            if (!vk.ok || primary == null) {
                checks += Check("Vulkan", "Driver Vulkan", Severity.BLOCK, vk.error ?: "Tidak ada perangkat Vulkan.")
            } else {
                val usingCustom = input.selectedDriver.isNotEmpty()
                val requirementSeverity = when {
                    primary.hardRequirementsMet -> Severity.OK
                    usingCustom && input.driverInstalled -> Severity.WARN
                    else -> Severity.BLOCK
                }
                val detail = if (primary.hardRequirementsMet) {
                    "${primary.name}, Vulkan ${primary.apiVersion}, driver ${primary.driverVersion} ${primary.driverName}".trim()
                } else {
                    "Driver sistem tidak punya: ${primary.blocking.joinToString()}." +
                        if (usingCustom) " Driver custom '${input.selectedDriver}' dipilih; probe hanya memeriksa driver sistem, jadi kecocokannya baru diketahui saat game dijalankan."
                        else " Impor driver Mesa Turnip (adrenotools .zip) di menu Driver GPU."
                }
                checks += Check("Vulkan", "Fitur wajib renderer", requirementSeverity, detail)
                if (!primary.apiAtLeast13 && !usingCustom) {
                    checks += Check("Vulkan", "Vulkan ${primary.apiVersion}", Severity.WARN,
                        "Perangkat referensi Pinyon Shift memakai driver Vulkan 1.3. Turnip memberi Vulkan 1.3+ pada Adreno 650.")
                }
                for (fallback in primary.fallbacks.take(4)) {
                    checks += Check("Vulkan", "Fallback", Severity.INFO, fallback)
                }
            }
            if (device.thermalStatus >= 3) {
                checks += Check("Perangkat", "Suhu", Severity.WARN, "Perangkat sedang panas (status termal ${device.thermalStatus}); performa akan dibatasi.")
            }
        }

        // --- Storage --------------------------------------------------------
        if (input.gameRootOnSharedStorage && !input.hasAllFilesAccess) {
            checks += Check("Penyimpanan", "Izin \"Akses semua file\"", Severity.BLOCK,
                "Game membaca /storage/emulated/0/FHorizon dengan path biasa; berikan izin di Pengaturan.")
        } else {
            checks += Check("Penyimpanan", "Akses file", Severity.OK, "OK")
        }
        if (input.saveFreeBytes in 0 until 2L * 1024 * 1024 * 1024) {
            checks += Check("Penyimpanan", "Ruang kosong", Severity.WARN,
                "Sisa ${formatBytes(input.saveFreeBytes)}; cache shader dan save butuh ruang.")
        }

        // --- Game files -----------------------------------------------------
        if (input.gameRoot == null) {
            checks += Check("File game", "Folder game", Severity.BLOCK,
                "default.xex tidak ditemukan. Salin folder hasil ekstraksi disc (yang berisi default.xex dan media/) ke /storage/emulated/0/FHorizon atau pilih foldernya.")
        } else {
            val xex = input.xex
            when {
                xex == null -> checks += Check("File game", "default.xex", Severity.BLOCK, "Belum dibaca.")
                !xex.ok -> checks += Check("File game", "default.xex", Severity.BLOCK, xex.error ?: "Tidak bisa dibaca")
                xex.titleId != XexSummary.FORZA_HORIZON_TITLE_ID -> checks += Check("File game", "Title ID ${xex.titleId}", Severity.BLOCK,
                    "Bukan Forza Horizon (${XexSummary.FORZA_HORIZON_TITLE_ID}).")
                else -> checks += Check("File game", "Forza Horizon ${xex.titleId}", Severity.OK,
                    "Media ${xex.mediaId}, versi ${xex.version}, ${xex.regions ?: "region ?"}, imports: xboxkrnl ${xex.kernelImports}, xam ${xex.xamImports}")
            }
            val scan = input.scan
            if (scan == null) {
                checks += Check("File game", "Kelengkapan file", Severity.BLOCK, "Belum dipindai.")
            } else if (!scan.complete) {
                checks += Check("File game", "Kelengkapan file", Severity.BLOCK,
                    "${scan.missingCount} file hilang, ${scan.wrongSizeCount} ukuran berbeda dari disc USA MS-2505 yang didukung." +
                        (scan.missing.firstOrNull()?.let { " Contoh: $it" } ?: ""))
            } else {
                checks += Check("File game", "Kelengkapan file", Severity.OK,
                    "${scan.presentFiles}/${scan.expectedFiles} file, ${formatBytes(scan.presentBytes)}")
            }
            when (input.verified) {
                true -> checks += Check("File game", "Verifikasi SHA-256", Severity.OK, "Semua file cocok dengan dump yang didukung.")
                false -> checks += Check("File game", "Verifikasi SHA-256", Severity.BLOCK,
                    "${input.verifyMismatches} file berbeda dari dump yang didukung; backend dibangun untuk dump persis ini.")
                null -> checks += Check("File game", "Verifikasi SHA-256", Severity.WARN,
                    "Belum diverifikasi penuh (±7,2 GB). Disarankan sebelum main pertama kali.")
            }
        }

        // --- Backend --------------------------------------------------------
        val module = input.module
        if (module == null) {
            checks += Check("Backend", "Modul backend rekompilasi", Severity.BLOCK,
                "Belum dipasang. Kode game hasil rekompilasi PowerPC→ARM64 harus dibangun di PC dari disc Anda sendiri (lihat README, tools/build-module).")
        } else {
            checks += Check("Backend", "Modul backend", Severity.OK,
                "${module.kind}, ${module.loadOrder.size} pustaka dimuat, ${formatBytes(module.totalBytes)}")
            checks += if (module.sdlMatches) Check("Backend", "SDL ${module.sdlVersion}", Severity.OK, "Cocok dengan launcher")
            else if (module.sdlVersion == null) Check("Backend", "Versi SDL", Severity.WARN, "Tidak terdeteksi; SDL akan menolak start jika tidak cocok.")
            else Check("Backend", "SDL ${module.sdlVersion}", Severity.BLOCK, "Launcher membawa SDL ${SdlCompat.JAVA_SDL_VERSION}.")
            val known = module.knownBackend
            checks += if (known != null) Check("Backend", "Versi backend", Severity.OK, known.note)
            else Check("Backend", "Versi backend", Severity.WARN,
                "Pinyon ${module.pinyonCommit?.take(7) ?: "?"} / ShiftGlue ${module.rexglueCommit?.take(7) ?: "?"} belum diuji dengan launcher ini.")
            for (warning in module.warnings.take(3)) checks += Check("Backend", "Catatan modul", Severity.INFO, warning)
            if (module.titleUpdateV4) {
                checks += Check("Backend", "Title update v4", Severity.INFO,
                    "Modul ini untuk TU v4: file update harus ada di Save/title-update-v4.")
            }
        }

        val verdict = when {
            checks.any { it.severity == Severity.BLOCK } -> Verdict.BLOCKED
            checks.any { it.severity == Severity.WARN } -> Verdict.READY_WITH_WARNINGS
            else -> Verdict.READY
        }
        return verdict to checks
    }
}
