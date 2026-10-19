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
import androidx.compose.ui.unit.dp
import id.fhorizon.mobile.compat.Severity
import id.fhorizon.mobile.core.FhPaths
import id.fhorizon.mobile.core.formatBytes
import id.fhorizon.mobile.game.XexSummary
import id.fhorizon.mobile.ui.CheckLine
import id.fhorizon.mobile.ui.KeyValue
import id.fhorizon.mobile.ui.SectionCard
import id.fhorizon.mobile.ui.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameFilesScreen(
    state: UiState,
    onPickFolder: () -> Unit,
    onUseDefault: () -> Unit,
    onRescan: () -> Unit,
    onVerify: () -> Unit,
    onCopyIntoApp: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            "Folder game",
            "Salin folder hasil ekstraksi disc Anda sendiri (berisi default.xex dan media/, ±7,2 GB, 2.400 file) ke " +
                "${FhPaths.defaultGameFolder.absolutePath} atau subfoldernya, lalu pindai. Disc yang didukung backend: " +
                "Forza Horizon USA retail (MS-2505, title ID 4D5309C9). FHorizon Mobile tidak menyertakan file game apa pun.",
        ) {
            KeyValue("Ditemukan", state.gameRoot?.absolutePath ?: "—")
            KeyValue("Sumber", state.gameRootSource.ifEmpty { "—" })
            KeyValue("Folder save", FhPaths.saveRoot.absolutePath)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFolder) { Text("Pilih folder (SAF)") }
                OutlinedButton(onClick = onUseDefault) { Text("Pakai folder default") }
                OutlinedButton(onClick = onRescan) { Text("Pindai ulang") }
                OutlinedButton(onClick = onCopyIntoApp) { Text("Salin ke penyimpanan aplikasi…") }
            }
            Text(
                "\"Salin ke penyimpanan aplikasi\" tidak memerlukan izin Akses semua file, tetapi membutuhkan ruang kosong 7,2 GB tambahan.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.scan?.let { scan ->
            SectionCard("Kelengkapan (ukuran file)") {
                CheckLine(
                    if (scan.complete) Severity.OK else Severity.BLOCK,
                    "${scan.presentFiles}/${scan.expectedFiles} file cocok",
                    "${formatBytes(scan.presentBytes)} dari ${formatBytes(scan.expectedBytes)} · ${scan.dumpId}",
                )
                if (scan.missingCount > 0) {
                    Text("Hilang (${scan.missingCount}):", style = MaterialTheme.typography.labelMedium)
                    Text(scan.missing.take(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
                if (scan.wrongSizeCount > 0) {
                    Text("Ukuran berbeda (${scan.wrongSizeCount}):", style = MaterialTheme.typography.labelMedium)
                    Text(scan.wrongSize.take(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onVerify, enabled = state.progress == null) { Text("Verifikasi SHA-256 penuh") }
                when (state.verified) {
                    true -> CheckLine(Severity.OK, "Terverifikasi", "Isi file identik dengan dump yang didukung.")
                    false -> CheckLine(Severity.BLOCK, "${state.verifyMismatches.size} file berbeda", state.verifyMismatches.take(10).joinToString("\n"))
                    null -> CheckLine(Severity.WARN, "Belum diverifikasi", "Membaca 7,2 GB; beberapa menit di UFS 3.1.")
                }
            }
        }

        state.xex?.let { xex -> XexCard(xex) }
    }
}

@Composable
private fun XexCard(xex: XexSummary) {
    SectionCard("default.xex", "Header XEX2 (bagian plaintext; image PE terenkripsi tidak dibuka)") {
        if (!xex.ok) {
            CheckLine(Severity.BLOCK, "Tidak bisa dibaca", xex.error ?: "")
        } else {
        KeyValue("Title ID", xex.titleId ?: "?")
        KeyValue("Media ID", xex.mediaId ?: "?")
        KeyValue("Versi / dasar", "${xex.version} / ${xex.baseVersion}")
        KeyValue("Disc", "${xex.discNumber} dari ${xex.discCount}")
        KeyValue("Region", xex.regions ?: "?")
        KeyValue("Entry point", xex.entryPoint ?: "?")
        KeyValue("Image base / ukuran", "${xex.imageBase} / ${formatBytes(xex.imageSize)}")
        KeyValue("Enkripsi / kompresi", "${xex.encryption} / ${xex.compression}")
        KeyValue("Nama PE asli", xex.originalPeName ?: "?")
        Text("Import sistem (dipenuhi HLE kernel/XAM runtime)", style = MaterialTheme.typography.labelMedium)
        for (imp in xex.imports) {
            KeyValue(imp.name, "${imp.records} record · v${imp.version}")
        }
        if (xex.staticLibraries.isNotEmpty()) {
            Text("Pustaka statis XDK", style = MaterialTheme.typography.labelMedium)
            Text(xex.staticLibraries.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
        for (warning in xex.warnings) CheckLine(Severity.INFO, "Catatan", warning)
        }
    }
}
