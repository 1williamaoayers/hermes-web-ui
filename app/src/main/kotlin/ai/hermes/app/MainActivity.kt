package ai.hermes.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var fab: FloatingActionButton
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

        // CI 自测钩子：允许通过 Intent extra 预设 URL (绕过弹窗)
        intent.getStringExtra("prefill_url")?.let {
            if (it.isNotBlank()) {
                prefs.serverUrl = it
                DebugLog.log("Main", "I", "Prefilled URL from intent: $it")
            }
        }

        webView = findViewById(R.id.webview)
        fab = findViewById(R.id.fab_menu)

        fab.setOnClickListener { showFabMenu() }
        fab.setOnLongClickListener {
            openDebug()
            true
        }

        setupWebView()

        if (prefs.isFirstLaunch) {
            promptForUrl()
        } else {
            loadHermes()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
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

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = null
                }
            )
        }

        // 清空 X-Requested-With header
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
            DebugLog.log("Main", "I", "X-Requested-With cleared")
        }

        // 注入 polyfill: 某些 WebView 版本缺少 window.visualViewport，
        // 导致 Naive UI / Vue 组件在调用 .addEventListener 时报 TypeError
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, POLYFILL_JS, setOf("*"))
                DebugLog.log("Main", "I", "Polyfill script registered (document-start)")
            } catch (e: Exception) {
                DebugLog.log("Main", "W", "addDocumentStartJavaScript failed: ${e.message}")
            }
        }

        applyViewMode()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                DebugLog.log("WV", "I", "Page started: $url")
                // Fallback polyfill injection for older WebView that doesn't support DOCUMENT_START_SCRIPT
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(POLYFILL_JS, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                DebugLog.log("WV", "I", "Page finished: $url")
                // Extra safety: re-inject polyfill on SPA route changes
                view?.evaluateJavascript(POLYFILL_JS, null)
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
                DebugLog.log("WV", "E", "Error ${request?.url}: $desc")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                DebugLog.log(
                    "WV", "W",
                    "HTTP ${errorResponse?.statusCode} for ${request?.url}"
                )
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                DebugLog.log("WV", "W", "SSL error for ${error?.url}, proceeding")
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme ?: return false
                return if (scheme == "http" || scheme == "https") {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {
                    }
                    true
                }
            }
        }
    }

    private fun applyViewMode() {
        webView.settings.userAgentString = when (prefs.viewMode) {
            ViewMode.DESKTOP -> UserAgents.DESKTOP
            ViewMode.MOBILE -> null
        }
    }

    private fun loadHermes() {
        val url = prefs.serverUrl
        if (url.isBlank()) {
            promptForUrl()
            return
        }
        DebugLog.log("Main", "I", "Loading: $url (mode=${prefs.viewMode})")
        webView.loadUrl(url)
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
        DebugLog.log("Main", "I", "Session cleared")
    }

    private fun showFabMenu() {
        val items = arrayOf(
            getString(R.string.action_refresh),
            getString(
                if (prefs.viewMode == ViewMode.MOBILE) R.string.switch_to_desktop
                else R.string.switch_to_mobile
            ),
            getString(R.string.action_change_url),
            getString(R.string.action_debug),
            getString(R.string.action_settings)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> webView.reload()
                    1 -> {
                        prefs.viewMode = if (prefs.viewMode == ViewMode.MOBILE) ViewMode.DESKTOP else ViewMode.MOBILE
                        applyViewMode()
                        webView.reload()
                    }
                    2 -> promptForUrl()
                    3 -> openDebug()
                    4 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun openDebug() {
        startActivity(Intent(this, DebugActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        val currentUrl = webView.url
        val targetUrl = prefs.serverUrl
        if (targetUrl.isNotBlank() && currentUrl != null && !currentUrl.startsWith(targetUrl)) {
            loadHermes()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /**
         * Polyfill for WebView APIs missing or incomplete in Android System WebView.
         *
         * Naive UI (Hermes Web UI's component library) calls .addEventListener on
         * several browser APIs that may be undefined or return MediaQueryList without
         * .addEventListener (only the deprecated .addListener) on older WebView builds.
         *
         * This shim provides safe stand-ins for all of them.
         */
        private const val POLYFILL_JS = """
            (function() {
              try {
                // ---- 1. window.visualViewport ----
                if (!window.visualViewport) {
                  var vvListeners = {};
                  var vv = {
                    addEventListener: function(type, fn) {
                      if (!vvListeners[type]) vvListeners[type] = [];
                      vvListeners[type].push(fn);
                      if (type === 'resize' || type === 'scroll') {
                        window.addEventListener(type, fn);
                      }
                    },
                    removeEventListener: function(type, fn) {
                      window.removeEventListener(type, fn);
                    },
                    dispatchEvent: function() { return true; }
                  };
                  Object.defineProperties(vv, {
                    width:  { get: function(){ return window.innerWidth; } },
                    height: { get: function(){ return window.innerHeight; } },
                    scale:  { get: function(){ return 1; } },
                    offsetLeft: { get: function(){ return 0; } },
                    offsetTop:  { get: function(){ return 0; } },
                    pageLeft:   { get: function(){ return window.pageXOffset || 0; } },
                    pageTop:    { get: function(){ return window.pageYOffset || 0; } }
                  });
                  try {
                    Object.defineProperty(window, 'visualViewport', { value: vv, writable: false, configurable: true });
                  } catch(_) { window.visualViewport = vv; }
                }

                // ---- 2. MediaQueryList.addEventListener (some WebView only has .addListener) ----
                try {
                  var mql = window.matchMedia('(min-width: 0px)');
                  if (mql && typeof mql.addEventListener !== 'function') {
                    var origMatchMedia = window.matchMedia.bind(window);
                    window.matchMedia = function(q) {
                      var m = origMatchMedia(q);
                      if (typeof m.addEventListener !== 'function') {
                        m.addEventListener = function(type, fn) {
                          if (type === 'change' && typeof m.addListener === 'function') {
                            m.addListener(fn);
                          }
                        };
                        m.removeEventListener = function(type, fn) {
                          if (type === 'change' && typeof m.removeListener === 'function') {
                            m.removeListener(fn);
                          }
                        };
                        m.dispatchEvent = function() { return true; };
                      }
                      return m;
                    };
                    console.log('[Hermes] matchMedia patched');
                  }
                } catch(e) { console.error('[Hermes] matchMedia patch failed:', e.message); }

                // ---- 3. screen.orientation ----
                if (!screen.orientation) {
                  var orientation = {
                    angle: 0,
                    type: 'portrait-primary',
                    onchange: null,
                    addEventListener: function() {},
                    removeEventListener: function() {},
                    dispatchEvent: function() { return true; },
                    lock: function() { return Promise.reject(new Error('not supported')); },
                    unlock: function() {}
                  };
                  try {
                    Object.defineProperty(screen, 'orientation', { value: orientation, writable: false, configurable: true });
                  } catch(_) { screen.orientation = orientation; }
                  console.log('[Hermes] screen.orientation polyfilled');
                }

                // ---- 4. navigator.connection ----
                if (!navigator.connection) {
                  var connection = {
                    effectiveType: '4g',
                    type: 'wifi',
                    downlink: 10,
                    rtt: 50,
                    saveData: false,
                    addEventListener: function() {},
                    removeEventListener: function() {},
                    dispatchEvent: function() { return true; }
                  };
                  try {
                    Object.defineProperty(navigator, 'connection', { value: connection, writable: false, configurable: true });
                  } catch(_) { navigator.connection = connection; }
                  console.log('[Hermes] navigator.connection polyfilled');
                }

                // ---- 5. document.fonts ----
                if (!document.fonts) {
                  document.fonts = {
                    ready: Promise.resolve(),
                    status: 'loaded',
                    size: 0,
                    addEventListener: function(){},
                    removeEventListener: function(){},
                    dispatchEvent: function(){ return true; },
                    check: function(){ return true; },
                    load: function(){ return Promise.resolve([]); },
                    forEach: function(){},
                    has: function(){ return false; }
                  };
                }

                // ---- 6. Last-resort safety net: wrap addEventListener globally ----
                // If any other API still returns undefined and Naive UI tries to call
                // .addEventListener on it, intercept and swallow the error.
                var origAEL = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = function() {
                  try { return origAEL.apply(this, arguments); }
                  catch(e) { console.warn('[Hermes] addEventListener swallowed:', e.message); }
                };

                console.log('[Hermes] all polyfills applied');

                // ---- 7. Capture FULL stack traces for unhandled errors ----
                window.addEventListener('error', function(ev) {
                  var msg = '[STACK] ' + (ev.message || 'error');
                  if (ev.error && ev.error.stack) msg += '\n' + ev.error.stack;
                  if (ev.filename) msg += '\n  at ' + ev.filename + ':' + ev.lineno + ':' + ev.colno;
                  console.error(msg);
                }, true);

                window.addEventListener('unhandledrejection', function(ev) {
                  var r = ev.reason;
                  var msg = '[REJECT] ' + (r && r.message ? r.message : String(r));
                  if (r && r.stack) msg += '\n' + r.stack;
                  console.error(msg);
                }, true);
              } catch (e) {
                console.error('[Hermes] polyfill error:', (e && e.message) || e);
              }
            })();
        """
    }
}
