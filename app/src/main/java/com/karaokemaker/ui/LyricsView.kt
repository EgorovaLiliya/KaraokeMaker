package com.karaokemaker.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karaokemaker.lyrics.LyricLine
import com.karaokemaker.lyrics.LyricsTiming
import kotlin.math.ceil

/**
 * Scrolling lyrics in the style of YouTube Music / Yandex Music: the current line is big and bright,
 * the view follows the song, and long instrumental breaks show a countdown to the next line.
 */
@Composable
fun LyricsView(
    lines: List<LyricLine>,
    positionMs: Long,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    onLineClick: ((Int) -> Unit)? = null,
) {
    val info = LyricsTiming.at(lines, positionMs)
    val listState = rememberLazyListState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) lastUserScroll = SystemClock.uptimeMillis()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(info.focus, lines.size) {
        if (info.focus >= 0 && SystemClock.uptimeMillis() - lastUserScroll > 2500) {
            listState.animateScrollToItem(info.focus)
        }
    }
    val fontSize = if (large) 46.sp else 27.sp
    BoxWithConstraints(modifier) {
        val topPad = maxHeight * 0.32f
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(nested),
            contentPadding = PaddingValues(top = topPad, bottom = maxHeight * 0.6f, start = 24.dp, end = 24.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                val active = i == info.active
                val color by animateColorAsState(
                    when {
                        active -> Color.White
                        i < info.focus -> Color.White.copy(alpha = 0.32f)
                        else -> Color.White.copy(alpha = 0.55f)
                    },
                    label = "lineColor",
                )
                val scale by animateFloatAsState(if (active) 1f else 0.9f, label = "lineScale")
                Text(
                    text = line.text,
                    color = color,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.2f,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .then(if (onLineClick != null) Modifier.clickable { onLineClick(i) } else Modifier)
                        .padding(vertical = if (large) 14.dp else 10.dp),
                )
            }
        }
        val countdown = info.countdownMs
        if (countdown != null) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topPad),
                contentAlignment = Alignment.Center,
            ) {
                Countdown(countdown, info.gapMs, large)
            }
        }
    }
}

@Composable
private fun Countdown(remainingMs: Long, gapMs: Long, large: Boolean) {
    val seconds = ceil(remainingMs / 1000.0).toInt()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (seconds <= 5) {
            Box(
                Modifier.size(if (large) 120.dp else 84.dp).background(Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$seconds", color = Color.White, fontSize = if (large) 64.sp else 44.sp, fontWeight = FontWeight.Black)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in 3 downTo 1) {
                    Box(
                        Modifier.size(12.dp).background(
                            if (seconds <= d) Accent else Color.White.copy(alpha = 0.25f),
                            CircleShape,
                        )
                    )
                }
            }
        } else {
            Text(
                "♪ Проигрыш · до текста ${formatTime(remainingMs + 999)}",
                color = AccentSoft,
                fontSize = if (large) 30.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { (1f - remainingMs.toFloat() / gapMs).coerceIn(0f, 1f) },
                modifier = Modifier.width(if (large) 420.dp else 220.dp).height(6.dp)
                    .background(Color.Transparent, RoundedCornerShape(3.dp)),
                color = Accent,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
        }
    }
}
