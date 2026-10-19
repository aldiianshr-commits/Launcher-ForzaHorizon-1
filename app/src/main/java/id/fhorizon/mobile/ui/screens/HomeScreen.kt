package id.fhorizon.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.fhorizon.mobile.compat.Severity
import id.fhorizon.mobile.compat.Verdict
import id.fhorizon.mobile.core.formatBytes
import id.fhorizon.mobile.ui.CheckLine
import id.fhorizon.mobile.ui.KeyValue
import id.fhorizon.mobile.ui.SectionCard
import id.fhorizon.mobile.ui.StatusDot
import id.fhorizon.mobile.ui.UiState
import id.fhorizon.mobile.ui.severityColor

@Composable
fun HomeScreen(
    state: UiState,
    onPlay: (ignoreWarnings: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onGrantStorage: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.storageGranted) {
            SectionCard(
                "Izin penyimpanan dibutuhkan",
                "Game membaca file dari /storage/emulated/0/FHorizon dan menyimpan save di FHorizon/Save. " +
                    "Android mewajibkan izin \"Akses semua file\" untuk itu.",
            ) {
                Button(onClick = onGrantStorage) { Text("Buka pengaturan izin") }
            }
        }
        state.lastSession?.let {
            SectionCard("Sesi sebelumnya") { CheckLine(Severity.WARN, "Game tertutup tidak normal", it) }
        }

        SectionCard("Forza Horizon (Xbox 360)", "Static recompilation PowerPC → ARM64 melalui ReXGlue/ShiftGlue + Pinyon Shift") {
            val color = when (state.verdict) {
                Verdict.READY -> severityColor(Severity.OK)
                Verdict.READY_WITH_WARNINGS -> severityColor(Severity.WARN)
                Verdict.BLOCKED -> severityColor(Severity.BLOCK)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color)
                Spacer(Modifier.width(8.dp))
                Text(state.verdict.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            KeyValue("Folder game", state.gameRoot?.absolutePath ?: "belum ditemukan")
            KeyValue("Modul backend", state.module?.let { "${it.sourceName} (${formatBytes(it.totalBytes)})" } ?: "belum dipasang")
            KeyValue("Preset grafis", state.graphics.preset.label)
            state.device?.let { d ->
                KeyValue("Perangkat", "${d.manufacturer} ${d.model} · ${d.socModel ?: "?"} · Adreno ${d.adrenoModel ?: "?"}")
                KeyValue("Vulkan", d.vulkan.primary?.let { "${it.apiVersion} · ${it.driverName.ifEmpty { "driver" }} ${it.driverVersion}" } ?: (d.vulkan.error ?: "?"))
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (state.verdict == Verdict.READY_WITH_WARNINGS) confirm = true else onPlay(false)
                    },
                    enabled = state.verdict != Verdict.BLOCKED && state.progress == null,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Periksa ulang")
                }
            }
            if (state.verdict == Verdict.BLOCKED) {
                Text(
                    "Play aktif setelah semua pemeriksaan merah teratasi. Launcher tidak akan menjalankan apa pun tanpa backend hasil rekompilasi yang valid.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("Pemeriksaan kompatibilitas") {
            val groups = state.checks.groupBy { it.group }
            for ((group, checks) in groups) {
                Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (check in checks) CheckLine(check.severity, check.title, check.detail)
                }
            }
        }
    }

    if (confirm) {
        val warnings = state.checks.filter { it.severity == Severity.WARN }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Jalankan dengan peringatan?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Kombinasi ini belum pernah diuji. Game bisa gagal start, crash, atau berjalan lambat. Save Anda tetap aman (backup otomatis tersedia di menu Save).")
                    for (w in warnings) Text("• ${w.title}: ${w.detail}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; onPlay(true) }) { Text("Jalankan") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } },
        )
    }
}
