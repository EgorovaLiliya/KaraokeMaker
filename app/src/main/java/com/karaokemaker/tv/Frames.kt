package com.karaokemaker.tv

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Phone ↔ TV protocol over one TCP socket: each frame is a 4-byte length plus a UTF-8 JSON object.
 * A "file" frame is followed by exactly `size` raw bytes of audio.
 */
object Frames {
    const val SERVICE_TYPE = "_karaokemaker._tcp"
    const val PORT = 47123

    fun write(out: DataOutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        synchronized(out) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    fun read(input: DataInputStream): JSONObject {
        val n = input.readInt()
        if (n < 0 || n > 20_000_000) throw IOException("Неверный пакет")
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun msg(type: String): JSONObject = JSONObject().put("type", type)

    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
