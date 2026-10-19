package id.fhorizon.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Sunset = Color(0xFFFF8A3D)
val Asphalt = Color(0xFF0F1115)
val Sky = Color(0xFF3D8BFF)
val Ok = Color(0xFF3DBE7A)
val Caution = Color(0xFFE0A030)
val Stop = Color(0xFFE5484D)

private val DarkColors = darkColorScheme(
    primary = Sunset,
    onPrimary = Color(0xFF2A1200),
    secondary = Sky,
    onSecondary = Color(0xFF001A3D),
    background = Asphalt,
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF161920),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF22262F),
    onSurfaceVariant = Color(0xFFB8BDC9),
    error = Stop,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC85A10),
    onPrimary = Color.White,
    secondary = Color(0xFF1F5FC4),
    background = Color(0xFFF7F7F9),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEEF2),
    error = Stop,
)

@Composable
fun FHorizonTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
