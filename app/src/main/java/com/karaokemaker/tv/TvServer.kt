package com.karaokemaker.tv

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.karaokemaker.lyrics.LyricLine
import com.karaokemaker.playback.newPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/** TV side: receives songs from the phone, plays them and reports the position back. */
object TvServer {
    data class TvSong(val key: String, val title: String, val artist: String, val lyrics: List<LyricLine>)

    data class State(
        val address: String = "",
        val client: String? = null,
        val song: TvSong? = null,
        val receiving: Float? = null,
        val error: String? = null,
    )

    val state = MutableStateFlow(State())

    var player: ExoPlayer? = null
        private set

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private lateinit var cacheDir: File

    /** Must be called on the main thread. */
    fun start(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        cacheDir = File(app.cacheDir, "tv").apply { mkdirs() }
        player = newPlayer(app)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch {
            try {
                val ss = try {
                    ServerSocket(Frames.PORT)
                } catch (e: IOException) {
                    ServerSocket(0)
                }
                server = ss
                state.update { it.copy(address = "${Frames.localIp() ?: "?"}:${ss.localPort}", error = null) }
                register(app, ss.localPort)
                while (isActive) {
                    val s = ss.accept()
                    runCatching { client?.close() }
                    client = s
                    launch { handle(s) }
                }
            } catch (e: Exception) {
                if (scope != null) state.update { it.copy(error = e.message) }
            }
        }
    }

    fun stop() {
        val sc = scope ?: return
        scope = null
        registration?.let { runCatching { nsd?.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        runCatching { client?.close() }
        sc.cancel()
        player?.release()
        player = null
        state.value = State()
    }

    private fun register(context: Context, port: Int) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = "KaraokeMaker TV (${Build.MODEL})"
            serviceType = Frames.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun cacheFile(key: String) = File(cacheDir, key.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private suspend fun handle(s: Socket) = coroutineScope {
        s.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        state.update { it.copy(client = s.inetAddress.hostAddress) }
        val ticker = launch {
            try {
                while (isActive) {
                    val snapshot = main {
                        player?.let { p ->
                            Frames.msg("state")
                                .put("key", state.value.song?.key ?: "")
                                .put("pos", p.currentPosition)
                                .put("playing", p.isPlaying)
                                .put("dur", p.duration.let { if (it == C.TIME_UNSET) 0L else it })
                        }
                    }
                    if (snapshot != null) Frames.write(out, snapshot)
                    delay(300)
                }
            } catch (e: IOException) {
                runCatching { s.close() }
            }
        }
        var pending: JSONObject? = null
        try {
            while (true) {
                val m = Frames.read(input)
                when (m.optString("type")) {
                    "hello" -> state.update { it.copy(client = m.optString("name")) }
                    "load" -> {
                        val key = m.getString("key")
                        val f = cacheFile(key)
                        if (f.exists() && f.length() == m.optLong("size")) {
                            apply(m, f)
                            Frames.write(out, Frames.msg("loaded").put("key", key))
                        } else {
                            pending = m
                            Frames.write(out, Frames.msg("need_file").put("key", key))
                        }
                    }
                    "file" -> {
                        val size = m.getLong("size")
                        val meta = pending
                        val tmp = File(cacheDir, "incoming.part")
                        receive(input, tmp, size)
                        if (meta != null) {
                            val key = meta.getString("key")
                            val f = cacheFile(key)
                            f.delete()
                            tmp.renameTo(f)
                            prune()
                            apply(meta, f)
                            pending = null
                            Frames.write(out, Frames.msg("loaded").put("key", key))
                        }
                    }
                    "lyrics" -> state.update { st ->
                        val song = st.song
                        if (song != null && song.key == m.optString("key")) {
                            st.copy(song = song.copy(lyrics = LyricLine.listFromJson(m.optJSONArray("lyrics"))))
                        } else st
                    }
                    "play" -> main { player?.play() }
                    "pause" -> main { player?.pause() }
                    "seek" -> main { player?.seekTo(m.optLong("ms")) }
                }
            }
        } catch (e: Exception) {
            // Phone disconnected.
        } finally {
            ticker.cancel()
            runCatching { s.close() }
            state.update { if (client === s) it.copy(client = null, receiving = null) else it }
        }
    }

    private fun receive(input: DataInputStream, target: File, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        var lastReport = 0L
        target.outputStream().use { out ->
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("Передача прервана")
                out.write(buf, 0, n)
                left -= n
                val done = size - left
                if (done - lastReport > 256 * 1024 || left == 0L) {
                    lastReport = done
                    state.update { it.copy(receiving = done.toFloat() / size) }
                }
            }
        }
        state.update { it.copy(receiving = null) }
    }

    private suspend fun apply(meta: JSONObject, file: File) {
        val song = TvSong(
            key = meta.getString("key"),
            title = meta.optString("title"),
            artist = meta.optString("artist"),
            lyrics = LyricLine.listFromJson(meta.optJSONArray("lyrics")),
        )
        main {
            player?.let { p ->
                p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                p.prepare()
                p.seekTo(meta.optLong("start"))
            }
        }
        state.update { it.copy(song = song) }
    }

    /** Keeps the few most recent songs so switching back is instant. */
    private fun prune() {
        cacheDir.listFiles().orEmpty()
            .filter { !it.name.endsWith(".part") }
            .sortedByDescending { it.lastModified() }
            .drop(4)
            .forEach { it.delete() }
    }
}
