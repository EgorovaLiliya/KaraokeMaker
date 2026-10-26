package com.karaokemaker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karaokemaker.tv.TvServer

/** Big-screen karaoke: plays what the phone sends and shows the lyrics. Remote: OK = play/pause, ◀ ▶ = ±10 s. */
@Composable
fun TvReceiverScreen(onExit: (() -> Unit)?) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        TvServer.start(context)
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            TvServer.stop()
        }
    }
    if (onExit != null) BackHandler { onExit() }
    val st by TvServer.state.collectAsState()
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            TvServer.player?.let { p ->
                pos = p.currentPosition
                dur = p.duration.coerceAtLeast(0)
                playing = p.isPlaying
            }
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(StageBrush)
            .focusRequester(focus)
            .onKeyEvent { e ->
                val p = TvServer.player ?: return@onKeyEvent false
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> {
                        if (p.isPlaying) p.pause() else p.play(); true
                    }
                    Key.MediaPlay -> { p.play(); true }
                    Key.MediaPause -> { p.pause(); true }
                    Key.DirectionLeft, Key.MediaRewind -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); true }
                    Key.DirectionRight, Key.MediaFastForward -> { p.seekTo(p.currentPosition + 10_000); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val song = st.song
        if (song == null) {
            Column(
                Modifier.align(Alignment.Center).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("KaraokeMaker", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Accent)
                Text(
                    if (st.client == null) "Откройте песню на телефоне и нажмите значок трансляции"
                    else "Телефон подключён: ${st.client}. Выберите песню.",
                    fontSize = 24.sp, textAlign = TextAlign.Center, color = Color.White,
                )
                Text("Адрес этого экрана: ${st.address}", fontSize = 20.sp, color = AccentSoft)
                st.error?.let { Text(it, color = Color(0xFFFF8A80)) }
                st.receiving?.let { ReceiveBar(it) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(song.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (song.artist.isNotBlank()) {
                        Text("  ·  ${song.artist}", fontSize = 22.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.weight(1f))
                    if (!playing) Text("⏸ пауза", fontSize = 22.sp, color = AccentSoft)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (song.lyrics.isEmpty()) {
                        Text("♪", Modifier.align(Alignment.Center), fontSize = 96.sp, color = Accent)
                    } else {
                        LyricsView(song.lyrics, pos, Modifier.fillMaxSize(), large = true)
                    }
                }
                st.receiving?.let { ReceiveBar(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(pos), color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.width(16.dp))
                    LinearProgressIndicator(
                        progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.weight(1f).height(6.dp),
                        color = Accent,
                        trackColor = Color.White.copy(alpha = 0.15f),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(formatTime(dur), color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun ReceiveBar(progress: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Получаю песню… ${(progress * 100).toInt()}%", color = AccentSoft, fontSize = 18.sp)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(400.dp).padding(top = 8.dp), color = Accent)
    }
}
