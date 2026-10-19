package id.fhorizon.mobile.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import id.fhorizon.mobile.controls.TouchLayout
import id.fhorizon.mobile.controls.TouchOverlayView
import id.fhorizon.mobile.settings.SettingsRepository
import id.fhorizon.mobile.ui.theme.FHorizonTheme

/**
 * Drag controls to move them; select one to change its size, transparency
 * and visibility. Saved to the same layout the game activity draws.
 */
class TouchLayoutEditorActivity : ComponentActivity() {
    private lateinit var repo: SettingsRepository
    private var layout by mutableStateOf(TouchLayout.defaults())
    private var selected by mutableStateOf<String?>(null)
    private lateinit var overlay: TouchOverlayView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(this)
        layout = repo.touchLayout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(28, 34, 44)) }
        overlay = TouchOverlayView(this).apply {
            editMode = true
            layout = this@TouchLayoutEditorActivity.layout
            onSelected = { selected = it }
            onLayoutChanged = { this@TouchLayoutEditorActivity.layout = it }
            setOnTouchListener { _, event -> handle(event) }
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val panel = ComposeView(this).apply {
            setContent { FHorizonTheme { Panel() } }
        }
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
    }

    private fun apply(next: TouchLayout) {
        layout = next
        overlay.layout = next
    }

    @androidx.compose.runtime.Composable
    private fun Panel() {
        Column(
            Modifier.padding(top = 8.dp).width(420.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val control = layout.controls.firstOrNull { it.id == selected }
            if (control == null) {
                Text("Seret kontrol untuk memindahkan. Ketuk satu kontrol untuk mengubah ukuran/transparansi.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Kontrol: ${control.label}", style = MaterialTheme.typography.titleSmall)
                Text("Ukuran ${(control.size * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(value = control.size, valueRange = 0.06f..0.5f,
                    onValueChange = { v -> apply(layout.update(control.id) { it.copy(size = v) }) })
                Text("Transparansi ${(control.opacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(value = control.opacity, valueRange = 0.1f..1f,
                    onValueChange = { v -> apply(layout.update(control.id) { it.copy(opacity = v) }) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tampilkan", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = control.visible, onCheckedChange = { v -> apply(layout.update(control.id) { it.copy(visible = v) }) })
                }
            }
            Text("Transparansi global ${(layout.globalOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            Slider(value = layout.globalOpacity, valueRange = 0.1f..1f, onValueChange = { v -> apply(layout.copy(globalOpacity = v)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { repo.touchLayout = layout; finish() }) { Text("Simpan") }
                OutlinedButton(onClick = { apply(TouchLayout.defaults()); selected = null; overlay.selectedId = null }) { Text("Bawaan") }
                OutlinedButton(onClick = { finish() }) { Text("Batal") }
            }
        }
    }
}
