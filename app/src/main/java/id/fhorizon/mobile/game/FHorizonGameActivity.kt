package id.fhorizon.mobile.game

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import id.fhorizon.mobile.controls.GamepadMapping
import id.fhorizon.mobile.controls.TouchLayout
import id.fhorizon.mobile.controls.TouchOutput
import id.fhorizon.mobile.controls.TouchOverlayView
import id.fhorizon.mobile.core.FhPaths
import id.fhorizon.mobile.drivers.DriverManager
import org.json.JSONObject
import org.libsdl.app.FHVirtualPad
import org.libsdl.app.SDLActivity
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * SDL's activity running the recompiled game from the installed backend
 * module. It runs in the ":game" process (see AndroidManifest.xml).
 *
 * Before SDL starts it sets the environment the ReXGlue/Pinyon Shift runtime
 * reads (game root, state root, GPU driver folder) and loads the module's
 * libraries from app-internal storage in dependency order. SDL then calls
 * SDL_main in libmain.so, the recompiled Forza Horizon executable.
 *
 * Around the game it adds: the custom on-screen controls (as an SDL virtual
 * gamepad), physical controller remapping and shaping, the output surface
 * size, a display refresh-rate hint, immersive full screen and sustained
 * performance mode.
 */
class FHorizonGameActivity : SDLActivity() {

    private var plan: GameLaunchPlan? = null
    private var overlay: TouchOverlayView? = null
    private var mapping: GamepadMapping = GamepadMapping()

