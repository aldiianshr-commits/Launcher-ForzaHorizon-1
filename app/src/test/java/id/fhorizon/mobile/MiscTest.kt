package id.fhorizon.mobile

import id.fhorizon.mobile.controls.GamepadMapping
import id.fhorizon.mobile.controls.TouchLayout
import id.fhorizon.mobile.game.GameLaunchPlan
import id.fhorizon.mobile.game.XexSummary
import id.fhorizon.mobile.module.SdlCompat
import id.fhorizon.mobile.save.SaveManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MiscTest {
    @Test
    fun vendoredSdlJavaMatchesConstant() {
        val source = listOf(
            File("src/main/java/org/libsdl/app/SDLActivity.java"),
            File("app/src/main/java/org/libsdl/app/SDLActivity.java"),
        ).first { it.isFile }.readText()
        fun constant(name: String) = Regex("$name\\s*=\\s*(\\d+);").find(source)!!.groupValues[1]
        val version = "${constant("SDL_MAJOR_VERSION")}.${constant("SDL_MINOR_VERSION")}.${constant("SDL_MICRO_VERSION")}"
        assertEquals(SdlCompat.JAVA_SDL_VERSION, version)
        assertNotNull(SdlCompat.findKnown("79d3e6fa", null))
        assertEquals(null, SdlCompat.findKnown("deadbeef", "cafebabe"))
    }

    @Test
    fun gamepadShaping() {
        val m = GamepadMapping(stickDeadZone = 0.2f, stickSensitivity = 1f, triggerDeadZone = 0.1f)
        assertEquals(0f, m.shapeStick(0.15f), 0.0001f)
        assertEquals(1f, m.shapeStick(1f), 0.0001f)
        assertEquals(-0.5f, m.shapeStick(-0.6f), 0.0001f)
        assertEquals(0f, m.shapeTrigger(0.05f), 0.0001f)
        assertEquals(1f, m.shapeTrigger(1f), 0.0001f)
        val remap = GamepadMapping(remap = mapOf(96 to 97))
        assertEquals(97, remap.mapKey(96))
        assertEquals(99, remap.mapKey(99))
        assertEquals(remap.copy(), GamepadMapping.fromJson(remap.toJson()))
    }

    @Test
    fun touchLayoutRoundTripKeepsOnlyKnownControls() {
        val layout = TouchLayout.defaults().update("a") { it.copy(x = 0.5f, size = 0.2f, visible = false) }
            .copy(globalOpacity = 0.4f, autoHideSeconds = 7)
        val back = TouchLayout.fromJson(layout.toJson())
        assertEquals(layout, back)
        assertEquals(TouchLayout.defaults().controls.size, back.controls.size)
    }

    @Test
    fun saveZipRoundTripAndRejectsTraversal() {
        val dir = Files.createTempDirectory("fhsave").toFile()
        val profile = File(dir, "Save/user").apply { mkdirs() }
        File(profile, "ForzaProfile").apply { mkdirs() }
        File(profile, "ForzaProfile/ForzaProfile").writeText("profile-1")
        val manager = SaveManager(profile, File(dir, "Backups"))
        val backup = manager.backup()!!
        File(profile, "ForzaProfile/ForzaProfile").writeText("profile-2")
        val restored = manager.restore(backup.file)
        assertTrue(restored.isSuccess)
        assertEquals("profile-1", File(profile, "ForzaProfile/ForzaProfile").readText())
        // The overwritten profile was kept as a before-restore backup.
        assertTrue(manager.list().any { it.label.startsWith("before-restore") })

        val evil = ByteArrayOutputStream()
        ZipOutputStream(evil).use { zip ->
            zip.putNextEntry(ZipEntry("../../escape.txt")); zip.write(1); zip.closeEntry()
        }
        val target = File(dir, "x").apply { mkdirs() }
        val error = runCatching { SaveManager.unzip(ByteArrayInputStream(evil.toByteArray()), target) }
        assertTrue(error.isFailure)
        assertFalse(File(dir, "escape.txt").exists())
        dir.deleteRecursively()
    }

    @Test
    fun launchPlanRoundTrip() {
        val dir = Files.createTempDirectory("fhplan").toFile()
        val plan = GameLaunchPlan(
            moduleDir = "/data/m", loadOrder = listOf("libc++_shared.so", "librexruntime.so", "libmain.so"),
            entryLibrary = "libmain.so", environment = mapOf("PINYON_SHIFT_STATE_ROOT" to "/s"),
            arguments = listOf("--gpu_backend=null"), surfaceWidth = 1152, surfaceHeight = 720,
            frameRateHint = 60f, fullscreen = true, sustainedPerformance = false, touchControls = true,
            touchLayoutJson = "{}", gamepadJson = "{}", logFile = "/l",
        )
        val file = File(dir, "plan.json")
        plan.write(file)
        assertEquals(plan, GameLaunchPlan.read(file))
        dir.deleteRecursively()
    }

    @Test
    fun xexSummaryParsesInspectorJson() {
        val json = """{"ok":true,"module_flags":"00000001","is_title":true,"is_patch":false,"header_size":4096,
            "execution":{"title_id":"4D5309C9","media_id":"1234ABCD","version":"0.0.0.1","base_version":"0.0.0.1",
            "disc_number":1,"disc_count":1,"platform":0,"savegame_id":"00000000"},"entry_point":"82000000",
            "imports":[{"name":"xboxkrnl.exe","version":"2.0.1.0","min_version":"2.0.1.0","records":10},
            {"name":"xam.xex","version":"2.0.1.0","min_version":"2.0.1.0","records":4}],"static_libraries":[],
            "security":{"image_size":100,"load_address":"82000000","regions_text":"NTSC-U"},"warnings":[]}"""
        val x = XexSummary.parse(json)
        assertTrue(x.ok)
        assertEquals(XexSummary.FORZA_HORIZON_TITLE_ID, x.titleId)
        assertEquals(10, x.kernelImports)
        assertEquals(4, x.xamImports)
        assertFalse(XexSummary.parse("""{"ok":false,"error":"bad"}""").ok)
    }
}
