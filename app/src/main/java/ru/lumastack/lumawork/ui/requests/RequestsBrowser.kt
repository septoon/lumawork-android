package ru.lumastack.lumawork.ui.requests

import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Auth cookie belongs only to the SimpleOne host; it never appears in a URL or saved UI state. */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun RequestsBrowser(url: String, authKey: String, onDismiss: () -> Unit) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var active by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (view?.canGoBack() == true) view?.goBack() else onDismiss() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                    Text("SimpleOne", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { error = null; view?.reload() }) { Text("Обновить") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setGeolocationEnabled(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.url.scheme != "https" || request.url.host != "sd.servionica.ru"
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { loading = true }
                            override fun onPageFinished(view: WebView, url: String) { loading = false }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                if (request.isForMainFrame) { loading = false; error = "Не удалось открыть SimpleOne. Проверьте подключение к интернету." }
                            }
                        }
                        view = this
                        val target = this
                        CookieManager.getInstance().setCookie("https://sd.servionica.ru", "auth=$authKey; Path=/; Secure; HttpOnly") {
                            if (active) target.loadUrl(url)
                        }
                    }
                }, onRelease = { released ->
                    active = false; view = null; released.stopLoading(); released.destroy()
                    CookieManager.getInstance().setCookie("https://sd.servionica.ru", "auth=; Path=/; Max-Age=0; Secure; HttpOnly")
                })
            }
        }
    }
}
