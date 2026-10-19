package id.fhorizon.mobile.core

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

fun parseJsonOrNull(text: String): JSONObject? = try {
    JSONObject(text)
} catch (e: Exception) {
    null
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}
