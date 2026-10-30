package com.karaokemaker.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import com.karaokemaker.data.Song
import com.karaokemaker.lyrics.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Phone side of the TV link: finds TVs on Wi-Fi, sends songs and remote-controls playback. */
object TvLink {
    data class Device(val name: String, val hosts: List<String>, val port: Int)

    data class State(
        val device: Device? = null,
        val connecting: Boolean = false,
        val sendingKey: String? = null,
        val loadedKey: String? = null,
        val upload: Float? = null,
        val playing: Boolean = false,
        val pos: Long = 0,
        val dur: Long = 0,
        val stamp: Long = 0,
        val error: String? = null,
    ) {
        val connected get() = device != null && !connecting
    }

    val devices = MutableStateFlow<List<Device>>(emptyList())
    val state = MutableStateFlow(State())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** All writes go through one thread so commands keep their order. */
    private val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var pendingFile: File? = null

    fun keyFor(song: Song, file: File) = "${song.id}_${file.name}"

    fun connect(device: Device) {
        disconnect()
        state.value = State(device = device, connecting = true)
        val holder = Any()
        currentAttempt = holder
        scope.launch {
            val mine = Frames.localIps()
            // Addresses in the phone's own network first.
            val hosts = device.hosts.distinct().sortedBy { h -> if (mine.any { Frames.subnet(it) == Frames.subnet(h) }) 0 else 1 }
            var lastError: Exception? = null
            for (host in hosts) {
                if (currentAttempt !== holder) return@launch
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(host, device.port), 4000)
                } catch (e: Exception) {
                    lastError = e
                    runCatching { s.close() }
                    continue
                }
                if (currentAttempt !== holder) {
                    runCatching { s.close() }
                    return@launch
                }
                socket = s
                try {
                    s.tcpNoDelay = true
                    out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                    val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                    state.update { it.copy(connecting = false, error = null) }
                    send(Frames.msg("hello").put("name", Build.MODEL))
                    while (true) onMessage(Frames.read(input))
                } catch (e: Exception) {
                    if (socket === s) {
                        socket = null
                        out = null
                        state.value = State(error = "Связь с телевизором прервалась: ${e.message ?: "соединение закрыто"}")
                    }
                    return@launch
                }
            }
            if (currentAttempt === holder) {
                val sameNetwork = hosts.any { h -> mine.any { Frames.subnet(it) == Frames.subnet(h) } }
                state.value = State(
                    error = if (!sameNetwork && mine.isNotEmpty()) {
                        "Телефон (${mine.first()}) и телевизор (${hosts.joinToString()}) в разных сетях. " +
                            "Подключите их к одной Wi-Fi сети (одному роутеру) и попробуйте снова."
                    } else {
                        "Не удалось подключиться к телевизору (${hosts.joinToString()}): ${lastError?.message ?: "нет ответа"}. " +
                            "Проверьте, что KaraokeMaker открыт на ТВ."
                    }
                )
            }
        }
    }

    @Volatile private var currentAttempt: Any? = null

    fun disconnect() {
        currentAttempt = null
        val s = socket
        socket = null
        out = null
        runCatching { s?.close() }
        state.value = State()
    }

    private fun onMessage(m: JSONObject) {
        when (m.optString("type")) {
            "state" -> state.update {
                it.copy(
                    pos = m.optLong("pos"),
                    playing = m.optBoolean("playing"),
                    dur = m.optLong("dur"),
                    stamp = SystemClock.elapsedRealtime(),
                )
            }
            "need_file" -> pendingFile?.let { upload(it) }
            "loaded" -> state.update { it.copy(loadedKey = m.optString("key"), sendingKey = null, upload = null) }
        }
    }

    private fun send(json: JSONObject) {
        scope.launch(writer) {
            val o = out ?: return@launch
            runCatching { Frames.write(o, json) }
        }
    }

    fun sendSong(song: Song, file: File, startMs: Long) {
        val key = keyFor(song, file)
        pendingFile = file
        state.update { it.copy(sendingKey = key, loadedKey = null) }
        send(
            Frames.msg("load")
                .put("key", key)
                .put("title", song.title)
                .put("artist", song.artist)
                .put("lyrics", LyricLine.listToJson(song.lyrics))
                .put("size", file.length())
                .put("start", startMs)
        )
    }

    fun sendLyrics(key: String, lyrics: List<LyricLine>) {
        send(Frames.msg("lyrics").put("key", key).put("lyrics", LyricLine.listToJson(lyrics)))
    }

    private fun upload(file: File) {
        scope.launch(writer) {
            val o = out ?: return@launch
            try {
                val size = file.length()
                synchronized(o) {
                    Frames.write(o, Frames.msg("file").put("size", size))
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastReport = 0L
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            sent += n
                            if (sent - lastReport > 256 * 1024) {
                                lastReport = sent
                                state.update { it.copy(upload = sent.toFloat() / size) }
                            }
                        }
                    }
                    o.flush()
                }
            } catch (e: Exception) {
                state.update { it.copy(error = "Не удалось передать песню: ${e.message}") }
            }
        }
    }

    fun play() {
        state.update { it.copy(playing = true, pos = positionNow(), stamp = SystemClock.elapsedRealtime()) }
        send(Frames.msg("play"))
    }

    fun pause() {
        state.update { it.copy(playing = false, pos = positionNow(), stamp = SystemClock.elapsedRealtime()) }
        send(Frames.msg("pause"))
    }

    fun seek(ms: Long) {
        state.update { it.copy(pos = ms, stamp = SystemClock.elapsedRealtime()) }
        send(Frames.msg("seek").put("ms", ms))
    }

    /** TV position, extrapolated between the ~300 ms status updates. */
    fun positionNow(): Long {
        val st = state.value
        if (!st.playing) return st.pos
        val p = st.pos + (SystemClock.elapsedRealtime() - st.stamp)
        return if (st.dur > 0) p.coerceAtMost(st.dur) else p
    }

    // ---- Discovery (mDNS / NSD) ----

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun startDiscovery(context: Context) {
        if (discovery != null) return
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovery = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                devices.update { list -> list.filter { it.name != info.serviceName } }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(Frames.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null }
    }

    fun stopDiscovery() {
        discovery?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discovery = null
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        val info = resolveQueue.removeFirstOrNull()
        if (info == null) {
            resolving = false
            return
        }
        resolving = true
        @Suppress("DEPRECATION")
        val ok = runCatching {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolveNext()
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val extra = runCatching {
                        serviceInfo.attributes["ips"]?.toString(Charsets.UTF_8)?.split(',')
                    }.getOrNull().orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                    val hosts = (listOfNotNull(serviceInfo.host?.hostAddress) + extra).distinct()
                    if (hosts.isNotEmpty()) {
                        val d = Device(serviceInfo.serviceName, hosts, serviceInfo.port)
                        devices.update { list -> list.filter { it.name != d.name } + d }
                    }
                    resolveNext()
                }
            })
        }.isSuccess
        if (!ok) resolving = false
    }
}
