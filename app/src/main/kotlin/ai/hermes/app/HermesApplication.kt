package ai.hermes.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class HermesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 跟随系统深色模式
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}
