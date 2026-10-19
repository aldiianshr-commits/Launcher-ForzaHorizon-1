package id.fhorizon.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.fhorizon.mobile.core.AppLog
import id.fhorizon.mobile.core.formatBytes
import id.fhorizon.mobile.ui.SectionCard
import id.fhorizon.mobile.ui.UiState
import java.io.File
import java.io.RandomAccessFile

private fun tail(file: File, maxBytes: Long = 64 * 1024): String = try {
    RandomAccessFile(file, "r").use { raf ->
        val start = (raf.length() - maxBytes).coerceAtLeast(0)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }
} catch (e: Exception) {
    "(tidak bisa dibaca: ${e.message})"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(
    state: UiState,
    gameLogs: () -> List<File>,
    report: () -> String,
    onShare: (String) -> Unit,
    onExtraArguments: (String) -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<File?>(null) }
    val files = remember(refresh) { gameLogs() }
    val launcher = remember(refresh) { AppLog.snapshot().takeLast(300).joinToString("\n") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Laporan diagnostik", "Pemeriksaan, perangkat, laporan Vulkan, header XEX, modul dan cvar grafis.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onShare(report()) }) { Text("Bagikan laporan") }
                OutlinedButton(onClick = { refresh++ }) { Text("Muat ulang") }
            }
        }
        SectionCard("Log game (Save/logs, Save/crashes)", "Ditulis runtime ReXGlue/Pinyon Shift; termasuk VULKAN_CAPABILITY_REPORT tiap start.") {
            if (files.isEmpty()) Text("Belum ada log game.", style = MaterialTheme.typography.bodySmall)
            for (file in files) {
                TextButton(onClick = { opened = if (opened == file) null else file }) {
                    Text("${file.parentFile?.name}/${file.name} · ${formatBytes(file.length())}")
                }
                if (opened == file) {
                    val text = remember(file, refresh) { tail(file) }
                    Text(
                        text, Modifier.horizontalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    )
                    OutlinedButton(onClick = { onShare(text) }) { Text("Bagikan isi log") }
                }
            }
        }
        SectionCard("Log launcher") {
            Text(launcher.ifEmpty { "(kosong)" }, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        SectionCard("Argumen backend tambahan (lanjutan)", "Satu per baris, format --nama=nilai (cvar ReXGlue), mis. --gpu_backend=null untuk uji tanpa renderer.") {
            var text by remember(state.extraArguments) { mutableStateOf(state.extraArguments) }
            OutlinedTextField(value = text, onValueChange = { text = it; onExtraArguments(it) }, minLines = 2)
        }
    }
}
