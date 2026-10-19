package id.fhorizon.mobile.game

import id.fhorizon.mobile.core.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the game process needs, written by the launcher process right
 * before it starts FHorizonGameActivity (files/launch-plan.json). The game
 * activity runs in its own process and reads only this file.
 */
data class GameLaunchPlan(
    val moduleDir: String,
    val loadOrder: List<String>,
    val entryLibrary: String,
    val environment: Map<String, String>,
    val arguments: List<String>,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val frameRateHint: Float,
    val fullscreen: Boolean,
    val sustainedPerformance: Boolean,
    val touchControls: Boolean,
    val touchLayoutJson: String,
    val gamepadJson: String,
    val logFile: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("module_dir", moduleDir)
        .put("load_order", JSONArray(loadOrder))
        .put("entry_library", entryLibrary)
        .put("environment", JSONObject(environment as Map<*, *>))
        .put("arguments", JSONArray(arguments))
        .put("surface_width", surfaceWidth)
        .put("surface_height", surfaceHeight)
        .put("frame_rate_hint", frameRateHint.toDouble())
        .put("fullscreen", fullscreen)
        .put("sustained_performance", sustainedPerformance)
        .put("touch_controls", touchControls)
        .put("touch_layout", touchLayoutJson)
        .put("gamepad", gamepadJson)
        .put("log_file", logFile)

    fun write(file: File) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(toJson().toString(2))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        fun read(file: File): GameLaunchPlan? {
            if (!file.isFile) return null
            val json = try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                return null
            }
            val env = HashMap<String, String>()
            json.optJSONObject("environment")?.let { obj ->
                for (key in obj.keys()) env[key] = obj.optString(key)
            }
            return GameLaunchPlan(
                moduleDir = json.optString("module_dir"),
                loadOrder = json.optJSONArray("load_order").strings(),
                entryLibrary = json.optString("entry_library", "libmain.so"),
                environment = env,
                arguments = json.optJSONArray("arguments").strings(),
                surfaceWidth = json.optInt("surface_width"),
                surfaceHeight = json.optInt("surface_height"),
                frameRateHint = json.optDouble("frame_rate_hint", 0.0).toFloat(),
                fullscreen = json.optBoolean("fullscreen", true),
                sustainedPerformance = json.optBoolean("sustained_performance", true),
                touchControls = json.optBoolean("touch_controls", true),
                touchLayoutJson = json.optString("touch_layout", "{}"),
                gamepadJson = json.optString("gamepad", "{}"),
                logFile = json.optString("log_file", ""),
            )
        }
    }
}
