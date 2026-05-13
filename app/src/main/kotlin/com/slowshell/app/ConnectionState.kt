package com.slowshell.app

sealed class ConnectionState {
    object Idle : ConnectionState()
    data class Connecting(val host: String, val port: Int) : ConnectionState()
    data class Connected(
        val host: String,
        val port: Int,
        val bytesSent: Long,
        val rms: Float
    ) : ConnectionState()
    data class Reconnecting(
        val host: String,
        val port: Int,
        val attempt: Int,
        val lastError: String
    ) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

fun categorizeIoError(message: String?): String {
    if (message == null) return "Unknown error"
    val lower = message.lowercase()
    return when {
        "connection refused" in lower ->
            "Server not listening on this port. Is snapserver running with a tcp source?"
        "no route to host" in lower ->
            "No route — phone and server may be on different networks"
        "network is unreachable" in lower ->
            "Network unreachable — check wifi"
        "host is unreachable" in lower ->
            "Host unreachable — wrong IP or server is offline"
        "connection reset" in lower ->
            "Server closed the connection"
        "broken pipe" in lower ->
            "Pipe broken — server restarted or network blip"
        "timeout" in lower || "timed out" in lower ->
            "Connection timed out — wrong IP or server unreachable"
        "permission denied" in lower ->
            "Permission denied (firewall?)"
        else -> message
    }
}
