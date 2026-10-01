package app.lumen.reader.viewer

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.FileKind
import app.lumen.reader.text.TextLoader
import app.lumen.reader.ui.theme.Lumen

/**
 * HTML and SVG, rendered by WebView in a sealed sandbox: no JavaScript, no network, no file access.
 * Pages render exactly as authored, but nothing can phone home.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(ref: DocRef, onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = Lumen.colors.isDark
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ref) {
        runCatching { TextLoader.load(context, ref, maxBytes = 12 shl 20).text }
            .onSuccess { src ->
                html = if (ref.kind == FileKind.Svg) {
                    val bg = if (dark) "#111318" else "#f4f5fa"
                    """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=10">
                    <style>html,body{margin:0;height:100%;background:$bg;display:flex;align-items:center;justify-content:center}
                    svg{max-width:100%;max-height:100vh;height:auto}</style></head><body>$src</body></html>"""
                } else src
            }
            .onFailure { error = it.message }
    }
    ViewerScaffold(ref, ref.name, if (ref.kind == FileKind.Svg) "Vector image" else "Web page · offline", true, onBack) { padding ->
        when {
            error != null -> CenteredMessage("Couldn't open this file", error)
            html == null -> LoadingIndicator()
            else -> Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(AndroidColor.TRANSPARENT)
                            settings.apply {
                                javaScriptEnabled = false
                                blockNetworkLoads = true
                                blockNetworkImage = true
                                allowFileAccess = false
                                allowContentAccess = false
                                builtInZoomControls = true
                                displayZoomControls = false
                                loadWithOverviewMode = true
                                useWideViewPort = ref.kind != FileKind.Svg
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                    if (request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", null)
                            }
                            loadDataWithBaseURL(null, html!!, "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
