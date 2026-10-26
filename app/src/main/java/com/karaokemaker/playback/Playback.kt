package com.karaokemaker.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.karaokemaker.tv.TvLink
import java.io.File

/** What the player UI controls: the phone's own player or the TV. */
interface Playback {
    fun position(): Long
    fun duration(): Long
    fun isPlaying(): Boolean
    fun play()
    fun pause()
    fun seekTo(ms: Long)
}

fun newPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
    setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
        true,
    )
}

class ExoPlayback(val player: ExoPlayer) : Playback {
    override fun position() = player.currentPosition
    override fun duration() = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
    override fun isPlaying() = player.isPlaying
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))
}

/** The phone's single player instance, shared by the player and the lyrics editor. */
object LocalPlayer {
    private var player: ExoPlayer? = null
    private var currentPath: String? = null

    fun get(context: Context): ExoPlayer = player ?: newPlayer(context).also { player = it }

    fun playback(context: Context) = ExoPlayback(get(context))

    /** Loads the file unless it is already loaded; keeps [startMs] position. */
    fun load(context: Context, file: File, startMs: Long) {
        val p = get(context)
        if (currentPath == file.path) return
        val wasPlaying = p.isPlaying
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.seekTo(startMs)
        if (wasPlaying) p.play()
        currentPath = file.path
    }

    fun pause() {
        player?.pause()
    }

    fun position(): Long = player?.currentPosition ?: 0L
}

object RemotePlayback : Playback {
    override fun position() = TvLink.positionNow()
    override fun duration() = TvLink.state.value.dur
    override fun isPlaying() = TvLink.state.value.playing
    override fun play() = TvLink.play()
    override fun pause() = TvLink.pause()
    override fun seekTo(ms: Long) = TvLink.seek(ms.coerceAtLeast(0))
}
