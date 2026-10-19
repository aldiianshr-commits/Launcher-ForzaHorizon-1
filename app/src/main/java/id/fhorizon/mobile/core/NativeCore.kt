package id.fhorizon.mobile.core

/**
 * libfhcore.so (native/): XEX2 header inspection, backend module (ELF)
 * validation and the Vulkan capability probe. Every call returns JSON.
 */
object NativeCore {
    @Volatile private var loaded: Boolean? = null
    @Volatile var loadError: String? = null
        private set

    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = try {
                System.loadLibrary("fhcore")
                true
            } catch (e: Throwable) {
                loadError = e.message ?: e.javaClass.simpleName
                AppLog.e("libfhcore.so failed to load", e)
                false
            }
            loaded = ok
            return ok
        }
    }

    fun xex(path: String): String =
        if (ensureLoaded()) inspectXex(path) else """{"ok":false,"error":"native core unavailable"}"""

    fun module(directory: String, entry: String = "libmain.so"): String =
        if (ensureLoaded()) inspectModule(directory, entry)
        else """{"libraries":[],"plan":{"ok":false,"errors":["native core unavailable"]}}"""

    fun vulkan(): String =
        if (ensureLoaded()) probeVulkan() else """{"ok":false,"error":"native core unavailable"}"""

    fun version(): String = if (ensureLoaded()) coreVersion() else "unavailable"

    @JvmStatic private external fun inspectXex(path: String): String
    @JvmStatic private external fun inspectModule(directory: String, entry: String): String
    @JvmStatic private external fun probeVulkan(): String
    @JvmStatic private external fun coreVersion(): String
}
