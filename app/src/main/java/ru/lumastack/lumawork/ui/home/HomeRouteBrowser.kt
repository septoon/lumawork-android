package ru.lumastack.lumawork.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HomeRouteBrowser(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    fun openExternal(target: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri())) }
        catch (_: android.content.ActivityNotFoundException) { error = "Не удалось открыть приложение карт." }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Закрыть карту") }
                    Text("Яндекс.Карты", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
                        context.startActivity(Intent.createChooser(share, "Поделиться маршрутом"))
                    }) { Icon(Icons.Default.Share, "Поделиться маршрутом") }
                }
                error?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { error = null; webView?.loadUrl(url) }) { Text("Повторить") }
                    }
                }
                androidx.compose.ui.viewinterop.AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { viewContext ->
                    android.webkit.WebView(viewContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setGeolocationEnabled(false)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = HomeRouteWebClient(onExternal = ::openExternal, onError = { error = it })
                        webView = this
                        loadUrl(url)
                    }
                }, onRelease = { view -> webView = null; view.stopLoading(); view.destroy() })
            }
        }
    }
}

private class HomeRouteWebClient(
    private val onExternal: (String) -> Unit,
    private val onError: (String) -> Unit,
) : android.webkit.WebViewClient() {
    override fun onPageFinished(view: android.webkit.WebView, url: String) {
        view.evaluateJavascript(YANDEX_ROUTE_SCRIPT, null)
    }
    override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
        if (request.url.scheme in listOf("http", "https")) return false
        onExternal(request.url.toString())
        return true
    }
    override fun onReceivedError(view: android.webkit.WebView, request: android.webkit.WebResourceRequest, failure: android.webkit.WebResourceError) {
        if (request.isForMainFrame) onError("Не удалось загрузить маршрут. Проверьте подключение к интернету.")
    }
}

// Shared behavior with the iOS Yandex browser: suppress app banners and resolve repeated start points.
private const val YANDEX_ROUTE_SCRIPT = """
(() => {
  try {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: function(_success, error) {
          if (typeof error === 'function') {
            error({ code: 1, message: 'Геолокация отключена в приложении «Инженер».' });
          }
        },
        watchPosition: function(_success, error) {
          if (typeof error === 'function') {
            error({ code: 1, message: 'Геолокация отключена в приложении «Инженер».' });
          }
          return 0;
        },
        clearWatch: function() {}
      }
    });
  } catch (_) {}

  try {
    localStorage.setItem('lumaWorkEmbeddedMaps', '1');
  } catch (_) {}

  const style = document.createElement('style');
  style.textContent = [
    'meta[name="apple-itunes-app"]{display:none!important}',
    'a[href^="yandexmaps:"]{display:none!important}',
    'a[href^="yandexnavi:"]{display:none!important}',
    '[class*="smartbanner" i]{display:none!important}',
    '[class*="app-banner" i]{display:none!important}',
    '[class*="install-app" i]{display:none!important}'
  ].join('\n');
  document.documentElement.appendChild(style);
})();
(() => {
  if (window.__lumaRepeatedStartRouteCheck) return;
  window.__lumaRepeatedStartRouteCheck = true;

  let attempts = 0;
  const timer = setInterval(() => {
    if (++attempts > 120) {
      clearInterval(timer);
      return;
    }

    const url = new URL(location.href);
    const points = url.searchParams.get('rtext')?.split('~') || [];
    const uris = url.searchParams.get('ruri')?.split('~') || [];
    if (points.length < 3 || uris.length !== points.length || !uris[0]) return;

    const repeatedStops = points.flatMap((point, index) =>
      index > 0 && index < points.length - 1 && point === points[0] && !uris[index]
        ? [index] : []
    );
    if (!repeatedStops.length) return;

    const routeKey = 'lumaRepeatedStart:' + points.join('~');
    if (sessionStorage.getItem(routeKey)) {
      clearInterval(timer);
      return;
    }

    repeatedStops.forEach(index => { uris[index] = uris[0]; });
    sessionStorage.setItem(routeKey, '1');
    url.searchParams.set('ruri', uris.join('~'));
    clearInterval(timer);
    location.replace(url.href);
  }, 500);
})();
"""
