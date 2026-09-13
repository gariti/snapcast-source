package com.lattice.app

import android.content.Context
import android.content.SharedPreferences

/**
 * The one place the SharedPreferences file and its keys are named. Every
 * reader (activity, services, the boot receiver) goes through here so the
 * file name cannot drift between call sites again — it was a string literal
 * in four files before the Lattice rename.
 *
 * The PSK lives here too and is excluded from cloud backup / device transfer
 * by res/xml/data_extraction_rules.xml + backup_rules.xml: a restored backup
 * on a new phone must re-pair by QR rather than inherit the key.
 */
object Prefs {
    const val FILE = "lattice_prefs"

    const val KEY_HOST = "host"
    const val KEY_LAN_HOSTS = "lan_hosts"
    const val KEY_PSK = "pairing_psk"
    const val KEY_SLOT = "slot_index"
    const val KEY_PARTY_MODE = "party_mode"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
