package com.karaokemaker.tv

import com.karaokemaker.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** TVs the phone has connected to before, most recent first, so they can be reached without discovery. */
object TvHistory {
    private const val MAX = 10

    val devices = MutableStateFlow(load())

    private fun load(): List<TvLink.Device> = runCatching {
        val a = JSONArray(Prefs.savedTvs)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val hosts = o.getJSONArray("hosts")
            TvLink.Device(o.getString("name"), (0 until hosts.length()).map { hosts.getString(it) }, o.getInt("port"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<TvLink.Device>) {
        Prefs.savedTvs = JSONArray().apply {
            list.forEach { d -> put(JSONObject().put("name", d.name).put("hosts", JSONArray(d.hosts)).put("port", d.port)) }
        }.toString()
    }

    /** Called after a successful connection; [workingHost] goes first so it is tried first next time. */
    fun remember(device: TvLink.Device, workingHost: String) {
        val entry = device.copy(hosts = (listOf(workingHost) + device.hosts).distinct())
        devices.update { list ->
            (listOf(entry) + list.filter { it.name != entry.name && workingHost !in it.hosts }).take(MAX).also(::save)
        }
    }

    /** The TV told us its real name (useful for TVs added by address). */
    fun rename(workingHost: String, name: String) {
        devices.update { list -> list.map { if (it.hosts.firstOrNull() == workingHost) it.copy(name = name) else it }.also(::save) }
    }

    fun forget(device: TvLink.Device) {
        devices.update { list -> list.filter { it != device }.also(::save) }
    }
}
