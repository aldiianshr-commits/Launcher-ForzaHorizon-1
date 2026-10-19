package id.fhorizon.mobile.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where FHorizon Mobile keeps things.
 *
 * Shared storage (needs "All files access"):
 *   /storage/emulated/0/FHorizon/            default game folder (holds default.xex,
 *                                            or a subfolder that does)
 *   /storage/emulated/0/FHorizon/Save/       the game's state root: user/ (the save
 *                                            profile), config/, logs/, cache/, drivers/
 *   /storage/emulated/0/FHorizon/Backups/    save backups made by the launcher
 *   /storage/emulated/0/FHorizon/Module/     drop a backend module (.zip) or a
 *                                            Pinyon Shift .apk here to import it
 *   /storage/emulated/0/FHorizon/Logs/       launcher diagnostics
 *
 * App-internal storage (only the app can write, so libraries may be loaded):
 *   files/modules/active/                    the installed backend module
 */
object FhPaths {
    const val ROOT_NAME = "FHorizon"

    val sharedRoot: File
        get() = File(Environment.getExternalStorageDirectory(), ROOT_NAME)

    val defaultGameFolder: File get() = sharedRoot
    val saveRoot: File get() = File(sharedRoot, "Save")
    val backupsRoot: File get() = File(sharedRoot, "Backups")
    val moduleDropFolder: File get() = File(sharedRoot, "Module")
    val launcherLogs: File get() = File(sharedRoot, "Logs")

    /** The game's own save profile inside the state root. */
    val saveProfile: File get() = File(saveRoot, "user")
    val hostConfigFile: File get() = File(saveRoot, "config/pinyon_shift.toml")
    val gameLogs: File get() = File(saveRoot, "logs")
    val gameCrashes: File get() = File(saveRoot, "crashes")
    val driversRoot: File get() = File(saveRoot, "drivers")
    val shaderCache: File get() = File(saveRoot, "cache")

    fun modulesRoot(context: Context): File = File(context.filesDir, "modules")
    fun activeModule(context: Context): File = File(modulesRoot(context), "active")
    fun launchPlanFile(context: Context): File = File(context.filesDir, "launch-plan.json")
    fun lastExitFile(context: Context): File = File(context.filesDir, "last-game-exit.json")

    /** App-specific external folder, readable by native code without permissions. */
    fun appExternal(context: Context): File =
        context.getExternalFilesDir(null) ?: File(context.filesDir, "external")

    /** Fallback location for game files copied in through the system file picker. */
    fun importedGameFolder(context: Context): File = File(appExternal(context), "game")

    fun ensureSharedLayout() {
        for (dir in listOf(sharedRoot, saveRoot, backupsRoot, moduleDropFolder, launcherLogs)) {
            if (!dir.isDirectory) dir.mkdirs()
        }
    }
}
