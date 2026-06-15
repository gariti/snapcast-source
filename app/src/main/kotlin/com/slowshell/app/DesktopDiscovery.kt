package com.slowshell.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * mDNS/Zeroconf discovery of the SlowShell desktop (advertised by the NixOS
 * slowshell-pairing-advertise.service as `_slowshell._udp`).
 *
 * Returns UNVERIFIED candidates: each carries the resolved IP plus the `vrfy`
 * TCP port from the service's TXT record. The caller MUST run each candidate
 * through PairingVerifier before trusting it — discovery alone proves nothing.
 */
class DesktopDiscovery(context: Context) {

    data class Candidate(val name: String, val host: String, val vrfyPort: Int)

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Browse for [windowMs], resolve each unique service, return candidates. */
    suspend fun discover(windowMs: Long = 4000): List<Candidate> = withContext(Dispatchers.IO) {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
                found.close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw: ${e.message}")
            return@withContext emptyList()
        }

        val candidates = mutableListOf<Candidate>()
        try {
            // Resolve SEQUENTIALLY — the legacy NsdManager rejects concurrent
            // resolveService calls (FAILURE_ALREADY_ACTIVE).
            withTimeoutOrNull(windowMs) {
                val seen = HashSet<String>()
                for (svc in found) {
                    val name = svc.serviceName ?: continue
                    if (!seen.add(name)) continue
                    val resolved = resolve(svc) ?: continue
                    val vrfy = resolved.attributes["vrfy"]
                        ?.toString(Charsets.US_ASCII)?.trim()?.toIntOrNull() ?: continue
                    val ip = resolved.host?.hostAddress ?: continue
                    Log.i(TAG, "candidate $name -> $ip vrfy=$vrfy")
                    candidates.add(Candidate(name, ip, vrfy))
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            found.close()
        }
        candidates
    }

    @Suppress("DEPRECATION") // resolveService kept for minSdk 29 compatibility
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val rl = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo)
                }
            }
            try {
                nsd.resolveService(info, rl)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    companion object {
        private const val TAG = "DesktopDiscovery"
        const val SERVICE_TYPE = "_slowshell._udp."
    }
}
