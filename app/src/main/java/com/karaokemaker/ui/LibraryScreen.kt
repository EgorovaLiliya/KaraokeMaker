package com.karaokemaker.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karaokemaker.data.Prefs
import com.karaokemaker.data.Processor
import com.karaokemaker.data.Song
import com.karaokemaker.data.SongPackage
import com.karaokemaker.data.SongStatus
import com.karaokemaker.data.SongStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ImportDraft(val uri: Uri, val fileName: String, val title: String, val artist: String)

private fun readDraft(context: Context, uri: Uri): ImportDraft {
    var name = "song.mp3"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
    }
    val base = name.substringBeforeLast('.')
    var title = ""
    var artist = ""
    runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(context, uri)
        title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
        artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
        r.release()
    }
    if (title.isBlank()) {
        val dash = Regex("\\s[-–—]\\s").find(base)
        if (dash != null) {
            if (artist.isBlank()) artist = base.substring(0, dash.range.first).trim()
            title = base.substring(dash.range.last + 1).trim()
        } else {
            title = base.replace('_', ' ').trim()
        }
    }
    return ImportDraft(uri, name, title, artist)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sharedUri: MutableState<Uri?>,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val songs by SongStore.songs.collectAsState()
    var draft by remember { mutableStateOf<ImportDraft?>(null) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pickedUri = uri
    }
    val incoming = sharedUri.value
    LaunchedEffect(incoming) {
        if (incoming != null) {
            pickedUri = incoming
            sharedUri.value = null
        }
    }
    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        // Clearing pickedUri restarts this effect, so it is done only after the work: earlier it cancelled the import.
        try {
            if (withContext(Dispatchers.IO) { SongPackage.isPackage(context, uri) }) {
                val result = withContext(Dispatchers.IO) { runCatching { SongPackage.import(context, uri) } }
                val text = result.fold(
                    onSuccess = { "Песня «${it.title}» добавлена" },
                    onFailure = { "Не удалось открыть песню: ${it.message}" },
                )
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            } else {
                draft = withContext(Dispatchers.IO) { readDraft(context, uri) }
            }
        } finally {
            pickedUri = null
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("KaraokeMaker", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { picker.launch("*/*") }) {
                        Icon(Icons.Default.Download, "Открыть полученную песню")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch("audio/*") },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Добавить песню") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (Prefs.mvsepToken.isBlank()) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onSettings),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        "Чтобы убирать основной вокал, укажите бесплатный API-токен MVSEP в настройках →",
                        Modifier.padding(16.dp),
                    )
                }
            }
            if (songs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Добавьте mp3 — приложение уберёт основной вокал, оставит бэки и найдёт текст песни.\n\n" +
                            "Получили готовую песню от друга? Откройте файл .karaoke.zip или нажмите ⬇ вверху.",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(songs, key = { it.id }) { song ->
                        SongCard(song, onOpen = { onOpen(song.id) }, onEdit = { onEdit(song.id) })
                    }
                }
            }
        }
    }

    draft?.let { d ->
        var title by remember(d) { mutableStateOf(d.title) }
        var artist by remember(d) { mutableStateOf(d.artist) }
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("Новая песня") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Название и исполнитель нужны, чтобы найти синхронизированный текст.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(artist, { artist = it }, label = { Text("Исполнитель") }, singleLine = true)
                    OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        Processor.import(context, d.uri, d.fileName, title.trim(), artist.trim())
                        draft = null
                    },
                ) { Text("Сделать караоке") }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SongCard(song: Song, onOpen: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val playable = song.status == SongStatus.READY || song.durationMs > 0
    Card(
        Modifier.fillMaxWidth().clickable(enabled = playable, onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            contentColor = Color.White,
        ),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song.artist.isNotBlank()) {
                    Text(song.artist, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (song.status) {
                    SongStatus.PROCESSING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(song.progress.ifBlank { "Обработка…" }, style = MaterialTheme.typography.bodySmall, color = AccentSoft)
                    }
                    SongStatus.ERROR -> Text(
                        song.error ?: "Ошибка",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF8A80),
                    )
                    SongStatus.READY -> {
                        Text(
                            (if (song.karaokeFile != null) "Минус готов" else "Без минуса") +
                                if (song.lyrics.isNotEmpty()) " · ${song.lyrics.size} строк текста" else " · нет текста",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        if (song.lyrics.isEmpty() && song.lyricsSource.isNotBlank()) {
                            Text(song.lyricsSource, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFCC80))
                        }
                    }
                }
            }
            if (song.status == SongStatus.ERROR) {
                IconButton(onClick = { Processor.retry(song.id) }) { Icon(Icons.Default.Refresh, "Повторить") }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Текст и дорожки") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; onEdit() },
                    )
                    if (song.status == SongStatus.READY) {
                        DropdownMenuItem(
                            text = { Text("Поделиться") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                menu = false
                                scope.launch {
                                    val intent = withContext(Dispatchers.IO) {
                                        runCatching { SongPackage.shareIntent(context, song) }
                                    }
                                    intent.onSuccess { context.startActivity(it) }.onFailure {
                                        Toast.makeText(context, "Не удалось подготовить файл: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${song.title}»?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; SongStore.delete(song.id) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
