package id.fhorizon.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import id.fhorizon.mobile.compat.Severity
import id.fhorizon.mobile.core.FhPaths
import id.fhorizon.mobile.core.formatBytes
import id.fhorizon.mobile.module.SdlCompat
import id.fhorizon.mobile.ui.CheckLine
import id.fhorizon.mobile.ui.KeyValue
import id.fhorizon.mobile.ui.SectionCard
import id.fhorizon.mobile.ui.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackendScreen(
    state: UiState,
    onImport: () -> Unit,
    onImportFromFolder: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            "Modul backend rekompilasi",
            "Kode Forza Horizon hasil static recompilation (PowerPC Xbox 360 → C++ → ARM64) beserta runtime " +
                "ReXGlue/ShiftGlue: HLE kernel & XAM, GPU Xenos → Vulkan, audio XMA, input, penyimpanan. " +
                "Kode ini diturunkan dari default.xex milik Anda, jadi dibangun di PC Anda dan tidak pernah dibagikan.",
        ) {
            val module = state.module
            if (module == null) {
                CheckLine(Severity.BLOCK, "Belum ada modul", "Bangun di PC dengan tools/build-module (lihat langkah di bawah), lalu impor.")
            } else {
                KeyValue("Sumber", module.sourceName)
                KeyValue("Jenis", if (module.kind == "pinyon-apk") "APK Android Pinyon Shift (pustaka native diambil)" else "Modul FHorizon")
                KeyValue("Dipasang", module.installedUtc)
                KeyValue("Pinyon Shift", module.pinyonCommit?.take(12) ?: "?")
                KeyValue("ShiftGlue SDK", module.rexglueCommit?.take(12) ?: "?")
                KeyValue("Versi build", module.buildVersion ?: "?")
                KeyValue("SDL", "${module.sdlVersion ?: "?"} (launcher: ${SdlCompat.JAVA_SDL_VERSION})")
                KeyValue("Ukuran", formatBytes(module.totalBytes))
                KeyValue("libmain.so SHA-256", module.libmainSha256?.take(16)?.plus("…") ?: "?")
                Text("Urutan muat", style = MaterialTheme.typography.labelMedium)
                Text(module.loadOrder.joinToString(" → "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (module.sideLibraries.isNotEmpty()) {
                    Text("Dimuat oleh runtime saat dibutuhkan", style = MaterialTheme.typography.labelMedium)
                    Text(module.sideLibraries.joinToString(", "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                for (w in module.warnings) CheckLine(Severity.WARN, "Catatan", w)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, enabled = state.progress == null) { Text("Impor modul (.zip / .apk)") }
                OutlinedButton(onClick = onImportFromFolder, enabled = state.progress == null) { Text("Impor dari FHorizon/Module") }
                if (state.module != null) OutlinedButton(onClick = onRemove) { Text("Hapus modul") }
            }
        }

        state.lastImport?.let { result ->
            if (!result.ok) {
                SectionCard("Impor terakhir ditolak") {
                    for (e in result.errors) CheckLine(Severity.BLOCK, "Galat", e)
                    for (w in result.warnings) CheckLine(Severity.WARN, "Peringatan", w)
                }
            }
        }

        SectionCard("Membangun modul di PC", "Satu kali per versi; ±45 GB ruang kosong, 30–90 menit") {
            val steps = listOf(
                "1. Siapkan ISO disc Forza Horizon USA (MS-2505) hasil dump sendiri.",
                "2. Clone repository ini di PC (Windows atau Linux x86-64).",
                "3. Linux: ./tools/build-module.sh --iso /path/forza-horizon.iso\n   Windows: powershell -File tools\\build-module.ps1 -IsoPath C:\\path\\forza-horizon.iso",
                "4. Skrip memakai Pinyon Shift commit ${SdlCompat.pinnedPinyonCommit.take(7)}: memverifikasi ISO, mengekstrak game, menjalankan rekompilasi ReXGlue, mengompilasi untuk Android arm64 (NDK), lalu membuat fhorizon-module-*.zip.",
                "5. Salin zip ke ${FhPaths.moduleDropFolder.absolutePath} dan folder game hasil ekstraksi (berisi default.xex) ke ${FhPaths.defaultGameFolder.absolutePath}/Game.",
                "6. Tekan \"Impor dari FHorizon/Module\".",
                "Sudah punya pinyon-shift.apk hasil \"pinyon.py android build\"? Impor .apk itu langsung.",
            )
            for (s in steps) Text(s, style = MaterialTheme.typography.bodySmall)
        }
    }
}
