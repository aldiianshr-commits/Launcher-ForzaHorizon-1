package id.fhorizon.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import id.fhorizon.mobile.controls.GamepadMapping
import id.fhorizon.mobile.controls.PadButton
import id.fhorizon.mobile.core.StorageAccess
import id.fhorizon.mobile.save.SaveBackup
import id.fhorizon.mobile.ui.screens.BackendScreen
import id.fhorizon.mobile.ui.screens.ControlsScreen
import id.fhorizon.mobile.ui.screens.GameFilesScreen
import id.fhorizon.mobile.ui.screens.GraphicsScreen
import id.fhorizon.mobile.ui.screens.HomeScreen
import id.fhorizon.mobile.ui.screens.LogsScreen
import id.fhorizon.mobile.ui.screens.SaveScreen
import id.fhorizon.mobile.ui.theme.FHorizonTheme

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Beranda", Icons.Default.Home),
    GAME("File game", Icons.Default.List),
    BACKEND("Backend", Icons.Default.Build),
    GRAPHICS("Grafis", Icons.Default.Settings),
    CONTROLS("Kontrol", Icons.Default.Face),
    SAVE("Save", Icons.Default.CheckCircle),
    LOGS("Log", Icons.Default.Info),
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var captureFor by mutableStateOf<PadButton?>(null)
    private var padsVersion by mutableIntStateOf(0)
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FHorizonTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        // Back from the permission screen, the game or the layout editor.
        if (resumed) {
            vm.refreshAll(probeVulkan = false)
            vm.reloadTouchLayout()
            padsVersion++
        }
        resumed = true
    }

    /** "Ubah" in the button mapping: the next controller button becomes the source. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = captureFor
        if (target != null && GamepadMapping.isGamepad(event.source) && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_BACK
        ) {
            val physical = event.keyCode
            vm.updateGamepad { mapping ->
                val remap = mapping.remap.filterValues { it != target.keycode }.toMutableMap()
                if (physical != target.keycode) remap[physical] = target.keycode else remap.remove(physical)
                mapping.copy(remap = remap)
            }
            captureFor = null
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, "Bagikan"))
    }

    @Composable
    private fun App() {
        val state by vm.state.collectAsState()
        var section by rememberSaveable { mutableStateOf(Section.HOME) }
        val snackbar = remember { SnackbarHostState() }
        var exportTarget by remember { mutableStateOf<SaveBackup?>(null) }

        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { vm.onFolderPicked(it) }
        }
        val copyFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { vm.copyFolderIntoApp(it) }
        }
        val pickModule = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { vm.importModule(it) }
        }
        val pickDriver = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { vm.importDriver(it) }
        }
        val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { vm.importBackup(it) }
        }
        val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val target = exportTarget
            if (uri != null && target != null) vm.exportBackup(target, uri)
            exportTarget = null
        }

        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it)
                vm.message(null)
            }
        }

        val wide = LocalConfiguration.current.screenWidthDp >= 600
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        for (s in Section.values()) {
                            NavigationBarItem(selected = s == section, onClick = { section = s },
                                icon = { Icon(s.icon, contentDescription = s.label) }, label = { Text(s.label) })
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail {
                        Text("FHorizon", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        for (s in Section.values()) {
                            NavigationRailItem(selected = s == section, onClick = { section = s },
                                icon = { Icon(s.icon, contentDescription = s.label) }, label = { Text(s.label) })
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 980.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("FHorizon Mobile", style = MaterialTheme.typography.headlineSmall)
                        state.progress?.let { p ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(p.title, style = MaterialTheme.typography.bodyMedium)
                                if (p.detail.isNotEmpty()) Text(p.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                if (p.fraction != null) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                if (p.cancellable) TextButton(onClick = { vm.cancelProgress() }) { Text("Batalkan") }
                            }
                        }
                        when (section) {
                            Section.HOME -> HomeScreen(
                                state,
                                onPlay = { ignore -> vm.play(this@MainActivity, ignore) },
                                onRefresh = { vm.refreshAll(true) },
                                onGrantStorage = { startActivity(StorageAccess.allFilesAccessIntent(this@MainActivity)) },
                            )
                            Section.GAME -> GameFilesScreen(
                                state,
                                onPickFolder = { pickFolder.launch(null) },
                                onUseDefault = { vm.useDefaultFolder() },
                                onRescan = { vm.rescan() },
                                onVerify = { vm.verify() },
                                onCopyIntoApp = { copyFolder.launch(null) },
                            )
                            Section.BACKEND -> BackendScreen(
                                state,
                                onImport = { pickModule.launch(arrayOf("application/zip", "application/vnd.android.package-archive", "application/octet-stream")) },
                                onImportFromFolder = { vm.importModuleFromDropFolder() },
                                onRemove = { vm.removeModule() },
                            )
                            Section.GRAPHICS -> GraphicsScreen(
                                state,
                                onUpdate = { vm.updateGraphics(it) },
                                onPreset = { vm.applyPreset(it) },
                                onReset = { vm.resetGraphics() },
                                onImportDriver = { pickDriver.launch(arrayOf("application/zip", "application/octet-stream")) },
                                onDeleteDriver = { vm.deleteDriver(it) },
                            )
                            Section.CONTROLS -> ControlsScreen(
                                state,
                                onTouchEnabled = { vm.setTouchEnabled(it) },
                                onLayout = { vm.updateTouchLayout(it) },
                                onOpenEditor = { startActivity(Intent(this@MainActivity, TouchLayoutEditorActivity::class.java)) },
                                onGamepad = { vm.updateGamepad(it) },
                                captureFor = captureFor,
                                onCapture = { captureFor = it },
                                padsVersion = padsVersion,
                                onRefreshPads = { padsVersion++ },
                            )
                            Section.SAVE -> SaveScreen(
                                state,
                                onBackup = { vm.backupSave() },
                                onRestore = { vm.restoreSave(it) },
                                onDelete = { vm.deleteBackup(it) },
                                onImport = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                                onExport = { backup ->
                                    exportTarget = backup
                                    exportBackup.launch(backup.file.name)
                                },
                            )
                            Section.LOGS -> LogsScreen(
                                state,
                                gameLogs = { vm.gameLogFiles() },
                                report = { vm.diagnosticReport() },
                                onShare = { share(it) },
                                onExtraArguments = { vm.updateExtraArguments(it) },
                            )
                        }
                    }
                }
            }
        }

    }
}
