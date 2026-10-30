package com.karaokemaker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.karaokemaker.lyrics.Lrc
import com.karaokemaker.lyrics.LyricLine

/** People press a bit after hearing the line start; shift taps back by a typical reaction time. */
private const val REACTION_MS = 250L

/**
 * Tap-to-sync: the song plays from the start and the singer taps the big button whenever the shown line begins.
 * "Проигрыш" marks where singing stops before an instrumental break, so the player can show a countdown there.
 */
@Composable
fun SyncScreen(
    lines: List<String>,
    player: ExoPlayer,
    positionMs: Long,
    minus: Boolean,
    onToggleMinus: (() -> Unit)?,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val starts = remember { mutableStateListOf<Long>() }
    val ends = remember { mutableStateListOf<Long?>() }
    var index by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        player.seekTo(0)
        player.play()
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onCancel)

    fun now() = (player.currentPosition - REACTION_MS).coerceAtLeast(0)

    fun finish() {
        player.pause()
        val timed = starts.indices.map { i -> LyricLine(starts[i], ends.getOrNull(i), lines[i]) }
        // Lines not reached yet stay as plain text, so they can be timed later.
        val rest = lines.drop(starts.size).joinToString("\n")
        onDone(Lrc.toLrc(timed) + rest)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Отменить") }
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                Text("Строка ${minOf(index + 1, lines.size)} из ${lines.size}", fontWeight = FontWeight.Bold)
                Text(Lrc.format(positionMs), style = MaterialTheme.typography.bodySmall, color = AccentSoft)
            }
            Button(onClick = ::finish) { Text("Готово") }
        }
        LinearProgressIndicator(progress = { index.toFloat() / lines.size }, Modifier.fillMaxWidth())
        if (onToggleMinus != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !minus, onClick = { if (minus) onToggleMinus() }, label = { Text("Оригинал") })
                FilterChip(selected = minus, onClick = { if (!minus) onToggleMinus() }, label = { Text("Минус") })
            }
        }

        Text(
            lines.getOrNull(index - 1) ?: "",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 18.sp,
        )
        Text(
            if (index < lines.size) "Нажмите, когда начнётся:" else "Все строки отмечены — нажмите «Готово»",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            lines.getOrNull(index) ?: "",
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(lines.getOrNull(index + 1) ?: "", color = Color.White.copy(alpha = 0.55f), fontSize = 20.sp)
        Spacer(Modifier.weight(1f))

        Button(
            enabled = index < lines.size,
            onClick = {
                val t = now()
                // Taps must go forward in time; ignore accidental double taps.
                if (starts.isEmpty() || t > starts.last() + 300) {
                    starts += t
                    ends += null
                    index++
                }
            },
            modifier = Modifier.fillMaxWidth().height(140.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text(
                if (index < lines.size) "Строка началась!" else "Готово",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = starts.isNotEmpty(),
                onClick = {
                    // Undo the last tap and replay a few seconds before it.
                    val t = starts.removeAt(starts.lastIndex)
                    ends.removeAt(ends.lastIndex)
                    index--
                    player.seekTo((t - 3000).coerceAtLeast(0))
                    player.play()
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, null)
                Text(" Назад")
            }
            OutlinedButton(
                enabled = starts.isNotEmpty() && ends.last() == null,
                onClick = { ends[ends.lastIndex] = now() },
                modifier = Modifier.weight(1f),
            ) { Text("♪ Проигрыш") }
            OutlinedButton(
                onClick = { if (player.isPlaying) player.pause() else player.play() },
                modifier = Modifier.weight(1f),
            ) { Text(if (player.isPlaying) "Пауза" else "Играть") }
        }
        Text(
            "«♪ Проигрыш» — нажмите, когда пение закончилось и началась музыка: на этом месте в караоке будет обратный отсчёт.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}
