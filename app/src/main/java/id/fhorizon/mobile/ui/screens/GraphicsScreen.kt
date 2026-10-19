package id.fhorizon.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import id.fhorizon.mobile.settings.AntiAliasing
import id.fhorizon.mobile.settings.Anisotropic
import id.fhorizon.mobile.settings.AspectRatio
import id.fhorizon.mobile.settings.FpsLimit
import id.fhorizon.mobile.settings.GraphicsSettings
import id.fhorizon.mobile.settings.Preset
import id.fhorizon.mobile.settings.Resolution
import id.fhorizon.mobile.settings.ShadowQuality
import id.fhorizon.mobile.settings.TextureQuality
import id.fhorizon.mobile.settings.Upscaler
import id.fhorizon.mobile.ui.ChoiceSetting
import id.fhorizon.mobile.ui.SectionCard
import id.fhorizon.mobile.ui.ToggleSetting
import id.fhorizon.mobile.ui.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphicsScreen(
    state: UiState,
    onUpdate: ((GraphicsSettings) -> GraphicsSettings) -> Unit,
    onPreset: (Preset) -> Unit,
    onReset: () -> Unit,
    onImportDriver: () -> Unit,
    onDeleteDriver: (String) -> Unit,
) {
    val g = state.graphics
    val vk = state.device?.vulkan?.primary
    var showCvars by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            "Preset",
            "Preset mengikuti preset Android Pinyon Shift (diukur di Adreno 740/830). Belum ada pengukuran di Adreno 650; mulai dari Low lalu naikkan.",
        ) {
            ChoiceSetting("Preset", Preset.values().filter { it != Preset.CUSTOM }, g.preset, { it.label }) { onPreset(it) }
            if (g.preset == Preset.CUSTOM) Text("Saat ini: Kustom", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset) { Text("Reset to Default") }
                TextButton(onClick = { showCvars = !showCvars }) { Text(if (showCvars) "Sembunyikan cvar backend" else "Lihat cvar backend") }
            }
            if (showCvars) {
                Text(
                    g.toCvars().entries.joinToString("\n") { "${it.key} = ${it.value}" },
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
            }
        }

        SectionCard("Tampilan") {
            ChoiceSetting(
                "Resolusi output", Resolution.values().toList(), g.resolution, { it.label },
                note = "Ukuran swapchain Vulkan (lebar mengikuti rasio layar). Game sendiri merender pada 1280×720 (skala 1x): backend " +
                    "membatasi Android ke 1x karena buffer resolve skala lebih besar tidak muat di memori ponsel. 900p/1080p paling bagus dengan upscaler FSR 1.",
            ) { v -> onUpdate { it.copy(resolution = v) } }
            ChoiceSetting("Upscaler output", Upscaler.values().toList(), g.upscaler, { it.label }) { v -> onUpdate { it.copy(upscaler = v) } }
            ChoiceSetting(
                "Batas FPS", FpsLimit.values().toList(), g.fps, { it.label },
                note = "pinyon_shift_fh1_render_fps_limit. 30 = kecepatan asli Xbox 360. 45 FPS belum pernah diuji backend; layar diminta 90 Hz agar jeda frame rata.",
            ) { v -> onUpdate { it.copy(fps = v) } }
            ChoiceSetting(
                "Aspect ratio", AspectRatio.values().toList(), g.aspect, { it.label },
                note = "Otomatis: isi layar 16:10 dengan memotong margin overscan. 16:9: gambar asli dengan bilah. 16:10: Hor+ (game merender pandangan lebih lebar).",
            ) { v -> onUpdate { it.copy(aspect = v) } }
            ToggleSetting("Fullscreen (immersive)", g.fullscreen, "Sembunyikan bilah status dan navigasi") { v -> onUpdate { it.copy(fullscreen = v) } }
            ToggleSetting("V-Sync", g.vsync) { v -> onUpdate { it.copy(vsync = v) } }
        }

        SectionCard("Kualitas") {
            ChoiceSetting(
                "Tekstur", TextureQuality.values().toList(), g.textures, { it.label },
                note = "vulkan_texture_max_size: Rendah 256, Sedang 1024, Tinggi tanpa batas. Lebih rendah = lebih hemat VRAM/RAM.",
            ) { v -> onUpdate { it.copy(textures = v) } }
            ChoiceSetting(
                "Bayangan", ShadowQuality.values().toList(), g.shadows, { it.label },
                enabled = { GraphicsSettings.shadowSupport(it).supported },
                note = GraphicsSettings.shadowSupport(ShadowQuality.LOW).reason + ". Bayangan matahari ±6 ms per frame di Adreno 740.",
            ) { v -> onUpdate { it.copy(shadows = v) } }
            ChoiceSetting(
                "Anti-aliasing", AntiAliasing.values().toList(), g.antiAliasing, { it.label },
                enabled = { GraphicsSettings.aaSupport(it, vk?.msaaSampleCounts).supported },
                note = "MSAA adalah MSAA 4x milik game sendiri (2x = sampel digabung, lebih murah). FXAA dijalankan pada gambar akhir.",
            ) { v -> onUpdate { it.copy(antiAliasing = v) } }
            ChoiceSetting(
                "Anisotropic filtering", Anisotropic.values().toList(), g.anisotropic, { it.label },
                enabled = { it.cvar <= 0 || (vk?.maxAnisotropy ?: 16.0) >= when (it) { Anisotropic.X2 -> 2.0; Anisotropic.X4 -> 4.0; Anisotropic.X8 -> 8.0; else -> 16.0 } },
            ) { v -> onUpdate { it.copy(anisotropic = v) } }
            ToggleSetting("Shader cache", g.shaderCache, "Simpan shader & pipeline Vulkan di Save/cache agar tidak dikompilasi ulang tiap start") { v ->
                onUpdate { it.copy(shaderCache = v) }
            }
        }

        SectionCard("Driver GPU", "android_gpu_driver. Turnip (Mesa, MIT) dimuat oleh backend melalui libadrenotools dari Save/drivers.") {
            val options = listOf("") + state.drivers.map { it.name }
            ChoiceSetting(
                "Driver", options, g.gpuDriver, { if (it.isEmpty()) "System Driver" else "Turnip/kustom: $it" },
            ) { v -> onUpdate { it.copy(gpuDriver = v) } }
            for (d in state.drivers) {
                Text("${d.name}: ${listOfNotNull(d.description, d.version, d.libraryName).joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImportDriver) { Text("Impor driver (.zip adrenotools)") }
                if (g.gpuDriver.isNotEmpty()) OutlinedButton(onClick = { onDeleteDriver(g.gpuDriver) }) { Text("Hapus driver terpilih") }
            }
            Text(
                "Driver sistem Adreno 650 mungkin tidak memenuhi fitur wajib renderer (lihat Beranda). Turnip untuk a6xx biasanya memberi Vulkan 1.3. Driver tidak dibundel: pilih build Turnip yang mendukung Adreno 650.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Optimasi Android (Snapdragon 870 / Adreno 650)", "Semua opsi di bawah adalah cvar backend yang nyata; dampaknya di Adreno 650 belum diukur.") {
            ToggleSetting("Refleksi 1/4 kecepatan", g.reflectionsQuarterRate, "Cubemap refleksi mobil digambar ulang 1/4 kali (±2,3 ms hemat di Adreno 740)") { v ->
                onUpdate { it.copy(reflectionsQuarterRate = v) }
            }
            ToggleSetting("Scene satu pass", g.singlePassScene, "Tiga tile predicated FH1 digambar sekali (±3 ms hemat, gambar sama)") { v ->
                onUpdate { it.copy(singlePassScene = v) }
            }
            ToggleSetting(
                "Coarse shading 2×2", g.coarseShading,
                if (vk?.hasFragmentShadingRate == false) "Driver tidak punya VK_KHR_fragment_shading_rate: opsi ini tidak berefek" else "Permukaan sedikit lebih lembut, ±1 ms hemat",
            ) { v -> onUpdate { it.copy(coarseShading = v) } }
            ToggleSetting("Gamma tekstur cepat", g.fastTextureGamma, "sRGB view alih-alih kurva konsol; medan sedikit lebih gelap") { v ->
                onUpdate { it.copy(fastTextureGamma = v) }
            }
            ToggleSetting("Batas cache tekstur rendah", g.lowMemoryTextureCache, "256/768 MB alih-alih 384/1024 MB; untuk RAM 6–8 GB") { v ->
                onUpdate { it.copy(lowMemoryTextureCache = v) }
            }
            ToggleSetting("Dekoder GPU di core prime (cpu7)", g.bindDecoderToPrimeCore, "android_thread_cores=GPU_Commands=7;*=0-6 (eksperimental)") { v ->
                onUpdate { it.copy(bindDecoderToPrimeCore = v) }
            }
            ToggleSetting("GPU turbo (adrenotools)", g.gpuTurbo, "Clock GPU maksimum saat bermain; lebih panas dan boros baterai, batas termal tetap berlaku") { v ->
                onUpdate { it.copy(gpuTurbo = v) }
            }
            ToggleSetting("Sustained performance mode", g.sustainedPerformance, "Clock stabil lebih rendah agar tidak cepat throttling (jika didukung perangkat)") { v ->
                onUpdate { it.copy(sustainedPerformance = v) }
            }
        }
    }
}
