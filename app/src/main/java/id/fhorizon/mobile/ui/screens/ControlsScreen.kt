package id.fhorizon.mobile.ui.screens

import android.os.Build
import android.os.VibrationEffect
import android.view.InputDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.fhorizon.mobile.controls.GamepadMapping
import id.fhorizon.mobile.controls.PadButton
import id.fhorizon.mobile.controls.TouchLayout
import id.fhorizon.mobile.ui.SectionCard
import id.fhorizon.mobile.ui.SliderSetting
import id.fhorizon.mobile.ui.ToggleSetting
import id.fhorizon.mobile.ui.UiState

data class ConnectedPad(val id: Int, val name: String, val vendor: Int, val product: Int, val vibrators: Int)

@Suppress("DEPRECATION")
fun connectedPads(): List<ConnectedPad> = InputDevice.getDeviceIds().mapNotNull { id ->
    val device = InputDevice.getDevice(id) ?: return@mapNotNull null
    if (!GamepadMapping.isGamepad(device.sources) || device.isVirtual) return@mapNotNull null
    val vibrators = if (Build.VERSION.SDK_INT >= 31) device.vibratorManager.vibratorIds.size
    else if (device.vibrator.hasVibrator()) 1 else 0
    ConnectedPad(id, device.name, device.vendorId, device.productId, vibrators)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsScreen(
    state: UiState,
    onTouchEnabled: (Boolean) -> Unit,
    onLayout: (TouchLayout) -> Unit,
    onOpenEditor: () -> Unit,
    onGamepad: ((GamepadMapping) -> GamepadMapping) -> Unit,
    captureFor: PadButton?,
    onCapture: (PadButton?) -> Unit,
    padsVersion: Int,
    onRefreshPads: () -> Unit,
) {
    val layout = state.touchLayout
    val pad = state.gamepad
    val pads = remember(padsVersion) { connectedPads() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            "Kontrol layar sentuh",
            "Tombol di layar dikirim ke game sebagai gamepad virtual SDL (A/B/X/Y, LB/RB, Back/Start, D-pad, dua stik, pedal LT/RT analog).",
        ) {
            ToggleSetting("Aktifkan kontrol layar sentuh FHorizon", state.touchEnabled,
                "Mati = memakai kontrol sentuh bawaan Pinyon Shift (tata letak tetap)") { onTouchEnabled(it) }
            SliderSetting("Transparansi global", layout.globalOpacity, 0.1f..1f, { "${(it * 100).toInt()}%" }) {
                onLayout(layout.copy(globalOpacity = it))
            }
            ToggleSetting("Stik kiri mengambang", layout.floatingLeftStick, "Stik muncul di tempat ibu jari menyentuh (seperempat kiri bawah)") {
                onLayout(layout.copy(floatingLeftStick = it))
            }
            ToggleSetting("Getar saat tombol ditekan", layout.hapticFeedback) { onLayout(layout.copy(hapticFeedback = it)) }
            SliderSetting("Sembunyikan otomatis", layout.autoHideSeconds.toFloat(), 0f..30f,
                { if (it < 1f) "tidak" else "${it.toInt()} dtk" }) { onLayout(layout.copy(autoHideSeconds = it.toInt())) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenEditor) { Text("Atur posisi, ukuran & transparansi…") }
                OutlinedButton(onClick = { onLayout(TouchLayout.defaults()) }) { Text("Tata letak bawaan") }
            }
        }

        SectionCard("Gamepad Bluetooth / USB", "Dideteksi oleh SDL di dalam game; pemetaan di bawah diterapkan sebelum SDL menerima event.") {
            if (pads.isEmpty()) {
                Text("Tidak ada gamepad terhubung.", style = MaterialTheme.typography.bodySmall)
            }
            for (p in pads) {
                Text(
                    "${p.name} · VID %04x PID %04x · ${if (p.vibrators > 0) "getar didukung (${p.vibrators})" else "tanpa motor getar"}".format(p.vendor, p.product),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefreshPads) { Text("Deteksi ulang") }
                OutlinedButton(onClick = { testVibration() }) { Text("Uji getar gamepad") }
            }
            SliderSetting("Dead zone stik", pad.stickDeadZone, 0f..0.4f, { "${(it * 100).toInt()}%" }) { v -> onGamepad { it.copy(stickDeadZone = v) } }
            SliderSetting("Sensitivitas stik", pad.stickSensitivity, 0.5f..2f, { "%.2fx".format(it) }) { v -> onGamepad { it.copy(stickSensitivity = v) } }
            SliderSetting("Dead zone trigger", pad.triggerDeadZone, 0f..0.4f, { "${(it * 100).toInt()}%" }) { v -> onGamepad { it.copy(triggerDeadZone = v) } }
            ToggleSetting("Tukar LT/RT", pad.swapTriggers) { v -> onGamepad { it.copy(swapTriggers = v) } }
            ToggleSetting("Balik sumbu Y stik kanan", pad.invertRightY) { v -> onGamepad { it.copy(invertRightY = v) } }
            ToggleSetting("Getar gamepad", pad.controllerRumble, "Rumble dari game ke motor controller (jika ada)") { v -> onGamepad { it.copy(controllerRumble = v) } }
            ToggleSetting("Getar tablet untuk kontrol sentuh", pad.deviceRumble) { v -> onGamepad { it.copy(deviceRumble = v) } }
            SliderSetting("Kekuatan getar", pad.rumbleStrength, 0f..1f, { "${(it * 100).toInt()}%" }) { v -> onGamepad { it.copy(rumbleStrength = v) } }
        }

        SectionCard("Pemetaan tombol", "Pilih fungsi Xbox 360, lalu tekan tombol fisik di controller.") {
            for (button in PadButton.values()) {
                val physical = pad.remap.entries.firstOrNull { it.value == button.keycode && it.key != button.keycode }?.key
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(button.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        physical?.let { "← " + (PadButton.ofKeycode(it)?.label ?: android.view.KeyEvent.keyCodeToString(it)) } ?: "bawaan",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    )
                    FilterChip(selected = captureFor == button, onClick = { onCapture(if (captureFor == button) null else button) },
                        label = { Text(if (captureFor == button) "Tekan tombol…" else "Ubah") })
                }
            }
            OutlinedButton(onClick = { onGamepad { it.copy(remap = emptyMap()) } }) { Text("Reset pemetaan") }
        }
    }
}

@Suppress("DEPRECATION")
private fun testVibration() {
    for (id in InputDevice.getDeviceIds()) {
        val device = InputDevice.getDevice(id) ?: continue
        if (!GamepadMapping.isGamepad(device.sources)) continue
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = device.vibratorManager
            if (manager.vibratorIds.isNotEmpty()) {
                manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            val vibrator = device.vibrator
            if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
