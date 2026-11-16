package app.lumen.reader.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.print.PrintManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Javascript
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.LocalNavigator
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Route
import app.lumen.reader.core.Saver
import app.lumen.reader.edit.toast
import app.lumen.reader.text.TextLoader
import app.lumen.reader.ui.components.AppIconButton
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.web.LocalSite
import kotlinx.coroutines.launch

/**
 * HTML and SVG in a WebView that behaves like a browser for local files but can't reach the
 * internet. JavaScript, storage, relative files beside the page, links between pages, alerts
 * and file pickers all work. Every request is answered from the page's own folder (or MHTML
 * archive) or refused, so nothing can phone home. Web links open in the user's browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(ref: DocRef, onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = Lumen.colors.isDark
    val scope = rememberCoroutineScope()
    val pickers = LocalPickers.current
    val navigate = LocalNavigator.current
    val svg = ref.kind == FileKind.Svg
    var site by remember { mutableStateOf<LocalSite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var js by rememberSaveable { mutableStateOf(true) }
    var darkPage by rememberSaveable { mutableStateOf(false) }
    var textZoom by rememberSaveable { mutableIntStateOf(100) }
    var title by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(0 to 0) }

    LaunchedEffect(ref, reload) {
        runCatching {
            if (svg) {
                val src = TextLoader.load(context, ref, maxBytes = 12 shl 20).text
                val bg = if (dark) "#111318" else "#f4f5fa"
                LocalSite.forFolder(context.cacheDir, ref.name, """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=10">
                    <style>html,body{margin:0;height:100%;background:$bg;display:flex;align-items:center;justify-content:center}
                    svg{max-width:100%;max-height:100vh;height:auto}</style></head><body>$src</body></html>""")
            } else LocalSite.load(context, ref)
        }.onSuccess { site = it }.onFailure { error = it.message }
    }

    val webView = remember { mutableStateOf<WebView?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    fun present(view: WebView, s: LocalSite, url: String, html: String) {
        view.loadDataWithBaseURL(url, if (darkPage) withDarkStyle(html) else html, "text/html", "utf-8", url)
    }

    // Apply settings changes live; JavaScript and dark page need the page re-rendered.
    LaunchedEffect(js, darkPage, site) {
        val v = webView.value ?: return@LaunchedEffect
        val s = site ?: return@LaunchedEffect
        if (v.settings.javaScriptEnabled != js || v.tag != darkPage) {
            v.settings.javaScriptEnabled = js
            v.tag = darkPage
            present(v, s, s.pageUrl, s.html)
        }
    }
    LaunchedEffect(textZoom) { webView.value?.settings?.textZoom = textZoom }
    BackHandler(enabled = canGoBack || finding) {
        if (finding) {
            finding = false; webView.value?.clearMatches()
        } else webView.value?.goBack()
    }
    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            webView.value?.destroy()
        }
    }

    ViewerScaffold(
        ref = ref,
        title = title?.takeIf { it.isNotBlank() && !it.startsWith(LocalSite.ORIGIN) } ?: ref.name,
        subtitle = when {
            svg -> "Vector image"
            site?.isArchive == true -> "Saved web page · offline"
            else -> "Web page · offline" + if (js) "" else " · JavaScript off"
        },
        chromeVisible = true,
        onBack = onBack,
        titleSlot = if (finding) {
            {
                TextField(
                    value = query,
                    onValueChange = { query = it; webView.value?.findAllAsync(it) },
                    singleLine = true,
                    placeholder = { Text("Find in page") },
                    suffix = { if (query.isNotEmpty()) Text(if (matches.second == 0) "0/0" else "${matches.first + 1}/${matches.second}") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { webView.value?.findNext(true) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else null,
        actions = {
            if (finding) {
                AppIconButton(Icons.Rounded.KeyboardArrowUp, "Previous match", { webView.value?.findNext(false) })
                AppIconButton(Icons.Rounded.KeyboardArrowDown, "Next match", { webView.value?.findNext(true) })
            } else if (!svg) {
                AppIconButton(Icons.Rounded.Search, "Find in page", { finding = true })
            }
        },
        menu = if (svg) emptyList() else listOf(
            MenuAction("Reload", Icons.Rounded.Refresh) { reload++ },
            MenuAction(if (js) "JavaScript: on" else "JavaScript: off", Icons.Rounded.Javascript) { js = !js },
            MenuAction(if (darkPage) "Dark page: on" else "Dark page: off", Icons.Rounded.DarkMode) { darkPage = !darkPage },
            MenuAction("Text size: $textZoom%", Icons.Rounded.FormatSize) {
                textZoom = TextSizes[(TextSizes.indexOf(textZoom) + 1) % TextSizes.size]
            },
            MenuAction("Print or save as PDF", Icons.Rounded.Print) {
                val v = webView.value ?: return@MenuAction
                val pm = context.getSystemService(PrintManager::class.java) ?: return@MenuAction
                val job = (title ?: ref.name).substringBeforeLast('.')
                pm.print(job, v.createPrintDocumentAdapter(job), null)
            },
            MenuAction("Edit source", Icons.Rounded.Code) { navigate(Route.Doc(ref.copy(kind = FileKind.Code))) }
        )
    ) { padding ->
        when {
            error != null -> CenteredMessage("Couldn't open this file", error)
            site == null -> LoadingIndicator()
            else -> Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                val s = site!!
                AndroidView(
                    factory = { ctx ->
                        val zoom = textZoom
                        WebView(ctx).apply {
                            webView.value = this
                            tag = darkPage
                            setBackgroundColor(if (svg) AndroidColor.TRANSPARENT else AndroidColor.WHITE)
                            settings.apply {
                                javaScriptEnabled = js && !svg
                                domStorageEnabled = true
                                // The sandbox: no network, no device files. Requests for the page's
                                // own folder are answered in shouldInterceptRequest below.
                                blockNetworkLoads = true
                                allowFileAccess = false
                                allowContentAccess = false
                                javaScriptCanOpenWindowsAutomatically = false
                                setSupportMultipleWindows(false)
                                builtInZoomControls = true
                                displayZoomControls = false
                                loadWithOverviewMode = true
                                useWideViewPort = !svg
                                textZoom = zoom
                                mediaPlaybackRequiresUserGesture = true
                                if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url
                                    // Another page of this site: render it ourselves so it keeps the sandbox.
                                    s.page(url)?.let { html ->
                                        present(view, s, url.toString(), html); return true
                                    }
                                    if (url.host == LocalSite.HOST) {
                                        toast(context, "That page isn't available offline"); return true
                                    }
                                    // Web, mail and phone links go to the apps made for them.
                                    if (url.scheme in setOf("http", "https", "mailto", "tel", "sms", "geo", "market")) {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }.onFailure { toast(context, "No app can open that link") }
                                    }
                                    return true
                                }

                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val url = request.url
                                    if (url.scheme == "data" || url.scheme == "blob") return null
                                    val r = runCatching { s.open(url) }.getOrNull()
                                        ?: return WebResourceResponse("text/plain", "utf-8", 404, "Offline", null, null)
                                    return WebResourceResponse(r.mime, r.charset, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), r.stream)
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView, t: String?) {
                                    title = t
                                }

                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }

                                // <input type="file">: hand the page files picked with the system picker.
                                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                                    fileCallback?.onReceiveValue(null)
                                    fileCallback = callback
                                    val mimes = params.acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim() }
                                        .map { if (it.startsWith(".")) android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) ?: "*/*" else it }
                                        .filter { it.isNotEmpty() }.ifEmpty { listOf("*/*") }
                                    scope.launch {
                                        val uris = pickers.openDocuments(mimes.toTypedArray(), params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                                        fileCallback?.onReceiveValue(uris.toTypedArray().takeIf { it.isNotEmpty() })
                                        fileCallback = null
                                    }
                                    return true
                                }
                            }
                            // <a download href="data:…">: save generated files (exports from web tools).
                            setDownloadListener { url, _, disposition, mime, _ ->
                                if (!url.startsWith("data:")) {
                                    toast(context, "Can't download that offline"); return@setDownloadListener
                                }
                                val name = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""").find(disposition.orEmpty())?.groupValues?.get(1) ?: "download"
                                scope.launch {
                                    val bytes = decodeDataUrl(url) ?: return@launch
                                    toast(context, Saver.saveCopy(context, pickers, Uri.decode(name), mime ?: "application/octet-stream") { it.write(bytes) })
                                }
                            }
                            setFindListener { active, count, _ -> matches = active to count }
                            present(this, s, s.pageUrl, s.html)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private val TextSizes = listOf(100, 115, 130, 150, 85)

/** Inverts the page's lightness (but not its pictures) for reading light pages at night. */
internal fun withDarkStyle(html: String): String {
    val css = "<style id=\"lumen-dark\">html{filter:invert(1) hue-rotate(180deg);background:#fff}" +
        "img,video,picture,canvas,iframe,svg image,[style*=\"background-image\"]{filter:invert(1) hue-rotate(180deg)}</style>"
    val head = Regex("</head>", RegexOption.IGNORE_CASE).find(html)
    return if (head != null) html.substring(0, head.range.first) + css + html.substring(head.range.first) else css + html
}

internal fun decodeDataUrl(url: String): ByteArray? = runCatching {
    val comma = url.indexOf(',')
    val meta = url.substring(5, comma)
    val data = url.substring(comma + 1)
    if (meta.endsWith(";base64")) java.util.Base64.getDecoder().decode(data)
    else Uri.decode(data).toByteArray(Charsets.UTF_8)
}.getOrNull()
