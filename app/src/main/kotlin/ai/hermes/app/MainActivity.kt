package ai.hermes.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var geckoView: GeckoView
    private lateinit var loadingView: View
    private lateinit var fab: FloatingActionButton

    private var session: GeckoSession? = null
    private var canGoBack: Boolean = false
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileGeckoResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val gr = pendingFileGeckoResult
            val prompt = pendingFilePrompt
            pendingFileGeckoResult = null
            pendingFilePrompt = null
            if (gr == null || prompt == null) return@registerForActivityResult

            val uris: Array<Uri> = if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                when {
                    data == null -> emptyArray()
                    data.clipData != null -> {
                        val cd = data.clipData!!
                        Array(cd.itemCount) { i -> cd.getItemAt(i).uri }
                    }
                    data.data != null -> arrayOf(data.data!!)
                    else -> emptyArray()
                }
            } else emptyArray()

            if (uris.isEmpty()) {
                gr.complete(prompt.dismiss())
            } else {
                gr.complete(prompt.confirm(this, uris))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)

        intent.getStringExtra("prefill_url")?.let {
            if (it.isNotBlank()) {
                prefs.serverUrl = it
                DebugLog.log("Main", "I", "Prefilled URL from intent: $it")
            }
        }

        geckoView = findViewById(R.id.gecko_view)
        loadingView = findViewById(R.id.loading_view)
        fab = findViewById(R.id.fab_menu)
        fab.setOnClickListener { showFabMenu() }
        fab.setOnLongClickListener { openDebug(); true }

        setupSession()

        if (prefs.isFirstLaunch) {
            promptForUrl()
        } else {
            loadHermes()
        }
    }

    private fun setupSession() {
        val runtime: GeckoRuntime = HermesApplication.sRuntime
            ?: run {
                DebugLog.log("Main", "E", "GeckoRuntime not initialized!")
                Toast.makeText(this, "Engine not ready", Toast.LENGTH_LONG).show()
                finish()
                return
            }

        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .allowJavascript(true)
            .build()

        val s = GeckoSession(settings)
        s.open(runtime)
        geckoView.setSession(s)

        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                DebugLog.log("Gecko", "I", "Location: $url")
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                DebugLog.log("Gecko", "E", "Load error: $uri code=${error.code}")
                return null
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val u = request.uri
                val scheme = Uri.parse(u).scheme
                if (scheme != null && scheme != "http" && scheme != "https" && scheme != "about" && scheme != "blob" && scheme != "data") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    } catch (_: Exception) {
                    }
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }

        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                DebugLog.log("Gecko", "I", "Page start: $url")
                loadingView.visibility = View.VISIBLE
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                DebugLog.log("Gecko", "I", "Page stop success=$success")
                loadingView.visibility = View.GONE
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (progress >= 100) loadingView.visibility = View.GONE
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
            ) {}
        }

        s.contentDelegate = object : GeckoSession.ContentDelegate {}

        s.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val gr = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingFileGeckoResult = gr
                pendingFilePrompt = prompt
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    DebugLog.log("Gecko", "E", "File chooser launch failed: ${e.message}")
                    gr.complete(prompt.dismiss())
                    pendingFilePrompt = null
                    pendingFileGeckoResult = null
                }
                return gr
            }
        }

        s.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                callback.grant(video?.firstOrNull(), audio?.firstOrNull())
            }
        }

        session = s
    }

    private fun loadHermes() {
        val url = prefs.serverUrl
        if (url.isBlank()) {
            promptForUrl()
            return
        }
        DebugLog.log("Main", "I", "Loading: $url")
        loadingView.visibility = View.VISIBLE
        session?.loadUri(url)
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
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text.toString().trim()
                if (isValidUrl(url)) {
                    if (prefs.serverUrl != url && prefs.serverUrl.isNotBlank()) {
                        clearSession()
                    }
                    prefs.serverUrl = url
                    loadHermes()
                } else {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                    promptForUrl()
                }
            }
            .show()
    }

    private fun isValidUrl(url: String): Boolean {
        return url.matches(Regex("^https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+$"))
    }

    private fun clearSession() {
        try {
            HermesApplication.sRuntime?.storageController?.clearData(
                StorageController.ClearFlags.ALL
            )
            DebugLog.log("Main", "I", "Storage cleared")
        } catch (e: Exception) {
            DebugLog.log("Main", "W", "Clear storage failed: ${e.message}")
        }
    }

    private fun showFabMenu() {
        val items = arrayOf(
            getString(R.string.action_refresh),
            getString(R.string.action_change_url),
            getString(R.string.action_debug),
            getString(R.string.action_settings)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> session?.reload()
                    1 -> promptForUrl()
                    2 -> openDebug()
                    3 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun openDebug() {
        startActivity(Intent(this, DebugActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (canGoBack) {
            session?.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && canGoBack) {
            session?.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
