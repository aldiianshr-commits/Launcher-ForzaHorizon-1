package id.fhorizon.mobile.module

/**
 * The SDL Java glue in this APK (app/src/main/java/org/libsdl/app, vendored
 * from SDL commit 8bf3b7215ad9fc3deb583c6a3a37c6c67f2e24e4) must match the SDL
 * compiled into the backend module: SDLActivity compares the two at start and
 * refuses to run on a mismatch. SdlCompatTest checks this constant against
 * the vendored SDLActivity.java.
 */
object SdlCompat {
    const val JAVA_SDL_VERSION = "3.5.0"
    const val JAVA_SDL_COMMIT = "8bf3b7215ad9fc3deb583c6a3a37c6c67f2e24e4"

    /** Backend source pins this launcher build was prepared against. */
    data class KnownBackend(val rexglueCommit: String, val pinyonCommit: String, val sdlVersion: String, val note: String)

    val knownBackends = listOf(
        KnownBackend(
            rexglueCommit = "79d3e6fae4b79e00def48a595c80f0eaa47fd2b6",
            pinyonCommit = "9331e453e9850d0fcc1c1606dee95db834a9d317",
            sdlVersion = "3.5.0",
            note = "Pinyon Shift 9331e45 (2026-10-17) with ShiftGlue SDK 79d3e6f",
        ),
    )

    /** Pinyon Shift commit tools/build-module.* check out by default. */
    val pinnedPinyonCommit: String get() = knownBackends.first().pinyonCommit

    fun findKnown(rexglueCommit: String?, pinyonCommit: String?): KnownBackend? =
        knownBackends.firstOrNull {
            (rexglueCommit != null && rexglueCommit.length >= 7 && it.rexglueCommit.startsWith(rexglueCommit)) ||
                (pinyonCommit != null && pinyonCommit.length >= 7 && it.pinyonCommit.startsWith(pinyonCommit))
        }
}
