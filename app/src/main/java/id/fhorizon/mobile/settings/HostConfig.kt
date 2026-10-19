package id.fhorizon.mobile.settings

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Edits the backend's configuration file, <state>/config/pinyon_shift.toml:
 * a flat file of `name = value` lines that the runtime loads into its cvars
 * at start (rex::cvar::LoadConfig). The rules follow Pinyon Shift's
 * src/config/host_config.h so the in-game settings menu and this launcher
 * edit the same file the same way:
 *
 * - a setting is the first line whose name, after leading spaces or tabs, is
 *   followed by optional spaces or tabs and '=';
 * - its value runs to '#' or the end of the line, trimmed, quotes removed;
 * - setting a value replaces that line (not its line ending) with
 *   `name = value`, or appends a line using the file's line ending;
 * - the file is written through a temporary file and ends with one line ending.
 */
object HostConfig {

    private data class Span(val lineBegin: Int, val lineEnd: Int, val valueBegin: Int, val valueEnd: Int)

    private fun isSpace(c: Char) = c == ' ' || c == '\t'

    private fun find(text: String, name: String): Span? {
        var lineBegin = 0
        while (lineBegin <= text.length) {
            var lineEnd = text.indexOf('\n', lineBegin).let { if (it < 0) text.length else it }
            val contentEnd = if (lineEnd > lineBegin && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            var at = lineBegin
            while (at < contentEnd && isSpace(text[at])) at++
            if (text.startsWith(name, at)) {
                var after = at + name.length
                while (after < contentEnd && isSpace(text[after])) after++
                if (after < contentEnd && text[after] == '=') {
                    var valueBegin = after + 1
                    var valueEnd = text.indexOf('#', valueBegin).let { if (it < 0 || it > contentEnd) contentEnd else it }
                    while (valueBegin < valueEnd && isSpace(text[valueBegin])) valueBegin++
                    while (valueEnd > valueBegin && isSpace(text[valueEnd - 1])) valueEnd--
                    return Span(lineBegin, contentEnd, valueBegin, valueEnd)
                }
            }
            if (lineEnd >= text.length) break
            lineBegin = lineEnd + 1
        }
        return null
    }

    /** The unquoted value of [name], or null when absent or empty. */
    fun get(text: String, name: String): String? {
        val span = find(text, name) ?: return null
        var value = text.substring(span.valueBegin, span.valueEnd)
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') value = value.substring(1, value.length - 1)
        return value.ifEmpty { null }
    }

    /** [text] with `name = literal` (strings must already be quoted). */
    fun set(text: String, name: String, literal: String): String {
        val line = "$name = $literal"
        val span = find(text, name)
        if (span != null) return text.substring(0, span.lineBegin) + line + text.substring(span.lineEnd)
        val ending = if (text.contains("\r\n")) "\r\n" else "\n"
        val base = if (text.isEmpty() || text.endsWith("\n")) text else text + ending
        return base + line + ending
    }

    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(c)
            }
        }
        append('"')
    }

    fun read(file: File): String = if (file.isFile) file.readText() else ""

    /** Applies [values] (name to TOML literal); returns the changed names. */
    fun apply(file: File, values: Map<String, String>, backupDir: File? = null): List<String> {
        val original = read(file)
        var text = original
        val changed = mutableListOf<String>()
        for ((name, literal) in values) {
            val before = find(text, name)?.let { text.substring(it.valueBegin, it.valueEnd) }
            if (before != literal) {
                text = set(text, name, literal)
                changed += name
            }
        }
        if (changed.isEmpty()) return changed
        if (!text.endsWith("\n")) text += "\n"
        file.parentFile?.mkdirs()
        if (backupDir != null && original.isNotEmpty()) {
            backupDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            File(backupDir, "pinyon_shift-$stamp.toml").writeText(original)
            // Keep the ten newest backups.
            backupDir.listFiles { f -> f.name.startsWith("pinyon_shift-") }
                ?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
        return changed
    }
}
