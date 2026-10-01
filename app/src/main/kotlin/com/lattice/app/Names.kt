package com.lattice.app

/**
 * Words, not instruments: the names the screens use for the desktop's things.
 * Connector names (`DP-2`, `eDP-1`), app-ids and niri verbs never reach a
 * label; these turn them into what a person calls them.
 */
object Names {
    /**
     * A display, by what it is: the laptop's own panel, the main one, or the
     * n-th other one. The main display is the largest external; with no
     * external there is only the laptop.
     */
    fun display(output: String, outputs: List<Link.Output>): String {
        if (output.startsWith("eDP") || output.startsWith("LVDS") || output.startsWith("DSI")) return "Laptop display"
        val externals = outputs.filter { !(it.name.startsWith("eDP") || it.name.startsWith("LVDS") || it.name.startsWith("DSI")) }
            .sortedByDescending { it.w.toLong() * it.h }
        val idx = externals.indexOfFirst { it.name == output }
        return when {
            idx == 0 || externals.isEmpty() -> "Main display"
            idx > 0 -> "Display ${idx + 1}"
            else -> output
        }
    }

    /** The desktop's own name without its domain: `nixos-1.tailb7f992.ts.net` → `nixos-1`. */
    fun host(host: String): String = host.substringBefore('.').ifBlank { host }

    /** What a window is, for a row's meta: its app, or what kind of thing it is. */
    fun windowKind(w: Link.Win): String = when {
        w.app.startsWith(AGENT_APP_PREFIX) -> "agent session"
        webPageFor(w) != null -> "web page"
        else -> w.app.ifBlank { "window" }
    }

    /** A window's name for a wordmark: its title, else its app, else "untitled". */
    fun windowTitle(w: Link.Win): String {
        if (w.app.startsWith(AGENT_APP_PREFIX)) {
            // A dispatched agent's foot window: the title is the session's
            // task, which is the only thing that tells three of them apart.
            return w.title.ifBlank { w.app.removePrefix(AGENT_APP_PREFIX) }
        }
        return w.title.ifBlank { w.app.ifBlank { "untitled" } }
    }

    /** A workspace by its name, else its number. */
    fun workspace(ws: Link.Workspace?): String? = ws?.let { it.name.ifBlank { "workspace ${it.idx}" } }

    fun seconds(s: Int): String = when {
        s <= 0 -> "never"
        s < 60 -> "$s s"
        s % 60 == 0 -> "${s / 60} min"
        else -> "${s / 60} min ${s % 60} s"
    }
}
