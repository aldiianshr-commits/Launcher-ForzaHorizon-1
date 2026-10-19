package id.fhorizon.mobile

import id.fhorizon.mobile.settings.HostConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HostConfigTest {
    @Test
    fun getReadsFirstMatchingLineAndStripsQuotesAndComments() {
        val text = "# comment\n  vsync = false # off\nvsync_extra = 1\npresent_effect=\"fsr\"\nempty =\n"
        assertEquals("false", HostConfig.get(text, "vsync"))
        assertEquals("1", HostConfig.get(text, "vsync_extra"))
        assertEquals("fsr", HostConfig.get(text, "present_effect"))
        assertNull(HostConfig.get(text, "empty"))
        assertNull(HostConfig.get(text, "missing"))
    }

    @Test
    fun setReplacesLineKeepingEndingsOrAppends() {
        val crlf = "a = 1\r\nb = 2\r\n"
        assertEquals("a = 1\r\nb = 3\r\n", HostConfig.set(crlf, "b", "3"))
        assertEquals("a = 1\r\nb = 2\r\nc = \"x\"\r\n", HostConfig.set(crlf, "c", "\"x\""))
        assertEquals("a = 1\nz = 9\n", HostConfig.set("a = 1", "z", "9"))
        assertEquals("q = 5\n", HostConfig.set("", "q", "5"))
    }

    @Test
    fun applyWritesOnlyChangesAndBacksUp() {
        val dir = Files.createTempDirectory("hostcfg").toFile()
        val file = File(dir, "config/pinyon_shift.toml")
        file.parentFile!!.mkdirs()
        file.writeText("vsync = true\nenabled_mods = \"x\"\n")
        val changed = HostConfig.apply(file, mapOf("vsync" to "true", "anisotropic_override" to "5"), File(dir, "backups"))
        assertEquals(listOf("anisotropic_override"), changed)
        val text = file.readText()
        assertTrue(text.contains("enabled_mods = \"x\""))
        assertTrue(text.endsWith("anisotropic_override = 5\n"))
        assertEquals(1, File(dir, "backups").listFiles()!!.size)
        assertEquals(emptyList<String>(), HostConfig.apply(file, mapOf("vsync" to "true"), File(dir, "backups")))
        dir.deleteRecursively()
    }

    @Test
    fun quoteEscapes() {
        assertEquals("\"a\\\"b\\\\c\"", HostConfig.quote("a\"b\\c"))
    }
}
