package com.lattice.app

import android.content.Context

/**
 * Where the desktop might be reachable, in dial order.
 *
 * The desktop usually has more than one address — a Tailscale MagicDNS name that
 * works from anywhere, and one or more LAN addresses that work only at home but
 * work there even when the tunnel is down. The QR pairing link carries both
 * (`host=<magicdns>&lan=<ip>[,<ip>]`); before this existed the pairing flow
 * verified them, kept ONE, and discarded the rest — so a phone sitting on the
 * same subnet as its desktop still had nothing to fall back to when Tailscale
 * dropped, which is exactly how the 2026-08-03 outage stayed an outage.
 *
 * Both are kept now. ControlChannelClient races them (see its connectFirstOf)
 * rather than guessing, so "am I at home or not" never has to be answered — the
 * address that answers IS the answer, re-decided on every reconnect.
 *
 * Order matters only as a head start: the primary is listed first, so when it
 * works the LAN sockets are typically never opened at all.
 */
object LinkHosts {
    // Mirrors MainActivity's PREFS_NAME / KEY_HOST — keep in sync.
    private const val PREFS_NAME = Prefs.FILE
    private const val KEY_HOST = "host"
    const val KEY_LAN_HOSTS = "lan_hosts"

    /** The user-visible primary host (MagicDNS name by default). */
    fun primary(context: Context): String =
        prefs(context).getString(KEY_HOST, "")?.trim() ?: ""

    /** Primary first, then the LAN addresses learned at pairing. Deduped. */
    fun all(context: Context): List<String> {
        val lan = prefs(context).getString(KEY_LAN_HOSTS, "").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotBlank() }
        return (listOf(primary(context)) + lan).filter { it.isNotBlank() }.distinct()
    }

    /** Remember the LAN candidates from a QR pair link (replaces the set). */
    fun storeLan(context: Context, hosts: List<String>) {
        prefs(context).edit()
            .putString(KEY_LAN_HOSTS, hosts.filter { it.isNotBlank() }.joinToString(","))
            .apply()
    }

    /**
     * Add one LAN candidate, keeping the existing ones. Used by the mDNS "Find"
     * flow — a phone paired before LAN candidates were kept has none stored, and
     * a cryptographically verified discovery result is exactly the address the
     * race is missing.
     */
    fun addLan(context: Context, host: String) {
        if (host.isBlank()) return
        val existing = prefs(context).getString(KEY_LAN_HOSTS, "").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (host in existing) return
        storeLan(context, existing + host)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
