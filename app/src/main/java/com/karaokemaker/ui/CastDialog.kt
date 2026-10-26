package com.karaokemaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karaokemaker.data.Prefs
import com.karaokemaker.tv.Frames
import com.karaokemaker.tv.TvLink

@Composable
fun CastDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        TvLink.startDiscovery(context)
        onDispose { TvLink.stopDiscovery() }
    }
    val devices by TvLink.devices.collectAsState()
    val st by TvLink.state.collectAsState()
    var manual by remember { mutableStateOf(Prefs.lastTvAddress) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Трансляция на ТВ") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    st.connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Подключаюсь к ${st.device?.name}…")
                    }
                    st.connected -> {
                        Text("Подключено: ${st.device?.name}", color = AccentSoft)
                        OutlinedButton(onClick = { TvLink.disconnect() }) { Text("Отключиться и играть на телефоне") }
                    }
                }
                st.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Запустите KaraokeMaker на телевизоре (тот же APK). Телефон и ТВ должны быть в одной Wi-Fi сети.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Ищу телевизоры…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                devices.forEach { d ->
                    TextButton(onClick = { TvLink.connect(d) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Tv, null)
                        Spacer(Modifier.width(8.dp))
                        Text(d.name, Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text("Адрес с экрана ТВ, напр. 192.168.1.5:${Frames.PORT}") },
                    singleLine = true,
                )
                OutlinedButton(
                    enabled = manual.isNotBlank(),
                    onClick = {
                        val text = manual.trim()
                        val host = text.substringBefore(':')
                        val port = text.substringAfter(':', "").toIntOrNull() ?: Frames.PORT
                        Prefs.lastTvAddress = text
                        TvLink.connect(TvLink.Device(host, host, port))
                    },
                ) { Text("Подключить по адресу") }
            }
        },
    )
}
