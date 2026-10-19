package id.fhorizon.mobile.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.fhorizon.mobile.FHorizonApp
import id.fhorizon.mobile.compat.Check
import id.fhorizon.mobile.compat.CompatInput
import id.fhorizon.mobile.compat.Compatibility
import id.fhorizon.mobile.compat.Severity
import id.fhorizon.mobile.compat.Verdict
import id.fhorizon.mobile.controls.GamepadMapping
import id.fhorizon.mobile.controls.TouchLayout
import id.fhorizon.mobile.core.AppLog
import id.fhorizon.mobile.core.FhPaths
import id.fhorizon.mobile.core.NativeCore
import id.fhorizon.mobile.core.StorageAccess
import id.fhorizon.mobile.device.DeviceInfo
import id.fhorizon.mobile.drivers.DriverManager
import id.fhorizon.mobile.drivers.GpuDriver
import id.fhorizon.mobile.game.GameCatalog
import id.fhorizon.mobile.game.GameLauncher
import id.fhorizon.mobile.game.GameScanner
import id.fhorizon.mobile.game.ScanReport
import id.fhorizon.mobile.game.XexSummary
import id.fhorizon.mobile.module.InstalledModule
import id.fhorizon.mobile.module.ModuleImportResult
import id.fhorizon.mobile.module.ModuleManager
import id.fhorizon.mobile.save.ProfileSummary
import id.fhorizon.mobile.save.SaveBackup
import id.fhorizon.mobile.save.SaveManager
import id.fhorizon.mobile.settings.GraphicsSettings
import id.fhorizon.mobile.settings.Preset
import id.fhorizon.mobile.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class Progress(val title: String, val detail: String = "", val fraction: Float? = null, val cancellable: Boolean = false)

