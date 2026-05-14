package ai.hermes.app

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class HermesApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        // 全局崩溃捕获 — 记录到 DebugLog 以便下次启动查看
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val msg = "FATAL CRASH in ${thread.name}: ${throwable.message}\n${throwable.stackTraceToString()}"
            Log.e("Hermes", msg)
            DebugLog.log("CRASH", "E", msg)
            // 保存到 SharedPreferences 以便下次启动显示
            try {
                getSharedPreferences("crash_log", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", msg)
                    .apply()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        if (sRuntime == null) {
            try {
                val settings = GeckoRuntimeSettings.Builder()
                    .consoleOutput(true)
                    .remoteDebuggingEnabled(true)
                    .aboutConfigEnabled(true)
                    .javaScriptEnabled(true)
                    .build()
                sRuntime = GeckoRuntime.create(this, settings)
                DebugLog.log("App", "I", "GeckoRuntime initialized")
            } catch (e: Exception) {
                val msg = "GeckoRuntime.create FAILED: ${e.message}\n${e.stackTraceToString()}"
                Log.e("Hermes", msg)
                DebugLog.log("App", "E", msg)
                getSharedPreferences("crash_log", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", msg)
                    .apply()
            }
        }
    }

    companion object {
        @JvmStatic
        var sRuntime: GeckoRuntime? = null
            private set
    }
}
