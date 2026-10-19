package id.fhorizon.mobile.settings

import android.content.Context
import android.content.SharedPreferences
import id.fhorizon.mobile.controls.GamepadMapping
import id.fhorizon.mobile.controls.TouchLayout
import id.fhorizon.mobile.core.parseJsonOrNull
import org.json.JSONObject

/** Launcher state that survives restarts (SharedPreferences, JSON values). */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("fhorizon", Context.MODE_PRIVATE)

    var graphics: GraphicsSettings?
        get() = prefs.getString(KEY_GRAPHICS, null)?.let { GraphicsSettings.fromJson(parseJsonOrNull(it)) }
        set(value) = prefs.edit().putString(KEY_GRAPHICS, value?.toJson()?.toString()).apply()

    var touchLayout: TouchLayout
        get() = TouchLayout.fromJson(prefs.getString(KEY_TOUCH, null)?.let { parseJsonOrNull(it) })
        set(value) = prefs.edit().putString(KEY_TOUCH, value.toJson().toString()).apply()

    var touchControlsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TOUCH_ENABLED, value).apply()

    var gamepad: GamepadMapping
        get() = GamepadMapping.fromJson(prefs.getString(KEY_GAMEPAD, null)?.let { parseJsonOrNull(it) })
        set(value) = prefs.edit().putString(KEY_GAMEPAD, value.toJson().toString()).apply()

    /** A folder the player picked, as a file-system path. */
    var gameFolder: String?
        get() = prefs.getString(KEY_GAME_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_GAME_FOLDER, value).apply()

    /** The last full SHA-256 verification: root path, default.xex size+mtime, result. */
    var verification: JSONObject?
        get() = prefs.getString(KEY_VERIFY, null)?.let { parseJsonOrNull(it) }
        set(value) = prefs.edit().putString(KEY_VERIFY, value?.toString()).apply()

    var showPerformanceOverlay: Boolean
        get() = prefs.getBoolean(KEY_PERF_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_PERF_OVERLAY, value).apply()

    /** Extra "--name=value" arguments for the backend, one per line (advanced). */
    var extraArguments: String
        get() = prefs.getString(KEY_EXTRA_ARGS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EXTRA_ARGS, value).apply()

    companion object {
        private const val KEY_GRAPHICS = "graphics"
        private const val KEY_TOUCH = "touch_layout"
        private const val KEY_TOUCH_ENABLED = "touch_enabled"
        private const val KEY_GAMEPAD = "gamepad"
        private const val KEY_GAME_FOLDER = "game_folder"
        private const val KEY_VERIFY = "verification"
        private const val KEY_PERF_OVERLAY = "perf_overlay"
        private const val KEY_EXTRA_ARGS = "extra_args"
    }
}
