package ai.hermes.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvUrl: TextView
    private lateinit var btnLaunch: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        tvUrl = findViewById(R.id.tv_current_url)
        btnLaunch = findViewById(R.id.btn_launch)

        btnLaunch.setOnClickListener { launchHermes() }
        findViewById<View>(R.id.btn_change_url).setOnClickListener { promptForUrl() }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (prefs.isFirstLaunch) {
            promptForUrl()
        } else {
            updateUrlDisplay()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUrlDisplay()
    }

    private fun updateUrlDisplay() {
        tvUrl.text = prefs.serverUrl.ifBlank { getString(R.string.not_set) }
    }

    private fun launchHermes() {
        val url = prefs.serverUrl
        if (url.isBlank()) {
            promptForUrl()
            return
        }

        val colorParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(this, R.color.hermes_primary))
            .setNavigationBarColor(ContextCompat.getColor(this, R.color.hermes_primary))
            .build()

        val intent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colorParams)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setInstantAppsEnabled(false)
            .build()

        intent.intent.putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://$packageName"))

        try {
            intent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            // Fallback: 用默认浏览器
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.error_no_browser, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptForUrl() {
        val input = EditText(this).apply {
            hint = "http://192.168.0.134:8648"
            setText(prefs.serverUrl.ifBlank { "http://" })
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_url_title)
            .setMessage(R.string.dialog_url_message)
            .setView(input)
            .setCancelable(!prefs.isFirstLaunch)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text.toString().trim()
                if (isValidUrl(url)) {
                    prefs.serverUrl = url
                    updateUrlDisplay()
                    launchHermes()
                } else {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                    promptForUrl()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isValidUrl(url: String): Boolean {
        return url.matches(Regex("^https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+$"))
    }
}