data class UiState(
    val storageGranted: Boolean = false,
    val device: DeviceInfo? = null,
    val gameRoot: File? = null,
    val gameRootSource: String = "",
    val xex: XexSummary? = null,
    val scan: ScanReport? = null,
    val verified: Boolean? = null,
    val verifyMismatches: List<String> = emptyList(),
    val module: InstalledModule? = null,
    val lastImport: ModuleImportResult? = null,
    val graphics: GraphicsSettings = GraphicsSettings(),
    val touchEnabled: Boolean = true,
    val touchLayout: TouchLayout = TouchLayout.defaults(),
    val gamepad: GamepadMapping = GamepadMapping(),
    val drivers: List<GpuDriver> = emptyList(),
    val profile: ProfileSummary? = null,
    val backups: List<SaveBackup> = emptyList(),
    val verdict: Verdict = Verdict.BLOCKED,
    val checks: List<Check> = emptyList(),
    val progress: Progress? = null,
    val message: String? = null,
    val lastSession: String? = null,
    val extraArguments: String = "",
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val repo = SettingsRepository(application)
    private val catalog by lazy { GameCatalog.load(application) }
    private val scanner by lazy { GameScanner(catalog) }
    private val modules = ModuleManager(application)
    private val saves get() = SaveManager(FhPaths.saveProfile, FhPaths.backupsRoot)
    private val drivers get() = DriverManager(FhPaths.driversRoot)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var verifyJob: Job? = null
    @Volatile private var verifyCancelled = false

    init {
        refreshAll()
    }

    fun message(text: String?) = _state.update { it.copy(message = text) }

    // ---- Refresh -----------------------------------------------------------

    fun refreshAll(probeVulkan: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val granted = StorageAccess.hasAllFilesAccess(app)
            if (granted) {
                FhPaths.ensureSharedLayout()
                (app as? FHorizonApp)?.attachLogFile()
            }
            val device = if (probeVulkan || _state.value.device == null) {
                setProgress("Memeriksa perangkat dan Vulkan")
                DeviceInfo.collect(app, probeVulkan = true)
            } else {
                _state.value.device
            }
            val graphics = repo.graphics ?: GraphicsSettings.defaultsFor(device?.totalRamBytes ?: 0L).also { repo.graphics = it }
            _state.update {
                it.copy(
                    storageGranted = granted,
                    device = device,
                    graphics = graphics,
                    touchEnabled = repo.touchControlsEnabled,
                    touchLayout = repo.touchLayout,
                    gamepad = repo.gamepad,
                    module = modules.installed(),
                    extraArguments = repo.extraArguments,
                    lastSession = readLastSession(),
                )
            }
            refreshGameFiles()
            refreshSaves()
            setProgress(null)
            AppLog.i("Device: ${device?.summaryJson()}")
        }
    }

    private suspend fun refreshGameFiles() {
        val granted = StorageAccess.hasAllFilesAccess(app)
        val chosen = repo.gameFolder?.let { File(it) }
        val candidates = buildList {
            if (chosen != null) add(chosen to "dipilih")
            if (granted) add(FhPaths.defaultGameFolder to "default")
            add(FhPaths.importedGameFolder(app) to "disalin ke aplikasi")
        }
        var root: File? = null
        var source = ""
        for ((dir, label) in candidates) {
            val found = try { scanner.findGameRoot(dir) } catch (e: Exception) { null }
            if (found != null) {
                root = found; source = label; break
            }
        }
        if (root == null) {
            _state.update { it.copy(gameRoot = null, xex = null, scan = null, verified = null) }
            evaluate()
            return
        }
        setProgress("Memindai file game", root.absolutePath)
        val xexFile = id.fhorizon.mobile.game.CaseInsensitiveResolver(root).resolve("default.xex")
        val xex = xexFile?.let { XexSummary.parse(NativeCore.xex(it.absolutePath)) }
        val scan = withContext(Dispatchers.IO) { scanner.quickCheck(root) }
        val verification = repo.verification
        val fingerprint = xexFile?.let { "${it.length()}:${it.lastModified()}" }
        val verified = if (verification != null && verification.optString("root") == root.absolutePath &&
            verification.optString("fingerprint") == fingerprint
        ) verification.optBoolean("ok") else null
        val mismatches = verification?.optJSONArray("mismatched")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        _state.update {
            it.copy(gameRoot = root, gameRootSource = source, xex = xex, scan = scan, verified = verified,
                verifyMismatches = if (verified == false) mismatches else emptyList())
        }
        AppLog.i("Game folder ${root.absolutePath} ($source): ${scan.presentFiles}/${scan.expectedFiles} files, missing ${scan.missingCount}, size mismatches ${scan.wrongSizeCount}; xex ${xex?.titleId} ${xex?.version}")
        evaluate()
    }

    private fun refreshSaves() {
        val granted = StorageAccess.hasAllFilesAccess(app)
        _state.update {
            it.copy(
                profile = if (granted) saves.summary() else null,
                backups = if (granted) saves.list() else emptyList(),
                drivers = if (granted) drivers.list() else emptyList(),
            )
        }
        evaluate()
    }

    private fun evaluate() {
        val s = _state.value
        val root = s.gameRoot
        val shared = root?.absolutePath?.startsWith(android.os.Environment.getExternalStorageDirectory().absolutePath) ?: true
        val selectedDriver = s.graphics.gpuDriver
        val input = CompatInput(
            device = s.device,
            hasAllFilesAccess = StorageAccess.hasAllFilesAccess(app),
            gameRootOnSharedStorage = shared,
            gameRoot = root?.absolutePath,
            xex = s.xex,
            scan = s.scan,
            verified = s.verified,
            verifyMismatches = s.verifyMismatches.size,
            module = s.module,
            selectedDriver = selectedDriver,
            driverInstalled = selectedDriver.isEmpty() || s.drivers.any { it.name == selectedDriver },
            saveFreeBytes = StorageAccess.freeBytes(FhPaths.saveRoot),
        )
        val (verdict, checks) = Compatibility.evaluate(input)
        _state.update { it.copy(verdict = verdict, checks = checks) }
    }

    private fun setProgress(title: String?, detail: String = "", fraction: Float? = null, cancellable: Boolean = false) {
        _state.update { it.copy(progress = title?.let { t -> Progress(t, detail, fraction, cancellable) }) }
    }

    private fun readLastSession(): String? {
        val file = FhPaths.lastExitFile(app)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            if (json.optString("state") == "started") {
                "Sesi game terakhir berakhir tanpa penutupan normal (crash atau keluar paksa). Lihat log game."
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ---- Game files -------------------------------------------------------

    fun onFolderPicked(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            AppLog.w("persistable permission not granted for $uri", e)
        }
        val path = StorageAccess.treeUriToPath(uri)
        if (path == null) {
            message("Folder ini bukan penyimpanan lokal perangkat (mis. cloud). Pilih folder di memori internal atau kartu SD.")
            return
        }
        repo.gameFolder = path.absolutePath
        repo.verification = null
        AppLog.i("Game folder chosen: ${path.absolutePath} ($uri)")
        if (!StorageAccess.hasAllFilesAccess(app)) {
            message("Folder dipilih: ${path.absolutePath}. Berikan izin \"Akses semua file\" agar game bisa membacanya, atau salin file ke penyimpanan aplikasi.")
        }
        viewModelScope.launch(Dispatchers.IO) {
            refreshGameFiles()
            setProgress(null)
        }
    }

    fun useDefaultFolder() {
        repo.gameFolder = null
        repo.verification = null
        viewModelScope.launch(Dispatchers.IO) {
            refreshGameFiles(); setProgress(null)
        }
    }

    fun rescan() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshGameFiles(); refreshSaves(); setProgress(null)
        }
    }

    fun verify() {
        val root = _state.value.gameRoot ?: return
        verifyCancelled = false
        verifyJob = viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val report = scanner.verify(
                root,
                progress = { done, total, path ->
                    setProgress("Verifikasi SHA-256", path, if (total > 0) done.toFloat() / total else null, cancellable = true)
                },
                cancelled = { verifyCancelled },
            )
            setProgress(null)
            if (report.cancelled) {
                message("Verifikasi dibatalkan.")
                return@launch
            }
            val xexFile = id.fhorizon.mobile.game.CaseInsensitiveResolver(root).resolve("default.xex")
            repo.verification = JSONObject()
                .put("root", root.absolutePath)
                .put("fingerprint", xexFile?.let { "${it.length()}:${it.lastModified()}" })
                .put("ok", report.ok)
                .put("mismatched", org.json.JSONArray(report.mismatched + report.missing))
                .put("time", System.currentTimeMillis())
            val seconds = (System.currentTimeMillis() - started) / 1000
            AppLog.i("Verification: ${report.checkedFiles} files in ${seconds}s, mismatched ${report.mismatched.size}, missing ${report.missing.size}")
            _state.update {
                it.copy(verified = report.ok, verifyMismatches = report.mismatched + report.missing)
            }
            evaluate()
            message(if (report.ok) "Semua ${report.checkedFiles} file cocok (${seconds} dtk)." else "${report.mismatched.size + report.missing.size} file tidak cocok.")
        }
    }

    fun cancelProgress() {
        verifyCancelled = true
    }

    /** Copies a picked folder into app storage (no "All files access" needed afterwards). */
    fun copyFolderIntoApp(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri) ?: error("folder tidak bisa dibuka")
                val target = FhPaths.importedGameFolder(app)
                target.mkdirs()
                var copied = 0L
                val total = catalog.dumps.first().sizeBytes
                verifyCancelled = false
                fun copy(dir: androidx.documentfile.provider.DocumentFile, out: File) {
                    for (child in dir.listFiles()) {
                        if (verifyCancelled) return
                        val name = child.name ?: continue
                        if (child.isDirectory) {
                            copy(child, File(out, name).apply { mkdirs() })
                        } else {
                            val dest = File(out, name)
                            if (dest.length() == child.length()) { copied += dest.length(); continue }
                            app.contentResolver.openInputStream(child.uri)?.use { input ->
                                dest.outputStream().use { output ->
                                    val buffer = ByteArray(1 shl 20)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        output.write(buffer, 0, read)
                                        copied += read
                                        setProgress("Menyalin file game", name, (copied.toFloat() / total).coerceAtMost(1f), true)
                                    }
                                }
                            }
                        }
                    }
                }
                copy(tree, target)
                repo.gameFolder = null
                setProgress(null)
                refreshGameFiles()
                setProgress(null)
                message(if (verifyCancelled) "Penyalinan dibatalkan; bisa dilanjutkan nanti." else "File game disalin ke ${target.absolutePath}.")
            } catch (e: Exception) {
                setProgress(null)
                AppLog.e("copy into app failed", e)
                message("Gagal menyalin: ${e.message}")
            }
        }
    }

    // ---- Backend module -------------------------------------------------

    fun importModule(uri: Uri) {
        val name = displayName(uri) ?: "module.zip"
        viewModelScope.launch(Dispatchers.IO) {
            val result = modules.import(name, { app.contentResolver.openInputStream(uri) ?: error("tidak bisa membuka $name") }) {
                setProgress("Memasang modul backend", it)
            }
            afterImport(result)
        }
    }

    fun importModuleFromDropFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val candidates = FhPaths.moduleDropFolder.listFiles { f -> f.isFile && (f.name.endsWith(".zip", true) || f.name.endsWith(".apk", true)) }
                ?.sortedByDescending { it.lastModified() }
            val file = candidates?.firstOrNull()
            if (file == null) {
                message("Tidak ada .zip/.apk di ${FhPaths.moduleDropFolder.absolutePath}.")
                return@launch
            }
            val result = modules.import(file.name, { file.inputStream() }) { setProgress("Memasang modul backend", it) }
            afterImport(result)
        }
    }

    private fun afterImport(result: ModuleImportResult) {
        setProgress(null)
        _state.update { it.copy(module = modules.installed(), lastImport = result) }
        evaluate()
        message(if (result.ok) "Modul backend terpasang." else "Modul ditolak: ${result.errors.firstOrNull()}")
    }

    fun removeModule() {
        viewModelScope.launch(Dispatchers.IO) {
            modules.remove()
            _state.update { it.copy(module = null, lastImport = null) }
            evaluate()
        }
    }

    // ---- Settings ---------------------------------------------------------

    fun updateGraphics(transform: (GraphicsSettings) -> GraphicsSettings, markCustom: Boolean = true) {
        val next = transform(_state.value.graphics).let { if (markCustom) it.copy(preset = Preset.CUSTOM) else it }
        repo.graphics = next
        _state.update { it.copy(graphics = next) }
        evaluate()
    }

    fun applyPreset(preset: Preset) = updateGraphics({ GraphicsSettings.preset(preset, it) }, markCustom = false)

    fun resetGraphics() {
        val defaults = GraphicsSettings.defaultsFor(_state.value.device?.totalRamBytes ?: 0L)
        repo.graphics = defaults
        _state.update { it.copy(graphics = defaults) }
        evaluate()
    }

    fun setTouchEnabled(enabled: Boolean) {
        repo.touchControlsEnabled = enabled
        _state.update { it.copy(touchEnabled = enabled) }
    }

    fun updateTouchLayout(layout: TouchLayout) {
        repo.touchLayout = layout
        _state.update { it.copy(touchLayout = layout) }
    }

    fun reloadTouchLayout() = _state.update { it.copy(touchLayout = repo.touchLayout) }

    fun updateGamepad(transform: (GamepadMapping) -> GamepadMapping) {
        val next = transform(_state.value.gamepad)
        repo.gamepad = next
        _state.update { it.copy(gamepad = next) }
    }

    fun updateExtraArguments(text: String) {
        repo.extraArguments = text
        _state.update { it.copy(extraArguments = text) }
    }

    // ---- Drivers ----------------------------------------------------------

    fun importDriver(uri: Uri) {
        val name = displayName(uri) ?: "driver.zip"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val folder = app.contentResolver.openInputStream(uri)!!.use { drivers.import(name, it) }
                refreshSaves()
                message("Driver '$folder' diimpor. Pilih di Grafis > Driver GPU.")
            } catch (e: Exception) {
                message("Driver tidak diimpor: ${e.message}")
            }
        }
    }

    fun deleteDriver(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            drivers.delete(name)
            if (_state.value.graphics.gpuDriver == name) updateGraphics({ it.copy(gpuDriver = "") })
            refreshSaves()
        }
    }

    // ---- Saves ------------------------------------------------------------

    fun backupSave() {
        viewModelScope.launch(Dispatchers.IO) {
            setProgress("Membuat backup save")
            val result = try { saves.backup() } catch (e: Exception) { AppLog.e("backup failed", e); null }
            setProgress(null)
            refreshSaves()
            message(result?.let { "Backup dibuat: ${it.file.name}" } ?: "Belum ada save untuk di-backup.")
        }
    }

    fun restoreSave(backup: SaveBackup) {
        viewModelScope.launch(Dispatchers.IO) {
            setProgress("Memulihkan save", backup.label)
            val result = saves.restore(backup.file)
            setProgress(null)
            refreshSaves()
            message(result.fold(
                { safety -> "Save dipulihkan dari ${backup.label}." + (safety?.let { " Save sebelumnya disimpan sebagai ${it.label}." } ?: "") },
                { "Gagal memulihkan: ${it.message}" },
            ))
        }
    }

    fun deleteBackup(backup: SaveBackup) {
        viewModelScope.launch(Dispatchers.IO) {
            saves.delete(backup.file)
            refreshSaves()
        }
    }

    fun importBackup(uri: Uri) {
        val name = displayName(uri) ?: "imported-save.zip"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.openInputStream(uri)!!.use { saves.importBackup(name, it) }
                refreshSaves()
                message("Backup diimpor: $name")
            } catch (e: Exception) {
                message("Impor gagal: ${e.message}")
            }
        }
    }

    fun exportBackup(backup: SaveBackup, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.openOutputStream(uri)!!.use { saves.export(backup.file, it) }
                message("Backup diekspor.")
            } catch (e: Exception) {
                message("Ekspor gagal: ${e.message}")
            }
        }
    }

    // ---- Play -------------------------------------------------------------

    fun play(activity: Activity, ignoreWarnings: Boolean) {
        val s = _state.value
        if (s.checks.any { it.severity == Severity.BLOCK }) {
            message("Belum bisa dijalankan: ${s.checks.first { it.severity == Severity.BLOCK }.title}")
            return
        }
        if (!ignoreWarnings && s.verdict == Verdict.READY_WITH_WARNINGS) return
        val module = s.module ?: return
        val root = s.gameRoot ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                setProgress("Menyiapkan game")
                GameLauncher.prepare(app, repo, s.graphics, module, root, s.device)
                FhPaths.lastExitFile(app).delete()
                setProgress(null)
                withContext(Dispatchers.Main) { GameLauncher.start(activity) }
            } catch (e: Exception) {
                setProgress(null)
                AppLog.e("launch failed", e)
                message("Gagal menyiapkan game: ${e.message}")
            }
        }
    }

    // ---- Diagnostics -------------------------------------------------------

    fun diagnosticReport(): String {
        val s = _state.value
        return buildString {
            appendLine("FHorizon Mobile ${id.fhorizon.mobile.BuildConfig.VERSION_NAME}, fhcore ${NativeCore.version()}")
            appendLine("Verdict: ${s.verdict.label}")
            for (c in s.checks) appendLine("[${c.severity}] ${c.group} / ${c.title}: ${c.detail}")
            appendLine()
            appendLine("Device: ${s.device?.summaryJson()}")
            appendLine("Vulkan: ${s.device?.vulkan?.raw}")
            appendLine("XEX: ${s.xex?.rawJson}")
            appendLine("Module: ${s.module?.toJson()}")
            appendLine("Graphics: ${s.graphics.toJson()}")
        }
    }

    fun gameLogFiles(): List<File> {
        val dirs = listOf(FhPaths.gameLogs, FhPaths.gameCrashes)
        return dirs.flatMap { dir -> dir.listFiles()?.filter { it.isFile }?.toList() ?: emptyList() }
            .sortedByDescending { it.lastModified() }
            .take(30)
    }

    private fun displayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
