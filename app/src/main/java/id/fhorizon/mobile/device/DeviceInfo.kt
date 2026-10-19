package id.fhorizon.mobile.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import id.fhorizon.mobile.core.NativeCore
import id.fhorizon.mobile.core.objects
import id.fhorizon.mobile.core.parseJsonOrNull
import id.fhorizon.mobile.core.strings
import org.json.JSONObject
import java.io.File

data class CpuCluster(val cores: List<Int>, val maxKhz: Long)

data class VulkanDevice(
    val name: String,
    val apiVersion: String,
    val apiVersionRaw: Long,
    val driverVersion: String,
    val driverName: String,
    val driverInfo: String,
    val hardRequirementsMet: Boolean,
    val blocking: List<String>,
    val fallbacks: List<String>,
    val msaaSampleCounts: Int,
    val maxAnisotropy: Double,
    val hasFragmentShadingRate: Boolean,
) {
    val apiAtLeast13: Boolean get() = apiVersionRaw >= ((1L shl 22) or (3L shl 12))
    fun supportsMsaa(samples: Int): Boolean = (msaaSampleCounts and samples) != 0
}

data class VulkanReport(val ok: Boolean, val error: String?, val devices: List<VulkanDevice>, val raw: String) {
    val primary: VulkanDevice? get() = devices.firstOrNull()

    companion object {
        fun parse(json: String): VulkanReport {
            val root = parseJsonOrNull(json) ?: return VulkanReport(false, "unreadable probe output", emptyList(), json)
            if (!root.optBoolean("ok")) return VulkanReport(false, root.optString("error"), emptyList(), json)
            val devices = root.optJSONArray("devices").objects().map { d ->
                VulkanDevice(
                    name = d.optString("name"),
                    apiVersion = d.optString("api_version"),
                    apiVersionRaw = d.optLong("api_version_raw"),
                    driverVersion = d.optString("driver_version"),
                    driverName = d.optString("driver_name"),
                    driverInfo = d.optString("driver_info"),
                    hardRequirementsMet = d.optBoolean("hard_requirements_met"),
                    blocking = d.optJSONArray("blocking").strings(),
                    fallbacks = d.optJSONArray("fallbacks").strings(),
                    msaaSampleCounts = d.optInt("framebuffer_color_sample_counts"),
                    maxAnisotropy = d.optDouble("max_sampler_anisotropy", 0.0),
                    hasFragmentShadingRate = d.optJSONObject("has")?.optBoolean("VK_KHR_fragment_shading_rate") ?: false,
                )
            }
            return VulkanReport(true, null, devices, json)
        }
    }
}

/** What the launcher knows about the device, gathered once per screen visit. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val socModel: String?,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowRamDevice: Boolean,
    val cpuFeatures: Set<String>,
    val clusters: List<CpuCluster>,
    val adrenoModel: String?,
    val kgslModel: String?,
    val thermalStatus: Int,
    val sustainedPerformanceSupported: Boolean,
    val vulkan: VulkanReport,
) {
    val isArm64: Boolean get() = abis.contains("arm64-v8a")
    /** ARMv8.2 features the module is compiled for (Pinyon Shift's cpu_baseline). */
    val hasArmv82: Boolean get() = cpuFeatures.isEmpty() || (cpuFeatures.contains("atomics") && cpuFeatures.contains("asimdrdm"))
    val isSnapdragon870: Boolean get() = socModel.equals("SM8250", true) && (adrenoModel == "650")
    val totalRamGb: Double get() = totalRamBytes / (1024.0 * 1024 * 1024)

    fun summaryJson(): JSONObject = JSONObject()
        .put("manufacturer", manufacturer).put("model", model).put("soc", socModel ?: "")
        .put("android", androidRelease).put("sdk", sdkInt).put("ram_bytes", totalRamBytes)
        .put("cpu_features_armv82", hasArmv82).put("adreno", adrenoModel ?: "")
        .put("kgsl", kgslModel ?: "").put("thermal_status", thermalStatus)
        .put("clusters", clusters.joinToString(";") { "${it.cores.joinToString(",")}@${it.maxKhz / 1000}MHz" })

    companion object {
        fun collect(context: Context, probeVulkan: Boolean = true): DeviceInfo {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val kgsl = readLine("/sys/class/kgsl/kgsl-3d0/gpu_model")
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
            val vulkan = if (probeVulkan) VulkanReport.parse(NativeCore.vulkan())
            else VulkanReport(false, "not probed", emptyList(), "{}")
            val adreno = adrenoModel(kgsl, soc, vulkan.primary?.name)
            return DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                socModel = soc,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                abis = Build.SUPPORTED_ABIS.toList(),
                totalRamBytes = memory.totalMem,
                availRamBytes = memory.availMem,
                lowRamDevice = am.isLowRamDevice,
                cpuFeatures = cpuFeatures(),
                clusters = clusters(),
                adrenoModel = adreno,
                kgslModel = kgsl,
                thermalStatus = pm.currentThermalStatus,
                sustainedPerformanceSupported = pm.isSustainedPerformanceModeSupported,
                vulkan = vulkan,
            )
        }

        fun cpuFeatures(): Set<String> = try {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }
                    ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet() ?: emptySet()
            }
        } catch (e: Exception) {
            emptySet()
        }

        /** Cores grouped by their maximum frequency (Snapdragon 870: 0-3, 4-6, 7). */
        fun clusters(): List<CpuCluster> {
            val byFreq = sortedMapOf<Long, MutableList<Int>>()
            val count = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            for (cpu in 0 until maxOf(count, 8)) {
                val freq = readLine("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")?.toLongOrNull() ?: continue
                byFreq.getOrPut(freq) { mutableListOf() }.add(cpu)
            }
            return byFreq.map { (freq, cores) -> CpuCluster(cores, freq) }
        }

        private fun adrenoModel(kgsl: String?, soc: String?, vulkanName: String?): String? {
            val regex = Regex("Adreno\\D*(\\d{3})")
            kgsl?.let { regex.find(it)?.groupValues?.get(1)?.let { m -> return m } }
            vulkanName?.let { regex.find(it)?.groupValues?.get(1)?.let { m -> return m } }
            return when (soc?.uppercase()) {
                "SM8250", "SM8250-AC" -> "650"
                "SM8350" -> "660"
                "SM8450", "SM8475" -> "730"
                "SM8550" -> "740"
                "SM8650" -> "750"
                "SM8750" -> "830"
                else -> null
            }
        }

        private fun readLine(path: String): String? = try {
            File(path).bufferedReader().use { it.readLine() }?.trim()
        } catch (e: Exception) {
            null
        }
    }
}
