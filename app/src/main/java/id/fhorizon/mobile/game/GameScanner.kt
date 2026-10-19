package id.fhorizon.mobile.game

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Result of comparing a folder against a supported dump. */
data class ScanReport(
    val root: File,
    val dumpId: String,
    val expectedFiles: Int,
    val presentFiles: Int,
    val missing: List<String>,
    val missingCount: Int,
    val wrongSize: List<String>,
    val wrongSizeCount: Int,
    val presentBytes: Long,
    val expectedBytes: Long,
) {
    val complete: Boolean get() = missingCount == 0 && wrongSizeCount == 0
}

data class VerifyReport(
    val root: File,
    val dumpId: String,
    val checkedFiles: Int,
    val mismatched: List<String>,
    val missing: List<String>,
    val cancelled: Boolean,
) {
    val ok: Boolean get() = !cancelled && mismatched.isEmpty() && missing.isEmpty()
}

/**
 * Finds the extracted game folder (the one holding default.xex) and checks it
 * against the catalog. Pure java.io, so it is unit-tested on the JVM.
 */
class GameScanner(private val catalog: GameCatalog) {

    /** Folders that are the launcher's own and never hold the game. */
    private val skipNames = setOf("save", "backups", "module", "logs", "android")

    fun findGameRoot(base: File, maxDepth: Int = 3): File? {
        if (!base.isDirectory) return null
        if (resolve(base, "default.xex") != null) return base
        if (maxDepth == 0) return null
        val children = base.listFiles { f -> f.isDirectory && f.name.lowercase() !in skipNames }
            ?.sortedWith(compareBy({ rank(it.name) }, { it.name.lowercase() }))
            ?: return null
        for (child in children) {
            findGameRoot(child, maxDepth - 1)?.let { return it }
        }
        return null
    }

    // Likely names first: Game, base (Pinyon Shift's .local/game/base), FH1.
    private fun rank(name: String): Int = when (name.lowercase()) {
        "game" -> 0
        "base" -> 1
        "forza horizon", "forzahorizon", "fh1" -> 2
        else -> 3
    }

    fun primaryDump(): SupportedDump = catalog.dumps.first()

    private fun resolve(dir: File, name: String): File? = CaseInsensitiveResolver(dir).resolve(name)

    fun quickCheck(root: File, dump: SupportedDump = primaryDump()): ScanReport {
        val resolver = CaseInsensitiveResolver(root)
        val missing = mutableListOf<String>()
        val wrongSize = mutableListOf<String>()
        var missingCount = 0
        var wrongCount = 0
        var present = 0
        var presentBytes = 0L
        for (entry in dump.files) {
            val file = resolver.resolve(entry.path)
            if (file == null) {
                missingCount++
                if (missing.size < 50) missing += entry.path
                continue
            }
            val length = file.length()
            if (length != entry.size) {
                wrongCount++
                if (wrongSize.size < 50) wrongSize += "${entry.path} (${length} B, expected ${entry.size} B)"
                continue
            }
            present++
            presentBytes += length
        }
        return ScanReport(
            root, dump.id, dump.files.size, present, missing, missingCount, wrongSize, wrongCount,
            presentBytes, dump.sizeBytes,
        )
    }

    /**
     * SHA-256 of every catalog file. [progress] receives (bytesDone, bytesTotal,
     * currentPath); [cancelled] is polled between buffers.
     */
    fun verify(
        root: File,
        dump: SupportedDump = primaryDump(),
        progress: (Long, Long, String) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ): VerifyReport {
        val resolver = CaseInsensitiveResolver(root)
        val mismatched = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val total = dump.sizeBytes
        var done = 0L
        var checked = 0
        val buffer = ByteArray(1 shl 20)
        for (entry in dump.files) {
            if (cancelled()) return VerifyReport(root, dump.id, checked, mismatched, missing, true)
            val file = resolver.resolve(entry.path)
            if (file == null) {
                missing += entry.path
                done += entry.size
                continue
            }
            progress(done, total, entry.path)
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    done += read
                    if (cancelled()) return VerifyReport(root, dump.id, checked, mismatched, missing, true)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != entry.sha256) mismatched += entry.path
            checked++
        }
        progress(total, total, "")
        return VerifyReport(root, dump.id, checked, mismatched, missing, false)
    }

    /** Title update v4 files in a folder (the backend's state/title-update-v4). */
    fun titleUpdatePresent(folder: File): SupportedTitleUpdate? {
        val resolver = CaseInsensitiveResolver(folder)
        return catalog.titleUpdates.firstOrNull { update ->
            update.files.all { f -> resolver.resolve(f.path)?.length() == f.size }
        }
    }
}

/**
 * Resolves catalog paths ("media/Audio/x.xml") to files, ignoring case: the
 * disc's file system is case-insensitive and extraction tools differ.
 */
class CaseInsensitiveResolver(private val root: File) {
    private val listings = HashMap<File, Map<String, File>>()

    fun resolve(relative: String): File? {
        val direct = File(root, relative)
        if (direct.isFile) return direct
        var current = root
        val parts = relative.split('/', '\\').filter { it.isNotEmpty() }
        for ((index, part) in parts.withIndex()) {
            val listing = listings.getOrPut(current) {
                current.listFiles()?.associateBy { it.name.lowercase() } ?: emptyMap()
            }
            val next = listing[part.lowercase()] ?: return null
            if (index == parts.lastIndex) return if (next.isFile) next else null
            if (!next.isDirectory) return null
            current = next
        }
        return null
    }
}
