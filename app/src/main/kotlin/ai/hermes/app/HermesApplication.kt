package ai.hermes.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class HermesApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        if (sRuntime == null) {
            val settings = GeckoRuntimeSettings.Builder()
                .consoleOutput(true)               // JS console → logcat (tag GeckoConsole)
                .remoteDebuggingEnabled(true)      // chrome://inspect 远程调试
                .aboutConfigEnabled(true)
                .javaScriptEnabled(true)
                .webManifestEnabled(true)
                .build()
            sRuntime = GeckoRuntime.create(this, settings)
            DebugLog.log("App", "I", "GeckoRuntime initialized")
        }
    }

    companion object {
        @JvmStatic
        var sRuntime: GeckoRuntime? = null
            private set
    }
}
