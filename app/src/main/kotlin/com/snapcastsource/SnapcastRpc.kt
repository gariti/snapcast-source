package com.snapcastsource

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

data class SnapClient(
    val id: String,
    val name: String,
    val groupId: String,
    val streamId: String,
    val muted: Boolean,
    val volumePercent: Int,
    val connected: Boolean
)

data class SnapStatus(
    val clients: List<SnapClient>,
    val streamIds: List<String>
)

class SnapcastRpc(
    private val host: String,
    private val port: Int = 1705
) {
    private var requestId = 0

    private fun call(method: String, params: JSONObject? = null): JSONObject {
        requestId++
        val req = JSONObject().apply {
            put("id", requestId)
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        return Socket(host, port).use { sock ->
            sock.soTimeout = 5_000
            val out = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            val rd = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            out.write(req.toString())
            out.write("\r\n")
            out.flush()
            val line = rd.readLine() ?: throw IOException("snapserver closed connection without responding")
            val resp = JSONObject(line)
            if (resp.has("error")) {
                throw IOException("snapserver error: ${resp.getJSONObject("error")}")
            }
            resp
        }
    }

    fun getStatus(): SnapStatus {
        val resp = call("Server.GetStatus")
        val server = resp.getJSONObject("result").getJSONObject("server")
        val groups = server.getJSONArray("groups")
        val streams = server.getJSONArray("streams")

        val clients = mutableListOf<SnapClient>()
        for (gi in 0 until groups.length()) {
            val g = groups.getJSONObject(gi)
            val gid = g.getString("id")
            val sid = g.getString("stream_id")
            val cs = g.getJSONArray("clients")
            for (ci in 0 until cs.length()) {
                val c = cs.getJSONObject(ci)
                val cid = c.getString("id")
                val cfg = c.getJSONObject("config")
                val vol = cfg.getJSONObject("volume")
                val hostObj = c.getJSONObject("host")
                val cfgName = cfg.optString("name", "")
                val displayName = if (cfgName.isNotEmpty()) cfgName else hostObj.optString("name", cid)
                clients.add(
                    SnapClient(
                        id = cid,
                        name = displayName,
                        groupId = gid,
                        streamId = sid,
                        muted = vol.optBoolean("muted", false),
                        volumePercent = vol.optInt("percent", 100),
                        connected = c.optBoolean("connected", false)
                    )
                )
            }
        }

        val streamIds = mutableListOf<String>()
        for (si in 0 until streams.length()) {
            streamIds.add(streams.getJSONObject(si).getString("id"))
        }
        return SnapStatus(clients, streamIds)
    }

    fun setGroupStream(groupId: String, streamId: String) {
        call("Group.SetStream", JSONObject().apply {
            put("id", groupId)
            put("stream_id", streamId)
        })
    }

    fun setClientMute(clientId: String, muted: Boolean, volumePercent: Int) {
        call("Client.SetVolume", JSONObject().apply {
            put("id", clientId)
            put("volume", JSONObject().apply {
                put("muted", muted)
                put("percent", volumePercent)
            })
        })
    }
}
