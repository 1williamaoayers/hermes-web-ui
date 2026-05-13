package ai.hermes.app

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_SERVER_URL, value).apply()

    var viewMode: ViewMode
        get() = ViewMode.valueOf(sp.getString(KEY_VIEW_MODE, ViewMode.MOBILE.name) ?: ViewMode.MOBILE.name)
        set(value) = sp.edit().putString(KEY_VIEW_MODE, value.name).apply()

    val isFirstLaunch: Boolean
        get() = serverUrl.isBlank()

    fun clear() {
        sp.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "hermes_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_VIEW_MODE = "view_mode"
    }
}

enum class ViewMode {
    MOBILE, DESKTOP
}
