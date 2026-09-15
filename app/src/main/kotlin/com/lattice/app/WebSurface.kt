package com.lattice.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The page itself, rendered on the phone. The third canvas surface.
 *
 * Same argument as the second one: terminal mode exists because a picture of
 * text is a bad way to read text, and a 768-px JPEG at 4 fps of a browser on
 * the 5K is worse. Here the phone loads the page, so scroll, pinch-zoom, text
 * selection and the IME are the platform's and cost the desktop nothing —
 * no `wf-recorder`, no pointer round trip, no frames on the wire.
 *
 * What it is NOT: the desktop's browser. This WebView has its own cookie jar,
 * so anything behind a login is logged out here until you sign in on the
 * phone. That is why web mode is a chip BESIDE the mirror rather than a
 * replacement for it — the mirror is the desktop's own authenticated screen,
 * and it stays one tap away.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSurface(
    url: String,
    enabled: Boolean,
    onOpenOnDesktop: (String) -> Unit,
    onTitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val ground = MaterialTheme.colorScheme.background.toArgb()
    val focus = LocalFocusManager.current

    var current by remember { mutableStateOf(url) }
    var typed by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    // One WebView for the life of the surface. Like the mirror and the
    // terminal it has to survive recomposition: rebuilding it would throw the
    // page, its scroll position and its history away on every frame that
    // touches this tree.
    val web = remember(ctx) {
        WebView(ctx).apply {
            setBackgroundColor(ground)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Lay out for this screen rather than at a desktop width and then
            // shrink, which is the whole reason to render here at all.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) {
                    current = u; loading = true; failure = null
                }
                override fun onPageFinished(v: WebView, u: String) {
                    current = u; loading = false; canBack = v.canGoBack()
                    // The page's own name, for the title strip. The desktop
                    // window's title is no use for a kiosk — it is the
                    // `dashboard-web · <url>` marker this surface parses the
                    // address out of, which would just repeat the bar below.
                    onTitle(v.title?.takeIf { it.isNotBlank() && it != u })
                }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    // Subresources fail constantly and say nothing useful;
                    // only the page's own failure earns a banner.
                    if (r.isForMainFrame) {
                        loading = false
                        failure = "${e.description}"
                    }
                }
            }
        }
    }

    // A new URL from outside — the desktop focused a different page — loads.
    // A URL the view reached by itself does not bounce back to where the
    // desktop last thought it was.
    var lastAsked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        if (url != lastAsked) {
            lastAsked = url
            typed = null
            web.loadUrl(url)
        }
    }

    // Stop the page's timers while the surface is not live, for the reason the
    // mirror stops wf-recorder: a backgrounded phone must not keep work going.
    LaunchedEffect(enabled) { if (enabled) web.onResume() else web.onPause() }
    DisposableEffect(web) {
        onDispose {
            onTitle(null)
            web.stopLoading()
            web.destroy()
        }
    }

    BackHandler(enabled = canBack) { web.goBack() }

    Column(modifier) {
        UrlBar(
            shown = typed ?: prettyUrl(current),
            editing = typed != null,
            onBeginEdit = { typed = current },
            onType = { typed = it },
            onGo = {
                val u = normalize(it)
                typed = null
                focus.clearFocus()
                if (u != null) { lastAsked = u; web.loadUrl(u) }
            },
            onReload = { web.reload() },
            onSendToDesktop = { onOpenOnDesktop(current) },
        )
        // A two-pixel line rather than a spinner: the page under it is the
        // thing to look at, and a spinner over a half-drawn page reads as
        // broken.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxSize())
        }
        failure?.let {
            Text(
                "$it · ⇧ opens it on the desktop instead",
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        AndroidView(
            factory = { web },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { contentDescription = "web page" },
        )
    }
}

/** Address, reload, and throw-it-at-the-big-screen. */
@Composable
private fun UrlBar(
    shown: String,
    editing: Boolean,
    onBeginEdit: () -> Unit,
    onType: (String) -> Unit,
    onGo: (String) -> Unit,
    onReload: () -> Unit,
    onSendToDesktop: () -> Unit,
) {
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = MaterialTheme.typography.labelMedium.fontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (editing) {
            BasicTextField(
                value = shown,
                onValueChange = onType,
                singleLine = true,
                textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo(shown) }),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 11.dp)
                    .semantics { contentDescription = "address" },
            )
        } else {
            Text(
                shown,
                Modifier
                    .weight(1f)
                    .clickable { onBeginEdit() }
                    .padding(vertical = 11.dp)
                    .semantics { contentDescription = "address" },
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSendToDesktop, modifier = Modifier.size(40.dp)) {
            Text(
                "⇧",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "open on the desktop" },
            )
        }
        IconButton(onClick = onReload, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "reload", Modifier.size(18.dp))
        }
    }
}

/** `https://claude.ai/artifact/X` → `claude.ai/artifact/X`. The scheme is noise. */
internal fun prettyUrl(u: String): String =
    u.removePrefix("https://").removePrefix("http://").removeSuffix("/")

/** What someone typed into the bar, as something a WebView will accept. */
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
