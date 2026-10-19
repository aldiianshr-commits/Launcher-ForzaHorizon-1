package id.fhorizon.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import id.fhorizon.mobile.core.FhPaths
import id.fhorizon.mobile.core.formatBytes
import id.fhorizon.mobile.save.SaveBackup
import id.fhorizon.mobile.ui.KeyValue
import id.fhorizon.mobile.ui.SectionCard
import id.fhorizon.mobile.ui.UiState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveScreen(
    state: UiState,
    onBackup: () -> Unit,
    onRestore: (SaveBackup) -> Unit,
    onDelete: (SaveBackup) -> Unit,
    onImport: () -> Unit,
    onExport: (SaveBackup) -> Unit,
) {
    var confirmRestore by remember { mutableStateOf<SaveBackup?>(null) }
    var confirmDelete by remember { mutableStateOf<SaveBackup?>(null) }
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            "Save game",
            "Profil disimpan runtime di ${FhPaths.saveProfile.absolutePath} (tata letak sama dengan Pinyon Shift di PC, jadi save bisa dipindah sebagai folder).",
        ) {
            val profile = state.profile
            if (profile == null) {
                Text("Butuh izin Akses semua file.", style = MaterialTheme.typography.bodySmall)
            } else if (!profile.exists || profile.files == 0) {
                Text("Belum ada save. Save dibuat game saat pertama kali dimainkan.", style = MaterialTheme.typography.bodySmall)
            } else {
                KeyValue("File", profile.files.toString())
                KeyValue("Ukuran", formatBytes(profile.bytes))
                KeyValue("Terakhir diubah", format.format(Date(profile.lastModified)))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBackup, enabled = state.progress == null) { Text("Backup sekarang") }
                OutlinedButton(onClick = onImport) { Text("Impor backup (.zip)") }
            }
        }

        SectionCard("Backup (${state.backups.size})", FhPaths.backupsRoot.absolutePath) {
            if (state.backups.isEmpty()) Text("Belum ada backup.", style = MaterialTheme.typography.bodySmall)
            for (backup in state.backups) {
                Column {
                    Text(backup.label, style = MaterialTheme.typography.bodyMedium)
                    Text("${formatBytes(backup.sizeBytes)} · ${format.format(Date(backup.modified))}", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { confirmRestore = backup }) { Text("Pulihkan") }
                        TextButton(onClick = { onExport(backup) }) { Text("Ekspor") }
                        TextButton(onClick = { confirmDelete = backup }) { Text("Hapus") }
                    }
                }
            }
        }
    }

    confirmRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Pulihkan ${backup.label}?") },
            text = { Text("Save saat ini akan di-backup otomatis dulu (before-restore-…), lalu diganti isi backup ini. Pastikan game tidak sedang berjalan.") },
            confirmButton = { TextButton(onClick = { confirmRestore = null; onRestore(backup) }) { Text("Pulihkan") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Batal") } },
        )
    }
    confirmDelete?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Hapus ${backup.label}?") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(backup) }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Batal") } },
        )
    }
}
