package com.karaokemaker.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

sealed interface Screen {
    data object Library : Screen
    data class Player(val id: String) : Screen
    data class Edit(val id: String) : Screen
    data object Settings : Screen
    data object TvReceiver : Screen
}

@Composable
fun PhoneApp(sharedUri: MutableState<Uri?>) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Library)) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize().background(StageBrush).safeDrawingPadding()) {
        when (val screen = stack.last()) {
            Screen.Library -> LibraryScreen(
                sharedUri = sharedUri,
                onOpen = { push(Screen.Player(it)) },
                onEdit = { push(Screen.Edit(it)) },
                onSettings = { push(Screen.Settings) },
            )
            is Screen.Player -> PlayerScreen(screen.id, onBack = ::pop, onEdit = { push(Screen.Edit(screen.id)) })
            is Screen.Edit -> EditScreen(screen.id, onBack = ::pop)
            Screen.Settings -> SettingsScreen(onBack = ::pop, onTvMode = { push(Screen.TvReceiver) })
            Screen.TvReceiver -> TvReceiverScreen(onExit = ::pop)
        }
    }
}
