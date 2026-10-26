package com.karaokemaker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.karaokemaker.data.Prefs
import com.karaokemaker.net.Mvsep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, onTvMode: () -> Unit) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(Prefs.mvsepToken) }
    var sepType by remember { mutableStateOf(Prefs.sepType) }
    var sepName by remember { mutableStateOf(Prefs.sepName) }
    var addOpt1 by remember { mutableStateOf(Prefs.addOpt1) }
    var whisperUrl by remember { mutableStateOf(Prefs.whisperUrl) }
    var whisperKey by remember { mutableStateOf(Prefs.whisperKey) }
    var whisperModel by remember { mutableStateOf(Prefs.whisperModel) }
    var language by remember { mutableStateOf(Prefs.language) }
    var preroll by remember { mutableStateOf(Prefs.prerollSec.toString()) }
    var algorithms by remember { mutableStateOf<List<Mvsep.Algorithm>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Mvsep.Algorithm?>(null) }

    fun saveAndBack() {
        Prefs.mvsepToken = token
        Prefs.sepType = sepType
        Prefs.sepName = sepName
        Prefs.addOpt1 = addOpt1
        Prefs.whisperUrl = whisperUrl.ifBlank { Prefs.DEFAULT_WHISPER_URL }
        Prefs.whisperKey = whisperKey
        Prefs.whisperModel = whisperModel.ifBlank { Prefs.DEFAULT_WHISPER_MODEL }
        Prefs.language = language
        Prefs.prerollSec = preroll.toIntOrNull()?.coerceIn(0, 15) ?: 4
        onBack()
    }
    androidx.activity.compose.BackHandler { saveAndBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::saveAndBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Настройки", fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section("Удаление основного вокала — MVSEP")
            Hint("Зарегистрируйтесь на mvsep.com (бесплатно), откройте профиль и скопируйте API token.")
            OutlinedTextField(
                token, { token = it.trim() }, label = { Text("MVSEP API token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Text("Модель: $sepName (№$sepType)" + if (addOpt1.isNotBlank()) ", вариант $addOpt1" else "")
            OutlinedButton(onClick = {
                loadError = null
                scope.launch {
                    try {
                        algorithms = withContext(Dispatchers.IO) { Mvsep.algorithms() }
                    } catch (e: Exception) {
                        loadError = e.message
                    }
                }
            }) { Text("Выбрать другую модель…") }
            loadError?.let { Text(it, color = Color(0xFFFF8A80)) }
            Hint("По умолчанию: MelBand Karaoke — убирает основной вокал и оставляет бэк-вокал и музыку.")

            HorizontalDivider()
            Section("Распознавание текста (Whisper)")
            Hint(
                "Нужно, если готового текста с таймингами нет в базе LRCLIB. Подходит любой OpenAI-совместимый сервис. " +
                    "По умолчанию Groq: бесплатный ключ на console.groq.com → API Keys."
            )
            OutlinedTextField(
                whisperKey, { whisperKey = it.trim() }, label = { Text("API-ключ") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(whisperUrl, { whisperUrl = it.trim() }, label = { Text("Адрес API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(whisperModel, { whisperModel = it.trim() }, label = { Text("Модель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Язык песен")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "Авто", "ru" to "Русский", "en" to "English").forEach { (code, name) ->
                    FilterChip(selected = language == code, onClick = { language = code }, label = { Text(name) })
                }
            }

            HorizontalDivider()
            Section("Плеер")
            OutlinedTextField(
                preroll, { preroll = it.filter(Char::isDigit).take(2) },
                label = { Text("Секунд до строки при нажатии на неё") }, singleLine = true,
            )

            HorizontalDivider()
            Section("Телевизор")
            Hint("На Android TV приложение само открывается как экран караоке. Этот режим — чтобы сделать экраном другой телефон или планшет.")
            OutlinedButton(onClick = onTvMode) {
                Icon(Icons.Default.Tv, null)
                Text("  Режим экрана для ТВ")
            }
        }
    }

    algorithms?.let { list ->
        AlertDialog(
            onDismissRequest = { algorithms = null },
            title = { Text("Модель MVSEP") },
            confirmButton = { TextButton(onClick = { algorithms = null }) { Text("Закрыть") } },
            text = {
                val sorted = list.sortedByDescending { a ->
                    val n = a.name.lowercase()
                    (if ("karaoke" in n) 2 else 0) + (if ("lead" in n || "back" in n) 1 else 0)
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(sorted) { a ->
                        Text(
                            "${a.name} (№${a.id})",
                            Modifier.fillMaxWidth().clickable {
                                algorithms = null
                                val options = a.fields.firstOrNull { it.name == "add_opt1" }?.options.orEmpty()
                                if (options.size > 1) picked = a else {
                                    sepType = a.id; sepName = a.name; addOpt1 = options.firstOrNull()?.key.orEmpty()
                                }
                            }.padding(vertical = 10.dp),
                            fontWeight = if ("karaoke" in a.name.lowercase()) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            },
        )
    }
    picked?.let { a ->
        val field = a.fields.first { it.name == "add_opt1" }
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(field.label) },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("Отмена") } },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(field.options) { o ->
                        Text(
                            o.label,
                            Modifier.fillMaxWidth().clickable {
                                sepType = a.id; sepName = a.name; addOpt1 = o.key; picked = null
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = AccentSoft)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
}
