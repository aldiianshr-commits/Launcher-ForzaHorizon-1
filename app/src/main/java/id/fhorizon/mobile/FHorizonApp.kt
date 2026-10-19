package id.fhorizon.mobile

import android.app.Application
import android.os.Build
import id.fhorizon.mobile.core.AppLog
import id.fhorizon.mobile.core.FhPaths
import id.fhorizon.mobile.core.StorageAccess
import java.io.File

class FHorizonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        attachLogFile()
        AppLog.i(
            "FHorizon Mobile ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }

    /** Called again after the player grants storage access. */
    fun attachLogFile() {
        if (StorageAccess.hasAllFilesAccess(this)) {
            FhPaths.ensureSharedLayout()
            AppLog.attachFile(File(FhPaths.launcherLogs, "launcher.log"))
        } else {
            AppLog.attachFile(File(filesDir, "launcher.log"))
        }
    }
}
