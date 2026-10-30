package com.karaokemaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karaokemaker.data.Processor
import com.karaokemaker.data.SongStore
import com.karaokemaker.lyrics.Lrc
import com.karaokemaker.playback.LocalPlayer
import kotlinx.coroutines.launch

/** Puts the current time tag on the line under the cursor and moves the cursor to the next line. */
private fun stamp(v: TextFieldValue, ms: Long): TextFieldValue {
    val text = v.text
    val cur = v.selection.start.coerceIn(0, text.length)
    val lineStart = if (cur == 0) 0 else text.lastIndexOf('\n', cur - 1) + 1
    val lineEnd = text.indexOf('\n', cur).let { if (it < 0) text.length else it }
    val newLine = "[${Lrc.format(ms)}]" + Lrc.stripLineTags(text.substring(lineStart, lineEnd))
    val newText = text.substring(0, lineStart) + newLine + text.substring(lineEnd)
    val next = (lineStart + newLine.length + 1).coerceAtMost(newText.length)
    return TextFieldValue(newText, TextRange(next))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditScreen(songId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { SongStore.get(songId) }
    if (initial == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initial.title) }
    var artist by remember { mutableStateOf(initial.artist) }
    var text by remember { mutableStateOf(TextFieldValue(Lrc.toLrc(initial.lyrics))) }
    var karaoke by remember { mutableStateOf(initial.karaokeFile) }
    var vocals by remember { mutableStateOf(initial.vocalsFile) }
    var language by remember { mutableStateOf(initial.language) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    val player = remember { LocalPlayer.get(context) }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    // The original by default: the lead vocal makes it easy to hear where each line starts.
    var playMinus by remember { mutableStateOf(false) }
    val minusFile = karaoke?.takeIf { it != initial.originalFile }
    val playFile = SongStore.file(initial, if (playMinus && minusFile != null) minusFile else initial.originalFile)
    LaunchedEffect(playFile.path) { LocalPlayer.load(context, playFile, LocalPlayer.position()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            pos = player.currentPosition
            playing = player.isPlaying
        }
    }
    DisposableEffect(Unit) { onDispose { player.pause() } }

    fun run(label: String, block: suspend () -> Unit) {
        busy = label
        message = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                busy = null
            }
        }
    }

    fun save() {
        val lines = Lrc.parse(text.text)
        SongStore.update(songId) {
            it.copy(
                title = title.trim(),
                artist = artist.trim(),
                lyrics = lines,
                lyricsSource = if (lines == it.lyrics) it.lyricsSource else "Отредактировано вручную",
                karaokeFile = karaoke,
                vocalsFile = vocals,
            )
        }
        onBack()
    }

    if (syncing) {
        SyncScreen(
            lines = Lrc.plainLines(text.text),
            player = player,
            positionMs = pos,
            minus = playMinus,
            onToggleMinus = if (minusFile != null) ({ playMinus = !playMinus }) else null,
            onDone = { result ->
                text = TextFieldValue(result)
                syncing = false
                message = "Время расставлено. Проверьте в плеере и нажмите «Сохранить»."
            },
            onCancel = { syncing = false },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Текст и дорожки", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Button(onClick = ::save, modifier = Modifier.padding(end = 8.dp)) { Text("Сохранить") }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(artist, { artist = it }, label = { Text("Исполнитель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            if (initial.stems.isNotEmpty()) {
                StemPicker("Минус (играет в караоке)", karaoke, initial.stems + initial.originalFile) { karaoke = it }
                StemPicker("Основной вокал (для распознавания)", vocals, initial.stems) { vocals = it }
            }

            Text("Язык песни (для распознавания)", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            LanguageChips(language) {
                language = it
                Processor.setLanguage(songId, it)
            }

            Text("Найти или распознать текст", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(enabled = busy == null, onClick = {
                    run("Ищу в LRCLIB…") {
                        val r = Processor.findLrclib(artist.trim(), title.trim(), initial.durationMs)
                        when {
                            r?.synced != null -> text = TextFieldValue(r.synced)
                            r?.plain != null -> {
                                text = TextFieldValue(r.plain)
                                message = "Найден текст без таймингов. Нажмите «Выровнять по вокалу» или расставьте метки ⏱."
                            }
                            else -> message = "В LRCLIB ничего не нашлось. Проверьте название или вставьте текст сами."
                        }
                    }
                }) { Text("LRCLIB") }
                FilledTonalButton(enabled = busy == null, onClick = {
                    run("Распознаю вокал…") {
                        text = TextFieldValue(Lrc.toLrc(Processor.recognise(songId, null, refresh = false)))
                    }
                }) { Text("Распознать по вокалу") }
                FilledTonalButton(enabled = busy == null && text.text.isNotBlank(), onClick = {
                    run("Выравниваю текст по вокалу…") {
                        val plain = Lrc.plainLines(text.text)
                        text = TextFieldValue(Lrc.toLrc(Processor.recognise(songId, plain, refresh = false)))
                    }
                }) { Text("Выровнять по вокалу") }
            }
            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            message?.let { Text(it, color = AccentSoft, style = MaterialTheme.typography.bodySmall) }

            Text("Расставить время вручную", fontWeight = FontWeight.SemiBold)
            Text(
                "Вставьте текст ниже (каждая строка песни — с новой строки), затем нажмите кнопку: " +
                    "песня заиграет с начала, а вы нажимаете большую кнопку в момент, когда начинается каждая строка.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Button(
                enabled = Lrc.plainLines(text.text).isNotEmpty(),
                onClick = { syncing = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Timer, null)
                Spacer(Modifier.width(8.dp))
                Text("Расставить время по ходу песни")
            }

            Text("Сдвинуть весь текст", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (delta in listOf(-1000L, -250L, 250L, 1000L)) {
                    OutlinedButton(onClick = {
                        text = TextFieldValue(Lrc.toLrc(Lrc.shift(Lrc.parse(text.text), delta)))
                    }) { Text((if (delta > 0) "+" else "−") + "%.2f".format(kotlin.math.abs(delta) / 1000.0).trimEnd('0').trimEnd('.', ',') + "с") }
                }
            }

            Text(
                "Для точной правки: формат строки [мм:сс.сс]текст, пустая метка — конец строки перед проигрышем. " +
                    "Кнопка «Метка» внизу ставит текущее время на строку, где стоит курсор.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White),
                label = { Text("Текст (LRC)") },
            )
            Spacer(Modifier.padding(8.dp))
        }
        // Mini player for tap-to-sync.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { player.seekTo((player.currentPosition - 5000).coerceAtLeast(0)) }) {
                Icon(Icons.Default.Replay5, "Назад 5 секунд")
            }
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Играть/пауза")
            }
            Text(Lrc.format(pos), fontFamily = FontFamily.Monospace)
            Box(Modifier.weight(1f))
            if (minusFile != null) {
                TextButton(onClick = { playMinus = !playMinus }) { Text(if (playMinus) "Минус" else "Оригинал") }
            }
            Button(onClick = { text = stamp(text, player.currentPosition) }) {
                Icon(Icons.Default.Timer, null)
                Spacer(Modifier.width(6.dp))
                Text("Метка")
            }
        }
    }
}

@Composable
private fun StemPicker(label: String, value: String?, options: List<String>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value?.removePrefix("stem_")?.substringAfter('_') ?: "не выбрано", maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(if (o.startsWith("stem_")) o.removePrefix("stem_").substringAfter('_') else "Оригинал песни") },
                        onClick = { open = false; onPick(o) },
                    )
                }
            }
        }
    }
}