    override fun onCreate(savedInstanceState: Bundle?) {
        plan = GameLaunchPlan.read(FhPaths.launchPlanFile(this))
        val plan = plan
        if (plan == null) {
            Toast.makeText(this, "Rencana peluncuran tidak ada; buka game dari FHorizon Mobile.", Toast.LENGTH_LONG).show()
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        for ((name, value) in plan.environment) setEnvironment(name, value)
        mapping = GamepadMapping.fromJson(parseOrNull(plan.gamepadJson))
        FHVirtualPad.configure(
            plan.touchControls, mapping.controllerRumble, mapping.deviceRumble, mapping.rumbleStrength, vibrator(),
        )
        writeSession("started")
        super.onCreate(savedInstanceState)
        if (SDLActivity.mBrokenLibraries) return

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (plan.sustainedPerformance) {
            try {
                window.setSustainedPerformanceMode(true)
            } catch (e: Exception) {
                Log.w(TAG, "sustained performance mode unavailable", e)
            }
        }
        if (plan.fullscreen) applyImmersive()
        configureSurface(plan)
        if (plan.touchControls) addOverlay(plan)
    }

    // ---- Library loading from the backend module -------------------------

    override fun getLibraries(): Array<String> = arrayOf("main")

    override fun loadLibraries() {
        val plan = plan ?: throw UnsatisfiedLinkError("launch plan missing")
        if (!cpuHasFeatures("atomics", "asimdrdm")) {
            throw UnsatisfiedLinkError(
                "Modul backend dikompilasi untuk ARMv8.2-A (LSE atomics, RDM); CPU ini tidak mendukungnya.",
            )
        }
        val dir = File(plan.moduleDir)
        for (name in plan.loadOrder) {
            val file = File(dir, name)
            if (!file.isFile) throw UnsatisfiedLinkError("modul tidak lengkap: $name hilang")
            Log.i(TAG, "loading ${file.absolutePath}")
            System.load(file.absolutePath)
        }
    }

    override fun getMainSharedObject(): String {
        val plan = plan ?: return super.getMainSharedObject()
        return File(plan.moduleDir, plan.entryLibrary).absolutePath
    }

    override fun getArguments(): Array<String> = plan?.arguments?.toTypedArray() ?: emptyArray()

    // ---- Surface, display and window ------------------------------------

    private fun configureSurface(plan: GameLaunchPlan) {
        val surface = SDLActivity.mSurface ?: return
        val holder = surface.holder
        if (plan.surfaceWidth > 0 && plan.surfaceHeight > 0) {
            // The Vulkan swapchain takes this size; the compositor scales it
            // to the panel. The guest itself renders at 1280x720.
            holder.setFixedSize(plan.surfaceWidth, plan.surfaceHeight)
        }
        if (plan.frameRateHint > 0f && Build.VERSION.SDK_INT >= 30) {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) = applyFrameRate(h, plan.frameRateHint)
                override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) =
                    applyFrameRate(h, plan.frameRateHint)
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
            if (holder.surface?.isValid == true) applyFrameRate(holder, plan.frameRateHint)
        }
    }

    private fun applyFrameRate(holder: SurfaceHolder, rate: Float) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        } catch (e: Exception) {
            Log.w(TAG, "setFrameRate($rate) failed", e)
        }
    }

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && plan?.fullscreen == true) applyImmersive()
        if (!hasFocus) {
            overlay?.releaseAll()
            FHVirtualPad.releaseAll()
        }
    }

    // ---- On-screen controls ---------------------------------------------

    private fun addOverlay(plan: GameLaunchPlan) {
        val layout = SDLActivity.mLayout ?: return
        val view = TouchOverlayView(this).apply {
            this.layout = TouchLayout.fromJson(parseOrNull(plan.touchLayoutJson))
            output = object : TouchOutput {
                override fun button(keycode: Int, pressed: Boolean) = FHVirtualPad.button(keycode, pressed)
                override fun axis(axis: Int, value: Float) = FHVirtualPad.axis(axis, value)
            }
            isClickable = false
            isFocusable = false
        }
        layout.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = view
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val view = overlay
        if (view != null && view.handle(event)) return true
        return super.dispatchTouchEvent(event)
    }

    // ---- Physical controllers -------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val remapped = GamepadMapping.remapKeyEvent(event, mapping)
        return super.dispatchKeyEvent(remapped ?: event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val shaped = GamepadMapping.shapeMotionEvent(event, mapping) ?: return super.dispatchGenericMotionEvent(event)
        return try {
            super.dispatchGenericMotionEvent(shaped)
        } finally {
            shaped.recycle()
        }
    }

    // ---- Called by the runtime (Pinyon Shift's SETTINGS > IMPORT DRIVER) ----

    /** SETTINGS > GRAPHICS > IMPORT DRIVER (.ZIP) in the game's own menu, from the native thread. */
    @Suppress("unused")
    fun importGpuDriver() {
        runOnUiThread {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
            try {
                startActivityForResult(intent, REQUEST_DRIVER_ZIP)
            } catch (e: Exception) {
                Toast.makeText(this, "Pemilih file tidak tersedia", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_DRIVER_ZIP) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        Thread({
            val message = try {
                var display = "driver.zip"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) display = it.getString(0)
                }
                val name = contentResolver.openInputStream(uri)!!.use { DriverManager(FhPaths.driversRoot).import(display, it) }
                "Driver $name diimpor: pilih di SETTINGS > GRAPHICS > GPU DRIVER lalu mulai ulang"
            } catch (e: Exception) {
                "Driver tidak diimpor: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }, "GpuDriverImport").start()
    }

    // ---- Lifecycle -------------------------------------------------------

    override fun onPause() {
        overlay?.releaseAll()
        FHVirtualPad.releaseAll()
        super.onPause()
    }

    override fun onDestroy() {
        writeSession("destroyed")
        super.onDestroy()
    }

    private fun writeSession(state: String) {
        try {
            FhPaths.lastExitFile(this).writeText(
                JSONObject().put("state", state).put("time_ms", System.currentTimeMillis())
                    .put("pid", android.os.Process.myPid()).toString(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "session marker not written", e)
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(VIBRATOR_SERVICE) as? Vibrator
    }

    companion object {
        private const val TAG = "FHorizonGame"
        private const val REQUEST_DRIVER_ZIP = 0x4648

        private fun parseOrNull(text: String): JSONObject? = try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }

        private fun setEnvironment(name: String, value: String) {
            try {
                Os.setenv(name, value, true)
            } catch (e: ErrnoException) {
                Log.e(TAG, "setenv $name failed", e)
            }
        }

        /** True when /proc/cpuinfo lists every feature (or cannot be read). */
        private fun cpuHasFeatures(vararg features: String): Boolean = try {
            BufferedReader(FileReader("/proc/cpuinfo")).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("Features")) {
                        val listed = line.substringAfter(':').trim().split(Regex("\\s+")).toSet()
                        return@use features.all { it in listed }
                    }
                    line = reader.readLine()
                }
                true
            }
        } catch (e: Exception) {
            true
        }
    }
}
