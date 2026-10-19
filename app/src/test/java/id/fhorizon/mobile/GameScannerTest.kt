package id.fhorizon.mobile

import id.fhorizon.mobile.game.GameCatalog
import id.fhorizon.mobile.game.GameScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class GameScannerTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val xex = "XEX2-test".toByteArray()
    private val zip = ByteArray(1000) { it.toByte() }

    private fun catalog(): GameCatalog = GameCatalog.parse(
        """{"schema_version":1,"dumps":[{"id":"test-dump","serial":"T-1","region":"USA","title_id":"4d5309c9",
            "file_count":2,"size_bytes":${xex.size + zip.size},
            "files":[["default.xex",${xex.size},"${sha(xex)}"],["media/Audio/Test.zip",${zip.size},"${sha(zip)}"]]}],
            "title_updates":[]}""",
    )

    @Test
    fun findsNestedRootIgnoringLauncherFolders() {
        val base = Files.createTempDirectory("fh").toFile()
        File(base, "Save/user").mkdirs()
        File(base, "Save/default.xex").writeBytes(xex)  // never picked: Save is the launcher's
        val game = File(base, "Game").apply { mkdirs() }
        File(game, "DEFAULT.XEX").writeBytes(xex)
        val scanner = GameScanner(catalog())
        assertEquals(game, scanner.findGameRoot(base))
        base.deleteRecursively()
    }

    @Test
    fun quickCheckAndVerifyCaseInsensitive() {
        val root = Files.createTempDirectory("fhgame").toFile()
        File(root, "default.xex").writeBytes(xex)
        File(root, "MEDIA/audio").mkdirs()
        File(root, "MEDIA/audio/test.ZIP").writeBytes(zip)
        val scanner = GameScanner(catalog())
        val dump = scanner.primaryDump()
        assertEquals("4D5309C9", dump.titleId)
        val quick = scanner.quickCheck(root)
        assertTrue(quick.complete)
        assertEquals(2, quick.presentFiles)
        val report = scanner.verify(root)
        assertTrue(report.ok)

        File(root, "MEDIA/audio/test.ZIP").writeBytes(zip.copyOf().also { it[5] = 99 })
        val bad = scanner.verify(root)
        assertFalse(bad.ok)
        assertEquals(listOf("media/Audio/Test.zip"), bad.mismatched)

        File(root, "MEDIA/audio/test.ZIP").delete()
        val missing = scanner.quickCheck(root)
        assertFalse(missing.complete)
        assertEquals(1, missing.missingCount)
        root.deleteRecursively()
    }

    @Test
    fun verifyCanBeCancelled() {
        val root = Files.createTempDirectory("fhcancel").toFile()
        File(root, "default.xex").writeBytes(xex)
        val report = GameScanner(catalog()).verify(root, cancelled = { true })
        assertTrue(report.cancelled)
        assertFalse(report.ok)
        root.deleteRecursively()
    }

    @Test
    fun realCatalogAssetParses() {
        val asset = listOf(File("src/main/assets/catalog/fh1-catalog.json"), File("app/src/main/assets/catalog/fh1-catalog.json"))
            .first { it.isFile }
        val catalog = GameCatalog.parse(asset.readText())
        val dump = catalog.dumps.first()
        assertEquals("4D5309C9", dump.titleId)
        assertEquals(2400, dump.files.size)
        assertNotNull(dump.executable)
        assertEquals(dump.sizeBytes, dump.files.sumOf { it.size })
    }
}
