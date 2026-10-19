package id.fhorizon.mobile

import id.fhorizon.mobile.settings.AntiAliasing
import id.fhorizon.mobile.settings.Anisotropic
import id.fhorizon.mobile.settings.AspectRatio
import id.fhorizon.mobile.settings.FpsLimit
import id.fhorizon.mobile.settings.GraphicsSettings
import id.fhorizon.mobile.settings.Preset
import id.fhorizon.mobile.settings.Resolution
import id.fhorizon.mobile.settings.ShadowQuality
import id.fhorizon.mobile.settings.TextureQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsSettingsTest {
    /** Every cvar the launcher writes exists in ShiftGlue SDK 79d3e6f / Pinyon Shift 9331e45. */
    private val knownCvars = setOf(
        "gpu_backend", "gpu_record_thread", "draw_resolution_scale_x", "draw_resolution_scale_y",
        "pinyon_shift_fh1_render_fps_limit", "host_present_fps_limit", "vsync", "vulkan_texture_max_size",
        "pinyon_shift_fh1_shadows", "fh1_msaa_single_sample", "fh1_msaa_2x", "swap_post_effect",
        "anisotropic_override", "store_shaders", "vulkan_pipeline_cache_persist", "android_gpu_driver",
        "present_letterbox", "present_allow_overscan_cutoff", "pinyon_shift_hor_plus", "present_effect",
        "pinyon_shift_fh1_env_map_rate", "fh1_untile_predicated_tiling", "fh1_coarse_shading",
        "fh1_coarse_shading_alpha_test", "texture_gamma_host_srgb", "android_gpu_turbo",
        "android_thread_cores", "texture_cache_memory_limit_soft", "texture_cache_memory_limit_hard",
    )

    @Test
    fun cvarsAreKnownAndPresetsDiffer() {
        for (preset in Preset.values()) {
            val cvars = GraphicsSettings.preset(preset).toCvars()
            for (name in cvars.keys) assertTrue("unknown cvar $name", name in knownCvars)
        }
        val low = GraphicsSettings.preset(Preset.VERY_LOW).toCvars()
        val ultra = GraphicsSettings.preset(Preset.ULTRA).toCvars()
        assertEquals("256", low["vulkan_texture_max_size"])
        assertEquals("0", ultra["vulkan_texture_max_size"])
        assertEquals("false", low["pinyon_shift_fh1_shadows"])
        assertEquals("true", ultra["pinyon_shift_fh1_shadows"])
        assertEquals("60", ultra["pinyon_shift_fh1_render_fps_limit"])
        assertEquals("5", ultra["anisotropic_override"])
    }

    @Test
    fun antiAliasingMapping() {
        val off = GraphicsSettings(antiAliasing = AntiAliasing.OFF).toCvars()
        assertEquals("true", off["fh1_msaa_single_sample"])
        val fxaa = GraphicsSettings(antiAliasing = AntiAliasing.FXAA).toCvars()
        assertEquals("\"fxaa\"", fxaa["swap_post_effect"])
        val msaa2 = GraphicsSettings(antiAliasing = AntiAliasing.MSAA_2X).toCvars()
        assertEquals("false", msaa2["fh1_msaa_single_sample"])
        assertEquals("true", msaa2["fh1_msaa_2x"])
        val msaa4 = GraphicsSettings(antiAliasing = AntiAliasing.MSAA_4X).toCvars()
        assertEquals("false", msaa4["fh1_msaa_2x"])
    }

    @Test
    fun aspectAndDriver() {
        val wide = GraphicsSettings(aspect = AspectRatio.R16_10, gpuDriver = "turnip-a6xx").toCvars()
        assertEquals("true", wide["pinyon_shift_hor_plus"])
        assertEquals("false", wide["present_letterbox"])
        assertEquals("\"turnip-a6xx\"", wide["android_gpu_driver"])
        assertEquals("\"\"", GraphicsSettings().toCvars()["android_gpu_driver"])
    }

    @Test
    fun surfaceFollowsPanelAspect() {
        // Xiaomi Pad 6: 2880 x 1800 (16:10).
        assertEquals(1152 to 720, GraphicsSettings(resolution = Resolution.P720).surfaceSize(2880, 1800))
        assertEquals(1728 to 1080, GraphicsSettings(resolution = Resolution.P1080).surfaceSize(1800, 2880))
        assertEquals(768 to 480, GraphicsSettings(resolution = Resolution.P480).surfaceSize(2880, 1800))
    }

    @Test
    fun unsupportedChoicesAreFlagged() {
        assertTrue(GraphicsSettings.shadowSupport(ShadowQuality.OFF).supported)
        assertFalse(GraphicsSettings.shadowSupport(ShadowQuality.LOW).supported)
        assertFalse(GraphicsSettings.aaSupport(AntiAliasing.MSAA_4X, 0x1 or 0x2).supported)
        assertTrue(GraphicsSettings.aaSupport(AntiAliasing.MSAA_2X, 0x1 or 0x2).supported)
    }

    @Test
    fun jsonRoundTrip() {
        val s = GraphicsSettings(
            preset = Preset.CUSTOM, resolution = Resolution.P900, fps = FpsLimit.F45, textures = TextureQuality.LOW,
            anisotropic = Anisotropic.X8, gpuDriver = "x", coarseShading = true,
        )
        assertEquals(s, GraphicsSettings.fromJson(s.toJson()))
    }
}
