package ai.hermes.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var errorView: View
    private lateinit var toolbar: Toolbar
    private lateinit var chromeClient: HermesWebChromeClient

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = chromeClient.filePathCallback ?: return@registerForActivityResult
            val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                when {
                    data == null -> null
                    data.clipData != null -> {
                        val count = data.clipData!!.itemCount
                        Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                    }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else null
            callback.onReceiveValue(uris)
            chromeClient.filePathCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        errorView = findViewById(R.id.error_view)

        setupWebView()

        swipeRefresh.setOnRefreshListener { webView.reload() }
        findViewById<View>(R.id.btn_retry).setOnClickListener { retryLoad() }

        if (prefs.isFirstLaunch) {
            promptForUrl()
        } else {
            loadHermes()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // WebView 远程调试 (chrome://inspect)
        WebView.setWebContentsDebuggingEnabled(true)

        chromeClient = HermesWebChromeClient(this, fileChooserLauncher)
        webView.webChromeClient = chromeClient

        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.loadsImagesAutomatically = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.textZoom = 100

        // 启用 ServiceWorker（关键：部分 Web 应用依赖它进行流式通信）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = null
                }
            )
        }

        applyViewMode()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished: $url")
                swipeRefresh.isRefreshing = false
                errorView.visibility = View.GONE
                swipeRefresh.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    error?.description?.toString() ?: "unknown"
                } else "unknown"
                Log.e(TAG, "onReceivedError: ${request?.url} - $desc")
                if (request?.isForMainFrame == true) {
                    showError()
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                // 容忍自签证书 (开发/内网环境)
                Log.w(TAG, "SSL error for ${error?.url}, proceeding anyway")
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme ?: return false
                return if (scheme == "http" || scheme == "https") {
                    false // WebView handles it
                } else {
                    // 其他 scheme 交给系统（mailto:, tel:, intent:, etc.）
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {
                    }
                    true
                }
            }
        }
    }

    companion object {
        private const val TAG = "Hermes"
    }

    private fun applyViewMode() {
        val s = webView.settings
        s.userAgentString = when (prefs.viewMode) {
            ViewMode.DESKTOP -> UserAgents.DESKTOP
            ViewMode.MOBILE -> UserAgents.MOBILE.takeIf { it.isNotEmpty() }
        }
        updateToolbarSubtitle()
    }

    private fun updateToolbarSubtitle() {
        supportActionBar?.subtitle = when (prefs.viewMode) {
            ViewMode.MOBILE -> "📱 " + getString(R.string.mode_mobile)
            ViewMode.DESKTOP -> "🖥️ " + getString(R.string.mode_desktop)
        }
    }

    private fun loadHermes() {
        val url = prefs.serverUrl
        if (url.isBlank()) {
            promptForUrl()
            return
        }
        errorView.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    private fun retryLoad() {
        errorView.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
        webView.reload()
    }

    private fun showError() {
        errorView.visibility = View.VISIBLE
        swipeRefresh.visibility = View.GONE
        swipeRefresh.isRefreshing = false
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
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val toggleItem = menu?.findItem(R.id.action_toggle_mode)
        toggleItem?.title = when (prefs.viewMode) {
            ViewMode.MOBILE -> getString(R.string.switch_to_desktop)
            ViewMode.DESKTOP -> getString(R.string.switch_to_mobile)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_mode -> {
                prefs.viewMode = when (prefs.viewMode) {
                    ViewMode.MOBILE -> ViewMode.DESKTOP
                    ViewMode.DESKTOP -> ViewMode.MOBILE
                }
                applyViewMode()
                webView.reload()
                invalidateOptionsMenu()
                true
            }
            R.id.action_refresh -> {
                webView.reload()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从 Settings 返回后如果 URL 变了，重新加载
        val currentUrl = webView.url
        val targetUrl = prefs.serverUrl
        if (targetUrl.isNotBlank() && currentUrl != null && !currentUrl.startsWith(targetUrl)) {
            loadHermes()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
