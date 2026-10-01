package com.lattice.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.lattice.app.lx.LxTheme

/**
 * What the canvas card needs to know about the page, and the two verbs its
 * bottom band offers. The card draws the Page field, the status line and the
 * reload / open-on-the-desktop buttons; this surface is the page only.
 */
class WebController {
    var current by mutableStateOf("")
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    internal var view: WebView? = null
    fun reload() { view?.reload() }
    fun load(url: String) { val u = normalize(url) ?: return; view?.loadUrl(u) }
}

@Composable
fun rememberWebController() = remember { WebController() }

/**
 * The page itself, rendered on the phone. The third canvas surface.
 *
 * Terminal mode exists because a picture of text is a bad way to read text,
 * and a 768-px JPEG at 4 fps of a browser on the 5K is worse. Here the phone
 * loads the page, so scroll, pinch-zoom, text selection and the IME are the
 * platform's and cost the desktop nothing.
 *
 * What it is NOT: the desktop's browser. This WebView has its own cookie jar,
 * so anything behind a login is signed out here — the card says so, once per
 * site. The mirror is the desktop's own authenticated screen, one chip away.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSurface(
    url: String,
    enabled: Boolean,
    controller: WebController,
    onTitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val ground = LxTheme.current.roles.ground.toArgb()

    // One WebView for the life of the surface: rebuilding it would throw the
    // page, its scroll position and its history away on every recomposition.
    val web = remember(ctx) {
        WebView(ctx).apply {
            setBackgroundColor(ground)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) {
                    controller.current = u; controller.loading = true; controller.failure = null
                }
                override fun onPageFinished(v: WebView, u: String) {
                    controller.current = u; controller.loading = false; controller.canBack = v.canGoBack()
                    // The page's own name, for the wordmark. The desktop window's
                    // title is no use for a kiosk — it is the `dashboard-web ·
                    // <url>` marker this surface parsed the address out of.
                    onTitle(v.title?.takeIf { it.isNotBlank() && it != u })
                }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    // Subresources fail constantly and say nothing useful; only
                    // the page's own failure earns the status line.
                    if (r.isForMainFrame) { controller.loading = false; controller.failure = "${e.description}" }
                }
            }
        }
    }
    DisposableEffect(web) {
        controller.view = web
        onDispose {
            controller.view = null
            onTitle(null)
            web.stopLoading()
            web.destroy()
        }
    }

    // A new URL from outside — the desktop focused a different page — loads.
    // A URL the view reached by itself does not bounce back.
    var lastAsked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        if (url != lastAsked) { lastAsked = url; web.loadUrl(url) }
    }
    // Stop the page's timers while the surface is not live, for the reason
    // the mirror stops wf-recorder.
    LaunchedEffect(enabled) { if (enabled) web.onResume() else web.onPause() }

    BackHandler(enabled = controller.canBack) { web.goBack() }

    Box(modifier) {
        AndroidView(
            factory = { web },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "web page" },
        )
    }
}

/** `https://claude.ai/artifact/X` → `claude.ai/artifact/X`. The scheme is noise. */
internal fun prettyUrl(u: String): String =
    u.removePrefix("https://").removePrefix("http://").removeSuffix("/")

/** What someone typed, as something a WebView will accept. */
internal fun normalize(typed: String): String? {
    val t = typed.trim()
    if (t.isEmpty()) return null
    if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:")) return t
    // A bare LAN/tailnet name is http on this network; anything with a dot
    // gets https and will redirect itself if it wanted otherwise.
    val host = t.substringBefore('/').substringBefore(':')
    val local = !host.contains('.') || host.startsWith("192.168.") || host.startsWith("10.")
    return if (local) "http://$t" else "https://$t"
}
