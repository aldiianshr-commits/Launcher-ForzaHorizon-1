package id.fhorizon.mobile.settings

import org.json.JSONObject

/**
 * Graphics settings and how each maps onto the backend.
 *
 * Every option ends up either as a cvar written to pinyon_shift.toml (read by
 * the ReXGlue/ShiftGlue runtime at start) or as an Android-side action of the
 * game activity (surface size, display refresh rate, immersive mode). Options
 * the backend cannot honour are shown but disabled, with the reason
 * ([Support]); they are never written.
 */
enum class Resolution(val label: String, val height: Int) {
    P480("480p", 480), P720("720p", 720), P900("900p", 900), P1080("1080p", 1080);
}

enum class FpsLimit(val label: String, val fps: Int) { F30("30 FPS", 30), F45("45 FPS", 45), F60("60 FPS", 60) }

enum class TextureQuality(val label: String, val maxSize: Int) {
    LOW("Rendah", 256), MEDIUM("Sedang", 1024), HIGH("Tinggi", 0)
}

enum class ShadowQuality(val label: String) { OFF("Mati"), LOW("Rendah"), MEDIUM("Sedang"), HIGH("Tinggi") }

enum class AntiAliasing(val label: String) { OFF("Mati"), FXAA("FXAA"), MSAA_2X("MSAA 2x"), MSAA_4X("MSAA 4x") }

enum class Anisotropic(val label: String, val cvar: Int) {
    GAME("Bawaan game", -1), OFF("Mati", 0), X2("2x", 2), X4("4x", 3), X8("8x", 4), X16("16x", 5)
}

enum class AspectRatio(val label: String) { AUTO("Otomatis"), R16_9("16:9"), R16_10("16:10") }

enum class Upscaler(val label: String, val cvar: String) {
    BILINEAR("Bilinear", "bilinear"), CAS("AMD CAS (tajam)", "cas"), FSR1("AMD FSR 1", "fsr")
}

enum class Preset(val label: String) { VERY_LOW("Very Low"), LOW("Low"), MEDIUM("Medium"), HIGH("High"), ULTRA("Ultra"), CUSTOM("Kustom") }

/** Which choices of a setting the backend honours, and why others are disabled. */
data class Support(val supported: Boolean, val reason: String? = null)

