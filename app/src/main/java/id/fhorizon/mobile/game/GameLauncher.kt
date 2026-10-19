package id.fhorizon.mobile.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import id.fhorizon.mobile.core.AppLog
import id.fhorizon.mobile.core.FhPaths
import id.fhorizon.mobile.device.DeviceInfo
import id.fhorizon.mobile.module.InstalledModule
import id.fhorizon.mobile.settings.FpsLimit
import id.fhorizon.mobile.settings.GraphicsSettings
import id.fhorizon.mobile.settings.HostConfig
import id.fhorizon.mobile.settings.SettingsRepository
import java.io.File

/**
 * Prepares a game start: writes the graphics settings into the backend's
 * configuration file, assembles the environment and the launch plan, and
 * starts FHorizonGameActivity in the game process.
 */
object GameLauncher {

    data class Prepared(val plan: GameLaunchPlan, val changedCvars: List<String>)

    fun prepare(
        context: Context,
        repo: SettingsRepository,
        graphics: GraphicsSettings,
        module: InstalledModule,
        gameRoot: File,
        device: DeviceInfo?,
    ): Prepared {
        val state = FhPaths.saveRoot
        for (dir in listOf("user", "config", "logs", "cache", "drivers", "crashes", "update", "backups")) {
            File(state, dir).mkdirs()
        }

        // Graphics and input cvars into <Save>/config/pinyon_shift.toml.
        val cvars = LinkedHashMap(graphics.toCvars())
        // The launcher's own on-screen controls replace Pinyon Shift's
        // built-in touch pad when enabled; otherwise the built-in one stays.
        cvars["pinyon_shift_touch_controls"] = (!repo.touchControlsEnabled).toString()
        cvars["vulkan_capability_report"] = "true"
        val changed = HostConfig.apply(FhPaths.hostConfigFile, cvars, File(state, "config/backups"))
        AppLog.i("Backend config: ${changed.size} setting(s) changed (${changed.joinToString()})")

        val env = linkedMapOf(
            "HOME" to context.filesDir.absolutePath,
            "PINYON_SHIFT_STATE_ROOT" to state.absolutePath,
            "PINYON_SHIFT_GAME_ROOT" to gameRoot.absolutePath,
            "REX_ANDROID_DRIVERS_DIR" to FhPaths.driversRoot.absolutePath,
        )
        if (module.buildManifestFile.isFile) env["PINYON_SHIFT_BUILD_MANIFEST"] = module.buildManifestFile.absolutePath
        device?.adrenoModel?.let { env["PINYON_SHIFT_ADRENO_MODEL"] = it }
        if (File(state, "cache/rally_adapter/rally-stage.toml").isFile) env["PINYON_SHIFT_RALLY_PREPARED"] = "1"

        val arguments = repo.extraArguments.lines().map { it.trim() }.filter { it.startsWith("--") }

        val metrics = displaySize(context)
        val (width, height) = graphics.surfaceSize(metrics.first, metrics.second)
        // A refresh rate the frame limit divides evenly (Xiaomi Pad 6 panel:
        // 30/48/50/60/90/120/144 Hz).
        val rate = when (graphics.fps) {
            FpsLimit.F30 -> 60f
            FpsLimit.F45 -> 90f
            FpsLimit.F60 -> 60f
        }
        val logFile = File(FhPaths.launcherLogs, "launcher.log").absolutePath
        val plan = GameLaunchPlan(
            moduleDir = module.directory.absolutePath,
            loadOrder = module.loadOrder,
            entryLibrary = "libmain.so",
            environment = env,
            arguments = arguments,
            surfaceWidth = width,
            surfaceHeight = height,
            frameRateHint = rate,
            fullscreen = graphics.fullscreen,
            sustainedPerformance = graphics.sustainedPerformance,
            touchControls = repo.touchControlsEnabled,
            touchLayoutJson = repo.touchLayout.toJson().toString(),
            gamepadJson = repo.gamepad.toJson().toString(),
            logFile = logFile,
        )
        plan.write(FhPaths.launchPlanFile(context))
        AppLog.i("Launch plan: surface ${width}x$height @${rate}Hz, ${module.loadOrder.size} libraries, game ${gameRoot.absolutePath}")
        return Prepared(plan, changed)
    }

    fun start(activity: Activity) {
        // Same task as the launcher: when the game process exits, the player
        // is back in FHorizon Mobile with the diagnostics at hand.
        activity.startActivity(Intent(activity, FHorizonGameActivity::class.java))
    }

    private fun displaySize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }
}
