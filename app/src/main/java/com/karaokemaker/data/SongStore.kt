package com.karaokemaker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

/** Songs live in filesDir/songs/<id>/ with a song.json next to the audio files. */
object SongStore {
    private lateinit var root: File
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    fun init(context: Context) {
        root = File(context.filesDir, "songs").apply { mkdirs() }
        val loaded = root.listFiles().orEmpty().mapNotNull { dir ->
            runCatching { Song.fromJson(JSONObject(File(dir, "song.json").readText())) }.getOrNull()
        }.map {
            // The process died while the song was being processed.
            if (it.status == SongStatus.PROCESSING) {
                it.copy(status = SongStatus.ERROR, progress = "", error = "Обработка прервана. Нажмите «Повторить».")
            } else it
        }
        _songs.value = loaded.sortedByDescending { it.createdAt }
    }

    fun dir(id: String) = File(root, id)

    fun file(song: Song, name: String) = File(dir(song.id), name)

    fun get(id: String): Song? = _songs.value.find { it.id == id }

    @Synchronized
    fun save(song: Song) {
        val d = dir(song.id).apply { mkdirs() }
        val tmp = File(d, "song.json.tmp")
        tmp.writeText(song.toJson().toString())
        tmp.renameTo(File(d, "song.json"))
        _songs.update { list -> (list.filter { it.id != song.id } + song).sortedByDescending { it.createdAt } }
    }

    @Synchronized
    fun update(id: String, change: (Song) -> Song): Song? {
        val current = get(id) ?: return null
        return change(current).also { save(it) }
    }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
        _songs.update { list -> list.filter { it.id != id } }
    }
}