data class GraphicsSettings(
    val preset: Preset = Preset.MEDIUM,
    val resolution: Resolution = Resolution.P720,
    val fps: FpsLimit = FpsLimit.F30,
    val textures: TextureQuality = TextureQuality.MEDIUM,
    val shadows: ShadowQuality = ShadowQuality.OFF,
    val antiAliasing: AntiAliasing = AntiAliasing.OFF,
    val anisotropic: Anisotropic = Anisotropic.GAME,
    val vsync: Boolean = true,
    val shaderCache: Boolean = true,
    /** "" = system driver, otherwise a folder name under Save/drivers. */
    val gpuDriver: String = "",
    val aspect: AspectRatio = AspectRatio.AUTO,
    val fullscreen: Boolean = true,
    val upscaler: Upscaler = Upscaler.BILINEAR,
    // Android tuning (all real backend cvars, see toCvars()).
    val reflectionsQuarterRate: Boolean = true,
    val singlePassScene: Boolean = true,
    val coarseShading: Boolean = false,
    val fastTextureGamma: Boolean = false,
    val gpuTurbo: Boolean = false,
    val bindDecoderToPrimeCore: Boolean = false,
    val lowMemoryTextureCache: Boolean = false,
    val sustainedPerformance: Boolean = true,
) {
    /**
     * cvar name -> TOML literal. Only settings the backend implements appear.
     * Sources: ShiftGlue SDK and Pinyon Shift cvars (see docs/COMPATIBILITY.md).
     */
    fun toCvars(): Map<String, String> {
        val c = linkedMapOf<String, String>()
        c["gpu_backend"] = "\"vulkan\""
        c["gpu_record_thread"] = "true"
        // Android renders the guest at the console's own 1280x720 (scale 1x):
        // larger scales need resolve buffers a phone's memory cannot hold.
        c["draw_resolution_scale_x"] = "1"
        c["draw_resolution_scale_y"] = "1"
        // Frame pacing: the game's own render limit, presentation unlimited.
        c["pinyon_shift_fh1_render_fps_limit"] = fps.fps.toString()
        c["host_present_fps_limit"] = "0"
        c["vsync"] = vsync.toString()
        c["vulkan_texture_max_size"] = textures.maxSize.toString()
        c["pinyon_shift_fh1_shadows"] = (shadows != ShadowQuality.OFF).toString()
        when (antiAliasing) {
            AntiAliasing.OFF -> {
                c["fh1_msaa_single_sample"] = "true"; c["fh1_msaa_2x"] = "false"; c["swap_post_effect"] = "\"none\""
            }
            AntiAliasing.FXAA -> {
                c["fh1_msaa_single_sample"] = "true"; c["fh1_msaa_2x"] = "false"; c["swap_post_effect"] = "\"fxaa\""
            }
            AntiAliasing.MSAA_2X -> {
                c["fh1_msaa_single_sample"] = "false"; c["fh1_msaa_2x"] = "true"; c["swap_post_effect"] = "\"none\""
            }
            AntiAliasing.MSAA_4X -> {
                c["fh1_msaa_single_sample"] = "false"; c["fh1_msaa_2x"] = "false"; c["swap_post_effect"] = "\"none\""
            }
        }
        c["anisotropic_override"] = anisotropic.cvar.toString()
        c["store_shaders"] = shaderCache.toString()
        c["vulkan_pipeline_cache_persist"] = shaderCache.toString()
        c["android_gpu_driver"] = HostConfig.quote(gpuDriver)
        when (aspect) {
            // Fill the screen without distortion by cutting into the title's
            // overscan margin (the in-game CROP).
            AspectRatio.AUTO -> {
                c["present_letterbox"] = "true"; c["present_allow_overscan_cutoff"] = "true"; c["pinyon_shift_hor_plus"] = "false"
            }
            // The game's 16:9 image with bars on a 16:10 panel (LETTERBOX).
            AspectRatio.R16_9 -> {
                c["present_letterbox"] = "true"; c["present_allow_overscan_cutoff"] = "false"; c["pinyon_shift_hor_plus"] = "false"
            }
            // Hor+: the title renders the panel's wider view (WIDER VIEW).
            AspectRatio.R16_10 -> {
                c["present_letterbox"] = "false"; c["present_allow_overscan_cutoff"] = "false"; c["pinyon_shift_hor_plus"] = "true"
            }
        }
        c["present_effect"] = "\"${upscaler.cvar}\""
        c["pinyon_shift_fh1_env_map_rate"] = if (reflectionsQuarterRate) "0.25" else "1"
        c["fh1_untile_predicated_tiling"] = singlePassScene.toString()
        if (coarseShading) {
            c["fh1_coarse_shading"] = "\"$COARSE_SHADING_PATTERN\""
            c["fh1_coarse_shading_alpha_test"] = "true"
        } else {
            c["fh1_coarse_shading"] = "\"\""
            c["fh1_coarse_shading_alpha_test"] = "false"
        }
        c["texture_gamma_host_srgb"] = fastTextureGamma.toString()
        c["android_gpu_turbo"] = gpuTurbo.toString()
        // Snapdragon 870: cpu7 is the 3.2 GHz prime core, alone in its cluster,
        // so the backend's "auto" leaves pinning to the scheduler there.
        c["android_thread_cores"] = if (bindDecoderToPrimeCore) "\"GPU_Commands=7;*=0-6\"" else "\"auto\""
        if (lowMemoryTextureCache) {
            c["texture_cache_memory_limit_soft"] = "256"
            c["texture_cache_memory_limit_hard"] = "768"
        } else {
            c["texture_cache_memory_limit_soft"] = "384"
            c["texture_cache_memory_limit_hard"] = "1024"
        }
        return c
    }

    /** Output (swapchain) height and width for a display of [displayWidth] x [displayHeight]. */
    fun surfaceSize(displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        val longSide = maxOf(displayWidth, displayHeight)
        val shortSide = minOf(displayWidth, displayHeight).coerceAtLeast(1)
        val height = minOf(resolution.height, shortSide)
        val width = ((height.toLong() * longSide) / shortSide).toInt() and 1.inv()
        return width to height
    }

    fun toJson(): JSONObject = JSONObject()
        .put("preset", preset.name).put("resolution", resolution.name).put("fps", fps.name)
        .put("textures", textures.name).put("shadows", shadows.name).put("aa", antiAliasing.name)
        .put("aniso", anisotropic.name).put("vsync", vsync).put("shader_cache", shaderCache)
        .put("gpu_driver", gpuDriver).put("aspect", aspect.name).put("fullscreen", fullscreen)
        .put("upscaler", upscaler.name).put("reflections_quarter", reflectionsQuarterRate)
        .put("single_pass", singlePassScene).put("coarse_shading", coarseShading)
        .put("fast_gamma", fastTextureGamma).put("gpu_turbo", gpuTurbo)
        .put("prime_core_decoder", bindDecoderToPrimeCore).put("low_memory_textures", lowMemoryTextureCache)
        .put("sustained", sustainedPerformance)

    companion object {
        /** Pinyon Shift's COARSE scene shading pattern (settings_menu.cpp). */
        const val COARSE_SHADING_PATTERN = "1024/32/4x/d1+0/32/4x/c3=2x2,0/16/1x/d1+720/16/1x/c0=2x2"

        fun fromJson(json: JSONObject?): GraphicsSettings {
            if (json == null) return GraphicsSettings()
            val d = GraphicsSettings()
            fun <T : Enum<T>> e(values: Array<T>, key: String, default: T): T =
                values.firstOrNull { it.name == json.optString(key) } ?: default
            return GraphicsSettings(
                preset = e(Preset.values(), "preset", d.preset),
                resolution = e(Resolution.values(), "resolution", d.resolution),
                fps = e(FpsLimit.values(), "fps", d.fps),
                textures = e(TextureQuality.values(), "textures", d.textures),
                shadows = e(ShadowQuality.values(), "shadows", d.shadows),
                antiAliasing = e(AntiAliasing.values(), "aa", d.antiAliasing),
                anisotropic = e(Anisotropic.values(), "aniso", d.anisotropic),
                vsync = json.optBoolean("vsync", d.vsync),
                shaderCache = json.optBoolean("shader_cache", d.shaderCache),
                gpuDriver = json.optString("gpu_driver", d.gpuDriver),
                aspect = e(AspectRatio.values(), "aspect", d.aspect),
                fullscreen = json.optBoolean("fullscreen", d.fullscreen),
                upscaler = e(Upscaler.values(), "upscaler", d.upscaler),
                reflectionsQuarterRate = json.optBoolean("reflections_quarter", d.reflectionsQuarterRate),
                singlePassScene = json.optBoolean("single_pass", d.singlePassScene),
                coarseShading = json.optBoolean("coarse_shading", d.coarseShading),
                fastTextureGamma = json.optBoolean("fast_gamma", d.fastTextureGamma),
                gpuTurbo = json.optBoolean("gpu_turbo", d.gpuTurbo),
                bindDecoderToPrimeCore = json.optBoolean("prime_core_decoder", d.bindDecoderToPrimeCore),
                lowMemoryTextureCache = json.optBoolean("low_memory_textures", d.lowMemoryTextureCache),
                sustainedPerformance = json.optBoolean("sustained", d.sustainedPerformance),
            )
        }

        /**
         * Presets for Snapdragon 870 / Adreno 650 class hardware. They follow
         * Pinyon Shift's Android presets (ULTRA LOW 30, QUALITY 30, SMOOTH 60),
         * which were measured on Adreno 740/830; nothing here was measured on
         * Adreno 650 yet.
         */
        fun preset(preset: Preset, base: GraphicsSettings = GraphicsSettings()): GraphicsSettings = when (preset) {
            Preset.VERY_LOW -> base.copy(
                preset = preset, resolution = Resolution.P480, fps = FpsLimit.F30, textures = TextureQuality.LOW,
                shadows = ShadowQuality.OFF, antiAliasing = AntiAliasing.OFF, anisotropic = Anisotropic.GAME,
                upscaler = Upscaler.BILINEAR, reflectionsQuarterRate = true, singlePassScene = true,
                coarseShading = true, fastTextureGamma = true, lowMemoryTextureCache = true,
            )
            Preset.LOW -> base.copy(
                preset = preset, resolution = Resolution.P720, fps = FpsLimit.F30, textures = TextureQuality.LOW,
                shadows = ShadowQuality.OFF, antiAliasing = AntiAliasing.OFF, anisotropic = Anisotropic.GAME,
                upscaler = Upscaler.BILINEAR, reflectionsQuarterRate = true, singlePassScene = true,
                coarseShading = true, fastTextureGamma = true, lowMemoryTextureCache = true,
            )
            Preset.MEDIUM -> base.copy(
                preset = preset, resolution = Resolution.P720, fps = FpsLimit.F30, textures = TextureQuality.MEDIUM,
                shadows = ShadowQuality.OFF, antiAliasing = AntiAliasing.FXAA, anisotropic = Anisotropic.GAME,
                upscaler = Upscaler.BILINEAR, reflectionsQuarterRate = true, singlePassScene = true,
                coarseShading = false, fastTextureGamma = false, lowMemoryTextureCache = false,
            )
            Preset.HIGH -> base.copy(
                preset = preset, resolution = Resolution.P900, fps = FpsLimit.F30, textures = TextureQuality.HIGH,
                shadows = ShadowQuality.HIGH, antiAliasing = AntiAliasing.MSAA_2X, anisotropic = Anisotropic.X4,
                upscaler = Upscaler.FSR1, reflectionsQuarterRate = true, singlePassScene = true,
                coarseShading = false, fastTextureGamma = false, lowMemoryTextureCache = false,
            )
            Preset.ULTRA -> base.copy(
                preset = preset, resolution = Resolution.P1080, fps = FpsLimit.F60, textures = TextureQuality.HIGH,
                shadows = ShadowQuality.HIGH, antiAliasing = AntiAliasing.MSAA_4X, anisotropic = Anisotropic.X16,
                upscaler = Upscaler.FSR1, reflectionsQuarterRate = false, singlePassScene = true,
                coarseShading = false, fastTextureGamma = false, lowMemoryTextureCache = false,
            )
            Preset.CUSTOM -> base.copy(preset = Preset.CUSTOM)
        }

        /** Defaults for the device: Low when RAM is 8 GB or less, Medium otherwise. */
        fun defaultsFor(totalRamBytes: Long): GraphicsSettings {
            val gb = totalRamBytes / (1024.0 * 1024 * 1024)
            return if (gb in 0.1..8.5) preset(Preset.LOW) else preset(Preset.MEDIUM)
        }

        fun shadowSupport(value: ShadowQuality): Support = when (value) {
            ShadowQuality.OFF, ShadowQuality.HIGH -> Support(true)
            else -> Support(false, "Backend hanya punya bayangan Mati/Aktif (pinyon_shift_fh1_shadows); tidak ada tingkat kualitas")
        }

        fun aaSupport(value: AntiAliasing, msaaSampleMask: Int?): Support = when (value) {
            AntiAliasing.MSAA_2X -> if (msaaSampleMask == null || (msaaSampleMask and 2) != 0) Support(true)
            else Support(false, "GPU tidak melaporkan MSAA 2x")
            AntiAliasing.MSAA_4X -> if (msaaSampleMask == null || (msaaSampleMask and 4) != 0) Support(true)
            else Support(false, "GPU tidak melaporkan MSAA 4x")
            else -> Support(true)
        }
    }
}
