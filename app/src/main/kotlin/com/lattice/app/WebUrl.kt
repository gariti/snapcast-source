package com.lattice.app

/**
 * Which URL the desktop's focused window is showing — worked out from the
 * window metadata the phone already has.
 *
 * No Wayland window knows it has a URL, and `desk` carries none: the phone is
 * handed `id, title, app, workspace, output, focused, floating, visible, rect`
 * and nothing else. So this is a reader of app-ids, and it is deliberately the
 * WEAKER of two answers — `lattice-link` can read `--app=<url>` straight out of
 * `/proc/<pid>/cmdline` and send the exact thing, query string and all. When
 * that arrives it wins (see `Link.webUrl`); this is what the phone can manage
 * on its own, with no desktop of the right vintage on the other end.
 *
 * Measured on this desktop 2026-09-14:
 *
 *   chrome-claude.ai__artifact_BGVnLeWBQdBhi5afo92oiE-Default
 *     → https://claude.ai/artifact/BGVnLeWBQdBhi5afo92oiE
 *   helium                → a tabbed window; its title is the PAGE title and
 *                           there is no way back to a URL from it
 *   org.qt-project.qml    → the dashboard kiosk (the stock `qml` runner
 *                           hardcodes that app-id), disambiguated by title
 */

/** Chromium app-mode windows: `chrome-<host>_<path>-<profile>`. */
private const val APP_MODE_PREFIX = "chrome-"

/**
 * The dashboard kiosk's window title. The app-id is the generic `qml` runtime's
 * (`exec -a` and QT_WAYLAND_APP_ID are both ignored by it), so it identifies
 * nothing on its own and the title is the only signal.
 */
private const val KIOSK_TITLE = "dashboard-web"

/** Tabbed browser windows. Recognised so the UI can say why there is no page. */
private val TABBED_BROWSERS = setOf("helium", "brave-browser", "chromium", "google-chrome", "firefox")

/** Where a URL came from, because the three sources are not equally trustworthy. */
enum class WebSource {
    /** The desktop read it out of the process it belongs to. Exact. */
    Desktop,

    /** Decoded from a Chromium app-mode app-id. The URL it OPENED at, lossily. */
    AppMode,

    /** The kiosk announced it in its window title. */
    Kiosk,
}

data class WebPage(val url: String, val source: WebSource)

/**
 * The page a window is showing, or null if it is not a web window — or is one
 * we cannot get a URL out of, which is every ordinary tabbed browser window.
 */
fun webPageFor(w: Link.Win?): WebPage? {
    if (w == null) return null
    kioskUrl(w.title)?.let { return WebPage(it, WebSource.Kiosk) }
    if (w.app.startsWith(APP_MODE_PREFIX)) {
        appModeUrl(w.app)?.let { return WebPage(it, WebSource.AppMode) }
    }
    return null
}

/** True for a browser window we recognise but cannot resolve — worth saying so. */
fun isTabbedBrowser(w: Link.Win?): Boolean = w != null && w.app in TABBED_BROWSERS

/**
 * `dashboard-web · <url>` — the kiosk puts its address in its title precisely
 * so this works over the `desk` message that already carries titles. A bare
 * `dashboard-web` is an older kiosk that does not, and resolves to nothing
 * rather than to a wrong guess.
 */
internal fun kioskUrl(title: String): String? {
    if (!title.startsWith(KIOSK_TITLE)) return null
    val rest = title.removePrefix(KIOSK_TITLE).trimStart(' ', '·', '-', '—')
    return rest.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

/**
 * Decode a Chromium app-mode app-id back into a URL.
 *
 * Chromium builds the name as `host + "_" + path` and then replaces every
 * character that is not allowed in a WM class — `/` included — with `_`, so
 * `https://claude.ai/artifact/X` becomes `claude.ai__artifact_X` and the whole
 * app-id is that between `chrome-` and `-<ProfileDir>`.
 *
 * **This is lossy and cannot be made otherwise.** A `_` in the original path is
 * indistinguishable from the `/` it sits next to, and the query string was
 * never in the app-id at all. That is why the URL is editable in the bar and
 * why the desktop's exact answer supersedes this one whenever it arrives —
 * a wrong guess you can see and correct beats a blank surface.
 */
internal fun appModeUrl(appId: String): String? {
    val body = appId.removePrefix(APP_MODE_PREFIX)
    // The profile directory is the last `-` segment. Path segments may contain
    // `-` themselves, so take the last one and no earlier.
    val hostAndPath = body.substringBeforeLast('-', missingDelimiterValue = "")
    if (hostAndPath.isEmpty()) return null

    // Chromium joins with a literal `_` and THEN mangles the path's slashes,
    // so the separator and the path's leading `/` are two different
    // underscores: `claude.ai` + `_` + `/artifact/X` → `claude.ai__artifact_X`.
    // Drop the separator, map the rest.
    val cut = hostAndPath.indexOf('_')
    val host = if (cut < 0) hostAndPath else hostAndPath.substring(0, cut)
    if (host.isEmpty()) return null
    val path = if (cut < 0) "" else hostAndPath.substring(cut + 1).replace('_', '/')

    return scheme(host) + host + path
}

/**
 * app-ids record no scheme, so it has to be inferred. A bare hostname with no
 * dot is a LAN/tailnet name and a private literal is a LAN address; both are
 * overwhelmingly plain http on this network (both registered dashboard sites
 * are). Everything else gets https.
 */
private fun scheme(hostPort: String): String {
    val host = hostPort.substringBefore(':')
    val local = !host.contains('.') ||
        host == "localhost" ||
        host.startsWith("192.168.") ||
        host.startsWith("10.") ||
        host.startsWith("127.") ||
        host.endsWith(".local") ||
        host.endsWith(".ts.net")
    return if (local) "http://" else "https://"
}
