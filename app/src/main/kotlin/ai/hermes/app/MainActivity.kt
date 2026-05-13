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
import androidx.webkit.WebViewCompat
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

        // 在页面加载前注入 JS，强制 Socket.IO 使用 polling transport
        // 这是关键修复：WebSocket upgrade 在 HTTP 下的 WebView 经常失效
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                FORCE_POLLING_JS,
                setOf("*")
            )
            Log.d(TAG, "Document-start JS injected")
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT not supported, falling back to onPageFinished")
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
                // Fallback injection for devices without DOCUMENT_START_SCRIPT support
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(FORCE_POLLING_JS) { result ->
                        Log.d(TAG, "Fallback polling patch: $result")
                    }
                }
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

        /**
         * 核心修复：禁用 WebSocket + 劫持 Socket.IO 以强制 polling transport。
         *
         * Android WebView 在明文 HTTP 下 WebSocket upgrade 经常失败（静默断开）。
         * 两道保险：
         *   (1) 置 window.WebSocket = undefined → Socket.IO 检测不到 WebSocket 自动用 polling
         *   (2) Hook window.io (如果存在) → 显式指定 transports: ['polling']
         */
        private const val FORCE_POLLING_JS = """
            (function() {
              try {
                // (1) 禁用 WebSocket 全局构造函数
                try {
                  Object.defineProperty(window, 'WebSocket', {
                    value: undefined,
                    writable: true,
                    configurable: true
                  });
                  console.log('[Hermes] WebSocket disabled');
                } catch (e) {
                  window.WebSocket = undefined;
                  console.log('[Hermes] WebSocket nulled (fallback)');
                }

                // (2) 如果 Socket.IO 已暴露 io()，重写以强制 polling
                if (typeof window.io === 'function' && !window.__hermesIoPatched) {
                  window.__hermesIoPatched = true;
                  var originalIO = window.io;
                  window.io = function() {
                    var args = Array.prototype.slice.call(arguments);
                    var opts = {};
                    var optsIdx = -1;
                    for (var i = 0; i < args.length; i++) {
                      if (typeof args[i] === 'object' && args[i] !== null && !Array.isArray(args[i])) {
                        opts = args[i]; optsIdx = i; break;
                      }
                    }
                    opts.transports = ['polling'];
                    opts.upgrade = false;
                    opts.rememberUpgrade = false;
                    if (optsIdx === -1) args.push(opts);
                    else args[optsIdx] = opts;
                    return originalIO.apply(this, args);
                  };
                  for (var k in originalIO) {
                    if (originalIO.hasOwnProperty(k)) window.io[k] = originalIO[k];
                  }
                  console.log('[Hermes] io() patched for polling');
                }
                return 'patched';
              } catch (e) {
                return 'error: ' + e.message;
              }
            })();
        """
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
