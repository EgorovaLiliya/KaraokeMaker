package com.karaokemaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karaokemaker.data.Prefs
import com.karaokemaker.data.SongStore
import com.karaokemaker.playback.LocalPlayer
import com.karaokemaker.playback.Playback
import com.karaokemaker.playback.RemotePlayback
import com.karaokemaker.tv.TvLink

@Composable
fun PlayerScreen(songId: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val songs by SongStore.songs.collectAsState()
    val song = songs.find { it.id == songId }
    if (song == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val tv by TvLink.state.collectAsState()
    var useOriginal by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    val fileName = if (useOriginal || song.karaokeFile == null) song.originalFile else song.karaokeFile
    val file = SongStore.file(song, fileName)
    val key = TvLink.keyFor(song, file)
    val remote = tv.connected

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            LocalPlayer.pause()
        }
    }
    LaunchedEffect(file.path) { LocalPlayer.load(context, file, LocalPlayer.position()) }
    // Hand the song over to the TV once connected (and again after switching minus/original).
    LaunchedEffect(remote, key) {
        if (remote && tv.loadedKey != key && tv.sendingKey != key) {
            val pos = LocalPlayer.position()
            LocalPlayer.pause()
            TvLink.sendSong(song, file, pos)
        }
    }
    // Keep the TV's lyrics in sync after editing.
    LaunchedEffect(remote, song.lyrics) {
        if (remote && tv.loadedKey == key) TvLink.sendLyrics(key, song.lyrics)
    }

    val local = remember { LocalPlayer.playback(context) }
    val playback: Playback = if (remote) RemotePlayback else local
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(playback) {
        while (true) {
            withFrameMillis { }
            pos = playback.position()
            dur = playback.duration().takeIf { it > 0 } ?: song.durationMs
            playing = playback.isPlaying()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    song.artist.ifBlank { song.lyricsSource },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { showCast = true }) {
                Icon(
                    if (remote) Icons.Default.CastConnected else Icons.Default.Cast,
                    "Трансляция на ТВ",
                    tint = if (remote) Accent else Color.White,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Редактировать текст") }
        }
        if (remote) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val status = when {
                    tv.upload != null -> "Передаю песню на ТВ…"
                    tv.loadedKey != key -> "Готовлю песню на ТВ…"
                    else -> "Играет на ТВ: ${tv.device?.name}"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = AccentSoft)
                tv.upload?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (song.lyrics.isEmpty()) {
                Text(
                    "Текста пока нет.\nДобавьте его через ✎ — можно вставить текст, и приложение само расставит время.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    color = Color.White.copy(alpha = 0.7f),
                )
            } else {
                LyricsView(
                    lines = song.lyrics,
                    positionMs = pos,
                    modifier = Modifier.fillMaxSize(),
                    onLineClick = { i ->
                        // Jump a few seconds before the line, to breathe in and get ready.
                        playback.seekTo(song.lyrics[i].startMs - Prefs.prerollSec * 1000L)
                        playback.play()
                    },
                )
            }
        }

        Controls(
            pos = pos,
            dur = dur,
            playing = playing,
            onSeek = { playback.seekTo(it) },
            onToggle = { if (playing) playback.pause() else playback.play() },
        )
        if (song.karaokeFile != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                FilterChip(selected = !useOriginal, onClick = { useOriginal = false }, label = { Text("Минус") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = useOriginal, onClick = { useOriginal = true }, label = { Text("Оригинал") })
            }
        }
    }

    if (showCast) CastDialog(onDismiss = { showCast = false })
}

@Composable
fun Controls(pos: Long, dur: Long, playing: Boolean, onSeek: (Long) -> Unit, onToggle: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Slider(
            value = if (dragging) dragValue else fraction,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * dur).toLong())
            },
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(if (dragging) (dragValue * dur).toLong() else pos), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(dur), style = MaterialTheme.typography.bodySmall)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSeek((pos - 10_000).coerceAtLeast(0)) }) {
                Icon(Icons.Default.Replay10, "Назад 10 секунд", Modifier.size(32.dp))
            }
            Spacer(Modifier.width(20.dp))
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Играть/пауза", Modifier.size(36.dp))
            }
            Spacer(Modifier.width(20.dp))
            IconButton(onClick = { onSeek(pos + 10_000) }) {
                Icon(Icons.Default.Forward10, "Вперёд 10 секунд", Modifier.size(32.dp))
            }
        }
    }
}
