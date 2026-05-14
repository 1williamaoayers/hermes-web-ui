package ai.hermes.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.mozilla.geckoview.StorageController

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var prefs: Prefs

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            prefs = Prefs(requireContext())

            findPreference<Preference>("server_url")?.apply {
                summary = prefs.serverUrl.ifBlank { getString(R.string.not_set) }
                setOnPreferenceClickListener {
                    showUrlDialog()
                    true
                }
            }

            findPreference<Preference>("test_connection")?.setOnPreferenceClickListener {
                testConnection()
                true
            }

            findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
                clearCache()
                true
            }

            findPreference<Preference>("about")?.apply {
                summary = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            }

            findPreference<Preference>("github")?.setOnPreferenceClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/1williamaoayers/hermes-web-ui"))
                )
                true
            }

            findPreference<Preference>("debug")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DebugActivity::class.java))
                true
            }
        }

        private fun showUrlDialog() {
            val input = EditText(requireContext()).apply {
                setText(prefs.serverUrl)
                setSelection(text.length)
                hint = "http://192.168.0.134:8648"
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_url_title)
                .setMessage(R.string.dialog_url_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val url = input.text.toString().trim()
                    if (url.matches(Regex("^https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+$"))) {
                        val changed = prefs.serverUrl != url
                        prefs.serverUrl = url
                        if (changed) {
                            clearStorageSilent()
                            Toast.makeText(requireContext(), R.string.session_cleared, Toast.LENGTH_SHORT).show()
                        }
                        findPreference<Preference>("server_url")?.summary = url
                    } else {
                        Toast.makeText(requireContext(), R.string.invalid_url, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun testConnection() {
            val url = prefs.serverUrl
            if (url.isBlank()) {
                Toast.makeText(requireContext(), R.string.not_set, Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(requireContext(), R.string.testing, Toast.LENGTH_SHORT).show()
            thread {
                val result = try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    "HTTP $code"
                } catch (e: Exception) {
                    "ERROR: ${e.message}"
                }
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show()
                }
            }
        }

        private fun clearCache() {
            clearStorageSilent()
            Toast.makeText(requireContext(), R.string.cache_cleared, Toast.LENGTH_SHORT).show()
        }

        private fun clearStorageSilent() {
            try {
                HermesApplication.sRuntime?.storageController?.clearData(
                    StorageController.ClearFlags.ALL
                )
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
