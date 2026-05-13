package ai.hermes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog

class HermesWebChromeClient(
    private val context: Context,
    private val fileChooserLauncher: ActivityResultLauncher<Intent>
) : WebChromeClient() {

    var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (consoleMessage == null) return false
        val tag = "HermesJS"
        val msg = "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
        when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.e(tag, msg)
            ConsoleMessage.MessageLevel.WARNING -> Log.w(tag, msg)
            ConsoleMessage.MessageLevel.DEBUG -> Log.d(tag, msg)
            else -> Log.i(tag, msg)
        }
        return true
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        this.filePathCallback?.onReceiveValue(null)
        this.filePathCallback = filePathCallback

        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return try {
            fileChooserLauncher.launch(intent)
            true
        } catch (e: Exception) {
            this.filePathCallback = null
            false
        }
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.grant(request.resources)
    }

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: android.webkit.JsResult?
    ): Boolean {
        AlertDialog.Builder(context)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
            .setOnCancelListener { result?.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: android.webkit.JsResult?
    ): Boolean {
        AlertDialog.Builder(context)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
            .show()
        return true
    }
}
