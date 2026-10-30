package com.karaokemaker.data

import com.karaokemaker.lyrics.LyricLine
import org.json.JSONArray
import org.json.JSONObject

enum class SongStatus { PROCESSING, READY, ERROR }

/** File fields hold names of files inside the song's own directory. */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val originalFile: String,
    val karaokeFile: String? = null,
    val vocalsFile: String? = null,
    val stems: List<String> = emptyList(),
    val mvsepHash: String? = null,
    val lyrics: List<LyricLine> = emptyList(),
    /** Language sung: "ru", "en" or "" (auto). Recognition without it often mistakes Russian for English. */
    val language: String = "",
    val lyricsSource: String = "",
    val status: SongStatus = SongStatus.PROCESSING,
    val progress: String = "",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val displayName: String get() = if (artist.isBlank()) title else "$artist — $title"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("duration", durationMs)
        .put("original", originalFile)
        .put("karaoke", karaokeFile ?: JSONObject.NULL)
        .put("vocals", vocalsFile ?: JSONObject.NULL)
        .put("stems", JSONArray(stems))
        .put("mvsepHash", mvsepHash ?: JSONObject.NULL)
        .put("lyrics", LyricLine.listToJson(lyrics))
        .put("language", language)
        .put("lyricsSource", lyricsSource)
        .put("status", status.name)
        .put("progress", progress)
        .put("error", error ?: JSONObject.NULL)
        .put("createdAt", createdAt)

    companion object {
        /** Cyrillic in the title or artist suggests a Russian song. */
        fun guessLanguage(title: String, artist: String): String =
            if ((title + artist).any { it in '\u0400'..'\u04FF' }) "ru" else ""

        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).ifEmpty { null }

        fun fromJson(o: JSONObject): Song {
            val stems = o.optJSONArray("stems")
            return Song(
                id = o.getString("id"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                durationMs = o.optLong("duration"),
                originalFile = o.getString("original"),
                karaokeFile = o.str("karaoke"),
                vocalsFile = o.str("vocals"),
                stems = if (stems == null) emptyList() else (0 until stems.length()).map { stems.getString(it) },
                mvsepHash = o.str("mvsepHash"),
                lyrics = LyricLine.listFromJson(o.optJSONArray("lyrics")),
                language = o.optString("language"),
                lyricsSource = o.optString("lyricsSource"),
                status = runCatching { SongStatus.valueOf(o.optString("status")) }.getOrDefault(SongStatus.ERROR),
                progress = o.optString("progress"),
                error = o.str("error"),
                createdAt = o.optLong("createdAt"),
            )
        }
    }
}
