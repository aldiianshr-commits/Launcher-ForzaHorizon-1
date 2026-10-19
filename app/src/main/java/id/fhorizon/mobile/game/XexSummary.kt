package id.fhorizon.mobile.game

import id.fhorizon.mobile.core.objects
import id.fhorizon.mobile.core.strings
import org.json.JSONObject

data class XexImport(val name: String, val version: String, val records: Int)

/** What libfhcore read from default.xex's plaintext headers. */
data class XexSummary(
    val ok: Boolean,
    val error: String?,
    val titleId: String?,
    val mediaId: String?,
    val version: String?,
    val baseVersion: String?,
    val discNumber: Int,
    val discCount: Int,
    val entryPoint: String?,
    val imageBase: String?,
    val imageSize: Long,
    val regions: String?,
    val encryption: String?,
    val compression: String?,
    val originalPeName: String?,
    val imports: List<XexImport>,
    val staticLibraries: List<String>,
    val warnings: List<String>,
    val rawJson: String,
) {
    /** System libraries the recompiled code calls into through the runtime's HLE. */
    val kernelImports: Int get() = imports.filter { it.name.startsWith("xboxkrnl", true) }.sumOf { it.records }
    val xamImports: Int get() = imports.filter { it.name.startsWith("xam", true) }.sumOf { it.records }

    companion object {
        const val FORZA_HORIZON_TITLE_ID = "4D5309C9"

        fun parse(json: String): XexSummary {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                return failed("unreadable inspector output", json)
            }
            if (!root.optBoolean("ok")) return failed(root.optString("error", "unknown error"), json)
            val exec = root.optJSONObject("execution")
            val security = root.optJSONObject("security")
            return XexSummary(
                ok = true,
                error = null,
                titleId = exec?.optString("title_id"),
                mediaId = exec?.optString("media_id"),
                version = exec?.optString("version"),
                baseVersion = exec?.optString("base_version"),
                discNumber = exec?.optInt("disc_number") ?: 0,
                discCount = exec?.optInt("disc_count") ?: 0,
                entryPoint = root.optString("entry_point", null),
                imageBase = root.optString("image_base", null),
                imageSize = security?.optLong("image_size") ?: 0L,
                regions = security?.optString("regions_text"),
                encryption = root.optString("encryption", null),
                compression = root.optString("compression", null),
                originalPeName = root.optString("original_pe_name", null),
                imports = root.optJSONArray("imports").objects().map {
                    XexImport(it.optString("name"), it.optString("version"), it.optInt("records"))
                },
                staticLibraries = root.optJSONArray("static_libraries").objects().map {
                    it.optString("name") + " " + it.optString("version")
                },
                warnings = root.optJSONArray("warnings").strings(),
                rawJson = json,
            )
        }

        private fun failed(error: String, json: String) = XexSummary(
            false, error, null, null, null, null, 0, 0, null, null, 0, null, null, null, null,
            emptyList(), emptyList(), emptyList(), json,
        )
    }
}
